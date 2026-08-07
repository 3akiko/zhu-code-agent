状态：approved
# zhuCodeAgent M2：Agent 循环与 Tool Use Tasks

> 依据已批准的 docs/spec.md 与 docs/plan.md。按序执行，每个任务都有独立验证；全部完成后进入 S6 验收。M1 归档于 docs/milestones/m1/，本文件不覆盖。

## 文件清单

| 操作 | 文件 | 职责 |
|------|------|------|
| 修改 | `config/AppConfig.java`、`config.example.yml` | 新增 tool.maxCallsPerTurn（默认 60）、ui.toolPreviewLines（默认 5） |
| 新建 | `tool/Tool.java`、`tool/ToolCall.java`、`tool/ToolResult.java`、`tool/ToolException.java` | 工具抽象与数据结构 |
| 新建 | `tool/ToolRegistry.java` | 6 个内置工具注册、byName、只读标记 |
| 新建 | `tool/PathGuard.java` | cwd 边界（realpath）+ 禁写 .git/ 与 ~/.zhu-code-agent/ |
| 新建 | `tool/DangerGuard.java` | 危险命令清单 + rm -rf 目标路径校验（cwd 内） |
| 新建 | `tool/ToolExecutor.java`、`tool/SerialToolExecutor.java` | 执行器接口（M5 并行扩展点）+ 串行实现 |
| 新建 | `tool/builtin/ReadFileTool.java`、`WriteFileTool.java`、`EditFileTool.java`、`GrepTool.java`、`GlobTool.java`、`BashTool.java` | 内置 6 工具 |
| 新建 | `permission/PermissionDecision.java`、`permission/PermissionManager.java` | 权限判定与「总是允许」记忆 |
| 修改 | `llm/StreamEvent.java` | 新增 ToolCall 事件 |
| 修改 | `llm/ChatRequest.java` | 新增 tools 字段（List<ToolSpec>） |
| 修改 | `llm/AnthropicClient.java` | 请求带 tools；解析 tool_use → ToolCall |
| 修改 | `llm/OpenAiClient.java` | 请求带 tools；解析 function_call → ToolCall |
| 修改 | `conversation/Message.java`、`conversation/Conversation.java` | 内容块（Text/ToolUse/ToolResult）；buildRequest 带 tools |
| 修改 | `session/Session.java`、`session/SessionStore.java` | 块序列化、64KB 截断、旧格式迁移 |
| 新建 | `agent/AgentUi.java`、`agent/AgentRunner.java` | UI 回调 + 消息循环 |
| 修改 | `tui/ChatApp.java`、`tui/SlashCommands.java` | AgentRunner 接入、每步状态行、工具摘要/预览、/permissions |
| 删除 | `tui/TurnRunner.java` | 由 agent/AgentRunner 取代（测试迁移） |
| 新建/修改 | 测试：`tool/PathGuardTest`、`DangerGuardTest`、各工具 Test、`permission/PermissionManagerTest`、`agent/AgentRunnerTest`、SseParser 工具样例、SessionStore 工具消息/迁移 Test；修改 `ConversationTest`、`AnthropicClientTest`、`OpenAiClientTest`、`StreamingIntegrationTest`、`SlashCommandsTest` | 见各任务验证 |

## T0: 配置扩展
**文件：** `config/AppConfig.java`、`config.example.yml` 依赖：无
**步骤：** ① AppConfig 增加 `toolMaxCallsPerTurn`（默认 60）与 `uiToolPreviewLines`（默认 5），SnakeYAML 绑定（对应键 `tool.max_calls_per_turn` / `ui.tool_preview_lines`）；② config.example.yml 加注释示例。
**验证：** `mvn -q test -Dtest=ConfigLoaderTest` 全绿（新增默认值与绑定用例）。

