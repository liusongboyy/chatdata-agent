import { computed, type ComputedRef } from 'vue'
import { session } from './useSession'
import type { PermissionCode } from '../permissions'

/**
 * 功能权限判定。
 *
 * ## 前端隐藏不是安全边界
 *
 * 这里只决定"要不要把按钮画出来"。真正的闸门在后端：每个接口都有对应的权限码，
 * 没权限直接 403。绕过界面直接调接口同样会被挡住。
 *
 * ## 为什么要有一个统一的 `can`
 *
 * 组件里散写 `session.user.value?.permissions?.includes('...')` 有两个问题：
 * 权限还没解析完时会闪一下、以及权限码写错了没人知道。集中在这里之后，
 * 调用方写的是 `can(P.IAM_USERS_WRITE)` —— 打错常量名 TypeScript 会直接报错。
 */
export function usePermissions(): {
  can: (code: PermissionCode | string) => boolean
  canAny: (codes: readonly string[]) => boolean
  granted: ComputedRef<string[]>
  loaded: ComputedRef<boolean>
} {
  const granted = computed<string[]>(() => session.user.value?.permissions ?? [])
  const loaded = computed(() => session.user.value !== null)

  function can(code: PermissionCode | string): boolean {
    return granted.value.includes(code)
  }

  function canAny(codes: readonly string[]): boolean {
    return codes.some((c) => granted.value.includes(c))
  }

  return { can, canAny, granted, loaded }
}
