import { get, post, put, del, ApiError } from './http'

/**
 * 管理面接口。
 *
 * ## 失败走状态码，不走 `success: false`
 *
 * 后端刻意用真实状态码：400 输入有问题、403 权限不足、404 不存在。
 * 所以这里**靠抛异常表达失败**，而不是返回一个要判空的信封——
 * 后者会让调用方忘掉判空、把失败当成成功继续走。
 */

/** 后端的统一信封。 */
interface Envelope<T> {
  code: number
  message: string
  data: T
}

// ── 类型 ──

export interface Datasource {
  id: number
  name: string
  dbType: string
  driverClass: string
  url: string
  username: string
  enabled: boolean
}

export interface DatasourceForm {
  name: string
  dbType: string
  driverClass: string
  url: string
  username: string
  /** 更新时留空表示不改密码。 */
  password: string
  enabled: boolean
}

export interface ProbeResult {
  ok: boolean
  message: string
}

export interface Domain {
  id: number
  name: string
  datasourceId: number | null
  status: string
  /** MODELED（建模）/ RAW（直连）。**RAW 没有行级/列级权限** */
  mode: string | null
  joinsJson: string | null
  permJson: string | null
}

export interface DomainForm {
  name: string
  description: string
  datasourceId: number | null
  status: string
  /**
   * 模式。
   *
   * - `MODELED`：语义建模 → DSL → 我们渲染 SQL。**行级/列级权限生效**
   * - `RAW`：只勾表，模型自己写 SQL。**只做表级授权**，行列规则配了也不生效
   *   （后端会拒绝保存带规则的表单）
   */
  mode: 'MODELED' | 'RAW'
  joinsJson: string | null
  permJson: string | null
  sortNo: number
}

/** 派生指标的类型：比值 / 差值 / 时长。 */
export type DerivedKind = 'ratio' | 'diff' | 'duration'

/**
 * 派生指标定义，对应 `columns_json` 里的 `derived` 块。
 *
 * - `ratio` / `diff` 用 `left` / `right`（域内已有**指标**的业务名，可跨表）
 * - `duration` 用 `start` / `end`（两个时间列）+ `unit`
 *
 * 模型只按名字引用派生指标，不写算式——口径在语义层定死。
 */
export interface DerivedDef {
  kind: DerivedKind
  left?: string | null
  right?: string | null
  start?: string | null
  end?: string | null
  /** SECOND / MINUTE / HOUR / DAY；缺省 HOUR */
  unit?: string | null
}

/**
 * 指标的统计条件：只把满足条件的行计入这一列。
 *
 * 例如「已解决工单数」= COUNT(工单号)，条件是 `状态 = 已解决`。
 * 用 CASE 渲染，不影响同一条查询里的其它指标；字段可以跨表。
 */
export interface MetricConditionDef {
  field: string
  /** = / != / > / < / >= / <= / in / like */
  op: string
  /** in 用逗号分隔多个取值 */
  value: string
}

export interface ColumnDef {
  /** 物理列名。**派生指标没有物理列**，此处为空串 */
  col: string
  type: string
  agg: string
  alias: string
  enabled: boolean
  /** 派生指标定义；普通列为 null。见 [DerivedDef] */
  derived?: DerivedDef | null
  /** 统计条件；无则为 null */
  condition?: MetricConditionDef | null
}

export interface DomainTable {
  id: number
  domainId: number
  name: string
  label: string
  mode: string
  sqlText: string | null
  columnsJson: string | null
  enabled: boolean
}

export interface DomainTableForm {
  name: string
  label: string
  mode: string
  sqlText: string | null
  columns: ColumnDef[]
  enabled: boolean
  sortNo: number
}

// ── 数据源 ──

export async function listDatasources(): Promise<Datasource[]> {
  return (await get<Envelope<Datasource[]>>('/api/engine/admin/datasources')).data
}

export async function createDatasource(form: DatasourceForm): Promise<number> {
  return (await post<Envelope<number>>('/api/engine/admin/datasources', form)).data
}

export async function updateDatasource(id: number, form: DatasourceForm): Promise<void> {
  await put<Envelope<void>>(`/api/engine/admin/datasources/${id}`, form)
}

export async function deleteDatasource(id: number): Promise<void> {
  await del<Envelope<void>>(`/api/engine/admin/datasources/${id}`)
}

/** 用表单里的参数试连（尚未保存）。 */
export async function testDatasource(form: DatasourceForm): Promise<ProbeResult> {
  return (await post<Envelope<ProbeResult>>('/api/engine/admin/datasources/test', form)).data
}

/** 试连已保存的数据源（密码留空则用已存的）。 */
export async function testSavedDatasource(id: number): Promise<ProbeResult> {
  return (await post<Envelope<ProbeResult>>(`/api/engine/admin/datasources/${id}/test`, {})).data
}

