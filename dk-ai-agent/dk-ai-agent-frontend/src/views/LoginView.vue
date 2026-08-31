<template>
  <div class="login-page">
    <!-- 左侧品牌面板：呼吸圆 + 涟漪，心理咨询场景的"先慢下来" -->
    <aside class="brand-panel" aria-hidden="true">
      <div class="ripple ripple-1"></div>
      <div class="ripple ripple-2"></div>
      <div class="ripple ripple-3"></div>

      <div class="brand-inner">
        <div class="brand-logo">
          <span class="brand-logo-mark"></span>
          <span class="brand-logo-text">AI 心理咨询师</span>
        </div>

        <div class="breath-stage">
          <div class="breath-circle">
            <span class="breath-label">{{ breathPhase }}</span>
          </div>
        </div>

        <blockquote class="brand-quote">
          「先把发生的事和你的感受说清楚，<br />再一起寻找可执行的下一步。」
        </blockquote>

        <ul class="brand-points">
          <li><span class="point-dot"></span>对话梳理 · 案例检索 · 逐字稿溯源</li>
          <li><span class="point-dot"></span>长期记忆自动整合，越聊越懂你</li>
          <li><span class="point-dot"></span>仅提供非医疗性心理疏导，紧急情况请联系专业机构</li>
        </ul>
      </div>
    </aside>

    <!-- 右侧表单区 -->
    <main class="form-panel">
      <div class="form-card">
        <header class="form-head">
          <h1 class="form-title">{{ tab === 'login' ? '欢迎回来' : '创建账号' }}</h1>
          <p class="form-subtitle">
            {{ tab === 'login' ? '登录后继续你的咨询记录' : '注册后即可开始第一段对话' }}
          </p>
        </header>

        <div class="tab-bar" role="tablist" aria-label="登录或注册">
          <button
            type="button"
            role="tab"
            :aria-selected="tab === 'login'"
            :class="{ active: tab === 'login' }"
            @click="switchTab('login')"
          >登录</button>
          <button
            type="button"
            role="tab"
            :aria-selected="tab === 'register'"
            :class="{ active: tab === 'register' }"
            @click="switchTab('register')"
          >注册</button>
          <span class="tab-indicator" :class="{ right: tab === 'register' }"></span>
        </div>

        <p v-if="notice" class="alert alert-disabled" role="alert">
          <span class="alert-icon" aria-hidden="true">!</span>
          <span>{{ notice }}</span>
          <button type="button" class="alert-close" aria-label="关闭提示" @click="clearAuthNotice()">×</button>
        </p>

        <p v-if="formError" class="alert alert-error" :class="{ shake: shakeKey > 0 }" :key="shakeKey" role="alert">
          <span class="alert-icon" aria-hidden="true">!</span>
          <span>{{ formError }}</span>
        </p>

        <form class="form-body" @submit.prevent="handleSubmit">
          <label class="field">
            <span class="field-label">用户名</span>
            <input
              v-model.trim="username"
              type="text"
              autocomplete="username"
              placeholder="3-32 位中英文、数字或下划线"
              maxlength="32"
              :disabled="submitting"
            />
          </label>

          <label class="field">
            <span class="field-label">密码</span>
            <span class="field-input">
              <input
                v-model="password"
                :type="showPassword ? 'text' : 'password'"
                :autocomplete="tab === 'login' ? 'current-password' : 'new-password'"
                placeholder="8-72 位字符"
                maxlength="72"
                :disabled="submitting"
              />
              <button
                type="button"
                class="eye-button"
                :aria-label="showPassword ? '隐藏密码' : '显示密码'"
                :title="showPassword ? '隐藏密码' : '显示密码'"
                tabindex="-1"
                @click="showPassword = !showPassword"
              >{{ showPassword ? '隐' : '显' }}</button>
            </span>
          </label>

          <label v-if="tab === 'register'" class="field">
            <span class="field-label">确认密码</span>
            <span class="field-input">
              <input
                v-model="confirmPassword"
                :type="showPassword ? 'text' : 'password'"
                autocomplete="new-password"
                placeholder="再输入一次密码"
                maxlength="72"
                :disabled="submitting"
              />
            </span>
          </label>

          <button class="submit-button" type="submit" :disabled="submitting">
            <span v-if="submitting" class="spinner" aria-hidden="true"></span>
            <span>{{ submitting ? '请稍候…' : (tab === 'login' ? '登 录' : '注 册') }}</span>
          </button>
        </form>

        <p class="form-foot">
          {{ tab === 'login' ? '还没有账号？' : '已经有账号了？' }}
          <button type="button" class="link-button" @click="switchTab(tab === 'login' ? 'register' : 'login')">
            {{ tab === 'login' ? '去注册' : '去登录' }}
          </button>
        </p>
      </div>
    </main>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { login, register } from '../api'
