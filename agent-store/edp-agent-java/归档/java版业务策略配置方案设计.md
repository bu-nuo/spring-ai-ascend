# EDPAgent治理策略框架设计（V1.0 Final）

---

# 1. 设计背景

EDPAgent是基于OpenJiuWen DeepAgent构建的企业级动态规划智能体。

为了满足不同企业、不同项目、不同业务场景的落地需求，支持客户通过配置化方式定制化Agent行为，EDPAgent采用：

```
能力与治理分离
默认能力与业务策略分离
```

的设计理念。

治理策略（Governance Policy）作为EDPAgent的重要组成部分，负责定义：

- Agent是谁
- Agent如何规划
- Agent如何执行
- Agent什么不能做
- Agent什么时候以及如何与人协同

从而实现：

```
同一个EDPAgent

可适配金融场景
可适配制造场景
可适配电力场景
可适配客户特定业务场景
```

而无需修改Agent代码。

---

# 2. 整体架构

EDPAgent采用两层治理模型：

```
Default Configuration
（默认能力，用于提供通用动态规划能力）

        ↓

Scenario Configuration
（业务场景定制覆盖，用于满足客户特定业务场景需求）
```

| 层级 | 负责人 | 目标 |
|------|--------|------|
| Default Configuration | EDP研发团队 | 提供通用动态规划能力 |
| Scenario Configuration | 项目团队/客户开发人员 | 满足项目特殊需求 |

# 3. 核心设计原则

## 原则1：能力与治理分离

Agent能力由代码实现。

治理策略由配置实现。

即：

```
代码负责"能做什么"

策略负责"应该怎么做"
```

例如：

```
EDPAgent支持搜索工具
```

属于能力。

而：

```
是否允许使用搜索工具
```

属于治理策略。

---

## 原则2：默认能力稳定

Default Governance代表EDPAgent开箱即用能力。

其目标是：

```
无需任何配置即可运行
```

默认治理由EDP研发团队维护。

行业团队不得修改。

---


## 原则3：继承覆盖机制

EDPAgent采用**继承覆盖**而非替代式覆盖。

**继承覆盖 vs 替代式覆盖：**

| 方案 | 业界采用 | 原因 |
|------|---------|------|
| **继承覆盖** | Spring Boot、Kustomize、Helm | 只写差异、避免分叉、可追溯 |
| **替代式覆盖** | 无主流采用 | 配置分叉、难以维护、无法回滚 |

**继承覆盖示例：**

```yaml
# Default配置
execution:
  max_steps: 100
  allowed_tools: [search, calculator, transfer]
  retry_enabled: true
  max_retry_count: 3

# Scenario继承覆盖（只写差异）
execution:
  max_steps: 50  # 仅覆盖这一个字段
  # 其他字段自动继承Default值
```

**继承覆盖的优势：**

1. **只写差异**：Scenario只需配置与Default不同的字段
2. **避免配置分叉**：未覆盖字段自动继承Default值
3. **差异可追溯**：一眼看出哪些字段被定制
4. **支持增量演进**：Default新增字段，Scenario自动继承默认值

**字段级别的继承覆盖原则：**

继承覆盖机制是**字段级别的**，不是文件级别的。不同字段可以采用不同的继承覆盖规则。

**继承覆盖规则分类：**

| 继承覆盖规则 | 适用场景 | 说明 |
|-------------|---------|------|
| **替代式覆盖（完全覆盖）** | 业务范围定义、业务流程定义 | Scenario完全覆盖Default，重新定义配置 |
| **继承式覆盖（只写差异）** | 约束参数调整、增量配置、固定配置 | Scenario只写差异部分，未覆盖字段自动继承Default值；若不写任何字段，完全继承Default值 |

**继承覆盖规则判断依据：**

