状态：approved
# zhuCodeAgent M4：上下文管理与稳定性 Spec

## 背景

- M3（文件编辑增强与 Plan Mode）已完成并验收通过：四文档归档于 `docs/milestones/m3/`，基线 `mvn test` 170/170 全绿。
- M3 已具备：diff 展示、/plan 先计划后执行、文件快照与 /undo /rewind、三档权限模式；M2 的 6 内置工具 + Agent 循环 + 双协议回填 + 路径/危险命令守卫全部沿用。
- 长会话的真实瓶颈（M4 要解决的）：
  1. **看不见**：每轮只显示 `in X / out Y`，没有会话累计、没有上下文占用百分比，用户不知道离窗口上限还有多远。
  2. **无预警**：接近上限时无任何提示，会话可能在最不该失败的时候失败。
  3. **不会自救**：没有压缩能力，历史无限膨胀 → 变贵、变慢、易超限。
  4. **不省钱**：没有 prompt 缓存，重复的前缀（system/tools/历史）每次都全价计费。
  5. **不可中断**：生成/工具执行中无法取消；流线程为 daemon 线程，退出时被直接掐断。
  6. **整文件重写**：会话每轮整文件原子写（O(n)），上下文变大后落盘越来越慢。
  7. **写死上限**：`AnthropicClient` 写死 max_tokens 8192/64000，换模型/换 thinking 配置易报错。

## 目标

让长会话「不崩、不贵、可中断、可持久、适配模型」：

- **看得见**：每轮 in/out 求和 + 会话累计 + 上下文占用百分比（以 API 返回的 usage 为准）。
- **有预警**：占用达可配阈值（默认 80%）时醒目告警。
- **会自救**：占用达压缩阈值（默认 90%）自动压缩；手动 `/compact` 随时触发；双层渐进（本地瘦身 → LLM 摘要折叠最旧轮次），不破坏会话 JSON 契约与双协议回填。
- **省钱提速**：Anthropic 请求打 cache_control 断点；双协议解析缓存命中并在完成行展示。
- **可中断**：生成中 Ctrl+C 取消本次生成（半成品回滚）；工具执行中常驻提示 + 单次 Ctrl+C 中断工具并终止本轮；退出时优雅 interrupt + join 所有流线程。
- **不爆盘**：会话存储改 JSONL 追加写（O(1)），兼容读取旧 .json 并转存；tool_result 64KB 截断沿用。
- **适配模型**：max_tokens 从写死改为「provider 可配 + 内置模型默认表」。

## 功能需求

### F1 token 用量统计与展示

- 每轮用户输入完成后，完成行展示：**本轮 in/out**（该轮全部 agent 步骤求和，含多步工具循环）、**会话累计**（历史所有轮次 in+out 单调累加）、**上下文占用百分比**（最近一次请求 inputTokens / context_window）。
- 会话累计随会话落盘（SessionMeta），跨会话恢复后继续累加。
- 上下文占用百分比以 API 返回的 inputTokens 为准，不引入本地 token 估算（tiktoken 类）。占用基数按协议口径（变更控制 2026-08-11）：**OpenAI 协议 `prompt_tokens` 已含缓存**（官方语义 = hit + miss）直接采用；**Anthropic 协议 `input_tokens` 不含缓存命中**，需 `input + cache_read_input_tokens` 才是本次请求真实占用（真机实测 DeepSeek /anthropic 端点第 2 轮 input=42 + cacheRead=768 = 810；真实 Claude 同协议约定）。

### F2 接近上限告警

- 配置 `context.alert_threshold`（默认 0.8）：当前占用百分比 ≥ 阈值时，在完成行/下次生成前显示醒目告警（如「⚠ 上下文已达 82%（阈值 80%）」）。
- 告警阈值 < 压缩阈值，保证「先提醒、后自救」。

### F3 上下文自动压缩（双层渐进）+ 手动 /compact

