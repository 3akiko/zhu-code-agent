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
