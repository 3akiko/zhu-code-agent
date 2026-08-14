# zhuCodeAgent —— 命令行 AI 编程助手（个人项目）

**时间**：2026 年 8 月 ~ 至今
**技术栈**：Java 21、Maven、JLine3、JDK HttpClient、Jackson、SnakeYAML、JUnit 5

**项目简介**：从零实现对标 Claude Code / Codex 的命令行 Coding Agent，用于 agent 核心机制学习与 agent 开发面试。已完成 M1（聊天 TUI + 会话持久化）、M2（Agent 循环与 Tool Use）、M3（文件编辑增强与 Plan Mode）、M4（上下文管理与稳定性）、M5（并行与 Subagents）：
- M1：彩色终端 TUI + SSE 流式对话 + 多轮记忆 + Anthropic/OpenAI 双后端（DeepSeek 的 OpenAI 与 Anthropic 两种兼容格式均可直连）+ Claude extended thinking 灰字展示 + 会话持久化与恢复。
- M2：模型工具调用闭环（tool_use / function_call 解析 → 权限确认 → 内置工具执行 → 结果回填 → 循环直到 end_turn）+ 6 个内置工具 + 安全边界与权限模型。
- M3：让文件修改"看得见、可反悔、可先规划"——write/edit 结果内嵌彩色 diff；`/plan` 先出计划、批准后执行（支持修改意见重新生成）；全量快照 `/undo` `/rewind`（跨会话）；权限模式三档 acceptEdits / bypassPermissions（安全红线不削弱）。
- M4：长会话"不崩、不贵、可中断"——token 统计与占用%（协议感知含缓存）、上限告警、双层渐进压缩（自动 + `/compact`）、prompt 缓存、Ctrl+C 流式中断（二次逃生门）、会话 JSONL 存储、max_tokens 可配置化。
- M5：从"单 agent 串行"升级为"多 agent 并行"——客户端并发安全重构（per-call 状态持有者 + 实例缓存复用）、并行工具执行（读并行·写串行·保序，虚拟线程）、Subagents/Task（父 agent 派独立会话子任务，权限继承、三层护栏、摘要回填、并行子任务、级联中断）。

全程 Spec 驱动开发（spec → plan → task → checklist 四文档 + 审批 + 验收报告），持续记录与 Claude Code / Codex 的对比。

**项目亮点**：

1、**统一 Provider 抽象与双协议工具调用**：`LlmClient` 接口 + 工厂 + `StreamEvent` 密封事件模型，一套调用方接入 Anthropic / OpenAI 双协议；M2 把 anthropic `tool_use` 与 openai `function_call` 收敛为统一 `ToolCall` 事件，请求体携带 tools 定义，tool_result 按各自协议回填——调用方（循环/会话/UI）不感知协议差异，新增协议零改动。

2、**自研 Agent 循环（ReAct）**：`AgentRunner` 消息循环——模型输出工具调用 → 权限判定 → 串行执行 → 一次性回填（协议要求同一条 assistant 消息的全部 tool_use 必须一次回填全部 tool_result）→ 循环直到 end_turn；循环护栏（单轮上限可配 + 每步流空闲超时）；执行器抽象为 `ToolExecutor` 接口，串行/并行只在执行器内部差异，为后续并行工具执行预留扩展点。

3、**安全体系（可面试深挖）**：`PathGuard` 路径边界（cwd 为根、realpath 含符号链接校验、禁写 `.git/` 与程序自身目录）；`DangerGuard` 危险命令防护（**所有文件系统修改命令 rm/rmdir/mv/cp 的目标必须位于工作区内，与 flags 无关**，shell 展开字符如 `~`/`$`/反引号直接拒绝，`rm -rf` 等危险命令即使曾「总是允许」也强制确认）；bash 工具无 stdin、30s 超时 kill 进程树、200KB 输出截断；密钥全程不落盘不落日志。

4、**权限确认模型**：只读工具自动放行；写类 / bash 行内确认（允许 / 拒绝 / 总是允许本次）；「总是允许」按「工具 + 关键参数」记忆（**文件写按路径、bash 按完整命令串**），仅内存、退出程序重置、不落盘——兼顾安全与交互效率，对标 Claude Code / Codex 的权限设计。

