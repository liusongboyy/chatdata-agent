import { get, post, type Envelope } from './http'

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

/**
 * 预览绑定：会绑到哪个渠道账号，当前登录的是谁。不消费票据。
 *
 * ⚠️ **要解包信封。** 这些接口返回 `Result<T>`（`{code, message, data}`），
 * 与 `/iam/me` 不同——那个返回裸 Map。把信封直接当 `BindPreview` 用的话，
 * 所有字段都是 undefined：页面显示「票据无效」，而真实原因是读取方式错了。
 *
 * 这个坑实测踩过：`bindStatus()` 一直返回空，页面永远显示「未绑定」，
 * 而数据库里明明绑着。
 */
export async function previewBind(ticket: string): Promise<BindPreview> {
  return (await get<Envelope<BindPreview>>(
    `/iam/channel/bind?ticket=${encodeURIComponent(ticket)}`,
  )).data
}

/** 确认绑定。票据在这一步被消费（一次性）。 */
export async function confirmBind(ticket: string): Promise<void> {
  await post<void>('/iam/channel/bind', { ticket })
}

/** 当前用户的绑定状态。**要解包信封**，理由见 {@link previewBind}。 */
export async function bindStatus(): Promise<BindStatus> {
  return (await get<Envelope<BindStatus>>('/iam/channel/bind/status')).data
}

/** 解除绑定。只作用于当前登录用户。 */
export async function unbindChannel(): Promise<void> {
  await post<void>('/iam/channel/bind/unbind')
}
