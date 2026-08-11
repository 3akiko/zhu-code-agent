状态：approved
# zhuCodeAgent M4：上下文管理与稳定性 Plan

> 依据已批准的 `docs/spec.md`（M4）。本设计满足 F1–F7，沿用 M1–M3 架构与安全红线。

## 架构概览

在现有分层（config → llm → conversation/session → agent → tool → tui）上增量扩展，依赖方向保持自上而下无环：

```
config/LlmLimits（模型表：窗口/输出上限/上下文预算）──┐
                                                     ▼
llm（LlmStream 返回流+取消句柄；Anthropic cache_control + usage 缓存指标；max_tokens 生效）
  ▲                                                      │
  │ consumeStep（累计 in/out/cache、响应中断）            │ stream
agent/AgentRunner（每轮结果扩展：总 in/out + cache）      │
  ▲                                                      │
tui/ChatApp（完成行统计/告警、/compact、Ctrl+C 信号分发、退出 join 在途流）
  │  ├─ context/ContextCompactor（snip → 截断 → LLM 摘要折叠，熔断）
  │  └─ session/SessionStore（JSONL 追加写 + 旧 .json 迁移 + 累计落盘）
tool/SerialToolExecutor + BashTool.cancel()（工具中断）
```

- 上下文占用 = 最近一次 API 返回的 inputTokens（Anthropic input_tokens / OpenAI prompt_tokens），不引入本地 tokenizer。
- 会话累计 = 每轮所有步骤 in+out 单调累加，随 SessionMeta 落盘，跨会话恢复。

## 核心数据结构

### LlmStream（新）
`record LlmStream(BlockingQueue<StreamEvent> events, Runnable cancel)`
- `events`：与现 `stream()` 返回同款事件队列。
- `cancel()`：中断后台流线程（interrupt + 关闭 HTTP 连接），并向队列投递中断事件，保证消费方不永久阻塞。
- `LlmClient.stream(ChatRequest)` 返回类型由 `BlockingQueue<StreamEvent>` 改为 `LlmStream`（接口变更，测试同步 `.events()`）。

### StreamEvent.StreamEnd（扩展）
`record StreamEnd(String stopReason, int inputTokens, int outputTokens, int cacheReadTokens, int cacheCreationTokens)`
- 保留 3 参构造（默认 cache=0）兼容旧调用/测试。
- Anthropic：cacheRead=cache_read_input_tokens，cacheCreation=cache_creation_input_tokens。
- OpenAI/DeepSeek：cacheRead=cached_tokens / prompt_cache_hit_tokens，cacheCreation=0。

### AgentRunner.Result / StepOutcome（扩展）
- `Result` 新增：`int totalInputTokens`、`int totalOutputTokens`（本轮全部步骤求和）、`int cacheReadTokens`、`int cacheCreationTokens`（最后一步缓存命中）、`boolean interrupted`（是否被 Ctrl+C 中断）。保留旧构造（新字段取 0/false）。
- `StepOutcome` 同步新增 cache 与 interrupted 字段。

### SessionMeta（扩展）
新增 `long totalInputTokens`、`long totalOutputTokens`（会话累计，随 JSONL meta 行落盘）。保留旧构造（默认 0）。

### ProviderConfig（扩展）
新增可空字段 `Integer contextWindow`、`Integer maxTokens`、`Boolean promptCache`（默认 true，F4 开关，变更控制 2026-08-11）；新增：
- `int effectiveContextWindow()`：配置值 ?? LlmLimits 模型表 ?? 默认 32K。
- `int effectiveMaxTokens(boolean thinking)`：配置值 ?? 模型表（thinking/plain 分列）?? 64000/8192。

### AppConfig（扩展）
新增：`double contextAlertThreshold`（默认 0.8）、`double contextCompactThreshold`（默认 0.9）、`double contextCompactTarget`（默认 0.6）、`boolean contextSnipEnabled`（默认 true）、`int contextKeepRecentTurns`（默认 8，折叠后保留的最近轮数）。ConfigLoader 解析 `context.*`。

