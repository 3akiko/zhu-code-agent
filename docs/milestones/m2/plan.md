状态：approved
# zhuCodeAgent M2：Agent 循环与 Tool Use Plan

> 依据已批准的 docs/spec.md（M2）设计。本文档与 Java 技术栈相关。M1 的四文档归档于 docs/milestones/m1/，本文档不覆盖。

## 架构概览

M2 在 M1 基础上新增 `agent`、`tool`、`permission` 三个模块，扩展 `llm`、`conversation`、`session`、`config`、`tui`：

```
Main → ChatApp(TUI 状态机)
        ├─ agent/AgentRunner        （消息循环：LLM ↔ 工具 ↔ 权限，替代 M1 的 TurnRunner）
        │     ├─ llm（扩展）         StreamEvent.ToolCall、请求带 tools 定义、tool_result 回填
        │     ├─ tool/              工具抽象 + 内置 6 工具 + 路径/危险命令守卫
        │     │     ├─ Tool / ToolCall / ToolResult / ToolRegistry
        │     │     ├─ ToolExecutor(接口) + SerialToolExecutor（M5 并行扩展点）
        │     │     ├─ builtin/ReadFileTool WriteFileTool EditFileTool BashTool GrepTool GlobTool
        │     │     └─ PathGuard + DangerGuard
        │     └─ permission/PermissionManager（a/d/s、按工具+参数记忆、不落盘）
        ├─ conversation（扩展）      结构化内容块 text/toolUse/toolResult
        └─ session（扩展）           tool 消息序列化 + 64KB 截断 + 旧格式兼容
```

依赖方向自上而下、无环。`agent/AgentRunner` 与 TUI 解耦（通过回调接口暴露状态/权限请求），便于单测与集成测试。

## 核心接口与数据结构

### StreamEvent（llm，扩展）
```java
public sealed interface StreamEvent {
    record TextDelta(String text) implements StreamEvent {}
    record ThinkingDelta(String text) implements StreamEvent {}
    record ThinkingComplete(String signature) implements StreamEvent {}
    record ToolCall(String id, String name, String argumentsJson) implements StreamEvent {} // 新增
    record StreamEnd(String stopReason, int inputTokens, int outputTokens) implements StreamEvent {}
    record Error(String message) implements StreamEvent {}
}
```
- Anthropic：`content_block_start(type=tool_use)` + `input_json_delta` 累积 → 结束时发 ToolCall（id/name/完整 argumentsJson）。
- OpenAI：`delta.tool_calls`（含 index/function.name/function.arguments 增量）→ 每个 index 累积 → 完成后发 ToolCall。

### ChatRequest（llm，扩展）
```java
public record ChatRequest(String systemPrompt, List<Message> messages, List<ToolSpec> tools) {}
// ToolSpec: record ToolSpec(String name, String description, JsonNode inputSchema) {}
```
`tools` 由 ToolRegistry 提供；客户端分别翻译为 anthropic `tools` 数组 / openai `tools` 数组。工具结果以 tool_result / role=tool 消息回传（客户端翻译，内部模型统一）。

### Message / ContentBlock（conversation，重构）
```java
public sealed interface ContentBlock {
    record TextBlock(String text) implements ContentBlock {}
    record ToolUseBlock(String id, String name, String argumentsJson) implements ContentBlock {}   // assistant
    record ToolResultBlock(String id, String name, boolean isError, String output) implements ContentBlock {} // user
}
public class Message {
    Role role;                     // user / assistant（枚举沿用 M1）
    List<ContentBlock> content;
    String thinking;               // assistant 可选（沿用 M1）
    String thinkingSignature;      // assistant 可选（沿用 M1）
}
```
- 内部模型协议无关；AnthropicClient / OpenAiClient 负责翻译成各自 wire 格式（anthropic: user 消息内嵌 tool_result 块；openai: role=tool 消息 + tool_call_id）。
- **兼容性**：SessionStore 加载旧会话（M1 的 `content` 为字符串）时自动迁移为 `TextBlock`，不丢历史。

