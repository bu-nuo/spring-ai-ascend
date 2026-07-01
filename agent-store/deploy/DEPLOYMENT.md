# agent-store Linux 部署指南

本文档描述在 **Linux 服务器** 上从源码到运行的完整流程：Maven 打包 → 构建两个 Docker 镜像 → 配置外部 Versatile Mock 地址 → Compose 启动 **adapter + edp-agent**。

> **Versatile Mock 不由本仓库 Compose 管理**，默认由同事独立部署；你只需拿到其可达地址并写入 `deploy/.env`。

---

## 目录结构（部署相关）

```
agent-store/
├── deploy/                              ← 编排层（Compose + 环境变量 + 本文档）
│   ├── docker-compose.yml
│   ├── .env.example
│   └── DEPLOYMENT.md
├── adapter-versatile-agent-java/
│   └── deploy/
│       └── Dockerfile                   ← adapter 镜像构建
├── edp-agent-java/
│   ├── .dockerignore
│   └── deploy/
│       ├── Dockerfile                   ← edp-agent 镜像构建
│       ├── requirements-mcp.txt
│       └── config/
│           ├── README.md
│           └── edp-agent.yaml           ← Docker 专用（容器服务名）
└── edp-agent-java/mock/                 ← Mock 源码（同事自行部署，本指南不包含）
```

### 配置单一来源

| 文件 | 权威来源 | Docker 如何使用 |
|------|----------|-----------------|
| `edp-config.yaml` | `engine/src/main/resources/` | Dockerfile 直接 COPY 进镜像 |
| `SysScriptsConfig.yaml` | 同上 | 同上 |
| `edp-agent.yaml` | **`deploy/config/` 手工维护** | Docker 网络地址；与 resources 仅 localhost/服务名不同 |

修改 `edp-config.yaml` / `SysScriptsConfig.yaml` 时**只改 resources**，重建镜像即可，无需额外同步步骤。

`deploy/config/edp-agent.yaml` 是 **Docker 专用副本**（含 `adapter-versatile` 等服务名），与 resources  intentionally 不同；改 tools/framework 等共用字段时需**两边同步**，改网络地址只改 deploy 版。

| 环境变量 | 覆盖 YAML 字段 |
|----------|----------------|
| `EDP_AGENT_VERSATILE_URL` | `versatile.url`（可选） |
| （无） | `adapter_a2a_url` 仅能通过 deploy/config/edp-agent.yaml 配置 |

---

## 调用链路

```
客户端
  → edp-agent (:8190)                    [对外 POST /a2a]
      → adapter-versatile (:8191)        [deploy/config/edp-agent.yaml → adapter_a2a_url]
          → 外部 Versatile Mock (:30001) [deploy/.env → VERSATILE_URL]
```

Compose 启动 **2 个容器**（2 个镜像），Mock 为第 3 方服务。

---

## 一、向mock索取的信息（外部 Versatile Mock）

在配置 `.env` 之前，请同事提供以下信息。缺任何一项都可能导致 adapter 连不上 Mock。

### 1. 必填

| 信息 | 说明 | 写入位置 |
|------|------|----------|
| **Versatile REST 基址 URL 模板** | 须含 `{workflow_id}`、`{conversation_id}` 占位符 | `deploy/.env` → `VERSATILE_URL` |
| **workflow_id** | Mock 注册的工作流 ID，如 `mock_workflow` | `deploy/.env` → `VERSATILE_WORKFLOW_ID` |
| **workspace_id** | 查询参数，如 `10` | `deploy/.env` → `VERSATILE_WORKSPACE_ID` |
| **容器内可达地址** | adapter **容器内** curl 能访问的 host:port，**不能写 localhost**（除非 Mock 与 adapter 同容器，通常不是） | `deploy/.env` → `VERSATILE_URL` 的 host 部分 |

**URL 模板示例**（请同事按实际接口给出）：

```text
http://<可达主机或域名>:<端口>/v1/0/agent-manager/workflows/{workflow_id}/conversations/{conversation_id}
```

### 2. 强烈建议确认

| 信息 | 说明 | 写入位置 |
|------|------|----------|
| **健康检查 URL** | 如 `GET http://<host>:30001/health` 返回 200 | 部署后自行 curl 验证 |
| **结果节点类型/名称** | adapter 识别「结果帧」用，Mock 默认 `QA` / `GXZQAResponseNode` | `VERSATILE_RESULT_NODE_TYPE`、`VERSATILE_RESULT_NODE_NAME` |
| **网络互通方式** | 同机不同 Compose / 跨机 / 共享 Docker 网络 | 决定 `VERSATILE_URL` 用 IP、域名还是 `host.docker.internal` |
| **鉴权** | 若 Mock 需要 Token 或特殊 Header | 需确认 adapter 是否支持（当前通过 `versatile.headers` 配置，一般 Mock 无鉴权） |

