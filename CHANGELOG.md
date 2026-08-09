# Changelog

本项目的所有重要变更都记录在此文件。格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/)。

## [Unreleased]

### Added（M3：文件编辑增强与 Plan Mode，2026-08-09）
- diff 展示：`edit_file` / `write_file` 结果内嵌变更 diff（+ 绿 / - 红 / @@ 亮青），TUI 完整展示、`ui.diff_max_lines`（默认 200）超长截断标注，diff 随会话落盘恢复可见。
- `/plan` 先计划后执行：计划阶段只读调研（write/edit/bash 被拦截、零副作用），模型 end_turn 即计划完成；审批 `y` 执行（写仍按权限模式确认）/ `d` 拒绝 / **任意文本修改意见重新生成**（变更控制 2026-08-09）。
- 快照回滚：write/edit 前全量快照落盘 `~/.zhu-code-agent/snapshots/<会话ID>/checkpoints.json`；`/undo` 撤销最近检查点、`/rewind` 列表回退（统一机制、跨会话）；回滚记录写回会话；bash 副作用不追踪；>10MB 文件跳过快照。
- 权限模式演进：`/permissions normal|acceptEdits|bypassPermissions`——acceptEdits 写文件自动批准、bypass bash 非危险自动批准；危险命令仍强制确认、cwd 外破坏性命令仍拒绝、禁写目录不变（红线不削弱）；仅内存、退出重置。
- 配置：`ui.diff_max_lines`（默认 200）。
- 测试：170 个（+43：diff / 快照回滚 / 权限模式 / 计划循环 / mock 端到端 / 真机 demo 全流程）。

### Fixed（M3）
- 权限模式参数大小写不匹配（`/permissions acceptEdits` 被误当查看）→ 小写比较（真机冒烟发现）。
- `/plan` 各出口未保存会话 → 统一 saveSession（review P2-1）。
- `WriteFileTool` 覆写无上限读取旧文件（大文件全量入内存）→ 10MB 上限跳过 diff（review P2-2）。
- `DiffGenerator` 仅增删末尾换行的变更 diff 为空 → 保留结尾空行（review P2-3）。
- `FileHistory` 每次快照双重全量读取 checkpoints.json → 合并为一次（review P3）。

### Changed（M3）
- `AgentRunner` 抽 `runLoop`（systemPrompt + addUser 开关），新增 `runPlan` / `runPlanContinue` / `runExecution`。
- `ToolResult` 增加 `renderHint`（FULL/PREVIEW）语义标记，UI 按标记渲染（会话 JSON 格式不变）。
- `SerialToolExecutor` 增加写前快照钩子（成功保持 / 失败丢弃），`ToolExecutor` 接口不变（M5 并行扩展点保留）。

### Added（M2：Agent 循环与 Tool Use，2026-08-08）
- Agent 循环（ReAct）：模型输出工具调用 → 权限确认 → 执行 → 结果回填 → 循环直到 end_turn；同一 assistant 消息的多个工具调用串行执行、一次性回填。
- 内置 6 工具：`read_file`（支持 offset/limit 行范围）、`write_file`、`edit_file`（精确字符串替换，唯一匹配）、`bash`（cwd、无 stdin、30s 超时、200KB 输出截断）、`grep`、`glob`。
- 权限确认：只读自动放行；写类/bash 行内确认（允许 a / 拒绝 d / 总是允许本次 s）；「总是允许」按工具+参数精确记忆、程序运行内有效、不落盘、退出重置；`/permissions` 查看与 reset；危险命令（rm -rf 等）即使曾「总是允许」也强制确认，且目标必须在工作区内。
- 安全边界：`PathGuard`（cwd 为根、realpath 含符号链接校验、禁写 `.git/` 与 `~/.zhu-code-agent/`）、`DangerGuard`（危险命令清单 + rm -rf 路径校验）。
- 双协议工具调用：anthropic `tool_use` / openai `function_call` 收敛为统一 `StreamEvent.ToolCall`；请求体携带 tools 定义；tool_result 按协议回传（anthropic 内嵌 user 消息 / openai role=tool 消息）。
- 会话持久化扩展：消息内容块化（text/tool_use/tool_result），tool_result 单条落盘上限 64KB（截断标注，内存完整），旧 M1 会话自动迁移。
- 循环护栏：单轮工具调用上限 `tool.max_calls_per_turn`（默认 60，触发停止并提示可继续）；每步流空闲超时 120s（宽容长思考）；`ui.tool_preview_lines` 结果预览行数可配（默认 5）。
- 执行器解耦：`ToolExecutor` 接口 + `SerialToolExecutor`（M2 唯一实现，M5 并行扩展点）。
- 测试：119 个（守卫/工具/权限/循环/双协议工具解析/会话迁移/mock LLM 端到端工具闭环/真机冒烟）。

