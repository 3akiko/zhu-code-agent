# M3 文件编辑增强与 Plan Mode 本地 Demo

> 目的：在本地真实跑通 M3 的四个核心能力——① diff 展示（看得见）② /plan 先计划后执行（含修改意见）③ 快照回滚 undo/rewind（可反悔）④ 权限模式（acceptEdits / bypassPermissions）。
> 本 Demo 用真实 DeepSeek API + 独立工作区，**不污染项目仓库**。
> 依赖：JDK 21+、Maven、`DEEPSEEK_API_KEY` 环境变量。
> 验证日期：2026-08-09（已按本流程完整实测通过：①–⑥ 全部符合预期）。

## 前置：打包 + 配置

```bash
# 1) 打包（在项目根目录）
cd /Users/huangdazhu/IdeaProjects/zhu-code-agent
mvn -q package -DskipTests          # 产出 target/zhu-code-agent.jar

# 2) 独立 demo 工作区（工具只允许写这里，避免污染项目）
mkdir -p /Users/huangdazhu/IdeaProjects/zhuCodeAgentProjects/m3-demo-ws && cd /Users/huangdazhu/IdeaProjects/zhuCodeAgentProjects/m3-demo-ws

# 3) 配置（DeepSeek anthropic 兼容格式，thinking 灰字可用；diff_max_lines 为 M3 新增配置）
cat > /Users/huangdazhu/IdeaProjects/zhuCodeAgentProjects/m3-demo-config.yml << 'CFG'
providers:
  - name: deepseek-anthropic
    protocol: anthropic
    model: deepseek-v4-pro
    base_url: https://api.deepseek.com/anthropic
    api_key: ${DEEPSEEK_API_KEY}
    thinking: true
tool:
  max_calls_per_turn: 60
ui:
  tool_preview_lines: 5
  diff_max_lines: 200          # M3：diff 展示最大行数（超长截断并标注）
CFG
```

## 启动

```bash
cd /Users/huangdazhu/IdeaProjects/zhuCodeAgentProjects/m3-demo-ws
java -jar /Users/huangdazhu/IdeaProjects/zhu-code-agent/target/zhu-code-agent.jar --config /Users/huangdazhu/IdeaProjects/zhuCodeAgentProjects/m3-demo-config.yml
```

- 「选择会话」→ 回车选 **新建对话**。
- 进入聊天后看到 `> ` 提示符。

---

## ① diff 展示（F1）：改了什么看得见

输入：

```
用 write_file 创建 demo.txt，内容三行：line1 / line2 / line3，然后用 edit_file 把 line2 改成 LINE2
```

预期：
1. `write_file` 权限确认 → 输入 `a` 回车 → 新建概览（**无 diff，因为是新文件**）：
   `└ write_file → 已创建 .../demo.txt（3 行 / 17 字节）`
2. `edit_file` 权限确认 → 输入 `a` 回车 → **完整彩色 diff**（不按 5 行预览截断）：

```
└ edit_file → 已替换 1 处 → .../demo.txt
@@ -2,1 +2,1 @@      ← 亮青
 line1                ← 上下文
-line2                ← 红色
+LINE2                ← 绿色
 line3                ← 上下文
```

> 验证点：diff 在工具结果内嵌、TUI 完整展示 + 颜色（+ 绿 / - 红 / @@ 亮青）。这就是"改了什么看得见"。

---

## ② /plan 先计划后执行（F2）：含修改意见重新生成（A1）

### 2.1 进入计划模式

输入：

```
/plan 在 workspace 创建 plan-demo.txt，内容三行：p1 / p2 / p3，并说明执行步骤
```

预期：
- `📋 计划模式：先产出计划，批准后才执行（只读调研）`
- 模型思考（灰字）→ 只读调研（可能出现 `🔧 glob ...`，**不会弹出任何写权限确认**——write/edit/bash 在计划阶段被拦截）
- 输出计划 → `── 计划完成，请审批 ──` → 提示：

```
[计划] 批准执行(y) / 拒绝(d) / 输入修改意见重新生成？
```

### 2.2 修改意见 → 重新生成（A1）

输入（非 y / d，作为修改意见）：

```
直接用 write_file 一步完成即可，不需要分多个工具步骤
```

预期：`↻ 已收到修改意见，重新生成计划…` → 模型基于意见输出**修订后的计划**（步骤变少）→ 再次出现审批提示。

### 2.3 批准执行

输入 `y` 回车：

预期：
- `✔ 已批准，开始执行…` → 执行阶段模型调用 `write_file` → **仍弹权限确认**（批准不等于免确认，安全语义不变）→ 输入 `a` 回车
- `└ write_file → 已创建 .../plan-demo.txt（3 行 / 5 字节）`
- `── 完成（end_turn · in ... / out ... tokens）`