### 3. 常见网络场景与 `VERSATILE_URL` 写法

| 场景 | adapter 容器内应使用的地址示例 |
|------|-------------------------------|
| Mock 与同事服务同机，端口映射到宿主机 30001 | `http://host.docker.internal:30001/v1/0/agent-manager/workflows/{workflow_id}/conversations/{conversation_id}` |
| Mock 在另一台机器 10.1.2.3:30001 | `http://10.1.2.3:30001/v1/0/agent-manager/workflows/{workflow_id}/conversations/{conversation_id}` |
| 已与 adapter 加入同一 Docker 网络，服务名 `versatile-mock` | `http://versatile-mock:30001/v1/0/agent-manager/workflows/{workflow_id}/conversations/{conversation_id}`（需在 compose 中接入 external network） |

`docker-compose.yml` 已为 adapter / edp-agent 配置 `extra_hosts: host.docker.internal:host-gateway`，便于同机访问宿主机端口。

### 4. edp-agent 侧（可选）

| 配置 | 位置 | 说明 |
|------|------|------|
| `adapter_a2a_url` | `deploy/config/edp-agent.yaml` | 固定为 `http://adapter-versatile:8191/a2a`，Compose 内无需改 |
| `EDP_AGENT_VERSATILE_URL` | `deploy/.env`（可选） | `call_versatile` 直连 Mock 时使用 |

---

## 二、前置条件

在 Linux 服务器上验证：

```bash
git --version          # 任意较新版本
java -version          # 须为 21
docker --version       # 20.10+
docker compose version # v2+
```

克隆仓库：

```bash
git clone <仓库地址> spring-ai-ascend
cd spring-ai-ascend
```

确认部署文件存在：

```bash
ls agent-store/deploy/docker-compose.yml
ls agent-store/deploy/.env.example
ls agent-store/adapter-versatile-agent-java/deploy/Dockerfile
ls agent-store/edp-agent-java/deploy/Dockerfile
ls agent-store/edp-agent-java/deploy/config/edp-agent.yaml
```

---

## 三、Maven 打包（生成 Fat JAR）

在**仓库根目录** `spring-ai-ascend/` 执行：

```bash
# ① 安装父工程依赖（agent-runtime 等）
./mvnw clean install -DskipTests

# ② 打包 edp-agent
./mvnw -pl agent-store/edp-agent-java -am package -DskipTests

# ③ 打包 adapter（不在根 pom modules 中，须单独指定）
./mvnw -f agent-store/adapter-versatile-agent-java/pom.xml package -DskipTests
```

确认产物：

```bash
ls agent-store/edp-agent-java/engine/target/edp-agent-engine-*.jar
ls agent-store/adapter-versatile-agent-java/target/adapter-versatile-agent-java-*.jar
```

---

## 四、构建 Docker 镜像

仍在**仓库根目录**执行（共 2 个镜像，不含 Mock）：

```bash
# adapter
docker build -t adapter-versatile-agent-java:latest \
  -f agent-store/adapter-versatile-agent-java/deploy/Dockerfile \
  agent-store/adapter-versatile-agent-java

# edp-agent（含 Python3 + mcp + 场景 skills；edp-config 等直接从 resources COPY）
docker build -t edp-agent-java:latest \
  -f agent-store/edp-agent-java/deploy/Dockerfile \
  agent-store/edp-agent-java
```

确认镜像：

```bash
docker images | grep -E 'adapter-versatile-agent-java|edp-agent-java'
```

代码或 JAR 更新后，重新执行第三节 + 本节，或在 `deploy/` 目录执行 `docker compose up -d --build`。

---

## 五、配置环境变量

```bash
cd agent-store/deploy
cp .env.example .env
vi .env   # 或 nano .env
```

### 必须修改

```env
# LLM 密钥
EDP_AGENT_MODEL_API_KEY=你的真实密钥

# 同事提供的 Versatile Mock 地址（adapter 容器内可达）
VERSATILE_URL=http://host.docker.internal:30001/v1/0/agent-manager/workflows/{workflow_id}/conversations/{conversation_id}
VERSATILE_WORKFLOW_ID=mock_workflow
VERSATILE_WORKSPACE_ID=10
```

### 配置项与作用对照

| 变量 | 消费者 | 作用 |
|------|--------|------|
| `VERSATILE_URL` | **adapter 容器** | adapter → 外部 Mock 的 REST 地址 |
| `VERSATILE_WORKFLOW_ID` | adapter | URL 中 `{workflow_id}` 替换值 |
| `VERSATILE_WORKSPACE_ID` | adapter | REST 查询参数 |
| `VERSATILE_RESULT_NODE_*` | adapter | 识别 Mock 流式结果节点 |
| `EDP_AGENT_MODEL_API_KEY` | edp-agent | LLM 调用密钥 |
| `EDP_AGENT_VERSATILE_URL` | edp-agent（可选） | 直连 Mock，绕过 adapter 时使用 |
| `MCP_*` | edp-agent | 产品列表 MCP（可选） |

