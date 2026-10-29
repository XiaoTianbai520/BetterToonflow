package com.toonflow.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.fasterxml.jackson.databind.JsonNode;
import com.toonflow.server.common.JsonbTypeHandler;
import java.time.OffsetDateTime;

@TableName(value = "ai_media_provider", autoResultMap = true)
public class AiMediaProvider {
    @TableId(value = "provider_id", type = IdType.INPUT)
    private String providerId;
    private String fileName;
    private String label;
    private String sourceCode;
    private String revision;
    @TableField(value = "config", typeHandler = JsonbTypeHandler.class)
    private JsonNode config;
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

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getSourceCode() {
        return sourceCode;
    }

    public void setSourceCode(String sourceCode) {
        this.sourceCode = sourceCode;
    }

    public String getRevision() {
        return revision;
    }

    public void setRevision(String revision) {
        this.revision = revision;
    }

    public JsonNode getConfig() {
        return config;
    }

    public void setConfig(JsonNode config) {
        this.config = config;
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
