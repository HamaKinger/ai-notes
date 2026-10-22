package com.ainotes.common;

/**
 * 未认证 / 无权限。由全局异常处理映射为 HTTP 401。
 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
