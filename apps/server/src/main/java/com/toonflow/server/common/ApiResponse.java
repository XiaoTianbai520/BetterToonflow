package com.toonflow.server.common;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApiResponse<T>(int code, T data, String message) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(200, data, "成功");
    }
}
