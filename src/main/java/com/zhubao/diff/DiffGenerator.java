package com.zhubao.diff;

import java.util.ArrayList;
import java.util.List;

/**
 * 轻量 diff 生成器（M3，spec F1 / plan 技术决策）：
 * 公共前缀/后缀 + 中间变更块（非完整 Myers diff）。
 * <ul>
 *   <li>edit_file 单点替换精确；write_file 给出变更概览</li>
 *   <li>输出：@@ 头 + 上下文（变更块前后各至多 2 行）+ `-` 行 + `+` 行</li>
 *   <li>超过 maxLines 截断并标注「…已截断，共 N 行」</li>
 * </ul>
 */
public final class DiffGenerator {

    private final int maxLines;

    public DiffGenerator(int maxLines) {
        this.maxLines = Math.max(1, maxLines);
    }

    /** 生成变更 diff 文本；无变更返回空串 */
    public String diff(String before, String after) {
        List<String> b = splitLines(before);
        List<String> a = splitLines(after);
        int prefix = commonPrefix(b, a);
        int suffix = commonSuffix(b, a, prefix);
        int bStart = prefix;
        int bEnd = b.size() - suffix;
        int aStart = prefix;
        int aEnd = a.size() - suffix;
        if (bStart == bEnd && aStart == aEnd) {
            return "";
        }
        int ctxBefore = Math.min(2, bStart);
        int ctxAfter = Math.min(2, b.size() - bEnd);
        List<String> out = new ArrayList<>();
        out.add(String.format("@@ -%d,%d +%d,%d @@", bStart + 1, bEnd - bStart, aStart + 1, aEnd - aStart));
        for (int i = bStart - ctxBefore; i < bStart; i++) {
            out.add(" " + b.get(i));
        }
        for (int i = bStart; i < bEnd; i++) {
            out.add("-" + b.get(i));
        }
        for (int i = aStart; i < aEnd; i++) {
            out.add("+" + a.get(i));
        }
        for (int i = bEnd; i < bEnd + ctxAfter; i++) {
            out.add(" " + b.get(i));
        }
        return truncate(out);
    }

    private String truncate(List<String> lines) {
        if (lines.size() <= maxLines) {
            return String.join("\n", lines);
        }
        List<String> head = new ArrayList<>(lines.subList(0, maxLines));
        head.add("…已截断，共 " + lines.size() + " 行");
        return String.join("\n", head);
    }

    private static List<String> splitLines(String content) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        // 保留结尾空行（split -1）：结尾换行视为一个"空行"，使"仅增删末尾换行"的变更可见（review P2-3）
        String[] parts = content.split("\n", -1);
        List<String> out = new ArrayList<>(parts.length);
        for (String part : parts) {
            out.add(part);
        }
        return out;
    }

    private static int commonPrefix(List<String> b, List<String> a) {
        int n = Math.min(b.size(), a.size());
        int i = 0;
        while (i < n && b.get(i).equals(a.get(i))) {
            i++;
        }
        return i;
    }

    private static int commonSuffix(List<String> b, List<String> a, int prefix) {
        int maxB = b.size() - prefix;
        int maxA = a.size() - prefix;
        int n = Math.min(maxB, maxA);
        int i = 0;
        while (i < n && b.get(b.size() - 1 - i).equals(a.get(a.size() - 1 - i))) {
            i++;
        }
        return i;
    }
}
