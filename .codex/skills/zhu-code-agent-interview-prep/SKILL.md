---
name: zhu-code-agent-interview-prep
description: 仅 zhuCodeAgent 项目（/Users/huangdazhu/IdeaProjects/zhu-code-agent）使用。整理/口语化项目面试问答（通用 6 问 + 细节追问），并同步到 Notion「📔 简历分析」页（https://www.notion.so/1b1f7b2f17d6801eaae0e8e4b100a56f，末尾按三级标题追加 + 追问用代码块）与 docs/resume.md「面试问答（6 问）」小节、docs/面试题库.md。当用户说「整理面试题 / 面试答案 / 口语化总结 / 同步到简历或 Notion」时使用。
---

# 面试问答整理与同步（zhuCodeAgent 项目专用）

目标：把项目面试问答整理成「面向面试官、口语化、可深挖」的答案，并保持 Notion 与仓库文档一致，方便面试前快速过一遍。

## 前置

1. 校验 cwd 为 `/Users/huangdazhu/IdeaProjects/zhu-code-agent`（非本项目则中止并说明）。
2. 读取现状，答案必须基于真实代码（可 `grep` 核实类名/机制，不要凭印象）：
   - `docs/面试题库.md`（按 A 项目全局 / C 循环 / F 上下文 / G 并发 分层的题库）
   - `docs/resume.md` 的「面试问答（6 问）」小节（root 活文档）
   - `docs/implementation.md`（实现细节/踩坑）、`docs/交接-M5.md`（M5 架构速查）
3. 同步目标（三处口径一致）：
   - Notion「📔 简历分析」页（id `1b1f7b2f17d6801eaae0e8e4b100a56f`）
   - `docs/resume.md`「面试问答（6 问）」小节
   - 需要时更新 `docs/面试题库.md` 对应分层章节

## 整理规范（口语化）

- 语气：像跟面试官聊天——先给结论再展开，结尾尽量给「一句话总结」。
- 结构：每问 = 参考答案（分点、可深挖）；细节追问给「Q：… / ★ 一句话总结」。
- 内容纪律：
  - 只讲真实实现；类名/机制对照代码（如 `StreamState`、`PermissionManager`、`ContextCompactor`、`SubagentCoordinator`）。
  - 「对比 Claude/Codex」类答案要诚实：多数是对齐，少数才是差异化改进；**不把修 DeepSeek 兼容端点的适配（如协议感知占用）说成对 Claude 的改进**。
  - 项目现状变化后要同步口径（如 M5 已完成，则"不足"里不再写并行未做）。
  - 需要细讲时用「代码块 + 一句话总结」的追问补充形式（参考 Notion 页 Q1 追问补充）。
- 参考：`docs/resume.md` 亮点条目、`docs/implementation.md` 踩坑记录、历史 review 讨论结论。

## Notion 同步流程（notion-update-page）

1. 先 `notion-fetch` 页面拿**当前末尾精确文本**（页面含大量图片签名 URL，fetch 中间会截断，尾部锚点可用 `notion-search` 且 `page_url=页面` 定位）。
2. `notion-update-page`：`command: "update_content"`、`properties: {}`、`content_updates: [{old_str, new_str}]`：
   - old_str = 现有末尾/锚点处的精确片段（一个自然段或一行即可，越短越不易因转义失败）
   - new_str = old_str + `\n\n### 面试 Q{N}：…\n\n正文…`；追问/一句话总结用 ```` ```text ```` 代码块
3. 追加后 `notion-fetch` / `notion-search` 验证落盘；blockquote 后接标题注意补空行（`\n\n`），避免被引用块吞并。
4. 页面结构/锚点历史见 `references/notion-page.md`。

## 仓库文档同步

- `docs/resume.md`：「面试问答（6 问）」小节末尾追加/更新 `### 面试 Q{N}：…`（与 Notion 内容一致）。
- `docs/面试题库.md`：若该问属于某分层（A 项目全局 / F 上下文 / G 并发…），同步更新对应章节；通用 6 问以 resume.md「面试问答」为准。

## 收尾

- 自查：Notion 与 docs 内容一致（同一问题两处都有且口径一致）；`git status` 确认变更清单。
- **先交用户 review 再提交**（用户明确同意后才 git commit + push）。