## T1: tool 基础 + 安全守卫
**文件：** `tool/Tool.java`、`ToolCall.java`、`ToolResult.java`、`ToolException.java`、`ToolRegistry.java`、`PathGuard.java`、`DangerGuard.java` 依赖：无
**步骤：** ① Tool 接口（name/description/inputSchema/execute）+ ToolCall/ToolResult record；② ToolRegistry（注册 6 工具、byName、只读集合 read_file/grep/glob）；③ PathGuard：resolveInWorkspace（归一化 + realpath 须在 cwd 内，否则 ToolException）+ assertNotForbidden（.git/ 与 ~/.zhu-code-agent/）；④ DangerGuard：危险命令清单（rm -rf/rm -fr/sudo rm/mkfs/dd 等）+ assertRmTargetsInWorkspace（解析 rm -rf 目标逐个校验在 cwd 内）。
**验证：** `mvn -q test -Dtest=PathGuardTest,DangerGuardTest`：`../` 逃逸、绝对路径越界、符号链接指向外 → 拒绝；写 .git/ 与 ~/.zhu-code-agent/ 拒绝；rm -rf 指向 cwd 外 → 抛错（不执行）。

## T2: 内置文件工具
**文件：** `tool/builtin/ReadFileTool.java`、`WriteFileTool.java`、`EditFileTool.java`、`GrepTool.java`、`GlobTool.java` 依赖：T1
**步骤：** ① 各工具实现 execute，先过 PathGuard；② read_file 支持 offset/limit 行范围；③ write_file 新建/覆写返回字节数；④ edit_file 唯一匹配替换，未找到/多匹配 → 可读错误；⑤ grep 返回 文件:行:内容 摘要；⑥ glob 返回路径列表。
**验证：** 各工具 Test（临时目录）：读写往返、行范围、edit 唯一/未找到/多匹配、grep/glob 结果、越界与禁写路径返回可读错误。

## T3: bash 工具
**文件：** `tool/builtin/BashTool.java` 依赖：T1
**步骤：** ProcessBuilder：cwd、stdin=/dev/null、waitFor 30s 超时（destroyForcibly + 进程树）、输出 200KB 截断、非 0 退出码/超时 → 结构化错误；DangerGuard 前置（危险命令强制确认标记 + rm -rf 路径校验）。
**验证：** `BashToolTest`：echo 正常、退出码非 0、超时命令（sleep 60）被 kill、交互命令无 stdin 失败、输出超 200KB 截断、rm -rf /tmp/xxx 被拒（目标仍在）。

## T4: 权限模块
**文件：** `permission/PermissionDecision.java`、`permission/PermissionManager.java` 依赖：T1
**步骤：** decide(call)：只读 → ALLOW；命中「总是允许」（工具+规范化参数）且非危险 → ALLOW；危险 → NEED_CONFIRM（强制）；其余写类/bash → NEED_CONFIRM；rememberAlways/reset/allowedList（仅内存）。
**验证：** `PermissionManagerTest`：只读 ALLOW、写类 NEED_CONFIRM、s 后同参数 ALLOW、不同参数仍 NEED_CONFIRM、危险命令即使曾允许仍 NEED_CONFIRM、reset 后清空。

## T5: llm 协议扩展
**文件：** `llm/StreamEvent.java`、`llm/ChatRequest.java`、`llm/AnthropicClient.java`、`llm/OpenAiClient.java` 依赖：T1（ToolSpec 由 ToolRegistry 提供）
**步骤：** ① StreamEvent 新增 ToolCall(id,name,argumentsJson)；② ChatRequest 加 List<ToolSpec> tools；③ AnthropicClient：请求体 tools 数组、content_block_start(tool_use)+input_json_delta 累积 → 完成后发 ToolCall；④ OpenAiClient：tools 数组、delta.tool_calls（index 聚合 name/arguments）→ ToolCall；⑤ 客户端负责把 tool_result 块翻译为 wire 格式（anthropic user 消息内嵌 tool_result；openai role=tool 消息）。
**验证：** `SseParserTest` 新增工具样例；`AnthropicClientTest`/`OpenAiClientTest` 更新请求体断言（含 tools）+ 新增 tool_use/function_call 解析用例（事件序列含 ToolCall、argumentsJson 完整）。

## T6: conversation 内容块
**文件：** `conversation/Message.java`、`conversation/Conversation.java` 依赖：T5
**步骤：** ① ContentBlock 密封接口（TextBlock/ToolUseBlock/ToolResultBlock）；② Message 改为 role + List<ContentBlock> + thinking/signature；③ Conversation：addUser/addAssistant 适配文本，新增 addToolUseBlocks/addToolResultBlocks、buildRequest(system, tools) 携带完整块历史；④ 旧字符串 content 构造兼容（迁移用）。
**验证：** 更新 `ConversationTest`：历史顺序、块内容、tools 回传、标题摘要、消息数——全绿。

