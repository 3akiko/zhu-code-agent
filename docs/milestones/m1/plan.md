状态：approved
# zhuCodeAgent M1：聊天 TUI + 会话持久化 Plan

> 依据已批准的 docs/spec.md（M1）设计。本文档与 Java 技术栈相关。

## 架构概览

M1 划分为六个模块，依赖方向自上而下、无环：

```
Main（入口）
  ├─ config    配置加载与校验（YAML → AppConfig/ProviderConfig）
  ├─ session   会话持久化（Session/SessionMeta/SessionStore）
  ├─ conversation 内存对话历史（Conversation/Message）
  ├─ llm       大模型统一抽象（LlmClient + StreamEvent + 工厂 + SSE 解析）
  └─ tui       彩色终端界面（TerminalUi/JLinePicker/ChatApp 状态机）
```

- **Main**：解析 CLI 参数（`--config <path>`）与环境变量，调用 ConfigLoader 加载配置，出错时输出可读错误并以非 0 退出；成功则启动 ChatApp。
- **config**：SnakeYAML 将 YAML 绑定为 AppConfig（provider 列表），完成 api_key 解析（直接值 → `${ENV_VAR}` → 协议约定环境变量）与字段校验。
- **conversation**：维护内存态消息历史（List<Message>），负责追加用户/助手消息、构造 ChatRequest（含 Anthropic thinking 回传）、生成会话标题摘要。
- **llm**：`LlmClient` 统一接口，`AnthropicClient`/`OpenAiClient` 两个实现分别把对话转成对应协议请求，经 JDK HttpClient 发起 SSE 流式请求，把增量事件写入 `BlockingQueue<StreamEvent>`；`LlmClientFactory` 按 protocol 创建实现；`SseParser` 提供共享的 SSE 协议解析。
- **session**：SessionStore 将会话（元数据 + 消息历史）序列化为 `~/.zhu-code-agent/sessions/{id}.json`，支持列表/加载/保存；损坏文件跳过并提示，不崩溃；**不保存 api_key**。
- **tui**：JLine3 提供终端与行编辑（LineReader + 输入历史）与 raw 模式按键读取，`Ansi` 助手提供 ANSI 256 色渲染；`JLinePicker` 实现通用方向键选择列表（provider 选择、会话选择）；`ChatApp` 为状态机（SESSION_SELECT → PROVIDER_SELECT → CHAT），主线程轮询流事件队列并渲染。

线程模型：UI 主线程负责输入与渲染；每次发送在后台线程发起 HTTP/SSE 流式请求并 put 事件到队列；UI 用带超时的 poll 消费队列，二者通过 `BlockingQueue<StreamEvent>` 解耦。

## 核心数据结构

### Message（conversation 包）
```java
public final class Message {
    String role;              // "user" | "assistant"
    String content;           // 正式文本（OpenAI 的 system 由客户端自行构造，不存这里）
    String thinking;          // 可选：assistant 的思考文本（Anthropic 多轮回传用）
    String thinkingSignature; // 可选：Anthropic thinking signature（多轮回传必需）
}
```
> 说明：Anthropic 协议要求 extended thinking 之后的多轮请求必须回传上一轮 assistant 消息的 thinking 内容块与 signature，否则报错。因此 Message 记录 thinking/signature，满足 spec F4+F7 的组合场景。session JSON 同样持久化这两个字段（thinkingSignature 非密钥，可落盘）。

### ChatRequest（llm 包）
```java
public record ChatRequest(String systemPrompt, List<Message> messages) {}
```
统一请求结构，由 Conversation 构造，由各协议客户端翻译成各自 API 格式。

### StreamEvent（llm 包，sealed interface）
```java
public sealed interface StreamEvent {
    record TextDelta(String text)                  implements StreamEvent {} // 正式回复增量
    record ThinkingDelta(String text)              implements StreamEvent {} // 思考增量
    record ThinkingComplete(String signature)      implements StreamEvent {} // Anthropic thinking 结束，带 signature
    record StreamEnd(String stopReason, int inputTokens, int outputTokens) implements StreamEvent {} // 正常结束 + 用量
    record Error(String message)                   implements StreamEvent {} // 出错（不保证有 StreamEnd）
}
```
M1 不含 ToolCall 事件（M2 再扩展）。事件契约：正常结束必发 StreamEnd；出错只发 Error。

### ProviderConfig（config 包）
```java
public class ProviderConfig {
    String name;      // 必填，唯一标识
    String protocol;  // "anthropic" | "openai"
    String model;     // 必填
    String baseUrl;   // 必填；为空时用协议默认值
    String apiKey;    // 可为空：${ENV_VAR} 引用或环境变量回退
    boolean thinking; // 默认 false
}
```