5、**工程化与质量**：245 个单元/集成测试全绿（含 mock HTTP/脚本化 LLM 端到端：临时工作区执行真实工具，双协议全覆盖；真机 DeepSeek 冒烟 M2 4 场景 + M3 demo 全流程 + M4 demo ①–⑤ + M5 demo 完整流程）；**多轮安全/正确性 review（含 review-agent 预审）修复多类问题**（tool_use 参数往返丢失、rm shell 展开绕过、权限记忆粒度、JSONL 收缩重写、中断状态可见性、占用口径、嵌套深度护栏、并行 bash 进程泄漏、真机 SIGINT 等）；会话落盘支持内容块（tool_use/tool_result）、单条 64KB 截断标注、旧格式自动迁移、JSONL 追加写；完整文档体系（四文档 + 验收报告 + 实现记录与对比）。

6、**文件编辑增强与 Plan Mode（M3）**：① **diff 展示**——write/edit 结果内嵌轻量行 diff（零依赖公共前缀/后缀算法），TUI 彩色展示（+ 绿 / - 红 / @@ 亮青）、超长截断、随会话落盘；② **`/plan` 先计划后执行**——计划阶段只读调研（写工具/bash 被拦截、零副作用），模型 end_turn 即计划完成，审批 `y` 执行 / `d` 拒绝 / **任意文本修改意见重新生成**，批准后写仍按权限模式确认；③ **全量快照回滚**——每次 write/edit 前把文件完整内容快照落盘（`~/.zhu-code-agent/snapshots/<会话ID>/`），`/undo` 回退最近检查点、`/rewind` 列表回退（统一机制、跨会话），回滚记录写回会话；bash 副作用不追踪；④ **权限模式演进**——normal / acceptEdits（写自动批准）/ bypassPermissions（bash 非危险自动批准），**危险命令强制确认、cwd 外破坏性命令拒绝等 M2 红线不削弱**，仅内存、退出重置。

7、**上下文管理与稳定性（M4）**：① **token 统计与占用**——完成行「本轮 in/out · 累计 · 占用% / 窗口 · cache」；占用基数**协议感知**（真机对比 DeepSeek OpenAI vs `/anthropic` 端点：OpenAI `prompt_tokens` 已含缓存，Anthropic `input_tokens` 不含缓存需 + cacheRead，否则告警/压缩永不触发）；② **双层渐进压缩**——本地瘦身（丢弃低价值 tool 对、截断超长结果）+ LLM 摘要折叠，生成前占用 ≥ 阈值自动触发 + 手动 `/compact` + 熔断；③ **prompt 缓存**——Anthropic `cache_control` 断点 + 双协议命中解析展示；④ **流式中断**——JVM 级 Ctrl+C 取消本轮（半成品回滚、assistant「（已中断）」、二次 Ctrl+C 逃生门），对齐 Codex「再按一次退出」；⑤ **会话 JSONL**——追加写、旧格式迁移、容错/去重；⑥ **max_tokens 可配置化**（模型表 + provider 覆盖）。

8、**并行与 Subagents（M5，面试重点）**：① **客户端并发安全重构**——流累积状态从实例字段迁入 **per-call `StreamState`**（一个流一个状态对象，worker 写/cancel 闭包读），同一客户端实例可并发 `stream()` 且工厂缓存复用（主会话与子任务共享实例），与 Claude（AsyncLocalStorage）/ Codex（Rust 所有权）一致；② **并行工具执行**——`ToolExecutor` 接口不变，新增并行实现：只读工具 + task 段内 **Java 21 虚拟线程**并行、write/bash 串行、**结果严格保序**（Anthropic 一次性按序回填 tool_result 的硬要求）、单失败隔离、Ctrl+C 中断在途段；「执行并行、汇报串行、细节折叠」的三层输出收敛（调度线程串行 UI 回调 + 终端输出锁 + 子任务静默折叠单行）；③ **Subagents / Task**——父 agent 通过 `task` 工具自主派生子任务（对标 Claude Code AgentTool / Codex spawn_agent）：独立会话隔离历史、权限继承（父已批准自动放行、未批准回主 UI 统一确认 + 来源标注 + 全局串行锁）、三层护栏（嵌套深度封顶 + 工具池裁剪 + 并行/步数上限）、结构化摘要回填（状态 + 摘要截断 + token，守 user/assistant 交替纪律、双协议兼容）、并行子任务、Ctrl+C 级联中断（`SubagentCoordinator` 统一取消在途子任务）；深度用 `AgentDepth` 显式传播解决虚拟线程 ThreadLocal 丢失。

