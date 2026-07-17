---
level: L2-LLD
module: agent-client
feature_type: functional
feature_id: Feat-Func-002
status: proposed
authority: non-authoritative
dependency:
  - ../../L1-High-Level-Design/agent-client/overview.md
  - ../../L1-High-Level-Design/agent-client/logical.md
  - ../../L1-High-Level-Design/agent-client/process.md
  - ../../L1-High-Level-Design/agent-client/development.md
  - ./Feat-Func-001-standardized-agent-invocation.md
  - ../agent-runtime/Feat-Func-009-调用端侧工具响应-新增支持带有端侧工具的请求.md
  - ../../../agent-client/docs/proposals/agent-client-v1-design.md
  - ../../../agent-client/examples/cloud-client/README.md
---

# 本地工具注册与远端驱动调用 — 设计文档

> 目标模块：`agent-client`（edge plane SDK）
> 参照实现：`agent-client/examples/cloud-client/`（可运行原型，JDK 17，已自校验通过）
> 最后更新：2026-07-17
> **⚠️ 状态：proposed / non-authoritative。** 本文是待评审的实现级设计，不是已接受实现事实。
> **🔗 wire 基线：** 端侧工具多轮语义**对齐 runtime 已在建的 `agent-runtime/Feat-Func-009`**：工具目录走 `params.metadata.clientTools`（`name`/`description`/`inputSchema`）；调用意图走 Task `status.message.metadata._interrupt`（`_interrupt_kind=client_tool`）；结果走**普通 TextPart observation 文本**回传，V1 单 pending、不回传 `toolCallId`。**唯一保留差异是拓扑：client 连 gateway 受治理透传到 runtime**（见 §8）。SDK 内部保留结构化 `ToolResult`/`Outcome` 等模型，仅在回传前渲染为 observation 文本，不上 wire。

---

## 1. 概述

### 1.1 特性定位

为端侧提供**本地工具的标准化 SPI 与注册管理**，使远端智能体能经**多轮请求**驱动调用客户端本地能力：远端把模型选中的端侧工具转为 `INPUT_REQUIRED` + `_interrupt` 意图 → SDK 经治理入口在本地执行工具 → 把结果作为下一轮请求带原 `taskId` 以 TextPart 回传续跑，直至任务完成。

- **解决的问题**：智能体需要客户端本地能力（读本地上下文、拍照、写业务系统等），但不能让服务端透明调用本地函数，也不能在客户端暴露 webhook 回调入口。本特性把本地能力表达为**可声明、可授权、可审计、可去重**的 capability，由 client 主动多轮回传结果。
- **适用场景**：需要观测本地环境或执行本地动作的智能体任务。**不适用**：纯服务端可完成、无需客户端参与的任务。

### 1.2 当前事实边界

本文描述 Feat-Func-002 的**拟议设计**。类型/流程以参照原型为准，符号名待评审固化。本地能力的 Observation/Action 分类是**客户端侧治理声明位**，不上 wire、也不是技术沙箱（企业可信开发者场景，见 `agent-client-v1-design.md` 附录 A.5）。wire 报文语义以 §3.5 为准，并与 `Feat-Func-009` 一致。

### 1.3 设计原则

1. **执行 ≠ 交付 ACK** — 先本地执行、把结果写 outbox，再回传；"服务端已接收"以回传成功响应为准。二者分离是幂等的基石。
2. **同一 toolCallId 只执行一次（本地去重）** — `toolCallId` 由服务端在 `_interrupt` 中给出、跨重投/重连稳定，是**本地执行去重键**；重复投递/重连重放不得重复执行有副作用的工具。注意：V1 提交结果时**不回传** `toolCallId`（runtime 按单 pending 自动关联），它仅用于 client 本地去重与诊断。
3. **治理骨架不可绕过** — schema 校验 → 策略 → 审批 → 去重 → 执行 → 结果 outbox 的**顺序与卡点存在**由 SDK 强制；卡点里"判断什么"由业务实现（平台能力 vs 业务策略的边界）。
4. **结果以 observation 文本上 wire** — 对齐 `Feat-Func-009`：回传是普通 TextPart 文本，无结果状态枚举、无 DataPart。SDK 内部保留结构化 `ToolResult`（含 `Outcome`），在回传前**渲染成明确的成功/拒绝/错误文本**（§3.4）。`Outcome` 是客户端模型，不与服务端做 wire 级对齐。
5. **不做沙箱** — 用进程内护栏（有界执行器 + deadline + 异常边界）防止工具拖垮宿主；强隔离属宿主部署能力。

