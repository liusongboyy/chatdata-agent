/**
 * 数据域/助理策略文档的解析、序列化与前端校验。
 *
 * <p>文档结构以引擎的 `DomainPermission` / `AssistantPermission` 为准。
 * 解析时只认识已知字段，**其余字段原样收进 `extras` 并在保存时带回**——
 * 表单不能成为"删掉未来字段"的工具。
 */

export interface RowRule {
  id: number
  role: string
  field: string
  op: string
  value: string
  sysVar: string
  dynamic: boolean
}

export interface ColRule {
  id: number
  role: string
  field: string
  action: string
}

export type Extras = Record<string, unknown>

export interface DomainPolicy {
  v: number
  isPublic: boolean
  grantRoles: string[]
  grantUsers: string[]
  unrestrictedRoles: string[]
  rowRules: RowRule[]
  colRules: ColRule[]
  extras: Extras
}

export interface AssistantPolicy {
  enabled: boolean
  grantRoles: string[]
  grantUsers: string[]
  extras: Extras
}

const DOMAIN_KEYS = new Set([
  'v',
  'isPublic',
  'grantRoles',
  'grantUsers',
  'unrestrictedRoles',
  'rowRules',
  'colRules',
])

const ASSISTANT_KEYS = new Set(['enabled', 'grantRoles', 'grantUsers'])

/**
 * 行规则运算符。
 *
 * `前缀` 与 `包含` **不能互相替代**：
 * - 「包含」两侧加通配符（`%值%`），用于模糊搜索
 * - 「前缀」只在右侧加（`值%`），用于组织树这类"覆盖整棵子树"的权限
 *
 * 层级权限配成「包含」会跨到兄弟子树：组织路径 `/1/10/` 与 `/1/100/` 是两支，
 * `%/1/10%` 会同时命中——那是越权，且不报错。
 */
export const ROW_OPERATORS = ['=', '!=', '>', '>=', '<', '<=', '包含', '前缀', 'in', 'not in'] as const

export const MASK_ACTION = '脱敏'

export function emptyDomainPolicy(): DomainPolicy {
  return {
    v: 1,
    isPublic: false,
    grantRoles: [],
    grantUsers: [],
    unrestrictedRoles: [],
    rowRules: [],
    colRules: [],
    extras: {},
  }
}

export function emptyAssistantPolicy(): AssistantPolicy {
  return { enabled: false, grantRoles: [], grantUsers: [], extras: {} }
}

interface Parsed<T> {
  policy: T
  error: string
}

/** 解析域策略。空文本返回空配置；坏 JSON 返回错误，由界面落到 JSON 模式修复。 */
export function parseDomainPolicy(text: string | null | undefined): Parsed<DomainPolicy> {
  if (!text || !text.trim()) return { policy: emptyDomainPolicy(), error: '' }

  let raw: unknown
  try {
    raw = JSON.parse(text)
  } catch {
    return { policy: emptyDomainPolicy(), error: '策略不是合法的 JSON' }
  }
  if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) {
    return { policy: emptyDomainPolicy(), error: '策略必须是一个 JSON 对象' }
  }

  const obj = raw as Record<string, unknown>
  return {
    policy: {
      v: typeof obj['v'] === 'number' ? obj['v'] : 1,
      isPublic: obj['isPublic'] === true,
      grantRoles: stringList(obj['grantRoles']),
      grantUsers: stringList(obj['grantUsers']),
      unrestrictedRoles: stringList(obj['unrestrictedRoles']),
      rowRules: arrayOf(obj['rowRules']).map(rowRuleFrom),
      colRules: arrayOf(obj['colRules']).map(colRuleFrom),
      extras: pickExtras(obj, DOMAIN_KEYS),
    },
    error: '',
  }
}

/** 解析助理策略。 */
export function parseAssistantPolicy(text: string | null | undefined): Parsed<AssistantPolicy> {
  if (!text || !text.trim()) return { policy: emptyAssistantPolicy(), error: '' }

  let raw: unknown
  try {
    raw = JSON.parse(text)
  } catch {
    return { policy: emptyAssistantPolicy(), error: '策略不是合法的 JSON' }
  }
  if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) {
    return { policy: emptyAssistantPolicy(), error: '策略必须是一个 JSON 对象' }
  }

  const obj = raw as Record<string, unknown>
  return {
    policy: {
      enabled: obj['enabled'] === true,
      grantRoles: stringList(obj['grantRoles']),
      grantUsers: stringList(obj['grantUsers']),
      extras: pickExtras(obj, ASSISTANT_KEYS),
    },
    error: '',
  }
}

