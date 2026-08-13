# M5 并行与 Subagents 本地 Demo

> 目的：在本地真实跑通 M5 的三个核心能力——① 客户端并发安全（per-call 状态 + 实例缓存复用，并行子任务共享同一客户端实例并发 `stream()`）② 并行工具执行（读并行/写串行/保序）③ Subagents/Task（父 agent 派子任务 → 独立会话/摘要回填/权限/护栏/级联中断）。
> 本 Demo 用真实 DeepSeek API + 独立工作区，**不污染项目仓库**。
> 依赖：JDK 21+、Maven、`DEEPSEEK_API_KEY` 环境变量。
> 验证日期：2026-08-13（两轮完整实测通过——上午首轮 + 下午 R5 修复后复跑；下述数字为最新一轮实测值）。

## 前置：打包 + 独立工作区 + 配置

```bash
# 1) 打包（在项目根目录）
cd /Users/huangdazhu/IdeaProjects/zhu-code-agent
mvn -q package -DskipTests          # 产出 target/zhu-code-agent.jar

# 2) 独立 demo 工作区（工具只允许写这里，避免污染项目）
mkdir -p /Users/huangdazhu/IdeaProjects/zhuCodeAgentProjects/m5-demo-ws && cd /Users/huangdazhu/IdeaProjects/zhuCodeAgentProjects/m5-demo-ws
echo "hello m5 smoke test" > a.txt

# 3) 配置（DeepSeek OpenAI 兼容格式；M5 agent.* 护栏）
cat > /Users/huangdazhu/IdeaProjects/zhuCodeAgentProjects/m5-demo-config.yml << 'CFG'
sessions_dir: /Users/huangdazhu/IdeaProjects/zhuCodeAgentProjects/m5-demo-sessions
providers:
  - name: deepseek
    protocol: openai
    model: deepseek-v4-flash
    base_url: https://api.deepseek.com
    api_key: ${DEEPSEEK_API_KEY}
tool:
  max_calls_per_turn: 60
ui:
  tool_preview_lines: 5
  diff_max_lines: 200
context:
  alert_threshold: 0.8
  compact_threshold: 0.9
  compact_target: 0.6
agent:
  max_subagent_depth: 2        # M5：父→子→孙封顶（孙 agent 工具池不再注册 task）
  max_parallel_subagents: 4    # M5：并行子任务上限
  max_steps_per_subagent: 30   # M5：单个子任务步数上限
CFG
```

## 启动

```bash
cd /Users/huangdazhu/IdeaProjects/zhuCodeAgentProjects/m5-demo-ws
java -jar /Users/huangdazhu/IdeaProjects/zhu-code-agent/target/zhu-code-agent.jar --config /Users/huangdazhu/IdeaProjects/zhuCodeAgentProjects/m5-demo-config.yml
```

- 「选择会话」→ 回车选 **新建对话**。
- 进入聊天后看到 `> ` 提示符。

---

## ① 子任务主链路（F3.1/F3.2/F3.6）：task 派子任务 → 独立会话 → 摘要回填

输入（引导父 agent 用 `task` 工具派子任务）：

```
请使用 task 工具派一个子任务，让子任务读取当前目录的 a.txt 文件并用一行总结内容。子任务完成后把它的报告告诉我。
```

预期（父 agent 自主调用 `task` → 子任务折叠单行 → 摘要回填 → 父综合输出）：

```
🔧 task {"prompt": "请执行以下子任务：\n\n1. 使用 read_file 工具（或适当的工具）读取当前工作区根目录下的 a.txt 文件内容。…}
⏳ 子任务#1: 请执行以下子任务： 1. 使用 read_file 工具（或适当的工具）读取当前工作区根目录下的 a.txt 文件内容。 …
✓ 子任务#1: [task#1] 状态=完成 · a.txt 的内容是一行测试文本 "hello m5 smoke test"。 · in 2579/out 61
└ task → [task#1] 状态=完成 · a.txt 的内容是一行测试文本 "hello m5 smoke test"。 · in 2579/out 61
── 完成（stop · 本轮 in 2646 / out 256 · 累计 2902 · 占用 0% / 1000000 · cache read 1408 / created 0）
```

