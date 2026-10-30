package com.toonflow.server.controller.proxy;

import com.toonflow.server.proxy.AgentRuntimeClient;
import com.toonflow.server.proxy.ProxyRequests;
import com.toonflow.server.proxy.ProxyResponses;
import com.toonflow.server.proxy.StreamRelay;
import com.toonflow.server.proxy.WorkspaceGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 第 5 组：主 Agent 会话与推理（10 个接口），全部转发至 Bun agentRuntime。 */
@RestController
public class AgentProxyController {
    private static final int MAX_BODY_BYTES = 32 * 1024 * 1024;
    private final AgentRuntimeClient runtime;
    private final ProxyRequests requests;
    private final WorkspaceGuard workspace;

    public AgentProxyController(AgentRuntimeClient runtime, ProxyRequests requests, WorkspaceGuard workspace) {
        this.runtime = runtime;
        this.requests = requests;
        this.workspace = workspace;
    }

    /** NDJSON 流：逐帧 flush，前端断开时级联取消上游推理。 */
    @PostMapping("/api/agent")
    public void chat(HttpServletRequest request, HttpServletResponse response) throws IOException {
        byte[] raw = requests.body(request, MAX_BODY_BYTES);
        workspace.require(requests.directory(requests.object(raw)));
        StreamRelay.relay(runtime.openStream(HttpMethod.POST, requests.target(request), raw), response,
                StreamRelay.Protocol.NDJSON);
    }

    @PostMapping("/api/agent/create")
    public ResponseEntity<byte[]> create(HttpServletRequest request) throws IOException {
        byte[] raw = requests.body(request, MAX_BODY_BYTES);
        workspace.require(requests.directory(requests.object(raw)));
        return ProxyResponses.passThrough(runtime.exchange(HttpMethod.POST, requests.target(request), raw));
    }

    @GetMapping("/api/agent/list")
    public ResponseEntity<byte[]> list(HttpServletRequest request) {
        workspace.require(requests.directoryParameter(request));
        return ProxyResponses.passThrough(runtime.exchange(HttpMethod.GET, requests.target(request), null));
    }

    @GetMapping("/api/agent/get")
    public ResponseEntity<byte[]> get(HttpServletRequest request) {
        workspace.require(requests.directoryParameter(request));
        return ProxyResponses.passThrough(runtime.exchange(HttpMethod.GET, requests.target(request), null));
    }

    @PatchMapping("/api/agent/rename")
    public ResponseEntity<byte[]> rename(HttpServletRequest request) throws IOException {
        byte[] raw = requests.body(request, MAX_BODY_BYTES);
        workspace.require(requests.directory(requests.object(raw)));
        return ProxyResponses.passThrough(runtime.exchange(HttpMethod.PATCH, requests.target(request), raw));
    }

    @DeleteMapping("/api/agent/message")
    public ResponseEntity<byte[]> message(HttpServletRequest request) throws IOException {
        byte[] raw = requests.body(request, MAX_BODY_BYTES);
        workspace.require(requests.directory(requests.object(raw)));
        return ProxyResponses.passThrough(runtime.exchange(HttpMethod.DELETE, requests.target(request), raw));
    }

    @PostMapping("/api/agent/answer")
    public ResponseEntity<byte[]> answer(HttpServletRequest request) throws IOException {
        byte[] raw = requests.body(request, MAX_BODY_BYTES);
        workspace.require(requests.directory(requests.object(raw)));
        return ProxyResponses.passThrough(runtime.exchange(HttpMethod.POST, requests.target(request), raw));
    }

    @PostMapping("/api/agent/canvasResult")
    public ResponseEntity<byte[]> canvasResult(HttpServletRequest request) throws IOException {
        byte[] raw = requests.body(request, MAX_BODY_BYTES);
        workspace.require(requests.directory(requests.object(raw)));
        return ProxyResponses.passThrough(runtime.exchange(HttpMethod.POST, requests.target(request), raw));
    }

    @GetMapping("/api/agent/mentionSource")
    public ResponseEntity<byte[]> mentionSource(HttpServletRequest request) {
        workspace.require(requests.directoryParameter(request));
        return ProxyResponses.passThrough(runtime.exchange(HttpMethod.GET, requests.target(request), null));
    }

    @GetMapping("/api/agent/skills")
    public ResponseEntity<byte[]> skills(HttpServletRequest request) {
        workspace.require(requests.directoryParameter(request));
        return ProxyResponses.passThrough(runtime.exchange(HttpMethod.GET, requests.target(request), null));
    }
}
