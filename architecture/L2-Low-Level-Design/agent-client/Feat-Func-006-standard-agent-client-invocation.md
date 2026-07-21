---
level: L2-LLD
module: agent-client
feature_type: functional
feature_id: FEAT-006
status: proposed
authority: non-authoritative
dependency:
  - ../../../version-scope/FEAT-006-standard-agent-client-invocation.md
  - ../../L1-High-Level-Design/agent-client/overview.md
  - ../../L1-High-Level-Design/agent-client/logical.md
  - ../../L1-High-Level-Design/agent-client/process.md
  - ../../L1-High-Level-Design/agent-client/development.md
  - ../agent-runtime/Feat-Func-009-调用端侧工具响应-新增支持带有端侧工具的请求.md
  - ../../../agent-client/docs/proposals/agent-client-v1-design.md
  - ../../../agent-client/examples/cloud-client/README.md
---

# 标准化智能体服务调用 — 设计文档（FEAT-006 L2）

> 目标模块：`agent-client`（edge plane SDK）
> 事实来源（authoritative）：`version-scope/FEAT-006-standard-agent-client-invocation.md`（本 L2 是其实现级细化，术语与语义以 FEAT-006 为准）
> 参照实现：`agent-client/examples/cloud-client/`（可运行原型，JDK 17；原型符号名为早期形态，正文已按 FEAT-006 术语对齐，原型代码待机械跟随，见 §9）
> 最后更新：2026-07-21
> **⚠️ 状态：proposed / non-authoritative。** 本文是待评审的实现级设计，不是已接受实现事实。
> **🔗 wire 基线：** client↔gateway 采用标准 A2A JSON-RPC 2.0 over HTTP + SSE，报文语义**对齐 runtime 已在建的 `agent-runtime/Feat-Func-009`**（方法只用 `SendMessage`/`SendStreamingMessage`/`GetTask`）。业务侧标识（`conversationId`/`invocationRef`）到 A2A wire 的映射见 §3.5。**唯一保留的拓扑差异：client 连接 gateway，由 gateway 受治理透传到 runtime**（见 §8）。

---

## 1. 概述

### 1.1 特性定位

`agent-client` 作为 edge plane SDK，为业务应用提供**标准化的智能体服务调用入口与客户端侧状态管理**：业务用统一 facade 发起调用、接收结果、观察过程、查询/取消/重订阅、继续等待输入，只面对稳定的客户端调用语义（`conversationId`、`invocationRef`、调用模式、状态投影、等待输入、错误分类、恢复线索），**不感知** A2A JSON-RPC、Gateway 路由、runtime endpoint 或服务端 `taskId`。

- **解决的问题**：不同业务应用用同一套客户端调用语义接入平台；把"客户端本地进度"与"服务端权威 Task 生命周期"解耦，也屏蔽 A2A/SSE 报文细节。
- **适用场景**：Web/BFF、后端业务系统、桌面/服务端应用发起智能体调用并消费输出；需断线补偿、进度投影的长周期任务。**不适用**：服务端 runtime-to-runtime 调用（属 agent-runtime）。

### 1.2 当前事实边界

本文描述 FEAT-006 的**拟议 L2 设计**。`agent-client` 生产实现尚未落地；最佳实践与测试方法见 `agent-client/docs/getting-started.md`；模块级决策/路线图见 `agent-client-v1-design.md`（已声明被 FEAT-006/007 取代）。client↔gateway 的 wire 报文语义以本文 §3.5 为准，并与 `Feat-Func-009` 一致。

### 1.3 设计原则

1. **三层主权分离** — `conversationId` 业务应用主权、`invocationRef`/`invocationId` client 主权、`taskRef`(=A2A `taskId`) runtime 主权。业务应用**只用 `invocationRef`** 操作调用；`taskRef` 仅 client 内部映射与诊断，**不得**成为业务操作句柄。
2. **客户端只投影，不拥有** — 服务端 Task lifecycle 权威 owner 是 `agent-runtime`；SDK 只维护 invocation 本地投影与 `invocationRef→taskRef` 受治理映射，绝不写服务端权威状态、不建第二套 TaskStore。
3. **公共 API 框架中立** — 公共签名只出现 JDK 类型与 SDK 自有值对象，A2A/HTTP/JSON 库类型隔离在 transport adapter 内。
4. **调用模式是端到端诉求** — 创建时必须声明 `BLOCKING`/`STREAMING`/`ASYNC`；`STREAMING` 沿链路传播、不支持时不静默降级；重订阅只观察不改模式。
5. **拓扑保留，语义不变** — client 连 gateway、由 gateway 受治理透传到 runtime；gateway 不改写 A2A 语义（见 §8），wire 契约与"直连 runtime"等价。
6. **流不可用可降级** — SSE 断开时用重订阅或 `GetTask` 轮询补偿；EOF 不等于成功，无明确终态一律走查询兜底。

