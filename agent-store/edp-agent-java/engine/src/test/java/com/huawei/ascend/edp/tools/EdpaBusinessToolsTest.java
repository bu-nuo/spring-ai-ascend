package com.huawei.ascend.edp.tools;

import com.huawei.ascend.edp.config.ActRuleConfig;
import com.huawei.ascend.edp.config.EdpConfig;
import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.singleagent.interrupt.ToolInterruptException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EDPAgent 五个内置业务工具的单元测试。
 *
 * <p>测试目标：验证每个工具 ToolCard 完整性和 invoke 行为</p>
 * <p>测试覆盖：</p>
 * <ul>
 *     <li>call_mcp — ToolCard 字段 + invoke 返回 mcp_intent</li>
 *     <li>call_versatile — ToolCard 字段 + invoke 返回 delegate_intent</li>
 *     <li>cancel_task — ToolCard 字段 + invoke 返回 cancelled</li>
 *     <li>ask_user — ToolCard 字段 + invoke 抛 ToolInterruptException</li>
 *     <li>lite_todo_write — ToolCard 字段 + step_id 枚举 + invoke 返回 accepted</li>
 *     <li>EdpaToolRegistry — 名称→构建器映射</li>
 *     <li>EdpaBusinessTools.build — null / 空列表 / 5工具 / bash跳过 / 未知工具跳过</li>
 * </ul>
 */
@DisplayName("EDPAgent 内置业务工具测试")
class EdpaBusinessToolsTest {

    // -----------------------------------------------------------------------
    // call_mcp
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("call_mcp 工具")
    class CallMcpToolTest {

        private final Tool tool = CallMcpTool.build();

        @Test
        @DisplayName("ToolCard 名称、描述、必填参数完整")
        void testToolCard() {
            ToolCard card = tool.getCard();
            assertEquals(EdpaBusinessTools.TOOL_CALL_MCP, card.getName());
            assertEquals(card.getName(), card.getId());
            assertTrue(card.getDescription().contains("MCP"), "描述应提及 MCP");

            @SuppressWarnings("unchecked")
            Map<String, Object> inputParams = card.getInputParams();
            assertNotNull(inputParams);
            assertEquals("object", inputParams.get("type"));

            @SuppressWarnings("unchecked")
            Map<String, Object> props = (Map<String, Object>) inputParams.get("properties");
            assertNotNull(props);
            assertTrue(props.containsKey("script_command"), "应包含 script_command 参数");
            assertTrue(props.containsKey("script_params"), "应包含 script_params 参数");

            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) inputParams.get("required");
            assertNotNull(required);
            assertTrue(required.contains("script_command"), "script_command 应为必填");
        }

        @Test
        @DisplayName("invoke 返回 mcp_intent 状态和输入透传")
        void testInvoke() throws Exception {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) tool.invoke(
                    Map.of("script_command", "echo hello", "script_params", Map.of("key", "value")),
                    Map.of());

