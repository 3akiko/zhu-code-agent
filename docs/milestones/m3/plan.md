状态：approved
# zhuCodeAgent M3：文件编辑增强与 Plan Mode Plan

> 依据已批准的 docs/spec.md（M3）设计。本文档与 Java 技术栈相关。M2 四文档已归档 docs/milestones/m2/。

## 架构概览

M3 在 M2 基础上新增 `diff`、`history` 两个模块，扩展 `permission`、`tool`、`agent`、`tui`、`config`：

```
Main → ChatApp(TUI 状态机)
        ├─ agent/AgentRunner          扩展：runPlan（只读计划循环）/ runExecution（批准后执行）
        │     ├─ llm/                不变
        │     ├─ tool/                + PlanModeExecutor（计划阶段拦截写工具/bash）
        │     │     ├─ builtin/       EditFileTool/WriteFileTool 结果内嵌 diff（DiffGenerator）
        │     │     └─ SerialToolExecutor  + 快照钩子（写前 FileHistory.snapshotBefore）
        │     ├─ history/（新）       FileHistory + FileCheckpoint：落盘检查点 / undo / rewind
        │     ├─ diff/（新）          DiffGenerator：before/after → 变更 diff 文本
        │     └─ permission/          + PermissionMode（normal/acceptEdits/bypassPermissions）
        ├─ conversation/             回滚记录以 user 消息写回（[回滚] …）
        └─ session/                  不变；快照独立存 ~/.zhu-code-agent/snapshots/<会话ID>/
```

依赖方向自上而下、无环。`ToolExecutor` 与 `Tool` 接口**不变**（N2），M5 并行扩展点不受影响。

## 核心接口与数据结构

### diff 包（新建 com.zhubao.diff）

```java
public final class DiffGenerator {
    public DiffGenerator(int maxLines) {}          // maxLines = ui.diff_max_lines（默认 200）
    /** 生成变更 diff 文本；超过 maxLines 截断并标注「…已截断，共 N 行」 */
    public String diff(String before, String after) {}
}
```
- 算法（轻量，非完整 Myers）：before/after 按行切分 → 找公共前缀/公共后缀 → 中间即变更块；输出：
  `@@ -起,数 +起,数 @@` + 前后各至多 2 行上下文 + `-` 行 + `+` 行。
- before 为空 → 全部 `+` 行；after 为空 → 全部 `-` 行。

### history 包（新建 com.zhubao.history）

```java
public record FileCheckpoint(
        String id,             // 递增序号（如 "0001"），保证顺序
        Instant timestamp,     // 快照时间
        String path,           // 工作区内绝对路径（已解析）
        boolean existed,       // 写前文件是否存在
        String beforeContent,  // 写前内容（existed=false 时为 null）
        String summary) {}     // 变更摘要（如 "edit_file: src/Foo.java"）

public final class FileHistory {
    public FileHistory(Path snapshotsRoot, PathGuard guard) {}
    /** 写操作前调用：读取当前内容 → 落盘检查点；超大文件（>10MB）返回 null（不生成检查点） */
    public String snapshotBefore(String sessionId, Path target, String summary) {}
    /** 写操作失败时调用：丢弃刚追加的检查点（避免垃圾检查点） */
    public void discardLast(String sessionId) {}
    /** /undo：回退最近一个检查点（快捷）；无可撤销时返回 ok=false */
    public RollbackResult undo(String sessionId) {}
    /** /rewind：回退到第 index 个检查点（含其本身及之后全部），其后检查点丢弃 */
    public RollbackResult rewindTo(String sessionId, int index) {}
    /** 列出检查点（时间倒序，供 /rewind 选择） */
    public List<FileCheckpoint> list(String sessionId) {}
}
public record RollbackResult(boolean ok, String message, List<String> actions) {}
```
- 存储布局：`~/.zhu-code-agent/snapshots/<会话ID>/checkpoints.json`（JSON 列表，原子写 tmp+rename，仿 SessionStore）；目录损坏跳过不崩溃。
- 回滚语义：undo = 撤销最近一个检查点（恢复该文件写前内容；existed=false → 删除文件）；rewind 到 k = 从最后一个到第 k 个**倒序逐个恢复**（同一文件多次写时顺序恢复正确），然后 `truncateFrom(k)` 丢弃 k 及之后。
- 恢复写入前**再过 PathGuard**（越界/禁写目录拒绝，返回可读错误）。

