/**
 * 功能权限码（问数侧）。
 *
 * 与 IAM 后端的 `PermissionCatalog` 一一对应。**写常量而不是到处拼裸字符串**：
 * 打错一个字母不会报错，只会表现为"这个按钮永远不出现"，极难发现。
 */
export const P = {
  // 问数管理台
  ASK_ADMIN_DATASOURCES: 'ask.admin.datasources',
  ASK_ADMIN_DATASOURCES_WRITE: 'ask.admin.datasources.write',
  ASK_ADMIN_DATASOURCES_DELETE: 'ask.admin.datasources.delete',
  ASK_ADMIN_DATASOURCES_TEST: 'ask.admin.datasources.test',
  ASK_ADMIN_MODELS: 'ask.admin.models',
  ASK_ADMIN_MODELS_WRITE: 'ask.admin.models.write',
  ASK_ADMIN_MODELS_TEST: 'ask.admin.models.test',
  ASK_ADMIN_DOMAINS: 'ask.admin.domains',
  ASK_ADMIN_DOMAINS_WRITE: 'ask.admin.domains.write',
  ASK_ADMIN_DOMAINS_DELETE: 'ask.admin.domains.delete',
  ASK_ADMIN_DOMAINS_TABLES: 'ask.admin.domains.tables',
  ASK_ADMIN_ASSISTANTS: 'ask.admin.assistants',
  ASK_ADMIN_ASSISTANTS_WRITE: 'ask.admin.assistants.write',
  ASK_ADMIN_ASSISTANTS_DELETE: 'ask.admin.assistants.delete',
  ASK_ADMIN_FEEDBACK: 'ask.admin.feedback',
  ASK_ADMIN_FEEDBACK_REVIEW: 'ask.admin.feedback.review',

  // 问数页
  ASK_UI_CHARTS: 'ask.ui.charts',
  ASK_UI_FEEDBACK: 'ask.ui.feedback',
  ASK_UI_FEEDBACK_CORRECT: 'ask.ui.feedback.correct',
} as const

export type PermissionCode = (typeof P)[keyof typeof P]

/**
 * 管理台的**页面**权限。
 *
 * 用于回答"这个人能不能进管理台"。刻意不含 `ask.ui.*`：
 * 那些是问数页里的展示偏好，只有它的人不该看到「管理台」入口。
 */
export const ASK_ADMIN_PAGE_CODES: readonly string[] = [
  P.ASK_ADMIN_DATASOURCES,
  P.ASK_ADMIN_MODELS,
  P.ASK_ADMIN_DOMAINS,
  P.ASK_ADMIN_ASSISTANTS,
  P.ASK_ADMIN_FEEDBACK,
]
