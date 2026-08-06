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
