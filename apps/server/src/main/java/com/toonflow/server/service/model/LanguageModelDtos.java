package com.toonflow.server.service.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 语言模型供应商探测与配置模型列表的对外数据结构，字段与 apiContract 3.4.1 / 3.4.2 一致。
 */
public final class LanguageModelDtos {
    private LanguageModelDtos() {
    }

    /** 上游 {@code /v1/models} 返回的单条模型；可选令牌上限缺失时不输出字段。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProviderModelItem(String id, String label, Integer contextWindow, Integer maxOutputTokens) {
    }

    /** 已启用且配置完整的语言模型条目，供前端模型选择器与文本节点使用。 */
    public record ConfiguredLanguageModel(
            String providerId,
            String providerLabel,
            String protocol,
            String modelId,
            String label,
            int contextWindow,
            int maxOutputTokens) {
    }
}
