/**
 * 功能权限码。
 *
 * 与 IAM 后端的 `PermissionCatalog` 一一对应。**写常量而不是到处拼裸字符串**：
 * 打错一个字母不会报错，只会表现为"这个按钮永远不出现"，而那种问题极难发现。
 *
 * 这里的标签与分组**不写在前端**——它们来自 `GET /iam/admin/permissions`，
 * 免得两边文案漂移。
 */
export const P = {
  // IAM 账号中心
  IAM_USERS: 'iam.users',
  IAM_USERS_WRITE: 'iam.users.write',
  IAM_USERS_ROLES: 'iam.users.roles',
  IAM_USERS_RESET_PASSWORD: 'iam.users.resetPassword',
  IAM_ORGS: 'iam.orgs',
  IAM_ORGS_WRITE: 'iam.orgs.write',
  IAM_ROLES: 'iam.roles',
  IAM_ROLES_WRITE: 'iam.roles.write',
  IAM_ROLES_PERMISSIONS: 'iam.roles.permissions',

  // 问数管理台（IAM 前端不用，列在这里是为了与后端目录保持可对照）
  ASK_ADMIN_DATASOURCES: 'ask.admin.datasources',
  ASK_ADMIN_DOMAINS: 'ask.admin.domains',
  ASK_ADMIN_ASSISTANTS: 'ask.admin.assistants',
  ASK_ADMIN_FEEDBACK: 'ask.admin.feedback',
} as const

export type PermissionCode = (typeof P)[keyof typeof P]
