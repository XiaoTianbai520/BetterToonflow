package com.toonflow.server.service.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.toonflow.server.common.BusinessException;
import com.toonflow.server.common.PathSandbox;
import com.toonflow.server.common.PerPathMutexQueue;
import com.toonflow.server.entity.SkillMeta;
import com.toonflow.server.mapper.SkillMetaMapper;
import com.toonflow.server.service.DataSnapshotSyncService;
import com.toonflow.server.service.asset.SandboxedFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SkillService {
    private static final Logger LOG = LoggerFactory.getLogger(SkillService.class);
    private final SandboxedFiles files;
    private final PathSandbox sandbox;
    private final PerPathMutexQueue mutex;
    private final SkillPackage packages;
    private final SkillMetaMapper mapper;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    private final DataSnapshotSyncService snapshots;

    public SkillService(SandboxedFiles files, PathSandbox sandbox, PerPathMutexQueue mutex, SkillPackage packages,
            SkillMetaMapper mapper, ObjectMapper json, PlatformTransactionManager transactionManager,
            DataSnapshotSyncService snapshots) {
        this.files = files;
        this.sandbox = sandbox;
        this.mutex = mutex;
        this.packages = packages;
        this.mapper = mapper;
        this.json = json;
        this.transaction = new TransactionTemplate(transactionManager);
        this.snapshots = snapshots;
    }

    public List<SkillSummary> get() throws IOException {
        return locked(root -> {
            Map<String, SkillMeta> metadata = new HashMap<>();
            mapper.selectList(null).forEach(meta -> metadata.put(meta.getSkillName(), meta));
            List<SkillSummary> summaries = new ArrayList<>();
            try (var children = Files.newDirectoryStream(root)) {
                for (Path entry : children) {
                    String name = entry.getFileName().toString();
                    if (name.startsWith(".")) {
                        continue;
                    }
                    Path directory = files.resolve(root, name, 1024);
                    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                        continue;
                    }
                    try {
                        var manifest = manifest(directory, name);
                        SkillMeta meta = metadata.get(name);
                        summaries.add(manifest.summary(meta == null || !Boolean.FALSE.equals(meta.getEnabled())));
                    } catch (BusinessException exception) {
                        if (exception.getStatus() != 400) {
                            throw exception;
                        }
                        LOG.warn("event=invalid_skill_skipped skill={}", name);
                    } catch (java.nio.file.NoSuchFileException exception) {
                        LOG.warn("event=skill_entry_missing skill={}", name);
                    }
                }
            }
            summaries.sort(java.util.Comparator.comparing(SkillSummary::name));
            return summaries;
        });
    }

    public SkillSummary install(String fileName, String base64, String url, boolean force) throws IOException {
        boolean remote = url != null;
        if (remote ? fileName != null || base64 != null : fileName == null || base64 == null) {
            throw invalid("url 与 fileName + base64 必须二选一");
        }
        byte[] bytes;
        if (remote) {
            var download = packages.download(url);
            fileName = download.fileName();
            bytes = download.bytes();
        } else {
            SkillPackage.validateExtension(fileName);
            if (base64.length() > ((SkillPackage.MAX_BYTES + 2L) / 3) * 4) {
                throw new BusinessException(413, "技能包超过 20MB", null);
            }
            try {
                bytes = Base64.getDecoder().decode(base64);
            } catch (IllegalArgumentException exception) {
                throw invalid("Base64 技能包非法");
            }
        }
        if (bytes.length == 0) {
            throw invalid("技能包不能为空");
        }
        if (bytes.length > SkillPackage.MAX_BYTES) {
            throw new BusinessException(413, "技能包超过 20MB", null);
        }
        boolean markdown = fileName.toLowerCase(Locale.ROOT).endsWith(".md");
        return locked(root -> installPackage(root, bytes, markdown, force));
    }

    private SkillSummary installPackage(Path root, byte[] bytes, boolean markdown, boolean force) throws IOException {
        Path stage = files.resolve(root, ".install-" + UUID.randomUUID(), 1024);
        Files.createDirectory(stage);
        try {
            if (markdown) {
                files.create(stage, "SKILL.md", bytes);
            } else {
                packages.extract(bytes, stage);
            }
            Path source = packages.packageRoot(stage);
            var manifest = manifest(source, null);
            String name = manifest.name();
            Path destination = files.resolve(root, name, 1024);
            boolean exists = Files.exists(destination, LinkOption.NOFOLLOW_LINKS);
            if (exists && !force) {
                throw invalid("同名技能已存在，请使用 force 覆盖安装");
            }
            if (exists && !Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) {
                throw invalid("技能目标不是目录");
            }
            SkillMeta previous = mapper.selectById(name);
            SkillMeta meta = metadata(name, manifest.description(), previous);
            sandbox.writeAtomic(source, SkillPackage.ORDER_FILE, json.writeValueAsBytes(meta.getFileOrder()));
            Path backup = files.resolve(root, ".backup-" + UUID.randomUUID(), 1024);
            boolean[] moved = {false, false};
            try {
                transact(() -> {
                    if (exists) {
                        Files.move(destination, backup);
                        moved[0] = true;
                    }
                    Files.move(source, destination);
                    moved[1] = true;
                    persist(meta, previous);
                    return null;
                });
            } catch (IOException | RuntimeException exception) {
                try {
                    if (moved[1]) {
                        files.removeTree(root, destination);
                    }
                    if (moved[0]) {
                        Files.move(backup, destination);
                    }
                } catch (IOException | RuntimeException rollback) {
                    throw rollbackFailed(exception, rollback);
                }
                throw exception;
            }
            // 数据库提交后的同步失败由公共恢复队列处理，不能再回滚已提交的安装。
            try {
                snapshots.syncAfterCommit();
            } finally {
                if (moved[0]) {
                    files.removeTree(root, backup);
                }
            }
            return manifest.summary(!Boolean.FALSE.equals(meta.getEnabled()));
        } finally {
            if (Files.exists(stage, LinkOption.NOFOLLOW_LINKS)) {
                files.removeTree(root, stage);
            }
        }
    }

    public void uninstall(String name) throws IOException {
        locked(root -> {
            Path directory = skill(root, name);
            Path backup = files.resolve(root, ".uninstall-" + UUID.randomUUID(), 1024);
            boolean[] moved = {false};
            try {
                transact(() -> {
                    Files.move(directory, backup);
                    moved[0] = true;
                    mapper.deleteById(name);
                    return null;
                });
            } catch (IOException | RuntimeException exception) {
                if (moved[0]) {
                    try {
                        Files.move(backup, directory);
                    } catch (IOException | RuntimeException rollback) {
                        throw rollbackFailed(exception, rollback);
                    }
                }
                throw exception;
            }
            try {
                snapshots.syncAfterCommit();
            } finally {
                files.removeTree(root, backup);
            }
            return null;
        });
    }

    public Map<String, Object> setEnabled(String name, boolean enabled) throws IOException {
        return locked(root -> {
            Path directory = skill(root, name);
            var manifest = manifest(directory, name);
            transact(() -> {
                SkillMeta previous = mapper.selectById(name);
                SkillMeta meta = metadata(name, manifest.description(), previous);
                if (previous == null) {
                    meta.setFileOrder(readOrder(directory));
                }
                meta.setEnabled(enabled);
                persist(meta, previous);
                return null;
            });
            snapshots.syncAfterCommit();
            return Map.of("name", name, "enabled", enabled);
        });
    }

    public Map<String, Object> list(String name) throws IOException {
        return locked(root -> {
            Path directory = skill(root, name);
            List<String> known = fileNames(directory);
            SkillMeta meta = mapper.selectById(name);
            JsonNode order = meta == null ? readOrder(directory) : meta.getFileOrder();
            Set<String> sorted = new LinkedHashSet<>();
            sorted.add("SKILL.md");
            if (order != null && order.isArray()) {
                for (JsonNode item : order) {
                    if (item.isTextual() && known.contains(item.textValue())) {
                        sorted.add(item.textValue());
                    }
                }
            }
            known.stream().sorted().forEach(sorted::add);
            return Map.of("mainPath", "SKILL.md", "files", List.copyOf(sorted));
        });
    }

    public Map<String, String> read(String name, String path) throws IOException {
        return locked(root -> {
            Path directory = skill(root, name);
            Path target = editable(directory, path, false);
            String content = SkillPackage.utf8(files.read(directory, SandboxedFiles.relative(directory, target),
                    1024, SkillPackage.MAX_BYTES));
            return Map.of("content", content);
        });
    }

    public void save(String name, String path, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > SkillPackage.MAX_BYTES) {
            throw new BusinessException(413, "技能文件超过 20MB", null);
        }
        SkillPackage.utf8(bytes);
        locked(root -> {
            Path directory = skill(root, name);
            Path target = editable(directory, path, false);
            String relative = SandboxedFiles.relative(directory, target);
            files.regularFile(directory, relative, 1024);
            boolean main = target.equals(directory.resolve("SKILL.md"));
            var manifest = main ? packages.manifest(content, name) : null;
            if (!main) {
                sandbox.writeAtomic(directory, relative, bytes);
                return null;
            }
            SkillMeta previous = mapper.selectById(name);
            SkillMeta meta = metadata(name, manifest.description(), previous);
            if (previous == null) {
                meta.setFileOrder(readOrder(directory));
            }
            replaceWithRollback(directory, relative, bytes, () -> {
                persist(meta, previous);
            });
            snapshots.syncAfterCommit();
            return null;
        });
    }

    public void create(String name, String path) throws IOException {
        locked(root -> {
            Path directory = skill(root, name);
            Path target = editable(directory, path, true);
            files.create(directory, SandboxedFiles.relative(directory, target), new byte[0]);
            return null;
        });
    }

    public void move(String name, String path, String target) throws IOException {
        locked(root -> {
            Path directory = skill(root, name);
            Path source = editable(directory, path, true);
            Path destination = editable(directory, target, true);
            files.regularFile(directory, SandboxedFiles.relative(directory, source), 1024);
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                throw new java.nio.file.FileAlreadyExistsException(destination.toString());
            }
            Files.move(source, destination);
            return null;
        });
    }

    public void order(String name, List<String> order) throws IOException {
        locked(root -> {
            Path directory = skill(root, name);
            Set<String> known = new HashSet<>(fileNames(directory));
            List<String> normalized = new ArrayList<>();
            for (String path : order) {
                Path target = editable(directory, path, true);
                String relative = SandboxedFiles.relative(directory, target);
                if (!known.contains(relative) || normalized.contains(relative)) {
                    throw invalid("排序列表包含重复路径或不存在的附属文件");
                }
                normalized.add(relative);
            }
            var manifest = manifest(directory, name);
            byte[] bytes = json.writeValueAsBytes(normalized);
            replaceWithRollback(directory, SkillPackage.ORDER_FILE, bytes, () -> {
                SkillMeta previous = mapper.selectById(name);
                SkillMeta meta = metadata(name, manifest.description(), previous);
                meta.setFileOrder(json.valueToTree(normalized));
                persist(meta, previous);
            });
            return null;
        });
    }

    public byte[] export(String type, String name) throws IOException {
        if (!"skill".equals(type) || name == null || !name.matches("[a-zA-Z0-9][a-zA-Z0-9-]{0,95}")) {
            throw invalid("仅允许导出 skill，且名称必须为 1..96 位字母、数字或短横线");
        }
        return locked(root -> packages.export(skill(root, name)));
    }

    private Path skill(Path root, String name) throws IOException {
        if (name == null || name.isEmpty() || name.startsWith(".") || name.length() > 1024
                || name.contains("/") || name.contains("\\")) {
            throw invalid("技能名称非法");
        }
        Path directory = files.resolve(root, name, 1024);
        sandbox.root(directory);
        files.regularFile(directory, "SKILL.md", 1024);
        return directory;
    }

    private Path editable(Path directory, String path, boolean attachmentOnly) throws IOException {
        Path target = files.resolve(directory, path == null ? "SKILL.md" : path, 1024);
        String leaf = target.getFileName().toString();
        if (leaf.equalsIgnoreCase(SkillPackage.ORDER_FILE)
                || (attachmentOnly && leaf.equalsIgnoreCase("SKILL.md"))) {
            throw invalid("不能修改系统排序文件或移动、创建主文件");
        }
        return target;
    }

    private List<String> fileNames(Path directory) throws IOException {
        return files.tree(directory, SkillPackage.ORDER_FILE).stream()
                .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .filter(path -> !path.getFileName().toString().equalsIgnoreCase(SkillPackage.ORDER_FILE))
                .map(path -> SandboxedFiles.relative(directory, path)).toList();
    }

    private JsonNode readOrder(Path directory) throws IOException {
        Path order = files.resolve(directory, SkillPackage.ORDER_FILE, 1024);
        if (!Files.exists(order, LinkOption.NOFOLLOW_LINKS)) {
            return json.createArrayNode();
        }
        try {
            return json.readTree(files.read(directory, SkillPackage.ORDER_FILE, 1024, SkillPackage.MAX_BYTES));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            LOG.warn("event=invalid_skill_order skill={}", directory.getFileName());
            return json.createArrayNode();
        }
    }

    private SkillPackage.Manifest manifest(Path directory, String name) throws IOException {
        return packages.manifest(SkillPackage.utf8(files.read(directory, "SKILL.md", 1024, SkillPackage.MAX_BYTES)), name);
    }

    private SkillMeta metadata(String name, String description, SkillMeta previous) {
        SkillMeta meta = new SkillMeta();
        meta.setSkillName(name);
        meta.setDescription(description);
        meta.setEnabled(previous == null || !Boolean.FALSE.equals(previous.getEnabled()));
        meta.setFileOrder(previous == null || previous.getFileOrder() == null
                ? json.createArrayNode() : previous.getFileOrder());
        meta.setUpdatedAt(OffsetDateTime.now());
        return meta;
    }

    private void persist(SkillMeta meta, SkillMeta previous) {
        int rows = previous == null ? mapper.insert(meta) : mapper.updateById(meta);
        if (rows != 1) {
            throw new BusinessException(409, "技能元数据已变更，请重试", null);
        }
    }

    private void replaceWithRollback(Path root, String path, byte[] bytes, Runnable database) throws IOException {
        Path target = files.resolve(root, path, 1024);
        byte[] previous = Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                ? files.read(root, path, 1024, SkillPackage.MAX_BYTES) : null;
        boolean[] changed = {false};
        try {
            transact(() -> {
                changed[0] = true;
                sandbox.writeAtomic(root, path, bytes);
                database.run();
                return null;
            });
        } catch (IOException | RuntimeException exception) {
            if (changed[0]) {
                try {
                    if (previous == null) {
                        sandbox.deleteFile(root, path);
                    } else {
                        sandbox.writeAtomic(root, path, previous);
                    }
                } catch (IOException | RuntimeException rollback) {
                    throw rollbackFailed(exception, rollback);
                }
            }
            throw exception;
        }
    }

    private <T> T transact(PerPathMutexQueue.IoOperation<T> operation) throws IOException {
        try {
            return transaction.execute(status -> {
                try {
                    return operation.run();
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
    }

    private <T> T locked(SkillOperation<T> operation) throws IOException {
        Path root = files.root("skills");
        // ACT: MVP 的安装包上限为 20MB，技能操作按根目录串行；规模增大后再拆成每技能锁及扫描读锁。
        return mutex.execute(root, () -> operation.run(root));
    }

    private BusinessException rollbackFailed(Exception original, Exception rollback) {
        LOG.error("event=skill_rollback_failed originalType={} rollbackType={}",
                original.getClass().getSimpleName(), rollback.getClass().getSimpleName());
        return new BusinessException(500, "技能操作失败且回滚未完成，备份已保留",
                Map.of("code", "SKILL_ROLLBACK_INCOMPLETE", "partial", true));
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(400, message, null);
    }

    @FunctionalInterface
    private interface SkillOperation<T> {
        T run(Path root) throws IOException;
    }

    public record SkillSummary(String name, String version, String displayName, String description, String author,
            String github, boolean enabled) {
    }
}
