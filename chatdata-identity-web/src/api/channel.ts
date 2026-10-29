import { get, post } from './http'

/**
 * 渠道自助绑定。
 *
 * ## 绑定目标由会话决定，不由链接决定
 *
 * 链接里的 ticket 只说明「某个飞书账号想绑定」。绑到**谁**由当前登录会话决定。
 * 所以这里的每个接口都不传用户 id——传了就等于让持有链接的人指定要接管哪个账号。
 */

export interface BindPreview {
  authenticated: boolean
  currentUser?: string
  valid?: boolean
  message?: string
  channel?: string
  /** 已掩码，只保留前后几位；完整 open_id 对人没有意义 */
  externalIdMasked?: string
  displayName?: string
}

export interface BindStatus {
  authenticated: boolean
  username?: string
  displayName?: string
  externalProvider?: string | null
  externalIdMasked?: string | null
}

/** 预览绑定：会绑到哪个渠道账号，当前登录的是谁。不消费票据。 */
export async function previewBind(ticket: string): Promise<BindPreview> {
  return await get<BindPreview>(`/iam/channel/bind?ticket=${encodeURIComponent(ticket)}`)
}

/** 确认绑定。票据在这一步被消费（一次性）。 */
export async function confirmBind(ticket: string): Promise<void> {
  await post<void>('/iam/channel/bind', { ticket })
}

/** 当前用户的绑定状态。 */
export async function bindStatus(): Promise<BindStatus> {
  return await get<BindStatus>('/iam/channel/bind/status')
}

/** 解除绑定。只作用于当前登录用户。 */
export async function unbindChannel(): Promise<void> {
  await post<void>('/iam/channel/bind/unbind')
}
