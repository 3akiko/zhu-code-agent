状态：approved
# zhuCodeAgent M2：Agent 循环与 Tool Use Checklist

> 依据已批准的 docs/spec.md（AC1-AC11）与 docs/plan.md 设计。每一项通过运行代码或观察行为验证（做 X 看到 Y）。验收结果记录到 `docs/验收报告-M2.md`。

## 实现完整性

- [ ] C1 消息循环（AC1/F1）：mock 模型返回「文本+tool_use」→ 工具在临时工作区真实执行 → 结果一次性回填（assistant 全 toolUse + user 全 toolResult）→ mock 返回 end_turn → 本轮结束输出最终文本；anthropic 与 openai 双协议均跑通完整循环（验证：AgentRunnerTest + 端到端用例）
- [ ] C2 工具集（AC2/F2）：6 个工具在临时工作区行为正确；read_file offset/limit 生效；edit_file 唯一匹配成功、未找到与多匹配返回可读错误；bash 超时/失败返回结构化错误（验证：各工具 Test + BashToolTest）
- [ ] C3 权限（AC3/F3/F4）：只读工具无确认直接执行；write/edit/bash 弹行内单键确认，a/d/s 行为正确；「总是允许」按工具+参数精确记忆、进程重启后失效（不落盘）；rm -rf 指向 cwd 外 → 立即拒绝（不执行、无副作用），指向 cwd 内 → 即使曾「总是允许」也强制确认（验证：PermissionManagerTest + 端到端权限分支 + 破坏性命令用例）
- [ ] C4 /permissions（AC4/F5）：显示本次程序运行内「总是允许」清单；/permissions reset 清空后同类操作重新询问（验证：SlashCommandsTest + PTY 冒烟）
- [ ] C5 路径边界（AC5/F6）：`../` 逃逸、cwd 外绝对路径、符号链接指向 cwd 外 → 工具拒绝并返回可读错误且不产生副作用；写 `.git/` 或 `~/.zhu-code-agent/` 被拒（验证：PathGuardTest + 各工具 Test）
- [ ] C6 循环护栏（AC6/F7）：`tool.max_calls_per_turn=3` 时 mock 返回 4 个工具调用 → 第 4 个不执行、本轮停止并提示可继续；end_turn 正常结束；每步流空闲超时生效（mock 流中途停止输出 → 连续 120s 无事件报「生成超时（无响应）」）（验证：AgentRunnerTest）
- [ ] C7 ToolCall 事件（AC7/F8）：anthropic tool_use 与 openai function_call 解析为统一 ToolCall（id/名称/完整 argumentsJson）；无工具调用时不产生 ToolCall（验证：SseParserTest + 双客户端 Test）
- [ ] C8 会话持久化（AC8/F9）：会话 JSON 含 tool_use/tool_result（含 id）；单条 tool_result 落盘超 64KB 截断并标注；恢复会话后继续输入可复用工具上下文；会话 JSON 无 api_key（验证：SessionStoreTest 更新 + 端到端恢复用例）
- [ ] C9 TUI 展示（AC9/F10）：每个 agent 步骤出现独立状态行且首内容到达清除；工具调用一行摘要（亮青高亮）；结果预览默认 5 行、`ui.tool_preview_lines` 可调、超长标注「…已截断，共 N 行」（验证：PTY 冒烟目检 + 配置生效检查）
- [ ] C10 安全（AC11/N3）：日志/异常/会话 JSON 不含 api_key；「总是允许」清单不落盘（退出重启后重置）；bash 命令与输出按原文落盘（验证：grep 会话目录 + 重启验证 + 代码审查）

## 集成

- [ ] I1 执行器解耦（N2）：循环只依赖 `execute(List<ToolCall>)→List<ToolResult>` 接口；SerialToolExecutor 为 M2 唯一实现，代码注释标注 M5 并行扩展点（验证：代码审查 + AgentRunnerTest）
- [ ] I2 双协议 wire 翻译（F8/N2）：anthropic tool_result 内嵌 user 消息、openai role=tool 消息由客户端负责，调用方（AgentRunner/Conversation）不感知协议差异（验证：双客户端 Test 断言请求 JSON + 端到端双协议用例）
- [ ] I3 旧会话迁移（F9/N4）：加载 M1 格式会话（content 为字符串）自动迁移为 TextBlock，历史不丢、可继续对话（验证：SessionStoreTest 迁移用例）
- [ ] I4 公开接口均有调用方：无死代码，编译 + 全部测试通过（验证：`mvn -q clean test`）

## 编译与测试

- [ ] B1 编译：`mvn -q clean compile` 无错误
- [ ] B2 测试：`mvn -q test` 全绿（M1 能力不回归：契约不变测试原样通过、契约变更测试按新契约更新、TurnRunner 测试迁移至 AgentRunner；M2 新增全部通过）
- [ ] B3 打包：`mvn -q package` 产出可执行 fat jar，`java -jar target/zhu-code-agent.jar` 可启动进入 TUI
- [ ] B4 质量：守卫先于执行（PathGuard/DangerGuard 在工具执行前）；未引入 lint 插件，以单测 + 集成测试 + code review 保证（记录在案）

## 端到端场景

- [ ] E1 mock LLM 完整工具闭环：本地 mock SSE 服务器模拟 anthropic/openai 返回「文本+tool_use」序列 → AgentRunner 在临时工作区真实执行工具 → 结果回填 → mock 返回 end_turn；覆盖：权限允许/拒绝/总是允许、路径越界拒绝、bash 超时、edit 失败重试、64KB 截断落盘、恢复会话后继续工具循环（验证：StreamingIntegrationTest 新增用例全绿）
- [ ] E2 破坏性命令安全红线：临时工作区建真实文件 → rm -rf 指向 cwd 外 → 被拒且文件仍在；rm -rf 指向 cwd 内 → 即使曾「总是允许」也强制确认，拒绝后文件仍在（验证：DangerGuardTest + BashToolTest + 端到端用例）
- [ ] E3 真机冒烟（DeepSeek，手动）：①「读 README 并总结」②「新建文件并写入内容」③「edit 改一处代码」④「跑一个命令」；人工目检：流式输出、每步状态行、工具摘要/预览、行内权限提示 a/d/s、/permissions 查看与 reset（验证：手动执行 + 记录到验收报告）
- [ ] E4 会话恢复工具上下文（mock）：跑一轮含工具调用的对话 → 落盘 → 退出 → 恢复该会话 → 继续输入，模型可引用此前工具结果（验证：端到端恢复用例）

## 验收报告

- [ ] 逐项记录结果与证据（通过/不通过 + 证据），输出到 `docs/验收报告-M2.md`（不覆盖 `docs/验收报告-M1.md`）
