<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { createOrg, listOrgs, updateOrg, type OrgView } from '../api/admin'
import { errorMessage } from '../api/http'
import OrgTreeSelect from '../components/OrgTreeSelect.vue'
import { formatDateTime } from '../lib/datetime'
import { usePermissions } from '../composables/usePermissions'
import { P } from '../permissions'

/**
 * 组织管理：**树形表格**。
 *
 * ## 为什么是展开折叠的表格，而不是逐级下钻
 *
 * 下钻一次只能看一层，要对比"华东和华北下面各有什么"就得来回点。
 * 组织树的规模是十级，一屏铺得下——展开折叠既看得到结构，又保住了
 * 「编码 / 状态 / 操作」这些要横向对比的列。
 *
 * ## 结构走 parentId，path 只用于显示
 *
 * `path` 是物化路径（`/1/12/`），看着方便但它是**派生数据**：一旦不一致
 * （手工改过库、导入过数据），节点就会错位或凭空消失。而 `parentId` 是结构本身。
 *
 * ## 过滤时必须保留祖先
 *
 * 搜"上海门店"命中的是深层节点，但它的父链不在结果里的话，它在树上根本渲染不出来。
 * 所以过滤的结果集 = 命中的节点 ∪ 它们的全部祖先，并且**过滤时强制展开**。
 *
 * ## 不做删除
 *
 * 后端没有删除组织的接口。删一个组织要先回答"它下面的人归谁、子组织怎么办"——
 * 那是个独立的设计问题，不该顺手加一个按钮。需要的话单独做。
 */
const { can } = usePermissions()

const orgs = ref<OrgView[]>([])
const loading = ref(false)
const listError = ref('')
const notice = ref('')

/** 展开的节点 id。 */
const expanded = ref<Set<number>>(new Set())

/**
 * 默认全展开的上限。
 *
 * <p>几千个组织的树一旦全展开，表格会一次渲染上万行 DOM——页面卡在首帧，
 * 而这一屏能看到的只有最上面十几行。超过上限就只展开根节点。
 */
const AUTO_EXPAND_LIMIT = 200

const nameFilter = ref('')
const statusFilter = ref<'' | 'enabled' | 'disabled'>('')

const showCreate = ref(false)
const createForm = ref({ code: '', name: '', parentId: null as number | null, sortNo: 0, enabled: true })
const createError = ref('')

const editing = ref<OrgView | null>(null)
const editForm = ref({ name: '', sortNo: 0, enabled: true })
const editError = ref('')

const busy = ref(false)

const byId = computed(() => new Map(orgs.value.map((o) => [o.id, o])))

/**
 * parentId → 子节点。顺序沿用后端返回的顺序（`sort_no, id` 全局排序，
 * 过滤出同一父节点的子集后，相对顺序不变——所以这里不需要再排一次）。
 */
const childrenMap = computed(() => {
  const map = new Map<number | null, OrgView[]>()
  for (const org of orgs.value) {
    const key = org.parentId ?? null
    const list = map.get(key)
    if (list) list.push(org)
    else map.set(key, [org])
  }
  return map
})

/** 父节点不存在的节点。平铺列表时代它们可见，树里不能凭空消失。 */
const orphans = computed(() =>
  orgs.value.filter((o) => o.parentId != null && !byId.value.has(o.parentId)),
)

const filterActive = computed(
  () => nameFilter.value.trim() !== '' || statusFilter.value !== '',
)

/** 命中过滤条件的节点 **及其全部祖先**；未过滤时为 null（表示不过滤）。 */
const visibleIds = computed<Set<number> | null>(() => {
  if (!filterActive.value) return null
  const needle = nameFilter.value.trim().toLowerCase()
  const keep = new Set<number>()
  for (const org of orgs.value) {
    const okName =
      needle === '' ||
      org.name.toLowerCase().includes(needle) ||
      org.code.toLowerCase().includes(needle)
    const okStatus =
      statusFilter.value === '' ||
      (statusFilter.value === 'enabled' ? org.enabled : !org.enabled)
    if (!okName || !okStatus) continue

    keep.add(org.id)
    // 祖先必须一起留下，否则命中的深层节点在树上没有可挂的地方
    const seen = new Set<number>()
    let parent = org.parentId
    while (parent != null && !seen.has(parent)) {
      seen.add(parent)
      keep.add(parent)
      parent = byId.value.get(parent)?.parentId ?? null
    }
  }
  return keep
})

