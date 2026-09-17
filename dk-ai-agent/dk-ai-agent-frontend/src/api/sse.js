export const createSSEConnection = (url, payload, { headers, onUnauthorized, onCsrfInvalid, fetchImpl = (...args) => fetch(...args) } = {}) => {
  const controller = new AbortController()
  let closed = false

  const connection = {
    onmessage: null,
    onerror: null,
    close() {
      if (closed) return
      closed = true
      controller.abort()
    }
  }

  const dispatchFrame = (frame) => {
    const data = frame
      .split(/\r?\n/)
      .filter((line) => line.startsWith('data:'))
      .map((line) => line.slice(5).replace(/^ /, ''))
      .join('\n')

    if (data && !closed) {
      connection.onmessage?.({ data })
    }
  }

  const run = async () => {
    try {
      const response = await fetchImpl(url, {
        method: 'POST',
        credentials: 'include',
        headers: {
          Accept: 'text/event-stream',
          'Content-Type': 'application/json',
          ...await headers?.()
        },
        body: JSON.stringify(payload),
        signal: controller.signal
      })

      if (!response.ok) {
        let errorPayload = {}
        try {
          errorPayload = await response.json()
        } catch (_) {
          // 非 JSON 错误页只保留 HTTP 状态，避免把代理响应写入界面。
        }
        if (response.status === 401) {
          onUnauthorized?.(errorPayload)
        }
        if (errorPayload?.error === 'CSRF_INVALID') onCsrfInvalid?.()
        // 带上状态码：调用方（PsychMaster 的 onerror）按 429/403 等给专属文案，
        // 而不是一律显示"连接中断"。
        const error = new Error(
          errorPayload?.message || `SSE request failed with HTTP ${response.status}`)
        error.status = response.status
        throw error
      }
      if (!response.body) {
        throw new Error('当前浏览器不支持流式响应')
      }

      const reader = response.body.getReader()
      const decoder = new TextDecoder('utf-8')
      let buffer = ''
      let completed = false

      while (!closed) {
        const { value, done } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })

        let separator = /\r?\n\r?\n/.exec(buffer)
        while (separator) {
          const frame = buffer.slice(0, separator.index)
          buffer = buffer.slice(separator.index + separator[0].length)
          dispatchFrame(frame)
          if (frame.split(/\r?\n/).some(line => { try { return line.startsWith('data:') && JSON.parse(line.slice(5)).type === 'done' } catch { return false } })) completed = true
          separator = /\r?\n\r?\n/.exec(buffer)
        }
      }

      if (!closed && !completed) {
        throw new Error('SSE 连接在完成事件前结束')
      }
    } catch (error) {
      if (!closed && error?.name !== 'AbortError') {
        connection.onerror?.(error)
      }
    }
  }

  void run()
  return connection
}

