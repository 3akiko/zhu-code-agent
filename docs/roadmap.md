状态：approved
# zhuCodeAgent 路线图（Roadmap）

> 最后更新：2026-08-13（M5 已完成：客户端并发安全 per-call 状态 + 实例缓存复用 / 并行工具执行（读并行·写串行·保序，虚拟线程）/ Subagents-Task（独立会话·权限继承·护栏·摘要回填·级联中断）；特性对比表新增 M5 列；M8+ 记录「/rewind /undo 回滚记录连续 user」待修项 +「子任务结果可展开全文」候选）
> 本文件是产品级地图：里程碑、优先级、扩展清单、与主流 Coding Agent 的对比追踪。
> 每个里程碑的详细需求/设计/任务/验收分别落在 `docs/spec.md` / `docs/plan.md` / `docs/task.md` / `docs/checklist.md`（四文档流程，见文末「文档与记录规范」）。

## 1. 项目定位与目标

- **一句话**：用 Java 从零实现一个命令行 Coding Agent（`zhuCodeAgent`），对标 Claude Code / Codex。
- **目标**：① 学习 agent 核心机制（流式对话、工具调用、权限控制、上下文管理）；② 为 agent 开发面试提供可讲的完整项目（架构、代码、文档、测试、对比）。
- **参考**：https://github.com/liuup/claude-code-analysis 、https://github.com/anthropics/claude-code 、网上泄漏源码库、本地参考项目 `/Users/huangdazhu/IdeaProjects/mewcode-java`（基于 Claude Code 的 Java 实现，采用其模块划分思想，但不抄其手写 TUI 框架）。

## 2. 总体原则

1. **安全控制优先**：工具执行、文件写入等危险操作默认需用户确认；路径越界检查；api_key 不落日志不打印、不写入会话文件。
2. **文档记录**：每个里程碑走 `spec → plan → task → checklist` 四文档 + 审批；里程碑完成后更新 `docs/implementation.md`（实现了什么、怎么实现的、与 Claude Code/Codex 的对比）和 `CHANGELOG.md`。
3. **主流技术栈**：Java 21 LTS + Maven；终端交互用 JLine3（含 ANSI 256 色渲染），HTTP 用 JDK HttpClient，JSON 用 Jackson，配置用 SnakeYAML（均为 Java 生态主流选择）。
4. **渐进式**：先跑通最小闭环（M1），再逐里程碑叠加核心能力；非核心一律放扩展清单，不提前做。
5. **对比追踪**：每个里程碑完成后更新「特性对比表」，让面试可讲"我做到了什么程度、和 Claude Code/Codex 差在哪、为什么这么取舍"。

## 3. 里程碑总览

  里程碑   名称   优先级   一句话目标   状态  
 -------- ------|--------|-----------|------|
| M1 | 聊天 TUI + 会话持久化 | P0 | 彩色终端 TUI + 流式输出 + 多轮记忆 + 双后端 + extended thinking + 会话落盘/恢复 | ✅ 已完成（2026-08-07） |
| M2 | Agent 循环与 Tool Use | P0 | 模型输出工具调用 → 执行内置工具 → 结果回填循环，含权限确认 | ✅ 已完成（2026-08-08） |
| M3 | 文件编辑增强与 Plan Mode | P0 | diff 展示、/plan 先计划后执行、文件快照回滚、权限模式 | ✅ 已完成（2026-08-09） |
| M4 | 上下文管理与稳定性 | P0 | token 统计/上限告警/自动压缩/prompt 缓存 + 流式中断 + 会话 JSONL + max_tokens 可配置化/优雅关闭 | ✅ 已完成（2026-08-12） |
| M5 | Agent 扩展：并行与 Subagents | P0 | 客户端并发安全重构 → 并行工具执行 → Subagents/Task 子任务 | ✅ 已完成（2026-08-13） |
| M6 | 生态集成：MCP / Hooks / Skills | P1 | MCP 协议接入 + 生命周期 Hooks + 可安装技能包 | 未开始 |
| M7 | 安全纵深与工程化 | P1 | OS 级沙箱 + 权限记忆跨会话落盘 + git 集成 + 结构化日志 | 未开始 |
| M8+ | 扩展特性 | P2 | 见「扩展清单」 | 未开始 |

优先级说明：P0=核心功能（面试必讲、Coding Agent 的骨架），P1=重要但可后置，P2=非核心/按需。

## 4. 里程碑详情

