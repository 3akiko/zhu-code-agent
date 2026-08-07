# TODO 清单

> 跨里程碑的待办/已知问题。里程碑级功能见 `docs/roadmap.md`，这里放开发中发现的具体待办。
> 每项完成后打勾并注明日期/提交。

## 待办

- [ ] **M2-ReAct 状态行生命周期重构**（2026-08-07 记录）
  - 背景：M2 的 ReAct 循环里，一轮用户输入会有多次 LLM 调用（think → tool_use → 执行工具 → tool result → think → …）。
  - 现状：`ChatApp.sendAndRender` 中 `boolean[] statusCleared` 是"每轮用户输入清一次"。
  - 需要：把"打印状态行 → 首个流式内容到达时清除"的粒度从「每轮一次」改为「每个 agent 步骤一次」；
    每个步骤有自己的状态行（如 ⏳ 正在思考… / 🔧 执行工具 read_file…），工具结果回来后替换/清除。
  - 可复用：`TerminalUi.clearPreviousLine()`（ANSI `\u001b[1A\u001b[K`）原语不变。
  - 关联：`TurnRunner` 需演进为 agent 循环（或新增 `AgentRunner`），处理 `StreamEvent` 的 ToolCall 事件与工具执行。
  - 位置：`src/main/java/com/zhubao/tui/ChatApp.java`（已加 TODO 注释）、`docs/roadmap.md` M2。

## 开发中发现的技术债务（2026-08-07 review 记录）

- [ ] **客户端并发安全**：`AnthropicClient`/`OpenAiClient` 的流累积状态（inputTokens/outputTokens/stopReason/thinkingAccum 等）是实例字段，同一实例并发 `stream()` 有数据竞争。M1 因「每轮新建实例 + UI 串行」规避；M2 做 subagents 并发前需重构为 per-call 状态持有者。
- [ ] **max_tokens 可配置化**：目前 AnthropicClient 写死 8192/64000；建议后续做成 provider 配置字段，并按模型区分上限（Claude Opus 32k vs Sonnet 64k），避免 opus + thinking 时 64000 报错。
- [ ] **会话存储性能**：当前每轮整文件原子写（O(n)/次），聊天规模够用；M2 上下文变大（工具输出/大段代码入历史）后评估 JSONL 追加写（无需文件锁，单进程单写者）。
- [ ] **多行输入**：M1 单行输入（Enter 即发送）；Shift+Enter 换行需绑定各终端的转义序列 + 多行渲染，属 M2 UI 增强。
- [ ] **退出时优雅关闭流线程**：目前 daemon 线程在 JVM 退出时被直接掐断；可改为退出前 interrupt + join。

## 其他已知项（可选项，来自开发过程）

- [ ] OpenAI 兼容协议 `reasoning_content` 解析（deepseek-reasoner 等推理模型思考灰字展示）——见 roadmap 扩展清单
- [ ] 应用内会话清理命令（如 `/clear-sessions`）——用户可临时用 `rm ~/.zhu-code-agent/sessions/*.json` 手动清
- [ ] Anthropic 真机验证（需 `ANTHROPIC_API_KEY`；代码路径已被 mock 测试覆盖）
