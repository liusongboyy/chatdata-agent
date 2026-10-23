import { get, post } from './http'

/**
 * 认证接口。
 *
 * 登录**不返回令牌**：它只建立会话（`SESSION` Cookie）。
 * 令牌由授权码流程在 `/oauth2/token` 签发，那一步在网关（BFF）侧完成。
 */

export interface Principal {
  authenticated?: boolean
  userId?: number
  tenant?: string
  username?: string
  displayName?: string
  orgId?: number | null
  orgCode?: string | null
  roles?: string[]
  /**
   * 功能权限码（页面/按钮/tab）。
   *
   * 与 `roles` 是两个维度：角色决定"能看哪些行"（数据策略按角色匹配），
   * 权限决定"能看到哪些页面与操作"。**界面据此隐藏**，真正的闸门在后端。
   */
  permissions?: string[]
}

/** 取 CSRF token（响应同时下发 XSRF-TOKEN Cookie）。写请求前必须先调一次。 */
export async function ensureCsrf(): Promise<void> {
  await get<{ token: string }>('/iam/auth/csrf')
}

export async function login(
  username: string,
  password: string,
  captcha: string,
  checkKey: string,
): Promise<Principal> {
  return await post<Principal>('/iam/auth/login', { username, password, captcha, checkKey })
}

/** 当前主体；未登录时后端返回 authenticated:false（不是 401，便于路由守卫判断）。 */
export async function me(): Promise<Principal> {
  return await get<Principal>('/iam/me')
}

export async function logout(): Promise<void> {
  await post<void>('/iam/auth/logout')
}

/** 验证码图片地址。每次换一张就换 key。 */
export function captchaUrl(checkKey: string): string {
  return `/captcha/${checkKey}`
}

export function newCheckKey(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID().replace(/-/g, '')
  }
  const bytes = new Uint8Array(16)
  crypto.getRandomValues(bytes)
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
}
