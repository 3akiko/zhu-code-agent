状态：approved
# zhuCodeAgent M5：Agent 扩展——并行与 Subagents Plan

> 依据已批准的 `docs/spec.md`（M5，F1–F3/N1–N6/AC1–AC5）。设计满足每条 F；沿用 M1–M4 架构与安全红线；不新增第三方依赖（N1）。

## 1. 架构概览

三层改动，按依赖方向自上而下无环：

```
┌─ llm 层（F1 客户端并发安全，硬前置）───────────────────────┐
│  StreamState(per-call) ← AnthropicStreamState/OpenAiStreamState │
│  AbstractStreamingClient: stream() 每次调用 new 状态 → 闭包捕获  │
│  AnthropicClient / OpenAiClient: 退化为无状态（只读 config）     │
│  LlmClientFactory: ConcurrentHashMap 按 provider 名缓存复用      │
└──────────────────────────────────────────────────────────────┘
┌─ tool 层（F2 并行执行 + F3 task 工具）─────────────────────┐
│  AbstractToolExecutor（公共单调用逻辑）                        │
│    ├─ SerialToolExecutor（继承，行为不变）                     │
│    └─ ParallelToolExecutor（读+task 段并行 / 写·bash 串行/保序）│
│  TaskTool（task 内置工具：派生子任务，setter 注入运行上下文）     │
└──────────────────────────────────────────────────────────────┘
┌─ agent 层（F3 子任务执行）────────────────────────────────┐
│  AgentRunner.runSubtask + SUBTASK_SYSTEM_PROMPT（复用 runLoop）│
│  SubagentCoordinator（在途子任务线程注册 + 级联取消）           │
└──────────────────────────────────────────────────────────────┘
┌─ tui 层（F3.9 UI + 中断）─────────────────────────────────┐
│  ChatApp 装配 ParallelToolExecutor/TaskTool；TuiAgentUi 子任务行 │
│  TurnInterruptController 级联取消（TOOL_EXEC 分支扩展）          │
└──────────────────────────────────────────────────────────────┘
config 层：AppConfig 新增 agent 护栏（深度/并行/步数）+ ConfigLoader + example
```

## 2. 核心数据结构

### 2.1 StreamState（llm 包，包私有抽象类；per-call 状态持有者，F1）

```java
abstract class StreamState {
    volatile InputStream activeBody;              // 取消时 close 打断阻塞读（原实例字段迁入）
    final AtomicBoolean cancelled = new AtomicBoolean(false); // per-call，实例复用不串
    int inputTokens, outputTokens, cacheReadTokens, cacheCreationTokens;
    String stopReason;
}
final class AnthropicStreamState extends StreamState {
    boolean inThinking; StringBuilder thinkingAccum = new StringBuilder();
    String thinkingSignature; boolean inToolUse;
    String toolUseId, toolUseName; StringBuilder toolUseJsonAccum = new StringBuilder();
}
final class OpenAiStreamState extends StreamState {
    final Map<Integer, ToolAccum> toolAccums = new LinkedHashMap<>(); // ToolAccum 内嵌
}
```
- 每个 `stream()` 调用 `newStreamState()` 创建；worker 线程写、cancel 闭包读（同对象，天然可见性一致）。

### 2.2 AbstractToolExecutor（tool 包，public abstract；F2 公共逻辑）

```java
public abstract class AbstractToolExecutor implements ToolExecutor {
    protected final ToolRegistry registry;   protected final PermissionManager permissions;
    protected final AgentUi ui;              protected final int previewLines;
    protected final FileHistory history;     protected final String sessionId;
    protected ToolResult executeOne(ToolCall call); // 中断检查→onToolCall→权限→快照→执行→结果（Serial 现有逻辑）
    protected static boolean isSnapshotTool(String name); // write_file/edit_file
    protected static String pathOf(ToolCall call);
}
```

### 2.3 ParallelToolExecutor（tool 包，F2）