> 验证点：计划阶段零写副作用 + 只读调研；意见→重新生成；批准后同轮执行且写仍按权限模式确认。
> 另可验证 **拒绝路径**：再来一次 `/plan ...`，计划出来后输入 `d` → `已拒绝计划，本轮结束（无副作用）`，工作区无新文件。

---

## ③ 权限模式（F4）：acceptEdits / bypassPermissions

### 3.1 查看当前模式

```
/permissions
```

预期：`权限模式：normal（逐项确认）...` + 切换提示。

### 3.2 acceptEdits：写文件自动批准，bash 仍确认

```
/permissions acceptEdits
```

预期：`✔ 权限模式 → acceptEdits（文件编辑自动批准）`。

输入：

```
用 write_file 创建 auto.txt 内容 auto-ok，然后用 bash 执行 echo hello
```

预期：`write_file` **不弹确认直接执行**（`└ write_file → 已创建 .../auto.txt（1 行 / 7 字节）`）；`bash echo hello` **仍弹权限确认**（输入 `a` 回车，看到 `hello`）。

### 3.3 bypassPermissions：bash 非危险也自动批准

```
/permissions bypassPermissions
```

预期：`✔ 权限模式 → bypassPermissions（自动批准，危险命令仍确认）`。

再输入：

```
用 bash 执行 echo bypass-ok，然后创建 file.txt 内容 bye
```

预期：`echo bypass-ok` 与 `write_file` **均不弹确认**直接执行。

### 3.4 危险命令红线不破（bypass 下仍强制确认）

输入：

```
用 bash 执行 rm -rf /tmp/evil-dir 看看
```

预期：`rm -rf`（危险命令）**即使 bypass 模式也弹权限确认** → 输入 `d` 回车 → `⚠ 已拒绝执行（用户选择拒绝）`，且命令未执行。

> 验证点：三档模式语义；危险命令强制确认（M2 安全红线不削弱）。模式仅内存——退出程序重启后回到 normal（见 ⑤）。

---

## ④ /undo /rewind（F3）：可反悔

> 经过 ①②③，当前会话已有多个检查点（每次 write/edit 前落盘一个）。

### 4.1 /undo 撤销最近一次写

```
/undo
```

预期：`✔ 已回滚到 检查点 #000N 之前` + `已恢复 ... / 已删除新建文件 ...`（撤销最近一次 write/edit，可连续 `/undo` 多次逐步回退）。

### 4.2 /rewind 列表选择回退

```
/rewind
```

预期：弹出检查点列表（**最新在上**，含时间 + 摘要如 `write_file: plan-demo.txt`、`edit_file: demo.txt`）→ ↑/↓ 选择、Enter 确认。选任意一个后：

```
✔ 已回滚到 检查点 #000M 之前
  已恢复 demo.txt（edit_file: demo.txt 前的状态）
  ...
```

该检查点**之后的所有 write/edit 效果被撤销，其后检查点被丢弃**。

> 验证点：检查点按会话落盘 `~/.zhu-code-agent/snapshots/<会话ID>/checkpoints.json`；undo=回退最近检查点（快捷），rewind=列表回退（统一机制）。

### 4.3 检查快照与回滚记录落盘

```bash
# 找到本会话 id（与 ~/.zhu-code-agent/sessions/ 下的会话 json 同名）
ls ~/.zhu-code-agent/snapshots/
cat ~/.zhu-code-agent/snapshots/<会话ID>/checkpoints.json   # 剩余检查点（含 beforeContent / summary）
grep -o "\[回滚\][^\"]*" ~/.zhu-code-agent/sessions/<会话ID>.json   # [回滚] 记录已写回会话
```

预期：`checkpoints.json` 是剩余检查点数组；会话 JSON 含 `[回滚] 已回滚到 检查点 ...（已恢复 ...）`。

---

## ⑤ 跨会话（F3/F1）：重启后仍可回滚、diff 可见

1. 先留一个检查点：输入 `用 write_file 创建 cross.txt 内容 keep`，允许后完成。
2. `/exit` 退出（自动保存会话）。
3. 重启（同一命令），「选择会话」里选**本会话**（标题应为首次 /plan 或写文件任务）。
4. `/undo`：

预期：**重启后 `/undo` 仍可用**——`✔ 已回滚到 检查点 #0001 之前` + `已删除新建文件 cross.txt`（检查点落盘、跨会话有效）。
5. `/permissions`：预期回到 **normal**（权限模式仅内存、退出重置）。

> 另外：向上翻看历史，① 的彩色 diff 与 ④ 的 `[回滚]` 记录在恢复后依然可见（工具结果随会话落盘）。