> 验证点：
> 1. **父 agent 自主派发**：`🔧 task {…}` 说明模型在 ReAct 循环里调用了 `task` 工具（无需用户手动触发）。
> 2. **折叠单行**：`⏳ 子任务#1: …`（执行中）→ `✓ 子任务#1: [task#1] 状态=完成 · …`（完成）——子任务内部流式内容不滚动到主终端。
> 3. **摘要回填**：`[task#1] 状态=完成 · 摘要 · in 2579/out 61` 作为 tool_result 回填父；子任务完整历史不进入父上下文（会话里只有摘要文本）。
> 4. **父继续循环**：父拿到摘要后综合输出最终报告。

## ② 并行子任务（F3.7）+ 客户端并发安全（F1）

> 客户端并发安全重构（per-call 状态持有者 + `LlmClientFactory` 按 provider 名缓存复用单例）已由 `ConcurrentStreamTest` 与 `SubagentIntegrationTest#openAiParallelSubtasksRunConcurrently` 覆盖，**2026-08-13 真机实测通过**（见下方冒烟记录）。真机复现步骤：

输入（引导父 agent 一次派两个并行子任务）：

```
用 task 工具同时派两个子任务：子任务1 用 grep 搜索工作区里 "hello" 出现在哪些文件；子任务2 用 glob 列出工作区所有 .txt 文件。两个都完成后汇总给我。
```

预期（两个子任务折叠行先后出现，各自摘要回填，父汇总）：

```
⏳ 子任务#1: …（grep hello）
⏳ 子任务#2: …（glob *.txt）
✓ 子任务#1: [task#1] 状态=完成 · …（grep 结果摘要）· in X/out Y
✓ 子任务#2: [task#2] 状态=完成 · …（glob 结果摘要）· in X/out Y
```

> 验证点：
> 1. 两个 `task` 调用并行执行（两个子任务共享同一客户端实例并发 `stream()`，per-call 状态隔离保证互不串扰）。
> 2. 各自摘要独立回填、顺序对应原调用（`tool_result` 保序）。

## ③ 子任务权限确认回主 UI（F3.4）：来源标注 + 串行弹窗

输入（引导子任务写文件）：

```
再用 task 工具派一个子任务，让子任务用 write_file 工具在当前目录创建 b.txt，内容为 "written by subagent"。
```

预期（子任务写文件触发权限确认，主 UI 弹窗并标注来源）：

```
⏳ 子任务#2: 请执行以下子任务： 1. 使用 write_file 工具在当前工作区根目录下创建文件 b.txt。 …
[子任务#2] 请求权限: write_file b.txt
[权限] write_file b.txt → 允许(a) / 拒绝(d) / 总是允许本次(s)？(输入后回车)   ← 输入 a 回车
```

> 验证点：
> 1. **来源标注**：`[子任务#2] 请求权限: …`——用户知道是哪个子任务在请求。
> 2. **串行弹窗**：多个子任务/主会话同时要确认时，同一时刻只有一个弹窗（全局串行锁）。
> 3. **权限继承**：若父已对该路径「总是允许」(s)，子任务内同名操作自动放行、不再弹窗。
> 4. 允许后子任务继续执行，最终摘要回填（实测：`✓ 子任务#2: [task#2] 状态=完成 · 文件已创建并内容正确。… · in 5870/out 631`），`b.txt` 内容为 `written by subagent`。

## ④ Ctrl+C 级联中断（F3.8）

输入（引导子任务执行长命令）：

```
用 task 工具派一个子任务，让子任务用 bash 执行命令 "sleep 20" 然后报告完成。
```

预期（允许 bash 后、`sleep 20` 执行中按 **Ctrl+C**）：

```
[子任务#4] 请求权限: bash sleep 20      ← 输入 a 回车允许，sleep 开始
^C
✓ 子任务#4: [task#4] 状态=被中断 · 生成被中断 · in 1207/out 66
⚠ 已中断
> 
```