| 判断依据 | 替代式覆盖 | 继承式覆盖 |
|---------|-----------|-----------|
| **字段性质** | 数组字段、嵌套对象字段 | 单值字段、数组字段、嵌套对象字段 |
| **业务含义** | 业务范围定义、业务流程定义 | 约束参数调整、增量配置、固定配置 |
| **使用场景** | 场景差异化配置 | 参数微调、增量演进、固定配置 |
| **是否需要删除Default配置** | 需要 | 不需要 |
| **是否需要完全重新定义** | 需要 | 不需要 |

**具体字段的继承覆盖规则：**

| 字段 | 继承覆盖规则 | 判断依据 |
|------|-------------|---------|
| **scope.allowed** | 替代式覆盖（完全覆盖） | 业务范围定义、场景差异化配置、需要完全重新定义 |
| **scope.denied** | 替代式覆盖（完全覆盖） | 业务范围定义、场景差异化配置、需要完全重新定义 |
| **scope.out_of_scope_message** | 替代式覆盖（完全覆盖） | 场景差异化配置、需要定制提示消息 |
| **summary** | 替代式覆盖（完全覆盖） | 场景差异化配置、需要完全重新定义总结格式 |
| **max_steps** | 继承式覆盖（只写差异） | 约束参数调整、参数微调 |
| **role** | 继承式覆盖（只写差异） | 固定配置、不写任何字段，完全继承Default值 |
| **allowed_tools** | 继承式覆盖（只写差异） | 固定配置、不写任何字段，完全继承Default值 |

**业界实践验证：**

- **Spring Boot**：application-{profile}.yml 只写差异部分，未覆盖字段自动继承 application.yml
- **Kustomize**：base + overlays 共享同一K8s资源Schema，patchesStrategicMerge 只写差异部分，未覆盖字段自动继承 base

---


## 原则4：统一Schema原则

Default和Scenario必须遵循同一个治理Schema。

即：

```
Default Policy Schema
      =
Scenario Policy Schema
```

区别仅在于：

```
Default
提供默认值

Scenario
提供业务场景覆盖值
```

这样可以保证：

```
治理策略可继承
治理策略可覆盖
治理策略可演进
```

避免治理体系分叉。

**业界实践参考：**

| 业界实践 | Schema设计 | 覆盖机制 |
|---------|-----------|---------|
| Spring Boot Profile | application.yml + application-{profile}.yml 共享同一Schema | 继承覆盖 |
| Kubernetes Kustomize | base + overlays 共享同一K8s资源Schema | patchesStrategicMerge |

统一Schema是继承覆盖的前提，如果Schema不一致，Scenario无法继承Default的默认值。

---

# 4. 治理域设计（三文件模型）

EDPAgent采用三文件治理模型：

```
Governance

├── planrule.yaml     (Identity 身份域)
├── actrule.yaml      (Planning 规划域 + Execution 执行域)
└── scriptconfig.yaml (Interaction 交互域)
```

各治理域职责如下：

```
Identity
回答Agent是谁

Planning
回答Agent如何规划任务

Execution
回答Agent如何执行任务

Interaction
回答Agent什么时候以及如何与人协同
```

---

## Identity（身份域）

### 作用

定义：

```
Agent是谁
Agent负责什么
Agent边界是什么
```

### Schema

```yaml
planrule:

  role:

  description:

  scope:

    allowed:

    denied:

    out_of_scope_message:

  supplementary_prompt:
```

### 字段说明

#### role

Agent角色。

类型：

```yaml
string
```

示例：

```yaml
role: planner
```

作用：

```
定义Agent角色定位
```

---

#### description

Agent描述。

类型：

```yaml
string
```

示例：

```yaml
description: "负责任务规划、执行和结果总结的智能助手"
```

作用：

```
定义Agent角色描述和职责说明
```

---

#### scope

Agent职责边界。

类型：

```yaml
object
```

作用：

```
定义Agent允许承担的业务范围、禁止承担的业务范围、超出业务范围时的提示消息
```

---

#### allowed

允许的业务范围列表。

类型：

```yaml
array[string]
```

示例：

```yaml
allowed:

  - "余额查询"

  - "转账"

  - "理财推荐"

  - "购买确认"
```

作用：

