package com.zhubao.tui;

/**
 * TUI 层异常（终端初始化、按键读取失败等）。
 */
public class TuiException extends RuntimeException {

    public TuiException(String message) {
        super(message);
    }

    public TuiException(String message, Throwable cause) {
        super(message, cause);
    }
}