### AppConfig（config 包）
```java
public class AppConfig {
    List<ProviderConfig> providers;   // 至少 1 个
    Path sessionsDir;                 // 默认 ~/.zhu-code-agent/sessions（M1 固定，不做配置项）
}
```

### SessionMeta / ProviderSnapshot / Session（session 包）
```java
public record ProviderSnapshot(String name, String protocol, String model, String baseUrl) {}
// 不含 apiKey：恢复会话时 api_key 仍从当前配置读取（spec F9 / N4）

public class SessionMeta {
    String id;            // yyyyMMdd-HHmmss-<shortId>
    Instant createdAt;    // 创建时间
    Instant updatedAt;    // 最后更新时间（列表排序用）
    String title;         // 首条用户消息前 30 字符（无消息则 "新对话"）
    int messageCount;
    ProviderSnapshot provider;
}

public class Session {
    SessionMeta meta;
    List<Message> messages;
}
```
JSON 结构（`{id}.json`）：
```json
{
  "id": "20260807-103000-a1b2c3",
  "createdAt": "2026-08-07T10:30:00Z",
  "updatedAt": "2026-08-07T10:35:12Z",
  "title": "帮我解释一下 JVM 内存模型…",
  "messageCount": 4,
  "provider": { "name": "claude", "protocol": "anthropic", "model": "claude-sonnet-4", "baseUrl": "https://api.anthropic.com" },
  "messages": [
    { "role": "user", "content": "…" },
    { "role": "assistant", "content": "…", "thinking": "…", "thinkingSignature": "…" }
  ]
}
```

### LlmClient（llm 包）
```java
public interface LlmClient {
    BlockingQueue<StreamEvent> stream(ChatRequest request);
}
```
`stream()` 实现约定：内部创建后台线程 → 发起 HTTP SSE 请求 → 解析事件 → put 到队列 → 正常完成 put StreamEnd，异常 put Error 并结束线程。

### SseEvent / SseParser（llm 包）
```java
public record SseEvent(String event, String data) {} // event 可为 null（OpenAI 只有 data）

public final class SseParser {
    public static List<SseEvent> parse(String text);      // 按空行切分事件块，供单测/调试
    public void feedLine(String line);                    // 逐行喂入，空行触发完整事件
}
```
规则：`event:` 行设置事件名；`data:` 行累加数据；空行触发一个事件；`:` 开头为注释忽略；支持多行 data（以换行连接）。

## 模块设计

### Main（入口）
**职责：** 解析 `--config <path>`（缺省时读环境变量 `ZHU_CODE_AGENT_CONFIG`，再缺省用 `~/.zhu-code-agent/config.yml`）；调用 ConfigLoader.load；异常打印可读错误并 `System.exit(1)`；成功 new ChatApp(config).run()。
**对外接口：** `public static void main(String[] args)`
**依赖：** config, tui

### config
**职责：** YAML → AppConfig；api_key 三级解析（直接值 → `${ENV_VAR}` 展开 → 协议约定环境变量 ANTHROPIC_API_KEY / OPENAI_API_KEY）；校验 provider 非空、protocol 合法、model 非空；重复 name 报错。
**对外接口：** `static AppConfig load(String path)`（path 为 null 时走默认路径）；`class ConfigException extends RuntimeException`（消息可读、不含密钥）。
**依赖：** SnakeYAML

### conversation
**职责：** 内存历史；`addUser/addAssistant`；`buildRequest(systemPrompt)`（Anthropic 场景把历史中 assistant 的 thinking/signature 回传——由客户端读取 Message 字段实现，Conversation 只提供完整历史）；生成标题摘要与消息数。
**对外接口：** `addUser(String)`、`addAssistant(String content, String thinking, String signature)`、`List<Message> getMessages()`、`ChatRequest buildRequest(String systemPrompt)`、`String previewTitle()`、`int messageCount()`、`ProviderSnapshot getProvider()`。
**依赖：** 无（纯数据结构）