```
定义Agent允许承担的业务范围

精确列举每个业务项，便于LLM精确匹配和判断
```

继承覆盖规则：

```
替代式覆盖（完全覆盖）

Scenario完全覆盖Default的allowed，重新定义业务范围
```

---

#### denied

禁止的业务范围列表。

类型：

```yaml
array[string]
```

示例：

```yaml
denied:

  - "基金相关业务"

  - "股票相关业务"
```

作用：

```
定义Agent禁止承担的业务范围

明确禁止某些业务，避免LLM误判

金融场景等敏感场景需要明确禁止某些业务（如基金、股票）
```

继承覆盖规则：

```
替代式覆盖（完全覆盖）

Scenario完全覆盖Default的denied，重新定义禁止业务范围

可选字段，Default可以不配置denied
```

---

#### out_of_scope_message

超出业务范围时的提示消息。

类型：

```yaml
string
```

示例：

```yaml
out_of_scope_message: "尚在学习中，暂不支持该业务"
```

作用：

```
定义超出业务范围时的提示消息

提供友好的用户体验，避免LLM直接拒绝
```

继承覆盖规则：

```
替代式覆盖（完全覆盖）

Scenario完全覆盖Default的out_of_scope_message，定制提示消息
```

---

## planrule.yaml（身份域）

### 作用

定义：

```
Agent是谁
Agent负责什么
Agent边界是什么
```

### Schema

```yaml
planrule:

  role:

  description:

  scope:

    allowed:

    denied:

    out_of_scope_message:

  supplementary_prompt:
```

### 字段说明

#### role

Agent角色。

类型：

```yaml
string
```

示例：

```yaml
role: 通用动态规划智能体角色定位
```

作用：

```
定义Agent角色定位
```

---

#### description

Agent描述。

类型：

```yaml
string
```

示例：

```yaml
description: "负责任务规划、执行和结果总结的智能助手"
```

作用：

```
定义Agent角色描述和职责说明
```

---

#### scope

Agent职责边界。

类型：

```yaml
object
```

作用：

```
定义Agent允许承担的业务范围、禁止承担的业务范围、超出业务范围时的提示消息
```

---

#### allowed

允许的业务范围列表。

类型：

```yaml
string
```

示例：

```yaml
allowed: " "
```

作用：

```
定义Agent允许承担的业务范围
```

继承覆盖规则：

```
替代式覆盖（完全覆盖）

Scenario完全覆盖Default的allowed，重新定义业务范围
```

---

#### denied

禁止的业务范围列表。

类型：

```yaml
string
```

示例：

```yaml
denied: " "
```

作用：

```
定义Agent禁止承担的业务范围

明确禁止某些业务，避免LLM误判
```

继承覆盖规则：

```
替代式覆盖（完全覆盖）

Scenario完全覆盖Default的denied，重新定义禁止业务范围

可选字段，Default可以不配置denied
```

---

#### out_of_scope_message

超出业务范围时的提示消息。

类型：

```yaml
string
```

示例：

```yaml
out_of_scope_message: "尚在学习中，暂不支持该业务"
```

作用：

```
定义超出业务范围时的提示消息

提供友好的用户体验，避免LLM直接拒绝
```

继承覆盖规则：

```
替代式覆盖（完全覆盖）

Scenario完全覆盖Default的out_of_scope_message，定制提示消息
```

---

#### supplementary_prompt

补充提示词。

类型：

```yaml
string
```

作用：

```
定义Agent行为约束规则

例如：修改意图处理、关键参数缺失时调用ask_user等
```

示例：

```yaml
supplementary_prompt: |
  行为约束：
    1. 当用户表达修改意图，暂停当前任务，重新规划
    2. 当遇到以下情况，**调用 `ask_user` 工具**暂停执行，等待用户补充：
    
    - 关键参数缺失
    - 敏感操作需用户确认
    - 用户输入有歧义
```

继承覆盖规则：

```
替代式覆盖（完全覆盖）

Scenario完全覆盖Default的supplementary_prompt，重新定义补充提示词
```

