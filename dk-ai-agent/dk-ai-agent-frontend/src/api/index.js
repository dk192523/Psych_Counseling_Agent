import { createSSEConnection } from './sse'
import { createCsrfClient } from './csrf'
import { logoutSession } from './logout'
import axios from 'axios'
import router from '../router'
import { clearAuth, setAuthNotice } from '../stores/auth'

// 根据环境变量设置 API 基础 URL
const API_BASE_URL = process.env.NODE_ENV === 'production'
 ? '/api' // 生产环境使用相对路径，适用于前后端部署在同一域名下
 : 'http://localhost:8123/api' // 开发环境指向本地后端服务

const csrf = createCsrfClient(API_BASE_URL)
export const getAuthConfiguration = () => csrf.get()

// 创建axios实例（后端会话是 HttpOnly Cookie，跨域开发模式必须带凭据）
const request = axios.create({
  baseURL: API_BASE_URL,
  timeout: 60000,
  withCredentials: true
})

request.interceptors.request.use(async config => {
  if (!['get', 'head', 'options'].includes(config.method?.toLowerCase())) {
    Object.assign(config.headers, await csrf.headers())
  }
  return config
})

// 这些认证端点的 401 由各自页面自行处理展示，拦截器不做全局跳转，
// 避免与路由守卫/表单错误提示互相打架。
const INTERCEPTOR_SKIP_URLS = ['/auth/login', '/auth/register', '/auth/me', '/auth/logout']

const handleUnauthorized = (payload = {}) => {
  csrf.clear()
  if (payload?.error === 'DISABLED') {
    setAuthNotice('该账号已被停用，请联系管理员')
  }
  clearAuth()

  const currentRoute = router.currentRoute.value
  if (currentRoute.path !== '/login') {
    const redirect = currentRoute.fullPath
    router.push({
      path: '/login',
      query: redirect && redirect !== '/' ? { redirect } : {}
    })
  }
}

// 响应拦截：会话失效统一处理。
// - 401 且 error=DISABLED → 写全局提示"账号已被停用"
// - 其余 401 → 静默
// 两种情况都清空本地登录态并跳 /login（保留 redirect 便于回到原页面）。
request.interceptors.response.use(
  (response) => response,
  (error) => {
    const status = error?.response?.status
    const url = error?.config?.url || ''

    if (status === 401 && !INTERCEPTOR_SKIP_URLS.some((path) => url.includes(path))) {
      handleUnauthorized(error.response?.data)
    }

    if (error?.response?.data?.error === 'CSRF_INVALID') csrf.clear()
    return Promise.reject(error)
  }
)

// fetch 支持 POST 请求体并保留 SSE 流式读取，避免咨询内容进入 URL。
// 返回值维持 EventSource 风格的 onmessage/onerror/close，调用页面无需感知传输变化。
export const connectSSE = (url, payload) => createSSEConnection(`${API_BASE_URL}${url}`, payload, { headers: csrf.headers, onUnauthorized: handleUnauthorized, onCsrfInvalid: csrf.clear })

// AI 心理咨询师聊天
// clientMsgId：前端为本轮生成的幂等键。SSE 中断后重发同一消息时携带同键，
// 后端据此防止用户消息重复归档（见 PsychMaster 的 openChatStream）。
export const chatWithPsychApp = (message, chatId, deepThinking = false, clientMsgId = null) => {
  return connectSSE('/ai/counseling/chat/sse', { message, chatId, deepThinking, clientMsgId })
}

// 心理咨询会话历史
export const createConversation = async () => {
  const response = await request.post('/ai/conversations')
  return response.data
}

export const getConversations = async () => {
  const response = await request.get('/ai/conversations')
  return response.data
}

export const getConversation = async (conversationId) => {
  const response = await request.get(`/ai/conversations/${encodeURIComponent(conversationId)}`)
  return response.data
}

export const deleteConversation = async (conversationId) => {
  await request.delete(`/ai/conversations/${encodeURIComponent(conversationId)}`)
}

// ==================== 认证 ====================

// 登录：成功后后端写 HttpOnly 会话 Cookie，axios 自动携带。
export const login = async (username, password) => {
  const response = await request.post('/auth/login', { username, password })
  csrf.clear()
  return response.data
}

// 注册：201 并自动登录。
export const register = async (username, password) => {
  const response = await request.post('/auth/register', { username, password })
  csrf.clear()
  return response.data
}

// 登出：销毁会话，204。
export const logout = async () => {
  await logoutSession(() => request.post('/auth/logout'), csrf.clear)
}

// 当前登录用户信息；未认证 401。
export const getMe = async () => {
  const response = await request.get('/auth/me')
  return response.data
}

// 修改自己的密码。
export const changeMyPassword = async (oldPassword, newPassword) => {
  await request.post('/users/me/password', { oldPassword, newPassword })
}

// ==================== 管理后台 ====================

// 用户分页列表：keyword 模糊搜索、status 过滤。
export const adminListUsers = async ({ keyword = '', status = '', page = 0, size = 20 } = {}) => {
  const response = await request.get('/admin/users', {
    params: { keyword, status, page, size }
  })
  return response.data
}

export const adminDisableUser = async (userId, reason = '') => {
  const response = await request.post(`/admin/users/${userId}/disable`, reason ? { reason } : {})
  return response.data
}

export const adminEnableUser = async (userId) => {
  const response = await request.post(`/admin/users/${userId}/enable`)
  return response.data
}

// 批量停用/解封：{ userIds: number[], action: 'DISABLE'|'ENABLE', reason? }
// → { succeeded: number[], failed: [{id, error}] }
export const adminBulk = async (payload) => {
  const response = await request.post('/admin/users/bulk', payload)
  return response.data
}

// 重置密码：返回 { tempPassword }，仅显示一次。
export const adminResetPassword = async (userId) => {
  const response = await request.post(`/admin/users/${userId}/password-reset`)
  return response.data
}

// 删除用户：级联删除其会话/消息/记忆。
export const adminDeleteUser = async (userId) => {
  await request.delete(`/admin/users/${userId}`)
}

// 全局统计：用户/会话/消息总量。
export const adminStats = async () => {
  const response = await request.get('/admin/stats')
  return response.data
}

export default {
  chatWithPsychApp,
  createConversation,
  getConversations,
  getConversation,
  deleteConversation,
  login,
  register,
  logout,
  getMe,
  changeMyPassword,
  adminListUsers,
  adminDisableUser,
  adminEnableUser,
  adminBulk,
  adminResetPassword,
  adminDeleteUser,
  adminStats
}
