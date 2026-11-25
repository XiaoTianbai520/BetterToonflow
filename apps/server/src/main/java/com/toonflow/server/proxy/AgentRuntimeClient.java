package com.toonflow.server.proxy;

import com.toonflow.server.common.BusinessException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/**
 * 面向 Bun agentRuntime 的内部 HTTP 客户端：统一注入内部鉴权令牌与追踪 ID。
 * 令牌只存在于 Java 与 Bun 之间，任何响应都不会回传前端。
 */
@Component
public class AgentRuntimeClient {
    private static final Logger LOG = LoggerFactory.getLogger(AgentRuntimeClient.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    /** 普通 JSON 转发读取超时 30s。 */
    private static final Duration JSON_TIMEOUT = Duration.ofSeconds(30);
    /** 流式转发仅限制等待响应头 30s，建连后的流式读取由 StreamRelay 按空闲超时管理，不设总时长上限。 */
    private static final long STREAM_HEADER_TIMEOUT_SECONDS = 30L;

    private final HttpClient http;
    private final String baseUrl;
    private final String internalToken;
    private final boolean tokenConfigured;

    public AgentRuntimeClient(
            @Value("${toonflow.agent-runtime.url:}") String configuredUrl,
            @Value("${TOONFLOW_AGENT_RUNTIME_PORT:3001}") String runtimePort,
            @Value("${TOONFLOW_INTERNAL_TOKEN:}") String internalToken) {
        // Runtime 仅监听回环地址；未显式配置时按端口环境变量拼装。
        String baseUrl = configuredUrl == null || configuredUrl.isBlank()
                ? "http://127.0.0.1:" + runtimePort.trim() : configuredUrl.trim();
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.internalToken = internalToken == null ? "" : internalToken.trim();
        this.tokenConfigured = !this.internalToken.isEmpty();
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
        if (!tokenConfigured) {
            LOG.warn("event=internal_token_missing hint=请设置 TOONFLOW_INTERNAL_TOKEN，否则内部运行时将拒绝全部代理请求");
        }
    }

    /**
     * 流式响应：HttpRequest 不设总时长超时，仅通过 sendAsync + orTimeout 限制 30s 响应头等待；
     * 响应头到达后即返回，响应体由 StreamRelay 逐帧读取、立即 flush 并按读取空闲超时管理。
     */
    public HttpResponse<InputStream> openStream(HttpMethod method, String pathAndQuery, byte[] body) {
        HttpRequest request = request(method, pathAndQuery, body, null);
        CompletableFuture<HttpResponse<InputStream>> upstream =
                http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        CompletableFuture<HttpResponse<InputStream>> timed =
                upstream.copy().orTimeout(STREAM_HEADER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        try {
            return timed.get();
        } catch (ExecutionException exception) {
            Throwable cause = unwrapCause(exception);
            if (cause instanceof TimeoutException || cause instanceof HttpTimeoutException) {
                cancelAndRelease(upstream);
                throw new BusinessException(502, "内部运行时响应超时", null);
            }
            if (isConnectFailure(cause)) {
                throw new BusinessException(502, "内部运行时不可用", null);
            }
            if (cause instanceof BusinessException businessException) {
                throw businessException;
            }
            throw new BusinessException(502, "内部运行时通信失败", null);
        } catch (InterruptedException exception) {
            cancelAndRelease(upstream);
            Thread.currentThread().interrupt();
            throw new BusinessException(500, "请求已被中断", null);
        }
    }

    /** 普通 JSON 响应：体积小且需要按状态码决定是否透传，允许一次性读取。 */
    public HttpResponse<byte[]> exchange(HttpMethod method, String pathAndQuery, byte[] body) {
        return send(request(method, pathAndQuery, body, JSON_TIMEOUT),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpRequest request(HttpMethod method, String pathAndQuery, byte[] body, Duration timeout) {
        if (!tokenConfigured) {
            throw new BusinessException(500, "服务间内部鉴权未配置，请设置 TOONFLOW_INTERNAL_TOKEN", null);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(target(pathAndQuery))
                .header("X-Toonflow-Internal-Token", internalToken)
                .header("X-Request-Id", UUID.randomUUID().toString())
                .header("Accept", "application/json, application/x-ndjson, text/event-stream");
        if (timeout != null) {
            builder.timeout(timeout);
        }
        if (body == null || body.length == 0) {
            builder.method(method.name(), HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json; charset=utf-8");
            builder.method(method.name(), HttpRequest.BodyPublishers.ofByteArray(body));
        }
        return builder.build();
    }

    /** Servlet 提供的路径和查询串已经转义，按原始 URI 解析以免把 % 再编码成 %25。 */
    private URI target(String pathAndQuery) {
        try {
            return URI.create(baseUrl + pathAndQuery);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(400, "参数错误", null);
        }
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        try {
            return http.send(request, handler);
        } catch (HttpTimeoutException exception) {
            throw new BusinessException(502, "内部运行时响应超时", null);
        } catch (ConnectException exception) {
            throw new BusinessException(502, "内部运行时不可用", null);
        } catch (IOException exception) {
            throw new BusinessException(502, "内部运行时通信失败", null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(500, "请求已被中断", null);
        }
    }

    private void cancelAndRelease(CompletableFuture<HttpResponse<InputStream>> upstream) {
        if (!upstream.cancel(true) && upstream.isDone() && !upstream.isCompletedExceptionally()) {
            try {
                InputStream body = upstream.join().body();
                if (body != null) {
                    body.close();
                }
            } catch (IOException | RuntimeException ignored) {
                // 超时或中断竞态下释放已到达的响应体，不掩盖主异常。
            }
        }
    }

    private static Throwable unwrapCause(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof ExecutionException || current instanceof CompletionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static boolean isConnectFailure(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof ConnectException) {
                return true;
            }
        }
        return false;
    }
}
