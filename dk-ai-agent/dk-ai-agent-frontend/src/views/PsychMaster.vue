<template>
  <div class="psych-master-container">
    <header class="header">
      <h1 class="title">AI 心理咨询师</h1>
      <div class="header-status">
        <span v-if="memoryStats" class="memory-chip" :title="memoryChipTitle">
          <span class="memory-chip-dot" aria-hidden="true"></span>
          <span class="memory-chip-full">{{ memoryStats.messageCount }} / {{ memoryStats.maxMessages }} 条 · {{ memoryStats.digestedCount }} 条已整合</span>
          <span class="memory-chip-compact">{{ memoryStats.messageCount }}/{{ memoryStats.maxMessages }}</span>
        </span>
        <div class="current-conversation" :title="currentConversationTitle">
          {{ currentConversationTitle }}
        </div>
        <div class="header-account">
          <span class="account-chip" :title="`当前登录：${me?.username}`">
            <span class="account-avatar" aria-hidden="true">{{ avatarChar }}</span>
            <span class="account-name">{{ me?.username }}</span>
          </span>
          <router-link v-if="isAdmin" class="account-link admin-entry" to="/admin" title="进入管理后台">
            管理后台
          </router-link>
          <button type="button" class="account-link" title="修改当前账号密码" @click="openPasswordModal">
            修改密码
          </button>
          <button type="button" class="account-link logout-button" :disabled="loggingOut" @click="handleLogout">
            {{ loggingOut ? '退出中…' : '退出登录' }}
          </button>
        </div>
      </div>
    </header>

    <div class="content-wrapper">
      <ConversationSidebar
        :conversations="conversations"
        :active-id="chatId"
        :loading="historyLoading"
        :disabled="isBusy"
        :error="historyError"
        :mobile-open="mobileHistoryOpen"
        @create="createNewConversation"
        @select="selectConversation"
        @delete="confirmDeleteConversation"
        @toggle-mobile="mobileHistoryOpen = !mobileHistoryOpen"
      />

      <main class="chat-area">
        <section class="mode-toolbar" aria-label="回答模式">
          <div class="mode-switch" role="radiogroup" aria-label="选择回答模式">
            <button
              type="button"
              role="radio"
              :aria-checked="responseMode === 'standard'"
              :class="{ active: responseMode === 'standard' }"
              :disabled="isBusy"
              @click="setResponseMode('standard')"
            >快速回复</button>
            <button
              type="button"
              role="radio"
              :aria-checked="responseMode === 'deep'"
              :class="{ active: responseMode === 'deep' }"
              :disabled="isBusy"
              @click="setResponseMode('deep')"
            >深度思考</button>
          </div>
          <p>{{ responseModeDescription }}</p>
        </section>

        <MemoryDigestCard :memory="memoryStats" />

        <ChatRoom
          :messages="messages"
          :connection-status="connectionStatus"
          :input-disabled="historyLoading || conversationLoading || !chatId"
          :thinking-state="thinkingState"
          ai-type="psych"
          @send-message="sendMessage"
        />
      </main>
    </div>

    <div class="footer-container">
      <AppFooter />
    </div>

    <transition name="modal-fade">
      <div v-if="passwordModalOpen" class="modal-overlay" @click.self="closePasswordModal">
        <div class="modal-card" role="dialog" aria-modal="true" aria-label="修改密码">
          <header class="modal-head">
            <h2>修改密码</h2>
            <button type="button" class="modal-close" aria-label="关闭" :disabled="changingPassword" @click="closePasswordModal">×</button>
          </header>

          <p class="modal-text">
            为保障账号安全，修改成功后所有设备会立即退出登录，需使用新密码重新登录。
          </p>

          <p v-if="passwordError" class="modal-error" role="alert">{{ passwordError }}</p>

          <form @submit.prevent="submitPasswordChange">
            <label class="modal-field">
              <span>旧密码</span>
              <input
                v-model="oldPassword"
                type="password"
                autocomplete="current-password"
                maxlength="72"
                :disabled="changingPassword"
              />
            </label>

            <label class="modal-field">
              <span>新密码</span>
              <input
                v-model="newPassword"
                type="password"
                autocomplete="new-password"
                placeholder="8-72 位字符"
                maxlength="72"
                :disabled="changingPassword"
              />
            </label>

            <label class="modal-field">
              <span>确认新密码</span>
              <input
                v-model="confirmPassword"
                type="password"
                autocomplete="new-password"
                placeholder="再输入一次新密码"
                maxlength="72"
                :disabled="changingPassword"
              />
            </label>

            <footer class="modal-foot">
              <button type="button" class="ghost-button" :disabled="changingPassword" @click="closePasswordModal">
                取消
              </button>
              <button type="submit" class="solid-button" :disabled="changingPassword">
                {{ changingPassword ? '提交中…' : '确认修改' }}
              </button>
            </footer>
          </form>
        </div>
      </div>
    </transition>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useHead } from '@vueuse/head'
