import { mount } from '@vue/test-utils'
import { afterEach, describe, it, expect, vi } from 'vitest'
import ChatRoom from './ChatRoom.vue'

describe('ChatRoom interactions', () => {
  afterEach(() => vi.unstubAllGlobals())
  it('does not submit Chinese IME composition, but submits confirmed Enter once', async () => {
    const wrapper = mount(ChatRoom)
    const input = wrapper.get('textarea')
    await input.setValue('最近有些焦虑')
    await input.trigger('keydown', { key: 'Enter', isComposing: true })
    expect(wrapper.emitted('send-message')).toBeUndefined()
    await input.trigger('keydown', { key: 'Enter', shiftKey: true })
    expect(wrapper.emitted('send-message')).toBeUndefined()
    await input.trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('send-message')).toEqual([['最近有些焦虑']])
    expect(input.element.value).toBe('')
    wrapper.unmount()
  })

  it('stops generation without sending another message', async () => {
    const wrapper = mount(ChatRoom, { props: { connectionStatus: 'connecting' } })
    await wrapper.get('.composer-stop').trigger('click')
    expect(wrapper.emitted('stop-stream')).toHaveLength(1)
    expect(wrapper.emitted('send-message')).toBeUndefined()
    wrapper.unmount()
  })

  it('retries explicitly and enforces the input length in the composer', async () => {
    const wrapper = mount(ChatRoom, { props: { retryableTurn: { clientMsgId: 'same-key' } } })
    expect(wrapper.get('textarea').attributes('maxlength')).toBe('4000')
    await wrapper.get('.retry-button').trigger('click')
    expect(wrapper.emitted('retry-send')).toHaveLength(1)
    wrapper.unmount()
  })

  it('renders formatting while blocking active HTML and javascript links', () => {
    const wrapper = mount(ChatRoom, { props: { messages: [{
      id: 'reply', isUser: false, time: Date.now(),
      content: '**正常文字** <img src=x onerror="alert(1)"><script>alert(2)</script> [危险](javascript:alert(3))'
    }] } })
    expect(wrapper.get('.markdown-body strong').text()).toBe('正常文字')
    expect(wrapper.find('.markdown-body script').exists()).toBe(false)
    expect(wrapper.find('.markdown-body [onerror]').exists()).toBe(false)
    expect(wrapper.find('.markdown-body a[href^="javascript:"]').exists()).toBe(false)
    wrapper.unmount()
  })
  it.each(['http://example.org/source', 'https://example.org/source?q=test#details'])('opens %s in the system browser without WebView navigation', async url => {
    const openExternal = vi.fn().mockReturnValue(true)
    vi.stubGlobal('psychDesktop', { openExternal })
    const wrapper = mount(ChatRoom, { props: { messages: [{
      id: 'source', isUser: false, time: Date.now(), content: `[来源 **说明**](${url})`
    }] } })
    const event = new MouseEvent('click', { bubbles: true, cancelable: true })
    wrapper.get('.markdown-body strong').element.dispatchEvent(event)
    expect(event.defaultPrevented).toBe(true)
    expect(openExternal).toHaveBeenCalledExactlyOnceWith(url)
    expect(wrapper.find('.source-link-error').exists()).toBe(false)
    wrapper.unmount()
  })
  it.each(['mailto:someone@example.org', '/relative-source', 'https://user:password@example.org/source'])('blocks unsupported source %s before calling the desktop bridge', async url => {
    const openExternal = vi.fn()
    vi.stubGlobal('psychDesktop', { openExternal })
    const wrapper = mount(ChatRoom, { props: { messages: [{
      id: 'source', isUser: false, time: Date.now(), content: `[来源](${url})`
    }] } })
    const event = new MouseEvent('click', { bubbles: true, cancelable: true })
    wrapper.get('.markdown-body a').element.dispatchEvent(event)
    await wrapper.vm.$nextTick()
    expect(event.defaultPrevented).toBe(true)
    expect(openExternal).not.toHaveBeenCalled()
    expect(wrapper.get('.source-link-error').text()).toContain('http/https')
    wrapper.unmount()
  })
  it('preserves normal browser links with opener isolation', () => {
    const wrapper = mount(ChatRoom, { props: { messages: [{
      id: 'source', isUser: false, time: Date.now(), content: '[来源](https://example.org/source)'
    }] } })
    const anchor = wrapper.get('.markdown-body a')
    expect(anchor.attributes('target')).toBe('_blank')
    expect(anchor.attributes('rel')).toBe('noopener noreferrer')
    const event = new MouseEvent('click', { bubbles: true, cancelable: true })
    anchor.element.dispatchEvent(event)
    expect(event.defaultPrevented).toBe(false)
    wrapper.unmount()
  })
  it.each(['failure', 'exception'])('keeps the conversation visible and reports browser opening %s', async outcome => {
    const openExternal = vi.fn(() => {
      if (outcome === 'exception') throw new Error('browser unavailable')
      return false
    })
    vi.stubGlobal('psychDesktop', { openExternal })
    const wrapper = mount(ChatRoom, { props: { messages: [{
      id: 'source', isUser: false, time: Date.now(), content: '[来源](https://example.org/source)'
    }] } })
    await wrapper.get('.markdown-body a').trigger('click')
    expect(wrapper.get('.source-link-error').text()).toContain('系统默认浏览器')
    expect(wrapper.get('.markdown-body a').text()).toBe('来源')
    wrapper.unmount()
  })
})
