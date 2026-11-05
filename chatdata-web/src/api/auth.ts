import { get, post } from './http'
import { ASK_ADMIN_PAGE_CODES } from '../permissions'

/**
 * 认证与主体信息。
 *
 * ## 登录不再由本应用负责
 *
 * 身份归 IAM，浏览器通过网关的 BFF 走授权码流程（`/bff/login`）。
 * 本模块只做两件事：**取当前主体**、**退出**。
 *
 * ## 为什么这里没有"令牌"
 *
 * 令牌存在网关的会话里，浏览器只有 `SESSION` Cookie。前端拿不到令牌，
 * 也就不存在"把令牌放哪"这个问题——这正是 BFF 模式的目的。
 */

/** 当前用户信息。`canConfig` 决定是否显示管理台入口。 */
export interface CurrentUser {
  username: string
  realname: string
  avatar: string
  roles: string[]
  permissions: string[]
  canConfig: boolean
}

/** ask 的 `/api/ask/me` 响应形状。 */
interface AskMe {
  username: string
  displayName: string
  roles: string[]
  permissions: string[]
}

/** 登录入口：交给网关的 BFF，它会跳到 IAM 的登录页。 */
export function bffLoginUrl(): string {
  return '/bff/login'
}

/**
 * 当前用户。
 *
 * ## 为什么不用网关的 `/bff/me`
 *
 * 那个端点回显的是**登录那一刻**抓取的属性（会话里存着的 `OAuth2User`）。
 * 管理员改完权限，用户不重新登录就看不到任何变化——界面会一直藏着该出现的按钮，
 * 而点旧按钮又会 403。
 *
 * `/api/ask/me` 读的是令牌内省结果（30 秒缓存），与后端 `@perm` 判定同源，
 * 所以"界面看到的"与"点下去能不能成"一致。
 */
export async function fetchCurrentUser(): Promise<CurrentUser> {
  const me = await get<AskMe>('/api/ask/me')
  const permissions = Array.isArray(me.permissions) ? me.permissions : []
  const roles = Array.isArray(me.roles) ? me.roles : []
  return {
    username: me.username ?? '',
    realname: me.displayName || me.username || '',
    avatar: '',
    roles,
    permissions,
    // 「有任一管理页权限就能进」——判据与后端一致，见 permissions.ts
    canConfig: ASK_ADMIN_PAGE_CODES.some((code) => permissions.includes(code)),
  }
}

/** IAM 的 CSRF Cookie 名与网关的 `XSRF-TOKEN` 不同，必须分别读取。 */
function iamCsrfToken(): string | null {
  const match = document.cookie.match(/(?:^|;\s*)IAM-XSRF-TOKEN=([^;]+)/)
  return match && match[1] ? decodeURIComponent(match[1]) : null
}

/**
 * 退出：先清 IAM 的 SSO 会话，再清网关会话，最后**一步直达** IAM 登录页。
 *
 * <h2>为什么清两处</h2>
 *
 * 只清网关的话，登录页自动跳 `/bff/login` 时 IAM 仍认得 `IAM_SESSION`，
 * 会静默重新登录——用户看起来"退不出去"。
 *
 * <h2>为什么跳转也放在这里，而不是让调用方 router.replace({ name: 'login' })</h2>
 *
 * 那样会多绕一圈：`/app/login` 是本地的一个页面，它挂载后**再**跳
 * `/bff/login`，网关再 302 到 IAM。用户看到的是
 *
 *   点退出 → 白屏闪一下"正在跳转登录…" → IAM 登录页
 *
 * 中间那次本地渲染没有任何信息量，只增加一次往返和一次闪烁。
 * 这里直接 `location.replace('/bff/login')`：
 *
 * - **replace 不是 assign**：登出后按"后退"不该回到已失效的页面，
 *   那会显示一屏加载失败的空白
 * - 跳转在 `finally` 里做：即使网关登出失败，也必须离开当前页面——
 *   停在原地只会让用户对着一个"已登出"的界面反复点击
 *
 * <h2>调用方要做什么</h2>
 *
 * 只调 `await logout()` 即可，**不要再自己 router.replace**。
 * 本函数不返回（页面已经在跳转）。
 */
export async function logout(): Promise<void> {
  const csrf = iamCsrfToken()
  try {
    await fetch('/iam/auth/logout', {
      method: 'POST',
      credentials: 'same-origin',
      headers: csrf ? { 'X-XSRF-TOKEN': csrf } : {},
    })
  } catch {
    // IAM 不可达时不阻断网关登出，否则用户会卡在"已登录"状态
  }

  try {
    await post<void>('/bff/logout', {})
  } catch {
    // 网关登出失败也要往下走：至少离开当前页面。
    // 停在这里比"会话没清干净"更糟——用户会以为按钮坏了
  }

  // 清前端内存里的身份，避免跳转前那一瞬间还显示着旧用户名
  identityCleared?.()

  // replace：登出后按后退不该回到已失效的页面
  window.location.replace('/bff/login')
}

/**
 * 身份清理回调。
 *
 * `api/auth` 不认识 `useSession`（那会形成 api ← composables 的循环依赖，
 * 而 session 本身依赖 api）。用回调把"清身份"这件事交给上层注册。
 */
let identityCleared: (() => void) | null = null

/** 注册身份清理。由 `useSession` 在模块加载时调用一次。 */
export function onLogout(handler: () => void): void {
  identityCleared = handler
}
