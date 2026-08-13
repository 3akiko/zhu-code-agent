# 文档收尾检查清单（速查版）

> 配合 SKILL.md 使用。每项完成后在 SKILL.md 主清单打勾。

| # | 文档 | 关键更新点 |
|---|------|-----------|
| 1 | `docs/验收报告-M{N}.md` | 新建；checklist 逐条 + 证据 + Review 修复；测试总数/新增数 |
| 2 | `docs/milestones/m{N}/` | 归档 spec/plan/task/checklist/resume 五份 |
| 3 | `README.md` | 状态行 / 当前功能 / 目录结构 / 测试数 / 相关文档链接 |
| 4 | `docs/implementation.md` | 追加 M{N} 节：实现 / 机制 / 对比 / 踩坑 |
| 5 | `CHANGELOG.md` | Added / Fixed / Changed（M{N}） |
| 6 | `docs/roadmap.md` | 顶部日期 / 总览表状态 / 详情状态 / **对比表加 M{N} 列（对齐列数）** |
| 7 | `docs/resume.md` | 简介 / 亮点条目 / 测试数 / 对比表 / 一句话亮点；root + 归档双份 |
| 8 | `docs/TODO.md` | 勾掉完成项 / 追加新债 |
| 9 | `docs/demo-M{N}*.md` | milestone-demo-verify 生成 + 真机回填 |
| 10 | `docs/交接-M{N}.md` + `docs/交接-M{N+1}.md` | 状态改已完成 + 建下一里程碑骨架 |
| 11 | `docs/面试题库.md` | 追加本里程碑面试题与回答要点 |
| 12 | 提交 | 先交用户 review，同意后 git commit + push |

## 高频遗漏（M5 实测踩坑）

- 特性对比表新增列后**逐行数列数**（漏 3 行导致错位）。
- resume 只更新 root 忘归档快照。
- 交接文档只改旧的忘建新的（下个会话接续断档）。
- demo 与面试题库不在 roadmap 9 项里，最容易漏。
- 测试数四处不一致（README/CHANGELOG/resume/验收报告）。
