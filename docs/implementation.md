# 实现记录（Implementation Notes）

> 持续累积文档：每个里程碑完成后更新「实现了什么、怎么实现的、与 Claude Code/Codex 的对比、踩坑」。
> 各里程碑的详细设计见 `docs/milestones/mN/` 归档。

## M1 聊天 TUI + 会话持久化

- 状态：已完成（2026-08-07）
- 对应归档：`docs/milestones/m1/`

### 实现了什么

- 彩色终端 TUI：JLine3 行编辑/输入历史 + ANSI 256 色（用户青/思考灰/错误红/状态绿/高亮亮青）
- 启动状态机：SESSION_SELECT（新建对话 + 历史会话列表）→ PROVIDER_SELECT（多 provider）→ CHAT
- 双后端流式对话：Anthropic Messages（SSE + extended thinking）与 OpenAI Chat Completions（SSE）
- 多轮记忆：历史随请求回传；Anthropic thinking 的 thinking 块 + signature 多轮回传
- 会话持久化/恢复：`~/.zhu-code-agent/sessions/{id}.json`，原子写，损坏文件跳过，不含 api_key
- 基础命令 /help /clear /exit；统一 Provider 工厂；核心逻辑单测 + mock HTTP 集成测试（共 57 个测试）

### 怎么实现的

**模块架构（依赖单向、无环）：**

```
Main → ChatApp(TUI 状态机) → LlmClientFactory → AnthropicClient / OpenAiClient
                        ├─→ Conversation（内存历史）→ ChatRequest
                        └─→ SessionStore（落盘/恢复）
```

**核心机制：**

- **流式**：`LlmClient.stream(ChatRequest)` 在后台线程发 HTTP/SSE 请求，`SseParser` 按行解析（event:/data:/空行），协议事件收敛为密封接口 `StreamEvent`（TextDelta/ThinkingDelta/ThinkingComplete/StreamEnd/Error）写入 `BlockingQueue`；UI 主线程 `poll(100ms)` 渲染（到达即显示，不攒批）。
- **thinking**：Anthropic `thinking_delta` → 灰色小字实时展示；`signature_delta` → 捕获 signature；`content_block_stop` → ThinkingComplete。多轮时把上一轮 assistant 的 thinking 块 + signature 回传（协议硬性要求）。
- **多轮/持久化**：`Conversation` 维护 `List<Message(Role, content, thinking, signature)>`；每轮完成后 `SessionStore.save` 刷新 updatedAt/标题/消息数；启动时按时间倒序列出会话供恢复。
- **安全**：api_key 只在内存（ProviderConfig），不打印、不落日志、不写入会话文件（会话只存 ProviderSnapshot：name/protocol/model/baseUrl）；配置异常消息脱敏。

**与 mewcode-java 的异同**：借鉴其模块划分（LlmClient 接口 + StreamEvent 密封接口 + Provider 工厂 + ProviderConfig 六字段 + SESSION/PROVIDER 选择），但 TUI 用 JLine3 + 自研 ANSI 而非其手写 tea 框架，并新增会话持久化与 mock 集成测试。

### 与 Claude Code / Codex 的对比

| 维度 | zhuCodeAgent（M1） | Claude Code / Codex |
|------|--------------------|--------------------|
| 聊天 TUI + 流式 | ✅ 已实现 | ✅ |
| 多后端 / extended thinking | ✅ anthropic+openai，thinking 灰字展示 | Claude Code 以 Anthropic 为主，支持 thinking |
| 会话恢复 | ✅ 启动选择恢复（M1） | ✅ --resume/--continue |
| 工具调用（读写文件/bash/grep） | ❌ M2 | ✅ |
| 权限控制 / plan mode | ❌ M2/M3 | ✅ |
| 上下文管理（compact/缓存/用量告警） | ❌ M4 | ✅ |
| MCP / Subagents / Hooks / Skills | ❌ M5+ | ✅ |
| 技术栈 | Java 21 | TypeScript(Node) / Rust |

**取舍说明**：M1 聚焦「终端 → LLM → 流式回显」闭环与 Provider 抽象，先把地基打稳；工具调用（Coding Agent 的核心价值）在 M2 引入，需要扩展 StreamEvent（ToolCall 事件）与权限确认。

### 踩坑记录