### 1.4 子特性全景

| 子特性 | 职责 | 关键抽象 | 状态 |
|--------|------|---------|------|
| 调用创建与本地句柄 | `invoke`（带 conversationId + 调用模式）→ 本地句柄 | `AgentClient`, `InvocationRequest`, `InvocationCall`, `InvocationMode` | ⬜ proposed |
| 事件归一化 | A2A status/artifact/message → 密封事件 | `InvocationEvent`（sealed） | ⬜ proposed |
| 状态投影与快照 | Task 状态本地投影 + 待办工具快照 | `TaskState`, `InvocationSnapshot` | ⬜ proposed |
| 查询 | 以 `invocationRef` 查快照 | `AgentClient#getInvocation` | ⬜ proposed |
| 取消 | 以 `invocationRef` 请求取消（MUST，内部映射 `CancelTask`） | `AgentClient#cancel` | ⬜ proposed（wire 依赖 gateway/runtime，见 §8） |
| 重订阅 | 以 `invocationRef` 只观察已有 invocation（MUST） | `AgentClient#resubscribe` | ⬜ proposed（wire 依赖 gateway/runtime，见 §8） |
| 继续等待输入（用户输入） | 新 invocation 关联旧 invocation | `AgentClient#continueInput`, `ContinueInputRequest` | ⬜ proposed |
| 传输抽象 | 领域操作 → A2A wire；OS/网络差异隔离 | `TransportProvider`（SPI） | ⬜ proposed |

> 端侧**工具结果**回传是"同一 invocation 下的内部恢复请求"（属 Feat-Func-007），**不是** `continueInput` 这种业务可见的新 invocation。二者区别见 §3.4。

---

## 2. 特性规格

### 2.1 能力清单

| 能力 | 状态 | 说明 |
|------|------|------|
| 标准调用 facade | ⬜ | 创建/查询/取消/重订阅/继续等待输入，统一以 `invocationRef` 操作 |
| conversation 传递 | ⬜ | 业务传入或委托生成 `conversationId`，同 conversation 稳定传递 |
| 调用模式声明 | ⬜ | 创建必声明 `BLOCKING`/`STREAMING`/`ASYNC` |
| invocation 回显 | ⬜ | 回显 `conversationId`/`invocationRef`/幂等键/模式/结果类型/状态投影/恢复线索；不要求业务持有 `taskId` |
| 归一化事件流 | ⬜ | Accepted / StatusChanged / ContentDelta / InputRequired / Completed / Failed |
| Task 状态投影 | ⬜ | `TaskState` 闭集 + `isTerminal()`；未知值映射 UNKNOWN |
| 快照查询 | ⬜ | `getInvocation(invocationRef)`（内部 `GetTask`），返回 `InvocationSnapshot` |
| 取消 | ⬜ | `cancel(invocationRef, reason)`（内部 `CancelTask`；wire 见 §8 G-6） |
| 重订阅 | ⬜ | `resubscribe(invocationRef)` 只观察已有 invocation（wire 见 §8 G-6） |
| 继续等待输入 | ⬜ | `continueInput` 新 invocation 关联旧 invocation 的 input_required |
| 幂等/UNKNOWN 恢复 | ⬜ | 同一 `invocationId`+幂等键+原创建请求恢复；不新增私有 ResolveInvocation |
| 传输可替换 | ⬜ | `TransportProvider` SPI；默认 JDK HttpClient，测试用 in-process fake |

### 2.2 显式排除

