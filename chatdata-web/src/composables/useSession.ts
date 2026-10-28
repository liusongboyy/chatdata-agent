import { ref, readonly } from 'vue'
import { fetchCurrentUser, type CurrentUser } from '../api/auth'

/**
 * 当前会话。
 *
 * ## 为什么不做全局状态库
 *
 * 整个应用只有"当前用户"这一份跨页面状态。引 Pinia 只为一件事，
 * 换来的是一个要维护的依赖与一套要遵守的写法。等真有第二、第三份状态再说。
 *
 * ## 身份解析只做一次
 *
 * `ensureResolved` 用共享的 Promise 去重：路由守卫与页面 `onMounted` 可能同时调它，
 * 不做去重会并发发两个请求，而且后回来的那个可能覆盖先回来的结果。
 */

const user = ref<CurrentUser | null>(null)
let pending: Promise<void> | null = null

async function resolve(): Promise<void> {
  try {
    user.value = await fetchCurrentUser()
  } catch {
    // 未登录是**正常状态**，不是错误——登录页就是为它准备的。
    // 这里不弹任何提示，否则每次打开应用都先闪一个红色报错
    user.value = null
  }
}

async function ensureResolved(): Promise<void> {
  if (user.value) return
  pending ??= resolve().finally(() => {
    pending = null
  })
  await pending
}

/** 登录成功后由登录页调用，避免再请求一次 getUserInfo。 */
function setUser(next: CurrentUser): void {
  user.value = next
}

function clear(): void {
  user.value = null
}

export const session = {
  user: readonly(user),
  ensureResolved,
  setUser,
  clear,
}