### 1.4 子特性全景

| 子特性 | 职责 | 关键抽象 | 状态 |
|--------|------|---------|------|
| 工具 SPI 与描述 | 声明本地能力、副作用类别、schema | `LocalTool`, `ToolDescriptor`(`SideEffect`) | ⬜ proposed |
| 注册管理 | register/replace、冲突策略、解析 | `LocalToolRegistry` | ⬜ proposed |
| 工具目录声明 | 注册表 → `params.metadata.clientTools` | `ToolDescriptor` → clientTools 映射 | ⬜ proposed |
| 治理骨架 | 策略/审批卡点 | `Governance`(`PolicyGuard`/`ApprovalProvider`) | ⬜ proposed |
| 多轮驱动 | INPUT_REQUIRED(`_interrupt`) → 执行 → TextPart 回传续跑 | `AgentClient#executeAndResume`, `TransportProvider#resumeToolResult` | ⬜ proposed |
| 去重与幂等 | toolCallId 本地去重、结果 outbox、ACK | `ToolDispatcher`, `ClientStateStore` | ⬜ proposed |
| 结果归一化与渲染 | 成功/失败模型 → observation 文本 | `ToolResult`(`Success`/`Failure`/`Outcome`) | ⬜ proposed |

---

## 2. 特性规格

### 2.1 能力清单

| 能力 | 状态 | 说明 |
|------|------|------|
| 工具声明与注册 | ⬜ | `LocalTool` + `ToolDescriptor`；register 冲突拒绝、replace 覆盖 |
| 副作用分类（客户端侧） | ⬜ | `OBSERVATION`（只读）/`ACTION`（有副作用，强治理）；不上 wire |
| 工具目录声明 | ⬜ | 注册表映射为 `clientTools[{name,description,inputSchema}]`（对齐 009） |
| schema 校验 | ⬜ | 原型用必填参数键；生产用 input JSON Schema（= `inputSchema`） |
| 策略卡点 | ⬜ | `PolicyGuard`：ALLOW/DENY/REQUIRE_APPROVAL |
| 审批卡点 | ⬜ | `ApprovalProvider`：Action 高危操作人工确认 |
| toolCallId 本地去重 | ⬜ | outbox + in-flight 双重去重，恰好执行一次 |
| deadline/超时 | ⬜ | 协作式超时 → 渲染为超时文本（放弃等待 ≠ 杀线程） |
| 结果回传 ACK | ⬜ | 回传成功后标记 acked；此后只重投不重跑 |
| SDK 自动编排多轮 | ⬜ | 业务不直调 handler，SDK 收 INPUT_REQUIRED 自动经治理入口执行并续跑 |
| 重连重放幂等 | ⬜ | 同一 toolCallId 重复投递只执行一次（原型已验证） |
| 单 pending 串行 | ⬜ | 对齐 009：同一时刻只有一个 pending 端侧工具；不支持并行 |

### 2.2 显式排除

