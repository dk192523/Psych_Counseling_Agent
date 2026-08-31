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
          :retryable-turn="retryableTurn"
          ai-type="psych"
          @send-message="sendMessage"
          @retry-send="retryFailedTurn"
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
// U9：可手动重发的中断轮次（message/mode/aiMessageIndex/clientMsgId）。
// 键沿用首轮生成的 clientMsgId，后端幂等去重保证重发不会重复归档。
const retryableTurn = ref(null)
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
  // 任何流收尾（新发送、切会话、删会话）都作废旧的重发入口，避免把旧轮次重放进新会话。
  retryableTurn.value = null
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
    return detail.memory || null
  } catch (error) {
    console.error('刷新会话记忆状态失败:', error)
    return null
  }
}

/*
 * U6：digest 时效性。
 *
 * 评审建议"每 30s 轮询"，但这个方向是错的：整合只由后端 onTurnArchived 在虚拟线程上触发，
 * 用户空闲时 digest 不会自己前进——固定轮询绝大多数请求都在确认"没变化"，纯浪费。
 *
 * 真正的窗口在别处：done 事件到达时，整合往往还在虚拟线程里跑。此刻取到的是整合前的旧
 * digest，而下一次刷新要等到下一轮回答结束——长对话里用户就一直看着过期的卡片。
 *
 * 所以只在这个已知窗口内做有界重试，用 updatedAt 判断整合是否落库，拿到新值立刻停。
 * 整合不一定发生（未达折叠阈值就不会推进），因此重试次数必须有上限而不是等到变化为止。
 */
const MEMORY_SETTLE_DELAYS_MS = [1200, 3000, 6000]

