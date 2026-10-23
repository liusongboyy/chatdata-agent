/**
 * 时间显示。
 *
 * 后端返回的是 Java `LocalDateTime.toString()`，形如 `2026-10-20T22:59:52`。
 * 那个 `T` 是 ISO-8601 的分隔符，不是给人看的——列表里出现它会显得像没处理过的原始数据。
 *
 * 刻意**不用 `toLocaleString()`**：它按浏览器时区与语言环境重新解释，
 * 而这里的值本来就是服务端的本地时间，再"本地化"一次会平白引入时区偏移。
 * 直接换掉分隔符即可。
 */
export function formatDateTime(value: string | null | undefined): string {
  if (!value) return '—'
  return value.replace('T', ' ').replace(/\.\d+$/, '')
}
