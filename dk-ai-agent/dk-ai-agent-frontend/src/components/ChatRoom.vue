<template>
  <div class="chat-container" :class="{ 'is-empty': isFreshConversation }">
    <!-- 空会话：Gemini 式居中问候，输入舱悬浮在画面中下方 -->
    <div v-if="isFreshConversation" class="chat-hero">
      <h2 class="hero-greeting psych-gradient-text">你好，我在听。</h2>
      <p class="hero-sub">慢慢说，我在这儿。</p>
    </div>

    <!-- 聊天记录区域 -->
    <div v-else ref="messagesContainer" class="chat-messages">
      <div class="thread-col">
        <div
          v-for="(msg, index) in visibleMessages"
          :key="msg.id ?? index"
          class="message-wrapper psych-message-enter"
        >
          <!-- AI消息 -->
          <div v-if="!msg.isUser" class="message ai-message" :class="[msg.type]">
            <div class="avatar ai-avatar">
              <AiAvatarFallback :type="aiType" />
            </div>
            <div class="message-bubble ai-bubble">
              <div class="message-content">
                <DeepThinkingIndicator
                  v-if="connectionStatus === 'connecting' && index === visibleMessages.length - 1 && thinkingState?.active && !msg.content"
                  :phase="thinkingState.phase"
                  :label="thinkingState.label"
                  :started-at="thinkingState.startedAt"
                  :fallback="thinkingState.fallback"
                />
                <div class="markdown-body" v-html="renderMarkdown(msg.content)"></div>
                <span
                  v-if="connectionStatus === 'connecting' && index === visibleMessages.length - 1 && !thinkingState?.active"
                  class="typing-indicator"
                >▋</span>
              </div>
              <div class="message-time">{{ formatTime(msg.time) }}</div>
            </div>
          </div>

          <!-- 用户消息 -->
          <div v-else class="message user-message" :class="[msg.type]">
            <div class="message-bubble user-bubble">
              <div class="message-content">{{ msg.content }}</div>
              <div class="message-time">{{ formatTime(msg.time) }}</div>
            </div>
            <div class="avatar user-avatar">
              <div class="avatar-placeholder">我</div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 输入区域：玻璃胶囊复合输入舱 -->
    <div class="composer-area">
      <!-- U9：上一轮发送中断后的手动重发入口。重发沿用原 clientMsgId，后端幂等去重，
           不会重复归档用户消息。 -->
      <div v-if="retryableTurn" class="chat-retry-bar" role="status">
        <span class="retry-text">上一条消息发送中断</span>
        <button type="button" class="retry-button" @click="emit('retry-send')">重新发送</button>
      </div>

      <div class="composer-shell" :class="{ 'is-busy': connectionStatus === 'connecting' }">
        <div class="composer-glow" aria-hidden="true"></div>
        <div class="composer">
          <textarea
            ref="inputBox"
            v-model="inputMessage"
            :maxlength="MAX_INPUT_CHARS"
            rows="1"
            @keydown="handleKeydown"
            @input="autoGrow"
            :placeholder="placeholderText"
            class="composer-input"
            :disabled="inputDisabled || connectionStatus === 'connecting'"
          ></textarea>
          <button
            @click="sendMessage"
            class="composer-send"
            :class="{ 'is-ready': inputMessage.trim() && !(inputDisabled || connectionStatus === 'connecting') }"
            :disabled="inputDisabled || connectionStatus === 'connecting' || !inputMessage.trim()"
            aria-label="发送"
          >
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path d="M12 19V5" />
              <path d="M5 12l7-7 7 7" />
            </svg>
          </button>
        </div>
      </div>
      <!-- 只在接近上限时出现：平时不给"还剩多少字"这种无谓的计量压力，
           但快撞墙时必须提前告知，否则用户会以为自己敲的字丢了。 -->
      <p v-if="showCounter" class="input-counter" :class="{ 'is-at-limit': remainingChars === 0 }">
        {{ remainingChars === 0 ? `已达上限 ${MAX_INPUT_CHARS} 字，可分几次说` : `还可输入 ${remainingChars} 字` }}
      </p>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, nextTick, watch, computed } from 'vue'
import DOMPurify from 'dompurify'
import MarkdownIt from 'markdown-it'
import AiAvatarFallback from './AiAvatarFallback.vue'
import DeepThinkingIndicator from './DeepThinkingIndicator.vue'

