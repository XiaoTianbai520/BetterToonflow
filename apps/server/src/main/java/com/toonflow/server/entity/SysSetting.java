package com.toonflow.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.fasterxml.jackson.databind.JsonNode;
import com.toonflow.server.common.JsonbTypeHandler;
import java.time.OffsetDateTime;

@TableName(value = "sys_setting", autoResultMap = true)
public class SysSetting {
    @TableId(value = "setting_key", type = IdType.INPUT)
    private String settingKey;
    @TableField(value = "setting_value", typeHandler = JsonbTypeHandler.class)
    private JsonNode settingValue;
    private OffsetDateTime updatedAt;

    public String getSettingKey() {
        return settingKey;
    }

    public void setSettingKey(String settingKey) {
        this.settingKey = settingKey;
    }

    public JsonNode getSettingValue() {
        return settingValue;
    }

    public void setSettingValue(JsonNode settingValue) {
        this.settingValue = settingValue;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
