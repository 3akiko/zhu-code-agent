# zhuCodeAgent

用 Java 从零实现的一个命令行 Coding Agent（对标 Claude Code / Codex），用于学习 agent 核心机制与 agent 开发面试。

> **状态：M4 已完成**（上下文管理与稳定性：token 统计/占用告警/自动压缩/prompt 缓存/流式中断/会话 JSONL/max_tokens 可配置化，2026-08-12）。里程碑规划见 [docs/roadmap.md](docs/roadmap.md)。

## 当前功能

- 彩色终端 TUI（JLine3 + ANSI 256 色：用户青色 / 思考灰色 / 错误红色 / 状态绿色）
- 启动流程：有历史会话先显示「新建对话 + 历史会话列表」，多 provider 显示选择列表，单 provider 直进聊天
- 流式输出：SSE 增量即到即显，不等待完整响应
- 多轮对话记忆：历史随请求回传，模型可引用之前内容
- 会话持久化与恢复：每轮回复完成后自动落盘到 `~/.zhu-code-agent/sessions/`，启动可选恢复（**不含 api_key**）
- 双后端：Anthropic Claude（含 extended thinking，思考灰字实时展示、正文正常颜色、signature 多轮回传）/ OpenAI（**DeepSeek 等 OpenAI 兼容服务可直接用：`protocol: openai` + 自定义 `base_url`**）
- 基础命令：`/help` `/clear` `/new`（保存当前并开新会话）`/permissions`（查看/切换权限模式、查看/重置「总是允许」清单）`/exit`（退出前保存会话）
- 统一 Provider 抽象：新增后端只需新增一个实现类 + 工厂分支，调用方不变
- **Agent 循环（ReAct）**：模型输出工具调用 → 权限确认 → 执行 → 结果回填 → 循环直到 end_turn；同一消息多个工具调用串行执行、一次性回填
- **内置 6 工具**：`read_file`（支持 offset/limit 行范围）/ `write_file` / `edit_file`（精确字符串替换，唯一匹配）/ `bash`（cwd、无 stdin、30s 超时、200KB 输出截断）/ `grep` / `glob`
- **权限确认**：只读工具自动放行；写类/bash 行内确认（允许 a / 拒绝 d / 总是允许本次 s）；「总是允许」按工具+参数精确记忆、程序运行内有效、不落盘、退出重置；`/permissions` 查看与重置
- **安全边界**：路径以工作区为根（realpath 含符号链接校验），越界/写 `.git/` 与 `~/.zhu-code-agent/` 拒绝；`rm -rf` 等危险命令即使曾「总是允许」也强制确认，且目标必须位于工作区内
- **循环护栏**：单轮工具调用上限（`tool.max_calls_per_turn`，默认 60）+ 每步流空闲超时 120s + 结果预览行数可配（`ui.tool_preview_lines`，默认 5）
- **diff 展示（M3）**：`edit_file` / `write_file`（覆写）结果内嵌变更 diff（`+` 绿 / `-` 红 / `@@` 亮青），TUI 完整展示、超长截断（`ui.diff_max_lines`，默认 200）、随会话落盘恢复可见
- **`/plan` 先计划后执行（M3）**：计划阶段只读调研（write/edit/bash 被拦截、零副作用）→ 模型出计划 → `y` 批准执行（写仍按权限模式确认）/ `d` 拒绝 / **输入任意文本作为修改意见重新生成计划**；单轮闭环、无模式状态机
- **快照回滚（M3）**：每次 write/edit 前把文件完整内容快照落盘（`~/.zhu-code-agent/snapshots/<会话ID>/`）；`/undo` 撤销最近一次写、`/rewind` 列表选择回退（统一机制、**跨会话有效**）；回滚记录写回会话；bash 副作用不追踪；>10MB 文件跳过快照
- **权限模式三档（M3）**：`/permissions normal|acceptEdits|bypassPermissions`——acceptEdits 写文件自动批准（bash 仍确认）、bypass bash 非危险命令也自动批准；**危险命令强制确认、cwd 外破坏性命令拒绝等安全红线不削弱**；仅内存、退出重置

