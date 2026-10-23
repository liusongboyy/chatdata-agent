#!/usr/bin/env python3
"""
生成评测夹具的工单数据（确定性）。

输出 SQL 到 stdout，由 prepare.sh 灌进 chatdata_eval。
固定随机种子，任何机器上跑出来的数据完全一致——评测基线必须可复现，
否则"这次比上次准了"没有任何意义。

不依赖任何第三方库，只用标准库。
"""
from __future__ import annotations

import datetime as dt
import random

RNG = random.Random(20260101)
TENANT = 1
TICKETS = 2000

CATEGORIES = [
    (1, "账号与登录", ["无法登录", "密码重置失败", "验证码收不到", "账号被锁定"]),
    (2, "订单与支付", ["支付失败", "订单状态不同步", "重复扣款", "退款未到账"]),
    (3, "产品使用", ["功能报错", "页面白屏", "导出失败", "数据不刷新"]),
    (4, "发票与合同", ["发票信息有误", "合同下载失败", "开票进度查询", "抬头变更"]),
    (5, "数据与报表", ["报表口径不一致", "数据延迟", "指标缺失", "权限看不到数据"]),
]

SLA_POLICIES = [
    # id, name, priority, category, first_response, resolve, warning
    (1, "P1 紧急", 1, None, 15, 240, 120),
    (2, "P2 高", 2, None, 30, 480, 240),
    (3, "P3 中", 3, None, 60, 1440, 720),
    (4, "P4 低", 4, None, 120, 2880, 1440),
    (5, "P5 咨询", 5, None, 240, 4320, 2160),
]

KNOWLEDGE_CATEGORIES = ["账号", "支付", "产品", "财务", "数据"]
KNOWLEDGE_TOPICS = [
    "如何重置密码", "支付失败排查", "发票开具流程", "报表口径说明",
    "数据权限申请", "工单 SLA 规则", "导出失败处理", "账号锁定解锁",
]

START = dt.datetime(2025, 1, 1, 0, 0, 0)
END = dt.datetime(2025, 12, 20, 23, 59, 59)


def rand_dt(start: dt.datetime = START, end: dt.datetime = END) -> dt.datetime:
    return start + dt.timedelta(seconds=RNG.randint(0, int((end - start).total_seconds())))


def fmt(value) -> str:
    if value is None:
        return "NULL"
    if isinstance(value, dt.datetime):
        return "'" + value.strftime("%Y-%m-%d %H:%M:%S") + "'"
    if isinstance(value, dt.date):
        return "'" + value.strftime("%Y-%m-%d") + "'"
    if isinstance(value, (int, float)):
        return str(value)
    return "'" + str(value).replace("\\", "\\\\").replace("'", "''") + "'"


def insert(table: str, columns: list[str], rows: list[tuple], chunk: int = 200) -> None:
    for i in range(0, len(rows), chunk):
        part = rows[i:i + chunk]
        values = ",\n".join(
            "(" + ", ".join(fmt(v) for v in row) + ")" for row in part
        )
        print(f"INSERT INTO `{table}` ({', '.join('`' + c + '`' for c in columns)}) VALUES\n{values};")


def weighted(options: list[tuple[object, int]]):
    total = sum(w for _, w in options)
    n = RNG.randint(1, total)
    upto = 0
    for value, weight in options:
        upto += weight
        if n <= upto:
            return value
    return options[-1][0]


