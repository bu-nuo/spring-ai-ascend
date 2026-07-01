package com.huawei.ascend.edp.rail;

import com.huawei.ascend.edp.config.EdpaEventType;
import com.huawei.ascend.edp.config.ScriptConstants;
import com.huawei.ascend.edp.config.ScriptResolver;
import com.huawei.ascend.edp.config.SysScriptsConfig;
import com.huawei.ascend.edp.config.ToolConstants;
import com.huawei.ascend.edp.enhancer.TodoSessionResolver;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.ModelCallInputs;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;
import com.openjiuwen.core.singleagent.interrupt.ToolInterruptException;
import com.openjiuwen.core.session.stream.OutputSchema;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.rails.DeepAgentRail;
import com.openjiuwen.harness.rails.TaskPlanningRail;
import com.openjiuwen.harness.tools.TodoItem;
import com.openjiuwen.harness.tools.TodoStatus;
import com.openjiuwen.harness.tools.TodoTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * EDPAgent 思维链事件发射 Rail。
 *
 * <p>严格按照 {@code EDPA_EventFlow_Design.md} 设计文档实现事件流处理。
 * 设计文档第四章定义了 5 个验收场景（S1~S5），本章为验收标准 + 开发规约 + 测试基线。</p>
 *
 * <p>事件发射职责（对齐设计文档 §7.1 各 Hook 职责表）：</p>
 * <ul>
 *     <li>{@code beforeInvoke}：发射 {@code conversation_start}；<b>不发跨轮 todolist</b>（Rule 9），只静默初始化 todo 状态追踪。</li>
 *     <li>{@code beforeModelCall}：不发任何事件（think_start 移到 afterModelCall，§7.1）。</li>
 *     <li>{@code afterModelCall}：有 reasoning → {@code think_start → think_chunk → think_end}（每轮一对，严格配对）；
 *         finish_reason=stop 且无 tool_calls → {@code final_answer_start → final_answer_chunk → final_answer_end}。
 *         think_end 在 final_answer_start 之前（Rule 3 / C4）。</li>
 *     <li>{@code beforeToolCall}：业务工具（call_versatile/call_mcp）→ {@code tool_start}；
 *         PLAN_FIRST 拦截 → 不发。</li>
 *     <li>{@code afterToolCall}：业务工具 → {@code tool_end}；todo_create/todo_modify → 每次 status 变化必发
 *         {@code todolist_start/item×N/end}（逐条，Rule 12）；位置：todo_end 转移 todolist 在 todo_end 之后（Rule 10），
 *         todo_start 转移 todolist 在 todo_start 之前（Rule 11），路径切换独立；ask_user 恢复 → {@code interrupt_end}。</li>
 *     <li>{@code onModelException}：如 think_start 未闭合 → {@code think_end}；{@code error_event{stage:model}}；
 *         {@code conversation_end}（异常不破坏配对，Rule 8）。</li>
 *     <li>{@code onToolException}：ToolInterruptException → {@code interrupt_start{tool}}（正常中断）；
 *         其他 → 如 tool_start 未闭合 → {@code tool_end{status:failed}}；{@code error_event{stage:tool}}；
 *         {@code conversation_end}。</li>
 *     <li>{@code afterInvoke}：{@code conversation_end}（如未已关闭）+ 清理会话状态。</li>
 * </ul>
 *
 * <p>优先级 priority=80：低于 TaskPlanningRail(90)，保证 afterToolCall 时读取刷新后的 todo 缓存。
 * 框架排序：数字越大越早执行；80 < 90 表示本 Rail 在 TaskPlanningRail 之后执行。</p>
 */
public class EdpaEventRail extends DeepAgentRail {

    private static final Logger LOGGER = LoggerFactory.getLogger(EdpaEventRail.class);

    private static final String TOOL_TODO_CREATE = ToolConstants.TODO_CREATE;
    private static final String TOOL_TODO_MODIFY = ToolConstants.TODO_MODIFY;
    private static final String TOOL_CALL_MCP = ToolConstants.CALL_MCP;
    private static final String TOOL_CALL_VERSATILE = ToolConstants.CALL_VERSATILE;
    private static final String TOOL_ASK_USER = ToolConstants.ASK_USER;

    /**
     * 上一轮发射的 todolist 指纹，用于检测任务列表是否变化并决定是否重推。
     * key 为 sessionId，value 为 todolist 内容指纹。
     */
    private final Map<String, String> lastTodolistFingerprint = new ConcurrentHashMap<>();

    /**
     * 标记当前 conversation 是否已经发射过 interrupt_start（ask_user HITL），
     * 用于在 interrupt 恢复时配对发射 interrupt_end。
     * key 为 sessionId，value 为 true/false。
     */
    private final Map<String, Boolean> interruptActive = new ConcurrentHashMap<>();

    /**
     * 标记当前轮 think_start 是否尚未闭合（think_end 未发）。
     * key 为 sessionId。afterModelCall 发 think_start 前置 true，发 think_end 后置 false。
     * 用于 onModelException 关闭本轮 think_start，保证 think_start == think_end（Rule 2）。
     */
    private final Map<String, Boolean> thinkOpen = new ConcurrentHashMap<>();

