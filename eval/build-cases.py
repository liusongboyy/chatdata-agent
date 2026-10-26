# -*- coding: utf-8 -*-
"""
评测用例定义与构建。

`build.py` 会逐条执行 gold SQL 并校验，然后产出 `eval/*-fixture.json`：
  · 能执行（语法/列名/表名都对）
  · 结果非空（空结果对准确率没有信号）
  · 不含 CURDATE()/NOW()/RAND() 等不确定函数
  · 建模域引用的表都必须已注册

**改完用例必须重跑全部数据集并更新 README 里的基线数字**——
基线是验收依据，悄悄变了就等于没有基线。

用法：
    ./eval/fixture/prepare.sh          # 先建夹具库
    python3 eval/build-cases.py        # 再用例构建 JSON
"""

import json
import re
from collections import Counter
from pathlib import Path

import pymysql

# ══════════ 用例定义 ══════════

# -*- coding: utf-8 -*-
"""评测用例定义。每条用例的 gold SQL 由 build.py 实际执行校验。"""

# ── 保险分析域（建模，2001）─────────────────────────────────
INSURANCE = [
    # SIMPLE
    ("insurance-001", "SIMPLE", "保单总数是多少？",
     "SELECT COUNT(*) AS cnt FROM ins_policy", False, ["count"]),
    ("insurance-002", "SIMPLE", "按保单状态统计保单数量",
     "SELECT status, COUNT(*) AS cnt FROM ins_policy GROUP BY status", False, ["group"]),
    ("insurance-003", "SIMPLE", "按大区统计保单数量",
     "SELECT region, COUNT(*) AS cnt FROM ins_policy GROUP BY region", False, ["group"]),
    ("insurance-004", "SIMPLE", "状态为退保的保单有多少张？",
     "SELECT COUNT(*) AS cnt FROM ins_policy WHERE status = '退保'", False, ["filter"]),
    ("insurance-005", "SIMPLE", "客户总数是多少？",
     "SELECT COUNT(*) AS cnt FROM ins_customer", False, ["count"]),
    ("insurance-006", "SIMPLE", "按险种类型统计保单数量",
     "SELECT product_type, COUNT(*) AS cnt FROM ins_policy GROUP BY product_type", False, ["group"]),
    ("insurance-007", "SIMPLE", "理赔报案总件数是多少？",
     "SELECT COUNT(*) AS cnt FROM ins_claim", False, ["count"]),
    ("insurance-008", "SIMPLE", "按理赔状态统计理赔件数",
     "SELECT status, COUNT(*) AS cnt FROM ins_claim GROUP BY status", False, ["group"]),
    ("insurance-009", "SIMPLE", "按销售渠道统计保单数量",
     "SELECT channel, COUNT(*) AS cnt FROM ins_policy GROUP BY channel", False, ["group"]),
    ("insurance-010", "SIMPLE", "按客户等级统计客户数量",
     "SELECT cust_level, COUNT(*) AS cnt FROM ins_customer GROUP BY cust_level", False, ["group"]),
    ("insurance-011", "SIMPLE", "按理赔类型统计理赔件数",
     "SELECT claim_type, COUNT(*) AS cnt FROM ins_claim GROUP BY claim_type", False, ["group"]),
    ("insurance-012", "SIMPLE", "男性客户有多少人？",
     "SELECT COUNT(*) AS cnt FROM ins_customer WHERE gender = '男'", False, ["filter"]),
    # MEDIUM
    ("insurance-013", "MEDIUM", "华东的保费合计是多少？",
     "SELECT SUM(premium) AS total_premium FROM ins_policy WHERE region = '华东'", False, ["filter", "agg"]),
    ("insurance-014", "MEDIUM", "按大区统计保费合计，并按保费从高到低排列",
     "SELECT region, SUM(premium) AS total_premium FROM ins_policy GROUP BY region ORDER BY total_premium DESC",
     True, ["group", "agg", "order"]),
    ("insurance-015", "MEDIUM", "按险种类型统计保费合计",
     "SELECT product_type, SUM(premium) AS total_premium FROM ins_policy GROUP BY product_type",
     False, ["group", "agg"]),
    ("insurance-016", "MEDIUM", "按客户等级统计保单数量",
     "SELECT c.cust_level, COUNT(*) AS cnt FROM ins_policy p JOIN ins_customer c ON p.cust_id = c.id GROUP BY c.cust_level",
     False, ["join", "group"]),
    ("insurance-017", "MEDIUM", "各区域的理赔赔付金额合计是多少？",
     "SELECT province, SUM(settle_amount) AS total_settle FROM ins_claim GROUP BY province", False, ["group", "agg"]),
    ("insurance-018", "MEDIUM", "状态为已赔付的理赔，赔付金额合计是多少？",
     "SELECT SUM(settle_amount) AS total_settle FROM ins_claim WHERE status = '已赔付'", False, ["filter", "agg"]),
    ("insurance-019", "MEDIUM", "按销售渠道统计保费合计",
     "SELECT channel, SUM(premium) AS total_premium FROM ins_policy GROUP BY channel", False, ["group", "agg"]),
    ("insurance-020", "MEDIUM", "华东和华南两个大区的保单数量分别是多少？",
     "SELECT region, COUNT(*) AS cnt FROM ins_policy WHERE region IN ('华东', '华南') GROUP BY region",
     False, ["filter", "group"]),
    ("insurance-021", "MEDIUM", "按性别统计客户的平均年龄",
     "SELECT gender, AVG(age) AS avg_age FROM ins_customer GROUP BY gender", False, ["group", "agg"]),
    ("insurance-022", "MEDIUM", "保障年限为 10 年的保单有多少张？",
     "SELECT COUNT(*) AS cnt FROM ins_policy WHERE term_years = 10", False, ["filter"]),
    ("insurance-023", "MEDIUM", "理赔状态为已拒赔的理赔申请金额合计是多少？",
     "SELECT SUM(claim_amount) AS total_claim FROM ins_claim WHERE status = '已拒赔'", False, ["filter", "agg"]),
    # HARD
    ("insurance-024", "HARD", "2025 年生效的保单保费合计是多少？",
     "SELECT SUM(premium) AS total_premium FROM ins_policy WHERE effect_date >= '2025-01-01' AND effect_date < '2026-01-01'",
     False, ["time", "agg"]),
    ("insurance-025", "HARD", "2025 年生效且状态为生效的保单有多少张？",
     "SELECT COUNT(*) AS cnt FROM ins_policy WHERE effect_date >= '2025-01-01' AND effect_date < '2026-01-01' AND status = '生效'",
     False, ["time", "filter"]),
    ("insurance-026", "HARD", "2024 年报案的理赔件数是多少？",
     "SELECT COUNT(*) AS cnt FROM ins_claim WHERE report_date >= '2024-01-01' AND report_date < '2025-01-01'",
     False, ["time"]),
    ("insurance-027", "HARD", "按投保年份统计保费合计",
     "SELECT YEAR(sign_date) AS yr, SUM(premium) AS total_premium FROM ins_policy GROUP BY YEAR(sign_date) ORDER BY yr",
     False, ["time", "group"]),
    ("insurance-028", "HARD", "按理赔类型统计赔付金额合计，只看已赔付的理赔",
     "SELECT claim_type, SUM(settle_amount) AS total_settle FROM ins_claim WHERE status = '已赔付' GROUP BY claim_type",
     False, ["filter", "group", "agg"]),
    ("insurance-029", "HARD", "各险种类型的保额合计是多少？",
     "SELECT product_type, SUM(coverage_amount) AS total_coverage FROM ins_policy GROUP BY product_type",
     False, ["group", "agg"]),
    ("insurance-030", "HARD", "2025 年理赔金额合计比申请金额合计少多少？",
     "SELECT SUM(settle_amount) AS total_settle, SUM(claim_amount) AS total_claim "
     "FROM ins_claim WHERE report_date >= '2025-01-01' AND report_date < '2026-01-01'",
     False, ["time", "agg"]),
]

