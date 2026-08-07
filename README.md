# zhuCodeAgent

用 Java 从零实现的一个命令行 Coding Agent（对标 Claude Code / Codex），用于学习 agent 核心机制与 agent 开发面试。

> **状态：M2 已完成**（Agent 循环与 Tool Use：6 个内置工具 + 权限确认 + 安全边界，2026-08-08）。里程碑规划见 [docs/roadmap.md](docs/roadmap.md)。

## 当前功能（M2）

- 彩色终端 TUI（JLine3 + ANSI 256 色：用户青色 / 思考灰色 / 错误红色 / 状态绿色）
- 启动流程：有历史会话先显示「新建对话 + 历史会话列表」，多 provider 显示选择列表，单 provider 直进聊天
- 流式输出：SSE 增量即到即显，不等待完整响应
- 多轮对话记忆：历史随请求回传，模型可引用之前内容
- 会话持久化与恢复：每轮回复完成后自动落盘到 `~/.zhu-code-agent/sessions/`，启动可选恢复（**不含 api_key**）
- 双后端：Anthropic Claude（含 extended thinking，思考灰字实时展示、正文正常颜色、signature 多轮回传）/ OpenAI（**DeepSeek 等 OpenAI 兼容服务可直接用：`protocol: openai` + 自定义 `base_url`**）
- 基础命令：`/help` `/clear` `/new`（保存当前并开新会话）`/exit`（退出前保存会话）
- 统一 Provider 抽象：新增后端只需新增一个实现类 + 工厂分支，调用方不变
- **Agent 循环（ReAct）**：模型输出工具调用 → 权限确认 → 执行 → 结果回填 → 循环直到 end_turn；同一消息多个工具调用串行执行、一次性回填
- **内置 6 工具**：`read_file`（支持 offset/limit 行范围）/ `write_file` / `edit_file`（精确字符串替换，唯一匹配）/ `bash`（cwd、无 stdin、30s 超时、200KB 输出截断）/ `grep` / `glob`
- **权限确认**：只读工具自动放行；写类/bash 行内确认（允许 a / 拒绝 d / 总是允许本次 s）；「总是允许」按工具+参数精确记忆、程序运行内有效、不落盘、退出重置；`/permissions` 查看与重置
- **安全边界**：路径以工作区为根（realpath 含符号链接校验），越界/写 `.git/` 与 `~/.zhu-code-agent/` 拒绝；`rm -rf` 等危险命令即使曾「总是允许」也强制确认，且目标必须位于工作区内
- **循环护栏**：单轮工具调用上限（`tool.max_calls_per_turn`，默认 60）+ 每步流空闲超时 120s + 结果预览行数可配（`ui.tool_preview_lines`，默认 5）

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
zhuCodeAgent v0.1.0 —— 命令行 Coding Agent
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
```

## 目录结构

```
src/main/java/com/zhubao/
├── Main.java              # 入口：--config 解析、退出码
├── config/                # YAML 配置：六字段 + api_key 三级解析 + 校验
├── conversation/          # 对话历史：Message(Role) / Conversation / Role 枚举
├── llm/                   # LlmClient 接口 + Anthropic/OpenAI 实现 + SseParser + StreamEvent
├── session/               # 会话落盘/恢复：Session / SessionStore（原子写，不含 api_key）
└── tui/                   # JLine3 终端 + Ansi 颜色 + JLinePicker + ChatApp 状态机 + TurnRunner
```

## 测试

```bash
mvn test    # 单元测试 + mock HTTP 集成测试（不依赖真实密钥）
```

覆盖：配置解析、SSE 解析、双协议流式客户端、thinking 多轮回传、会话存取、端到端流式链路。

## 相关文档

- [Roadmap / 里程碑与对比表](docs/roadmap.md)
- [M1 四文档归档](docs/milestones/m1/)
- [实现记录与 Claude Code/Codex 对比](docs/implementation.md)
- [变更日志](CHANGELOG.md)
