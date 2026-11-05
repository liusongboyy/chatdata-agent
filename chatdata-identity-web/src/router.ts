import { createRouter, createWebHistory } from 'vue-router'
import { session } from './composables/useSession'
import { P } from './permissions'
import LoginView from './views/LoginView.vue'
import AdminLayout from './views/AdminLayout.vue'
import UserListView from './views/UserListView.vue'
import OrgListView from './views/OrgListView.vue'
import RoleListView from './views/RoleListView.vue'
import NoAccessView from './views/NoAccessView.vue'
import BindView from './views/BindView.vue'
import AccountView from './views/AccountView.vue'

/**
 * 管理页清单：路径、名称、标题、以及**进入它需要的权限**。
 *
 * 导航与路由守卫都读这一份，免得"菜单里看得见、点进去被弹回"。
 * 新增页面时只改这里。
 */
export const ADMIN_PAGES = [
  { name: 'admin-users', path: '/admin/users', label: '用户', permission: P.IAM_USERS },
  { name: 'admin-orgs', path: '/admin/orgs', label: '组织', permission: P.IAM_ORGS },
  { name: 'admin-roles', path: '/admin/roles', label: '角色', permission: P.IAM_ROLES },
] as const

/** 当前主体**第一个**有权限的管理页。一个都没有时返回 null。 */
export function firstAllowedPath(permissions: readonly string[]): string | null {
  const hit = ADMIN_PAGES.find((page) => permissions.includes(page.permission))
  return hit ? hit.path : null
}

export const router = createRouter({
  // 挂在 /iam/app/ 下，base 必须与 vite 的 base 一致
  history: createWebHistory('/iam/app/'),
  routes: [
    // ⚠️ 静态重定向，理由同上：重定向函数在守卫之前跑，那时权限还是空的。
    // 权限跳转由守卫完成
    { path: '/', redirect: '/admin/users' },
    { path: '/login', name: 'login', component: LoginView },
    { path: '/no-access', name: 'no-access', component: NoAccessView, meta: { requiresAuth: true } },
    // 渠道绑定页。要求登录：绑定到谁由会话决定，未登录时页面自己会跳登录页
    { path: '/bind', name: 'bind', component: BindView, meta: { requiresAuth: true } },
    /*
     * 我的账号。**不在 /admin 下**，也不要求任何权限码——
     * 判据只有"登录了"。绑定/解绑是每个人自己的事，而最需要这一页的
     * 恰恰是没有任何管理权限的普通用户。
     *
     * 放进 /admin 的话，路由守卫会因为他们没有 IAM_USERS 之类的码
     * 把人弹到 /no-access——那就等于这页不存在。
     */
    { path: '/account', name: 'account', component: AccountView, meta: { requiresAuth: true } },
    {
      path: '/admin',
      component: AdminLayout,
      meta: { requiresAuth: true },
      children: [
        { path: '', redirect: '/admin/users' },
        {
          path: 'users',
          name: 'admin-users',
          component: UserListView,
          meta: { permission: P.IAM_USERS },
        },
        {
          path: 'orgs',
          name: 'admin-orgs',
          component: OrgListView,
          meta: { permission: P.IAM_ORGS },
        },
        {
          path: 'roles',
          name: 'admin-roles',
          component: RoleListView,
          meta: { permission: P.IAM_ROLES },
        },
        // 数据策略已回归业务模块（ADR-0010）：IAM 只管身份与功能权限。
        // 旧书签重定向到用户页，避免留下一个打不开的死链接。
        { path: 'policies', redirect: '/admin/users' },
      ],
    },
  ],
})

/**
 * 路由守卫。
 *
 * 两件事：未登录跳登录页（带 returnTo，授权码流程要用）；已登录但没这个页面的权限时，
 * 跳到**第一个有权限的页面**，而不是原地弹回造成死循环。
 */
router.beforeEach(async (to) => {
  if (!to.meta['requiresAuth']) return true

  await session.ensureResolved()
  if (!session.user.value) {
    return { name: 'login', query: { returnTo: to.fullPath } }
  }

  const required = to.meta['permission'] as string | undefined
  if (required && !(session.user.value.permissions ?? []).includes(required)) {
    const fallback = firstAllowedPath(session.user.value.permissions ?? [])
    // 一个管理页都没有：去"无权限"页，而不是在 /admin/* 之间来回跳
    return fallback ? { path: fallback } : { name: 'no-access' }
  }
  return true
})