const markdown = new MarkdownIt({
  html: false,
  breaks: true,
  linkify: true,
  typographer: false
})

const defaultLinkOpen = markdown.renderer.rules.link_open || ((tokens, idx, options, env, self) => self.renderToken(tokens, idx, options, env, self))
markdown.renderer.rules.link_open = (tokens, idx, options, env, self) => {
  tokens[idx].attrSet('target', '_blank')
  tokens[idx].attrSet('rel', 'noopener noreferrer')
  return defaultLinkOpen(tokens, idx, options, env, self)
}

const props = defineProps({
  messages: {
    type: Array,
    default: () => []
  },
  connectionStatus: {
    type: String,
    default: 'disconnected'
  },
  inputDisabled: {
    type: Boolean,
    default: false
  },
  thinkingState: {
    type: Object,
    default: null
  },
  // 中断轮次信息（由 PsychMaster 维护）；非空时输入区上方显示"重新发送"。
  retryableTurn: {
    type: Object,
    default: null
  },
  aiType: {
    type: String,
    default: 'psych'
  }
})

const emit = defineEmits(['send-message', 'retry-send'])

// 与后端 ConversationHistoryService 的入口截断上限保持一致。前端 maxlength 只是即时反馈，
// 真正的边界在后端——浏览器端的限制随时能被绕过。
const MAX_INPUT_CHARS = 4000
// textarea 自增长的行数上限：超过就内部滚动，不能让输入区无限吃掉消息区的高度。
const MAX_INPUT_ROWS = 6

const inputMessage = ref('')
const messagesContainer = ref(null)
const inputBox = ref(null)

// 欢迎消息（id 以 welcome- 开头）只是占位文案：空会话渲染 hero 问候，不进消息列；
// 首条消息发出后它也不再以气泡形式出现在聊天顶部。
const visibleMessages = computed(() =>
  props.messages.filter(message => !String(message.id || '').startsWith('welcome-'))
)
const isFreshConversation = computed(() => visibleMessages.value.length === 0)

const remainingChars = computed(() => MAX_INPUT_CHARS - inputMessage.value.length)
const showCounter = computed(() => remainingChars.value <= 200)

const placeholderText = computed(() => {
  if (props.aiType === 'psych') {
    return '说说最近最困扰你的事…（Enter 发送，Shift+Enter 换行）'
  }
  return '请输入消息...'
})

const renderMarkdown = (content) => {
  return DOMPurify.sanitize(markdown.render(content || ''))
}

// 输入框随内容自增长，到 MAX_INPUT_ROWS 行为止；之后内部滚动。
// 直接读 scrollHeight 前必须先把 height 归零，否则测到的是"当前高度"而永远不会回缩。
const autoGrow = () => {
  const el = inputBox.value
  if (!el) return
  el.style.height = 'auto'
  const lineHeight = parseFloat(getComputedStyle(el).lineHeight) || 22
  const maxHeight = lineHeight * MAX_INPUT_ROWS
  el.style.height = `${Math.min(el.scrollHeight, maxHeight)}px`
  el.style.overflowY = el.scrollHeight > maxHeight ? 'auto' : 'hidden'
}

// Enter 发送、Shift+Enter 换行。
// isComposing 这道判断是中文输入的关键：拼音候选框里按 Enter 是"确认选字"，
// 原实现的 @keydown.enter.prevent 会把它当成发送，于是半截未上屏的内容直接飞出去。
// keyCode 229 是部分 Android 输入法在组合态下上报的值，isComposing 在那里并不可靠。
const handleKeydown = (event) => {
  if (event.key !== 'Enter') return
  if (event.isComposing || event.keyCode === 229) return
  if (event.shiftKey || event.ctrlKey || event.metaKey || event.altKey) return

  event.preventDefault()
  sendMessage()
}

// 发送消息
const sendMessage = () => {
  if (!inputMessage.value.trim()) return
  if (props.inputDisabled || props.connectionStatus === 'connecting') return

  emit('send-message', inputMessage.value)
  inputMessage.value = ''
  nextTick(autoGrow)
}

