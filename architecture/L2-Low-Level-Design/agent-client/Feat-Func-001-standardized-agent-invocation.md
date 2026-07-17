---
level: L2-LLD
module: agent-client
feature_type: functional
feature_id: Feat-Func-001
status: proposed
authority: non-authoritative
dependency:
  - ../../L1-High-Level-Design/agent-client/overview.md
  - ../../L1-High-Level-Design/agent-client/logical.md
  - ../../L1-High-Level-Design/agent-client/process.md
  - ../../L1-High-Level-Design/agent-client/development.md
  - ../agent-runtime/Feat-Func-009-调用端侧工具响应-新增支持带有端侧工具的请求.md
  - ../../../agent-client/docs/proposals/agent-client-v1-design.md
  - ../../../agent-client/examples/cloud-client/README.md
---

# 标准化智能体服务调用 — 设计文档

> 目标模块：`agent-client`（edge plane SDK）
> 参照实现：`agent-client/examples/cloud-client/`（可运行原型，JDK 17，已自校验通过）
> 最后更新：2026-07-17
> **⚠️ 状态：proposed / non-authoritative。** 本文是待评审的实现级设计，不是已接受实现事实。类型/方法签名以参照原型为准、需经评审后固化。
> **🔗 wire 基线：** client↔gateway 采用标准 A2A JSON-RPC 2.0 over HTTP + SSE，报文语义**对齐 runtime 已在建的 `agent-runtime/Feat-Func-009`**（方法只用 `SendMessage`/`SendStreamingMessage`/`GetTask`；端侧工具意图走 `_interrupt`；结果走普通 TextPart）。**唯一保留的差异是拓扑：client 连接 gateway，由 gateway 受治理透传到 runtime**（见 §8 对 gateway 的要求）。

---

## 1. 概述

### 1.1 特性定位

`agent-client` 作为 edge plane SDK，为业务应用提供**标准化的智能体服务调用入口与客户端侧状态管理**：业务提交一次调用意图，SDK 建立本地调用句柄、消费服务端实时输出（A2A SSE），把服务端 Task 状态归一化为框架中立事件与快照，并支持查询与断线恢复。

- **解决的问题**：业务侧调用智能体时，容易把"客户端本地进度"与"服务端权威 Task 生命周期"混为一谈，也容易被 A2A JSON-RPC/SSE 报文细节绑架。本特性把调用形态标准化，让业务只面对 JDK 类型与 SDK 自有值对象。
- **适用场景**：Web/BFF、后端业务系统、桌面/服务端应用发起智能体调用并消费流式输出；需要断线补偿、进度投影的长周期任务。**不适用**：服务端 runtime-to-runtime 调用（走 A2A 标准入口，属 agent-runtime）。

### 1.2 当前事实边界

本文描述 Feat-Func-001 的**拟议设计**。`agent-client` 生产实现尚未落地；面向业务的黑盒行为、最佳实践与测试方法见 `agent-client/docs/getting-started.md`；能力拆解与决策项见 `agent-client-v1-design.md`；client↔gateway 的 wire 报文语义以本文 §3.5 为准，并与 `Feat-Func-009` 保持一致。模块级 API/SPI 契约以 L1 附录评审定稿为准。

### 1.3 设计原则

1. **客户端只投影，不拥有** — 服务端 Task lifecycle 的权威 owner 是 `agent-runtime`；SDK 只维护 `ClientInvocation` / `Cursor` 等本地投影，绝不写服务端权威状态。
2. **公共 API 框架中立** — 公共签名只出现 JDK 类型与 SDK 自有值对象，A2A / HTTP / JSON 库类型不得泄漏，隔离在 transport adapter 内。
3. **A2A 原生 wire，对齐 runtime** — client↔gateway 采用标准 A2A JSON-RPC 2.0 over HTTP + SSE，方法/报文与 `Feat-Func-009` 一致：只用 `SendMessage`/`SendStreamingMessage`/`GetTask`；`serverTaskId` 即 A2A `message.taskId`。
4. **拓扑保留，语义不变** — client 连接 gateway、由 gateway 受治理透传到 runtime。gateway 不改写 A2A 语义（见 §8），因此对 SDK 而言 gateway 与"直连 runtime"在 wire 契约上等价。
5. **流不可用可降级** — SSE 断开时用 `GetTask` 轮询补偿（V1 runtime 未提供 `tasks/resubscribe`）；EOF 不等于成功，无明确终态一律走查询兜底。

