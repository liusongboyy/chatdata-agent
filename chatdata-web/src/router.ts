import { createRouter, createWebHistory } from 'vue-router'
import { session } from './composables/useSession'
import { P } from './permissions'
import LoginView from './views/LoginView.vue'
import AskView from './views/AskView.vue'
import AdminLayout from './views/admin/AdminLayout.vue'
import DatasourceListView from './views/admin/DatasourceListView.vue'
import ModelListView from './views/admin/ModelListView.vue'
import DomainListView from './views/admin/DomainListView.vue'
import DomainTablesView from './views/admin/DomainTablesView.vue'
import AssistantListView from './views/admin/AssistantListView.vue'
import FeedbackReviewView from './views/admin/FeedbackReviewView.vue'
import ModelingDraftView from './views/admin/ModelingDraftView.vue'
import HealthView from './views/admin/HealthView.vue'

/**
 * 管理页清单：路径、名称、标题、以及**进入它需要的权限**。
 *
 * 导航与路由守卫都读这一份，免得"菜单里看得见、点进去被弹回"。
 */
export const ADMIN_PAGES = [
  {
    name: 'admin-health',
    path: '/admin/health',
    label: '健康度',
    // 与「语义域」同档：看健康度的人就是配域的人
    permission: P.ASK_ADMIN_DOMAINS,
  },
  {
    name: 'admin-datasources',
    path: '/admin/datasources',
    label: '数据源',
    permission: P.ASK_ADMIN_DATASOURCES,
  },
  {
    name: 'admin-models',
    path: '/admin/models',
    label: '模型',
    permission: P.ASK_ADMIN_MODELS,
  },
  { name: 'admin-domains', path: '/admin/domains', label: '语义域', permission: P.ASK_ADMIN_DOMAINS },
  {
    name: 'admin-modeling',
    path: '/admin/modeling',
    label: '建模助手',
    // 用**页面码**而不是操作码：ASK_ADMIN_DOMAINS_TABLES 是"能改注册表"的
    // 操作权限，不在 ASK_ADMIN_PAGE_CODES 里，用它会让 canConfig 判不到，
    // 表现是「点菜单被弹回问数页」（实测踩到）
    permission: P.ASK_ADMIN_DOMAINS,
  },
  {
    name: 'admin-assistants',
    path: '/admin/assistants',
    label: '助理',
    permission: P.ASK_ADMIN_ASSISTANTS,
  },
  {
    name: 'admin-feedback',
    path: '/admin/feedback',
    label: '反馈审核',
    permission: P.ASK_ADMIN_FEEDBACK,
  },
] as const

/** 当前主体**第一个**有权限的管理页。一个都没有时返回 null。 */
export function firstAllowedAdminPath(permissions: readonly string[]): string | null {
  const hit = ADMIN_PAGES.find((page) => permissions.includes(page.permission))
  return hit ? hit.path : null
}

export const router = createRouter({
  // 挂在 /app/ 下，故 history base 必须与 vite 的 base 一致
  history: createWebHistory('/app/'),
  routes: [
    { path: '/', redirect: '/ask' },
    { path: '/login', name: 'login', component: LoginView },
    { path: '/ask', name: 'ask', component: AskView, meta: { requiresAuth: true } },
    {
      // 管理面。**与"能问数"是两回事**，见 ADR-0004：早期给所有用户都授了 chat2bi，
      // 结果是每个能问数的人都能进管理台配数据源与指标口径。
      // 现在按页面级功能权限判，判据在后端，这里只是提前拦一道
      path: '/admin',
      component: AdminLayout,
      meta: { requiresAuth: true, requiresAdmin: true },
      children: [
        // ⚠️ 这里用**静态**重定向，不能按权限算目标。
        // vue-router 的重定向函数在 beforeEach 守卫**之前**执行，那时身份还没解析，
        // 权限是空的，会一律落到 /ask（实测踩到：只有反馈审核权限的人进不了管理台）。
        // 权限跳转交给下面的守卫：它先解析身份，再按 meta.permission 弹到第一个有权限的页
        { path: '', redirect: '/admin/datasources' },
        {
          path: 'health',
          name: 'admin-health',
          component: HealthView,
          meta: { permission: P.ASK_ADMIN_DOMAINS },
        },
        {
          path: 'datasources',
          name: 'admin-datasources',
          component: DatasourceListView,
          meta: { permission: P.ASK_ADMIN_DATASOURCES },
        },
        {
          path: 'models',
          name: 'admin-models',
          component: ModelListView,
          meta: { permission: P.ASK_ADMIN_MODELS },
        },
        {
          path: 'domains',
          name: 'admin-domains',
          component: DomainListView,
          meta: { permission: P.ASK_ADMIN_DOMAINS },
        },
        {
          path: 'domains/:id/tables',
          name: 'admin-domain-tables',
          component: DomainTablesView,
          meta: { permission: P.ASK_ADMIN_DOMAINS_TABLES },
        },
        {
          path: 'modeling',
          name: 'admin-modeling',
          component: ModelingDraftView,
          meta: { permission: P.ASK_ADMIN_DOMAINS },
        },
        {
          path: 'assistants',
          name: 'admin-assistants',
          component: AssistantListView,
          meta: { permission: P.ASK_ADMIN_ASSISTANTS },
        },
        {
          path: 'feedback',
          name: 'admin-feedback',
          component: FeedbackReviewView,
          meta: { permission: P.ASK_ADMIN_FEEDBACK },
        },
      ],
    },
  ],
})

/**
 * 路由守卫：未登录时跳登录页。
 *
 * 身份在**首次导航前**解析一次（见 useSession），之后靠内存状态。
 *
 * 管理台的准入：先看"有没有任一管理页权限"（canConfig），再看这一页要的码。
 * 两级都要，因为只有 `ask.ui.charts` 的人也能通过"有权限"这一关，
 * 但他不该看到管理台入口。
 */
router.beforeEach(async (to) => {
  if (!to.meta.requiresAuth) return true

  await session.ensureResolved()
  const user = session.user.value
  if (!user) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  if (to.meta['requiresAdmin'] && !user.canConfig) {
    return { name: 'ask' }
  }

  const required = to.meta['permission'] as string | undefined
  if (required && !user.permissions.includes(required)) {
    // 跳到第一个有权限的管理页，而不是原地弹回造成死循环
    return firstAllowedAdminPath(user.permissions) ?? { name: 'ask' }
  }
  return true
})
