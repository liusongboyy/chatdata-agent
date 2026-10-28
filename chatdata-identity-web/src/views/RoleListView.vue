<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { createRole, listRoles, updateRole, type RoleView } from '../api/admin'
import { errorMessage } from '../api/http'
import RolePermissionModal from '../components/RolePermissionModal.vue'
import { usePermissions } from '../composables/usePermissions'
import { P } from '../permissions'

/**
 * 角色管理。
 *
 * 分类沿用 ADR-0004：ADMIN（管理台）/ DATA（数据权限）/ FUNCTION（功能开关）。
 * **把数据权限角色误当管理台角色授予会提权**，所以分类在界面上要显式可见。
 *
 * 角色码创建后不可改：权限配置按它逐字符引用，改名等于静默失效。
 */
const { can } = usePermissions()

const roles = ref<RoleView[]>([])
const loading = ref(false)
const listError = ref('')
const notice = ref('')

const showCreate = ref(false)
const createForm = ref({ code: '', name: '', category: 'DATA' as RoleView['category'], description: '', enabled: true })
const createError = ref('')

const editing = ref<RoleView | null>(null)
const editForm = ref({ name: '', category: 'DATA' as RoleView['category'], description: '', enabled: true })
const editError = ref('')

/** 正在配置功能权限的角色。 */
const permissionTarget = ref<RoleView | null>(null)

const busy = ref(false)

const CATEGORY_LABEL: Record<RoleView['category'], string> = {
  ADMIN: '管理台',
  DATA: '数据权限',
  FUNCTION: '功能',
}

