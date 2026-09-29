# 落叶（LuoYe）个人 AI 智能体 · 技术设计文档

## 文档信息

| 字段 | 内容 |
| ---- | ---- |
| 文档名称 | 落叶（LuoYe）个人 AI 智能体技术设计文档 |
| 文档版本 | v1.0（初稿，待评审确认） |
| 依据 | 需求文档 v0.2 |
| 创建日期 | 2026-09-29 |
| 技术栈 | Spring Boot + LangChain4j + Vue 3 + PostgreSQL + pgvector |
| 关联仓库 | `back-end-LangChan4j` / `front-end` |

> 本文档为**技术设计（Design）层面**描述，不含具体代码。设计定稿并经确认后，再按模块拆分开发。
> 依赖需求编号沿用 v0.2：功能 F1.x–F7.x、非功能 N1–N18、里程碑 M1–M5。

---

## 1. 总体架构

### 1.1 架构图

```
┌────────────────────────── 前端 front-end（Vue 3）───────────────────────┐
│ 对话页 / 会话列表 / 记忆管理 / 知识库&笔记 / 设置 / 数据导出             │
│ [Pinia 状态] [Vue Router] [请求封装] [SSE 客户端]                       │
└───────────────▲──────────────────────────┬────────────────────────────┘
                │ HTTPS  REST + SSE (JSON)  │
┌───────────────┴──────────────────────────┴────────────────────────────┐
│                      后端 back-end（Spring Boot）                      │
│  Web 层：REST Controller / SSE Controller → JWT 认证拦截器             │
│  应用服务层（编排事务与横切）：                                        │
│  ┌───────────┐ ┌───────────┐ ┌────────────┐ ┌─────────────┐           │
│  │对话服务     │ │记忆服务    │ │知识库服务    │ │工具服务       │         │
│  │ChatSvc    │ │MemorySvc  │ │KbSvc       │ │ToolRunner   │         │
│  └─────┬─────┘ └─────┬─────┘ └─────┬──────┘ └──────┬──────┘         │
│        └─────────────┴──────┬──────┴───────────────┘                 │
│              LangChain4j 编排（AiServices / ChatMemory / Retriever）   │
│   ┌─────────────────┐  ┌───────────────┐  ┌─────────────────────┐     │
│   │ ChatLanguageModel│  │EmbeddingModel │  │ EmbeddingStore      │     │
│   │ （对话，可切换）  │  │（嵌入，可切换）│  │ Memory/Kb pgvector  │     │
│   └────────┬────────┘  └───────┬───────┘  └─────────┬───────────┘     │
│            │ 供应商适配（OpenAI 兼容 / Ollama）       │                  │
└────────────┼──────────────────┼────────────────────┼──────────────────┘
             ▼                  ▼                    ▼
    ┌──────────────── PostgreSQL + pgvector（关系表 / 向量列 / FTS）──────┐
    │ users, sessions, messages, long_term_memory, kb_documents,       │
    │ kb_chunks, notes, configs, todos, tool_calls, memory_correction   │
    └───────────────────────────────────────────────────────────────────┘
```

### 1.2 模块职责

| 模块 | 职责 | 对应功能 |
| ---- | ---- | -------- |
| 对话服务 | 多轮编排、SSE 流式、打断、重生成 | F1.1–F1.4 |
| 记忆服务 | 长期记忆抽取/召回/衰减/管理 | F2.1–F2.5 |
| 知识库服务 | 文档/笔记入库、分块、嵌入、RAG 检索 | F3.1–F3.4 |
| 工具服务 | @Tool 注册、工具调用日志、失败降级 | F4.x / F6.x |
| 配置服务 | 模型/供应商/人格/隐私配置、多模型切换 | F7.x |
| LLM 抽象层 | 屏蔽供应商差异，模型可插拔 | N13/N14 |
| 认证 | 单用户 JWT 最小方案 | N5/N8 |

### 1.3 关键设计决策

- 采用 **PostgreSQL + pgvector** 统一承载关系数据与向量检索（记忆 + 知识库全部走向量），并用 PostgreSQL **全文检索（FTS）** 兜底关键词类搜索（F3.3、F1.4），避免引入第二套搜索引擎。
- 短期记忆落库到 `messages` 表，通过自定义 `ChatMemoryStore` 与 LangChain4j `MessageWindowChatMemory` 对接，保证 N11（重启不丢）。
- 记忆与知识库的嵌入、抽取**非每轮触发**（N18），显式记忆即时、隐式记忆批量低频、嵌入增量。