# ── 工单分析域（建模，2002）────────────────────────────────
TICKET_MODELED = [
    ("ticket-m-001", "SIMPLE", "工单总数是多少？",
     "SELECT COUNT(*) AS cnt FROM ticket WHERE deleted = 0", False, ["count"]),
    ("ticket-m-002", "SIMPLE", "按状态统计工单数量",
     "SELECT status, COUNT(*) AS cnt FROM ticket WHERE deleted = 0 GROUP BY status", False, ["group"]),
    ("ticket-m-003", "SIMPLE", "按优先级统计工单数量",
     "SELECT priority, COUNT(*) AS cnt FROM ticket WHERE deleted = 0 GROUP BY priority", False, ["group"]),
    ("ticket-m-004", "SIMPLE", "按来源统计工单数量",
     "SELECT source, COUNT(*) AS cnt FROM ticket WHERE deleted = 0 GROUP BY source", False, ["group"]),
    ("ticket-m-005", "SIMPLE", "状态为已关闭的工单有多少张？",
     "SELECT COUNT(*) AS cnt FROM ticket WHERE deleted = 0 AND status = 'CLOSED'", False, ["filter"]),
    ("ticket-m-006", "SIMPLE", "评论里内部备注有多少条？",
     "SELECT COUNT(*) AS cnt FROM ticket_comment WHERE deleted = 0 AND is_internal = 1", False, ["filter"]),
    ("ticket-m-007", "SIMPLE", "按工单分类统计工单数量",
     "SELECT c.name, COUNT(*) AS cnt FROM ticket t JOIN ticket_category c ON t.category_id = c.id "
     "WHERE t.deleted = 0 GROUP BY c.name", False, ["join", "group"]),
    ("ticket-m-008", "SIMPLE", "处理团队 3 的工单有多少张？",
     "SELECT COUNT(*) AS cnt FROM ticket WHERE deleted = 0 AND team_id = 3", False, ["filter"]),
    ("ticket-m-009", "SIMPLE", "按流转后的状态统计流转次数",
     "SELECT to_status, COUNT(*) AS cnt FROM ticket_flow WHERE deleted = 0 GROUP BY to_status", False, ["group"]),
    ("ticket-m-010", "SIMPLE", "优先级为 1 的工单有多少张？",
     "SELECT COUNT(*) AS cnt FROM ticket WHERE deleted = 0 AND priority = 1", False, ["filter"]),
    ("ticket-m-011", "SIMPLE", "按分类名称统计已关闭工单的数量",
     "SELECT c.name, COUNT(*) AS cnt FROM ticket t JOIN ticket_category c ON t.category_id = c.id "
     "WHERE t.deleted = 0 AND t.status = 'CLOSED' GROUP BY c.name", False, ["join", "group", "filter"]),
    ("ticket-m-012", "SIMPLE", "来自 WEB 渠道的工单有多少张？",
     "SELECT COUNT(*) AS cnt FROM ticket WHERE deleted = 0 AND source = 'WEB'", False, ["filter"]),
    # MEDIUM
    ("ticket-m-013", "MEDIUM", "2025 年 3 月创建的工单有多少张？",
     "SELECT COUNT(*) AS cnt FROM ticket WHERE deleted = 0 "
     "AND created_at >= '2025-03-01' AND created_at < '2025-04-01'", False, ["time"]),
    ("ticket-m-014", "MEDIUM", "每个分类下的工单平均评论数是多少？",
     "SELECT c.name, COUNT(cm.id) AS cnt FROM ticket t "
     "JOIN ticket_category c ON t.category_id = c.id "
     "JOIN ticket_comment cm ON cm.ticket_id = t.id AND cm.deleted = 0 "
     "WHERE t.deleted = 0 GROUP BY c.name", False, ["join", "group"]),
    ("ticket-m-015", "MEDIUM", "按创建月份统计 2025 年的工单数量",
     "SELECT DATE_FORMAT(created_at, '%Y-%m') AS ym, COUNT(*) AS cnt FROM ticket "
     "WHERE deleted = 0 AND created_at >= '2025-01-01' AND created_at < '2026-01-01' "
     "GROUP BY DATE_FORMAT(created_at, '%Y-%m') ORDER BY ym", False, ["time", "group"]),
    ("ticket-m-016", "MEDIUM", "每个优先级里状态为已关闭的工单有多少张？",
     "SELECT priority, COUNT(*) AS cnt FROM ticket WHERE deleted = 0 AND status = 'CLOSED' GROUP BY priority",
     False, ["filter", "group"]),
    ("ticket-m-017", "MEDIUM", "按来源和状态统计工单数量",
     "SELECT source, status, COUNT(*) AS cnt FROM ticket WHERE deleted = 0 GROUP BY source, status",
     False, ["group"]),
    ("ticket-m-018", "MEDIUM", "2025 年下半年（7 到 12 月）创建的工单有多少张？",
     "SELECT COUNT(*) AS cnt FROM ticket WHERE deleted = 0 "
     "AND created_at >= '2025-07-01' AND created_at < '2026-01-01'", False, ["time"]),
    ("ticket-m-019", "MEDIUM", "评论条数最多的前 5 张工单，各自有多少条评论？",
     "SELECT ticket_id, COUNT(*) AS cnt FROM ticket_comment WHERE deleted = 0 "
     "GROUP BY ticket_id ORDER BY cnt DESC, ticket_id LIMIT 5", True, ["group", "order", "limit"]),
    ("ticket-m-020", "MEDIUM", "AI 推荐分类与实际分类不一致的工单有多少张？",
     "SELECT COUNT(*) AS cnt FROM ticket WHERE deleted = 0 AND ai_category_id <> category_id",
     False, ["compare"]),
    ("ticket-m-021", "MEDIUM", "平均每张工单有多少次状态流转？",
     "SELECT COUNT(*) / (SELECT COUNT(*) FROM ticket WHERE deleted = 0) AS avg_flow "
     "FROM ticket_flow WHERE deleted = 0", False, ["agg"]),
    ("ticket-m-022", "MEDIUM", "每个处理团队的平均 AI 置信度是多少？",
     "SELECT team_id, AVG(ai_confidence) AS avg_conf FROM ticket WHERE deleted = 0 GROUP BY team_id",
     False, ["group", "agg"]),
    ("ticket-m-023", "MEDIUM", "2025 年第一季度创建的工单按来源统计数量",
     "SELECT source, COUNT(*) AS cnt FROM ticket WHERE deleted = 0 "
     "AND created_at >= '2025-01-01' AND created_at < '2025-04-01' GROUP BY source", False, ["time", "group"]),
    # HARD
    ("ticket-m-024", "HARD", "2025 年创建的工单里，已解决的工单占比是多少？用 0 到 1 之间的小数表示",
     "SELECT SUM(CASE WHEN status IN ('RESOLVED','CLOSED') THEN 1 ELSE 0 END) / COUNT(*) AS ratio "
     "FROM ticket WHERE deleted = 0 AND created_at >= '2025-01-01' AND created_at < '2026-01-01'",
     False, ["business", "time"]),
    ("ticket-m-025", "HARD", "已关闭的工单从创建到关闭平均用了多少小时？",
     "SELECT AVG(TIMESTAMPDIFF(HOUR, created_at, closed_at)) AS avg_hours FROM ticket "
     "WHERE deleted = 0 AND status = 'CLOSED' AND closed_at IS NOT NULL", False, ["business"]),
    ("ticket-m-026", "HARD", "已解决的工单从创建到解决平均用了多少分钟？",
     "SELECT AVG(TIMESTAMPDIFF(MINUTE, created_at, resolved_at)) AS avg_minutes FROM ticket "
     "WHERE deleted = 0 AND status IN ('RESOLVED','CLOSED') AND resolved_at IS NOT NULL",
     False, ["business"]),
    ("ticket-m-027", "HARD", "按分类统计工单中内部备注占全部评论的比例",
     "SELECT c.name, SUM(CASE WHEN cm.is_internal = 1 THEN 1 ELSE 0 END) / COUNT(*) AS ratio "
     "FROM ticket t JOIN ticket_category c ON t.category_id = c.id "
     "JOIN ticket_comment cm ON cm.ticket_id = t.id AND cm.deleted = 0 "
     "WHERE t.deleted = 0 GROUP BY c.name", False, ["join", "business"]),
    ("ticket-m-028", "HARD", "处理人 5 负责的工单里，各优先级的工单数量分别是多少？",
     "SELECT priority, COUNT(*) AS cnt FROM ticket WHERE deleted = 0 AND assignee_id = 5 GROUP BY priority",
     False, ["filter", "group"]),
    ("ticket-m-029", "HARD", "每个分类的工单平均解决时长（小时）是多少？只算已解决的",
     "SELECT c.name, AVG(TIMESTAMPDIFF(HOUR, t.created_at, t.resolved_at)) AS avg_hours "
     "FROM ticket t JOIN ticket_category c ON t.category_id = c.id "
     "WHERE t.deleted = 0 AND t.resolved_at IS NOT NULL GROUP BY c.name", False, ["join", "business"]),
    ("ticket-m-030", "HARD", "2025 年关闭的工单里，超时关闭（关闭时间晚于 SLA 截止时间）的有多少张？",
     "SELECT COUNT(*) AS cnt FROM ticket WHERE deleted = 0 AND status = 'CLOSED' "
     "AND closed_at IS NOT NULL AND closed_at > sla_deadline", False, ["business", "time"]),
]

