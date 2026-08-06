package com.zhubao.tui;

/**
 * 轻量 ANSI 256 色渲染助手（方案 B 彩色 TUI）。
 *
 * <p>颜色语义与 mewcode 一致：用户/提示青色、思考灰色、错误红色、状态绿色、高亮亮青。
 * 未采用 Mordant：其 3.x 为 Kotlin-first API（Java 调用需 Widget 转换），且 Maven
 * Central 上默认构件是 KMP metadata 包，集成成本高；M1 颜色需求简单，自研更轻量。
 */
public final class Ansi {

    /** 用户消息/提示符：青色（256 色 80） */
    public static final String USER = "\u001b[38;5;80m";
    /** 思考内容/次要信息：灰色（242） */
    public static final String THINKING = "\u001b[38;5;242m";
    /** 错误：红色（203） */
    public static final String ERROR = "\u001b[38;5;203m";
    /** 状态/成功：绿色（78） */
    public static final String STATUS = "\u001b[38;5;78m";
    /** 高亮/标题：亮青（99） */
    public static final String HIGHLIGHT = "\u001b[38;5;99m";
    /** 重置所有样式 */
    public static final String RESET = "\u001b[0m";

    private Ansi() {
    }

    /** 给文本上色（code 为 null 时不加色） */
    public static String color(String text, String code) {
        if (code == null || text == null) {
            return text;
        }
        return code + text + RESET;
    }
}
