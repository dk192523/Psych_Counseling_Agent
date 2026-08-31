<template>
  <div class="admin-page">
    <!-- 顶栏：管理员身份 + 返回咨询 + 退出 -->
    <header class="admin-header">
      <div class="admin-header-left">
        <h1 class="admin-title">管理后台</h1>
        <span class="admin-chip">
          <span class="admin-chip-dot" aria-hidden="true"></span>
          管理员 · {{ me?.username }}
        </span>
      </div>
      <nav class="admin-header-right">
        <router-link class="header-link" to="/psych-master">← 返回咨询</router-link>
        <button type="button" class="header-logout" @click="handleLogout">退出登录</button>
      </nav>
    </header>

    <main class="admin-main">
      <p v-if="loadError" class="load-error" role="alert">
        {{ loadError }}
        <button type="button" class="link-button" @click="refreshAll">重试</button>
      </p>

      <!-- 统计卡片 -->
      <section class="stats-grid" aria-label="平台统计">
        <div v-for="item in statCards" :key="item.key" class="stat-card" :style="{ '--accent': item.color }">
          <div class="stat-value">{{ formatNumber(stats?.[item.key]) }}</div>
          <div class="stat-label">{{ item.label }}</div>
        </div>
      </section>

      <!-- 用户表 -->
      <section class="table-card">
        <div class="table-toolbar">
          <label class="search-box">
            <span class="search-icon" aria-hidden="true">⌕</span>
            <input
              v-model="keyword"
              type="search"
              placeholder="搜索用户名…"
              aria-label="搜索用户名"
              :disabled="loading"
            />
          </label>

          <label class="status-filter">
            <span class="visually-hidden">状态筛选</span>
            <select v-model="statusFilter" :disabled="loading" @change="onFilterChange">
              <option value="">全部状态</option>
              <option value="ACTIVE">正常</option>
              <option value="DISABLED">已停用</option>
            </select>
          </label>

          <button type="button" class="ghost-button" :disabled="loading" @click="refreshAll">
            <span class="refresh-icon" :class="{ spinning: loading }" aria-hidden="true">⟳</span>
            刷新
          </button>

          <span class="table-total">共 {{ totalElements }} 名用户</span>
        </div>

        <!-- 批量操作栏 -->
        <transition name="slide-fade">
          <div v-if="selected.size > 0" class="bulk-bar" role="toolbar" aria-label="批量操作">
            <span class="bulk-count">已选 <strong>{{ selected.size }}</strong> 项</span>
            <button type="button" class="bulk-button danger" :disabled="busy" @click="openBulkDisable">批量停用</button>
            <button type="button" class="bulk-button" :disabled="busy" @click="runBulk('ENABLE')">批量解封</button>
            <button type="button" class="link-button" @click="selected.clear()">清除选择</button>
          </div>
        </transition>

        <div class="table-scroll">
          <table class="user-table">
            <thead>
              <tr>
                <th class="col-check">
                  <input
                    type="checkbox"
                    aria-label="全选本页可选用户"
                    :checked="allSelectableSelected"
                    :indeterminate.prop="someSelected"
                    :disabled="loading || selectableRows.length === 0"
                    @change="toggleSelectAll"
                  />
                </th>
                <th>用户名</th>
                <th>角色</th>
                <th>状态</th>
                <th class="col-num">会话数</th>
                <th>最后登录</th>
                <th class="col-actions">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="loading && users.length === 0">
                <td colspan="7" class="table-state">正在加载用户列表…</td>
              </tr>
              <tr v-else-if="users.length === 0">
                <td colspan="7" class="table-state">没有匹配的用户</td>
              </tr>
              <tr
                v-for="row in users"
                :key="row.id"
                :class="{ 'is-self': isSelf(row), 'is-disabled-row': row.status === 'DISABLED', 'is-selected': selected.has(row.id) }"
              >
                <td class="col-check">
                  <input
                    type="checkbox"
                    :aria-label="`选择用户 ${row.username}`"
                    :checked="selected.has(row.id)"
                    :disabled="isSelf(row)"
                    :title="isSelf(row) ? '不能对自己执行批量操作' : undefined"
                    @change="toggleSelect(row.id)"
                  />
                </td>
                <td class="col-username">
                  <span class="username-text">{{ row.username }}</span>
                  <span v-if="isSelf(row)" class="self-badge">我</span>
                </td>
                <td>
                  <span class="badge" :class="row.role === 'ADMIN' ? 'role-admin' : 'role-user'">
                    {{ row.role === 'ADMIN' ? '管理员' : '用户' }}
                  </span>
                </td>
                <td>
                  <span
                    class="badge status"
                    :class="row.status === 'ACTIVE' ? 'status-active' : 'status-off'"
                    :title="row.status === 'DISABLED' && row.disabledReason ? `停用原因：${row.disabledReason}` : undefined"
                  >
                    <span class="status-dot" aria-hidden="true"></span>
                    {{ row.status === 'ACTIVE' ? '正常' : '已停用' }}
                  </span>
                </td>
                <td class="col-num">{{ row.conversationCount ?? 0 }}</td>
                <td class="col-time">{{ formatDateTime(row.lastLoginAt) || '从未登录' }}</td>
                <td class="col-actions">
                  <template v-if="row.status === 'ACTIVE'">
                    <button
                      type="button"
                      class="row-button warn"
                      :disabled="isSelf(row) || busy"
                      :title="isSelf(row) ? '不能停用自己' : '停用该账号，并立即销毁其全部会话'"
                      @click="openDisable(row)"
                    >停用</button>
                  </template>
                  <template v-else>
                    <button
                      type="button"
                      class="row-button"
                      :disabled="isSelf(row) || busy"
                      :title="isSelf(row) ? '不能操作自己' : '恢复该账号'"
                      @click="runEnable(row)"
                    >解封</button>
                  </template>
                  <button
                    type="button"
                    class="row-button"
                    :disabled="busy"
                    title="重置为临时密码（仅显示一次）"
                    @click="runResetPassword(row)"
                  >重置密码</button>
                  <button
                    type="button"
                    class="row-button danger"
                    :disabled="isSelf(row) || busy"
                    :title="isSelf(row) ? '不能删除自己' : '删除账号及其全部会话、消息与记忆'"
                    @click="openDelete(row)"
                  >删除</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- 分页 -->
        <div class="table-pager">
          <span class="pager-info">第 {{ totalElements === 0 ? 0 : page + 1 }} / {{ totalPages }} 页 · 每页 {{ size }} 条</span>
          <div class="pager-controls">
            <button type="button" class="ghost-button" :disabled="page <= 0 || loading" @click="goPage(page - 1)">上一页</button>
            <button type="button" class="ghost-button" :disabled="page >= totalPages - 1 || loading" @click="goPage(page + 1)">下一页</button>
          </div>
        </div>
      </section>
    </main>

    <!-- 模态框 -->
    <transition name="modal-fade">
      <div v-if="modal.type" class="modal-overlay" @click.self="closeModal">
        <div class="modal-card" role="dialog" aria-modal="true" :aria-label="modalTitle">
          <header class="modal-head">
            <h2>{{ modalTitle }}</h2>
            <button type="button" class="modal-close" aria-label="关闭" @click="closeModal">×</button>
          </header>

          <!-- 停用（单个） -->
          <template v-if="modal.type === 'disable'">
            <p class="modal-text">
              停用后 <strong>{{ modal.user?.username }}</strong> 将无法登录，其全部在线会话会被立即销毁。
            </p>
            <label class="modal-field">
              <span>停用原因（可选）</span>
              <input v-model="modal.reason" type="text" maxlength="200" placeholder="例如：违规使用" />
            </label>
            <footer class="modal-foot">
              <button type="button" class="ghost-button" :disabled="modal.submitting" @click="closeModal">取消</button>
              <button type="button" class="solid-button danger" :disabled="modal.submitting" @click="runDisable">
                {{ modal.submitting ? '处理中…' : '确认停用' }}
              </button>
            </footer>
          </template>

          <!-- 批量停用 -->
          <template v-else-if="modal.type === 'bulkDisable'">
            <p class="modal-text">即将批量停用 <strong>{{ selected.size }}</strong> 个账号，其在线会话会被立即销毁。</p>
            <label class="modal-field">
              <span>停用原因（可选）</span>
              <input v-model="modal.reason" type="text" maxlength="200" placeholder="例如：批量清理违规账号" />
            </label>
            <footer class="modal-foot">
              <button type="button" class="ghost-button" :disabled="modal.submitting" @click="closeModal">取消</button>
              <button type="button" class="solid-button danger" :disabled="modal.submitting" @click="runBulk('DISABLE')">
                {{ modal.submitting ? '处理中…' : `确认停用 ${selected.size} 项` }}
              </button>
            </footer>
          </template>

          <!-- 删除确认 -->
          <template v-else-if="modal.type === 'delete'">
            <p class="modal-text">
              确认删除用户 <strong>{{ modal.user?.username }}</strong> 吗？<br />
              该用户的会话、消息与记忆将被<strong class="danger-text">永久删除，无法恢复</strong>。
            </p>
            <footer class="modal-foot">
              <button type="button" class="ghost-button" :disabled="modal.submitting" @click="closeModal">取消</button>
              <button type="button" class="solid-button danger" :disabled="modal.submitting" @click="runDelete">
                {{ modal.submitting ? '删除中…' : '确认删除' }}
              </button>
            </footer>
          </template>

          <!-- 重置密码结果 -->
          <template v-else-if="modal.type === 'resetResult'">
            <p class="modal-text">
              用户 <strong>{{ modal.user?.username }}</strong> 的密码已重置。
            </p>
            <div class="temp-password-box">
              <code class="temp-password">{{ modal.tempPassword }}</code>
              <button type="button" class="ghost-button" @click="copyTempPassword">复制</button>
            </div>
            <p class="temp-password-note">⚠ 临时密码仅显示这一次，关闭窗口后无法再次查看，请立即告知用户。</p>
            <footer class="modal-foot">
              <button type="button" class="solid-button" @click="closeModal">我已记录，关闭</button>
            </footer>
          </template>

          <!-- 批量结果 -->
          <template v-else-if="modal.type === 'bulkResult'">
            <p class="modal-text">
              批量操作完成：成功 <strong class="ok-text">{{ modal.bulkResult?.succeeded?.length ?? 0 }}</strong> 项，
              失败 <strong :class="(modal.bulkResult?.failed?.length ?? 0) > 0 ? 'danger-text' : 'ok-text'">
                {{ modal.bulkResult?.failed?.length ?? 0 }}
              </strong> 项。
            </p>
            <ul v-if="modal.bulkResult?.failed?.length" class="bulk-failed-list">
              <li v-for="item in modal.bulkResult.failed" :key="item.id">
                {{ usernameOf(item.id) }}（#{{ item.id }}）：{{ bulkFailText(item.error) }}
              </li>
            </ul>
            <footer class="modal-foot">
              <button type="button" class="solid-button" @click="closeModal">知道了</button>
            </footer>
          </template>
        </div>
      </div>
    </transition>

    <!-- 轻提示 -->
    <div class="toast-stack" aria-live="polite">
      <transition-group name="toast">
        <div v-for="toast in toasts" :key="toast.id" class="toast" :class="toast.type">
          {{ toast.message }}
        </div>
      </transition-group>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  adminBulk,
  adminDeleteUser,
  adminDisableUser,
  adminEnableUser,
  adminListUsers,
  adminResetPassword,
  adminStats
} from '../api'
import { useAuth } from '../stores/auth'