# ── 工单直连域（RAW，2003）────────────────────────────────
TICKET_RAW = [
    ("ticket-r-001", "SIMPLE", "工单总数是多少？",
     "SELECT COUNT(*) AS cnt FROM ticket WHERE deleted = 0", False, ["count"]),
    ("ticket-r-002", "SIMPLE", "按状态统计工单数量",
     "SELECT status, COUNT(*) AS cnt FROM ticket WHERE deleted = 0 GROUP BY status", False, ["group"]),
    ("ticket-r-003", "SIMPLE", "通知里未读的有多少条？",
     "SELECT COUNT(*) AS cnt FROM notification WHERE deleted = 0 AND read_status = 0", False, ["filter"]),
    ("ticket-r-004", "SIMPLE", "知识库里已发布的文章有多少篇？",
     "SELECT COUNT(*) AS cnt FROM knowledge_article WHERE deleted = 0 AND status = 1", False, ["filter"]),
    ("ticket-r-005", "SIMPLE", "按通知类型统计通知条数",
     "SELECT type, COUNT(*) AS cnt FROM notification WHERE deleted = 0 GROUP BY type", False, ["group"]),
    ("ticket-r-006", "SIMPLE", "SLA 违规记录有多少条？",
     "SELECT COUNT(*) AS cnt FROM sla_violation WHERE deleted = 0", False, ["count"]),
    ("ticket-r-007", "SIMPLE", "按违规类型统计 SLA 违规次数",
     "SELECT violation_type, COUNT(*) AS cnt FROM sla_violation WHERE deleted = 0 GROUP BY violation_type",
     False, ["group"]),
    ("ticket-r-008", "SIMPLE", "分派规则一共有几条？",
     "SELECT COUNT(*) AS cnt FROM dispatch_rule WHERE deleted = 0", False, ["count"]),
    ("ticket-r-009", "SIMPLE", "按启用状态统计 SLA 策略数量",
     "SELECT enabled, COUNT(*) AS cnt FROM sla_policy WHERE deleted = 0 GROUP BY enabled", False, ["group"]),
    ("ticket-r-010", "SIMPLE", "知识附件一共有多少个？",
     "SELECT COUNT(*) AS cnt FROM knowledge_attachment WHERE deleted = 0", False, ["count"]),
    # MEDIUM
    ("ticket-r-011", "MEDIUM", "浏览量最高的那篇知识文章的标题是什么？",
     "SELECT title FROM knowledge_article WHERE deleted = 0 ORDER BY view_count DESC, id LIMIT 1",
     True, ["order", "limit"]),
    ("ticket-r-012", "MEDIUM", "每篇文章平均有多少个附件？",
     "SELECT COUNT(*) / (SELECT COUNT(*) FROM knowledge_article WHERE deleted = 0) AS avg_attach "
     "FROM knowledge_attachment WHERE deleted = 0", False, ["agg"]),
    ("ticket-r-013", "MEDIUM", "SLA 违规记录里逾期最久的是多少分钟？",
     "SELECT MAX(overdue_minutes) AS max_overdue FROM sla_violation WHERE deleted = 0", False, ["agg"]),
    ("ticket-r-014", "MEDIUM", "按分类统计知识文章数量",
     "SELECT category_id, COUNT(*) AS cnt FROM knowledge_article WHERE deleted = 0 GROUP BY category_id",
     False, ["group"]),
    ("ticket-r-015", "MEDIUM", "每张工单平均有多少条流转记录？",
     "SELECT COUNT(*) / (SELECT COUNT(*) FROM ticket WHERE deleted = 0) AS avg_flow "
     "FROM ticket_flow WHERE deleted = 0", False, ["agg"]),
    ("ticket-r-016", "MEDIUM", "有 SLA 计时记录的工单有多少张？",
     "SELECT COUNT(DISTINCT ticket_id) AS cnt FROM sla_timer WHERE deleted = 0", False, ["distinct"]),
    ("ticket-r-017", "MEDIUM", "知识文章的总浏览量是多少？",
     "SELECT SUM(view_count) AS total_view FROM knowledge_article WHERE deleted = 0", False, ["agg"]),
    ("ticket-r-018", "MEDIUM", "有帮助数最高的知识文章标题是什么？",
     "SELECT title FROM knowledge_article WHERE deleted = 0 ORDER BY helpful_count DESC, id LIMIT 1",
     True, ["order", "limit"]),
    ("ticket-r-019", "MEDIUM", "每个工单分类下已关闭工单的数量",
     "SELECT c.name, COUNT(*) AS cnt FROM ticket t JOIN ticket_category c ON t.category_id = c.id "
     "WHERE t.deleted = 0 AND t.status = 'CLOSED' GROUP BY c.name", False, ["join", "group"]),
    ("ticket-r-020", "MEDIUM", "逾期分钟数超过 600 的 SLA 违规有多少条？",
     "SELECT COUNT(*) AS cnt FROM sla_violation WHERE deleted = 0 AND overdue_minutes > 600",
     False, ["filter"]),
    ("ticket-r-021", "MEDIUM", "附件总大小是多少字节？",
     "SELECT SUM(file_size) AS total_size FROM knowledge_attachment WHERE deleted = 0", False, ["agg"]),
    ("ticket-r-022", "MEDIUM", "按工单状态统计通知数量（通过工单关联）",
     "SELECT t.status, COUNT(*) AS cnt FROM notification n JOIN ticket t ON n.biz_id = t.id "
     "WHERE n.deleted = 0 AND t.deleted = 0 AND n.type = 'ticket' GROUP BY t.status", False, ["join", "group"]),
    ("ticket-r-023", "MEDIUM", "平均每张工单产生多少条评论？",
     "SELECT COUNT(*) / (SELECT COUNT(*) FROM ticket WHERE deleted = 0) AS avg_comment "
     "FROM ticket_comment WHERE deleted = 0", False, ["agg"]),
    # HARD
    ("ticket-r-024", "HARD", "已关闭的工单从创建到关闭平均用了多少小时？",
     "SELECT AVG(TIMESTAMPDIFF(HOUR, created_at, closed_at)) AS avg_hours FROM ticket "
     "WHERE deleted = 0 AND status = 'CLOSED' AND closed_at IS NOT NULL", False, ["business"]),
    ("ticket-r-025", "HARD", "AI 推荐分类与工单实际分类一致的工单占全部工单的比例是多少？用 0 到 1 之间的小数表示",
     "SELECT SUM(CASE WHEN ai_category_id = category_id THEN 1 ELSE 0 END) / COUNT(*) AS ratio "
     "FROM ticket WHERE deleted = 0", False, ["business"]),
    ("ticket-r-026", "HARD", "按工单 id，统计每张工单的流转次数和评论数，没有记录的显示 0",
     "SELECT t.id, COUNT(DISTINCT f.id) AS flow_cnt, COUNT(DISTINCT c.id) AS comment_cnt "
     "FROM ticket t LEFT JOIN ticket_flow f ON f.ticket_id = t.id AND f.deleted = 0 "
     "LEFT JOIN ticket_comment c ON c.ticket_id = t.id AND c.deleted = 0 "
     "WHERE t.deleted = 0 GROUP BY t.id ORDER BY t.id LIMIT 50", True, ["join", "multi-agg"]),
    ("ticket-r-027", "HARD", "列出每条 SLA 违规记录对应的工单编号和违规类型",
     "SELECT t.ticket_no, v.violation_type FROM sla_violation v JOIN ticket t ON v.ticket_id = t.id "
     "WHERE v.deleted = 0 AND t.deleted = 0 ORDER BY v.id LIMIT 50", True, ["join"]),
    ("ticket-r-028", "HARD", "解决违规和首次响应违规各有多少条？",
     "SELECT violation_type, COUNT(*) AS cnt FROM sla_violation WHERE deleted = 0 GROUP BY violation_type",
     False, ["group"]),
    ("ticket-r-029", "HARD", "没有 SLA 计时记录的工单有多少张？",
     "SELECT COUNT(*) AS cnt FROM ticket t WHERE t.deleted = 0 "
     "AND NOT EXISTS (SELECT 1 FROM sla_timer st WHERE st.ticket_id = t.id AND st.deleted = 0)",
     False, ["subquery"]),
    ("ticket-r-030", "HARD", "知识文章里，有附件的文章占比是多少？用 0 到 1 之间的小数表示",
     "SELECT COUNT(DISTINCT a.id) / (SELECT COUNT(*) FROM knowledge_article a2 WHERE a2.deleted = 0) AS ratio "
     "FROM knowledge_article a JOIN knowledge_attachment att ON att.article_id = a.id "
     "WHERE a.deleted = 0 AND att.deleted = 0", False, ["business"]),
]