import ChatRoom from '../components/ChatRoom.vue'
import ConversationSidebar from '../components/ConversationSidebar.vue'
import MemoryDigestCard from '../components/MemoryDigestCard.vue'
import AppFooter from '../components/AppFooter.vue'
import {
  chatWithPsychApp,
  changeMyPassword,
  createConversation,
  deleteConversation,
  getConversation,
  getConversations
} from '../api'
import { useAuth } from '../stores/auth'

const router = useRouter()
const { me, isAdmin, logout, clearAuth, setAuthNotice } = useAuth()
const loggingOut = ref(false)

const avatarChar = computed(() => (me.value?.username || '?').charAt(0).toUpperCase())

// 退出：销毁后端会话并清空本地登录态，回到登录页。
const handleLogout = async () => {
  if (loggingOut.value) return
  loggingOut.value = true
  try {
    stopStream()
    await logout()
    await router.push('/login')
  } finally {
    loggingOut.value = false
  }
}

// ---------- 修改密码 ----------
const passwordModalOpen = ref(false)
const oldPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const passwordError = ref('')
const changingPassword = ref(false)

const openPasswordModal = () => {
  oldPassword.value = ''
  newPassword.value = ''
  confirmPassword.value = ''
  passwordError.value = ''
  passwordModalOpen.value = true
}

const closePasswordModal = () => {
  if (changingPassword.value) return
  passwordModalOpen.value = false
}

// 后端错误码 → 中文提示（401 会话失效由 axios 拦截器统一处理跳转）
const PASSWORD_ERROR_MESSAGES = {
  BAD_OLD_PASSWORD: '旧密码不正确',
  RATE_LIMITED: '请求过于频繁，请稍后再试'
}

const mapPasswordError = (error) => {
  const status = error?.response?.status
  const payload = error?.response?.data
  const code = payload?.error

  if (status === 401) return ''
  if (code === 'VALIDATION') {
    return payload?.message || '新密码不符合要求，请检查后重试'
  }
  if (code && PASSWORD_ERROR_MESSAGES[code]) {
    return PASSWORD_ERROR_MESSAGES[code]
  }
  if (error?.response) {
    return payload?.message || '密码修改失败，请稍后重试'
  }
  return '网络连接失败，请检查网络后重试'
}

const submitPasswordChange = async () => {
  if (changingPassword.value) return
  passwordError.value = ''

  if (!oldPassword.value) {
    passwordError.value = '请输入旧密码'
    return
  }
  if (newPassword.value.length < 8 || newPassword.value.length > 72) {
    passwordError.value = '新密码长度需为 8-72 位字符'
    return
  }
  if (confirmPassword.value !== newPassword.value) {
    passwordError.value = '两次输入的新密码不一致'
    return
  }

  changingPassword.value = true
  try {
    await changeMyPassword(oldPassword.value, newPassword.value)
    // 改密成功后后端会吊销该用户全部会话（含当前会话），
    // 不能留在原页面：停流 → 清空本地登录态 → 跳登录页并提示重新登录。
    stopStream()
    clearAuth()
    setAuthNotice('密码已修改，请使用新密码重新登录')
    passwordModalOpen.value = false
    await router.push('/login')
  } catch (error) {
    const message = mapPasswordError(error)
    if (message) passwordError.value = message
  } finally {
    changingPassword.value = false
  }
}

