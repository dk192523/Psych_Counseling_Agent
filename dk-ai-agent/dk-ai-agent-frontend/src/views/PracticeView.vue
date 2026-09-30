<template>
  <div class="practice-page">
    <header class="practice-header">
      <router-link to="/psych-master">← 返回咨询</router-link>
      <span>AI 心理咨询师 · 模拟练习</span>
    </header>

    <main class="practice-main">
      <h1>模拟练习</h1>
      <p class="intro">每轮 5 道本地原创模拟题，练习 CBT、心理评估、心理急救（PFA）与治疗选择等专业基础知识。</p>
      <aside class="notice" aria-label="练习说明">
        非考试真题，不提供资格认证或专业能力评估。答题不写入咨询会话或长期记忆，
        成绩仅在本页临时显示，刷新、离开或重新开始后清除。
      </aside>

      <section v-if="loading" class="card" role="status">正在加载题目…</section>
      <section v-else-if="loadError" class="card">
        <p role="alert">{{ loadError }}</p>
        <button type="button" class="primary" @click="startPractice">重试加载</button>
      </section>
      <section v-else-if="finished" class="card" aria-live="polite">
        <h2>本轮练习完成</h2>
        <p>共 {{ questions.length }} 题，答对 {{ correctCount }} 题。</p>
        <p class="muted">结果只说明本次选项是否符合题设，不代表正式考试成绩。</p>
        <button type="button" class="primary" @click="startPractice">重新开始</button>
      </section>
      <section v-else-if="question" class="card" :aria-busy="submitting">
        <div class="question-meta">
          <span>第 {{ questionIndex + 1 }} / {{ questions.length }} 题</span>
          <span>{{ question.domain }}</span>
        </div>
        <form @submit.prevent="submitAnswer">
          <fieldset :disabled="submitting || !!result">
            <legend>{{ question.text }}</legend>
            <label
              v-for="(text, option) in question.options"
              :key="`${question.id}-${option}`"
              class="option"
              :class="{ selected: selectedOption === option }"
            >
              <input v-model="selectedOption" type="radio" name="practice-option" :value="option" />
              <span><strong>{{ option }}.</strong> {{ text }}</span>
            </label>
          </fieldset>
          <p class="source">题目来源：{{ question.source }}</p>
          <p v-if="answerError" class="error" role="alert">{{ answerError }}</p>
          <button
            v-if="!result"
            type="submit"
            class="primary"
            :disabled="!selectedOption || submitting"
          >{{ submitting ? '提交中…' : answerError ? '重试提交' : '提交答案' }}</button>
        </form>

        <div v-if="result" class="answer-result" :class="{ incorrect: !result.correct }" role="status">
          <h2>{{ result.correct ? '回答正确' : '回答错误' }}</h2>
          <p>正确答案：{{ result.correctOption }} · {{ question.options[result.correctOption] }}</p>
          <p class="explanation">{{ result.explanation }}</p>
          <p v-if="result.sourceUrl" class="source">来源定位：{{ result.sourceUrl }}</p>
        </div>

        <div class="actions">
          <button v-if="result" type="button" class="primary" @click="nextQuestion">
            {{ questionIndex + 1 === questions.length ? '查看本轮结果' : '下一题' }}
          </button>
          <button type="button" class="secondary" :disabled="submitting" @click="startPractice">重新开始</button>
        </div>
      </section>
    </main>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { getPracticeQuestions, submitPracticeAnswer } from '../api'

const questions = ref([])
const questionIndex = ref(0)
const selectedOption = ref('')
const result = ref(null)
const correctCount = ref(0)
const finished = ref(false)
const loading = ref(false)
const submitting = ref(false)
const loadError = ref('')
const answerError = ref('')
const question = computed(() => questions.value[questionIndex.value])
let requestVersion = 0
let controller = null
let disposed = false

// 取消网络请求并校验版本：即使响应已到达，也不能在离页或新一轮后覆盖状态。
const beginRequest = () => {
  controller?.abort()
  controller = new AbortController()
  return { version: ++requestVersion, signal: controller.signal }
}
const isCurrent = version => !disposed && version === requestVersion

const errorMessage = (error, fallback) => {
  const status = error?.response?.status
  if (status === 401) return '登录状态已失效，正在前往登录页。'
  if (error?.response?.data?.error === 'CSRF_INVALID') return '安全令牌已过期，请重试。'
  if (status === 429) return '请求过于频繁，请稍后重试。'
  return fallback
}