### llm
**职责：** 统一抽象 + 双协议实现 + SSE 解析 + 事件模型。
- `AnthropicClient`：POST `{baseUrl}/v1/messages`，Header `x-api-key`、`anthropic-version: 2023-06-01`、`content-type: application/json`；body 含 `model`、`system`（顶层）、`messages`（翻译 Message：assistant 带 thinking/signature 时输出 content blocks）、`stream: true`，thinking=true 时加 `thinking: {type:"enabled", budget_tokens: 32000}`；解析 `content_block_start/content_block_delta(thinking_delta|text_delta)/message_delta/signature` 事件映射为 StreamEvent。
- `OpenAiClient`：POST `{baseUrl}/v1/chat/completions`，Header `Authorization: Bearer <key>`；body 含 `model`、`messages`（systemPrompt 作为 role=system 首条）、`stream: true`；解析 `data: {chunk}` 与 `[DONE]` 映射为 StreamEvent（无 thinking 事件）。
- `LlmClientFactory`：`static LlmClient create(ProviderConfig)`，switch(protocol) → new AnthropicClient / OpenAiClient，未知协议抛 LlmException。
- `LlmException`：可读错误（不含密钥）。
**对外接口：** 上述类 + `SseParser`。
**依赖：** JDK HttpClient、Jackson（解析响应 JSON）、conversation（Message/ChatRequest）、config（ProviderConfig）

### session
**职责：** 会话落盘/读取/列表；原子写（先写 `{id}.json.tmp` 再 rename）；损坏文件在 list/load 时跳过并打印警告；目录不存在时自动创建。
**对外接口：** `List<SessionMeta> list()`（按 updatedAt 倒序）、`Optional<Session> load(String id)`、`void save(Session session)`、`Path getSessionsDir()`。
**依赖：** Jackson（含 jsr310 支持）、conversation（Message）、自身数据结构

### tui
**职责：** 终端交互与界面状态机。
- `TerminalUi`：封装 JLine3（Terminal + LineReader，启用行编辑与输入历史）与 `Ansi`（颜色）；`String readLine(String prompt)`、`void print(String text, String ansiCode)`、`void clearScreen()`。
- `JLinePicker<T>`：通用方向键选择列表（标题 + 若干项 + 说明行），↑/↓ 移动、Enter 确认、Ctrl+C 退出；返回 `Optional<T>`。
- `ChatApp`：状态机与主循环（见下）。
- `SlashCommands`：`isCommand(line)`、`handle(line, ctx)` 处理 `/help /exit /clear`；`/exit` 返回退出信号，`/clear` 只清屏不清历史。
**依赖：** JLine3、llm、conversation、session、config

## 模块交互（数据流）

1. `Main.main` → `ConfigLoader.load(configPath)` → `AppConfig`（含 providers）
2. `ChatApp.run()`：
   - `SessionStore.list()` 有历史 → 状态 `SESSION_SELECT`：`JLinePicker` 展示「新建对话 + 历史会话列表」；选历史 → `SessionStore.load(id)` → 重建 `Conversation` 并取 `ProviderSnapshot`（api_key 从当前配置同名 provider 或协议环境变量读取）；选新建 → 进入下一步
   - 新建且 `providers.size() > 1` → 状态 `PROVIDER_SELECT`：picker 选中 `ProviderConfig`；`=1` 直接使用
   - 状态 `CHAT` 循环：
     - `TerminalUi.readLine("> ")`；空行跳过；斜杠命令走 `SlashCommands`
     - `conversation.addUser(text)` → `LlmClient client = LlmClientFactory.create(provider)` → `BlockingQueue<StreamEvent> q = client.stream(conversation.buildRequest(SYSTEM_PROMPT))`
     - 轮询渲染：`q.poll(100ms)` → `TextDelta` 正常色打印并累积 / `ThinkingDelta` 灰色打印并累积 / `ThinkingComplete` 记录 signature / `Error` 红色打印并终止本次（不回填助手消息）/ `StreamEnd` 终止本次（记录用量）
     - 完成后 `conversation.addAssistant(text, thinking, signature)` → `SessionStore.save(conversation.toSession())` → 回到输入
3. 会话保存时机：每轮回复完成后 + `/exit` 退出前（spec F8/F9）。

## 文件组织