useHead({
  title: 'AI 心理咨询师',
  meta: [
    {
      name: 'description',
      content: 'AI 心理咨询师从专业、自然的咨询视角，陪你梳理职场、家庭、关系与成长中的困扰'
    },
    {
      name: 'keywords',
      content: 'AI 心理咨询师,AI心理咨询,心理疏导,职场压力,家庭关系,婚恋情感,自我成长'
    }
  ]
})

const WELCOME_MESSAGE = '你好，很高兴见到你。今天想聊些什么？'
const MODE_STORAGE_KEY = 'psych-response-mode'

const readStoredResponseMode = () => {
  try {
    return window.localStorage.getItem(MODE_STORAGE_KEY) === 'deep' ? 'deep' : 'standard'
  } catch {
    return 'standard'
  }
}

const storeResponseMode = (mode) => {
  try {
    window.localStorage.setItem(MODE_STORAGE_KEY, mode)
  } catch {
    // 浏览器禁用本地存储时只影响模式记忆，不影响当前会话。
  }
}

const messages = ref([])
const conversations = ref([])
const chatId = ref('')
const connectionStatus = ref('disconnected')
const historyLoading = ref(false)
const conversationLoading = ref(false)
const historyError = ref('')
const mobileHistoryOpen = ref(false)
const responseMode = ref(readStoredResponseMode())
const thinkingState = ref(null)
const memoryStats = ref(null)
let eventSource = null
let streamVersion = 0

const isBusy = computed(() => (
  historyLoading.value ||
  conversationLoading.value ||
  connectionStatus.value === 'connecting'
))

const currentConversationTitle = computed(() => {
  const current = conversations.value.find(item => item.id === chatId.value)
  return current?.title || '新会话'
})

const memoryChipTitle = computed(() => {
  if (!memoryStats.value) return ''
  const { messageCount, maxMessages, digestedCount, digestChars } = memoryStats.value
  return `当前会话共 ${messageCount} 条消息，原文保留上限 ${maxMessages} 条；已有 ${digestedCount} 条整合进长期记忆（${digestChars} 字）`
})

const responseModeDescription = computed(() => (
  responseMode.value === 'deep'
    ? 'Agent 会先规划检索并核对依据，回答更慢，但会减少表面相似案例的干扰。'
    : '沿用原有 Java RAG 链路，响应更快。'
))

const parseChatStreamEvent = (rawData) => {
  if (rawData === '[DONE]') {
    return { type: 'done', content: '' }
  }

  try {
    const parsed = JSON.parse(rawData)
    if (typeof parsed === 'string') {
      return parsed === '[DONE]'
        ? { type: 'done', content: '' }
        : { type: 'delta', content: parsed }
    }

    if (parsed && typeof parsed === 'object') {
      const supportedTypes = ['status', 'fallback', 'delta', 'done']
      const type = supportedTypes.includes(parsed.type) ? parsed.type : 'delta'
      return {
        type,
        content: typeof parsed.content === 'string' ? parsed.content : '',
        phase: typeof parsed.phase === 'string' ? parsed.phase : '',
        effectiveMode: typeof parsed.effectiveMode === 'string' ? parsed.effectiveMode : '',
        fallback: Boolean(parsed.fallback)
      }
    }
  } catch {
    // 兼容旧接口直接推送的纯文本分片。
  }

  return { type: 'delta', content: rawData }
}

const toTimestamp = (value) => {
  const timestamp = value ? new Date(value).getTime() : Date.now()
  return Number.isNaN(timestamp) ? Date.now() : timestamp
}

const makeWelcomeMessage = () => ({
  id: `welcome-${chatId.value || 'new'}`,
  content: WELCOME_MESSAGE,
  isUser: false,
  time: Date.now()
})

