package com.toonflow.server.service.settings;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.toonflow.server.common.BusinessException;
import com.toonflow.server.entity.AiLanguageProvider;
import com.toonflow.server.entity.AiMediaProvider;
import com.toonflow.server.entity.SysSetting;
import com.toonflow.server.mapper.AiLanguageProviderMapper;
import com.toonflow.server.mapper.AiMediaProviderMapper;
import com.toonflow.server.mapper.SysSettingMapper;
import com.toonflow.server.service.DataSnapshotSyncService;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SettingsService {
    private static final Set<String> PROTOCOLS = Set.of(
            "openai-completions", "openai-responses", "anthropic-messages");
    private static final Set<String> NODE_TYPES = Set.of("textNode", "imageNode", "imageGenerationNode",
            "videoNode", "videoGenerationNode", "audioNode", "audioGenerationNode");
    private final SysSettingMapper settingMapper;
    private final AiLanguageProviderMapper languageMapper;
    private final AiMediaProviderMapper mediaMapper;
    private final DataSnapshotSyncService snapshotSync;
    private final ObjectMapper json;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;
    private final ReentrantLock saveLock = new ReentrantLock(true);

    public SettingsService(SysSettingMapper settingMapper, AiLanguageProviderMapper languageMapper,
            AiMediaProviderMapper mediaMapper, DataSnapshotSyncService snapshotSync, ObjectMapper json,
            PlatformTransactionManager transactionManager) {
        this.settingMapper = settingMapper;
        this.languageMapper = languageMapper;
        this.mediaMapper = mediaMapper;
        this.snapshotSync = snapshotSync;
        this.json = json;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public JsonNode get() {
        return Objects.requireNonNull(readTransaction.execute(status -> loadSettings()));
    }

    private ObjectNode loadSettings() {
        ObjectNode settings = json.createObjectNode();
        for (SysSetting setting : settingMapper.selectList(
                new LambdaQueryWrapper<SysSetting>().orderByAsc(SysSetting::getSettingKey))) {
            settings.set(setting.getSettingKey(), setting.getSettingValue());
        }
        stripExcluded(settings);
        // 供应商始终从独立权威表组装，避免旧 sys_setting 记录遮蔽现状。
        settings.remove(List.of("providers", "mediaProviders", "customProviders", "mediaProviderConfigs"));
        ArrayNode providers = json.createArrayNode();
        for (AiLanguageProvider provider : languageMapper.selectList(
                new LambdaQueryWrapper<AiLanguageProvider>().orderByAsc(AiLanguageProvider::getProviderId))) {
            ObjectNode value = providers.addObject();
            value.put("id", provider.getProviderId());
            value.put("label", provider.getLabel());
            value.put("apiUrl", provider.getApiUrl());
            value.put("apiKey", provider.getApiKey());
            value.put("protocol", provider.getProtocol());
            value.set("models", provider.getModels());
            value.put("enabled", provider.getEnabled());
        }
        if (!providers.isEmpty()) {
            settings.set("providers", providers);
        }
        ObjectNode mediaProviders = json.createObjectNode();
        for (AiMediaProvider provider : mediaMapper.selectList(
                new LambdaQueryWrapper<AiMediaProvider>().orderByAsc(AiMediaProvider::getProviderId))) {
            ObjectNode value = mediaProviders.putObject(provider.getProviderId());
            value.set("config", provider.getConfig());
            value.put("enabled", provider.getEnabled());
            value.set("models", provider.getModels());
        }
        if (!mediaProviders.isEmpty()) {
            settings.set("mediaProviders", mediaProviders);
        }
        return settings;
    }

    public JsonNode save(JsonNode body) {
        require(body != null && body.isObject() && body.path("settings").isObject());
        ObjectNode settings = ((ObjectNode) body.get("settings")).deepCopy();
        // DEC-03 / DEC-07：先剥离，再校验，遗留字段即使类型错误也不生效。
        stripExcluded(settings);
        validateSettings(settings);
        List<AiLanguageProvider> languages = parseLanguages(settings.path("providers"));
        saveLock.lock();
        try {
            return Objects.requireNonNull(writeTransaction.execute(status -> saveSettings(settings, languages)));
        } finally {
            saveLock.unlock();
        }
    }

    private ObjectNode saveSettings(ObjectNode settings, List<AiLanguageProvider> languages) {
        JsonNode media = settings.path("mediaProviders");
        List<AiMediaProvider> installed = mediaMapper.selectList(null);
        Set<String> installedIds = new HashSet<>();
        installed.forEach(provider -> installedIds.add(provider.getProviderId()));
        media.fieldNames().forEachRemaining(id -> require(installedIds.contains(id)));

        ObjectNode result = json.createObjectNode();
        if (settings.has("providers")) {
            result.set("models", listModels(languages));
        }
        normalizeNodeMenu(settings);
        result.set("nodeMenu", settings.path("general").path("nodeMenu").isMissingNode()
                ? json.nullNode() : settings.get("general").get("nodeMenu"));

        settingMapper.delete(new LambdaQueryWrapper<SysSetting>());
        ObjectNode values = settings.deepCopy();
        values.remove(List.of("providers", "mediaProviders", "customProviders", "mediaProviderConfigs"));
        OffsetDateTime now = OffsetDateTime.now();
        values.properties().forEach(entry -> {
            SysSetting setting = new SysSetting();
            setting.setSettingKey(entry.getKey());
            setting.setSettingValue(entry.getValue());
            setting.setUpdatedAt(now);
            settingMapper.insert(setting);
        });
        languageMapper.delete(new LambdaQueryWrapper<AiLanguageProvider>());
        for (AiLanguageProvider provider : languages) {
            provider.setUpdatedAt(now);
            languageMapper.insert(provider);
        }
        for (AiMediaProvider provider : installed) {
            JsonNode value = media.path(provider.getProviderId());
            // 完整覆盖配置时保留源码、revision、模型定义；安装/删除属于供应商模块。
            AiMediaProvider update = new AiMediaProvider();
            update.setProviderId(provider.getProviderId());
            update.setConfig(value.has("config") ? value.get("config") : json.createObjectNode());
            update.setEnabled(value.path("enabled").asBoolean(false));
            update.setUpdatedAt(now);
            mediaMapper.updateById(update);
        }
        snapshotSync.syncAfterCommit();
        return result;
    }

    public Map<String, Object> systemPrompt() {
        return Map.of("defaultSystemPrompt", DefaultSystemPrompt.CONTENT, "maxLength", 50000);
    }

    private void stripExcluded(ObjectNode settings) {
        List<String> excluded = new ArrayList<>();
        settings.fieldNames().forEachRemaining(key -> {
            if (key.startsWith("desktopUpdate") || Set.of("mcp", "memory", "agents").contains(key)) {
                excluded.add(key);
            }
        });
        settings.remove(excluded);
        if (settings.path("personalization").isObject()) {
            ((ObjectNode) settings.get("personalization")).remove(List.of("memory", "agents"));
        }
    }

    private void validateSettings(ObjectNode settings) {
        settings.fieldNames().forEachRemaining(key -> require(!key.isBlank() && key.length() <= 64));
        if (settings.has("agentSystemPrompt")) {
            require(settings.get("agentSystemPrompt").isTextual());
            if (settings.get("agentSystemPrompt").textValue().length() > 50000) {
                throw new BusinessException(400, "系统提示词不能超过 50000 字", null);
            }
        }
        for (String key : List.of("general", "personalization", "ffmpeg", "mediaProviders")) {
            require(!settings.has(key) || settings.get(key).isObject());
        }
        JsonNode ffmpeg = settings.path("ffmpeg");
        if (ffmpeg.has("mode")) {
            require(ffmpeg.get("mode").isTextual()
                    && Set.of("auto", "download", "system").contains(ffmpeg.get("mode").textValue()));
        }
        if (ffmpeg.has("source")) {
            require(ffmpeg.get("source").isTextual() && !ffmpeg.get("source").textValue().isBlank());
        }
        if (settings.has("ffmpegMode")) {
            require(settings.get("ffmpegMode").isTextual()
                    && Set.of("auto", "download", "system").contains(settings.get("ffmpegMode").textValue()));
        }
        settings.path("mediaProviders").properties().forEach(entry -> {
            require(!entry.getKey().isBlank() && entry.getKey().length() <= 64 && entry.getValue().isObject());
            JsonNode value = entry.getValue();
            require(!value.has("config") || value.get("config").isObject());
            require(!value.has("enabled") || value.get("enabled").isBoolean());
            require(!value.has("models") || value.get("models").isArray());
        });
    }

    private List<AiLanguageProvider> parseLanguages(JsonNode value) {
        List<AiLanguageProvider> providers = new ArrayList<>();
        if (value.isMissingNode()) {
            return providers;
        }
        require(value.isArray());
        Set<String> ids = new HashSet<>();
        for (JsonNode item : value) {
            require(item.isObject());
            String id = requiredText(item, "id", 64);
            require(id.matches("[a-zA-Z0-9][a-zA-Z0-9._-]*") && ids.add(id));
            String protocol = requiredText(item, "protocol", 32);
            require(PROTOCOLS.contains(protocol));
            String apiUrl = requiredText(item, "apiUrl", Integer.MAX_VALUE);
            URI uri = URI.create(apiUrl);
            require(("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getQuery() == null && uri.getFragment() == null);
            require(!item.has("enabled") || item.get("enabled").isBoolean());
            require(item.path("models").isArray());
            Set<String> modelIds = new HashSet<>();
            for (JsonNode model : item.get("models")) {
                require(model.isObject() && modelIds.add(requiredText(model, "id", Integer.MAX_VALUE)));
                optionalText(model, "label", model.get("id").textValue(), Integer.MAX_VALUE);
                for (String key : List.of("contextWindow", "maxOutputTokens")) {
                    require(!model.has(key) || (model.get(key).isIntegralNumber()
                            && model.get(key).canConvertToLong() && model.get(key).longValue() > 0));
                }
            }
            AiLanguageProvider provider = new AiLanguageProvider();
            provider.setProviderId(id);
            provider.setLabel(optionalText(item, "label", id, 128));
            provider.setApiUrl(apiUrl);
            provider.setApiKey(optionalText(item, "apiKey", "", Integer.MAX_VALUE));
            provider.setProtocol(protocol);
            provider.setModels(item.get("models").deepCopy());
            provider.setEnabled(item.path("enabled").asBoolean(true));
            providers.add(provider);
        }
        return providers;
    }

    private ArrayNode listModels(List<AiLanguageProvider> providers) {
        ArrayNode models = json.createArrayNode();
        for (AiLanguageProvider provider : providers) {
            if (!Boolean.TRUE.equals(provider.getEnabled())) {
                continue;
            }
            for (JsonNode model : provider.getModels()) {
                ObjectNode value = models.addObject();
                value.put("providerId", provider.getProviderId());
                value.put("providerLabel", provider.getLabel());
                value.put("protocol", provider.getProtocol());
                value.put("modelId", model.get("id").textValue());
                value.put("label", model.path("label").asText(model.get("id").textValue()));
                value.put("contextWindow", model.path("contextWindow").asLong(128000));
                value.put("maxOutputTokens", model.path("maxOutputTokens").asLong(16384));
            }
        }
        return models;
    }

    private void normalizeNodeMenu(ObjectNode settings) {
        if (settings.path("general").has("nodeMenu")) {
            ObjectNode general = (ObjectNode) settings.get("general");
            ArrayNode menu = normalizeMenu(general.get("nodeMenu"), 1, false, new HashSet<>());
            if (menu.isEmpty()) {
                general.remove("nodeMenu");
            } else {
                general.set("nodeMenu", menu);
            }
        }
    }

    private ArrayNode normalizeMenu(JsonNode items, int depth, boolean hidden, Set<String> ids) {
        ArrayNode result = json.createArrayNode();
        if (!items.isArray()) {
            return result;
        }
        for (JsonNode item : items) {
            String type = item.isTextual() ? item.textValue().trim() : item.path("type").asText("").trim();
            boolean itemHidden = hidden || item.path("hidden").asBoolean(false);
            if (!type.isEmpty()) {
                if (!NODE_TYPES.contains(type) || !ids.add("node:" + type)) {
                    continue;
                }
                String alias = item.path("alias").asText("").trim();
                if (alias.isEmpty() && !itemHidden) {
                    result.add(type);
                } else {
                    ObjectNode node = result.addObject().put("type", type);
                    if (!alias.isEmpty()) {
                        node.put("alias", alias);
                    }
                    if (itemHidden) {
                        node.put("hidden", true);
                    }
                }
                continue;
            }
            String id = item.path("id").asText("");
            String label = item.path("label").asText("").trim();
            if (!id.startsWith("group:") || id.length() <= 6 || label.isEmpty()
                    || !item.path("children").isArray()) {
                continue;
            }
            // 沿用原菜单的三级上限；旧配置超深或重复组提升其子项。
            boolean promote = depth >= 3 || !ids.add(id);
            ArrayNode children = normalizeMenu(item.get("children"), promote ? depth : depth + 1,
                    promote && itemHidden, ids);
            if (promote) {
                result.addAll(children);
            } else {
                ObjectNode group = result.addObject().put("id", id).put("label", label);
                group.set("children", children);
                if (itemHidden) {
                    group.put("hidden", true);
                }
            }
        }
        return result;
    }

    private String requiredText(JsonNode value, String key, int maxLength) {
        require(value.path(key).isTextual());
        String text = value.get(key).textValue();
        require(!text.isBlank() && text.length() <= maxLength);
        return text;
    }

    private String optionalText(JsonNode value, String key, String fallback, int maxLength) {
        if (!value.has(key)) {
            return fallback;
        }
        require(value.get(key).isTextual() && value.get(key).textValue().length() <= maxLength);
        return value.get(key).textValue();
    }

    private void require(boolean valid) {
        if (!valid) {
            throw new BusinessException(400, "参数错误", null);
        }
    }
}