---

## actrule.yaml（规划域 + 执行域）

### 作用

定义：

```
任务如何拆解与规划（Planning）
如何执行任务（Execution）
```

### Schema

```yaml
actrule:

  planning:

    max_subtasks:

    replan_enabled:

    max_replan_count:

  execution:

    max_steps:

    allowed_tools:

    retry_enabled:

    max_retry_count:
```

### 字段说明

#### max_subtasks

类型：

```yaml
integer
```

作用：

```
限制单层最大子任务数量
```

---

#### replan_enabled

类型：

```yaml
boolean
```

作用：

```
允许失败后重新规划
```

---

#### max_replan_count

类型：

```yaml
integer
```

作用：

```
限制最大重规划次数
```

---

## Execution（执行域）

### 作用

定义：

```
如何执行任务
如何使用工具
如何控制资源消耗
```

### Schema

```yaml
execution:

  max_steps:

  allowed_tools:

  retry_enabled:

  max_retry_count:
```

### 字段说明

#### max_steps

类型：

```yaml
integer
```

作用：

```
限制最大执行步数
```

---

#### allowed_tools

类型：

```yaml
array[string]
```

作用：

```
允许调用的工具列表
```

---

#### retry_enabled

类型：

```yaml
boolean
```

作用：

```
是否允许失败重试
```

---

#### max_retry_count

类型：

```yaml
integer
```

作用：

```
限制最大重试次数
```

---

## Interaction（交互域）

### 作用

定义：

```
Agent如何与人协同
Agent何时需要人工参与
```

### Schema

```yaml
interaction:

  # 话术模板

  tool_start:

  tool_end:

  todo_start:

  todo_end:

  todolist_start:

  todolist_end:

  interrupt_start:

  request_start:

  planning_start:

  task_cancelled:

  cancel_confirm:

  out_of_scope:

  # think_chunk 推送模式

  think_chunk_mode:

  think_chunk_fixed_scripts:

    enabled:

    chars_per_frame:

    tokens_between_frames:

    min_interval_ms:

    default_scripts:

    execution_scripts:

    resume_scripts:

  # 执行总结格式

  summary:

    format:

    max_length:

    required_fields:
```

### 字段说明

#### tool_start

工具调用开始话术。

类型：

```yaml
string
```

作用：

```
工具开始调用时，展示给用户的话术
```

---

#### tool_end

工具调用结束话术。

类型：

```yaml
string
```

作用：

```
工具结束调用时，展示给用户的话术
```

---

#### todo_start

Todo开始话术。

类型：

```yaml
string
```

作用：

```
Todo开始执行时，展示给用户的话术
```

---

#### todo_end

Todo结束话术。

类型：

```yaml
string
```

作用：

```
Todo结束执行时，展示给用户的话术
```

---

#### todolist_start

TodoList开始话术。

类型：

```yaml
string
```

作用：

```
TodoList开始执行时，展示给用户的话术
```

---

#### todolist_end

TodoList结束话术。

类型：

```yaml
string
```

作用：

```
TodoList结束执行时，展示给用户的话术
```

---

#### interrupt_start

中断开始话术。

类型：

```yaml
string
```

作用：

```
Human-in-the-loop中断时，展示给用户的话术
```

---

#### request_start

用户请求开始话术。

类型：

```yaml
string
```

作用：

```
用户发起请求时，展示给用户的话术
```

---

#### planning_start

规划开始话术。

类型：

```yaml
string
```

作用：

```
开始规划时，展示给用户的话术
```

---

#### task_cancelled

任务取消话术。

类型：

```yaml
string
```

作用：

```
任务被取消时，展示给用户的话术
```

---

#### cancel_confirm

取消确认话术。

类型：

```yaml
string
```

作用：

```
确认取消操作时，展示给用户的话术
```

---

#### out_of_scope

超出范围话术。

类型：

```yaml
string
```

作用：

```
任务超出范围时，展示给用户的话术
```

---

#### think_chunk_mode