    /**
     * 标记当前轮 tool_start 是否尚未闭合（tool_end 未发）。
     * key 为 sessionId。beforeToolCall 发 tool_start 前置 true，afterToolCall 发 tool_end 后置 false。
     * 用于 onToolException 关闭本轮 tool_start，保证 tool_start == tool_end（Rule 6）。
     */
    private final Map<String, Boolean> toolOpen = new ConcurrentHashMap<>();

    /**
     * 标记当前 conversation 的 conversation_end 是否已发射（异常处理时已关闭）。
     * key 为 sessionId。onModelException/onToolException(非中断) 发 conversation_end 后置 true，
     * afterInvoke 检查此标记避免重复发射。
     */
    private final Map<String, Boolean> conversationClosed = new ConcurrentHashMap<>();

    /**
     * 上一轮各 todo 的状态快照（id→status），用于检测状态转移并决定是否发射 todo_start/todo_end。
     * 外层 key 为 sessionId，内层 key 为 todoId，value 为上一轮的 TodoStatus。
     * 核心用途：区分 PENDING→CANCELLED（路径切换，不发 start/end）与 IN_PROGRESS→CANCELLED（执行中取消，发 end）。
     */
    private final Map<String, Map<String, TodoStatus>> prevTodoStatus = new ConcurrentHashMap<>();

    /**
     * 被规划前置守卫拦截（_plan_first_block）的业务工具 callId 集合，
     * afterToolCall 据此跳过 tool_end 发射（未真正执行的工具不发配对事件）。
     */
    private final Set<String> skippedToolCallIds = ConcurrentHashMap.newKeySet();

    /**
     * 持有 DeepAgent 引用，用于在 afterToolCall 中查找 TaskPlanningRail 读取最新 todos 缓存。
     */
    private final DeepAgent deepAgent;

    /**
     * 话术配置（A 面：生命周期事件话术 content）。null 表示不填 content（等价现状，保回归安全）。
     */
    private final SysScriptsConfig scripts;

    /** lazy 创建 TodoTool，路径与 Core TaskPlanningRail / EdpaTodoRail 一致（.todo）。 */
    private volatile TodoTool todoTool;

    public EdpaEventRail(DeepAgent deepAgent) {
        this(deepAgent, null);
    }

    public EdpaEventRail(DeepAgent deepAgent, SysScriptsConfig scripts) {
        this.deepAgent = deepAgent;
        this.scripts = scripts;
    }

    @Override
    public int priority() {
        return 80;
    }

    // ═══════════════════════════════════════════════════
    // Hook 实现（对齐设计文档 §7.1 各 Hook 职责表）
    // ═══════════════════════════════════════════════════

    /**
     * beforeInvoke：发射 conversation_start；不发跨轮 todolist（Rule 9）。
     *
     * <p>设计文档 v1.1 Rule 9：conversation_start 时不再立即重放 todolist。
     * 前端跨轮自行持久化状态。本方法只静默初始化 todo 状态追踪（指纹 + prevTodoStatus），
     * 供 afterToolCall 检测状态转移，但不发任何事件。</p>
     */
    @Override
    public void beforeInvoke(AgentCallbackContext ctx) {
        String sid = sessionId(ctx);
        conversationClosed.remove(sid);
        LOGGER.info("[EDPA-DIAG] beforeInvoke sid={}, todosAtStart={} -> emit conversation_start (no cross-round todolist, Rule 9)",
                sid, diagTodosSummary(ctx));
        emit(ctx, EdpaEventType.CONVERSATION_START, Map.of());
        initTodoStateSilent(ctx, sid);
    }

    /**
     * beforeModelCall：不发任何事件（think_start 移到 afterModelCall，§7.1）。
     *
     * <p>设计文档 §7.1 明确：beforeModelCall 不发任何事件。think_start/think_chunk/think_end
     * 全部在 afterModelCall 中根据 LLM 返回的 reasoning_content 决定是否发射（§7.2）。</p>
     */
    @Override
    public void beforeModelCall(AgentCallbackContext ctx) {
        // 不发任何事件（设计文档 §7.1）
    }