const mapStoredMessages = (storedMessages = []) => {
  return storedMessages
    .filter(message => ['user', 'assistant'].includes(String(message.role).toLowerCase()))
    .map(message => ({
      id: message.id,
      content: message.content || '',
      isUser: String(message.role).toLowerCase() === 'user',
      time: toTimestamp(message.createdAt)
    }))
}

const normalizeConversationList = (data) => {
  const list = Array.isArray(data)
    ? data
    : (data?.conversations || data?.items || [])

  return [...list].sort((left, right) => (
    toTimestamp(right.updatedAt) - toTimestamp(left.updatedAt)
  ))
}

const addMessage = (content, isUser) => {
  messages.value.push({
    id: `local-${Date.now()}-${messages.value.length}`,
    content,
    isUser,
    time: Date.now()
  })
}

const stopStream = () => {
  streamVersion += 1
  if (eventSource) {
    eventSource.close()
    eventSource = null
  }
  if (connectionStatus.value === 'connecting') {
    connectionStatus.value = 'disconnected'
  }
  thinkingState.value = null
}

const setResponseMode = (mode) => {
  if (isBusy.value || !['standard', 'deep'].includes(mode)) return
  responseMode.value = mode
  storeResponseMode(mode)
}

const refreshConversations = async ({ silent = false } = {}) => {
  if (!silent) historyLoading.value = true

  try {
    conversations.value = normalizeConversationList(await getConversations())
    historyError.value = ''
  } catch (error) {
    console.error('读取历史会话失败:', error)
    historyError.value = '历史会话暂时无法读取，请稍后重试。'
  } finally {
    if (!silent) historyLoading.value = false
  }
}

const refreshCurrentMemory = async () => {
  const targetId = chatId.value
  if (!targetId) return

  try {
    const detail = await getConversation(targetId)
    // 请求返回前用户可能已切换会话，过期结果直接丢弃。
    if (chatId.value !== targetId) return
    memoryStats.value = detail.memory || null
  } catch (error) {
    console.error('刷新会话记忆状态失败:', error)
  }
}

const loadConversation = async (conversationId) => {
  stopStream()
  conversationLoading.value = true

  try {
    const detail = await getConversation(conversationId)
    chatId.value = detail.id || conversationId
    memoryStats.value = detail.memory || null
    const storedMessages = mapStoredMessages(detail.messages)
    messages.value = storedMessages.length > 0 ? storedMessages : [makeWelcomeMessage()]
    historyError.value = ''
    mobileHistoryOpen.value = false
  } catch (error) {
    console.error('读取会话内容失败:', error)
    historyError.value = '这段会话暂时无法打开，请稍后重试。'
  } finally {
    conversationLoading.value = false
  }
}

const selectConversation = async (conversationId) => {
  if (!conversationId) return
  if (conversationId === chatId.value) {
    mobileHistoryOpen.value = false
    return
  }
  await loadConversation(conversationId)
}

const createNewConversation = async () => {
  stopStream()
  conversationLoading.value = true

  try {
    const created = await createConversation()
    const createdId = created?.id || created?.conversationId
    if (!createdId) throw new Error('创建会话接口没有返回会话 ID')

    chatId.value = createdId
    messages.value = [makeWelcomeMessage()]
    memoryStats.value = null
    mobileHistoryOpen.value = false
    historyError.value = ''
    await refreshConversations({ silent: true })
  } catch (error) {
    console.error('创建会话失败:', error)
    historyError.value = '新会话创建失败，请检查后端服务后重试。'
  } finally {
    conversationLoading.value = false
  }
}

const confirmDeleteConversation = async (conversation) => {
  const title = conversation.title || '这段会话'
  if (!window.confirm(`确认删除“${title}”吗？删除后无法恢复。`)) return

  conversationLoading.value = true
  try {
    await deleteConversation(conversation.id)
    const deletedCurrent = conversation.id === chatId.value
    if (deletedCurrent) {
      stopStream()
      chatId.value = ''
      messages.value = []
      memoryStats.value = null
    }

    await refreshConversations({ silent: true })
    if (deletedCurrent) {
      if (conversations.value.length > 0) {
        await loadConversation(conversations.value[0].id)
      } else {
        await createNewConversation()
      }
    }
  } catch (error) {
    console.error('删除会话失败:', error)
    historyError.value = '会话删除失败，请稍后重试。'
  } finally {
    conversationLoading.value = false
  }
}