---

## ⑥ 可选：超长 diff 截断（F1/N6）+ bash 改动不追踪（F3 边界）

### 6.1 超长 diff 截断

输入（用 bash 生成 120 行文件，再整体覆写为不同内容 → diff 超 200 行截断）：

```
用 bash 执行 seq 1 120 > big.txt，然后用 write_file 把 big.txt 覆写成 120 行不同的内容（每行 "new line i"）
```

预期：两次确认（bash、write_file）后，write_file 结果 diff 以 `…已截断，共 241~242 行` 结尾（保留前 200 行 + 标注；精确行数视末尾换行 ±1）。

### 6.2 bash 改动不追踪

上一步 `bash` 创建的 `big.txt` 不会被快照（bash 副作用不追踪）——`/undo` 只会撤销 `write_file` 覆写，不会"还原"bash 的 `seq` 生成（M3 声明的边界）。

---

## 验证点对照表

| 功能点 | Demo 步骤 | 观察 | 对应 spec / 代码 |
|--------|-----------|------|------------------|
| diff 展示（可见） | ① / ⑥ | 工具结果内嵌 ±diff、TUI 彩色、超长截断标注 | spec F1 → `DiffGenerator` + `EditFileTool/WriteFileTool` + `ChatApp.coloredDiff` |
| diff 落盘可见 | ⑤ | 恢复会话后历史 diff 仍在 | spec F1 → tool_result 随会话落盘 |
| /plan 只读调研 + 零副作用 | ②.1 | 计划阶段无写确认、无文件产生 | spec F2 → `PlanModeExecutor` |
| /plan 修改意见重新生成 | ②.2 | `↻` 后计划按意见修订 | spec F2（变更记录）→ `AgentRunner.runPlanContinue` |
| /plan 批准后执行、写仍确认 | ②.3 | 批准后仍弹权限确认 | spec F2 → `ChatApp.executePlannedTurn` |
| /plan 拒绝零副作用 | ② 备注 | `d` 后无新文件 | spec F2/AC2 |
| 快照检查点落盘 | ④.3 | `snapshots/<会话ID>/checkpoints.json` | spec F3 → `FileHistory` |
| /undo 撤销最近、可连续 | ④.1 | 文件恢复/新建删除 | spec F3 → `FileHistory.undo` |
| /rewind 列表回退、丢弃其后 | ④.2 | 恢复到所选检查点之前 | spec F3 → `FileHistory.rewindTo` |
| 回滚记录写回会话 | ④.3 | 会话 JSON 含 `[回滚] ...` | spec F3/AC3 → `ChatApp.renderRollback` |
| 跨会话回滚 | ⑤ | 重启后 /undo 仍可用 | spec F3/AC3（检查点落盘） |
| 权限模式三档 | ③.1-3.3 | 切换生效、写/bash 行为变化 | spec F4 → `PermissionManager` + `PermissionMode` |
| 危险命令强制确认（红线） | ③.4 | bypass 下 rm -rf 仍确认 | spec F4/N3 → `DangerGuard` |
| 模式仅内存 | ⑤ | 重启回到 normal | spec F4 → 不落盘 |
| bash 改动不追踪 | ⑥.2 | /undo 不还原 bash 副作用 | spec F3 声明边界 |
| diff_max_lines 配置 | 前置 / ⑥.1 | 截断标注随配置 | spec N1 → `ui.diff_max_lines` |

## 常见问题

- **JLine native-access 警告**：无害，可用 `java --enable-native-access=ALL-UNNAMED -jar ...` 消除。
- **模型不调工具**：提示词里明确说"用 write_file / edit_file / bash / glob 工具"更稳定。
- **权限确认必须回车**：输入 `a`/`d`/`s` 后按回车。
- **/plan 想再来一次**：`/plan` 是单轮命令，每次独立"计划→审批→执行"闭环；不满意可在审批处输入修改意见重新生成，或 `d` 后重新 `/plan`。
- **快照/检查点目录**：`~/.zhu-code-agent/snapshots/`（程序专属，工具不可写、不占用工作区）；与 `~/.zhu-code-agent/sessions/` 的会话 JSON 同名（会话 id）。
- **demo 后清理**：`rm -f /Users/huangdazhu/IdeaProjects/zhuCodeAgentProjects/m3-demo-config.yml && rm -rf /Users/huangdazhu/IdeaProjects/zhuCodeAgentProjects/m3-demo-ws`（可选；快照与会话留在 `~/.zhu-code-agent/`，可用 `/permissions reset` 清权限记忆、`rm ~/.zhu-code-agent/snapshots/<会话ID>` 清快照）。