1. **Mordant 不可用**：3.x 是 Kotlin-first（Java 需 Widget 转换），Maven Central 默认构件为 KMP metadata 包（`mordant-jvm` 变体才含 JVM 类）；参考项目 mewcode 实际也没用它。→ 改为 JLine3 + 自研 `Ansi` 256 色助手（用户批准）。
2. **SSE 末尾空行**：`message_stop`/`[DONE]` 事件需要空行触发 flush；测试夹具首版缺末尾空行导致走 EOF 兜底、usage 全 0。→ 测试夹具补空行。
3. **JLine EOF**：Ctrl+D 时 `readLine` 抛 `EndOfFileException` 而非返回 null，未捕获会打印堆栈。→ 在 `TerminalUi.readLine` 捕获转 null 表示退出。
4. **`Map.of` 顺序不定**：thinking 配置 JSON 字段顺序随迭代器变化，请求体断言不稳定。→ 用 `LinkedHashMap` 保证确定性。
5. **魔法字符串角色**：Message 里 "user"/"assistant" 散落多处。→ 抽出 `Role` 枚举（`@JsonValue`/`@JsonCreator` 保持 JSON 仍为小写 wire 值）。

## M2 Agent 循环与 Tool Use

- 状态：已完成（2026-08-08）
- 对应归档：`docs/milestones/m2/`

### 实现了什么

- Agent 循环（ReAct）：`agent/AgentRunner` 取代 `tui/TurnRunner`——一次用户输入 = 多步「LLM 调用 → 工具执行 → 结果回填」，直到模型 end_turn；单轮上限 60、每步流空闲超时 120s。
- 内置 6 工具（`tool/builtin/`）：read_file（offset/limit）、write_file、edit_file（精确替换）、bash（无 stdin/30s/200KB）、grep、glob。
- 权限（`permission/PermissionManager`）：只读自动放行；写类/bash 行内确认 a/d/s；「总是允许」按工具+参数精确记忆、仅内存、退出重置；危险命令强制确认。
- 安全守卫：`PathGuard`（cwd 为根、realpath 校验、禁写 .git/ 与 ~/.zhu-code-agent/）、`DangerGuard`（危险清单 + rm -rf 目标路径校验）。
- 双协议工具调用：`StreamEvent.ToolCall` 统一 anthropic tool_use / openai function_call；请求带 tools 定义；tool_result 按协议回传。
- 会话扩展：Message 内容块化（Text/ToolUse/ToolResult）、tool_result 落盘 64KB 截断标注、旧 M1 会话自动迁移。
- 测试：119 个全绿（+62），含 mock LLM 端到端工具闭环（临时工作区真实工具）与真机冒烟 4 场景。

### 怎么实现的（关键设计）

```
AgentRunner（agent 包）          消息循环：stream → 事件累积 → 无工具则结束 / 有工具则执行
  ├─ ToolExecutor(接口)          execute(List<ToolCall>) → List<ToolResult>（M5 并行扩展点）
  │    └─ SerialToolExecutor     串行：权限判定 → 执行/拒绝 → 收集
  ├─ PermissionManager           只读 ALLOW；写类 NEED_CONFIRM；危险强制确认；记忆仅内存
  ├─ ToolRegistry + Tool         6 内置工具，全过 PathGuard；bash 过 DangerGuard
  └─ AgentUi(接口)               状态行/增量渲染/工具摘要预览/权限回调（TUI 与测试各自实现）
```

- **协议正确的批量回填**：一条 assistant 消息的 N 个 tool_use 必须一次性回填全部 tool_result（双协议强制）→ 串行执行只发生在执行器内部，循环/回填/落盘与"串行还是并行"无关（M5 并行 = 新增执行器实现）。
- **三层截断**：bash 输出 200KB（回填模型）/ tool_result 落盘 64KB / TUI 预览 5 行（可配）；内存始终保留完整结果。
- **安全先于执行**：PathGuard/DangerGuard 在工具执行前校验，rm -rf 目标越界直接拒绝不执行。

### 与 Claude Code / Codex 的对比（M2）

| 维度 | zhuCodeAgent（M2） | Claude Code / Codex |
|------|--------------------|--------------------|
| Agent 循环 + 工具调用 | ✅ 6 内置工具 + 串行循环 | ✅ 并行部分工具 + 更长循环 |
| 权限控制 | ✅ 只读自动/写类确认/总是允许（内存） | ✅ 权限模式（plan/acceptEdits/bypass）+ 会话级记忆 |
| 危险命令防护 | ✅ rm -rf 路径校验 + 强制确认 | ✅ 危险命令拦截 |
| OS 级沙箱 | ❌ M5+（Seatbelt/bubblewrap） | ✅ macOS Seatbelt / Linux bubblewrap |
| 循环护栏 | 次数上限 60 + 流空闲超时 120s | 单轮工具数上限暂停 + 上下文/预算 |
| 工具结果展开/收缩 | ❌ M3+ | ✅ 交互式折叠 |