### M1 聊天 TUI + 会话持久化（P0，本次）
- **目标**：用户启动 `zhuCodeAgent` 进入彩色终端 TUI，输入问题，调用所选 provider 的 LLM API，回复经 SSE 流式逐段实时打印；多轮对话，AI 记住之前内容；会话历史落盘，下次启动可恢复继续聊。
- **包含**：JLine3+ANSI 彩色 TUI；provider 启动选择（多 provider 时）；会话启动选择/恢复（有历史会话时显示「新建对话 + 历史会话列表」）；YAML 六字段配置（name/protocol/model/base_url/api_key/thinking）；Provider 抽象统一接口（anthropic / openai 两个实现，SSE 流式）；Claude extended thinking（灰色小字实时展示）；会话 JSON 落盘（每轮回复完成后保存、退出时保存，会话文件不含 api_key）；基础命令 /help /exit /clear；核心逻辑单元测试 + mock HTTP 流式集成测试。
- **不做**：tool use、文件操作、代码编辑、上下文压缩、Markdown 富渲染、流式中断。
- **验收入口**：`docs/spec.md`（M1）。

### M2 Agent 循环与 Tool Use（P0）
- **状态**：✅ 已完成（2026-08-08），四文档归档 `docs/milestones/m2/`，验收报告 `docs/验收报告-M2.md`。
- **目标**：从"纯对话"升级为"会干活的 agent"——模型在回复中声明工具调用，程序执行并把结果回填，模型继续，直到完成。
- **包含**：消息循环（tool_use / function_call 解析 → 执行 → 结果回填）；内置工具集 `read_file / write_file / edit_file / bash / grep / glob`；工具执行前权限确认（允许/拒绝/总是允许本次）；流事件模型扩展出 ToolCall 事件；安全控制：bash 与文件写默认需确认、路径越界检查。
- **不做**：plan mode、diff 展示、undo（留给 M3）。
- **验收入口**：M2 的 `docs/spec.md`（届时新建）。

### M3 文件编辑增强与 Plan Mode（P0）
- **状态**：✅ 已完成（2026-08-09），四文档归档 `docs/milestones/m3/`，验收报告 `docs/验收报告-M3.md`，真机 demo `docs/demo-M3文件编辑与PlanMode.md`（已实测通过）。
- **目标**：让文件修改"看得见、可反悔、可先规划"。
- **包含**：`edit_file` 变更 diff 展示；`/plan` 模式（先产出计划，用户批准后才执行，支持修改意见重新生成）；文件历史快照与回滚（undo/rewind，跨会话）；权限模式演进（acceptEdits / bypassPermissions 三档，安全红线不削弱）。
- **验收入口**：M3 的 `docs/spec.md`。

### M4 上下文管理与稳定性（P0）✅ 已完成（2026-08-12）
- **目标**：让长会话"不崩、不贵、可中断、可脚本化"——上下文窗口是真实 coding agent 的硬瓶颈，也是后面所有功能的地基。
- **包含**：
  - token 用量统计与展示（每轮 in/out + 会话累计 + 上限百分比）
  - 接近上限告警（如 80% 提示，可配）
  - 上下文自动压缩（compaction：摘要化最旧轮次 / 截断 tool_result / 丢弃历史，对标 Claude Code 的 /compact）
  - prompt 缓存（Anthropic cache_control + OpenAI cached_tokens 展示，降本提速）
  - 流式中断（Ctrl+C 取消本次生成，半成品消息回滚）
  - 会话存储 JSONL 化（追加写替代整文件原子写；tool_result 截断策略沿用）
  - 技术债：max_tokens 可配置化（按模型区分上限）；退出时优雅关闭流线程（interrupt + join）
- **不做**：并行工具/Subagents（M5）、MCP/Hooks/Skills（M6）、OS 沙箱（M7）；`-p` 非交互与结构化输出容错后置 M8+（2026-08-10 范围调整）。
- **验收入口**：M4 的 `docs/spec.md`。

### M5 Agent 扩展：并行与 Subagents（P0）✅ 已完成（2026-08-13）
- **目标**：从"单 agent 串行"升级为"多 agent 并行"——技术说服力最强的里程碑，面试重点。
- **包含**：
  - ① 客户端并发安全重构：AnthropicClient / OpenAiClient 的流累积状态改为 per-call 持有者（Subagents 与并行工具执行的**硬前置**，TODO 已记）
  - ② 并行工具执行：读类并行 / 写类串行、结果保序（`ToolExecutor` 新增并行实现，接口不变）
  - ③ Subagents / Task：父 agent 派生子任务（独立会话/权限/护栏上限），结果回填，支持并行子任务