const refreshMemoryUntilSettled = async () => {
  const targetId = chatId.value
  const before = memoryStats.value
  const baseline = `${before?.updatedAt ?? ''}|${before?.digestedCount ?? -1}`

  for (const delay of MEMORY_SETTLE_DELAYS_MS) {
    await new Promise(resolve => setTimeout(resolve, delay))
    // 期间用户切了会话或又发了一轮，这条重试链就没有意义了。
    if (chatId.value !== targetId || connectionStatus.value === 'connecting') return

    const memory = await refreshCurrentMemory()
    if (!memory) return
    if (`${memory.updatedAt ?? ''}|${memory.digestedCount ?? -1}` !== baseline) return
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

// crypto.randomUUID 只在安全上下文（HTTPS/localhost）可用；非安全部署退回手工 v4，
// 保证幂等键在任何环境都存在。
const newClientMsgId = () => {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (char) => {
    const random = (Math.random() * 16) | 0
    return (char === 'x' ? random : (random & 0x3) | 0x8).toString(16)
  })
}

const sendMessage = (message) => {
  if (!chatId.value || connectionStatus.value === 'connecting') return

  addMessage(message, true)
  stopStream()

  const aiMessageIndex = messages.value.length
  addMessage('', false)

  connectionStatus.value = 'connecting'
  openChatStream({
    message,
    mode: responseMode.value,
    chatId: chatId.value,
    aiMessageIndex,
    clientMsgId: newClientMsgId(),
    retryCount: 0
  })
}

// U9 手动重发入口：沿用首轮的 aiMessageIndex 与 clientMsgId——用户气泡不重复添加，
// 后端按幂等键去重归档。手动重发自带 retryCount>1，不再触发自动重试。
const retryFailedTurn = () => {
  const turn = retryableTurn.value
  if (!turn || turn.chatId !== chatId.value || connectionStatus.value === 'connecting') return

  stopStream()
  const aiMessage = messages.value[turn.aiMessageIndex]
  if (aiMessage) {
    aiMessage.content = ''
  }

  connectionStatus.value = 'connecting'
  openChatStream({ ...turn, retryCount: turn.retryCount + 1 })
}

/*
 * U9：分级容错。
 * - 首字节前失败（一个 delta 都没收到）视为连接抖动，自动用同一 clientMsgId 重发一次：
 *   后端幂等键保证重发绝不重复归档，重连期间两种模式都显示指示条。
 * - 流中途断开（已收到部分回答）不自动重发——重发会从头再答一遍，与已显示的半截回答
 *   叠加成两段内容；改为置错误态并提供手动"重新发送"。
 */
const openChatStream = ({ message, mode, chatId: turnChatId, aiMessageIndex, clientMsgId, retryCount }) => {
  if (retryCount > 0) {
    thinkingState.value = {
      active: true,
      phase: 'planning',
      label: '网络波动，正在重新连接…',
      fallback: false,
      startedAt: Date.now()
    }
  } else {
    thinkingState.value = mode === 'deep'
      ? {
          active: true,
          phase: 'planning',
          label: '正在启动深度思考…',
          fallback: false,
          startedAt: Date.now()
        }
      : null
  }
  const activeStreamVersion = ++streamVersion
  const currentEventSource = chatWithPsychApp(message, turnChatId, mode === 'deep', clientMsgId)
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
      // 整合是异步的（虚拟线程），done 时往往还没落库，所以后面跟一段有界重试。
      refreshCurrentMemory()
      refreshMemoryUntilSettled()
    }
  }

  currentEventSource.onerror = (error) => {
    if (eventSource !== currentEventSource || activeStreamVersion !== streamVersion) return
    console.error('SSE Error:', error)
    currentEventSource.close()
    eventSource = null

    const receivedAnyDelta = Boolean(messages.value[aiMessageIndex]?.content)
    if (!receivedAnyDelta && retryCount < 1) {
      connectionStatus.value = 'connecting'
      openChatStream({
        message,
        mode,
        chatId: turnChatId,
        aiMessageIndex,
        clientMsgId,
        retryCount: retryCount + 1
      })
      return
    }

    connectionStatus.value = 'error'
    thinkingState.value = null
    if (!messages.value[aiMessageIndex]?.content) {
      messages.value[aiMessageIndex].content = '连接中断了，请稍后再试。'
    }
    retryableTurn.value = { message, mode, chatId: turnChatId, aiMessageIndex, clientMsgId, retryCount }
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

/*
 * U8：移动端键盘弹出时把可视高度同步到 --app-viewport-height。
 *
 * 为什么不能只靠 100dvh：dvh 跟随的是浏览器 UI（地址栏）的伸缩，而软键盘在 iOS Safari 上
 * 根本不改变 layout viewport——它只缩小 visualViewport，然后把整页往上平移来让焦点可见。
 * 结果就是标题和模式栏被顶出屏幕。只有读 visualViewport.height 才能拿到键盘遮挡后的真实高度。
 *
 * offsetTop 那一项是补偿 iOS 已经平移过的量：键盘弹出时它非 0，不减掉的话外壳仍会偏移。
 */
const applyViewportHeight = () => {
  const viewport = window.visualViewport
  const height = viewport ? viewport.height - viewport.offsetTop : window.innerHeight
  document.documentElement.style.setProperty('--app-viewport-height', `${Math.round(height)}px`)
}

const bindViewportListeners = () => {
  applyViewportHeight()
  const viewport = window.visualViewport
  if (!viewport) {
    // 老浏览器无 visualViewport：退回 resize，CSS 侧的 100dvh 兜底仍然生效。
    window.addEventListener('resize', applyViewportHeight)
    return
  }
  viewport.addEventListener('resize', applyViewportHeight)
  viewport.addEventListener('scroll', applyViewportHeight)
}

const unbindViewportListeners = () => {
  const viewport = window.visualViewport
  if (viewport) {
    viewport.removeEventListener('resize', applyViewportHeight)
    viewport.removeEventListener('scroll', applyViewportHeight)
  } else {
    window.removeEventListener('resize', applyViewportHeight)
  }
  document.documentElement.style.removeProperty('--app-viewport-height')
}

onMounted(() => {
  bindViewportListeners()
  return initializeConversations()
})

onBeforeUnmount(() => {
  stopStream()
  unbindViewportListeners()
})
</script>

<style scoped>
.psych-master-container {
  display: flex;
  min-height: 100vh;
  flex-direction: column;
  background: var(--psych-canvas-base);
}

/* 头部玻璃条：全页唯一用 backdrop-filter 的地方——内容从它下面滚过，
   模糊这一层的合成成本可控，换来真正的"毛玻璃"质感。 */
.header {
  position: sticky;
  top: 0;
  z-index: 10;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(120px, auto);
  align-items: center;
  /* 高度必须是固定契约：下方 content-wrapper 的确定高度按它推算（56px）。
     只用 padding 撑高的话，wrapper 的高度计算会和实际 header 脱节。 */
  height: 56px;
  padding: 0 24px;
  background: rgba(255, 255, 255, 0.66);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
  border-bottom: 1px solid var(--psych-glass-line);
  color: var(--psych-ink);
}

.title {
  margin: 0;
  font-size: 19px;
  font-weight: 800;
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
  padding: 4px 11px;
  border: 1px solid var(--psych-glass-line);
  border-radius: var(--psych-radius-pill);
  background: rgba(255, 255, 255, 0.55);
  color: var(--psych-ink-secondary);
  font-size: 12px;
  white-space: nowrap;
  transition: background-color 0.15s ease, border-color 0.15s ease;
}

.memory-chip:hover {
  background: var(--psych-glass-strong);
  border-color: rgba(37, 99, 235, 0.30);
}

.memory-chip-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--psych-primary);
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.16);
}

.memory-chip-compact {
  display: none;
}

