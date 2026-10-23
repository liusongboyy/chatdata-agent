-- =====================================================================
-- 助理访问授权缺口修复（ADR-0002 的 R1 在助理层的对应项）
--
-- 背景（已实测，见 AssistantAccessProbeTest）：
--   chat2bi_assistant.perm_json 缺少 `enabled` 字段时，canAccess() **对所有角色返回 true**。
--   决策逻辑为：
--       createBy == username            → 放行
--       permJson 为空 / 解析失败          → 放行
--       enabled != true 且 != "true"    → 放行   ← 缺口在此
--       enabled == true → 按 grantRoles / grantUsers 判定，两者都为空则拒绝
--
--   演示数据的两个助理 perm_json 均为 NULL，因此对任何身份都放行。
--
-- 严重性低于域层：助理闸门管的是"能用哪个助手"（功能可见性），
-- **数据保护仍由域层承担**——实测中无角色的用户被域层拦成 0 行。
-- 故本项为纵深防御，不阻塞其他工作。
--
-- 本脚本为两个演示助理补上授权。真实项目应在助理管理界面配置，
-- 此处仅用于让本地环境处于与生产一致的状态。
-- =====================================================================

-- 保险问数助手：授予带数据权限规则的两个角色
UPDATE `chat2bi_assistant`
SET `perm_json` = JSON_OBJECT(
        'enabled',    TRUE,
        'grantRoles', JSON_ARRAY('区域经理', '分析师'),
        'grantUsers', JSON_ARRAY()
    ),
    `update_time` = NOW()
WHERE `id` = '956f9607116047ada54619734d85f99f';

-- 系统用户助手：演示数据中只有 区域经理 有对应的列脱敏规则
UPDATE `chat2bi_assistant`
SET `perm_json` = JSON_OBJECT(
        'enabled',    TRUE,
        'grantRoles', JSON_ARRAY('区域经理'),
        'grantUsers', JSON_ARRAY()
    ),
    `update_time` = NOW()
WHERE `id` = 'eaba9231e2db4b348a321857067508eb';
