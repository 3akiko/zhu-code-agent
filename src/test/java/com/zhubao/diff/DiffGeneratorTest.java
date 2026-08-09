package com.zhubao.diff;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DiffGenerator 单测（M3 spec F1）：精确替换/多行/新建/覆写/无变更/截断。
 */
class DiffGeneratorTest {

    private final DiffGenerator gen = new DiffGenerator(200);

    @Test
    void singleLineReplacementShowsMinusPlusWithContext() {
        String diff = gen.diff("line1\nline2\nline3\nline4\n", "line1\nlineX\nline3\nline4\n");
        assertTrue(diff.contains("@@ -2,1 +2,1 @@"), diff);
        assertTrue(diff.contains("-line2"), diff);
        assertTrue(diff.contains("+lineX"), diff);
        assertTrue(diff.contains(" line1"), diff);   // 变更前上下文
        assertTrue(diff.contains(" line3"), diff);   // 变更后上下文
    }

    @Test
    void multilineAdditionHasNoRemovedLines() {
        String diff = gen.diff("a\nb\nc\n", "a\nb\nc\nd\n");
        assertTrue(diff.contains("@@ -4,0 +4,1 @@"), diff);
        assertTrue(diff.contains("+d"), diff);
        assertFalse(diff.contains("\n-"), diff); // 除 @@ 头外无 - 行
    }

    @Test
    void createFileShowsAllPlus() {
        String diff = gen.diff("", "x\ny\n");
        assertTrue(diff.contains("@@ -1,0 +1,3 @@"), diff);
        assertTrue(diff.contains("+x"), diff);
        assertTrue(diff.contains("+y"), diff);
    }

    @Test
    void deleteAllShowsAllMinus() {
        String diff = gen.diff("x\ny\n", "");
        assertTrue(diff.contains("@@ -1,3 +1,0 @@"), diff);
        assertTrue(diff.contains("-x"), diff);
        assertTrue(diff.contains("-y"), diff);
    }

    @Test
    void overwriteShowsBothSides() {
        String diff = gen.diff("old\n", "new\n");
        assertTrue(diff.contains("@@ -1,1 +1,1 @@"), diff);
        assertTrue(diff.contains("-old"), diff);
        assertTrue(diff.contains("+new"), diff);
    }

    @Test
    void noChangeReturnsEmpty() {
        assertEquals("", gen.diff("a\nb\n", "a\nb\n"));
    }

    @Test
    void overMaxLinesTruncatedWithAnnotation() {
        DiffGenerator small = new DiffGenerator(3);
        String before = String.join("\n", java.util.stream.IntStream.rangeClosed(1, 10)
                .mapToObj(i -> "old" + i).toList());
        String after = String.join("\n", java.util.stream.IntStream.rangeClosed(1, 10)
                .mapToObj(i -> "new" + i).toList());
        String diff = small.diff(before, after);
        String[] lines = diff.split("\n");
        assertTrue(diff.contains("…已截断，共 "), diff);
        assertEquals(3, lines.length - 1);          // 保留 3 行 + 1 行标注
        assertTrue(lines[0].startsWith("@@"), diff);
    }

    @Test
    void trailingNewlineOnlyChangeIsVisible() {
        // 删除末尾换行："a\n" → "a"：应显示一个 "-" 空行
        String remove = gen.diff("a\n", "a");
        assertTrue(remove.contains("@@ -2,1 +2,0 @@"), remove);
        assertTrue(remove.contains("\n-"), remove);
        // 新增末尾换行："a" → "a\n"：应显示一个 "+" 空行
        String add = gen.diff("a", "a\n");
        assertTrue(add.contains("@@ -2,0 +2,1 @@"), add);
        assertTrue(add.contains("\n+"), add);
    }

    @Test
    void emptyBothSidesReturnsEmpty() {
        assertEquals("", gen.diff("", ""));
    }
}