# ══════════ 第二批用例 ══════════



INSURANCE_2 = [
    ("insurance-031", "SIMPLE", "保额最高的那类险种是什么？",
     "SELECT product_type FROM ins_policy ORDER BY coverage_amount DESC LIMIT 1", True, ["order", "limit"]),
    ("insurance-032", "SIMPLE", "一共涉及多少个省份的保单？",
     "SELECT COUNT(DISTINCT province) AS cnt FROM ins_policy", False, ["distinct"]),
    ("insurance-033", "SIMPLE", "理赔单号一共有多少个？",
     "SELECT COUNT(DISTINCT claim_no) AS cnt FROM ins_claim", False, ["distinct"]),
    ("insurance-034", "SIMPLE", "平均每张保单的保费是多少？",
     "SELECT AVG(premium) AS avg_premium FROM ins_policy", False, ["agg"]),
    ("insurance-035", "SIMPLE", "最大的一笔年保费是多少？",
     "SELECT MAX(premium) AS max_premium FROM ins_policy", False, ["agg"]),
    ("insurance-036", "SIMPLE", "最小的一笔年保费是多少？",
     "SELECT MIN(premium) AS min_premium FROM ins_policy", False, ["agg"]),
    ("insurance-037", "SIMPLE", "按职业统计客户数量",
     "SELECT occupation, COUNT(*) AS cnt FROM ins_customer GROUP BY occupation", False, ["group"]),
    ("insurance-038", "SIMPLE", "已赔付的理赔有多少件？",
     "SELECT COUNT(*) AS cnt FROM ins_claim WHERE status = '已赔付'", False, ["filter"]),
    ("insurance-039", "SIMPLE", "省份为广东的保单有多少张？",
     "SELECT COUNT(*) AS cnt FROM ins_policy WHERE province = '广东'", False, ["filter"]),
    ("insurance-040", "SIMPLE", "车险保单有多少张？",
     "SELECT COUNT(*) AS cnt FROM ins_policy WHERE product_type = '车险'", False, ["filter"]),
    ("insurance-041", "MEDIUM", "金卡客户的保单数量是多少？",
     "SELECT COUNT(*) AS cnt FROM ins_policy p JOIN ins_customer c ON p.cust_id = c.id "
     "WHERE c.cust_level = '金卡'", False, ["join", "filter"]),
    ("insurance-042", "MEDIUM", "各销售渠道的平均保费是多少？",
     "SELECT channel, AVG(premium) AS avg_premium FROM ins_policy GROUP BY channel", False, ["group", "agg"]),
    ("insurance-043", "MEDIUM", "按险种类型统计赔付金额合计",
     "SELECT product_type, SUM(settle_amount) AS total_settle FROM ins_claim GROUP BY product_type",
     False, ["group", "agg"]),
    ("insurance-044", "MEDIUM", "华北和西北的保费合计分别是多少？",
     "SELECT region, SUM(premium) AS total_premium FROM ins_policy "
     "WHERE region IN ('华北', '西北') GROUP BY region", False, ["filter", "group", "agg"]),
    ("insurance-045", "MEDIUM", "按客户性别统计保单数量",
     "SELECT c.gender, COUNT(*) AS cnt FROM ins_policy p JOIN ins_customer c ON p.cust_id = c.id "
     "GROUP BY c.gender", False, ["join", "group"]),
    ("insurance-046", "MEDIUM", "理赔状态为已赔付的理赔，平均赔付金额是多少？",
     "SELECT AVG(settle_amount) AS avg_settle FROM ins_claim WHERE status = '已赔付'", False, ["filter", "agg"]),
    ("insurance-047", "MEDIUM", "按大区统计客户数量",
     "SELECT region, COUNT(*) AS cnt FROM ins_customer GROUP BY region", False, ["group"]),
    ("insurance-048", "MEDIUM", "50 岁以上的客户有多少人？",
     "SELECT COUNT(*) AS cnt FROM ins_customer WHERE age > 50", False, ["filter"]),
    ("insurance-049", "MEDIUM", "线上直销渠道的保费合计是多少？",
     "SELECT SUM(premium) AS total_premium FROM ins_policy WHERE channel = '线上直销'", False, ["filter", "agg"]),
    ("insurance-050", "MEDIUM", "按保障年限统计保单数量",
     "SELECT term_years, COUNT(*) AS cnt FROM ins_policy GROUP BY term_years", False, ["group"]),
    ("insurance-051", "HARD", "2024 年投保的保单保费合计是多少？",
     "SELECT SUM(premium) AS total_premium FROM ins_policy "
     "WHERE sign_date >= '2024-01-01' AND sign_date < '2025-01-01'", False, ["time", "agg"]),
    ("insurance-052", "HARD", "2025 年各险种类型的理赔件数分别是多少？",
     "SELECT product_type, COUNT(*) AS cnt FROM ins_claim "
     "WHERE report_date >= '2025-01-01' AND report_date < '2026-01-01' GROUP BY product_type",
     False, ["time", "group"]),
    ("insurance-053", "HARD", "按生效年份统计保单数量",
     "SELECT YEAR(effect_date) AS yr, COUNT(*) AS cnt FROM ins_policy GROUP BY YEAR(effect_date) ORDER BY yr",
     False, ["time", "group"]),
    ("insurance-054", "HARD", "2025 年赔付支出总额是多少？",
     "SELECT SUM(settle_amount) AS total_settle FROM ins_claim "
     "WHERE report_date >= '2025-01-01' AND report_date < '2026-01-01'", False, ["time", "agg"]),
    ("insurance-055", "HARD", "2024 年各月的理赔报案件数分别是多少？",
     "SELECT DATE_FORMAT(report_date, '%Y-%m') AS ym, COUNT(*) AS cnt FROM ins_claim "
     "WHERE report_date >= '2024-01-01' AND report_date < '2025-01-01' "
     "GROUP BY DATE_FORMAT(report_date, '%Y-%m') ORDER BY ym", False, ["time", "group"]),
    ("insurance-056", "HARD", "已赔付的理赔中，赔付金额占申请金额的比例是多少？",
     "SELECT SUM(settle_amount) / SUM(claim_amount) AS ratio FROM ins_claim WHERE status = '已赔付'",
     False, ["business"]),
    ("insurance-057", "HARD", "2025 年新投保客户的平均年龄是多少？",
     "SELECT AVG(age) AS avg_age FROM ins_customer "
     "WHERE reg_date >= '2025-01-01' AND reg_date < '2026-01-01'", False, ["time", "agg"]),
    ("insurance-058", "HARD", "各险种的出单省份里，广东省的保费合计是多少？",
     "SELECT SUM(premium) AS total_premium FROM ins_policy WHERE province = '广东'", False, ["filter", "agg"]),
    ("insurance-059", "HARD", "按大区统计退保保单占比",
     "SELECT region, SUM(CASE WHEN status = '退保' THEN 1 ELSE 0 END) / COUNT(*) AS ratio "
     "FROM ins_policy GROUP BY region", False, ["business"]),
    ("insurance-061", "HARD", "赔付金额占申请金额的比例是多少？用 0 到 1 之间的小数表示",
     "SELECT SUM(settle_amount) / NULLIF(SUM(claim_amount), 0) AS ratio FROM ins_claim",
     False, ["derived", "ratio"]),
    ("insurance-062", "HARD", "赔付金额比申请金额少多少？",
     "SELECT SUM(settle_amount) - SUM(claim_amount) AS diff FROM ins_claim",
     False, ["derived", "diff"]),
    ("insurance-060", "HARD", "2025 年生效的保单中，健康险的保费合计是多少？",
     "SELECT SUM(premium) AS total_premium FROM ins_policy "
     "WHERE effect_date >= '2025-01-01' AND effect_date < '2026-01-01' AND product_type = '健康险'",
     False, ["time", "filter", "agg"]),
    ("insurance-063", "MEDIUM", "保费最高的 3 个区域分别是哪些？",
     "SELECT region, SUM(premium) AS total_premium FROM ins_policy "
     "GROUP BY region ORDER BY total_premium DESC LIMIT 3",
     False, ["sort", "topn"]),
    ("insurance-064", "MEDIUM", "各区域的保费分别占全部保费的比例是多少？用 0 到 1 之间的小数表示",
     "SELECT region, SUM(premium) AS total_premium, "
     "SUM(premium) / (SELECT SUM(premium) FROM ins_policy) AS ratio "
     "FROM ins_policy GROUP BY region",
     False, ["share"]),
    ("insurance-065", "HARD", "2025 年全年保费比 2024 年全年增长了多少？用 0 到 1 之间的小数表示",
     "SELECT cur, prev, (cur - prev) / prev AS growth FROM ("
     "SELECT (SELECT SUM(premium) FROM ins_policy "
     "        WHERE effect_date >= '2025-01-01' AND effect_date < '2026-01-01') AS cur, "
     "       (SELECT SUM(premium) FROM ins_policy "
     "        WHERE effect_date >= '2024-01-01' AND effect_date < '2025-01-01') AS prev) t",
     False, ["compare", "yoy"]),
]