- 配置 `context.compact_threshold`（默认 0.9）：每次生成前以最近一次请求的占用基数（按 F1 协议口径）估算占用，≥ 阈值自动触发压缩；手动 `/compact` 命令随时触发（无阈值要求）。
- 压缩流程（两层）：
  1. **本地零成本瘦身**（可配 `context.snip_enabled`，默认开）：丢弃低价值轮次（空结果工具调用、被拒绝的调用）；把超长 tool_result 输出截断到阈值（默认沿用 64KB 落盘上限的发送视图）。
  2. **LLM 摘要折叠**：把最旧 N 轮折叠为一条摘要消息（保留关键结论/文件路径/决策/工具结果要点），折叠范围以「压缩后占用 ≤ 目标水位（默认 0.6）」为准；摘要以 user 文本消息 +「【上下文已压缩】」标记前缀写回会话，**不新增内容块类型**，不破坏 JSON 契约与双协议回填。
- 压缩完成：会话落盘；显示压缩边界与 pre/post token 数。
- 自动压缩失败（API 错误等）：返回可读提示不崩溃；**连续 3 次失败熔断**（本次运行内不再自动压缩，手动 /compact 仍可用）。

### F4 prompt 缓存（降本提速）

- **Anthropic**：请求体 system / tools 处打 `cache_control: {"type": "ephemeral"}` 断点；解析 usage 的 `cache_read_input_tokens` / `cache_creation_input_tokens`。
- **OpenAI / DeepSeek**：服务端自动缓存，客户端不打点；解析 `cached_tokens`（OpenAI prompt_tokens_details）/ `prompt_cache_hit_tokens`（DeepSeek）。
- 完成行展示缓存命中（如「cache read X · created Y」或「cache hit X」）；**不落盘**（实时展示）。
- 压缩会改变历史前缀导致缓存失效：压缩边界提示「缓存已重置」。
- **provider 开关（变更控制 2026-08-11）**：Anthropic 侧新增可选 `prompt_cache`（默认 true）控制是否发送 cache_control 断点；关闭时 system 回退字符串、tools 不带 cache_control（兼容不识别 cache_control 的端点）。

### F5 流式中断与优雅关闭

- **生成中 Ctrl+C**：取消当前这次 LLM 生成——丢弃已打印的半成品文本（回滚，不写入会话）、中断流线程并 join、会话写入可见的「（已中断）」标记并落盘、回到输入提示符；**尚未执行的工具调用不执行**。
- **工具执行中**：状态行常驻提示「（Ctrl+C 中断并终止本轮）」；一次 Ctrl+C 中断正在执行的工具（如 bash 进程销毁）并终止本轮，记录「（已中断）」。
- **二次 Ctrl+C 逃生门（变更控制 2026-08-11）**：本轮内 1.5 秒内连续第二次 Ctrl+C → 恢复默认 SIGINT 并优雅退出（取消在途流 + 落盘会话），避免一轮卡死时无兜底（对齐 Codex「再按一次退出」）；跨轮窗口重置。
- **退出时**（/exit、Ctrl+D、提示符 Ctrl+C）：中断并 join 所有在途流线程，确保无 daemon 线程泄漏、会话落盘完成。

### F6 会话存储 JSONL 化

- 新格式 `.jsonl`：每行一个 JSON 对象（首行会话元数据，其后每条消息一行），追加写 O(1)；单进程单写者，无需文件锁。
- 读取兼容旧 `.json`（迁移逻辑沿用）；新建会话写 `.jsonl`；加载旧 `.json` 后首次保存转存为 `.jsonl`（原子 rename，成功后删除旧文件）。
- tool_result 落盘 64KB 截断策略沿用；损坏行跳过并警告，进程不崩溃。

### F7 max_tokens 可配置化

- ProviderConfig 新增可选 `max_tokens` 字段；未配置时按「模型 → 输出上限」内置默认表取值（thinking 64000 / plain 8192 为基础，映射表覆盖 deepseek-v4-flash / deepseek-v4-pro / claude / gpt 常见模型），按模型区分避免报错。
- config.example.yml 更新；ConfigLoader / ConfigLoaderTest 同步（M2/M3 模式）。

## 非功能需求

