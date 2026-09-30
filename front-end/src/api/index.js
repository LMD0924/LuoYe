/**
 * M1 HTTP 与 SSE 客户端，依赖浏览器 fetch、ReadableStream 和 localStorage。
 * 为会话请求附加 JWT，并将服务器事件分发给对话页。
 * @author Codex（代码生成及注释）
 * @since 2026-09-30
 */
const baseURL = import.meta.env.VITE_API_BASE_URL || '/api/v1'

/**
 * 发起 API 请求；失败时抛出带 HTTP status 的 Error。
 * @param {string} path 相对于 API 根地址的路径
 * @param {RequestInit} options fetch 请求选项
 * @returns {Promise<Response>} 成功响应，正文由调用方读取
 */
async function request(path, options = {}) {
  const headers = new Headers(options.headers || {})
  const token = localStorage.getItem('luoye_token')
  if (token) headers.set('Authorization', `Bearer ${token}`)
  if (options.body && !(options.body instanceof FormData))
    headers.set('Content-Type', 'application/json')
  const response = await fetch(`${baseURL}${path}`, { ...options, headers })
  if (!response.ok) {
    let message = `请求失败（${response.status}）`
    try {
      const body = await response.json()
      message = body.message || message
    } catch (_) {
      /* 非 JSON 或空响应时保留通用错误信息。 */
    }
    const error = new Error(message)
    error.status = response.status
    throw error
  }
  return response
}

/**
 * 校验登录凭据；此函数不负责保存令牌。
 * @param {string} username 登录名
 * @param {string} password 登录密码
 * @returns {Promise<object>} 包含 token 和 expiresIn 的响应
 */
export async function login(username, password) {
  const response = await request('/auth/login', {
    method: 'POST',
    body: JSON.stringify({ username, password }),
  })
  return response.json()
}
/** @returns {Promise<Array>} 当前用户的会话列表 */
export async function listSessions() {
  return (await request('/sessions')).json()
}
/**
 * 创建并持久化空会话。
 * @param {string|null} title 会话标题；空值显示为未命名会话
 * @returns {Promise<object>} 新建会话
 */
export async function createSession(title = null) {
  return (
    await request('/sessions', {
      method: 'POST',
      body: JSON.stringify({ title }),
    })
  ).json()
}
/**
 * @param {string} sessionId 会话 UUID
 * @returns {Promise<Array>} 按会话序号排列的完整历史
 */
export async function listMessages(sessionId) {
  return (await request(`/sessions/${sessionId}/messages`)).json()
}

/**
 * 通过 POST 发送消息并读取 SSE；fetch 允许携带 Bearer 头和 JSON 请求体。
 * @param {string} sessionId 会话 UUID
 * @param {string} content 当前用户消息
 * @param {Object<string, Function>} handlers 按事件名索引的回调
 * @returns {Promise<void>} 响应流结束时完成，传输错误时拒绝
 */
export async function streamMessage(sessionId, content, handlers = {}) {
  const response = await request(`/chat/sessions/${sessionId}/messages`, {
    method: 'POST',
    body: JSON.stringify({ content, stream: true }),
  })
  if (!response.body) throw new Error('浏览器不支持流式响应')
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  // 网络块可能拆开一个 SSE 事件，buffer 保留尚未收到空行分隔符的尾部。
  const consume = (raw) => {
    buffer += raw
    const blocks = buffer.split(/\r?\n\r?\n/)
    buffer = blocks.pop() || ''
    for (const block of blocks) {
      let event = 'message'
      const data = []
      for (const line of block.split(/\r?\n/)) {
        if (line.startsWith('event:')) event = line.slice(6).trim()
        if (line.startsWith('data:')) data.push(line.slice(5).trim())
      }
      // 没有 data 的心跳或注释帧不应触发业务回调。
      if (!data.length) continue
      let payload
      try {
        payload = JSON.parse(data.join('\n'))
      } catch (_) {
        payload = { content: data.join('\n') }
      }
      const handler = handlers[event]
      if (handler) handler(payload)
    }
  }
  try {
    while (true) {
      const { value, done } = await reader.read()
      // 使用流式解码，避免一个中文字符跨网络块时被拆成乱码。
      if (value) consume(decoder.decode(value, { stream: !done }))
      if (done) break
    }
    if (buffer.trim()) consume('\n\n')
  } finally {
    reader.releaseLock()
  }
}

/**
 * 请求后端关闭指定生成任务，不直接取消此处的 fetch 读取器。
 * @param {string} sessionId 会话 UUID
 * @param {string} runId start 事件返回的生成 UUID
 * @returns {Promise<object>} 包含 aborted 标记的响应
 */
export async function abortStream(sessionId, runId) {
  return (
    await request(`/chat/sessions/${sessionId}/streams/${runId}`, {
      method: 'DELETE',
    })
  ).json()
}