| 排除项 | 原因 | 替代 |
|--------|------|------|
| 业务应用以 `taskId` 操作调用 | Task 主权属 runtime；`taskId` 不是业务句柄 | 一律走 `invocationRef` |
| 服务端 Task 生命周期写入 | 权威 owner 是 agent-runtime | 只投影，经受治理入口发请求 |
| 客户端 webhook / S2C 回调入口 | 安全面与网络可达性风险 | 折叠为 Task 待输入意图 + client 主动多轮请求 |
| 第二套 run/job 状态机 | 避免与 A2A Task 事实冲突 | 统一投影到 `TaskState` |
| 独立 turn 概念 | FEAT-006 不引入 | 多轮=同 conversation 下多个 invocation |
| 私有 `ResolveInvocation` 查询 | FEAT-006 当前版本不新增 | 用同一 invocationId+幂等键+原创建请求恢复 |
| 一次 invoke 内并行多工具 | `Feat-Func-009` V1 单 pending | 单 pending 串行多轮（见 Feat-Func-007） |

### 2.3 接口契约（Logical View）

```java
/** 面向业务的稳定入口。业务只用 invocationRef 操作调用；公共签名只用 JDK 类型与 SDK 自有值对象。 */
public interface AgentClient extends AutoCloseable {
    /** 首次调用：必须携带 conversationId 与调用模式。返回本地调用控制器。 */
    InvocationCall invoke(InvocationRequest request);
    /** 查询调用快照（内部解析 taskRef → A2A GetTask）。 */
    CompletionStage<InvocationSnapshot> getInvocation(String invocationRef);
    /** 请求取消（请求非命令；内部映射 A2A CancelTask）。 */
    CompletionStage<InvocationSnapshot> cancel(String invocationRef, String reason);
    /** 重订阅：只观察已有 invocation 的服务流，不改调用模式、不隐式新建 invocation。 */
    Flow.Publisher<InvocationEvent> resubscribe(String invocationRef);
    /** 继续等待输入（用户输入）：创建新 invocation 关联旧 invocation 的 input_required。 */
    InvocationCall continueInput(ContinueInputRequest request);
    LocalToolRegistry tools();   // 见 Feat-Func-007
    @Override void close();
}

/** 一次本地调用控制器：以 invocationRef 为句柄，暴露 accepted/events/completion，不伪造服务端状态。 */
public interface InvocationCall extends AutoCloseable {
    String invocationRef();
    String conversationId();
    CompletionStage<Handle> accepted();          // Handle(invocationRef, conversationId, diagnosticTaskRef)
    Flow.Publisher<InvocationEvent> events();     // 可多订阅，尊重 demand
    CompletionStage<InvocationSnapshot> completion();
}

public enum InvocationMode { BLOCKING, STREAMING, ASYNC }
```

#### 数据类型

| 类型 | 关键字段 | 含义 | 约束 |
|------|---------|------|------|
| `InvocationRequest` | `agentId`, `conversationId`, `mode`, `input`, `invocationId`, `idempotencyKey`, `trace`, `credentialContext`, `deadline` | 首轮调用请求 | `agentId`/`conversationId`/`mode` 非空；`invocationId`/`idempotencyKey` 缺省自动生成 |
| `ContinueInputRequest` | `conversationId`, `relatedInvocationRef`(或 `inputRequiredRef`), `input`, `invocationId`, `idempotencyKey` | 继续等待输入（用户输入） | 关联不可续接/过期/多义 → 明确错误，不偷偷新建普通任务 |
| `InvocationEvent`（sealed） | `invocationRef()` + 6 个变体 | 归一化事件 | 变体闭集，transport 负责映射 |
| `TaskState`（enum） | `isTerminal()` | Task 状态本地投影 | 闭集 + UNKNOWN 兜底 |
| `InvocationSnapshot` | `invocationRef`, `state`, `terminal`, `pendingToolCall`, `diagnosticTaskRef` | invocation 只读投影 | `pendingToolCall` 仅 INPUT_REQUIRED 非空；`diagnosticTaskRef` 标注非操作性 |
| `Handle` | `invocationRef`, `conversationId`, `diagnosticTaskRef` | 接受回执 | `diagnosticTaskRef` 仅诊断，不作操作句柄 |

#### 行为承诺

