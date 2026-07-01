package com.huawei.ascend.edp.config;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.*;

/**
 * EdpConfigLoader EDP 专有配置加载器单元测试。
 *
 * 验证阶段 3 配置生产化中的 edp-config.yaml 加载。
 * 覆盖：todolist 占位、话术配置路径。
 */
class EdpConfigLoaderTest {

    @Test
    void testLoad_ValidYaml() {
        Path configPath = Path.of("src/main/resources/edp-config.yaml").toAbsolutePath();
        if (Files.exists(configPath)) {
            EdpConfig config = EdpConfigLoader.load(configPath);
            assertNotNull(config, "加载结果不应为 null");
        } else {
            System.out.println("SKIP: edp-config.yaml not found at " + configPath);
        }
    }

    @Test
    void testLoad_TodolistPlaceholder() {
        Path configPath = Path.of("src/main/resources/edp-config.yaml").toAbsolutePath();
        if (Files.exists(configPath)) {
            EdpConfig config = EdpConfigLoader.load(configPath);
            assertNotNull(config.getTodolistSteps(), "todolistSteps 不应为 null");
            assertTrue(config.getTodolistSteps().size() > 0, "应有占位步骤");
            assertEquals("_placeholder_", config.getTodolistSteps().get(0).getSkill(),
                    "占位步骤的 skill 应为 _placeholder_（真实步骤由场景文件提供）");
        } else {
            System.out.println("SKIP: edp-config.yaml not found");
        }
    }

    @Test
    void testLoad_UtterancesConfigPath() {
        Path configPath = Path.of("src/main/resources/edp-config.yaml").toAbsolutePath();
        if (Files.exists(configPath)) {
            EdpConfig config = EdpConfigLoader.load(configPath);
            assertNotNull(config.getUtterances(), "utterances 不应为 null");
            assertEquals("./SysScriptsConfig.yaml", config.getUtterances().getConfigPath(),
                    "话术配置路径应为 SysScriptsConfig.yaml（替代 ScriptsConfig.md）");
        } else {
            System.out.println("SKIP: edp-config.yaml not found");
        }
    }

    @Test
    void testLoad_Limits() {
        Path configPath = Path.of("src/main/resources/edp-config.yaml").toAbsolutePath();
        if (Files.exists(configPath)) {
            EdpConfig config = EdpConfigLoader.load(configPath);
            assertNotNull(config.getLimits(), "limits 不应为 null");
            assertNotNull(config.getLimits().getTasks(), "limits.tasks 不应为 null");
            assertTrue(config.getLimits().getTasks().containsKey("call_versatile"),
                    "limits 应包含 call_versatile 限制");
        } else {
            System.out.println("SKIP: edp-config.yaml not found");
        }
    }

    @Test
    void testLoad_NonExistentFile() {
        // EdpConfigLoader 对不存在文件返回默认对象（降级设计）
        EdpConfig config = EdpConfigLoader.load(Path.of("/nonexistent/edp-config.yaml"));
        assertNotNull(config, "不存在文件应返回默认对象（降级设计）");
        assertNull(config.getScope(), "默认对象 scope 应为 null");
    }

    @Test
    void testValidateScenarioConfig_ExistingWealthDemo() {
        Path scenarioHome = Path.of("src/main/resources/scenarios").toAbsolutePath();
        if (Files.exists(scenarioHome)) {
            assertDoesNotThrow(() -> EdpConfigValidator.validateScenarioConfig(scenarioHome),
                    "wealth-demo 场景校验不应抛异常");
        } else {
            System.out.println("SKIP: scenarioHome not found");
        }
    }
}