---

## 2. 数据库设计（PostgreSQL + pgvector）

### 2.0 通用约定

- 扩展：`CREATE EXTENSION IF NOT EXISTS vector;`、`CREATE EXTENSION IF NOT EXISTS pg_trgm;`
- 主键统一 `id uuid primary key default gen_random_uuid()`。
- 基础列：`created_at timestamptz not null default now()`、`updated_at timestamptz`、`deleted_at timestamptz null`（软删除，满足数据清除）。
- 向量列类型 `vector(N)`，N 与嵌入维度一致（示例按 1536 记，实际取供应商维度）。

### 2.1 users（单用户，为多用户预留）

```sql
id uuid pk
username varchar(64) unique not null
display_name varchar(128)
password_hash varchar(256)          -- bcrypt，单用户最小方案（见 §9）
created_at / updated_at / deleted_at
```

### 2.2 sessions 会话

```sql
id uuid pk
user_id uuid not null references users(id)
title varchar(200)
status varchar(20) default 'active'  -- active | archived
created_at / updated_at / deleted_at
-- 索引：user_id, created_at
```

### 2.3 messages 消息（短期记忆载体）

```sql
id uuid pk
session_id uuid not null references sessions(id)
seq int not null                    -- 会话内顺序
role varchar(16) not null           -- USER | ASSISTANT | TOOL | SYSTEM | MEMORY
content text
tool_calls jsonb                    -- 该轮工具调用（审计/回显）
citations jsonb                     -- 引用来源（§3.8 格式）
tokens_used int
status varchar(16) default 'completed' -- completed | interrupped | generated
run_id uuid                         -- 流式 run 标识（用于打断）
created_at
-- 索引：session_id, seq; 唯一 (session_id, seq)
```

> 短期记忆 = 会话内最近 `maxMessages` 条 `messages`。LangChain4j `MessageWindowChatMemory` 的读写经由自定义 `ChatMemoryStore` 落在本表。

### 2.4 long_term_memory 长期记忆

```sql
id uuid pk
user_id uuid not null references users(id)
content text not null                -- 记忆事实文本
memory_type varchar(32) default 'fact' -- fact | preference | event | profile
source varchar(16) not null          -- explicit | inferred（F2.4）
confidence varchar(8) not null       -- high | medium | low（inferred 必填）
status varchar(16) default 'active'  -- pending | active | stale | deleted
embedding vector(1536)
importance_weight float default 0.5  -- 衰减因子（§5.3）
access_count int default 0           -- 访问频率
last_access_at timestamptz
never_decay boolean default false    -- 用户高亮/显式声明，不轻易回收
correction_count int default 0       -- 被纠正次数（抑制误抽取）
origin_session_id uuid null          -- 来源会话
origin_message_id uuid null          -- 来源消息
next_review_at timestamptz null      -- 低频复审时间
created_at / updated_at / deleted_at
-- 索引：user_id+status; embedding HNSW/ivfflat; status+last_access_at
```

### 2.5 kb_documents 知识库文档

```sql
id uuid pk
user_id uuid not null references users(id)
title varchar(255) not null
source_type varchar(16) not null     -- upload | note（方案 A，笔记为一种来源）
source_ref uuid null                 -- source_type='note' 时指向 notes.id
file_name varchar(255)
mime varchar(100)
doc_meta jsonb                       -- 解析元信息（页数/字数等）
status varchar(16) default 'indexing'-- indexing | ready | failed
chunk_count int default 0
created_at / updated_at / deleted_at
```

### 2.6 kb_chunks 文本块

```sql
id uuid pk
document_id uuid not null references kb_documents(id)
user_id uuid not null
seq int not null                     -- 文档内分块序号
content text not null
embedding vector(1536)
token_count int
metadata jsonb                       -- {page, heading, paragraph_id, source_type}
created_at
-- 索引：document_id,seq; 向量索引；user_id（检索过滤）
```

### 2.7 notes 笔记（统一存储方案 A）

```sql
id uuid pk
user_id uuid not null
title varchar(255) not null
content text                         -- Markdown 原文
status varchar(16) default 'active'
kb_document_id uuid null             -- 关联的 kb_documents（source_type='note'）
created_at / updated_at / deleted_at
```

