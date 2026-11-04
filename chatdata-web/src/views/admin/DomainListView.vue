<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  checkRoles,
  createDomain,
  deleteDomain,
  listDatasources,
  listDomains,
  listRoles,
  listTables,
  modelingCheck,
  parseColumns,
  parsePerm,
  updateDomain,
  type Datasource,
  type Domain,
  type DomainForm,
  type RoleOption,
} from '../../api/admin'
import RoleMultiSelect from '../../components/admin/RoleMultiSelect.vue'
import UserMultiSelect from '../../components/admin/UserMultiSelect.vue'
import { errorMessage } from '../../api/http'
import { usePermissions } from '../../composables/usePermissions'
import { P } from '../../permissions'
import {
  MASK_ACTION,
  ROW_OPERATORS,
  emptyDomainPolicy,
  nextRuleId,
  parseDomainPolicy,
  serializeDomainPolicy,
  validateDomainPolicy,
  type ColRule,
  type DomainPolicy,
  type RowRule,
} from './policyCodec'


/**
 * 语义域管理。
 *
 * ## 权限用结构化表单，不再手写 JSON
 *
 * 角色/用户从 IAM 目录点选；行规则与列规则用表格编辑。字段名可以从该域
 * 已注册的表列里选，读不到时仍可手填——不因为元数据不可用就堵住保存。
 *
 * ## 启用中的域必须配授权
 *
 * 后端会拒绝（这是 ADR-0002 的 R1：授权为空 = 对所有已认证用户开放）。
 * 表单上把这条写出来，免得管理员保存时才撞墙。
 *
 * ## 高级 JSON 仍在
 *
 * 表单覆盖不到的字段（未来新增、手工加的）不会被丢掉：解析时收进 extras，
 * 保存时原样带回。切到 JSON 模式时以 JSON 原文为准，切回来先校验。
 */

const { can } = usePermissions()
const domains = ref<Domain[]>([])
const datasources = ref<Datasource[]>([])
const loading = ref(false)
const listError = ref('')

const showForm = ref(false)
const editingId = ref<number | null>(null)
const form = ref<DomainForm>(emptyForm())
const policy = ref<DomainPolicy>(emptyDomainPolicy())
/** 高级 JSON 模式的原文；与结构化表单互斥，切换时同步。 */
const showAdvanced = ref(false)
const advancedJson = ref('')
/** 该域已注册的表列，用于行/列规则字段下拉。 */
const fieldOptions = ref<{ col: string; label: string }[]>([])

/**
 * IAM 角色目录里的角色，用于校验权限配置里填的角色名。
 *
 * 拿不到时给空数组——RoleHint 会退化成一句「无法校验」的提示，
 * 而不是把每个角色都报成"不存在"（那会吓得人不敢保存）。
 */
const allRoles = ref<RoleOption[]>([])
const saving = ref(false)
const formError = ref('')

const isEditing = computed(() => editingId.value !== null)

function emptyForm(): DomainForm {
  return {
    name: '',
    description: '',
    datasourceId: null,
    status: 'disabled',
    mode: 'MODELED',
    joinsJson: null,
    permJson: null,
    sortNo: 0,
  }
}

function pretty(doc: string): string {
  try {
    return JSON.stringify(JSON.parse(doc), null, 2)
  } catch {
    return doc
  }
}

/** 从 JSON 模式回到结构化模式；解析失败就留在 JSON 模式并报错。 */
function toggleAdvanced(): void {
  formError.value = ''
  if (!showAdvanced.value) {
    advancedJson.value = pretty(serializeDomainPolicy(policy.value))
    showAdvanced.value = true
    return
  }
  const parsed = parseDomainPolicy(advancedJson.value)
  if (parsed.error) {
    formError.value = `高级 JSON 无法解析：${parsed.error}`
    return
  }
  policy.value = parsed.policy
  showAdvanced.value = false
}

/** 把结构化表单拼回 perm_json；高级模式下以 JSON 原文为准。 */
function buildPermJson(): string {
  if (showAdvanced.value) {
    const parsed = parseDomainPolicy(advancedJson.value)
    if (parsed.error) {
      throw new Error(`高级 JSON 无法解析：${parsed.error}`)
    }
    policy.value = parsed.policy
    showAdvanced.value = false
    return serializeDomainPolicy(policy.value)
  }
  return serializeDomainPolicy(policy.value)
}

