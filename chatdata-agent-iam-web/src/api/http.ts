/**
 * HTTP 基础设施。
 *
 * ## 凭据只走 Cookie
 *
 * IAM 的登录是"建立服务端会话"：`SESSION` Cookie 是 HttpOnly 的，
 * 前端拿不到也不需要拿。写请求要带 CSRF token——它来自非 HttpOnly 的
 * `XSRF-TOKEN` Cookie，由后端在 `/iam/auth/csrf` 时下发。
 */

/** 后端的统一信封。 */
export interface Envelope<T> {
  code: number
  message: string
  data: T
}

export class ApiError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return error.message
  if (error instanceof Error) return error.message
  return '未知错误'
}

/**
 * 从 Cookie 读 CSRF token。**名字是 IAM-XSRF-TOKEN**：
 * 网关也用 XSRF-TOKEN，Cookie 不区分端口，同名会互相覆盖。
 * 读不到就返回 null——请求照发，由后端判 403。
 */
function csrfToken(): string | null {
  const match = document.cookie.match(/(?:^|;\s*)IAM-XSRF-TOKEN=([^;]+)/)
  return match && match[1] ? decodeURIComponent(match[1]) : null
}

async function toError(response: Response): Promise<ApiError> {
  let message = `请求失败（HTTP ${response.status}）`
  try {
    const body = (await response.json()) as Partial<Envelope<unknown>>
    if (body.message) message = body.message
  } catch {
    // 响应体不是 JSON（如 502 的 HTML 错误页）——用默认信息
  }
  return new ApiError(response.status, message)
}

async function send<T>(method: string, url: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (method !== 'GET') {
    const token = csrfToken()
    if (token) headers['X-XSRF-TOKEN'] = token
  }

  const response = await fetch(url, {
    method,
    credentials: 'same-origin',
    headers,
    ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
  })

  if (!response.ok) throw await toError(response)
  if (response.status === 204) return undefined as T
  return (await response.json()) as T
}

export function get<T>(url: string): Promise<T> {
  return send<T>('GET', url)
}

export function post<T>(url: string, body?: unknown): Promise<T> {
  return send<T>('POST', url, body ?? {})
}

export function put<T>(url: string, body?: unknown): Promise<T> {
  return send<T>('PUT', url, body ?? {})
}
