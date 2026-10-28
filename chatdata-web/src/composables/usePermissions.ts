import { computed, type ComputedRef } from 'vue'
import { session } from './useSession'
import type { PermissionCode } from '../permissions'

/**
 * 功能权限判定（问数侧）。
 *
 * ## 前端隐藏不是安全边界
 *
 * 这里只决定"要不要把按钮/tab 画出来"。真正的闸门在后端：
 * 每个管理面接口都有对应的权限码（见 `AdminApiPermissions`），没权限直接 403。
 *
 * ## 为什么要有一个统一的 `can`
 *
 * 组件里散写 `session.user.value?.permissions?.includes('...')` 有两个问题：
 * 权限码写错了没人知道；以及各处拼法不一致。集中之后调用方写 `can(P.ASK_ADMIN_FEEDBACK_REVIEW)`，
 * 打错常量名 TypeScript 直接报错。
 */
export function usePermissions(): {
  can: (code: PermissionCode | string) => boolean
  canAny: (codes: readonly string[]) => boolean
  granted: ComputedRef<readonly string[]>
} {
  const granted = computed<readonly string[]>(() => session.user.value?.permissions ?? [])

  function can(code: PermissionCode | string): boolean {
    return granted.value.includes(code)
  }

  function canAny(codes: readonly string[]): boolean {
    return codes.some((c) => granted.value.includes(c))
  }

  return { can, canAny, granted }
}
