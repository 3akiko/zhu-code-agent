状态：approved
# zhuCodeAgent M1：聊天 TUI + 会话持久化 Spec

## 背景

- 从零实现命令行 Coding Agent（`zhuCodeAgent`），对标 Claude Code / Codex，用于自身学习与 agent 开发面试。
- 当前仓库为空白 Maven 骨架（仅有默认 App.java 与 pom.xml），无任何产品代码。
- 本地有参考项目 `mewcode-java`（基于 Claude Code 的 Java 实现），借鉴其模块划分与配置设计思想。
- M1 是第一个里程碑：先跑通「终端 → LLM API → 流式回显」的最小闭环，并支持会话历史落盘与恢复；验证 Provider 抽象与 TUI 架构，为后续工具调用（M2）打地基。

## 目标

- 用户启动程序后进入彩色终端 TUI，可输入问题、获得流式回复、进行多轮对话。
- 支持 Anthropic Claude 与 OpenAI 两类后端，通过 YAML 配置切换，流式走 SSE、增量即到即显。
- 支持 Claude 的 extended thinking，思考过程以灰色小字实时展示。
- 会话历史自动落盘，启动时可选择恢复历史会话继续对话。
- Provider 层抽象成统一接口，后续新增后端不需要改动调用方。
- M1 不做任何 agent 能力（tool use、文件操作、代码编辑），纯对话 + 会话管理。

## 功能需求

- F1 启动流程：执行启动命令后进入彩色终端 TUI，启动流程为「会话选择（若有历史）→ provider 选择（新建会话且多 provider 时）→ 聊天」；配置中只有一个 provider 且新建会话时直接进入聊天；启动时可通过配置路径参数或环境变量指定配置文件位置。
- F2 聊天输入：底部输入行支持基础行内编辑（方向键移动、退格、Home/End）；支持翻阅本会话已发送的输入历史（上/下方向键）；空输入回车不发送。
- F3 流式输出：发送后，模型回复经 SSE 增量接收并实时打印（到达即显示，不等待完整响应）；流式期间显示状态指示（如 "正在生成…"）。
- F4 多轮记忆（内存）：每轮的用户消息与助手回复追加到会话历史并随下一次请求发送；模型能引用此前轮次的内容。
- F5 YAML 配置：使用 YAML 管理 provider 列表，每条含六个字段：name（标识）、protocol（anthropic/openai）、model、base_url、api_key、thinking（可选，是否启用扩展思考）；api_key 支持直接值或 `${ENV_VAR}` 引用，为空时回退到约定环境变量；配置解析失败时给出可读错误并以非 0 退出码退出；仓库提供示例配置文件。
- F6 双协议与统一抽象：protocol=anthropic 走 Claude Messages API（SSE 流式、extended thinking）；protocol=openai 走 OpenAI Chat Completions API（SSE 流式）；两类响应收敛为统一的流事件序列；新增协议只需新增一个实现，调用方不变。
- F7 extended thinking：provider.thinking=true 且 protocol=anthropic 时请求开启 extended thinking；思考内容以灰色小字实时打印，正式回复以正常颜色打印；思考内容不计入正式回复。
- F8 基础命令：`/help` 打印可用命令与当前 provider/model 信息；`/exit` 退出进程（退出码 0，退出前保存会话）；`/clear` 清空屏幕（不丢会话历史）。
- F9 会话持久化与恢复：每轮回复完成后自动将会话保存到 `~/.zhu-code-agent/sessions/` 下的 JSON 文件；退出（含 /exit）时保存最后一次状态；启动时若存在历史会话，先显示恢复选择页（「新建对话」+ 历史会话列表，按时间倒序，每项显示时间、首条消息摘要、消息数），方向键+回车选择；恢复后该会话完整上下文继续生效；恢复的会话使用其记录时的 provider（name/protocol/model/base_url 快照），api_key 仍从当前配置读取。

## 非功能需求