### 踩坑记录（M2）

1. **bash 大输出死锁**：先 waitFor 再读输出，输出 >64KB（管道缓冲）时子进程写满阻塞 → 假超时。→ 独立线程并发读，kill 后取回已读部分。
2. **raw 单键权限确认不可靠**：受限 PTY 下 tcsetattr 未生效（行缓冲），`enterRawMode()+reader().read()` 收不到按键。→ 改「输入 a/d/s 回车确认」，spec F3/AC3 变更控制记录。
3. **macOS /var↔/private/var**：PathGuard 根目录与候选路径统一 realpath 比较，规避平台符号链接误判越界。
4. **Java 文本块转义**：mock SSE 里 partial_json 的 `\"` 在文本块中会被转义吃掉一层，wire 层 JSON 非法。→ fixture 用 `\\"` 保留反斜杠。

## M3 文件编辑增强与 Plan Mode

- 状态：已完成（2026-08-09）
- 对应归档：`docs/milestones/m3/`；验收报告 `docs/验收报告-M3.md`；真机 demo `docs/demo-M3文件编辑与PlanMode.md`（已实测通过）

### 实现了什么

- **diff 展示**：`diff/DiffGenerator`（公共前缀/后缀 + 中间变更块，零依赖）——`edit_file`/`write_file` 结果内嵌 diff（`-` 红 / `+` 绿 / `@@` 亮青），TUI 按 `ToolResult.renderHint`（FULL/PREVIEW）完整展示、`ui.diff_max_lines`（默认 200）超长截断标注、diff 随 tool_result 落盘恢复可见。
- **`/plan` 先计划后执行**：`AgentRunner.runPlan/runPlanContinue/runExecution` + `PlanModeExecutor`（计划阶段只读，write/edit/bash 拦截回填错误、零副作用）；模型 end_turn 即计划完成、最终文本即计划；审批 `y` 执行（写仍按权限模式确认）/ `d` 拒绝 / **任意文本修改意见重新生成**（变更控制 2026-08-09）。
- **快照回滚**：`history/FileHistory` + `FileCheckpoint`——每次 write/edit 前把文件完整内容快照落盘 `~/.zhu-code-agent/snapshots/<会话ID>/checkpoints.json`（原子写、损坏跳过）；`/undo` 回退最近检查点、`/rewind` 列表回退（统一机制、跨会话）；回滚动作 `[回滚]` 写回会话；bash 副作用不追踪；>10MB 文件跳过快照并注明。
- **权限模式演进**：`permission/PermissionMode`（normal / acceptEdits / bypassPermissions），`/permissions` 切换；acceptEdits 写文件自动批准、bypass bash 非危险自动批准；**危险命令强制确认、cwd 外破坏性命令拒绝、禁写目录等 M2 红线不削弱**；仅内存、退出重置。
- 配置：`ui.diff_max_lines`（默认 200）。
- 测试：170 个全绿（+43），含脚本化 LLM 端到端（计划批准/拒绝、三权限模式、undo/rewind 跨会话真实回滚）与真机 demo ①–⑥ 全流程实测。

### 怎么实现的（关键设计）

- **全量快照而非补丁**：检查点存修改前完整内容，回滚 = 直接写回（`FileHistory.rewindTo` 按检查点倒序恢复 + `subList` 截断丢弃）；`DiffGenerator` 只负责展示，回滚不依赖 diff。
- **单轮计划闭环**：`/plan` = 一个受约束的 agent 循环（`PlanModeExecutor` 拦截写类），模型 end_turn 即计划完成；批准后 `runExecution` 不重复 addUser——`runLoop` 的 `addUser` 开关让同一循环服务"新输入/计划/续计划/执行"四种形态。
- **语义化渲染**：`ToolResult.renderHint`（FULL/PREVIEW）——UI 按语义标记渲染而非按工具名分支；renderHint 只存在于内存对象，落盘走 `ToolResultBlock`，会话 JSON 格式不变。
- **统一检查点机制**：`/undo` = `/rewind` 到最近检查点（一套落盘存储、两个命令入口），跨会话有效。
- **安全不削弱**：权限模式只影响"是否询问"，危险命令强制确认与 cwd 外拒绝仍由 `DangerGuard`/`PathGuard` 兜底；计划阶段拦截先于执行，零副作用。

### 与 Claude Code / Codex 的对比（M3）