async function load(): Promise<void> {
  loading.value = true
  listError.value = ''
  try {
    roles.value = await listRoles()
  } catch (e: unknown) {
    listError.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)

function openCreate(): void {
  createForm.value = { code: '', name: '', category: 'DATA', description: '', enabled: true }
  createError.value = ''
  showCreate.value = true
}

async function submitCreate(): Promise<void> {
  busy.value = true
  createError.value = ''
  try {
    await createRole({ ...createForm.value })
    showCreate.value = false
    notice.value = '角色已创建'
    await load()
  } catch (e: unknown) {
    createError.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}

function openEdit(role: RoleView): void {
  editing.value = role
  editForm.value = {
    name: role.name,
    category: role.category,
    description: role.description ?? '',
    enabled: role.enabled,
  }
  editError.value = ''
}

async function submitEdit(): Promise<void> {
  if (!editing.value) return
  busy.value = true
  editError.value = ''
  try {
    await updateRole(editing.value.id, { ...editForm.value })
    editing.value = null
    notice.value = '角色已更新'
    await load()
  } catch (e: unknown) {
    editError.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}

function openPermissions(role: RoleView): void {
  permissionTarget.value = role
}

async function onPermissionsSaved(count: number): Promise<void> {
  permissionTarget.value = null
  notice.value = `功能权限已保存，共 ${count} 项。相关用户最长 5 秒后生效。`
  await load()
}
</script>

<template>
  <section>
    <header class="page-head">
      <div>
        <h1 class="page-title">角色</h1>
        <p class="sub">
          角色码被数据策略与助手授权按字符串引用，<strong>创建后不可重命名</strong>。
          功能权限决定这个角色能看到哪些<strong>页面、按钮与 tab</strong>。
        </p>
      </div>
      <button v-if="can(P.IAM_ROLES_WRITE)" class="btn btn-primary" type="button" @click="openCreate">
        ＋ 新增角色
      </button>
    </header>

    <p v-if="listError" class="note note-danger" role="alert">{{ listError }}</p>
    <p v-if="notice" class="note">{{ notice }}</p>

    <div v-if="loading" class="note">加载中…</div>
    <div v-else-if="roles.length === 0" class="note">还没有角色。</div>

    <div v-else class="table-card">
      <table class="data">
        <thead>
          <tr>
            <th scope="col">角色</th>
            <th scope="col">分类</th>
            <th scope="col">说明</th>
            <th scope="col">功能权限</th>
            <th scope="col">状态</th>
            <th scope="col">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in roles" :key="r.id">
            <td>
              <div class="role-cell">
                <b>{{ r.name }}</b>
                <small class="mono">{{ r.code }}</small>
              </div>
            </td>
            <td>
              <span
                class="cat"
                :class="{
                  'cat-admin': r.category === 'ADMIN',
                  'cat-data': r.category === 'DATA',
                }"
              >
                {{ CATEGORY_LABEL[r.category] }}
              </span>
            </td>
            <td class="desc">{{ r.description ?? '—' }}</td>
            <td>
              <span v-if="r.code === 'super_admin'" class="cat cat-admin">内置超管 · 全部</span>
              <span v-else-if="r.permissionCount > 0" class="perm mono">{{ r.permissionCount }} 项</span>
              <span v-else class="perm perm-off">未配置</span>
            </td>
            <td>
              <span class="state" :class="r.enabled ? 'state-on' : 'state-off'">
                {{ r.enabled ? '启用' : '停用' }}
              </span>
            </td>
            <td>
              <div class="acts">
                <button
                  v-if="can(P.IAM_ROLES_PERMISSIONS)"
                  class="act"
                  type="button"
                  @click="openPermissions(r)"
                >
                  权限
                </button>
                <button
                  v-if="can(P.IAM_ROLES_WRITE)"
                  class="act"
                  type="button"
                  @click="openEdit(r)"
                >
                  编辑
                </button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
      <footer class="table-foot">共 {{ roles.length }} 个角色 · 停用后不参与鉴权</footer>
    </div>

    <div v-if="showCreate" class="modal-mask" @click.self="showCreate = false">
      <div class="modal" role="dialog" aria-modal="true">
        <h2 class="modal-title">新增角色</h2>
        <div class="form">
          <div class="two-col">
            <div class="field">
              <label class="label" for="r-code">角色码</label>
              <input id="r-code" v-model="createForm.code" class="input" placeholder="如 区域经理" />
              <p class="field-note">创建后不可改。</p>
            </div>
            <div class="field">
              <label class="label" for="r-name">名称</label>
              <input id="r-name" v-model="createForm.name" class="input" />
            </div>
          </div>
          <div class="field">
            <label class="label" for="r-cat">分类</label>
            <select id="r-cat" v-model="createForm.category" class="input">
              <option value="ADMIN">管理台（可进管理面）</option>
              <option value="DATA">数据权限（决定能看哪些行/列）</option>
              <option value="FUNCTION">功能开关</option>
            </select>
            <p class="field-note">分类不是标签：把数据权限角色当管理台角色授予会提权。</p>
          </div>
          <div class="field">
            <label class="label" for="r-desc">说明</label>
            <input id="r-desc" v-model="createForm.description" class="input" />
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

    <div v-if="editing" class="modal-mask" @click.self="editing = null">
      <div class="modal" role="dialog" aria-modal="true">
        <h2 class="modal-title">编辑角色 · {{ editing.code }}</h2>
        <div class="form">
          <div class="field">
            <label class="label" for="er-name">名称</label>
            <input id="er-name" v-model="editForm.name" class="input" />
          </div>
          <div class="field">
            <label class="label" for="er-cat">分类</label>
            <select id="er-cat" v-model="editForm.category" class="input">
              <option value="ADMIN">管理台</option>
              <option value="DATA">数据权限</option>
              <option value="FUNCTION">功能开关</option>
            </select>
          </div>
          <div class="field">
            <label class="label" for="er-desc">说明</label>
            <input id="er-desc" v-model="editForm.description" class="input" />
          </div>
          <label class="check">
            <input v-model="editForm.enabled" type="checkbox" />
            <span>启用（停用后不参与鉴权）</span>
          </label>
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

    <RolePermissionModal
      v-if="permissionTarget"
      :role="permissionTarget"
      @close="permissionTarget = null"
      @saved="onPermissionsSaved"
    />
  </section>
</template>

<style scoped>
.table-card {
  background: var(--surface);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
  overflow: hidden;
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

.role-cell {
  display: flex;
  flex-direction: column;
  line-height: 1.3;
}

.role-cell b {
  font-size: 12.5px;
  font-weight: 600;
}

.role-cell small {
  margin-top: 2px;
  font-size: 10.5px;
  color: var(--ink-faint);
}

.cat {
  display: inline-flex;
  padding: 3px 8px;
  border-radius: 6px;
  background: var(--surface-sunk);
  font-size: 10.5px;
  color: var(--ink-soft);
  white-space: nowrap;
}

.cat-admin {
  background: var(--accent-soft);
  color: var(--accent-deep);
}

.cat-data {
  background: #f3edff;
  color: #6d3fd6;
}

.perm {
  font-size: 11px;
  color: var(--ink-soft);
}

.perm-off {
  padding: 3px 8px;
  border-radius: 6px;
  background: var(--off-soft);
  color: var(--ink-faint);
}

.desc {
  max-width: 34ch;
  color: var(--ink-soft);
  font-size: 11.5px;
}

.acts {
  display: flex;
  gap: 2px;
  white-space: nowrap;
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
  padding: 12px 18px;
  border-top: 1px solid var(--line);
  font-size: 10.5px;
  color: var(--ink-faint);
}

.check {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--text-sm);
  cursor: pointer;
}
</style>