> 方案 A 落地：笔记保存/更新 → 同步 upsert 一条 `kb_documents(source_type='note')` 及对应 `kb_chunks`（增量重分块+重嵌入）；检索时即与上传文档统一命中。

### 2.8 configs 配置

```sql
id uuid pk
user_id uuid not null
config_key varchar(64) not null      -- profile|persona|memory|persona_density|model|provider|privacy|token_budget...
config_value jsonb not null
updated_at
-- 唯一 (user_id, config_key)
```

> 敏感凭据（API Key）不存本表，走环境变量或独立加密列 `secrets` 隔离（N6）。

### 2.9 todos 待办/日程、tool_calls 日志、memory_correction 纠偏

```sql
todos: id, user_id, title, description, due_at, remind_at, status(open|done), created_at, updated_at, deleted_at

tool_calls: id, user_id, session_id, tool_name, input jsonb, output jsonb,
            status(success|failed|pending_confirm), latency_ms, called_at, confirm_token uuid null

memory_correction: id, user_id, memory_id, corrected_value, source_message_id, created_at
```

### 2.10 ER 关系（文字简图）

```
users 1 ── N sessions ── N messages
  │        │
  │ N ── sessions 之外：长期记忆（多来源会话）
  │
  ├── N long_term_memory (source: session/message)
  ├── N kb_documents 1 ── N kb_chunks
  ├── N notes N:1 kb_documents(source_type=note)
  ├── N configs
  ├── N todos
  ├── N tool_calls (session)
  └── ─── memory_correction ─ memory_id → long_term_memory
```

## 3. 接口契约（REST + SSE）

### 3.1 通用约定

- 基础路径 `/api/v1`；认证头 `Authorization: Bearer <jwt>`（§9）。
- 错误统一返回 `{ "code": "...", "message": "...", "traceId": "..." }`。
- SSE 仅用于"发送消息"流式通道；其余 CRUD 用普通 REST，`Content-Type: application/json; charset=utf-8`。

### 3.2 SSE 发送消息（流式）

```
POST /api/v1/chat/sessions/{sessionId}/messages
Authorization: Bearer <jwt>

{ "content": "明天下午3点提醒我开会",
  "stream": true,
  "regenerateOf": null }        // 仅重生成时携带被重生成消息 id
```

响应 `text/event-stream; charset=utf-8`：

```
event: delta
data: {"type":"delta","content":"好的，"}

event: tool
data: {"type":"tool","tool":"todos_create","status":"running","message":"正在安排日程…"}

event: citation
data: {"type":"citation","citations":[{"kind":"memory","source":"profile","snippet":"…","sentence_idx":0}]}

event: done
data: {"type":"done","messageId":"m_123","tokensUsed":286}

event: error
data: {"type":"error","code":"provider_unavailable","message":"模型暂不可用"}
```

打断/终止当前流式 run：

```
DELETE /api/v1/chat/sessions/{sessionId}/streams/{runId}
→ 200 { "runId":"...", "aborted": true }
```

> 实现：Spring `SseEmitter` + LangChain4j `StreamingChatLanguageModel`；首 token 目标 ≤2s（N1）。`delta/tool/citation/done` 分帧，前端把 `tool` 渲染为进度提示而非正文。打断时后端终止流式并丢弃未落库的临时上下文（与 F1.2/F1.3 联动）。

### 3.3 会话管理

```
GET    /api/v1/sessions                         → 列表
POST   /api/v1/sessions   {"title":"..."}       → 新建
GET    /api/v1/sessions/{id}
PATCH  /api/v1/sessions/{id}   {"title":"..."}
DELETE /api/v1/sessions/{id}                    → 软删
GET    /api/v1/sessions?q=<关键词>               → FTS 搜索
```

### 3.4 记忆管理（F2.3/F2.4 透明可控）

```
GET    /api/v1/memory?type=fact&status=active&q=生日   → 列表/搜索
GET    /api/v1/memory/{id}
PATCH  /api/v1/memory/{id}   {"content":"..."}          → 编辑
DELETE /api/v1/memory/{id}
POST   /api/v1/memory/confirm  {"ids":[...]}            → 确认 inferred 低置信项
DELETE /api/v1/memory          {"scope":"all"}          → 一键清空
```

### 3.5 知识库与笔记

