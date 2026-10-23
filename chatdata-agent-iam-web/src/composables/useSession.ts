import { ref } from 'vue'
import { me, type Principal } from '../api/auth'

/**
 * 当前会话。
 *
 * 身份在**首次导航前**解析一次，之后靠内存状态；不做每次导航都请求 `/iam/me`。
 * 会话本身在服务端（Redis），刷新页面时重新解析一次。
 */
const user = ref<Principal | null>(null)
let resolved = false

export const session = {
  user,

  async ensureResolved(): Promise<void> {
    if (resolved) return
    try {
      const principal = await me()
      user.value = principal.authenticated ? principal : null
    } catch {
      // 401 也走这里：没登录不是错误
      user.value = null
    } finally {
      resolved = true
    }
  },

  setUser(principal: Principal | null): void {
    user.value = principal
    resolved = true
  },

  clear(): void {
    user.value = null
    resolved = true
  },
}
