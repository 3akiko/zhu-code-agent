状态：approved
# zhuCodeAgent M2：Agent 循环与 Tool Use Spec

## 背景

- M1（聊天 TUI + 会话持久化）已完成并验收通过：四文档归档于 `docs/milestones/m1/`，基线 `mvn test` 57/57 全绿。
- M2 目标：从"纯对话"升级为"会干活的 agent"——模型在回复中声明工具调用，程序按权限执行并把结果回填，循环直到模型完成。
- 参考实现：Claude Code（tool_use + 权限确认 + 单轮工具上限暂停机制）、Codex CLI（function calling + 权限模式 + /goal token 预算）。本项目 M2 采用"次数上限 + 停止提示"护栏（对标两者思路的简化版）。

## 目标

- 建立完整 agent 循环（ReAct 风格）：模型输出工具调用 → 权限确认 → 执行 → 结果回填 → 继续循环，直到模型 end_turn。
- 内置 6 个工具：read_file / write_file / edit_file / bash / grep / glob。
- 双协议统一：anthropic `tool_use` 与 openai `function_call` 收敛为统一 ToolCall 事件与 tool_result 回填。
- 安全：只读自动执行；写类与 bash 需确认（允许/拒绝/总是允许本次）；路径越界检查；bash 超时/输出上限/无 stdin。
- 循环有界：单轮工具调用上限默认 60（可配），防失控；模型 end_turn 自然结束。
- 会话持久化扩展：tool_use/tool_result 完整落盘（含截断与标注），恢复后 agent 循环上下文完整。

## 功能需求

- **F1 消息循环（agent loop）**：一次用户输入进入 agent 循环：① 调用 LLM；② 若回复含工具调用，按声明顺序逐个执行并回填结果；③ 再次调用 LLM；④ 重复直到模型无工具调用或 `stop_reason=end_turn`，本轮结束并输出最终文本。同一 assistant 消息中的多个工具调用串行执行，结果一次性回填（assistant 消息含全部 tool_use 块 + 后续 user 消息含全部 tool_result 块，符合双协议要求）。
- **F2 内置工具集（6 个）**：
  - `read_file`：读取文件，支持 `offset`/`limit` 行范围（配合"截断后重取"）；文件不存在/越界返回可读错误。
  - `write_file`：创建或覆写文件（cwd 内）。
  - `edit_file`：精确字符串替换（`old_string` → `new_string`），要求唯一匹配；未找到/多匹配返回可读错误，模型调整后重试。
  - `bash`：cwd 下执行命令；无 stdin；30s 超时（kill 进程树）；输出上限 200KB（超出截断并标注）；超时/失败返回结构化错误。
  - `grep`：cwd 内按文本/正则搜索，返回匹配文件与行。
  - `glob`：按通配符模式列出 cwd 内匹配路径。
- **F3 权限确认**：`read_file/grep/glob` 只读自动执行；`write_file/edit_file/bash` 执行前需确认。确认方式为**行内确认**：`[权限] <工具与参数摘要> → 允许(a) / 拒绝(d) / 总是允许本次(s)？(输入后回车)`；bash 提示显示完整命令，文件写提示显示路径与变更摘要（行数/大小）。「总是允许本次」= 按**「工具+参数」精确记忆**（bash 按完整命令串、文件写按路径），**本次程序运行内有效，不落盘，退出程序重置**。
  - 变更记录（2026-08-08）：确认方式由"raw 单键（免回车）"改为"输入 a/d/s 回车确认"。理由：raw 单键依赖终端原始模式（tcsetattr），实测在受限 PTY 下不生效（行缓冲、按键无法即时读取），回车确认保证跨终端稳定；安全语义不变。