- **必须**：`accepted()` 完成时携带非空 `invocationRef`；后续所有操作以它为准，业务不接触 `taskId`。
- **必须**：创建声明 `mode`；`STREAMING` 不支持时不静默降级（显式降级并回显事实）。
- **必须**：`completion()` 只在服务端出现明确终态时结算；SSE EOF 无终态视为异常，触发重订阅/`GetTask` 兜底。
- **必须**：UNKNOWN 时回显 `invocationRef`+幂等键+恢复线索；用同一 `invocationId`+幂等键+原创建请求恢复。
- **禁止**：公共 API 出现 A2A/HTTP/JSON 库类型或把 `taskId` 作为业务操作参数；禁止 SDK 写服务端 Task 状态。
- **允许**：`events()` 被多个订阅者消费（观测/驱动分离）。

---

## 3. 核心实现（参照原型）

### 3.1 分层与传输隔离

```
业务应用
  │  只依赖 api 包（JDK 类型 + SDK 值对象；只见 invocationRef）
  ▼
AgentClient (api)  ──►  DefaultAgentClient (internal, core 编排 + invocationRef→taskRef 映射)
                              │
                              ▼  只依赖 TransportProvider SPI
                        TransportProvider (transport.spi)
                         ├── JDK HttpClient 实现：A2A JSON-RPC + SSE 连 gateway（生产，wire 见 §3.5）
                         └── InProcessFakeGateway（transport.fake，测试用，模拟 A2A 多轮）
```

wire 细节（`SendMessage`/`SendStreamingMessage`/`GetTask`、SSE 语义、创建幂等、gateway 端点/鉴权）与 `invocationRef↔taskRef` 映射封装在 core/transport 层，公共 API 不感知。

### 3.2 调用创建与接受

```
业务: client.invoke(request{conversationId, mode, input, invocationId, idempotencyKey})
  │  构造 CreateCommand(conversationId→contextId, invocationId/idempotencyKey→messageId, agentId, input, mode, clientTools)
  ▼
DefaultAgentClient: transport.createAndStream(cmd) → Flow.Publisher<InvocationEvent>
  │  建立 invocationRef，内部绑定 taskRef（首帧 result.task.id）
  ▼
transport（A2A）: SendMessage/SendStreamingMessage（无 taskId 创建）POST 到 gateway
  │  首个事件 Accepted：结算 accepted() → Handle(invocationRef, conversationId, diagnosticTaskRef)
```

创建幂等：UNKNOWN（发了创建请求但不知是否建了 Task）时用**同一 `invocationId`+幂等键**（=同一 A2A `messageId`）重发，由 **gateway 按 messageId 去重**（见 §8 G-6）；未提供时降级为"不自动重试创建"。当前版本不新增以 `invocationId` 查询 runtime 的私有接口。

### 3.3 事件归一化

transport adapter 把 A2A 报文映射为密封事件（业务永不接触 A2A 类型），映射源与 `Feat-Func-009` 报文结构一致：

| A2A 报文（对齐 Feat-Func-009） | 归一化事件 |
|---------|-----------|
| 首个响应 / `result.task`（新建） | `Accepted(invocationRef)`（内部记录 taskRef、contextId） |
| `result.statusUpdate` / `result.task.status`（`TASK_STATE_*`） | `StatusChanged(invocationRef, TaskState, terminal)` |
| `Message` / `Artifact` 文本 part | `ContentDelta(invocationRef, text)` |
| `TASK_STATE_INPUT_REQUIRED` + `_interrupt`（`_interrupt_kind=client_tool`） | `InputRequired(invocationRef, ToolCall)`（见 Feat-Func-007） |
| `TASK_STATE_COMPLETED` | `Completed(invocationRef, summary)` |
| `TASK_STATE_FAILED` / JSON-RPC error | `Failed(invocationRef, errorCode, message)` |

> `TaskState` 枚举对齐 A2A（`TASK_STATE_SUBMITTED`/`WORKING`/`INPUT_REQUIRED`/`COMPLETED`/`FAILED`/`CANCELED`/`REJECTED`）；未识别值映射 UNKNOWN。

### 3.4 状态投影 + 两类"续跑"的区分

`TaskState` 是服务端状态在 client 侧的**只读投影**：

```
SUBMITTED → WORKING → (INPUT_REQUIRED ⇄ WORKING)* → COMPLETED / FAILED / CANCELED / REJECTED
```

**INPUT_REQUIRED 有两种成因，处理路径不同：**