const sendMessage = (message) => {
  if (!chatId.value || connectionStatus.value === 'connecting') return

  addMessage(message, true)
  stopStream()

  const aiMessageIndex = messages.value.length
  addMessage('', false)

  connectionStatus.value = 'connecting'
  const selectedMode = responseMode.value
  thinkingState.value = selectedMode === 'deep'
    ? {
        active: true,
        phase: 'planning',
        label: '正在启动深度思考…',
        fallback: false,
        startedAt: Date.now()
      }
    : null
  const activeStreamVersion = ++streamVersion
  const currentEventSource = chatWithPsychApp(message, chatId.value, selectedMode === 'deep')
  eventSource = currentEventSource

  currentEventSource.onmessage = (event) => {
    if (eventSource !== currentEventSource || activeStreamVersion !== streamVersion) return
    const payload = parseChatStreamEvent(event.data)

    if (['status', 'fallback'].includes(payload.type)) {
      thinkingState.value = {
        active: true,
        phase: payload.phase || (payload.type === 'fallback' ? 'fallback' : 'planning'),
        label: payload.content || '正在继续处理…',
        fallback: payload.type === 'fallback' || payload.fallback,
        startedAt: thinkingState.value?.startedAt || Date.now()
      }
    }

    if (payload.type === 'delta' && aiMessageIndex < messages.value.length) {
      thinkingState.value = null
      messages.value[aiMessageIndex].content += payload.content ?? ''
    }

    if (payload.type === 'done') {
      thinkingState.value = null
      connectionStatus.value = 'disconnected'
      currentEventSource.close()
      eventSource = null
      refreshConversations({ silent: true })
      // 回答结束后后端可能已整合记忆，静默重取 detail 刷新芯片与 digest 卡片。
      refreshCurrentMemory()
    }
  }

  currentEventSource.onerror = (error) => {
    if (eventSource !== currentEventSource || activeStreamVersion !== streamVersion) return
    console.error('SSE Error:', error)
    connectionStatus.value = 'error'
    thinkingState.value = null
    currentEventSource.close()
    eventSource = null

    if (!messages.value[aiMessageIndex]?.content) {
      messages.value[aiMessageIndex].content = '连接中断了，请稍后再试。'
    }
    refreshConversations({ silent: true })
  }
}

const initializeConversations = async () => {
  await refreshConversations()
  if (conversations.value.length > 0) {
    await loadConversation(conversations.value[0].id)
  } else if (!historyError.value) {
    await createNewConversation()
  }
}

onMounted(initializeConversations)
onBeforeUnmount(stopStream)
</script>

<style scoped>
.psych-master-container {
  display: flex;
  min-height: 100vh;
  flex-direction: column;
  background-color: #f4f7f7;
}

.header {
  position: sticky;
  top: 0;
  z-index: 10;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(120px, auto);
  align-items: center;
  padding: 14px 24px;
  background-color: #2c7a7b;
  box-shadow: 0 2px 8px rgba(22, 52, 52, 0.12);
  color: white;
}

.title {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
}

.header-status {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: flex-end;
  justify-self: end;
  gap: 10px;
}

.memory-chip {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 6px;
  padding: 3px 10px;
  border: 1px solid rgba(255, 255, 255, 0.32);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.12);
  color: rgba(255, 255, 255, 0.92);
  font-size: 12px;
  white-space: nowrap;
  transition: background-color 0.15s ease;
}

.memory-chip:hover {
  background: rgba(255, 255, 255, 0.2);
}

.memory-chip-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #9fe8d9;
  box-shadow: 0 0 0 2px rgba(255, 255, 255, 0.12);
}

.memory-chip-compact {
  display: none;
}