**edp-agent → adapter** 写在 `deploy/config/edp-agent.yaml` 的 `adapter_a2a_url`，值为 `http://adapter-versatile:8191/a2a`。

---

## 六、启动服务

```bash
cd agent-store/deploy
docker compose up -d
```

将启动 2 个容器：

| 容器名 | 镜像 | 宿主机端口 |
|--------|------|-----------|
| `adapter-versatile` | `adapter-versatile-agent-java:latest` | 8191 |
| `edp-agent` | `edp-agent-java:latest` | 8190 |

查看状态：

```bash
docker compose ps
docker compose logs -f
```

停止：

```bash
docker compose down
```

---

## 七、部署后验证

### 7.1 确认外部 Mock 可达（在 adapter 容器内）

```bash
# 将 URL 换成 .env 中 VERSATILE_URL 的 health 路径（向同事确认）
docker exec adapter-versatile curl -sf http://host.docker.internal:30001/health
```

### 7.2 本栈健康检查

```bash
curl -s http://localhost:8191/.well-known/agent-card.json
curl -s http://localhost:8190/.well-known/agent-card.json
```

### 7.3 容器间网络

```bash
docker exec edp-agent curl -s http://adapter-versatile:8191/.well-known/agent-card.json
```

### 7.4 adapter 上游地址

```bash
docker compose logs adapter-versatile | grep "versatile request url"
# 应出现 .env 中配置的 host，而非 localhost:30001
```

### 7.5 端到端 A2A 流式请求

```bash
curl -N -X POST http://localhost:8190/a2a \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{
    "jsonrpc": "2.0",
    "method": "SendStreamingMessage",
    "id": "test-1",
    "params": {
      "message": {
        "role": "ROLE_USER",
        "messageId": "msg-1",
        "contextId": "conv-linux-test-001",
        "parts": [{"text": "推荐理财产品"}]
      },
      "metadata": {"userId": "test", "agentId": "edp-agent"}
    }
  }'
```

返回 SSE 流式数据即表示 **edp-agent → adapter → 外部 Mock** 全链路打通。

---

## 八、离线部署（无网络服务器）

在构建机：

```bash
docker save adapter-versatile-agent-java:latest -o adapter-versatile.tar
docker save edp-agent-java:latest -o edp-agent.tar
```

在目标机：

```bash
docker load -i adapter-versatile.tar
docker load -i edp-agent.tar
cd agent-store/deploy
cp .env.example .env
# 编辑 .env（含 VERSATILE_URL 与 LLM 密钥）
docker compose up -d
```

Mock 镜像由同事在目标环境另行部署。

---

## 九、故障排查

| 现象 | 可能原因 | 处理 |
|------|---------|------|
| `VERSATILE_URL is required` | 未创建 `.env` 或未设置变量 | `cp .env.example .env` 并填写 |
| adapter 连 Mock 超时 | URL 用了 `localhost:30001` | 改为 `host.docker.internal` 或同事给的 IP |
| edp-agent 连不上 adapter | `deploy/config/edp-agent.yaml` 中 `adapter_a2a_url` 仍为 localhost | 保持 `http://adapter-versatile:8191/a2a` |
| `docker build` 找不到 JAR | 未 Maven 打包 | 重跑第三节 |
| edp-agent 启动失败 | 未设 `EDP_AGENT_MODEL_API_KEY` | 检查 `.env` |
| adapter unhealthy | 冷启动慢 | 等待 90s 或查 `docker compose logs adapter-versatile` |
| 全链路 404 | Mock 未启动或 workflow_id 不匹配 | 与同事核对 `VERSATILE_WORKFLOW_ID` |

---

## 十、完整流程一览

```
git clone
    ↓
向同事索取 Versatile Mock 可达地址 + workflow_id + workspace_id
    ↓
./mvnw clean install + package（2 个 Java 模块）
    ↓
docker build × 2（adapter / edp-agent）
    ↓
cd agent-store/deploy && cp .env.example .env
    ↓
填写 EDP_AGENT_MODEL_API_KEY、VERSATILE_URL 等
    ↓
docker compose up -d
    ↓
curl 健康检查 + A2A 流式请求验证
```

---

## 附录：与本地 java -jar 开发的区别

| 配置 | Docker Compose 部署 | 本地开发 |
|------|---------------------|----------|
| 环境变量文件 | `agent-store/deploy/.env` | `edp-agent-java/.env.example` |
| Versatile 地址 | `deploy/.env` → `VERSATILE_URL` | `localhost:30001` |
| edp-agent → adapter | `deploy/config/edp-agent.yaml` | `resources/edp-agent.yaml`（localhost） |
| Mock 部署 | 同事负责 | 本地 `mock/` 目录自行启动 |
