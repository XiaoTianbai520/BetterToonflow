package com.toonflow.server.proxy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.toonflow.server.common.BusinessException;
import com.toonflow.server.common.RevisionUtils;
import com.toonflow.server.entity.AiMediaProvider;
import com.toonflow.server.mapper.AiMediaProviderMapper;
import com.toonflow.server.service.DataSnapshotSyncService;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 媒体供应商写操作协同（DEC-05）：Bun 只负责 AST 校验与远程模型拉取，
 * ai_media_provider 表是唯一权威源，事务提交后由 DataSnapshotSyncService
 * 原子派生 data/providers/&lt;fileName&gt; 与 data/settings.json。
 */
@Service
public class MediaProviderPersistenceService {
    private static final Logger LOG = LoggerFactory.getLogger(MediaProviderPersistenceService.class);
    private static final int MAX_SOURCE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_MODELS = 500;
    private static final int MAX_LABEL_LENGTH = 128;
    private static final Set<Integer> STATUSES = Set.of(400, 403, 404, 409, 413, 415, 424, 500, 502);
    private static final Pattern FILE_NAME = Pattern.compile("[a-z][a-zA-Z0-9]{0,95}\\.ts");
    private static final Pattern PROVIDER_ID = Pattern.compile("[a-z][a-zA-Z0-9]{0,95}");
    private static final Pattern REVISION = Pattern.compile("[a-f0-9]{64}");