```java
public final class ParallelToolExecutor extends AbstractToolExecutor implements ToolExecutor {
    @Override public List<ToolResult> execute(List<ToolCall> calls);
    // 调度：按原顺序扫描，连续「可并行」调用组段（段内虚拟线程并行），
    //       write_file/edit_file/bash 单独串行；段间保持原顺序；
    //       结果按原 calls 顺序组装（保序，F2.3）。
    private boolean isParallelCall(ToolCall c); // readOnly() 工具 || "task"
}
```

### 2.4 TaskTool（tool 包，F3）

```java
public final class TaskTool implements Tool {
    // setter 注入（ChatApp 装配，仿 mewcode AgentTool 模式）：
    Supplier<LlmClient> clientProvider; ToolRegistry registry; PermissionManager permissions;
    AgentUi ui; FileHistory history; String sessionId;
    int maxDepth, maxParallel, maxSteps;  // 来自 AppConfig 护栏
    private final AtomicInteger running = new AtomicInteger(); // 并行子任务计数（F3.5）
    String name() = "task"; description/inputSchema（prompt 必填 + constraints 可选）;
    ToolResult execute(ToolCall call);
    List<ToolSpec> buildSubtaskTools(int childDepth); // 达上限时不含 task（F3.5 工具池裁剪）
}
```

### 2.5 SubagentCoordinator（agent 包，F3.8 级联取消）

```java
public final class SubagentCoordinator {
    private static final Set<Thread> SUBAGENT_THREADS = ConcurrentHashMap.newKeySet();
    public static void register(Thread t); public static void unregister(Thread t);
    public static void cancelAll(); // interrupt 全部在途子任务线程
}
```

### 2.6 AppConfig 新增（F3.5 护栏，均可配）

```java
record AppConfig(..., int agentMaxDepth, int agentMaxParallel, int agentMaxStepsPerSubtask)
// 默认：2 / 4 / 30；config.example.yml 新增 agent 段：
// agent: { max_subagent_depth: 2, max_parallel_subagents: 4, max_steps_per_subagent: 30 }
```

### 2.7 子任务结果摘要（F3.6）

- 格式（文本，走现有 `ToolResult`/`ToolResultBlock` 通道，双协议天然兼容）：
  `[task] 状态=完成|失败|被中断 · 摘要=<截断 2000 字符> · in/out=<token> · 错误=<原因>`（失败/中断时含原因；守 N3 交替：父 assistant tool_use 之后紧跟 user tool_result）。

## 3. 模块设计

### 3.1 llm：AbstractStreamingClient（F1）
- **职责**：SSE 流式骨架，改为 per-call 状态驱动。
- **改动**：删实例字段 `activeBody`/`cancelled`/`onStreamStart()`；新增 `protected abstract StreamState newStreamState()`；`stream()` 创建 queue+state、worker 跑 `runStream(request, queue, state)`、`LlmStream` 的 cancel/join 闭包捕获 state；`cancelStream(worker, queue, state)` 用 `state.cancelled` CAS + close `state.activeBody` + 投递「已中断」；`handleEvent(SseEvent, BlockingQueue<StreamEvent>, StreamState)` 签名变化；`runStream` 内用 `state.cancelled`/`state.activeBody`。
- **依赖**：仅 JDK；`LlmStream` 不变（F1.4 事件契约不变）。

### 3.2 llm：AnthropicClient / OpenAiClient（F1）
- **职责**：协议翻译。移除全部实例累积字段；`newStreamState()` 返回各自子类；`handleEvent(sse, queue, state)` 读写 `state` 字段；`buildRequest` 不变（只读 config）。
- **依赖**：`StreamState` 体系。

### 3.3 llm：LlmClientFactory（F1.3）
- **职责**：按 provider 名缓存复用。
- **改动**：`ConcurrentHashMap<String, LlmClient>` + `computeIfAbsent(config.getName(), k -> createNew(config))`；`createNew` 保持现有 switch。调用方（ChatApp 每轮 `create(provider)`）透明复用同一实例。

### 3.4 tool：SerialToolExecutor（F2）
- **改动**：改为 `extends AbstractToolExecutor`，`execute` 循环逐调用调 `executeOne`（行为与现状完全一致，回归由 SerialToolExecutorTest 保证）。构造签名不变（含 history/sessionId 重载）。