// ── 语义域 ──

export async function listDomains(): Promise<Domain[]> {
  return (await get<Envelope<Domain[]>>('/api/engine/admin/domains')).data
}

export async function createDomain(form: DomainForm): Promise<number> {
  return (await post<Envelope<number>>('/api/engine/admin/domains', form)).data
}

export async function updateDomain(id: number, form: DomainForm): Promise<void> {
  await put<Envelope<void>>(`/api/engine/admin/domains/${id}`, form)
}

export async function deleteDomain(id: number): Promise<void> {
  await del<Envelope<void>>(`/api/engine/admin/domains/${id}`)
}

// ── 域内注册表 ──

export async function listTables(domainId: number): Promise<DomainTable[]> {
  return (await get<Envelope<DomainTable[]>>(`/api/engine/admin/domains/${domainId}/tables`)).data
}

export async function createTable(domainId: number, form: DomainTableForm): Promise<number> {
  return (await post<Envelope<number>>(`/api/engine/admin/domains/${domainId}/tables`, form)).data
}

export async function updateTable(id: number, form: DomainTableForm): Promise<void> {
  await put<Envelope<void>>(`/api/engine/admin/tables/${id}`, form)
}

export async function deleteTable(id: number): Promise<void> {
  await del<Envelope<void>>(`/api/engine/admin/tables/${id}`)
}