.current-conversation {
  max-width: 260px;
  min-width: 0;
  overflow: hidden;
  color: rgba(255, 255, 255, 0.82);
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.header-account {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 8px;
  padding-left: 12px;
  border-left: 1px solid rgba(255, 255, 255, 0.22);
}

.account-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 10px 3px 4px;
  border: 1px solid rgba(255, 255, 255, 0.3);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.12);
  transition: background-color 0.15s ease;
}

.account-chip:hover {
  background: rgba(255, 255, 255, 0.2);
}

.account-avatar {
  display: inline-flex;
  width: 20px;
  height: 20px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #9fe8d9;
  color: #16413f;
  font-size: 11px;
  font-weight: 800;
}

.account-name {
  max-width: 110px;
  overflow: hidden;
  color: rgba(255, 255, 255, 0.94);
  font-size: 12px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.account-link {
  padding: 5px 10px;
  border: 1px solid rgba(255, 255, 255, 0.3);
  border-radius: 6px;
  background: transparent;
  color: rgba(255, 255, 255, 0.9);
  cursor: pointer;
  font-size: 12px;
  white-space: nowrap;
  transition: background-color 0.15s ease;
}

.account-link:hover:not(:disabled) {
  background: rgba(255, 255, 255, 0.16);
  color: #ffffff;
}

.account-link:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.admin-entry {
  border-color: rgba(159, 232, 217, 0.55);
  background: rgba(159, 232, 217, 0.14);
  color: #c9f4ea;
  font-weight: 700;
}

.admin-entry:hover {
  background: rgba(159, 232, 217, 0.26);
}

.content-wrapper {
  display: grid;
  min-height: calc(100vh - 140px);
  flex: 1;
  grid-template-columns: 280px minmax(0, 1fr);
}

.chat-area {
  display: flex;
  min-width: 0;
  padding: 16px;
  flex-direction: column;
  gap: 10px;
  overflow: hidden;
}

.chat-area :deep(.chat-container) {
  min-height: 0;
  flex: 1;
}

.mode-toolbar {
  display: flex;
  min-height: 44px;
  padding: 8px 10px;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  border: 1px solid #d8e1e1;
  border-radius: 8px;
  background: #ffffff;
}

.mode-toolbar p {
  margin: 0;
  color: #5d6f70;
  font-size: 12px;
  line-height: 1.45;
  text-align: right;
}

.mode-switch {
  display: inline-grid;
  flex: 0 0 auto;
  grid-template-columns: repeat(2, 1fr);
  border: 1px solid #9eb8b8;
  border-radius: 7px;
  overflow: hidden;
}

.mode-switch button {
  min-width: 82px;
  padding: 7px 12px;
  border: 0;
  background: #ffffff;
  color: #315d5e;
  cursor: pointer;
  font-size: 13px;
}

.mode-switch button + button {
  border-left: 1px solid #9eb8b8;
}

.mode-switch button.active {
  background: #2c7a7b;
  color: #ffffff;
}

.mode-switch button:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.mode-switch button:focus-visible {
  outline: 2px solid #1f5f60;
  outline-offset: -3px;
}

.footer-container {
  margin-top: auto;
}

/* ---------- 修改密码模态框（与 AdminPanel 模态框同规格） ---------- */
.modal-overlay {
  position: fixed;
  inset: 0;
  z-index: 50;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  background: rgba(20, 42, 42, 0.45);
  backdrop-filter: blur(2px);
}

.modal-card {
  width: 100%;
  max-width: 400px;
  padding: 20px 22px;
  border-radius: 12px;
  background: #ffffff;
  box-shadow: 0 24px 60px rgba(15, 40, 40, 0.28);
}

.modal-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.modal-head h2 {
  margin: 0;
  color: #1d3b3c;
  font-size: 17px;
  font-weight: 800;
}

.modal-close {
  padding: 2px 8px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: #71898a;
  cursor: pointer;
  font-size: 19px;
  line-height: 1;
}

.modal-close:hover:not(:disabled) {
  background: #eef4f4;
}

.modal-close:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}

.modal-text {
  margin: 0 0 14px;
  color: #3c5354;
  font-size: 13px;
  line-height: 1.7;
}