import { setMe, useAuth } from '../stores/auth'

const route = useRoute()
const router = useRouter()
const { notice, clearAuthNotice } = useAuth()

const USERNAME_PATTERN = new RegExp('^[A-Za-z0-9_\\u4e00-\\u9fa5]{3,32}$')

const tab = ref('login')
const username = ref('')
const password = ref('')
const confirmPassword = ref('')
const showPassword = ref(false)
const submitting = ref(false)
const formError = ref('')
const shakeKey = ref(0)

// 呼吸圆文案随动画节奏切换
const breathPhase = ref('吸气')
let breathTimer = null

onMounted(() => {
  breathTimer = window.setInterval(() => {
    breathPhase.value = breathPhase.value === '吸气' ? '呼气' : '吸气'
  }, 4000)
})

onBeforeUnmount(() => {
  if (breathTimer) window.clearInterval(breathTimer)
})

const switchTab = (nextTab) => {
  if (nextTab === tab.value) return
  tab.value = nextTab
  formError.value = ''
  confirmPassword.value = ''
}

const showError = (message) => {
  formError.value = message
  shakeKey.value += 1
}

// 后端错误码 → 中文提示
const ERROR_MESSAGES = {
  BAD_CREDENTIALS: '用户名或密码错误',
  DISABLED: '该账号已被停用，请联系管理员',
  LOCKED: '登录失败次数过多，请 15 分钟后重试',
  DUPLICATE_USERNAME: '该用户名已被注册，换一个试试吧',
  RATE_LIMITED: '请求过于频繁，请稍后再试',
  VALIDATION: '' // 优先展示后端 message
}

const mapApiError = (error) => {
  const payload = error?.response?.data
  const code = payload?.error

  if (code === 'VALIDATION') {
    return payload?.message || '输入的信息不符合要求，请检查后重试'
  }
  if (code && ERROR_MESSAGES[code]) {
    return ERROR_MESSAGES[code]
  }
  if (error?.response) {
    return payload?.message || '服务暂时不可用，请稍后重试'
  }
  return '网络连接失败，请检查网络后重试'
}

const validate = () => {
  if (!USERNAME_PATTERN.test(username.value)) {
    showError('用户名需为 3-32 位中英文、数字或下划线')
    return false
  }
  if (password.value.length < 8 || password.value.length > 72) {
    showError('密码长度需为 8-72 位字符')
    return false
  }
  if (tab.value === 'register' && confirmPassword.value !== password.value) {
    showError('两次输入的密码不一致')
    return false
  }
  return true
}

// redirect 参数只允许站内路径，防开放重定向
const targetPath = computed(() => {
  const redirect = route.query.redirect
  if (typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//')) {
    return redirect
  }
  return '/psych-master'
})