### tool 包
```java
public interface Tool {
    String name();
    String description();
    JsonNode inputSchema();              // JSON Schema，供模型与请求体
    ToolResult execute(ToolCall call);   // 已通过 PathGuard 前置校验
}
public record ToolCall(String id, String name, String argumentsJson) {}
public record ToolResult(String id, String name, boolean isError, String output) {}

public interface ToolExecutor {
    List<ToolResult> execute(List<ToolCall> calls);   // M2 仅串行实现；M5 并行扩展点（spec N2）
}
public final class SerialToolExecutor implements ToolExecutor {
    // 逐个调用：权限判定 → 执行/拒绝 → 收集 ToolResult（含“已拒绝”的 error 结果）
}
public final class ToolRegistry {
    List<Tool> all(); Tool byName(String name);
}
```

### permission 包
```java
public enum PermissionDecision { ALLOW, DENY, NEED_CONFIRM }
public final class PermissionManager {
    PermissionDecision decide(ToolCall call);
    // 只读工具(registry 标记 readOnly) → ALLOW
    // 写类/bash：命中「总是允许」记忆(工具+参数精确匹配)且非危险 → ALLOW
    //          危险命令(DangerGuard) → NEED_CONFIRM（强制，即使曾总是允许）
    //          其余 → NEED_CONFIRM
    void rememberAlways(String key);     // 按“工具名 + 规范化参数”记忆，仅内存
    List<String> allowedList(); void reset();   // /permissions 用
}
```

### PathGuard / DangerGuard（tool 包，安全守卫）
```java
public final class PathGuard {
    Path root;  // 项目 cwd
    Path resolveInWorkspace(String raw);       // 归一化 + realpath(存在时) → 必须在 root 内，否则抛 ToolException
    void assertNotForbidden(Path p);           // 禁止写 .git/ 与 ~/.zhu-code-agent/
}
public final class DangerGuard {
    boolean isDangerous(String command);       // rm -rf / rm -fr / sudo rm / mkfs / dd if= / mv → / 等内置清单
    void assertRmTargetsInWorkspace(String command, Path root); // rm -rf 的目标逐个解析，必须在 cwd 内，否则抛 ToolException
}
```

## 模块交互与数据流

### AgentRunner 循环（一次用户输入）
```
1. conversation.addUser(userText)
2. loop（步数 ≤ tool.max_calls_per_turn）:
   a. queue = client.stream(conversation.buildRequest(SYSTEM_PROMPT, tools))
   b. 消费事件（每步打印状态行 ⏳ 思考中…，首内容到达清除）:
      - TextDelta → 累积文本
      - ThinkingDelta/ThinkingComplete → 累积（沿用 M1）
      - ToolCall → 收集（含 id/名称/参数）
      - Error → 结束本轮（错误提示）
      - StreamEnd → 判断:
          stopReason == end_turn 或 无 ToolCall → 本轮完成，退出循环
          否则 → 进入 c
   c. results = executor.execute(toolCalls)   // 串行；每个调用先 PermissionManager.decide：
      - NEED_CONFIRM → 回调 UI 行内单键 a/d/s（s → rememberAlways）
      - DENY → 构造“已拒绝”错误结果
      - 危险命令 → 强制确认 + rm -rf 路径校验（PathGuard）
   d. conversation 追加 assistant(文本+全部 toolUseBlock) + user(全部 toolResultBlock)（一次性回填，spec F1）
   e. 步数 +1；继续 2a
3. 若步数达上限 → 提示“已达本轮工具调用上限(N)，输入『继续』可开启新一轮”
```
- 超时：每步流式消费采用**流空闲超时**（该步开始后连续 120s 无事件 → 中断该步并报"生成超时（无响应）"）；工具执行单独超时（bash 30s）；整轮不设墙钟。
- UI 回调接口 `AgentUi`：onStepStatus(text)、onToolCallSummary(call)、onToolResultPreview(result, previewLines)、askPermission(call) → Decision（测试注入自动应答桩）。