const router = useRouter()
const { me, logout } = useAuth()

const PAGE_SIZE = 20

const stats = ref(null)
const users = ref([])
const totalElements = ref(0)
const page = ref(0)
const size = ref(PAGE_SIZE)
const keyword = ref('')
const statusFilter = ref('')
const loading = ref(false)
const busy = ref(false)
const loadError = ref('')
const selected = ref(new Set())
const toasts = ref([])

const modal = reactive({
  type: '', // '' | disable | bulkDisable | delete | resetResult | bulkResult
  user: null,
  reason: '',
  tempPassword: '',
  bulkResult: null,
  submitting: false
})

let debounceTimer = null
let toastSeq = 0

const statCards = [
  { key: 'totalUsers', label: '用户总数', color: '#2563eb' },
  { key: 'activeUsers', label: '活跃用户', color: '#3d9a6c' },
  { key: 'disabledUsers', label: '停用用户', color: '#c05656' },
  { key: 'adminCount', label: '管理员', color: '#b07f2e' },
  { key: 'totalConversations', label: '会话总数', color: '#4a7a9b' },
  { key: 'totalMessages', label: '消息总数', color: '#6b7f8e' }
]

const totalPages = computed(() => Math.max(1, Math.ceil(totalElements.value / size.value)))

const isSelf = (row) => Boolean(me.value && row.id === me.value.id)

