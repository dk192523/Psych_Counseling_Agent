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
  background: #ffffff;
  border-right: 1px solid #dce5e5;
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
  border-bottom: 1px solid #e6ecec;
}

.history-heading h2 {
  margin: 0;
  color: #213536;
  font-size: 16px;
  line-height: 1.4;
}

.history-heading p {
  margin: 4px 0 0;
  color: #718080;
  font-size: 12px;
}

.new-conversation-button {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  gap: 4px;
  min-height: 34px;
  padding: 6px 10px;
  border: 1px solid #2c7a7b;
  border-radius: 6px;
  background: #ffffff;
  color: #246667;
  cursor: pointer;
  font-size: 13px;
}

.new-conversation-button:hover:not(:disabled) {
  background: #edf7f6;
}

.conversation-list {
  min-height: 0;
  padding-top: 8px;
  overflow-y: auto;
}

.conversation-item {
  position: relative;
  display: flex;
  margin-bottom: 4px;
  border-left: 3px solid transparent;
  background: transparent;
}

.conversation-item:hover,
.conversation-item:focus-within {
  background: #f2f7f7;
}

.conversation-item.active {
  border-left-color: #2c7a7b;
  background: #e8f3f2;
}

.conversation-select {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  align-items: flex-start;
  gap: 3px;
  padding: 10px 42px 10px 10px;
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
  color: #243737;
  font-size: 14px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conversation-count {
  flex: 0 0 auto;
  padding: 0 6px;
  border-radius: 9px;
  background: #e3eeee;
  color: #496565;
  font-size: 10px;
  font-variant-numeric: tabular-nums;
  line-height: 16px;
  white-space: nowrap;
}

.conversation-item.active .conversation-count {
  background: #cde5e3;
  color: #1f5f60;
}

.conversation-preview {
  color: #647474;
  font-size: 12px;
}

.conversation-time {
  color: #8a9696;
  font-size: 11px;
}

.delete-conversation-button {
  position: absolute;
  top: 8px;
  right: 6px;
  padding: 5px 6px;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: #8b5f5f;
  cursor: pointer;
  font-size: 11px;
  opacity: 0;
}

.conversation-item:hover .delete-conversation-button,
.conversation-item:focus-within .delete-conversation-button,
.conversation-item.active .delete-conversation-button {
  opacity: 1;
}

.delete-conversation-button:hover:not(:disabled) {
  background: #f8eaea;
  color: #8d3434;
}

.history-state,
.history-error {
  margin: 16px 4px;
  color: #788585;
  font-size: 13px;
  line-height: 1.6;
}

.history-error {
  padding: 8px;
  border-left: 3px solid #b75c5c;
  background: #fff4f4;
  color: #8d3434;
}

button:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

@media (max-width: 768px) {
  .history-shell {
    border-right: 0;
    border-bottom: 1px solid #dce5e5;
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
    background: #ffffff;
    color: #294344;
    cursor: pointer;
    font-size: 14px;
  }

  .history-count {
    min-width: 24px;
    margin-right: auto;
    padding: 1px 6px;
    border-radius: 10px;
    background: #e3eeee;
    color: #496565;
    text-align: center;
    font-size: 12px;
  }

  .history-panel {
    display: none;
    height: auto;
    max-height: 300px;
    padding: 12px;
    border-top: 1px solid #e6ecec;
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