- **F4 危险命令兜底**：破坏性命令（如 `rm -rf`，内置小清单）双重保护：① 即使曾被「总是允许」也每次强制确认；② **目标路径必须解析后位于项目 cwd 内**——指向 cwd 外（如 `/`、`~`、`/usr`、`/etc`、`/tmp` 等）→ **立即拒绝、不执行、不产生副作用**。单测与实操均不得对真实目录执行破坏性命令（测试只用临时工作区）。
- **F5 `/permissions` 命令**：查看本次程序运行内的「总是允许」清单；`/permissions reset` 清空（清空后同类操作重新询问）。
- **F6 路径边界**：所有文件工具以 cwd 为根；路径经 `realpath` 解析（含符号链接）后必须在 cwd 内；`..` 逃逸、指向 cwd 外的绝对路径 → **拒绝并返回可读错误**（不崩溃、不产生副作用）；**禁止写 `.git/` 与 `~/.zhu-code-agent/`**（程序自身目录与配置）。
- **F7 循环护栏**：单轮工具调用总数上限默认 60（config：`tool.max_calls_per_turn`），到达上限**停止本轮并提示**"已达本轮工具调用上限，输入『继续』可开启新一轮"；**每步 LLM 调用采用流空闲超时**——该步开始后连续 120s 无任何事件（文本/思考/工具调用等）→ 中断该步并报"生成超时（无响应）"（对标 Claude Code / Codex 的 idle 语义，非 wall-clock，宽容长思考）；工具执行单独超时（bash 30s）；整轮不设墙钟（防失控靠次数上限）。
  - 变更记录（2026-08-08）：由"轮次总超时 120s（沿用 M1）"改为"每步流空闲超时 120s"，用户批准（对齐参考实现的空闲超时语义）。
- **F8 流事件模型扩展**：`StreamEvent` 新增 `ToolCall` 事件（id/名称/参数）；anthropic `content_block_start(tool_use)+input_json_delta` 与 openai `delta.tool_calls` 均收敛为统一 `ToolCall`；模型不声明工具时不产生 ToolCall。
- **F9 会话持久化扩展**：`Conversation`/`Message` 支持结构化内容块（文本 + `tool_use[]`/`tool_result[]`）；session JSON 持久化 `tool_use`（id/工具名/参数）与 `tool_result`（id/输出/是否报错），**单条 tool_result 落盘上限 64KB**（超出截断并标注）；恢复会话后 agent 循环上下文完整；截断结果模型可**重新调用工具**获取完整内容（如 `read_file` offset/limit）。
- **F10 TUI 展示**：状态行粒度改为**每 agent 步骤一条**（⏳ 思考中… → 🔧 执行工具 read_file: xxx → 首内容到达清除，复用 `clearPreviousLine`）；工具调用显示**一行摘要**（工具名+参数摘要，亮青高亮）；工具结果预览默认 **5 行**（config：`ui.tool_preview_lines`），超出标注「…已截断，共 N 行」；思考灰字/正文/错误色沿用 M1。

## 非功能需求

- **N1 技术栈**：Java 21 + Maven；沿用 JLine3 / JDK HttpClient / Jackson / SnakeYAML；bash 执行用 `ProcessBuilder`；新增配置项 `tool.max_calls_per_turn`（默认 60）、`ui.tool_preview_lines`（默认 5），写入 `config.example.yml`。
- **N2 可扩展性**：工具执行与消息循环解耦——循环只依赖 `execute(List<ToolCall>) → List<ToolResult>` 接口，M2 仅实现串行执行器；`ToolCall` 携带 `id`（双协议要求）；为 M5 并行工具执行预留扩展点，M2 不实现、不加配置开关（YAGNI）。
- **N3 安全**：api_key 仍不打印、不落日志、不写会话文件；权限状态（「总是允许」清单）不落盘；bash 命令与输出按原文落盘（与 api_key 脱敏不冲突——api_key 在内存、不经过 bash 环境）。
- **N4 稳定性**：工具错误/超时/越界均返回可读错误（回填给模型或提示用户），进程不崩溃；循环有界（F7）；会话文件损坏仍跳过不崩溃（沿用 M1）。危险命令校验先于执行；破坏性命令测试仅针对临时目录，绝不触碰真实用户目录。
- **N5 测试**：工具执行、路径边界、权限判定、循环控制、事件解析、会话序列化有单元测试；**mock LLM 端到端**（临时工作区执行真实工具）覆盖双协议与权限/边界分支；真机冒烟 1~2 个场景（DeepSeek）；M1 的 57 个测试不回归。
- **N6 性能**：工具结果按需截断（bash 200KB / 落盘 64KB / 预览 5 行），控制内存与会话文件膨胀；结果回填不阻塞 UI 主流程（沿用事件队列模式）。

