<script setup>
/**
 * M1 对话页，依赖登录/会话 Store 与 fetch SSE 客户端。
 * 展示登录、会话列表、历史消息和流式回复，并支持发送停止请求。
 * @author Codex（代码生成及注释）
 * @since 2026-09-30
 */
import { nextTick, onMounted, ref } from 'vue'
import { useAuthStore } from '@/stores/auth.js'
import { useSessionStore } from '@/stores/session.js'
import { abortStream, streamMessage } from '@/api/index.js'

const auth = useAuthStore()
const sessions = useSessionStore()
const input = ref('')
const sending = ref(false)
const error = ref('')
const loginForm = ref({ username: 'admin', password: '' })
const messagesEl = ref(null)

/** 等待 Vue 完成 DOM 更新后滚动到底部。 */
function scrollBottom() {
  nextTick(() => {
    if (messagesEl.value)
      messagesEl.value.scrollTop = messagesEl.value.scrollHeight
  })
}
/** 登录成功后恢复会话列表，将登录或加载错误展示在页面上。 */
async function signIn() {
  error.value = ''
  try {
    await auth.login(loginForm.value.username, loginForm.value.password)
    await sessions.loadFirst()
  } catch (e) {
    error.value = e.message
  }
}
/**
 * 生成期间不切换会话，避免回复出现在另一会话的消息列表里。
 * @param {string} id 要打开的会话 UUID
 */
async function selectSession(id) {
  if (sending.value) return
  error.value = ''
  try {
    await sessions.open(id)
    scrollBottom()
  } catch (e) {
    error.value = e.message
  }
}
/** 创建并打开空会话；生成过程中忽略操作。 */
async function createNew() {
  if (sending.value) return
  error.value = ''
  try {
    await sessions.newSession()
  } catch (e) {
    error.value = e.message
  }
}
/** 发送当前输入，创建临时消息气泡，并根据 SSE 回调更新显示。 */
async function send() {
  const content = input.value.trim()
  if (!content || sending.value) return
  if (!sessions.currentSessionId) await createNew()
  if (!sessions.currentSessionId) return
  error.value = ''
  input.value = ''
  sending.value = true
  // 临时 ID 只用于本页渲染；重新打开会话后由数据库历史替换。
  const userMessage = { id: `local-user-${Date.now()}`, role: 'USER', content }
  const assistantMessage = {
    id: `local-assistant-${Date.now()}`,
    role: 'ASSISTANT',
    content: '',
  }
  sessions.messages.push(userMessage, assistantMessage)
  scrollBottom()
  let runId = null
  try {
    await streamMessage(sessions.currentSessionId, content, {
      // start 提供停止生成所需的 runId；delta 每次只包含新增文本。
      start: (payload) => {
        runId = payload.runId
        runIdForStop.value = payload.runId
      },
      delta: (payload) => {
        assistantMessage.content += payload.content || ''
        scrollBottom()
      },
      done: () => {
        sending.value = false
        runIdForStop.value = null
        scrollBottom()
      },
      error: (payload) => {
        error.value = payload.message || '生成失败'
        assistantMessage.content = ''
        sending.value = false
      },
    })
  } catch (e) {
    error.value = e.message
    assistantMessage.content = ''
    sending.value = false
  } finally {
    if (sending.value) sending.value = false
    runIdForStop.value = null
    await sessions.refresh()
  }
}
/** 使用 start 事件返回的 runId 请求停止当前生成。 */
async function stop() {
  if (!sending.value || !runIdForStop.value) return
  try {
    await abortStream(sessions.currentSessionId, runIdForStop.value)
  } catch (_) {
    /* 当前忽略停止请求错误，原响应流仍按其生命周期继续。 */
  }
}
const runIdForStop = ref(null)
// 本地令牌存在时尝试恢复会话；服务端仍会验证令牌是否有效。
onMounted(async () => {
  if (auth.signedIn) {
    try {
      await sessions.loadFirst()
    } catch (e) {
      error.value = e.message
    }
  }
})
</script>

