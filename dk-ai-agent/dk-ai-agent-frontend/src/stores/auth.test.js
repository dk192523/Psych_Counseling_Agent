import { beforeEach, expect, it, vi } from 'vitest'
vi.mock('../api', () => ({ getMe: vi.fn(), logout: vi.fn() }))
import { getMe, logout as apiLogout } from '../api'
import { clearAuth, ensureMe, logout, setMe, useAuth } from './auth'
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
beforeEach(() => { vi.resetAllMocks(); clearAuth() })
it.each([403, 500, undefined])('preserves identity when logout cannot be confirmed (%s)', async status => {
  setMe({ id: 7 }); apiLogout.mockRejectedValue({ response: { status } })
  await expect(logout()).rejects.toBeDefined()
  expect(useAuth().me.value.id).toBe(7)
})
it.each([204, 401])('clears identity after confirmed logout (%s)', async status => {
  setMe({ id: 7 })
  if (status === 401) apiLogout.mockRejectedValue({ response: { status } })
  else apiLogout.mockResolvedValue()
  await logout(); expect(useAuth().isLoggedIn.value).toBe(false)
})
it('discards stale identity after logout and returns current identity to waiting guards', async () => {
  const old = deferred(); getMe.mockReturnValue(old.promise)
  const pending = ensureMe(true); clearAuth(); old.resolve({ id: 7 })
  expect(await pending).toBeNull(); expect(useAuth().isLoggedIn.value).toBe(false)
})
it('old failed request cannot erase a newly logged-in account', async () => {
  const old = deferred(); getMe.mockReturnValue(old.promise)
  const pending = ensureMe(true); setMe({ id: 8 }); old.reject({ response: { status: 401 } })
  expect(await pending).toEqual({ id: 8 }); expect(useAuth().me.value.id).toBe(8)
})
it('latest forced refresh wins and old completion cannot clear its pending reference', async () => {
  const a = deferred(), b = deferred()
  getMe.mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise)
  const first = ensureMe(true), second = ensureMe(true)
  a.resolve({ id: 7 }); await first
  b.resolve({ id: 8 }); await second
  expect(useAuth().me.value.id).toBe(8)
})
it('an identity refresh during logout cannot prevent the confirmed logout', async () => {
  setMe({ id: 7 })
  const exit = deferred(), refresh = deferred()
  apiLogout.mockReturnValue(exit.promise); getMe.mockReturnValue(refresh.promise)
  const leaving = logout(), pending = ensureMe(true)
  refresh.resolve({ id: 7 }); await pending
  exit.resolve(); await leaving
  expect(useAuth().isLoggedIn.value).toBe(false)
})