**与 Claude Code / Codex 的对比（面试可讲）**：

| 维度 | zhuCodeAgent（现状） | Claude Code / Codex |
|------|---------------------|---------------------|
| 聊天 TUI + 流式 + thinking | ✅ M1 完成 | ✅ |
| Agent 循环 + 工具调用 | ✅ M2：7 内置工具 + 双协议；M5 并行执行（读并行/写串行/保序） | ✅ 并行部分工具 |
| 权限控制 | ✅ M2 只读自动/写类确认/总是允许（内存）+ M3 acceptEdits / bypassPermissions 三档（危险仍强制确认） | ✅ 权限模式（plan/acceptEdits/bypass）+ 会话级记忆 |
| diff 展示 / undo 回滚 | ✅ M3：write/edit 结果内嵌彩色 diff + `/undo` `/rewind` 全量快照回滚（跨会话） | ✅ Claude Code（FileSnapshotService 全量快照）；Codex 靠 git 回滚 |
| 危险命令防护 | ✅ rm/mv/cp 目标限工作区 + rm -rf 强制确认 | ✅ 危险命令拦截 |
| OS 级沙箱 | ❌ 规划（M7，Seatbelt/bubblewrap） | ✅ macOS Seatbelt / Linux bubblewrap |
| 上下文管理 | ✅ M4：token 统计/占用%（协议感知含缓存）/告警/双层压缩（自动 + /compact）/prompt 缓存/流式中断 | ✅ 四层压缩 + cache-aware（CC）；auto-compact（Codex） |
| MCP / Subagents | ✅ Subagents（M5）：task 工具派独立会话子任务 + 并行 + 护栏 + 摘要回填 + 级联中断；MCP 未做（M6 规划） | ✅ |
| 技术栈 | Java 21（JLine3 / HttpClient / Jackson） | TypeScript(Node) / Rust |

**取舍说明**：先用"次数上限 + 空闲超时"做循环护栏（对标参考实现的暂停/预算思路的简化版）；安全采用"权限确认 + 应用层路径边界"先行，OS 级沙箱留后续里程碑；工具结果交互式展开留待与 diff 展示一起做。

**一句话亮点**：用 Java 从零实现了 Coding Agent 的核心闭环——流式多后端对话（M1）、工具调用闭环 + 权限 + 安全边界（M2）、文件编辑可见可反悔可先规划（M3）、上下文管理与稳定性（M4）、**从单 agent 升级为多 agent 并行（M5：per-call 并发安全 + 虚拟线程并行工具 + Subagents/Task）**，245 测试全绿、真机可用、多轮 review 加固，全程文档化可追溯。

---

## 面试问答（6 问）

> 围绕项目整理的通用面试问答（口语化参考答案），与 Notion「📔 简历分析」页同步。

### 面试 Q1：你在项目中遇到的最大的难点是什么？你是怎么一步一步解决的？

**最大难点：把单 agent 升级为多 agent 并行（task 子任务 + 并行工具）。** 表面上看只是加一个 task 工具派生子任务，但真正落地时，四个问题会同时压过来：**数据不串、输出不乱、权限不重、能停得下来**——任何一个没处理好，demo 就会出诡异 bug。

**① 数据不串（并发安全，硬前置）**
最底层的坑：`AnthropicClient/OpenAiClient` 的流式累积状态（inputTokens、thinkingAccum 等）原来是实例字段，同一实例并发开多个流就有数据竞争。我先做重构：把实例字段全部挪到 per-call 状态对象 `StreamState`——一个流一个状态，客户端实例变成"无共享可变状态"，同一实例可以并发 stream()、可以复用。关键流程是**先写复现测试**：并发跑 N 个流，断言各自的 token 统计、thinking 累积不串，先跑红 → 修复 → 跑绿，用测试把并发安全钉死。

**② 输出不乱（并行打印）**
并行之后最直观的问题是终端输出互相打断。我做了三层收敛：渲染入口唯一（调度线程串行回调 UI）、`TerminalUi` 内部输出锁、子任务输出折叠成一行静默摘要、完成时统一回填。父任务和子任务同时在跑，终端也不会花屏、不会错位。