const handleSubmit = async () => {
  if (submitting.value) return
  formError.value = ''
  clearAuthNotice()

  if (!validate()) return

  submitting.value = true
  try {
    const me = tab.value === 'login'
      ? await login(username.value, password.value)
      : await register(username.value, password.value)

    setMe(me)
    await router.push(targetPath.value)
  } catch (error) {
    showError(mapApiError(error))
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.login-page {
  display: grid;
  min-height: 100vh;
  grid-template-columns: minmax(380px, 46%) minmax(0, 1fr);
  background: var(--psych-canvas-base);
}

/* ---------- 左侧品牌面板 ---------- */
.brand-panel {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  background:
    radial-gradient(120% 90% at 12% 8%, rgba(14, 165, 233, 0.20) 0%, transparent 46%),
    radial-gradient(110% 100% at 90% 96%, rgba(10, 22, 51, 0.60) 0%, transparent 52%),
    linear-gradient(158deg, #2563eb 0%, #1d4ed8 52%, #16295c 100%);
  color: #eef4ff;
}

.ripple {
  position: absolute;
  left: 50%;
  top: 44%;
  aspect-ratio: 1;
  transform: translate(-50%, -50%);
  border: 1px solid rgba(147, 197, 253, 0.24);
  border-radius: 50%;
  animation: ripple 9s linear infinite;
}

.ripple-1 { width: 340px; animation-delay: 0s; }
.ripple-2 { width: 560px; animation-delay: 3s; }
.ripple-3 { width: 800px; animation-delay: 6s; }

@keyframes ripple {
  0% { opacity: 0; transform: translate(-50%, -50%) scale(0.55); }
  25% { opacity: 0.9; }
  100% { opacity: 0; transform: translate(-50%, -50%) scale(1.25); }
}

.brand-inner {
  position: relative;
  z-index: 1;
  display: flex;
  max-width: 460px;
  flex-direction: column;
  gap: 26px;
  padding: 48px 44px;
}

.brand-logo {
  display: inline-flex;
  align-items: center;
  gap: 10px;
}

.brand-logo-mark {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: #93c5fd;
  box-shadow: 0 0 0 4px rgba(147, 197, 253, 0.22);
}

.brand-logo-text {
  font-size: 15px;
  font-weight: 700;
  letter-spacing: 0.08em;
}

.breath-stage {
  display: flex;
  justify-content: center;
  padding: 12px 0 4px;
}

.breath-circle {
  display: flex;
  width: 148px;
  height: 148px;
  align-items: center;
  justify-content: center;
  border: 1px solid rgba(255, 255, 255, 0.34);
  border-radius: 50%;
  background: radial-gradient(circle at 34% 30%, rgba(255, 255, 255, 0.2), rgba(255, 255, 255, 0.04) 62%);
  box-shadow: 0 0 0 10px rgba(255, 255, 255, 0.05), 0 0 46px rgba(147, 197, 253, 0.20);
  animation: breathe 8s ease-in-out infinite;
}

.breath-label {
  color: rgba(255, 255, 255, 0.88);
  font-size: 14px;
  letter-spacing: 0.4em;
  text-indent: 0.4em;
}

@keyframes breathe {
  0%, 100% { transform: scale(0.82); }
  50% { transform: scale(1.04); }
}

.brand-quote {
  margin: 0;
  color: #f2f7ff;
  font-family: Georgia, 'Songti SC', 'STSong', 'SimSun', serif;
  font-size: 24px;
  font-weight: 600;
  letter-spacing: 0.04em;
  line-height: 1.75;
}

.brand-points {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.brand-points li {
  display: flex;
  align-items: center;
  gap: 10px;
  color: rgba(238, 247, 246, 0.78);
  font-size: 13px;
  line-height: 1.6;
}

.point-dot {
  flex: 0 0 auto;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: rgba(147, 197, 253, 0.85);
}

/* ---------- 右侧表单 ---------- */
.form-panel {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 24px;
  background:
    radial-gradient(60% 50% at 88% 6%, rgba(37, 99, 235, 0.08) 0%, transparent 70%),
    radial-gradient(50% 42% at 6% 96%, rgba(14, 165, 233, 0.08) 0%, transparent 70%),
    var(--psych-gradient-canvas);
}

.form-card {
  width: 100%;
  max-width: 400px;
  padding: 34px 34px 26px;
  border: 1px solid var(--psych-glass-line);
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.78);
  box-shadow: 0 18px 46px rgba(15, 23, 42, 0.10);
  animation: card-in 0.32s ease both;
}

@keyframes card-in {
  from { opacity: 0; transform: translateY(10px); }
  to { opacity: 1; transform: translateY(0); }
}

.form-title {
  margin: 0;
  color: var(--psych-ink);
  font-size: 26px;
  font-weight: 800;
  letter-spacing: 0.02em;
}

.form-subtitle {
  margin: 8px 0 0;
  color: var(--psych-ink-muted);
  font-size: 13px;
}

.tab-bar {
  position: relative;
  display: grid;
  margin: 22px 0 18px;
  border-bottom: 1px solid var(--psych-glass-line);
  grid-template-columns: 1fr 1fr;
}

.tab-bar button {
  padding: 10px 0 12px;
  border: 0;
  background: transparent;
  color: var(--psych-ink-muted);
  cursor: pointer;
  font-size: 15px;
  font-weight: 600;
  transition: color 0.18s ease;
}

.tab-bar button.active {
  color: var(--psych-primary);
}

.tab-bar button:focus-visible {
  outline: 2px solid var(--psych-primary);
  outline-offset: -4px;
}

.tab-indicator {
  position: absolute;
  bottom: -1px;
  left: 0;
  width: 50%;
  height: 2px;
  border-radius: 2px;
  background: var(--psych-primary);
  transition: transform 0.24s cubic-bezier(0.4, 0, 0.2, 1);
}

.tab-indicator.right {
  transform: translateX(100%);
}

.alert {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 14px;
  padding: 9px 12px;
  border-radius: 6px;
  font-size: 13px;
  line-height: 1.55;
}

.alert-icon {
  flex: 0 0 auto;
  width: 16px;
  height: 16px;
  margin-top: 1px;
  border-radius: 50%;
  color: #ffffff;
  font-size: 11px;
  font-weight: 700;
  line-height: 16px;
  text-align: center;
}

.alert-error {
  border: 1px solid #f0c8c8;
  background: #fdf3f3;
  color: #933838;
}

.alert-error .alert-icon { background: #c05656; }

.alert-disabled {
  border: 1px solid #ecd9b8;
  background: #fdf8ee;
  color: #8a6125;
}

.alert-disabled .alert-icon { background: #c08b3c; }

.alert-close {
  margin-left: auto;
  padding: 0 2px;
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
  font-size: 15px;
  line-height: 1;
  opacity: 0.7;
}

.alert-close:hover { opacity: 1; }

.shake {
  animation: shake 0.34s ease;
}

@keyframes shake {
  0%, 100% { transform: translateX(0); }
  25% { transform: translateX(-5px); }
  55% { transform: translateX(4px); }
  80% { transform: translateX(-2px); }
}

.form-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.field-label {
  color: var(--psych-ink-secondary);
  font-size: 13px;
  font-weight: 600;
}

.field-input {
  position: relative;
  display: block;
}

.field input {
  width: 100%;
  padding: 11px 12px;
  border: 1px solid #d5deea;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.75);
  color: var(--psych-ink);
  font-size: 14px;
  transition: border-color 0.16s ease, box-shadow 0.16s ease;
}

.field-input input {
  padding-right: 44px;
}

.field input::placeholder {
  color: #9aa7ba;
}

.field input:focus {
  outline: none;
  border-color: var(--psych-primary);
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.14);
}

.field input:disabled {
  opacity: 0.65;
}

.eye-button {
  position: absolute;
  top: 50%;
  right: 8px;
  transform: translateY(-50%);
  padding: 4px 8px;
  border: 0;
  border-radius: var(--psych-radius-pill);
  background: transparent;
  color: var(--psych-ink-muted);
  cursor: pointer;
  font-size: 12px;
}

.eye-button:hover {
  background: var(--psych-primary-soft);
  color: var(--psych-primary);
}

.submit-button {
  display: inline-flex;
  height: 46px;
  align-items: center;
  justify-content: center;
  gap: 8px;
  margin-top: 4px;
  border: 0;
  border-radius: var(--psych-radius-pill);
  background: linear-gradient(135deg, var(--psych-primary) 0%, var(--psych-accent) 100%);
  color: #ffffff;
  cursor: pointer;
  font-size: 15px;
  font-weight: 700;
  letter-spacing: 0.24em;
  text-indent: 0.24em;
  box-shadow: 0 6px 16px -6px var(--psych-primary-glow);
  transition: transform 0.15s ease, box-shadow 0.15s ease, filter 0.15s ease;
}

.submit-button:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 10px 22px -8px var(--psych-primary-glow);
  filter: brightness(1.05);
}

.submit-button:active:not(:disabled) {
  transform: translateY(0);
}

.submit-button:disabled {
  cursor: not-allowed;
  opacity: 0.7;
}

.spinner {
  width: 15px;
  height: 15px;
  border: 2px solid rgba(255, 255, 255, 0.4);
  border-top-color: #ffffff;
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.form-foot {
  margin: 18px 0 0;
  color: var(--psych-ink-muted);
  font-size: 13px;
  text-align: center;
}

.link-button {
  padding: 0 2px;
  border: 0;
  background: transparent;
  color: var(--psych-primary);
  cursor: pointer;
  font-size: 13px;
  font-weight: 700;
}

.link-button:hover {
  text-decoration: underline;
}

/* ---------- 响应式 ---------- */
@media (max-width: 900px) {
  .login-page {
    grid-template-columns: 1fr;
    grid-template-rows: auto 1fr;
  }

  .brand-panel {
    min-height: 190px;
  }

  .brand-inner {
    gap: 12px;
    padding: 26px 22px;
  }

  .breath-stage,
  .brand-points {
    display: none;
  }

  .brand-quote {
    font-size: 17px;
  }

  .form-panel {
    padding: 26px 16px 40px;
  }

  .form-card {
    padding: 26px 20px 20px;
  }
}
</style>
