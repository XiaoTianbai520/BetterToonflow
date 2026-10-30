package com.toonflow.server.proxy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.toonflow.server.common.BusinessException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.stereotype.Component;

/** 请求体读取与转发路径拼装：请求体一次性读取，响应体仍由流式中继逐帧转发。 */
@Component
public class ProxyRequests {
    private final ObjectMapper json;

    public ProxyRequests(ObjectMapper json) {
        this.json = json;
    }

    public byte[] body(HttpServletRequest request, int maxBytes) throws IOException {
        ServletInputStream stream = request.getInputStream();
        byte[] data = stream.readNBytes(maxBytes + 1);
        if (data.length > maxBytes) {
            throw new BusinessException(413, "请求体超过体积上限", null);
        }
        return data.length == 0 ? null : data;
    }

    public JsonNode object(byte[] raw) {
        if (raw == null) {
            throw new BusinessException(400, "参数错误", null);
        }
        JsonNode parsed;
        try {
            parsed = json.readTree(raw);
        } catch (IOException exception) {
            throw new BusinessException(400, "参数错误", null);
        }
        if (parsed == null || !parsed.isObject()) {
            throw new BusinessException(400, "参数错误", null);
        }
        return parsed;
    }

    public String directory(JsonNode body) {
        JsonNode value = body.get("directory");
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new BusinessException(400, "参数错误", null);
        }
        return value.textValue();
    }

    public String directoryParameter(HttpServletRequest request) {
        String value = request.getParameter("directory");
        if (value == null || value.isBlank()) {
            throw new BusinessException(400, "参数错误", null);
        }
        return value;
    }

    /** 转发路径与原始查询串，查询串保持原样交由 URI 构造器编码。 */
    public String target(HttpServletRequest request) {
        String query = request.getQueryString();
        String path = request.getRequestURI();
        return query == null || query.isEmpty() ? path : path + "?" + query;
    }

    public byte[] serialize(JsonNode value) {
        try {
            return json.writeValueAsBytes(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("请求体序列化失败", exception);
        }
    }
}