def main() -> None:
    print("SET NAMES utf8mb4;")
    print("SET FOREIGN_KEY_CHECKS = 0;")
    print("TRUNCATE TABLE `dispatch_rule`;")
    print("TRUNCATE TABLE `notification`;")
    print("TRUNCATE TABLE `sla_violation`;")
    print("TRUNCATE TABLE `sla_timer`;")
    print("TRUNCATE TABLE `sla_policy`;")
    print("TRUNCATE TABLE `knowledge_attachment`;")
    print("TRUNCATE TABLE `knowledge_article`;")
    print("TRUNCATE TABLE `knowledge_category`;")
    print("TRUNCATE TABLE `ticket_flow`;")
    print("TRUNCATE TABLE `ticket_comment`;")
    print("TRUNCATE TABLE `ticket`;")
    print("TRUNCATE TABLE `ticket_category`;")

    # ── 分类与 SLA 策略 ─────────────────────────────────────────────
    cat_rows = []
    for cid, name, _topics in CATEGORIES:
        cat_rows.append((cid, TENANT, 0, name, cid, 1, cid, cid,
                         dt.datetime(2025, 1, 1), dt.datetime(2025, 1, 1), 0))
    insert("ticket_category",
           ["id", "tenant_id", "parent_id", "name", "sort_order", "enabled",
            "default_team_id", "default_sla_id", "created_at", "updated_at", "deleted"],
           cat_rows)

    sla_rows = [(pid, name, priority, category, first, resolve, warning, 1, TENANT,
                 dt.datetime(2025, 1, 1), dt.datetime(2025, 1, 1), 0)
                for pid, name, priority, category, first, resolve, warning in SLA_POLICIES]
    insert("sla_policy",
           ["id", "name", "priority", "category_id", "first_response_time", "resolve_time",
            "warning_time", "enabled", "tenant_id", "created_at", "updated_at", "deleted"],
           sla_rows)

    kcat_rows = [(i + 1, name, 0, i + 1, TENANT,
                  dt.datetime(2025, 1, 1), dt.datetime(2025, 1, 1), 0)
                 for i, name in enumerate(KNOWLEDGE_CATEGORIES)]
    insert("knowledge_category",
           ["id", "name", "parent_id", "sort_order", "tenant_id",
            "created_at", "updated_at", "deleted"], kcat_rows)

    # ── 工单 ────────────────────────────────────────────────────────
    ticket_rows = []
    flow_rows = []
    comment_rows = []
    timer_rows = []
    violation_rows = []
    tickets_meta = []

    flow_id = 1
    comment_id = 1
    timer_id = 1
    violation_id = 1
    status_flow = {
        "OPEN": ["OPEN"],
        "IN_PROGRESS": ["OPEN", "IN_PROGRESS"],
        "RESOLVED": ["OPEN", "IN_PROGRESS", "RESOLVED"],
        "CLOSED": ["OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED"],
    }

    for tid in range(1, TICKETS + 1):
        priority = weighted([(1, 8), (2, 22), (3, 38), (4, 22), (5, 10)])
        status = weighted([("OPEN", 12), ("IN_PROGRESS", 18), ("RESOLVED", 20), ("CLOSED", 50)])
        source = weighted([("WEB", 35), ("APP", 25), ("EMAIL", 25), ("API", 15)])
        cid, cname, topics = RNG.choice(CATEGORIES)
        created = rand_dt()
        policy = SLA_POLICIES[priority - 1]
        first_deadline = created + dt.timedelta(minutes=policy[4])
        resolve_deadline = created + dt.timedelta(minutes=policy[5])

        resolved = None
        closed = None
        if status in ("RESOLVED", "CLOSED"):
            minutes = int(policy[5] * RNG.uniform(0.25, 1.6))
            resolved = created + dt.timedelta(minutes=max(5, minutes))
            if status == "CLOSED":
                closed = resolved + dt.timedelta(minutes=RNG.randint(10, 2880))
        updated = closed or resolved or (created + dt.timedelta(minutes=RNG.randint(5, 2880)))
        squashed = RNG.random() < 0.03
        deleted = 1 if squashed else 0

        team_id = RNG.randint(1, 6)
        assignee = RNG.randint(1, 30) if status != "OPEN" else None
        ai_category = cid if RNG.random() < 0.72 else RNG.choice([c for c, _, _ in CATEGORIES])
        ticket_rows.append((
            tid, TENANT, f"T2025{tid:06d}", f"{RNG.choice(topics)}（{tid}）",
            f"用户反馈：{cname} 相关问题，期望尽快处理。", cid, priority, status, source,
            RNG.randint(1, 40), assignee, team_id, policy[0], resolve_deadline,
            resolved, closed, ai_category, assignee, round(RNG.uniform(0.55, 0.99), 4),
            created, updated, deleted,
        ))

        # 流转：按最终状态生成，20% 概率多一次回退
        states = status_flow[status]
        points = [created]
        if resolved:
            points.append(resolved)
        if closed:
            points.append(closed)
        while len(points) < len(states):
            points.insert(1, points[0] + dt.timedelta(minutes=RNG.randint(5, 480)))
        points = sorted(points)
        for i, to_status in enumerate(states):
            from_status = states[i - 1] if i > 0 else None
            at = points[min(i, len(points) - 1)]
            flow_rows.append((
                flow_id, TENANT, tid, from_status, to_status, RNG.randint(1, 30),
                f"状态变更为 {to_status}", at, at, deleted,
            ))
            flow_id += 1
        if status in ("RESOLVED", "CLOSED") and RNG.random() < 0.18:
            reopen_at = points[-1] + dt.timedelta(minutes=RNG.randint(5, 120))
            flow_rows.append((flow_id, TENANT, tid, "RESOLVED", "IN_PROGRESS",
                              RNG.randint(1, 30), "问题复现，重新打开", reopen_at, reopen_at, deleted))
            flow_id += 1

        # 评论
        n_comments = weighted([(0, 25), (1, 35), (2, 25), (3, 15)])
        for _ in range(n_comments):
            at = created + dt.timedelta(minutes=RNG.randint(1, 2880))
            if resolved and at > resolved:
                at = resolved
            internal = 1 if RNG.random() < 0.30 else 0
            comment_rows.append((
                comment_id, TENANT, tid, RNG.randint(1, 40),
                "内部备注：已联系研发排查" if internal else "用户补充：问题仍然存在",
                internal, at, at, deleted,
            ))
            comment_id += 1

        # SLA 计时与违规
        if resolved:
            first_responded = created + dt.timedelta(
                minutes=RNG.randint(1, int(policy[4] * RNG.uniform(0.3, 1.4))))
            if first_responded > resolved:
                first_responded = resolved
            violation = 2 if resolved > resolve_deadline else (
                1 if first_responded > first_deadline else 0)
            timer_rows.append((
                timer_id, TENANT, tid, policy[0], created, first_deadline, resolve_deadline,
                first_responded, resolved, violation, 1 if RNG.random() < 0.4 else 0,
                created, updated, deleted,
            ))
            if violation:
                vtype = "resolve" if violation == 2 else "first_response"
                deadline = resolve_deadline if violation == 2 else first_deadline
                actual = resolved if violation == 2 else first_responded
                overdue = max(1, int((actual - deadline).total_seconds() // 60))
                violation_rows.append((
                    violation_id, TENANT, tid, timer_id, vtype,
                    f"{'解决' if vtype == 'resolve' else '首次响应'}超时 {overdue} 分钟",
                    overdue, weighted([(0, 50), (1, 25), (2, 25)]),
                    actual, actual, deleted,
                ))
                violation_id += 1
            timer_id += 1

        tickets_meta.append((tid, created, status, deleted))

    insert("ticket",
           ["id", "tenant_id", "ticket_no", "title", "description", "category_id", "priority",
            "status", "source", "creator_id", "assignee_id", "team_id", "sla_policy_id",
            "sla_deadline", "resolved_at", "closed_at", "ai_category_id", "ai_assignee_id",
            "ai_confidence", "created_at", "updated_at", "deleted"], ticket_rows)
    insert("ticket_flow",
           ["id", "tenant_id", "ticket_id", "from_status", "to_status", "operator_id",
            "comment", "created_at", "updated_at", "deleted"], flow_rows)
    insert("ticket_comment",
           ["id", "tenant_id", "ticket_id", "user_id", "content", "is_internal",
            "created_at", "updated_at", "deleted"], comment_rows)
    insert("sla_timer",
           ["id", "tenant_id", "ticket_id", "policy_id", "ticket_created_at",
            "first_response_deadline", "resolve_deadline", "first_responded_at",
            "resolved_at", "violation_status", "warning_sent", "created_at", "updated_at",
            "deleted"], timer_rows)
    insert("sla_violation",
           ["id", "tenant_id", "ticket_id", "timer_id", "violation_type", "description",
            "overdue_minutes", "status", "created_at", "updated_at", "deleted"], violation_rows)

    # ── 知识库 ──────────────────────────────────────────────────────
    article_rows = []
    for aid in range(1, 301):
        topic = RNG.choice(KNOWLEDGE_TOPICS)
        published = RNG.random() < 0.8
        created = rand_dt()
        article_rows.append((
            aid, f"{topic}（{aid}）", f"# {topic}\n\n本文说明 {topic} 的标准处理流程。",
            f"{topic} 的处理说明", None, RNG.randint(1, 5),
            "FAQ", 1 if published else 0, created if published else None,
            RNG.randint(0, 5000), RNG.randint(0, 500), RNG.randint(1, 30),
            created, created, 1 if RNG.random() < 0.03 else 0, TENANT,
        ))
    insert("knowledge_article",
           ["id", "title", "content", "summary", "embedding", "category_id", "tags",
            "status", "published_at", "view_count", "helpful_count", "created_by",
            "created_at", "updated_at", "deleted", "tenant_id"], article_rows, chunk=100)

    attachment_rows = []
    for i in range(1, 61):
        attachment_rows.append((
            i, RNG.randint(1, 300), f"附件-{i}.pdf", RNG.randint(10_000, 5_000_000),
            f"/files/{i}.pdf", "pdf", TENANT, rand_dt(), rand_dt(), 0,
        ))
    insert("knowledge_attachment",
           ["id", "article_id", "file_name", "file_size", "file_path", "file_type",
            "tenant_id", "created_at", "updated_at", "deleted"], attachment_rows)

    # ── 通知 ────────────────────────────────────────────────────────
    notification_rows = []
    for nid in range(1, 501):
        ntype = weighted([("system", 30), ("ticket", 50), ("sla", 20)])
        read = RNG.random() < 0.4
        created = rand_dt()
        notification_rows.append((
            nid, RNG.randint(1, 40), f"{ntype} 通知 {nid}", f"通知内容 {nid}", ntype,
            RNG.randint(1, TICKETS), 1 if read else 0,
            created if read else None, TENANT, created, created,
            1 if RNG.random() < 0.03 else 0,
        ))
    insert("notification",
           ["id", "user_id", "title", "content", "type", "biz_id", "read_status",
            "read_at", "tenant_id", "created_at", "updated_at", "deleted"], notification_rows)

    # ── 分派规则 ────────────────────────────────────────────────────
    dispatch_rows = []
    for rid in range(1, 21):
        dispatch_rows.append((
            rid, TENANT, f"分派规则 {rid}", rid,
            f'{{"priority":{rid},"categoryId":{(rid % 5) + 1}}}',
            weighted([("assign", 70), ("notify", 30)]),
            f'{{"teamId":{(rid % 6) + 1}}}', 1,
            dt.datetime(2025, 1, 1), dt.datetime(2025, 1, 1), 0,
        ))
    insert("dispatch_rule",
           ["id", "tenant_id", "name", "priority", "conditions", "action_type",
            "action_config", "enabled", "created_at", "updated_at", "deleted"], dispatch_rows)

    print("SET FOREIGN_KEY_CHECKS = 1;")


if __name__ == "__main__":
    main()