think_chunk推送模式。

类型：

```yaml
string
```

取值：

```
fixed_script
real_stream
```

含义：

| 值 | 说明 |
|------|------|
| fixed_script | 固定话术推送（按帧推送固定字符数） |
| real_stream | 实时流推送（实时推送LLM输出） |

---

#### think_chunk_fixed_scripts

固定话术推送配置。

类型：

```yaml
object
```

字段说明：

| 字段 | 类型 | 作用 |
|------|------|------|
| enabled | boolean | 是否启用固定话术推送 |
| chars_per_frame | integer | 每帧推送字符数 |
| tokens_between_frames | integer | 帧间token数 |
| min_interval_ms | integer | 最小推送间隔（毫秒） |
| default_scripts | array[string] | planning阶段话术列表 |
| execution_scripts | array[string] | executing阶段话术列表 |
| resume_scripts | array[string] | resuming阶段话术列表 |

---

#### summary

执行总结格式配置。

类型：

```yaml
object
```

字段说明：

| 字段 | 类型 | 作用 |
|------|------|------|
| format | string | 定义总结格式 |
| max_length | integer | 定义总结最大长度 |
| required_fields | array[string] | 定义总结必填字段 |

作用：

```
定义Agent执行总结的格式和约束

Default作为通用动态规划智能体，定义通用的总结格式

Scenario根据业务诉求调整总结格式
```

示例：

```yaml
summary:

  format: "需求概述→规划过程→任务执行→结果汇总→异常说明"

  max_length: 500

  required_fields:

    - "理财产品名称"

    - "购买金额"
```

继承覆盖规则：

```
替代式覆盖（完全覆盖）

Scenario完全覆盖Default的summary，重新定义总结格式
```

---

# 5. 三文件字段定义

## 5.1 planrule.yaml（身份域）

```yaml
planrule:

  role:

  description:

  scope:

    allowed:

    denied:

    out_of_scope_message:

  supplementary_prompt:
```

## 5.2 actrule.yaml（规划域 + 执行域）

```yaml
actrule:

  planning:

    max_subtasks:

    replan_enabled:

    max_replan_count:

  execution:

    max_steps:

    allowed_tools:

    retry_enabled:

    max_retry_count:
```

## 5.3 scriptconfig.yaml（交互域）

```yaml
interaction:

  # 话术模板

  tool_start:

  tool_end:

  todo_start:

  todo_end:

  todolist_start:

  todolist_end:

  interrupt_start:

  request_start:

  planning_start:

  task_cancelled:

  cancel_confirm:

  out_of_scope:

  # think_chunk 推送模式

  think_chunk_mode:

  think_chunk_fixed_scripts:

    enabled:

    chars_per_frame:

    tokens_between_frames:

    min_interval_ms:

    default_scripts:

    execution_scripts:

    resume_scripts:

  # 执行总结格式

  summary:

    format:

    max_length:

    required_fields:
```

---

# 6. 配置目录设计

**分仓发布目录结构：**

```
agent-store仓（EDPAgent开源仓库）/

engine/src/main/resources/

├── governance/                     # 治理策略配置
│   ├── actrule.yaml              # 规划+执行约束
│   ├── planrule.yaml             # 身份域
│   └── scriptconfig.yaml         # 交互域
├── application.yml                 # 环境配置（模型、框架、第三方服务等）
├── application-dev.yml             # 开发环境配置
├── application-prod.yml            # 生产环境配置
├── application-test.yml            # 测试环境配置

（删除：edp-agent.yaml、edp-config.yaml、SysScriptsConfig.yaml）
```

---

**Solution仓（客户业务场景仓库）/

scenarios/