### 1.4 子特性全景

| 子特性 | 职责 | 关键抽象 | 状态 |
|--------|------|---------|------|
| 调用创建与本地句柄 | `invoke` → 本地句柄 + accepted/completion future | `AgentClient`, `InvocationRequest`, `InvocationCall` | ⬜ proposed |
| 事件归一化 | A2A status/artifact/message → 密封事件 | `InvocationEvent`（sealed） | ⬜ proposed |
| 状态投影与快照 | Task 状态本地投影 + 待办工具快照 | `TaskState`, `InvocationSnapshot` | ⬜ proposed |
| 服务流消费与断线补偿 | SSE 消费、`GetTask` 轮询降级 | `InvocationCall#events/completion`, `getTask` | ⬜ proposed |
| 取消 | 请求式取消 | `AgentClient#cancelTask` | ⚠️ 依赖 gateway/runtime 支持（V1 未在 `Feat-Func-009` 范围） |
| 传输抽象 | 领域操作 → A2A wire；OS/网络差异隔离 | `TransportProvider`（SPI） | ⬜ proposed |

---

## 2. 特性规格

### 2.1 能力清单

| 能力 | 状态 | 说明 |
|------|------|------|
| 同步接受（拿到 serverTaskId） | ⬜ | `invoke` 后 `accepted()` 完成时携带 `taskId`/`contextId` |
| 归一化事件流 | ⬜ | Accepted / StatusChanged / ContentDelta / InputRequired / Completed / Failed |
| Task 状态投影 | ⬜ | `TaskState` 闭集 + `isTerminal()`；未知值映射 UNKNOWN |
| 终态完成投影 | ⬜ | `completion()` 在 COMPLETED 完成、FAILED 异常完成 |
| 快照查询（轮询降级） | ⬜ | `getTask(taskId)` → `GetTask`，返回 `InvocationSnapshot`，含待办工具 |
| 传输可替换 | ⬜ | `TransportProvider` SPI；默认 JDK HttpClient，测试用 in-process fake |
| 取消 | ⚠️ | `cancelTask` 保留 API；wire 支持待 gateway/runtime 补齐（见 §7、§8 G-6） |
| 断线补偿 | ⬜ | `GetTask` 轮询（V1 无 `tasks/resubscribe`，见 §5.2） |

### 2.2 显式排除

| 排除项 | 原因 | 替代 |
|--------|------|------|
| 服务端 Task 生命周期写入 | 权威 owner 是 agent-runtime | 只投影，经受治理入口发请求 |
| 客户端 webhook / S2C 回调入口 | 增加安全面与网络可达性风险（见 L1 overview §6） | 折叠为 Task 待输入意图 + client 主动多轮请求 |
| REST 简化入口 | 属 agent-runtime RESTful Facade | 见 runtime facade |
| 第二套 run/job 状态机 | 避免与 A2A Task 事实冲突 | 统一投影到 `TaskState` |
| 一次 invoke 内并行多工具 | `Feat-Func-009` V1 同一时刻只允许一个 pending 端侧工具 | 单 pending 串行多轮（见 Feat-Func-002） |

### 2.3 接口契约（Logical View）

```java
/** 面向业务的稳定入口。公共签名只用 JDK 类型与 SDK 自有值对象。 */
public interface AgentClient extends AutoCloseable {
    /** 首次调用（无 taskId）。返回本地调用控制器。 */
    InvocationCall invoke(InvocationRequest request);
    /** 查询服务端 Task 快照（SSE 不可用时的降级路径，映射 A2A GetTask）。 */
    CompletionStage<InvocationSnapshot> getTask(String taskId);
    /** 请求取消（请求非命令）；wire 支持待 gateway/runtime 补齐。 */
    CompletionStage<InvocationSnapshot> cancelTask(String taskId, String reason);
    LocalToolRegistry tools();   // 见 Feat-Func-002
    @Override void close();
}

/** 一次本地调用控制器：暴露 accepted/events/completion，不伪造服务端状态。 */
public interface InvocationCall extends AutoCloseable {
    String clientInvocationId();
    CompletionStage<Handle> accepted();          // Handle(taskId, contextId)
    Flow.Publisher<InvocationEvent> events();     // 可多订阅，尊重 demand
    CompletionStage<InvocationSnapshot> completion();
}
```

