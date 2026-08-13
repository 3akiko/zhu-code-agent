状态：approved
# zhuCodeAgent M5：Agent 扩展——并行与 Subagents Tasks

> 依据已批准的 `docs/spec.md`（F1–F3/AC1–AC5）与 `docs/plan.md`。粒度 ≤15–30 分钟/任务，每个任务含验证。
> 运行验证统一用 `mvn -q -Dtest=<测试类> test`（mock，无需网络；沙箱外跑 LLM socket 测试用 require_escalated）。

## 文件清单

| 操作 | 文件 | 职责 |
|------|------|------|
| 新建 | `src/main/java/com/zhubao/llm/StreamState.java` | per-call 状态基类 + Anthropic/OpenAi 子类 |
| 修改 | `src/main/java/com/zhubao/llm/AbstractStreamingClient.java` | 状态迁入 StreamState；cancel/runStream 用 state |
| 修改 | `src/main/java/com/zhubao/llm/AnthropicClient.java` | 去实例字段；newStreamState + handleEvent(sse,queue,state) |
| 修改 | `src/main/java/com/zhubao/llm/OpenAiClient.java` | 同上 |
| 修改 | `src/main/java/com/zhubao/llm/LlmClientFactory.java` | 按 provider 名缓存复用 |
| 新建 | `src/test/java/com/zhubao/llm/ConcurrentStreamTest.java` | F1 并发测试 |
| 新建 | `src/main/java/com/zhubao/tool/AbstractToolExecutor.java` | 单调用公共逻辑 |
| 修改 | `src/main/java/com/zhubao/tool/SerialToolExecutor.java` | 继承 AbstractToolExecutor，行为不变 |
| 新建 | `src/main/java/com/zhubao/tool/ParallelToolExecutor.java` | 读+task 段并行 / 写·bash 串行 / 保序 |
| 新建 | `src/test/java/com/zhubao/tool/ParallelToolExecutorTest.java` | F2 测试 |
| 修改 | `src/main/java/com/zhubao/agent/AgentRunner.java` | 参数放宽 ToolExecutor；runSubtask + SUBTASK_SYSTEM_PROMPT |
| 修改 | `src/main/java/com/zhubao/agent/AgentUi.java` | default onSubtaskStart/onSubtaskEnd |
| 新建 | `src/main/java/com/zhubao/agent/SubagentCoordinator.java` | 在途子任务线程 + 级联取消 |
| 修改 | `src/test/java/com/zhubao/agent/AgentRunnerTest.java` | 补 runSubtask 用例 |
| 新建 | `src/main/java/com/zhubao/tool/TaskTool.java` | task 工具（setter 注入上下文） |
| 修改 | `src/main/java/com/zhubao/tool/ToolRegistry.java` | 新增 public register(Tool) |
| 新建 | `src/test/java/com/zhubao/tool/TaskToolTest.java` | F3 工具级测试 |
| 修改 | `src/main/java/com/zhubao/tui/ChatApp.java` | ParallelToolExecutor + TaskTool 装配 + 子任务行 |
| 修改 | `src/main/java/com/zhubao/tui/TurnInterruptController.java` | TOOL_EXEC 分支级联 cancelAll |
| 修改 | `src/main/java/com/zhubao/config/AppConfig.java` | agent 护栏字段（默认 2/4/30） |
| 修改 | `src/main/java/com/zhubao/config/ConfigLoader.java` | 读 agent.max_subagent_depth / max_parallel_subagents / max_steps_per_subagent |
| 修改 | `config.example.yml` | 新增 agent 段 |
| 修改 | `src/test/java/com/zhubao/config/ConfigLoaderTest.java` | 补 agent 段用例 |
| 新建 | `src/test/java/com/zhubao/integration/SubagentIntegrationTest.java` | F3 端到端（mock 双协议） |

