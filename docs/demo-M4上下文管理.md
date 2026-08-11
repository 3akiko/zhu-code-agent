# M4 上下文管理与稳定性 本地 Demo

> 目的：在本地真实跑通 M4 的五个核心能力——① token 统计与**协议感知占用**（含缓存）② prompt 缓存展示 ③ 双层渐进压缩（手动 `/compact` + 自动）④ Ctrl+C 生成中断 ⑤ JSONL 会话落盘。
> 本 Demo 用真实 DeepSeek API + 独立工作区，**不污染项目仓库**。
> 依赖：JDK 21+、Maven、`DEEPSEEK_API_KEY` 环境变量。
> 验证日期：2026-08-11（已按本流程完整实测通过：①–⑤ 全部符合预期，下述数字为本轮实测值）。

## 前置：打包 + 小窗口配置

> 为什么用小窗口：你的主用模型（deepseek-v4-flash / pro）上下文是 **1M**，正常跑占用% 永远很小，告警/自动压缩无法演示。Demo 把 `context_window` 调成 **2000**，让占用% 快速爬升、告警与压缩都能触发。
> M4 占用口径已按**协议感知**修正（变更控制 2026-08-11）：OpenAI `prompt_tokens` 已含缓存直接采用；**Anthropic `input_tokens` 不含缓存命中，占用 = input + cacheRead**（覆盖 DeepSeek /anthropic 端点与真实 Claude）。对照样例见 `docs/DeepSeek-OpenAI-vs-Anthropic/`。

```bash
# 1) 打包（在项目根目录）
cd /Users/huangdazhu/IdeaProjects/zhu-code-agent
mvn -q package -DskipTests          # 产出 target/zhu-code-agent.jar

# 2) 独立 demo 工作区（工具只允许写这里，避免污染项目）
mkdir -p /Users/huangdazhu/IdeaProjects/zhuCodeAgentProjects/m4-demo-ws && cd /Users/huangdazhu/IdeaProjects/zhuCodeAgentProjects/m4-demo-ws

# 3) 配置（DeepSeek anthropic 兼容格式；小窗口让占用%/告警/自动压缩可触发）
cat > /Users/huangdazhu/IdeaProjects/zhuCodeAgentProjects/m4-demo-config.yml << 'CFG'
sessions_dir: /Users/huangdazhu/IdeaProjects/zhuCodeAgentProjects/m4-demo-sessions
providers:
  - name: deepseek-anthropic
    protocol: anthropic
    model: deepseek-v4-pro
    base_url: https://api.deepseek.com/anthropic
    api_key: ${DEEPSEEK_API_KEY}
    thinking: true
    context_window: 2000       # M4：故意调小窗口（真机 1M），让占用%/告警/自动压缩容易触发
    prompt_cache: true         # M4：prompt 缓存断点（默认开；端点不识别可设 false）
tool:
  max_calls_per_turn: 60
ui:
  tool_preview_lines: 5
  diff_max_lines: 200
context:
  alert_threshold: 0.5         # 占用 ≥ 50% 告警（先提醒）
  compact_threshold: 0.9       # 占用 ≥ 90% 自动压缩（默认值，后自救）；低于阈值时手动 /compact 可先演示折叠
  compact_target: 0.4
  snip_enabled: true
  keep_recent_turns: 2
CFG
```

## 启动

```bash
cd /Users/huangdazhu/IdeaProjects/zhuCodeAgentProjects/m4-demo-ws
java -jar /Users/huangdazhu/IdeaProjects/zhu-code-agent/target/zhu-code-agent.jar --config /Users/huangdazhu/IdeaProjects/zhuCodeAgentProjects/m4-demo-config.yml
```

- 「选择会话」→ 回车选 **新建对话**。
- 进入聊天后看到 `> ` 提示符。

---

## ① token 统计 + 协议感知占用（F1/F4）：完成行数字看得见

输入（多步工具循环，让本轮有多个请求、产生缓存）：

```
用 write_file 创建 demo2.txt，内容三行：aa / bb / cc，然后 read_file 读回
```

预期（每步工具权限确认输入 `a` 回车继续；最后一行是完成行）：

```
⚠ 上下文已达 59%（阈值 50%）
── 完成（end_turn · 本轮 in 309 / out 196 · 累计 505 · 占用 59% / 2000 · cache read 1024 / created 0）
```

> 验证点：
> 1. **本轮 in/out** = 该轮全部 agent 步骤求和（多步工具循环 > 单次请求）。
> 2. **占用 59%**：Anthropic 口径 = 最后一步 input（≈160）+ cacheRead（1024）≈ 1180 / 2000 ≈ 59%。若按旧口径只算 input 会显示 8%（严重低估）——这就是协议感知修正的效果。
> 3. **cache read 1024**：system + 工具定义命中 prompt 缓存；完成行单独展示 cache，不混入「本轮 in」。

## ② 告警（F2）：先提醒

继续输入一轮工具任务（让占用继续爬升）：

```
把 demo2.txt 的 bb 改成 BB，再 read_file 读回
```

预期完成行前出现**告警行**（占用 ≥ 50% 阈值）：

```
⚠ 上下文已达 74%（阈值 50%）
── 完成（end_turn · 本轮 in 418 / out 179 · 累计 1102 · 占用 74% / 2000 · cache read 1280 / created 0）
```

> 验证点：占用 ≥ `alert_threshold`（0.5）时输出「⚠ 上下文已达 P%（阈值 T%）」。74% = 最后一步 input（≈200）+ cacheRead（1280）= 1480 / 2000。

## ③ 压缩（F3）：先手动、后自动

### 3.1 手动 /compact（任意时刻，无阈值要求）

再跑一轮让会话累积 3 个真实轮次（`keep_recent_turns=2`，折叠需 ≥3 轮才有旧历史可折）：