## 不做的事（M2 边界）

- 不做 plan mode、diff 展示、文件快照回滚/undo（M3）。
- 不做交互式工具结果展开/收缩（M3+，与 M3 的块级/diff 渲染一起做）。
- 不做权限模式演进 acceptEdits / bypassPermissions（M3）。
- 不做无进展检测、Claude Code 式"暂停-继续"（M5）。
- 不做并行工具执行（M5）。
- 不做 OS 级沙箱（Seatbelt/bubblewrap/容器化）（M5+）。
- 不做交互式 PTY 工具/长驻进程（M5+）。
- 不做 /goal 类长任务与累计 token 预算（M5+）。
- 不做 MCP、subagents、hooks、skills、多模态。
- 不做上下文压缩、token 用量统计、prompt 缓存（M4）。

## 验收标准

- **AC1（F1）**：mock 模型返回「文本+tool_use」→ 工具在临时工作区真实执行 → 结果回填 → 模型返回 end_turn → 本轮结束输出最终文本；anthropic 与 openai 双协议均能跑通完整循环。
- **AC2（F2）**：6 个工具在临时工作区各自行为正确（读/写/改/命令/搜索/通配）；read_file offset/limit 生效；edit_file 唯一匹配成功、未找到与多匹配返回可读错误；bash 超时/失败返回结构化错误。
- **AC3（F3/F4）**：只读工具无确认直接执行；write/edit/bash 弹行内确认（输入 a/d/s 回车），a/d/s 行为正确；「总是允许」按工具+参数精确记忆、退出程序后失效（不落盘）；rm -rf 指向 cwd 外 → **立即拒绝（不执行、无副作用）**；指向 cwd 内 → 即使曾「总是允许」也强制确认（验证：临时工作区建真实文件，确认拒绝后文件仍存在）。
- **AC4（F5）**：`/permissions` 显示清单；`/permissions reset` 清空后再次触发同类操作会重新询问。
- **AC5（F6）**：`../` 逃逸、cwd 外绝对路径、符号链接指向 cwd 外 → 工具拒绝并返回可读错误且不产生副作用；写 `.git/` 或 `~/.zhu-code-agent/` 被拒。
- **AC6（F7）**：配置 `tool.max_calls_per_turn=3` 时 mock 返回 4 个工具调用 → 第 4 个不执行、本轮停止并提示可继续；end_turn 正常结束；流空闲超时生效（mock 流中途停止输出 → 连续 120s 无事件后报"生成超时（无响应）"）。
- **AC7（F8）**：anthropic tool_use 与 openai function_call 解析为统一 ToolCall（id/名称/参数）；无工具调用时不产生 ToolCall 事件。
- **AC8（F9）**：会话 JSON 含 tool_use/tool_result（含 id）；单条结果超 64KB 截断并标注；恢复会话后继续输入可复用工具上下文（mock 验证）；会话 JSON 仍无 api_key。
- **AC9（F10）**：每个 agent 步骤出现独立状态行且首内容到达清除；工具调用显示一行摘要；结果预览默认 5 行、`ui.tool_preview_lines` 可调、超长标注截断。
- **AC10（N5）**：`mvn test` 全绿（原 57 不回归 + M2 新增全部通过）；mock 端到端通过；真机冒烟（DeepSeek）1~2 个场景通过。
- **AC11（N3）**：日志/异常/会话 JSON 不含 api_key；权限清单不落盘（退出重启后重置）。