```
上传：POST /api/v1/kb/documents   (multipart/form-data: file)
GET    /api/v1/kb/documents                        → 列表（含 source_type）
GET    /api/v1/kb/documents/{id}
DELETE /api/v1/kb/documents/{id}
GET    /api/v1/kb/search?q=<词>                    → 文档/块检索
笔记：GET/POST/PATCH/DELETE /api/v1/notes          保存即入库重索引
```

### 3.6 待办/日程、设置、认证

```
CRUD  /api/v1/todos                （F4.1–F4.3）
GET/PUT /api/v1/configs            （人格/陪伴浓度/记忆开关/联网开关/模型/token预算）
POST/PUT /api/v1/configs/model     → 触发模型切换（§7）
POST /api/v1/auth/login  {"username":"...","password":"..."}
      → { "token":"<jwt>", "expiresIn":3600 }
GET  /api/v1/auth/me
```

### 3.7 引用来源格式（citations）

需求 F3.2/F6.2 要求标注来源并区分"个人知识"与"网络结论"。消息正文以内联 `[1]` 上标定位，`citations` 数组给出结构化来源：

```json
"citations": [
  { "id":"chunk_9f3c", "kind":"kb",      "source":"笔记《春游计划》",
    "title":"春游计划", "snippet":"集合时间定在上午9点…", "url":null, "rank":0 },
  { "id":"web_58aa",   "kind":"web",     "source":"天气预报官网",
    "title":"明日天气", "snippet":"…", "url":"https://example.com/", "rank":1 }
]
```

- `kind`：`memory`/`kb` 来自个人数据，`web` 来自联网检索，满足 F6.2 区分。
- 段落尾部说明示例：`来源：[1] 个人笔记《春游计划》；[2] 网络——天气预报官网`。

### 3.8 数据导出与清除格式

```
GET /api/v1/data/export
→ 200; Content-Disposition: attachment; filename=luoye-export-<date>.jsonl
```

JSONL，`schema_version` 打头，每行一类对象：

```json
{"schema_version":1,"exported_at":"2026-09-29T10:00:00+08:00"}
{"kind":"sessions","data":{"id":"...","title":"..."}}
{"kind":"messages","data":{"id":"...","session_id":"...","role":"USER","content":"...","citations":[]}}
{"kind":"memory","data":{"id":"...","content":"...","source":"explicit","confidence":"high"}}
{"kind":"kb_documents","data":{"id":"...","title":"...","source_type":"note","chunk_count":3}}
{"kind":"notes","data":{"id":"...","title":"...","content":"# 春游计划\\n..."}}
{"kind":"todos","data":{"id":"...","title":"...","due_at":"...","status":"open"}}
{"kind":"configs","data":{"config_key":"persona_density","config_value":0.6}}
```

一键清除：`DELETE /api/v1/data → 200 {"cleared":true}`（物理删除全部用户数据，满足 10.3 隐私合规）。

## 4. LangChain4j 组件设计

### 4.1 AiServices 接口（对话编排入口）

```
interface LuoyeAssistant {
    @SystemMessage("""${persona} ... 记忆摘要：${memoryContext}""")
    @MemoryId 绑定 sessionId
    String chat(@MemoryId UUID sessionId, @V("user") String userMessage);

    // 流式版本
    TokenStream stream(@MemoryId UUID sessionId, String userMessage);
}
```

- `@SystemMessage` 模板注入：人格 prompt（清晰独立，便于版本化）、召回到的长期记忆摘要、知识库上下文引用、联网开关等用户首选项。
- 绑定能力：`ChatMemory`（短期）、`@Tool` 集合、`ContentRetriever`（知识库/记忆，见 4.4）。
- 多轮上下文由 `@MemoryId`（sessionId）路由到对应会话窗口。

### 4.2 ChatMemory / 短期记忆

- 用 `MessageWindowChatMemory`，`maxMessagesPerMemory=<可配置>`（如 20），等价于"会话内最近 N 条"。
- 自定义 `ChatMemoryStore` 落库到 `messages` 表：`setMessages(memoryId, messages)` / `getMessages(memoryId)` 与表读写映射；重启后可从表恢复（N11）。
- 长期记忆召回结果以 `MEMORY` 角色消息注入当前窗口（见 §5.4），与用户/助手消息并列但不污染历史。

### 4.3 EmbeddingStore（长期记忆 + 知识库）

