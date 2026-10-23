---
Status: accepted
---

# 流式采用事件级 SSE，由本项目自建端点包裹同步 ask

## 背景

「会话形态」一项已定：**SSE 流式 + MVP 支持多轮追问**。其动机是避免 LLM 首 token 延迟（通常 1–3 秒，问数链路更长）让界面看起来卡死。

实现前对 JimuChatBI 的流式能力做了核查，结论与预期不符：

| 层 | 流式能力 |
|---|---|
| chat2bi 的 ask 链路 | **无**。只调用 `IJimuAiAssistantService.callAi(String,String)`，阻塞返回 |
| jimu 的 SSE 端点 | 有，但是 `/jmreport/ai/assistant/createReport/stream`、`/generateChart/stream` —— 属**报表/图表设计器**的 AI，chat2bi 的 ask 路径不可达 |
| jimu 的 `IJimuAiAssistantService.routeStream(String,Map):SseEmitter` | **非逐字**。实现为 `ChatClient.prompt().system().user().call().chatResponse()`（阻塞）后发事件 |

即：**逐字（token 级）流式在本技术栈中不可获得**，除非重写 LLM 调用点。

同时发现一个对治理层有独立价值的扩展点：`JimuAiAutoConfiguration.jimuAiChatClient(JimuAiConfigBean, ObjectProvider<ChatClient.Builder>, ObjectProvider<ChatModel>)` —— **宿主可提供自己的 `ChatModel` / `ChatClient.Builder`**，jimureport 便不再自建 `OpenAiChatModel`。

## 决定

**采用事件级 SSE，不追求逐字流式。** 自建 `POST /api/ask/stream` 返回 `SseEmitter`：

1. 立即发出 `started` 事件——这是本决定的主要目的，消除「界面卡死」观感
2. 在**工作线程**上执行同步的 `IChat2biAskService.ask(...)`
3. 执行期间发**心跳事件**，避免长连接被中间层掐断
4. 完成后发 `result` 事件，载荷即 chat2bi 的完整响应对象（含 `dsl` / `sql` / `data` / `columns` / `insight` / `chartType`）

**文字小结（`insight`）若需逐字呈现，由本项目基于 agentscope-java 的层自行生成**，而非从 chat2bi 内部提取——这符合既定的 agent 分工（主链路交给 JimuChatBI，agentscope 负责路由、澄清与**解读**）。

## 考虑过的其他方案

- **复用 jimu 的 `routeStream`**：不可行——它服务于报表/图表生成，与 chat2bi 的 ask 链路无关；且它本身也不是逐字流式。
- **重写 LLM 调用点以获得逐字流式**：需要替换 `IJimuAiAssistantService` 实现或改写 `Chat2biAskServiceImpl` 的调用方式，代价是放弃厂商升级路径，收益仅是结构化小对象（DSL、SQL）的逐字呈现——**收益远小于代价**。
- **不做流式，仅同步返回**：最简单，但会让 5–15 秒的问数过程完全无反馈，与已定的会话形态冲突。

## 后果

- **工作线程会跨线程，权限上下文必须显式传递。** `ThreadLocal` 中的身份/权限上下文不会自动传播到执行 `ask` 的工作线程。必须在提交任务**前**捕获上下文、在任务内重设、在任务结束后清理。此处失败形态是**权限静默失效**（不过滤而非报错），与 ADR-0002 记录的风险同源，需一并测试守护。
- **心跳间隔需与网关/反代超时对齐**，否则长耗时问数仍会被掐断。
- **事件级 SSE 的客户端契约比逐字简单**：前端只需处理 `started` / `heartbeat` / `result` / `error` 四类事件，无需增量拼接。
- **宿主可提供 `ChatModel` 以接管 LLM 调用。** 这是成本控制（token 计量、预算、熔断）与供应商切换的落点，不需要改动厂商代码。若后续选择由本项目生成 `insight`，这条路径同时成为该功能的实现基础。
- **本决定不改变准确率或权限的任何行为**——纯属传输层形态。
