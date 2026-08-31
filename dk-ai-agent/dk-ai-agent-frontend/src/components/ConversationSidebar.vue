<template>
  <section class="history-shell" aria-label="历史会话">
    <button
      class="mobile-history-toggle"
      type="button"
      :aria-expanded="mobileOpen"
      aria-controls="conversation-history-panel"
      @click="$emit('toggle-mobile')"
    >
      <span>历史会话</span>
      <span class="history-count">{{ conversations.length }}</span>
      <span aria-hidden="true">{{ mobileOpen ? '收起' : '展开' }}</span>
    </button>

    <aside
      id="conversation-history-panel"
      class="history-panel"
      :class="{ 'mobile-open': mobileOpen }"
    >
      <div class="history-heading">
        <div>
          <h2>历史会话</h2>
          <p>选择一段记录继续聊</p>
        </div>
        <button
          class="new-conversation-button"
          type="button"
          :disabled="disabled"
          title="新建会话"
          aria-label="新建会话"
          @click="$emit('create')"
        >
          <span aria-hidden="true">＋</span>
          <span>新建</span>
        </button>
      </div>

      <p v-if="error" class="history-error" role="status">{{ error }}</p>
      <div v-if="loading" class="history-state">正在读取会话...</div>
      <div v-else-if="conversations.length === 0" class="history-state">
        还没有历史会话
      </div>

      <div v-else class="conversation-list">
        <article
          v-for="conversation in conversations"
          :key="conversation.id"
          class="conversation-item"
          :class="{ active: conversation.id === activeId }"
        >
          <button
            class="conversation-select"
            type="button"
            :disabled="disabled"
            :aria-current="conversation.id === activeId ? 'true' : undefined"
            @click="$emit('select', conversation.id)"
          >
            <span class="conversation-title-row">
              <span class="conversation-title">{{ conversation.title || '新会话' }}</span>
              <span
                class="conversation-count"
                :title="`共 ${conversation.messageCount ?? 0} 条消息，原文保留上限 ${conversation.maxMessages ?? 0} 条`"
              >{{ formatMessageCount(conversation) }}</span>
            </span>
            <span class="conversation-preview">{{ conversation.preview || '暂时还没有消息' }}</span>
            <time class="conversation-time" :datetime="conversation.updatedAt">
              {{ formatUpdatedAt(conversation.updatedAt) }}
            </time>
          </button>
          <button
            class="delete-conversation-button"
            type="button"
            :disabled="disabled"
            title="删除会话"
            :aria-label="`删除会话：${conversation.title || '新会话'}`"
            @click.stop="$emit('delete', conversation)"
          >
            删除
          </button>
        </article>
      </div>
    </aside>
  </section>
</template>

<script setup>
defineProps({
  conversations: {
    type: Array,
    default: () => []
  },
  activeId: {
    type: String,
    default: ''
  },
  loading: {
    type: Boolean,
    default: false
  },
  disabled: {
    type: Boolean,
    default: false
  },
  error: {
    type: String,
    default: ''
  },
  mobileOpen: {
    type: Boolean,
    default: false
  }
})

defineEmits(['create', 'select', 'delete', 'toggle-mobile'])

const formatUpdatedAt = (value) => {
  if (!value) return '刚刚'

  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''

  const now = new Date()
  if (date.toDateString() === now.toDateString()) {
    return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  }

  return date.toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric' })
}

const formatMessageCount = (conversation) => {
  const count = Number(conversation.messageCount ?? 0)
  const max = Number(conversation.maxMessages ?? 0)
  return max > 0 ? `${count}/${max}` : `${count}`
}
</script>

<style scoped>
.history-shell {
  min-width: 0;
  /* 玻璃面板：透出页面底色，与聊天区渐变连成一体 */
  background: rgba(255, 255, 255, 0.5);
  border-right: 1px solid var(--psych-glass-line);
}

.mobile-history-toggle {
  display: none;
}

.history-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  padding: 18px 14px;
  box-sizing: border-box;
}

.history-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 0 4px 14px;
  border-bottom: 1px solid var(--psych-glass-line);
}

.history-heading h2 {
  margin: 0;
  color: var(--psych-ink);
  font-size: 16px;
  line-height: 1.4;
}

.history-heading p {
  margin: 4px 0 0;
  color: var(--psych-ink-muted);
  font-size: 12px;
}

