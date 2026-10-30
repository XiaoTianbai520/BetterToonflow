package com.toonflow.server.proxy;

import com.toonflow.server.common.ApiResponse;
import java.net.http.HttpResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** 普通 JSON 转发：状态码、响应头与正文原样透传，避免改写上游错误信封。 */
public final class ProxyResponses {
    private static final MediaType DEFAULT_JSON = MediaType.parseMediaType("application/json; charset=utf-8");

    private ProxyResponses() {
    }

    public static ResponseEntity<byte[]> passThrough(HttpResponse<byte[]> upstream) {
        byte[] body = upstream.body() == null ? new byte[0] : upstream.body();
        return ResponseEntity.status(upstream.statusCode())
                .contentType(mediaType(upstream))
                .cacheControl(CacheControl.noStore())
                .body(body);
    }

    public static ResponseEntity<ApiResponse<Object>> envelope(int status, Object data, String message) {
        return ResponseEntity.status(status).body(new ApiResponse<>(status, data, message));
    }

    public static MediaType mediaType(HttpResponse<?> upstream) {
        return upstream.headers().firstValue("content-type")
                .map(ProxyResponses::parse)
                .orElse(DEFAULT_JSON);
    }

    private static MediaType parse(String value) {
        try {
            return MediaType.parseMediaType(value);
        } catch (InvalidMediaTypeException exception) {
            return DEFAULT_JSON;
        }
    }
}
