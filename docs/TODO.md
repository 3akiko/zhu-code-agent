# TODO 清单

> 跨里程碑的待办/已知问题。里程碑级功能见 `docs/roadmap.md`，这里放开发中发现的具体待办。
> 每项完成后打勾并注明日期/提交。
> **里程碑归属**（2026-08-10 与 roadmap M4–M8+ 重组对齐）：M4 上下文管理与稳定性 / M5 并行与 Subagents / M6 生态集成 / M7 安全纵深与工程化 / M8+ 扩展。

## 待办

- [x] **M2-ReAct 状态行生命周期重构**（2026-08-07 记录，M2 2026-08-08 完成：AgentRunner 每步状态行）
  - 背景：M2 的 ReAct 循环里，一轮用户输入会有多次 LLM 调用（think → tool_use → 执行工具 → tool result → think → …）。
  - 现状：`ChatApp.sendAndRender` 中 `boolean[] statusCleared` 是"每轮用户输入清一次"。
  - 需要：把"打印状态行 → 首个流式内容到达时清除"的粒度从「每轮一次」改为「每个 agent 步骤一次」；
    每个步骤有自己的状态行（如 ⏳ 正在思考… / 🔧 执行工具 read_file…），工具结果回来后替换/清除。
  - 可复用：`TerminalUi.clearPreviousLine()`（ANSI `\u001b[1A\u001b[K`）原语不变。
  - 关联：`TurnRunner` 需演进为 agent 循环（或新增 `AgentRunner`），处理 `StreamEvent` 的 ToolCall 事件与工具执行。
  - 位置：`src/main/java/com/zhubao/tui/ChatApp.java`（已加 TODO 注释）、`docs/roadmap.md` M2。

## 开发中发现的技术债务（2026-08-07 review 记录）

- [x] **客户端并发安全**（M5 2026-08-13 完成：per-call `StreamState` 状态持有者 + `LlmClientFactory` 按 provider 名缓存复用；`ConcurrentStreamTest` 覆盖并发两流/cancel 隔离/复用不残留）
- [x] **max_tokens 可配置化**（M4 2026-08-12 完成：`LlmLimits` 模型表 + provider `context_window`/`max_tokens` 覆盖，F7）。
- [x] **会话存储性能**（M4 2026-08-12 完成：JSONL 追加写 O(1)，F6）。
- [ ] **多行输入**（→ 后置 **M8+**：UI 体验类）：M1 单行输入（Enter 即发送）；Shift+Enter 换行需绑定各终端的转义序列 + 多行渲染，属 M2 UI 增强。
- [x] **退出时优雅关闭流线程**（M4 2026-08-12 完成：`TurnInterruptController` cancel + join，F5）。

## M4 开发中发现的技术债务（2026-08-12 记录）

- [ ] **/rewind /undo 回滚记录连续 user**（→ **M8+**，roadmap 扩展清单已记录）：`ChatApp.renderRollback` 以 **user 角色**写入「[回滚] …」记录，紧接着提问出现**连续 user 消息**，违反 spec N3，Anthropic 严格端点/代理可能 `400 roles must alternate`（M4 demo 会话 `20260811-234252-a2dc.jsonl` [26][27] 实测）。修复：① [回滚] 记录合入下一条真实 user 消息（或按角色补位）；② `AnthropicClient.buildMessages` 连续同角色合并兜底。
- [ ] **中断轮不计累计**（→ 设计内行为，如要精确统计可后续做）：被取消的流拿不到 usage（StreamEnd 未到达），中断轮实际消耗的 token 不进会话累计。
- [ ] **prompt 缓存短前缀不命中**（→ 观察项）：DeepSeek 两种端点都需足够长公共前缀才命中缓存（Anthropic 端点还需显式 `cache_control`）；长会话首轮 cache 为 0 属预期。

## M3 开发中发现的技术债务（2026-08-09 review 记录）

- [ ] **回滚冲突检测**（→ 建议 **M7**：与 git 集成/回滚协同时一并）：M3 的 undo/rewind 直接恢复检查点内容，不检测「回滚后用户/其他工具又手工改过文件」——可能覆盖后续改动；后续可加「恢复前比对当前内容与检查点后一状态，不一致则提示」。
- [ ] **快照目录清理**（→ 建议 **M7** 工程化：总量上限与清理策略）：`~/.zhu-code-agent/snapshots/<会话ID>/checkpoints.json` 只增不减（rewind 会截断，但普通会话持续增长），无容量上限；后续可按会话/时间清理或做大小上限。
- [ ] **/plan 修改意见文案**（→ 后置小项）：意见以 user 消息「（对计划的修改意见）xxx」写入会话，模型可见；后续可考虑专用标记块避免与普通用户消息混淆。

## 其他已知项（可选项，来自开发过程）

- [ ] OpenAI 兼容协议 `reasoning_content` 解析（→ **M8+**，roadmap 扩展清单已有）：deepseek-reasoner 等推理模型思考灰字展示
- [ ] 应用内会话清理命令（→ 后置小工具）：如 `/clear-sessions`；用户可临时用 `rm ~/.zhu-code-agent/sessions/*.json` 手动清
- [ ] Anthropic 真机验证（→ 可选，需 `ANTHROPIC_API_KEY`；代码路径已被 mock 测试覆盖）


## M5 开发中发现的技术债务（2026-08-13）

- [ ] **权限弹窗与其他输出交错**（→ 可后续优化）：并行子任务完成行/工具摘要可能插入到权限弹窗行内（弹窗用 readLine 等待输入、不换行，其他线程输出经 TerminalUi 锁但仍同屏）。方案候选：弹窗期间其他输出暂存/弹窗完成后补打，或弹窗独立区域。
- [ ] **子任务结果可展开全文**（→ **M8+**，roadmap 已记录）：Task 子任务默认只回填结构化摘要；后续可请求展开子任务完整 transcript（需保留子历史引用 + 二次查询通道）。
- [ ] **子任务会话不落盘**（→ 设计内，如要可追溯再评估）：子任务仅内存、结果摘要进父会话；如要审计子任务内部过程需独立 JSONL。
- [ ] **并行子任务写同一文件的冲突检测**（→ 观察项）：并行子任务可能同时写同一路径（M5 接受该风险，由权限确认 + 用户可见兜底；Claude Code 用 worktree 隔离，M8+ 候选）。