```
把 demo2.txt 的 cc 改成 CC，再 read_file 读回
```

（本轮完成行应为 `占用 88% / 2000 · cache read 1664`，仍 <90% 不会自动压缩。）然后输入：

```
/compact
```

预期：

```
📦 已压缩：折叠 1 轮 · 丢弃 0 · 截断 0
```

> 验证点：
> 1. `/compact` 直接触发压缩，不受阈值/熔断限制。
> 2. 折叠的是最旧轮次（按 `keep_recent_turns` 保留最近 2 轮）；会话过短或刚折完时会提示「当前上下文无需压缩」（正常边界）。

### 3.2 自动压缩（生成前占用 ≥ 90%）

继续一轮工具任务（占用重新爬升）：

```
把 demo2.txt 的 aa 改成 AA，再 read_file 读回
```

（本轮完成行 `占用 95% / 2000 · cache read 1792`。）再输入任意一句触发**下一次生成前**的自动压缩：

```
好，谢谢，简单回复即可
```

预期：

```
⚠ 上下文占用达 95%（阈值 90%），自动压缩…
📦 已压缩：折叠 1 轮 · 丢弃 0 · 截断 0（缓存已重置，占用以下一次请求复核）
── 完成（end_turn · 本轮 in 654 / out 5 · 累计 3502 · 占用 78% / 2000 · cache read 896 / created 0）
```

> 验证点：
> 1. 生成前 `lastInputTokens/window ≥ compact_threshold`（0.9）→ 自动触发压缩（先提醒 ② 后自救 ③）。
> 2. 压缩后 `lastInputTokens` 重置为 0、缓存重建（cache read 1792 → 896）、占用回落（95% → 78%）。
> 3. 压缩摘要以「【上下文已压缩】」user 消息合入会话（见 ⑤），user/assistant 交替不破坏。

## ④ Ctrl+C 生成中断（F5）：单次中断不退出

输入一个问题（让生成有足够时长），生成中按一次 **Ctrl+C**：

```
写一篇 3000 字关于操作系统原理的文章，包含进程管理、内存管理、文件系统、调度算法四个章节
```

预期：

```
^C
⚠ 已中断
>            ← 回到提示符，进程不退出
```

> 验证点：
> 1. 单次 Ctrl+C 只中断本轮生成：半成品不写入会话，显示「⚠ 已中断」，回提示符。
> 2. **进程不退出**（JVM 级信号注册，非终端默认行为）；再输入问题可正常继续。
> 3. 会话落盘带 assistant「（已中断）」标记（见 ⑤）。
> 4. 若 Ctrl+C 恰在自动压缩阶段按下，会显示「⚠ 压缩失败: 摘要生成被中断」，压缩优雅降级、主生成继续——同样不退出。
> 5. **二次 Ctrl+C 逃生门**：一轮内 1.5s 内再按一次 Ctrl+C → 恢复系统默认 SIGINT，优雅退出（对齐 Codex「再按一次退出」）。正常退出用 `/exit` 或 `/quit`。

## ⑤ JSONL 会话落盘（F6）：可检查、可恢复

退出（`/exit`）后检查会话文件：

```bash
ls -la /Users/huangdazhu/IdeaProjects/zhuCodeAgentProjects/m4-demo-sessions/
cat /Users/huangdazhu/IdeaProjects/zhuCodeAgentProjects/m4-demo-sessions/*.jsonl
```

预期（每行一个 JSON）：

```json
{"id":"20260811-...","createdAt":"...","provider":{"name":"deepseek-anthropic","protocol":"anthropic","model":"deepseek-v4-pro","baseUrl":"https://api.deepseek.com/anthropic"},"totalInputTokens":3502,"totalOutputTokens":...}
{"role":"user","content":[{"type":"text","text":"【上下文已压缩】\n..."}]}
{"role":"assistant","thinking":"...","content":[...]}
{"role":"assistant","content":[{"type":"text","text":"（已中断）"}]}
```

> 验证点：
> 1. 首行 **meta 行**：id / 时间 / provider / 累计字段（`totalInputTokens` / `totalOutputTokens`），随会话追加更新（压缩、中断轮之后都会更新）。
> 2. 消息行每行一个 JSON，user/assistant **交替**、压缩摘要合并为一条 user 消息。
> 3. Ctrl+C 中断轮有 `assistant「（已中断）」` 标记。
> 4. 重启 app → 选择该会话 → 累计继续累加、消息完整恢复。

---

## 安全观察点

- Demo 独立工作区 `m4-demo-ws` 与独立 sessions 目录，不污染项目仓库。
- 配置里 `api_key` 用 `${DEEPSEEK_API_KEY}` 环境变量引用，不落盘明文密钥。
- 中断/压缩/落盘均不改变工具权限模型；`undo`/`rewind` 快照能力不受影响。

## 常见问题

- **占用% 没到 50%？** 多轮工具任务会让 input+cacheRead 快速爬升；若仍很低，检查 `context_window` 是否生效（配置里 2000）。
- **cache read 一直是 0？** 需要足够长的公共前缀才命中缓存（见 `docs/DeepSeek-OpenAI-vs-Anthropic/README.md`）；若端点不识别 `cache_control`，设 `prompt_cache: false`。
- **手动 /compact 提示「无需压缩」？** 折叠需要会话超过 `keep_recent_turns`（2）个真实轮次；刚被自动压缩折完时也没有可折内容，属正常边界。
- **自动压缩没触发？** 确认占用 ≥ `compact_threshold`（0.9）；演示流程里第 5 轮前占用 95% 会触发。
- **Ctrl+C 把整个程序退出了？** 单次不应退出；若连按两次（1.5s 内）会走逃生门退出，属预期。