    private final AiMediaProviderMapper mapper;
    private final DataSnapshotSyncService snapshotSync;
    private final AgentRuntimeClient runtime;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public MediaProviderPersistenceService(AiMediaProviderMapper mapper, DataSnapshotSyncService snapshotSync,
            AgentRuntimeClient runtime, ObjectMapper json, PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.snapshotSync = snapshotSync;
        this.runtime = runtime;
        this.json = json;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public JsonNode add(String source) {
        requireSource(source);
        ObjectNode request = json.createObjectNode();
        request.put("source", source);
        JsonNode metadata = runtimeData(HttpMethod.POST, "/api/providers/media/validate", request);
        String providerId = requireField(metadata, "id");
        String fileName = requireField(metadata, "fileName");
        if (!PROVIDER_ID.matcher(providerId).matches() || !fileName.equals(providerId + ".ts")) {
            throw new BusinessException(500, "内部运行时返回的供应商标识无效", null);
        }
        // provider_id 在 V1 表结构中为 VARCHAR(64)，超长标识无法作为权威记录落库。
        if (providerId.length() > 64) {
            throw new BusinessException(400, "供应商 ID 不能超过 64 个字符", null);
        }
        String revision = requireRevision(metadata);
        if (!revision.equals(RevisionUtils.sha256(source))) {
            throw new BusinessException(500, "内部运行时返回的源码摘要不一致", null);
        }
        if (!metadata.path("models").isArray()) {
            throw new BusinessException(500, "内部运行时返回的模型列表无效", null);
        }
        String label = requireLabel(metadata, providerId);
        ReentrantLock lock = lock(fileName);
        lock.lock();
        try {
            transaction.executeWithoutResult(status -> {
                if (mapper.selectById(providerId) != null || findByFileName(fileName) != null) {
                    throw exists();
                }
                AiMediaProvider entity = new AiMediaProvider();
                entity.setProviderId(providerId);
                entity.setFileName(fileName);
                entity.setLabel(label);
                entity.setSourceCode(source);
                entity.setRevision(revision);
                entity.setConfig(json.createObjectNode());
                entity.setModels(metadata.get("models"));
                entity.setEnabled(Boolean.TRUE);
                entity.setUpdatedAt(OffsetDateTime.now());
                mapper.insert(entity);
                snapshotSync.syncAfterCommit();
            });
        } catch (DuplicateKeyException exception) {
            throw exists();
        } finally {
            lock.unlock();
        }
        return withoutSource(metadata);
    }

    public JsonNode save(String fileName, JsonNode models, String revision) {
        requireFileName(fileName);
        requireRevision(revision);
        if (models == null || !models.isArray() || models.size() > MAX_MODELS) {
            throw new BusinessException(400, "参数错误", null);
        }
        ReentrantLock lock = lock(fileName);
        lock.lock();
        try {
            AiMediaProvider current = requireProvider(fileName);
            RevisionUtils.requireMatch(revision, current.getRevision());
            ObjectNode request = json.createObjectNode();
            request.put("fileName", fileName);
            request.put("revision", revision);
            request.set("models", models);
            JsonNode metadata = commitRuntimeData(HttpMethod.PUT, "/api/providers/media/save", request);
            persist(current, metadata, revision);
            return withoutSource(metadata);
        } finally {
            lock.unlock();
        }
    }

    public JsonNode refreshModels(String fileName, String revision) {
        requireFileName(fileName);
        requireRevision(revision);
        ReentrantLock lock = lock(fileName);
        lock.lock();
        try {
            AiMediaProvider current = requireProvider(fileName);
            RevisionUtils.requireMatch(revision, current.getRevision());
            ObjectNode request = json.createObjectNode();
            request.put("fileName", fileName);
            request.put("revision", revision);
            JsonNode metadata = commitRuntimeData(HttpMethod.POST, "/api/providers/media/models", request);
            persist(current, metadata, revision);
            return withoutSource(metadata);
        } finally {
            lock.unlock();
        }
    }

    public void delete(String fileName, String revision) {
        requireFileName(fileName);
        requireRevision(revision);
        ReentrantLock lock = lock(fileName);
        lock.lock();
        try {
            AiMediaProvider current = requireProvider(fileName);
            RevisionUtils.requireMatch(revision, current.getRevision());
            ObjectNode request = json.createObjectNode();
            request.put("fileName", fileName);
            request.put("revision", revision);
            commitRuntimeData(HttpMethod.DELETE, "/api/providers/media/delete", request);
            transaction.executeWithoutResult(status -> {
                int deleted = mapper.delete(new LambdaQueryWrapper<AiMediaProvider>()
                        .eq(AiMediaProvider::getProviderId, current.getProviderId())
                        .eq(AiMediaProvider::getRevision, revision));
                if (deleted != 1) {
                    throw stale();
                }
                snapshotSync.syncAfterCommit();
            });
        } finally {
            lock.unlock();
        }
    }

    /** 数据库权威版本已校验通过后才落库，仍以 revision 做 CAS 防止并发覆盖。 */
    private void persist(AiMediaProvider current, JsonNode metadata, String expectedRevision) {
        String source = requireField(metadata, "source");
        String revision = requireRevision(metadata);
        if (!revision.equals(RevisionUtils.sha256(source))) {
            throw new BusinessException(500, "内部运行时返回的源码摘要不一致", null);
        }
        JsonNode models = metadata.path("models").isArray() ? metadata.get("models") : current.getModels();
        String label = requireLabel(metadata, current.getLabel());
        transaction.executeWithoutResult(status -> {
            AiMediaProvider update = new AiMediaProvider();
            update.setLabel(label);
            update.setSourceCode(source);
            update.setRevision(revision);
            update.setModels(models);
            update.setUpdatedAt(OffsetDateTime.now());
            int updated = mapper.update(update, new LambdaUpdateWrapper<AiMediaProvider>()
                    .eq(AiMediaProvider::getProviderId, current.getProviderId())
                    .eq(AiMediaProvider::getRevision, expectedRevision));
            if (updated != 1) {
                throw stale();
            }
            snapshotSync.syncAfterCommit();
        });
    }

    private JsonNode runtimeData(HttpMethod method, String path, JsonNode body) {
        HttpResponse<byte[]> upstream = runtime.exchange(method, path, serialize(body));
        return data(upstream);
    }

    /**
     * 数据库版本已确认一致时，磁盘快照滞后会让 Bun 误判 404/409；
     * 先对齐快照再重试一次，仍失败则按上游错误返回。
     */
    private JsonNode commitRuntimeData(HttpMethod method, String path, JsonNode body) {
        try {
            return runtimeData(method, path, body);
        } catch (BusinessException exception) {
            if (exception.getStatus() != 404 && exception.getStatus() != 409) {
                throw exception;
            }
            repairSnapshot();
            return runtimeData(method, path, body);
        }
    }

    private void repairSnapshot() {
        try {
            snapshotSync.syncAfterCommit();
        } catch (RuntimeException exception) {
            LOG.warn("event=media_provider_snapshot_repair_failed errorType={}", exception.getClass().getSimpleName());
        }
    }

    private JsonNode data(HttpResponse<byte[]> upstream) {
        JsonNode envelope = parse(upstream.body());
        int status = upstream.statusCode();
        int code = envelope.path("code").asInt(status);
        if (status == 200 && code == 200) {
            JsonNode value = envelope.get("data");
            return value == null ? json.nullNode() : value;
        }
        int mapped = STATUSES.contains(status) ? status : (STATUSES.contains(code) ? code : 500);
        String message = envelope.path("message").isTextual() ? envelope.get("message").textValue()
                : "内部运行时请求失败";
        JsonNode value = envelope.get("data");
        throw new BusinessException(mapped, message, value == null || value.isNull() ? null : value);
    }

    private JsonNode parse(byte[] body) {
        if (body == null || body.length == 0) {
            return json.createObjectNode();
        }
        try {
            return json.readTree(body);
        } catch (IOException exception) {
            throw new BusinessException(502, "内部运行时返回非预期结构", null);
        }
    }

    private byte[] serialize(JsonNode body) {
        try {
            return json.writeValueAsBytes(body);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("供应商请求体序列化失败", exception);
        }
    }

    private AiMediaProvider requireProvider(String fileName) {
        AiMediaProvider provider = findByFileName(fileName);
        if (provider == null) {
            throw new BusinessException(404, "媒体供应商不存在", Map.of("code", "ENOENT"));
        }
        return provider;
    }

    private AiMediaProvider findByFileName(String fileName) {
        return mapper.selectList(new LambdaQueryWrapper<AiMediaProvider>()
                .eq(AiMediaProvider::getFileName, fileName)).stream().findFirst().orElse(null);
    }

    private String requireField(JsonNode metadata, String field) {
        JsonNode value = metadata == null ? null : metadata.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new BusinessException(500, "内部运行时返回的数据不完整", null);
        }
        return value.textValue();
    }