| 排除项 | 原因 | 替代 |
|--------|------|------|
| 工具沙箱 / 任意代码执行 | 一个 Java 库做不了可靠 OS 沙箱；强隔离属宿主 | 进程内护栏；需强隔离时跑独立进程/容器 |
| 服务端透明调用本地函数 | 违反 edge 治理与不可信 caller 原则 | 声明式 capability + client 主动多轮回传 |
| 客户端 webhook 回调入口 | 安全面/网络可达性风险 | 折叠为 Task 待执行意图 |
| 结构化结果 / 结果枚举 / DataPart 上 wire | `Feat-Func-009` V1 只接受 TextPart observation | SDK 内部结构化，回传前渲染为文本（差异清单见 §9） |
| 并行多工具 | 009 V1 单 pending | 串行多轮 |
| 强杀运行中工具 | `Thread.stop()` 已废弃，不安全 | 协作式取消/超时 |

### 2.3 接口契约（Logical View）

```java
/** 业务实现的本地能力执行 SPI（只见 SDK 自有类型，不见 A2A/HTTP）。 */
public interface LocalTool {
    ToolDescriptor descriptor();
    CompletionStage<ToolResult> execute(ToolInvocation invocation, ToolExecutionContext context);
}

/** 治理入口：SDK 内部统一经此执行并回传；同一 toolCallId 只执行一次。 */
public interface AgentClient {
    CompletionStage<InvocationSnapshot> executeAndResume(String taskId, InvocationEvent.ToolCall toolCall);
    LocalToolRegistry tools();
}

/** 结果为客户端侧模型；回传前由 SDK 渲染成 observation 文本（不上 wire 的枚举）。 */
public sealed interface ToolResult permits ToolResult.Success, ToolResult.Failure {
    enum Outcome { OK, ERROR, REJECTED, TIMEOUT }
    Outcome outcome();
}
```

#### 数据类型

| 类型 | 关键字段 | 含义 | 约束 |
|------|---------|------|------|
| `ToolDescriptor` | `toolId`, `version`, `sideEffect`, `requiredArgumentKeys`, `timeout` | 工具公开描述 | `sideEffect` 决定客户端治理强度（不上 wire）；`toolId` 映射 wire `name` |
| `ToolInvocation` | `toolCallId`, `arguments`, `deadline` | 一次远端驱动调用（源自 `_interrupt`） | `toolCallId` 是本地幂等/去重键 |
| `ToolResult` | `outcome` + Success/Failure | 客户端归一化结果 | 回传前渲染为 observation 文本 |
| `ToolExecutionContext` | `tenantId`, `traceId`, `deadline` | 执行上下文 | 由 SDK 组装传播（tenant 以 gateway 注入为权威） |

#### 行为承诺

- **必须**：同一 `toolCallId` 恰好执行一次；重复投递/重连重放返回同一结果，不重执行。
- **必须**：先写结果 outbox 再回传；回传成功才标记 acked。
- **必须**：策略/审批/去重卡点顺序固定，业务不可绕过。
- **必须**：拒绝/错误/超时均渲染为**明确的 observation 文本**回传（对齐 009 §2.3.3）。
- **禁止**：用 TextPart 承载工具定义（工具只走 `metadata.clientTools`）；公共 API 出现 A2A/HTTP 类型。
- **允许**：`PolicyGuard`/`ApprovalProvider` 由业务自定义判据（自动放行、弹窗确认等）。

---

## 3. 核心实现（参照原型）

### 3.1 治理骨架（ToolDispatcher 管道）

```
executeAndResume(taskId, toolCall)     ← toolCall 由 _interrupt 归一化而来
  │
  ▼ ToolDispatcher.dispatch(invocation, ctx)   ← 顺序不可绕过
  ├─ 1. outbox 去重：store.findToolResult(toolCallId) 命中 → 复用，绝不重执行
  ├─ 2. in-flight 去重：同一 toolCallId 并发只跑一次（putIfAbsent）
  ├─ 3. 解析工具：registry.resolve(toolName) → toolId，缺失 → REJECTED(TOOL_NOT_REGISTERED)
  ├─ 4. schema 校验：必填参数键缺失 → REJECTED(SCHEMA_INVALID)
  ├─ 5. PolicyGuard.check → ALLOW / DENY(→REJECTED) / REQUIRE_APPROVAL
  ├─ 6. REQUIRE_APPROVAL → ApprovalProvider.requestApproval → 未批 → REJECTED(APPROVAL_REJECTED)
  ├─ 7. 执行：tool.execute(...).orTimeout(deadline) → 超时 TIMEOUT / 异常 ERROR
  └─ 8. 结果落 outbox（store.saveToolResult），并渲染为 observation 文本
  │
  ▼ transport.resumeToolResult(ResumeCommand(taskId, messageId, observationText))
  └─ 成功响应 = 交付 ACK → store.markAcked(toolCallId)  ← 此后只重投不重跑
```

