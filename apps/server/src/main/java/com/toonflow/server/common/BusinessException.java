package com.toonflow.server.common;

import java.util.Set;

/** message 必须是可安全返回客户端的提示，不接受底层异常原文。 */
public class BusinessException extends RuntimeException {
    private static final Set<Integer> STATUSES = Set.of(400, 403, 404, 409, 413, 415, 424, 500, 502);
    private final int status;
    private final Object data;

    public BusinessException(int status, String message, Object data) {
        super(message);
        if (!STATUSES.contains(status)) {
            throw new IllegalArgumentException("不支持的业务状态码");
        }
        this.status = status;
        this.data = data;
    }

    public int getStatus() {
        return status;
    }

    public Object getData() {
        return data;
    }
}