.current-conversation {
  max-width: 260px;
  min-width: 0;
  overflow: hidden;
  color: var(--psych-ink-muted);
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
  border-left: 1px solid var(--psych-glass-line);
}

.account-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 11px 3px 4px;
  border: 1px solid var(--psych-glass-line);
  border-radius: var(--psych-radius-pill);
  background: rgba(255, 255, 255, 0.55);
  transition: background-color 0.15s ease;
}

.account-chip:hover {
  background: var(--psych-glass-strong);
}

.account-avatar {
  display: inline-flex;
  width: 22px;
  height: 22px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--psych-primary), var(--psych-accent));
  color: #ffffff;
  font-size: 11px;
  font-weight: 800;
}

.account-name {
  max-width: 110px;
  overflow: hidden;
  color: var(--psych-ink);
  font-size: 12px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.account-link {
  padding: 5px 12px;
  border: 1px solid var(--psych-glass-line);
  border-radius: var(--psych-radius-pill);
  background: transparent;
  color: var(--psych-ink-secondary);
  cursor: pointer;
  font-size: 12px;
  white-space: nowrap;
  transition: background-color 0.15s ease, border-color 0.15s ease, color 0.15s ease;
}

.account-link:hover:not(:disabled) {
  background: var(--psych-primary-soft);
  border-color: rgba(37, 99, 235, 0.35);
  color: var(--psych-primary);
}

.account-link:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.admin-entry {
  border-color: rgba(37, 99, 235, 0.40);
  background: var(--psych-primary-soft);
  color: var(--psych-primary);
  font-weight: 700;
}

.admin-entry:hover:not(:disabled) {
  background: #dbe7fd;
  border-color: var(--psych-primary);
  color: var(--psych-primary);
}

.content-wrapper {
  display: grid;
  /* 高度必须是确定值，不能用 min-height：grid 的 auto 行会随会话内容撑高，
     整条 flex 高度链随之失效——chat-messages 的内部滚动永不触发，
     输入舱被推出视口（长会话要滚到页面最底才能输入）。
     flex 必须是 none：外层容器高度由内容决定时，flex-basis 0% 会覆盖掉
     这里的确定高度，让 wrapper 回退成内容高（移动端修复同样用了 flex: none）。
     56px = header 固定高度；页脚从折叠线以下开始，滚动页面可见。 */
  height: calc(100vh - 56px);
  flex: none;
  grid-template-columns: 280px minmax(0, 1fr);
}

.chat-area {
  display: flex;
  min-width: 0;
  padding: 16px;
  flex-direction: column;
  gap: 10px;
  overflow: hidden;
  /* 蓝色柔光渐变画布：消息列、输入舱、气泡、卡片全部浮在它上面，
     玻璃面的"透"透的就是这一层。 */
  background: var(--psych-gradient-canvas);
}

.chat-area :deep(.chat-container) {
  min-height: 0;
  flex: 1;
}

.mode-toolbar {
  display: flex;
  min-height: 46px;
  padding: 7px 12px;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  border: 1px solid var(--psych-glass-line);
  border-radius: var(--psych-radius-pill);
  background: var(--psych-glass);
  box-shadow: 0 2px 10px -4px var(--psych-shadow-glass);
  flex-shrink: 0;
}

.mode-toolbar p {
  margin: 0;
  color: var(--psych-ink-muted);
  font-size: 12px;
  line-height: 1.45;
  text-align: right;
}

/* 分段切换：玻璃轨道 + 激活态主色胶囊 */
.mode-switch {
  display: inline-grid;
  flex: 0 0 auto;
  grid-template-columns: repeat(2, 1fr);
  gap: 4px;
  padding: 3px;
  border: 1px solid var(--psych-glass-line);
  border-radius: var(--psych-radius-pill);
  background: rgba(255, 255, 255, 0.55);
}

.mode-switch button {
  min-width: 82px;
  padding: 6px 14px;
  border: 0;
  border-radius: var(--psych-radius-pill);
  background: transparent;
  color: var(--psych-ink-secondary);
  cursor: pointer;
  font-size: 13px;
  transition: background-color 0.2s ease, color 0.2s ease, box-shadow 0.2s ease;
}

.mode-switch button.active {
  background: var(--psych-primary);
  color: #ffffff;
  box-shadow: 0 2px 8px -2px var(--psych-primary-glow);
}

.mode-switch button:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.mode-switch button:focus-visible {
  outline: 2px solid var(--psych-primary);
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
  background: rgba(30, 41, 59, 0.38);
  backdrop-filter: blur(3px);
}

.modal-card {
  width: 100%;
  max-width: 400px;
  padding: 22px 24px;
  border: 1px solid var(--psych-glass-line);
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.92);
  box-shadow: 0 24px 60px rgba(15, 23, 42, 0.22);
}

