状态：approved
# zhuCodeAgent M1：聊天 TUI + 会话持久化 Tasks

> 依据已批准的 docs/spec.md 与 docs/plan.md。按序执行，每个任务都有独立验证；全部完成后进入 S4 验收。

## 文件清单

| 操作 | 文件 | 职责 |
|------|------|------|
| 修改 | `pom.xml` | Java 21 release、M1 依赖、shade/exec 插件、移除 junit 3.8.1 |
| 新建 | `config.example.yml` | 示例配置（双 provider 示例 + 单 provider 示例） |
| 新建 | `src/main/java/com/zhubao/Main.java` | 入口：--config/环境变量/默认路径、退出码 |
| 新建 | `src/main/java/com/zhubao/config/AppConfig.java` | 配置根对象 |
| 新建 | `src/main/java/com/zhubao/config/ProviderConfig.java` | 六字段 provider |
| 新建 | `src/main/java/com/zhubao/config/ConfigLoader.java` | YAML 绑定、api_key 三级解析、校验 |
| 新建 | `src/main/java/com/zhubao/config/ConfigException.java` | 可读配置异常 |
| 新建 | `src/main/java/com/zhubao/conversation/Message.java` | role/content/thinking/signature |
| 新建 | `src/main/java/com/zhubao/conversation/Conversation.java` | 内存历史 + buildRequest + 摘要 |
| 新建 | `src/main/java/com/zhubao/llm/StreamEvent.java` | 密封事件模型 |
| 新建 | `src/main/java/com/zhubao/llm/ChatRequest.java` | 统一请求结构 |
| 新建 | `src/main/java/com/zhubao/llm/SseEvent.java` | SSE 事件记录 |
| 新建 | `src/main/java/com/zhubao/llm/SseParser.java` | SSE 行解析 |
| 新建 | `src/main/java/com/zhubao/llm/LlmException.java` | 可读 LLM 异常（脱敏） |
| 新建 | `src/main/java/com/zhubao/llm/LlmClient.java` | 统一接口 |
| 新建 | `src/main/java/com/zhubao/llm/LlmClientFactory.java` | 按 protocol 创建 |
| 新建 | `src/main/java/com/zhubao/llm/AnthropicClient.java` | Claude Messages + SSE + thinking |
| 新建 | `src/main/java/com/zhubao/llm/OpenAiClient.java` | Chat Completions + SSE |
| 新建 | `src/main/java/com/zhubao/session/Session.java` | 会话（meta+messages） |
| 新建 | `src/main/java/com/zhubao/session/SessionMeta.java` | 会话元数据 |
| 新建 | `src/main/java/com/zhubao/session/ProviderSnapshot.java` | provider 快照（不含 apiKey） |
| 新建 | `src/main/java/com/zhubao/session/SessionStore.java` | 落盘/读取/列表/原子写 |
| 新建 | `src/main/java/com/zhubao/tui/TerminalUi.java` | JLine3 行编辑 + ANSI 颜色（Ansi 助手） |
| 新建 | `src/main/java/com/zhubao/tui/JLinePicker.java` | 方向键选择列表 |
| 新建 | `src/main/java/com/zhubao/tui/AppState.java` | SESSION_SELECT/PROVIDER_SELECT/CHAT |
| 新建 | `src/main/java/com/zhubao/tui/SlashCommands.java` | /help /exit /clear |
| 新建 | `src/main/java/com/zhubao/tui/ChatApp.java` | 状态机 + 主循环 + 流式渲染 |
| 新建 | `src/test/java/com/zhubao/config/ConfigLoaderTest.java` | 配置单测 |
| 新建 | `src/test/java/com/zhubao/llm/SseParserTest.java` | SSE 解析单测 |
| 新建 | `src/test/java/com/zhubao/llm/AnthropicClientTest.java` | mock 流式 + thinking 回传 |
| 新建 | `src/test/java/com/zhubao/llm/OpenAiClientTest.java` | mock 流式 |
| 新建 | `src/test/java/com/zhubao/llm/LlmClientFactoryTest.java` | 工厂单测 |
| 新建 | `src/test/java/com/zhubao/conversation/ConversationTest.java` | 历史/请求构造单测 |
| 新建 | `src/test/java/com/zhubao/session/SessionStoreTest.java` | 会话存取单测 |
| 新建 | `src/test/java/com/zhubao/integration/StreamingIntegrationTest.java` | mock 端到端流式 |

