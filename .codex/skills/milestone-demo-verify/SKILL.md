---
name: milestone-demo-verify
description: 仅 zhuCodeAgent 项目（/Users/huangdazhu/IdeaProjects/zhu-code-agent）使用。里程碑收尾时，按本次代码变更盘点功能点，生成/更新一份 demo 文档（docs/demo-MN*.md，仿 demo-M5并行与Subagents.md），并在本地用真实 DeepSeek API 完整真机验证（PTY），回填实测记录供用户快速复核。当用户说「按本次变更写 demo / 收尾真机验证 / 快速验证」时使用。
---

# 里程碑 Demo 生成与真机验证（zhuCodeAgent 项目专用）

目标：把「本次里程碑做了什么」变成一份**可复现的 demo 文档** + **已实测的证据**，用户照文档即可快速复核。

## 前置

1. 校验 cwd 为 `/Users/huangdazhu/IdeaProjects/zhu-code-agent`（非本项目则中止并说明）。
2. 读 `docs/roadmap.md` 该里程碑章节（目标/包含/不做）+ 已批准 `docs/spec.md`（功能需求 F）→ 盘点功能点清单。
3. 基线：`mvn clean test` 全绿（沙箱外，`require_escalated` + prefix_rule `["mvn","clean","test"]`）。
4. 打包：`mvn -q package -DskipTests` → `target/zhu-code-agent.jar`（**`mvn clean test` 会清 target，必须先打包**）。
5. 确认 `DEEPSEEK_API_KEY` 已设置。

## 流程

1. **写 demo 文档初稿** `docs/demo-MN*.md`：参照 `docs/demo-M5并行与Subagents.md` 的结构（模板见 `references/demo-template.md`）——
   标题 + 目的块（核心能力/独立工作区/依赖/验证日期）→ 前置（打包 + 独立工作区 + 临时 config）→ 启动 → 分场景（每场景：**引导消息** + **预期输出** + **验证点**）→ 真机冒烟记录表 → 回归基线。
   - 每个功能点至少一个场景；引导消息要**显式指示模型使用目标能力**（如「用 task 工具派…」）。
2. **准备独立工作区**（`/tmp/` 或 `zhuCodeAgentProjects/`，不污染仓库）+ 种子文件 + 临时 config（provider + 该里程碑相关配置，如 M5 `agent:` 护栏、M4 `context:` 小窗口）。
3. **真机逐场景验证**：`java -jar target/zhu-code-agent.jar --config <cfg>`（`require_escalated` + `tty=true`），会话选择页回车「新建对话」，按场景输入引导消息，观察输出。
   - 权限确认：`a` 回车允许；`s` 总是允许；`d` 拒绝。
   - 中断验证：长命令执行中发 `\u0003`（Ctrl+C），观察级联/取消语义。
   - **记录每个场景的实测输出**（状态行/摘要/in out 数字）——这是 demo 的「真机冒烟记录」证据。
4. **回填**：把实测数字 + 验证日期写回 demo 文档；若行为与预期不符，报告用户（可能走变更控制，不擅自改代码）。
5. **退出** `/exit`；核实产物（如写入的文件内容）。
6. **汇报**：场景清单 + 每场景实测证据 + 冒烟记录表位置。

## 关键注意（M5 实测教训）

- 真机必须沙箱外 + PTY：`java -jar` / `mvn` 用 `require_escalated`；`tty=true` 才能交互。
- **exec PTY 是 DumbTerminal，与用户真实终端（PosixSysTerminal）行为可能不同**（如权限弹窗后 Ctrl+C 的 SIGINT 语义）——涉及中断/信号的场景，提示用户在真实终端复验。
- 子任务/并行输出是折叠单行（`⏳/✓ 子任务#N`），不是没有输出。
- 模型可能不按剧本走（先自行侦查、结果矛盾后自主核验）——这是正常 agent 行为，记录实际输出即可，不要因此判失败。
- 数字要真实：状态/摘要/in out 都来自实际运行，与「验证日期」一起更新。
- demo 只在独立工作区跑，回归基线（`mvn clean test`）不因 demo 改变。

## 完成标准

- 每个功能点都有 demo 场景 + 实测输出证据（状态行/摘要/完成行）。
- demo 文档「真机冒烟记录」已回填实测 + 验证日期。
- 已提示用户哪些场景需在真实终端复验（如有）。