- N1 技术栈：Java 21 LTS + Maven；JLine3（终端输入/行编辑 + ANSI 256 色渲染）、JDK HttpClient（SSE 流式）、Jackson（JSON 序列化）、SnakeYAML（配置解析）。
  - 变更记录（2026-08-07 用户批准）：原计划的 Mordant 因 3.x 为 Kotlin-first API、Maven 默认构件为 KMP metadata 包、Java 集成成本高而弃用，改为 JLine3 + 自研轻量 ANSI 256 色助手（颜色语义与 mewcode 一致）。
- N2 可维护性：按职责分包（config / llm / tui / conversation / session 等）；关键类有易懂注释；面向接口编程；单一职责。
- N3 稳定性：配置错误、网络错误、API 错误、JSON 解析错误（含会话文件损坏）均给出可读提示，程序不崩溃、不残留半截状态；退出码语义清晰。
- N4 安全：api_key 不打印、不落入日志与异常信息、不写入会话文件；异常消息脱敏。
- N5 测试：SSE 解析、配置加载、请求体构造、thinking 解析、消息历史构建、会话序列化/恢复有单元测试；流式链路用本地 mock HTTP 服务器做集成测试，不依赖真实密钥。
- N6 性能：增量到达即渲染，不做攒批，流式感知延迟低；会话保存为轻量写盘（每轮一次小文件更新），不阻塞 UI 主流程。

## 不做的事（M1 边界）

- 不做 tool use、文件读写、命令执行、代码编辑（属 M2/M3）。
- 不做上下文压缩、token 用量统计、prompt 缓存（属 M4）。
- 不做 Markdown 富渲染（代码块高亮、表格等）。
- 不做流式中断（Ctrl+C 取消本次生成）。
- 不做 MCP、subagents、hooks、skills、多模态。
- 不做 `--resume`/`--continue` 快捷参数（启动选择页已覆盖恢复场景）。
- 不做 `openai-compat` 协议、非交互模式（-p）、remote/Web 模式。

## 验收标准

- AC1（F1）：单 provider 且无历史会话时启动直接进入聊天；双 provider 且新建会话时显示选择列表，方向键+回车选中后进入对应 provider 的聊天；有历史会话时启动先显示恢复选择页。
- AC2（F2）：输入行支持方向键编辑与上下翻历史；空输入回车不发送。
- AC3（F3）：发送后回复增量实时出现（不等完整响应），期间有状态指示。
- AC4（F4）：第二轮提问能引用第一轮内容（如"我刚才问了什么"），模型回答正确（用真实 API 或 mock 服务器验证）。
- AC5（F5）：六字段 YAML 解析正确；`${ENV_VAR}` 引用与空值环境变量回退生效；坏配置给出可读错误且退出码非 0；示例配置文件可复制即用。
- AC6（F6）：anthropic 与 openai 两种协议均能完成流式对话；Provider 通过工厂按 protocol 创建，新增协议不改调用方（代码审查 + 两类 mock 集成测试验证）。
- AC7（F7）：thinking=true 时输出含灰色思考文字与正常色正文；thinking=false 时不出现思考段（mock SSE 事件验证，真实 API 可选）。
- AC8（F8）：`/help` 打印帮助与当前 provider/model；`/exit` 退出且退出码 0；`/clear` 清屏且历史仍在。
- AC9（N3/N4）：断网、401、500、坏 JSON、会话文件损坏时显示可读错误且进程不崩溃；日志与异常信息中不含 api_key。
- AC10（N5/N6）：`mvn test` 全绿；核心逻辑有单测覆盖；mock 流式集成测试通过。
- AC11（F9）：对话中每轮回复完成后会话文件被更新；退出后重启，选择该会话可恢复之前的全部消息与上下文，继续提问仍能引用旧内容（验证：跑一轮对话 → 退出 → 恢复 → 问"我刚才问的什么"）；会话文件中不含 api_key。