async function loadFields(domainId: number | null): Promise<void> {
  fieldOptions.value = []
  if (domainId === null) return
  try {
    const tables = await listTables(domainId)
    const seen = new Set<string>()
    const out: { col: string; label: string }[] = []
    for (const t of tables) {
      for (const c of parseColumns(t.columnsJson)) {
        if (!c.col || seen.has(c.col)) continue
        seen.add(c.col)
        out.push({ col: c.col, label: `${c.alias || c.col} · ${t.name}` })
      }
    }
    fieldOptions.value = out
  } catch {
    // 读不到字段不影响保存：规则字段仍可手填
    fieldOptions.value = []
  }
}

function addRowRule(): void {
  policy.value.rowRules.push({
    id: nextRuleId(policy.value.rowRules),
    role: '',
    field: '',
    op: '=',
    value: '',
    sysVar: '',
    dynamic: false,
  } satisfies RowRule)
}

function addColRule(): void {
  policy.value.colRules.push({
    id: nextRuleId(policy.value.colRules),
    role: '',
    field: '',
    action: MASK_ACTION,
  } satisfies ColRule)
}

function removeRowRule(id: number): void {
  policy.value.rowRules = policy.value.rowRules.filter((r) => r.id !== id)
}

function removeColRule(id: number): void {
  policy.value.colRules = policy.value.colRules.filter((r) => r.id !== id)
}

/** 规则行的角色提示：不存在是红、停用是黄。角色目录为空时不判断。 */
function roleTone(code: string): string {
  if (!code || allRoles.value.length === 0) return ''
  const role = allRoles.value.find((r) => r.code === code)
  if (!role) return 'row-bad'
  return role.enabled ? '' : 'row-warn'
}

/** 角色目录问题只提示、不阻断保存——与旧 RoleHint 的取舍一致。 */
const roleWarnings = computed(() => {
  if (allRoles.value.length === 0) return []
  const codes = new Set<string>([
    ...policy.value.grantRoles,
    ...policy.value.unrestrictedRoles,
    ...policy.value.rowRules.map((r) => r.role),
    ...policy.value.colRules.map((r) => r.role),
  ])
  const problems = checkRoles([...codes].filter(Boolean), allRoles.value)
  return [...problems.entries()]
    .filter(([, p]) => p !== null)
    .map(([code, p]) => `${code}（${p === 'missing' ? '不存在' : '已停用'}）`)
})

/**
 * 每个域的覆盖度：严重问题数 / 建议数。
 *
 * ## 为什么要在这里显示
 *
 * 管理员打开语义域列表，需要**一眼看出哪个域建得糙**。
 * 点进每个域去看一遍不现实——十几个域就是十几次点击。
 *
 * ## 为什么逐个调而不是批量接口
 *
 * 域不多（十几个），逐个调的总耗时可以接受。等域上百了再做批量——
 * 现在做是过早优化，而批量接口还要处理"部分失败"的语义。
 *
 * ## 失败的域不显示徽章
 *
 * 检查失败（网络、后端异常）时留空，而不是显示 "0"——
 * 显示 0 会让管理员以为"这个域没问题"，那是错误的信息。
 * 这里静默，列表本身的错误由 listError 统一报。
 */
const coverage = ref<Record<number, { errors: number; warnings: number }>>({})

/** 只有严重问题才是 ERROR 类别；其余都归为建议。与后端 Category 的枚举名一致。 */
const ERROR_CATEGORIES = new Set([
  'DOMAIN_NOT_LOADABLE', 'NO_GRANT', 'DATASOURCE_UNUSABLE', 'NO_VISIBLE_TABLE',
  'BROKEN_JOIN_REF', 'NO_FIELD', 'DANGLING_RULE_ROLE', 'RAW_DOMAIN_WITH_RULES',
  'BROKEN_DERIVED_METRIC', 'BROKEN_METRIC_CONDITION',
])