### Fixed（M2）
- `BashTool` 大输出死锁：输出超过管道缓冲区（~64KB）时先 waitFor 后读输出导致子进程写满阻塞假超时 → 独立线程边读边等。
- 权限确认 raw 单键在受限 PTY 不生效（tcsetattr 未切换原始模式）→ 改为「输入 a/d/s 回车确认」（变更控制，spec F3/AC3 已记录）。
- `PathGuard` 根目录与候选路径统一 realpath 比较，规避 macOS /var↔/private/var 符号链接误判越界。

### Changed（M2）
- `TurnRunner` 演进为 `agent/AgentRunner`（消息循环与 TUI 解耦）；`Message` 内容块化（向后兼容加载旧会话）。
- 状态行粒度：每 agent 步骤一条（⏳ 思考中… / 🔧 执行工具…），首内容到达清除；思考灰字与正文/工具摘要分行显示。

### Fixed（M2 review 后，2026-08-08/09）
- 会话恢复后 tool_use 参数丢失：`Message.readBlock` 读错字段名（`arguments` vs `argumentsJson`）且对文本节点误用 `toString()` → 改为 `argumentsJson` + `asText()`（兼容旧 `arguments`）。
- `rm -rf` 路径校验被 shell 展开绕过：`~/x`、`$HOME/x`、`$(pwd)/x` 字面上落在 cwd 内被放行 → 目标含 `~ $ 反引号 $() ; & | < >` 等展开字符直接拒绝。
- 工作区边界扩展：从「仅 rm -rf」扩展到所有 `rm`/`rmdir`/`mv`/`cp`（删除/移动/复制目标必须位于工作区内，与 flags 无关），对齐 Claude Code/Codex。
- 权限记忆粒度：`/permissions` 支持带参数（`/permissions reset` 此前被误判为未知命令）。

### Docs（M2）
- M2 四文档（spec/plan/task/checklist）已批准并归档至 `docs/milestones/m2/`；验收报告 `docs/验收报告-M2.md`。

### Added（M1：聊天 TUI + 会话持久化）
- 彩色终端 TUI：JLine3 行编辑/输入历史 + ANSI 256 色渲染（用户青/思考灰/错误红/状态绿）。
- 启动状态机：会话选择（新建 + 历史恢复）→ Provider 选择（多 provider 时）→ 聊天。
- 双后端流式对话：Anthropic Claude Messages（SSE + extended thinking，思考灰字展示、signature 多轮回传）与 OpenAI Chat Completions（SSE）。
- 多轮对话记忆：内存历史随请求回传。
- 会话持久化与恢复：`~/.zhu-code-agent/sessions/{id}.json`，每轮回复完成后落盘，启动可选恢复，原子写、损坏文件跳过、不含 api_key。
- 统一 Provider 抽象：`LlmClient` 接口 + `LlmClientFactory` + `StreamEvent` 密封事件模型。
- YAML 六字段配置：name/protocol/model/base_url/api_key/thinking，api_key 支持 `${ENV_VAR}` 与环境变量回退。
- 基础命令：`/help` `/clear` `/new`（保存当前会话并新建，不退出程序）`/exit`。
- 测试：57 个测试（配置/SSE 解析/双协议客户端/thinking 回传/会话存取/mock 端到端流式）。

### Changed
- 流式状态行「⏳ 正在生成…」改为：第一个流式内容（思考/正文）到达时原地清除（ANSI 上移清行），出错无内容时也清除，避免残留（方案 A）。
- 技术栈调整（用户批准）：弃用 Mordant（Kotlin-first、KMP metadata 包、Java 集成成本高），改为 JLine3 + 自研 ANSI 256 色助手。
- Message 角色字符串改为 `Role` 枚举（JSON 仍为小写 user/assistant）。

### Verified
- 2026-08-07 用真实 DeepSeek API（`protocol: openai` + `https://api.deepseek.com`）完成端到端验证：流式输出、多轮记忆、会话落盘、密钥脱敏。

### Added
- DeepSeek 的 Anthropic 兼容格式支持（`protocol: anthropic` + `base_url: https://api.deepseek.com/anthropic`）：thinking 灰字直接可用，真实 API 验证通过（2026-08-07）。

### Fixed
- `AnthropicClient` 补发 `max_tokens`（Anthropic 协议必需；thinking 时 64000，否则 8192），此前未发可能被服务端拒绝。
- JLinePicker 选择「新建对话」时 `Optional.of(null)` 抛 NullPointerException：改为返回下标（`pickIndex`），「新建对话」= 下标 0 为合法选择，Ctrl+C 返回 empty，二者不再混淆（2026-08-07 真实使用中发现并修复）。

### Changed
- 流式状态行「⏳ 正在生成…」改为：第一个流式内容（思考/正文）到达时原地清除（ANSI 上移清行），出错无内容时也清除，避免残留（方案 A）。
- 会话选择列表的时间改为本地时区显示（MM-dd HH:mm），此前显示 UTC ISO 时间。

### Docs
- 新增 `docs/roadmap.md`、`docs/spec.md`、`docs/plan.md`、`docs/task.md`、`docs/checklist.md`（M1 归档于 `docs/milestones/m1/`）。
- 新增 `docs/implementation.md`（实现记录与 Claude Code/Codex 对比）。
