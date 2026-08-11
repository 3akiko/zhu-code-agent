状态：approved
# zhuCodeAgent M4：上下文管理与稳定性 Tasks

> 依据已批准的 `docs/spec.md`（F1–F7）与 `docs/plan.md`。粒度 ≤15–30 分钟/任务，每个任务含验证。
> 运行验证统一用 `mvn -q -Dtest=<测试类> test`（mock，无需网络）。

## 文件清单

| 操作 | 文件 | 职责 |
|------|------|------|
| 新增 | `src/main/java/com/zhubao/config/LlmLimits.java` | 模型表 + 窗口/输出上限解析 |
| 新增 | `src/main/java/com/zhubao/llm/LlmStream.java` | 流事件队列 + 取消句柄 |
| 新增 | `src/main/java/com/zhubao/context/CompactionOptions.java` | 压缩参数 |
| 新增 | `src/main/java/com/zhubao/context/CompactionResult.java` | 压缩结果 |
| 新增 | `src/main/java/com/zhubao/context/ContextCompactor.java` | snip/截断/摘要折叠/熔断 |
| 新增 | `src/main/java/com/zhubao/tui/TurnInterruptController.java` | 本轮中断控制（JVM 级信号/逃生门/状态收敛） |
| 修改 | `src/main/java/com/zhubao/config/ProviderConfig.java` | +contextWindow/maxTokens + effective |
| 修改 | `src/main/java/com/zhubao/config/AppConfig.java` | +context.* 字段与默认值 |
| 修改 | `src/main/java/com/zhubao/config/ConfigLoader.java` | 解析 context.* 与 provider 新字段 |
| 修改 | `src/main/java/com/zhubao/session/SessionMeta.java` | +累计 token 字段 |
| 修改 | `src/main/java/com/zhubao/llm/StreamEvent.java` | StreamEnd + cache 字段 |
| 修改 | `src/main/java/com/zhubao/llm/LlmClient.java` | stream() → LlmStream |
| 修改 | `src/main/java/com/zhubao/llm/AbstractStreamingClient.java` | LlmStream + cancel/join |
| 修改 | `src/main/java/com/zhubao/llm/AnthropicClient.java` | cache_control + usage 缓存 + max_tokens 生效 |
| 修改 | `src/main/java/com/zhubao/llm/OpenAiClient.java` | cached_tokens / prompt_cache_hit_tokens |
| 修改 | `src/main/java/com/zhubao/agent/AgentRunner.java` | LlmStream、累计、cache、中断 |
| 修改 | `src/main/java/com/zhubao/session/SessionStore.java` | JSONL 追加 + 迁移 + cursor |
| 修改 | `src/main/java/com/zhubao/tool/builtin/BashTool.java` | currentProcess + cancel() |
| 修改 | `src/main/java/com/zhubao/tool/SerialToolExecutor.java` | 中断标志停止后续调用 |
| 修改 | `src/main/java/com/zhubao/tui/ChatApp.java` | 统计/告警//compact/Ctrl+C/优雅关闭 |
| 修改 | `config.example.yml` | context.* 与 provider 新字段示例 |
| 测试 | `src/test/java/com/zhubao/config/ConfigLoaderTest.java` | 新增 context.*/provider 字段用例 |
| 测试 | `src/test/java/com/zhubao/llm/AnthropicClientTest.java` | cache_control/max_tokens/cache usage |
| 测试 | `src/test/java/com/zhubao/llm/OpenAiClientTest.java` | cached_tokens 解析 |
| 测试 | `src/test/java/com/zhubao/agent/AgentRunnerTest.java` | 累计/cache/中断 |
| 测试 | 新增 `src/test/java/com/zhubao/context/ContextCompactorTest.java` | snip/截断/折叠/熔断 |
| 测试 | 新增 `src/test/java/com/zhubao/session/SessionStoreJsonlTest.java` | 追加/恢复/迁移/坏行 |
| 测试 | 新增 `src/test/java/com/zhubao/tool/BashToolInterruptTest.java` | cancel 中断 |
| 测试 | 新增 `src/test/java/com/zhubao/integration/M4ContextIntegrationTest.java` | 统计/压缩/中断 mock 端到端 |