- **token 统计与占用（M4）**：完成行展示「本轮 in/out · 会话累计 · 占用% / 窗口 · cache read/created」；占用基数**协议感知**（Anthropic `input+cacheRead`、OpenAI `prompt_tokens` 已含缓存），对照样例见 [DeepSeek 双协议对比](docs/DeepSeek-OpenAI-vs-Anthropic/README.md)
- **上下文占用告警（M4）**：占用 ≥ `context.alert_threshold`（默认 0.8）时输出「⚠ 上下文已达 P%（阈值 T%）」提醒
- **上下文自动压缩（M4）**：双层渐进——本地瘦身（丢弃空/被拒低价值 tool 对、截断超长 tool_result）+ LLM 摘要折叠（最旧 N 轮折叠为「【上下文已压缩】」user 消息）；生成前占用 ≥ `compact_threshold`（默认 0.9）自动触发，或随时手动 `/compact`；连续 3 次失败熔断（自动停用、手动仍可用）
- **prompt 缓存（M4）**：Anthropic 请求 system/工具定义打 `cache_control` 断点（`prompt_cache: false` 可关），双协议缓存命中解析并在完成行展示（降本提速）
- **流式中断（M4）**：生成/工具执行中 **Ctrl+C** 取消本轮（半成品回滚、写 assistant「（已中断）」、无悬空 tool_use），**单次不退出**；一轮内 1.5s 连续两次 Ctrl+C = 逃生门，恢复默认 SIGINT 优雅退出（对齐 Codex「再按一次退出」）
- **会话存储 JSONL（M4）**：追加写（O(1)），旧 `.json` 自动迁移，损坏行容错、历史收缩整文件重写、列表去重；会话累计随 meta 行落盘恢复
- **max_tokens 可配置化（M4）**：内置模型表（deepseek-v4-flash/pro 1M 窗口、thinking 64000 / plain 8192，opus 32000），provider 可 `context_window` / `max_tokens` 覆盖

## 构建与运行

要求：JDK 21+，Maven 3.9+。

```bash
# 1) 配置（首次）
cp config.example.yml ~/.zhu-code-agent/config.yml
#    编辑 ~/.zhu-code-agent/config.yml：六字段 name/protocol/model/base_url/api_key/thinking
#    api_key 支持直接值 / ${ENV_VAR} 引用 / 留空回退到 ANTHROPIC_API_KEY 或 OPENAI_API_KEY

# 2) 开发运行
mvn exec:java

# 3) 打包与运行
mvn -q package
java -jar target/zhu-code-agent.jar [--config <path>]
```

> JDK 24+ 运行时会看到 JLine 的 native-access 警告（无害），可用
> `java --enable-native-access=ALL-UNNAMED -jar target/zhu-code-agent.jar` 消除。

### DeepSeek 快速开始（OpenAI 兼容）

```bash
cp config.example.yml ~/.zhu-code-agent/config.yml   # 含 deepseek 示例
java -jar target/zhu-code-agent.jar                  # 直接进入聊天
```

- 模型：`deepseek-chat`（通用对话）/ `deepseek-reasoner`（思考模式，OpenAI 格式下思考内容 M1 暂不展示）。
- **想要思考灰字展示：用 DeepSeek 的 Anthropic 兼容格式**（官方支持，无需改代码）：
  ```yaml
  - name: deepseek-anthropic
    protocol: anthropic
    model: deepseek-v4-pro
    base_url: https://api.deepseek.com/anthropic
    api_key: ${DEEPSEEK_API_KEY}
    thinking: true
  ```
  思考过程会像 Claude 一样灰色实时滚动，正文正常颜色（2026-08-07 真实 API 验证通过）。
- Anthropic 暂无密钥可先不配置 claude 项；拿到密钥后补上 `api_key: ${ANTHROPIC_API_KEY}` 即可。

## 使用示例

```
$ java -jar target/zhu-code-agent.jar
zhuCodeAgent v0.2.0 —— 命令行 Coding Agent
Provider: claude · anthropic · claude-sonnet-4-5　输入 /help 查看帮助
> 用一句话解释什么是 JVM
[思考灰字…] [正文正常色流式输出…]
── 完成（end_turn · in 20 / out 45 tokens）
> /exit
```

## 配置说明

配置文件默认路径 `~/.zhu-code-agent/config.yml`，可用 `--config <path>` 或环境变量 `ZHU_CODE_AGENT_CONFIG` 覆盖。结构见 [config.example.yml](config.example.yml)：