├── wealth-demo/
│   ├── governance/                 # 治理策略配置（Scenario继承覆盖）
│   │   ├── actrule.yaml           # 继承覆盖：只写差异（如max_subtasks）
│   │   ├── planrule.yaml          # 继承覆盖：只写差异（如scope）
│   │   └── scriptconfig.yaml      # 继承覆盖：只写差异（如话术模板）
│   ├── skills/                     # 业务技能目录
│   │   ├── product_recommend_skill/
│   │   │   ├── SKILL.md
│   │   │   ├── SKILL.yaml
│   │   │   ├── scripts/
│   │   │       run_product_recommend.py
│   │   ├── interact_finance_rec_skill/
│   │   │   ├── SKILL.md
│   │   │   ├── SKILL.yaml
│   │   ├── product_select_skill/
│   │   │   ├── SKILL.md
│   │   │   ├── SKILL.yaml
│   │   ├── fund_planning_skill/
│   │   │   ├── SKILL.md
│   │   │   ├── SKILL.yaml
│   │   │   ├── scripts/
│   │   │       run_fund_planning.py
│   ├── README.md                   # 场景说明文档

（删除：scenario-config.yaml、ScriptsConfig.yaml）
```

**关键设计说明：**

1. **Schema唯一**：schemas目录仅在agent-store仓定义，Solution仓不定义Schema
2. **继承覆盖**：Scenario配置文件只写与Default不同的字段，未覆盖字段自动继承
3. **分仓发布**：Default随EDPAgent版本发布，Scenario可独立演进
4. **Schema校验**：Scenario不允许定义Schema之外的字段
5. **配置职责分离**：
   - governance：治理约束配置（planrule、actrule、scriptconfig）
   - application.yml：环境配置（模型、框架、第三方服务）
   - skills：业务技能定义

---

# 7. 配置优先级与运行时加载机制

## 配置优先级

```
采用继承覆盖模型。

加载顺序：

Default Configuration

       ↓

Scenario Configuration

       ↓

合并覆盖

       ↓

Schema校验

优先级：

Scenario > Default

最终形成：

Effective Governance
```

**合并规则（参考Kustomize Strategic Merge）：**

| 字段类型 | 合并策略 | 示例 |
|---------|---------|------|
| **单值字段** | Scenario覆盖Default | max_steps: 50 覆盖 max_steps: 100 |
| **嵌套对象** | 逐字段继承覆盖 | data_masking.enabled 覆盖，fields 继承 |

**合并示例：**

```yaml
# Default配置
execution:
  max_steps: 100
  allowed_tools: [search, calculator, transfer]
  retry_enabled: true
  max_retry_count: 3

# Scenario配置（finance场景）
execution:
  max_steps: 50                    # 覆盖

# Effective Governance（finance场景）
execution:
  max_steps: 50                    # Scenario覆盖
  allowed_tools: [search, calculator, transfer]  # Default继承
  retry_enabled: true              # Default继承
  max_retry_count: 3               # Default继承
```

**业界实践参考：**

| 业界实践 | 加载顺序 | 合并机制 |
|---------|---------|---------|
| Spring Boot | application.yml → application-{profile}.yml → 环境变量 → 命令行参数 | 后加载覆盖前加载 |
| Kustomize | base → overlays → patchesStrategicMerge | 智能合并，只覆盖指定字段 |

---

## 启动生效方式

当检测到application.yaml文件，有配置启动哪个具体的scenario时，则加载对应scenario的配置。

```yaml
scenario-home: ${EDP_AGENT_SCENARIO_HOME:../scenarios/wealth-demo}
```

否则，加载默认配置。

---

## Schema校验机制

**允许：**

覆盖已有治理项

**不允许：**

定义Schema之外的治理项

**保证：**

治理体系可控演进

**校验流程：**

```
加载Default配置
    ↓
加载Scenario配置
    ↓
合并覆盖
    ↓
Schema校验（禁止新增字段）
    ↓
生成Effective Governance
```

# 8. 治理策略发布与定制机制

## 分仓发布策略

**agent-store仓（EDPAgent开源仓库）：**

```
governance/

├── schemas/              # 统一Schema定义（唯一）
│   identity.schema.yaml
│   planning.schema.yaml
│   execution.schema.yaml
│   security.schema.yaml
│   interaction.schema.yaml
│
└── default/              # Default配置实例
    identity.yaml
    planning.yaml
    execution.yaml
    security.yaml
    interaction.yaml