TICKET_MODELED_2 = [
    ("ticket-m-031", "SIMPLE", "评论一共有多少条？",
     "SELECT COUNT(*) AS cnt FROM ticket_comment WHERE deleted = 0", False, ["count"]),
    ("ticket-m-032", "SIMPLE", "流转记录一共有多少条？",
     "SELECT COUNT(*) AS cnt FROM ticket_flow WHERE deleted = 0", False, ["count"]),
    ("ticket-m-033", "SIMPLE", "状态为处理中的工单有多少张？",
     "SELECT COUNT(*) AS cnt FROM ticket WHERE deleted = 0 AND status = 'IN_PROGRESS'", False, ["filter"]),
    ("ticket-m-034", "SIMPLE", "来自 APP 的工单有多少张？",
     "SELECT COUNT(*) AS cnt FROM ticket WHERE deleted = 0 AND source = 'APP'", False, ["filter"]),
    ("ticket-m-035", "SIMPLE", "分类名称叫「订单与支付」的工单有多少张？",
     "SELECT COUNT(*) AS cnt FROM ticket t JOIN ticket_category c ON t.category_id = c.id "
     "WHERE t.deleted = 0 AND c.name = '订单与支付'", False, ["join", "filter"]),
    ("ticket-m-036", "SIMPLE", "平均优先级是多少？",
     "SELECT AVG(priority) AS avg_priority FROM ticket WHERE deleted = 0", False, ["agg"]),
    ("ticket-m-037", "SIMPLE", "最高优先级（数字最小）的工单有多少张？",
     "SELECT COUNT(*) AS cnt FROM ticket WHERE deleted = 0 AND priority = 1", False, ["filter"]),
    ("ticket-m-038", "SIMPLE", "处理过工单的不同处理人有多少个？",
     "SELECT COUNT(DISTINCT assignee_id) AS cnt FROM ticket WHERE deleted = 0 AND assignee_id IS NOT NULL",
     False, ["distinct"]),
    ("ticket-m-039", "SIMPLE", "按团队统计工单数量",
     "SELECT team_id, COUNT(*) AS cnt FROM ticket WHERE deleted = 0 GROUP BY team_id", False, ["group"]),
    ("ticket-m-040", "SIMPLE", "按是否内部备注统计评论数量",
     "SELECT is_internal, COUNT(*) AS cnt FROM ticket_comment WHERE deleted = 0 GROUP BY is_internal",
     False, ["group"]),
    ("ticket-m-041", "MEDIUM", "2025 年 1 月创建的工单按状态统计数量",
     "SELECT status, COUNT(*) AS cnt FROM ticket WHERE deleted = 0 "
     "AND created_at >= '2025-01-01' AND created_at < '2025-02-01' GROUP BY status", False, ["time", "group"]),
    ("ticket-m-042", "MEDIUM", "每个分类的平均 AI 置信度是多少？",
     "SELECT c.name, AVG(t.ai_confidence) AS avg_conf FROM ticket t "
     "JOIN ticket_category c ON t.category_id = c.id WHERE t.deleted = 0 GROUP BY c.name",
     False, ["join", "agg"]),
    ("ticket-m-043", "MEDIUM", "评论数超过 2 条的工单有多少张？",
     "SELECT COUNT(*) AS cnt FROM (SELECT ticket_id FROM ticket_comment WHERE deleted = 0 "
     "GROUP BY ticket_id HAVING COUNT(*) > 2) x", False, ["subquery"]),
    ("ticket-m-044", "MEDIUM", "状态为已关闭的工单，平均有多少条评论？",
     "SELECT COUNT(*) / COUNT(DISTINCT t.id) AS avg_comment FROM ticket t "
     "JOIN ticket_comment cm ON cm.ticket_id = t.id AND cm.deleted = 0 "
     "WHERE t.deleted = 0 AND t.status = 'CLOSED'", False, ["join", "business"]),
    ("ticket-m-045", "MEDIUM", "按来源统计内部备注数量",
     "SELECT t.source, COUNT(*) AS cnt FROM ticket t "
     "JOIN ticket_comment cm ON cm.ticket_id = t.id AND cm.deleted = 0 "
     "WHERE t.deleted = 0 AND cm.is_internal = 1 GROUP BY t.source", False, ["join", "group"]),
    ("ticket-m-046", "MEDIUM", "2025 年 11 月创建的工单有多少张？",
     "SELECT COUNT(*) AS cnt FROM ticket WHERE deleted = 0 "
     "AND created_at >= '2025-11-01' AND created_at < '2025-12-01'", False, ["time"]),
    ("ticket-m-047", "MEDIUM", "处理团队 1 和团队 2 各有多少张工单？",
     "SELECT team_id, COUNT(*) AS cnt FROM ticket WHERE deleted = 0 AND team_id IN (1, 2) GROUP BY team_id",
     False, ["filter", "group"]),
    ("ticket-m-048", "MEDIUM", "每个分类下已解决（含已关闭）的工单有多少张？",
     "SELECT c.name, COUNT(*) AS cnt FROM ticket t JOIN ticket_category c ON t.category_id = c.id "
     "WHERE t.deleted = 0 AND t.status IN ('RESOLVED', 'CLOSED') GROUP BY c.name",
     False, ["join", "filter", "group"]),
    ("ticket-m-049", "MEDIUM", "流转记录里「OPEN 变到 IN_PROGRESS」发生了多少次？",
     "SELECT COUNT(*) AS cnt FROM ticket_flow WHERE deleted = 0 "
     "AND from_status = 'OPEN' AND to_status = 'IN_PROGRESS'", False, ["filter"]),
    ("ticket-m-050", "MEDIUM", "按流转前后状态组合统计流转次数",
     "SELECT from_status, to_status, COUNT(*) AS cnt FROM ticket_flow WHERE deleted = 0 "
     "GROUP BY from_status, to_status", False, ["group"]),
    ("ticket-m-051", "HARD", "各优先级的工单平均解决时长（小时）是多少？只算已解决的",
     "SELECT priority, AVG(TIMESTAMPDIFF(HOUR, created_at, resolved_at)) AS avg_hours "
     "FROM ticket WHERE deleted = 0 AND resolved_at IS NOT NULL GROUP BY priority",
     False, ["business"]),
    ("ticket-m-052", "HARD", "2025 年为已关闭状态的工单占 2025 年全部工单的比例是多少？",
     "SELECT SUM(CASE WHEN status = 'CLOSED' THEN 1 ELSE 0 END) / COUNT(*) AS ratio FROM ticket "
     "WHERE deleted = 0 AND created_at >= '2025-01-01' AND created_at < '2026-01-01'",
     False, ["business", "time"]),
    ("ticket-m-053", "HARD", "有评论的工单占比是多少？",
     "SELECT COUNT(DISTINCT t.id) / (SELECT COUNT(*) FROM ticket WHERE deleted = 0) AS ratio "
     "FROM ticket t JOIN ticket_comment cm ON cm.ticket_id = t.id AND cm.deleted = 0 "
     "WHERE t.deleted = 0", False, ["business"]),
    ("ticket-m-054", "HARD", "各分类的工单中，内部备注占全部评论的比例分别是多少？",
     "SELECT c.name, SUM(CASE WHEN cm.is_internal = 1 THEN 1 ELSE 0 END) / COUNT(*) AS ratio "
     "FROM ticket t JOIN ticket_category c ON t.category_id = c.id "
     "JOIN ticket_comment cm ON cm.ticket_id = t.id AND cm.deleted = 0 "
     "WHERE t.deleted = 0 GROUP BY c.name", False, ["join", "business"]),
    ("ticket-m-055", "HARD", "2025 年创建的工单中，AI 推荐分类准确（AI 分类与实际分类一致）的比例是多少？",
     "SELECT SUM(CASE WHEN ai_category_id = category_id THEN 1 ELSE 0 END) / COUNT(*) AS ratio "
     "FROM ticket WHERE deleted = 0 AND created_at >= '2025-01-01' AND created_at < '2026-01-01'",
     False, ["business", "time"]),
    ("ticket-m-056", "HARD", "按优先级统计平均流转次数",
     "SELECT t.priority, COUNT(f.id) / COUNT(DISTINCT t.id) AS avg_flow FROM ticket t "
     "JOIN ticket_flow f ON f.ticket_id = t.id AND f.deleted = 0 "
     "WHERE t.deleted = 0 GROUP BY t.priority", False, ["join", "business"]),
    ("ticket-m-057", "HARD", "已关闭工单中，关闭时间早于 SLA 截止时间的比例是多少？",
     "SELECT SUM(CASE WHEN closed_at <= sla_deadline THEN 1 ELSE 0 END) / COUNT(*) AS ratio "
     "FROM ticket WHERE deleted = 0 AND status = 'CLOSED' AND closed_at IS NOT NULL",
     False, ["business"]),
    ("ticket-m-058", "HARD", "2025 年 4 月创建的工单里，已关闭的有多少张？",
     "SELECT COUNT(*) AS cnt FROM ticket WHERE deleted = 0 AND status = 'CLOSED' "
     "AND created_at >= '2025-04-01' AND created_at < '2025-05-01'", False, ["time", "filter"]),
    ("ticket-m-059", "HARD", "各分类下工单的平均优先级是多少？",
     "SELECT c.name, AVG(t.priority) AS avg_priority FROM ticket t "
     "JOIN ticket_category c ON t.category_id = c.id WHERE t.deleted = 0 GROUP BY c.name",
     False, ["join", "agg"]),
    ("ticket-m-061", "HARD", "有解决时间的工单，平均解决时长是多少小时？",
     "SELECT AVG(TIMESTAMPDIFF(HOUR, created_at, resolved_at)) AS avg_hours FROM ticket "
     "WHERE deleted = 0 AND resolved_at IS NOT NULL",
     False, ["derived", "duration"]),
    ("ticket-m-060", "HARD", "处理人 7 的工单里，平均解决时长（小时）是多少？",
     "SELECT AVG(TIMESTAMPDIFF(HOUR, created_at, resolved_at)) AS avg_hours FROM ticket "
     "WHERE deleted = 0 AND assignee_id = 7 AND resolved_at IS NOT NULL", False, ["business"]),
    ("ticket-m-062", "MEDIUM", "工单数最多的 5 个分类分别是哪几个？",
     "SELECT c.name, COUNT(*) AS cnt FROM ticket t "
     "JOIN ticket_category c ON t.category_id = c.id "
     "WHERE t.deleted = 0 GROUP BY c.name ORDER BY cnt DESC LIMIT 5",
     False, ["sort", "topn"]),
    ("ticket-m-063", "MEDIUM", "各分类的工单数占全部工单数的比例分别是多少？用 0 到 1 之间的小数表示",
     "SELECT c.name, COUNT(*) AS cnt, "
     "COUNT(*) / (SELECT COUNT(*) FROM ticket WHERE deleted = 0) AS ratio "
     "FROM ticket t JOIN ticket_category c ON t.category_id = c.id "
     "WHERE t.deleted = 0 GROUP BY c.name",
     False, ["share"]),
]