- 基于 pgvector 实现 `EmbeddingStore<TextSegment>`（或封装现成 pgvector 集成库）。
- 两个 store 实例：`memoryEmbeddingStore`（`long_term_memory`）、`kbEmbeddingStore`（`kb_chunks`）；业务上以 `user_id` 作为租户过滤，`EmbeddingSearchRequest` 附加过滤保证单用户数据隔离。
- 向量索引建议 **HNSW**（检索快、适合单机个人库），满足 N3 检索 ≤1s。
- 嵌入维度以所选供应商为准，切换供应商时需重建索引/提供向量重算工具。

### 4.4 ContentRetriever（知识库 RAG + 记忆召回）

- `KnowledgeRetriever`：基于 `kbEmbeddingStore` 检索 topK 文本块 → 作为上下文注入，并把命中块拼为 `citations`（§3.7）。
- `MemoryRetriever`：基于 `memoryEmbeddingStore` 检索 topK 记忆 → 注入 prompt 系统区，来源 `kind=memory`。
- 组合：用 `AiServices` 的能力绑定，或用自定义检索器一次性返回"知识 + 记忆"两类，直接参与生成与引用标注。

### 4.5 Tool 注册（@Tool）

```
@Tool("联网搜索最新信息")
String webSearch(@P("query") String query);

@Tool("创建待办/日程")  Todos todosCreate(...);
@Tool("新建或更新笔记") Notes noteSave(...);
@Tool("记录一条长期记忆") void memoryRemember(...);
@Tool("查询某条长期记忆") ...
```

- 统一工具注册表 `ToolRegistry`：扫描 `@Tool` Bean，动态绑定到不同的 AiServices 装配组合（联网开关/人格浓度影响可用工具集）。
- 工具调度由模型自主选择；执行前后经 `ToolExecutionGuard` 记录 `tool_calls` 日志（审计 N7）与 token 消耗，外部副作用（N9）挂起等待用户确认（见 §8）。

## 5. 记忆模块详细设计

### 5.1 记忆产生时机

| 类型 | 时机 | 机制 | 落库字段 |
| ---- | ---- | ---- | -------- |
| 显式记忆 | 即时 | 检测到"记住/补充到记忆/我…"等指令（规则预判 + 模型确认），或用 `memoryRemember` 工具直接写入 | `source=explicit, confidence=high, status=active` |
| 隐式记忆 | 会话收尾/低频 | 专门的抽取 Agent 对整段会话批量抽取事实（非每轮，N18），模型自评置信度 | `source=inferred, confidence=high/medium/low` |
| 待确认记忆 | 即时提示 | `confidence=low` 的 inferred 项进入 `status=pending`，前端提示"我是否记对了？"，用户确认后转 active | `status=pending → active` |
| 纠偏 | 即时 | 用户纠正时更新/删除记忆，`correction_count+1`，并写入 `memory_correction`，反馈给抽取器抑制同类 | 更新 content / `status=deleted` |

触发模式示例（显式）：`记住… / 我生日是… / 别忘了我… / 我住在…`；`忘记/不对/我不住…` 触发删除或纠偏。

### 5.2 召回策略

- **按需主检**：每轮生成前，用"当前用户消息 + 最近上下文摘要"作 query，`MemoryRetriever` 对 `status=active` 记忆向量检索 topK（如 5），注入 `@SystemMessage` 的记忆区。
- **高频常驻**（冷热分级）：`importance_weight` 与 `access_count` 高的少量记忆放入**热区缓存**常驻 prompt，避免每轮全量检索、也控制成本；其余为冷区按需检索。
- **未命中区分**：记录"已检索未命中"与"未检索"两类状态，避免模型误以为"没查到=不存在"，并配合 N18 做检索频率上限（同一会话窗口内固定次数）。
- **引用可溯**：命中记忆以 `kind=memory` 进 citations，前端可回跳查看（F2.3 透明度）。

### 5.3 衰减算法（F2.5）

记忆留存价值计分：

```
score = w1·importance + w2·recency + w3·frequency
recency = exp( - (now - last_access_at) / T )        // T: 时间衰减半衰期(如 90 天)
frequency = ln(1 + access_count) / ln(1 + W)         // W: 频率归一化窗口
```

| score 区间 | 动作 |
| ---------- | ---- |
| ≥ S_keep | `status=active`，保留，`next_review_at` 顺延 |
| < S_keep 且 > S_drop | 降级为"低频记忆"（不入热区，仅冷区按需检索），进入复审队列 |
| ≤ S_drop | 过期清理：`status=stale` → 定期物理清除，删除同名向量 |

