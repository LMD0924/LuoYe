# 落叶项目骨架

依据技术设计 v1.1 和需求 v0.2。本阶段只提供构建、依赖、配置、数据库迁移和静态页面，不进入 M1。

## 目录

```text
back-end-LangChan4j/
  pom.xml                         # Spring Boot 3.3.4 / Java 17 / LangChain4j 0.36.2 BOM
  .env.example                    # 环境变量说明，不自动加载
  luoye-api/
    pom.xml                       # Web / Security / JJWT / 可执行 jar
    src/main/java/com/luoye/api/
      LuoyeApiApplication.java    # 启动与跨模块扫描
      config/                     # 安全默认拒绝全部请求，JWT 配置占位
      controller/                 # 空目录
      stream/                     # 空目录
    src/main/resources/
      application.yml
      db/migration/V1__init.sql
  luoye-service/
    pom.xml                       # LangChain4j / OpenAI 兼容 / Ollama
    src/main/java/com/luoye/service/{chat,memory,kb,tool}/
  luoye-dao/
    pom.xml                       # JPA / PostgreSQL / pgvector Java / Flyway
    src/main/java/com/luoye/dao/{entity,repository,store}/
  luoye-common/
    pom.xml
    src/main/java/com/luoye/common/response/
front-end/
  package.json
  package-lock.json
  vite.config.js                  # @ 路径别名，/api 代理 localhost:8080
  jsconfig.json                   # JavaScript 路径别名与编辑器提示
  .env.example
  index.html
  src/
    main.js                       # Vue / Pinia / Router / Tailwind CSS
    App.vue                       # 静态导航布局
    router/index.js                # 五个页面，按需加载
    views/
      ChatView.vue
      MemoryView.vue
      KnowledgeView.vue
      NotesView.vue
      SettingsView.vue
    api/                          # 空模块，不发送请求
    stores/                       # 空模块，不处理认证或会话
    components/
    types/
    assets/styles.css
```

依赖方向：api → service → dao → common；只对 api 打包 Spring Boot 可执行 jar。
pgvector 本轮采用 com.pgvector:pgvector JDBC 集成依赖，EmbeddingStore 适配留待对应里程碑。

## 前端选型

- JavaScript + Tailwind CSS 4：使用 @tailwindcss/vite 插件与工具类搭建静态布局，移除 TypeScript 和 Element Plus。
- md-editor-v3：Vue 3 原生 Markdown 编辑器；仅声明依赖，编辑、保存、入库均未实现。

## 构建与启动

后端从项目根目录运行：

```powershell
mvn -f back-end-LangChan4j/pom.xml package -DskipTests
```

需准备 PostgreSQL 16 与 pgvector >= 0.5（HNSW），创建空的 luoye 数据库及用户。
通过 IDE 或终端注入 DB_PASSWORD，可选覆盖 DB_URL、DB_USERNAME。
首次启动执行 Flyway；数据库账号需有安装 vector、pg_trgm 扩展和建表权限。
然后运行：

```powershell
java -jar back-end-LangChan4j/luoye-api/target/luoye-api-0.1.0-SNAPSHOT.jar
```

没有数据库时后端启动会失败；构建不需要数据库。当前 HTTP 请求默认拒绝，尚无登录/健康检查业务端点。

前端：

```powershell
cd front-end
npm ci
npm run dev
npm run build
```

开发地址 http://localhost:5173。生产使用 history 路由，Web 服务器需将页面路由回退到 index.html；
/api 必须代理后端，不能回退到页面。子路径部署时同步调整 Vite base。
VITE_ 环境变量公开给浏览器，禁止放入任何 API Key 或 JWT 密钥。

## 配置边界

application.yml 已预留数据库、独立的对话/嵌入供应商、模型、API Key、Ollama 和 JWT 配置。
langchain4j.chat / embedding 是项目自定义配置，尚无模型工厂读取，不是自动装配的 Starter 配置。
JWT 密钥生成、持久化、签发和校验均留待 M1。空密钥不会被用于签发。
不提供用户种子数据，不执行任何模型请求。

## V1 数据库迁移

包含 vector、pg_trgm 扩展以及 users、sessions、messages、long_term_memory、
kb_documents、kb_chunks、notes、configs、todos、tool_calls、memory_correction 共 11 张表。
包含主外键、唯一约束、枚举 CHECK、inferred 置信度约束、HNSW 余弦索引、
会话/消息/文档块查询索引及 FTS/trigram 索引。

设计具体化说明：
- 保留 §2.3 状态 interrupped 的原始拼写，未擅自修改文档契约；需要修正时统一变更契约和迁移。
- notes.status 文档只定义 active 默认值，未自行增加枚举 CHECK。
- §2.9 省略的类型使用 UUID、text/varchar、timestamptz、int；枚举按原文约束。
- 按 ER 关系补齐外键；笔记与文档相互引用的外键在建表后添加。不额外增加一对一约束。
- 未添加自动删除触发器或级联删除业务策略；后续删除服务须在事务中解除双向引用并按依赖顺序删除。
- 两处 vector(1536) 固定维度。切换维度需新增迁移并重新嵌入，不能只改环境变量。
- simple FTS 不提供中文分词；pg_trgm 预留中文子串检索路径，具体查询待业务实现。
- updated_at 由后续应用维护；不添加更新时间触发器。
- V1 应用于空库；一旦执行，后续变更应新增 V2 等迁移，不修改已执行的 V1。

## 阶段边界

无 Controller、Entity、Repository、模型 Bean、SSE 客户端或业务状态实现。
页面仅显示名称。确认本骨架后才进入 M1；长期记忆属 M2，知识库/笔记入库属 M3。
## 本轮验证

- Maven 多模块 package 成功，使用 Java 17；未添加或运行业务测试。
- 前端使用 JavaScript，构建命令为 vite build；不再运行 TypeScript 类型检查。
- SQL 已静态核对 11 张表、12 个 CHECK、两处 vector(1536)；未在 PostgreSQL 实例执行迁移。