> `toolCallId` 只在 client 本地用于去重/诊断，**不进入** resume 报文（V1 单 pending，runtime 自动关联，见 §3.5）。

### 3.2 副作用分类与差异化治理（客户端侧）

| 类别 | 语义 | 治理重点 | 原型示例 |
|------|------|---------|---------|
| `OBSERVATION` | 只读观测业务环境 | 数据范围、脱敏、租户隔离、时效 | `customer.profile.read`, `device.camera.capture`（占位） |
| `ACTION` | 修改业务环境/触发副作用 | 授权、审批、幂等、审计、回滚 | `ticket.create`（策略要求审批） |

> SDK 无法从技术上强制回调实现符合声明（函数体由开发者写），且 `sideEffect` **不上 wire**。分类的价值在于**在客户端侧按声明施加差异化治理并留审计痕迹**，不是沙箱、也不透传给服务端。

### 3.3 执行与交付 ACK 分离（幂等基石）

```
本地执行成功 ──写 outbox──► [结果已生成，未确认送达]
        │                              │
   网络在此断开 ────────────────────────┤  重试只重投同一结果文本
        ▼                              ▼
   回传成功响应 ──markAcked──► [服务端已接收]
```

任何时候只要 outbox 有结果，dispatch 直接复用；重投由 runtime 的单 pending 关联幂等推进（同一 Task 的 pending ToolCall 已被消费则不再重复续跑）。

### 3.4 结果 → observation 文本渲染

对齐 `Feat-Func-009` §2.3.3：SDK 把结构化 `ToolResult` 渲染成明确的 observation 文本再上 wire。

| 本地情形 | ToolResult（客户端模型） | 回传 TextPart（observation 文本，示例） |
|---------|-----------|-------------|
| 成功 | `Success(output)` | 结果摘要 / JSON 文本，如 `页面正文……` 或 `{"ticketId":"TK-8801"}` |
| 策略/审批拒绝 | `Failure(REJECTED, code)` | `客户端拒绝执行该工具：用户取消了操作。` |
| 执行抛错 | `Failure(ERROR, code)` | `客户端工具执行失败：本地插件不可用。` |
| 超过 deadline | `Failure(TIMEOUT)` | `客户端工具执行超时：未在限定时间内完成。` |

> 大结果不应无限内联 TextPart；应回传受治理对象引用 + 必要摘要（对齐 009 §2.3.3）。

### 3.5 wire 契约（对齐 Feat-Func-009）

**① 工具目录声明（创建请求 `params.metadata.clientTools`）**

SDK 把注册表中本次可用工具映射为 `clientTools` 数组（`toolId`→`name`；`requiredArgumentKeys`/schema→`inputSchema`）。`version`/`sideEffect`/`timeout`/`output_schema` 是客户端本地字段，**不上 wire**。

```json
{
  "clientTools": [
    {
      "name": "customer.profile.read",
      "description": "读取当前客户档案",
      "inputSchema": {
        "type": "object",
        "properties": { "customerId": {"type": "string"} },
        "required": ["customerId"],
        "additionalProperties": false
      }
    }
  ]
}
```

**② 调用意图（Task `status.message.metadata._interrupt`）**

```json
{
  "_interrupt": {
    "toolName": "customer.profile.read",
    "toolCallId": "call-123",
    "context": { "_interrupt_kind": "client_tool", "arguments": { "customerId": "C-9" } },
    "message": "Client tool invocation required: customer.profile.read",
    "payload": { "...": "保真/诊断字段，client 优先用提升字段，不依赖内部序列化" }
  }
}
```

