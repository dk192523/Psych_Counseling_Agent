import { shallowMount, flushPromises } from '@vue/test-utils'
import { ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import PsychMaster from './PsychMaster.vue'
import ChatRoom from '../components/ChatRoom.vue'
import ConversationSidebar from '../components/ConversationSidebar.vue'
import * as api from '../api'

vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))
vi.mock('@unhead/vue', () => ({ useHead: vi.fn() }))
vi.mock('../stores/auth', () => ({ useAuth: () => ({ me: ref({ username: 'tester' }), isAdmin: ref(false), logout: vi.fn(), clearAuth: vi.fn(), setAuthNotice: vi.fn() }) }))
vi.mock('../api', () => ({ chatWithPsychApp: vi.fn(), changeMyPassword: vi.fn(), createConversation: vi.fn(), deleteConversation: vi.fn(), getConversation: vi.fn(), getConversations: vi.fn() }))

const detail = id => ({ id, messages: [{ id: 1, role: 'assistant', content: id, createdAt: '2026-09-17T00:00:00Z' }] })
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
describe('conversation and stream state', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    api.getConversations.mockResolvedValue([{ id: 'first', title: 'first' }])
    api.getConversation.mockImplementation(async id => detail(id))
    api.chatWithPsychApp.mockImplementation(() => ({ close: vi.fn(), onmessage: null, onerror: null }))
  })
  it('ignores an older conversation response after a newer selection', async () => {
    const wrapper = shallowMount(PsychMaster)
    await flushPromises()
    const a = deferred(), b = deferred()
    api.getConversation.mockImplementation(id => id === 'a' ? a.promise : b.promise)
    const sidebar = wrapper.getComponent(ConversationSidebar)
    sidebar.vm.$emit('select', 'a')
    sidebar.vm.$emit('select', 'b')
    b.resolve(detail('b')); await flushPromises()
    a.resolve(detail('a')); await flushPromises()
    expect(sidebar.props('activeId')).toBe('b')
    expect(wrapper.getComponent(ChatRoom).props('messages')[0].content).toBe('b')
    wrapper.unmount()
  })
  it('preserves the server error after refreshing the list and does not retry a stored partial', async () => {
    const wrapper = shallowMount(PsychMaster)
    await flushPromises()
    const room = wrapper.getComponent(ChatRoom)
    room.vm.$emit('send-message', '你好')
    await flushPromises()
    const connection = api.chatWithPsychApp.mock.results[0].value
    connection.onmessage({ data: JSON.stringify({ type: 'error', phase: 'partial', content: '已保存部分回答' }) })
    await flushPromises()
    expect(wrapper.getComponent(ConversationSidebar).props('error')).toBe('已保存部分回答')
    expect(room.props('retryableTurn')).toBeNull()
    expect(connection.close).toHaveBeenCalledOnce()
    wrapper.unmount()
  })
  it('manual retry reuses the original key; stop closes the active stream', async () => {
    const wrapper = shallowMount(PsychMaster)
    await flushPromises()
    const room = wrapper.getComponent(ChatRoom)
    room.vm.$emit('send-message', '最近压力很大')
    await flushPromises()
    const first = api.chatWithPsychApp.mock.calls[0]
    api.chatWithPsychApp.mock.results[0].value.onerror(Object.assign(new Error('busy'), { status: 429 }))
    await flushPromises()
    expect(api.chatWithPsychApp).toHaveBeenCalledTimes(1)
    room.vm.$emit('retry-send'); await flushPromises()
    expect(api.chatWithPsychApp.mock.calls[1]).toEqual(first)
    const active = api.chatWithPsychApp.mock.results[1].value
    room.vm.$emit('stop-stream'); await flushPromises()
    expect(active.close).toHaveBeenCalledOnce()
    expect(room.props('connectionStatus')).toBe('disconnected')
    wrapper.unmount()
  })
})