TICKET_RAW_2 = [
    ("ticket-r-031", "SIMPLE", "工单评论一共有多少条？",
     "SELECT COUNT(*) AS cnt FROM ticket_comment WHERE deleted = 0", False, ["count"]),
    ("ticket-r-032", "SIMPLE", "按工单来源统计数量",
     "SELECT source, COUNT(*) AS cnt FROM ticket WHERE deleted = 0 GROUP BY source", False, ["group"]),
    ("ticket-r-033", "SIMPLE", "知识分类一共有几个？",
     "SELECT COUNT(*) AS cnt FROM knowledge_category WHERE deleted = 0", False, ["count"]),
    ("ticket-r-034", "SIMPLE", "通知总条数是多少？",
     "SELECT COUNT(*) AS cnt FROM notification WHERE deleted = 0", False, ["count"]),
    ("ticket-r-035", "SIMPLE", "工单流转记录一共有多少条？",
     "SELECT COUNT(*) AS cnt FROM ticket_flow WHERE deleted = 0", False, ["count"]),
    ("ticket-r-036", "SIMPLE", "SLA 计时记录有多少条？",
     "SELECT COUNT(*) AS cnt FROM sla_timer WHERE deleted = 0", False, ["count"]),
    ("ticket-r-037", "SIMPLE", "按动作类型统计分派规则数量",
     "SELECT action_type, COUNT(*) AS cnt FROM dispatch_rule WHERE deleted = 0 GROUP BY action_type",
     False, ["group"]),
    ("ticket-r-038", "SIMPLE", "已读通知有多少条？",
     "SELECT COUNT(*) AS cnt FROM notification WHERE deleted = 0 AND read_status = 1", False, ["filter"]),
    ("ticket-r-039", "SIMPLE", "按状态统计知识文章数量",
     "SELECT status, COUNT(*) AS cnt FROM knowledge_article WHERE deleted = 0 GROUP BY status",
     False, ["group"]),
    ("ticket-r-040", "SIMPLE", "评论里内部备注有多少条？",
     "SELECT COUNT(*) AS cnt FROM ticket_comment WHERE deleted = 0 AND is_internal = 1", False, ["filter"]),
    ("ticket-r-041", "MEDIUM", "浏览量最低的已发布知识文章标题是什么？",
     "SELECT title FROM knowledge_article WHERE deleted = 0 AND status = 1 "
     "ORDER BY view_count ASC, id LIMIT 1", True, ["order", "limit"]),
    ("ticket-r-042", "MEDIUM", "文件大小最大的附件是哪个文件？",
     "SELECT file_name FROM knowledge_attachment WHERE deleted = 0 "
     "ORDER BY file_size DESC, id LIMIT 1", True, ["order", "limit"]),
    ("ticket-r-043", "MEDIUM", "每个分类下知识文章的平均浏览量是多少？",
     "SELECT category_id, AVG(view_count) AS avg_view FROM knowledge_article "
     "WHERE deleted = 0 GROUP BY category_id", False, ["group", "agg"]),
    ("ticket-r-044", "MEDIUM", "按读取状态统计通知数量",
     "SELECT read_status, COUNT(*) AS cnt FROM notification WHERE deleted = 0 GROUP BY read_status",
     False, ["group"]),
    ("ticket-r-045", "MEDIUM", "SLA 策略里解决时限最长的是多少分钟？",
     "SELECT MAX(resolve_time) AS max_resolve FROM sla_policy WHERE deleted = 0", False, ["agg"]),
    ("ticket-r-046", "MEDIUM", "有附件的知识文章有多少篇？",
     "SELECT COUNT(DISTINCT article_id) AS cnt FROM knowledge_attachment WHERE deleted = 0",
     False, ["distinct"]),
    ("ticket-r-047", "MEDIUM", "SLA 违规里已升级的有多少条？",
     "SELECT COUNT(*) AS cnt FROM sla_violation WHERE deleted = 0 AND status = 1", False, ["filter"]),
    ("ticket-r-048", "MEDIUM", "按工单优先级统计流转记录数量",
     "SELECT t.priority, COUNT(*) AS cnt FROM ticket_flow f JOIN ticket t ON f.ticket_id = t.id "
     "WHERE f.deleted = 0 AND t.deleted = 0 GROUP BY t.priority", False, ["join", "group"]),
    ("ticket-r-049", "MEDIUM", "知识文章标题里包含「支付」的有多少篇？",
     "SELECT COUNT(*) AS cnt FROM knowledge_article WHERE deleted = 0 AND title LIKE '%支付%'",
     False, ["like"]),
    ("ticket-r-050", "MEDIUM", "平均每张工单对应多少条 SLA 违规记录？",
     "SELECT COUNT(*) / (SELECT COUNT(*) FROM ticket WHERE deleted = 0) AS avg_violation "
     "FROM sla_violation WHERE deleted = 0", False, ["agg"]),
    ("ticket-r-051", "HARD", "各知识分类下已发布文章的平均浏览量和平均点赞数分别是多少？",
     "SELECT category_id, AVG(view_count) AS avg_view, AVG(helpful_count) AS avg_helpful "
     "FROM knowledge_article WHERE deleted = 0 AND status = 1 GROUP BY category_id",
     False, ["group", "agg"]),
    ("ticket-r-052", "HARD", "2025 年创建的工单按来源统计已关闭的数量",
     "SELECT source, COUNT(*) AS cnt FROM ticket WHERE deleted = 0 AND status = 'CLOSED' "
     "AND created_at >= '2025-01-01' AND created_at < '2026-01-01' GROUP BY source",
     False, ["time", "group", "filter"]),
    ("ticket-r-053", "HARD", "首次响应违规和解决违规的平均逾期分钟数分别是多少？",
     "SELECT violation_type, AVG(overdue_minutes) AS avg_overdue FROM sla_violation "
     "WHERE deleted = 0 GROUP BY violation_type", False, ["group", "agg"]),
    ("ticket-r-054", "HARD", "既有流转记录又有评论的工单有多少张？",
     "SELECT COUNT(*) AS cnt FROM ticket t WHERE t.deleted = 0 "
     "AND EXISTS (SELECT 1 FROM ticket_flow f WHERE f.ticket_id = t.id AND f.deleted = 0) "
     "AND EXISTS (SELECT 1 FROM ticket_comment c WHERE c.ticket_id = t.id AND c.deleted = 0)",
     False, ["subquery"]),
    ("ticket-r-055", "HARD", "评论数最多的 5 张工单，各自有多少条评论？",
     "SELECT ticket_id, COUNT(*) AS cnt FROM ticket_comment WHERE deleted = 0 "
     "GROUP BY ticket_id ORDER BY cnt DESC, ticket_id LIMIT 5", True, ["group", "order", "limit"]),
    ("ticket-r-056", "HARD", "按知识分类统计文章数量和总浏览量",
     "SELECT category_id, COUNT(*) AS cnt, SUM(view_count) AS total_view FROM knowledge_article "
     "WHERE deleted = 0 GROUP BY category_id", False, ["group", "multi-agg"]),
    ("ticket-r-057", "HARD", "SLA 违规中逾期 1000 分钟以上的占比是多少？",
     "SELECT SUM(CASE WHEN overdue_minutes > 1000 THEN 1 ELSE 0 END) / COUNT(*) AS ratio "
     "FROM sla_violation WHERE deleted = 0", False, ["business"]),
    ("ticket-r-058", "HARD", "各处理团队的平均解决时长（小时）是多少？",
     "SELECT team_id, AVG(TIMESTAMPDIFF(HOUR, created_at, resolved_at)) AS avg_hours FROM ticket "
     "WHERE deleted = 0 AND resolved_at IS NOT NULL GROUP BY team_id", False, ["business"]),
    ("ticket-r-059", "HARD", "知识文章里草稿状态的有多少篇？",
     "SELECT COUNT(*) AS cnt FROM knowledge_article WHERE deleted = 0 AND status = 0", False, ["filter"]),
    ("ticket-r-060", "HARD", "每个工单分类下的 SLA 违规数量（通过工单关联）",
     "SELECT c.name, COUNT(*) AS cnt FROM sla_violation v "
     "JOIN ticket t ON v.ticket_id = t.id AND t.deleted = 0 "
     "JOIN ticket_category c ON t.category_id = c.id "
     "WHERE v.deleted = 0 GROUP BY c.name", False, ["join", "group"]),
]

