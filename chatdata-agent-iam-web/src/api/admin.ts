import { get, post, put, type Envelope } from './http'

/**
 * 管理面接口。**与 ask 的管理面同一套信封**：`{code, message, data}`，
 * 失败走真实状态码（400/401/403/500），调用方靠抛异常分支。
 */

// ── 类型 ──

export interface UserView {
  id: number
  username: string
  displayName: string | null
  status: 'ACTIVE' | 'DISABLED'
  createdAt: string | null
  orgName: string | null
  /** 主组织 id。编辑表单要用它预选；只给 orgName 的话，重名组织会选错。 */
  orgId: number | null
  roles: string[]
}

export interface UserCreateForm {
  username: string
  password: string
  displayName: string
  orgId: number | null
  roles: string[]
  status: 'ACTIVE' | 'DISABLED'
}

export interface OrgView {
  id: number
  parentId: number | null
  code: string
  name: string
  path: string
  sortNo: number
  enabled: boolean
  createdAt: string | null
}

export interface OrgForm {
  code: string
  name: string
  parentId: number | null
  sortNo: number
  enabled: boolean
}

export interface RoleView {
  id: number
  code: string
  name: string
  category: 'ADMIN' | 'DATA' | 'FUNCTION'
  description: string | null
  enabled: boolean
  /** 已配置的功能权限项数。列表要能一眼看出"配了几项"，否则得逐个点开。 */
  permissionCount: number
}

export interface RoleForm {
  code: string
  name: string
  category: 'ADMIN' | 'DATA' | 'FUNCTION'
  description: string
  enabled: boolean
}

// ── 用户 ──

/** 一页用户。`page` 是服务端纠正过的实际页码（越界会回到最后一页）。 */
export interface UserPage {
  items: UserView[]
  total: number
  page: number
  size: number
}

export interface UserStats {
  total: number
  active: number
  disabled: number
  /** 有用户的组织数（去重）。 */
  orgs: number
}

export interface UserQuery {
  page?: number
  size?: number
  keyword?: string
  status?: 'ACTIVE' | 'DISABLED'
  /** 按该组织**及其子树**筛选。 */
  orgId?: number | null
}

export async function listUsers(query: UserQuery = {}): Promise<UserPage> {
  const params = new URLSearchParams()
  if (query.page) params.set('page', String(query.page))
  if (query.size) params.set('size', String(query.size))
  if (query.keyword) params.set('keyword', query.keyword)
  if (query.status) params.set('status', query.status)
  if (query.orgId !== null && query.orgId !== undefined) params.set('orgId', String(query.orgId))
  const qs = params.toString()
  return (await get<Envelope<UserPage>>(`/iam/admin/users${qs ? `?${qs}` : ''}`)).data
}

/** 顶部统计。全量口径，不随筛选变化。 */
export async function userStats(): Promise<UserStats> {
  return (await get<Envelope<UserStats>>('/iam/admin/users/stats')).data
}

export async function createUser(form: UserCreateForm): Promise<number> {
  return (await post<Envelope<number>>('/iam/admin/users', form)).data
}

export async function updateUser(
  id: number,
  form: { displayName: string; status: 'ACTIVE' | 'DISABLED'; orgId: number | null },
): Promise<void> {
  await put<Envelope<void>>(`/iam/admin/users/${id}`, form)
}

export async function resetPassword(id: number, password: string): Promise<void> {
  await put<Envelope<void>>(`/iam/admin/users/${id}/password`, { password })
}

export async function assignRoles(id: number, roles: string[]): Promise<void> {
  await put<Envelope<void>>(`/iam/admin/users/${id}/roles`, { roles })
}

// ── 组织 ──

export async function listOrgs(): Promise<OrgView[]> {
  return (await get<Envelope<OrgView[]>>('/iam/admin/orgs')).data
}

export async function createOrg(form: OrgForm): Promise<number> {
  return (await post<Envelope<number>>('/iam/admin/orgs', form)).data
}

export async function updateOrg(
  id: number,
  form: { name: string; sortNo: number; enabled: boolean },
): Promise<void> {
  await put<Envelope<void>>(`/iam/admin/orgs/${id}`, form)
}

// ── 角色 ──

export async function listRoles(): Promise<RoleView[]> {
  return (await get<Envelope<RoleView[]>>('/iam/admin/roles')).data
}

export async function createRole(form: RoleForm): Promise<void> {
  await post<Envelope<void>>('/iam/admin/roles', form)
}

export async function updateRole(id: number, form: Omit<RoleForm, 'code'>): Promise<void> {
  await put<Envelope<void>>(`/iam/admin/roles/${id}`, form)
}

// ── 功能权限 ──

/**
 * 权限目录树。
 *
 * 三级（模块 → 页面 → 动作），**标签与分组都来自后端**，前端不重写文案。
 * `kind` 为 `MODULE` 的节点只是分组，不可勾选。
 */
export interface PermissionNode {
  code: string
  label: string
  kind: 'MODULE' | 'PAGE' | 'ACTION'
  /** 单项就等于相当大的权限（重置密码、配角色权限…），配置时单独提示 */
  sensitive: boolean
  children: PermissionNode[]
}

export async function listPermissionCatalog(): Promise<PermissionNode[]> {
  return (await get<Envelope<PermissionNode[]>>('/iam/admin/permissions')).data
}

export async function getRolePermissions(roleId: number): Promise<string[]> {
  return (await get<Envelope<{ roleId: number; codes: string[] }>>(
    `/iam/admin/roles/${roleId}/permissions`,
  )).data.codes
}

/** 整表替换：提交的是"这个角色最终的权限集合"。 */
export async function saveRolePermissions(roleId: number, codes: string[]): Promise<number> {
  return (await put<Envelope<{ saved: number }>>(`/iam/admin/roles/${roleId}/permissions`, {
    codes,
  })).data.saved
}