const selectableRows = computed(() => users.value.filter((row) => !isSelf(row)))

const allSelectableSelected = computed(() => (
  selectableRows.value.length > 0 &&
  selectableRows.value.every((row) => selected.value.has(row.id))
))

const someSelected = computed(() => {
  const count = selectableRows.value.filter((row) => selected.value.has(row.id)).length
  return count > 0 && count < selectableRows.value.length
})

const modalTitle = computed(() => {
  switch (modal.type) {
    case 'disable': return '停用账号'
    case 'bulkDisable': return '批量停用'
    case 'delete': return '删除用户'
    case 'resetResult': return '密码已重置'
    case 'bulkResult': return '批量操作结果'
    default: return ''
  }
})

// ---------- 数据加载 ----------

const loadUsers = async () => {
  loading.value = true
  loadError.value = ''
  try {
    const data = await adminListUsers({
      keyword: keyword.value.trim(),
      status: statusFilter.value,
      page: page.value,
      size: size.value
    })
    users.value = Array.isArray(data.content) ? data.content : []
    totalElements.value = Number(data.totalElements ?? 0)
    // 服务端页码回写，防止越界页返回空时本地页码漂移
    page.value = Number(data.page ?? page.value)
  } catch (error) {
    console.error('加载用户列表失败:', error)
    loadError.value = error?.response?.data?.error === 'FORBIDDEN'
      ? '没有管理员权限，无法访问该页面。'
      : '用户列表加载失败，请稍后重试。'
  } finally {
    loading.value = false
  }
}