| 维度 | zhuCodeAgent（M3） | Claude Code / Codex |
|------|--------------------|--------------------|
| diff 展示 | ✅ 结果内嵌轻量 diff + 彩色 + 超长截断 + 落盘 | ✅ 块级 diff 可展开（CC）；diff 高亮（Codex） |
| /plan 先计划后执行 | ✅ 单轮计划 + 审批 y/d + 修改意见重新生成（只读调研） | ✅ plan mode（CC 可多轮对话） |
| undo / rewind | ✅ 全量快照 + 跨会话（对齐 CC 的 FileSnapshotService 思路） | ✅ CC 有；Codex 靠 git 回滚 |
| 权限模式 | ✅ acceptEdits / bypassPermissions 三档（危险仍强制确认） | ✅ plan/acceptEdits/bypass |
| 交互式结果展开/收缩 | ❌ M3+ | ✅ CC 有 |

### 踩坑记录（M3）

1. **权限模式命令大小写**：`handlePermissions` 将命令整体小写化后与驼峰常量比较 → 永不匹配、`/permissions acceptEdits` 被当查看 → 统一小写比较（真机冒烟发现）。
2. **diff 纯换行差异不可见**：`splitLines` 丢弃结尾空行 → 仅增删末尾换行的 edit 显示为空 diff → 保留结尾空行（review P2-3）。
3. **覆写大文件全量读内存**：`WriteFileTool` 覆写前无上限 `readString` 旧内容（绕过 10MB 快照上限）→ 超 10MB 跳过 diff（review P2-2）。
4. **快照双重全量读取**：`nextId` + `append` 各读一次 checkpoints.json → 合并为一次 load（review P3）。
5. **/plan 不落盘**：handlePlan 各出口未 saveSession → 统一补齐（review P2-1）。


## M4 上下文管理与稳定性

- 状态：已完成（2026-08-12）
- 对应归档：`docs/milestones/m4/`；验收报告 `docs/验收报告-M4.md`；真机 demo `docs/demo-M4上下文管理.md`（已实测通过）；DeepSeek 双协议对比样例 `docs/DeepSeek-OpenAI-vs-Anthropic/`

### 实现了什么

- **token 统计与展示**：完成行「本轮 in/out（该轮全部步骤求和）· 会话累计（单调累加、随 meta 落盘恢复）· 占用% / 窗口 · cache read/created」；占用基数**协议感知**（`ProviderConfig.occupancyBasis`：Anthropic `input_tokens + cache_read_input_tokens`，OpenAI `prompt_tokens` 已含缓存不重复计，变更控制 2026-08-11）。
- **接近上限告警**：`context.alert_threshold`（默认 0.8），占用 ≥ 阈值输出「⚠ 上下文已达 P%（阈值 T%）」。
- **双层渐进压缩**：① 本地瘦身 `ContextCompactor.snip`（丢弃空/「已拒绝执行」/「权限禁止」低价值 tool 配对、截断 >64KB tool_result）+ ② LLM 摘要折叠（最旧 N 轮折叠为带「【上下文已压缩】」标记的 user 摘要、合并进最近段首条真实 user、可再折叠 ≤2 层）；生成前占用 ≥ `compact_threshold`（默认 0.9）自动触发 + 手动 `/compact` 任意时刻 + 连续 3 次失败熔断（自动停用、手动仍可用）。
- **prompt 缓存**：Anthropic 请求 system（块数组）/ 每个工具定义打 `cache_control:{type:ephemeral}` 断点（provider `prompt_cache` 开关默认 true，关闭回退字符串 system）；双协议缓存命中解析进 `StreamEnd`（Anthropic `cache_read/creation_input_tokens`、OpenAI `prompt_tokens_details.cached_tokens`、DeepSeek `prompt_cache_hit_tokens`）并在完成行展示。
- **流式中断**：JVM 级 `Signals.register("INT", …)`（DumbTerminal 下 `Terminal.handle` 不注册信号的真机发现）→ 生成中取消当前 `LlmStream`、工具执行中销毁 Bash 进程树；半成品回滚、写 **assistant「（已中断）」**、不追加进行中那轮 tool_use/tool_result（保持 user/assistant 交替、无悬空 tool_use）；**二次 Ctrl+C 逃生门**（本轮内 1.5s 连续两次 → 恢复默认 SIGINT + 优雅退出，对齐 Codex「再按一次退出」）；状态收敛进 `TurnInterruptController`（volatile/AtomicBoolean 修复弱内存模型可见性 + 逃生门粘性）。
- **会话存储 JSONL**：`SessionStore` 追加写（每 save 一条 last-wins meta 行 + 自 cursor 新消息行，O(1)），旧 `.json` 自动迁移、历史收缩整文件重写、损坏行容错跳过、list 去重；累计随 meta 恢复。
- **max_tokens 可配置化**：`LlmLimits` 内置模型表（deepseek-v4-flash/pro 1M 窗口、thinking 64000/plain 8192、opus 32000）+ provider `context_window` / `max_tokens` 覆盖。
- 测试：215 个全绿（+45），含 mock 双协议端到端（统计/压缩/中断/逃生门）与真机 demo ①–⑤ 全流程实测。