> 验证点：
> 1. **级联取消**：Ctrl+C 中断所有在途子任务线程（`SubagentCoordinator.cancelAll()`）。
> 2. **半成品不写入**：子任务摘要标记「被中断」，本轮中断、回到提示符，会话不追加悬空 tool_use。
> 3. 二次 Ctrl+C 逃生门（M4 机制）在多流下沿用。

## ⑤ 护栏（F3.5）：深度封顶 + 工具池裁剪

- **工具池裁剪**：深度已达上限（层 2 孙 agent）的子 agent 工具池**不注册 `task` 工具**——模型看不到就不会调用（已由 `SubagentIntegrationTest#nestedDepthPrunesTaskAtGrandchild` 断言孙请求体 tools 无 task）。
- **运行时兜底拒绝**：任何超限调用返回可读错误「嵌套深度超限」。
- **并行/步数上限**：`max_parallel_subagents`（默认 4）/ `max_steps_per_subagent`（默认 30）超限均返回可读错误、不崩溃。

## ⑥ 并行工具执行（F2，读并行/写串行/保序）

> 已由 `ParallelToolExecutorTest` 覆盖（`[R1,R2,W1,R3]` 结果保序 / 读段并行耗时 < 串行 / 写·bash 串行 / 单失败隔离 / 中断丢弃）。真机复现指令：

**输入**（先准备种子文件 `x1.txt` / `x2.txt`）：

```
依次用 read_file 读取 x1.txt 和 x2.txt 的内容（各读前 3 行），然后用 write_file 创建 sum.txt 汇总两文件内容。
```

**预期**（一次工具调用批次 = `[R1, R2, W1]`）：

```
🔧 read_file x1.txt（limit 3）
🔧 read_file x2.txt（limit 3）      ← 两个只读同一段并行执行（无权限弹窗、无相互等待）
🔧 write_file sum.txt               ← 写类单独串行，需权限确认
└ read_file → …（x1.txt 内容）
└ read_file → …（x2.txt 内容）
└ write_file → …（diff/创建确认）
```

> 验证点：
> 1. **读并行**：`R1`/`R2` 无权限弹窗、无串行等待（同段内并行；可通过多文件大内容时耗时观察，比串行快）。
> 2. **写串行**：`W1` 单独执行、需权限确认（写类/bash 一律串行 + 确认，危险强制）。
> 3. **结果保序**：tool_result 按原调用顺序回填（R1 → R2 → W1），与完成先后无关。
> 4. 若同批含 `bash`，它同样归串行段（无法静态判读/写，安全第一）。

---

## 真机冒烟记录（2026-08-13）

| 场景 | 实测结果 |
|------|---------|
| ① 子任务主链路（读 a.txt） | `✓ 子任务#1: [task#1] 状态=完成 · a.txt 的内容为「hello m5 smoke test」… · in 2436/out 88` → 父综合输出 ✅（父先自行 bash 侦查后派 task） |
| ② 并行子任务 | 父一次两个 `task` 并行：`✓ 子任务#2: [task#2] 状态=完成 · glob **/*.txt 未匹配 · in 2481/out 191`、`✓ 子任务#3: [task#3] 状态=完成 · grep hello → a.txt:1 · in 2523/out 250` → 父发现矛盾自行核验修正 ✅（共享同一客户端实例并发 stream，F1 真机确认） |
| ③ 子任务权限确认（写 c.txt） | `[子任务#4] 请求权限: write_file c.txt` + `[权限] … a/d/s` 弹窗；允许后 `✓ 子任务#4: [task#4] 状态=完成 · … c.txt 写入成功 … · in 3993/out 371`，`c.txt` = `written by subagent v2` ✅ |
| ④ 级联中断（sleep 20 + Ctrl+C） | `✓ 子任务#5: [task#5] 状态=被中断 · 生成被中断 · in 1196/out 76` + `⚠ 已中断` + 回提示符 ✅ |
| ⑥ 并行工具 | 集成测试覆盖（`ParallelToolExecutorTest`：保序/读并行/写串行/失败隔离/中断），真机可在②场景中间接观察（子任务内 read/grep 并行） |

## 回归基线

- `mvn clean test`：**244/244 全绿**（M1–M4 215 + M5 新增 29），M1–M4 功能无回归（详见 `docs/checklist.md` Review 修复验证节）。