## T1: 配置——agent 护栏
**文件：** `AppConfig.java`、`ConfigLoader.java`、`config.example.yml`、`ConfigLoaderTest.java`
**依赖：** 无（可最先做，也可并行）
**步骤：**
1. AppConfig record 新增 `int agentMaxDepth`（默认 2）、`int agentMaxParallel`（默认 4）、`int agentMaxStepsPerSubtask`（默认 30）+ 对应 DEFAULT 常量。
2. ConfigLoader 读 `agent.max_subagent_depth` / `agent.max_parallel_subagents` / `agent.max_steps_per_subagent`（缺失/非法回退默认，沿用 nestedInt 模式）。
3. config.example.yml 加 `agent:` 段（含注释说明）。
4. ConfigLoaderTest 补 3 个用例：默认值 / 配置生效 / 非法回退。
**验证：** `mvn -q -Dtest=ConfigLoaderTest test` 全绿。

## T2: llm——StreamState + AbstractStreamingClient per-call 重构（F1）
**文件：** 新建 `StreamState.java`、修改 `AbstractStreamingClient.java`
**依赖：** T1（无关，可并行）
**步骤：**
1. `StreamState.java`：基类（activeBody/cancelled/token 统计/stopReason）+ `AnthropicStreamState`（thinking/单块 tool_use）+ `OpenAiStreamState`（toolAccums），包私有同文件。
2. `AbstractStreamingClient`：删实例字段 `activeBody`/`cancelled`/`onStreamStart()`；新增 `protected abstract StreamState newStreamState()`；`stream()` 创建 queue+state、worker 跑 `runStream(request, queue, state)`；`cancelStream(worker, queue, state)` 用 `state.cancelled` CAS + close `state.activeBody` + 投递「已中断」；`runStream` 内 `state.activeBody`/`state.cancelled`；`handleEvent(SseEvent, BlockingQueue<StreamEvent>, StreamState)` 签名变化（先改签名，子类适配在 T3）。
**验证：** 编译通过（T3 完成前测试可能红，先只编译 main）。

## T3: llm——AnthropicClient / OpenAiClient 去实例字段（F1）
**文件：** `AnthropicClient.java`、`OpenAiClient.java`
**依赖：** T2
**步骤：**
1. 移除全部实例累积字段（Anthropic：inputTokens/outputTokens/cacheReadTokens/cacheCreationTokens/stopReason/inThinking/thinkingAccum/thinkingSignature/inToolUse/toolUseId/toolUseName/toolUseJsonAccum；OpenAI：inputTokens/outputTokens/cacheReadTokens/stopReason/toolAccums + ToolAccum 类）。
2. `newStreamState()` 返回各自子类（AnthropicStreamState / OpenAiStreamState）。
3. `handleEvent(SseEvent, queue, state)` 内强转 `(AnthropicStreamState)/(OpenAiStreamState) state` 并读写 state 字段；`buildRequest` 不变。
**验证：** `mvn -q -Dtest=AnthropicClientTest,OpenAiClientTest,SseParserTest test` 全绿（F1.4 事件契约不回归）。

## T4: llm——LlmClientFactory 缓存复用 + 并发测试（F1）
**文件：** `LlmClientFactory.java`、新建 `ConcurrentStreamTest.java`
**依赖：** T3
**步骤：**
1. `LlmClientFactory`：`ConcurrentHashMap<String, LlmClient>` + `computeIfAbsent(config.getName(), k -> createNew(config))`；`createNew` 保持原 switch。
2. `ConcurrentStreamTest`：复用 mock 服务器——同一实例并发发起两个流：各自文本/token 用量/工具调用互不串扰；对一个流 cancel 不影响另一个；同实例第二次流复用后 cancelled 不残留（首流取消后再起新流正常出结果）。
**验证：** `mvn -q -Dtest=ConcurrentStreamTest test` 全绿 + T3 测试不回归。