const loadStats = async () => {
  try {
    stats.value = await adminStats()
  } catch (error) {
    console.error('加载统计数据失败:', error)
  }
}

const refreshAll = async () => {
  await Promise.all([loadUsers(), loadStats()])
}

// ---------- 搜索防抖 / 筛选 / 分页 ----------

watch(keyword, () => {
  if (debounceTimer) window.clearTimeout(debounceTimer)
  debounceTimer = window.setTimeout(() => {
    page.value = 0
    loadUsers()
  }, 300)
})

onBeforeUnmount(() => {
  if (debounceTimer) window.clearTimeout(debounceTimer)
})

const onFilterChange = () => {
  page.value = 0
  loadUsers()
}

const goPage = (next) => {
  if (next < 0 || next > totalPages.value - 1) return
  page.value = next
  loadUsers()
}

// ---------- 选择 ----------

const toggleSelect = (id) => {
  const next = new Set(selected.value)
  if (next.has(id)) {
    next.delete(id)
  } else {
    next.add(id)
  }
  selected.value = next
}

const toggleSelectAll = () => {
  if (allSelectableSelected.value) {
    const next = new Set(selected.value)
    selectableRows.value.forEach((row) => next.delete(row.id))
    selected.value = next
  } else {
    const next = new Set(selected.value)
    selectableRows.value.forEach((row) => next.add(row.id))
    selected.value = next
  }
}

// ---------- 行操作 ----------

const openDisable = (row) => {
  modal.user = row
  modal.reason = ''
  modal.type = 'disable'
}

const openBulkDisable = () => {
  modal.reason = ''
  modal.type = 'bulkDisable'
}

const openDelete = (row) => {
  modal.user = row
  modal.type = 'delete'
}

const resetModal = () => {
  modal.type = ''
  modal.user = null
  modal.reason = ''
  modal.tempPassword = ''
  modal.bulkResult = null
}

// submitting 守卫只拦"用户在请求进行中手动关闭"；
// 提交逻辑成功后的关闭必须走 resetModal——此时 submitting 尚为 true，
// 直接调 closeModal 会被守卫挡回，导致弹窗在操作成功后仍然留在页面上。
const closeModal = () => {
  if (modal.submitting) return
  resetModal()
}

const usernameOf = (id) => {
  const row = users.value.find((item) => item.id === id)
  return row ? row.username : '未知用户'
}

const BULK_FAIL_TEXT = {
  SELF_OPERATION: '不能对自己执行该操作',
  NOT_FOUND: '用户不存在或已被删除',
  USER_NOT_FOUND: '用户不存在',
  INVALID_ID: '无效的用户 ID',
  INTERNAL_ERROR: '该项处理失败，请稍后重试'
}

const bulkFailText = (code) => BULK_FAIL_TEXT[code] || code || '操作失败'

const apiFailText = (error, fallback) => {
  const payload = error?.response?.data
  const code = payload?.error
  if (code === 'SELF_OPERATION') return '不能对自己执行该操作'
  if (code === 'USER_NOT_FOUND') return '用户不存在'
  return payload?.message || fallback
}