/** 把后端返回的 columns_json 解析成结构化列表。坏数据返回空数组而不是抛错。 */
export function parseColumns(json: string | null): ColumnDef[] {
  if (!json) return []
  try {
    const parsed = JSON.parse(json) as ColumnDef[]
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

// ── 元数据（从数据源导入表与字段） ──

export interface TableMeta {
  name: string
  comment: string
  type: string
}

export interface ColumnMeta {
  name: string
  type: string
  nullable: boolean
  primary: boolean
  comment: string
}

/** 列出数据源里的表。 */
export async function listDatasourceTables(datasourceId: number): Promise<TableMeta[]> {
  return (await get<Envelope<TableMeta[]>>(`/api/engine/admin/datasources/${datasourceId}/tables`))
    .data
}

/** 列出某张表的字段。 */
export async function listDatasourceColumns(
  datasourceId: number,
  table: string,
): Promise<ColumnMeta[]> {
  return (
    await get<Envelope<ColumnMeta[]>>(
      `/api/engine/admin/datasources/${datasourceId}/tables/${encodeURIComponent(table)}/columns`,
    )
  ).data
}

/** 把后端返回的 perm_json 解析成对象，供表单回显。 */
export function parsePerm(json: string | null): Record<string, unknown> {
  if (!json) return {}
  try {
    const parsed = JSON.parse(json) as unknown
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed)
      ? (parsed as Record<string, unknown>)
      : {}
  } catch {
    return {}
  }
}

export { ApiError }

/** 保存表关联。 */
export async function saveJoins(domainId: number, joinsJson: string): Promise<void> {
  await put<Envelope<void>>(`/api/engine/admin/domains/${domainId}/joins`, { joinsJson })
}

/** 表关联的一项。 */
export interface TableJoin {
  id: number
  left: string
  right: string
  leftCol: string
  rightCol: string
  type: string
}

/** 把 joins_json 解析成结构化列表。 */
export function parseJoins(json: string | null): TableJoin[] {
  if (!json) return []
  try {
    const parsed = JSON.parse(json) as TableJoin[]
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

// ── 业务术语 ──

export interface TermRow {
  id: number
  domainId: number
  name: string
  synonymsJson: string | null
  description: string | null
  applyKind: string
}

export interface TermForm {
  name: string
  synonyms: string[]
  description: string
  applyKind: string
}

export async function listTerms(domainId: number): Promise<TermRow[]> {
  return (await get<Envelope<TermRow[]>>(`/api/engine/admin/domains/${domainId}/terms`)).data
}

export async function createTerm(domainId: number, form: TermForm): Promise<number> {
  return (await post<Envelope<number>>(`/api/engine/admin/domains/${domainId}/terms`, form)).data
}

export async function updateTerm(id: number, form: TermForm): Promise<void> {
  await put<Envelope<void>>(`/api/engine/admin/terms/${id}`, form)
}

export async function deleteTerm(id: number): Promise<void> {
  await del<Envelope<void>>(`/api/engine/admin/terms/${id}`)
}

// ── 查询主题 ──

export interface TopicRow {
  id: number
  domainId: number
  name: string
  description: string | null
  status: string
  fieldsJson: string | null
}

export interface TopicForm {
  name: string
  description: string
  status: string
  fields: string[]
}

export async function listTopics(domainId: number): Promise<TopicRow[]> {
  return (await get<Envelope<TopicRow[]>>(`/api/engine/admin/domains/${domainId}/topics`)).data
}

export async function createTopic(domainId: number, form: TopicForm): Promise<number> {
  return (await post<Envelope<number>>(`/api/engine/admin/domains/${domainId}/topics`, form)).data
}

export async function updateTopic(id: number, form: TopicForm): Promise<void> {
  await put<Envelope<void>>(`/api/engine/admin/topics/${id}`, form)
}

export async function deleteTopic(id: number): Promise<void> {
  await del<Envelope<void>>(`/api/engine/admin/topics/${id}`)
}

/** 解析字符串数组 JSON。 */
export function parseStringList(json: string | null): string[] {
  if (!json) return []
  try {
    const parsed = JSON.parse(json) as string[]
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

// ── 助理 ──

export interface AssistantRow {
  id: number
  name: string
  avatar: string | null
  description: string | null
  welcome: string | null
  domainId: number | null
  domainIds: string | null
  showSql: boolean
  status: string
  questionsJson: string | null
  permJson: string | null
}

export interface AssistantForm {
  name: string
  avatar: string
  description: string
  welcome: string
  domainId: number | null
  domainIds: string[]
  showSql: boolean
  status: string
  questions: string[]
  permJson: string | null
}

export async function listAssistants(): Promise<AssistantRow[]> {
  return (await get<Envelope<AssistantRow[]>>('/api/engine/admin/assistants')).data
}

export async function createAssistant(form: AssistantForm): Promise<number> {
  return (await post<Envelope<number>>('/api/engine/admin/assistants', form)).data
}

export async function updateAssistant(id: number, form: AssistantForm): Promise<void> {
  await put<Envelope<void>>(`/api/engine/admin/assistants/${id}`, form)
}

export async function deleteAssistant(id: number): Promise<void> {
  await del<Envelope<void>>(`/api/engine/admin/assistants/${id}`)
}

/** 助理的权限配置形状。与域层不同：这里看的是 enabled 开关。 */
export interface AssistantPerm {
  enabled?: boolean
  grantRoles?: string[]
  grantUsers?: string[]
}

/**
 * 全部角色（含已停用的）。
 *
 * <h2>它为什么在管理面接口里</h2>
 *
 * 权限配置里的角色名必须与 IAM 角色目录里的角色码（`iam_role.role_code`）**逐字符一致**，否则规则静默失效
 * （见 `docs/permission-config.md` 第四节）。让管理员对着自由文本框凭记忆敲角色名，
 * 就是在制造这类错误。这个接口让界面能：
 *
 * 1. 把可选角色列出来让人**点选而不是手打**（治本）
 * 2. 当场标出已经填错的角色（治标，但能立刻发现）
 *
 * 含已停用的是刻意的：「不存在」与「存在但已停用」要分开提示，处理方式不同。
 */
export interface RoleOption {
  /** 角色码。**权限配置里填的就是它** */
  code: string
  /** 显示名，给人看 */
  name: string
  /** 停用的角色不参与鉴权，规则引用它同样不会生效 */
  enabled: boolean
}

export async function listRoles(): Promise<RoleOption[]> {
  return (await get<Envelope<RoleOption[]>>('/api/engine/admin/roles')).data
}

/**
 * 用户选项：策略编辑器里点选授权用户。
 *
 * `username` 是 `grantUsers` 里要存的值；显示名只用于界面。
 * 包含停用用户——已保存的策略可能引用他们，需要能标出来。
 */
export interface UserOption {
  username: string
  displayName: string | null
  status: string | null
}

export async function searchUsers(keyword = ''): Promise<UserOption[]> {
  const params = new URLSearchParams()
  if (keyword.trim()) params.set('keyword', keyword.trim())
  const suffix = params.toString()
  return (await get<Envelope<UserOption[]>>(
    `/api/engine/admin/user-options${suffix ? `?${suffix}` : ''}`,
  )).data
}

/** 一个角色码的问题。`null` 表示没问题。 */
export type RoleProblem = 'missing' | 'disabled' | null

/**
 * 逐个检查角色码。
 *
 * 逐字符比对，**不做大小写归一化也不 trim**——运行时就是这么比的，
 * 这里若宽容一点，就会造出「界面说没问题、运行时匹配不上」这种最坏组合。
 */
export function checkRoles(codes: string[], roles: RoleOption[]): Map<string, RoleProblem> {
  const byCode = new Map(roles.map((r) => [r.code, r]))
  const out = new Map<string, RoleProblem>()
  for (const code of codes) {
    if (!code) continue
    const hit = byCode.get(code)
    out.set(code, hit === undefined ? 'missing' : hit.enabled ? null : 'disabled')
  }
  return out
}

/** 从权限规则 JSON 里抓出所有被引用的角色码（行规则与列规则）。 */
export function rolesInRules(rulesJson: string): string[] {
  if (!rulesJson.trim()) return []
  let parsed: unknown
  try {
    parsed = JSON.parse(rulesJson)
  } catch {
    // JSON 本身不合法由别处的校验负责，这里不重复报
    return []
  }
  if (typeof parsed !== 'object' || parsed === null) return []
  const out: string[] = []
  for (const key of ['rowRules', 'colRules'] as const) {
    const list = (parsed as Record<string, unknown>)[key]
    if (!Array.isArray(list)) continue
    for (const item of list) {
      const role = (item as Record<string, unknown> | null)?.['role']
      if (typeof role === 'string' && role.trim()) out.push(role)
    }
  }
  return out
}

/**
 * 单独改数据源密码。
 *
 * <h2>为什么不并进 `updateDatasource`</h2>
 *
 * 后端**刻意**把"改配置"与"改密码"拆成两个端点：合成的接口迟早有人改个名字，
 * 顺手把密码清掉。这个拆分是对的，前端的错在于**从来没调用过这个端点**——
 * 编辑表单里那个「（留空表示不修改）」的密码框此前是个空操作，
 * 填了也不会生效。现在只有真的填了才调它。
 */
export async function updateDatasourcePassword(id: number, password: string): Promise<void> {
  await put<Envelope<void>>(`/api/engine/admin/datasources/${id}/password`, { password })
}

// ── SQL 范例（直连模式的 few-shot）──

/**
 * 一条 SQL 范例。
 *
 * **只有人工确认过正确的查询才该进来。**执行成功 ≠ 语义正确——一条把「上个月」
 * 丢成全量、但执行成功的查询，如果被当成范例，会教模型重复那个错误。
 */
export interface SqlExample {
  id: number
  domainId: number
  /** 示例问法（中文，贴近业务人员的说法）。检索靠它和关键词 */
  question: string
  sql: string
  /** 口径要点，会与范例一起进 prompt */
  note: string | null
  /** 检索关键词，顿号/逗号分隔。**命中即视为强相关** */
  keywords: string | null
  source: string
  enabled: boolean
}

export interface SqlExampleForm {
  question: string
  sql: string
  note: string | null
  keywords: string | null
  enabled: boolean
  sortNo: number
}

export async function listSqlExamples(domainId: number): Promise<SqlExample[]> {
  return (await get<Envelope<SqlExample[]>>(`/api/engine/admin/domains/${domainId}/sql-examples`)).data
}

export async function createSqlExample(domainId: number, form: SqlExampleForm): Promise<number> {
  return (await post<Envelope<number>>(`/api/engine/admin/domains/${domainId}/sql-examples`, form)).data
}

export async function updateSqlExample(id: number, form: SqlExampleForm): Promise<void> {
  await put<Envelope<void>>(`/api/engine/admin/sql-examples/${id}`, form)
}

export async function deleteSqlExample(id: number): Promise<void> {
  await del<Envelope<void>>(`/api/engine/admin/sql-examples/${id}`)
}

// ── 用户反馈审核 ──

export type FeedbackStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

/**
 * 一条用户反馈。
 *
 * `SQL_CORRECTION` 类型带 `correctedSql`；审核通过后它会进入该域的范例库。
 * `WRONG_ANSWER` / `HELPFUL` 只用于统计，不产生范例。
 */
export interface FeedbackRow {
  id: number
  domainId: number
  assistantId: string | null
  username: string
  question: string
  feedbackType: 'HELPFUL' | 'WRONG_ANSWER' | 'SQL_CORRECTION'
  generatedSql: string | null
  correctedSql: string | null
  note: string | null
  status: FeedbackStatus
  /** 审核通过后写入/复用的范例 id */
  exampleId: number | null
  reviewedBy: string | null
  reviewedAt: string | null
  createdAt: string
}

/** 按状态取反馈；状态留空取全部。 */
export async function listFeedback(status: string, limit = 100): Promise<FeedbackRow[]> {
  const qs = new URLSearchParams()
  if (status) qs.set('status', status)
  qs.set('limit', String(limit))
  return (await get<Envelope<FeedbackRow[]>>(`/api/engine/admin/feedback?${qs.toString()}`)).data
}

/** 通过。返回写入/复用的范例 id（非修正类型为 null）。 */
export async function approveFeedback(id: number): Promise<number | null> {
  return (await post<Envelope<number | null>>(`/api/engine/admin/feedback/${id}/approve`, {})).data
}

/** 拒绝。不写范例库。 */
export async function rejectFeedback(id: number): Promise<void> {
  await post<Envelope<void>>(`/api/engine/admin/feedback/${id}/reject`, {})
}