| 成因 | 触发 | 处理 | 是否新 invocation |
|------|------|------|------------------|
| **端侧工具**（`_interrupt_kind=client_tool`） | 模型要调用本地工具 | SDK 经治理入口执行并**内部恢复请求**续跑同一 Task（Feat-Func-007） | 否（内部恢复请求） |
| **用户输入**（人工补充） | 需用户补充信息/确认 | 业务侧感知，调用 `continueInput` 发**新 invocation** 关联旧 invocation | 是（业务可见新 invocation） |

> 恢复点校验与 Task 推进由 runtime 控制；关联不可续接/过期/多义 → client 返回明确错误。

### 3.5 wire 契约（client↔gateway，对齐 Feat-Func-009）+ 标识映射

**标识映射（FEAT-006 业务标识 → A2A wire）**

| FEAT-006 标识 | 主权 | A2A wire 字段 | 说明 |
|--------------|------|--------------|------|
| `conversationId` | 业务应用 | `message.contextId` | 同 conversation 稳定传递 |
| `invocationId` / `idempotencyKey` | agent-client | `message.messageId` | 创建幂等键；UNKNOWN 重发复用 |
| `invocationRef` | agent-client | 本地句柄（不上 wire） | 内部映射到 taskRef |
| `taskRef` | agent-runtime | `message.taskId` / `result.task.id` | 内部/诊断，不对业务暴露 |

**① 创建（`SendStreamingMessage`/`SendMessage`，无 taskId）**

```json
{
  "jsonrpc": "2.0", "id": "req-1", "method": "SendStreamingMessage",
  "params": {
    "message": {
      "role": "ROLE_USER", "messageId": "msg-1", "contextId": "conv-1",
      "parts": [{"text": "帮我看看当前页面并建单"}]
    },
    "metadata": { "clientTools": [ /* = ToolView 投影，见 Feat-Func-007 §3.5 */ ] }
  }
}
```

**② 等待端侧工具**：Task 进入 `TASK_STATE_INPUT_REQUIRED`，`status.message.metadata._interrupt` 携带 `toolName`/`toolCallId`/`context.arguments`/`_interrupt_kind`（结构见 Feat-Func-007 §3.5 与 `Feat-Func-009` §2.3.4）。SSE 帧为 `event: jsonrpc` + 完整 JSON-RPC `data`。

**③ 提交结果并续跑（`SendMessage`，带原 taskId + 普通 TextPart）**

```json
{
  "jsonrpc": "2.0", "id": "req-2", "method": "SendMessage",
  "params": { "message": {
    "role": "ROLE_USER", "messageId": "msg-2", "taskId": "task-123", "contextId": "conv-1",
    "parts": [{"text": "页面正文……"}]
  } }
}
```

- 结果以**普通 TextPart observation 文本**回传，`message.taskId` 是唯一关联字段，**不回传 `toolCallId`**（V1 单 pending，见 Feat-Func-007）。

**④ 查询（`GetTask`）**：`{"method":"GetTask","params":{"taskId":"task-123"}}`（client 内部用 invocationRef→taskRef 解析）。

---

## 4. 代码结构（参照原型包）

```
com.huawei.ascend.client
├── api/
│   ├── AgentClient.java            # 业务入口（invocationRef 操作面）
│   ├── AgentClients.java           # builder 工厂
│   ├── InvocationRequest.java      # 调用请求（conversationId/mode/invocationId/幂等键…）
│   ├── ContinueInputRequest.java   # 继续等待输入（关联旧 invocation）
│   ├── InvocationMode.java         # BLOCKING/STREAMING/ASYNC
│   ├── InvocationCall.java         # 本地句柄：invocationRef + accepted/events/completion
│   ├── InvocationEvent.java        # sealed 事件层次 + ToolCall
│   ├── TaskState.java              # Task 状态投影 enum（isTerminal）
│   └── InvocationSnapshot.java     # invocation 只读快照
├── transport/spi/
│   └── TransportProvider.java      # 传输抽象（createAndStream/getTask/cancel/resubscribe/resumeToolResult）
├── transport/fake/
│   └── InProcessFakeGateway.java   # 测试用：SubmissionPublisher 模拟 A2A 多轮
└── internal/
    └── DefaultAgentClient.java     # core 编排 + invocationRef→taskRef 受治理映射
```

---

## 5. 运行流程

### 5.1 主流程（成功多轮）

