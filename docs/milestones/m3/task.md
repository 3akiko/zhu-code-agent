状态：approved
# zhuCodeAgent M3：文件编辑增强与 Plan Mode Tasks

> 依据已批准的 docs/spec.md + docs/plan.md（M3）。每个任务自包含，粒度 ≤15 分钟为主。
> 任务顺序按依赖排列；T14 需用户 review 确认后才执行。

## 文件清单

| 操作 | 文件 | 职责 |
|------|------|------|
| 新建 | `src/main/java/com/zhubao/diff/DiffGenerator.java` | before/after → diff 文本（截断标注） |
| 新建 | `src/main/java/com/zhubao/history/FileCheckpoint.java` | 检查点记录 |
| 新建 | `src/main/java/com/zhubao/history/FileHistory.java` | 检查点落盘 + snapshot/undo/rewind |
| 新建 | `src/main/java/com/zhubao/permission/PermissionMode.java` | 三档权限模式枚举 |
| 新建 | `src/main/java/com/zhubao/tool/RenderHint.java` | 渲染提示 PREVIEW/FULL |
| 新建 | `src/main/java/com/zhubao/tool/PlanModeExecutor.java` | 计划阶段只读执行器 |
| 修改 | `src/main/java/com/zhubao/tool/ToolResult.java` | +renderHint 字段与工厂重载（会话 JSON 不变） |
| 修改 | `src/main/java/com/zhubao/tool/builtin/EditFileTool.java`、`WriteFileTool.java` | 结果内嵌 diff（renderHint=FULL） |
| 修改 | `src/main/java/com/zhubao/tool/ToolRegistry.java` | 构造加 diffMaxLines，装配 DiffGenerator |
| 修改 | `src/main/java/com/zhubao/tool/SerialToolExecutor.java` | 快照钩子（history+sessionId，可空） |
| 修改 | `src/main/java/com/zhubao/permission/PermissionManager.java` | mode 字段 + decide 按模式 |
| 修改 | `src/main/java/com/zhubao/agent/AgentRunner.java` | runPlan/runExecution + PLAN_SYSTEM_PROMPT + runLoop |
| 修改 | `src/main/java/com/zhubao/tui/SlashCommands.java`、`ChatApp.java` | 新命令 + plan/undo/rewind/模式流程 + diff 彩色预览 |
| 修改 | `src/main/java/com/zhubao/config/AppConfig.java`、`ConfigLoader.java`、`config.example.yml` | ui.diff_max_lines |
| 新建 | `src/test/java/com/zhubao/diff/DiffGeneratorTest.java`、`history/FileHistoryTest.java`、`permission/PermissionManagerModeTest.java`、`tool/PlanModeExecutorTest.java`、`integration/PlanModeIntegrationTest.java` | 单测 + mock 端到端 |
| 修改 | `src/test/java/com/zhubao/tool/FileToolsTest.java`、`config/ConfigLoaderTest.java`、`tui/SlashCommandsTest.java`、`permission/PermissionManagerTest.java`、`agent/AgentRunnerTest.java`、`integration/AgentLoopIntegrationTest.java` | 断言适配 + 回归 |

## T1: config 扩展 ui.diff_max_lines
**文件：** `config/AppConfig.java`、`config/ConfigLoader.java`、`config.example.yml`、`test/…/config/ConfigLoaderTest.java`
**依赖：** 无
**步骤：**
1. AppConfig 增加 `int uiDiffMaxLines` + 常量 `DEFAULT_UI_DIFF_MAX_LINES = 200`
2. ConfigLoader.bind 用 `nestedInt(map, "ui", "diff_max_lines", DEFAULT)` 解析
3. config.example.yml 的 ui 段加 `diff_max_lines: 200`
**验证：** `mvn -q -Dtest=ConfigLoaderTest test` 绿；临时 yml 断言默认 200 / 自定义值生效

## T2: diff 模块 + ToolResult.renderHint
**文件：** 新建 `diff/DiffGenerator.java`、`tool/RenderHint.java`；修改 `tool/ToolResult.java`；新建 `test/…/diff/DiffGeneratorTest.java`
**依赖：** 无
**步骤：**
1. `RenderHint { PREVIEW, FULL }` 枚举
2. ToolResult 增加 `renderHint`（默认 PREVIEW）+ ok/error 工厂带 hint 重载；落盘走 ToolResultBlock（不含 hint）→ 会话 JSON 不变
3. DiffGenerator(maxLines)：行切分 → 公共前缀/后缀 → 中间变更块 → `@@ -起,数 +起,数 @@` + 上下文 ±2 行 + `-`/`+` 行；before 空 → 全 `+`；after 空 → 全 `-`；超 maxLines 截断并标注「…已截断，共 N 行」
**验证：** DiffGeneratorTest 覆盖精确替换/多行/新建/覆写/截断；`mvn -q -Dtest=DiffGeneratorTest test` 绿