### CompactionOptions / CompactionResult（新，包 com.zhubao.context）
- `record CompactionOptions(double target, boolean snipEnabled, int keepRecentTurns, int toolResultCap)`
- `record CompactionResult(boolean compacted, int foldedTurns, int droppedTurns, int truncatedResults, String summary, String errorMessage)`（`compacted=false` 时其余多为 0）

### ContextCompactor（新，包 com.zhubao.context）
`CompactionResult compact(Conversation conv, LlmClient client, CompactionOptions opts, AgentUi ui)`
- 纯函数式：输入会话 + 客户端，产出折叠后的会话（直接修改 conv）与结果摘要。

## 模块设计

### config / LlmLimits
- **职责**：模型→{contextWindow, maxPlain, maxThinking} 映射表 + 解析。
- **内置表（初版）**：deepseek-v4-flash/pro→窗口 1_000_000；deepseek-chat/reasoner→64K；claude-sonnet-4-5→200K（thinking 64000）；claude-opus-4→200K（thinking 32000）；gpt-4o→128K。未命中→窗口 32K、max 64000/8192。
- **对外接口**：`static ModelLimit resolve(ProviderConfig)`、`static int defaultContextWindow()`。
- **依赖**：ProviderConfig。

### llm
- **AbstractStreamingClient**：`stream()` 改为创建 `LlmStream`；worker 线程引用保存为局部；`cancel()` = interrupt worker + 关闭响应体 + 队列投递 `Error("已中断")`。新增 `join()`（带超时）供退出时优雅关闭。
- **AnthropicClient**（F4/F7）：`system` 由字符串改为块数组 `[{type:text, text, cache_control:{type:ephemeral}}]`；每个 tool 加 `cache_control:{type:ephemeral}`；`max_tokens` 改用 `config.effectiveMaxTokens(config.isThinking())`；解析 usage 的 cache_read/creation → StreamEnd。`prompt_cache=false` 时（provider 开关）system 回退字符串、tools 不带 cache_control（兼容端点，变更控制 2026-08-11）。
- **OpenAiClient**（F4）：解析 `usage.prompt_tokens_details.cached_tokens` 与 `usage.prompt_cache_hit_tokens`（DeepSeek）→ StreamEnd.cacheRead；`max_tokens` 仅在显式配置时写入请求体（默认行为不变，避免回归）。

### context / ContextCompactor（F3）
- **snip（零成本）**：遍历消息，识别「assistant 工具调用消息 + 随后 user 纯 tool_result 消息」配对；若该配对所有 tool_result 均为空输出或「已拒绝执行/权限禁止」，整对丢弃。
- **截断**：剩余消息中 tool_result.output 超 `toolResultCap`（复用 64KB）→ 内存中截断并标注「…（已压缩截断，共 N 字节）」（与落盘截断一致的发送视图）。
- **LLM 摘要折叠**：调用一次 `client.stream(摘要请求)`（system=压缩指令，messages=最旧 `keepRecentTurns` 轮之前的全部轮次，无工具），取最终文本为摘要；将这些轮次替换为一条 user 消息「【上下文已压缩】…摘要…」。折叠后若估算仍超目标（用下一请求复核，不本地估算），可再折叠摘要本身（≤2 层封顶）。
- **熔断**：连续 3 次 compact 失败 → 本次运行自动压缩停用（手动 /compact 不受限）。
- **对外接口**：见数据结构；**依赖**：Conversation、LlmClient、AgentUi（进度/摘要展示）。

### session / SessionStore（F6）
- **写**：`.jsonl` 追加——每次 save：① 追加一行 meta JSON（SessionMeta，last-wins）；② 追加自内存 cursor 起的新消息行（每条 Message 序列化前套用 64KB 截断）。O(1)。
- **读**：`load(id)` 先试 `.jsonl` 再试 `.json`（旧格式迁移路径沿用）；`.jsonl` 逐行解析，最后一条 meta 生效，损坏行跳过并警告。
- **迁移**：加载旧 `.json` 后首次 save 写 `.jsonl` 并在 rename 成功后删除旧 `.json`。
- **cursor**：进程内 `Map<会话id, 已写消息数>`，load 时初始化，save 后更新；防重复追加。
- **list()**：同时扫描 `.json`/`.jsonl`。
- **依赖**：Session/SessionMeta/Message。