const pushToast = (message, type = 'success') => {
  const id = ++toastSeq
  toasts.value.push({ id, message, type })
  window.setTimeout(() => {
    toasts.value = toasts.value.filter((item) => item.id !== id)
  }, 3200)
}

const runDisable = async () => {
  if (!modal.user) return
  modal.submitting = true
  try {
    await adminDisableUser(modal.user.id, modal.reason.trim())
    pushToast(`已停用 ${modal.user.username}，其在线会话已销毁`)
    modal.submitting = false
    resetModal()
    selected.value = new Set()
    await refreshAll()
  } catch (error) {
    pushToast(apiFailText(error, '停用失败，请稍后重试'), 'error')
  } finally {
    modal.submitting = false
  }
}

const runEnable = async (row) => {
  busy.value = true
  try {
    await adminEnableUser(row.id)
    pushToast(`已解封 ${row.username}`)
    await refreshAll()
  } catch (error) {
    pushToast(apiFailText(error, '解封失败，请稍后重试'), 'error')
  } finally {
    busy.value = false
  }
}

const runResetPassword = async (row) => {
  busy.value = true
  try {
    const data = await adminResetPassword(row.id)
    modal.user = row
    modal.tempPassword = data?.tempPassword || ''
    modal.type = 'resetResult'
  } catch (error) {
    pushToast(apiFailText(error, '重置密码失败，请稍后重试'), 'error')
  } finally {
    busy.value = false
  }
}

const copyTempPassword = async () => {
  const text = modal.tempPassword
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    pushToast('临时密码已复制到剪贴板')
  } catch {
    // clipboard API 不可用（非安全上下文等）时降级为选中文本
    pushToast('复制失败，请手动选择文本复制', 'error')
  }
}

const runDelete = async () => {
  if (!modal.user) return
  modal.submitting = true
  try {
    const deletedId = modal.user.id
    await adminDeleteUser(deletedId)
    pushToast(`已删除 ${modal.user.username} 及其全部数据`)
    modal.submitting = false
    resetModal()
    const next = new Set(selected.value)
    next.delete(deletedId)
    selected.value = next
    await refreshAll()
  } catch (error) {
    pushToast(apiFailText(error, '删除失败，请稍后重试'), 'error')
  } finally {
    modal.submitting = false
  }
}

// ---------- 批量操作 ----------

const runBulk = async (action) => {
  const userIds = [...selected.value]
  if (userIds.length === 0) return
  if (userIds.length > 100) {
    pushToast('单次批量操作最多 100 个用户', 'error')
    return
  }

  // busy 统一锁住行按钮与批量栏（ENABLE 走批量栏时没有模态框，modal.submitting 拦不到）
  busy.value = true
  modal.submitting = true
  try {
    const payload = action === 'DISABLE'
      ? { userIds, action, reason: modal.reason.trim() || undefined }
      : { userIds, action }
    const result = await adminBulk(payload)
    const failed = result?.failed ?? []
    const succeeded = result?.succeeded ?? []

    if (failed.length > 0) {
      modal.bulkResult = result
      modal.type = 'bulkResult'
    } else {
      modal.submitting = false
      resetModal()
      pushToast(`批量${action === 'DISABLE' ? '停用' : '解封'}成功（${succeeded.length} 项）`)
    }

    const next = new Set()
    failed.forEach((item) => next.add(item.id))
    selected.value = next
    await refreshAll()
  } catch (error) {
    pushToast(apiFailText(error, '批量操作失败，请稍后重试'), 'error')
  } finally {
    busy.value = false
    modal.submitting = false
  }
}

// ---------- 顶栏 ----------

const handleLogout = async () => {
  await logout()
  await router.push('/login')
}

// ---------- 工具 ----------

const formatNumber = (value) => {
  const num = Number(value ?? 0)
  return Number.isFinite(num) ? num.toLocaleString('zh-CN') : '0'
}

const formatDateTime = (value) => {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  return date.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  })
}

onMounted(refreshAll)
</script>

<style scoped>
.admin-page {
  min-height: 100vh;
  background: var(--psych-canvas-base);
}

/* ---------- 顶栏 ---------- */
.admin-header {
  position: sticky;
  top: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 13px 24px;
  background-color: var(--psych-primary);
  box-shadow: 0 2px 8px rgba(22, 52, 52, 0.12);
  color: #ffffff;
}