- **N1 技术栈**：Java 21 + Maven；不新增第三方依赖（JSONL 用现有 Jackson；token 占用以 API 返回为准，不引入 tiktoken）。
- **N2 安全**：M1–M3 安全红线全部沿用不削弱——危险命令强制确认、cwd 外破坏性命令立即拒绝、禁写 `.git/` 与 `~/.zhu-code-agent/`、api_key 不落日志不落盘（JSONL 同样不含）；压缩摘要消息不含密钥；权限模式与「总是允许」不落盘不变。
- **N3 兼容性**：会话 JSON 契约不破坏——旧 `.json` 可加载、新 `.jsonl` 可加载；双协议（anthropic tool_use ↔ openai function_call）回填不回归；压缩/折叠历史不破坏加载与回填（参考 M2「旧会话字符串 content → TextBlock 迁移」经验）。
- **N4 稳定性**：压缩/中断/JSONL 读写失败均返回可读错误、进程不崩溃；自动压缩熔断（连续 3 次失败停止）；损坏 JSONL 行跳过。
- **N5 测试**：token 统计、告警、压缩（含摘要 mock）、中断、JSONL 读写与迁移、max_tokens 配置均有单元/集成测试；M3 的 170 个测试不回归。
- **N6 性能**：JSONL 追加写 O(1)；压缩按需触发（达阈值才执行）；tool_result 截断控制内存与会话文件膨胀。

## 不做的事

- 不做 `-p` 非交互模式、结构化输出容错（工具参数 schema 校验 + 重试）——后置 M8+（2026-08-10 范围调整）。
- 不做并行工具 / Subagents（M5）、MCP / Hooks / Skills（M6）、OS 级沙箱（M7）。
- 不做 Context Collapse 只读投影、分层摘要超过 2 层（留 M5+ 演进；YAGNI）。
- 不做本地 token 估算（tiktoken 类）——占用以 API 返回的 inputTokens 为准。
- 不做 `/stats` 命令——完成行 + 告警行已覆盖（YAGNI）。
- 不做缓存指标落盘（仅实时展示）。
- 不做多行输入、无进展检测、Claude Code「暂停-继续」（M8+）。

## 验收标准

- **AC1（F1/F2）**：mock 双协议会话：多步工具循环一轮完成后，完成行显示本轮 in/out 求和、会话累计、占用百分比；配置小窗口（如 context_window=2000）使占用达阈值时显示告警；会话保存并恢复后累计仍在并继续累加。
- **AC2（F3）**：mock 场景：小窗口下自动触发压缩——最旧轮次被折叠为带「【上下文已压缩】」标记的摘要消息、低价值空结果轮次被丢弃、超长 tool_result 被截断；压缩后占用回落到目标水位以下；压缩后会话可加载、双协议回填不回归；手动 `/compact` 立即触发；连续 3 次压缩失败后本次运行不再自动压缩。
- **AC3（F4）**：Anthropic 请求体 system/tools 含 `cache_control` 断点；mock 返回 `cache_read_input_tokens` / `cache_creation_input_tokens` 时完成行显示；OpenAI/DeepSeek mock 返回 `cached_tokens` / `prompt_cache_hit_tokens` 时完成行显示。
- **AC4（F5）**：模拟生成中 Ctrl+C → 半成品不写入会话、显示「（已中断）」、回到提示符、未执行工具不执行；模拟工具执行中 Ctrl+C → 工具被中断、本轮终止；退出时在途流线程被 join（测试断言线程结束、无泄漏）。
- **AC5（F6）**：新建会话保存为 `.jsonl` 且为追加写；旧 `.json` 会话可加载且首次保存转存为 `.jsonl`（旧文件被删除）；损坏行跳过不崩溃；tool_result 64KB 截断沿用。
- **AC6（F7）**：provider 未配置 `max_tokens` 时按内置模型默认表取值（thinking 64000 / plain 8192）；配置后请求体使用配置值；config.example.yml 与 ConfigLoaderTest 覆盖。
- **AC7（N2/N3/N4）**：会话/JSONL 不含 api_key；压缩摘要不含密钥；旧会话字符串 content → TextBlock 迁移仍工作；双协议回填在压缩后不回归；压缩/JSONL 失败路径均不崩溃、返回可读信息。
