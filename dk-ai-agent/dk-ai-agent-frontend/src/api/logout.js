// Logout is idempotent. Retry only a rejected CSRF check, never an ambiguous network failure.
export const logoutSession = async (post, clearCsrf) => {
  try {
    await post()
  } catch (error) {
    if (error?.response?.status !== 403 || error?.response?.data?.error !== 'CSRF_INVALID') throw error
    clearCsrf()
    await post()
  } finally {
    clearCsrf()
  }
}