## T3: 工具结果内嵌 diff
**文件：** `tool/builtin/EditFileTool.java`、`tool/builtin/WriteFileTool.java`、`tool/ToolRegistry.java`；修改 `test/…/tool/FileToolsTest.java`
**依赖：** T1、T2
**步骤：**
1. EditFileTool 构造注入 DiffGenerator；替换成功后输出 `已替换 1 处 → <path>\n<diff>`，renderHint=FULL
2. WriteFileTool 构造注入 DiffGenerator；写前读旧内容——存在 → `已覆写 N 字节 → <path>\n<diff>`；不存在 → `已创建 <path>（N 行 / M 字节）`；renderHint=FULL
3. ToolRegistry 构造加 diffMaxLines，创建 DiffGenerator 并传入两工具
4. FileToolsTest 断言新输出格式与 hint
**验证：** `mvn -q -Dtest='FileToolsTest,DiffGeneratorTest' test` 绿

## T4: history 存储与快照
**文件：** 新建 `history/FileCheckpoint.java`、`history/FileHistory.java`；新建 `test/…/history/FileHistoryTest.java`
**依赖：** 无
**步骤：**
1. FileCheckpoint record（id 递增序号/timestamp/path/existed/beforeContent/summary）
2. FileHistory(snapshotsRoot, guard)：每会话 `checkpoints.json` 原子写（tmp+rename，仿 SessionStore）；`snapshotBefore`（读当前内容→追加检查点→返回内容；>10MB 返回 null 不落盘）；`discardLast`；`list`（时间倒序）；损坏文件跳过不崩溃
3. 快照目录 `~/.zhu-code-agent/snapshots/<会话ID>/`；工具不可写（PathGuard 已禁 ~/.zhu-code-agent/）
**验证：** FileHistoryTest：快照落盘/重载读取/discardLast/超大文件跳过/损坏跳过

## T5: history 回滚
**文件：** `history/FileHistory.java`（undo/rewindTo）+ FileHistoryTest 扩展
**依赖：** T4
**步骤：**
1. `undo`：撤销最近检查点——恢复 beforeContent（existed=false → 删除文件）；恢复前 PathGuard 校验（越界/禁写 → 可读错误）
2. `rewindTo(index)`：从最后一个到 index **倒序**逐个恢复；`truncateFrom(index)` 丢弃其后检查点
3. `RollbackResult(ok, message, actions)` 返回恢复动作列表
**验证：** FileHistoryTest：undo 单步/连续、同文件多次写 rewind 顺序正确、created 文件 undo 删除、跨会话新实例读取、恢复路径越界/禁写拒绝

## T6: SerialToolExecutor 快照钩子
**文件：** `tool/SerialToolExecutor.java`；适配相关测试构造
**依赖：** T3、T4
**步骤：**
1. 构造追加 `FileHistory history`、`String sessionId`（均可为 null，测试兼容）
2. 权限 ALLOW 后、执行前：write_file/edit_file → 解析 path（guard）→ `snapshotBefore`；工具执行成功保持检查点 / 失败 `discardLast`；解析失败/超大文件跳过并在结果注明
3. 只读工具与 bash 不触发快照
**验证：** executor 单测：写成功生成检查点、写失败无残留、只读无检查点；现有测试编译通过

## T7: PlanModeExecutor
**文件：** 新建 `tool/PlanModeExecutor.java`；新建 `test/…/tool/PlanModeExecutorTest.java`
**依赖：** T6
**步骤：**
1. 实现 ToolExecutor：write_file/edit_file/bash → `ToolResult.error("计划阶段禁止该操作（仅只读调研）")`，调用 ui.onToolCall/onToolResult；其余委托内部 SerialToolExecutor
2. 拒绝结果不计快照、无副作用
**验证：** PlanModeExecutorTest：写/bash 拦截、只读放行、UI 回调触发

## T8: PermissionMode + PermissionManager
**文件：** 新建 `permission/PermissionMode.java`；修改 `permission/PermissionManager.java`；新建 `test/…/permission/PermissionManagerModeTest.java`
**依赖：** 无
**步骤：**
1. `PermissionMode { NORMAL, ACCEPT_EDITS, BYPASS_PERMISSIONS }`
2. PermissionManager 加 `mode` 字段 + `mode()/setMode()`；decide() 按模式：acceptEdits → write/edit ALLOW、bash 照旧；bypass → bash 非危险 ALLOW；**危险命令仍 NEED_CONFIRM**；`reset()` 只清 alwaysAllowed、mode 不变
**验证：** PermissionManagerModeTest：三模式行为、危险强制确认、reset 不影响 mode；原 PermissionManagerTest 回归绿