```
zhu-code-agent/
├── pom.xml                     # 依赖与插件（见技术决策）
├── config.example.yml          # 示例配置（可复制即用，AC5）
├── docs/                       # 四文档 + roadmap
└── src/
    ├── main/java/com/zhubao/
    │   ├── Main.java
    │   ├── config/AppConfig.java
    │   ├── config/ProviderConfig.java
    │   ├── config/ConfigLoader.java
    │   ├── config/ConfigException.java
    │   ├── conversation/Conversation.java
    │   ├── conversation/Message.java
    │   ├── llm/LlmClient.java
    │   ├── llm/ChatRequest.java
    │   ├── llm/StreamEvent.java
    │   ├── llm/LlmClientFactory.java
    │   ├── llm/AnthropicClient.java
    │   ├── llm/OpenAiClient.java
    │   ├── llm/SseParser.java
    │   ├── llm/SseEvent.java
    │   ├── llm/LlmException.java
    │   ├── session/Session.java
    │   ├── session/SessionMeta.java
    │   ├── session/ProviderSnapshot.java
    │   ├── session/SessionStore.java
    │   └── tui/ChatApp.java
    │   ├── tui/TerminalUi.java
    │   ├── tui/JLinePicker.java
    │   ├── tui/AppState.java
    │   └── tui/SlashCommands.java
    └── test/java/com/zhubao/
        ├── config/ConfigLoaderTest.java
        ├── llm/SseParserTest.java
        ├── llm/AnthropicClientTest.java      # mock HttpServer
        ├── llm/OpenAiClientTest.java         # mock HttpServer
        ├── llm/LlmClientFactoryTest.java
        ├── conversation/ConversationTest.java
        ├── session/SessionStoreTest.java     # 临时目录
        └── integration/StreamingIntegrationTest.java  # mock 端到端流式
```

## 技术决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 语言/构建 | Java 21 LTS + Maven（`maven.compiler.release=21`） | 与参考项目一致、生态主流；本机 JDK 25 可直接编译运行 |
| TUI 方案 | JLine3(3.28.x) + 自研 `Ansi` 256 色助手，自写简单轮询循环 | 方案 B 需要颜色/状态/选择列表；用主流库而非 mewcode 手写 tea 框架。Mordant 3.x 为 Kotlin-first API、Maven 默认构件为 KMP metadata 包、Java 集成成本高且 mewcode 实际未使用，故弃用（2026-08-07 用户批准） |
| HTTP/SSE | JDK `java.net.http.HttpClient`（ofInputStream）+ 自写 SseParser | 零第三方 HTTP 依赖、天然支持流式；SSE 解析可独立单测、面试可讲 |
| JSON/YAML | Jackson databind + jackson-datatype-jsr310；SnakeYAML | Java 生态主流；jsr310 处理 Instant |
| 流式事件传递 | `BlockingQueue<StreamEvent>`：后台线程生产、UI 轮询消费 | 解耦 HTTP 与 UI 线程；可单测；参考 mewcode 同款模式 |
| Provider 抽象 | `LlmClient` 接口 + `LlmClientFactory` switch(protocol) + `StreamEvent` 密封接口 | 满足 spec F6：新增后端 = 新实现类 + 工厂分支，调用方不变 |
| Anthropic thinking 多轮 | Message 保存 thinking+signature，请求回传 thinking 块与 signature | Anthropic 协议硬性要求，否则 thinking 后多轮直接报错（F4+F7 组合） |
| api_key 解析 | 直接值 → `${ENV_VAR}` → 协议环境变量（ANTHROPIC_API_KEY/OPENAI_API_KEY） | 满足 spec F5；密钥不写死在仓库 |
| base_url | 配置为空时按协议取默认（anthropic=https://api.anthropic.com，openai=https://api.openai.com） | 友好默认；客户端自行拼接 /v1/messages 与 /v1/chat/completions |
| 会话存储 | `~/.zhu-code-agent/sessions/{id}.json`，Jackson 序列化，原子写（tmp+rename） | 满足 spec F9；损坏文件跳过不崩溃；不含 api_key（N4） |
| 打包/运行 | maven-shade-plugin 打可执行 fat jar + exec-maven-plugin 开发运行 | `java -jar target/zhu-code-agent.jar` 与 `mvn exec:java` 两用 |
| 测试 | JUnit 5.11 + JDK `com.sun.net.httpserver.HttpServer` 作 mock LLM 服务器 | 无真实密钥即可跑集成测试（AC6/AC10），面试可演示 |
| 系统提示词 | 内置常量 SYSTEM_PROMPT（介绍 zhuCodeAgent、要求简洁回复） | Anthropic/OpenAI 都需要 system 级说明 |

## 需求覆盖对照

- F1（启动流程）→ tui/ChatApp 状态机 + JLinePicker；F2（输入）→ TerminalUi(LineReader)；F3（流式）→ llm 流事件 + ChatApp 轮询渲染；F4（多轮记忆）→ conversation；F5（配置）→ config；F6（双协议）→ llm；F7（thinking）→ AnthropicClient + StreamEvent.Thinking*；F8（命令）→ SlashCommands；F9（持久化）→ session。
- 非功能 N1~N6 分别对应技术栈、分包、异常处理、密钥脱敏、测试、轻量写盘（SessionStore 每轮一次小文件 + 原子写）。