    /**
     * afterModelCall：有 reasoning → think 对；finish_reason=stop 且无 tool_calls → final_answer 对。
     *
     * <p>设计文档 §7.2 判断流程：</p>
     * <pre>
     * ① 有 reasoning → emit think_start → think_chunk{content:reasoning} → think_end
     * ② finish_reason=stop 且无 tool_calls → emit final_answer_start → final_answer_chunk{content:content} → final_answer_end
     * ③ 有 tool_calls → 不发 final_answer，由后续 beforeToolCall/afterToolCall 处理
     * </pre>
     *
     * <p>think_end 在 final_answer_start 之前（Rule 3 / C4）。
     * think_start 数 == think_end 数（每轮 LLM 推理一对，严格配对，无 quirk，Rule 2）。</p>
     */
    /**
     * afterModelCall：有 think 内容 → think_start → think_chunk → think_end（每轮一对）；
     * finish_reason=stop 且无 tool_calls → final_answer 对。
     *
     * <p>think_chunk 内容：优先 reasoning_content（推理模型），无则取 content（非推理模型回退）。
     * 每轮 LLM 调用发一对 think_start/think_end，严格配对。</p>
     */
    @Override
    public void afterModelCall(AgentCallbackContext ctx) {
        if (!(ctx.getInputs() instanceof ModelCallInputs inputs)) {
            return;
        }
        diagModelResponse(ctx, inputs);
        String sid = sessionId(ctx);
        Object response = inputs.getResponse();
        if (!(response instanceof AssistantMessage msg)) {
            return;
        }

        String reasoning = msg.getReasoningContent();
        String content = safe(msg.getContentAsString());

        // think_chunk 内容：优先 reasoning_content（推理模型），无则取 content（非推理模型回退）。
        // reasoning 仅为标点占位（如 "." / "。"）时视为无数据，回退到 content（LLM 实际输出）。
        String thinkContent = (reasoning != null && reasoning.strip().length() > 1) ? reasoning : content;

        // planning_start（无配对，开始规划）：必须在 think_start 之前发射（EdpaEventType 枚举生命周期
        // 顺序：request_start → planning_start → think_start → … → todolist_start）。仅当本轮模型决定
        // 调用 todo_create（主动规划）时发一次（per-request 去重）。寒暄/超范围/直接作答的轮次
        // tool_calls 不含 todo_create，不发——故「你好」不会出现 planning_start。
        if (containsTodoCreate(msg)) {
            maybeEmitPlanningStart(ctx, sid);
        }

        // ① 发 think 对（每轮 LLM 一对，严格配对，Rule 2）
        thinkOpen.put(sid, true);
        emit(ctx, EdpaEventType.THINK_START, Map.of());
        if (!thinkContent.isBlank()) {
            emit(ctx, EdpaEventType.THINK_CHUNK, Map.of("content", thinkContent));
        }
        emit(ctx, EdpaEventType.THINK_END, Map.of());
        thinkOpen.put(sid, false);

        // ② finish_reason=stop 且无 tool_calls → 发 final_answer 对
        if (isFinalAnswer(msg)) {
            emit(ctx, EdpaEventType.FINAL_ANSWER_START, Map.of());
            emit(ctx, EdpaEventType.FINAL_ANSWER_CHUNK, Map.of("content", content));
            emit(ctx, EdpaEventType.FINAL_ANSWER_END, Map.of());
        }
        // ③ 有 tool_calls → 不发 final_answer，由后续 beforeToolCall/afterToolCall 处理
    }

    /**
     * beforeToolCall：业务工具（call_versatile/call_mcp）发射 tool_start。
     *
     * <p>设计文档 §7.1 + Rule 6：仅业务工具发 tool_start。
     * todo_create / todo_modify / ask_user / read_file 不发 tool_start。
     * PLAN_FIRST 拦截（_plan_first_block=true）时不发 tool_start，记录 id 供 afterToolCall 跳过 tool_end。</p>
     */
    @Override
    public void beforeToolCall(AgentCallbackContext ctx) {
        if (!(ctx.getInputs() instanceof ToolCallInputs inputs)) {
            return;
        }
        String toolName = inputs.getToolName();
        if (!isBusinessTool(toolName)) {
            return;
        }
        String sid = sessionId(ctx);
        // PLAN_FIRST 真拦截（EdpaTodoRail 未规划 todo 时拦截，工具未执行）→ 不发 tool_start；
        // 但视为「进入规划阶段」（强制规划），发 planning_start（per-request 一次）。
        if (Boolean.TRUE.equals(ctx.getExtra().get(ScriptConstants.KEY_PLAN_FIRST_BLOCK))) {
            maybeEmitPlanningStart(ctx, sid);
            LOGGER.info("[EDPA-DIAG] beforeToolCall tool={} PLAN_FIRST_BLOCK (真拦截, 不发 tool_start)", toolName);
            ToolCall tc = inputs.getToolCall();
            if (tc != null && tc.getId() != null) {
                skippedToolCallIds.add(tc.getId());
            }
            return;
        }
        // 中断接管型（_skip_tool=true 但非 PLAN_FIRST）或真实执行：工具已执行/将执行 → 发 tool_start
        String mode = Boolean.TRUE.equals(ctx.getExtra().get(ScriptConstants.KEY_SKIP_TOOL)) ? "interrupt-handled" : "real-exec";
        LOGGER.info("[EDPA-DIAG] beforeToolCall tool={} mode={} -> emit tool_start", toolName, mode);
        toolOpen.put(sid, true);
        emit(ctx, EdpaEventType.TOOL_START, Map.of(
                "tool", toolName,
                "content", ScriptResolver.toolStart(scripts, toolName)));
    }

