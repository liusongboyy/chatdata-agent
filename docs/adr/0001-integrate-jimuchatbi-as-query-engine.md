---
Status: superseded by ADR-0006
---

# 集成 JimuChatBI 作为问数能力，自建治理层

> **⚠️ 本 ADR 已被 [ADR-0006](0006-self-build-ask-engine-supersedes-integration.md) 取代。**
> 决定已反转：改为自研问数与语义层。本文保留，用于理解当时的权衡与工期估算——
> 其中「自研 6–9 个月、准确率不可排期」的判断**至今成立**，是新方案必须承接的成本。
>
> 被证伪的是本文的一个未经验证的前提：**「集成后仍可修改厂商行为」**。
> 下文「`-sources.jar` 可从 Maven Central 获取」这句有误导性——实测三个 sources jar
> 均为空壳（只有 `README.md`），且字节码混淆、页面模板加密，**后端实际不可改**。
> 详见 ADR-0006 的「背景」。

## 背景

本项目的目标是企业级问数 agent。JimuChatBI 1.2.0（`org.jeecgframework.jimureport:jimuchatbi-spring-boot4-starter`）已经提供了问数所需的绝大部分能力：语义建模（指标/维度/口径）、NL2SQL、30+ 数据源适配、7 种图表、内置 Chat2BI agent 与多轮追问。它的 parent 正是 `spring-boot-starter-parent:4.1.0`，与本项目技术栈精确对齐，且不收取授权费、`-sources.jar` 可从 Maven Central 获取。

自研同样路径的估计是 6–9 个月（单人全职），其中 NL2SQL 准确率这一项没有确定工期——管道可以在 3 个月内铺完，但准确率从 55% 提升到 80% 依赖语料与评测集的持续迭代，不是可排期的工作。

## 决定

集成 JimuChatBI 承担语义层、NL2SQL、数据源适配与图表展现；本项目只自建**治理层**：数据权限、审计、身份与权限接入、以及基于 agentscope-java 的问题路由与澄清追问。

关键理由：无论选择集成还是自研，治理层的工作量完全相同。集成让我们把全部精力投在治理层，而不必先花 6–9 个月重建取数能力再开始治理。

## 考虑过的其他方案

- **全栈自研**（基于 agentscope-java 自建语义层 + NL2SQL）：许可证干净、完全可控、无 vendor 依赖，但需 6–9 个月且准确率无确定工期，等于重走 JimuChatBI 已走完的路。
- **购买 jeecg 商业授权**：可消除 copyleft 义务，但非必需——开源路径本身不收费。
- **独立部署 JimuChatBI 作为外部服务**：可能改变 copyleft 的适用分析，但需法务结论，且增加一层运维复杂度。

## 后果

- **数据权限不走 ORM 层。** JimuChatBI 在其 agent 内部生成并执行 SQL，任何基于 MyBatis 拦截器（如 `TenantLineHandler`）的方案都不会生效。该问题的最终结论见 ADR-0002——已实测验证：复用厂商自带的行级/列级权限，零自建 SQL 改写代码。
- **接口隔离是退出选项，不是许可证缓解措施。** 把取数能力定义在本项目自己的接口之后，可让未来更换实现只需数周而非推倒重来。但把 AGPL 的 JAR 链入 Spring Boot 应用时，copyleft 义务针对的是合并后的作品，与内部接口如何设计无关。
- **AGPL 义务未决。** JimuChatBI 仓库声明 AGPL-3.0；其 POM 的 `<licenses>` 写的是「功能可以免费用」，这不是许可证标识符，无法据此做合规审查。需要 jeecg 或法务给出**书面**结论。此事项不阻塞开发，但必须在产品对外发布前解决。