# ══════════ 构建 ══════════

BASE = Path(__file__).resolve().parent
CONN = dict(host="127.0.0.1", user="root", password="root", database="chatdata_eval")

NONDETERMINISTIC = re.compile(r"\b(CURDATE|NOW|CURRENT_DATE|CURRENT_TIMESTAMP|RAND|UUID)\s*\(", re.I)
REQUIRED_MARK = "<!-- 占位 -->"

# 每个域允许引用的表（建模域必须全部已注册）
ALLOWED_TABLES = {
    "2001": {"ins_policy", "ins_customer", "ins_claim"},
    "2002": {"ticket", "ticket_category", "ticket_comment", "ticket_flow"},
    "2003": {"ticket", "ticket_category", "ticket_comment", "ticket_flow",
             "knowledge_article", "knowledge_attachment", "knowledge_category",
             "notification", "sla_policy", "sla_timer", "sla_violation", "dispatch_rule"},
}


def tables_in(sql: str) -> set[str]:
    return {t.lower() for t in re.findall(r"\b(?:FROM|JOIN)\s+`?(\w+)`?", sql, re.I)}


def validate(domain: str, case_id: str, sql: str, cur) -> tuple[str, int]:
    problems = []
    if NONDETERMINISTIC.search(sql):
        problems.append("gold SQL 含不确定函数")
    missing = tables_in(sql) - ALLOWED_TABLES[domain]
    if missing:
        problems.append(f"引用了域外或未注册的表：{sorted(missing)}")
    rows = None
    try:
        cur.execute(sql)
        rows = cur.fetchall()
    except Exception as e:  # noqa: BLE001
        problems.append(f"执行失败：{e}")
    if rows is not None and len(rows) == 0:
        problems.append("结果为空（对准确率无信号）")
    return "; ".join(problems), (len(rows) if rows is not None else -1)