### 怎么实现的（关键设计）

- **协议感知占用基数**：Anthropic 协议 `input_tokens` 只含未缓存部分、`cache_read_input_tokens` 单独返回（DeepSeek `/anthropic` 端点与真实 Claude 同约定），占用 = input + cacheRead 才是真实窗口占用；OpenAI `prompt_tokens` 官方语义 = 命中 + 未命中，直接采用。真机对比样例（`docs/DeepSeek-OpenAI-vs-Anthropic/`）证明：OpenAI 第 2 轮 `prompt_tokens=723 = 640 hit + 83 miss`，Anthropic 第 2 轮 `input=42 + cacheRead=768 = 810`——同前缀、语义差一个量级。
- **压缩不引入本地 tokenizer**：占用/阈值全以 API 返回 usage 为准（spec N1 零依赖），压缩效果由下一次请求复核（`lastInputTokens` 压缩后重置为 0）。
- **中断 = 信号重定向而非进程退出**：`Signals.register` 在本轮内把 SIGINT 从「终止进程」重定向为「取消本轮」，endTurn 恢复前一个 handler；提示符处的 Ctrl+C 仍由 JLine 处理（退出语义不受影响）。
- **JSONL last-wins + cursor 追加**：meta 行记录最新快照，加载取最后一条 meta；消息按内存 cursor 只追加新行；检测到历史收缩（消息数 < cursor）→ 整文件重写（review P1 修复）。
- **会话交替纪律（spec N3）**：中断标记用 assistant「（已中断）」、压缩摘要合并进首条真实 user、不追加悬空 tool_use——保证 user/assistant 交替与双协议回填（M8+ 记录 `/rewind` 回滚记录连续 user 待修项）。

### 与 Claude Code / Codex 的对比（M4）

| 维度 | zhuCodeAgent（M4） | Claude Code / Codex |
|------|--------------------|--------------------|
| token 统计/占用 | ✅ 完成行统计 + 占用%（协议感知含缓存） | ✅ 状态行剩余上下文（Codex）；CC 有成本统计 |
| 上限告警 | ✅ alert_threshold 可配 | ✅ auto-compact 前提示（Codex） |
| 自动压缩 | ✅ 双层（snip 瘦身 + LLM 摘要折叠）+ 手动 /compact + 熔断 | ✅ CC 四层渐进（snip → microcompact → context collapse → auto-compact，cache-aware）；Codex auto-compact 默认开 |
| prompt 缓存 | ✅ cache_control 断点 + 双协议命中展示 | ✅ 前缀缓存 + cache-aware 决策 |
| 流式中断 | ✅ Ctrl+C 取消本轮（半成品回滚）+ 二次逃生门 | ✅ Esc/Ctrl+C 中断（CC）；Codex 二次 Ctrl+C 退出 |
| 会话存储 | ✅ JSONL 追加写 + 迁移/容错/去重 | ✅ 各自会话/续传机制 |
| max_tokens | ✅ 模型表 + provider 覆盖 | ✅ 自动/可配 |

### 踩坑记录（M4）

1. **DeepSeek `/anthropic` 端点 `input_tokens` 不含缓存**：真机第 2 轮 input=321 + cacheRead=1024，旧口径只算 321 → 告警/自动压缩永不触发 → 占用基数改为协议感知（变更控制 2026-08-11，对比样例见 `docs/DeepSeek-OpenAI-vs-Anthropic/`）。
2. **JLine DumbTerminal 信号不注册**：受限 PTY 下 `Terminal.handle` 只存 handler 不注册 → Ctrl+C 直接杀进程 → 改用 JVM 级 `Signals.register`（真机复测通过）。
3. **弱内存模型逃生门失效**：信号线程写/主线程读无同步 → 逃生门/取消失效 → `TurnInterruptController` 跨线程字段加 volatile、`exitRequested` 改 AtomicBoolean、逃生门粘性跨 runTurn 不清零（review P2/P3）。
4. **v4-flash 在 anthropic 端点默认思考**：不传 `thinking` 也返回 thinking 块（对比样例实证），`thinking` 参数更像提示而非强制。
5. **prompt 缓存需长前缀**：短 prompt 两轮 cache=0，长 system + 工具定义后第 2 轮才命中；Anthropic 端点还需显式 `cache_control` 断点。
6. **中断轮不计累计**：被取消的流拿不到 usage（StreamEnd 未到达），中断轮实际消耗不进会话累计（设计内行为）。


