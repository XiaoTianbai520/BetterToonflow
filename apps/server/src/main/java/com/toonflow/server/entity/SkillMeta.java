package com.toonflow.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.fasterxml.jackson.databind.JsonNode;
import com.toonflow.server.common.JsonbTypeHandler;
import java.time.OffsetDateTime;

@TableName(value = "skill_meta", autoResultMap = true)
public class SkillMeta {
    @TableId(value = "skill_name", type = IdType.INPUT)
    private String skillName;
    private String description;
    private Boolean enabled;
    @TableField(value = "file_order", typeHandler = JsonbTypeHandler.class)
    private JsonNode fileOrder;
    private OffsetDateTime updatedAt;

    public String getSkillName() {
        return skillName;
    }

    public void setSkillName(String skillName) {
        this.skillName = skillName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public JsonNode getFileOrder() {
        return fileOrder;
    }

    public void setFileOrder(JsonNode fileOrder) {
        this.fileOrder = fileOrder;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
