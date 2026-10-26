<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import {
  createTable,
  deleteTable,
  listDatasourceColumns,
  listDatasourceTables,
  listDomains,
  listTables,
  createTerm,
  createTopic,
  deleteTerm,
  deleteTopic,
  listTerms,
  listTopics,
  parseColumns,
  parseJoins,
  parseStringList,
  saveJoins,
  updateTerm,
  updateTopic,
  updateTable,
  type ColumnDef,
  type DerivedKind,
  type DomainTable,
  type DomainTableForm,
  type TableJoin,
  type TableMeta,
  type TermForm,
  type TermRow,
  type TopicForm,
  type TopicRow,
} from '../../api/admin'
import { errorMessage } from '../../api/http'
import { usePermissions } from '../../composables/usePermissions'
import { P } from '../../permissions'

import SqlExamplePanel from '../../components/admin/SqlExamplePanel.vue'

/**
 * 域内注册表。
 *
 * ## 列名是这里最容易出错的地方
 *
 * `col` 必须与数据库里的列名**逐字符一致**——渲染器原样加引用它，
 * 差一个字符就是 `Unknown column`，而那个报错要到用户提问时才出现。
 * 所以表单上把它标成必填并给出提示。
 *
 * 目前**没有**连库校验（拿 `DatabaseMetaData` 核对列是否存在）。
 * 那是元数据同步该做的事，属于已知缺口。
 */
const { can } = usePermissions()
const route = useRoute()
const domainId = Number(route.params['id'])

const domainName = ref('')
/**
 * 域的模式。SQL 范例只对**直连模式**有意义——建模模式的模型产出 DSL，
 * SQL 由服务端渲染，给它看 SQL 范例没有用（见 SqlExamplePanel 的说明）。
 */
const domainMode = ref<string>('MODELED')
/** 域绑定的数据源 id。为空表示用应用默认库，那时无法从库读元数据。 */
const domainDatasourceId = ref<number | null>(null)

// ── 术语 ──
const terms = ref<TermRow[]>([])
const termForm = ref<TermForm | null>(null)
const termEditingId = ref<number | null>(null)
const termsError = ref('')
const termsSaving = ref(false)

// ── 主题 ──
const topics = ref<TopicRow[]>([])
const topicForm = ref<TopicForm | null>(null)
const topicEditingId = ref<number | null>(null)
const topicsError = ref('')
const topicsSaving = ref(false)

// ── 表关联 ──
const joins = ref<TableJoin[]>([])
const joinDraft = ref<TableJoin | null>(null)
const joinsSaving = ref(false)
const joinsError = ref('')

/** 导入面板的状态。 */
const pickerOpen = ref(false)
const pickerTables = ref<TableMeta[]>([])
const pickerLoading = ref(false)
const pickerError = ref('')
const tables = ref<DomainTable[]>([])
const loading = ref(false)
const listError = ref('')

const showForm = ref(false)
const editingId = ref<number | null>(null)
const form = ref<DomainTableForm>(emptyForm())
const saving = ref(false)
const formError = ref('')

const isEditing = computed(() => editingId.value !== null)

/** 列的「类型」：普通列，或三种派生指标之一。 */
const DERIVED_KIND_OPTIONS: { value: '' | DerivedKind; label: string }[] = [
  { value: '', label: '普通列' },
  { value: 'ratio', label: '派生·比值' },
  { value: 'diff', label: '派生·差值' },
  { value: 'duration', label: '派生·时长' },
]

/** 统计条件的运算符。展示用 ≠ / ≥，传后端的是 ASCII 写法。 */
const CONDITION_OPS = [
  { value: '=', label: '= 等于' },
  { value: '!=', label: '≠ 不等于' },
  { value: '>', label: '> 大于' },
  { value: '<', label: '< 小于' },
  { value: '>=', label: '≥ 大于等于' },
  { value: '<=', label: '≤ 小于等于' },
  { value: 'in', label: 'in 属于（逗号分隔）' },
  { value: 'like', label: 'like 包含' },
]

const DURATION_UNITS = [
  { value: 'SECOND', label: '秒' },
  { value: 'MINUTE', label: '分钟' },
  { value: 'HOUR', label: '小时' },
  { value: 'DAY', label: '天' },
]

/** 派生输入的下拉候选项。`metric` 决定它能当分子分母还是当起止时间。 */
interface FieldOption {
  name: string
  table: string
  metric: boolean
}

const AGG_OPTIONS = [
  { value: '', label: '不聚合（维度）' },
  { value: 'SUM', label: 'SUM 求和' },
  { value: 'COUNT', label: 'COUNT 计数' },
  { value: 'COUNT_DISTINCT', label: 'COUNT DISTINCT 去重计数' },
  { value: 'AVG', label: 'AVG 平均' },
  { value: 'MAX', label: 'MAX 最大' },
  { value: 'MIN', label: 'MIN 最小' },
]

function emptyForm(): DomainTableForm {
  return {
    name: '',
    label: '',
    mode: 'TABLE',
    sqlText: null,
    columns: [{ col: '', type: '', agg: '', alias: '', enabled: true, derived: null }],
    enabled: true,
    sortNo: 0,
  }
}