async function loadCoverage(list: Domain[]): Promise<void> {
  const out: Record<number, { errors: number; warnings: number }> = {}
  await Promise.all(list.map(async (d) => {
    // 直连域后端直接返回空，不必发请求（还能省一次往返）
    if (d.mode === 'RAW') {
      out[d.id] = { errors: 0, warnings: 0 }
      return
    }
    try {
      const vs = await modelingCheck(d.id)
      let e = 0
      let w = 0
      for (const v of vs) {
        if (ERROR_CATEGORIES.has(v.category)) e++
        else w++
      }
      out[d.id] = { errors: e, warnings: w }
    } catch {
      // 不写入 → 不显示徽章。见上面注释：显示 0 是错误信息
    }
  }))
  coverage.value = { ...out }
}

/** 徽章文案与色调。 */
function coverageOf(id: number): { errors: number; warnings: number } | null {
  return coverage.value[id] ?? null
}

async function load(): Promise<void> {
  loading.value = true
  listError.value = ''
  try {
    const [ds, dm] = await Promise.all([listDatasources(), listDomains()])
    datasources.value = ds
    domains.value = dm
    // 不 await：徽章是增强信息，不该拖慢列表首屏
    void loadCoverage(dm)
  } catch (e: unknown) {
    listError.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  // 角色表与域列表一起加载。**角色拿不到不影响页面可用**，故单独 catch
  listRoles()
    .then((r) => (allRoles.value = r))
    .catch(() => (allRoles.value = []))
  await load()
})

function startCreate(): void {
  editingId.value = null
  form.value = emptyForm()
  policy.value = emptyDomainPolicy()
  showAdvanced.value = false
  advancedJson.value = ''
  fieldOptions.value = []
  formError.value = ''
  showForm.value = true
}

function startEdit(d: Domain): void {
  editingId.value = d.id
  form.value = {
    name: d.name,
    description: '',
    datasourceId: d.datasourceId,
    status: d.status,
    // 老数据没有 mode 列时后端给 null，按建模模式处理（与后端的兜底一致）
    mode: d.mode === 'RAW' ? 'RAW' : 'MODELED',
    joinsJson: d.joinsJson,
    permJson: d.permJson,
    sortNo: 0,
  }
  const parsed = parseDomainPolicy(d.permJson)
  policy.value = parsed.policy
  showAdvanced.value = parsed.error !== ''
  advancedJson.value = parsed.error ? (d.permJson ?? '') : ''
  void loadFields(d.id)
  formError.value = parsed.error ? `现有策略无法解析，请直接在高级 JSON 中修复：${parsed.error}` : ''
  showForm.value = true
}

async function save(): Promise<void> {
  saving.value = true
  formError.value = ''
  try {
    form.value.permJson = buildPermJson()
  } catch (e: unknown) {
    formError.value = e instanceof Error ? e.message : '策略 JSON 格式不正确，请检查'
    saving.value = false
    return
  }

  const issues = validateDomainPolicy(policy.value)
  const all = [...issues.roles, ...issues.users, ...issues.rows, ...issues.cols]
  if (all.length > 0) {
    formError.value = all.join('；')
    saving.value = false
    return
  }

  try {
    if (editingId.value !== null) {
      await updateDomain(editingId.value, form.value)
    } else {
      await createDomain(form.value)
    }
    showForm.value = false
    await load()
  } catch (e: unknown) {
    formError.value = errorMessage(e)
  } finally {
    saving.value = false
  }
}

async function remove(d: Domain): Promise<void> {
  if (!window.confirm(`删除数据域「${d.name}」？它下面的注册表会一并删除。`)) return
  try {
    await deleteDomain(d.id)
    await load()
  } catch (e: unknown) {
    window.alert(errorMessage(e))
  }
}

function datasourceName(id: number | null): string {
  if (id === null) return '应用默认库'
  return datasources.value.find((d) => d.id === id)?.name ?? `#${id}`
}

/**
 * 列表里的策略状态。
 *
 * <p>这里曾显示 `v1` —— 那是 `perm_json` 的 **schema 版本号**（`{"v":1,...}`），
 * 由代码写死、不是可编辑的版本，也不是"改了几次"。于是每一行都是 v1，
 * 既不能点也不能改，看起来像坏掉的控件。
 *
 * 真正该告诉管理员的是：**这份策略能不能用、是宽还是严**。
 * 判据按 PRD §7 的 fail-closed 语义来：没有授权就是谁都进不去，是高危项。
 */