### TUI（ChatApp 改造，spec F10）
- 状态行粒度：每 agent 步骤一条（打印状态行 → 首个流式内容/工具摘要到达时 `clearPreviousLine` 清除），复用 M1 `clearPreviousLine()` 原语，改动点在“打印/清除的粒度”。
- 工具调用一行摘要（`🔧 read_file src/A.java`，亮青高亮）；结果预览默认 5 行（`ui.tool_preview_lines`），超出标注「…已截断，共 N 行」。
- `/permissions`：显示/清空「总是允许」清单。

### 会话持久化（session，spec F9）
- `Message.content` 块列表序列化到 JSON；tool_result 单条输出 >64KB 时截断 + 追加「…（已截断，共 N 字节）」，内存仍保留完整结果（供当前会话模型回填）。
- 加载旧 M1 会话（content 为字符串）→ 迁移为 TextBlock。
- 仍不含 api_key；「总是允许」清单不落盘。

## 内置工具实现要点

| 工具 | 参数 | 行为/安全 |
|------|------|-----------|
| read_file | path, offset?, limit? | PathGuard 校验；支持行范围；不存在/越界 → 可读错误 |
| write_file | path, content | PathGuard + assertNotForbidden；可新建/覆写；返回写入字节数 |
| edit_file | path, old_string, new_string | 唯一匹配才替换；未找到/多匹配 → 可读错误（模型重试） |
| bash | command | ProcessBuilder：cwd、无 stdin（`/dev/null`）、30s 超时（destroyForcibly + 进程树）、输出 200KB 截断；非 0 退出码/超时 → 结构化错误；命令原文落盘 |
| grep | pattern, path? | cwd 内递归搜索，返回 文件:行:内容 摘要 |
| glob | pattern | cwd 内通配符匹配，返回路径列表 |

- 所有文件工具先过 `PathGuard`（realpath 在 cwd 内、禁写 .git/ 与 ~/.zhu-code-agent/）。
- `bash` 过 `DangerGuard`：危险命令强制确认；`rm -rf` 额外校验目标在 cwd 内，否则**直接拒绝不执行**（spec F4，用户安全红线）。

## 文件清单

| 操作 | 文件 | 职责 |
|------|------|------|
| 新建 | `agent/AgentRunner.java`、`agent/AgentUi.java` | 消息循环 + UI 回调 |
| 新建 | `tool/Tool.java`、`ToolCall.java`、`ToolResult.java`、`ToolRegistry.java`、`ToolExecutor.java`、`SerialToolExecutor.java`、`ToolException.java` | 工具抽象与执行 |
| 新建 | `tool/PathGuard.java`、`tool/DangerGuard.java` | 路径边界 + 危险命令守卫 |
| 新建 | `tool/builtin/ReadFileTool.java`、`WriteFileTool.java`、`EditFileTool.java`、`BashTool.java`、`GrepTool.java`、`GlobTool.java` | 内置 6 工具 |
| 新建 | `permission/PermissionManager.java`、`PermissionDecision.java` | 权限判定与记忆 |
| 修改 | `llm/StreamEvent.java`（+ToolCall）、`ChatRequest.java`（+tools）、`AnthropicClient.java`（tools/tool_use 解析）、`OpenAiClient.java`（tools/function_call 解析） | 协议扩展 |
| 修改 | `conversation/Message.java`、`Conversation.java`（内容块、addToolUse/addToolResult、buildRequest 带 tools） | 结构化历史 |
| 修改 | `session/Session.java`、`SessionStore.java`（块序列化、64KB 截断、旧格式迁移） | 持久化扩展 |
| 修改 | `config/AppConfig.java`、`config.example.yml`（tool.max_calls_per_turn=60、ui.tool_preview_lines=5） | 配置扩展 |
| 修改 | `tui/ChatApp.java`（AgentRunner 接入、每步状态行、工具摘要/预览、/permissions）、`SlashCommands.java` | TUI 扩展 |
| 删除 | `tui/TurnRunner.java`（由 agent/AgentRunner 取代，测试迁移） | 演进 |
| 新建 | `test/…/PathGuardTest`、`DangerGuardTest`、各工具 Test、`PermissionManagerTest`、`AgentRunnerTest`、会话工具消息 Test、SseParser 工具样例 Test；扩展 `StreamingIntegrationTest` | 测试 |

