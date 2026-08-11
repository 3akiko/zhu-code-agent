# zhuCodeAgent —— 命令行 AI 编程助手（个人项目）

**时间**：2026 年 8 月 ~ 至今
**技术栈**：Java 21、Maven、JLine3、JDK HttpClient、Jackson、SnakeYAML、JUnit 5

**项目简介**：从零实现对标 Claude Code / Codex 的命令行 Coding Agent，用于 agent 核心机制学习与 agent 开发面试。已完成 M1（聊天 TUI + 会话持久化）、M2（Agent 循环与 Tool Use）、M3（文件编辑增强与 Plan Mode）、M4（上下文管理与稳定性）：
- M1：彩色终端 TUI + SSE 流式对话 + 多轮记忆 + Anthropic/OpenAI 双后端（DeepSeek 的 OpenAI 与 Anthropic 两种兼容格式均可直连）+ Claude extended thinking 灰字展示 + 会话持久化与恢复。
- M2：模型工具调用闭环（tool_use / function_call 解析 → 权限确认 → 内置工具执行 → 结果回填 → 循环直到 end_turn）+ 6 个内置工具 + 安全边界与权限模型。
- M3：让文件修改"看得见、可反悔、可先规划"——write/edit 结果内嵌彩色 diff；`/plan` 先出计划、批准后执行（支持修改意见重新生成）；全量快照 `/undo` `/rewind`（跨会话）；权限模式三档 acceptEdits / bypassPermissions（安全红线不削弱）。
- M4：长会话"不崩、不贵、可中断"——token 统计与占用%（协议感知含缓存）、上限告警、双层渐进压缩（自动 + `/compact`）、prompt 缓存、Ctrl+C 流式中断（二次逃生门）、会话 JSONL 存储、max_tokens 可配置化。

全程 Spec 驱动开发（spec → plan → task → checklist 四文档 + 审批 + 验收报告），持续记录与 Claude Code / Codex 的对比。

**项目亮点**：

1、**统一 Provider 抽象与双协议工具调用**：`LlmClient` 接口 + 工厂 + `StreamEvent` 密封事件模型，一套调用方接入 Anthropic / OpenAI 双协议；M2 把 anthropic `tool_use` 与 openai `function_call` 收敛为统一 `ToolCall` 事件，请求体携带 tools 定义，tool_result 按各自协议回填——调用方（循环/会话/UI）不感知协议差异，新增协议零改动。

2、**自研 Agent 循环（ReAct）**：`AgentRunner` 消息循环——模型输出工具调用 → 权限判定 → 串行执行 → 一次性回填（协议要求同一条 assistant 消息的全部 tool_use 必须一次回填全部 tool_result）→ 循环直到 end_turn；循环护栏（单轮上限可配 + 每步流空闲超时）；执行器抽象为 `ToolExecutor` 接口，串行/并行只在执行器内部差异，为后续并行工具执行预留扩展点。

3、**安全体系（可面试深挖）**：`PathGuard` 路径边界（cwd 为根、realpath 含符号链接校验、禁写 `.git/` 与程序自身目录）；`DangerGuard` 危险命令防护（**所有文件系统修改命令 rm/rmdir/mv/cp 的目标必须位于工作区内，与 flags 无关**，shell 展开字符如 `~`/`$`/反引号直接拒绝，`rm -rf` 等危险命令即使曾「总是允许」也强制确认）；bash 工具无 stdin、30s 超时 kill 进程树、200KB 输出截断；密钥全程不落盘不落日志。

4、**权限确认模型**：只读工具自动放行；写类 / bash 行内确认（允许 / 拒绝 / 总是允许本次）；「总是允许」按「工具 + 关键参数」记忆（**文件写按路径、bash 按完整命令串**），仅内存、退出程序重置、不落盘——兼顾安全与交互效率，对标 Claude Code / Codex 的权限设计。

5、**工程化与质量**：215 个单元/集成测试全绿（含 mock HTTP/脚本化 LLM 端到端：临时工作区执行真实工具，双协议全覆盖；真机 DeepSeek 冒烟 M2 4 场景 + M3 demo 全流程 + M4 demo ①–⑤）；**多轮安全/正确性 review（含 review-agent 预审）修复多类问题**（tool_use 参数往返丢失、rm shell 展开绕过、权限记忆粒度、JSONL 收缩重写、中断状态可见性、占用口径等）；会话落盘支持内容块（tool_use/tool_result）、单条 64KB 截断标注、旧格式自动迁移、JSONL 追加写；完整文档体系（四文档 + 验收报告 + 实现记录与对比）。