SDK 归一化映射：`toolName`→`toolId`（经注册表解析）、`toolCallId`→本地去重键、`context.arguments`→`ToolInvocation.arguments`。只处理 `_interrupt_kind=client_tool`（区别于人工输入/远程 A2A delegate）。

**③ 结果回传（`SendMessage`，原 taskId + 普通 TextPart）**

```json
{
  "message": {
    "role": "ROLE_USER",
    "messageId": "msg-2",
    "taskId": "task-123",
    "contextId": "ctx-1",
    "parts": [{"text": "{\"name\":\"张三\",\"level\":\"VIP\"}"}]
  }
}
```

- 结果只走 TextPart；**不回传 `toolCallId`、无结果枚举、无 DataPart**（V1 单 pending，runtime 自动关联）。

---

## 4. 代码结构（参照原型包）

```
com.huawei.ascend.client
├── tool/spi/
│   ├── LocalTool.java              # 本地能力执行 SPI（+ of(...) 便捷工厂）
│   ├── ToolDescriptor.java         # 工具描述 + SideEffect(OBSERVATION/ACTION)（客户端侧）
│   ├── ToolInvocation.java         # 一次调用（toolCallId 本地去重键，源自 _interrupt）
│   ├── ToolResult.java             # sealed：Success/Failure + Outcome（客户端模型）
│   ├── ToolExecutionContext.java   # tenant/trace/deadline
│   └── LocalToolRegistry.java      # 注册表（register 冲突/replace 覆盖）
├── spi/
│   └── Governance.java             # PolicyGuard / ApprovalProvider + 决策类型
├── state/spi/
│   └── ClientStateStore.java       # 结果 outbox / ACK（可替换）
└── internal/
    ├── ToolDispatcher.java         # 治理骨架 + 去重管道 + 结果渲染（本特性核心）
    ├── DefaultToolRegistry.java    # 注册表实现
    └── InMemoryStateStore.java     # 内存 outbox（MVP）
```

### 核心类静态关系

```
DefaultAgentClient
   │ executeAndResume（收到 _interrupt 归一化的 ToolCall）
   ▼
ToolDispatcher ── uses ──► DefaultToolRegistry ── resolves(toolName) ──► LocalTool（业务实现）
   │  ├── uses ──► Governance.PolicyGuard / ApprovalProvider（业务实现）
   │  └── uses ──► ClientStateStore（outbox/ACK）
   ▼
TransportProvider.resumeToolResult（带 taskId + observation 文本续跑，见 Feat-Func-001 §3.5）
```

---

## 5. 运行流程

### 5.1 多轮驱动主流程（SDK 自动编排）

```
用户 → 业务应用            agent-client SDK              gateway (受治理透传)        runtime (A2A, Feat-Func-009)
  │  invoke ───────────────►│                               │                          │
  │                         │ SendStreamingMessage          │                          │
  │                         │  + metadata.clientTools ─────►│──────── 透传 ───────────►│
  │                         │◄──── Accepted(taskId) ────────│◄─────────────────────────│
  │                         │◄─ INPUT_REQUIRED + _interrupt │◄──── _interrupt ─────────│  (client_tool)
  │                         │  ToolDispatcher: 校验/策略/审批/去重/执行/outbox/渲染文本
  │                         │ SendMessage(taskId, TextPart) │                          │
  │                         │  ── 结果 observation 文本 ───►│──────── 透传 ───────────►│  自动关联 pending ToolCall
  │                         │◄──── WORKING ─────────────────│◄─────────────────────────│  markAcked
  │                         │◄─ INPUT_REQUIRED + _interrupt2│  ...（重复直至无待办工具）
  │                         │◄──── COMPLETED ───────────────│◄─────────────────────────│
  │◄── completion() 结算 ────│                               │                          │
```