// 格式化时间
const formatTime = (timestamp) => {
  const date = new Date(timestamp)
  return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

// 自动滚动到底部
const scrollToBottom = async () => {
  await nextTick()
  if (messagesContainer.value) {
    messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
  }
}

// 监听消息变化与内容变化，自动滚动
watch(() => props.messages.length, () => {
  scrollToBottom()
})

watch(() => props.messages.map(m => m.content).join(''), () => {
  scrollToBottom()
})

watch(() => `${props.thinkingState?.phase || ''}:${props.thinkingState?.label || ''}`, () => {
  scrollToBottom()
})

onMounted(() => {
  scrollToBottom()
  autoGrow()
})
</script>

<style scoped>
/*
 * 画布透明：蓝色 radial 渐变由 PsychMaster 的 chat-area 提供，
 * 消息列、输入舱、气泡全部浮在那层渐变上，玻璃的"透"透的就是它。
 * C5 保持纯 flex：消息区 flex:1 + min-height:0，输入舱 flex-shrink:0。
 */
.chat-container {
  display: flex;
  flex-direction: column;
  height: 70vh;
  min-height: 600px;
  overflow: hidden;
}

/* ---------- 空会话 hero：居中问候 + 悬浮输入 ---------- */
.chat-container.is-empty {
  justify-content: center;
  padding: 24px 16px calc(28px + env(safe-area-inset-bottom, 0px));
}

.chat-hero {
  padding: 0 20px;
  margin-bottom: 34px;
  text-align: center;
}

.hero-greeting {
  margin: 0;
  font-size: clamp(26px, 4.4vw, 36px);
  font-weight: 700;
  letter-spacing: 0.01em;
}

.hero-sub {
  margin: 14px 0 0;
  color: var(--psych-ink-muted);
  font-size: 15px;
}

/* ---------- 消息列 ---------- */
.chat-messages {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 18px 16px 6px;
  display: flex;
  flex-direction: column;
}

/* 消息列与输入舱同宽对齐：气泡永远不长过输入舱的外沿。 */
.thread-col {
  width: 100%;
  max-width: 820px;
  margin-inline: auto;
}

.message-wrapper {
  margin-bottom: 16px;
  display: flex;
  flex-direction: column;
  width: 100%;
}

.message {
  display: flex;
  align-items: flex-start;
  max-width: 85%;
  margin-bottom: 8px;
}

.user-message {
  margin-left: auto; /* 用户消息靠右 */
  flex-direction: row; /* 正常顺序，先气泡后头像 */
}

.ai-message {
  margin-right: auto; /* AI消息靠左 */
}

.avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  overflow: hidden;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
}

.user-avatar {
  margin-left: 10px;
}

.ai-avatar {
  margin-right: 10px;
}

.avatar-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--psych-primary-soft);
  color: var(--psych-primary);
  font-size: 13px;
  font-weight: 700;
}

/* ---------- 玻璃气泡 ---------- */
.message-bubble {
  padding: 11px 14px;
  position: relative;
  word-wrap: break-word;
  max-width: 100%;
  min-width: 0;
  border-radius: 20px;
}

.ai-bubble {
  background: var(--psych-glass-raised);
  border: 1px solid var(--psych-glass-line);
  box-shadow: 0 2px 10px -4px var(--psych-shadow-glass);
  border-bottom-left-radius: 6px;
  color: var(--psych-ink);
  text-align: left;
}

.user-bubble {
  /* 主色玻璃：白字对比度由 --psych-glass-primary 的透明度档位保证。 */
  background: var(--psych-glass-primary);
  border: 1px solid var(--psych-glass-line-primary);
  box-shadow: 0 2px 10px -4px var(--psych-shadow-glass);
  border-bottom-right-radius: 6px;
  color: #ffffff;
  text-align: left;
}

.message-content {
  font-size: 16px;
  line-height: 1.6;
  min-width: 0;
}

.user-message .message-content {
  white-space: pre-wrap;
}

.message-time {
  font-size: 11px;
  margin-top: 5px;
  text-align: right;
  opacity: 0.62;
}

.user-bubble .message-time {
  color: rgba(255, 255, 255, 0.85);
  opacity: 1;
}

/* ---------- Markdown ---------- */
.markdown-body {
  min-width: 0;
  max-width: 100%;
  overflow-wrap: anywhere;
  line-height: 1.7;
}

.markdown-body :deep(p),
.markdown-body :deep(ul),
.markdown-body :deep(ol),
.markdown-body :deep(blockquote),
.markdown-body :deep(pre) {
  margin: 0 0 12px;
}

.markdown-body :deep(:last-child) {
  margin-bottom: 0;
}

.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3),
.markdown-body :deep(h4) {
  margin: 18px 0 10px;
  line-height: 1.35;
  font-weight: 700;
}

.markdown-body :deep(h1) {
  font-size: 1.28em;
}