## T1: ProviderConfig 扩展 + LlmLimits 模型表
**文件：** `config/ProviderConfig.java`、新增 `config/LlmLimits.java`
**依赖：** 无
**步骤：**
1. ProviderConfig 增可空 `Integer contextWindow` / `Integer maxTokens` 字段 + getter/setter。
2. 新增 `effectiveContextWindow()`：配置 ?? LlmLimits 表 ?? 默认 32K。
3. 新增 `effectiveMaxTokens(boolean thinking)`：配置 ?? 表（thinking/plain 分列）?? 64000/8192。
4. LlmLimits：`record ModelLimit(int contextWindow, int maxPlain, int maxThinking)` + 静态表（deepseek-v4-flash/pro→1M；deepseek-chat/reasoner→64K；claude-sonnet-4-5→200K/64000；claude-opus-4→200K/32000；gpt-4o→128K）+ `resolve(ProviderConfig)`。
5. 单测：`LlmLimitsTest`（表命中/未命中默认/配置覆盖）。
**验证：** `mvn -q -Dtest=LlmLimitsTest,ConfigLoaderTest test` 通过；未命中模型 effectiveContextWindow=32768。

## T2: AppConfig/ConfigLoader/config.example.yml 新字段
**文件：** `config/AppConfig.java`、`config/ConfigLoader.java`、`config.example.yml`、`test/.../ConfigLoaderTest.java`
**依赖：** T1
**步骤：**
1. AppConfig 增 `contextAlertThreshold`(0.8)/`contextCompactThreshold`(0.9)/`contextCompactTarget`(0.6)/`contextSnipEnabled`(true)/`contextKeepRecentTurns`(8) + 默认常量。
2. ConfigLoader 解析 `context.alert_threshold` 等嵌套字段 + provider `context_window`/`max_tokens`/`prompt_cache`（变更控制 2026-08-11）。
3. config.example.yml 增加 `context:` 段与 provider 可选字段注释。
4. ConfigLoaderTest 新增：context 默认值、显式配置、provider 新字段解析。
**验证：** `mvn -q -Dtest=ConfigLoaderTest test` 通过。

## T3: SessionMeta 累计 token 字段
**文件：** `session/SessionMeta.java`
**依赖：** 无
**步骤：**
1. record 增 `long totalInputTokens`/`long totalOutputTokens`；保留旧构造（默认 0）供既有调用/测试。
2. 编译全项目确认无遗漏调用点。
**验证：** `mvn -q test-compile` 通过。

## T4: StreamEnd cache 字段
**文件：** `llm/StreamEvent.java`
**依赖：** 无
**步骤：**
1. `StreamEnd` 增 `int cacheReadTokens`/`int cacheCreationTokens`；保留 3 参构造（cache=0）。
2. 更新直接 new StreamEnd(…, …, …) 的既有测试到新构造或保持兼容。
**验证：** `mvn -q -Dtest=AnthropicClientTest,OpenAiClientTest,SseParserTest test` 通过（编译同步）。

## T5: LlmStream + LlmClient 接口变更 + AbstractStreamingClient cancel/join
**文件：** 新增 `llm/LlmStream.java`、`llm/LlmClient.java`、`llm/AbstractStreamingClient.java`、`agent/AgentRunner.java`（consumeStep 改用 `.events()`）
**依赖：** T4
**步骤：**
1. 新增 `record LlmStream(BlockingQueue<StreamEvent> events, Runnable cancel)`。
2. `LlmClient.stream()` 返回 `LlmStream`。
3. AbstractStreamingClient：创建 LlmStream；`cancel()` = interrupt worker + 关闭响应 + 队列投递 `Error("已中断")`；新增 `join()`（超时 3s）。
4. AgentRunner.consumeStep 用 `.events()` 轮询；所有测试里 `client.stream(...)` 调用点改 `.events()`。
**验证：** `mvn -q test` 编译 + 既有 llm/agent 测试通过。