## 路径校验细则（PathGuard / DangerGuard）

> 2026-08-08 补充（review 后文档化，含两处修复：tool_use 参数往返、rm -rf shell 展开拒绝）。

### PathGuard：文件工具的工作区边界

**规则（对所有文件类工具执行前强制校验）**：
1. **根 = 项目 cwd**（`System.getProperty("user.dir")`），构造时 `toAbsolutePath().normalize()`。
2. **归一化**：相对路径基于 root 解析，`..`/`.` 归一化后必须 `startsWith(root)`，否则拒绝（`路径越界（必须在工作区内）`）。
3. **realpath（符号链接）**：路径存在 → 对整条路径 `toRealPath()`；不存在 → 对**最近存在的祖先** `toRealPath()` 再拼回剩余部分。解析后必须仍 `startsWith(root)`，否则拒绝（`符号链接指向工作区外`）。
   - **为什么**：符号链接是文件系统里指向另一路径的特殊文件（如 `ln -s ~/Documents/secret ./data`）。只做字符串检查时 `data/notes.txt` 看似在 cwd 内，实际读写落在 cwd 外；`toRealPath()` 逐层解开链接得真实路径后再比较，防"看起来在里面、实际在外面"的偷渡。
   - **不存在时找祖先**：`write_file` 新建文件时目标不存在，无法对整条路径 realpath → 对最近存在的祖先 realpath 再拼回剩余部分（如 `data` 是链接到 cwd 外的目录时，`data/new.txt` 会被解析到 cwd 外而拒绝，文件不会真实创建）。
   - **root 也 realpath**：两侧统一 realpath 后比较，规避 macOS `/var ↔ /private/var` 类平台链接导致的误判。
4. **root 与候选统一 realpath 比较**：规避 macOS `/var ↔ /private/var` 这类平台符号链接导致误判（两侧都解析后再比）。
5. **禁写目录**：`.git/`（任意层级组件名为 `.git`）与 `~/.zhu-code-agent/`（程序自身目录）→ 拒绝（`禁止操作 .git 目录` / `禁止操作程序自身目录`）。
6. **失败语义**：校验失败抛 `ToolException`，由工具层捕获转为 `ToolResult.error` 回填模型，进程不崩溃、不产生副作用。

**覆盖工具**：read_file / write_file / edit_file / grep（子目录）/ glob；bash 的 cwd 目录也来自 PathGuard.root。

### DangerGuard：危险命令防护

**危险判定**：内置前缀清单（`sudo rm`、`mkfs`、`dd if=`、`shutdown`、`reboot`、`chown -R`、`:(){` 等）+ `rm` 递归强制删除识别（`-rf`/`-fr`/`-r -f` 分离 flag 均识别）。命中即「危险」→ 权限层**强制确认**（即使曾「总是允许」）。

**文件系统修改命令目标路径校验（用户安全红线，2026-08-08 扩展）**：
- **覆盖范围**：所有 `rm`（含无 flags / `-f` / `-r`，对齐 Claude Code/Codex「delete 边界与 flags 无关」）+ `rmdir` + `mv`/`cp` 目标（最后一个参数，或 `-t`/`--target-directory` 的值）。
- **规则**：
  1. **展开字符拒绝**：目标含 shell 展开/元字符（`~`、`$`、反引号、`$()`、`;`、`&`、`|`、`<`、`>`、引号、括号等，即不在白名单 `字母数字 / . _ - * ? [ ]` 内）→ **直接拒绝**（无法静态校验，运行时可能展开到 cwd 外）。覆盖 `rm ~/x`、`rm $HOME/x`、`mv a ~/dest`、`cp a $(pwd)/x` 等。
  2. **越界拒绝**：目标经 PathGuard 解析必须位于 cwd 内（`/tmp/x`、`../x`、`/etc/hosts` 等拒绝；`rm` 校验所有参数、`mv`/`cp` 校验目标）。
  3. **通配符**：取通配符前前缀静态校验（前缀必须在 cwd 内），通配符本体由 shell 在 cwd 内展开。
  4. **执行前校验**：BashTool.execute 在 `ProcessBuilder` 前调用 `DangerGuard.assertFileMutationsInWorkspace`——即使权限被绕过也拦得住（纵深防御）。
