/**
 * 会话与历史消息状态，依赖 Pinia、Vue 和会话 API。
 * 对话页共享该状态，新增消息的生成状态由页面管理。
 * @author Codex（代码生成及注释）
 * @since 2026-09-30
 */
import { ref } from 'vue'
import { defineStore } from 'pinia'
import { createSession, listMessages, listSessions } from '@/api/index.js'

/** 创建或取得会话列表、当前会话和消息状态。 */
export const useSessionStore = defineStore('session', () => {
  const sessions = ref([])
  const currentSessionId = ref(null)
  const messages = ref([])
  const loading = ref(false)
  /** @returns {Promise<Array>} 刷新并返回服务器上的会话列表 */
  async function refresh() {
    sessions.value = await listSessions()
    return sessions.value
  }
  /**
   * 切换当前会话并以服务器历史替换消息列表。
   * @param {string} id 目标会话 UUID
   * @returns {Promise<void>}
   */
  async function open(id) {
    currentSessionId.value = id
    messages.value = await listMessages(id)
  }
  /** @returns {Promise<object>} 创建、置顶并打开的新会话 */
  async function newSession() {
    const session = await createSession()
    sessions.value.unshift(session)
    await open(session.id)
    return session
  }
  /** 加载列表及首个会话；无会话时保持空状态，失败时仍释放 loading。 */
  async function loadFirst() {
    loading.value = true
    try {
      await refresh()
      if (sessions.value.length) await open(sessions.value[0].id)
    } finally {
      loading.value = false
    }
  }
  return {
    sessions,
    currentSessionId,
    messages,
    loading,
    refresh,
    open,
    newSession,
    loadFirst,
  }
})
