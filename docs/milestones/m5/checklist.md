状态：approved
# zhuCodeAgent M5：Agent 扩展——并行与 Subagents Checklist

> 每一项通过「运行代码/观察行为」验证（做 X 看到 Y）。依据已批准 spec AC1–AC5、plan 集成点、task 文件清单。

## 实现完整性

- [x] F1 StreamState 体系（基类 + Anthropic/OpenAi 子类）已实现，token/stopReason/activeBody/cancelled 全部随 per-call 状态（验证：编译 + ConcurrentStreamTest）
- [x] F1 AnthropicClient/OpenAiClient 无实例累积字段；newStreamState() 返回各自子类（验证：代码走查 + 客户端测试全绿）
- [x] F1 LlmClientFactory 缓存复用：同 provider 名 `create` 返回同一实例（验证：ConcurrentStreamTest 断言 `==`）
- [x] F2 AbstractToolExecutor 抽取 + SerialToolExecutor 继承，串行行为不变（验证：SerialToolExecutorTest/PlanModeExecutorTest/FileToolsTest 全绿）
- [x] F2 ParallelToolExecutor 实现：读+task 段并行 / write·edit·bash 串行 / 段间保序 / 结果按原序（验证：ParallelToolExecutorTest）
- [x] F3 TaskTool + ToolRegistry.register；setter 注入 clientProvider/permissions/ui/history/sessionId/护栏（验证：编译 + TaskToolTest）
- [x] F3 AgentRunner.runSubtask + SUBTASK_SYSTEM_PROMPT；run/runExecution 参数放宽 ToolExecutor（验证：AgentRunnerTest + 编译）
- [x] F3 SubagentCoordinator（register/unregister/cancelAll）+ TurnInterruptController TOOL_EXEC 分支级联（验证：代码走查 + SubagentIntegrationTest）
- [x] F3 AgentUi default onSubtaskStart/onSubtaskEnd + TuiAgentUi 折叠单行（验证：编译 + 集成）
- [x] 配置：AppConfig agent 护栏（2/4/30）+ ConfigLoader 读 agent.* + config.example.yml agent 段（验证：ConfigLoaderTest）

## F1 客户端并发安全（AC1）

- [x] 同一客户端实例并发两个流：各自文本/token 用量/工具调用互不串扰（验证：ConcurrentStreamTest 断言两个流的 TextDelta/StreamEnd 各自独立）
- [x] 对一个流 cancel 不影响另一个流（另一个流正常收到 StreamEnd）（验证：ConcurrentStreamTest）
- [x] 实例复用后 cancelled 不残留：首流取消后再起新流正常出结果（验证：ConcurrentStreamTest）
- [x] 缓存复用后主会话与子任务共享同一实例且并发安全（验证：SubagentIntegrationTest 中父+子并发流）

## F2 并行工具执行（AC2）

- [x] `[R1,R2,W1,R3]` 结果按原调用顺序回填（r1,r2,w1,r3 顺序一致）（验证：ParallelToolExecutorTest）
- [x] 读段并行可观测：阻塞读 mock 下 R1/R2 耗时重叠、整体 < 串行（验证：ParallelToolExecutorTest 断言耗时/顺序）
- [x] 写/bash 串行且保持原序（验证：ParallelToolExecutorTest 断言执行顺序）
- [x] 单个读工具失败 → isError 结果返回、同段其他调用正常完成（验证：ParallelToolExecutorTest）
- [x] Ctrl+C 中断在途读段：剩余调用不执行、本轮终止（验证：ParallelToolExecutorTest 模拟 interrupt）
- [x] 权限判定与串行一致：只读自动放行、写/bash 确认、危险命令强制确认（验证：ParallelToolExecutorTest + 现有权限测试不回归）
- [x] 快照/回滚不回归：写类串行快照顺序一致、只读不触发快照（验证：FileHistoryTest + 集成）

## F3 Subagents / Task（AC3）

