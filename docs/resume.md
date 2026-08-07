# zhuCodeAgent —— 命令行 AI 编程助手（个人项目）

**时间**：2026 年 8 月 ~ 至今
**技术栈**：Java 21、Maven、JLine3、JDK HttpClient、Jackson、SnakeYAML、JUnit 5

**项目简介**：从零实现对标 Claude Code / Codex 的命令行 Coding Agent，用于 agent 核心机制学习与 agent 开发面试。已完成 M1（聊天 TUI + 会话持久化）与 M2（Agent 循环与 Tool Use）：
- M1：彩色终端 TUI + SSE 流式对话 + 多轮记忆 + Anthropic/OpenAI 双后端（DeepSeek 的 OpenAI 与 Anthropic 两种兼容格式均可直连）+ Claude extended thinking 灰字展示 + 会话持久化与恢复。
- M2：模型工具调用闭环（tool_use / function_call 解析 → 权限确认 → 内置工具执行 → 结果回填 → 循环直到 end_turn）+ 6 个内置工具 + 安全边界与权限模型。

全程 Spec 驱动开发（spec → plan → task → checklist 四文档 + 审批 + 验收报告），持续记录与 Claude Code / Codex 的对比。

**项目亮点**：

1、**统一 Provider 抽象与双协议工具调用**：`LlmClient` 接口 + 工厂 + `StreamEvent` 密封事件模型，一套调用方接入 Anthropic / OpenAI 双协议；M2 把 anthropic `tool_use` 与 openai `function_call` 收敛为统一 `ToolCall` 事件，请求体携带 tools 定义，tool_result 按各自协议回填——调用方（循环/会话/UI）不感知协议差异，新增协议零改动。

2、**自研 Agent 循环（ReAct）**：`AgentRunner` 消息循环——模型输出工具调用 → 权限判定 → 串行执行 → 一次性回填（协议要求同一条 assistant 消息的全部 tool_use 必须一次回填全部 tool_result）→ 循环直到 end_turn；循环护栏（单轮上限可配 + 每步流空闲超时）；执行器抽象为 `ToolExecutor` 接口，串行/并行只在执行器内部差异，为后续并行工具执行预留扩展点。

3、**安全体系（可面试深挖）**：`PathGuard` 路径边界（cwd 为根、realpath 含符号链接校验、禁写 `.git/` 与程序自身目录）；`DangerGuard` 危险命令防护（`rm -rf` 等目标必须位于工作区内，越界立即拒绝不执行，且即使曾「总是允许」也强制确认）；bash 工具无 stdin、30s 超时 kill 进程树、200KB 输出截断；密钥全程不落盘不落日志。

4、**权限确认模型**：只读工具自动放行；写类 / bash 行内确认（允许 / 拒绝 / 总是允许本次）；「总是允许」按「工具 + 参数」精确记忆（bash 按完整命令串），仅内存、退出程序重置、不落盘——兼顾安全与交互效率，对标 Claude Code / Codex 的权限设计。

5、**工程化与质量**：119 个单元/集成测试全绿（含 mock HTTP 端到端：临时工作区执行真实工具 + mock LLM 模拟 tool_use 序列，双协议全覆盖；真机 DeepSeek 冒烟 4 场景）；会话落盘支持内容块（tool_use/tool_result）、单条 64KB 截断标注、旧格式自动迁移；完整文档体系（四文档 + 验收报告 + 实现记录与对比）。

**与 Claude Code / Codex 的对比（面试可讲）**：

| 维度 | zhuCodeAgent（现状） | Claude Code / Codex |
|------|---------------------|---------------------|
| 聊天 TUI + 流式 + thinking | ✅ M1 完成 | ✅ |
| Agent 循环 + 工具调用 | ✅ M2：6 内置工具 + 串行循环 + 双协议 | ✅ 并行部分工具 |
| 权限控制 | ✅ 只读自动 / 写类确认 / 总是允许（内存） | ✅ 权限模式（plan/acceptEdits/bypass）+ 会话级记忆 |
| 危险命令防护 | ✅ rm -rf 路径校验 + 强制确认 | ✅ 危险命令拦截 |
| OS 级沙箱 | ❌ 规划（M5+，Seatbelt/bubblewrap） | ✅ macOS Seatbelt / Linux bubblewrap |
| 上下文管理 / MCP / Subagents | ❌ 规划（M4 / M5+） | ✅ |
| 技术栈 | Java 21（JLine3 / HttpClient / Jackson） | TypeScript(Node) / Rust |

**取舍说明**：先用"次数上限 + 空闲超时"做循环护栏（对标参考实现的暂停/预算思路的简化版）；安全采用"权限确认 + 应用层路径边界"先行，OS 级沙箱留后续里程碑；工具结果交互式展开留待与 diff 展示一起做。

**一句话亮点**：用 Java 从零实现了 Coding Agent 的两大核心——流式多后端对话（M1）与"工具调用闭环 + 权限 + 安全边界"（M2），119 测试全绿、真机可用，全程文档化可追溯。
