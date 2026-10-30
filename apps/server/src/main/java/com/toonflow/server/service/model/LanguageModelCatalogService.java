package com.toonflow.server.service.model;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.toonflow.server.entity.AiLanguageProvider;
import com.toonflow.server.mapper.AiLanguageProviderMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 从 PostgreSQL {@code ai_language_provider} 组装可直接使用的语言模型清单。
 * 只暴露已启用且 apiKey / apiUrl 配置完整的供应商，缺失字段的旧记录静默跳过。
 */
@Service
public class LanguageModelCatalogService {
    private static final Set<String> PROTOCOLS = Set.of(
            "openai-completions", "openai-responses", "anthropic-messages");
    private static final int DEFAULT_CONTEXT_WINDOW = 128000;
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 16384;
    private final AiLanguageProviderMapper mapper;

    public LanguageModelCatalogService(AiLanguageProviderMapper mapper) {
        this.mapper = mapper;
    }

    public List<LanguageModelDtos.ConfiguredLanguageModel> list() {
        List<LanguageModelDtos.ConfiguredLanguageModel> models = new ArrayList<>();
        for (AiLanguageProvider provider : mapper.selectList(new LambdaQueryWrapper<AiLanguageProvider>()
                .orderByAsc(AiLanguageProvider::getProviderId))) {
            if (!Boolean.TRUE.equals(provider.getEnabled())) {
                continue;
            }
            String apiKey = provider.getApiKey() == null ? "" : provider.getApiKey().trim();
            String apiUrl = provider.getApiUrl() == null ? "" : provider.getApiUrl().trim();
            String protocol = provider.getProtocol() == null ? "" : provider.getProtocol().trim();
            if (apiKey.isEmpty() || apiUrl.isEmpty() || !PROTOCOLS.contains(protocol)) {
                continue;
            }
            JsonNode configured = provider.getModels();
            if (configured == null || !configured.isArray()) {
                continue;
            }
            for (JsonNode model : configured) {
                if (!model.isObject()) {
                    continue;
                }
                String id = model.path("id").asText("").trim();
                if (id.isEmpty()) {
                    continue;
                }
                String label = model.path("label").asText("").trim();
                models.add(new LanguageModelDtos.ConfiguredLanguageModel(
                        provider.getProviderId(),
                        provider.getLabel() == null || provider.getLabel().isBlank()
                                ? provider.getProviderId() : provider.getLabel(),
                        protocol,
                        id,
                        label.isEmpty() ? id : label,
                        limit(model, "contextWindow", DEFAULT_CONTEXT_WINDOW),
                        limit(model, "maxOutputTokens", DEFAULT_MAX_OUTPUT_TOKENS)));
            }
        }
        return models;
    }

    private int limit(JsonNode model, String field, int fallback) {
        JsonNode value = model.path(field);
        if (!value.isIntegralNumber()) {
            return fallback;
        }
        long number = value.asLong();
        return number > 0 && number <= Integer.MAX_VALUE ? (int) number : fallback;
    }
}