## T7: session 扩展
**文件：** `session/Session.java`、`session/SessionStore.java` 依赖：T6
**步骤：** ① 块列表序列化；② 单条 tool_result 输出 >64KB 截断 + 追加「…（已截断，共 N 字节）」标注（内存保留完整）；③ 加载旧 M1 格式（content 为字符串）→ 迁移为 TextBlock；④ 仍不含 api_key。
**验证：** 更新 `SessionStoreTest`：块往返、64KB 截断标注、旧格式迁移、损坏跳过、无 apiKey、无 .tmp。

## T8: agent 循环
**文件：** `agent/AgentUi.java`、`agent/AgentRunner.java`、`tool/SerialToolExecutor.java` 依赖：T2/T3/T4/T5/T6
**步骤：** ① AgentUi 回调（onStepStatus/onToolCallSummary/onToolResultPreview/askPermission）；② SerialToolExecutor：逐个 decide→执行/拒绝→收集（拒绝=error 结果）；③ AgentRunner：addUser → 步循环（≤maxCallsPerTurn）→ stream(buildRequest(system, tools)) → 消费事件（TextDelta/Thinking/ToolCall/Error/StreamEnd）→ 有 ToolCall 则执行并一次性回填 assistant(全 toolUse)+user(全 toolResult) → 继续；end_turn/无工具调用 → 结束；每步流空闲超时 120s（连续无事件）；步数达上限 → 提示可继续。
**验证：** `AgentRunnerTest`（stub LlmClient 返回预设事件序列 + 临时工作区）：文本→end_turn 直接结束；tool_use→执行→回填→end_turn 完整循环；权限拒绝 → error 结果回填；步数上限触发；流空闲超时触发。

## T9: TUI 接入
**文件：** `tui/ChatApp.java`、`tui/SlashCommands.java` 依赖：T8
**步骤：** ① ChatApp 接入 AgentRunner（AgentUi 实现：每步状态行 ⏳/🔧，首内容到达 clearPreviousLine 清除）；② 工具调用一行摘要 + 结果预览 uiToolPreviewLines 行 + 截断标注；③ 权限提示行内单键 a/d/s；④ SlashCommands 新增 /permissions（查看/重置）。
**验证：** `SlashCommandsTest` 新增 /permissions 用例全绿；PTY 冒烟：真实跑一轮工具对话观察状态行/摘要/预览/权限提示。

## T10: 移除 TurnRunner + 集成测试迁移
**文件：** 删除 `tui/TurnRunner.java`；修改 `integration/StreamingIntegrationTest.java` 依赖：T8
**步骤：** ① 删除 TurnRunner；② StreamingIntegrationTest 改为驱动 AgentRunner（流式→落盘→恢复链路保留）。
**验证：** 迁移后的集成测试全绿（原 4 项能力：流式渲染、保存、恢复、thinking 分离）。

## T11: mock LLM 端到端（工具场景）
**文件：** 修改 `integration/StreamingIntegrationTest.java`、`llm/StreamTestSupport.java` 依赖：T8/T10
**步骤：** mock HttpServer 返回「文本+tool_use」序列 → AgentRunner 在临时工作区真实执行工具 → 结果回填 → mock 返回 end_turn；覆盖 anthropic 与 openai 双协议、权限允许/拒绝/总是允许、路径越界拒绝、bash 超时、edit 失败重试、64KB 截断落盘、恢复后继续工具循环。
**验证：** 新增端到端用例全绿（临时工作区，不碰真实目录；破坏性命令用例只断言"拒绝且目标仍在"）。

## T12: 全量回归 + 打包 + 真机冒烟
**文件：** 无 依赖：全部
**步骤：** ① `mvn clean test` 全绿（M1 能力不回归 + M2 新增）；② `mvn package` 产出 fat jar 可启动；③ 真机冒烟（DeepSeek）：「读 README 总结」「新建并写入文件」「edit 改一处」「跑个命令」各一次，人工目检权限提示/状态行/摘要/预览。
**验证：** 全绿 + jar 可跑 + 冒烟记录到验收报告（docs/验收报告-M2.md）。
