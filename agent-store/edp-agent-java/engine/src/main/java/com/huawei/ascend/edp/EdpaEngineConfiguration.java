package com.huawei.ascend.edp;

import com.huawei.ascend.edp.config.EdpAgentConfig;
import com.huawei.ascend.edp.handler.EdpaRuntimeHandler;
import com.huawei.ascend.runtime.engine.openjiuwen.OpenJiuwenAgentRuntimeHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * EDPAgent Spring Bean 配置。
 *
 * <p>文件作用：</p>
 * <ul>
 *     <li>创建 EDPAgent RuntimeHandler Bean。</li>
 *     <li>从 application.yml 注入 edp-agent.yaml 和 edp-config.yaml 路径。</li>
 *     <li>从环境变量注入密钥（apiKey 等），覆盖 YAML 中的占位符。</li>
 *     <li>在 Spring 容器启动期间完成 EDPAgent 初始化。</li>
 * </ul>
 *
 * <p>密钥外部化说明：</p>
 * <ul>
 *     <li>edp-agent.yaml 通过 Jackson 直读加载，不支持 Spring Boot ${...} 占位符替换。</li>
 *     <li>密钥类配置（apiKey）通过环境变量在 Bean 创建阶段注入，YAML 中写 PLACEHOLDER_USE_ENV_VAR 占位。</li>
 *     <li>非密钥类配置（versatile.url 等）直接硬编码在 YAML 中。</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class EdpaEngineConfiguration {

    /**
     * 创建并初始化 EDPAgent runtime handler。
     *
     * <p>作用：agent-runtime 通过该 Bean 获取可处理 A2A 请求的 OpenJiuwen AgentRuntimeHandler。</p>
     *
     * @param yamlPath 标准 agent 配置路径，来自 edpa.agent.yaml-path
     * @param configPath EDP 专有配置路径，来自 edpa.agent.config-path
     * @return 已初始化的 OpenJiuwenAgentRuntimeHandler Bean
     */
    @Bean
    OpenJiuwenAgentRuntimeHandler edpaRuntimeHandler(
            @Value("${edpa.agent.yaml-path}") String yamlPath,
            @Value("${edpa.agent.config-path}") String configPath,
            @Value("${edpa.agent.scenario-home}") String scenarioHome) {
        EdpaRuntimeHandler handler = new EdpaRuntimeHandler();
        handler.init(yamlPath, configPath, resolveEnvOverrides(), scenarioHome);
        return handler;
    }

    /**
     * 从环境变量收集密钥类配置，用于覆盖 YAML 中的占位符。
     *
     * 环境变量优先于 edp-agent.yaml 中的值。
     *
     * @return 环境变量覆盖后的 EdpAgentConfig（仅含密钥类字段）
     */
    private EdpAgentConfig.EnvOverrides resolveEnvOverrides() {
        EdpAgentConfig.EnvOverrides overrides = new EdpAgentConfig.EnvOverrides();

        // API Key：必须通过环境变量注入
        String apiKey = System.getenv("EDP_AGENT_MODEL_API_KEY");
        if (apiKey != null && !apiKey.isBlank()) {
            overrides.setApiKey(apiKey);
        }

        // 可选覆盖：模型 provider / name / baseUrl
        String provider = System.getenv("EDP_AGENT_MODEL_PROVIDER");
        if (provider != null && !provider.isBlank()) {
            overrides.setModelProvider(provider);
        }

        String modelName = System.getenv("EDP_AGENT_MODEL_NAME");
        if (modelName != null && !modelName.isBlank()) {
            overrides.setModelName(modelName);
        }

        String baseUrl = System.getenv("EDP_AGENT_MODEL_BASE_URL");
        if (baseUrl != null && !baseUrl.isBlank()) {
            overrides.setModelBaseUrl(baseUrl);
        }

        // 可选覆盖：Versatile URL
        String versatileUrl = System.getenv("EDP_AGENT_VERSATILE_URL");
        if (versatileUrl != null && !versatileUrl.isBlank()) {
            overrides.setVersatileUrl(versatileUrl);
        }

        return overrides;
    }
}
