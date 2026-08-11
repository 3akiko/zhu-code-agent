状态：approved
# zhuCodeAgent M4：上下文管理与稳定性 Checklist

> 每一项通过「运行代码/观察行为」验证（做 X 看到 Y）。依据已批准 spec AC1–AC7、plan 集成点、task 文件清单。

## 实现完整性

- [x] F1 统计-求和：多步工具循环一轮完成后，完成行显示「本轮 in X / out Y」为全部步骤求和（验证：M4ContextIntegrationTest 断言）
- [x] F1 统计-累计：完成行显示「累计 Z」且随会话单调累加、落盘恢复后继续累加（验证：SessionStoreJsonlTest + M4ContextIntegrationTest）
- [x] F1 统计-占用：完成行显示占用百分比 = lastInputTokens / effectiveContextWindow；占用基数按协议口径（Anthropic = input + cacheRead，OpenAI = prompt_tokens 已含缓存不重复计）（验证：ProviderConfigTest + mock usage 断言文本）
- [x] F2 告警：占用 ≥ alertThreshold（默认 0.8）时输出「⚠ 上下文已达…（阈值…）」告警行（验证：小窗口 mock 集成）
- [x] F3 本地瘦身-丢弃：空结果 /「已拒绝执行」/「权限禁止」的 tool 配对整对丢弃，正常配对保留（验证：ContextCompactorTest）
- [x] F3 本地瘦身-截断：>64KB tool_result 截断并标注，<cap 不动（验证：ContextCompactorTest）
- [x] F3 摘要折叠：最旧 N 轮折叠为一条含「【上下文已压缩】」的 user 消息；摘要可再折叠 ≤2 层；折叠后消息序仍合法（user/assistant 交替不破）（验证：ContextCompactorTest）
- [x] F3 自动触发：生成前占用 ≥ compactThreshold（默认 0.9）→ 自动压缩并显示边界（pre/post）（验证：小窗口 M4ContextIntegrationTest）
- [x] F3 手动 /compact：任意时刻触发压缩并显示边界（验证：集成/冒烟）
- [x] F3 熔断：连续 3 次压缩失败 → 本次运行自动压缩停用、手动仍可用（验证：ContextCompactorTest）
- [x] F4 cache_control：Anthropic 请求体 system 为块数组、system/tools 均含 `cache_control:{type:ephemeral}`（验证：AnthropicClientTest 断言请求体）
- [x] F4 缓存解析与展示：Anthropic cache_read/creation、OpenAI cached_tokens、DeepSeek prompt_cache_hit_tokens 解析进 StreamEnd 并在完成行展示（验证：客户端测试 + 集成断言）
- [x] F4 prompt_cache 开关（review-P2）：默认 true 发送 cache_control；`prompt_cache: false` 时 system 回退字符串、无 cache_control（验证：ConfigLoaderTest + AnthropicClientTest）
- [x] F5 生成中断：模拟 Ctrl+C → Result.interrupted=true、半成品不写入会话、会话含 assistant「（已中断）」、未执行工具不执行（验证：M4ContextIntegrationTest）
- [x] F5 中断回归-交替与无悬空：中断后会话保持 user/assistant 交替、无悬空 tool_use；紧接着继续一轮（anthropic + openai 双协议）回填/加载/继续对话均正常（验证：M4ContextIntegrationTest + AgentLoopIntegrationTest）
- [x] F5 工具中断：工具执行中 cancel() → 快速返回「命令执行被中断」、进程销毁、executor 停止后续调用（验证：BashToolInterruptTest）
- [x] F5 优雅关闭：退出路径对在途流 cancel + join（带超时），无 daemon 线程泄漏（验证：Interrupt 集成断言线程结束 + 代码走查）
- [x] F5 二次 Ctrl+C 逃生门（review 加固）：本轮内 1.5s 连续两次 Ctrl+C → 恢复默认 SIGINT + 优雅退出；单次不退出；跨轮窗口重置（验证：TurnInterruptControllerTest）
- [x] F6 JSONL-追加：新会话保存为 .jsonl、追加写不重复旧消息、每行一个 JSON（验证：SessionStoreJsonlTest）
- [x] F6 JSONL-迁移：旧 .json 可 load、首 save 转 .jsonl 且删除旧文件、内容一致（验证：SessionStoreJsonlTest）
- [x] F6 JSONL-容错：损坏行跳过并警告、不崩溃、其余消息保留（验证：SessionStoreJsonlTest）
- [x] F6 JSONL-截断：>64KB tool_result 落盘截断标注沿用（验证：SessionStoreJsonlTest）
- [x] F7 max_tokens：Anthropic 未配置时按模型表（thinking 64000 / plain 8192，opus→32000），显式配置后生效（验证：AnthropicClientTest + ConfigLoaderTest）

## 集成

- [x] LlmClient.stream() 返回类型变更后全部调用点/测试已同步 `.events()`（验证：编译 + 全测试通过）
- [x] SessionMeta 累计字段随 JSONL meta 行落盘并恢复（验证：SessionStoreJsonlTest）
- [x] 压缩后会话可加载、双协议回填（anthropic tool_use ↔ openai function_call）不回归（验证：Compaction 集成 + AgentLoopIntegrationTest 全绿）
- [x] 中断与权限/快照交互：中断不产生额外快照副作用、权限状态一致（验证：代码走查 + 集成）

## 编译与测试

- [x] `mvn test` 全绿：170（M1–M3 不回归）+ M4 新增全部通过（验证：mvn test）
- [x] F1 占用口径回归（变更控制 2026-08-11）：Anthropic input+cacheRead / OpenAI prompt_tokens 不重复计缓存（验证：ProviderConfigTest 5 用例）
- [x] `mvn -q package` 出包成功，`target/zhu-code-agent.jar` 存在（验证：mvn -q package && ls target）

## 端到端场景

- [x] 场景 1（统计→告警→压缩→恢复）：小窗口（context_window=2000）下跑多轮（mock/真机）→ 完成行出现累计与占用% → 达 80% 出现告警 → 达 90% 自动压缩 → 摘要消息出现、占用回落、会话可继续且可保存恢复（验证：M4ContextIntegrationTest + 真机冒烟可选）
- [x] 场景 2（中断+继续）：生成中 Ctrl+C → 半成品消失、显示已中断、回提示符 → 继续提问正常回复、会话含「（已中断）」且保存后可恢复（验证：集成 + 冒烟）
- [x] 场景 3（迁移）：旧 .json 会话启动可恢复 → 继续对话后文件转 .jsonl、旧文件删除、消息不丢（验证：SessionStoreJsonlTest + 手工）

## 安全

- [x] 会话/JSONL 不含 api_key；压缩摘要不含密钥（验证：SessionStoreTest 既有断言 + 代码走查）
