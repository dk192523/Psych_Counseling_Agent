<template>
  <div
    class="deep-thinking-indicator"
    :class="{ 'is-fallback': isFallback }"
    role="status"
    aria-live="polite"
  >
    <div class="thinking-heading">
      <span class="thinking-mark" aria-hidden="true">
        <span></span>
        <span></span>
        <span></span>
      </span>
      <strong>{{ displayLabel }}</strong>
      <span class="elapsed-time">{{ elapsedSeconds }} 秒</span>
    </div>

    <div v-if="!isFallback" class="stage-track" aria-hidden="true">
      <span
        v-for="(stage, index) in stages"
        :key="stage.phase"
        class="stage-segment"
        :class="{
          'is-complete': index < activeStageIndex,
          'is-active': index === activeStageIndex
        }"
      ></span>
    </div>

    <p class="thinking-note">
      {{ isFallback ? '不会丢失本轮内容，系统正在使用原有稳定链路继续。' : '只展示执行阶段，不展示模型内部思维链。' }}
    </p>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'

const props = defineProps({
  phase: {
    type: String,
    default: 'planning'
  },
  label: {
    type: String,
    default: ''
  },
  startedAt: {
    type: Number,
    default: () => Date.now()
  },
  fallback: {
    type: Boolean,
    default: false
  }
})

const stages = [
  { phase: 'planning', label: '正在梳理问题与检索方向…' },
  { phase: 'retrieving', label: '正在查找真正相关的材料…' },
  { phase: 'grading', label: '正在核对材料与当前情况…' },
  { phase: 'answering', label: '正在组织更贴合你的回应…' }
]

const now = ref(Date.now())
const timer = window.setInterval(() => {
  now.value = Date.now()
}, 1000)

const isFallback = computed(() => props.fallback || ['fallback', 'safety'].includes(props.phase))
const activeStageIndex = computed(() => {
  const index = stages.findIndex(stage => stage.phase === props.phase)
  return index === -1 ? 0 : index
})
const displayLabel = computed(() => {
  if (props.label) return props.label
  if (isFallback.value) return '正在切换到稳妥模式…'
  return stages[activeStageIndex.value]?.label || stages[0].label
})
const elapsedSeconds = computed(() => Math.max(0, Math.floor((now.value - props.startedAt) / 1000)))

onBeforeUnmount(() => window.clearInterval(timer))
</script>

<style scoped>
.deep-thinking-indicator {
  width: min(420px, 72vw);
  padding: 4px 2px 2px;
  color: #1e3a8a;
}

.thinking-heading {
  display: grid;
  grid-template-columns: 22px minmax(0, 1fr) auto;
  gap: 9px;
  align-items: center;
}

.thinking-heading strong {
  font-size: 14px;
  font-weight: 650;
}

.thinking-mark {
  display: flex;
  height: 18px;
  align-items: center;
  justify-content: center;
  gap: 2px;
}

.thinking-mark span {
  width: 3px;
  height: 7px;
  border-radius: 2px;
  background: var(--psych-primary);
  animation: thinking-bars 1s ease-in-out infinite;
}

.thinking-mark span:nth-child(2) {
  animation-delay: 0.16s;
}

.thinking-mark span:nth-child(3) {
  animation-delay: 0.32s;
}

.elapsed-time {
  color: var(--psych-ink-muted);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.stage-track {
  display: grid;
  margin-top: 11px;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 5px;
}

.stage-segment {
  position: relative;
  height: 3px;
  border-radius: 2px;
  overflow: hidden;
  background: rgba(37, 99, 235, 0.18);
}

.stage-segment.is-complete {
  background: var(--psych-primary);
}

.stage-segment.is-active::after {
  position: absolute;
  inset: 0;
  border-radius: inherit;
  background: var(--psych-primary);
  content: '';
  transform-origin: left;
  animation: stage-scan 1.25s ease-in-out infinite;
}

.thinking-note {
  margin: 9px 0 0;
  color: var(--psych-ink-muted);
  font-size: 12px;
  line-height: 1.45;
}

.is-fallback {
  color: var(--psych-warning);
}

.is-fallback .thinking-mark span {
  background: #d97706;
}

.is-fallback .stage-segment {
  background: rgba(217, 119, 6, 0.20);
}

.is-fallback .stage-segment.is-complete,
.is-fallback .stage-segment.is-active::after {
  background: #d97706;
}

@keyframes thinking-bars {
  0%, 100% { height: 6px; opacity: 0.55; }
  50% { height: 16px; opacity: 1; }
}

@keyframes stage-scan {
  0% { transform: scaleX(0.08); opacity: 0.45; }
  65% { transform: scaleX(1); opacity: 1; }
  100% { transform: scaleX(1); opacity: 0.35; }
}

@media (prefers-reduced-motion: reduce) {
  .thinking-mark span,
  .stage-segment.is-active::after {
    animation: none;
  }
}

@media (max-width: 480px) {
  .deep-thinking-indicator {
    width: min(330px, 70vw);
  }

  .thinking-heading {
    grid-template-columns: 18px minmax(0, 1fr) auto;
    gap: 6px;
  }

  .thinking-heading strong {
    font-size: 13px;
  }
}
</style>