### 3.5 tool：ParallelToolExecutor（F2）
- **职责**：顺序分段并行调度。
- **算法**（满足 F2.2/F2.3/F2.4/F2.5/F2.6/F2.7）：
  1. 扫描 `calls`，按 `isParallelCall` 连续分组：可并行段（只读工具 + task）/ 串行单（write/edit/bash）。
  2. 依原顺序执行各段：
     - 串行单：`executeOne`（权限/快照/结果与串行一致）。
     - 并行段：`try (var ex = Executors.newVirtualThreadPerTaskExecutor())` 提交每调用（`ex.submit(() -> executeOne(call))`），按段内原序 `Future.get` 收集（保序）；提交前串行调 `ui.onToolCall`、收集时串行调 `ui.onToolResult`（避免终端交错）；段内单调用失败以 `isError` 结果返回、不拖垮段（F2.4）。
  3. 段间检查 `Thread.currentThread().isInterrupted()` → break（在途段结果丢弃，F2.7）；整批按原 `calls` 顺序组装 `results`。
- **快照**：`executeOne` 内 `isSnapshotTool`（write/edit）走串行单 → 快照顺序与串行一致（F2.6）。
- **依赖**：`AbstractToolExecutor`、`Executors.newVirtualThreadPerTaskExecutor()`（N1 零新增依赖）。

### 3.6 tool：TaskTool（F3）
- **职责**：`task` 工具，父 agent 调用即派生子任务。
- **execute(call) 流程**：
  1. 解析 `prompt`（缺省 → isError「缺少任务描述」）。
  2. 深度检查：当前深度 ≥ `maxDepth` → isError「嵌套深度超限」（兜底拒绝，F3.5）。
  3. 并发计数：`running.incrementAndGet()` > `maxParallel` → 释放并 isError「并行子任务超限」；结束 `finally` 释放（F3.5）。
  4. 构建子任务工具集 `buildSubtaskTools(childDepth)`：`registry.all()` 过滤——`childDepth == maxDepth` 时剔除 `task`（工具池裁剪，F3.5）。
  5. 独立 `Conversation`，`addUser(prompt)`（F3.2 隔离父历史，仅内存不落盘）。
  6. 子任务 `AgentUi`：`SubagentUi`（静默 onStep/onEvent/onToolCall/onToolResult；`askPermission` 转发主 ui 并加「子任务#n」来源标注，F3.4）。
  7. 主 ui `onSubtaskStart(id, prompt 首行)`（F3.9 折叠单行）。
  8. `SubagentCoordinator.register(当前线程)` → `AgentRunner.runSubtask(client, subConv, tools, subExecutor, subUi, maxSteps, …)` → `unregister`（F3.8 级联取消钩子）。
  9. 子任务执行器：`new ParallelToolExecutor(registry, permissions, subUi, previewLines, history, sessionId)`——子任务内读并行/写串行，写文件快照进父会话链（/undo 可回滚子任务改动）。
  10. 结果摘要格式化（2.7）→ `ui.onSubtaskEnd(id, summary)` → 返回 `ToolResult.ok/error(call, summary)`（F3.6；失败不自动重试）。
- **模型/客户端**：子任务用同一 provider 共享缓存实例（`clientProvider.get()`），并发 `stream()` 由 F1 保证安全（F3.3）。
- **依赖**：`AgentRunner`、`ParallelToolExecutor`、`SubagentCoordinator`、`Conversation`、`LlmClientFactory`。

### 3.7 agent：AgentRunner（F3）
- **改动**：`run`/`runExecution` 参数由 `SerialToolExecutor` 放宽为 `ToolExecutor`（`runLoop` 已是 `ToolExecutor`，仅签名放宽）；新增：
  - `public static final String SUBTASK_SYSTEM_PROMPT`：子任务身份提示词（聚焦任务、直接执行工具、不询问用户、最终以简洁报告收尾、不再次派生超出允许的工具）。
  - `public static Result runSubtask(LlmClient client, Conversation conversation, List<ToolSpec> tools, ToolExecutor executor, AgentUi ui, int maxSteps, long stepIdleTimeoutMs, Consumer<LlmStream> onStream)`：内部 `runLoop(..., SUBTASK_SYSTEM_PROMPT, false, onStream)`，`maxCallsPerTurn = maxSteps`（F3.10：子任务步数独立护栏，不计父计数）。
