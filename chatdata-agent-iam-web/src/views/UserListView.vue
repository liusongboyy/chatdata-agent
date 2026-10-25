<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import {
  assignRoles,
  createUser,
  listOrgs,
  listRoles,
  listUsers,
  resetPassword,
  userStats,
  updateUser,
  type OrgView,
  type RoleView,
  type UserQuery,
  type UserView,
} from '../api/admin'
import { errorMessage } from '../api/http'
import OrgTreeSelect from '../components/OrgTreeSelect.vue'

/**
 * 用户管理。
 *
 * 三件事都在这页：新建、改状态/姓名、分配角色与重置密码。
 * **角色用点选而不是手打**——权限配置按角色码逐字符匹配，
 * 让人凭记忆敲码就是在制造"静默失效"。
 */
const users = ref<UserView[]>([])
/** 服务端过滤后的总数，不是本页条数。 */
const total = ref(0)
const page = ref(1)
const size = ref(20)
const stats = ref({ total: 0, active: 0, disabled: 0, orgs: 0 })
const orgs = ref<OrgView[]>([])
const roles = ref<RoleView[]>([])

const loading = ref(false)
const listError = ref('')
const notice = ref('')

const showCreate = ref(false)
const createForm = ref({ username: '', password: '', displayName: '', orgId: null as number | null, roles: [] as string[], status: 'ACTIVE' as const })
const createError = ref('')

const editing = ref<UserView | null>(null)
// orgId 是**权威值**：null = 不设组织。与其他字段的"空则不改"不同——
// 组织必须能被清空，而 null 表达不了"不改"和"清空"两件事
const editForm = ref({
  displayName: '',
  status: 'ACTIVE' as 'ACTIVE' | 'DISABLED',
  orgId: null as number | null,
})
const editError = ref('')

const roleTarget = ref<UserView | null>(null)
const roleSelection = ref<string[]>([])
const roleError = ref('')

const passwordTarget = ref<UserView | null>(null)
const newPassword = ref('')
const passwordError = ref('')

const busy = ref(false)

// 筛选在**服务端**做：10 万用户时把全量取回来在浏览器里 filter，
// 等于每次进页面都传 20MB、渲染 10 万行。这里只传条件，拿回一页。
const keyword = ref('')
const statusFilter = ref<'ALL' | 'ACTIVE' | 'DISABLED'>('ALL')
const orgFilter = ref<number | null>(null)

const roleMap = computed(() => new Map(roles.value.map((r) => [r.code, r])))
const pageCount = computed(() => Math.max(1, Math.ceil(total.value / size.value)))

/** 组织树与角色清单只在首次加载时取；翻页、筛选不再重复拉。 */
let bootstrapped = false
let keywordTimer: ReturnType<typeof setTimeout> | null = null

function initial(user: UserView): string {
  const source = user.displayName?.trim() || user.username
  return source.slice(0, 1).toUpperCase()
}

function roleClass(code: string): string {
  const category = roleMap.value.get(code)?.category
  if (category === 'ADMIN') return 'role-admin'
  if (category === 'DATA') return 'role-data'
  return ''
}

function resetFilters(): void {
  keyword.value = ''
  statusFilter.value = 'ALL'
  orgFilter.value = null
}

