package com.toonflow.server.controller.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.toonflow.server.common.BusinessException;
import com.toonflow.server.proxy.AgentRuntimeClient;
import com.toonflow.server.proxy.ProxyRequests;
import com.toonflow.server.proxy.ProxyResponses;
import com.toonflow.server.proxy.StreamRelay;
import com.toonflow.server.proxy.WorkspaceGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 第 6 组：多模态生成与媒体供应商沙箱的转发部分（模型、生成、计价、余额）。 */
@RestController
public class MediaProxyController {
    private static final int MAX_BODY_BYTES = 16 * 1024 * 1024;
    private static final Set<String> MEDIA_TYPES = Set.of("image", "video", "audio");
    private final AgentRuntimeClient runtime;
    private final ProxyRequests requests;
    private final WorkspaceGuard workspace;

    public MediaProxyController(AgentRuntimeClient runtime, ProxyRequests requests, WorkspaceGuard workspace) {
        this.runtime = runtime;
        this.requests = requests;
        this.workspace = workspace;
    }

    /** SSE 流：文本/思考增量帧逐帧 flush，20s 保活帧原样透传。 */
    @PostMapping("/api/ai/generate")
    public void generate(HttpServletRequest request, HttpServletResponse response) throws IOException {
        byte[] raw = requests.body(request, MAX_BODY_BYTES);
        requests.object(raw);
        StreamRelay.relay(runtime.openStream(HttpMethod.POST, requests.target(request), raw), response,
                StreamRelay.Protocol.SSE);
    }

    @GetMapping("/api/ai/media/models")
    public ResponseEntity<byte[]> models() {
        return ProxyResponses.passThrough(runtime.exchange(HttpMethod.GET, "/api/ai/media/models", null));
    }

    /** 前导 \n 保活 Chunked JSON：建连前的目录与媒体类型校验失败仍返回真实 HTTP 错误码。 */
    @PostMapping("/api/ai/media/generate")
    public void generateMedia(HttpServletRequest request, HttpServletResponse response) throws IOException {
        byte[] raw = requests.body(request, MAX_BODY_BYTES);
        JsonNode body = requests.object(raw);
        workspace.require(requests.directory(body));
        if (!MEDIA_TYPES.contains(body.path("mediaType").asText(""))) {
            throw new BusinessException(400, "参数错误", null);
        }
        StreamRelay.relay(runtime.openStream(HttpMethod.POST, "/api/ai/media/generate", raw), response,
                StreamRelay.Protocol.CHUNKED_JSON);
    }

    @PostMapping("/api/ai/media/price")
    public ResponseEntity<byte[]> price(HttpServletRequest request) throws IOException {
        byte[] raw = requests.body(request, MAX_BODY_BYTES);
        requests.object(raw);
        return ProxyResponses.passThrough(runtime.exchange(HttpMethod.POST, "/api/ai/media/price", raw));
    }

    @PostMapping("/api/ai/media/balance")
    public ResponseEntity<byte[]> balance(HttpServletRequest request) throws IOException {
        byte[] raw = requests.body(request, MAX_BODY_BYTES);
        requests.object(raw);
        return ProxyResponses.passThrough(runtime.exchange(HttpMethod.POST, "/api/ai/media/balance", raw));
    }

    /** 账户失效时上游返回 404，按原状态与 JSON 正文透传，供前端触发 accountId 重刷。 */
    @GetMapping("/api/ai/media/balanceEvents")
    public void balanceEvents(HttpServletRequest request, HttpServletResponse response) {
        StreamRelay.relay(runtime.openStream(HttpMethod.GET, requests.target(request), null), response,
                StreamRelay.Protocol.SSE);
    }
}