### permission 包（扩展）

```java
public enum PermissionMode { NORMAL, ACCEPT_EDITS, BYPASS_PERMISSIONS }
// PermissionManager 新增：
//   private PermissionMode mode = PermissionMode.NORMAL;
//   public PermissionMode mode() / setMode(PermissionMode)
//   decide(ToolCall) 按 mode 调整（见下）
//   reset() 只清「总是允许」，mode 不变
```
decide() 逻辑：
- 只读工具 → ALLOW（不变）
- write_file/edit_file：`mode ∈ {ACCEPT_EDITS, BYPASS}` → ALLOW；否则命中「总是允许」→ ALLOW；否则 NEED_CONFIRM
- bash：危险命令 → **NEED_CONFIRM（强制，spec F4 红线不变）**；`mode == BYPASS` → ALLOW；命中「总是允许」→ ALLOW；否则 NEED_CONFIRM

### tool 包（扩展）

```java
public enum RenderHint { PREVIEW, FULL }   // 渲染提示：PREVIEW=按 tool_preview_lines 预览；FULL=完整展示

// ToolResult 增加可选字段 renderHint（默认 PREVIEW；FULL 表示结果含需完整展示的 diff）
// ToolResult 是内存对象（落盘的是 ContentBlock.ToolResultBlock，不含该字段）→ 会话 JSON 格式不变
// ok/error 工厂增加带 hint 的重载；SerialToolExecutor/PlanModeExecutor 生成的结果默认 PREVIEW

public final class PlanModeExecutor implements ToolExecutor {   // /plan 计划阶段执行器
    public PlanModeExecutor(ToolRegistry registry, PermissionManager permissions,
                            AgentUi ui, int previewLines) {}
    // write_file / edit_file / bash → ToolResult.error("计划阶段禁止该操作（仅只读调研）")
    // 其余（read_file/grep/glob）→ 委托内部 SerialToolExecutor 逐个执行
}
```
SerialToolExecutor 新增快照钩子（构造参数追加 `FileHistory history`、`String sessionId`，可为 null）：
- 权限 ALLOW 后、执行前，若目标是 write_file/edit_file：解析 path → `history.snapshotBefore(...)`（解析失败/超大文件跳过并注明）→ 执行工具 → 成功 `commit`（检查点已落盘即保持）/ 失败 `discardLast`。

### agent 包（扩展 AgentRunner）

```java
public static final String PLAN_SYSTEM_PROMPT =  // 计划模式系统提示词：
    "...可用只读工具调研；完成调研后以文本输出分步执行计划并结束，不得调用 write_file/edit_file/bash...";

/** /plan 计划阶段：addUser + PLAN 提示词 + PlanModeExecutor 受限循环；end_turn 即计划完成，assistant 写回计划文本 */
public static Result runPlan(LlmClient client, Conversation conversation, String userText,
        List<ToolSpec> tools, PlanModeExecutor executor, AgentUi ui,
        int maxCallsPerTurn, long stepIdleTimeoutMs) {}

/** 批准后执行阶段：不再 addUser（会话已含 user+计划+调研结果），正常循环（SerialToolExecutor） */
public static Result runExecution(LlmClient client, Conversation conversation,
        List<ToolSpec> tools, SerialToolExecutor executor, AgentUi ui,
        int maxCallsPerTurn, long stepIdleTimeoutMs) {}

/** 修改意见后重新生成计划：不再 addUser（会话已含任务+调研+旧计划+意见），计划循环（PlanModeExecutor） */
public static Result runPlanContinue(LlmClient client, Conversation conversation,
        List<ToolSpec> tools, PlanModeExecutor executor, AgentUi ui,
        int maxCallsPerTurn, long stepIdleTimeoutMs) {}
```
- 重构：抽出私有 `runLoop(client, conversation, tools, executor, ui, maxCalls, idleTimeout, systemPrompt, boolean addUser)`；`run()` = runLoop(NORMAL, addUser=true)；`runPlan` = runLoop(PLAN, addUser=true, PlanModeExecutor)；`runExecution` = runLoop(NORMAL, addUser=false, SerialToolExecutor)；`runPlanContinue` = runLoop(PLAN, addUser=false, PlanModeExecutor)。

