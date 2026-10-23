/**
 * HTTP 基础设施。
 *
 * ## 凭据只走 Cookie，而且是网关的会话 Cookie
 *
 * 登录由网关的 BFF 完成，浏览器只有 `SESSION`（HttpOnly），
 * **拿不到也不需要 access token**。本模块完全依赖它。
 *
 * ## 写请求要带 CSRF token
 *
 * 会话 Cookie 鉴权必须防 CSRF。网关下发非 HttpOnly 的 `XSRF-TOKEN` Cookie，
 * 前端读出来回填到 `X-XSRF-TOKEN` 头（Spring Security 的约定）。
 * 读不到就照发——让后端判 403，而不是在这里静默跳过。
 */

/** 后端统一的错误信封。字段名沿用现有 `/sys/**` 契约。 */
interface ErrorEnvelope {
  success?: boolean
  code?: number
  message?: string
}

/** 接口错误。带上状态码，调用方可据此分支（如 401 跳登录）。 */
export class ApiError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

/** 从任意抛出物里取可读信息。不用 `any`——外部输入一律先当 unknown。 */
export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return error.message
  if (error instanceof Error) return error.message
  return '未知错误'
}

async function toError(response: Response): Promise<ApiError> {
  let message = `请求失败（HTTP ${response.status}）`
  try {
    const body = (await response.json()) as ErrorEnvelope
    if (body.message) message = body.message
  } catch {
    // 响应体不是 JSON（如 502 的 HTML 错误页）——用默认信息，不把 HTML 塞进提示
  }
  return new ApiError(response.status, message)
}

/**
 * GET。
 *
 * @throws ApiError 非 2xx
 */
export async function get<T>(url: string): Promise<T> {
  const response = await fetch(url, {
    method: 'GET',
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  })

  if (!response.ok) throw await toError(response)
  return (await response.json()) as T
}

/**
 * POST JSON。
 *
 * @throws ApiError 非 2xx
 */
export async function post<T>(url: string, body: unknown): Promise<T> {
  return send<T>('POST', url, body)
}

/** PUT JSON。 */
export async function put<T>(url: string, body: unknown): Promise<T> {
  return send<T>('PUT', url, body)
}

/**
 * DELETE。
 *
 * <p>带 body 是因为部分后端框架要求 DELETE 也有 Content-Length；
 * 空对象比裸请求更稳。
 */
export async function del<T>(url: string): Promise<T> {
  return send<T>('DELETE', url, {})
}

/** 从 Cookie 读 CSRF token；读不到返回 null。 */
export function csrfToken(): string | null {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/)
  return match && match[1] ? decodeURIComponent(match[1]) : null
}

async function send<T>(method: string, url: string, body: unknown): Promise<T> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    Accept: 'application/json',
  }
  const csrf = csrfToken()
  if (csrf) headers['X-XSRF-TOKEN'] = csrf

  const response = await fetch(url, {
    method,
    credentials: 'same-origin',
    headers,
    body: JSON.stringify(body),
  })

  if (!response.ok) throw await toError(response)

  // 204 或空响应体时不该尝试解析 JSON——那会抛一个与真实原因无关的语法错误
  const text = await response.text()
  return (text ? JSON.parse(text) : null) as T
}
