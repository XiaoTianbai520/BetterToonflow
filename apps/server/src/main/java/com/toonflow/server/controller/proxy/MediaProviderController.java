package com.toonflow.server.controller.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.toonflow.server.common.BusinessException;
import com.toonflow.server.proxy.AgentRuntimeClient;
import com.toonflow.server.proxy.MediaProviderPersistenceService;
import com.toonflow.server.proxy.ProxyRequests;
import com.toonflow.server.proxy.ProxyResponses;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.http.HttpResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 媒体供应商沙箱（6 个接口）。写操作由 Bun 完成 AST 校验与模型拉取，
 * 权威数据落 ai_media_provider，磁盘快照在事务提交后原子派生。
 */
@RestController
public class MediaProviderController {
    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;
    private final AgentRuntimeClient runtime;
    private final MediaProviderPersistenceService providers;
    private final ProxyRequests requests;
    private final ObjectMapper json;

    public MediaProviderController(AgentRuntimeClient runtime, MediaProviderPersistenceService providers,
            ProxyRequests requests, ObjectMapper json) {
        this.runtime = runtime;
        this.providers = providers;
        this.requests = requests;
        this.json = json;
    }

    @GetMapping("/api/providers/media/list")
    public ResponseEntity<byte[]> list() {
        return ProxyResponses.passThrough(runtime.exchange(HttpMethod.GET, "/api/providers/media/list", null));
    }

    @PostMapping("/api/providers/media/validate")
    public ResponseEntity<?> validate(HttpServletRequest request) throws IOException {
        byte[] raw = requests.body(request, MAX_BODY_BYTES);
        requests.object(raw);
        HttpResponse<byte[]> upstream = runtime.exchange(HttpMethod.POST, "/api/providers/media/validate", raw);
        if (upstream.statusCode() != 200) {
            return ProxyResponses.passThrough(upstream);
        }
        JsonNode data = envelopeData(upstream.body());
        return ProxyResponses.envelope(200, data, "媒体供应商校验通过");
    }

    @PostMapping("/api/providers/media/add")
    public ResponseEntity<?> add(HttpServletRequest request) throws IOException {
        JsonNode body = requests.object(requests.body(request, MAX_BODY_BYTES));
        JsonNode source = body.get("source");
        if (source == null || !source.isTextual()) {
            throw new BusinessException(400, "参数错误", null);
        }
        return ProxyResponses.envelope(200, providers.add(source.textValue()), "媒体供应商已添加");
    }

    @PutMapping("/api/providers/media/save")
    public ResponseEntity<?> save(HttpServletRequest request) throws IOException {
        JsonNode body = requests.object(requests.body(request, MAX_BODY_BYTES));
        JsonNode models = body.get("models");
        return ProxyResponses.envelope(200,
                providers.save(text(body, "fileName"), models, text(body, "revision")), "模型列表已保存");
    }

    @PostMapping("/api/providers/media/models")
    public ResponseEntity<?> refreshModels(HttpServletRequest request) throws IOException {
        JsonNode body = requests.object(requests.body(request, MAX_BODY_BYTES));
        return ProxyResponses.envelope(200,
                providers.refreshModels(text(body, "fileName"), text(body, "revision")), "模型列表已刷新");
    }

    @DeleteMapping("/api/providers/media/delete")
    public ResponseEntity<?> delete(HttpServletRequest request) throws IOException {
        JsonNode body = requests.object(requests.body(request, MAX_BODY_BYTES));
        providers.delete(text(body, "fileName"), text(body, "revision"));
        return ProxyResponses.envelope(200, null, "媒体供应商已删除");
    }

    private JsonNode envelopeData(byte[] payload) {
        JsonNode envelope;
        try {
            envelope = json.readTree(payload);
        } catch (IOException exception) {
            throw new BusinessException(502, "内部运行时返回非预期结构", null);
        }
        JsonNode data = envelope == null ? null : envelope.get("data");
        return data == null || data.isNull() ? null : data;
    }

    private String text(JsonNode body, String field) {
        JsonNode value = body.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }
}
