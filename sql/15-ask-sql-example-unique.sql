-- =====================================================================
-- SQL 范例：同一域内问法唯一
--
-- 为什么需要：
--
--   1. **数据本身**：两条问法完全相同的范例是纯冗余——检索只会挑中其中一条，
--      而管理员以为自己加了两条不同的写法。
--   2. **实现依赖**：管理面的 create 是「插入后按问法读回主键」
--      （{@code @Options(useGeneratedKeys)} 要往参数对象回填 id，而 record 不可变，
--      见 SemanticAdminMapper 的说明）。**问法不唯一时，读回会拿到错的那条 id。**
--
-- 作用域是 (domain_id, question) 而不是全局 question：同一个问法在不同域里
-- 引用的是不同的表，各自有一条范例是合理的。
--
-- 长度：question 是 VARCHAR(500) + utf8mb4 → 索引键 2000 字节，
-- 加上 domain_id 共约 2008 字节，在 InnoDB 的 3072 字节上限之内（DYNAMIC 行格式）。
-- =====================================================================

ALTER TABLE `ask_sql_example`
  ADD UNIQUE KEY `uk_ask_sql_example_domain_question` (`domain_id`, `question`);