    /**
     * afterToolCall：业务工具发射 tool_end；ask_user 中断恢复发射 interrupt_end；
     * todo_create/todo_modify 后发射 todolist + todo_start/todo_end。
     *
     * <p>设计文档 §7.1 + §7.3 + Rule 4/5/6/7：</p>
     * <ul>
     *     <li>业务工具 → tool_end{tool, data}（Rule 6）</li>
     *     <li>ask_user 恢复（_skip_tool=true 且 interruptActive）→ interrupt_end{tool}（Rule 7）</li>
     *     <li>todo_create/todo_modify → todolist_start/item/end（指纹变化时）+ todo_start/todo_end（基于状态转移，§7.3）</li>
     * </ul>
     */
    @Override
    public void afterToolCall(AgentCallbackContext ctx) {
        if (!(ctx.getInputs() instanceof ToolCallInputs inputs)) {
            return;
        }
        String toolName = inputs.getToolName();
        String sid = sessionId(ctx);

        // 业务工具完成 → tool_end
        if (isBusinessTool(toolName)) {
            ToolCall tc = inputs.getToolCall();
            if (tc != null && tc.getId() != null && skippedToolCallIds.remove(tc.getId())) {
                LOGGER.info("[EDPA-DIAG] afterToolCall tool={} SKIP (PLAN_FIRST 拦截, 不发 tool_end)", toolName);
                return;
            }
            Object toolResult = inputs.getToolResult();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("tool", toolName);
            payload.put("data", toolResult != null ? toolResult : "");
            payload.put("content", ScriptResolver.toolEnd(scripts, toolName));
            LOGGER.info("[EDPA-DIAG] afterToolCall tool={} -> emit tool_end", toolName);
            emit(ctx, EdpaEventType.TOOL_END, payload);
            toolOpen.put(sid, false);
            return;
        }

        // ask_user 中断恢复：检测 _skip_tool 标记 + interruptActive 配对
        if (TOOL_ASK_USER.equals(toolName) && Boolean.TRUE.equals(ctx.getExtra().get(ScriptConstants.KEY_SKIP_TOOL))) {
            if (interruptActive.getOrDefault(sid, false)) {
                LOGGER.info("[EDPA-DIAG] afterToolCall tool={} interrupt resume -> emit interrupt_end", toolName);
                emit(ctx, EdpaEventType.INTERRUPT_END, Map.of("tool", toolName));
                interruptActive.remove(sid);
            }
            return;
        }

        // todo 相关事件
        if (TOOL_TODO_CREATE.equals(toolName) || TOOL_TODO_MODIFY.equals(toolName)) {
            LOGGER.info("[EDPA-DIAG] afterToolCall todo tool={} -> emitTodoEvents", toolName);
            emitTodoEvents(ctx);
        }
    }

    /**
     * onModelException：关闭未闭合的 think_start（保 Rule 2）+ error_event{stage:model} + conversation_end（保 Rule 1）。
     *
     * <p>设计文档 Rule 8：异常不破坏配对。所有已打开的 start 必须先发对应 end 关闭，
     * 再发 error_event，最后 conversation_end。像关括号一样从内到外依次关闭。</p>
     */
    @Override
    public void onModelException(AgentCallbackContext ctx) {
        String sid = sessionId(ctx);
        if (Boolean.TRUE.equals(thinkOpen.get(sid))) {
            LOGGER.info("[EDPA-DIAG] onModelException sid={} -> emit think_end (关闭未闭合 think_start)", sid);
            emit(ctx, EdpaEventType.THINK_END, Map.of());
            thinkOpen.put(sid, false);
        }
        Exception ex = ctx.getException();
        LOGGER.error("[EDPA-DIAG] onModelException -> emit error_event(stage=model), type={}, msg={}",
                ex == null ? "null" : ex.getClass().getName(),
                ex == null ? "null" : truncate(String.valueOf(ex.getMessage()), 200));
        emit(ctx, EdpaEventType.ERROR_EVENT, Map.of("stage", "model"));
        emitConversationEnd(ctx, sid);
    }

    /**
     * onToolException：ToolInterruptException → interrupt_start（正常中断）；
     * 其他异常 → tool_end{status:failed}（如未闭合）+ error_event{stage:tool} + conversation_end。
     *
     * <p>设计文档 Rule 7 + Rule 8：</p>
     * <ul>
     *     <li>ToolInterruptException 是正常中断机制（如 ask_user 触发中断等待用户输入），
     *         不应作为 error_event。发射 interrupt_start 表示"中断等待用户输入"。
     *         interrupt_start 在本轮末（conversation_end 前），interrupt_end 在下轮首（conversation_start 后）。</li>
     *     <li>其他工具异常：如 tool_start 已发未闭合 → emit tool_end{status:failed}（保 Rule 6）；
     *         emit error_event{stage:tool}；emit conversation_end（保 Rule 1）。</li>
     * </ul>
     */
    @Override
    public void onToolException(AgentCallbackContext ctx) {
        String sid = sessionId(ctx);
        Exception exception = ctx.getException();
        LOGGER.info("[EDPA-DIAG] onToolException exception type={}, msg={}",
                exception == null ? "null" : exception.getClass().getName(),
                exception == null ? "null" : truncate(String.valueOf(exception.getMessage()), 200));

        // ToolInterruptException 是正常中断机制（如 ask_user），不应作为 error_event
        // 框架可能将 ToolInterruptException 包装在 RuntimeException 中（"Error invoking rail callback: beforeToolCall"），
        // 需要递归检查 cause 链找到被包装的 ToolInterruptException。
        Throwable cause = exception;
        while (cause != null && !(cause instanceof ToolInterruptException)) {
            cause = cause.getCause();
        }
        if (cause instanceof ToolInterruptException) {
            interruptActive.put(sid, true);
            String toolName = "";
            if (ctx.getInputs() instanceof ToolCallInputs inputs) {
                toolName = inputs.getToolName();
                // F3-fix：ask_user 话术在 onToolException 解析（异常处理回调必触发；
                // beforeToolCall(80) 被 AskUserTemplateRail(85) 抛异常中断、不可达）。
                if (TOOL_ASK_USER.equals(toolName)) {
                    ScriptResolver.resolveAskUser(scripts, inputs.getToolArgs(), ctx.getExtra());
                }
            }
            // content：优先读刚解析的业务话术（_edp_response_template），缺则回落 interrupt_start 配置兜底。
            Object rt = ctx.getExtra().get(ScriptConstants.KEY_RESPONSE_TEMPLATE);
            String content = (rt != null && !String.valueOf(rt).isBlank())
                    ? String.valueOf(rt) : ScriptResolver.interruptStart(scripts);
            LOGGER.info("[EDPA-DIAG] onToolException ToolInterruptException -> emit interrupt_start(tool={})", toolName);
            emit(ctx, EdpaEventType.INTERRUPT_START, Map.of("tool", toolName, "content", content));
            return; // 正常中断，不发 error_event，conversation_end 由 afterInvoke 发射
        }

        // 其他工具异常 → 先关 tool（如未闭合），再报错，最后关 conversation
        if (Boolean.TRUE.equals(toolOpen.get(sid))) {
            String toolName = "";
            if (ctx.getInputs() instanceof ToolCallInputs inputs) {
                toolName = inputs.getToolName();
            }
            LOGGER.info("[EDPA-DIAG] onToolException -> emit tool_end(status=failed) (关闭未闭合 tool_start)", sid);
            emit(ctx, EdpaEventType.TOOL_END, Map.of("tool", toolName, "status", "failed"));
            toolOpen.put(sid, false);
        }
        LOGGER.error("[EDPA-DIAG] onToolException -> emit error_event(stage=tool)");
        emit(ctx, EdpaEventType.ERROR_EVENT, Map.of("stage", "tool"));
        emitConversationEnd(ctx, sid);
    }

