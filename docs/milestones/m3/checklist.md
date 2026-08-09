状态：approved
# zhuCodeAgent M3：文件编辑增强与 Plan Mode Checklist

> 每项通过运行代码或观察行为验证（做 X 看到 Y），聚焦系统行为。覆盖 spec AC1–AC6 与 plan 集成点。

## 实现完整性

- [ ] DiffGenerator 可生成变更 diff（验证：`DiffGeneratorTest` 通过；精确替换/多行/新建/覆写/截断断言成立）
- [ ] edit_file/write_file 结果含 diff 且 renderHint=FULL（验证：`FileToolsTest` 断言结果文本含 `-`/`+` 行与 hint）
- [ ] ToolResult.renderHint 语义生效（验证：写工具结果 hint=FULL；其余结果默认 PREVIEW；会话 JSON 不含 hint、旧会话可加载）
- [ ] FileHistory 快照/undo/rewind 可用（验证：`FileHistoryTest` 通过；落盘/重载/discardLast/超大文件/损坏跳过断言成立）
- [ ] PlanModeExecutor 拦截写工具与 bash（验证：`PlanModeExecutorTest` 通过；返回「计划阶段禁止该操作」错误）
- [ ] PermissionManager 三档模式生效（验证：`PermissionManagerModeTest` 通过；危险命令仍 NEED_CONFIRM）
- [ ] AgentRunner.runPlan/runExecution 可用（验证：`AgentRunnerTest` + 集成测试通过；runPlan 返回计划文本且无写副作用）
- [ ] /plan /undo /rewind 命令被解析（验证：`SlashCommandsTest` 通过）
- [ ] ui.diff_max_lines 配置生效（验证：`ConfigLoaderTest` 通过；默认 200、自定义值生效）

## 集成

- [ ] SerialToolExecutor 写前快照 → 工具执行 → 成功保持检查点 / 失败 discardLast（验证：executor 测试断言检查点数量与内容）
- [ ] ChatApp /plan 流程接通：runPlan → 展示计划 → y → runExecution；d → 无副作用（验证：PlanModeIntegrationTest 通过）
- [ ] ChatApp /undo 与 /rewind 接通：FileHistory 恢复文件 → `[回滚] …` 记录写回会话 → saveSession（验证：集成测试断言文件内容与会话消息）
- [ ] ChatApp /permissions 切换与展示当前模式（验证：集成测试/PTY 冒烟断言模式切换生效）
- [ ] TuiAgentUi 按 renderHint 渲染：FULL 完整彩色 diff（`+` 绿 / `-` 红 / `@@` 亮青）、PREVIEW 走 tool_preview_lines（验证：PTY 冒烟观察；单测断言渲染分支）

## 编译与测试

- [ ] `mvn test` 全绿：原 127 不回归 + M3 新增全部通过（验证：测试报告 Failures=0, Errors=0）
- [ ] `mvn -q package` 成功产出 `target/zhu-code-agent.jar`（验证：文件存在、构建 SUCCESS）

## 端到端场景

- [ ] 场景 1（/plan 批准全流程）：mock 模型先 read_file 调研 → 尝试 write 被拦截回填错误 → end_turn 出计划 → 输入 y → 同轮执行 write（权限确认通过）→ 最终文件存在且内容正确
- [ ] 场景 2（/plan 拒绝）：mock 模型出计划 → 输入 d → 临时工作区无任何写副作用（文件不存在/内容未变）
- [ ] 场景 3（diff 可见）：临时工作区 edit_file 真实替换后，工具结果含 `-`/`+` diff 且 TUI 彩色展示（PTY 冒烟观察 + 单测断言）
- [ ] 场景 4（undo/rewind）：临时工作区两次 edit 后 `/undo` 连续撤销恢复原内容；`/rewind` 列表选择中间检查点 → 该点之后改动被撤销、其后检查点被丢弃；重载会话（新 FileHistory 实例）后 `/undo`/`/rewind` 仍可用
- [ ] 场景 5（权限模式）：acceptEdits 下 write/edit 直接执行、bash 仍询问；bypassPermissions 下 bash 非危险直接执行、危险命令仍强制确认、指向 cwd 外的破坏性命令立即拒绝不执行
- [ ] 场景 6（安全稳定）：日志/会话 JSON/快照目录不含 api_key；工具调用写 `~/.zhu-code-agent/snapshots/` 被 PathGuard 拒绝（可读错误、无副作用）；损坏的 checkpoints.json 被跳过不崩溃
- [ ] 场景 7（计划修改意见）：mock 出计划 → 输入「用方案 B」→ 模型基于意见重新生成计划（第二次请求含意见文本）→ y → 最终文件按第二次计划内容写入
