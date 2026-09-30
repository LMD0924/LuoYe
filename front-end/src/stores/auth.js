/**
 * 登录状态模块，依赖 Pinia、Vue 和登录 API。
 * 本地持有令牌只用于恢复界面状态；有效性仍由服务端验证。
 * @author Codex（代码生成及注释）
 * @since 2026-09-30
 */
import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { login as loginRequest } from '@/api/index.js'

/** 创建或取得共享登录状态。 */
export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem('luoye_token'))
  const signedIn = computed(() => Boolean(token.value))
  /**
   * 登录成功后更新内存状态和浏览器存储；异常交由页面展示。
   * @param {string} username 登录名
   * @param {string} password 密码
   * @returns {Promise<void>}
   */
  async function login(username, password) {
    const data = await loginRequest(username, password)
    token.value = data.token
    localStorage.setItem('luoye_token', data.token)
  }
  /** 清除浏览器令牌；不会吊销服务器已签发的 JWT。 */
  function logout() {
    token.value = null
    localStorage.removeItem('luoye_token')
  }
  return { token, signedIn, login, logout }
})