### tui 包（扩展）

- SlashCommands.Action 新增 `PLAN / UNDO / REWIND`；parse：`/plan`（含带参数）、`/undo`、`/rewind`。
- ChatApp 新增：
  - `private final FileHistory fileHistory;`（程序级，snapshotsRoot + PathGuard，构造一次）
  - `handlePlan(String task)`：空任务 → 提示「请输入任务描述，如 /plan 重构 xxx」；否则进入计划循环：runPlan → 展示计划（流式输出即计划文本）→ `readLine("[计划] 批准执行(y) / 拒绝(d) / 输入修改意见重新生成？")` → `y` → runExecution 并渲染结果；`d` → 提示已拒绝、无副作用；**其他输入 = 修改意见 → conversation.addUser(意见) → 再次 runPlanContinue（不重复 addUser 任务）重新生成计划**，可循环多次（用户主动触发，天然可控）。
  - `handleUndo()` / `handleRewind()`：调 FileHistory → 打印 actions → `conversation.addUser("[回滚] …")` → saveSession。
  - `handlePermissions` 扩展：`/permissions normal|acceptEdits|bypassPermissions` 切换模式；查看时显示当前模式。
  - `TuiAgentUi.onToolResult`：按 `result.renderHint()` 渲染——`FULL`（写工具带 diff 的结果）**完整展示**（工具层已按 diff_max_lines 截断），行首 `+` 绿色 / `-` 红色 / `@@` 亮青；`PREVIEW`（其余结果）沿用 tool_preview_lines 预览。**UI 不按工具名分支，只按语义标记**（A+ 决策）。
- 快照目录默认值：`~/.zhu-code-agent/snapshots`（常量，仿 defaultSessionsDir）。

### config 包（扩展）

- AppConfig 增加 `int uiDiffMaxLines`（默认 200，常量 `DEFAULT_UI_DIFF_MAX_LINES`）。
- ConfigLoader：`nestedInt(map, "ui", "diff_max_lines", DEFAULT)`。
- config.example.yml：ui 下加 `diff_max_lines: 200`。

## 模块设计

### DiffGenerator（diff 包）
**职责：** before/after 内容 → 变更 diff 文本（含截断标注）。
**对外接口：** `String diff(String, String)`。
**依赖：** 无（纯函数）。EditFileTool/WriteFileTool 持有实例生成结果 diff。

### FileHistory（history 包）
**职责：** 检查点落盘、undo/rewind 恢复、恢复前路径校验。
**对外接口：** snapshotBefore / discardLast / undo / rewindTo / list。
**依赖：** PathGuard、snapshotsRoot。被 SerialToolExecutor（快照）与 ChatApp（undo/rewind 命令）调用。

### PlanModeExecutor（tool 包）
**职责：** 计划阶段执行器——只放行只读工具，写/bash 拦截回填错误。
**对外接口：** `List<ToolResult> execute(List<ToolCall>)`。
**依赖：** ToolRegistry、PermissionManager、AgentUi、previewLines。

### PermissionManager（permission 包）
**职责：** 按模式判定权限 + 「总是允许」记忆（不变）。
**对外接口：** decide / rememberAlways / allowedList / reset / **mode / setMode**。
**依赖：** ToolRegistry、DangerGuard。

### AgentRunner（agent 包）
**职责：** 普通循环（不变）+ 计划循环（runPlan）+ 批准后执行（runExecution）。
**对外接口：** run / runPlan / runExecution。
**依赖：** LlmClient、Conversation、ToolExecutor、AgentUi。

### ChatApp（tui 包）
**职责：** TUI 状态机接线——/plan、/undo、/rewind、/permissions 模式、diff 彩色预览、FileHistory 与 executor 组装。
**依赖：** 上述全部。

## 模块交互