## T6: AnthropicClient cache_control + max_tokens + cache usage
**文件：** `llm/AnthropicClient.java`、`test/.../AnthropicClientTest.java`
**依赖：** T1、T4、T5
**步骤：**
1. `system` 改为块数组 `[{type:text, text, cache_control:{type:ephemeral}}]`（无 thinking/有 thinking 均生效）。
2. 每个 tool 加 `cache_control:{type:ephemeral}`。
3. `prompt_cache=false` 时 system 回退字符串、tools 不带 cache_control（review-P2 开关）。
3. `max_tokens` 用 `config.effectiveMaxTokens(config.isThinking())`。
4. 解析 usage：`cache_read_input_tokens`/`cache_creation_input_tokens` → StreamEnd cache 字段。
5. 测试更新：请求体含 cache_control 断点、max_tokens 按模型、mock usage 缓存字段透传。
**验证：** `mvn -q -Dtest=AnthropicClientTest test` 通过。

## T7: OpenAiClient cached_tokens 解析
**文件：** `llm/OpenAiClient.java`、`test/.../OpenAiClientTest.java`
**依赖：** T4
**步骤：**
1. 解析 `usage.prompt_tokens_details.cached_tokens`（OpenAI）与 `usage.prompt_cache_hit_tokens`（DeepSeek）→ StreamEnd.cacheReadTokens。
2. `max_tokens` 仅当 provider 显式配置时写入请求体（默认行为不变）。
3. 测试：两种字段解析、未配置不写 max_tokens。
**验证：** `mvn -q -Dtest=OpenAiClientTest test` 通过。

## T8: AgentRunner Result/StepOutcome 扩展
**文件：** `agent/AgentRunner.java`、`test/.../AgentRunnerTest.java`
**依赖：** T5、T6、T7
**步骤：**
1. StepOutcome 增 cache 字段；consumeStep 累计。
2. Result 增 `totalInputTokens`/`totalOutputTokens`（本轮求和）/`cacheReadTokens`/`cacheCreationTokens`/`interrupted`；保留旧构造。
3. run/runPlan/runPlanContinue/runExecution 全部填充新字段；中断（InterruptedException / `Error("已中断")`）→ `interrupted=true`、不写半成品、**不追加进行中那轮 tool_use/tool_result**。
4. 测试：多步求和、中断结果。
**验证：** `mvn -q -Dtest=AgentRunnerTest,AgentLoopIntegrationTest,PlanModeIntegrationTest test` 通过。

## T9: ContextCompactor 本地层（snip + 截断）
**文件：** 新增 `context/CompactionOptions.java`、`context/CompactionResult.java`、`context/ContextCompactor.java`
**依赖：** T5
**步骤：**
1. Options（target/snipEnabled/keepRecentTurns/toolResultCap=64KB）；Result（compacted/foldedTurns/droppedTurns/truncatedResults/summary/errorMessage）。
2. snip：识别「assistant 工具调用消息 + 随后 user 纯 tool_result 消息」配对，全空输出或「已拒绝执行/权限禁止」→ 整对丢弃（count droppedTurns）。
3. 截断：剩余 tool_result.output > cap → 内存截断 + 标注（count truncatedResults）。
4. 提供 `compact(...)` 入口，先只做本地层（摘要部分 T10）。
**验证：** 编译通过；后续由 T11 单测覆盖。

## T10: ContextCompactor LLM 摘要折叠 + 熔断
**文件：** `context/ContextCompactor.java`
**依赖：** T9
**步骤：**
1. 摘要请求：system=压缩指令，messages=最旧 keepRecentTurns 轮之前的全部轮次（无工具），消费 stream 取最终文本。
2. 折叠：这些轮次替换为一条 user 消息「【上下文已压缩】…摘要…」；foldedTurns 计数。
3. 折叠后估算仍超目标 → 摘要本身再折叠（≤2 层封顶）。
4. 熔断：静态/实例计数，连续 3 次失败置 disabled（自动停用；手动不受限）。
5. 失败返回 errorMessage，不抛异常、不动会话。
**验证：** 编译通过；T11 覆盖。