.modal-error {
  margin: 0 0 14px;
  padding: 9px 12px;
  border: 1px solid #f0c8c8;
  border-radius: 6px;
  background: #fdf3f3;
  color: #933838;
  font-size: 13px;
  line-height: 1.55;
}

.modal-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 14px;
}

.modal-field span {
  color: #557071;
  font-size: 12px;
  font-weight: 600;
}

.modal-field input {
  padding: 9px 11px;
  border: 1px solid #c8d6d6;
  border-radius: 7px;
  color: #21393a;
  font-size: 13px;
  transition: border-color 0.16s ease, box-shadow 0.16s ease;
}

.modal-field input::placeholder {
  color: #9fb0b0;
}

.modal-field input:focus {
  outline: none;
  border-color: #2c7a7b;
  box-shadow: 0 0 0 3px rgba(44, 122, 123, 0.14);
}

.modal-field input:disabled {
  opacity: 0.65;
}

.modal-foot {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 18px;
}

.ghost-button {
  padding: 9px 16px;
  border: 1px solid #c8d6d6;
  border-radius: 7px;
  background: #ffffff;
  color: #315d5e;
  cursor: pointer;
  font-size: 13px;
  transition: background-color 0.15s ease, border-color 0.15s ease;
}

.ghost-button:hover:not(:disabled) {
  border-color: #2c7a7b;
  background: #edf7f6;
}

.ghost-button:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.solid-button {
  padding: 9px 16px;
  border: 0;
  border-radius: 7px;
  background: #2c7a7b;
  color: #ffffff;
  cursor: pointer;
  font-size: 13px;
  font-weight: 700;
  transition: filter 0.15s ease, transform 0.15s ease;
}

.solid-button:hover:not(:disabled) {
  filter: brightness(1.08);
  transform: translateY(-1px);
}

.solid-button:disabled {
  cursor: not-allowed;
  opacity: 0.65;
}

.modal-fade-enter-active,
.modal-fade-leave-active {
  transition: opacity 0.18s ease;
}

.modal-fade-enter-from,
.modal-fade-leave-to {
  opacity: 0;
}

@media (max-width: 768px) {
  .header {
    grid-template-columns: minmax(0, 1fr) minmax(72px, auto);
    padding: 11px 14px;
  }

  .title {
    font-size: 17px;
    text-align: left;
  }

  .header-status {
    gap: 6px;
  }

  .memory-chip {
    gap: 5px;
    padding: 2px 8px;
    font-size: 11px;
  }

  .memory-chip-full {
    display: none;
  }

  .memory-chip-compact {
    display: inline;
  }

  .current-conversation {
    max-width: 72px;
    font-size: 11px;
  }

  .header-account {
    gap: 5px;
    padding-left: 8px;
  }

  .account-name {
    display: none;
  }

  .account-chip {
    padding: 2px;
  }

  .account-link {
    padding: 4px 7px;
    font-size: 11px;
  }

  .content-wrapper {
    display: flex;
    height: calc(100dvh - 48px);
    min-height: 0;
    flex: none;
    flex-direction: column;
    overflow: hidden;
  }

  .chat-area {
    min-height: 0;
    flex: 1;
    padding: 10px;
  }

  .chat-area :deep(.chat-container) {
    height: auto;
    min-height: 0;
  }

  .mode-toolbar {
    min-height: 0;
    padding: 6px;
    align-items: stretch;
    flex-direction: column;
    gap: 5px;
  }

  .mode-switch {
    align-self: flex-start;
  }

  .mode-switch button {
    min-width: 76px;
    padding: 6px 10px;
  }

  .mode-toolbar p {
    font-size: 11px;
    text-align: left;
  }
}

@media (max-width: 480px) {
  .header {
    grid-template-columns: minmax(0, 1fr) minmax(62px, auto);
    padding: 9px 10px;
  }

  .title {
    font-size: 16px;
  }

  .current-conversation {
    max-width: 62px;
  }

  .chat-area {
    padding: 8px;
  }

  .chat-area :deep(.chat-container) {
    height: auto;
    min-height: 0;
  }

  .content-wrapper {
    height: calc(100dvh - 44px);
  }
}
</style>