    /**
     * afterInvoke：发射 conversation_end（如未已关闭）+ 清理本轮会话状态。
     *
     * <p>设计文档 §7.1：请求结束发射 conversation_end。
     * 如果异常处理已发射 conversation_end（conversationClosed=true），则跳过避免重复。</p>
     *
     * <p><b>注意</b>：interruptActive 不在此清理——它需要跨轮持久化以配对 interrupt_start（本轮 onToolException）
     * 与 interrupt_end（下轮 afterToolCall ask_user 恢复），见 Rule 7。
     * interruptActive 仅在 afterToolCall 发射 interrupt_end 时清理。
     * lastTodolistFingerprint / prevTodoStatus 在下轮 beforeInvoke 的跨轮快照中重新初始化。</p>
     */
    @Override
    public void afterInvoke(AgentCallbackContext ctx) {
        String sid = sessionId(ctx);
        LOGGER.info("[EDPA-DIAG] afterInvoke sid={} -> emit conversation_end (if not already closed)", sid);
        emitConversationEnd(ctx, sid);
        // 清理本轮状态（interruptActive 跨轮持久化，不在此清理）
        lastTodolistFingerprint.remove(sid);
        thinkOpen.remove(sid);
        toolOpen.remove(sid);
        conversationClosed.remove(sid);
        prevTodoStatus.remove(sid);
    }

    // ═══════════════════════════════════════════════════
    // 私有辅助方法
    // ═══════════════════════════════════════════════════

    /**
     * 发射 conversation_end（带防重入保护）。
     *
     * <p>异常处理（onModelException / onToolException 非中断）可能已发射 conversation_end，
     * afterInvoke 检查 conversationClosed 标记避免重复发射。</p>
     */
    private void emitConversationEnd(AgentCallbackContext ctx, String sid) {
        if (Boolean.TRUE.equals(conversationClosed.get(sid))) {
            return;
        }
        emit(ctx, EdpaEventType.CONVERSATION_END, Map.of());
        conversationClosed.put(sid, true);
    }

    /**
     * 静默初始化 todo 状态追踪（beforeInvoke 时调用，不发事件）。
     *
     * <p>设计文档 v1.1 Rule 9：conversation_start 不再发跨轮 todolist。
     * 本方法只加载当前 todos 并初始化 lastTodolistFingerprint 与 prevTodoStatus，
     * 供后续 afterToolCall 检测状态转移，<b>不发 todolist_start/item/end</b>。</p>
     */
    private void initTodoStateSilent(AgentCallbackContext ctx, String sid) {
        List<TodoItem> todos = loadCurrentTodos(ctx);
        if (todos == null || todos.isEmpty()) {
            return;
        }
        lastTodolistFingerprint.put(sid, fingerprint(todos));
        updatePrevTodoStatus(sid, todos);
    }