## T11: ContextCompactorTest
**文件：** 新增 `test/.../context/ContextCompactorTest.java`
**依赖：** T9、T10
**步骤：**
1. snip：空结果/被拒配对被丢弃、正常配对保留。
2. 截断：>64KB 截断并标注、<cap 不动。
3. 折叠：mock LlmClient 返回摘要文本 → 最旧轮次折叠、摘要消息含「【上下文已压缩】」、会话消息序仍合法（user/assistant 交替不破）。
4. 熔断：mock 连续失败 3 次 → disabled。
**验证：** `mvn -q -Dtest=ContextCompactorTest test` 通过。

## T12: BashTool.cancel + SerialToolExecutor 中断停止
**文件：** `tool/builtin/BashTool.java`、`tool/SerialToolExecutor.java`、新增 `test/.../tool/BashToolInterruptTest.java`
**依赖：** 无（可与 T9–T11 并行）
**步骤：**
1. BashTool 增 `volatile Process currentProcess`；execute 设置/清理；`cancel()` destroy 进程树。
2. waitFor 中断 → 返回「命令执行被中断」。
3. SerialToolExecutor 循环中检查线程中断/共享中断标志 → 停止后续调用。
4. 测试：慢命令（sleep）执行中 cancel → 快速返回被中断、进程销毁。
**验证：** `mvn -q -Dtest=BashToolInterruptTest,BashToolTest test` 通过。

## T13: SessionStore JSONL 追加写 + 迁移
**文件：** `session/SessionStore.java`
**依赖：** T3
**步骤：**
1. save：`{id}.jsonl` 追加——meta 行（SessionMeta last-wins）+ 自 cursor 起新消息行（套 64KB 截断）；更新 cursor。
2. load：先 `.jsonl` 后 `.json`；`.jsonl` 逐行解析、最后 meta 生效、坏行跳过警告。
3. 迁移：加载旧 `.json` 后首 save 写 `.jsonl` 并删旧 `.json`。
4. list()：扫描 `.json`+`.jsonl`。
**验证：** `mvn -q -Dtest=SessionStoreTest test` 通过（既有用例兼容）。

## T14: SessionStoreJsonlTest
**文件：** 新增 `test/.../session/SessionStoreJsonlTest.java`
**依赖：** T13
**步骤：**
1. 追加：两次 save 不重复写旧消息、文件为逐行 JSON。
2. 恢复：save → 新 SessionStore load → 消息/累计/meta 一致。
3. 迁移：手写旧 `.json` → load → save → 生成 `.jsonl` 且旧文件删除、内容一致。
4. 坏行：最后一行损坏 → 跳过不崩溃、其余消息保留。
5. 截断：>64KB tool_result 落盘截断标注。
**验证：** `mvn -q -Dtest=SessionStoreJsonlTest test` 通过。

## T15: ChatApp 统计展示 + 告警行
**文件：** `tui/ChatApp.java`
**依赖：** T8、T3、T6、T7
**步骤：**
1. sendAndRender 后更新 lastInputTokens / 会话累计（存 ChatApp 字段，saveSession 写入 SessionMeta）；lastInputTokens 用 `provider.occupancyBasis(input, cacheRead)`——Anthropic `input_tokens` 不含缓存需 + cacheRead，OpenAI `prompt_tokens` 已含缓存原样采用（变更控制 2026-08-11）。
2. renderResult 完成行：`本轮 in X / out Y · 累计 Z · 占用 P%（窗口 W）· cache read R / created C`。
3. 占用 ≥ alertThreshold → 告警行「⚠ 上下文已达 P%（阈值 T%）」。
**验证：** 编译通过 + `ProviderConfigTest`（协议口径）+ T18 集成测试覆盖。