## T0: 工程骨架与依赖
**文件：** `pom.xml`
**依赖：** 无
**步骤：**
1. 设置 `<maven.compiler.release>21</maven.compiler.release>` 与 UTF-8。
2. 依赖：jline 3.28.0、jackson-databind 2.21.x、jackson-datatype-jsr310、snakeyaml 2.x、junit-jupiter 5.11.4（test）；移除 junit 3.8.1（mordant 因 Kotlin-first/Java 集成成本高弃用，改自研 Ansi 助手，2026-08-07 批准）。
3. 插件：maven-shade-plugin（mainClass=com.zhubao.Main，fat jar）、exec-maven-plugin、surefire（JUnit5）。
**验证：** `mvn -q -DskipTests compile` 成功；`mvn -q test` 成功（当前 0 测试）。

## T1: config 模块
**文件：** `config/AppConfig.java`、`config/ProviderConfig.java`、`config/ConfigLoader.java`、`config/ConfigException.java`、`config.example.yml`
**依赖：** T0
**步骤：**
1. ProviderConfig：name/protocol/model/baseUrl/apiKey/thinking（getter/setter，默认 thinking=false）。
2. AppConfig：providers + sessionsDir（默认 `~/.zhu-code-agent/sessions`）。
3. ConfigLoader.load(String path)：path 为 null 时尝试 `ZHU_CODE_AGENT_CONFIG` 环境变量，再默认 `~/.zhu-code-agent/config.yml`；SnakeYAML 绑定；api_key 三级解析（直接值 → `${ENV_VAR}` 展开 → ANTHROPIC_API_KEY/OPENAI_API_KEY 按 protocol 回退）；校验 provider 非空/protocol∈{anthropic,openai}/model 非空/name 不重复；错误抛 ConfigException（消息不含密钥）。
4. config.example.yml：一份双 provider（claude+openai）示例，注释说明各字段。
**验证：** 运行 `mvn -q test -Dtest=ConfigLoaderTest`，覆盖：六字段解析、${ENV} 展开、环境变量回退、空列表/非法 protocol/重复 name 抛 ConfigException、异常消息不含 api_key。

## T2: conversation 模块
**文件：** `conversation/Message.java`、`conversation/Conversation.java`
**依赖：** T1（复用 ProviderSnapshot 概念，先用简单 record）
**步骤：**
1. Message：role/content/thinking/thinkingSignature（可空字段）。
2. Conversation：构造时接收 ProviderSnapshot；addUser/addAssistant(content,thinking,signature)；getMessages；buildRequest(systemPrompt) 返回 ChatRequest；previewTitle()（首条用户消息前 30 字符，空则"新对话"）；messageCount()。
**验证：** `mvn -q test -Dtest=ConversationTest`：历史顺序、buildRequest 内容、标题摘要、消息数。

## T3: llm 基础（事件模型 + SSE 解析）
**文件：** `llm/StreamEvent.java`、`llm/ChatRequest.java`、`llm/SseEvent.java`、`llm/SseParser.java`、`llm/LlmException.java`
**依赖：** T2
**步骤：**
1. StreamEvent 密封接口：TextDelta/ThinkingDelta/ThinkingComplete(signature)/StreamEnd(stopReason,inputTokens,outputTokens)/Error(message)。
2. SseParser：feedLine 逐行（event:/data:/空行触发/注释忽略/多行 data 连接）+ 静态 parse(String) 供测试。
**验证：** `mvn -q test -Dtest=SseParserTest`：anthropic 事件样例（content_block_start/delta/text_delta/thinking_delta/message_delta）与 openai 样例（data: {...} 与 [DONE]）解析正确。

## T4: llm 客户端与工厂
**文件：** `llm/LlmClient.java`、`llm/LlmClientFactory.java`、`llm/AnthropicClient.java`、`llm/OpenAiClient.java`
**依赖：** T3
**步骤：**
1. LlmClient：`BlockingQueue<StreamEvent> stream(ChatRequest request)`，实现内起后台线程发请求并写队列。
2. AnthropicClient：POST `{baseUrl}/v1/messages`；Header x-api-key/anthropic-version:2023-06-01/content-type；body：model/system/messages/stream:true，thinking=true 时加 thinking{type:enabled,budget_tokens:32000}；assistant 历史消息带 thinking/signature 时翻译为 content blocks（thinking+text）回传；解析 SSE → StreamEvent。
3. OpenAiClient：POST `{baseUrl}/v1/chat/completions`；Bearer 认证；systemPrompt 作 role=system 首条；解析 data 块与 [DONE]。
4. LlmClientFactory.create(provider)：switch protocol，未知抛 LlmException。
5. 错误处理：非 2xx 读 body 摘要生成 Error（不含密钥）；JSON 解析失败生成 Error。
**验证：** `mvn -q test -Dtest=AnthropicClientTest,OpenAiClientTest,LlmClientFactoryTest`（mock HttpServer）：两类协议流式事件序列正确；thinking 多轮请求体断言含 signature 回传；401/500 产生 Error 事件；工厂分支正确。