function policyState(d: Domain): { label: string; tone: string } {
  if (!d.permJson || !d.permJson.trim()) return { label: '未配置', tone: 'state-missing' }
  const parsed = parseDomainPolicy(d.permJson)
  if (parsed.error) return { label: '解析失败', tone: 'state-bad' }
  return { label: '已配置', tone: '' }
}

/** 列表里的授权摘要：公开 / 角色与用户 / 未配置。 */
function grantSummary(d: Domain): string {
  const perm = parsePerm(d.permJson)
  if (perm.isPublic === true) return '公开'
  const roles = (perm.grantRoles as string[] | undefined) ?? []
  const users = (perm.grantUsers as string[] | undefined) ?? []
  if (roles.length === 0 && users.length === 0) return '未配置'
  return [...roles, ...users.map((u) => `用户 ${u}`)].join('、')
}
</script>

<template>
  <section>
    <header class="page-head">
      <div>
        <h1 class="page-title">语义域</h1>
        <p class="sub">一次提问先路由到域，再在其中取数。启用中的域必须配置访问授权。</p>
      </div>
      <button v-if="can(P.ASK_ADMIN_DOMAINS_WRITE)" class="btn btn-primary" type="button" @click="startCreate">新增语义域</button>
    </header>

    <p v-if="listError" class="note note-danger" role="alert">{{ listError }}</p>
    <div v-else-if="loading" class="note">加载中…</div>
    <div v-else-if="domains.length === 0" class="note">还没有语义域。</div>

    <div v-else class="table-card">
      <table class="data">
        <thead>
          <tr>
            <th scope="col">名称</th>
            <th scope="col">数据源</th>
            <th scope="col">状态</th>
            <th scope="col" title="访问授权 + 行规则 + 列规则，在「编辑」里配置">策略</th>
            <th scope="col">授权</th>
            <th scope="col" title="建模覆盖度：缺 AVG / 缺去重计数 / 缺时间口径等。点开注册表后切到「覆盖度」看详情">
              覆盖度
            </th>
            <th scope="col" class="col-actions">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="d in domains" :key="d.id">
            <td class="strong">{{ d.name }}</td>
            <td class="sub">{{ datasourceName(d.datasourceId) }}</td>
            <td>
              <span class="state" :class="d.status === 'enabled' ? 'state-on' : 'state-off'">
                {{ d.status === 'enabled' ? '启用' : '停用' }}
              </span>
            </td>
            <td>
              <span class="state" :class="policyState(d).tone">{{ policyState(d).label }}</span>
            </td>
            <td class="sub">
              {{ grantSummary(d) }}
            </td>
            <td>
              <template v-if="coverageOf(d.id)">
                <span
                  v-if="coverageOf(d.id)!.errors === 0 && coverageOf(d.id)!.warnings === 0"
                  class="cov cov-ok"
                  title="没有发现明显短板"
                >✓</span>
                <template v-else>
                  <span
                    v-if="coverageOf(d.id)!.errors"
                    class="cov cov-error"
                    :title="`${coverageOf(d.id)!.errors} 个严重问题`"
                  >{{ coverageOf(d.id)!.errors }}</span>
                  <span
                    v-if="coverageOf(d.id)!.warnings"
                    class="cov cov-warn"
                    :title="`${coverageOf(d.id)!.warnings} 条改进建议`"
                  >{{ coverageOf(d.id)!.warnings }}</span>
                </template>
              </template>
              <span v-else class="cov-empty" title="直连模式不做建模检查">—</span>
            </td>
            <td class="col-actions">
              <RouterLink class="btn btn-quiet btn-sm" :to="`/admin/domains/${d.id}/tables`">
                注册表
              </RouterLink>
              <button v-if="can(P.ASK_ADMIN_DOMAINS_WRITE)" class="btn btn-quiet btn-sm" type="button" @click="startEdit(d)">编辑</button>
              <button v-if="can(P.ASK_ADMIN_DOMAINS_DELETE)" class="btn btn-danger btn-sm" type="button" @click="remove(d)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
      <footer class="table-foot">共 {{ domains.length }} 个语义域 · 启用中的域必须配置访问授权</footer>
    </div>

    <div v-if="showForm" class="modal-mask" @click.self="showForm = false">
      <div class="modal" role="dialog" aria-modal="true">
        <h2 class="modal-title">{{ isEditing ? '编辑语义域' : '新增语义域' }}</h2>

        <div class="form">
          <div class="two-col">
            <div class="field">
              <label class="label" for="dm-name">名称</label>
              <input id="dm-name" v-model="form.name" class="input" placeholder="如：保险分析域" />
            </div>
            <div class="field">
              <label class="label" for="dm-status">状态</label>
              <select id="dm-status" v-model="form.status" class="input">
                <option value="disabled">停用（草稿）</option>
                <option value="enabled">启用</option>
              </select>
            </div>

            <div class="field">
              <label class="label" for="dm-mode">模式</label>
              <select id="dm-mode" v-model="form.mode" class="input">
                <option value="MODELED">建模模式（语义建模，行/列权限生效）</option>
                <option value="RAW">直连模式（只勾表，模型自己写 SQL）</option>
              </select>
              <!-- 直连模式的能力降级必须写在这里，而不是藏在文档里：
                   管理员选它的时候就要知道「行/列规则配了也不生效」 -->
              <p v-if="form.mode === 'RAW'" class="field-note note note-warn">
                <strong>直连模式没有行级/列级权限。</strong>
                模型自己写 SQL，规则没有安全的注入点，因此
                <code>rowRules</code>/<code>colRules</code> 配了也不会生效（保存时会被拒绝）。
                敏感列请靠<strong>不把带敏感列的表勾进来</strong>来控制。
                该模式也不需要列别名、聚合、术语与主题——模型看的是库里的真实表结构。
              </p>
            </div>
          </div>

          <div class="field">
            <label class="label" for="dm-ds">绑定数据源</label>
            <select id="dm-ds" v-model="form.datasourceId" class="input">
              <option :value="null">应用默认库</option>
              <option v-for="d in datasources" :key="d.id" :value="d.id">
                {{ d.name }}（{{ d.dbType }}）
              </option>
            </select>
          </div>

          <fieldset v-if="!showAdvanced" class="grants">
            <legend class="label">访问授权</legend>

            <label class="checkbox">
              <input v-model="policy.isPublic" type="checkbox" />
              <span>公开（所有已认证用户可访问）</span>
            </label>

            <div class="field">
              <label class="label" for="dm-roles">授权角色</label>
              <RoleMultiSelect id="dm-roles" v-model="policy.grantRoles" :roles="allRoles" />
            </div>

            <div class="field">
              <label class="label" for="dm-users">授权用户</label>
              <UserMultiSelect id="dm-users" v-model="policy.grantUsers" />
            </div>

            <div class="field">
              <label class="label" for="dm-unrestricted">可见全量的角色</label>
              <RoleMultiSelect
                id="dm-unrestricted"
                v-model="policy.unrestrictedRoles"
                :roles="allRoles"
                placeholder="留空表示没有角色能看全量"
              />
              <p class="field-note">
                没有任何行规则的角色看不到数据。要看全量必须显式列在这里——
                厂商那套是"没配规则就看全部"，本项目改成显式声明。
              </p>
            </div>

            <p v-if="roleWarnings.length" class="field-note note note-warn" role="status">
              角色目录提示：{{ roleWarnings.join('、') }}。停用/不存在的角色引用不会生效，但仍可保存（便于先修策略）。
            </p>
          </fieldset>

          <fieldset v-if="!showAdvanced" class="grants">
            <legend class="label">行规则</legend>
            <p class="field-note">
              规则按<strong>角色</strong>匹配；同一用户命中多个角色时按并集。动态规则的变量取不到时，
              引擎会<strong>拒绝查询</strong>而不是跳过规则。
            </p>
            <div v-if="policy.rowRules.length === 0" class="rule-empty">没有行规则：只有「可见全量的角色」能看数据。</div>
            <div v-else class="rules">
              <div class="rule-head">
                <span>角色</span>
                <span>字段</span>
                <span>运算符</span>
                <span>条件值</span>
                <span />
              </div>
              <div v-for="r in policy.rowRules" :key="r.id" class="rule-row" :class="roleTone(r.role)">
                <select v-model="r.role" class="input" aria-label="角色">
                  <option value="">选择角色</option>
                  <option v-for="role in allRoles" :key="role.code" :value="role.code">
                    {{ role.name || role.code }}{{ role.enabled ? '' : '（已停用）' }}
                  </option>
                  <option v-if="r.role && !allRoles.some((x) => x.code === r.role)" :value="r.role">
                    {{ r.role }}（不存在）
                  </option>
                </select>
                <input v-model="r.field" class="input mono" list="dm-field-options" placeholder="region" aria-label="字段" />
                <select v-model="r.op" class="input" aria-label="运算符">
                  <option v-for="op in ROW_OPERATORS" :key="op" :value="op">{{ op }}</option>
                </select>
                <input
                  v-if="!r.dynamic"
                  v-model="r.value"
                  class="input"
                  placeholder="华东"
                  aria-label="条件值"
                />
                <input
                  v-else
                  v-model="r.sysVar"
                  class="input mono"
                  list="dm-sysvar-options"
                  placeholder="orgCode"
                  aria-label="动态变量"
                />
                <div class="rule-tail">
                  <label class="checkbox checkbox-sm" title="从权限上下文取变量，而不是写死值">
                    <input v-model="r.dynamic" type="checkbox" />
                    <span>动态</span>
                  </label>
                  <button type="button" class="btn btn-quiet btn-sm" @click="removeRowRule(r.id)">移除</button>
                </div>
              </div>
            </div>
            <button type="button" class="btn btn-quiet btn-sm rule-add" @click="addRowRule">+ 添加行规则</button>
          </fieldset>

          <fieldset v-if="!showAdvanced" class="grants">
            <legend class="label">列规则</legend>
            <p class="field-note">对指定角色的敏感列做脱敏。运算符/动作目前只有「脱敏」，保持与引擎一致。</p>
            <div v-if="policy.colRules.length === 0" class="rule-empty">没有列规则：授权角色能看到字段原值。</div>
            <div v-else class="rules rules-col">
              <div class="rule-head">
                <span>角色</span>
                <span>字段</span>
                <span>动作</span>
                <span />
              </div>
              <div v-for="r in policy.colRules" :key="r.id" class="rule-row" :class="roleTone(r.role)">
                <select v-model="r.role" class="input" aria-label="角色">
                  <option value="">选择角色</option>
                  <option v-for="role in allRoles" :key="role.code" :value="role.code">
                    {{ role.name || role.code }}{{ role.enabled ? '' : '（已停用）' }}
                  </option>
                  <option v-if="r.role && !allRoles.some((x) => x.code === r.role)" :value="r.role">
                    {{ r.role }}（不存在）
                  </option>
                </select>
                <input v-model="r.field" class="input mono" list="dm-field-options" placeholder="premium" aria-label="字段" />
                <span class="rule-fixed">{{ MASK_ACTION }}</span>
                <button type="button" class="btn btn-quiet btn-sm" @click="removeColRule(r.id)">移除</button>
              </div>
            </div>
            <button type="button" class="btn btn-quiet btn-sm rule-add" @click="addColRule">+ 添加列规则</button>
          </fieldset>

          <datalist id="dm-field-options">
            <option v-for="f in fieldOptions" :key="f.col" :value="f.col">{{ f.label }}</option>
          </datalist>
          <datalist id="dm-sysvar-options">
            <option value="orgCode">当前组织编码</option>
            <option value="userCode">当前用户账号</option>
            <option value="orgPath">当前组织路径（单组织，id 形式 /1/10/）</option>
            <option value="orgPaths">当前组织路径（含兼岗，多值，id 形式）</option>
            <option value="orgCodePath">当前组织编码路径（单组织，/HQ/华东/）</option>
            <option value="orgCodePaths">当前组织编码路径（含兼岗，多值）</option>
          </datalist>

          <div class="advanced">
            <button type="button" class="btn btn-quiet btn-sm" @click="toggleAdvanced">
              {{ showAdvanced ? '← 返回结构化编辑' : '高级：查看/编辑 JSON' }}
            </button>
            <template v-if="showAdvanced">
              <p class="field-note">
                保存时以这段 JSON 为准。未知字段会保留；切回结构化前会先校验。
              </p>
              <textarea v-model="advancedJson" class="input mono" rows="14" spellcheck="false" />
            </template>
          </div>
        </div>

        <p v-if="formError" class="note note-danger" role="alert">{{ formError }}</p>

        <div class="modal-actions">
          <div class="spacer" />
          <button class="btn btn-quiet" type="button" @click="showForm = false">取消</button>
          <button class="btn btn-primary" type="button" :disabled="saving" @click="save">
            {{ saving ? '保存中…' : '保存' }}
          </button>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.cov {
  display: inline-block;
  min-width: 20px;
  text-align: center;
  font-size: 0.75rem;
  font-weight: 700;
  border-radius: 999px;
  padding: 2px 7px;
  margin-right: 4px;
}
.cov-ok { background: #d1fae5; color: #047857; }
.cov-error { background: #fee2e2; color: #b91c1c; }
.cov-warn { background: #fef3c7; color: #b45309; }
.cov-empty { color: #cbd5e1; font-size: 0.78rem; }

.table-card {
  background: var(--surface);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
  /* 必须能横向滚动：表头每格都是 nowrap，加上「授权」列会列出全部角色，
     整张表常比卡片宽。用 overflow:hidden 会把右侧的「编辑/删除」直接裁掉，
     而且**没有任何滚动条**——看起来像按钮消失了。 */
  overflow-x: auto;
}

.strong {
  font-weight: 600;
}

.state-missing {
  color: var(--warn);
  background: var(--warn-soft, #fff4e0);
}

.state-bad {
  color: var(--danger, #b42318);
  background: var(--danger-soft, #fdecec);
}

.col-actions {
  text-align: right;
  white-space: nowrap;
}

.table-foot {
  padding: 12px 18px;
  border-top: 1px solid var(--line);
  font-size: 10.5px;
  color: var(--ink-faint);
}

.col-actions > * + * {
  margin-left: var(--space-2);
}

/* ── 弹层 ── */
/* 授权是一组相关字段，用 fieldset 圈起来——
   它们必须一起理解（比如"公开"会让下面三个列表失效） */
.grants {
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: var(--space-4);
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.grants legend {
  padding: 0 var(--space-2);
}

.checkbox {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--text-sm);
  cursor: pointer;
}

.spacer {
  flex: 1;
}

.rule-empty {
  padding: 8px 10px;
  border-radius: var(--radius);
  background: var(--surface-sunk);
  font-size: 11px;
  color: var(--ink-faint);
}

.rules {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.rule-head,
.rule-row {
  display: grid;
  grid-template-columns: 1.2fr 1.1fr 0.7fr 1.2fr 1fr;
  gap: 6px;
  align-items: center;
}

.rules-col .rule-head,
.rules-col .rule-row {
  grid-template-columns: 1.2fr 1.2fr 0.7fr 0.7fr;
}

.rule-head {
  font-size: 10px;
  color: var(--ink-faint);
}

.rule-row .input {
  font-size: 11px;
}

.row-warn {
  outline: 1px solid var(--warn, #b54708);
  border-radius: var(--radius);
}

.row-bad {
  outline: 1px solid var(--danger, #b42318);
  border-radius: var(--radius);
}

.rule-tail {
  display: flex;
  align-items: center;
  gap: 4px;
}

.checkbox-sm {
  gap: 3px;
  font-size: 10.5px;
  white-space: nowrap;
}

.rule-fixed {
  font-size: 11px;
  color: var(--ink-faint);
}

.rule-add {
  align-self: flex-start;
}

.advanced {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

@media (max-width: 720px) {
  .rule-head,
  .rule-row,
  .rules-col .rule-head,
  .rules-col .rule-row {
    grid-template-columns: 1fr;
  }

  .rule-head {
    display: none;
  }
}

@media (max-width: 560px) {
  .two-col {
    grid-template-columns: 1fr;
  }
}
</style>