async function load(): Promise<void> {
  loading.value = true
  listError.value = ''
  try {
    const [ds, ts] = await Promise.all([listDomains(), listTables(domainId)])
    const domain = ds.find((d) => d.id === domainId)
    if (domain) domainMode.value = domain.mode === 'RAW' ? 'RAW' : 'MODELED'
    domainName.value = domain?.name ?? `#${domainId}`
    domainDatasourceId.value = domain?.datasourceId ?? null
    joins.value = parseJoins(domain?.joinsJson ?? null)
    tables.value = ts

    const [tm, tp] = await Promise.all([listTerms(domainId), listTopics(domainId)])
    terms.value = tm
    topics.value = tp
  } catch (e: unknown) {
    listError.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)

function startCreate(): void {
  editingId.value = null
  form.value = emptyForm()
  formError.value = ''
  showForm.value = true
}

function startEdit(t: DomainTable): void {
  editingId.value = t.id
  form.value = {
    name: t.name,
    label: t.label ?? '',
    mode: t.mode,
    sqlText: t.sqlText,
    columns: parseColumns(t.columnsJson),
    enabled: t.enabled,
    sortNo: 0,
  }
  if (form.value.columns.length === 0) {
    form.value.columns = [{ col: '', type: '', agg: '', alias: '', enabled: true, derived: null }]
  }
  formError.value = ''
  showForm.value = true
}

function addColumn(): void {
  form.value.columns = [
    ...form.value.columns,
    { col: '', type: '', agg: '', alias: '', enabled: true, derived: null },
  ]
}

function addCondition(c: ColumnDef): void {
  c.condition = { field: '', op: '=', value: '' }
}

function removeCondition(c: ColumnDef): void {
  c.condition = null
}

/** 条件的人话写法，用于预览与列表展示。 */
function conditionText(c: ColumnDef): string {
  const d = c.condition
  if (!d) return ''
  const op = d.op === '!=' ? '≠' : d.op
  return `${d.field || '?'} ${op} ${d.value || '?'}`
}

function addDerivedColumn(): void {
  form.value.columns = [
    ...form.value.columns,
    { col: '', type: '', agg: '', alias: '', enabled: true, derived: { kind: 'ratio', left: '', right: '' } },
  ]
}

/** 这一列当前是普通列还是哪种派生指标。 */
function columnKind(c: ColumnDef): '' | DerivedKind {
  return c.derived?.kind ?? ''
}

function onKindChange(c: ColumnDef, event: Event): void {
  const raw = (event.target as HTMLSelectElement).value
  if (!raw) {
    c.derived = null
    return
  }
  const kind = raw as DerivedKind
  c.derived =
    kind === 'duration'
      ? { kind, start: '', end: '', unit: 'HOUR' }
      : { kind, left: '', right: '' }
  // 派生指标没有物理列，本身也已是聚合结果——这两项必须清空，
  // 否则保存时会被后端拒绝（规则与运行时同一份）
  c.col = ''
  c.agg = ''
  c.condition = null
}

/**
 * 可作派生输入的字段。
 *
 * 输入存的是**业务名**，所以下拉里给的是 `alias || col`——与问数时用的是同一个名字。
 * 跨表也可以：渲染器会把输入列所在的表 JOIN 进来。
 */
const fieldOptions = computed<FieldOption[]>(() => {
  const out: FieldOption[] = []
  const seen = new Set<string>()
  const push = (name: string, table: string, metric: boolean): void => {
    const n = (name ?? '').trim()
    if (!n || seen.has(n)) return
    seen.add(n)
    out.push({ name: n, table, metric })
  }
  for (const t of tables.value) {
    // 正在编辑的表用表单里的最新值，不用库里的旧值
    if (editingId.value !== null && t.id === editingId.value) continue
    for (const c of parseColumns(t.columnsJson)) {
      if (!c.enabled || c.derived) continue
      push(c.alias || c.col, t.label || t.name, !!c.agg)
    }
  }
  for (const c of form.value.columns) {
    if (!c.enabled || c.derived) continue
    push(c.alias || c.col, '本表', !!c.agg)
  }
  return out
})

/** 比值/差值的两侧必须是**指标**（带聚合方式）。 */
const metricOptions = computed(() => fieldOptions.value.filter((o) => o.metric))
/** 时长的起止必须是**维度**（原始时间列，不聚合）。 */
const dimensionOptions = computed(() => fieldOptions.value.filter((o) => !o.metric))

/** 派生指标的人话表达，用于编辑器预览与列表展示。 */
function derivedFormula(c: ColumnDef): string {
  const d = c.derived
  if (!d) return ''
  if (d.kind === 'duration') {
    const unit = DURATION_UNITS.find((u) => u.value === (d.unit || 'HOUR'))?.label ?? d.unit ?? ''
    return `${d.start || '?'} → ${d.end || '?'}，按${unit}取平均`
  }
  return `${d.left || '?'} ${d.kind === 'ratio' ? '÷' : '−'} ${d.right || '?'}`
}

function removeColumn(i: number): void {
  form.value.columns = form.value.columns.filter((_, idx) => idx !== i)
}

async function save(): Promise<void> {
  saving.value = true
  formError.value = ''
  try {
    const badCondition = form.value.columns.find(
      (c) => c.condition && (!c.condition.field || !(c.condition.value ?? '').trim()),
    )
    if (badCondition) {
      formError.value = `列「${badCondition.alias || badCondition.col}」的统计条件还没填完`
      return
    }
    const conditionOnDimension = form.value.columns.find(
      (c) => c.condition && !c.derived && !c.agg,
    )
    if (conditionOnDimension) {
      formError.value = `列「${conditionOnDimension.alias || conditionOnDimension.col}」配了统计条件，`
        + '但没有聚合方式——条件只对指标有意义'
      return
    }

    const nameless = form.value.columns.find(
      (c) => c.derived && (c.alias ?? '').trim() === '',
    )
    if (nameless) {
      formError.value = '派生指标必须填业务名——没有名字就永远匹配不上'
      return
    }
    const incomplete = form.value.columns.find((c) => {
      if (!c.derived) return false
      return c.derived.kind === 'duration'
        ? !c.derived.start || !c.derived.end
        : !c.derived.left || !c.derived.right
    })
    if (incomplete) {
      formError.value = `派生指标「${incomplete.alias}」还没选全输入列`
      return
    }

    // 丢掉空行：用户点了「添加一列」却没填，不该因此保存失败。
    // **派生指标没有物理列**，判空要看业务名而不是列名
    const cleaned: DomainTableForm = {
      ...form.value,
      columns: form.value.columns.filter((c) =>
        c.derived ? (c.alias ?? '').trim() !== '' : (c.col ?? '').trim() !== '',
      ),
    }
    if (editingId.value !== null) {
      await updateTable(editingId.value, cleaned)
    } else {
      await createTable(domainId, cleaned)
    }
    showForm.value = false
    await load()
  } catch (e: unknown) {
    formError.value = errorMessage(e)
  } finally {
    saving.value = false
  }
}

/**
 * 打开"从数据源导入"。
 *
 * 这是**列名校验之外的另一半**：与其事后校验，不如让管理员根本不用手打。
 * 上一轮那个不存在的列（org_code）就是手打的产物。
 */
async function openPicker(): Promise<void> {
  if (domainDatasourceId.value === null) {
    pickerError.value = '这个域绑定的是"应用默认库"，引擎读不到它的元数据。请先绑定一个数据源。'
    pickerOpen.value = true
    pickerTables.value = []
    return
  }
  pickerOpen.value = true
  pickerLoading.value = true
  pickerError.value = ''
  try {
    pickerTables.value = await listDatasourceTables(domainDatasourceId.value)
    if (pickerTables.value.length === 0) {
      pickerError.value = '数据源里没有读到任何表。'
    }
  } catch (e: unknown) {
    pickerError.value = errorMessage(e)
  } finally {
    pickerLoading.value = false
  }
}

/** 选中一张表：填表名、显示名，并把字段读出来预填。 */
async function pickTable(t: TableMeta): Promise<void> {
  if (domainDatasourceId.value === null) return
  pickerLoading.value = true
  pickerError.value = ''
  try {
    const cols = await listDatasourceColumns(domainDatasourceId.value, t.name)
    form.value.name = t.name
    // 显示名优先用表注释——它就是给业务看的
    form.value.label = t.comment || t.name
    form.value.columns = cols.map((c) => ({
      col: c.name,
      type: c.type,
      // 聚合方式留空：默认都是维度，需要当指标的由管理员自己改。
      // 猜一个 SUM 出来是危险的——金额列求和无所谓，比率列求和就是错的
      agg: '',
      alias: c.comment || c.name,
      enabled: true,
    }))
    pickerOpen.value = false
  } catch (e: unknown) {
    pickerError.value = errorMessage(e)
  } finally {
    pickerLoading.value = false
  }
}


// ── 术语 ──

function startAddTerm(): void {
  termEditingId.value = null
  // applyKind 固定 none —— 另外两种需要渲染侧支持才会生效，后端也会拒绝。
  // 见 TermInput.validated 的说明
  termForm.value = { name: '', synonyms: [], description: '', applyKind: 'none' }
  termsError.value = ''
}

function startEditTerm(t: TermRow): void {
  termEditingId.value = t.id
  termForm.value = {
    name: t.name,
    synonyms: parseStringList(t.synonymsJson),
    description: t.description ?? '',
    applyKind: 'none',
  }
  termsError.value = ''
}

function setSynonyms(raw: string): void {
  if (termForm.value) {
    termForm.value.synonyms = raw
      .split(/[,，、;；\s]+/)
      .map((x) => x.trim())
      .filter(Boolean)
  }
}

function synonymsText(t: TermForm): string {
  return t.synonyms.join('、')
}

async function saveTerm(): Promise<void> {
  const f = termForm.value
  if (!f) return
  termsSaving.value = true
  termsError.value = ''
  try {
    if (termEditingId.value !== null) {
      await updateTerm(termEditingId.value, f)
    } else {
      await createTerm(domainId, f)
    }
    termForm.value = null
    terms.value = await listTerms(domainId)
  } catch (e: unknown) {
    termsError.value = errorMessage(e)
  } finally {
    termsSaving.value = false
  }
}

async function removeTerm(t: TermRow): Promise<void> {
  if (!window.confirm(`删除术语「${t.name}」？`)) return
  try {
    await deleteTerm(t.id)
    terms.value = await listTerms(domainId)
  } catch (e: unknown) {
    window.alert(errorMessage(e))
  }
}

// ── 主题 ──

/** 域内全部「表名.列名」，供主题选字段。 */
function allFieldRefs(): string[] {
  const out: string[] = []
  for (const t of tables.value) {
    for (const c of parseColumns(t.columnsJson)) {
      out.push(`${t.name}.${c.col}`)
    }
  }
  return out
}

function startAddTopic(): void {
  topicEditingId.value = null
  topicForm.value = { name: '', description: '', status: 'draft', fields: [] }
  topicsError.value = ''
}

function startEditTopic(t: TopicRow): void {
  topicEditingId.value = t.id
  topicForm.value = {
    name: t.name,
    description: t.description ?? '',
    status: t.status,
    fields: parseStringList(t.fieldsJson),
  }
  topicsError.value = ''
}

function toggleTopicField(ref: string): void {
  const f = topicForm.value
  if (!f) return
  f.fields = f.fields.includes(ref) ? f.fields.filter((x) => x !== ref) : [...f.fields, ref]
}

async function saveTopic(): Promise<void> {
  const f = topicForm.value
  if (!f) return
  topicsSaving.value = true
  topicsError.value = ''
  try {
    if (topicEditingId.value !== null) {
      await updateTopic(topicEditingId.value, f)
    } else {
      await createTopic(domainId, f)
    }
    topicForm.value = null
    topics.value = await listTopics(domainId)
  } catch (e: unknown) {
    topicsError.value = errorMessage(e)
  } finally {
    topicsSaving.value = false
  }
}

async function removeTopic(t: TopicRow): Promise<void> {
  if (!window.confirm(`删除主题「${t.name}」？`)) return
  try {
    await deleteTopic(t.id)
    topics.value = await listTopics(domainId)
  } catch (e: unknown) {
    window.alert(errorMessage(e))
  }
}

// ── 表关联 ──

/** 某张注册表的字段名，供连接列下拉用。 */
function columnsOf(tableId: string): string[] {
  const t = tables.value.find((x) => String(x.id) === tableId)
  return t ? parseColumns(t.columnsJson).map((c) => c.col).filter((c) => c !== '') : []
}

function startAddJoin(): void {
  const first = tables.value[0]
  const second = tables.value[1]
  if (!first || !second) {
    joinsError.value = '至少要有两张注册表才能建关联。'
    return
  }
  joinsError.value = ''
  joinDraft.value = {
    id: Date.now(),
    left: String(first.id),
    right: String(second.id),
    leftCol: '',
    rightCol: '',
    // 默认 INNER：保单必有对应客户这类关系最常用。
    // LEFT 会让"没有匹配行"的一侧保留，语义不同，需要管理员有意选
    type: 'INNER JOIN',
  }
}

function editJoin(j: TableJoin): void {
  joinsError.value = ''
  joinDraft.value = { ...j }
}

function cancelJoin(): void {
  joinDraft.value = null
  joinsError.value = ''
}

function commitJoin(): void {
  const d = joinDraft.value
  if (!d) return
  if (!d.leftCol || !d.rightCol) {
    joinsError.value = '请选择两侧的连接列'
    return
  }
  const exists = joins.value.some((j) => j.id === d.id)
  joins.value = exists
    ? joins.value.map((j) => (j.id === d.id ? d : j))
    : [...joins.value, d]
  joinDraft.value = null
  joinsError.value = ''
}

function removeJoin(id: number): void {
  joins.value = joins.value.filter((j) => j.id !== id)
}

async function persistJoins(): Promise<void> {
  joinsSaving.value = true
  joinsError.value = ''
  try {
    await saveJoins(domainId, JSON.stringify(joins.value))
  } catch (e: unknown) {
    joinsError.value = errorMessage(e)
  } finally {
    joinsSaving.value = false
  }
}

function tableLabel(id: string): string {
  const t = tables.value.find((x) => String(x.id) === id)
  return t ? t.label || t.name : `#${id}`
}

async function remove(t: DomainTable): Promise<void> {
  if (!window.confirm(`删除注册表「${t.label || t.name}」？`)) return
  try {
    await deleteTable(t.id)
    await load()
  } catch (e: unknown) {
    window.alert(errorMessage(e))
  }
}
</script>

<template>
  <section>
    <header class="page-head">
      <div>
        <RouterLink class="back" to="/admin/domains">← 语义域</RouterLink>
        <h1 class="page-title">{{ domainName }} · 注册表</h1>
        <p class="sub">
          这里声明域内可问的表与字段。字段名必须与数据库里的列名逐字符一致。
        </p>
      </div>
      <button v-if="can(P.ASK_ADMIN_DOMAINS_TABLES)" class="btn btn-primary" type="button" @click="startCreate">新增表</button>
    </header>

    <p v-if="listError" class="note note-danger" role="alert">{{ listError }}</p>
    <div v-else-if="loading" class="note">加载中…</div>
    <div v-else-if="tables.length === 0" class="note">
      这个域还没有注册表。没有表就问不出任何数据。
    </div>

    <div v-else class="cards">
      <article v-for="t in tables" :key="t.id" class="card">
        <div class="card-head">
          <h2 class="card-title">{{ t.label || t.name }}</h2>
          <span class="tag">{{ t.mode === 'SQL' ? 'SQL 虚拟表' : '物理表' }}</span>
          <span v-if="!t.enabled" class="tag tag-off">对 LLM 不可见</span>
        </div>

        <p class="mono sub">{{ t.name }}</p>

        <ul class="cols">
          <li v-for="(c, idx) in parseColumns(t.columnsJson)" :key="idx" class="col-item">
            <span class="col-alias">{{ c.alias || c.col }}</span>
            <span v-if="c.derived" class="col-derived mono">{{ derivedFormula(c) }}</span>
            <span v-else class="col-name mono">{{ c.col }}</span>
            <span v-if="c.agg" class="col-agg">{{ c.agg }}</span>
            <span v-if="c.condition" class="col-cond">仅 {{ conditionText(c) }}</span>
            <span v-if="!c.enabled" class="col-off">已关闭</span>
          </li>
          <li v-if="parseColumns(t.columnsJson).length === 0" class="sub">（没有列定义）</li>
        </ul>

        <div class="card-actions">
          <button v-if="can(P.ASK_ADMIN_DOMAINS_TABLES)" class="btn btn-quiet btn-sm" type="button" @click="startEdit(t)">编辑</button>
          <button v-if="can(P.ASK_ADMIN_DOMAINS_TABLES)" class="btn btn-danger btn-sm" type="button" @click="remove(t)">删除</button>
        </div>
      </article>
    </div>



    <!-- ── 业务术语 ──
         实测问题的正解：模型会跟着用户的措辞走（问「保费合计」产出
         metrics:["保费合计"]），而登记的别名是「保费」。术语让它在<strong>一开始</strong>
         就知道那些说法指同一个字段，不必等渲染失败再重试。 -->
    <section class="joins">
      <header class="joins-head">
        <div>
          <h2 class="joins-title">业务术语</h2>
          <p class="sub">
            登记用户可能用的各种说法，让模型翻译成登记字段。近义词与释义都会进 prompt。
          </p>
        </div>
        <button v-if="can(P.ASK_ADMIN_DOMAINS_TABLES)" class="btn btn-quiet" type="button" @click="startAddTerm">新增术语</button>
      </header>

      <p v-if="termsError" class="note note-danger" role="alert">{{ termsError }}</p>

      <ul v-if="terms.length" class="term-list">
        <li v-for="t in terms" :key="t.id" class="term-item">
          <div class="term-main">
            <span class="term-name">{{ t.name }}</span>
            <span v-if="parseStringList(t.synonymsJson).length" class="term-syn">
              用户也可能说：{{ parseStringList(t.synonymsJson).join('、') }}
            </span>
          </div>
          <p v-if="t.description" class="term-desc">{{ t.description }}</p>
          <div class="join-buttons">
            <button v-if="can(P.ASK_ADMIN_DOMAINS_TABLES)" class="btn btn-quiet btn-sm" type="button" @click="startEditTerm(t)">编辑</button>
            <button v-if="can(P.ASK_ADMIN_DOMAINS_TABLES)" class="btn btn-danger btn-sm" type="button" @click="removeTerm(t)">删除</button>
          </div>
        </li>
      </ul>
      <p v-else class="note">还没有术语。没有它时，用户换个说法问同一个指标会失败。</p>

      <div v-if="termForm" class="term-editor">
        <div class="two-col">
          <div class="field">
            <label class="label" for="tm-name">术语名</label>
            <input id="tm-name" v-model="termForm.name" class="input" placeholder="如：保费" />
          </div>
          <div class="field">
            <label class="label" for="tm-syn">近义词</label>
            <input
              id="tm-syn"
              class="input"
              :value="synonymsText(termForm)"
              placeholder="多个用、分隔，如：保费合计、保费金额"
              @input="setSynonyms(($event.target as HTMLInputElement).value)"
            />
          </div>
        </div>
        <div class="field">
          <label class="label" for="tm-desc">释义</label>
          <input id="tm-desc" v-model="termForm.description" class="input" placeholder="写给模型看的说明" />
        </div>
        <p class="field-note">
          应用方式固定为「仅释义」。「时间口径」与「数据筛选」需要渲染侧支持才会生效，
          当前尚未接入——提供点了不生效的选项比不提供更糟。
        </p>
        <div class="join-buttons">
          <button class="btn btn-quiet btn-sm" type="button" @click="termForm = null">取消</button>
          <button class="btn btn-primary btn-sm" type="button" :disabled="termsSaving" @click="saveTerm">
            {{ termsSaving ? '保存中…' : '保存' }}
          </button>
        </div>
      </div>
    </section>

    <!-- ── 查询主题 ── -->
    <section class="joins">
      <header class="joins-head">
        <div>
          <h2 class="joins-title">查询主题</h2>
          <p class="sub">
            把一次问数的字段范围收敛到一个子集。表多时能缩短 prompt 并降低选错表的概率。
            <strong>不是限制</strong>——模型仍可用主题之外的字段。
          </p>
        </div>
        <button v-if="can(P.ASK_ADMIN_DOMAINS_TABLES)" class="btn btn-quiet" type="button" @click="startAddTopic">新增主题</button>
      </header>

      <p v-if="topicsError" class="note note-danger" role="alert">{{ topicsError }}</p>

      <ul v-if="topics.length" class="term-list">
        <li v-for="t in topics" :key="t.id" class="term-item">
          <div class="term-main">
            <span class="term-name">{{ t.name }}</span>
            <span class="tag" :class="t.status === 'published' ? '' : 'tag-off'">
              {{ t.status === 'published' ? '已发布' : '草稿' }}
            </span>
            <span class="term-syn">{{ parseStringList(t.fieldsJson).length }} 个字段</span>
          </div>
          <p v-if="t.description" class="term-desc">{{ t.description }}</p>
          <p v-if="parseStringList(t.fieldsJson).length" class="term-fields mono">
            {{ parseStringList(t.fieldsJson).join('、') }}
          </p>
          <div class="join-buttons">
            <button v-if="can(P.ASK_ADMIN_DOMAINS_TABLES)" class="btn btn-quiet btn-sm" type="button" @click="startEditTopic(t)">编辑</button>
            <button v-if="can(P.ASK_ADMIN_DOMAINS_TABLES)" class="btn btn-danger btn-sm" type="button" @click="removeTopic(t)">删除</button>
          </div>
        </li>
      </ul>
      <p v-else class="note">还没有主题。只有草稿不进 prompt，发布后才生效。</p>

      <div v-if="topicForm" class="term-editor">
        <div class="two-col">
          <div class="field">
            <label class="label" for="tp-name">主题名</label>
            <input id="tp-name" v-model="topicForm.name" class="input" placeholder="如：保费分析" />
          </div>
          <div class="field">
            <label class="label" for="tp-status">状态</label>
            <select id="tp-status" v-model="topicForm.status" class="input">
              <option value="draft">草稿（不进 prompt）</option>
              <option value="published">已发布</option>
            </select>
          </div>
        </div>
        <div class="field">
          <label class="label" for="tp-desc">描述</label>
          <input id="tp-desc" v-model="topicForm.description" class="input" />
        </div>
        <div class="field">
          <span class="label">常用字段</span>
          <div class="field-picker">
            <button
              v-for="ref in allFieldRefs()"
              :key="ref"
              type="button"
              class="chip"
              :class="{ 'chip-on': topicForm.fields.includes(ref) }"
              @click="toggleTopicField(ref)"
            >
              {{ ref }}
            </button>
            <span v-if="allFieldRefs().length === 0" class="sub">还没有注册表，先配表。</span>
          </div>
        </div>
        <div class="join-buttons">
          <button class="btn btn-quiet btn-sm" type="button" @click="topicForm = null">取消</button>
          <button class="btn btn-primary btn-sm" type="button" :disabled="topicsSaving" @click="saveTopic">
            {{ topicsSaving ? '保存中…' : '保存' }}
          </button>
        </div>
      </div>
    </section>

    <!-- ── 表关联 ──
         没有它，注册了多张表也连不起来，多表问题必然失败。
         放在注册表页面而不是语义域表单里，因为编辑关联要看到表与字段。 -->
    <section class="joins">
      <header class="joins-head">
        <div>
          <h2 class="joins-title">表关联</h2>
          <p class="sub">
            定义表之间怎么连。多表问题靠它——没配关联的表查不到一起。
          </p>
        </div>
        <div class="joins-actions">
          <button
            class="btn btn-quiet"
            type="button"
            :disabled="joinsSaving || tables.length < 2"
            @click="startAddJoin"
          >
            新增关联
          </button>
          <button
            class="btn btn-primary"
            type="button"
            :disabled="joinsSaving"
            @click="persistJoins"
          >
            {{ joinsSaving ? '保存中…' : '保存关联' }}
          </button>
        </div>
      </header>

      <p v-if="joinsError" class="note note-danger" role="alert">{{ joinsError }}</p>

      <p v-if="tables.length < 2" class="note">
        至少要有两张注册表才能建关联。
      </p>

      <ul v-else-if="joins.length" class="join-list">
        <li v-for="j in joins" :key="j.id" class="join-item">
          <span class="join-table">{{ tableLabel(j.left) }}</span>
          <span class="mono join-col">{{ j.leftCol || '?' }}</span>
          <span class="join-op">{{ j.type === 'LEFT JOIN' ? '⟕' : '⋈' }}</span>
          <span class="join-table">{{ tableLabel(j.right) }}</span>
          <span class="mono join-col">{{ j.rightCol || '?' }}</span>
          <span class="join-type">{{ j.type }}</span>
          <span class="join-buttons">
            <button v-if="can(P.ASK_ADMIN_DOMAINS_TABLES)" class="btn btn-quiet btn-sm" type="button" @click="editJoin(j)">编辑</button>
            <button class="btn btn-danger btn-sm" type="button" @click="removeJoin(j.id)">
              删除
            </button>
          </span>
        </li>
      </ul>

      <p v-else class="note">还没有关联。单表问题不受影响，多表问题会失败。</p>

      <!-- 编辑一条关联。用内联行而不是弹层：只有四个下拉，弹层反而更绕 -->
      <div v-if="joinDraft" class="join-editor">
        <div class="join-side">
          <label class="label">左侧表与列</label>
          <select v-model="joinDraft.left" class="input" @change="joinDraft.leftCol = ''">
            <option v-for="t in tables" :key="t.id" :value="String(t.id)">
              {{ t.label || t.name }}
            </option>
          </select>
          <select v-model="joinDraft.leftCol" class="input mono">
            <option value="">选择列…</option>
            <option v-for="c in columnsOf(joinDraft.left)" :key="c" :value="c">{{ c }}</option>
          </select>
        </div>

        <div class="join-mid">
          <label class="label">方式</label>
          <select v-model="joinDraft.type" class="input">
            <option value="INNER JOIN">INNER 内连接</option>
            <option value="LEFT JOIN">LEFT 左连接</option>
          </select>
          <p class="field-note">
            LEFT 会保留左表全部行；INNER 只保留两边都匹配的行。
            <strong>选错不报错，只是行数不同</strong>。
          </p>
        </div>

        <div class="join-side">
          <label class="label">右侧表与列</label>
          <select v-model="joinDraft.right" class="input" @change="joinDraft.rightCol = ''">
            <option v-for="t in tables" :key="t.id" :value="String(t.id)">
              {{ t.label || t.name }}
            </option>
          </select>
          <select v-model="joinDraft.rightCol" class="input mono">
            <option value="">选择列…</option>
            <option v-for="c in columnsOf(joinDraft.right)" :key="c" :value="c">{{ c }}</option>
          </select>
        </div>

        <div class="join-buttons">
          <button class="btn btn-quiet btn-sm" type="button" @click="cancelJoin">取消</button>
          <button class="btn btn-primary btn-sm" type="button" @click="commitJoin">确定</button>
        </div>
      </div>
    </section>

    <div v-if="showForm" class="modal-mask" @click.self="showForm = false">
      <div class="modal" role="dialog" aria-modal="true">
        <h2 class="modal-title">{{ isEditing ? '编辑注册表' : '新增注册表' }}</h2>

        <div class="form">
          <div class="two-col">
            <div class="field">
              <label class="label" for="tb-name">表名</label>
              <input id="tb-name" v-model="form.name" class="input mono" placeholder="ins_policy" />
            </div>
            <div class="field">
              <label class="label" for="tb-label">显示名</label>
              <input id="tb-label" v-model="form.label" class="input" placeholder="保单表" />
            </div>
          </div>

          <div class="two-col">
            <div class="field">
              <label class="label" for="tb-mode">类型</label>
              <select id="tb-mode" v-model="form.mode" class="input">
                <option value="TABLE">物理表</option>
                <option value="SQL">SQL 虚拟表</option>
              </select>
            </div>
            <label class="checkbox self-end">
              <input v-model="form.enabled" type="checkbox" />
              <span>对 LLM 可见</span>
            </label>
          </div>

          <div v-if="form.mode === 'SQL'" class="field">
            <label class="label" for="tb-sql">SQL 定义</label>
            <textarea id="tb-sql" v-model="form.sqlText" class="input mono" rows="3" />
          </div>

          <fieldset class="cols-editor">
            <legend class="label">字段</legend>
            <div class="import-row">
              <button class="btn btn-quiet btn-sm" type="button" @click="openPicker">
                从数据源导入字段
              </button>
              <span class="field-note inline">
                连上数据源读真实表结构，直接选中而不是手打——手打列名是上一轮出过错的地方
              </span>
            </div>
            <p class="field-note">
              <strong>列名</strong>要与数据库里的列名一致（渲染时原样引用，差一个字符就查不到）。
              <strong>业务名</strong>是问数时用户与模型使用的名字。
              带聚合方式的只能当指标，不带的只能当维度。
              <strong>派生指标</strong>由已有指标或时间列算出（比值/差值/时长），没有物理列，
              模型只按名字引用——口径在这里定死，不交给模型拼算式。
            </p>

            <div class="col-head">
              <span>列名</span>
              <span>业务名</span>
              <span>类型</span>
              <span>聚合方式</span>
              <span>启用</span>
              <span />
            </div>

            <div v-for="(c, i) in form.columns" :key="i" class="col-block">
              <div class="col-row">
                <input
                  v-model="c.col"
                  class="input mono"
                  :disabled="!!c.derived"
                  :placeholder="c.derived ? '（派生指标无物理列）' : 'premium'"
                />
                <input v-model="c.alias" class="input" placeholder="保费" />
                <select class="input" :value="columnKind(c)" @change="onKindChange(c, $event)">
                  <option v-for="k in DERIVED_KIND_OPTIONS" :key="k.value" :value="k.value">
                    {{ k.label }}
                  </option>
                </select>
                <select v-model="c.agg" class="input" :disabled="!!c.derived">
                  <option v-for="a in AGG_OPTIONS" :key="a.value" :value="a.value">
                    {{ a.label }}
                  </option>
                </select>
                <input v-model="c.enabled" type="checkbox" />
                <button class="btn btn-quiet btn-sm" type="button" @click="removeColumn(i)">
                  移除
                </button>
              </div>

              <!-- 统计条件：只影响这一列的聚合，用 CASE 渲染 -->
              <div v-if="c.condition" class="derived-row">
                <label class="derived-slot">
                  <span>条件字段</span>
                  <select v-model="c.condition.field" class="input mono">
                    <option value="">选择字段…</option>
                    <option v-for="o in fieldOptions" :key="o.name" :value="o.name">
                      {{ o.name }} · {{ o.table }}
                    </option>
                  </select>
                </label>
                <label class="derived-slot">
                  <span>运算符</span>
                  <select v-model="c.condition.op" class="input">
                    <option v-for="o in CONDITION_OPS" :key="o.value" :value="o.value">
                      {{ o.label }}
                    </option>
                  </select>
                </label>
                <label class="derived-slot">
                  <span>取值</span>
                  <input v-model="c.condition.value" class="input" placeholder="已解决" />
                </label>
                <p class="derived-formula mono">
                  仅统计满足「{{ conditionText(c) }}」的行
                  <button class="link-btn" type="button" @click="removeCondition(c)">移除条件</button>
                </p>
              </div>
              <button
                v-else-if="!c.derived && c.agg"
                class="link-btn cond-add"
                type="button"
                @click="addCondition(c)"
              >
                ＋ 统计条件
              </button>

              <!-- 派生指标的输入：只有选全了才保存得下去 -->
              <div v-if="c.derived" class="derived-row">
                <template v-if="c.derived.kind === 'duration'">
                  <label class="derived-slot">
                    <span>起始时间</span>
                    <select v-model="c.derived.start" class="input mono">
                      <option value="">选择列…</option>
                      <option v-for="o in dimensionOptions" :key="o.name" :value="o.name">
                        {{ o.name }} · {{ o.table }}
                      </option>
                    </select>
                  </label>
                  <label class="derived-slot">
                    <span>结束时间</span>
                    <select v-model="c.derived.end" class="input mono">
                      <option value="">选择列…</option>
                      <option v-for="o in dimensionOptions" :key="o.name" :value="o.name">
                        {{ o.name }} · {{ o.table }}
                      </option>
                    </select>
                  </label>
                  <label class="derived-slot">
                    <span>单位</span>
                    <select v-model="c.derived.unit" class="input">
                      <option v-for="u in DURATION_UNITS" :key="u.value" :value="u.value">
                        {{ u.label }}
                      </option>
                    </select>
                  </label>
                </template>
                <template v-else>
                  <label class="derived-slot">
                    <span>{{ c.derived.kind === 'ratio' ? '分子' : '被减数' }}</span>
                    <select v-model="c.derived.left" class="input mono">
                      <option value="">选择指标…</option>
                      <option v-for="o in metricOptions" :key="o.name" :value="o.name">
                        {{ o.name }} · {{ o.table }}
                      </option>
                    </select>
                  </label>
                  <label class="derived-slot">
                    <span>{{ c.derived.kind === 'ratio' ? '分母' : '减数' }}</span>
                    <select v-model="c.derived.right" class="input mono">
                      <option value="">选择指标…</option>
                      <option v-for="o in metricOptions" :key="o.name" :value="o.name">
                        {{ o.name }} · {{ o.table }}
                      </option>
                    </select>
                  </label>
                </template>
                <p class="derived-formula mono">＝ {{ derivedFormula(c) }}</p>
              </div>
            </div>

            <div class="add-col">
              <button class="btn btn-quiet btn-sm" type="button" @click="addColumn">
                + 添加一列
              </button>
              <button class="btn btn-quiet btn-sm" type="button" @click="addDerivedColumn">
                + 添加派生指标
              </button>
            </div>
          </fieldset>
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

    <!-- 导入面板：列出数据源里的表，选中后回填表单。
         做成独立的弹层而不是塞进表单里——它是"选一张表"，与"编辑这张表"是两件事 -->
    <div v-if="pickerOpen" class="modal-mask" @click.self="pickerOpen = false">
      <div class="modal picker" role="dialog" aria-modal="true" aria-label="从数据源选择表">
        <h2 class="modal-title">从数据源选择表</h2>

        <p v-if="pickerLoading" class="note">读取中…</p>
        <p v-else-if="pickerError" class="note note-danger" role="alert">{{ pickerError }}</p>

        <ul v-else class="table-list">
          <li v-for="t in pickerTables" :key="t.name">
            <button type="button" class="table-item" @click="pickTable(t)">
              <span class="table-name mono">{{ t.name }}</span>
              <span v-if="t.comment" class="table-comment">{{ t.comment }}</span>
              <span class="table-type">{{ t.type }}</span>
            </button>
          </li>
        </ul>

        <div class="modal-actions">
          <div class="spacer" />
          <button class="btn btn-quiet" type="button" @click="pickerOpen = false">取消</button>
        </div>
      </div>
    </div>
  </section>

  <!-- SQL 范例只给直连模式：建模模式的模型产出 DSL，SQL 由我们渲染，
       给它看 SQL 范例没有意义 -->
  <SqlExamplePanel v-if="domainMode === 'RAW'" :domain-id="domainId" />

</template>

<style scoped>
.back {
  display: inline-block;
  margin-bottom: var(--space-2);
  font-size: var(--text-sm);
  color: var(--ink-soft);
  text-decoration: none;
}

.back:hover {
  color: var(--ink);
}

.cols {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.col-item {
  display: flex;
  align-items: baseline;
  gap: var(--space-2);
  font-size: var(--text-sm);
  padding: 0.15em 0;
}

.col-alias {
  font-weight: 550;
  min-width: 5em;
}

.col-name {
  color: var(--ink-faint);
}

/* 派生指标没有列名，显示成算式 */
.col-derived {
  color: var(--ink-faint);
  font-size: var(--text-xs);
}

.col-agg {
  padding: 0 0.4em;
  border-radius: 3px;
  background: var(--accent-soft);
  color: var(--accent-deep);
  font-size: var(--text-xs);
  font-weight: 600;
}

.col-off {
  color: var(--warn);
  font-size: var(--text-xs);
}

.col-cond {
  color: var(--ink-faint);
  font-size: var(--text-xs);
}

/* ── 弹层 ── */
.self-end {
  align-self: end;
  padding-bottom: 0.6em;
}

.cols-editor {
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: var(--space-4);
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.cols-editor legend {
  padding: 0 var(--space-2);
}

/* 列编辑器用固定栅格对齐，让每行字段纵向可读 */
.col-head,
.col-row {
  display: grid;
  grid-template-columns: 1.1fr 0.9fr 7.5em 1.3fr 3.5em 4.5em;
  gap: var(--space-2);
  align-items: center;
}

/* 一列占两行：基本信息 + （可选）派生输入。
   派生输入放在下一行而不是塞进同一行——它有 2–3 个下拉，挤进去会把
   列名/业务名压到看不清，而这两个恰恰最容易填错 */
.col-block {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.col-block + .col-block {
  padding-top: var(--space-2);
  border-top: 1px dashed var(--line);
}

.derived-row {
  display: grid;
  grid-template-columns: 1fr 1fr 6em;
  gap: var(--space-2);
  align-items: end;
  padding-left: var(--space-3);
  border-left: 2px solid var(--ink);
}

.derived-slot {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: var(--text-xs);
  color: var(--ink-faint);
}

.derived-slot .input {
  padding: 0.35em 0.5em;
  font-size: var(--text-sm);
}

.derived-formula {
  grid-column: 1 / -1;
  margin: 0;
  font-size: var(--text-xs);
  color: var(--ink-faint);
}

/* 轻量按钮：用于"＋ 统计条件""移除条件"这类就地动作，
   用整颗按钮会把列编辑器挤成一堆色块 */
.link-btn {
  padding: 0;
  border: none;
  background: none;
  color: var(--ink-faint);
  font-size: var(--text-xs);
  text-decoration: underline;
  text-underline-offset: 2px;
  cursor: pointer;
}

.link-btn:hover {
  color: var(--ink);
}

.cond-add {
  align-self: flex-start;
  margin-left: var(--space-3);
}

.col-head {
  font-size: var(--text-xs);
  color: var(--ink-faint);
  padding-bottom: var(--space-1);
  border-bottom: 1px solid var(--line);
}

.col-row .input {
  padding: 0.4em 0.6em;
  font-size: var(--text-sm);
}

.add-col {
  display: flex;
  gap: var(--space-2);
  margin-top: var(--space-3);
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


/* ── 导入 ── */
.import-row {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex-wrap: wrap;
  margin-bottom: var(--space-2);
}

.picker {
  width: min(560px, 100%);
}

.table-list {
  list-style: none;
  margin: 0;
  padding: 0;
  max-height: 50dvh;
  overflow-y: auto;
  border: 1px solid var(--line);
  border-radius: var(--radius);
}

.table-list li + li {
  border-top: 1px solid var(--line);
}

.table-item {
  display: flex;
  align-items: baseline;
  gap: var(--space-3);
  width: 100%;
  padding: var(--space-3) var(--space-4);
  background: transparent;
  border: none;
  text-align: left;
  transition: background-color var(--duration-fast) var(--ease-out);
}

.table-item:hover {
  background: var(--accent-soft);
}

.table-name {
  font-weight: 600;
  flex-shrink: 0;
}

.table-comment {
  flex: 1;
  min-width: 0;
  font-size: var(--text-sm);
  color: var(--ink-soft);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.table-type {
  font-size: var(--text-xs);
  color: var(--ink-faint);
  flex-shrink: 0;
}


/* ── 表关联 ── */
.joins {
  margin-top: var(--space-7);
  padding-top: var(--space-5);
  border-top: 1px solid var(--line);
}

.joins-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-4);
  margin-bottom: var(--space-4);
}

.joins-title {
  font-size: var(--text-lg);
}

.joins-actions {
  display: flex;
  gap: var(--space-2);
}

.join-list {
  list-style: none;
  margin: 0;
  padding: 0;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
}

.join-item {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-3) var(--space-4);
  font-size: var(--text-sm);
  flex-wrap: wrap;
}

.join-item + .join-item {
  border-top: 1px solid var(--line);
}

.join-table {
  font-weight: 600;
}

.join-col {
  color: var(--accent-deep);
  background: var(--accent-soft);
  padding: 0.1em 0.45em;
  border-radius: 3px;
}

.join-op {
  color: var(--ink-faint);
  font-size: 1.1em;
}

.join-type {
  margin-left: auto;
  font-size: var(--text-xs);
  color: var(--ink-faint);
}

.join-buttons {
  display: flex;
  gap: var(--space-2);
}

.join-editor {
  margin-top: var(--space-4);
  padding: var(--space-4);
  border: 1px solid var(--ink);
  border-radius: var(--radius);
  background: var(--surface);
  display: grid;
  grid-template-columns: 1fr 1fr 1fr auto;
  gap: var(--space-4);
  align-items: start;
}

.join-side,
.join-mid {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.join-editor .field-note {
  margin: 0;
  font-size: var(--text-xs);
  color: var(--ink-faint);
  line-height: 1.6;
}

@media (max-width: 860px) {
  .join-editor {
    grid-template-columns: 1fr;
  }
}


/* ── 术语与主题 ── */
.term-list {
  list-style: none;
  margin: 0;
  padding: 0;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
}

.term-item {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: var(--space-2) var(--space-4);
  padding: var(--space-3) var(--space-4);
  align-items: start;
}

.term-item + .term-item {
  border-top: 1px solid var(--line);
}

.term-main {
  display: flex;
  align-items: baseline;
  gap: var(--space-3);
  flex-wrap: wrap;
}

.term-name {
  font-weight: 650;
}

.term-syn {
  font-size: var(--text-sm);
  color: var(--ink-soft);
}

.term-desc {
  grid-column: 1;
  font-size: var(--text-sm);
  color: var(--ink-faint);
}

.term-fields {
  grid-column: 1;
  font-size: var(--text-xs);
  color: var(--accent-deep);
}

.term-editor {
  margin-top: var(--space-4);
  padding: var(--space-4);
  border: 1px solid var(--ink);
  border-radius: var(--radius);
  background: var(--surface);
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

/* 字段选择用 chip 而不是多选下拉：域内字段可能几十个，
   下拉里翻找比一眼扫过去慢得多 */
.field-picker {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  padding: var(--space-3);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface-sunk);
  max-height: 200px;
  overflow-y: auto;
}

.chip {
  padding: 4px 9px;
  border: 1px solid var(--line);
  border-radius: var(--radius-pill);
  background: var(--surface);
  font-size: 10.5px;
  color: var(--ink-soft);
}

.chip:hover {
  border-color: #c9d0f5;
}

.chip-on {
  border-color: var(--ink);
  background: var(--accent-soft);
  color: var(--accent-deep);
  font-weight: 600;
}

@media (max-width: 700px) {
  .col-head {
    display: none;
  }

  .col-row {
    grid-template-columns: 1fr 1fr;
    padding-bottom: var(--space-3);
    border-bottom: 1px solid var(--line);
  }

  .derived-row {
    grid-template-columns: 1fr;
  }

  .two-col {
    grid-template-columns: 1fr;
  }
}
</style>
