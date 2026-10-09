import { describe, it, expect, vi } from 'vitest'
import { createSSEConnection } from './sse'
const encoder = new TextEncoder()
const response = (parts) => ({ ok: true, body: new ReadableStream({ start(controller) { parts.forEach(p => controller.enqueue(typeof p === 'string' ? encoder.encode(p) : p)); controller.close() } }) })
const settle = () => new Promise(resolve => setTimeout(resolve, 20))
describe('SSE transport', () => {
  it('preserves split UTF-8 and split frame delimiters', async () => {
    const bytes = encoder.encode('data: {"type":"delta","content":"你好"}\r\n\r\ndata: {"type":"done"}\r\n\r\n')
    const fetcher = vi.fn().mockResolvedValue(response([...bytes].map(x => new Uint8Array([x]))))
    const connection = createSSEConnection('/api/chat', {}, { fetchImpl: fetcher, headers: async () => ({ 'X-CSRF-TOKEN': 'csrf' }) })
    const messages = []; const error = vi.fn()
    connection.onmessage = e => messages.push(JSON.parse(e.data)); connection.onerror = error
    await settle()
    expect(messages).toEqual([{ type: 'delta', content: '你好' }, { type: 'done' }])
    expect(error).not.toHaveBeenCalled()
    expect(fetcher.mock.calls[0][1].headers['X-CSRF-TOKEN']).toBe('csrf')
    expect(fetcher.mock.calls[0][1].headers['X-Client-Request-Id']).toMatch(/^[A-Za-z0-9_-]{16,80}$/)
  })
  it('fails on truncated streams instead of reporting success', async () => {
    const connection = createSSEConnection('/api/chat', {}, { fetchImpl: async () => response(['data: {"type":"delta","content":"part"}\n\n']) })
    const error = vi.fn(); connection.onerror = error
    await settle()
    expect(error).toHaveBeenCalledOnce()
  })
  it('preserves HTTP errors for the UI', async () => {
    const connection = createSSEConnection('/api/chat', {}, { fetchImpl: async () => ({ ok: false, status: 429, json: async () => ({ message: 'slow down' }) }) })
    const error = vi.fn(); connection.onerror = error
    await settle()
    expect(error.mock.calls[0][0].status).toBe(429)
  })
  it('closing aborts and cancels the matching proxy request once without an error notification', async () => {
    let signal
    const fetcher = vi.fn(async (url, options) => {
      if (url.startsWith('/__client/cancel/')) return { ok: true, status: 204 }
      signal = options.signal
      return new Promise((_resolve, reject) => options.signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError'))))
    })
    const connection = createSSEConnection('/api/chat', {}, { fetchImpl: fetcher })
    const error = vi.fn(); connection.onerror = error
    await settle(); connection.close(); connection.close(); await settle()
    const requestId = fetcher.mock.calls[0][1].headers['X-Client-Request-Id']
    expect(signal.aborted).toBe(true)
    expect(fetcher).toHaveBeenCalledTimes(2)
    expect(fetcher.mock.calls[1]).toEqual([`/__client/cancel/${requestId}`, {
      method: 'POST', credentials: 'include', keepalive: true
    }])
    expect(error).not.toHaveBeenCalled()
  })
  it('assigns a distinct request ID to each connection', async () => {
    const fetcher = vi.fn().mockImplementation(async () => response(['data: {"type":"done"}\n\n']))
    createSSEConnection('/api/chat', {}, { fetchImpl: fetcher })
    createSSEConnection('/api/chat', {}, { fetchImpl: fetcher })
    await settle()
    const ids = fetcher.mock.calls.map(([, options]) => options.headers['X-Client-Request-Id'])
    expect(ids[0]).toMatch(/^[A-Za-z0-9_-]{16,80}$/)
    expect(ids[1]).not.toBe(ids[0])
  })
  it.each(['missing', 'rejected', 'throws'])('ignores a %s cancel endpoint in ordinary browsers', async (failure) => {
    const fetcher = vi.fn((url, options) => {
      if (url.startsWith('/__client/cancel/')) {
        if (failure === 'throws') throw new TypeError('no cancel route')
        if (failure === 'rejected') return Promise.reject(new TypeError('network unavailable'))
        return Promise.resolve({ ok: false, status: 404 })
      }
      return new Promise((_resolve, reject) => options.signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError'))))
    })
    const connection = createSSEConnection('/api/chat', {}, { fetchImpl: fetcher })
    const error = vi.fn(); connection.onerror = error
    await settle(); connection.close(); await settle()
    expect(fetcher).toHaveBeenCalledTimes(2)
    expect(error).not.toHaveBeenCalled()
  })
  it('does not submit a request when closed while waiting for headers', async () => {
    let resolveHeaders
    const fetcher = vi.fn()
    const connection = createSSEConnection('/api/chat', {}, {
      fetchImpl: fetcher, headers: () => new Promise(resolve => { resolveHeaders = resolve })
    })
    connection.close()
    resolveHeaders({ 'X-CSRF-TOKEN': 'csrf' })
    await settle()
    expect(fetcher).not.toHaveBeenCalled()
  })
})