## T9: AgentRunner runPlan/runExecution
**文件：** `agent/AgentRunner.java`；修改 `test/…/agent/AgentRunnerTest.java`
**依赖：** T7
**步骤：**
1. 新增 PLAN_SYSTEM_PROMPT（「可用只读工具调研；完成后以文本输出分步执行计划并结束，不得调用写工具」）
2. 抽 `runLoop(client, conversation, tools, executor, ui, maxCalls, idleTimeout, systemPrompt, addUser)`
3. `run()` = runLoop(NORMAL, addUser=true)；`runPlan()` = runLoop(PLAN, addUser=true, PlanModeExecutor)；`runPlanContinue()` = runLoop(PLAN, addUser=false, PlanModeExecutor)；`runExecution()` = runLoop(NORMAL, addUser=false, SerialToolExecutor)
**验证：** AgentRunnerTest 回归绿；新增 runPlan mock 用例：调研后 end_turn 返回计划文本、不产生写副作用

## T10: SlashCommands 扩展
**文件：** `tui/SlashCommands.java`；扩展 `test/…/tui/SlashCommandsTest.java`
**依赖：** 无
**步骤：**
1. Action 加 `PLAN / UNDO / REWIND`；parse 识别 `/plan`（含带参数）、`/undo`、`/rewind`
**验证：** SlashCommandsTest 绿

## T11: ChatApp 接线
**文件：** `tui/ChatApp.java`
**依赖：** T3、T5、T6、T7、T8、T9、T10
**步骤：**
1. 构造 FileHistory（snapshotsRoot 默认 `~/.zhu-code-agent/snapshots` + guard）；sendAndRender 传 history+sessionId 给 executor
2. `handlePlan`：空任务 → 提示「请输入任务描述，如 /plan 重构 xxx」；否则计划循环：runPlan → 流式展示计划 → `readLine("[计划] 批准执行(y) / 拒绝(d) / 输入修改意见重新生成？")` → y → runExecution 渲染结果；d → 提示拒绝、无副作用；其他输入=意见 → conversation.addUser(意见) → runPlanContinue 重新生成（循环由用户驱动）
3. `handleUndo`/`handleRewind`：FileHistory → 打印 actions → `conversation.addUser("[回滚] …")` → saveSession
4. `handlePermissions`：`/permissions normal|acceptEdits|bypassPermissions` 切换 + 查看显示当前模式
5. `TuiAgentUi.onToolResult`：按 `result.renderHint()`——FULL 完整展示并彩色（行首 `+` 绿 / `-` 红 / `@@` 亮青）；PREVIEW 沿用 tool_preview_lines
**验证：** 编译通过；后续 T12 集成测试；PTY 冒烟

## T12: mock 端到端集成测试
**文件：** 新建 `test/…/integration/PlanModeIntegrationTest.java`；适配 `AgentLoopIntegrationTest.java` 构造
**依赖：** T11
**步骤：**
1. 计划批准场景：mock 模型 read_file 调研 → 尝试 write（被拦截回填错误）→ end_turn 出计划 → y → 执行 write（权限通过）→ 完成；断言文件最终存在且内容正确
2. 计划拒绝场景：d → 断言无任何文件副作用
3. 权限模式场景：acceptEdits/bypass 下写工具直接执行；bypass 下 bash 非危险自动、危险命令仍确认
5. 计划修改意见场景：mock 出计划 → 输入「用方案 B」→ 第二次计划反映意见（断言请求含意见文本）→ y → 按最终计划执行
4. undo/rewind 场景：临时工作区 edit 后 undo/rewind 恢复内容、跨会话（重载 FileHistory 新实例）仍可回滚
**验证：** `mvn -q -Dtest='PlanModeIntegrationTest,AgentLoopIntegrationTest' test` 绿

## T13: 全量回归 + 收尾
**文件：** 无新代码（如有小修则改）
**依赖：** T12 及全部
**步骤：**
1. `mvn test` 全量绿（原 127 + M3 新增）
2. `mvn -q package` 产出 `target/zhu-code-agent.jar`
3. PTY 真机冒烟 1~2 场景（/plan 全流程、edit 后 diff 彩色、/undo）
4. 更新 docs/TODO.md（勾掉已完成、记录新技术债如「回滚冲突检测」）
**验证：** mvn test 全绿；package 成功；冒烟记录

## T14: 里程碑收尾（用户 review 确认后）
**文件：** docs/implementation.md、docs/resume.md、docs/roadmap.md、CHANGELOG.md、docs/milestones/m3/ 归档
**依赖：** T13 + **用户 review 确认**
**步骤：**
1. 先交用户 review（对照 checklist/验收报告），用户确认后才继续
2. 归档四文档到 docs/milestones/m3/ + 更新 implementation.md / CHANGELOG / roadmap 对比表 / resume.md（root + 归档）
3. git 提交到 `feature-m3-plan-mode` 分支（先切分支，master 保持可运行基线）
**验证：** 用户 review 通过；文档与代码现状一致

## 执行顺序
```
T1 ─┐
T2 ─┼─→ T3 ─┐
T4 ─→ T5 ─→ T6 ─→ T7 ─→ T9 ─┐
T8 ──────────────────────────┼─→ T11 ─→ T12 ─→ T13 ─→ T14(需用户 review)
T10 ─────────────────────────┘
```
