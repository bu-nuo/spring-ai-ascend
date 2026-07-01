# deploy/config/

仅 **`edp-agent.yaml`** 为 Docker 部署专用（含 `adapter-versatile` 等容器服务名）。

`edp-config.yaml`、`SysScriptsConfig.yaml` 由 Dockerfile 直接从 `engine/src/main/resources/` 复制，不在此目录维护。