            assertEquals(EdpaBusinessTools.TOOL_CALL_MCP, result.get("tool"));
            assertEquals("mcp_intent", result.get("status"));
            assertNotNull(result.get("input"));
        }
    }

    // -----------------------------------------------------------------------
    // call_versatile
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("call_versatile 工具")
    class CallVersatileToolTest {

        private final Tool tool = CallVersatileTool.build();

        @Test
        @DisplayName("ToolCard 名称、描述、必填参数完整")
        void testToolCard() {
            ToolCard card = tool.getCard();
            assertEquals(EdpaBusinessTools.TOOL_CALL_VERSATILE, card.getName());
            assertEquals(card.getName(), card.getId());
            assertTrue(card.getDescription().contains("Versatile"), "描述应提及 Versatile");

            @SuppressWarnings("unchecked")
            Map<String, Object> inputParams = card.getInputParams();
            assertNotNull(inputParams);

            @SuppressWarnings("unchecked")
            Map<String, Object> props = (Map<String, Object>) inputParams.get("properties");
            assertNotNull(props);
            assertTrue(props.containsKey("query_description"), "应包含 query_description 参数");
            assertTrue(props.containsKey("query_intent"), "应包含 query_intent 参数");
            assertTrue(props.containsKey("input_key"), "应包含 input_key 参数");

            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) inputParams.get("required");
            assertTrue(required.contains("query_description"), "query_description 应为必填");
            assertTrue(required.contains("query_intent"), "query_intent 应为必填");
        }

        @Test
        @DisplayName("invoke 返回 delegate_intent 状态和输入透传")
        void testInvoke() throws Exception {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) tool.invoke(
                    Map.of("query_description", "Test query", "query_intent", "test"),
                    Map.of());

            assertEquals(EdpaBusinessTools.TOOL_CALL_VERSATILE, result.get("tool"));
            assertEquals("delegate_intent", result.get("status"));
            assertNotNull(result.get("input"));
        }
    }

    // -----------------------------------------------------------------------
    // cancel_task
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("cancel_task 工具")
    class CancelTaskToolTest {

        private final Tool tool = CancelTaskTool.build();

        @Test
        @DisplayName("ToolCard 名称、描述、参数完整")
        void testToolCard() {
            ToolCard card = tool.getCard();
            assertEquals(EdpaBusinessTools.TOOL_CANCEL_TASK, card.getName());
            assertEquals(card.getName(), card.getId());
            assertTrue(card.getDescription().contains("取消"), "描述应提及 取消");
            assertTrue(card.getDescription().contains("ask_user"), "描述应提示先通过 ask_user 确认");

            @SuppressWarnings("unchecked")
            Map<String, Object> inputParams = card.getInputParams();
            assertNotNull(inputParams);

            @SuppressWarnings("unchecked")
            Map<String, Object> props = (Map<String, Object>) inputParams.get("properties");
            assertTrue(props.containsKey("reason"), "应包含 reason 参数");
        }

        @Test
        @DisplayName("invoke 返回 cancelled 状态和输入透传")
        void testInvoke() throws Exception {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) tool.invoke(
                    Map.of("reason", "用户要求取消"),
                    Map.of());

            assertEquals(EdpaBusinessTools.TOOL_CANCEL_TASK, result.get("tool"));
            assertEquals("cancelled", result.get("status"));
            assertNotNull(result.get("input"));
        }
    }

    // -----------------------------------------------------------------------
    // ask_user — 唯一会抛异常的工具
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("ask_user 工具")
    class EnhancedAskUserToolTest {

        private final Tool tool = EnhancedAskUserTool.build();

        @Test
        @DisplayName("ToolCard 名称、描述、必填参数完整")
        void testToolCard() {
            ToolCard card = tool.getCard();
            assertEquals(EdpaBusinessTools.TOOL_ENHANCED_ASK_USER, card.getName());
            assertEquals(card.getName(), card.getId());
            assertTrue(card.getDescription().contains("追问"), "描述应提及 追问");

            @SuppressWarnings("unchecked")
            Map<String, Object> inputParams = card.getInputParams();
            assertNotNull(inputParams);

            @SuppressWarnings("unchecked")
            Map<String, Object> props = (Map<String, Object>) inputParams.get("properties");
            assertTrue(props.containsKey("question"), "应包含 question 参数");
            assertTrue(props.containsKey("missing_fields"), "应包含 missing_fields 参数");

            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) inputParams.get("required");
            assertTrue(required.contains("question"), "question 应为必填");
        }

        @Test
        @DisplayName("invoke 抛出 ToolInterruptException（正常行为，用于触发用户交互）")
        void testInvokeThrowsInterrupt() {
            ToolInterruptException ex = assertThrows(ToolInterruptException.class, () -> {
                tool.invoke(Map.of("question", "请确认您的需求"), Map.of());
            }, "ask_user 应通过抛异常触发中断，而非普通返回");

            assertNotNull(ex.getRequest(), "应携带 InterruptRequest");
            assertEquals("ask_user_interrupt", ex.getRequest().getInterruptId());
            assertTrue(ex.getRequest().getMessage().contains("请确认您的需求"),
                    "interrupt message 应包含用户问题");
        }
    }

    // -----------------------------------------------------------------------
    // lite_todo_write — 唯一需要 EdpConfig 的工具
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("lite_todo_write 工具")
    class LiteTodoWriteToolTest {

        @Test
        @DisplayName("EdpConfig 为 null 时仍可构建，step_id 枚举为空")
        void testBuildWithNullEdpConfig() {
            Tool tool = LiteTodoWriteTool.build(null);
            assertNotNull(tool);

            @SuppressWarnings("unchecked")
            Map<String, Object> inputParams = tool.getCard().getInputParams();
            assertNotNull(inputParams);

            // 逐层进入 properties → todos → items → properties → step_id → enum
            @SuppressWarnings("unchecked")
            Map<String, Object> props = (Map<String, Object>) inputParams.get("properties");
            @SuppressWarnings("unchecked")
            Map<String, Object> todos = (Map<String, Object>) props.get("todos");
            @SuppressWarnings("unchecked")
            Map<String, Object> items = (Map<String, Object>) todos.get("items");
            @SuppressWarnings("unchecked")
            Map<String, Object> itemProps = (Map<String, Object>) items.get("properties");
            @SuppressWarnings("unchecked")
            Map<String, Object> stepId = (Map<String, Object>) itemProps.get("step_id");

            @SuppressWarnings("unchecked")
            List<Integer> stepEnum = (List<Integer>) stepId.get("enum");
            assertTrue(stepEnum.isEmpty(), "无 EdpConfig 时 step_id 枚举应为空");
        }

        @Test
        @DisplayName("EdpConfig 提供 todolistSteps 时，step_id 枚举动态填充")
        void testBuildWithEdpConfigSteps() {
            EdpConfig edpConfig = new EdpConfig();
            List<EdpConfig.TodolistStep> steps = new ArrayList<>();
            steps.add(createStep(1, "需求分析", "analysis"));
            steps.add(createStep(2, "方案设计", "design"));
            steps.add(createStep(3, "代码实现", "coding"));
            edpConfig.setTodolistSteps(steps);

            Tool tool = LiteTodoWriteTool.build(edpConfig);
            assertNotNull(tool);

            @SuppressWarnings("unchecked")
            Map<String, Object> inputParams = tool.getCard().getInputParams();

            @SuppressWarnings("unchecked")
            Map<String, Object> props = (Map<String, Object>) inputParams.get("properties");
            @SuppressWarnings("unchecked")
            Map<String, Object> todos = (Map<String, Object>) props.get("todos");
            @SuppressWarnings("unchecked")
            Map<String, Object> items = (Map<String, Object>) todos.get("items");
            @SuppressWarnings("unchecked")
            Map<String, Object> itemProps = (Map<String, Object>) items.get("properties");
            @SuppressWarnings("unchecked")
            Map<String, Object> stepId = (Map<String, Object>) itemProps.get("step_id");

            @SuppressWarnings("unchecked")
            List<Integer> stepEnum = (List<Integer>) stepId.get("enum");
            assertEquals(List.of(1, 2, 3), stepEnum, "step_id 枚举应与 EdpConfig 中的步骤 ID 一致");
        }

        @Test
        @DisplayName("ToolCard 名称、描述完整")
        void testToolCard() {
            Tool tool = LiteTodoWriteTool.build(null);
            ToolCard card = tool.getCard();
            assertEquals(EdpaBusinessTools.TOOL_LITE_TODO_WRITE, card.getName());
            assertEquals(card.getName(), card.getId());
            assertTrue(card.getDescription().contains("Todo"), "描述应提及 Todo");
            assertTrue(card.getDescription().contains("edp-config.yaml"), "描述应提及 edp-config.yaml");
        }

        @Test
        @DisplayName("invoke 返回 accepted 状态和 todos 透传")
        void testInvoke() throws Exception {
            Tool tool = LiteTodoWriteTool.build(null);
            List<Map<String, Object>> todos = List.of(
                    Map.of("step_id", 1, "status", "done", "note", "已完成"),
                    Map.of("step_id", 2, "status", "pending", "note", "待处理"));

            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) tool.invoke(
                    Map.of("todos", todos),
                    Map.of());

            assertEquals(EdpaBusinessTools.TOOL_LITE_TODO_WRITE, result.get("tool"));
            assertEquals("accepted", result.get("status"));
            assertNotNull(result.get("todos"));
        }

        private static EdpConfig.TodolistStep createStep(int stepId, String content, String skill) {
            EdpConfig.TodolistStep step = new EdpConfig.TodolistStep();
            step.setStepId(stepId);
            step.setContent(content);
            step.setSkill(skill);
            return step;
        }
    }

    // -----------------------------------------------------------------------
    // EdpaToolRegistry — 名称 → 构建器映射
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("EdpaToolRegistry 注册表")
    class EdpaToolRegistryTest {

        @Test
        @DisplayName("五个内置工具名称均能成功构建")
        void testAllBuiltinToolsBuildable() {
            assertNotNull(EdpaToolRegistry.build(EdpaBusinessTools.TOOL_CALL_MCP, null));
            assertNotNull(EdpaToolRegistry.build(EdpaBusinessTools.TOOL_CALL_VERSATILE, null));
            assertNotNull(EdpaToolRegistry.build(EdpaBusinessTools.TOOL_CANCEL_TASK, null));
            assertNotNull(EdpaToolRegistry.build(EdpaBusinessTools.TOOL_ENHANCED_ASK_USER, null));
            assertNotNull(EdpaToolRegistry.build(EdpaBusinessTools.TOOL_LITE_TODO_WRITE, null));
        }

        @Test
        @DisplayName("未知工具名返回 null")
        void testUnknownToolReturnsNull() {
            assertNull(EdpaToolRegistry.build("nonexistent_tool", null));
        }

        @Test
        @DisplayName("bash 和 skill_tool 不在注册表中（由 DeepAgent 原生注册）")
        void testNativeToolsNotInRegistry() {
            assertFalse(EdpaToolRegistry.isBuiltin("bash"),
                    "bash 是 DeepAgent 原生工具，不在业务注册表");
            assertFalse(EdpaToolRegistry.isBuiltin("skill_tool"),
                    "skill_tool 是 DeepAgent 原生工具，不在业务注册表");
        }

        @Test
        @DisplayName("五个业务工具名称均判定为 builtin")
        void testBusinessToolsAreBuiltin() {
            assertTrue(EdpaToolRegistry.isBuiltin(EdpaBusinessTools.TOOL_CALL_MCP));
            assertTrue(EdpaToolRegistry.isBuiltin(EdpaBusinessTools.TOOL_CALL_VERSATILE));
            assertTrue(EdpaToolRegistry.isBuiltin(EdpaBusinessTools.TOOL_CANCEL_TASK));
            assertTrue(EdpaToolRegistry.isBuiltin(EdpaBusinessTools.TOOL_ENHANCED_ASK_USER));
            assertTrue(EdpaToolRegistry.isBuiltin(EdpaBusinessTools.TOOL_LITE_TODO_WRITE));
        }
    }

    // -----------------------------------------------------------------------
    // EdpaBusinessTools.build — 配置驱动注册
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("EdpaBusinessTools.build 配置驱动注册")
    class BuildMethodTest {

        @Test
        @DisplayName("actrule 为 null 时返回空列表")
        void testNullActruleReturnsEmpty() {
            List<Tool> tools = EdpaBusinessTools.build(null, null);
            assertTrue(tools.isEmpty());
        }

        @Test
        @DisplayName("allowed_tools 为空时返回空列表")
        void testEmptyAllowedToolsReturnsEmpty() {
            ActRuleConfig actrule = new ActRuleConfig();
            actrule.setAllowedTools(List.of());
            List<Tool> tools = EdpaBusinessTools.build(null, actrule);
            assertTrue(tools.isEmpty());
        }

        @Test
        @DisplayName("允许全部五个业务工具时，按 actrule 顺序注册")
        void testAllFiveToolsRegistered() {
            ActRuleConfig actrule = new ActRuleConfig();
            actrule.setAllowedTools(List.of(
                    EdpaBusinessTools.TOOL_CALL_MCP,
                    EdpaBusinessTools.TOOL_CALL_VERSATILE,
                    EdpaBusinessTools.TOOL_ENHANCED_ASK_USER,
                    EdpaBusinessTools.TOOL_LITE_TODO_WRITE,
                    EdpaBusinessTools.TOOL_CANCEL_TASK));

            List<Tool> tools = EdpaBusinessTools.build(null, actrule);
            assertEquals(5, tools.size(), "应注册全部五个业务工具");

            assertEquals(EdpaBusinessTools.TOOL_CALL_MCP, tools.get(0).getCard().getName());
            assertEquals(EdpaBusinessTools.TOOL_CALL_VERSATILE, tools.get(1).getCard().getName());
            assertEquals(EdpaBusinessTools.TOOL_ENHANCED_ASK_USER, tools.get(2).getCard().getName());
            assertEquals(EdpaBusinessTools.TOOL_LITE_TODO_WRITE, tools.get(3).getCard().getName());
            assertEquals(EdpaBusinessTools.TOOL_CANCEL_TASK, tools.get(4).getCard().getName());
        }

        @Test
        @DisplayName("bash 和 skill_tool 在列表中时被跳过（DeepAgent 原生）")
        void testNativeToolsSkipped() {
            ActRuleConfig actrule = new ActRuleConfig();
            actrule.setAllowedTools(List.of("bash", EdpaBusinessTools.TOOL_CALL_MCP, "skill_tool"));

            List<Tool> tools = EdpaBusinessTools.build(null, actrule);
            assertEquals(1, tools.size(), "bash 和 skill_tool 应被跳过，只注册 call_mcp");
            assertEquals(EdpaBusinessTools.TOOL_CALL_MCP, tools.get(0).getCard().getName());
        }

        @Test
        @DisplayName("未知工具名不导致崩溃，仅跳过")
        void testUnknownToolSkippedWithoutCrash() {
            ActRuleConfig actrule = new ActRuleConfig();
            actrule.setAllowedTools(List.of(EdpaBusinessTools.TOOL_CALL_MCP, "unknown_tool"));

            List<Tool> tools = EdpaBusinessTools.build(null, actrule);
            assertEquals(1, tools.size(), "unknown_tool 应被跳过");
            assertEquals(EdpaBusinessTools.TOOL_CALL_MCP, tools.get(0).getCard().getName());
        }

        @Test
        @DisplayName("只允许部分工具时只注册部分")
        void testPartialToolsRegistration() {
            ActRuleConfig actrule = new ActRuleConfig();
            actrule.setAllowedTools(List.of(
                    EdpaBusinessTools.TOOL_CALL_MCP,
                    EdpaBusinessTools.TOOL_CANCEL_TASK));

            List<Tool> tools = EdpaBusinessTools.build(null, actrule);
            assertEquals(2, tools.size());
            assertEquals(EdpaBusinessTools.TOOL_CALL_MCP, tools.get(0).getCard().getName());
            assertEquals(EdpaBusinessTools.TOOL_CANCEL_TASK, tools.get(1).getCard().getName());
        }
    }
}
