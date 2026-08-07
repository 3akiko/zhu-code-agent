状态：approved
# zhuCodeAgent 路线图（Roadmap）

> 最后更新：2026-08-07（M1 规划阶段，含会话持久化调整）
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

| 里程碑 | 名称 | 优先级 | 一句话目标 | 状态 |
|--------|------|--------|-----------|------|
| M1 | 聊天 TUI + 会话持久化 | P0 | 彩色终端 TUI + 流式输出 + 多轮记忆 + 双后端 + extended thinking + 会话落盘/恢复 | ✅ 已完成（2026-08-07） |
| M2 | Agent 循环与 Tool Use | P0 | 模型输出工具调用 → 执行内置工具 → 结果回填循环，含权限确认 | ✅ 已完成（2026-08-08） |
| M3 | 文件编辑增强与 Plan Mode | P0 | diff 展示、/plan 先计划后执行、文件快照回滚、权限模式 | 未开始 |
| M4 | 上下文管理 | P1 | token 统计、上限告警、自动压缩、prompt 缓存 | 未开始 |
| M5+ | 扩展特性 | P2 | 见「扩展清单」 | 未开始 |

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
- **目标**：让文件修改"看得见、可反悔、可先规划"。
- **包含**：`edit_file` 变更 diff 展示；`/plan` 模式（先产出计划，用户批准后才执行）；文件历史快照与回滚（rewind）；权限模式演进（acceptEdits / bypassPermissions 等）。
- **验收入口**：M3 的 `docs/spec.md`。

### M4 上下文管理（P1）
- **包含**：token 用量统计与展示、接近上限告警、上下文自动压缩（compaction，对标 Claude Code 的 /compact）、Anthropic prompt caching。
- **验收入口**：M4 的 `docs/spec.md`。

### M5+ 扩展特性（P2，按需）
- Markdown 富渲染（代码块高亮、表格）。
- 流式中断（Ctrl+C 取消本次生成）。
- `--resume` / `--continue` 快捷参数（直接恢复最近会话，跳过启动选择页）。
- `openai-compat` 协议（与 openai 同构，仅 base_url 不同，扩展成本低；DeepSeek 已可直接用 `protocol: openai`）。
- OpenAI 兼容协议的 `reasoning_content` 解析（deepseek-reasoner 等推理模型思考展示）。
- Subagents / Task（并行子任务）。
- MCP（Model Context Protocol）集成。
- Hooks（生命周期钩子）。
- Skills（可安装技能包）。
- 多模态输入（图片）。
- 非交互模式（`-p "prompt"` 一次性输出）与 remote/Web 模式（参考 mewcode 的 Javalin 远程模式）。
- 团队/多 agent 协作模式。
- 工具循环增强：无进展检测（连续相同工具+相同参数且结果无变化 → 主动提示停止）、Claude Code 式「暂停-继续」、并行工具执行（读类并行/写类串行）。
- OS 级沙箱（macOS Seatbelt / Linux bubblewrap / 容器化），对标 Codex `--sandbox` 三档（readOnly/workspace-write/danger-full-access）与 Claude Code 的沙箱化 Bash。
- /goal 类长任务：跨轮累计 token 预算做护栏（对标 Codex 0.128+ /goal）。

## 5. 特性对比表（随实现更新）

> 现状 = M1 已完成（2026-08-07）。每完成一个里程碑回填一列并标注完成日期。

| 功能维度 | zhuCodeAgent（M1） | zhuCodeAgent（M2，2026-08-08） | Claude Code | Codex CLI |
|----------|---------------------|-------------|-----------|
| 交互界面 | ✅ 已完成（M1）：JLine3+ANSI 彩色 TUI | ✅ 沿用 M1（每 agent 步骤状态行） | Ink(React) 全屏 TUI | 类 TUI + 状态行 |
| 流式输出 | ✅ 已完成（M1）：SSE 增量实时打印 | 有 | 有 |
| 多后端 | ✅ 已完成（M1）：anthropic / openai | Anthropic 为主 | OpenAI 为主 |
| extended thinking | ✅ 已完成（M1）：灰色小字展示 + 多轮回传 | 有（可展开） | 有（reasoning） |
| 工具调用 | 未做 | ✅ 已完成：6 内置工具 + Agent 循环 + 双协议 | 有 | 有 |
| 权限控制 | 未做 | ✅ 部分：只读自动 + 写类/bash 行内确认 + 总是允许（内存）；acceptEdits/bypass 留 M3 | 有（plan/acceptEdits/bypass） | 有（plan/auto） |
| 会话恢复 | ✅ 已完成（M1）：启动选择恢复 | ✅ 工具消息随会话落盘，恢复后循环上下文完整 | 有（--resume/--continue） | 有（--resume/--continue） |
| 上下文管理 | 未做（M4） | 有（auto-compact） | 有（--compact） |
| MCP / Subagents / Hooks | 未做（M5+） | 有 | 部分 |
| 技术栈 | Java 21 | TypeScript/Node | Rust |

## 6. 文档与记录规范

- 每个里程碑生成四份文档并审批：`docs/spec.md`（做什么）→ `docs/plan.md`（怎么做）→ `docs/task.md`（按什么顺序做）→ `docs/checklist.md`（做对了没）。全部批准后才开始写实现代码（HARD GATE）。
- 每份文档第一行状态标记：`状态：draft` / 用户批准后 `状态：approved`；已批准文档修改须走变更控制（从受影响的最上游文档改起并重新审批）。
- **里程碑归档（防止覆盖丢失）**：根目录的 `docs/spec.md` 等四份是「当前里程碑」的工作文档，下一里程碑会重新生成覆盖。因此每个里程碑验收完成后，先把该里程碑已批准的完整四文档归档到 `docs/milestones/mN/`（如 `docs/milestones/m1/`），再开启下一里程碑。
- **持续累积文档（不随里程碑覆盖，一直追加更新）**：
  - `README.md`：项目如何使用（简介、功能列表、安装/构建、配置说明、运行命令、对比链接），每个里程碑完成时更新。
  - `docs/implementation.md`：实现了什么、核心实现思路（架构图/关键类/数据流）、与 Claude Code / Codex 的对比差异、踩坑记录。
  - `CHANGELOG.md`：版本化变更记录（未发布 → 已发布）。
  - **`docs/resume.md`：面试用简历**（项目简介/亮点/技术栈/成果数据/与主流 Coding Agent 对比），每个里程碑完成后同步更新，保证面试时拿到的简历与项目现状一致。
  - 本文件第 5 节对比表。
- **提交流程（重要）**：每个里程碑（或一次开发批）完成后，**先把结果交用户 review（对照 checklist/验收报告）**，用户明确确认后再执行 `git commit`；未经用户确认不提交。变更控制（spec 等已批准文档修改）仍需按前文流程重新审批。
- 安全控制贯穿：危险操作确认、密钥脱敏（含不写入会话文件）、路径检查，并写入各里程碑 spec 的验收标准。

## 7. 参考资源

- Claude Code 分析：https://github.com/liuup/claude-code-analysis
- Claude Code 官方：https://github.com/anthropics/claude-code
- 本地 Java 参考实现：`/Users/huangdazhu/IdeaProjects/mewcode-java`（借鉴模块划分：LlmClient 接口 + StreamEvent 密封接口 + Provider 工厂 + ProviderConfig 六字段 + PROVIDER_SELECT/CHAT/RESUME 状态机；不抄其手写 TUI 框架）
