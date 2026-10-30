package com.toonflow.server.service.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.toonflow.server.common.BusinessException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 直接以调用方提供的 apiUrl / protocol / apiKey 请求上游 {@code {apiUrl}/v1/models}。
 * 上游非 2xx、超时或结构不符一律视为 502，避免把供应商侧错误伪装成本机参数错误。
 */
@Service
public class LanguageModelProbeService {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            // 与旧实现一致：不跟随重定向，防止 apiKey 被带到未知主机。
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public LanguageModelProbeService(ObjectMapper json) {
        this.json = json;
    }

    public List<LanguageModelDtos.ProviderModelItem> fetch(String apiUrl, String protocol, String apiKey) {
        boolean anthropic = "anthropic-messages".equals(protocol);
        Map<String, LanguageModelDtos.ProviderModelItem> models = new LinkedHashMap<>();
        Set<String> cursors = new HashSet<>();
        String cursor = null;
        try {
            while (true) {
                HttpResponse<String> response = http.send(request(apiUrl, protocol, apiKey, cursor),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new BusinessException(502,
                            "获取模型列表失败（HTTP " + response.statusCode() + "），请检查 API 地址、协议和密钥", null);
                }
                JsonNode body = parse(response.body());
                JsonNode items = body.path("data");
                if (!items.isArray()) {
                    throw new BusinessException(502, "上游模型列表结构不符合预期", null);
                }
                for (JsonNode item : items) {
                    String id = item.path("id").asText("").trim();
                    if (id.isEmpty()) {
                        throw new BusinessException(502, "模型列表包含无效的模型 ID", null);
                    }
                    String label = text(item, "display_name");
                    if (label == null) {
                        label = text(item, "displayName");
                    }
                    models.put(id, new LanguageModelDtos.ProviderModelItem(id, label == null ? id : label,
                            positive(item, "inputTokenLimit"), positive(item, "outputTokenLimit")));
                }
                JsonNode hasMore = body.path("has_more");
                if (hasMore.isMissingNode() || hasMore.isNull() || !hasMore.asBoolean(false) || !anthropic) {
                    break;
                }
                String next = text(body, "last_id");
                if (next == null || next.isBlank()) {
                    throw new BusinessException(502, "模型列表缺少分页游标", null);
                }
                if (!cursors.add(next)) {
                    throw new BusinessException(502, "模型列表分页游标重复", null);
                }
                cursor = next;
            }
        } catch (HttpTimeoutException exception) {
            throw new BusinessException(502, "请求上游模型列表超时，请检查网络与 API 地址", null);
        } catch (IOException exception) {
            throw new BusinessException(502, "请求上游模型列表失败，请检查网络与 API 地址", null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(502, "请求上游模型列表被中断", null);
        }
        return new ArrayList<>(models.values());
    }

    private HttpRequest request(String apiUrl, String protocol, String apiKey, String cursor) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint(apiUrl, protocol, cursor))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .GET();
        if ("anthropic-messages".equals(protocol)) {
            builder.header("anthropic-version", "2023-06-01");
            builder.header("x-api-key", apiKey);
        } else if (!apiKey.isEmpty()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        return builder.build();
    }

    /** apiUrl 允许带子路径；根路径补 /v1，尾部斜杠归一后再拼 models。 */
    private URI endpoint(String apiUrl, String protocol, String cursor) {
        URI base = URI.create(apiUrl);
        String path = base.getRawPath();
        if (path == null || path.isEmpty() || "/".equals(path)) {
            path = "/v1";
        }
        path = path.replaceAll("/+$", "") + "/models";
        StringBuilder query = new StringBuilder();
        if ("anthropic-messages".equals(protocol)) {
            query.append("?limit=1000");
        }
        if (cursor != null) {
            query.append(query.isEmpty() ? '?' : '&')
                    .append("after_id=").append(URLEncoder.encode(cursor, StandardCharsets.UTF_8));
        }
        return URI.create(base.getScheme() + "://" + base.getRawAuthority() + path + query);
    }

    private JsonNode parse(String body) {
        try {
            JsonNode node = json.readTree(body);
            if (node == null || !node.isObject()) {
                throw new BusinessException(502, "上游模型列表结构不符合预期", null);
            }
            return node;
        } catch (IOException exception) {
            throw new BusinessException(502, "上游模型列表结构不符合预期", null);
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual()) {
            return null;
        }
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }

    private Integer positive(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber()) {
            return null;
        }
        long number = value.asLong();
        return number > 0 && number <= Integer.MAX_VALUE ? (int) number : null;
    }
}