.new-conversation-button {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  gap: 4px;
  min-height: 34px;
  padding: 6px 14px;
  border: 1px solid rgba(37, 99, 235, 0.40);
  border-radius: var(--psych-radius-pill);
  background: var(--psych-primary-soft);
  color: var(--psych-primary);
  cursor: pointer;
  font-size: 13px;
  font-weight: 600;
  transition: background-color 0.2s ease, border-color 0.2s ease, box-shadow 0.2s ease;
}

.new-conversation-button:hover:not(:disabled) {
  background: #dbe7fd;
  border-color: var(--psych-primary);
  box-shadow: 0 3px 10px -3px var(--psych-primary-glow);
}

.conversation-list {
  min-height: 0;
  padding-top: 10px;
  overflow-y: auto;
}

/* 圆角卡片条目：激活态用主色软底标出，不再用左侧硬边条 */
.conversation-item {
  position: relative;
  display: flex;
  margin-bottom: 4px;
  border-radius: 12px;
  background: transparent;
  transition: background-color 0.15s ease;
}

.conversation-item:hover,
.conversation-item:focus-within {
  background: rgba(255, 255, 255, 0.75);
}

.conversation-item.active {
  background: var(--psych-primary-soft);
}

.conversation-select {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  align-items: flex-start;
  gap: 3px;
  padding: 10px 42px 10px 12px;
  border: 0;
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
}

.conversation-title-row {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 6px;
}

.conversation-preview {
  display: block;
  width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conversation-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  color: var(--psych-ink);
  font-size: 14px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conversation-item.active .conversation-title {
  color: var(--psych-primary);
}

.conversation-count {
  flex: 0 0 auto;
  padding: 0 7px;
  border-radius: var(--psych-radius-pill);
  background: rgba(37, 99, 235, 0.10);
  color: var(--psych-ink-muted);
  font-size: 10px;
  font-variant-numeric: tabular-nums;
  line-height: 17px;
  white-space: nowrap;
}

.conversation-item.active .conversation-count {
  background: rgba(37, 99, 235, 0.16);
  color: var(--psych-primary);
}

.conversation-preview {
  color: var(--psych-ink-muted);
  font-size: 12px;
}

.conversation-time {
  color: #93a1b8;
  font-size: 11px;
}

.delete-conversation-button {
  position: absolute;
  top: 8px;
  right: 6px;
  padding: 5px 8px;
  border: 0;
  border-radius: var(--psych-radius-pill);
  background: transparent;
  color: #a06a6a;
  cursor: pointer;
  font-size: 11px;
  opacity: 0;
  transition: background-color 0.15s ease, color 0.15s ease;
}

.conversation-item:hover .delete-conversation-button,
.conversation-item:focus-within .delete-conversation-button,
.conversation-item.active .delete-conversation-button {
  opacity: 1;
}

.delete-conversation-button:hover:not(:disabled) {
  background: var(--psych-danger-soft);
  color: var(--psych-danger);
}

.history-state,
.history-error {
  margin: 16px 4px;
  color: var(--psych-ink-muted);
  font-size: 13px;
  line-height: 1.6;
}

.history-error {
  padding: 8px 10px;
  border-radius: 10px;
  border: 1px solid #f3c6c6;
  background: var(--psych-danger-soft);
  color: #b34242;
}

button:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

@media (max-width: 768px) {
  .history-shell {
    border-right: 0;
    border-bottom: 1px solid var(--psych-glass-line);
  }

  .mobile-history-toggle {
    display: flex;
    width: 100%;
    min-height: 44px;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
    padding: 10px 14px;
    border: 0;
    background: transparent;
    color: var(--psych-ink);
    cursor: pointer;
    font-size: 14px;
  }

  .history-count {
    min-width: 24px;
    margin-right: auto;
    padding: 1px 7px;
    border-radius: var(--psych-radius-pill);
    background: rgba(37, 99, 235, 0.10);
    color: var(--psych-ink-muted);
    text-align: center;
    font-size: 12px;
  }

  .history-panel {
    display: none;
    height: auto;
    max-height: 300px;
    padding: 12px;
    border-top: 1px solid var(--psych-glass-line);
  }

  .history-panel.mobile-open {
    display: flex;
  }

  .conversation-list {
    max-height: 210px;
  }

  .delete-conversation-button {
    opacity: 1;
  }
}
</style>
