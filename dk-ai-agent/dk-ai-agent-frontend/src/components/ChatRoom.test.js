import { mount } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import ChatRoom from './ChatRoom.vue'

describe('ChatRoom interactions', () => {
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
})