```yaml
providers:
  - name: claude            # 供应商标识名
    protocol: anthropic     # anthropic | openai
    model: claude-sonnet-4-5
    base_url: https://api.anthropic.com   # 留空按协议取默认
    api_key: ${ANTHROPIC_API_KEY}         # 直接值 / ${ENV_VAR} / 空则回退环境变量
    thinking: true                        # 是否启用 extended thinking（可选，默认 false）
    context_window: 200000              # M4：可选，覆盖模型表上下文窗口（占用%分母）
    max_tokens: 64000                   # M4：可选，覆盖模型表输出上限
    prompt_cache: true                   # M4：Anthropic cache_control 断点（默认 true；端点不识别可设 false）

# M4：上下文管理（默认值见 config.example.yml）
context:
  alert_threshold: 0.8    # 占用告警阈值
  compact_threshold: 0.9  # 自动压缩触发阈值
  compact_target: 0.6     # 压缩目标水位
  keep_recent_turns: 8    # 压缩折叠后保留的最近轮数
```

## 目录结构

```
src/main/java/com/zhubao/
├── Main.java              # 入口：--config 解析、退出码
├── config/                # YAML 配置：providers 六字段 + tool/ui 配置 + api_key 三级解析
├── conversation/          # 对话历史：Message 内容块(text/tool_use/tool_result) / Conversation / Role
├── llm/                   # LlmClient 接口 + Anthropic/OpenAI 实现 + SseParser + StreamEvent
├── agent/                 # AgentRunner 消息循环（普通 run / 计划 runPlan / 批准后执行 runExecution）
├── tool/                  # 工具抽象 + 6 内置工具 + PathGuard/DangerGuard + SerialToolExecutor/PlanModeExecutor
├── permission/            # 权限判定 + 三档模式（normal / acceptEdits / bypassPermissions）
├── diff/                  # DiffGenerator（write/edit 结果内嵌轻量行 diff）
├── history/               # FileHistory 检查点快照 + /undo /rewind 回滚
├── context/               # M4：ContextCompactor 双层压缩（snip 瘦身 + LLM 摘要折叠）
├── session/               # 会话落盘/恢复：Session / SessionStore（JSONL 追加写，不含 api_key）
└── tui/                   # JLine3 终端 + Ansi 颜色 + JLinePicker + ChatApp + SlashCommands + TurnInterruptController
```

## 测试

```bash
mvn test    # 单元测试 + mock HTTP 集成测试（不依赖真实密钥）
```

覆盖：配置解析、SSE 解析、双协议流式客户端、thinking 多轮回传、会话存取、端到端流式链路、6 工具与路径/危险命令守卫、权限判定与三档模式、diff 生成、快照回滚（undo/rewind 跨会话）、/plan 计划循环、mock LLM 端到端（M2 工具闭环 + M3 计划批准/拒绝/权限模式）、**M4 上下文管理（token 统计/占用协议感知/告警/压缩/中断/JSONL/逃生门）**。当前 **215 个测试全绿**；真机验证见 [docs/demo-M4上下文管理.md](docs/demo-M4上下文管理.md) 与 [docs/demo-M3文件编辑与PlanMode.md](docs/demo-M3文件编辑与PlanMode.md)。

## 相关文档

- [Roadmap / 里程碑与对比表](docs/roadmap.md)
- 里程碑归档：[M1](docs/milestones/m1/) · [M2](docs/milestones/m2/) · [M3](docs/milestones/m3/) · [M4](docs/milestones/m4/)
- 验收报告：[M1](docs/验收报告-M1.md) · [M2](docs/验收报告-M2.md) · [M3](docs/验收报告-M3.md) · [M4](docs/验收报告-M4.md)
- 真机 Demo：[M2 工具执行](docs/demo-M2工具执行.md) · [M3 文件编辑与 Plan Mode](docs/demo-M3文件编辑与PlanMode.md) · [M4 上下文管理](docs/demo-M4上下文管理.md)
- DeepSeek 双协议对比样例：[docs/DeepSeek-OpenAI-vs-Anthropic/](docs/DeepSeek-OpenAI-vs-Anthropic/README.md)
- [实现记录与 Claude Code/Codex 对比](docs/implementation.md)
- [待办与技术债](docs/TODO.md)
- [变更日志](CHANGELOG.md)
