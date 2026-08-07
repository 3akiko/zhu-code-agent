# zhuCodeAgent —— 命令行 AI 编程助手（个人项目）

**时间**：2026 年 8 月 ~ 至今
**技术栈**：Java 21、Maven、JLine3、JDK HttpClient、Jackson、SnakeYAML、JUnit 5

**项目简介**：从零实现对标 Claude Code / Codex 的命令行 Coding Agent，用于 agent 开发学习与面试。已完成 M1：彩色终端 TUI + SSE 流式对话 + 多轮记忆 + Anthropic/OpenAI 双后端（DeepSeek 的 OpenAI 与 Anthropic 两种兼容格式均可直连）+ Claude extended thinking 灰字展示 + 会话持久化与恢复。全程 Spec 驱动开发（spec → plan → task → checklist 四文档 + 验收报告），并持续记录与 Claude Code/Codex 的对比。

**项目亮点**：

1、**统一 Provider 抽象**：`LlmClient` 接口 + 工厂 + `StreamEvent` 密封事件模型，一套调用方接入 Anthropic / OpenAI 双协议，新增后端零改动调用方；YAML 六字段配置，api_key 三级解析（直接值 / `${ENV_VAR}` / 环境变量回退），密钥不落盘、不落日志；

2、**自研 SSE 流式链路**：轻量 SSE 解析器把双协议流收敛为统一事件（TextDelta / ThinkingDelta / StreamEnd / Error），增量即到即显、不攒批；Anthropic extended thinking 思考过程灰字实时滚动、正式回复正常颜色，signature 多轮回传保证 thinking 后续轮次正确性；

3、**会话持久化与恢复**：每轮回复完成后 JSON 原子写（tmp + rename）落盘，损坏文件自动跳过不崩溃；启动时「新建对话 + 历史会话列表」方向键选择恢复，上下文无缝续聊；会话文件仅存 provider 快照、不含 api_key；

4、**JLine3 + ANSI 256 色终端交互**：行编辑/输入历史/方向键选择器，会话选择 → Provider 选择 → 聊天的状态机；流式期间状态指示与命令体系（/help /clear /new /exit），运行时可保存当前会话并开新会话；

5、**工程化与质量**：57 个单元/集成测试全绿，含 mock HTTP 端到端流式链路（无需真实密钥）；完整文档体系（README / 实现记录与 Claude Code·Codex 对比 / 变更日志 / 验收报告），覆盖安全控制（密钥脱敏、会话文件不含密钥）。