.admin-header-left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.admin-title {
  margin: 0;
  font-size: 19px;
  font-weight: 800;
  letter-spacing: 0.04em;
}

.admin-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 10px;
  border: 1px solid rgba(255, 255, 255, 0.32);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.12);
  font-size: 12px;
  white-space: nowrap;
}

.admin-chip-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #93c5fd;
  box-shadow: 0 0 0 2px rgba(255, 255, 255, 0.12);
}

.admin-header-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.header-link {
  padding: 6px 10px;
  border-radius: 6px;
  color: rgba(255, 255, 255, 0.9);
  font-size: 13px;
  transition: background-color 0.15s ease;
}

.header-link:hover {
  background: rgba(255, 255, 255, 0.14);
  color: #ffffff;
}

.header-logout {
  padding: 6px 12px;
  border: 1px solid rgba(255, 255, 255, 0.4);
  border-radius: 6px;
  background: transparent;
  color: #ffffff;
  cursor: pointer;
  font-size: 13px;
  transition: background-color 0.15s ease;
}

.header-logout:hover {
  background: rgba(255, 255, 255, 0.16);
}

/* ---------- 主体 ---------- */
.admin-main {
  max-width: 1180px;
  margin: 0 auto;
  padding: 22px 20px 48px;
}

.load-error {
  margin: 0 0 16px;
  padding: 10px 14px;
  border-left: 3px solid #b75c5c;
  border-radius: 6px;
  background: #fff4f4;
  color: #8d3434;
  font-size: 13px;
}

/* ---------- 统计卡片 ---------- */
.stats-grid {
  display: grid;
  gap: 14px;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  margin-bottom: 20px;
}

.stat-card {
  position: relative;
  padding: 16px 18px 14px;
  border: 1px solid #d5deea;
  border-radius: 10px;
  background: #ffffff;
  overflow: hidden;
  transition: transform 0.16s ease, box-shadow 0.16s ease;
}

.stat-card::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  width: 100%;
  height: 3px;
  background: var(--accent, var(--psych-primary));
}

.stat-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 10px 22px rgba(22, 52, 52, 0.1);
}

.stat-value {
  color: var(--psych-ink);
  font-size: 28px;
  font-weight: 800;
  font-variant-numeric: tabular-nums;
  line-height: 1.2;
}

.stat-label {
  margin-top: 4px;
  color: var(--psych-ink-muted);
  font-size: 12px;
  letter-spacing: 0.06em;
}

/* ---------- 表格卡片 ---------- */
.table-card {
  border: 1px solid #d5deea;
  border-radius: 10px;
  background: #ffffff;
  overflow: hidden;
}

.table-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  border-bottom: 1px solid #e3eaf4;
}

.search-box {
  position: relative;
  flex: 1 1 220px;
  max-width: 320px;
}

.search-icon {
  position: absolute;
  left: 10px;
  top: 50%;
  transform: translateY(-50%);
  color: #9aa7ba;
  font-size: 15px;
  pointer-events: none;
}

.search-box input {
  width: 100%;
  padding: 8px 12px 8px 30px;
  border: 1px solid #d5deea;
  border-radius: 7px;
  background: rgba(255, 255, 255, 0.75);
  color: var(--psych-ink);
  font-size: 13px;
  transition: border-color 0.16s ease, box-shadow 0.16s ease;
}

.search-box input:focus {
  outline: none;
  border-color: var(--psych-primary);
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.14);
}

.status-filter select {
  padding: 8px 10px;
  border: 1px solid #d5deea;
  border-radius: 7px;
  background: rgba(255, 255, 255, 0.75);
  color: var(--psych-ink);
  cursor: pointer;
  font-size: 13px;
}

.status-filter select:focus {
  outline: none;
  border-color: var(--psych-primary);
}

.ghost-button {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 8px 12px;
  border: 1px solid #d5deea;
  border-radius: 7px;
  background: #ffffff;
  color: var(--psych-ink-secondary);
  cursor: pointer;
  font-size: 13px;
  transition: background-color 0.15s ease, border-color 0.15s ease;
}

.ghost-button:hover:not(:disabled) {
  border-color: var(--psych-primary);
  background: var(--psych-primary-soft);
}

.ghost-button:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.refresh-icon {
  display: inline-block;
}