    /**
     * 发射 todo 事件（afterToolCall 中 todo_create/todo_modify 后调用）。
     *
     * <p>设计文档 v1.1 §7.3 + Rule 4/5/10/11：每次状态变化必发 todolist（逐条），
     * 位置由"不进 [todo_start…todo_end] 对内部"决定：</p>
     * <ul>
     *     <li>todo_end 转移（IN_PROGRESS→COMPLETED/CANCELLED）：先 todo_end，后 todolist（Rule 10）。</li>
     *     <li>todo_start 转移（建表/PENDING→IN_PROGRESS）：先 todolist，后 todo_start（Rule 11）。</li>
     *     <li>路径切换（PENDING→CANCELLED）：独立 todolist（无 todo_start/todo_end，Rule 4④）。</li>
     * </ul>
     */
    private void emitTodoEvents(AgentCallbackContext ctx) {
        List<TodoItem> todos = loadCurrentTodos(ctx);
        if (todos == null || todos.isEmpty()) {
            LOGGER.info("[EDPA-DIAG] emitTodoEvents todos 为空, 跳过");
            return;
        }

        String sid = sessionId(ctx);
        String fp = fingerprint(todos);
        String lastFp = lastTodolistFingerprint.get(sid);
        boolean changed = !fp.equals(lastFp);
        LOGGER.info("[EDPA-DIAG] emitTodoEvents todos={}, fpChanged={} (prev={} new={})",
                todos.size(), changed, lastFp, fp);

        Map<String, TodoStatus> prevMap = prevTodoStatus.getOrDefault(sid, new LinkedHashMap<>());

        // 判定本轮转移类型
        boolean hasEnd = false;
        boolean hasStart = false;
        boolean hasPathSwitch = false;
        for (TodoItem todo : todos) {
            TodoStatus cur = todo.getStatus();
            TodoStatus prev = prevMap.get(todo.getId());
            if (isInProgress(cur) && !isInProgress(prev)) {
                hasStart = true;
            }
            if (isCompletedLike(cur) && isInProgress(prev)) {
                hasEnd = true;
            }
            if (cur == TodoStatus.CANCELLED && isInProgress(prev)) {
                hasEnd = true;
            }
            if (cur == TodoStatus.CANCELLED && !isInProgress(prev) && prev != TodoStatus.CANCELLED && prev != null) {
                hasPathSwitch = true;
            }
        }

        // ① end 转移：先 todo_end，后 todolist（Rule 10/11，todolist 在对外的结束侧）
        if (hasEnd) {
            emitTodoEnds(ctx, todos, prevMap);
            if (changed) {
                emitTodolistPerItem(ctx, todos);
            }
        }
        // ② start 转移：先 todolist，后 todo_start（Rule 11，todolist 在对外的开始侧）
        if (hasStart) {
            if (changed) {
                emitTodolistPerItem(ctx, todos);
            }
            emitTodoStarts(ctx, todos, prevMap);
        }
        // ③ 路径切换：独立 todolist（无 todo_start/todo_end）
        if (hasPathSwitch && !hasEnd && !hasStart) {
            if (changed) {
                emitTodolistPerItem(ctx, todos);
            }
        }

        if (changed) {
            lastTodolistFingerprint.put(sid, fp);
        }
        updatePrevTodoStatus(sid, todos);
    }

    /**
     * 发射 todolist_start → todolist_item{单条}×N → todolist_end（逐条，Rule 12）。
     *
     * <p>设计文档 v1.1 Rule 12：N 条 todo = N 个 todolist_item 事件，每个携带单条 todo（非 tasks 数组）。</p>
     */
    private void emitTodolistPerItem(AgentCallbackContext ctx, List<TodoItem> todos) {
        emit(ctx, EdpaEventType.TODOLIST_START, Map.of("content", ScriptResolver.todolistStart(scripts)));
        for (TodoItem todo : todos) {
            emit(ctx, EdpaEventType.TODOLIST_ITEM, toTaskMap(todo));
        }
        emit(ctx, EdpaEventType.TODOLIST_END, Map.of("content", ScriptResolver.todolistEnd(scripts)));
    }

    /**
     * 发射 todo_start{PENDING/null → IN_PROGRESS}（任务开始执行）。
     *
     * <p>设计文档 v1.1 Rule 5：PENDING/null→IN_PROGRESS 发 todo_start。</p>
     */
    private void emitTodoStarts(AgentCallbackContext ctx, List<TodoItem> todos, Map<String, TodoStatus> prevMap) {
        for (TodoItem todo : todos) {
            TodoStatus current = todo.getStatus();
            TodoStatus prev = prevMap.get(todo.getId());
            if (isInProgress(current) && !isInProgress(prev)) {
                LOGGER.info("[EDPA-DIAG] emitTodoStarts todo {} {}->IN_PROGRESS -> emit todo_start",
                        todo.getId(), prev);
                emit(ctx, EdpaEventType.TODO_START, Map.of(
                        "id", todo.getId(),
                        "content", ScriptResolver.todoStart(scripts, safe(todo.getContent()))));
            }
        }
    }

    /**
     * 发射 todo_end{IN_PROGRESS → COMPLETED/CANCELLED}（与 todo_start 配对）。
     *
     * <p>设计文档 v1.1 Rule 5：IN_PROGRESS→COMPLETED/DONE 发 todo_end{completed}；
     * IN_PROGRESS→CANCELLED 发 todo_end{cancelled}。PENDING→CANCELLED（路径切换）在此不发，
     * 由 emitTodoEvents 的路径切换分支发独立 todolist（Rule 4④）。</p>
     */
    private void emitTodoEnds(AgentCallbackContext ctx, List<TodoItem> todos, Map<String, TodoStatus> prevMap) {
        for (TodoItem todo : todos) {
            TodoStatus current = todo.getStatus();
            TodoStatus prev = prevMap.get(todo.getId());

            // IN_PROGRESS → COMPLETED/DONE：todo_end{completed}
            if (isCompletedLike(current) && isInProgress(prev)) {
                LOGGER.info("[EDPA-DIAG] emitTodoEnds todo {} IN_PROGRESS->COMPLETED -> emit todo_end(completed)",
                        todo.getId());
                emit(ctx, EdpaEventType.TODO_END, Map.of(
                        "id", todo.getId(),
                        "content", ScriptResolver.todoEnd(scripts, safe(todo.getContent())),
                        "status", "completed"));
            }

            // IN_PROGRESS → CANCELLED：todo_end{cancelled}
            if (current == TodoStatus.CANCELLED && isInProgress(prev)) {
                LOGGER.info("[EDPA-DIAG] emitTodoEnds todo {} IN_PROGRESS->CANCELLED -> emit todo_end(cancelled)",
                        todo.getId());
                emit(ctx, EdpaEventType.TODO_END, Map.of(
                        "id", todo.getId(),
                        "content", ScriptResolver.todoEnd(scripts, safe(todo.getContent())),
                        "status", "cancelled"));
            }
        }
    }