.markdown-body :deep(h2) {
  padding-bottom: 6px;
  border-bottom: 1px solid var(--psych-glass-line);
  font-size: 1.18em;
}

.markdown-body :deep(h3) {
  font-size: 1.08em;
}

.markdown-body :deep(h4) {
  font-size: 1em;
}

.markdown-body :deep(ul),
.markdown-body :deep(ol) {
  padding-left: 22px;
}

.markdown-body :deep(ul) {
  list-style: disc outside;
}

.markdown-body :deep(ol) {
  list-style: decimal outside;
}

.markdown-body :deep(li + li) {
  margin-top: 4px;
}

.markdown-body :deep(blockquote) {
  padding: 8px 12px;
  border-left: 3px solid var(--psych-primary);
  border-radius: 0 10px 10px 0;
  background: rgba(37, 99, 235, 0.06);
  color: var(--psych-ink-secondary);
}

.markdown-body :deep(code) {
  padding: 2px 5px;
  border-radius: 6px;
  background: rgba(37, 99, 235, 0.08);
  color: #1d4ed8;
  font-family: Consolas, "Courier New", monospace;
  font-size: 0.9em;
}

.markdown-body :deep(pre) {
  max-width: 100%;
  padding: 12px;
  overflow-x: auto;
  border: 1px solid var(--psych-glass-line);
  border-radius: 12px;
  background: rgba(248, 250, 253, 0.9);
}

.markdown-body :deep(pre code) {
  padding: 0;
  background: transparent;
  color: inherit;
}

.markdown-body :deep(a) {
  color: var(--psych-primary);
  text-decoration: underline;
  text-underline-offset: 2px;
  overflow-wrap: anywhere;
}

.markdown-body :deep(strong) {
  color: #16233b;
  font-weight: 700;
}

.markdown-body :deep(table) {
  display: block;
  width: max-content;
  max-width: 100%;
  margin: 0 0 12px;
  overflow-x: auto;
  border-collapse: collapse;
}

.markdown-body :deep(th),
.markdown-body :deep(td) {
  padding: 7px 10px;
  border: 1px solid #dbe3ef;
  text-align: left;
  white-space: nowrap;
}

.markdown-body :deep(th) {
  background: rgba(37, 99, 235, 0.07);
}

.markdown-body :deep(hr) {
  margin: 14px 0;
  border: 0;
  border-top: 1px solid #dbe3ef;
}

/* ---------- 输入舱：玻璃胶囊 + 呼吸光晕 ---------- */
.composer-area {
  flex-shrink: 0;
  width: 100%;
  max-width: 852px; /* 与消息列 820px + 两侧 16px 对齐 */
  margin: 0 auto;
  padding: 8px 16px calc(12px + env(safe-area-inset-bottom, 0px));
  box-sizing: border-box;
}

.chat-container.is-empty .composer-area {
  max-width: 680px;
}

/* 重发条：琥珀玻璃，贴在输入舱上方 */
.chat-retry-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
  padding: 8px 14px;
  border: 1px solid #f0e2b6;
  border-radius: 14px;
  background: rgba(254, 248, 227, 0.88);
}

.retry-text {
  font-size: 13px;
  color: var(--psych-warning);
}

.retry-button {
  flex-shrink: 0;
  border: 1px solid #e3b23c;
  border-radius: var(--psych-radius-pill);
  padding: 4px 14px;
  background: #fffbef;
  color: var(--psych-warning);
  font-size: 13px;
  cursor: pointer;
  transition: background-color 0.2s ease;
}

.retry-button:hover {
  background: #fdf3d3;
}

/* 光晕：常驻极淡环境光把胶囊从渐变底上托起来，聚焦/生成中时呼吸 */
.composer-shell {
  position: relative;
}

.composer-glow {
  position: absolute;
  inset: -6px -4px -10px;
  border-radius: var(--psych-radius-pill);
  background: linear-gradient(
    90deg,
    var(--psych-primary) 0%,
    var(--psych-accent) 55%,
    var(--psych-primary-light) 100%
  );
  filter: blur(16px);
  opacity: 0.14;
  pointer-events: none;
  transition: opacity 0.4s ease;
}

.composer-shell:focus-within .composer-glow,
.composer-shell.is-busy .composer-glow {
  animation: composer-breathe 3s ease-in-out infinite;
}

@keyframes composer-breathe {
  0%, 100% { opacity: 0.28; transform: scale(0.98); }
  50% { opacity: 0.55; transform: scale(1.01); }
}