- **危险分级不变**：`rm -rf` 等仍属「危险命令」→ 权限层强制确认（即使曾「总是允许」）；普通 `rm`/`mv`/`cp` 正常确认 + 路径限制。

### 相关修复记录（review 后）

- **P1：tool_use 参数落盘丢失**——`Message.readBlock` 曾读 `arguments`（序列化字段实为 `argumentsJson`）且对文本节点用 `toString()`；改为读 `argumentsJson` + `asText()`（兼容 `arguments` 对象/字符串），并补往返与恢复断言。
- **P1：rm -rf shell 展开绕过**——静态校验只看字面量，`~/x`、`$HOME/x` 可展开到 cwd 外；新增白名单字符校验，含展开字符的目标直接拒绝。
- **P2：权限记忆粒度**——「总是允许」按 spec F3 改为文件写按**路径**、bash 按**完整命令串**记忆（此前按完整参数 JSON，同路径改内容会重复询问）。
- **安全扩展（review 后）**——路径限制从「仅 rm -rf」扩展到所有 `rm`/`rmdir`/`mv`/`cp`（删除/移动/复制目标必须位于工作区内，与 flags 无关；含 shell 展开字符直接拒绝），对齐 Claude Code / Codex 的「工作区边界适用于所有文件系统修改操作」。


## M5 Agent 扩展：并行与 Subagents

- 状态：已完成（2026-08-13）
- 对应归档：`docs/milestones/m5/`

### 实现了什么

- **① 客户端并发安全重构（硬前置）**：`AnthropicClient`/`OpenAiClient` 的流累积状态（token 统计/stopReason/thinking/toolAccums/activeBody/cancelled）从实例字段迁入 **per-call `StreamState`**（基类 + Anthropic/OpenAi 子类）；`AbstractStreamingClient.stream()` 每次调用 new 状态对象、worker 线程写入、cancel 闭包捕获；`LlmClientFactory` 按 provider 名**缓存复用单例**（主会话与子任务共享实例并发 stream）。
- **② 并行工具执行**：新增 `AbstractToolExecutor`（单调用公共逻辑：权限/快照/执行/结果），`SerialToolExecutor` 继承（行为不变）、`ParallelToolExecutor` 顺序分段——只读工具 ∪ `task` 组段、段内 **Java 21 虚拟线程**并行；write/edit/bash 串行；**结果严格按原调用顺序回填**；单失败不拖垮段；Ctrl+C 中断在途读段。
- **③ Subagents / Task**：`task` 内置工具（第 7 个）——父 agent ReAct 中调用即派生**独立会话**子任务（内存隔离、不落盘），复用 `AgentRunner.runSubtask` + `SUBTASK_SYSTEM_PROMPT`；**三层护栏**（嵌套深度默认 2（工具池裁剪：深度封顶子 agent 不注册 task）+ 并行子任务上限 4 + 子任务步数上限 30）；**权限继承**（共享父 PermissionManager，父已批准自动放行；未批准回主 UI 确认 + `[子任务#N]` 来源标注 + 全局串行锁一次一弹窗）；**结构化摘要回填**（状态 + 摘要截断 2000 + token，走现有 tool_result 通道，双协议/N3 交替兼容）；**并行子任务**（虚拟线程）；**级联中断**（`SubagentCoordinator.cancelAll()` interrupt 全部在途子任务线程，摘要标记「被中断」）。

### 怎么实现的

**并发架构（M5 核心决策）：**

```
stream() 每次调用:
  StreamState state = newStreamState();        // per-call：token/stopReason/thinking/toolAccums/activeBody/cancelled
  Thread worker = new Thread(runStream(...));   // 后台消费 SSE 入队
  return new LlmStream(queue, cancel闭包, join闭包); // 句柄：事件队列 + 取消 + 等待

LlmClientFactory.create(name) → ConcurrentHashMap 缓存复用单例

ParallelToolExecutor.execute(calls):
  [R1,R2, W1, R3] → 并行段(R1,R2) 虚拟线程 → W1 串行 → 并行段(R3)
  UI 回调只在调度线程串行调（提交前 onToolCall / 收集后 onToolResult）
  AgentDepth 显式传播（虚拟线程不继承 ThreadLocal）→ 嵌套子任务深度正确

TaskTool.execute:
  深度检查 → 并行计数 → 独立 Conversation → SubagentUi(静默) →
  SubagentCoordinator.register → AgentRunner.runSubtask → 摘要回填 → unregister
```