6、**文件编辑增强与 Plan Mode（M3）**：① **diff 展示**——write/edit 结果内嵌轻量行 diff（零依赖公共前缀/后缀算法），TUI 彩色展示（+ 绿 / - 红 / @@ 亮青）、超长截断、随会话落盘；② **`/plan` 先计划后执行**——计划阶段只读调研（写工具/bash 被拦截、零副作用），模型 end_turn 即计划完成，审批 `y` 执行 / `d` 拒绝 / **任意文本修改意见重新生成**，批准后写仍按权限模式确认；③ **全量快照回滚**——每次 write/edit 前把文件完整内容快照落盘（`~/.zhu-code-agent/snapshots/<会话ID>/`），`/undo` 回退最近检查点、`/rewind` 列表回退（统一机制、跨会话），回滚记录写回会话；bash 副作用不追踪；④ **权限模式演进**——normal / acceptEdits（写自动批准）/ bypassPermissions（bash 非危险自动批准），**危险命令强制确认、cwd 外破坏性命令拒绝等 M2 红线不削弱**，仅内存、退出重置。

7、**上下文管理与稳定性（M4）**：① **token 统计与占用**——完成行「本轮 in/out · 累计 · 占用% / 窗口 · cache」；占用基数**协议感知**（真机对比 DeepSeek OpenAI vs `/anthropic` 端点：OpenAI `prompt_tokens` 已含缓存，Anthropic `input_tokens` 不含缓存需 + cacheRead，否则告警/压缩永不触发）；② **双层渐进压缩**——本地瘦身（丢弃低价值 tool 对、截断超长结果）+ LLM 摘要折叠，生成前占用 ≥ 阈值自动触发 + 手动 `/compact` + 熔断；③ **prompt 缓存**——Anthropic `cache_control` 断点 + 双协议命中解析展示；④ **流式中断**——JVM 级 Ctrl+C 取消本轮（半成品回滚、assistant「（已中断）」、二次 Ctrl+C 逃生门），对齐 Codex「再按一次退出」；⑤ **会话 JSONL**——追加写、旧格式迁移、容错/去重；⑥ **max_tokens 可配置化**（模型表 + provider 覆盖）。

**与 Claude Code / Codex 的对比（面试可讲）**：

| 维度 | zhuCodeAgent（现状） | Claude Code / Codex |
|------|---------------------|---------------------|
| 聊天 TUI + 流式 + thinking | ✅ M1 完成 | ✅ |
| Agent 循环 + 工具调用 | ✅ M2：6 内置工具 + 串行循环 + 双协议 | ✅ 并行部分工具 |
| 权限控制 | ✅ M2 只读自动/写类确认/总是允许（内存）+ M3 acceptEdits / bypassPermissions 三档（危险仍强制确认） | ✅ 权限模式（plan/acceptEdits/bypass）+ 会话级记忆 |
| diff 展示 / undo 回滚 | ✅ M3：write/edit 结果内嵌彩色 diff + `/undo` `/rewind` 全量快照回滚（跨会话） | ✅ Claude Code（FileSnapshotService 全量快照）；Codex 靠 git 回滚 |
| 危险命令防护 | ✅ rm/mv/cp 目标限工作区 + rm -rf 强制确认 | ✅ 危险命令拦截 |
| OS 级沙箱 | ❌ 规划（M5+，Seatbelt/bubblewrap） | ✅ macOS Seatbelt / Linux bubblewrap |
| 上下文管理 | ✅ M4：token 统计/占用%（协议感知含缓存）/告警/双层压缩（自动 + /compact）/prompt 缓存/流式中断 | ✅ 四层压缩 + cache-aware（CC）；auto-compact（Codex） |
| MCP / Subagents | ❌ 规划（M5+） | ✅ |
| 技术栈 | Java 21（JLine3 / HttpClient / Jackson） | TypeScript(Node) / Rust |

**取舍说明**：先用"次数上限 + 空闲超时"做循环护栏（对标参考实现的暂停/预算思路的简化版）；安全采用"权限确认 + 应用层路径边界"先行，OS 级沙箱留后续里程碑；工具结果交互式展开留待与 diff 展示一起做。

**一句话亮点**：用 Java 从零实现了 Coding Agent 的核心闭环——流式多后端对话（M1）、工具调用闭环 + 权限 + 安全边界（M2）、文件编辑可见可反悔可先规划（M3：diff / /plan / 快照 undo-rewind / 权限模式三档）、上下文管理与稳定性（M4：协议感知占用 + 双层压缩 + prompt 缓存 + 流式中断 + JSONL），215 测试全绿、真机可用、多轮 review 加固，全程文档化可追溯。