#### 数据类型

| 类型 | 关键字段 | 含义 | 约束 |
|------|---------|------|------|
| `InvocationRequest` | `agentId`, `text`, `clientInvocationId`, `idempotencyKey`, `deadline` | 首轮调用请求 | `agentId` 非空；两个 id 缺省自动生成 |
| `InvocationEvent`（sealed） | `taskId()` + 6 个变体 | 归一化事件 | 变体闭集，transport 负责映射 |
| `TaskState`（enum） | `isTerminal()` | Task 状态本地投影 | 闭集 + UNKNOWN 兜底 |
| `InvocationSnapshot` | `state`, `terminal`, `pendingToolCall` | Task 只读投影 | `pendingToolCall` 仅 INPUT_REQUIRED 非空 |

#### 行为承诺

- **必须**：`accepted()` 完成时携带非空 `serverTaskId`（= A2A `message.taskId`）；后续所有请求以它为准。
- **必须**：`completion()` 只在服务端出现明确终态时结算；SSE EOF 无终态视为异常，触发 `GetTask` 兜底。
- **禁止**：公共 API 出现 A2A/HTTP/JSON 库类型；禁止 SDK 写服务端 Task 状态。
- **允许**：`events()` 被多个订阅者消费（观测/驱动分离）。

---

## 3. 核心实现（参照原型）

### 3.1 分层与传输隔离

```
业务应用
  │  只依赖 api 包（JDK 类型 + SDK 值对象）
  ▼
AgentClient (api)  ──►  DefaultAgentClient (internal, core 编排)
                              │
                              ▼  只依赖 TransportProvider SPI
                        TransportProvider (transport.spi)
                         ├── JDK HttpClient 实现：A2A JSON-RPC + SSE 连 gateway（生产，wire 见 §3.5）
                         └── InProcessFakeGateway（transport.fake，测试用，模拟 A2A 多轮）
```

wire 细节（`SendMessage`/`SendStreamingMessage`/`GetTask`、SSE 语义、创建幂等、gateway 端点/鉴权）完全封装在 transport adapter，公共 API 与 core 不感知。因此 wire 字段改名/增减、甚至"直连 vs 经 gateway"的切换都是 transport 层可闭环的变更，不波及业务代码。

### 3.2 调用创建与接受

```
业务: client.invoke(request)
  │  构造 CreateCommand(clientInvocationId, idempotencyKey=messageId, agentId, text, clientTools)
  ▼
DefaultAgentClient: transport.createAndStream(cmd) → Flow.Publisher<InvocationEvent>
  │  包装为 InvocationCall（内部订阅捕获 handle/终态）
  ▼
transport（A2A）: SendStreamingMessage（无 taskId 创建），POST 到 gateway A2A 端点
  │  首个事件 Accepted 携带 serverTaskId（= A2A message.taskId）
  ▼
InvocationCall.accepted() 完成
```

创建幂等：UNKNOWN（发了创建请求但不知服务端是否建了 Task）时，用**同一 `messageId`** 重发。A2A/`Feat-Func-009` 未定义创建去重语义，因此这依赖 **gateway 在去重窗口内按 `messageId` 去重**（见 §8 G-6）；gateway 未提供时，client 降级为"不自动重试创建"以避免重复建 Task。

### 3.3 事件归一化

transport adapter 把 A2A 报文映射为密封事件（业务永不接触 A2A 类型），映射源与 `Feat-Func-009` 的报文结构一致：