.refresh-icon.spinning {
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.table-total {
  margin-left: auto;
  color: var(--psych-ink-muted);
  font-size: 12px;
}

/* ---------- 批量栏 ---------- */
.bulk-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 9px 16px;
  border-bottom: 1px solid #e3eaf4;
  background: var(--psych-primary-soft);
}

.bulk-count {
  color: var(--psych-ink-secondary);
  font-size: 13px;
}

.bulk-count strong {
  color: var(--psych-ink);
  font-size: 15px;
}

.bulk-button {
  padding: 6px 12px;
  border: 1px solid var(--psych-primary);
  border-radius: 6px;
  background: #ffffff;
  color: var(--psych-primary);
  cursor: pointer;
  font-size: 12px;
  font-weight: 600;
  transition: background-color 0.15s ease;
}

.bulk-button:hover:not(:disabled) {
  background: var(--psych-primary-soft);
}

.bulk-button.danger {
  border-color: #c07a56;
  color: #96481f;
}

.bulk-button.danger:hover:not(:disabled) {
  background: #fbeee6;
}

.bulk-button:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.slide-fade-enter-active,
.slide-fade-leave-active {
  transition: opacity 0.18s ease, transform 0.18s ease;
}

.slide-fade-enter-from,
.slide-fade-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}

/* ---------- 表格 ---------- */
.table-scroll {
  overflow-x: auto;
}

.user-table {
  width: 100%;
  min-width: 880px;
  border-collapse: collapse;
  font-size: 13px;
}

.user-table th {
  padding: 10px 14px;
  border-bottom: 1px solid #e3eaf4;
  background: #f7fbfb;
  color: #5d7374;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.05em;
  text-align: left;
  white-space: nowrap;
}

.user-table td {
  padding: 11px 14px;
  border-bottom: 1px solid #eef2f2;
  color: #2d4546;
  vertical-align: middle;
}

.user-table tbody tr {
  transition: background-color 0.12s ease;
}

.user-table tbody tr:hover {
  background: #f4f9f8;
}

.user-table tbody tr.is-selected {
  background: var(--psych-primary-soft);
}

.user-table tbody tr.is-self {
  background: #fbfdf7;
}

.user-table tbody tr.is-disabled-row .username-text {
  color: #8a9a9a;
}

.col-check {
  width: 40px;
  text-align: center;
}

.user-table input[type='checkbox'] {
  width: 15px;
  height: 15px;
  cursor: pointer;
  accent-color: var(--psych-primary);
}

.user-table input[type='checkbox']:disabled {
  cursor: not-allowed;
}

.col-username {
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.username-text {
  font-weight: 600;
}

.self-badge {
  margin-left: 6px;
  padding: 1px 6px;
  border-radius: 9px;
  background: rgba(37, 99, 235, 0.16);
  color: var(--psych-primary);
  font-size: 10px;
  font-weight: 700;
}

.col-num {
  font-variant-numeric: tabular-nums;
  text-align: right;
}

.user-table th.col-num {
  text-align: right;
}

.col-time {
  color: #647a7b;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 2px 9px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.04em;
  white-space: nowrap;
}

.role-admin {
  background: var(--psych-primary);
  color: #ffffff;
}

.role-user {
  border: 1px solid #c3d2d2;
  background: #f2f6f6;
  color: #557071;
}

.status-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
}

.status-active {
  background: #e4f4ea;
  color: #2c6e49;
}

.status-active .status-dot {
  background: #3d9a6c;
}

.status-off {
  background: #fbeaea;
  color: #933838;
}

.status-off .status-dot {
  background: #c05656;
}

/* ---------- 行操作 ---------- */
.col-actions {
  white-space: nowrap;
}

.row-button {
  padding: 5px 9px;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: var(--psych-primary);
  cursor: pointer;
  font-size: 12px;
  font-weight: 600;
  transition: background-color 0.13s ease;
}

.row-button + .row-button {
  margin-left: 2px;
}

.row-button:hover:not(:disabled) {
  background: #e2efee;
}

.row-button.warn {
  color: #96661f;
}

.row-button.warn:hover:not(:disabled) {
  background: #f8efdf;
}

.row-button.danger {
  color: #9c4040;
}

.row-button.danger:hover:not(:disabled) {
  background: #f8eaea;
}

.row-button:disabled {
  cursor: not-allowed;
  opacity: 0.4;
}

.table-state {
  padding: 34px 14px;
  color: var(--psych-ink-muted);
  text-align: center;
}