    private String requireRevision(JsonNode metadata) {
        String revision = requireField(metadata, "revision");
        if (!REVISION.matcher(revision).matches()) {
            throw new BusinessException(500, "内部运行时返回的 revision 无效", null);
        }
        return revision;
    }

    private String requireLabel(JsonNode metadata, String fallback) {
        JsonNode value = metadata == null ? null : metadata.get("label");
        String label = value != null && value.isTextual() ? value.textValue().trim() : "";
        if (label.isEmpty()) {
            label = fallback == null ? "" : fallback;
        }
        if (label.isEmpty()) {
            throw new BusinessException(500, "内部运行时返回的数据不完整", null);
        }
        if (label.length() > MAX_LABEL_LENGTH) {
            throw new BusinessException(400, "供应商名称不能超过 128 个字符", null);
        }
        return label;
    }

    /** 源码属于内部协同字段，只落库，不返回前端。 */
    private JsonNode withoutSource(JsonNode metadata) {
        if (!(metadata instanceof ObjectNode)) {
            return metadata;
        }
        ObjectNode copy = ((ObjectNode) metadata).deepCopy();
        copy.remove("source");
        return copy;
    }

    private void requireSource(String source) {
        if (source == null || source.isBlank()) {
            throw new BusinessException(400, "参数错误", null);
        }
        if (source.getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES) {
            throw new BusinessException(400, "供应商源码不能超过 2 MB", null);
        }
    }

    private void requireFileName(String fileName) {
        if (fileName == null || !FILE_NAME.matcher(fileName).matches()) {
            throw new BusinessException(400, "参数错误", null);
        }
    }

    private void requireRevision(String revision) {
        if (revision == null || !REVISION.matcher(revision).matches()) {
            throw new BusinessException(400, "参数错误", null);
        }
    }

    private ReentrantLock lock(String fileName) {
        return locks.computeIfAbsent(fileName, key -> new ReentrantLock(true));
    }

    private BusinessException exists() {
        return new BusinessException(409, "媒体供应商已存在", Map.of("code", "EEXIST"));
    }

    private BusinessException stale() {
        return new BusinessException(409, "供应商文件已被外部修改，请刷新页面后再试", Map.of("code", "ESTALE"));
    }
}