**关键机制：**

- **per-call 状态 vs ThreadLocal**：选 per-call 状态对象（业界一致：Claude AsyncLocalStorage / Codex 所有权）——cancel 跨线程需外部访问、闭包可捕获、实例复用不串；ThreadLocal 绑定线程且无法被 cancel 闭包访问。
- **深度传播**：并行段虚拟线程不继承 ThreadLocal，`AgentDepth`（静态 ThreadLocal）在提交前捕获父线程深度、线程内恢复，使「子任务内再派 task」读到正确深度（工具池裁剪 + 运行时兜底拒绝）。
- **并行 bash 进程管理**：`BashTool` 用并发集合 `activeProcesses` 管理在途进程，cancel/中断统一销毁全部（单引用在并行下会覆盖丢失）。
- **终端输出收敛**：UI 回调调度线程串行 + `TerminalUi` 输出锁 + 子任务静默折叠单行——「执行并行、汇报串行、细节折叠」。
- **权限**：并行段天然无弹窗（只读自动放行 + task 不确认）；子任务权限回主 UI + `ReentrantLock` 全局串行（一次一弹窗）；权限继承（alwaysAllowed 共享）。

### 与 Claude Code / Codex 的对比

| 维度 | zhuCodeAgent（M5） | Claude Code / Codex |
|------|--------------------|--------------------|
| 客户端并发 | per-call 状态持有者 + 实例缓存复用 | AsyncLocalStorage（Node）/ 所有权（Rust） |
| 并行工具执行 | 读段虚拟线程并行 / 写·bash 串行 / 保序 | coordinator 模式「写按文件集串行、研究可并行」 |
| Subagents | `task` 工具派生子任务：独立会话/权限继承/三层护栏/工具池裁剪/摘要回填/并行/级联中断 | `AgentTool`/`Task` 工具：sidechain 独立 transcript + task-notification 摘要回填；Codex `spawn_agent` 独立 session + final message |
| 嵌套护栏 | 深度上限（工具池裁剪 + 运行时拒绝）+ 并行上限 + 步数上限 | teammate 禁止嵌套 teammate（CC）/ agent_max_depth + spawn slots（Codex） |
| 权限 | 子任务回主 UI 统一确认 + 来源标注 + 串行锁 + 继承 | leader permission bridge（CC）/ 权限桥接 |

**取舍说明**：M5 聚焦「多 agent 并行」闭环——先修并发安全硬前置，再做并行工具（接口不变、安全不削弱），最后 Subagents（独立会话 + 护栏 + 摘要回填 + 级联中断）。不做 MCP/Hooks/Skills（M6）、OS 沙箱（M7）、子任务可展开全文（M8+ 候选）；子任务不落盘（仅内存 + 摘要进父会话）。

### 踩坑记录（M5）

1. **客户端并发是硬前置**：未重构 per-call 状态前并行会数据竞争——先修 `StreamState` 再做并行/子任务。
2. **ThreadLocal 深度跨虚拟线程丢失**：`newVirtualThreadPerTaskExecutor` 每任务新线程不继承 ThreadLocal → 嵌套子任务深度护栏失效（孙 agent 工具池仍含 task）。→ 用 `AgentDepth` 显式传播。
3. **/plan 可经 task 绕过只读**：`PlanModeExecutor` 拦截集不含 task，计划阶段可派子任务改写工作区（M3 回归）→ 拦截集加 task。
4. **并行 bash 进程泄漏**：`BashTool` 单 `currentProcess` 引用在并行下被覆盖，cancel 只销毁最后一个 → 并发集合管理。
5. **真机权限弹窗后 Ctrl+C 退出进程**：JLine `readLine`（权限弹窗）临时接管 SIGINT、结束后可能恢复为默认 → 弹窗结束后 `rearmSignalHandler()` 重新注册本轮 handler。
6. **工具摘要与流式正文粘连**：模型先输出正文再调工具时 `🔧` 摘要接在同一行 → `textOpen` 标志 + 打印前先换行。
7. **测试隔离（工厂缓存）**：集成测试每用例新建 mock server（端口不同），`LlmClientFactory` 缓存复用会串 baseUrl → 测试 `@BeforeEach resetCache()`；`MockHttpServer` 需显式线程池（JDK HttpServer 默认单线程会阻塞并发请求）。