## 技术决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 循环位置 | 新 `agent/AgentRunner` 替代 `tui/TurnRunner` | 消息循环是核心逻辑，与 TUI 解耦可单测/集成测试（对齐 M1 的 TurnRunner 思路，但职责更清晰） |
| 工具抽象 | `Tool` 接口 + `ToolRegistry` + `ToolExecutor` 接口（仅串行实现） | 满足 spec N2：M5 并行 = 新增执行器实现，循环/回填/落盘不改 |
| 内部消息模型 | 协议无关 ContentBlock（text/toolUse/toolResult），客户端负责 wire 翻译 | 沿用 M1「统一内部模型 + 双客户端翻译」模式，双协议零调用方改动 |
| 旧会话兼容 | 加载时字符串 content → TextBlock 迁移 | 用户已有 M1 会话，恢复不能坏（spec F9/N4） |
| 权限确认 | PermissionManager + UI 行内单键 a/d/s 回调 | spec F3；测试注入自动应答桩；「总是允许」仅内存不落盘 |
| 危险命令 | DangerGuard 清单 + rm -rf 目标路径校验（cwd 内） | spec F4 用户安全红线：cwd 外破坏性命令立即拒绝 |
| bash 执行 | ProcessBuilder + 30s 超时 destroyForcibly + 无 stdin + 200KB 截断 | spec F2/F7；可控、可测 |
| 路径安全 | PathGuard：归一化 + realpath 在 cwd 内 + 禁写 .git/ 与 ~/.zhu-code-agent/ | spec F6；防止 `..`/符号链接逃逸 |
| 循环护栏 | 步数上限 tool.max_calls_per_turn（默认 60）+ 每步流空闲超时 120s | spec F7；防失控与卡死，宽容长思考 |
| 截断分层 | bash 输出 200KB / 落盘 64KB / 预览 5 行 | spec N6；内存全量、落盘/展示分层截断 |
| 配置 | `tool.max_calls_per_turn`、`ui.tool_preview_lines` 进 config + 示例 | 可调护栏与展示，防写死 |

## 需求覆盖对照

- F1（消息循环）→ agent/AgentRunner；F2（6 工具）→ tool/builtin + BashTool；F3/F4（权限+危险命令）→ permission + DangerGuard + UI 回调；F5（/permissions）→ SlashCommands + PermissionManager；F6（路径边界）→ PathGuard；F7（循环护栏）→ AgentRunner 步数/超时；F8（ToolCall 事件）→ StreamEvent.ToolCall + 双客户端解析；F9（持久化）→ conversation 内容块 + session 64KB 截断/迁移；F10（TUI）→ ChatApp 每步状态行/摘要/预览。
- N1（技术栈/配置）→ 新增 ProcessBuilder + 两个配置项；N2（可扩展）→ ToolExecutor 接口；N3（安全）→ 不落盘权限清单、bash 原文落盘、api_key 不落盘沿用；N4（稳定）→ 守卫先于执行 + 循环有界；N5（测试）→ 单测 + mock 端到端 + 真机冒烟；**M1 能力不回归**：契约不变测试（ConfigLoader/SseParser/Factory）原样通过，契约变更测试（Conversation/双客户端/SessionStore/集成）按新契约更新后全绿，TurnRunner 测试迁移至 AgentRunner，M1 已验收能力均有覆盖；N6（性能）→ 三层截断。