### agent / AgentRunner（F1/F5）
- consumeStep 改用 `LlmStream.events`；累计每步 in/out 与 cache 到 Result；捕获中断（`InterruptedException` 或 `Error("已中断")`）→ `interrupted=true` 结果，**不把半成品写入会话**；中断时进行中那轮的工具调用与结果**不回填**（不产生悬空 tool_use）。
- 工具执行返回后检查中断标志 → 终止本轮（不再发起下一步 LLM）。
- `Result` 新字段随 run/runPlan/runPlanContinue/runExecution 全部填充。

### tool（F5）
- **BashTool**：新增 `volatile Process currentProcess` + `cancel()`（destroy 进程树）；execute 末尾清空引用；`waitFor` 收到 InterruptedException → 返回「命令执行被中断」。
- **SerialToolExecutor**：每轮循环检查共享中断标志/线程中断 → 停止后续调用；`Tool`/`ToolExecutor` 接口不变（M5 并行扩展点保留）。

### tui / ChatApp（F1/F2/F3/F5）
- **统计展示**：renderResult 完成行追加：`本轮 in X / out Y · 累计 Z · 占用 P%（窗口 W）· cache read R / created C`（OpenAI 侧仅 read）。
- **告警**：占用 ≥ alertThreshold → 醒目告警行「⚠ 上下文已达 P%（阈值 T%）」。
- **压缩触发**：每轮生成前检查 lastInputTokens/effectiveContextWindow ≥ compactThreshold 且未熔断 → ContextCompactor.compact → 落盘 + 显示「压缩边界：N 轮折叠为摘要（pre … / post …）」；手动 `/compact` 命令任意时刻触发。
- **中断（状态收敛于 `TurnInterruptController`）**：`Signals.register("INT", …)`（JVM 级，JLine 公共 API）仅在本轮进行中安装（GENERATING / TOOL_EXEC 阶段），本轮结束恢复前一个 handler；空闲阶段保留 JLine 默认 Ctrl+C 退出（真机冒烟修正：DumbTerminal 下 Terminal.handle 不注册 JVM 信号，改用 JVM 级注册，2026-08-11）。phase / chatThread / activeStream / 信号 token 全部收敛进 `TurnInterruptController`，ChatApp 只暴露 beginTurn/endTurn/槽位/exitRequested/shutdown：
  - GENERATING → 当前 LlmStream.cancel()（interrupt worker + 主线程）→ 丢弃半成品 → 会话写入 **assistant 消息「（已中断）」**（保持 user/assistant 交替，兼容双协议）并落盘 → 回提示符；
  - TOOL_EXEC → 当前 BashTool.cancel() + 主线程 interrupt → 终止本轮 → 写 **assistant「（已中断）」**落盘；
  - 中断时**不追加进行中那轮的 tool_use/tool_result**（避免悬空 tool_use 破坏 Anthropic 契约）；
  - 状态行在工具执行期间常驻「（Ctrl+C 中断并终止本轮）」提示。
  - **二次 Ctrl+C 逃生门**：本轮内 1.5s 连续第二次 Ctrl+C → 恢复默认 SIGINT + 置 exitRequested（同时取消在途工作），主循环落盘会话后优雅退出（review 加固 2026-08-11）。
- **优雅关闭**：`run()` finally 中 cancel + join 所有在途流线程（带超时），再关 TUI；`saveSession` 在中断/退出路径统一调用。

## 模块交互（数据流）

1. **一轮正常流**：ChatApp.sendAndRender →（可选自动压缩）→ AgentRunner.run（consumeStep 调 LlmClient.stream() 得 LlmStream，消费 events 累计 in/out/cache；有 ToolCall → SerialToolExecutor 串行执行 → 回填 → 继续）→ Result → ChatApp 更新 lastInputTokens/累计 → renderResult（统计+告警+缓存）→ saveSession（JSONL 追加，累计落盘）。
2. **自动压缩流**：sendAndRender 前 `lastInputTokens / window ≥ compactThreshold` → ContextCompactor：snip → 截断 → LLM 摘要折叠 → conv 更新 → saveSession → 显示边界 → 继续 AgentRunner。
3. **Ctrl+C 中断流**：信号线程 → 按阶段分发（cancel 流 / cancel 工具 + interrupt 主线程）→ consumeStep 或工具执行返回中断 → AgentRunner 返回 interrupted Result（不写半成品、不追加进行中那轮 tool_use/tool_result）→ ChatApp 写 assistant「（已中断）」→ saveSession → 回提示符。
4. **退出流**：/exit 或提示符 Ctrl+C → ChatApp.run finally → 在途流 cancel + join（超时）→ ui.close → 主线程退出。