## T5: tool——AbstractToolExecutor 抽取 + SerialToolExecutor 继承（F2）
**文件：** 新建 `AbstractToolExecutor.java`、修改 `SerialToolExecutor.java`
**依赖：** T1（无强依赖，可并行）
**步骤：**
1. `AbstractToolExecutor`：持有 registry/permissions/ui/previewLines/history/sessionId（protected）；`executeOne(ToolCall)` 迁移 SerialToolExecutor.execute 循环体内的单调用逻辑（中断检查→onToolCall→权限判定→快照→registry.byName→execute→discardLast/oversize→onToolResult）；`isSnapshotTool`/`pathOf` 静态方法迁入。
2. `SerialToolExecutor`：`extends AbstractToolExecutor`；构造转发 super；`execute` 循环逐调用调 `executeOne`（行为完全一致）。
**验证：** `mvn -q -Dtest=SerialToolExecutorTest,PlanModeExecutorTest,FileToolsTest test` 全绿（行为不回归）。

## T6: tool——ParallelToolExecutor（F2）
**文件：** 新建 `ParallelToolExecutor.java`、新建 `ParallelToolExecutorTest.java`
**依赖：** T5
**步骤：**
1. 实现 `execute(calls)`：按原顺序扫描分组——`isParallelCall = tool.readOnly() || "task"` 连续段并行，其余（write/edit/bash）串行单；段间保持原序。
2. 并行段：`try (var ex = Executors.newVirtualThreadPerTaskExecutor())` 提交 `ex.submit(() -> executeOne(call))`，按段内原序 `Future.get` 收集；提交前串行 `ui.onToolCall`、收集时串行 `ui.onToolResult`；单调用失败以 isError 返回不拖垮段。
3. 段间检查 `Thread.currentThread().isInterrupted()` → break；整批按原 calls 顺序组装 results。
4. `ParallelToolExecutorTest`：保序（[R1,R2,W1,R3] 结果顺序 == 调用顺序）；读段并行（阻塞读 mock 可观测重叠/耗时低于串行）；写串行；单失败隔离；中断后剩余调用不执行。
**验证：** `mvn -q -Dtest=ParallelToolExecutorTest test` 全绿 + T5 不回归。

## T7: agent——AgentRunner 放宽 + runSubtask + SubagentCoordinator + AgentUi（F3）
**文件：** `AgentRunner.java`、`AgentUi.java`、新建 `SubagentCoordinator.java`、`AgentRunnerTest.java`
**依赖：** T4（client 可并发）、T6（子任务内并行工具）
**步骤：**
1. `AgentRunner.run`/`runExecution` 参数 `SerialToolExecutor` → `ToolExecutor`（`runPlan`/`runPlanContinue` 已用 PlanModeExecutor 不变）。
2. 新增 `SUBTASK_SYSTEM_PROMPT`（子任务身份提示词：聚焦任务、直接执行工具、不询问用户、最终简洁报告收尾）。
3. 新增 `runSubtask(LlmClient, Conversation, List<ToolSpec>, ToolExecutor, AgentUi, int maxSteps, long stepIdleTimeoutMs, Consumer<LlmStream>)`：内部 `runLoop(..., SUBTASK_SYSTEM_PROMPT, false, onStream)`，maxCallsPerTurn=maxSteps。
4. `AgentUi` 新增 default 方法 `onSubtaskStart(int, String)` / `onSubtaskEnd(int, String)`（空实现）。
5. `SubagentCoordinator`：`ConcurrentHashMap.newKeySet()` 静态集合 + `register(Thread)/unregister(Thread)/cancelAll()`。
6. `AgentRunnerTest` 补 runSubtask 用例（独立会话执行、步数上限到达即结束、SUBTASK 提示词生效）。
**验证：** `mvn -q -Dtest=AgentRunnerTest,StreamingIntegrationTest test` 全绿。

