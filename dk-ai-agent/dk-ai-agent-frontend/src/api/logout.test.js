import { expect, it, vi } from 'vitest'
import { logoutSession } from './logout'
const expired = { response: { status: 403, data: { error: 'CSRF_INVALID' } } }
it('refreshes an expired token once before retrying logout', async () => {
  const clear = vi.fn(), post = vi.fn().mockRejectedValueOnce(expired).mockImplementationOnce(async () => {
    expect(clear).toHaveBeenCalledOnce()
  })
  await logoutSession(post, clear)
  expect(post).toHaveBeenCalledTimes(2)
})
it('does not loop when another tab keeps rotating the token', async () => {
  const post = vi.fn().mockRejectedValue(expired)
  await expect(logoutSession(post, vi.fn())).rejects.toBe(expired)
  expect(post).toHaveBeenCalledTimes(2)
})
it('does not retry an unknown network outcome', async () => {
  const post = vi.fn().mockRejectedValue(new Error('network'))
  await expect(logoutSession(post, vi.fn())).rejects.toThrow('network')
  expect(post).toHaveBeenCalledOnce()
})
