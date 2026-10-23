-- =====================================================================
-- 用户所属组织
--
-- 用途：支撑**动态行级权限**——`rowRules` 里配 `dynamic: true` +
-- `sysVar: "orgCode"` 时，运行时用当前登录用户的组织值做过滤。
--
-- 为什么需要它：没有组织维度时，只能"按角色写死区域"——每加一个区域就要加一个角色，
-- 角色表会随组织变化不断膨胀。有了它才能做到"一个角色 + 按人取组织"。
--
-- 前提说明（实测确认）：动态变量的取值来自
-- `JmReportTokenServiceI.getUserInfo(token)` 返回的 Map，按 `sysVar` 的字符串查键。
-- 字节码确认**没有白名单**——任意自定义键都可以用，不限于厂商预定义的那五个。
-- 因此这里用 `org_code` 这个自有命名，而不是 jeecg 的 `sysOrgCode`。
-- （`ChatbiTokenService` 会同时填两套键名，厂商文档里的示例也能直接用。）
--
-- 关于多组织：本表按「一个用户属于一个组织」设计，这是最常见的情形。
-- 若将来需要一人多组织，应改为独立的 `chatdata_user_org` 关联表，
-- 并让 `getUserInfo` 返回一个列表或选定值——那时 `sysVar` 的语义需要重新定义。
-- =====================================================================

ALTER TABLE `chatdata_user`
    ADD COLUMN `org_code` VARCHAR(64) DEFAULT NULL COMMENT '所属组织编码，供动态行级权限使用'
        AFTER `display_name`;

-- 演示数据：给用户分配组织。
-- 演示语义域的 rowRules 用的是 `region`（华东/华北/…），此处按区域对齐，
-- 便于直接验证动态规则。
UPDATE `chatdata_user` SET `org_code` = '华东' WHERE `username` = 'zhangsan';
UPDATE `chatdata_user` SET `org_code` = '华北' WHERE `username` = 'lisi';
-- admin 不设组织：它本来就被厂商的 admin 绕过规则豁免（见 docs/permission-config.md 坑⑤），
-- 留空可以顺带验证「动态变量取不到值时规则被跳过」的行为。
UPDATE `chatdata_user` SET `org_code` = NULL WHERE `username` = 'admin';
