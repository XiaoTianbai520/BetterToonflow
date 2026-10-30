package com.toonflow.server.proxy;

import com.toonflow.server.common.BusinessException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
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
    /** 长耗时与流式转发读取超时 31 分钟，略大于调试沙箱 30 分钟硬超时。 */
    private static final Duration STREAM_TIMEOUT = Duration.ofMillis(1_860_000);

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

    /** 流式响应：只在响应头到达后返回，响应体由调用方逐帧读取并立即 flush。 */
    public HttpResponse<InputStream> openStream(HttpMethod method, String pathAndQuery, byte[] body) {
        return send(request(method, pathAndQuery, body, STREAM_TIMEOUT),
                HttpResponse.BodyHandlers.ofInputStream());
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
                .timeout(timeout)
                .header("X-Toonflow-Internal-Token", internalToken)
                .header("X-Request-Id", UUID.randomUUID().toString())
                .header("Accept", "application/json, application/x-ndjson, text/event-stream");
        if (body == null || body.length == 0) {
            builder.method(method.name(), HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json; charset=utf-8");
            builder.method(method.name(), HttpRequest.BodyPublishers.ofByteArray(body));
        }
        return builder.build();
    }

    /** 查询串按原始文本交给 URI 构造器编码，避免已转义字符被二次转义。 */
    private URI target(String pathAndQuery) {
        int separator = pathAndQuery.indexOf('?');
        String path = separator < 0 ? pathAndQuery : pathAndQuery.substring(0, separator);
        String query = separator < 0 ? null : pathAndQuery.substring(separator + 1);
        URI base = URI.create(baseUrl + path);
        try {
            return new URI(base.getScheme(), base.getAuthority(), base.getPath(), query, null);
        } catch (URISyntaxException exception) {
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
}