**普通一轮（F1/F3/F4）：**
```
ChatApp.sendAndRender
 → AgentRunner.run（NORMAL 提示词, addUser=true）
    → consumeStep：LLM 流事件 → toolCalls
    → SerialToolExecutor.execute
        → PermissionManager.decide（按 mode）
        → [write/edit] FileHistory.snapshotBefore(sessionId, path)（写前快照，暂存）
        → tool.execute → EditFileTool/WriteFileTool 用 DiffGenerator 生成 diff 进 ToolResult
        → 成功保持检查点 / 失败 discardLast
        → TuiAgentUi.onToolResult：按 renderHint=FULL 完整展示 diff（+绿/-红/@@亮青）
    → conversation.addAssistantWithTools + addToolResultBlocks → 循环直到 end_turn
```
**/plan（F2）：**
```
ChatApp.handlePlan("/plan <任务>")
 → AgentRunner.runPlan（PLAN 提示词, addUser=true, PlanModeExecutor）
    → read_file/grep/glob 正常执行；write/edit/bash 被拦截回填错误（无副作用、无快照）
    → 模型 end_turn（无工具调用）→ assistant 写回计划文本 → Result(text=计划)
 → TUI 展示计划（流式已输出）→ readLine：y / d / 修改意见
 → y → AgentRunner.runExecution（NORMAL 提示词, addUser=false, SerialToolExecutor）→ 同普通一轮
 → d → 提示已拒绝，本轮结束（会话保留计划文本，无文件副作用）
 → 其他输入=意见 → conversation.addUser(意见) → AgentRunner.runPlanContinue（PLAN, addUser=false）→ 回到展示计划，可循环（用户驱动）
```
**/undo / /rewind（F3）：**
```
ChatApp.handleUndo → FileHistory.undo(sessionId) → 恢复最近检查点 → 打印 actions
ChatApp.handleRewind → FileHistory.list → JLinePicker 选择 → FileHistory.rewindTo(sessionId, index)
 → 打印 actions → conversation.addUser("[回滚] …") → saveSession
```
**/permissions（F4）：** `ChatApp.handlePermissions` → PermissionManager.setMode / mode 展示。

## 文件组织

| 操作 | 文件 | 职责 |
|------|------|------|
| 新建 | `diff/DiffGenerator.java` | before/after → diff 文本（截断标注） |
| 新建 | `history/FileCheckpoint.java`、`history/FileHistory.java` | 检查点记录 + 落盘/undo/rewind |
| 新建 | `permission/PermissionMode.java` | 三档权限模式枚举 |
| 新建 | `tool/PlanModeExecutor.java` | 计划阶段只读执行器 |
| 修改 | `tool/builtin/EditFileTool.java`、`WriteFileTool.java` | 结果内嵌 diff（构造注入 DiffGenerator） |
| 修改 | `tool/ToolRegistry.java` | 构造加 diffMaxLines，装配工具 |
| 修改 | `tool/SerialToolExecutor.java` | 快照钩子（history+sessionId，可空） |
| 修改 | `tool/ToolResult.java`（+`RenderHint`） | renderHint 字段 + 工厂重载（会话 JSON 不变） |
| 修改 | `permission/PermissionManager.java` | mode 字段 + decide 按模式 |
| 修改 | `agent/AgentRunner.java` | runPlan/runExecution + PLAN_SYSTEM_PROMPT + runLoop 重构 |
| 修改 | `tui/SlashCommands.java` | +PLAN/UNDO/REWIND |
| 修改 | `tui/ChatApp.java` | plan/undo/rewind/permission-mode 流程 + diff 彩色预览 + FileHistory 接线 |
| 修改 | `config/AppConfig.java`、`config/ConfigLoader.java`、`config.example.yml` | ui.diff_max_lines |
| 新建 | `test/…/diff/DiffGeneratorTest`、`test/…/history/FileHistoryTest`、`test/…/permission/PermissionManagerModeTest`、`test/…/tool/PlanModeExecutorTest` | 单测 |
| 修改 | `test/…/integration/AgentLoopIntegrationTest`（或新增 PlanModeIntegrationTest）、`SlashCommandsTest`、`ConfigLoaderTest`、工具/执行器测试 | mock 端到端 + 回归适配 |