- **不做**：MCP/Hooks/Skills（M6）、OS 沙箱（M7）。
- **验收入口**：M5 的 `docs/spec.md`。

### M6 生态集成：MCP / Hooks / Skills（P1）
- **目标**：接入生态标准与扩展机制，让 agent"可连接、可扩展"。
- **包含**：
  - MCP（Model Context Protocol）：自研轻量 stdio JSON-RPC 客户端，把 MCP server 的工具注册进现有 `Tool` 抽象（零协议改动）
  - Hooks：生命周期事件（PreToolUse / PostToolUse / Stop 等）+ 配置驱动的 hook 执行（通知/命令）
  - Skills：可安装技能包（SKILL.md + 元数据，按需注入 system prompt）
- **验收入口**：M6 的 `docs/spec.md`。

### M7 安全纵深与工程化（P1）
- **目标**：从"应用层守卫"升级为"应用层 + 内核层纵深防御"，并补齐生产工程。
- **包含**：
  - OS 级沙箱：macOS Seatbelt（bash 子进程沙箱化：仅工作区可写、deny 网络），对标 Codex `--sandbox` 三档；与 PathGuard/DangerGuard/PermissionManager 叠加（纵深防御）
  - 权限记忆跨会话落盘：会话级 permissions/settings（按路径/命令持久化授权 + 默认权限模式，落盘脱敏）
  - git 集成：dirty 检测、commit 建议、与 /plan / diff / 回滚协同
  - 结构化日志（`--verbose`，JSON lines，api_key 脱敏）
- **不做**：worktree 隔离（M8+ 可选亮点）。
- **验收入口**：M7 的 `docs/spec.md`。

### M8+ 扩展特性（P2，按需）
- Markdown 富渲染（代码块高亮、表格）。
- `-p` 非交互模式（一次性输出 + 退出码，供 CI / 脚本化；原 M4，2026-08-10 范围调整后置）。
- 结构化输出容错（工具参数 schema 校验 + 重试回填；原 M4，2026-08-10 范围调整后置）。
- `--resume` / `--continue` 快捷参数（直接恢复最近会话，跳过启动选择页）。
- `openai-compat` 协议（与 openai 同构，仅 base_url 不同，扩展成本低；DeepSeek 已可直接用 `protocol: openai`）。
- OpenAI 兼容协议的 `reasoning_content` 解析（deepseek-reasoner 等推理模型思考展示）。
- 多模态输入（图片）。
- remote/Web 模式（参考 mewcode 的 Javalin 远程模式）。
- 团队 / 多 agent 协作模式。
- 工具循环增强：无进展检测（连续相同工具+相同参数且结果无变化 → 主动提示停止）、Claude Code 式「暂停-继续」。
- 工具结果块级渲染 / 交互式展开-收缩（M2/M3 连续延期的项，与 diff 块级渲染一起做）。
- `/goal` 类长任务：跨轮累计 token 预算做护栏（对标 Codex 0.128+ /goal）。
- git worktree 隔离工作区（对标 Codex 的 worktree 模式：临时分支工作区 + 权限/快照按工作区根隔离，安全隔离 + git 深度结合）。
- 长期记忆机制（对标 Claude Code 的 CLAUDE.md + Memory tool / Codex 的 AGENTS.md + Memories）：项目级持久指令文件 + 跨会话摘要沉淀与按需注入（M4 的压缩摘要是现成素材；2026-08-10 S1 讨论后列为候选）。
- 回滚记录消息格式修正（N3 加固）：**`/undo` 与 `/rewind` 共用 `ChatApp.renderRollback`，都会以 user 角色写入「[回滚] …」记录**（`conversation.addUser`）；当前行为：**`/undo` / `/rewind` 只回退文件内容（恢复检查点内容、删除新建文件），对话留痕**——JSONL 会话消息不回退、只追加「[回滚] …」记录，紧接着提问会出现**连续 user 消息**，违反 spec N3，Anthropic 严格端点/代理可能 `400 roles must alternate`（M4 demo 会话 `20260811-234252-a2dc.jsonl` [26][27] 实测，2026-08-12 记录）。方案：① 语义修正——[回滚] 记录**合入下一条真实 user 消息**（或按上一消息角色补位），保证 user/assistant 交替；② 防御兜底——`AnthropicClient.buildMessages` 增加**连续同角色合并**，任何来源的连续消息发请求前归一化为交替格式。
- 子任务结果可展开全文（M5 S1 评估记录，2026-08-12）：Task 子任务完成后默认只回填结构化摘要（状态 + 最终文本摘要截断 + token 用量）；后续演进为父 agent 可请求展开子任务完整 transcript / 最终回复全文（需保留子历史引用 + 二次查询通道 + 展开内容进父上下文的压缩协同；对标 Claude Code 继续已有 subagent 的 SendMessage/resume）。M5 不做，列为候选。