- `never_decay=true`（用户显式高亮/强调）权重最高，不进入降级/回收。
- 敏感信息（N6 关键词命中身份证号/地址/密钥）默认不进通用长期记忆，直接拦截。
- 去重：写入前用文本+向量相似度查重，与既有 active 记忆相似度超阈值则合并而非重复插入。

### 5.4 与短期记忆的交接

1. 会话进行中：短期记忆 = `MessageWindowChatMemory`（messages 表），保证上下文连贯（F1.1/F2.2）。
2. 会话收尾（或间隔 N 条消息）：抽取器把会话内"值得长期保留"的信息沉淀为 `long_term_memory`：
   - 用户明确要求记住的 → `explicit`；自动抽取的 → `inferred`（置信度标注）。
   - 与已有记忆去重/合并，避免冗余。
3. 长期记忆进入后续会话：召回后以 `MEMORY` 角色消息注入当前窗口，供模型引用；用户可随时在记忆页查看并删除。

> 一句话交接链：**短期（会话窗口）→ 抽取沉淀（长期记忆表）→ 召回注入（跨会话复用）**。

## 6. 知识库与笔记的统一存储设计（方案 A）

- **统一模型**：笔记 = `kb_documents.source_type='note'`，`source_ref=notes.id`；上传文档 = `kb_documents.source_type='upload'`。二者都落 `kb_chunks`，走同一 `kbEmbeddingStore` 检索。
- **入库管线（上传文档）**：

```
上传文件 → 解析(txt/md/pdf) → DocumentSplitter 分块 → 逐块嵌入 → 写入 kb_chunks
        → 状态 indexing → ready；解析失败 → failed（前端可重传）
```

- **笔记同步（F3.4）**：保存笔记 → upsert `kb_documents(note)` → 增量重分块+重嵌入新增/变更块 → 更新 `chunk_count`。缺失块删除、当前块更新，避免全量重建（N18 增量嵌入）。
- **统一检索**：`KnowledgeRetriever` 查 `kb_chunks`（可按 `source_type` 过滤"仅笔记/仅文档"）；命中即生成 `kind=kb` 引用。
- **检索双通道**：
  - 向量检索：语义相关（RAG 主路径，F3.2）。
  - PostgreSQL FTS/`pg_trgm`：精确关键词、专有名词兜底（F3.3 全文搜索、F1.4 会话搜索）。
- **删除级联**：删除文档 → 删除其 `kb_chunks` 与向量；删除笔记 → 同步删除对应 `kb_documents` 及 chunks。

## 7. 配置管理与多模型切换

### 7.1 配置分层

| 层 | 内容 | 存储 |
| -- | ---- | ---- |
| 运行配置（可运行时改） | 人格/陪伴浓度、记忆开关、联网开关、token 预算、模型选择 | `configs` 表 |
| 机密凭据 | API Key / Secret | 环境变量或 `secrets` 加密列（N6） |

### 7.2 多模型切换（F7.2 / N14）

- 以 Spring Bean 暴露 `ChatLanguageModel` 与 `EmbeddingModel`；供应商/模型信息来自 `configs`。
- **供应商注册表 + 工厂**：`ModelProvider` 枚举（`openai-compatible`、`ollama` 等），统一工厂按配置构建对应 LangChain4j 模型。
- **对话与嵌入可并行**：对话用模型 A、嵌入/检索用模型 B 分别配置，互不耦合（`ChatLanguageModel` 与 `EmbeddingModel` 各自独立 Bean）。
- **切换动作**：`POST /api/v1/configs/model` → 校验连通性 → 重建所需 Bean（或热替换）→ 返回新配置状态。
- **一致性**：人格 prompt 与长期记忆独立于具体模型持久化，切换模型时原样沿用，避免人格与记忆漂移（需求 7.4）。
- **降级路径**：云端模型不可用时切到本地（Ollama）降级，满足 N17。

## 8. 工具调用失败降级策略