见 §3.2–3.5 与 Feat-Func-007 §5 的多轮时序；本特性负责"接受 → 事件归一化 → 状态投影 → 终态结算"。

### 5.2 断线补偿与降级

```
SSE 断开
  ├── resubscribe(invocationRef)：若 gateway/runtime 支持 tasks/resubscribe → 续接事件流
  └── 否则轮询 getInvocation(invocationRef)（内部 GetTask）→ InvocationSnapshot
        → 达终态则结算 completion；仍 INPUT_REQUIRED 则据 pendingToolCall 继续驱动（Feat-Func-007）
```

> V1 若 gateway/runtime 尚未提供 `tasks/resubscribe`，`resubscribe` 内部降级为轮询——属 transport 层内部行为，不改公共 API（缺口转述见 §8 G-6）。

### 5.3 错误、取消、降级（对齐 FEAT-006 §5.1.6）

| 错误场景 | 触发 | 行为 | 对外结果 |
|---------|------|------|---------|
| 网络失败 | 断网/超时 | 回显幂等键与调用关联供恢复判断 | 可重试网络错误 |
| 路由失败 | route not found / 无权限 / 不可用 | 暴露平台错误 | route/permission/service 错误 |
| 服务端失败 | A2A/Task error | 展示 failed invocation 投影 | 不包装成网络失败 |
| 接受未确认 | 不知是否建 Task | 回显 invocationRef+幂等键+恢复线索 | `UNKNOWN`（非成功/失败） |
| 已接受未完成 | 已建未终态 | 回显 invocation 状态投影 | `ACCEPTED_WITH_INVOCATION` |
| SSE 中断 | 流断 | 提示重订阅/查询确认进展 | 不判定 Task 失败 |
| 流式不可用 | 链路不支持 STREAMING | 不静默降级 | 明确错误或显式降级并回显实际模式 |
| 关联不可续接 | continueInput 关联过期/多义/终态 | 返回明确错误 | 不偷偷新建普通任务 |
| 取消 | `cancel(invocationRef)` | 内部 `CancelTask`；是否立即中断由 runtime 决定 | 返回快照/确定错误 |
| 未知状态 | 服务端新增 TaskState | 映射 UNKNOWN，不崩溃 | 由查询兜底细化 |

---

## 6. 配置与使用

### 6.1 构造与调用（参照原型；符号名以 FEAT-006 术语为准）

```java
AgentClient client = AgentClients.builder()
        .transport(transportProvider)   // 生产：A2A over HTTP/SSE 连 gateway；测试：InProcessFakeGateway
        .eventListener(event -> log(event))
        .build();

InvocationCall call = client.invoke(InvocationRequest.builder()
        .agentId("support-agent")
        .conversationId("conv-1")
        .mode(InvocationMode.STREAMING)
        .input("...")
        .build());
Handle handle = call.accepted().toCompletableFuture().get();     // handle.invocationRef()
InvocationSnapshot terminal = call.completion().toCompletableFuture().get();
```

### 6.2 关键构造项

| 构造项 | 类型 | 默认 | 说明 |
|-------|------|------|------|
| `transport` | `TransportProvider` | 无（必填） | wire 绑定（gateway 端点/鉴权）；隔离 OS/网络差异 |
| `stateStore` | `ClientStateStore` | 内存实现 | 见 Feat-Func-007（结果 outbox / ACK） |
| `eventListener` | `Consumer<InvocationEvent>` | no-op | 观测/日志/指标 |

> 租户不由 SDK 自证；鉴权与租户注入在 gateway 完成（§8 G-3）。

---

## 7. 当前限制

| 限制 | 影响范围 | 临时方案 |
|------|---------|---------|
| 无生产实现 | 仅原型 + fake gateway；A2A HttpClient adapter 待实现 | 以原型验证 core 逻辑 |
| 原型符号名滞后 | 原型仍用 `taskId`/`clientInvocationId` 旧签名 | 正文已按 FEAT-006 术语；原型机械跟随（§9） |
| 取消/重订阅 wire 缺口 | FEAT-006 要求 MUST，但 `Feat-Func-009` 范围只含 Send/Get | 保留 API；缺口转述见 §8 G-6 |
| 创建幂等依赖 gateway | A2A/009 未定义创建去重 | 见 §8 G-6；未落地则不自动重试创建 |
| 内存态投影 | 不承诺跨进程重启恢复 | 换 `ClientStateStore` 持久化实现 |