## T8: tool——TaskTool + ToolRegistry.register（F3）
**文件：** 新建 `TaskTool.java`、修改 `ToolRegistry.java`、新建 `TaskToolTest.java`
**依赖：** T7
**步骤：**
1. `ToolRegistry` 新增 `public void register(Tool)`（放入 tools 映射）。
2. `TaskTool`：setter 注入（clientProvider/registry/permissions/ui/history/sessionId/maxDepth/maxParallel/maxSteps/previewLines）；`name()="task"`、description、inputSchema（prompt 必填 + constraints 可选）；`execute` 流程按 plan 3.6（深度检查→并发计数→buildSubtaskTools→独立 Conversation→SubagentUi→onSubtaskStart→SubagentCoordinator.register→runSubtask→unregister→摘要→onSubtaskEnd→ToolResult）；子任务 executor `new ParallelToolExecutor(registry, permissions, subUi, previewLines, history, sessionId)`；`buildSubtaskTools(childDepth)` 达上限剔除 task。
3. `TaskToolTest`：深度超限拒绝（isError + 可读信息）；并发超限拒绝；工具池裁剪（层 2 不含 task）；摘要格式（状态/摘要/token）；失败不自动重试（返回 isError 摘要）。
**验证：** `mvn -q -Dtest=TaskToolTest test` 全绿。

## T9: tui——ChatApp 装配 + 级联中断 + 子任务行（F2/F3）
**文件：** `ChatApp.java`、`TurnInterruptController.java`
**依赖：** T6、T8
**步骤：**
1. `ChatApp`：构造 `TaskTool`（setter 注入 provider 的 clientProvider/permissions/agentUi 提供者/history/sessionId/护栏）→ `toolRegistry.register(taskTool)`；`sendAndRender`/`executePlannedTurn` 的 `SerialToolExecutor` → `ParallelToolExecutor`（构造参数同：registry/permissions/agentUi/previewLines/history/sessionId）。
2. `TuiAgentUi` 实现 `onSubtaskStart`/`onSubtaskEnd`：折叠单行（`⏳ 子任务#N: <prompt 首行>…` / `✓ 子任务#N: <摘要首行>`）。
3. `TurnInterruptController.cancelCurrent()` 的 TOOL_EXEC 分支追加 `SubagentCoordinator.cancelAll()`。
4. 子任务权限确认（T9 细化，2026-08-13）：`SubagentUi` 全静默（onStep/onEvent/onToolCall/onToolResult 空实现，只转发 askPermission）；`TuiAgentUi.askPermission` 内部加 `ReentrantLock` 全局串行（主会话与所有子任务同一时刻只有一个权限弹窗在读输入）；弹窗文案带 `[子任务#N]` 来源前缀；级联取消（SubagentCoordinator.cancelAll）interrupt 子任务线程以打断其阻塞的 readLine。残余风险：JLine readLine 跨线程/中断行为——mock 用自动应答桩，真机 PTY 冒烟验证（沙箱外 require_escalated）；若不可接受则走变更控制升级为「权限请求投递主线程队列、主循环安全时机弹窗」，不擅自改设计。
**验证：** 编译通过 + `mvn -q -Dtest=AgentLoopIntegrationTest,M4ContextIntegrationTest test` 不回归。

## T10: integration——SubagentIntegrationTest 端到端（F3）
**文件：** 新建 `SubagentIntegrationTest.java`
**依赖：** T9
**步骤：**
1. mock 双协议（Anthropic + OpenAI）：父 agent 一步输出 `task` tool_use → 子任务在独立会话执行（mock 返回子任务内部思考/工具/最终文本）→ 摘要回填（tool_result 含状态/摘要/token）→ 父继续下一轮。
2. 并行子任务：父一步输出两个 `task` tool_use → 两个子任务并行执行、均回填摘要。
3. 深度超限：孙 agent 调用 task → 拒绝（工具池裁剪或运行时兜底）并回填可读错误。
4. 权限继承：父已「总是允许」的写操作在子任务内自动放行（不二次确认，测试断言 PermissionManager 状态）。
5. 级联中断：子任务运行中模拟 Ctrl+C → 所有在途子任务中断、摘要标记「被中断」、父半成品不写入会话。
6. 父 max_calls_per_turn 只计 task 调用本身（子任务内部步骤不消耗父计数，测试用短上限断言）。
**验证：** `mvn -q -Dtest=SubagentIntegrationTest test` 全绿。

## T11: 全量回归 + 收尾
**文件：** 无新增
**依赖：** T1–T10
**步骤：**
1. `mvn clean test` 全量：原 215 + 新增全绿（沙箱外跑）。
2. 核对 spec AC1–AC5 逐条映射到已通过的测试。
3. 检查无 api_key 泄漏、无禁写目录回归（N2）。
**验证：** `mvn clean test` BUILD SUCCESS，215 + 新增全绿。

