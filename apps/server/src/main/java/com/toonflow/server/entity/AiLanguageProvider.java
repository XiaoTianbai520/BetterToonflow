package com.toonflow.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.fasterxml.jackson.databind.JsonNode;
import com.toonflow.server.common.JsonbTypeHandler;
import java.time.OffsetDateTime;

@TableName(value = "ai_language_provider", autoResultMap = true)
public class AiLanguageProvider {
    @TableId(value = "provider_id", type = IdType.INPUT)
    private String providerId;
    private String label;
    private String apiUrl;
    private String protocol;
    private String apiKey;
    @TableField(value = "models", typeHandler = JsonbTypeHandler.class)
    private JsonNode models;
    private Boolean enabled;
    private OffsetDateTime updatedAt;

    public String getProviderId() {
        return providerId;
    }

    public void setProviderId(String providerId) {
        this.providerId = providerId;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public JsonNode getModels() {
        return models;
    }

    public void setModels(JsonNode models) {
        this.models = models;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
