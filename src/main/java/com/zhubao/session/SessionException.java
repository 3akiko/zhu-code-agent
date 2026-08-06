package com.zhubao.session;

/**
 * 会话存储异常（目录创建、读写失败等）。
 */
public class SessionException extends RuntimeException {

    public SessionException(String message) {
        super(message);
    }

    public SessionException(String message, Throwable cause) {
        super(message, cause);
    }
}
