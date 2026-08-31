<template>
  <section v-if="digest" class="memory-card" aria-label="长期记忆摘要">
    <button
      class="memory-toggle"
      type="button"
      :aria-expanded="open"
      aria-controls="memory-digest-body"
      @click="open = !open"
    >
      <span class="memory-title">
        <span class="memory-dot" aria-hidden="true"></span>
        长期记忆 · 由 AI 自动整合
      </span>
      <span class="memory-meta">{{ coveredSummary }}</span>
      <span class="memory-chevron" :class="{ open }" aria-hidden="true">▾</span>
    </button>
    <div v-if="open" id="memory-digest-body" class="memory-body">
      <!-- digest 一律按纯文本渲染（pre-wrap），不走 v-html，杜绝注入 -->
      <p class="memory-digest">{{ digest }}</p>
    </div>
  </section>
</template>

<script setup>
import { computed, ref } from 'vue'

const props = defineProps({
  memory: {
    type: Object,
    default: () => null
  }
})

const open = ref(false)

const digest = computed(() => (props.memory?.digest || '').trim())

const formatUpdatedAt = (value) => {
  if (!value) return ''

  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''

  const now = new Date()
  if (date.toDateString() === now.toDateString()) {
    return `今天 ${date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })}`
  }

  return date.toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric' })
}

const coveredSummary = computed(() => {
  const digestedCount = Number(props.memory?.digestedCount || 0)
  const digestChars = Number(props.memory?.digestChars || 0)
  const updatedAt = formatUpdatedAt(props.memory?.updatedAt)

  const parts = [`覆盖前 ${digestedCount} 条消息`, `${digestChars} 字`]
  if (updatedAt) parts.push(`更新于${updatedAt}`)
  return parts.join(' · ')
})
</script>

<style scoped>
.memory-card {
  flex-shrink: 0;
  border: 1px solid var(--psych-glass-line);
  border-radius: 14px;
  background: var(--psych-glass);
  box-shadow: 0 2px 10px -4px var(--psych-shadow-glass);
  overflow: hidden;
}

.memory-toggle {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 10px;
  padding: 9px 13px;
  border: 0;
  background: transparent;
  color: var(--psych-primary);
  cursor: pointer;
  text-align: left;
  transition: background-color 0.15s ease;
}

.memory-toggle:hover {
  background: rgba(255, 255, 255, 0.55);
}

.memory-toggle:focus-visible {
  outline: 2px solid var(--psych-primary);
  outline-offset: -2px;
}

.memory-title {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.02em;
}

.memory-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--psych-primary);
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.16);
}

.memory-meta {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  color: var(--psych-ink-muted);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.memory-chevron {
  flex: 0 0 auto;
  color: var(--psych-primary);
  font-size: 12px;
  transform: rotate(-90deg);
  transition: transform 0.2s ease;
}

.memory-chevron.open {
  transform: rotate(0deg);
}

.memory-body {
  border-top: 1px dashed var(--psych-glass-line);
  background: rgba(255, 255, 255, 0.65);
}

.memory-digest {
  max-height: 220px;
  margin: 0;
  padding: 12px 14px;
  overflow-y: auto;
  color: var(--psych-ink-secondary);
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

@media (max-width: 768px) {
  .memory-toggle {
    gap: 8px;
    padding: 7px 10px;
  }

  .memory-title {
    font-size: 11px;
  }

  .memory-meta {
    font-size: 10px;
  }

  .memory-digest {
    max-height: 180px;
    padding: 10px 12px;
    font-size: 12px;
  }
}
</style>