业务无需自己订阅并调用 handler；SDK 收到 `INPUT_REQUIRED` 即经**治理入口**执行并续跑（原型 `DefaultAgentClient` 内部订阅驱动）。

### 5.2 重连重放幂等（原型已验证）

```
gateway/runtime 重复投递 INPUT_REQUIRED(_interrupt.toolCallId=call-0)   ← 模拟 SSE 断线重连 / GetTask 重观察
  → 第 1 次：执行工具（count=1），resume 推进
  → 第 2 次：dispatch 命中 outbox（按 toolCallId）→ 复用结果，不重执行；
             resume 命中 runtime 单 pending 关联幂等 → 不重复推进
结果：工具恰好执行一次（原型断言 [PASS]）
```

> `_interrupt.toolCallId` 跨重投稳定，是本地去重键；断线后 `GetTask` 返回同一 pending，client 据其 `toolCallId` 判定"已执行则复用"。

### 5.3 错误、取消、降级

| 错误场景 | 触发条件 | 行为 | 回传 observation 文本 |
|---------|---------|------|---------|
| 工具未注册 | `toolName` 无匹配 | 不执行 | `客户端拒绝执行该工具：工具未注册。` |
| 参数不合 schema | 必填键缺失 | 不执行 | `客户端拒绝执行该工具：参数不合法。` |
| 策略拒绝 | PolicyGuard DENY | 不执行 | `客户端拒绝执行该工具：策略不允许。` |
| 审批否决 | ApprovalProvider 未批 | 不执行 | `客户端拒绝执行该工具：用户未批准。` |
| 执行超时 | 超过 deadline | 放弃等待 | `客户端工具执行超时：……` |
| 执行异常 | 工具抛错 | catch 成结构化失败 | `客户端工具执行失败：……` |
| 结果重投 | 回传前后断网 | 复用 outbox 结果文本重投 | 不重执行、不重推进 |

---

## 6. 配置与使用

### 6.1 注册与治理（参照原型）

```java
AgentClient client = AgentClients.builder()
        .transport(transport)   // A2A over HTTP/SSE 连 gateway
        .policyGuard((descriptor, inv, ctx) -> completed(
                descriptor.sideEffect() == SideEffect.ACTION
                        ? PolicyDecision.requireApproval("确认执行：" + descriptor.toolId())
                        : PolicyDecision.allow()))
        .approvalProvider((descriptor, inv, prompt) -> completed(ApprovalDecision.approved("auto")))
        .build();

client.tools().register(LocalTool.of(
        ToolDescriptor.builder().toolId("ticket.create").version("1")
                .sideEffect(SideEffect.ACTION)
                .requiredArgumentKeys(Set.of("customerId", "summary")).build(),
        (inv, ctx) -> completed(ToolResult.ok(Map.of("ticketId", "TK-8801")))));
```

### 6.2 关键扩展点

| SPI | 默认 | 业务通常自定义什么 |
|-----|------|------------------|
| `LocalTool` | 无 | 具体本地能力（读/写/设备） |
| `PolicyGuard` | 全放行 | 权限、数据范围、副作用约束判据 |
| `ApprovalProvider` | 自动批准 | 弹出确认 UI / 审批流 |
| `ClientStateStore` | 内存 | 需重启恢复时换文件/DB outbox |

---

## 7. 当前限制

| 限制 | 影响范围 | 临时方案 |
|------|---------|---------|
| 结果只文本 | 无法回传结构化结果/明确 outcome 枚举给服务端 | SDK 渲染明确文本；结构化属 V1.1 差异（§9） |
| 单 pending | 一次只处理一个端侧工具，不支持并行 | 串行多轮（对齐 009） |
| wire 无 `toolCallId` 回传 | 依赖 runtime 单 pending 自动关联 | client 本地仍以 toolCallId 去重 |
| 无沙箱 | 不可信代码不能安全隔离 | 进程内护栏；强隔离跑独立进程/容器 |
| 内存 outbox | 不承诺跨进程重启恢复 | 换持久化 `ClientStateStore` |
| schema 校验为最小实现 | 原型仅校验必填键 | 生产接 JSON Schema 校验（`inputSchema`） |
| 工具版本不上 wire | `clientTools` 无 version 字段 | 同名多版本由 client 本地消歧 |