def build(name: str, domain_id: str, defs, cur, primary_metric: str = "strict"):
    out_cases, errors = [], []
    for cid, diff, question, sql, ordered, tags in defs:
        problem, n = validate(domain_id, cid, sql, cur)
        if problem:
            errors.append(f"{cid}: {problem}")
            continue
        out_cases.append({
            "id": cid, "difficulty": diff, "question": question,
            "goldSql": sql.strip(), "ordered": ordered, "columnOrder": "ANY",
            "tags": tags, "note": f"夹具数据；gold 结果 {n} 行。",
        })
    return {"name": name, "domainId": domain_id, "userId": "eval",
            "roles": ["super_admin"], "assistantId": None,
            "primaryMetric": primary_metric, "cases": out_cases}, errors


def main() -> int:
    conn = pymysql.connect(**CONN)
    cur = conn.cursor()
    all_errors = []

    ins, e1 = build("保险分析域基线（评测夹具）", "2001", INSURANCE + INSURANCE_2, cur)
    tm, e2 = build("工单分析域基线（建模·评测夹具）", "2002",
                   TICKET_MODELED + TICKET_MODELED_2, cur)
    # 直连域用**包含式**作主指标：模型自己写 SQL，没有语义层收敛字段范围，
    # 常顺手多带 id/名称之类的上下文列。那是设计使然，不是答错。
    # 用严格口径衡量会把这类情况记成失败，让数字失去指导意义。
    tr, e3 = build("工单直连域基线（直连·评测夹具）", "2003",
                   TICKET_RAW + TICKET_RAW_2, cur, primary_metric="contains")
    all_errors += e1 + e2 + e3

    if all_errors:
        print("以下用例未通过校验，已剔除：")
        for e in all_errors:
            print("  -", e)

    for fname, data in [
        ("insurance-fixture.json", ins),
        ("ticket-modeled-fixture.json", tm),
        ("ticket-raw-fixture.json", tr),
    ]:
        (BASE / fname).write_text(
            json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        dist = Counter(c["difficulty"] for c in data["cases"])
        print(f"{fname}: {len(data['cases'])} 条 {dict(dist)}")

    print("总计：", len(ins["cases"]) + len(tm["cases"]) + len(tr["cases"]), "条")
    conn.close()
    return 1 if all_errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