    /**
     * 更新 prevTodoStatus 快照（供下次状态转移比较）。
     */
    private void updatePrevTodoStatus(String sid, List<TodoItem> todos) {
        Map<String, TodoStatus> map = new LinkedHashMap<>();
        for (TodoItem todo : todos) {
            map.put(todo.getId(), todo.getStatus());
        }
        prevTodoStatus.put(sid, map);
    }

    /**
     * 读取当前会话的 todos。
     *
     * <p>主路径：从 TodoTool 落盘文件读，用与 EdpaTodoRail 注入一致的「转义真实 sessionId」，
     * 保证多会话隔离且能读到 todo_create 写入的数据。</p>
     *
     * <p>兜底路径：TodoTool 不可用（workspace 未就绪，如单元测试 mock DeepAgent 无 workspace）时，
     * 回落到 TaskPlanningRail.cachedTodos 读缓存，保证事件发射逻辑可被确定性单测驱动。</p>
     */
    private List<TodoItem> loadCurrentTodos(AgentCallbackContext ctx) {
        String rawSid = sessionId(ctx);
        String sid = TodoSessionResolver.sanitizeSessionId(rawSid);
        TodoTool tool = getTodoTool();
        if (tool != null) {
            try {
                List<TodoItem> todos = tool.load(sid);
                return todos != null ? todos : new ArrayList<>();
            } catch (Exception e) {
                LOGGER.debug("EdpaEventRail.loadCurrentTodos from disk failed: {}", e.getMessage());
            }
        }
        // 兜底：TodoTool 不可用时从 TaskPlanningRail 缓存读
        return loadFromTaskPlanningCache(rawSid);
    }

    /**
     * 从已注册的 TaskPlanningRail 缓存读 todos（TodoTool 不可用时的兜底）。
     */
    private List<TodoItem> loadFromTaskPlanningCache(String sid) {
        if (deepAgent == null) {
            return null;
        }
        try {
            for (Object rail : deepAgent.getRegisteredRails()) {
                if (rail instanceof TaskPlanningRail tpr) {
                    List<TodoItem> todos = tpr.cachedTodos(sid);
                    return todos != null ? todos : new ArrayList<>();
                }
            }
        } catch (Exception e) {
            LOGGER.debug("EdpaEventRail.loadFromTaskPlanningCache failed: {}", e.getMessage());
        }
        return null;
    }

    /** lazy 创建 TodoTool，路径与 Core TaskPlanningRail / EdpaTodoRail 一致（.todo）。 */
    private TodoTool getTodoTool() {
        if (todoTool != null) {
            return todoTool;
        }
        try {
            String todoPath = deepAgent.getWorkspace().root().resolve(".todo").toString();
            todoTool = new TodoTool(todoPath);
            return todoTool;
        } catch (Exception e) {
            LOGGER.warn("EdpaEventRail failed to create TodoTool: {}", e.getMessage());
            return null;
        }
    }

    // ═══════════════════════════════════════════════════
    // 静态辅助方法
    // ═══════════════════════════════════════════════════

    private static boolean isBusinessTool(String toolName) {
        return TOOL_CALL_MCP.equals(toolName) || TOOL_CALL_VERSATILE.equals(toolName);
    }

    /**
     * planning_start 一次性发射（per-request 去重）。
     *
     * <p>UC-C05 + EdpaEventType 枚举：planning_start 语义=「Agent 进入规划阶段」，无配对，
     * 在 think_start 之前发射。触发点：① afterModelCall 检测到本轮模型决定调用 todo_create
     * （主动规划）；② beforeToolCall 检测到 PLAN_FIRST 拦截（强制规划）。寒暄/超范围/直接作答
     * 的请求不发。per-request 去重保证同一请求内只发一次。</p>
     */
    private void maybeEmitPlanningStart(AgentCallbackContext ctx, String sid) {
        if (Boolean.TRUE.equals(ctx.getExtra().get(ScriptConstants.KEY_PLANNING_START_SENT))) {
            return;
        }
        ctx.getExtra().put(ScriptConstants.KEY_PLANNING_START_SENT, Boolean.TRUE);
        String content = ScriptResolver.resolve(scripts, EdpaEventType.PLANNING_START.wireName(), Map.of());
        LOGGER.info("[EDPA-DIAG] sid={} -> emit planning_start (planning entry, before think_start)", sid);
        emit(ctx, EdpaEventType.PLANNING_START, Map.of("content", content));
    }

