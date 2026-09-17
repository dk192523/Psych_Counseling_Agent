import { describe, it, expect, vi } from 'vitest'
import { createCsrfClient } from './csrf'
describe('CSRF lifecycle', () => {
  it('shares concurrent requests and refreshes after session rotation', async () => {
    const fetcher = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ token: 'masked', headerName: 'X-CSRF-TOKEN' }) })
    const client = createCsrfClient('/api', fetcher)
    const [a, b] = await Promise.all([client.headers(), client.headers()])
    expect(a).toEqual({ 'X-CSRF-TOKEN': 'masked' })
    expect(a).toEqual(b)
    expect(fetcher).toHaveBeenCalledTimes(1)
    client.clear()
    await client.headers()
    expect(fetcher).toHaveBeenCalledTimes(2)
  })
  it('does not cache a failed request', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce({ ok: false }).mockResolvedValue({ ok: true, json: async () => ({ token: 'new', headerName: 'X-CSRF-TOKEN' }) })
    const client = createCsrfClient('/api', fetcher)
    await expect(client.headers()).rejects.toThrow()
    expect(await client.headers()).toEqual({ 'X-CSRF-TOKEN': 'new' })
  })
})