| A2A 报文（对齐 Feat-Func-009） | 归一化事件 |
|---------|-----------|
| 首个响应 / `result.task`（新建） | `Accepted(taskId, contextId)` |
| `result.statusUpdate` / `result.task.status`（`TASK_STATE_*`） | `StatusChanged(taskId, TaskState, terminal)` |
| `Message` / `Artifact` 文本 part | `ContentDelta(taskId, text)` |
| `TASK_STATE_INPUT_REQUIRED` + `status.message.metadata._interrupt`（`_interrupt_kind=client_tool`） | `InputRequired(taskId, ToolCall)`（见 Feat-Func-002） |
| `TASK_STATE_COMPLETED` | `Completed(taskId, summary)` |
| `TASK_STATE_FAILED` / JSON-RPC error | `Failed(taskId, errorCode, message)` |

> `TaskState` 枚举字符串对齐 A2A `TaskState`（`TASK_STATE_SUBMITTED`/`WORKING`/`INPUT_REQUIRED`/`COMPLETED`/`FAILED`/`CANCELED`/`REJECTED`）；未识别值映射 UNKNOWN。

### 3.4 状态投影

`TaskState` 是服务端状态在 client 侧的**只读投影**，不由 SDK 驱动流转：

```
SUBMITTED → WORKING → (INPUT_REQUIRED ⇄ WORKING)* → COMPLETED / FAILED / CANCELED / REJECTED
```

| 当前投影 | 观察到的事件 | 下一投影 | 附加行为 |
|---------|------------|---------|---------|
| —（invoke 前） | Accepted | SUBMITTED/WORKING | 结算 `accepted()` |
| WORKING | StatusChanged(INPUT_REQUIRED) + InputRequired | INPUT_REQUIRED | 记录 pendingToolCall，触发工具驱动（Feat-Func-002） |
| INPUT_REQUIRED | 结果回传后 StatusChanged(WORKING) | WORKING | 续跑同一 Task |
| WORKING | Completed | COMPLETED | 结算 `completion()` |
| WORKING/任意 | Failed | FAILED | `completion()` 异常完成 |

> 未知/新增状态一律映射 `UNKNOWN`（不阻塞、不崩溃），由 `GetTask` 兜底细化——保证向前兼容。

### 3.5 wire 契约（client↔gateway，对齐 Feat-Func-009）

> client 对 gateway 发起的 A2A 报文，与 `Feat-Func-009` 对 runtime 定义的报文**完全一致**；gateway 只做受治理透传（§8）。以下为本特性涉及的三类报文（工具目录/意图/结果的字段细节见 Feat-Func-002 §3.5）。

**① 创建（`SendStreamingMessage` / `SendMessage`，无 taskId）**

```json
{
  "jsonrpc": "2.0",
  "id": "req-1",
  "method": "SendStreamingMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "messageId": "msg-1",
      "parts": [{"text": "帮我看看当前页面并建单"}]
    },
    "metadata": { "clientTools": [ /* 见 Feat-Func-002 §3.5 */ ] }
  }
}
```

- `messageId` 由 client 生成、稳定；同一次创建的重试复用同一 `messageId`（创建幂等键，见 §3.2）。
- `params.metadata.clientTools` 为本次可用的本地工具目录；无端侧工具时可省略。

**② 等待端侧工具（SSE / 阻塞响应 / `GetTask` 观察）**

Task 进入 `TASK_STATE_INPUT_REQUIRED`，`status.message.metadata._interrupt` 携带 `toolName`/`toolCallId`/`context.arguments`/`_interrupt_kind=client_tool`（完整结构见 Feat-Func-002 §3.5 与 `Feat-Func-009` §2.3.4）。SSE 帧格式为 `event: jsonrpc` + `data:` 为完整 JSON-RPC 响应。

**③ 提交结果并续跑（`SendMessage`，带原 taskId）**

```json
{
  "jsonrpc": "2.0",
  "id": "req-2",
  "method": "SendMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "messageId": "msg-2",
      "taskId": "task-123",
      "contextId": "ctx-1",
      "parts": [{"text": "页面正文……"}]
    }
  }
}
```

- 结果以**普通 TextPart observation 文本**回传，`message.taskId` 是唯一关联字段，**不回传 `toolCallId`**（V1 单 pending，runtime 自动关联，见 Feat-Func-002）。

**④ 查询（`GetTask`）**

```json
{ "jsonrpc": "2.0", "id": "req-3", "method": "GetTask", "params": { "taskId": "task-123" } }
```

---

## 4. 代码结构（参照原型包）