## T16: ChatApp 自动压缩 + /compact
**文件：** `tui/ChatApp.java`
**依赖：** T9、T10、T13
**步骤：**
1. sendAndRender 前：lastInputTokens/window ≥ compactThreshold 且未熔断 → ContextCompactor.compact → saveSession → 显示「压缩边界：N 轮折叠…（pre/post）」。
2. `/compact` 命令：任意时刻触发压缩 + 显示边界。
3. 压缩后 lastInputTokens 重置（等待下一次 API usage 复核）。
**验证：** 编译通过 + `ProviderConfigTest`（协议口径）+ T18 集成测试覆盖。

## T17: TurnInterruptController + ChatApp Ctrl+C 分发 + 优雅关闭
**文件：** 新增 `tui/TurnInterruptController.java`、`tui/ChatApp.java`
**依赖：** T5、T12
**步骤：**
1. 新增 `TurnInterruptController`：收敛 phase/chatThread/activeStream/信号 token；`beginTurn` 用 `Signals.register("INT", …)`（JVM 级）安装，`endTurn` 恢复前一个 handler + join 在途流 + 清中断标志；`onStep`/`onToolCall`/`onStreamCreated` 设槽位；`shutdown()` 退出兜底。
2. 二次 Ctrl+C 逃生门：`onCtrlC` 记录时间戳，本轮内 1.5s 连续第二次 → 恢复默认 SIGINT + 置 `exitRequested` + 取消在途工作；ChatApp 主循环收到 `exitRequested` → 落盘会话后优雅退出（对齐 Codex「再按一次退出」）。
3. GENERATING → 当前 LlmStream.cancel() + 主线程 interrupt → 丢弃半成品 → 会话写 **assistant「（已中断）」**→ saveSession → 回提示符。
4. TOOL_EXEC → 当前 BashTool.cancel() + 主线程 interrupt → 终止本轮 → 写 **assistant「（已中断）」**落盘；状态行工具执行期常驻「（Ctrl+C 中断并终止本轮）」。
5. 中断路径统一：**不追加进行中那轮的 tool_use/tool_result**（保持 user/assistant 交替、无悬空 tool_use）。
6. run() finally：`interrupt.shutdown()`（cancel + join 在途流）→ ui.close。
**验证：** 编译通过 + `TurnInterruptControllerTest`（单次取消/逃生门/跨轮重置）+ T18 集成测试（模拟中断路径）。

## T18: M4 mock 端到端集成测试
**文件：** 新增 `test/.../integration/M4ContextIntegrationTest.java`
**依赖：** T15、T16、T17
**步骤：**
1. TokenStats：mock 双协议多步循环 → 完成行数据（本轮求和/累计/占用%）。
2. Compaction：小窗口（context_window=2000）→ 自动压缩触发 → 摘要消息出现、低价值轮次消失、会话可加载。
3. Interrupt：注入中断（模拟 Ctrl+C 调用 cancel/线程 interrupt）→ Result.interrupted、半成品未写入、会话含 assistant「（已中断）」。
5. 回归-中断交替：中断后会话保持 user/assistant 交替、无悬空 tool_use；紧接着继续一轮（anthropic 与 openai 双协议）回填与加载均正常（验证：M4ContextIntegrationTest + AgentLoopIntegrationTest）。
4. Alert：占用 ≥ 阈值 → 告警输出。
**验证：** `mvn -q -Dtest=M4ContextIntegrationTest test` 通过。

## T19: 全量回归 + 冒烟
**文件：** 无（全仓）
**依赖：** T1–T18
**步骤：**
1. `mvn test` 全量：170（M1–M3 不回归）+ M4 新增全部通过。
2. `mvn -q package` 出包。
3. 手工冒烟（真机可选）：小窗口配置下跑一轮真实对话观察统计/告警；手动 /compact；Ctrl+C 中断。
**验证：** `mvn test` 全绿；jar 可运行。

## 执行顺序
```
T1 → T2 → [T3, T4] → T5 → [T6, T7] → T8
                                  ↘
T9 → T10 → T11        T12（可并行）→ T13 → T14
                                     ↘
[T15, T16, T17] → T18 → T19
```