| 场景 | 处理 |
| ---- | ---- |
| 工具执行异常 | 捕获并返回**语义化错误**给模型 → 模型选择重试 / 换工具 / 直接向用户说明，不崩溃 |
| 工具超时 | 统一超时（如联网搜索 10s）；超时按失败处理并反馈 |
| 外部副作用（写操作/联网/外部服务） | 先落 `tool_calls(status=pending_confirm)` 挂起，发 `confirm_token`，等用户确认后才真正执行（N9） |
| 模型供应商不可用 | 返回友好降级提示，保留会话不崩溃（N10）；可自动切本地模型 |
| 单日 token 超限 | 拒绝新生成请求并提醒，拆分/改用低成本路径（N16） |
| 嵌入/抽取失败 | 降级为文本索引（FTS）继续检索；重建队列重试 |

- **幂等**：写类工具接口带幂等键，避免重试产生重复副作用。
- **审计**：所有调用（含失败）写入 `tool_calls`（N7），可在前端/日志查看调用链。

## 9. 单用户认证最小方案

- **账户**：`users` 表仅存单用户，密码用 bcrypt 哈希（不落明文）。
- **登录**：`POST /api/v1/auth/login` 校验后签发 **JWT**（HS256，对称密钥走环境变量）。
- **鉴权**：Spring 拦截器/Filter 校验 `Authorization: Bearer <token>`；除 `/auth/login`、健康检查、静态资源外均需认证。
- **生命周期**：`exp`（可配置，默认 1h）；前端持有 token；可选 `logout` 后置失效/黑名单（单机场景可仅前端清除）。
- **安全**：TLS（N8）、密钥隔离（N6）、失败次数限制/简单限流。
- **扩展性**：字段已带 `user_id`，后续多用户只需加注册/角色，业务表无需改结构（为多用户预留，本期不开通）。

## 10. 非功能需求落地映射

| 编号 | 要求 | 设计落地 |
| ---- | ---- | -------- |
| N1 | 首 token ≤2s | SSE + 流式模型 + 预加载 |
| N3 | 检索 ≤1s | pgvector HNSW 向量索引 |
| N5 | 数据本地化 | PostgreSQL 自托管 |
| N6 | 敏感信息 | 密钥加密列/环境变量、敏感记忆拦截 |
| N7 | 审计 | `tool_calls` 全量日志 |
| N9 | 对外操作确认 | 挂起确认机制（§8） |
| N11 | 重启不丢 | 记忆/消息落库 + ChatMemoryStore 恢复 |
| N13/N14 | 模块化/可插拔 | 供应商工厂 + Bean 抽象 |
| N15 | 可观测 | 日志 + 基础指标 |
| N16 | 单日 token 上限 | 预算熔断 |
| N17 | 本地模型降级 | Ollama 供应商 |
| N18 | 低频抽取/增量嵌入 | 显式即时、隐式批量、嵌入增量 |

## 11. 里程碑对应用例（映射需求 10.2）

| 阶段 | 技术落点 |
| ---- | -------- |
| M1 MVP | AiServices + MessageWindowChatMemory + SSE，多轮连贯用例通过 |
| M2 记忆 | long_term_memory + MemoryRetriever + 记忆管理 API（含 M2b 纠偏） |
| M3 知识库 | 入库管线 + RAG + citations（笔记=方案 A 统一入库） |
| M4 事务 | Todos 工具 + 自然语言解析 + 提醒调度 |
| M5 扩展 | 联网检索工具 + 多模态占位 |

## 12. 待确认决策点

1. **嵌入/短信号用第三方 pgvector 集成还是自实现 `EmbeddingStore`**：影响集成工作量与可控性。
2. **隐式抽取的触发频率**：会话收尾一次 vs 每 N 条消息一次；影响成本（N18）。
3. **SSE 打断协议**：采用 `DELETE /streams/{runId}` 还是复用连接内 `event: cancel`。
4. **数据导出格式**：单文件 JSONL 已列，是否改 zip（含原文附件）。
5. **单日 token 预算默认值** 与 `MessageWindowChatMemory` 窗口大小（maxMessages）。
6. **热区缓存容量**（常驻记忆条数与 token 预算上限）。
7. **pgvector 索引**：HNSW vs ivfflat（随数据量权衡训练耗时/召回）。

> 以上为**技术设计初稿**。请评审确认（尤其 §12 决策点），确认后我再按模块拆分进入开发。

---

## 附录

- 附录 A：表结构 DDL 草稿（§2 各表，含 pgvector 索引建法示例）。
- 附录 B：SSE 事件类型枚举与前端状态机约定。
- 附录 C：人格 prompt 模板占位符（`${persona}`、`${memoryContext}`）约定位。