```
com.huawei.ascend.client
├── api/
│   ├── AgentClient.java            # 业务入口（本特性主接口）
│   ├── AgentClients.java           # builder 工厂（transport/store/governance/eventListener 可替换）
│   ├── InvocationRequest.java      # 调用请求（record + builder）
│   ├── InvocationCall.java         # 本地句柄：accepted/events/completion + Handle
│   ├── InvocationEvent.java        # sealed 事件层次 + ToolCall
│   ├── TaskState.java              # Task 状态投影 enum（isTerminal）
│   └── InvocationSnapshot.java     # Task 只读快照
├── transport/spi/
│   └── TransportProvider.java      # 传输抽象（createAndStream/getTask/cancel/resumeToolResult）
├── transport/fake/
│   └── InProcessFakeGateway.java   # 测试用：SubmissionPublisher 模拟 A2A 多轮
└── internal/
    └── DefaultAgentClient.java     # core 编排：串联 transport，维护调用投影
```

### 核心类静态关系

```
«interface» AgentClient        «interface» TransportProvider
      ▲                                 ▲
      │ implements                      │ implements
DefaultAgentClient ──── uses ──────────┤
      │                                 ├── (prod) JDK HttpClient A2A adapter → gateway
      │ creates                         └── (test) InProcessFakeGateway
      ▼
«interface» InvocationCall  ◄── 内部订阅 Flow.Publisher<InvocationEvent>
```

---

## 5. 运行流程

### 5.1 主流程（成功多轮）

见 §3.2–3.5 与 Feat-Func-002 §5 的多轮时序；本特性负责"接受 → 事件归一化 → 状态投影 → 终态结算"。

### 5.2 断线补偿与降级

```
SSE 断开（V1 runtime/gateway 无 tasks/resubscribe）
  └── 轮询 GetTask(taskId) → InvocationSnapshot（含 pendingToolCall）
        → 达终态则结算 completion；仍 INPUT_REQUIRED 则据 pendingToolCall 继续驱动（Feat-Func-002）
```

> 若后续 gateway/runtime 支持 `tasks/resubscribe`，transport adapter 可在有 cursor 时优先续接事件流，再降级到 `GetTask` 轮询——属 transport 层内部优化，不改公共 API。

### 5.3 错误、取消、降级

| 错误场景 | 触发条件 | 行为 | 对外结果 |
|---------|---------|------|---------|
| 创建 UNKNOWN | 创建请求超时/断网，不知是否建 Task | 用同一 messageId 重发（依赖 gateway 去重，§8 G-6） | 不重复建 Task |
| SSE EOF 无终态 | 流结束但未见 Completed/Failed | 不判定成功，转 `GetTask` 兜底 | `completion()` 依查询结果结算 |
| 取消 | 业务调用 `cancelTask` | V1 wire 未支持；gateway/runtime 补齐前记录并降级 | 见 §7、§8 G-6 |
| 未知状态 | 服务端新增 TaskState | 映射 UNKNOWN，不崩溃 | 由 `GetTask` 兜底细化 |
| 慢消费 | 业务订阅者处理慢 | 尊重 Flow demand，本地背压 | 不反压服务端控制面 |

---

## 6. 配置与使用

### 6.1 构造（参照原型）

```java
AgentClient client = AgentClients.builder()
        .transport(transportProvider)   // 生产：A2A over HTTP/SSE 连 gateway；测试：InProcessFakeGateway
        .tenantId("acme")
        .eventListener(event -> log(event))  // 可选：观测所有归一化事件
        .build();

InvocationCall call = client.invoke(
        InvocationRequest.builder().agentId("support-agent").text("...").build());
Handle handle = call.accepted().toCompletableFuture().get();
InvocationSnapshot terminal = call.completion().toCompletableFuture().get();
```

### 6.2 关键构造项

| 构造项 | 类型 | 默认 | 说明 |
|-------|------|------|------|
| `transport` | `TransportProvider` | 无（必填） | wire 绑定（gateway 端点/鉴权）；隔离 OS/网络差异 |
| `stateStore` | `ClientStateStore` | 内存实现 | 见 Feat-Func-002（结果 outbox / ACK） |
| `tenantId` | `String` | `default` | 传播到执行上下文；wire 上的租户以 gateway 从凭据注入为准（§8 G-3） |
| `eventListener` | `Consumer<InvocationEvent>` | no-op | 观测/日志/指标，不影响编排 |

