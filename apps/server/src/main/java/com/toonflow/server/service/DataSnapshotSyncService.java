package com.toonflow.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.toonflow.server.common.BusinessException;
import com.toonflow.server.common.PathSandbox;
import com.toonflow.server.common.RevisionUtils;
import com.toonflow.server.entity.AiLanguageProvider;
import com.toonflow.server.entity.AiMediaProvider;
import com.toonflow.server.entity.SkillMeta;
import com.toonflow.server.entity.SysPersonalization;
import com.toonflow.server.entity.SysSetting;
import com.toonflow.server.mapper.AiLanguageProviderMapper;
import com.toonflow.server.mapper.AiMediaProviderMapper;
import com.toonflow.server.mapper.SkillMetaMapper;
import com.toonflow.server.mapper.SysPersonalizationMapper;
import com.toonflow.server.mapper.SysSettingMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 数据库始终是权威源；恢复队列保存待对账意图，不保存可能过期的文件内容。 */
@Service
public class DataSnapshotSyncService implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(DataSnapshotSyncService.class);
    private final SysSettingMapper settingMapper;
    private final SysPersonalizationMapper personalizationMapper;
    private final AiLanguageProviderMapper languageProviderMapper;
    private final AiMediaProviderMapper mediaProviderMapper;
    private final SkillMetaMapper skillMapper;
    private final ObjectMapper json;
    private final PathSandbox sandbox;
    private final TransactionTemplate readTransaction;
    private final Path configuredRoot;
    private final int maxAttempts;
    private final long initialBackoffMs;
    // ACT: MVP 配置规模小，全量对账串行执行；配置量显著增长后再拆分按快照键的队列。
    private final ReentrantLock syncLock = new ReentrantLock(true);
    private final AtomicLong requested = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final LongAdder attempts = new LongAdder();
    private final LongAdder failures = new LongAdder();
    private final LongAdder repairedFiles = new LongAdder();
    private volatile Instant lastSuccessfulReconciliation;
    private volatile Instant lastFailureAt;
    private volatile String lastErrorType;
    private volatile List<String> pendingItems = List.of();
    private volatile boolean started;

    public DataSnapshotSyncService(SysSettingMapper settingMapper, SysPersonalizationMapper personalizationMapper,
            AiLanguageProviderMapper languageProviderMapper, AiMediaProviderMapper mediaProviderMapper,
            SkillMetaMapper skillMapper, ObjectMapper json, PathSandbox sandbox,
            PlatformTransactionManager transactionManager,
            @Value("${toonflow.data-dir}") String dataDirectory,
            @Value("${toonflow.snapshot.max-attempts}") int maxAttempts,
            @Value("${toonflow.snapshot.initial-backoff-ms}") long initialBackoffMs) {
        if (maxAttempts < 1 || maxAttempts > 10 || initialBackoffMs < 1 || initialBackoffMs > 5000) {
            throw new IllegalArgumentException("快照重试次数须为 1~10，初始退避须为 1~5000ms");
        }
        this.settingMapper = settingMapper;
        this.personalizationMapper = personalizationMapper;
        this.languageProviderMapper = languageProviderMapper;
        this.mediaProviderMapper = mediaProviderMapper;
        this.skillMapper = skillMapper;
        this.json = json;
        this.sandbox = sandbox;
        this.configuredRoot = Path.of(dataDirectory).toAbsolutePath().normalize();
        this.maxAttempts = maxAttempts;
        this.initialBackoffMs = initialBackoffMs;
        this.readTransaction = new TransactionTemplate(transactionManager);
        // afterCommit 回调时原事务资源可能尚未释放，必须开启独立事务读取已提交状态。
        this.readTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.readTransaction.setReadOnly(true);
    }

    /** Wave 3 写服务在数据库事务内调用；回滚不会触发磁盘同步。无事务时按已提交状态对账。 */
    public void syncAfterCommit() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    requestReconciliation();
                }
            });
        } else {
            requestReconciliation();
        }
    }

    @Override
    public void run(ApplicationArguments args) {
        requested.incrementAndGet();
        try {
            recoverPending();
        } finally {
            started = true;
        }
    }

    /** 周期全量对账同时覆盖提交后回调遗漏、快照外部修改及进程中断后的恢复。 */
    @Scheduled(initialDelayString = "${toonflow.snapshot.reconciliation-delay-ms}",
            fixedDelayString = "${toonflow.snapshot.reconciliation-delay-ms}")
    public void reconcilePeriodically() {
        if (started) {
            requested.incrementAndGet();
            recoverPending();
        }
    }

    @Scheduled(initialDelayString = "${toonflow.snapshot.recovery-delay-ms}",
            fixedDelayString = "${toonflow.snapshot.recovery-delay-ms}")
    public void recoverContinuously() {
        if (started && requested.get() > completed.get()) {
            recoverPending();
        }
    }

    public SyncStatus getStatus() {
        return new SyncStatus(requested.get(), completed.get(), attempts.sum(), failures.sum(), repairedFiles.sum(),
                requested.get() > completed.get(), pendingItems, lastSuccessfulReconciliation,
                lastFailureAt, lastErrorType);
    }

    private void requestReconciliation() {
        requested.incrementAndGet();
        synchronizeWithRetry();
    }

    private void recoverPending() {
        try {
            synchronizeWithRetry();
        } catch (BusinessException exception) {
            // 每次失败已记录状态与日志，队列不出队；后续调度会继续重试。
            LOG.warn("event=snapshot_recovery_pending requested={} completed={} pendingItems={}",
                    requested.get(), completed.get(), pendingItems);
        }
    }

    private void synchronizeWithRetry() {
        syncLock.lock();
        try {
            if (requested.get() <= completed.get()) {
                return;
            }
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                long generation = requested.get();
                attempts.increment();
                try {
                    reconcile();
                    completed.set(generation);
                    pendingItems = List.of();
                    lastErrorType = null;
                    lastSuccessfulReconciliation = Instant.now();
                    LOG.info("event=snapshot_reconciliation_succeeded generation={} repairedFiles={} pending={}",
                            generation, repairedFiles.sum(), requested.get() > completed.get());
                    return;
                } catch (IOException | RuntimeException exception) {
                    failures.increment();
                    lastFailureAt = Instant.now();
                    lastErrorType = exception.getClass().getSimpleName();
                    if (pendingItems.isEmpty()) {
                        pendingItems = List.of("reconciliation");
                    }
                    // 不打印底层 message、SQL 或快照正文，防止 apiKey / 数据库凭据泄露。
                    LOG.error("event=snapshot_sync_failed attempt={} maxAttempts={} errorType={} pendingItems={}",
                            attempt, maxAttempts, lastErrorType, pendingItems);
                    if (attempt < maxAttempts && !pauseBeforeRetry(attempt)) {
                        break;
                    }
                }
            }
            throw new BusinessException(500, "数据库已提交，但文件快照同步失败，已加入后台恢复队列",
                    Map.of("partial", true, "code", "SNAPSHOT_SYNC_PENDING"));
        } finally {
            syncLock.unlock();
        }
    }

    private boolean pauseBeforeRetry(int attempt) {
        try {
            Thread.sleep(Math.min(5000, initialBackoffMs * (1L << (attempt - 1))));
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOG.warn("event=snapshot_retry_interrupted pendingItems={}", pendingItems);
            return false;
        }
    }

    private void reconcile() throws IOException {
        pendingItems = List.of("database");
        Map<String, byte[]> snapshots = Objects.requireNonNull(readTransaction.execute(status -> loadSnapshots()));
        pendingItems = List.of("dataRoot");
        Path root = sandbox.initializeRoot(configuredRoot);
        sandbox.createDirectories(root, "providers");
        sandbox.createDirectories(root, "memories");
        List<String> pending = new ArrayList<>(snapshots.keySet());
        for (String document : List.of("memories/memory.md", "AGENTS.md")) {
            if (!snapshots.containsKey(document)) {
                pending.add(document);
            }
        }
        for (String fileName : sandbox.listFiles(root, "providers")) {
            String path = "providers/" + fileName;
            if (fileName.endsWith(".ts") && !snapshots.containsKey(path)) {
                pending.add(path);
            }
        }
        pendingItems = List.copyOf(pending);
        for (String path : List.copyOf(pending)) {
            byte[] bytes = snapshots.get(path);
            if (bytes == null) {
                sandbox.deleteFile(root, path);
            } else if (sandbox.synchronizeFile(root, path, bytes)) {
                repairedFiles.increment();
                LOG.info("event=snapshot_file_repaired file={} revision={}", path, RevisionUtils.sha256(bytes));
            }
            pending.remove(path);
            pendingItems = List.copyOf(pending);
        }
    }

    private Map<String, byte[]> loadSnapshots() {
        ObjectNode settings = json.createObjectNode();
        settingMapper.selectList(null).stream().sorted(java.util.Comparator.comparing(SysSetting::getSettingKey))
                .filter(setting -> !excludedSetting(setting.getSettingKey()))
                .forEach(setting -> settings.set(setting.getSettingKey(), setting.getSettingValue()));
        if (settings.path("personalization").isObject()) {
            ((ObjectNode) settings.get("personalization")).remove(List.of("memory", "agents"));
        }
        List<AiLanguageProvider> languages = languageProviderMapper.selectList(null).stream()
                .sorted(java.util.Comparator.comparing(AiLanguageProvider::getProviderId)).toList();
        ArrayNode providers = json.createArrayNode();
        for (AiLanguageProvider provider : languages) {
            ObjectNode value = providers.addObject();
            value.put("id", provider.getProviderId());
            value.put("label", provider.getLabel());
            value.put("apiUrl", provider.getApiUrl());
            value.put("protocol", provider.getProtocol());
            value.put("apiKey", provider.getApiKey());
            value.set("models", provider.getModels());
            value.put("enabled", provider.getEnabled());
        }
        if (!providers.isEmpty()) {
            settings.set("providers", providers);
        } else {
            settings.remove("providers");
        }
        // 兼容当前 Bun 配置读取路径；供应商数据始终由独立权威表派生。
        ArrayNode enabledProviders = json.createArrayNode();
        for (int index = 0; index < languages.size(); index++) {
            if (Boolean.TRUE.equals(languages.get(index).getEnabled())) {
                enabledProviders.add(providers.get(index));
            }
        }
        settings.set("customProviders", enabledProviders);
        ObjectNode mediaProviders = json.createObjectNode();
        ObjectNode mediaConfigs = json.createObjectNode();
        Map<String, byte[]> snapshots = new LinkedHashMap<>();
        Set<String> fileNames = new HashSet<>();
        for (AiMediaProvider provider : mediaProviderMapper.selectList(null).stream()
                .sorted(java.util.Comparator.comparing(AiMediaProvider::getProviderId)).toList()) {
            String fileName = provider.getFileName();
            if (fileName == null || !fileName.matches("[a-z][a-zA-Z0-9]{0,95}\\.ts")
                    || fileName.matches("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])\\.ts")
                    || !fileNames.add(fileName.toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException("数据库媒体供应商文件名无效或重复");
            }
            addDocument(snapshots, "providers/" + fileName, provider.getSourceCode(), provider.getRevision());
            ObjectNode value = mediaProviders.putObject(provider.getProviderId());
            value.set("config", provider.getConfig());
            value.put("enabled", provider.getEnabled());
            value.set("models", provider.getModels());
            mediaConfigs.set(provider.getProviderId(), provider.getConfig());
        }
        settings.remove(List.of("mediaProviders", "mediaProviderConfigs"));
        if (!mediaProviders.isEmpty()) {
            settings.set("mediaProviders", mediaProviders);
            settings.set("mediaProviderConfigs", mediaConfigs);
        }
        for (SysPersonalization document : personalizationMapper.selectList(null)) {
            String path = switch (document.getDocType()) {
                case "memory" -> "memories/memory.md";
                case "agents" -> "AGENTS.md";
                default -> throw new IllegalStateException("数据库个性化文档类型无效");
            };
            addDocument(snapshots, path, document.getContent(), document.getRevision());
        }
        ObjectNode envelope = json.createObjectNode();
        envelope.set("settings", settings);
        envelope.set("toolConfigs", settings.path("toolConfigs").isMissingNode()
                ? json.createObjectNode() : settings.get("toolConfigs"));
        ArrayNode disabledSkills = envelope.putArray("disabledSkills");
        skillMapper.selectList(null).stream().filter(skill -> Boolean.FALSE.equals(skill.getEnabled()))
                .map(SkillMeta::getSkillName).sorted().forEach(disabledSkills::add);
        try {
            snapshots.put("settings.json", json.writerWithDefaultPrettyPrinter().writeValueAsBytes(envelope));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("配置快照序列化失败");
        }
        return snapshots;
    }

    private boolean excludedSetting(String key) {
        return key.equals("memory") || key.equals("agents") || key.startsWith("desktopUpdate") || key.equals("mcp");
    }

    private void addDocument(Map<String, byte[]> snapshots, String path, String content, String revision) {
        if (content == null || revision == null || !RevisionUtils.sha256(content).equals(revision)) {
            throw new IllegalStateException("数据库文档正文与 revision 不一致");
        }
        snapshots.put(path, content.getBytes(StandardCharsets.UTF_8));
    }

    public record SyncStatus(long requestedGeneration, long completedGeneration, long attempts, long failures,
            long repairedFiles, boolean recoveryPending, List<String> pendingItems,
            Instant lastSuccessfulReconciliation, Instant lastFailureAt, String lastErrorType) {
    }
}