.modal-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.modal-head h2 {
  margin: 0;
  color: var(--psych-ink);
  font-size: 17px;
  font-weight: 800;
}

.modal-close {
  padding: 2px 9px;
  border: 0;
  border-radius: var(--psych-radius-pill);
  background: transparent;
  color: var(--psych-ink-muted);
  cursor: pointer;
  font-size: 19px;
  line-height: 1;
}

.modal-close:hover:not(:disabled) {
  background: var(--psych-primary-soft);
  color: var(--psych-primary);
}

.modal-close:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}

.modal-text {
  margin: 0 0 14px;
  color: var(--psych-ink-secondary);
  font-size: 13px;
  line-height: 1.7;
}

.modal-error {
  margin: 0 0 14px;
  padding: 9px 12px;
  border: 1px solid #f3c6c6;
  border-radius: 10px;
  background: var(--psych-danger-soft);
  color: #b34242;
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
  color: var(--psych-ink-secondary);
  font-size: 12px;
  font-weight: 600;
}

.modal-field input {
  padding: 9px 12px;
  border: 1px solid #d5deea;
  border-radius: 10px;
  color: var(--psych-ink);
  font-size: 13px;
  transition: border-color 0.16s ease, box-shadow 0.16s ease;
}

.modal-field input::placeholder {
  color: #9aa7ba;
}

.modal-field input:focus {
  outline: none;
  border-color: var(--psych-primary);
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.14);
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
  border: 1px solid #d5deea;
  border-radius: var(--psych-radius-pill);
  background: rgba(255, 255, 255, 0.7);
  color: var(--psych-ink-secondary);
  cursor: pointer;
  font-size: 13px;
  transition: background-color 0.15s ease, border-color 0.15s ease, color 0.15s ease;
}

.ghost-button:hover:not(:disabled) {
  border-color: rgba(37, 99, 235, 0.4);
  background: var(--psych-primary-soft);
  color: var(--psych-primary);
}

.ghost-button:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.solid-button {
  padding: 9px 18px;
  border: 0;
  border-radius: var(--psych-radius-pill);
  background: linear-gradient(135deg, var(--psych-primary), var(--psych-accent));
  color: #ffffff;
  cursor: pointer;
  font-size: 13px;
  font-weight: 700;
  box-shadow: 0 3px 10px -3px var(--psych-primary-glow);
  transition: filter 0.15s ease, transform 0.15s ease;
}

.solid-button:hover:not(:disabled) {
  filter: brightness(1.06);
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
    /* 与下方 content-wrapper 的兜底 calc(100dvh - 48px) 保持同一契约 */
    height: 48px;
    padding: 0 14px;
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
    /* U8：优先用 JS 写入的真实可视高度（键盘弹出后已扣除遮挡），
       100dvh 只是 visualViewport 不可用时的兜底。 */
    height: var(--app-viewport-height, calc(100dvh - 48px));
    max-height: var(--app-viewport-height, calc(100dvh - 48px));
    min-height: 0;
    flex: none;
    flex-direction: column;
    overflow: hidden;
  }

  .chat-area {
    min-height: 0;
    flex: 1;
    padding: 10px;
    /* 键盘弹出后消息区自己滚，外壳不许滚——否则标题栏会被一起带走。 */
    overflow: hidden;
  }

  .chat-area :deep(.chat-container) {
    height: auto;
    min-height: 0;
  }

  /* U7：模式栏在窄屏必须常驻可见。原来它是普通流式块，聊天区一长就被挤出视口，
     用户根本找不到"深度思考"开关。sticky + 顶部吸附保证它永远在。 */
  .mode-toolbar {
    position: sticky;
    top: 0;
    z-index: 5;
    min-height: 0;
    padding: 6px;
    align-items: stretch;
    flex-direction: column;
    gap: 5px;
    flex-shrink: 0;
    /* 纵向堆叠不适合胶囊半径，换回常规大圆角 */
    border-radius: 16px;
  }

  /* 整行等宽，取消固定 min-width：窄到 320px 也只是按钮变窄，不会溢出被裁掉。 */
  .mode-switch {
    align-self: stretch;
    width: 100%;
  }

  .mode-switch button {
    min-width: 0;
    padding: 6px 10px;
  }

  /* 说明文字压到一行并允许省略，腾出的高度留给消息区；但按钮本身绝不隐藏。 */
  .mode-toolbar p {
    font-size: 11px;
    text-align: left;
    overflow: hidden;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
  }
}

@media (max-width: 480px) {
  .header {
    grid-template-columns: minmax(0, 1fr) minmax(62px, auto);
    height: 44px;
    padding: 0 10px;
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
    height: var(--app-viewport-height, calc(100dvh - 44px));
    max-height: var(--app-viewport-height, calc(100dvh - 44px));
  }
}
</style>