- **依赖**：无新增。

### 3.8 agent：SubagentCoordinator（F3.8）
- **职责**：集中跟踪在途子任务线程，提供级联取消。
- **接口**：`register/unregister/cancelAll`（静态，`ConcurrentHashMap.newKeySet()`，线程安全）。

### 3.9 tui：TurnInterruptController（F3.8）
- **改动**：`cancelCurrent()` 的 `TOOL_EXEC` 分支追加 `SubagentCoordinator.cancelAll()`（级联 interrupt 所有在途子任务线程；子任务生成阶段 poll 中断、工具阶段 `isInterrupted` break、bash 由既有机制响应，沿用 M4 机制）。主线程 `activeStream` 语义不变。

### 3.10 tui：ChatApp / TuiAgentUi（F2/F3）
- **改动**：
  - 装配：构造 `TaskTool`（setter 注入 clientProvider/permissions/ui/history/sessionId/护栏）→ `ToolRegistry.register(taskTool)`（`ToolRegistry` 新增 `public void register(Tool)`）。
  - `sendAndRender`/`executePlannedTurn`：`SerialToolExecutor` → `ParallelToolExecutor`；client 走工厂缓存实例（透明）。
  - `AgentUi` 新增 default 方法 `onSubtaskStart(int, String)` / `onSubtaskEnd(int, String)`（空实现，测试桩零改动）；`TuiAgentUi` 实现为折叠单行（如 `⏳ 子任务#1: 执行中…` / `✓ 子任务#1: <摘要首行>`）。
- **依赖**：现有字段 `toolRegistry`/`permissionManager`/`fileHistory`/`config`。

### 3.11 config：AppConfig / ConfigLoader / config.example.yml（F3.5）
- AppConfig record 新增 3 字段 + 默认常量；ConfigLoader 读 `agent.max_subagent_depth` / `agent.max_parallel_subagents` / `agent.max_steps_per_subagent`；config.example.yml 加 `agent:` 段；ConfigLoaderTest 补用例（M2–M4 模式）。

## 4. 模块交互（数据流）

```
主会话（ChatApp.sendAndRender）
  └─ AgentRunner.run → runLoop（executor=ParallelToolExecutor）
       └─ 每步 consumeStep → client.stream(...)          // F1：每次 new StreamState
       └─ 有工具调用 → executor.execute(calls)           // F2：读+task 段并行 / 写·bash 串行 / 保序
            ├─ 只读工具 → 虚拟线程并行 executeOne → 结果按原序
            ├─ task     → TaskTool.execute → SubagentRunner（独立 Conversation + SUBTASK_SYSTEM_PROMPT）
            │              └─ 内部 runLoop（ParallelToolExecutor 递归，深度+1）→ 摘要回填 tool_result
            └─ write/edit/bash → 串行 executeOne（权限确认/快照）
       └─ addAssistantWithTools + addToolResultBlocks → 继续循环
中断（Ctrl+C → TurnInterruptController.onCtrlC）
  ├─ GENERATING → activeStream.cancel()
  └─ TOOL_EXEC  → bashTool.cancel() + SubagentCoordinator.cancelAll() + chatThread.interrupt()
```

## 5. 文件组织

