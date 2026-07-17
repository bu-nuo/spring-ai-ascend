---
level: L2-LLD
module: agent-client
status: proposed
authority: non-authoritative
dependency:
  - ../../README.md
  - ../../L1-High-Level-Design/agent-client/overview.md
  - ../../L1-High-Level-Design/agent-client/logical.md
---

# agent-client L2 详细设计

## 目的

本目录保存 `agent-client` 模块的 L2 详细设计。它在 `agent-client` L1 高阶设计指导下，按特性颗粒度展开实现级设计，回答"某个特性由哪些类型/SPI 协作承接、走怎样的运行流程、有哪些状态与错误语义、当前边界在哪里"，不重新定义 L0/L1 已确定的系统边界、模块职责与依赖方向。

## ⚠️ 成熟度声明（与 agent-runtime L2 的差异）

`agent-runtime` 的 L2 只记录"与代码严格对应的当前事实"。`agent-client` 当前在代码仓中仍是 **edge plane 的 SDK skeleton**（见 L1 `overview.md`），尚无生产实现。因此本目录下的 Feat-Func 文档：

- 状态为 **`proposed` / `non-authoritative`**，是待评审的实现级设计，不是已接受的实现事实，也不是冻结的公共 API 或线协议。
- 其类型/方法签名以**可运行原型** `agent-client/examples/cloud-client/` 为参照实现（该原型已按 JDK 17 编译并自校验通过），但符号名需经协议评审后才固化。
- client↔gateway 线协议为标准 A2A JSON-RPC 2.0 over HTTP + SSE，报文语义**对齐 runtime 已在建的 `architecture/L2-Low-Level-Design/agent-runtime/Feat-Func-009-*.md`**（唯一差异是拓扑：client 连 gateway 受治理透传到 runtime）；wire 细节内联在本目录 Feat-Func-001 §3.5 / Feat-Func-002 §3.5，对 gateway 的要求见各文档 §8。能力拆解以 `agent-client/docs/proposals/agent-client-v1-design.md` 为准。

一旦 SDK 落地并与代码事实对应，应把状态提升为 `active`，并回填 version-scope 事实要求登记。

## 命名规则

沿用仓库 L2 惯例：功能特性 `Feat-Func-[3 digits]-[short name].md`，编号在 `agent-client` 模块内独立递增，**不并入 `version-scope/` 的全局 `FEAT-XXX` 序列**（`module: agent-client` 消歧）。

## 功能特性清单

| 编号 | 文档 | 特性 | 当前设计边界 |
|---|---|---|---|
| Feat-Func-001 | [标准化智能体服务调用](Feat-Func-001-standardized-agent-invocation.md) | 端侧发起智能体调用、持有本地句柄、消费归一化事件流、投影服务端 Task 状态、查询/断线补偿。 | 客户端只投影、不拥有服务端 Task 生命周期；wire 对齐 Feat-Func-009（Send/Get），保留 gateway 拓扑。 |
| Feat-Func-002 | [本地工具注册与远端驱动调用](Feat-Func-002-local-tool-registration-and-remote-driven-invocation.md) | 本地工具标准化 SPI 与注册管理，被远端智能体经多轮请求驱动执行，治理骨架 + 去重/幂等 + 结果渲染为 observation 文本回传。 | 对齐 Feat-Func-009：`clientTools` 声明 + `_interrupt` 意图 + TextPart 结果、单 pending；不做工具沙箱；执行与交付 ACK 分离。 |

## 关联文档

- L1 视图：`architecture/L1-High-Level-Design/agent-client/{overview,logical,scenarios,development,process,physical}.md`
- SDK 设计提案：`agent-client/docs/proposals/agent-client-v1-design.md`
- runtime 侧对齐基线：`architecture/L2-Low-Level-Design/agent-runtime/Feat-Func-009-调用端侧工具响应-新增支持带有端侧工具的请求.md`
- 端侧接入最佳实践与测试：`agent-client/docs/getting-started.md`
- 设备可移植性与 V1 交付形态：`agent-client/docs/device-portability-and-v1-delivery.md`
- 参照实现（可运行原型）：`agent-client/examples/cloud-client/`