**③ 权限不重（并行权限控制）**
权限容易想复杂。我的设计：`PermissionManager` 统一判定；只读工具并行段天然不弹窗；写类工具在并行段不并行、逐个串行执行；子任务需要权限时回主 UI 弹窗并标注来源（子任务#N），用一把全局 ReentrantLock 保证同一时刻只有一个弹窗在等输入，避免两个线程抢读一个输入；子任务继承父任务的权限模式与护栏，父已批准的权限不重复审批。

**④ 能停得下来（级联中断）**
Ctrl+C 不能只停父任务、子任务还在后台跑。macOS 的坑：JLine 受限终端（DumbTerminal）不注册信号，所以改用 JVM 级 `Signals.register` 接 SIGINT；Apple Silicon 弱内存模型下信号线程写、主线程读不可见，用 volatile + AtomicBoolean；中断时经 `SubagentCoordinator` 级联取消——父任务一中断，所有在途子任务一起 cancel + join；再按一次 Ctrl+C 是逃生门强制退出；JLine readLine 接管 SIGINT 后还要 rearm 信号。

**⑤ 两个隐藏坑**
- ThreadLocal 跨虚拟线程丢失：agent 深度（AgentDepth）用 ThreadLocal 存，虚拟线程切换下会丢，后来改成显式传播；
- BashTool 单进程引用被并发覆盖：并行下多个工具同时跑，共用一个引用会互相覆盖，改成并发安全集合。

**方法论**：每个问题都走"复现测试跑红 → 修复 → 跑绿 → 全量回归"，最后 245 个测试全绿，再用真机 DeepSeek（OpenAI / Anthropic 兼容双协议）跑通 demo 佐证。

### 面试 Q2：为什么要做这个项目，为什么不用现有的框架？

**为什么做这个项目（动机三层）：**
- 想真正搞懂 agent 核心机制——流式对话、工具调用、权限控制、上下文管理、多 agent 协作，这些是 LLM 应用的主干，调 API 套壳学不到底层；
- 准备一个"架构 + 代码 + 文档 + 测试 + 对比"都完整的作品锻炼自己的架构能力，每一层都能讲清"为什么这么设计"；
- 验证命题：用 Java 从零能不能做出可用的命令行 coding agent（对标 Claude Code / Codex）。

**为什么不用 LangChain / LangGraph 当脚手架：**
- **学习目的决定**：用框架等于把协议适配、循环控制、工具编排、上下文管理这些核心难点全交给框架——什么都学不到，深度也无从谈起，**框架藏起来的恰好是难点本身**；
- **抽象过多、不可控**：LangChain 有 Model→Prompt→Chain→Agent→Memory→Callback 多层封装，出问题要追好几层；coding agent 需要精细控制（流式、权限、中断、并行、回填顺序），框架抽象反而碍事；
- **协议不透明**：LangChain 默认把模型收敛成"prompt 进、文本出"，工具调用是后补的补丁式支持，对 Anthropic `tool_use` / OpenAI `function_call` 双协议流式 + thinking 支持不透明，做不了"协议感知占用"这类精细优化；
- **LangGraph 定位不符**：LangGraph 是图编排（节点/边/状态机），适合复杂多步工作流；coding agent 的核心是一个 ReAct 循环 + 工具执行 + 上下文管理，套图框架是杀鸡用牛刀，还会把中断、并行、回填纪律这些横切控制散到图结构里，全局级联取消这类控制不好做；
- **自研的工程收益**：每层都透明——`LlmClient`/`StreamEvent`/`ToolExecutor` 是自己的抽象，新增协议零改动、并行只换执行器实现（M5 就是这样加的）；
- **Java 生态没有等价物**：LangChain/LangGraph 主要在 Python/TS；Java 生态（JLine/Jackson/虚拟线程）成熟，但没有同量级的 agent 脚手架，从零写是合理选择（Spring AI 偏 RAG/对话，不是 coding agent 编排层）。

**一句话总结**：不用框架不是"框架不好"，是"目标不同"——我要的是理解与可控，框架给的是效率与抽象；coding agent 的难点恰好藏在框架替你解决的那部分里。

### 面试 Q3：这个项目最大的亮点是什么，你是怎么做的？