    /** 当前模型响应的 tool_calls 是否包含 todo_create（即 LLM 主动进入规划）。 */
    private static boolean containsTodoCreate(AssistantMessage msg) {
        List<ToolCall> tcs = msg.getToolCalls();
        if (tcs == null || tcs.isEmpty()) {
            return false;
        }
        for (ToolCall tc : tcs) {
            if (tc != null && ToolConstants.TODO_CREATE.equals(tc.getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInProgress(TodoStatus status) {
        return status == TodoStatus.IN_PROGRESS;
    }

    private static boolean isCompletedLike(TodoStatus status) {
        return status == TodoStatus.COMPLETED || status == TodoStatus.DONE;
    }

    /**
     * 判断当前 model call 是否是最终回答。
     *
     * <p>条件：finish_reason=stop 且 AssistantMessage 不含 tool_calls（§7.2）。</p>
     */
    private static boolean isFinalAnswer(AssistantMessage msg) {
        String finishReason = msg.getFinishReason();
        boolean hasToolCalls = msg.getToolCalls() != null && !msg.getToolCalls().isEmpty();
        return "stop".equals(finishReason) && !hasToolCalls;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String sessionId(AgentCallbackContext ctx) {
        try {
            return ctx.getSession().getSessionId();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * 单条 todo → Map（供 todolist_item 逐条 payload，Rule 12）。
     */
    private static Map<String, Object> toTaskMap(TodoItem todo) {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("id", todo.getId());
        task.put("content", todo.getContent());
        task.put("description", todo.getDescription());
        task.put("status", todo.getStatus() != null ? todo.getStatus().name().toLowerCase() : null);
        task.put("depends_on", todo.getDependsOn());
        return task;
    }

    private static String fingerprint(List<TodoItem> todos) {
        StringBuilder sb = new StringBuilder();
        for (TodoItem todo : todos) {
            sb.append(todo.getId()).append(':')
                    .append(todo.getStatus()).append('|')
                    .append(todo.getDependsOn()).append(';');
        }
        return sb.toString();
    }

    // ── 诊断日志（[EDPA-DIAG] 前缀，定位事件流与预期不一致的偏离点）──

    /**
     * 记录 LLM 模型响应的关键信息：finish_reason / tool_calls 数量与名称 / 文本预览 / 当前 todo 缓存。
     */
    private void diagModelResponse(AgentCallbackContext ctx, ModelCallInputs inputs) {
        try {
            Object response = inputs.getResponse();
            String finishReason = "n/a";
            int toolCallCount = 0;
            String toolNames = "";
            String contentPreview = "";
            String reasoningPreview = "";
            if (response instanceof AssistantMessage msg) {
                finishReason = msg.getFinishReason();
                List<ToolCall> tcs = msg.getToolCalls();
                toolCallCount = tcs == null ? 0 : tcs.size();
                if (tcs != null && !tcs.isEmpty()) {
                    StringBuilder names = new StringBuilder();
                    for (ToolCall tc : tcs) {
                        if (names.length() > 0) {
                            names.append(",");
                        }
                        names.append(tc.getName());
                    }
                    toolNames = names.toString();
                }
                contentPreview = safe(msg.getContentAsString());
                reasoningPreview = safe(msg.getReasoningContent());
            }
            LOGGER.info("[EDPA-DIAG] MODEL_RESPONSE finishReason={}, toolCalls={}, toolNames=[{}], "
                            + "reasoningPreview=[{}], contentPreview=[{}], todos={}",
                    finishReason, toolCallCount, toolNames,
                    truncate(reasoningPreview, 200), truncate(contentPreview, 300), diagTodosSummary(ctx));
        } catch (Exception e) {
            LOGGER.warn("[EDPA-DIAG] model response diag failed: {}", e.getMessage());
        }
    }

    /**
     * 汇总当前会话 todo 缓存：数量 + 每项 content=status。
     */
    private String diagTodosSummary(AgentCallbackContext ctx) {
        try {
            List<TodoItem> todos = loadCurrentTodos(ctx);
            if (todos == null || todos.isEmpty()) {
                return "[]";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("count=").append(todos.size()).append(", [");
            for (int i = 0; i < todos.size(); i++) {
                TodoItem t = todos.get(i);
                if (i > 0) {
                    sb.append("; ");
                }
                sb.append(safe(t.getContent())).append('=')
                        .append(t.getStatus() != null ? t.getStatus().name() : "?");
            }
            sb.append("]");
            return sb.toString();
        } catch (Exception e) {
            return "diagErr:" + e.getMessage();
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() <= max ? one : one.substring(0, max) + "...";
    }

    /**
     * 核心方法：发射事件到输出流。
     *
     * <p>统一事件格式：event + timestamp + conversation_id + payload。
     * 通过 {@code Session.writeStream(OutputSchema(type="custom", payload=eventMap))} 输出。</p>
     *
     * @param ctx       回调上下文
     * @param eventType 事件类型
     * @param payload   事件负载
     */
    private void emit(AgentCallbackContext ctx, EdpaEventType type, Map<String, Object> payload) {
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("event", type.wireName());
            event.put("timestamp", System.currentTimeMillis());
            event.put("conversation_id", sessionId(ctx));
            event.putAll(payload);
            LOGGER.info("EdpaEventRail emit '{}'", type.wireName());
            ctx.getSession().writeStream(new OutputSchema("custom", 0, event));
        } catch (Exception e) {
            LOGGER.warn("EdpaEventRail emit '{}' failed: {}", type.wireName(), e.getMessage());
        }
    }
}