- [x] 父调 task → 子任务在独立会话执行（看不到父历史）→ 结构化摘要回填（状态/摘要/token）→ 父继续循环（验证：SubagentIntegrationTest 双协议）
- [x] 摘要回填走 tool_result 通道、保持 user/assistant 交替、双协议兼容（验证：SubagentIntegrationTest 断言会话消息序 + 双协议回填）
- [x] 父一次两个 task 并行执行、均回填摘要（验证：SubagentIntegrationTest）
- [x] 深度超限拒绝：层 2 孙 agent 工具池不含 task（不可见）+ 运行时兜底拒绝返回可读错误（验证：TaskToolTest + SubagentIntegrationTest）
- [x] 父已「总是允许」的操作子任务内自动放行、不二次确认（验证：SubagentIntegrationTest 断言 PermissionManager 共享）
- [x] 未批准写类回主 UI 确认并带「[子任务#N]」来源标注（验证：mock 桩断言 askPermission 调用次数/标注；真机 PTY 冒烟）
- [x] 子任务运行中 Ctrl+C → 级联中断所有在途子任务、摘要标记「被中断」、父半成品不写入会话（验证：SubagentIntegrationTest）
- [x] 父 max_calls_per_turn 只计 task 调用本身、子任务内部步骤不消耗父计数（验证：SubagentIntegrationTest 短上限断言）
- [x] 子任务失败不自动重试：isError 摘要回填、由父模型决策（验证：TaskToolTest/SubagentIntegrationTest 断言无重试调用）

## N2/N3/N4 安全兼容稳定（AC4）

- [x] 安全红线不削弱：危险命令强制确认、cwd 外拒绝、禁写 .git/ 与 ~/.zhu-code-agent/、api_key 不落日志不落盘；子任务不绕过任何权限判定（验证：现有安全测试全绿 + 子任务路径代码走查）
- [x] 双协议回填不回归：anthropic tool_use ↔ openai function_call（验证：AgentLoopIntegrationTest + SubagentIntegrationTest 双协议全绿）
- [x] 子任务摘要回填保持 user/assistant 交替、无悬空 tool_use（验证：SubagentIntegrationTest 断言消息序）
- [x] M4 占用协议感知/压缩/中断/JSONL 不回归（验证：M4ContextIntegrationTest/SessionStoreJsonlTest 全绿）
- [x] 并发/并行/子任务失败路径均不崩溃、返回可读信息（验证：各失败用例 + 全量测试）

## 编译与测试（AC5）

- [x] `mvn clean test` 全量 BUILD SUCCESS：原 215 + 新增全绿（沙箱外跑 LLM mock socket 测试）
- [x] 新增测试齐备：ConcurrentStreamTest / ParallelToolExecutorTest / TaskToolTest / SubagentIntegrationTest / AgentRunnerTest（runSubtask）/ ConfigLoaderTest（agent 段）
- [x] 无新增第三方依赖（N1：pom.xml 无变更）（验证：git diff pom.xml）

## 端到端场景

- [x] 场景 1（主流程）：mock 双协议会话，父 agent 收到复杂任务 → 一步派 2 个并行子任务（各自独立会话执行只读/写工具）→ 摘要回填 → 父综合输出最终文本 → 会话 JSONL 落盘可恢复、不含 api_key（验证：SubagentIntegrationTest + SessionStoreJsonlTest）
- [x] 场景 2（边界）：孙 agent 尝试再派 task → 被拒（工具池无 task/兜底拒绝）→ 可读错误回填、进程不崩溃（验证：SubagentIntegrationTest）
- [x] 场景 3（真机 PTY，2026-08-13 已做）：DeepSeek v4-flash 真机——task 工具派子任务 → 折叠单行（⏳/✓ 子任务#N）；子任务写文件/bash → 主 UI 弹窗带「[子任务#N]」来源标注、串行弹窗；子任务 `sleep 20` 中 Ctrl+C → 级联取消、摘要「状态=被中断」、本轮中断、半成品不写入。JLine 跨线程/中断真机正常，无需变更控制升级

## Review 修复验证（2026-08-13）

- [x] R1 深度护栏：maxDepth=2 时孙 agent 请求体 tools 不含 task；子任务内再派 task 读到正确深度（验证：SubagentIntegrationTest#nestedDepthPrunesTaskAtGrandchild 全绿）
- [x] R2 /plan 拦截 task：计划阶段调 task 返回「计划阶段禁止该操作」，不派生子任务（验证：PlanModeExecutorTest#taskBlockedInPlanMode 全绿）
- [x] R3 终端输出串行：TerminalUi 所有写方法加 outputLock，并行子任务/权限确认输出不交错（验证：全量 244 测试全绿 + 真机冒烟）
- [x] R4 中断不再等待在途虚拟线程：shutdownNow 后 close（验证：ParallelToolExecutorTest 中断用例全绿）

- [x] R5 并行 bash 进程销毁：并行 3 个 bash，cancel 后全部进程快速销毁、无泄漏（验证：BashToolParallelInterruptTest 全绿 + BashToolTest/BashToolInterruptTest 回归）

- [x] R6 真机 SIGINT：权限弹窗结束后重新注册本轮 handler，工具执行中 Ctrl+C 走级联中断而非退出进程（验证：TurnInterruptControllerTest/集成回归全绿；真机复验待真实终端）
- [x] R7 工具摘要换行：流式正文后 `🔧 task …` 摘要分行展示（验证：集成回归全绿；真机复验待真实终端）