## 文件组织

| 操作 | 文件 | 职责 |
|------|------|------|
| 新增 | `src/main/java/com/zhubao/llm/LlmStream.java` | 流事件队列 + 取消句柄 |
| 新增 | `src/main/java/com/zhubao/config/LlmLimits.java` | 模型表 + 窗口/输出上限解析 |
| 新增 | `src/main/java/com/zhubao/context/ContextCompactor.java` | snip/截断/摘要折叠/熔断 |
| 新增 | `src/main/java/com/zhubao/context/CompactionOptions.java` | 压缩参数 |
| 新增 | `src/main/java/com/zhubao/context/CompactionResult.java` | 压缩结果 |
| 修改 | `src/main/java/com/zhubao/llm/LlmClient.java` | stream() → LlmStream |
| 修改 | `src/main/java/com/zhubao/llm/AbstractStreamingClient.java` | LlmStream + cancel/join |
| 修改 | `src/main/java/com/zhubao/llm/AnthropicClient.java` | cache_control + usage 缓存 + max_tokens 生效 |
| 修改 | `src/main/java/com/zhubao/llm/OpenAiClient.java` | cached_tokens / prompt_cache_hit_tokens |
| 修改 | `src/main/java/com/zhubao/llm/StreamEvent.java` | StreamEnd + cache 字段 |
| 修改 | `src/main/java/com/zhubao/agent/AgentRunner.java` | LlmStream、累计、cache、中断 |
| 修改 | `src/main/java/com/zhubao/session/SessionStore.java` | JSONL 追加 + 迁移 + cursor |
| 修改 | `src/main/java/com/zhubao/session/SessionMeta.java` | +累计 token 字段 |
| 修改 | `src/main/java/com/zhubao/config/AppConfig.java` | +context.* 字段与默认值 |
| 修改 | `src/main/java/com/zhubao/config/ProviderConfig.java` | +contextWindow/maxTokens + effective |
| 修改 | `src/main/java/com/zhubao/config/ConfigLoader.java` | 解析新字段 |
| 修改 | `src/main/java/com/zhubao/tool/builtin/BashTool.java` | currentProcess + cancel() |
| 修改 | `src/main/java/com/zhubao/tool/SerialToolExecutor.java` | 中断标志停止后续调用 |
| 修改 | `src/main/java/com/zhubao/tui/ChatApp.java` | 统计/告警//compact/Ctrl+C/优雅关闭 |
| 修改 | `config.example.yml` | context.* 与 provider 新字段示例 |
| 测试 | `src/test/java/...`（ConfigLoaderTest / AnthropicClientTest / OpenAiClientTest / AgentLoopIntegrationTest / StreamingIntegrationTest 同步 + 新增 ContextCompactorTest / SessionStoreJsonlTest / TokenStatsTest / InterruptTest / CompactionIntegrationTest） | 见 checklist |