## 技术决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| diff 算法 | 公共前缀/后缀 + 中间变更块（轻量），非完整 Myers | edit_file 单点替换精确；write_file 给变更概览；零第三方依赖；可单测 |
| diff 位置 | 工具结果内（tool result 文本） | spec F1 随落盘、模型可见可自校验 |
| 写工具结果预览 | `ToolResult.renderHint`（FULL/PREVIEW）语义标记，UI 按标记渲染：FULL 完整展示 diff、PREVIEW 走 tool_preview_lines | 解耦（UI 不按工具名分支）；ToolResult 为内存对象，会话 JSON 格式不变；为 M3+ 折叠/展开留扩展点（A+ 决策，用户批准） |
| 快照时机 | 执行器层：权限通过后、执行前快照；成功保持 / 失败 discardLast | Tool/ToolExecutor 接口不变（N2）；失败写不产生垃圾检查点 |
| 检查点存储 | 每会话 `checkpoints.json` 原子写（tmp+rename） | 与 SessionStore 一致；恢复会话后跨会话可回滚（F3） |
| undo 语义 | = rewind 到最近检查点（统一机制，一套存储两个入口） | 用户批准（spec F3 变更记录） |
| 回滚记录 | `conversation.addUser("[回滚] …")` | AC3 落盘可见；模型下一轮可感知 |
| 权限模式 | PermissionManager 持有 mode 字段，decide() 一处生效 | executor/UI 不改，测试注入方便 |
| 计划阶段写拦截 | PlanModeExecutor（实现 ToolExecutor 接口）包装 | 接口不变、可单测；执行链路不破坏（N2） |
| 计划修改意见 | 批准环节 readLine：y 批准 / d 拒绝 / 任意文本=意见 → addUser(意见)+runPlanContinue 重新生成（循环由用户驱动，无硬上限） | 覆盖「模型计划中提待确认项」场景；保持单轮形态、无模式状态机（用户批准，变更控制 2026-08-09） |
| /plan 结束判定 | 模型 end_turn（无工具调用）= 计划完成，最终文本即计划 | 复用现有循环语义（用户已确认）；护栏沿用（60 步/120s） |
| 冲突检测 | 不做（回滚可能覆盖回滚后的手工修改） | spec 未要求，M3 不做，YAGNI |
| 回滚路径校验 | 恢复写入前再过 PathGuard | 防回滚越界/写禁写目录（N3/N4） |
| 超大文件 | >10MB 跳过快照并在结果注明 | N6 防内存/磁盘膨胀 |
| 配置 | `ui.diff_max_lines`（默认 200）进 config | 可调展示上限，对齐 M2 模式 |

## 需求覆盖对照

- F1（diff 展示）→ diff/DiffGenerator + EditFileTool/WriteFileTool（结果带 renderHint=FULL）+ TuiAgentUi 按 hint 彩色预览。
- F2（/plan）→ AgentRunner.runPlan/runPlanContinue/runExecution + PlanModeExecutor + ChatApp.handlePlan（y/d/修改意见循环）+ PLAN_SYSTEM_PROMPT；护栏沿用。
- F3（快照回滚）→ history/FileHistory + SerialToolExecutor 快照钩子 + ChatApp.handleUndo/handleRewind + 回滚记录写回会话；bash 不追踪（仅写工具快照）。
- F4（权限模式）→ permission/PermissionMode + PermissionManager.decide + ChatApp.handlePermissions；红线不破（危险强制确认/cwd 外拒绝）。
- N1 → AppConfig/ConfigLoader/config.example.yml（ui.diff_max_lines），零新依赖。
- N2 → ToolExecutor/Tool 接口不变；新增独立 diff/history 模块。
- N3 → 快照目录 `~/.zhu-code-agent/snapshots`（PathGuard 禁工具写）；权限模式/清单不落盘；计划阶段 PlanModeExecutor 拦截先于执行（零副作用）。
- N4 → 失败可读不崩溃、损坏检查点跳过、恢复前 PathGuard 校验。
- N5 → 各模块单测 + mock 端到端（计划批准/拒绝、三权限模式、undo/rewind 真实回滚）+ M2 127 回归。
- N6 → diff_max_lines 截断 + 10MB 快照上限。
