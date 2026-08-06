状态：approved
# zhuCodeAgent M1：聊天 TUI + 会话持久化 Checklist

> 依据已批准的 docs/spec.md（AC1-AC11）与 docs/plan.md 设计。每一项通过运行代码或观察行为验证（做 X 看到 Y）。

## 实现完整性

- [ ] C1 配置加载（AC5）：六字段 YAML 解析正确；`${ENV_VAR}` 引用与环境变量回退生效；坏配置 stderr 输出可读错误且退出码非 0（验证：`mvn -q test -Dtest=ConfigLoaderTest` + 手动 `--config /nonexistent` 观察）
- [ ] C2 对话历史（AC4 前置）：消息按 user/assistant 顺序追加；buildRequest 携带完整历史；标题摘要取首条消息前 30 字符（验证：ConversationTest）
- [ ] C3 SSE 解析：anthropic 事件样例（content_block_start/delta/thinking_delta/message_delta）与 openai 样例（data 块 + [DONE]）解析为正确事件（验证：SseParserTest）
- [ ] C4 双协议客户端（AC6/AC7）：anthropic 与 openai 两类 mock 流式事件序列正确；thinking=true 时请求体含 thinking 配置、多轮请求体回传 signature（验证：AnthropicClientTest/OpenAiClientTest 断言请求 JSON 与事件序列）
- [ ] C5 会话存取（AC11/N4）：save→list→load 往返一致；会话 JSON 无 apiKey 字段；损坏文件 list/load 跳过不抛异常；无残留 .tmp（验证：SessionStoreTest）
- [ ] C6 终端交互（AC2/AC8）：输入行方向键编辑与上下翻历史可用；/help /exit /clear 行为正确（验证：SlashCommands 单测 + 手动 TUI 冒烟）
- [ ] C7 状态机与流式渲染（AC1/AC3）：SESSION_SELECT/PROVIDER_SELECT/CHAT 流转正确；TextDelta 正常色、ThinkingDelta 灰色、Error 红色（验证：StreamingIntegrationTest + 手动观察）

## 集成

- [ ] I1 Provider 工厂：LlmClientFactory 按 protocol 返回正确实现，ChatApp 调用方不感知协议差异；新增协议不改调用方（验证：LlmClientFactoryTest + 代码审查）
- [ ] I2 保存链路：每轮回复完成后会话文件被更新（验证：StreamingIntegrationTest 断言文件存在且 messageCount 递增）
- [ ] I3 恢复链路：恢复历史会话后继续对话，旧消息随请求回传、模型可引用（验证：E1 场景）
- [ ] I4 公开接口均有真实调用方：无死代码，编译 + 全部测试通过（验证：`mvn -q test`）

## 编译与测试

- [ ] B1 编译：`mvn -q clean compile` 无错误
- [ ] B2 测试：`mvn -q test` 全绿（单测 + mock 集成测试）
- [ ] B3 打包：`mvn -q package` 产出可执行 fat jar，`java -jar target/zhu-code-agent.jar` 可启动进入 TUI
- [ ] B4 质量：M1 未引入 lint 插件，以单测 + 集成测试 + code review 保证质量（记录在案）

## 端到端场景

- [ ] E1 完整对话闭环（mock 服务器）：本地 mock SSE 服务器模拟 OpenAI/Anthropic → 驱动 ChatApp：输入「你好」→ 回复逐段实时出现 → 输入「我刚才问的什么」→ mock 返回引用上一轮 → 会话文件生成 → 退出 → 重启选择恢复该会话 → 上下文仍在（验证：StreamingIntegrationTest 自动化 + 手动跑一遍）
- [ ] E2 边界-坏配置：`--config /nonexistent` 或格式错误配置 → stderr 可读错误、退出码非 0、进程不崩溃
- [ ] E3 安全-密钥脱敏（N4）：搜索日志、异常输出、会话 JSON 均无 api_key 明文（验证：grep 会话目录 + 坏配置/401 场景观察输出）
- [ ] E4 thinking 展示：anthropic thinking=true（mock）→ 灰色思考文字先出现、正常色正文随后，正文不含思考内容；thinking=false → 无思考段
- [ ] E5 provider 选择：双 provider 配置启动显示选择列表，方向键+回车选中进入对应聊天；单 provider 配置启动直进聊天

## 验收报告

- [ ] 逐项记录结果与证据（通过/不通过 + 证据），输出到 `docs/验收报告-M1.md`