.composer {
  position: relative;
  display: flex;
  align-items: flex-end;
  gap: 10px;
  padding: 9px 9px 9px 18px;
  border-radius: 26px;
  background: var(--psych-glass);
  border: 1px solid var(--psych-glass-line);
  box-shadow: 0 2px 14px -6px var(--psych-shadow-glass);
  transition: background-color 0.3s ease, border-color 0.3s ease, box-shadow 0.3s ease;
}

.composer-shell:focus-within .composer,
.composer-shell.is-busy .composer {
  background: var(--psych-glass-strong);
  border-color: rgba(37, 99, 235, 0.35);
  box-shadow: 0 6px 22px -8px var(--psych-primary-glow);
}

.composer-input {
  flex: 1;
  min-width: 0;
  border: 0;
  outline: none;
  background: transparent;
  resize: none;
  padding: 10px 0;
  color: var(--psych-ink);
  font-family: inherit;
  font-size: 16px;
  line-height: 22px;
  /* 高度由 autoGrow 按行数算，这里只给首屏一行的初值，不再写 max-height 死值。 */
  height: 42px;
  overflow-y: hidden;
  scrollbar-width: none;
  -ms-overflow-style: none;
}

.composer-input::-webkit-scrollbar {
  display: none;
}

.composer-input::placeholder {
  color: var(--psych-ink-muted);
  opacity: 0.75;
}

.composer-input:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

/* 发送按钮：空态是灰色小圆，有内容时点亮成蓝→天蓝渐变圆 */
.composer-send {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: none;
  width: 40px;
  height: 40px;
  border: 0;
  border-radius: var(--psych-radius-pill);
  background: #e6ebf4;
  color: #93a1b8;
  cursor: not-allowed;
  transition: background 0.25s ease, color 0.25s ease, box-shadow 0.25s ease, transform 0.15s ease;
}

.composer-send.is-ready {
  background: linear-gradient(135deg, var(--psych-primary), var(--psych-accent));
  color: #ffffff;
  cursor: pointer;
  box-shadow: 0 3px 12px -3px var(--psych-primary-glow);
}

.composer-send.is-ready:hover {
  transform: translateY(-1px);
}

.composer-send.is-ready:active {
  transform: scale(0.93);
}

.composer-send:disabled {
  opacity: 0.75;
}

.input-counter {
  margin: 6px 4px 0;
  color: var(--psych-ink-muted);
  font-size: 12px;
  text-align: right;
}

.input-counter.is-at-limit {
  color: var(--psych-warning);
  font-weight: 600;
}

.typing-indicator {
  display: inline-block;
  animation: blink 0.7s infinite;
  margin-left: 2px;
  color: var(--psych-primary);
}

@keyframes blink {
  0% { opacity: 0; }
  50% { opacity: 1; }
  100% { opacity: 0; }
}

/* ---------- 响应式设计 ---------- */
@media (max-width: 768px) {
  .message {
    max-width: 95%;
  }

  .message-content {
    font-size: 15px;
  }

  .chat-container.is-empty {
    padding: 20px 14px calc(20px + env(safe-area-inset-bottom, 0px));
  }

  .hero-greeting {
    font-size: clamp(23px, 6vw, 28px);
  }

  .hero-sub {
    font-size: 14px;
  }

  .composer-area {
    padding: 6px 10px calc(10px + env(safe-area-inset-bottom, 0px));
  }

  .composer {
    padding: 7px 7px 7px 14px;
    gap: 8px;
  }

  .composer-input {
    padding: 8px 0;
    height: 38px;
    font-size: 16px; /* 保持 16px：小于 16px 的输入框在 iOS 上聚焦会自动放大页面 */
  }

  .composer-send {
    width: 38px;
    height: 38px;
  }

  .chat-messages {
    padding: 14px 10px 4px;
  }
}

@media (max-width: 480px) {
  .avatar {
    width: 32px;
    height: 32px;
  }

  .message-bubble {
    padding: 10px 12px;
  }

  .message-content {
    font-size: 14px;
  }

  .thread-col {
    max-width: 100%;
  }
}

/* 无障碍：系统开启"减弱动效"时停掉呼吸光晕与入场动画 */
@media (prefers-reduced-motion: reduce) {
  .composer-glow {
    animation: none !important;
  }

  .psych-message-enter {
    animation: none;
  }
}
</style>
