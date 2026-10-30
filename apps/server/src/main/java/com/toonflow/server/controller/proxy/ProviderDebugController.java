package com.toonflow.server.controller.proxy;

import com.toonflow.server.proxy.AgentRuntimeClient;
import com.toonflow.server.proxy.ProxyRequests;
import com.toonflow.server.proxy.ProxyResponses;
import com.toonflow.server.proxy.StreamRelay;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 媒体供应商在线调试台：静态解析与沙箱试运行。 */
@RestController
public class ProviderDebugController {
    private static final int MAX_BODY_BYTES = 16 * 1024 * 1024;
    private final AgentRuntimeClient runtime;
    private final ProxyRequests requests;

    public ProviderDebugController(AgentRuntimeClient runtime, ProxyRequests requests) {
        this.runtime = runtime;
        this.requests = requests;
    }

    @PostMapping("/api/providers/debug/inspect")
    public ResponseEntity<byte[]> inspect(HttpServletRequest request) throws IOException {
        byte[] raw = requests.body(request, MAX_BODY_BYTES);
        requests.object(raw);
        return ProxyResponses.passThrough(runtime.exchange(HttpMethod.POST, "/api/providers/debug/inspect", raw));
    }

    /** NDJSON 流：抓包日志与结果事件逐行 flush，断开时终止沙箱执行。 */
    @PostMapping("/api/providers/debug/run")
    public void run(HttpServletRequest request, HttpServletResponse response) throws IOException {
        byte[] raw = requests.body(request, MAX_BODY_BYTES);
        requests.object(raw);
        StreamRelay.relay(runtime.openStream(HttpMethod.POST, "/api/providers/debug/run", raw), response,
                StreamRelay.Protocol.NDJSON);
    }
}