## 执行顺序
```
T1（配置，可独立）
T2 → T3 → T4（F1 硬前置链）
T5 → T6（F2 链）
T7 → T8（F3 依赖链）
T6 + T8 → T9（ChatApp 装配）
T9 → T10 → T11
```


## Review 修复（2026-08-13 review-agent 审查后，用户批准）

- **R1（P1）嵌套深度护栏失效**：`TaskTool` 原用 `ThreadLocal<Integer> depth` 沿执行链传深度，但并行段虚拟线程（每任务新线程）不继承 ThreadLocal → 子任务内再派 task 时深度丢失（maxDepth=2 时孙 agent 工具池仍含 task、运行时拒绝失效）。修复：新增 `agent/AgentDepth`（静态 ThreadLocal 显式传播），`ParallelToolExecutor` 提交虚拟线程前捕获父线程深度、线程内恢复并 finally clear；`TaskTool` 改用 `AgentDepth`。复现测试：`SubagentIntegrationTest#nestedDepthPrunesTaskAtGrandchild`（maxDepth=2 断言孙请求体 tools 不含 task）。
- **R2（P1）/plan 计划阶段可经 task 绕过只读约束**：`PlanModeExecutor.WRITE_TOOLS` 不含 `task` → 计划阶段可派子任务改写工作区（M3 回归）。修复：`WRITE_TOOLS` 加 `task` + 错误文案更新。测试：`PlanModeExecutorTest#taskBlockedInPlanMode`。
- **R3（P2）并行子任务完成并发写终端**：`TerminalUi.print/println` 无锁，多个虚拟线程同时输出会交错（System.out 有锁不崩溃但行序/ANSI 状态错乱）。修复：`TerminalUi` 所有写方法加 `outputLock` 串行。
- **R4（P3）并行段中断路径 `ExecutorService.close()` 可能等待在途虚拟线程**：`close()` 默认 awaitTermination 无超时。修复：中断时 `shutdownNow()` 再 `close()`。

- **R5（P2，2026-08-13）并行 bash 进程销毁不完整**：`BashTool` 原用单个 `currentProcess` 引用，并行 bash（并行子任务各跑 bash / 父+子并发）时后启动的覆盖前者引用，`cancel()`/中断只销毁最后启动的进程，其余 bash 子进程泄漏（继续运行产生副作用）。修复：`currentProcess` 改为并发集合 `activeProcesses`（`ConcurrentHashMap.newKeySet()`），execute 注册/finally 移除，`cancel()` 与中断 catch 统一 `killAll()` 销毁全部在途进程。测试：新增 `BashToolParallelInterruptTest`（3 个并行 sleep 30，cancel 后全部快速结束）；`BashToolTest`/`BashToolInterruptTest` 回归全绿。

- **R6（P1，2026-08-13 真机反馈）权限弹窗后 Ctrl+C 直接退出进程**：真机（PosixSysTerminal）下 JLine `readLine`（权限确认弹窗）临时接管 SIGINT，弹窗结束后可能把 INT 处理恢复为默认——此后工具执行中 Ctrl+C 走默认终止而非级联中断。修复：`TurnInterruptController.rearmSignalHandler()`（重新 `Signals.register`），`TuiAgentUi.askPermission` 弹窗结束（finally）后调用；相关中断/集成测试回归全绿。真机复验需在真实终端执行（exec PTY 为 DumbTerminal，不触发）。
- **R7（P2，2026-08-13 真机反馈）工具摘要行与流式正文粘连**：模型先输出正文（`ui.print` 不换行）再调工具时，`🔧 task …` 摘要接在正文同一行。修复：`TuiAgentUi` 新增 `textOpen` 标志（TextDelta 打印后置位），`onToolCall/onToolResult/onSubtaskStart/End/Permission` 打印前 `breakTextLine()` 先换行；`onStep/clearLeftoverStatus` 重置。相关集成测试回归全绿。