## 技术决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 流接口 | `LlmClient.stream()` 返回 `LlmStream(events, cancel)` | 中断需要句柄；为 M5 per-call 状态铺路；变更一次性同步测试 |
| 占用估算 | 以最近一次 API 返回 inputTokens 为准，不引入本地 tokenizer | spec N1；双协议 usage 即完整请求占用，零依赖 |
| 压缩触发 | 每轮生成前检查阈值；手动 /compact 随时 | 简单可靠；中间步超限由下一轮兜底 + 熔断防循环 |
| 折叠策略 | 折叠最旧 N 轮（保留最近 8 轮）为摘要 user 消息；摘要可再折叠 ≤2 层；效果由下一请求 usage 复核 | 无本地估算下的启发式 + API 复核；两层封顶符合 spec |
| 摘要消息 | user 文本 +「【上下文已压缩】」前缀，不新增块类型 | 不破坏会话 JSON 契约与双协议回填 |
| JSONL 格式 | 每行一 JSON：每 save 追加 meta 行（last-wins）+ 新消息行；内存 cursor 防重复 | O(1) 追加、单文件、崩溃容忍（坏行跳过） |
| JSONL 迁移 | load 兼容 .json/.jsonl；旧 .json 首次 save 转 .jsonl 并删旧 | 向后兼容、不丢数据 |
| 中断接入 | `Signals.register("INT")`（JVM 级）仅本轮安装/恢复；状态收敛进 `TurnInterruptController`；本轮内二次 Ctrl+C = 逃生门优雅退出；中断标记用 assistant 角色、不追加进行中 tool_use/tool_result | 真机冒烟修正：哑/受限 PTY 下 JLine Terminal.handle 不注册 JVM 信号会导致 Ctrl+C 杀进程；JVM 级注册真机与哑终端都有效；逃生门消除「一轮卡死无兜底」风险（对齐 Codex） |
| 工具中断 | BashTool.cancel() destroy 进程 + 主线程 interrupt；executor 中断后停后续调用 | 单进程单写者下最小改动，安全（写操作有状态行提示） |
| max_tokens | 解析进 LlmLimits；Anthropic 恒生效；OpenAI 仅显式配置时发送 | 解决写死/opus 报错；避免 OpenAI 默认请求行为回归 |
| 缓存展示 | 完成行实时展示 read/created，不落盘 | spec F4 |

## 变更记录

- 2026-08-11：中断标记由 user 改为 **assistant 角色「（已中断）」**；中断时**不追加进行中那轮的 tool_use/tool_result**（避免连续 user 消息 / 悬空 tool_use，保护 Anthropic/OpenAI 双协议回填，spec N3）。用户批准（S4 阶段，选择 A）。级联更新 task.md / checklist.md 并补回归用例。

- 2026-08-11（review-P2）：新增 provider 可选 `prompt_cache` 开关（默认 true）控制 Anthropic cache_control 断点；关闭时 system 回退字符串、tools 不带 cache_control，兼容不识别 cache_control 的端点（如 DeepSeek anthropic 兼容格式）。用户批准。级联更新 spec/task/checklist。
- 2026-08-11（review 加固）：中断状态收敛进 `TurnInterruptController`（phase/chatThread/activeStream/信号 token 集中管理，ChatApp 只留 beginTurn/endTurn/槽位）；新增二次 Ctrl+C 逃生门（本轮内 1.5s 连续两次 → 恢复默认 SIGINT + 优雅退出），消除「一轮卡死无兜底」风险，对齐 Codex「再按一次退出」。spec F5 / task T17 / checklist 已同步。用户批准。

- 2026-08-11（review-P2/P3 加固）：`TurnInterruptController` 跨线程字段加 volatile（chatThread/activeStream/lastInterruptAt），`exitRequested` 改 `AtomicBoolean`——修复弱内存模型（Apple Silicon）下信号线程写/主线程读不可见导致的逃生门/取消失效；逃生门改为**粘性**（`consumeExitRequest()` 消费前跨 runTurn 不清零），避免被压缩/计划循环的后续 beginTurn 吞掉。用户批准。

- 2026-08-11（变更控制）：F1 占用口径按协议感知——**OpenAI `prompt_tokens` 已含缓存**（官方 `prompt_tokens = prompt_cache_hit_tokens + prompt_cache_miss_tokens`）直接采用；**Anthropic `input_tokens` 不含缓存命中**，占用基数 = `input + cacheRead`（覆盖 DeepSeek /anthropic 兼容端点与真实 Claude）。依据：官方 create-chat-completion 文档 + 真机抓取对比样例（`docs/DeepSeek-OpenAI-vs-Anthropic/`：OpenAI 第 2 轮 prompt_tokens=723=640 hit+83 miss；Anthropic 第 2 轮 input=42 + cacheRead=768）。实现：`ProviderConfig.occupancyBasis` + `ChatApp.applyResult`；本轮 in / 会话累计仍按未缓存 input 求和、缓存单独展示。用户批准。级联更新 spec/task/checklist，补 `ProviderConfigTest` 回归。