## 5. 特性对比表（随实现更新）

> 现状 = M5 已完成（2026-08-13）。每完成一个里程碑回填一列并标注完成日期。M6–M8+ 为规划目标，见「4. 里程碑详情」。

| 功能维度 | zhuCodeAgent（M1） | zhuCodeAgent（M2，2026-08-08） | zhuCodeAgent（M3，2026-08-09） | zhuCodeAgent（M4，2026-08-12） | zhuCodeAgent（M5，2026-08-13） | Claude Code | Codex CLI |
|----------|---------------------|------------------------------|------------------------------|------------------------------|-----------|------------|
| 交互界面 | ✅ 已完成（M1）：JLine3+ANSI 彩色 TUI | ✅ 沿用 M1（每 agent 步骤状态行） | ✅ 沿用 M2 | ✅ 沿用 M3（完成行统计/告警行，斜杠命令扩充 /compact） | Ink(React) 全屏 TUI | 类 TUI + 状态行 |
| 流式输出 | ✅ 已完成（M1）：SSE 增量实时打印 | ✅ 沿用 M1 | ✅ 沿用 M1 | ✅ 沿用 M1（新增 Ctrl+C 流式中断） | 有 | 有 |
| 多后端 | ✅ 已完成（M1）：anthropic / openai | ✅ 沿用 M1 | ✅ 沿用 M1 | ✅ 沿用 M1（DeepSeek 双格式直连） | Anthropic 为主 | OpenAI 为主 |
| extended thinking | ✅ 已完成（M1）：灰色小字展示 + 多轮回传 | ✅ 沿用 M1 | ✅ 沿用 M1 | ✅ 沿用 M1 | 有（可展开） | 有（reasoning） |
| 工具调用 | 未做 | ✅ 已完成：6 内置工具 + Agent 循环 + 双协议 | ✅ 沿用 M2 | ✅ 沿用 M2 | ✅ 并行执行（读段虚拟线程并行/写·bash 串行/保序；tool + task 归并行段） | 有 | 有 |
| 权限控制 | 未做 | ✅ 部分：只读自动 + 写类/bash 行内确认 + 总是允许（内存） | ✅ 三档模式：normal / acceptEdits / bypassPermissions（危险命令仍强制确认，红线不削弱） | ✅ 沿用 M3 | ✅ 并行确认串行化（全局锁一次一弹窗）+ 子任务权限回主 UI（来源标注）+ 父已批准继承不二次确认 | 有（plan/acceptEdits/bypass） | 有（plan/auto） |
| diff 展示 / undo 回滚 | 未做 | 未做（留 M3） | ✅ diff 内嵌彩色展示 + 全量快照 /undo /rewind（跨会话） | ✅ 沿用 M3 | 有（FileSnapshotService 快照） | diff 高亮；回滚靠 git |
| 会话恢复 | ✅ 已完成（M1）：启动选择恢复 | ✅ 工具消息随会话落盘，恢复后循环上下文完整 | ✅ 沿用 M2（快照按会话隔离） | ✅ JSONL 追加写 + 会话累计随 meta 恢复 | 有（--resume/--continue） | 有（--resume/--continue） |
| 上下文管理 | 未做 | 未做 | 未做（M4 规划） | ✅ M4 已完成：token 统计/占用%（**协议感知含缓存**：Anthropic input+cacheRead、OpenAI prompt_tokens）/上限告警/双层渐进压缩（snip 瘦身 + LLM 摘要折叠）/自动 + 手动 /compact/熔断/prompt 缓存（cache_control 断点 + 双协议命中展示）/流式中断 | ✅ 沿用 M4（子任务摘要经 tool_result 回填进父会话，占用/压缩兼容） | 有：四层渐进压缩（snip → microcompact → context collapse → auto-compact，含 cache-aware 决策）+ /compact + prompt 缓存 | 有：auto-compact 默认开（可配 model_context_window / model_auto_compact_token_limit）+ /compact + 状态行剩余上下文 |
| MCP / Subagents / Hooks / Skills | 未做 | 未做 | 未做 | 未做（M5 Subagents / M6 MCP·Hooks·Skills 规划） | ✅ Subagents：task 工具派生子任务（独立会话/权限继承/三层护栏/工具池裁剪/摘要回填/并行子任务/级联中断）；MCP/Hooks/Skills 未做（M6） | 有（MCP + subagents + hooks + skills） | 有（MCP client + subagents + hooks + skills，2026 起 GA） |
| OS 级沙箱 | 未做 | 未做 | 未做 | 未做（M7 规划：macOS Seatbelt） | 无原生 OS 沙箱（权限确认 + 快照回滚） | 有（read-only / workspace-write / danger-full-access：macOS Seatbelt / Linux Landlock+Bubblewrap） |
| 技术栈 | Java 21 | Java 21（同左） | Java 21（同左） | Java 21（同左） | TypeScript/Node | Rust |