---

## 7. 当前限制

| 限制 | 影响范围 | 临时方案 |
|------|---------|---------|
| 无生产实现 | 仅原型 + fake gateway；A2A HttpClient adapter 待实现 | 以原型验证 core 逻辑，adapter 随 wire 定稿落地 |
| 内存态投影 | 不承诺跨进程重启恢复 | 换 `ClientStateStore` 持久化实现 |
| 无 `tasks/resubscribe` | V1 断线只能 `GetTask` 轮询补偿 | 待 gateway/runtime 支持后 transport 层优化 |
| 取消未上 wire | `Feat-Func-009` 范围只含 Send/Get，无 `tasks/cancel` | 保留 API；见 §8 G-6 转述给 gateway/runtime |
| 创建幂等依赖 gateway | A2A/009 未定义创建去重 | 见 §8 G-6；未落地则不自动重试创建 |
| 单 Task 单流 | 未设计一次 invoke 内多 Task 分裂 | 每次 invoke 一个 serverTaskId |

---

## 8. 对 gateway 的要求（转述给 gateway 负责同事）

> gateway 的设计文档尚未上传。以下是 client 侧为"连接 gateway 而非直连 runtime"所需的 gateway 职责。**总原则：gateway 是受治理的 A2A 透传代理，对 client 暴露的 A2A 语义必须与 `Feat-Func-009` 对 runtime 定义的完全一致，不得改写。**

| 编号 | 要求 | 说明 / 理由 |
|------|------|------------|
| **G-1 A2A 兼容入口** | gateway 对 client 暴露标准 A2A JSON-RPC 端点（`SendMessage`/`SendStreamingMessage`/`GetTask` over HTTP POST），并逐字段透传到目标 runtime | client 的 transport adapter 按 `Feat-Func-009` 编解码；gateway 不得改动 `result.task`/`result.statusUpdate`/`TASK_STATE_*`/`status.message.metadata._interrupt` 语义 |
| **G-2 Agent 发现与 Card url 改写** | 暴露 Agent Card 发现入口，并把 Card 内 `url` 改写为 gateway 自身地址 | 防止 client 直连 runtime 绕过治理 |
| **G-3 认证与租户注入** | 完成鉴权，从凭据解析租户并注入下游；**丢弃** client 自报的租户 header | client 不自证租户；tenantId 仅用于本地上下文/日志 |
| **G-4 按 agentId 路由** | 依据 `agentId` 路由到目标 runtime 服务 | — |
| **G-5 粘滞路由（关键）** | 带 `taskId` 的续跑/查询请求必须路由到**持有该 Task 的同一 runtime 实例** | `Feat-Func-009` 的 pending `_interrupt` 存在实例级 TaskStore；错误实例会导致 resume 无法关联、Task 永久挂起 |
| **G-6 创建幂等 + 取消（client 诉求）** | ①在去重窗口内对 `同租户 + 同 messageId` 的**创建**消息去重（已建返回既有 Task，未建视首次）；②若要支持取消，需 gateway+runtime 补齐 `tasks/cancel` | A2A/009 未定义创建去重与本特性取消；缺失时 client 分别降级为"不自动重试创建"与"取消不可用" |
| **G-7 错误分层** | 治理层错误（401/403/404/413/429/503）以 HTTP + 稳定 error body 暴露，与 A2A JSON-RPC error 分层 | client 需同时处理 HTTP 层与 JSON-RPC 层错误 |
| **G-8 SSE 逐帧透传** | 保持 runtime 的 SSE 帧（`event: jsonrpc` + 完整 JSON-RPC `data`）逐帧透传，不缓冲成整体、不改写 `_interrupt` | 保证 client 事件归一化与断线语义可用 |
| **G-9 TLS 与配额声明** | 声明 TLS、请求正文上限、事件大小上限 | client 据此约束工具目录/结果大小（见 Feat-Func-002 §8） |

> 端侧工具多轮相关的 gateway 要求（`clientTools`/`_interrupt`/TextPart 结果透传）见 Feat-Func-002 §8。