/* ---------- 分页 ---------- */
.table-pager {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 16px;
  border-top: 1px solid #e3eaf4;
}

.pager-info {
  color: var(--psych-ink-muted);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.pager-controls {
  display: flex;
  gap: 8px;
}

/* ---------- 模态框 ---------- */
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
  max-width: 440px;
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
  color: var(--psych-ink);
  font-size: 17px;
  font-weight: 800;
}

.modal-close {
  padding: 2px 8px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--psych-ink-muted);
  cursor: pointer;
  font-size: 19px;
  line-height: 1;
}

.modal-close:hover {
  background: #eef4f4;
}

.modal-text {
  margin: 0 0 14px;
  color: #3c5354;
  font-size: 13.5px;
  line-height: 1.7;
}

.danger-text {
  color: #b04848;
}

.ok-text {
  color: #2c6e49;
}

.modal-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 16px;
}

.modal-field span {
  color: #557071;
  font-size: 12px;
  font-weight: 600;
}

.modal-field input {
  padding: 9px 11px;
  border: 1px solid #d5deea;
  border-radius: 7px;
  color: var(--psych-ink);
  font-size: 13px;
}

.modal-field input:focus {
  outline: none;
  border-color: var(--psych-primary);
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.14);
}

.modal-foot {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.solid-button {
  padding: 9px 16px;
  border: 0;
  border-radius: 7px;
  background: var(--psych-primary);
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

.solid-button.danger {
  background: #b04848;
}

.solid-button:disabled {
  cursor: not-allowed;
  opacity: 0.65;
}

.temp-password-box {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
  padding: 12px 14px;
  border: 1px dashed #9eb8b8;
  border-radius: 8px;
  background: #f6fbfa;
}

.temp-password {
  flex: 1;
  color: #14443f;
  font-family: 'Cascadia Code', Consolas, 'Courier New', monospace;
  font-size: 17px;
  font-weight: 700;
  letter-spacing: 0.08em;
  word-break: break-all;
  user-select: all;
}

.temp-password-note {
  margin: 0 0 16px;
  color: #96661f;
  font-size: 12px;
  line-height: 1.6;
}

.bulk-failed-list {
  max-height: 160px;
  margin: 0 0 16px;
  padding: 10px 14px 10px 30px;
  border-radius: 8px;
  background: #fdf3f3;
  color: #933838;
  font-size: 12.5px;
  line-height: 1.8;
  overflow-y: auto;
}

.modal-fade-enter-active,
.modal-fade-leave-active {
  transition: opacity 0.18s ease;
}

.modal-fade-enter-from,
.modal-fade-leave-to {
  opacity: 0;
}

/* ---------- Toast ---------- */
.toast-stack {
  position: fixed;
  top: 68px;
  left: 50%;
  z-index: 60;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  transform: translateX(-50%);
  pointer-events: none;
}

.toast {
  padding: 9px 18px;
  border-radius: 8px;
  background: var(--psych-ink);
  color: #f0f7f6;
  font-size: 13px;
  box-shadow: 0 10px 24px rgba(15, 40, 40, 0.24);
}

.toast.error {
  background: #933838;
}

.toast-enter-active,
.toast-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}

.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}

/* ---------- 工具 ---------- */
.link-button {
  padding: 0 4px;
  border: 0;
  background: transparent;
  color: var(--psych-primary);
  cursor: pointer;
  font-size: inherit;
  font-weight: 700;
}

.link-button:hover {
  text-decoration: underline;
}

.visually-hidden {
  position: absolute;
  width: 1px;
  height: 1px;
  margin: -1px;
  padding: 0;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
  border: 0;
}

/* ---------- 响应式 ---------- */
@media (max-width: 768px) {
  .admin-header {
    flex-wrap: wrap;
    padding: 11px 14px;
  }

  .admin-title {
    font-size: 17px;
  }

  .admin-main {
    padding: 16px 12px 36px;
  }

  .stats-grid {
    grid-template-columns: repeat(auto-fit, minmax(130px, 1fr));
    gap: 10px;
  }

  .stat-value {
    font-size: 22px;
  }

  .table-toolbar {
    padding: 12px;
  }

  .table-total {
    margin-left: 0;
    width: 100%;
  }

  .table-pager {
    flex-direction: column;
    align-items: stretch;
  }

  .pager-controls {
    justify-content: flex-end;
  }
}
</style>