## 6. 文档与记录规范

- 每个里程碑生成四份文档并审批：`docs/spec.md`（做什么）→ `docs/plan.md`（怎么做）→ `docs/task.md`（按什么顺序做）→ `docs/checklist.md`（做对了没）。全部批准后才开始写实现代码（HARD GATE）。
- 每份文档第一行状态标记：`状态：draft` / 用户批准后 `状态：approved`；已批准文档修改须走变更控制（从受影响的最上游文档改起并重新审批）。
- **里程碑归档（防止覆盖丢失）**：根目录的 `docs/spec.md` 等四份是「当前里程碑」的工作文档，下一里程碑会重新生成覆盖。因此每个里程碑验收完成后，先把该里程碑已批准的完整四文档归档到 `docs/milestones/mN/`（如 `docs/milestones/m1/`），再开启下一里程碑。
- **持续累积文档（不随里程碑覆盖，一直追加更新）**：
  - `README.md`：项目如何使用（简介、功能列表、安装/构建、配置说明、运行命令、对比链接），每个里程碑完成时更新。
  - `docs/implementation.md`：实现了什么、核心实现思路（架构图/关键类/数据流）、与 Claude Code / Codex 的对比差异、踩坑记录。
  - `CHANGELOG.md`：版本化变更记录（未发布 → 已发布）。
  - **`docs/resume.md`：面试用简历**（项目简介/亮点/技术栈/成果数据/与主流 Coding Agent 对比），每个里程碑完成后同步更新，保证面试时拿到的简历与项目现状一致，同时也归档到`docs/milestones/mN/`下。
  - 本文件第 5 节对比表。
- **提交流程（重要）**：每个里程碑（或一次开发批）完成后，**先把结果交用户 review（对照 checklist/验收报告）**，用户明确确认后再执行 `git commit`；未经用户确认不提交。变更控制（spec 等已批准文档修改）仍需按前文流程重新审批。
- **里程碑收尾检查清单（容易漏，逐个打勾）**：
  1. 验收报告 `docs/验收报告-MN.md` 写了吗？
  2. 四文档归档 `docs/milestones/mN/`（含 resume 快照）了吗？根目录四文档状态是否都 `approved`？
  3. **README.md 更新了吗？**（状态行、当前功能、目录结构、测试数、相关文档链接——M2 时就漏过目录结构里的 `TurnRunner`，务必检查）
  4. `docs/implementation.md` 的「实现了什么/怎么实现/对比/踩坑」追加了吗？
  5. `CHANGELOG.md` 的 Added/Fixed/Changed 追加了吗？
  6. `docs/roadmap.md` 的里程碑状态、特性对比表、顶部「最后更新」日期改了吗？
  7. `docs/resume.md`（root 活文档）同步了吗？测试数/亮点/对比表是否与代码现状一致？
  8. `docs/TODO.md` 勾掉已完成项、追加新技术债了吗？
  9. 真机 Demo 文档（可选但推荐）写了吗？本地提交后 push 远程了吗？
- 安全控制贯穿：危险操作确认、密钥脱敏（含不写入会话文件）、路径检查，并写入各里程碑 spec 的验收标准。

## 7. 参考资源

- Claude Code 分析：https://github.com/liuup/claude-code-analysis
- Claude Code 官方：https://github.com/anthropics/claude-code
- 本地 Java 参考实现：`/Users/huangdazhu/IdeaProjects/mewcode-java`（借鉴模块划分：LlmClient 接口 + StreamEvent 密封接口 + Provider 工厂 + ProviderConfig 六字段 + PROVIDER_SELECT/CHAT/RESUME 状态机；不抄其手写 TUI 框架）