export function serializeDomainPolicy(p: DomainPolicy): string {
  return JSON.stringify({
    ...p.extras,
    v: p.v,
    isPublic: p.isPublic,
    grantRoles: p.grantRoles,
    grantUsers: p.grantUsers,
    unrestrictedRoles: p.unrestrictedRoles,
    rowRules: p.rowRules.map((r) => ({
      id: r.id,
      role: r.role,
      field: r.field,
      op: r.op,
      value: r.dynamic ? '' : r.value,
      sysVar: r.dynamic ? r.sysVar : '',
      dynamic: r.dynamic,
    })),
    colRules: p.colRules.map((r) => ({
      id: r.id,
      role: r.role,
      field: r.field,
      action: r.action || MASK_ACTION,
    })),
  })
}

export function serializeAssistantPolicy(p: AssistantPolicy): string {
  return JSON.stringify({
    ...p.extras,
    enabled: p.enabled,
    grantRoles: p.grantRoles,
    grantUsers: p.grantUsers,
  })
}

/** 新规则的 id：沿用现有最大值 + 1，保证同一份文档里不重复。 */
export function nextRuleId(rules: { id: number }[]): number {
  return rules.reduce((max, r) => Math.max(max, Number.isFinite(r.id) ? r.id : 0), 0) + 1
}

export interface DomainPolicyErrors {
  roles: string[]
  users: string[]
  rows: string[]
  cols: string[]
}

/**
 * 前端校验。
 *
 * <p>角色存在性由调用方根据 IAM 角色目录判断；用户只校验非空——
 * 用户目录是搜索式的，没出现在当前结果里不等于不存在，最终由后端 best-effort 校验。
 */
export function validateDomainPolicy(p: DomainPolicy): DomainPolicyErrors {
  const errors: DomainPolicyErrors = { roles: [], users: [], rows: [], cols: [] }

  for (const r of p.rowRules) {
    const no = r.id
    if (!r.role.trim()) errors.rows.push(`行规则 ${no}：未选择角色`)
    if (!r.field.trim()) errors.rows.push(`行规则 ${no}：未选择或填写字段`)
    if (!ROW_OPERATORS.includes(r.op as (typeof ROW_OPERATORS)[number])) {
      errors.rows.push(`行规则 ${no}：运算符不受支持（${r.op || '空'}）`)
    }
    if (r.dynamic) {
      if (!r.sysVar.trim()) errors.rows.push(`行规则 ${no}：动态规则未选择变量`)
    } else if (!r.value.trim()) {
      errors.rows.push(`行规则 ${no}：未填写条件值`)
    }
  }

  for (const r of p.colRules) {
    const no = r.id
    if (!r.role.trim()) errors.cols.push(`列规则 ${no}：未选择角色`)
    if (!r.field.trim()) errors.cols.push(`列规则 ${no}：未选择或填写字段`)
  }

  return errors
}

export function validateAssistantPolicy(p: AssistantPolicy): string[] {
  const out: string[] = []
  if (p.enabled && p.grantRoles.length === 0 && p.grantUsers.length === 0) {
    out.push('启用访问控制时，至少需要一个授权角色或授权用户')
  }
  return out
}

const OP_ALIASES: Record<string, string> = {
  '==': '=',
  '<>': '!=',
  '≠': '!=',
  '≥': '>=',
  '≤': '<=',
  like: '包含',
  LIKE: '包含',
  IN: 'in',
  'NOT IN': 'not in',
}

/** 把引擎也接受的别名收敛到下拉里的写法，避免"能读却保存不了"。 */
function canonicalOp(raw: string): string {
  return OP_ALIASES[raw] ?? (raw || '=')
}

function rowRuleFrom(v: unknown): RowRule {
  const o = asObject(v)
  return {
    id: numberOr(o['id'], 0),
    role: stringOr(o['role']),
    field: stringOr(o['field']),
    op: canonicalOp(stringOr(o['op'])),
    value: stringOr(o['value']),
    sysVar: stringOr(o['sysVar']),
    dynamic: o['dynamic'] === true,
  }
}

function colRuleFrom(v: unknown): ColRule {
  const o = asObject(v)
  return {
    id: numberOr(o['id'], 0),
    role: stringOr(o['role']),
    field: stringOr(o['field']),
    action: stringOr(o['action']) || MASK_ACTION,
  }
}

function asObject(v: unknown): Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v)
    ? (v as Record<string, unknown>)
    : {}
}

function arrayOf(v: unknown): unknown[] {
  return Array.isArray(v) ? v : []
}

function stringList(v: unknown): string[] {
  return arrayOf(v).filter((x): x is string => typeof x === 'string' && x.trim() !== '')
}

function stringOr(v: unknown): string {
  return typeof v === 'string' ? v : ''
}

function numberOr(v: unknown, fallback: number): number {
  return typeof v === 'number' && Number.isFinite(v) ? v : fallback
}

function pickExtras(obj: Record<string, unknown>, known: Set<string>): Extras {
  const out: Extras = {}
  for (const [key, value] of Object.entries(obj)) {
    if (!known.has(key)) out[key] = value
  }
  return out
}
