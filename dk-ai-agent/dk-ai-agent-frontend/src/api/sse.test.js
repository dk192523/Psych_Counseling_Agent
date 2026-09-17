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
  it('closing aborts the network request without an error notification', async () => {
    let signal
    const connection = createSSEConnection('/api/chat', {}, { fetchImpl: async (_url, options) => { signal = options.signal; return new Promise((_resolve, reject) => options.signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))) } })
    const error = vi.fn(); connection.onerror = error
    await settle(); connection.close(); await settle()
    expect(signal.aborted).toBe(true)
    expect(error).not.toHaveBeenCalled()
  })
})
