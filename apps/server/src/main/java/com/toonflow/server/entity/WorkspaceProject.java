package com.toonflow.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

@TableName(value = "workspace_project", autoResultMap = true)
public class WorkspaceProject {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String name;
    private String directory;
    private OffsetDateTime lastOpenedAt;
    private OffsetDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDirectory() {
        return directory;
    }

    public void setDirectory(String directory) {
        this.directory = directory;
    }

    public OffsetDateTime getLastOpenedAt() {
        return lastOpenedAt;
    }

    public void setLastOpenedAt(OffsetDateTime lastOpenedAt) {
        this.lastOpenedAt = lastOpenedAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
