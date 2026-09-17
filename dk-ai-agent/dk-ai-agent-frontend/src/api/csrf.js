// Session-bound masked token from Spring Security. JSON transport also works in cross-origin dev.
export const createCsrfClient = (baseUrl, fetchImpl = (...args) => fetch(...args)) => {
  let pending = null
  const clear = () => { pending = null }
  const get = () => {
    if (!pending) {
      pending = fetchImpl(`${baseUrl}/auth/csrf`, { credentials: 'include', cache: 'no-store' })
        .then(async response => {
          if (!response.ok) throw new Error('无法获取页面安全凭据，请稍后重试')
          const value = await response.json()
          if (!value.token || value.headerName !== 'X-CSRF-TOKEN') throw new Error('安全凭据格式异常')
          return value
        }).catch(error => { clear(); throw error })
    }
    return pending
  }
  return { get, clear, headers: async () => { const token = await get(); return { [token.headerName]: token.token } } }
}