async function load(): Promise<void> {
  loading.value = true
  listError.value = ''
  try {
    const query: UserQuery = { page: page.value, size: size.value, orgId: orgFilter.value }
    const kw = keyword.value.trim()
    if (kw !== '') query.keyword = kw
    if (statusFilter.value !== 'ALL') query.status = statusFilter.value
    const [p, s, o, r] = await Promise.all([
      listUsers(query),
      userStats(),
      bootstrapped ? Promise.resolve(orgs.value) : listOrgs(),
      bootstrapped ? Promise.resolve(roles.value) : listRoles(),
    ])
    users.value = p.items
    total.value = p.total
    // 页码越界时服务端会回到最后一页，这里跟着它走，避免翻页器显示一个空页
    page.value = p.page
    stats.value = s
    if (!bootstrapped) {
      orgs.value = o
      roles.value = r
      bootstrapped = true
    }
  } catch (e: unknown) {
    listError.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

/** 条件变了就回第一页——停在第 7 页看一个只剩 2 页的结果集没有意义。 */
function applyFilters(): void {
  page.value = 1
  void load()
}

watch(statusFilter, applyFilters)
watch(orgFilter, applyFilters)
watch(keyword, () => {
  if (keywordTimer) clearTimeout(keywordTimer)
  keywordTimer = setTimeout(applyFilters, 300)
})

function goPage(next: number): void {
  const target = Math.min(Math.max(1, next), pageCount.value)
  if (target === page.value) return
  page.value = target
  void load()
}

function changeSize(event: Event): void {
  size.value = Number((event.target as HTMLSelectElement).value)
  page.value = 1
  void load()
}

onMounted(load)

function openCreate(): void {
  createForm.value = { username: '', password: '', displayName: '', orgId: null, roles: [], status: 'ACTIVE' }
  createError.value = ''
  showCreate.value = true
}

async function submitCreate(): Promise<void> {
  busy.value = true
  createError.value = ''
  try {
    await createUser({
      username: createForm.value.username,
      password: createForm.value.password,
      displayName: createForm.value.displayName,
      orgId: createForm.value.orgId,
      roles: createForm.value.roles,
      status: createForm.value.status,
    })
    showCreate.value = false
    notice.value = '用户已创建'
    await load()
  } catch (e: unknown) {
    createError.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}

function openEdit(user: UserView): void {
  editing.value = user
  editForm.value = { displayName: user.displayName ?? '', status: user.status, orgId: user.orgId }
  editError.value = ''
}

async function submitEdit(): Promise<void> {
  if (!editing.value) return
  busy.value = true
  editError.value = ''
  try {
    await updateUser(editing.value.id, {
      displayName: editForm.value.displayName,
      status: editForm.value.status,
      orgId: editForm.value.orgId,
    })
    editing.value = null
    notice.value = '用户已更新'
    await load()
  } catch (e: unknown) {
    editError.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}

function openRoles(user: UserView): void {
  roleTarget.value = user
  roleSelection.value = [...user.roles]
  roleError.value = ''
}

async function submitRoles(): Promise<void> {
  if (!roleTarget.value) return
  busy.value = true
  roleError.value = ''
  try {
    await assignRoles(roleTarget.value.id, roleSelection.value)
    roleTarget.value = null
    notice.value = '角色已更新'
    await load()
  } catch (e: unknown) {
    roleError.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}

function openPassword(user: UserView): void {
  passwordTarget.value = user
  newPassword.value = ''
  passwordError.value = ''
}

async function submitPassword(): Promise<void> {
  if (!passwordTarget.value) return
  busy.value = true
  passwordError.value = ''
  try {
    await resetPassword(passwordTarget.value.id, newPassword.value)
    passwordTarget.value = null
    notice.value = '密码已重置；该用户已签发的令牌立即失效'
  } catch (e: unknown) {
    passwordError.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section>
    <header class="page-head">
      <div>
        <h1 class="page-title">用户</h1>
        <p class="sub">账号、所属组织与角色。角色决定能看哪些数据，点选而不是手打。</p>
      </div>
      <button class="btn btn-primary" type="button" @click="openCreate">＋ 新增用户</button>
    </header>

    <div class="stats">
      <div class="stat">
        <span class="stat-label">用户总数</span>
        <b class="stat-value">{{ stats.total }}</b>
        <span class="stat-meta">覆盖 {{ stats.orgs }} 个组织</span>
      </div>
      <div class="stat">
        <span class="stat-label">启用中</span>
        <b class="stat-value">{{ stats.active }}</b>
        <span class="stat-meta ok">可正常登录</span>
      </div>
      <div class="stat">
        <span class="stat-label">已停用</span>
        <b class="stat-value">{{ stats.disabled }}</b>
        <span class="stat-meta muted">令牌已失效</span>
      </div>
      <div class="stat">
        <span class="stat-label">可分配角色</span>
        <b class="stat-value">{{ roles.length }}</b>
        <span class="stat-meta muted">按角色收敛权限</span>
      </div>
    </div>

    <p v-if="listError" class="note note-danger" role="alert">{{ listError }}</p>
    <p v-if="notice" class="note">{{ notice }}</p>

    <section class="table-card">
      <div class="toolbar">
        <label class="search">
          <span class="search-icon" aria-hidden="true">⌕</span>
          <input v-model="keyword" type="search" placeholder="搜索账号、姓名、组织或角色" />
        </label>
        <OrgTreeSelect
          v-model="orgFilter"
          :orgs="orgs"
          placeholder="全部组织"
          empty-label="全部组织"
          class="org-filter"
        />
        <select v-model="statusFilter" class="select">
          <option value="ALL">全部状态</option>
          <option value="ACTIVE">启用</option>
          <option value="DISABLED">停用</option>
        </select>
        <button class="reset" type="button" @click="resetFilters">重置</button>
        <span class="count">{{ total }} 人</span>
      </div>

      <div v-if="loading" class="note">加载中…</div>
      <div v-else-if="users.length === 0" class="note">
        {{ keyword || statusFilter !== 'ALL' || orgFilter !== null ? '没有匹配的用户。换个关键词或重置筛选。' : '还没有用户。' }}
      </div>

      <div v-else class="table-wrap">
        <table class="data">
          <thead>
            <tr>
              <th scope="col">用户</th>
              <th scope="col">所属组织</th>
              <th scope="col">角色</th>
              <th scope="col">状态</th>
              <th scope="col">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="u in users" :key="u.id">
              <td>
                <div class="user-cell">
                  <span class="avatar" aria-hidden="true">{{ initial(u) }}</span>
                  <span class="user-text">
                    <b>{{ u.displayName ?? u.username }}</b>
                    <small class="mono">@{{ u.username }}</small>
                  </span>
                </div>
              </td>
              <td>
                <span v-if="u.orgName" class="org">{{ u.orgName }}</span>
                <span v-else class="dash">未分配</span>
              </td>
              <td>
                <div v-if="u.roles.length" class="roles">
                  <span v-for="r in u.roles" :key="r" class="role" :class="roleClass(r)">
                    {{ roleMap.get(r)?.name ?? r }}
                  </span>
                </div>
                <span v-else class="dash">无角色</span>
              </td>
              <td>
                <span class="state" :class="{ on: u.status === 'ACTIVE' }">
                  {{ u.status === 'ACTIVE' ? '启用' : '停用' }}
                </span>
              </td>
              <td>
                <div class="acts">
                  <button class="act" type="button" @click="openEdit(u)">编辑</button>
                  <button class="act" type="button" @click="openRoles(u)">角色</button>
                  <button class="act" type="button" @click="openPassword(u)">重置密码</button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <footer v-if="!loading && total > 0" class="table-foot">
        <span>共 {{ total }} 位用户 · 停用账号会立即失效已签发令牌</span>
        <div class="pager">
          <select class="select" :value="size" @change="changeSize">
            <option :value="20">20 条/页</option>
            <option :value="50">50 条/页</option>
            <option :value="100">100 条/页</option>
          </select>
          <button class="act" type="button" :disabled="page <= 1" @click="goPage(page - 1)">上一页</button>
          <span class="pager-pos">{{ page }} / {{ pageCount }}</span>
          <button class="act" type="button" :disabled="page >= pageCount" @click="goPage(page + 1)">下一页</button>
        </div>
      </footer>
    </section>

    <!-- 新增用户 -->
    <div v-if="showCreate" class="modal-mask" @click.self="showCreate = false">
      <div class="modal" role="dialog" aria-modal="true">
        <h2 class="modal-title">新增用户</h2>
        <div class="form">
          <div class="two-col">
            <div class="field">
              <label class="label" for="u-name">账号</label>
              <input id="u-name" v-model="createForm.username" class="input" />
            </div>
            <div class="field">
              <label class="label" for="u-display">姓名</label>
              <input id="u-display" v-model="createForm.displayName" class="input" />
            </div>
          </div>
          <div class="two-col">
            <div class="field">
              <label class="label" for="u-pwd">初始密码</label>
              <input id="u-pwd" v-model="createForm.password" class="input" type="password" />
              <p class="field-note">至少 6 位；写入的是 BCrypt 哈希。</p>
            </div>
            <div class="field">
              <span class="label">所属组织</span>
              <OrgTreeSelect
                v-model="createForm.orgId"
                :orgs="orgs"
                placeholder="不指定"
                empty-label="不指定"
              />
            </div>
          </div>
          <div class="field">
            <span class="label">角色</span>
            <div class="checks">
              <label v-for="r in roles" :key="r.code" class="check">
                <input v-model="createForm.roles" type="checkbox" :value="r.code" />
                <span>{{ r.name }}<span class="sub">（{{ r.code }}）</span></span>
              </label>
            </div>
          </div>
        </div>
        <p v-if="createError" class="note note-danger" role="alert">{{ createError }}</p>
        <div class="modal-actions">
          <button class="btn btn-quiet" type="button" @click="showCreate = false">取消</button>
          <button class="btn btn-primary" type="button" :disabled="busy" @click="submitCreate">
            {{ busy ? '保存中…' : '保存' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 编辑 -->
    <div v-if="editing" class="modal-mask" @click.self="editing = null">
      <div class="modal" role="dialog" aria-modal="true">
        <h2 class="modal-title">编辑用户 · {{ editing.username }}</h2>
        <div class="form">
          <div class="field">
            <label class="label" for="e-display">姓名</label>
            <input id="e-display" v-model="editForm.displayName" class="input" />
          </div>
          <div class="field">
            <span class="label">所属组织</span>
            <OrgTreeSelect
              v-model="editForm.orgId"
              :orgs="orgs"
              placeholder="（不设组织）"
              empty-label="（不设组织）"
            />
            <p class="field-note">
              组织决定数据范围——行级规则的动态变量取的就是用户的主组织。
              改完最长 30 秒生效（内省缓存），不需要重新登录。
            </p>
          </div>
          <div class="field">
            <label class="label" for="e-status">状态</label>
            <select id="e-status" v-model="editForm.status" class="input">
              <option value="ACTIVE">启用</option>
              <option value="DISABLED">停用</option>
            </select>
            <p class="field-note">停用会同时让该用户已签发的令牌失效（token_version + 1）。</p>
          </div>
        </div>
        <p v-if="editError" class="note note-danger" role="alert">{{ editError }}</p>
        <div class="modal-actions">
          <button class="btn btn-quiet" type="button" @click="editing = null">取消</button>
          <button class="btn btn-primary" type="button" :disabled="busy" @click="submitEdit">
            {{ busy ? '保存中…' : '保存' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 角色 -->
    <div v-if="roleTarget" class="modal-mask" @click.self="roleTarget = null">
      <div class="modal" role="dialog" aria-modal="true">
        <h2 class="modal-title">分配角色 · {{ roleTarget.username }}</h2>
        <div class="checks">
          <label v-for="r in roles" :key="r.code" class="check">
            <input v-model="roleSelection" type="checkbox" :value="r.code" />
            <span>{{ r.name }}<span class="sub">（{{ r.code }} · {{ r.category }}）</span></span>
          </label>
        </div>
        <p class="field-note">覆盖式保存：取消勾选即收回该角色。</p>
        <p v-if="roleError" class="note note-danger" role="alert">{{ roleError }}</p>
        <div class="modal-actions">
          <button class="btn btn-quiet" type="button" @click="roleTarget = null">取消</button>
          <button class="btn btn-primary" type="button" :disabled="busy" @click="submitRoles">
            {{ busy ? '保存中…' : '保存' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 重置密码 -->
    <div v-if="passwordTarget" class="modal-mask" @click.self="passwordTarget = null">
      <div class="modal" role="dialog" aria-modal="true">
        <h2 class="modal-title">重置密码 · {{ passwordTarget.username }}</h2>
        <div class="field">
          <label class="label" for="p-new">新密码</label>
          <input id="p-new" v-model="newPassword" class="input" type="password" />
          <p class="field-note">至少 6 位。重置后该用户已签发的令牌立即失效。</p>
        </div>
        <p v-if="passwordError" class="note note-danger" role="alert">{{ passwordError }}</p>
        <div class="modal-actions">
          <button class="btn btn-quiet" type="button" @click="passwordTarget = null">取消</button>
          <button class="btn btn-primary" type="button" :disabled="busy" @click="submitPassword">
            {{ busy ? '保存中…' : '重置' }}
          </button>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
  margin-bottom: 18px;
}

.stat {
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 16px 18px;
  background: var(--surface);
  border-radius: var(--radius);
  box-shadow: var(--shadow-sm);
}

.stat-label {
  font-size: 11px;
  color: var(--ink-soft);
}

.stat-value {
  font-family: var(--font-mono);
  font-size: 24px;
  font-weight: 700;
  line-height: 1;
  letter-spacing: -0.02em;
}

.stat-meta {
  font-size: 10px;
  color: var(--ink-faint);
}

.stat-meta.ok {
  color: var(--ok);
}

.table-card {
  background: var(--surface);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
  overflow: hidden;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 15px 18px;
  border-bottom: 1px solid var(--line);
  flex-wrap: wrap;
}

.search {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  min-width: 200px;
  height: 36px;
  padding: 0 12px;
  border: 1px solid var(--line-strong);
  border-radius: 10px;
  background: var(--surface);
}

.search:focus-within {
  border-color: var(--ink);
  box-shadow: var(--focus-ring);
}

.search-icon {
  font-size: 14px;
  color: var(--ink-faint);
}

.search input {
  flex: 1;
  min-width: 0;
  border: none;
  background: transparent;
  font-size: 12.5px;
}

.search input:focus {
  outline: none;
}

.select {
  height: 36px;
  padding: 0 30px 0 11px;
  border: 1px solid var(--line-strong);
  border-radius: 10px;
  background-color: var(--surface);
  font-size: 12px;
  color: var(--ink);
}

.select:focus {
  outline: none;
  border-color: var(--ink);
  box-shadow: var(--focus-ring);
}

.org-filter {
  width: 170px;
}

.org-filter :deep(.org-trigger) {
  height: 36px;
  padding: 0 11px;
  border-radius: 10px;
  background: var(--surface);
  font-size: 12px;
}

.reset {
  height: 36px;
  padding: 0 12px;
  border: none;
  border-radius: 9px;
  background: transparent;
  font-size: 12px;
  color: var(--ink-soft);
  cursor: pointer;
}

.reset:hover {
  background: var(--surface-sunk);
  color: var(--ink);
}

.count {
  margin-left: auto;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--ink-faint);
}

.table-wrap {
  overflow-x: auto;
}

.data {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--text-sm);
}

.data th,
.data td {
  padding: 12px 18px;
  text-align: left;
  border-bottom: 1px solid var(--line);
  white-space: nowrap;
  vertical-align: middle;
}

.data th {
  font-size: 10.5px;
  font-weight: 600;
  letter-spacing: 0.04em;
  color: var(--ink-faint);
  background: var(--surface-sunk);
}

.data tbody tr:last-child td {
  border-bottom: none;
}

.data tbody tr:hover td {
  background: #fafbff;
}

.user-cell {
  display: flex;
  align-items: center;
  gap: 11px;
}

.avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  flex: none;
  border-radius: 50%;
  background: var(--off-soft);
  color: var(--accent-deep);
  font-size: 12px;
  font-weight: 700;
}

/* 双色轮换，让长列表里的头像不至于一片同色——纯装饰，不表达状态 */
.data tbody tr:nth-child(even) .avatar {
  background: linear-gradient(135deg, #ece4ff, #f6f2ff);
  color: #7a4ce0;
}

.user-text {
  display: flex;
  flex-direction: column;
  line-height: 1.3;
}

.user-text b {
  font-size: 12.5px;
  font-weight: 600;
}

.user-text small {
  margin-top: 2px;
  font-size: 10.5px;
  color: var(--ink-faint);
}

.org {
  font-size: 12px;
  color: var(--ink-soft);
}

.dash {
  font-size: 11.5px;
  color: var(--ink-faint);
}

.roles {
  display: flex;
  gap: 5px;
  flex-wrap: wrap;
}

.role {
  padding: 3px 8px;
  border-radius: 6px;
  background: var(--surface-sunk);
  font-size: 10.5px;
  color: var(--ink-soft);
}

.role-admin {
  background: var(--accent-soft);
  color: var(--accent-deep);
}

.role-data {
  background: #f3edff;
  color: #6d3fd6;
}

.state {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 9px;
  border-radius: var(--radius-pill);
  background: var(--off-soft);
  font-size: 10.5px;
  color: var(--ink-faint);
}

.state.on {
  background: var(--ok-soft);
  color: #07864a;
}

.acts {
  display: flex;
  gap: 2px;
}

.act {
  padding: 4px 7px;
  border: none;
  border-radius: 7px;
  background: transparent;
  font-size: 11.5px;
  color: var(--ink-soft);
  cursor: pointer;
}

.act:hover {
  background: var(--accent-soft);
  color: var(--accent-deep);
}

.table-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding: 12px 18px;
  border-top: 1px solid var(--line);
  font-size: 10.5px;
  color: var(--ink-faint);
}

.pager {
  display: flex;
  align-items: center;
  gap: 8px;
}

.pager .select {
  width: auto;
}

.pager-pos {
  font-variant-numeric: tabular-nums;
}

.pager .act:disabled {
  opacity: 0.4;
  cursor: default;
}

.checks {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2) var(--space-4);
}

.check {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--text-sm);
  cursor: pointer;
}

@media (max-width: 960px) {
  .stats {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 560px) {
  .count {
    margin-left: 0;
  }
}
</style>