const startPractice = async () => {
  if (loading.value || submitting.value || disposed) return
  const { version, signal } = beginRequest()
  loading.value = true
  loadError.value = ''
  answerError.value = ''
  questions.value = []
  questionIndex.value = 0
  selectedOption.value = ''
  result.value = null
  correctCount.value = 0
  finished.value = false
  try {
    const data = await getPracticeQuestions(5, signal)
    if (!isCurrent(version)) return
    if (!Array.isArray(data) || !data.length) {
      loadError.value = '暂无可用题目，请稍后重试。'
      return
    }
    questions.value = data
  } catch (error) {
    if (isCurrent(version)) loadError.value = errorMessage(error, '题目加载失败，请检查网络后重试。')
  } finally {
    if (isCurrent(version)) loading.value = false
  }
}

const submitAnswer = async () => {
  if (!question.value || !selectedOption.value || result.value || submitting.value || loading.value || disposed) return
  const { version, signal } = beginRequest()
  submitting.value = true
  answerError.value = ''
  try {
    const data = await submitPracticeAnswer(question.value.id, selectedOption.value, signal)
    if (!isCurrent(version)) return
    result.value = data
    if (data.correct) correctCount.value += 1
  } catch (error) {
    if (isCurrent(version)) answerError.value = errorMessage(error, '答案提交失败，已保留你的选项，请重试。')
  } finally {
    if (isCurrent(version)) submitting.value = false
  }
}

const nextQuestion = () => {
  if (!result.value || submitting.value) return
  if (questionIndex.value + 1 === questions.value.length) {
    finished.value = true
    return
  }
  questionIndex.value += 1
  selectedOption.value = ''
  result.value = null
  answerError.value = ''
}

onMounted(startPractice)
onBeforeUnmount(() => {
  disposed = true
  requestVersion += 1
  controller?.abort()
})
</script>

<style scoped>
.practice-page { min-height: 100vh; background: var(--psych-canvas-base, #f3f6fb); color: var(--psych-ink, #172b4d); }
.practice-header { display: flex; align-items: center; justify-content: space-between; gap: 16px; flex-wrap: wrap; padding: 18px 24px; border-bottom: 1px solid #d9e2ef; background: #fff; }
.practice-header a { color: #245ec5; text-decoration: none; }
.practice-header a:hover { text-decoration: underline; }
.practice-main { width: min(100%, 820px); margin: 0 auto; padding: 32px 20px 64px; box-sizing: border-box; }
h1 { margin: 0 0 12px; font-size: 28px; }
h2 { margin: 0 0 12px; font-size: 20px; }
p { line-height: 1.7; }
.intro, .muted, .source { color: #52647e; }
.notice { padding: 16px 18px; border: 1px solid #cbdcf4; border-radius: 12px; background: #eaf2ff; line-height: 1.7; }
.card { margin-top: 24px; padding: 28px; border: 1px solid #d9e2ef; border-radius: 18px; background: #fff; overflow-wrap: anywhere; }
.question-meta { display: flex; justify-content: space-between; gap: 12px; flex-wrap: wrap; margin-bottom: 20px; font-size: 14px; color: #52647e; }
fieldset { min-width: 0; padding: 0; margin: 0; border: 0; }
legend { width: 100%; padding: 0; margin-bottom: 20px; font-size: 20px; font-weight: 600; line-height: 1.65; }
.option { display: flex; align-items: flex-start; gap: 12px; padding: 14px; margin-bottom: 12px; border: 1px solid #ced8e7; border-radius: 10px; line-height: 1.65; cursor: pointer; }
.option.selected { border-color: #2563eb; background: #eff5ff; }
.option input { flex: 0 0 auto; margin-top: 6px; accent-color: #2563eb; }
fieldset:disabled .option { cursor: default; }
.source { font-size: 13px; overflow-wrap: anywhere; }
.error { color: #a32232; }
.answer-result { margin-top: 24px; padding: 18px; border: 1px solid #97cab0; border-radius: 12px; background: #effaf4; }
.answer-result.incorrect { border-color: #e7c393; background: #fff7ec; }
.explanation { white-space: pre-wrap; }
.actions { display: flex; flex-wrap: wrap; gap: 12px; margin-top: 20px; }
button { min-height: 44px; padding: 10px 20px; border-radius: 10px; font: inherit; cursor: pointer; }
.primary { border: 1px solid #245ec5; background: #245ec5; color: #fff; }
.secondary { border: 1px solid #b9c8dd; background: #fff; color: #314d75; }
button:disabled { cursor: not-allowed; opacity: .6; }
button:focus-visible, a:focus-visible, .option:focus-within { outline: 3px solid #60a5fa; outline-offset: 3px; }
@media (max-width: 480px) {
  .practice-header { padding: 14px 16px; font-size: 13px; }
  .practice-main { padding: 24px 12px 40px; }
  .card { padding: 20px 16px; }
  legend { font-size: 18px; }
  .actions { flex-direction: column; }
  form > button { width: 100%; }
}
</style>