**参考答案**（选 2-3 个展开）：
- **协议无关双后端抽象**：`StreamEvent` 密封事件模型把 anthropic `tool_use` / openai `function_call`、thinking 差异收敛成统一事件，调用方（循环/会话/UI）零感知；加协议 = 加一个客户端类 + 工厂分支。做法：先定统一事件模型，再写两个翻译器，用双协议端到端测试钉住行为一致。
- **per-call 并发安全设计（M5 硬前置）**：流累积状态从实例字段迁到 `StreamState`，一个流一个状态、客户端实例无共享可变状态可并发复用——这是支撑"并行工具 + 多子任务"的地基。做法：先写并发复现测试跑红 → 重构 → 跑绿 → 全量回归。
- **上下文管理与稳定性**：prompt 缓存（Anthropic `cache_control` + OpenAI cached_tokens 命中）+ 双层渐进压缩（本地瘦身 + LLM 摘要折叠）+ 协议感知占用（真机对比发现 DeepSeek `/anthropic` 端点 `input_tokens` 不含缓存，占用须按协议归一，否则告警/压缩永不触发）；Ctrl+C 中断演进（受限终端信号注册、弱内存模型可见性、二次逃生门、M5 级联取消）。
- **工程方法**：全程 Spec 驱动（spec → plan → task → checklist）+ 复现测试先行 + review-agent 预审，245 测试全绿、真机 DeepSeek 双协议验证。

### 面试 Q4：对比 claude 和 codex，你有什么优秀的改进？

诚实说，多数能力是在对齐 Claude Code / Codex，真正算得上"差异化/改进"的是：
- **一套代码双协议**：Claude 只有自家协议、Codex 走自家 API；我用统一 `StreamEvent`/`ToolCall` 抽象同时接 Anthropic + OpenAI 双协议，DeepSeek 两种兼容格式都能直连，上层零感知、换后端零改动；
- **权限记忆更精确、更保守**：文件写按路径记忆、bash 按完整命令串精确记忆（Claude 的权限记忆更宽泛，相似命令容易误放行）；「总是允许」仅内存、退出即重置、不落盘；
- **危险命令红线不可绕过**：即使某条命令被「总是允许」过，`rm -rf` 等危险命令仍强制确认；bash 目标必须落在工作区内、shell 展开字符直接拒绝；
- **跨会话可回滚**：全量快照 `/undo` `/rewind` 跨会话有效（重开还能反悔）；实现零依赖、比 Claude 的快照体系更简单直接；
- **Java 21 虚拟线程并行**：并行工具执行用虚拟线程（每任务一线程、开销低）+ per-call 状态对象保证客户端并发安全——对应 Claude 的 AsyncLocalStorage / Codex 的 Rust 所有权，在 Java 生态里这是正确的落法；
- **级联中断体验**：父 Ctrl+C 连带取消所有在途子任务 + 二次 Ctrl+C 逃生门；JLine 受限终端（DumbTerminal 不注册信号、Apple Silicon 弱内存模型）的坑都踩过并解决。

### 面试 Q5：这个项目还有什么不足，是需要改进的？

思路：主动讲已知边界，每个都能接"为什么没做 + 什么时候做 + 怎么做"：
- **OS 级沙箱缺失**：安全目前是应用层守卫（PathGuard / DangerGuard / 权限确认），没有内核级隔离；Claude Code 用 macOS Seatbelt、Codex 用 Landlock/bubblewrap。规划 M7，用沙箱化子进程做纵深防御；
- **非交互模式（`-p`）缺失**——先解释它是什么：不进入 TUI、不逐权限弹窗，一条命令直接给模型一个任务（如 `zhu-code-agent -p "重构 xxx 模块"`），agent 跑完一次性输出结果、按成功/失败返回退出码，供 CI / 脚本 / IDE 集成调用（对标 Codex CLI 的 `codex exec`、Claude Code 的 `claude -p`）。我目前只有交互式 TUI，执行中必须有人盯着弹窗按 a/d/s，无法无人值守。规划 M8+：一次性执行 + 结构化输出（JSON 摘要）+ 退出码，权限改为预置 allowlist / 默认拒绝策略代替弹窗；
- **MCP / Hooks / Skills 生态未接**：只有内置 7 个工具，不能接外部工具生态（Claude Code 的 MCP、Codex 的 hooks）。规划 M6；
- **工具参数结构化校验缺失**：模型可能生成非法参数，目前靠执行时报错回填自愈，没有 schema 校验 + 重试（M8+）。

### 面试 Q6：做了这个项目对你有什么启发？如果重新再做一遍你会有什么改进和优化？

