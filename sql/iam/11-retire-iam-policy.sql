-- =====================================================================
-- ADR-0010 收尾：退役 IAM 的数据策略路径
--
-- 从这一刻起：
--   · 数据域/助理的授权与行/列规则只存在 ask 的 perm_json；
--   · IAM 不再提供 `/iam/admin/policies`、`/iam/policy/effective`、
--     `/iam/policy/versions`，也不再解析 DOMAIN/ASSISTANT 文档；
--   · IAM 只保留身份与功能权限，以及只读的角色/用户目录
--     （`/iam/policy/roles`、`/iam/policy/users`）。
--
-- 前置条件（**不要跳过**）：
--   1. 已执行 10-migrate-policies-back-to-ask.sql，并确认它的复核查询为 0 行；
--   2. ask 与 IAM 都已升级到不再读 iam_policy 的版本，且观察了一个发布周期。
--
-- 本脚本**不删除数据**：把 iam_policy 改名成带日期的 retired 表保留。
-- 确认不再需要回溯后，再由人工执行 DROP TABLE。
-- 可重复执行。
-- =====================================================================

-- 1) 删除已经退役的功能权限码。
--    PermissionCatalog 里已没有这两项；留着它们只会出现在"未知码"清理报告里。
DELETE FROM `iam_role_permission`
WHERE `code` IN ('iam.policies', 'iam.policies.write');

-- 2) 退役策略表：改名而不是 DROP，保留最后一份可回溯的数据。
--    用存储过程是因为 MySQL 脚本没有 IF EXISTS 表保护的等价写法，
--    而重复执行时 `CREATE TABLE ... LIKE` 会直接报错。
DROP PROCEDURE IF EXISTS `retire_iam_policy`;

DELIMITER $$
CREATE PROCEDURE `retire_iam_policy`()
BEGIN
    DECLARE v_rows BIGINT DEFAULT 0;

    IF EXISTS (SELECT 1
               FROM `information_schema`.`tables`
               WHERE `table_schema` = DATABASE()
                 AND `table_name` = 'iam_policy') THEN

        SELECT COUNT(*) INTO v_rows FROM `iam_policy`;

        CREATE TABLE IF NOT EXISTS `iam_policy_retired_20261023` LIKE `iam_policy`;
        -- INSERT IGNORE：重复执行时不会因为主键冲突失败
        INSERT IGNORE INTO `iam_policy_retired_20261023` SELECT * FROM `iam_policy`;

        -- 先确认副本确实写进去了，再动原表。
        -- 行数对不上就抛错，绝不带着"可能丢了一份策略"继续。
        IF (SELECT COUNT(*) FROM `iam_policy_retired_20261023`) < v_rows THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'iam_policy 退役失败：副本行数少于原表，已中止';
        END IF;

        DROP TABLE `iam_policy`;

        SELECT CONCAT('iam_policy 已退役：', v_rows, ' 行保存在 iam_policy_retired_20261023')
               AS `retire_result`;
    ELSE
        SELECT 'iam_policy 不存在，无需退役' AS `retire_result`;
    END IF;
END$$
DELIMITER ;

CALL `retire_iam_policy`();
DROP PROCEDURE IF EXISTS `retire_iam_policy`;

-- 3) 复核：这两个码应该为 0 行
SELECT `code`, COUNT(*) AS `roles`
FROM `iam_role_permission`
WHERE `code` IN ('iam.policies', 'iam.policies.write')
GROUP BY `code`;
