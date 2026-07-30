import { computed, reactive } from 'vue'
import { getMe, logout as apiLogout } from '../api'

/**
 * 模块级登录态缓存（无 Pinia，直接 reactive 单例）。
 * - me: 当前登录用户（GET /api/auth/me 的响应）
 * - fetched: 是否已确认过登录态（含"确认未登录"），避免每次路由切换重复请求 me
 * - notice: 全局登录态提示（如 401 DISABLED 被踢下线时由 axios 拦截器写入）
 */
const state = reactive({
  me: null,
  fetched: false,
  notice: ''
})

let inflightMe = null

/**
 * 确保已拉取当前登录态。首次懒加载 GET /api/auth/me，
 * 并发调用共享同一个 in-flight Promise；force=true 时强制重取。
 * @returns {Promise<object|null>} me 对象；未认证返回 null
 */
export const ensureMe = (force = false) => {
  if (!force && state.fetched) {
    return Promise.resolve(state.me)
  }
  if (!force && inflightMe) {
    return inflightMe
  }

  inflightMe = getMe()
    .then((me) => {
      state.me = me
      state.fetched = true
      return me
    })
    .catch((error) => {
      // me 接口的 401 由路由守卫处理跳转，这里只落状态。
      if (error?.response?.data?.error === 'DISABLED') {
        state.notice = '该账号已被停用，请联系管理员'
      }
      state.me = null
      state.fetched = true
      return null
    })
    .finally(() => {
      inflightMe = null
    })

  return inflightMe
}

/** 登录/注册成功后直接写入 me（接口已自动登录，无需再拉一次）。 */
export const setMe = (me) => {
  state.me = me
  state.fetched = true
  state.notice = ''
}

/**
 * 清空本地登录态缓存（登出或被踢下线时调用）。
 * fetched 置 true 表示"已确认未登录"，后续守卫直接重定向，
 * 不再反复打 me 接口；重新登录时 setMe 会覆盖该状态。
 */
export const clearAuth = () => {
  state.me = null
  state.fetched = true
  inflightMe = null
}

export const setAuthNotice = (message) => {
  state.notice = message || ''
}

export const clearAuthNotice = () => {
  state.notice = ''
}

/**
 * 登出：调用后端销毁会话（失败也继续，会话可能已过期），
 * 再清空本地缓存。跳转由调用方决定。
 */
export const logout = async () => {
  try {
    await apiLogout()
  } catch {
    // 401/网络错误都视为已登出，静默继续。
  }
  clearAuth()
}

/**
 * 组件内使用的响应式视图。store 是模块单例，
 * 多处 useAuth() 共享同一份 state。
 */
export const useAuth = () => {
  const me = computed(() => state.me)
  const isLoggedIn = computed(() => Boolean(state.me))
  const isAdmin = computed(() => state.me?.role === 'ADMIN')
  const notice = computed(() => state.notice)

  return {
    me,
    isLoggedIn,
    isAdmin,
    notice,
    ensureMe,
    setMe,
    clearAuth,
    setAuthNotice,
    clearAuthNotice,
    logout
  }
}
