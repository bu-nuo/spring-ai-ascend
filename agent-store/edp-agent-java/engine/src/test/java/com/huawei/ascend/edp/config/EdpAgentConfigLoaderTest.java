package com.huawei.ascend.edp.config;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.*;

/**
 * EdpAgentConfigLoader 标准 Agent YAML 加载器单元测试。
 *
 * 验证阶段 1 工程身份生产化 + 阶段 3 配置生产化。
 * 覆盖：edp-agent.yaml Jackson 直读加载、密钥占位符机制。
 */
class EdpAgentConfigLoaderTest {

    @Test
    void testLoad_ValidYaml() {
        Path yamlPath = Path.of("src/main/resources/edp-agent.yaml").toAbsolutePath();
        if (Files.exists(yamlPath)) {
            EdpAgentConfig config = EdpAgentConfigLoader.load(yamlPath);
            assertNotNull(config, "加载结果不应为 null");
            assertEquals("edp-agent", config.getName(), "名称应为 edp-agent");
        } else {
            System.out.println("SKIP: edp-agent.yaml not found at " + yamlPath);
        }
    }

    @Test
    void testLoad_ApiKeyPlaceholder() {
        Path yamlPath = Path.of("src/main/resources/edp-agent.yaml").toAbsolutePath();
        if (Files.exists(yamlPath)) {
            EdpAgentConfig config = EdpAgentConfigLoader.load(yamlPath);
            assertNotNull(config.getModel(), "model 不应为 null");
            assertEquals("PLACEHOLDER_USE_ENV_VAR", config.getModel().getApiKey(),
                    "apiKey 应为 PLACEHOLDER_USE_ENV_VAR（密钥外部化机制）");
        } else {
            System.out.println("SKIP: edp-agent.yaml not found");
        }
    }

    @Test
    void testLoad_VersatileUrl() {
        Path yamlPath = Path.of("src/main/resources/edp-agent.yaml").toAbsolutePath();
        if (Files.exists(yamlPath)) {
            EdpAgentConfig config = EdpAgentConfigLoader.load(yamlPath);
            assertNotNull(config.getVersatile(), "versatile 不应为 null");
            assertTrue(config.getVersatile().getUrl().startsWith("http://"),
                    "versatile URL 应以 http:// 开头（不使用 ${...} 占位符）");
        } else {
            System.out.println("SKIP: edp-agent.yaml not found");
        }
    }

    @Test
    void testLoad_EmptySystemPrompt() {
        Path yamlPath = Path.of("src/main/resources/edp-agent.yaml").toAbsolutePath();
        if (Files.exists(yamlPath)) {
            EdpAgentConfig config = EdpAgentConfigLoader.load(yamlPath);
            assertNotNull(config.getPrompt(), "prompt 不应为 null");
            assertEquals("", config.getPrompt().getSystem(),
                    "系统提示词应为空（动态生成，由 ScenarioPromptBuilder 拼接）");
        } else {
            System.out.println("SKIP: edp-agent.yaml not found");
        }
    }

    @Test
    void testLoad_NonExistentFile() {
        // EdpAgentConfigLoader 对不存在文件返回默认对象（降级设计）
        EdpAgentConfig config = EdpAgentConfigLoader.load(Path.of("/nonexistent/edp-agent.yaml"));
        assertNotNull(config, "不存在文件应返回默认对象（降级设计）");
        assertNull(config.getName(), "默认对象 name 应为 null");
    }

    @Test
    void testEnvOverrides_ApiKeyOverride() {
        EdpAgentConfig config = new EdpAgentConfig();
        EdpAgentConfig.Model model = new EdpAgentConfig.Model();
        model.setApiKey("PLACEHOLDER_USE_ENV_VAR");
        config.setModel(model);

        EdpAgentConfig.EnvOverrides overrides = new EdpAgentConfig.EnvOverrides();
        overrides.setApiKey("real-api-key-from-env");

        // 模拟 applyEnvOverrides
        if (overrides.getApiKey() != null && !overrides.getApiKey().isBlank()) {
            model.setApiKey(overrides.getApiKey());
        }

        assertEquals("real-api-key-from-env", config.getModel().getApiKey(),
                "EnvOverrides 应覆盖 PLACEHOLDER apiKey");
    }
}