**启发：**
- 对 Agent 的理解从"调 API"变成"系统设计"：核心不是模型有多强，而是循环控制、状态管理、安全边界、上下文管理这些工程问题；模型负责"决策"，剩下的"执行可靠"全靠工程。
- 测试是重构的底气：M5 的并发安全重构如果没有复现测试，根本不敢动；"先写失败测试再修复"能大幅降低改动的心理和实际风险。
- 协议差异比想象中多：双协议实现让我体会到"统一抽象"的价值，也体会到真机验证的重要性——很多问题 mock 测不出来（如 DeepSeek 的 cache token 口径）。
- 主流实现值得先调研再动手：Claude Code / Codex 的很多机制（权限模式、快照回滚、压缩、中断、subagent）事后看都是先例，先对齐主流设计能少走弯路。

**如果重做一遍会改进：**
- **抽象先行的时机**：M1 就该把 per-call 状态 / `LlmStream` 这类抽象定好，M5 就不用大规模重构（成本最低的时机是第一次写流式的时候）。
- **并发与隔离更早规划**：从 M2 起就按"可能并行"的假设写客户端和工具（无共享可变状态、并发安全集合），而不是 M5 再统一改。
- **沙箱更早做**：OS 级沙箱（M7 规划）其实应该更靠前，应用层守卫只是过渡；重做会在架构里预留进程隔离边界。
- **生态接入提前**：MCP / Hooks 这类扩展点（M6 规划）如果 M1 就留好接口（如 ToolRegistry 的外部注册），后面接入成本更低。
- **更多参考主流实现**：开发前系统性地把 Claude Code / Codex 的机制（权限、压缩、subagent、恢复）读透、形成对照表再动手，避免"重新发明轮子"。

### 面试补充：记忆模块与上下文管理（口语化）

**记忆，一句话：一条对话就是内存里一份消息列表，每轮结束都存到磁盘，重开能续上。**

1. **内容块消息**：内存里消息不是纯文本，是文本块 / 工具调用块 / 工具结果块——同时接 Anthropic 和 OpenAI 两种协议，内存统一成块结构、发请求时各自翻译，上层不用感知协议差异。
2. **落盘**：每会话一个 JSONL 文件，一行一条消息追加写 + 一行元数据（标题 / 模型 / 累计 token）；崩了已写的都在，坏行跳过，重开读回续聊。
3. **文件级记忆**：写文件前全量快照，`/undo` `/rewind` 跨会话可回滚。
4. **子任务记忆隔离（M5）**：子任务独立会话、看不到父对话，跑完只回填摘要，父上下文不被撑爆。

**上下文管理，一句话：对话越长越贵、越容易爆，核心三件事——数清楚、省着用、压缩。**

1. **数清楚（协议感知占用）**：每次请求拿真实 usage 并按协议算占用。坑：Anthropic 的 `input_tokens` 不含缓存命中、OpenAI 的 `prompt_tokens` 含——不区分，DeepSeek 的 Anthropic 兼容端点占用永远显示很低、自动压缩永不触发。所以 Anthropic 口径 = input + cacheRead，OpenAI 直接用 prompt_tokens。
2. **省着用（prompt 缓存）**：system 和工具定义打 `cache_control` 断点，每次请求带同样前缀，命中率高、省钱提速；OpenAI 读 cached_tokens 看命中。
3. **压缩（双层渐进）**：第一层免费——把"模型发了工具调用、但结果全空 / 被拒绝"的整对删掉（必须整对删，协议要求工具调用和结果成对交替；删前确认无一条有价值结果），超长结果截断；第二层才花钱——最旧 N 轮折叠成带【上下文已压缩】的摘要，合并进最近一条真实 user 消息（避免连续 user，双协议交替纪律）。占用到 90% 自动压缩，也可手动 `/compact`；连续 3 次失败熔断。

**追问：中断了程序，怎么保证对话不丢？**
答：原则是"每轮结束必落盘"。普通完成、Ctrl+C 中断一轮（半成品不写回 + 补"（已中断）"标记）、二次 Ctrl+C 逃生门退出、Ctrl+D、`/exit` 都先落盘再走。强杀（kill -9）无兜底钩子，最多丢当前轮增量，已落盘历史不损坏（JSONL 追加写、坏行跳过、meta last-wins）。

**追问：snip 怎么判断"没价值"？**
答：查 tool_result 输出：为空，或含"已拒绝 / 权限禁止"（用户拒绝、权限禁止、危险命令被拦都算）。只要一对里有一个结果有内容，整对保留，宁保守勿误删。