---

## 8. 对 gateway 的要求（转述给 gateway 负责同事）

> gateway 的设计文档尚未上传。**总原则：gateway 是受治理的 A2A 透传代理，对 client 暴露的 A2A 语义必须与 `Feat-Func-009` 对 runtime 定义的完全一致，不得改写。**

| 编号 | 要求 | 说明 / 理由 |
|------|------|------------|
| **G-1 A2A 兼容入口** | 暴露标准 A2A JSON-RPC 端点（`SendMessage`/`SendStreamingMessage`/`GetTask` over HTTP POST），逐字段透传到目标 runtime | 不得改动 `result.task`/`result.statusUpdate`/`TASK_STATE_*`/`_interrupt` 语义与 `contextId`/`messageId`/`taskId` 字段 |
| **G-2 Agent 发现与 Card url 改写** | 暴露 Agent Card 发现入口，把 Card `url` 改写为 gateway 自身地址 | 防止 client 直连 runtime 绕过治理 |
| **G-3 认证与租户注入** | 完成鉴权，从凭据解析租户注入下游；**丢弃** client 自报租户 | client 不自证租户 |
| **G-4 按 agentId 路由** | 依据 `agentId` 路由到目标 runtime | — |
| **G-5 粘滞路由（关键）** | 带 `taskId` 的续跑/查询/取消必须路由到**持有该 Task 的同一 runtime 实例** | 009 的 pending `_interrupt` 存实例级 TaskStore；错误实例会致 resume 失败/Task 挂起 |
| **G-6 创建幂等 + 取消 + 重订阅（client 诉求）** | ①按 `同租户+同 messageId` 去重创建；②FEAT-006 要求 MUST 的 **取消/重订阅** 需 gateway+runtime 补齐 `CancelTask`/`tasks/resubscribe`（超出 009 范围） | 缺失时分别降级为"不自动重试创建""取消/重订阅不可用" |
| **G-7 错误分层** | 治理层错误（401/403/404/413/429/503）以 HTTP + 稳定 error body 暴露，与 A2A JSON-RPC error 分层 | client 两层都处理 |
| **G-8 SSE 逐帧透传** | 保持 runtime SSE 帧（`event: jsonrpc` + 完整 JSON-RPC `data`）逐帧透传，不缓冲、不改写 `_interrupt` | 保证事件归一化与断线语义 |
| **G-9 TLS 与配额声明** | 声明 TLS、请求正文上限、事件大小上限 | client 据此约束工具目录/结果大小（见 Feat-Func-007 §8） |

> 端侧工具多轮相关的 gateway 要求见 Feat-Func-007 §8。

---

## 9. 与 version-scope FEAT-006 的一致性与落地映射

> 正文已按 FEAT-006 对齐（invocationRef 操作面、调用模式、conversationId、取消/重订阅 MUST、错误分类、术语）。剩余仅为**参照原型代码**的机械跟随项，不影响本 L2 提交。

| 项 | FEAT-006 要求 | 本文状态 | 落地项（原型代码待改） |
|---|--------------|---------|----------------------|
| 操作句柄 | 只用 `invocationRef` | ✅ 已对齐 | 原型 `getTask(taskId)`/`cancelTask(taskId)` → `getInvocation/cancel(invocationRef)` |
| 调用模式 | 创建声明 BLOCKING/STREAMING/ASYNC | ✅ 已对齐 | 原型 `InvocationRequest` 增加 `mode` 字段与三路消费 |
| conversationId | 必传、业务主权 | ✅ 已对齐 | 原型 `InvocationRequest` 增加 `conversationId`（→ contextId） |
| 两类续跑区分 | 用户输入=新 invocation；工具结果=内部恢复 | ✅ 已对齐（§3.4） | 原型补 `continueInput` 入口 |
| 取消/重订阅 | MUST | ✅ 定位已对齐 | wire 缺口转 gateway/runtime（§8 G-6） |
| 术语 | conversationId/invocationRef/taskRef/UNKNOWN | ✅ 已对齐 | 原型符号名机械替换 |
| 标识映射 | — | ✅ §3.5 给出 conversationId↔contextId、invocationId/幂等键↔messageId、taskRef↔taskId | transport adapter 实现映射 |
