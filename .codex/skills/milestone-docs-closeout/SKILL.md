---
name: milestone-docs-closeout
description: 仅 zhuCodeAgent 项目（/Users/huangdazhu/IdeaProjects/zhu-code-agent）使用。里程碑完成时，更新项目全部重要文档并归档，做到"一个不落"：验收报告、里程碑归档、README、implementation、CHANGELOG、roadmap、resume（简历）、TODO、交接文档、面试题库、demo 文档。当用户说「做一下 M{N} 文档收尾 / 更新文档 / 收尾别遗漏」时使用。文档更新后再提交（先交用户 review 或按用户指示）。
---

# 里程碑文档收尾（zhuCodeAgent 项目专用）

目标：里程碑完成并测试全绿后，把项目全部重要文档同步到"已实现"口径，**不漏一项**，方便面试/交接/下一里程碑接续。

## 前置

1. 校验 cwd 为 `/Users/huangdazhu/IdeaProjects/zhu-code-agent`（非本项目则中止并说明）。
2. 确认开发完成：`mvn clean test` 全绿（记下测试总数与 M{N} 新增数）。
3. 确认本里程碑四文档（`docs/spec.md` / `plan.md` / `task.md` / `checklist.md`）状态均为 `approved`；如有 review 修复（R1…Rn）已记录进 task/checklist。
4. 读 `docs/roadmap.md` 该里程碑章节 + 上一份 `docs/交接-M{N-1}.md`，了解范围与已完成项。

## 收尾检查清单（按序执行，逐项打勾）

- [ ] **1. 验收报告**：新建 `docs/验收报告-M{N}.md`（参照 `docs/验收报告-M5.md`）——通过项按 checklist 逐条 + 证据（测试类/代码走查/真机），Review 修复单列；顶部写测试总数与新增数。
- [ ] **2. 里程碑归档**：`mkdir -p docs/milestones/m{N}` 并 `cp docs/spec.md docs/plan.md docs/task.md docs/checklist.md docs/resume.md docs/milestones/m{N}/`；根目录四文档保持 approved。
- [ ] **3. README.md**：① 顶部「状态」行改为 M{N} 已完成 + 一句话目标；②「当前功能」追加本里程碑能力（参照已有 M4/M5 条目风格）；③「目录结构」如有新包/新职责更新对应行；④「测试」段追加覆盖说明 + 更新测试总数；⑤「相关文档」追加 M{N} 归档/验收/demo 链接。
- [ ] **4. docs/implementation.md**：追加 `## M{N} …` 节——实现了什么 / 怎么实现的（架构图、关键机制、核心决策）/ 与 Claude Code·Codex 对比表 / 踩坑记录（每条一行，M5 起至少含并发/线程/真机类踩坑）。
- [ ] **5. CHANGELOG.md**：`[Unreleased]` 顶部追加 `### Added（M{N}…）` / `### Fixed（M{N}）` / `### Changed（M{N}）`；Fixed 列 review 修复项。
- [ ] **6. docs/roadmap.md**：① 顶部「最后更新」日期 + 概述；② 第 3 节里程碑总览表该行状态改 `✅ 已完成（YYYY-MM-DD）`；③ 第 4 节该里程碑详情标题加 `✅ 已完成`；④ **第 5 节特性对比表新增 M{N} 列**（表头 + 每行对应列填值；注意 M1–M{N-1} 列与 Claude Code/Codex 列都要保留，行数对齐！M5 曾因漏填 3 行列错位）。
- [ ] **7. docs/resume.md（root 活文档）**：① 项目简介加 M{N} 一行；② 项目亮点追加 `N+1、**…（M{N}）**` 条目（面试重点，写清能力/对比/关键决策）；③ 工程化质量条目更新测试数与 review 修复；④ 对比表更新（Subagents/MCP 等行、OS 沙箱行、技术栈行）；⑤ 一句话亮点更新。**归档快照**在步骤 2 一并复制。
- [ ] **8. docs/TODO.md**：勾掉本里程碑已完成的技术债（标日期）；追加「M{N} 开发中发现的技术债务」小节（新问题 + 归属里程碑）。
- [ ] **9. demo 文档**：调用 `milestone-demo-verify` skill 生成/更新 `docs/demo-M{N}*.md` 并真机验证回填（若已单独做过则确认存在且冒烟记录完整）。
- [ ] **10. 交接文档**：更新 `docs/交接-M{N}.md` 状态行为「**M{N} 已完成（日期）**…M{N+1} 范围与前置见 roadmap」；新建 `docs/交接-M{N+1}.md` 骨架（基线 = M{N} 完成点、范围 = roadmap M{N+1}、踩坑 = 本里程碑新增、协作约定照抄）——方便下个会话直接接续。
- [ ] **11. 面试题库**：更新 `docs/面试题库.md`——追加本里程碑可讲的面试题与回答要点（如 M5：并行打印/权限控制怎么处理、per-call 状态 vs ThreadLocal、级联中断、深度护栏；答案可参考 `docs/implementation.md` 与 review 讨论）。
- [ ] **12. 自查**：`git status` 确认待提交清单；把「检查清单逐项打勾」的结论汇报给用户，**先交用户 review 再提交**（用户明确同意后才 git commit + push）。

## 关键注意

- **对比表最易错**：新增里程碑列时，逐行核对列数（表头 N 列 ↔ 每行 N 列），M5 曾因 3 行漏填导致表格错位。
- **resume.md 要 root + 归档双份**：root 活文档持续更新；归档快照随 milestones 复制（面试拿到的简历与代码现状一致）。
- **交接文档是"会话接力"关键**：M{N} 完成后必须更新状态 + 建 M{N+1} 骨架，否则下个会话从 roadmap 现找。
- **测试数要真实**：`mvn clean test` 实际数字（如 245），README/CHANGELOG/resume/验收报告四处一致。
- 文档语言与现有文档一致（中文，口语化但准确）；日期用实际完成日。
- **不要遗漏 demo 与面试题库**：这两个最容易被漏（roadmap 9 项清单不含 demo 细节与面试题库，本项目补充）。