<template>
  <!-- 登录状态只决定界面展示，API 的实际访问控制在后端。 -->
  <section
    v-if="!auth.signedIn"
    class="mx-auto max-w-md rounded-xl border border-stone-200 bg-white p-6 shadow-sm"
  >
    <h2 class="mb-2">登录落叶</h2>
    <p class="mb-5 text-sm text-stone-500">使用后端配置的单用户账号继续。</p>
    <form class="space-y-4" @submit.prevent="signIn">
      <input
        v-model="loginForm.username"
        class="w-full rounded border p-2"
        placeholder="用户名"
        autocomplete="username"
      />
      <input
        v-model="loginForm.password"
        class="w-full rounded border p-2"
        type="password"
        placeholder="密码"
        autocomplete="current-password"
      />
      <button
        class="w-full rounded bg-emerald-700 px-4 py-2 text-white"
        type="submit"
      >
        登录
      </button>
    </form>
    <p v-if="error" class="mt-4 text-sm text-red-600">{{ error }}</p>
  </section>
  <section v-else class="flex h-[calc(100vh-3rem)] min-h-[520px] gap-4">
    <aside
      class="flex w-64 shrink-0 flex-col rounded-xl border border-stone-200 bg-white p-3"
    >
      <div class="mb-3 flex items-center justify-between">
        <h2 class="text-lg">会话</h2>
        <button
          class="rounded bg-emerald-700 px-3 py-1 text-sm text-white"
          @click="createNew"
        >
          新建
        </button>
      </div>
      <div class="space-y-1 overflow-y-auto">
        <button
          v-for="session in sessions.sessions"
          :key="session.id"
          class="block w-full rounded px-3 py-2 text-left text-sm hover:bg-stone-100"
          :class="
            session.id === sessions.currentSessionId
              ? 'bg-emerald-50 font-medium'
              : ''
          "
          @click="selectSession(session.id)"
        >
          {{ session.title || '未命名会话' }}
        </button>
      </div>
      <button
        class="mt-auto pt-4 text-left text-sm text-stone-500"
        @click="auth.logout"
      >
        退出登录
      </button>
    </aside>
    <div
      class="flex min-w-0 flex-1 flex-col rounded-xl border border-stone-200 bg-white"
    >
      <div ref="messagesEl" class="flex-1 space-y-4 overflow-y-auto p-5">
        <div
          v-if="!sessions.messages.length"
          class="py-16 text-center text-stone-400"
        >
          新建会话，开始聊天
        </div>
        <article
          v-for="message in sessions.messages"
          :key="message.id"
          class="flex"
          :class="message.role === 'USER' ? 'justify-end' : 'justify-start'"
        >
          <div
            class="max-w-[80%] whitespace-pre-wrap rounded-2xl px-4 py-3"
            :class="
              message.role === 'USER'
                ? 'bg-emerald-700 text-white'
                : 'bg-stone-100 text-stone-800'
            "
          >
            {{
              message.content ||
              (sending && message.role === 'ASSISTANT' ? '▌' : '')
            }}
          </div>
        </article>
      </div>
      <p v-if="error" class="px-5 text-sm text-red-600">{{ error }}</p>
      <form
        class="flex gap-3 border-t border-stone-100 p-4"
        @submit.prevent="send"
      >
        <textarea
          v-model="input"
          class="min-h-12 flex-1 resize-none rounded border p-3"
          placeholder="输入消息，Enter 发送"
          @keydown.enter.exact.prevent="send"
        /><button
          v-if="sending"
          type="button"
          class="rounded border border-stone-300 px-5 py-2"
          @click="stop"
        >
          停止</button
        ><button
          v-else
          class="rounded bg-emerald-700 px-5 py-2 text-white disabled:opacity-50"
          :disabled="!input.trim()"
        >
          发送
        </button>
      </form>
    </div>
  </section>
</template>