/** 拍平成表格行：{节点, 缩进层级, 是否有子节点}。 */
const rows = computed(() => {
  const out: { org: OrgView; depth: number; hasChildren: boolean }[] = []
  const seen = new Set<number>()

  const walk = (parentId: number | null, depth: number): void => {
    for (const org of childrenMap.value.get(parentId) ?? []) {
      if (seen.has(org.id)) continue // 防环：坏数据不该让页面转不出来
      seen.add(org.id)
      if (visibleIds.value && !visibleIds.value.has(org.id)) continue

      const kids = (childrenMap.value.get(org.id) ?? []).filter(
        (k) => !visibleIds.value || visibleIds.value.has(k.id),
      )
      out.push({ org, depth, hasChildren: kids.length > 0 })

      // 过滤时强制展开，否则命中的深层节点看不见
      if (kids.length > 0 && (expanded.value.has(org.id) || filterActive.value)) {
        walk(org.id, depth + 1)
      }
    }
  }

  walk(null, 0)
  return out
})

const stats = computed(() => ({
  total: orgs.value.length,
  enabled: orgs.value.filter((o) => o.enabled).length,
  disabled: orgs.value.filter((o) => !o.enabled).length,
  roots: orgs.value.filter((o) => o.parentId == null).length,
}))

function toggle(id: number): void {
  const next = new Set(expanded.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  expanded.value = next
}

function expandAll(): void {
  expanded.value = new Set(orgs.value.map((o) => o.id))
}

function collapseAll(): void {
  expanded.value = new Set()
}

function resetFilters(): void {
  nameFilter.value = ''
  statusFilter.value = ''
}

async function load(): Promise<void> {
  loading.value = true
  listError.value = ''
  try {
    orgs.value = await listOrgs()
    // 首次加载全展开：一屏看完整棵树
    if (expanded.value.size === 0) {
      if (orgs.value.length <= AUTO_EXPAND_LIMIT) {
        expandAll()
      } else {
        expanded.value = new Set(orgs.value.filter((o) => o.parentId === null).map((o) => o.id))
      }
    }
  } catch (e: unknown) {
    listError.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)

/** 从某一行「新增」时，父级默认是那一行——这是最常见的意图。 */
function openCreate(parent: OrgView | null): void {
  createForm.value = {
    code: '',
    name: '',
    parentId: parent?.id ?? null,
    sortNo: 0,
    enabled: true,
  }
  createError.value = ''
  showCreate.value = true
}

async function submitCreate(): Promise<void> {
  busy.value = true
  createError.value = ''
  try {
    await createOrg({ ...createForm.value })
    showCreate.value = false
    notice.value = '组织已创建'
    await load()
  } catch (e: unknown) {
    createError.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}

function openEdit(org: OrgView): void {
  editing.value = org
  editForm.value = { name: org.name, sortNo: org.sortNo, enabled: org.enabled }
  editError.value = ''
}

async function submitEdit(): Promise<void> {
  if (!editing.value) return
  busy.value = true
  editError.value = ''
  try {
    await updateOrg(editing.value.id, { ...editForm.value })
    editing.value = null
    notice.value = '组织已更新'
    await load()
  } catch (e: unknown) {
    editError.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}

/**
 * 状态开关。
 *
 * <p>只发 `enabled`：后端的更新是"空则不改"的局部合并，另外两个字段传 null 即保持原值。
 * 这样一次点击就够，不必让人开弹窗改一个开关再保存。
 */
async function toggleEnabled(org: OrgView): Promise<void> {
  busy.value = true
  listError.value = ''
  try {
    await updateOrg(org.id, { name: org.name, sortNo: org.sortNo, enabled: !org.enabled })
    notice.value = `「${org.name}」已${org.enabled ? '停用' : '启用'}`
    await load()
  } catch (e: unknown) {
    listError.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section>
    <header class="page-head">
      <div>
        <h1 class="page-title">组织</h1>
        <p class="sub">
          组织树决定数据范围：行级规则里的动态变量取的就是用户的主组织。
        </p>
      </div>
      <button
        v-if="can(P.IAM_ORGS_WRITE)"
        class="btn btn-primary"
        type="button"
        @click="openCreate(null)"
      >
        ＋ 新增组织
      </button>
    </header>

    <div class="stats">
      <div class="stat">
        <span class="stat-label">组织总数</span>
        <b class="stat-value">{{ stats.total }}</b>
        <span class="stat-meta">{{ stats.roots }} 个根组织</span>
      </div>
      <div class="stat">
        <span class="stat-label">启用中</span>
        <b class="stat-value">{{ stats.enabled }}</b>
        <span class="stat-meta ok">可参与数据授权</span>
      </div>
      <div class="stat">
        <span class="stat-label">已停用</span>
        <b class="stat-value">{{ stats.disabled }}</b>
        <span class="stat-meta muted">不参与数据授权</span>
      </div>
      <div class="stat">
        <span class="stat-label">当前显示</span>
        <b class="stat-value">{{ rows.length }}</b>
        <span class="stat-meta muted">{{ filterActive ? '已按条件筛选' : '整棵组织树' }}</span>
      </div>
    </div>

    <p v-if="listError" class="note note-danger" role="alert">{{ listError }}</p>
    <p v-if="notice" class="note">{{ notice }}</p>

    <section class="table-card">
      <div class="toolbar">
        <label class="search">
          <span class="search-icon" aria-hidden="true">⌕</span>
          <input v-model="nameFilter" type="search" placeholder="搜索组织名称或编码" />
        </label>
        <select v-model="statusFilter" class="select">
          <option value="">全部状态</option>
          <option value="enabled">启用</option>
          <option value="disabled">停用</option>
        </select>
        <button class="reset" type="button" @click="resetFilters">重置</button>
        <button class="reset" type="button" @click="expandAll">全展开</button>
        <button class="reset" type="button" @click="collapseAll">全收起</button>
        <span class="count">{{ orgs.length }} 个组织</span>
      </div>

      <div v-if="loading" class="note">加载中…</div>

      <template v-else>
        <div v-if="rows.length === 0" class="note">
          {{ filterActive ? '没有匹配的组织。' : '还没有组织。' }}
        </div>

        <div v-else class="table-wrap">
          <table class="data">
            <thead>
              <tr>
                <th scope="col">组织</th>
                <th scope="col">编码</th>
                <th scope="col">状态</th>
                <th scope="col">创建时间</th>
                <th scope="col">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in rows" :key="row.org.id">
                <td>
                  <span class="tree-cell" :style="{ paddingLeft: `${row.depth * 1.4}rem` }">
                    <button
                      v-if="row.hasChildren"
                      class="twist"
                      type="button"
                      :aria-expanded="expanded.has(row.org.id) || filterActive"
                      :aria-label="expanded.has(row.org.id) ? '收起' : '展开'"
                      @click="toggle(row.org.id)"
                    >
                      {{ expanded.has(row.org.id) || filterActive ? '−' : '+' }}
                    </button>
                    <span v-else class="twist twist-leaf" aria-hidden="true">·</span>
                    <span class="org-name">{{ row.org.name }}</span>
                  </span>
                </td>
                <td><span class="code mono">{{ row.org.code }}</span></td>
                <td>
                  <span class="state" :class="row.org.enabled ? 'state-on' : 'state-off'">
                    {{ row.org.enabled ? '启用' : '停用' }}
                  </span>
                </td>
                <td class="created">{{ formatDateTime(row.org.createdAt) }}</td>
                <td>
                  <div v-if="can(P.IAM_ORGS_WRITE)" class="acts">
                    <button class="act" type="button" @click="openCreate(row.org)">新增下级</button>
                    <button class="act" type="button" @click="openEdit(row.org)">编辑</button>
                    <button
                      class="act"
                      type="button"
                      :disabled="busy"
                      @click="toggleEnabled(row.org)"
                    >
                      {{ row.org.enabled ? '停用' : '启用' }}
                    </button>
                  </div>
                  <span v-else class="dash">—</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <footer v-if="rows.length > 0" class="table-foot">
          共 {{ orgs.length }} 个组织
          <span v-if="filterActive">· 已按条件筛选</span>
          · 组织决定数据范围，改动最长 30 秒生效
        </footer>
      </template>
    </section>

    <!-- 孤儿：父节点不存在。不单列的话它们在树里彻底消失 -->
    <section v-if="!filterActive && orphans.length > 0" class="table-card orphans">
      <div class="orphan-head">
        <h2 class="orphan-title">父组织不存在的节点</h2>
        <p class="field-note">
          这些行的 <code>parent_id</code> 指向一个不存在的组织，因此在树上挂不到任何一层。
          需要人工修正数据。
        </p>
      </div>
      <div class="table-wrap">
        <table class="data">
          <thead>
            <tr>
              <th scope="col">名称</th>
              <th scope="col">编码</th>
              <th scope="col">问题</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="org in orphans" :key="org.id">
              <td>{{ org.name }}</td>
              <td class="mono">{{ org.code }}</td>
              <td><span class="tag tag-warn">父 id={{ org.parentId }} 不存在</span></td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <!-- 新增 -->
    <div v-if="showCreate" class="modal-mask" @click.self="showCreate = false">
      <div class="modal" role="dialog" aria-modal="true">
        <h2 class="modal-title">新增组织</h2>
        <div class="form">
          <div class="two-col">
            <div class="field">
              <label class="label" for="o-code">编码</label>
              <input id="o-code" v-model="createForm.code" class="input" placeholder="如 华东" />
              <p class="field-note">创建后不可改：策略与外部同步按它映射。</p>
            </div>
            <div class="field">
              <label class="label" for="o-name">名称</label>
              <input id="o-name" v-model="createForm.name" class="input" />
            </div>
          </div>
          <div class="two-col">
            <div class="field">
              <span class="label">上级组织</span>
              <OrgTreeSelect
                v-model="createForm.parentId"
                :orgs="orgs"
                placeholder="作为根组织"
                empty-label="作为根组织"
              />
              <p class="field-note">选中的组织会成为新节点的父级；不选则挂在根上。</p>
            </div>
            <div class="field">
              <label class="label" for="o-sort">排序号</label>
              <input id="o-sort" v-model.number="createForm.sortNo" class="input" type="number" />
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

    <!-- 修改 -->
    <div v-if="editing" class="modal-mask" @click.self="editing = null">
      <div class="modal" role="dialog" aria-modal="true">
        <h2 class="modal-title">修改组织 · {{ editing.name }}</h2>
        <div class="form">
          <div class="two-col">
            <div class="field">
              <label class="label" for="eo-name">名称</label>
              <input id="eo-name" v-model="editForm.name" class="input" />
            </div>
            <div class="field">
              <label class="label" for="eo-sort">排序号</label>
              <input id="eo-sort" v-model.number="editForm.sortNo" class="input" type="number" />
            </div>
          </div>
          <label class="check">
            <input v-model="editForm.enabled" type="checkbox" />
            <span>启用</span>
          </label>
          <p class="field-note">
            编码与上级组织不可在此修改：编码被策略与外部同步按字符串引用；
            改父级要重写整棵子树的 <code>path</code>，还会影响按组织授予的角色。两者都需要单独做。
          </p>
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
  border-color: var(--accent);
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
  border-color: var(--accent);
  box-shadow: var(--focus-ring);
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
  vertical-align: middle;
}

.data th {
  font-size: 10.5px;
  font-weight: 600;
  letter-spacing: 0.04em;
  color: var(--ink-faint);
  background: var(--surface-sunk);
  white-space: nowrap;
}

.data tbody tr:last-child td {
  border-bottom: none;
}

.data tbody tr:hover td {
  background: #fafbff;
}

.tree-cell {
  display: inline-flex;
  align-items: center;
  gap: 9px;
}

.twist {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  flex: none;
  padding: 0;
  border: 1px solid var(--line-strong);
  border-radius: 6px;
  background: var(--surface);
  color: var(--ink-soft);
  font-size: 13px;
  line-height: 1;
  cursor: pointer;
}

.twist:hover {
  border-color: var(--accent);
  color: var(--accent-deep);
}

/* 叶子节点占位：宽度与 twist 一致，名字才会对齐 */
.twist-leaf {
  border-color: transparent;
  background: transparent;
  color: var(--ink-faint);
  cursor: default;
}

.org-name {
  font-size: 12.5px;
  font-weight: 600;
}

.code {
  display: inline-flex;
  padding: 3px 8px;
  border-radius: 6px;
  background: var(--surface-sunk);
  font-size: 10.5px;
  color: var(--ink-soft);
}

.created {
  font-size: 11px;
  color: var(--ink-faint);
  white-space: nowrap;
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

.act:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.dash {
  font-size: 11.5px;
  color: var(--ink-faint);
}

.table-foot {
  padding: 12px 18px;
  border-top: 1px solid var(--line);
  font-size: 10.5px;
  color: var(--ink-faint);
}

.orphans {
  margin-top: 18px;
}

.orphan-head {
  padding: 16px 18px;
  border-bottom: 1px solid var(--line);
}

.orphan-title {
  margin: 0 0 4px;
  font-size: 12.5px;
  font-weight: 650;
  color: var(--ink);
}

.check {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--text-sm);
  cursor: pointer;
}
</style>