```

发布策略：

- schemas：治理规范，随EDPAgent版本发布
- default：默认治理，随EDPAgent版本发布

---

**Solution仓（客户业务场景仓库）：**

```
scenarios/

├── finance/              # 金融场景配置
├── power/                # 电力场景配置
├── manufacturing/        # 制造场景配置
└── custom/               # 客户自定义场景
```

发布策略：

- scenarios：客户业务场景配置，可独立演进
- 不包含Schema定义，Schema由agent-store仓统一管理

---

## 客户配置原则

客户不应通过阅读：

default

学习配置。

因为：

default是默认值

它不是配置规范。

客户应参考：

schemas

进行治理配置。

---

## Scenario配置原则

Scenario主要用于表达业务场景特殊需求。

**推荐：**

仅配置需要覆盖Default治理的配置项

采用继承覆盖，只写差异部分

**字段级别的继承覆盖原则：**

继承覆盖机制是**字段级别的**，不是文件级别的。不同字段可以采用不同的继承覆盖规则。

| 继承覆盖规则 | 适用字段 | 说明 |
|-------------|---------|------|
| **替代式覆盖（完全覆盖）** | scope.allowed、scope.denied、scope.out_of_scope_message、summary | Scenario完全覆盖Default，重新定义配置 |
| **继承式覆盖（只写差异）** | max_steps、description、role、allowed_tools、retry_enabled | Scenario只写差异部分，未覆盖字段自动继承Default值；若不写任何字段，完全继承Default值 |

**系统强制要求：**

Scenario不允许定义Schema之外的治理项

Scenario必须通过Schema校验

**系统负责：**

加载Default配置

加载Scenario配置

继承覆盖合并

Schema校验

生成Effective Governance

---

# 10. 最终设计理念

EDPAgent治理体系遵循：

```
Default Configuration（默认能力）
     ↓

Scenario Configuration（业务场景定制）
```

其中：

```
Default Governance
解决"能用"

Scenario Governance
解决"适用"
```

**核心设计理念：**

1. **统一Schema**：Default和Scenario共享同一套Schema，保证继承覆盖可行
2. **继承覆盖**：Scenario只写差异部分，未覆盖字段自动继承Default值
3. **字段级别的继承覆盖**：不同字段可以采用不同的继承覆盖规则（替代式覆盖、继承式覆盖）
4. **分仓发布**：Default随EDPAgent版本发布，Scenario可独立演进
5. **Schema校验**：禁止Scenario定义Schema之外的字段，保证治理体系可控演进

**字段级别的继承覆盖规则：**

| 继承覆盖规则 | 适用字段 | 说明 |
|-------------|---------|------|
| **替代式覆盖（完全覆盖）** | scope.allowed、scope.denied、scope.out_of_scope_message、summary | Scenario完全覆盖Default，重新定义配置 |
| **继承式覆盖（只写差异）** | max_steps、description、role、allowed_tools、retry_enabled | Scenario只写差异部分，未覆盖字段自动继承Default值；若不写任何字段，完全继承Default值 |

**业界实践对标：**

| EDPAgent设计 | 业界实践 | 对标说明 |
|-------------|---------|---------|
| Default + Scenario | Spring Boot application.yml + application-{profile}.yml | 两层架构、继承覆盖 |
| 统一Schema | Kustomize base + overlays共享Schema | Schema统一是继承前提 |
| 继承覆盖 | Kustomize patchesStrategicMerge | 只写差异、智能合并 |
| 分仓发布 | Kustomize base仓 + overlay仓分离 | Default和Scenario独立演进 |

运行时通过：

```
Governance Loader Engine
```

实现：

```
加载Default配置
加载Scenario配置
继承覆盖合并
Schema校验
生成Effective Governance
```

最终形成：

```
Effective Governance
```

使同一个EDPAgent能够通过治理策略配置快速适配不同的行业和业务场景，而无需修改Agent核心代码。

---