## T5: session 模块
**文件：** `session/Session.java`、`session/SessionMeta.java`、`session/ProviderSnapshot.java`、`session/SessionStore.java`
**依赖：** T2（可并行于 T3/T4）
**步骤：**
1. 数据结构如上；id=`yyyyMMdd-HHmmss-<4位随机>`。
2. SessionStore(Path dir)：save 原子写（tmp+rename）；list 按 updatedAt 倒序（损坏文件跳过+警告）；load 解析失败返回 empty；目录不存在自动创建。
3. Jackson 注册 jsr310；序列化忽略 null。
**验证：** `mvn -q test -Dtest=SessionStoreTest`（@TempDir）：save→list→load 往返一致；文件 JSON 无 apiKey 字段；写入损坏文件后 list/load 不抛异常；无残留 .tmp。

## T6: tui 基础组件
**文件：** `tui/TerminalUi.java`、`tui/JLinePicker.java`、`tui/AppState.java`、`tui/SlashCommands.java`
**依赖：** T1（可并行于 T3/T4/T5）
**步骤：**
1. TerminalUi：包装 JLine3 Terminal/LineReader（行编辑+本会话输入历史）、Ansi 256 色打印（青/灰/红/绿/亮青）、清屏、关闭。
2. JLinePicker：标题 + 项列表 + 说明，↑/↓/Enter/Ctrl+C。
3. SlashCommands：isCommand；handle 返回枚举（HELP/EXIT/CLEAR/NONE）；/help 文案含当前 provider/model。
**验证：** SlashCommands 纯逻辑单测（命令识别与分发）；TerminalUi/JLinePicker 用 `mvn exec:java` 手动冒烟（方向键、历史、颜色）。

## T7: ChatApp 状态机与主循环
**文件：** `tui/ChatApp.java`
**依赖：** T4、T5、T6
**步骤：**
1. run()：SessionStore.list() 非空 → SESSION_SELECT picker（新建+历史）；新建且 providers>1 → PROVIDER_SELECT；进入 CHAT。
2. CHAT 循环：readLine("> ")；空跳过；斜杠命令处理；否则 addUser → LlmClientFactory.create → stream(buildRequest(SYSTEM_PROMPT)) → 轮询 poll(100ms)：TextDelta 正常色累积打印、ThinkingDelta 灰色累积打印、ThinkingComplete 记 signature、Error 红色终止、StreamEnd 终止；完成后 addAssistant + SessionStore.save。
3. 恢复会话时用 snapshot 的 provider，api_key 从当前配置同名 provider 或协议环境变量取。
4. 保存时机：每轮回复完成后 + /exit 前。
**验证：** `mvn -q test -Dtest=StreamingIntegrationTest`（mock HttpServer + 假终端/直接驱动 ChatApp 核心方法）：新会话流式回复入库；多轮消息递增；thinking 事件灰字与正文分离。

## T8: Main 入口
**文件：** `Main.java`
**依赖：** T7
**步骤：**
1. 解析 `--config <path>`；缺省读 `ZHU_CODE_AGENT_CONFIG`；再缺省默认路径。
2. ConfigLoader 失败 → stderr 可读消息 + exit 1；成功 → new ChatApp(config).run()。
3. SYSTEM_PROMPT 常量定义于此或独立常量类。
**验证：** 手动：坏配置路径 `mvn exec:java -- -Dexec.args="--config /nonexistent"` 输出可读错误且 `echo $?` 非 0；正常配置启动进入 TUI。

## T9: M1 收尾：持续文档 + 里程碑归档
**文件：** `README.md`、`docs/implementation.md`、`CHANGELOG.md`、`docs/milestones/m1/*`
**依赖：** T8
**步骤：**
1. 编写 `README.md`：项目简介、当前功能列表（M1）、安装/构建（mvn 打包与 java -jar）、配置说明（config.example.yml 六字段 + api_key 三级解析）、运行命令（mvn exec:java / java -jar）、目录结构、与 Claude Code/Codex 对比链接、Roadmap 链接。
2. 编写 `docs/implementation.md`：M1 实现了什么、核心实现思路（模块架构图/关键类/流事件模型/线程模型）、与 Claude Code / Codex 的对比差异、踩坑记录。
3. 编写 `CHANGELOG.md`：首版条目（0.1.0 未发布）。
4. 归档：将已批准的 M1 四文档复制到 `docs/milestones/m1/`（spec/plan/task/checklist 各一份，保留 approved 状态标记）。
5. 更新 `docs/roadmap.md`：M1 状态改为「已完成」，回填第 5 节对比表。
**验证：** 手动检查四份文档齐全、README 可指导新人跑起程序、对比表已回填。

## 执行顺序
```
T0
 ├→ T1 → T2 → T3 → T4 ─┐
 ├→ T5（与 T3/T4 并行）─┼→ T7 → T8 → T9
 └→ T6（与 T3/T4/T5 并行）┘
```