---

## 8. 对 gateway 的要求（转述给 gateway 负责同事）

> 通用 gateway 要求（A2A 兼容入口、Card url 改写、鉴权/租户注入、agentId 路由、**粘滞路由**、错误分层、SSE 逐帧透传、创建幂等/取消）见 Feat-Func-001 §8。以下是**端侧工具多轮**特有的透传要求。

| 编号 | 要求 | 说明 / 理由 |
|------|------|------------|
| **GT-1 clientTools 透传** | 创建请求的 `params.metadata.clientTools` 必须**原样透传**给 runtime，不吞掉、不改写、不重排 | runtime 据此注入本次模型工具视图（009 §2.3.2）；缺失则端侧工具不可见 |
| **GT-2 `_interrupt` 透传** | runtime 的 `status.message.metadata._interrupt`（含提升字段 `toolName`/`toolCallId`/`context.arguments`/`_interrupt_kind`）必须原样透传给 client | client 据提升字段归一化为 `ToolCall`；不得删减这些字段 |
| **GT-3 TextPart 结果透传** | client 用原 `taskId` 的 `SendMessage` + 普通 TextPart 回传，gateway 原样转发，不得丢弃/改写 TextPart，不得要求结构化 | 现网 runtime 曾有"丢非文本 part"风险；结果走文本可规避，但 gateway 也不得反向丢文本 |
| **GT-4 粘滞路由（工具续跑尤其依赖）** | 带 `taskId` 的结果回传必须路由到**持有该 Task pending ToolCall 的同一 runtime 实例** | 见 Feat-Func-001 §8 G-5；工具多轮对会话亲和性零容忍 |
| **GT-5 正文/事件配额声明** | `clientTools` 目录计入创建请求正文上限；结果 TextPart 计入事件/正文上限；超限策略（拒绝/引用）需 gateway 声明 | client 据此约束目录大小与结果内联，改用受治理对象引用 |
| **GT-6 保持单 pending 语义** | 不得由 gateway 侧合并/拆分多个 `_interrupt` 或伪造并行 pending | 对齐 009 V1 单 pending；并行属未来 V1.1（§9） |

---

## 9. 与 Feat-Func-009 的差异 / 客户端增强诉求（未来 V1.1，需 runtime+gateway 共同升级）

> 以下能力在本 V1 设计中**已按 009 对齐裁剪掉 wire 表达**，仅保留在 client 本地模型。如需上 wire，须与 runtime、gateway 共同评审升级，属 V1.1，不阻塞本次提交。

| 诉求 | 现状（V1 对齐 009） | V1.1 期望 | 依赖方 |
|------|---------|---------|--------|
| 结构化工具结果 | 只回传 TextPart 文本 | DataPart / `clientToolResults` 结构化结果 | runtime + gateway |
| 明确 outcome 枚举 | 渲染为文本（OK/REJECTED/ERROR/TIMEOUT 仅本地） | wire 级 `outcome` 与服务端对齐 | runtime |
| 副作用分类上 wire | `sideEffect` 仅客户端治理 | `clientTools[].sideEffect` 供服务端差异化 | runtime + gateway |
| 工具版本 | `clientTools` 无 version | `clientTools[].version` + 意图回带 | runtime |
| `toolCallId` 回传 | 单 pending 自动关联，不回传 | 回传 `toolCallId` 支持并行/精确关联 | runtime |
| 并行多工具 | 单 pending 串行 | 一次 INPUT_REQUIRED 多 pending 并行 | runtime + core |
| 创建幂等 / 取消 | 依赖 gateway（Feat-Func-001 §8 G-6） | wire 级去重 + `tasks/cancel` | gateway + runtime |