| 操作 | 文件 | 职责 |
|------|------|------|
| 新建 | `src/main/java/com/zhubao/llm/StreamState.java` | per-call 状态基类 + Anthropic/OpenAi 子类（可同文件包私有） |
| 修改 | `src/main/java/com/zhubao/llm/AbstractStreamingClient.java` | 状态迁入 StreamState；cancel/runStream 用 state |
| 修改 | `src/main/java/com/zhubao/llm/AnthropicClient.java` / `OpenAiClient.java` | 去实例字段；newStreamState/handleEvent(sse,queue,state) |
| 修改 | `src/main/java/com/zhubao/llm/LlmClientFactory.java` | 按 provider 名缓存复用 |
| 新建 | `src/main/java/com/zhubao/tool/AbstractToolExecutor.java` | 单调用公共逻辑（权限/快照/执行/结果） |
| 修改 | `src/main/java/com/zhubao/tool/SerialToolExecutor.java` | 继承 AbstractToolExecutor，行为不变 |
| 新建 | `src/main/java/com/zhubao/tool/ParallelToolExecutor.java` | 读+task 段并行 / 写·bash 串行 / 保序 |
| 新建 | `src/main/java/com/zhubao/tool/TaskTool.java` | task 工具（setter 注入上下文） |
| 修改 | `src/main/java/com/zhubao/tool/ToolRegistry.java` | 新增 public register(Tool) |
| 修改 | `src/main/java/com/zhubao/agent/AgentRunner.java` | run/runExecution 放宽 ToolExecutor；runSubtask + SUBTASK_SYSTEM_PROMPT |
| 新建 | `src/main/java/com/zhubao/agent/SubagentCoordinator.java` | 在途子任务线程 + 级联取消 |
| 修改 | `src/main/java/com/zhubao/agent/AgentUi.java` | default onSubtaskStart/onSubtaskEnd |
| 修改 | `src/main/java/com/zhubao/tui/ChatApp.java` | ParallelToolExecutor + TaskTool 装配 + 子任务行 |
| 修改 | `src/main/java/com/zhubao/tui/TurnInterruptController.java` | TOOL_EXEC 分支级联 cancelAll |
| 修改 | `src/main/java/com/zhubao/config/AppConfig.java` / `ConfigLoader.java` | agent 护栏字段 |
| 修改 | `config.example.yml` | agent 段 |
| 新建/修改 | 测试：`llm/ConcurrentStreamTest`、`tool/ParallelToolExecutorTest`、`tool/TaskToolTest`、`integration/SubagentIntegrationTest`、`config/ConfigLoaderTest`（补 agent 段）、`agent/AgentRunnerTest`（runSubtask） | 见 checklist |

## 6. 技术决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 流状态管理（F1） | per-call `StreamState` 对象，非 ThreadLocal、非实例字段 | 业界一致（Claude AsyncLocalStorage/Codex 所有权）；cancel 跨线程需外部访问；闭包可捕获；实例复用不串 |
| 客户端复用（F1.3） | `ConcurrentHashMap` 按 provider 名缓存单例 | 主会话与子任务共享实例并发；调用方透明；HttpClient/config 只读 |
| 并行载体（F2.8） | Java 21 虚拟线程（每段 `newVirtualThreadPerTaskExecutor`） | 短生命周期阻塞 I/O 场景；免池调优/耗尽；零新依赖；中断语义一致 |
| 并行调度（F2.2） | 顺序分段：只读+task 并行 / write·edit·bash 串行，段间保序 | 保序硬约束 + 写安全 + 与 Claude Code「写串行/读并行」一致 |
| task 归并行段且调用本身不确认 | task 与只读同段并行；危险操作在子任务内部被 PermissionManager 拦截 | 多 task 天然并行；task 调用非危险操作，权限在子任务内部兜底 |
| 子任务失败重试（F3.6） | 不自动重试，isError 摘要回填由父模型决策 | 避免重复副作用；遵循 M2 工具失败语义 |
| 深度拒绝（F3.5） | 工具池裁剪（层 2 不含 task）+ 运行时兜底拒绝 | 源头杜绝无效调用省 token；防御边缘情况 |
| 子任务落盘（F3.2） | 仅内存、不落盘 | YAGNI；无恢复需求；摘要已进父会话 |
| 摘要截断 | 常量 2000（非配置） | YAGNI；护栏三项才可配 |
| 公共逻辑复用（F2） | 抽取 `AbstractToolExecutor`，Serial 继承 | 权限/快照/结果逻辑单点，行为不回归 |
| 子任务快照 | 子任务写文件快照进父会话链（history/sessionId 透传） | /undo 可回滚子任务改动，安全红线不削弱 |
