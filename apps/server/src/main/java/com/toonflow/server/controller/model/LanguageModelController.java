package com.toonflow.server.controller.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.toonflow.server.common.ApiResponse;
import com.toonflow.server.common.BusinessException;
import com.toonflow.server.service.model.LanguageModelCatalogService;
import com.toonflow.server.service.model.LanguageModelDtos;
import com.toonflow.server.service.model.LanguageModelProbeService;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 第四组：语言模型探测与已配置模型清单（apiContract 3.4.1 / 3.4.2）。 */
@RestController
public class LanguageModelController {
    private static final Set<String> PROTOCOLS = Set.of(
            "openai-completions", "openai-responses", "anthropic-messages");
    private static final int MAX_API_URL_LENGTH = 2048;
    private static final int MAX_API_KEY_LENGTH = 8192;
    private final LanguageModelProbeService probeService;
    private final LanguageModelCatalogService catalogService;

    public LanguageModelController(LanguageModelProbeService probeService,
            LanguageModelCatalogService catalogService) {
        this.probeService = probeService;
        this.catalogService = catalogService;
    }

    @PostMapping("/api/providers/models")
    public ApiResponse<List<LanguageModelDtos.ProviderModelItem>> fetchModels(@RequestBody(required = false) JsonNode body) {
        JsonNode payload = requireObject(body);
        String apiUrl = requireApiUrl(payload);
        String protocol = requireProtocol(payload);
        String apiKey = requireApiKey(payload);
        return ApiResponse.success(probeService.fetch(apiUrl, protocol, apiKey));
    }

    @GetMapping("/api/ai/models")
    public ApiResponse<List<LanguageModelDtos.ConfiguredLanguageModel>> models() {
        return ApiResponse.success(catalogService.list());
    }

    private JsonNode requireObject(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new BusinessException(400, "参数错误", null);
        }
        return body;
    }

    private String requireApiUrl(JsonNode body) {
        JsonNode value = body.path("apiUrl");
        if (!value.isTextual()) {
            throw new BusinessException(400, "参数错误", null);
        }
        String apiUrl = value.asText();
        if (apiUrl.isEmpty() || apiUrl.length() > MAX_API_URL_LENGTH) {
            throw new BusinessException(400, "请填写不含查询参数的 HTTP API 基础地址", null);
        }
        try {
            URI uri = new URI(apiUrl);
            boolean http = "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
            if (!http || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new BusinessException(400, "请填写不含查询参数的 HTTP API 基础地址", null);
            }
        } catch (URISyntaxException exception) {
            throw new BusinessException(400, "请填写不含查询参数的 HTTP API 基础地址", null);
        }
        return apiUrl;
    }

    private String requireProtocol(JsonNode body) {
        JsonNode value = body.path("protocol");
        if (!value.isTextual() || !PROTOCOLS.contains(value.asText())) {
            throw new BusinessException(400, "参数错误", null);
        }
        return value.asText();
    }

    private String requireApiKey(JsonNode body) {
        JsonNode value = body.path("apiKey");
        if (!value.isTextual()) {
            throw new BusinessException(400, "参数错误", null);
        }
        String apiKey = value.asText().trim();
        if (apiKey.isEmpty() || apiKey.length() > MAX_API_KEY_LENGTH) {
            throw new BusinessException(400, "请填写有效的 API 密钥", null);
        }
        return apiKey;
    }
}
