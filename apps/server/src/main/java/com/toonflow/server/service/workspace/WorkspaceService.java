package com.toonflow.server.service.workspace;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.toonflow.server.common.BusinessException;
import com.toonflow.server.common.PathSandbox;
import com.toonflow.server.common.PerPathMutexQueue;
import com.toonflow.server.common.RevisionUtils;
import com.toonflow.server.entity.WorkspaceProject;
import com.toonflow.server.mapper.WorkspaceProjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaTypeFactory;
import org.springframework.stereotype.Service;

@Service
public class WorkspaceService {
    private static final Logger LOG = LoggerFactory.getLogger(WorkspaceService.class);
    private static final int MAX_SNAPSHOT_BYTES = 20 * 1024 * 1024;
    private static final int MAX_WRITE_BYTES = 100 * 1024 * 1024;
    private static final Pattern RANGE = Pattern.compile("bytes=(\\d*)-(\\d*)");
    private final PathSandbox sandbox;
    private final PerPathMutexQueue mutex;
    private final WorkspaceProjectMapper projectMapper;
    private final WorkspaceDirectoryPicker picker;
    private final WorkspaceImageImporter images;
    private final Path dataDirectory;

    public WorkspaceService(PathSandbox sandbox, PerPathMutexQueue mutex, WorkspaceProjectMapper projectMapper,
            WorkspaceDirectoryPicker picker, WorkspaceImageImporter images,
            @Value("${toonflow.data-dir}") String dataDirectory) {
        this.sandbox = sandbox;
        this.mutex = mutex;
        this.projectMapper = projectMapper;
        this.picker = picker;
        this.images = images;
        this.dataDirectory = Path.of(dataDirectory).toAbsolutePath().normalize();
    }

    public Map<String, String> check(String directory) throws IOException {
        Path root = workspaceRoot(directory);
        return mutex.execute(root, () -> {
            String name = root.getFileName().toString();
            name = name.substring(0, name.offsetByCodePoints(0, Math.min(64, name.codePointCount(0, name.length()))));
            OffsetDateTime openedAt = OffsetDateTime.now();
            if (updateProject(root, name, openedAt) == 0) {
                WorkspaceProject project = new WorkspaceProject();
                project.setDirectory(root.toString());
                project.setName(name);
                project.setLastOpenedAt(openedAt);
                project.setCreatedAt(openedAt);
                try {
                    projectMapper.insert(project);
                } catch (DuplicateKeyException exception) {
                    // 唯一约束处理其他服务进程同时首次打开同一目录的竞争。
                    if (updateProject(root, name, openedAt) == 0) {
                        throw exception;
                    }
                }
            }
            return Map.of("directory", root.toString());
        });
    }

    private int updateProject(Path directory, String name, OffsetDateTime openedAt) {
        return projectMapper.update(null, Wrappers.<WorkspaceProject>lambdaUpdate()
                .eq(WorkspaceProject::getDirectory, directory.toString())
                .set(WorkspaceProject::getName, name).set(WorkspaceProject::getLastOpenedAt, openedAt));
    }

    public WorkspaceListing listWorkspaces(String path) throws IOException {
        String relative = requirePath(path.trim(), true);
        Path offset = Path.of(relative.replace('\\', '/'));
        if (offset.isAbsolute() || relative.contains(":") || List.of(relative.split("[/\\\\]")).contains("..")) {
            throw invalid("必须使用不含 .. 的相对路径");
        }
        Path data = sandbox.initializeRoot(dataDirectory);
        Path root = sandbox.createDirectories(data, "workspaces");
        Path parent = checked(root, relative);
        List<DirectoryEntry> directories = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(parent)) {
            for (Path entry : stream) {
                if (entry.getFileName().toString().equalsIgnoreCase(".toonflow") || Files.isSymbolicLink(entry)) {
                    continue;
                }
                Path child = checked(root, relative(root, entry));
                if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    directories.add(new DirectoryEntry(child.getFileName().toString(), relative(root, child)));
                }
            }
        }
        directories.sort(Comparator.comparing(DirectoryEntry::name));
        return new WorkspaceListing(relative(root, parent), parent.toString(),
                parent.equals(root) ? null : relative(root, parent.getParent()), directories);
    }

    public WorkspaceDirectoryPicker.Selection selectDirectory() throws IOException {
        WorkspaceDirectoryPicker.Selection selection = picker.select();
        return selection.directory() == null ? selection
                : new WorkspaceDirectoryPicker.Selection(true, workspaceRoot(selection.directory()).toString());
    }

    public FileListing listFiles(String directory, String path) throws IOException {
        Path root = workspaceRoot(directory);
        Path parent = checked(root, requirePath(path, true));
        List<FileEntry> entries = new ArrayList<>();
        boolean hasAnyEntry = false;
        try (var stream = Files.newDirectoryStream(parent)) {
            for (Path entry : stream) {
                hasAnyEntry = true;
                if (entry.getFileName().toString().equalsIgnoreCase(".toonflow") || Files.isSymbolicLink(entry)) {
                    continue;
                }
                Path child = checked(root, relative(root, entry));
                BasicFileAttributes attributes = Files.readAttributes(child, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (attributes.isDirectory() || attributes.isRegularFile()) {
                    entries.add(new FileEntry(child.getFileName().toString(), relative(root, child),
                            attributes.isDirectory() ? "directory" : "file"));
                }
            }
        }
        entries.sort(Comparator.comparing(FileEntry::type).thenComparing(FileEntry::name));
        return new FileListing(root.toString(), parent.equals(root) && !hasAnyEntry, entries);
    }

    public Object readSnapshot(String directory, String path, Integer maxBytes) throws IOException {
        int limit = maxBytes == null ? MAX_SNAPSHOT_BYTES : maxBytes;
        if (limit < 1 || limit > MAX_SNAPSHOT_BYTES) {
            throw invalid("maxBytes 必须为 1..20971520 的整数");
        }
        Path root = workspaceRoot(directory);
        requirePath(path, false);
        return mutex.execute(root, () -> {
            Path file = regularFile(root, path);
            if (Files.size(file) > limit) {
                throw tooLarge();
            }
            try (InputStream stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                return WorkspaceTextCodec.snapshot(readLimited(stream, limit));
            }
        });
    }

    public void readBinary(String directory, String path, String range, HttpServletResponse response)
            throws IOException {
        Path root = workspaceRoot(directory);
        Path file = regularFile(root, requirePath(path, false));
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size();
            long start = 0;
            long end = size - 1;
            if (range != null) {
                Matcher match = RANGE.matcher(range);
                boolean valid = match.matches() && !(match.group(1).isEmpty() && match.group(2).isEmpty());
                try {
                    if (valid && match.group(1).isEmpty()) {
                        long suffix = Long.parseLong(match.group(2));
                        valid = suffix > 0;
                        start = Math.max(0, size - suffix);
                    } else if (valid) {
                        start = Long.parseLong(match.group(1));
                        end = match.group(2).isEmpty() ? end : Math.min(end, Long.parseLong(match.group(2)));
                    }
                } catch (NumberFormatException exception) {
                    valid = false;
                }
                if (!valid || start >= size || end < start) {
                    response.setStatus(416);
                    response.setHeader("Content-Range", "bytes */" + size);
                    response.setContentLengthLong(0);
                    return;
                }
                response.setStatus(206);
                response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + size);
            }
            response.setContentType(MediaTypeFactory.getMediaType(file.getFileName().toString())
                    .map(Object::toString).orElse("application/octet-stream"));
            response.setHeader("Accept-Ranges", "bytes");
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setContentLengthLong(end - start + 1);
            channel.position(start);
            long remaining = end - start + 1;
            ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
            while (remaining > 0) {
                buffer.clear().limit((int) Math.min(buffer.capacity(), remaining));
                int count = channel.read(buffer);
                if (count < 0) {
                    throw new IOException("读取过程中源文件被截断");
                }
                response.getOutputStream().write(buffer.array(), 0, count);
                remaining -= count;
            }
        }
    }

    public Object write(String directory, String path, InputStream stream, long contentLength, boolean exclusive,
            String expectedRevision, String encoding) throws IOException {
        requirePath(path, false);
        if (expectedRevision != null && (exclusive || !expectedRevision.matches("[a-f0-9]{64}"))) {
            throw invalid("expectedRevision 必须为 SHA-256 小写摘要，且不能与 exclusive=true 同传");
        }
        if (encoding != null) {
            WorkspaceTextCodec.charset(encoding);
        }
        Path root = workspaceRoot(directory);
        if (contentLength > MAX_WRITE_BYTES) {
            throw tooLarge();
        }
        byte[] bytes = WorkspaceTextCodec.encode(readLimited(stream, MAX_WRITE_BYTES), encoding);
        return mutex.execute(root, () -> {
            Path target = checked(root, path);
            protectRoot(root, target);
            if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                if (expectedRevision != null) {
                    RevisionUtils.requireMatch(expectedRevision, null);
                }
                if (exclusive) {
                    throw new FileAlreadyExistsException(target.toString());
                }
                throw invalid("目标是目录");
            }
            if (expectedRevision != null) {
                // 公共封装在同路径队列中比较摘要并执行原子替换。
                return Map.of("revision", sandbox.writeIfRevision(root, path, bytes, expectedRevision));
            }
            if (exclusive) {
                writeExclusive(root, path, bytes);
            } else {
                sandbox.writeAtomic(root, path, bytes);
            }
            return null;
        });
    }

    public void mkdir(String directory, String path) throws IOException {
        Path root = workspaceRoot(directory);
        requirePath(path, false);
        mutate(root, () -> {
            Files.createDirectory(checked(root, path));
            return null;
        });
    }

    public void rename(String directory, String path, String target) throws IOException {
        Path root = workspaceRoot(directory);
        requirePath(path, false);
        requirePath(target, false);
        mutate(root, () -> {
            Path source = checked(root, path);
            Path destination = checked(root, target);
            protectRoot(root, source);
            protectRoot(root, destination);
            requireTransfer(source, destination);
            validateTree(root, source);
            // 不使用 ATOMIC_MOVE：其在部分平台会覆盖现存目标，违反非覆盖契约。
            Files.move(source, destination);
            return null;
        });
    }

    public void copy(String directory, String path, String target) throws IOException {
        Path root = workspaceRoot(directory);
        requirePath(path, false);
        requirePath(target, false);
        mutate(root, () -> {
            rejectCopyLinks(root, path);
            Path source = checked(root, path);
            Path destination = checked(root, target);
            protectRoot(root, destination);
            requireTransfer(source, destination);
            validateTree(root, source);
            List<CreatedEntry> created = new ArrayList<>();
            try {
                Files.walkFileTree(source, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path entry, BasicFileAttributes attributes)
                            throws IOException {
                        copyEntry(entry, attributes);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path entry, BasicFileAttributes attributes) throws IOException {
                        copyEntry(entry, attributes);
                        return FileVisitResult.CONTINUE;
                    }

                    private void copyEntry(Path entry, BasicFileAttributes attributes) throws IOException {
                        validateEntry(root, entry, attributes);
                        Path output = checked(root, relative(root, destination.resolve(source.relativize(entry))));
                        if (attributes.isDirectory()) {
                            Files.createDirectory(output);
                            created.add(new CreatedEntry(output, fileKey(output)));
                        } else {
                            // 先独占创建并登记，以便 copy 中途 I/O 失败也能回滚半成品。
                            try (var channel = FileChannel.open(output, StandardOpenOption.CREATE_NEW,
                                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                                created.add(new CreatedEntry(output, fileKey(output)));
                                try (InputStream input = Files.newInputStream(checked(root, relative(root, entry)),
                                        LinkOption.NOFOLLOW_LINKS)) {
                                    byte[] buffer = new byte[64 * 1024];
                                    int count;
                                    while ((count = input.read(buffer)) != -1) {
                                        ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, count);
                                        while (bytes.hasRemaining()) {
                                            channel.write(bytes);
                                        }
                                    }
                                }
                            }
                        }
                    }
                });
            } catch (IOException | RuntimeException exception) {
                boolean incomplete = false;
                for (int index = created.size() - 1; index >= 0; index--) {
                    CreatedEntry entry = created.get(index);
                    try {
                        Path current = checked(root, relative(root, entry.path()));
                        if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                            if (!Objects.equals(entry.fileKey(), fileKey(current))) {
                                throw new IOException("复制目标已被替换");
                            }
                            Files.delete(current);
                        }
                    } catch (IOException | RuntimeException cleanupException) {
                        incomplete = true;
                        LOG.error("event=workspace_copy_cleanup_failed target={} errorType={}",
                                relative(root, entry.path()), cleanupException.getClass().getSimpleName());
                    }
                }
                if (incomplete) {
                    throw new BusinessException(500, "复制中断，目标内容未完全清理", Map.of(
                            "code", "ECOPYINCOMPLETE", "partial", true, "target", relative(root, destination)));
                }
                throw exception;
            }
            return null;
        });
    }

    public void remove(String directory, String path, boolean recursive) throws IOException {
        Path root = workspaceRoot(directory);
        requirePath(path, true);
        mutate(root, () -> {
            Path target = checked(root, path);
            protectRoot(root, target);
            if (!recursive || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                Files.delete(target);
                return null;
            }
            validateTree(root, target);
            Files.walkFileTree(target, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path entry, BasicFileAttributes attributes)
                        throws IOException {
                    validateEntry(root, entry, attributes);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path entry, BasicFileAttributes attributes) throws IOException {
                    validateEntry(root, entry, attributes);
                    Files.delete(checked(root, relative(root, entry)));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path entry, IOException exception) throws IOException {
                    if (exception != null) {
                        throw exception;
                    }
                    Files.delete(checked(root, relative(root, entry)));
                    return FileVisitResult.CONTINUE;
                }
            });
            return null;
        });
    }

    public Map<String, String> importImage(String directory, String url) throws IOException {
        Path root = workspaceRoot(directory);
        WorkspaceImageImporter.Image image = images.load(url);
        return mutate(root, () -> {
            checked(root, "assets/chatImages");
            sandbox.createDirectories(root, "assets/chatImages");
            String path = "assets/chatImages/image-" + System.currentTimeMillis() + "-"
                    + UUID.randomUUID().toString().replace("-", "").substring(0, 8) + "." + image.extension();
            writeExclusive(root, path, image.bytes());
            return Map.of("path", path, "mimeType", image.mimeType());
        });
    }

    private Path workspaceRoot(String directory) throws IOException {
        if (directory == null || directory.trim().isEmpty() || directory.trim().length() > 4096) {
            throw invalid("工作区目录长度必须为 1..4096");
        }
        Path requested = Path.of(directory.trim());
        if (!requested.isAbsolute()) {
            throw invalid("工作区必须为绝对路径");
        }
        rejectSystemPath(requested);
        Path root = sandbox.root(requested);
        rejectSystemPath(root);
        if (root.getParent() == null || !Files.isReadable(root) || !Files.isExecutable(root)) {
            throw forbidden();
        }
        // 配置和密钥不能通过将 data 本身作为工作区读取；托管工作区是唯一例外。
        Path realData = Files.exists(dataDirectory) ? dataDirectory.toRealPath() : dataDirectory;
        if (root.startsWith(realData) && !root.startsWith(realData.resolve("workspaces"))) {
            throw forbidden();
        }
        return root;
    }

    private Path checked(Path root, String relative) throws IOException {
        Path target = sandbox.resolve(root, relative);
        rejectSystemPath(target);
        Path realData = Files.exists(dataDirectory) ? dataDirectory.toRealPath() : dataDirectory;
        if (target.startsWith(realData) && !target.startsWith(realData.resolve("workspaces"))) {
            throw forbidden();
        }
        return target;
    }

    private void rejectSystemPath(Path path) throws IOException {
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        for (Path segment : path) {
            String name = segment.toString().toLowerCase(Locale.ROOT);
            if (List.of(".toonflow", ".ssh", ".aws", ".codex", ".git", "$recycle.bin",
                    "system volume information").contains(name)
                    || (windows && name.matches("(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?"))) {
                throw forbidden();
            }
        }
        Path normalized = path.toAbsolutePath().normalize();
        Path realData = Files.exists(dataDirectory) ? dataDirectory.toRealPath() : dataDirectory;
        // 管理员显式配置的托管工作区可部署在 ProgramData 或 /var/lib 下。
        if (normalized.startsWith(dataDirectory.resolve("workspaces"))
                || normalized.startsWith(realData.resolve("workspaces"))) {
            return;
        }
        if (windows) {
            for (String variable : List.of("SystemRoot", "ProgramFiles", "ProgramFiles(x86)", "ProgramData")) {
                String value = System.getenv(variable);
                if (value != null && normalized.startsWith(Path.of(value).toAbsolutePath().normalize())) {
                    throw forbidden();
                }
            }
        } else {
            for (String system : List.of("/etc", "/proc", "/sys", "/dev", "/bin", "/sbin", "/usr",
                    "/boot", "/var/lib", "/var/log", "/var/run", "/var/spool", "/lib", "/lib64", "/run")) {
                if (normalized.startsWith(Path.of(system))) {
                    throw forbidden();
                }
            }
        }
    }

    private Path regularFile(Path root, String path) throws IOException {
        Path file = checked(root, path);
        if (!Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isRegularFile()) {
            throw invalid("目标不是普通文件");
        }
        return file;
    }

    private void validateTree(Path root, Path source) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path entry, BasicFileAttributes attributes) throws IOException {
                validateEntry(root, entry, attributes);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path entry, BasicFileAttributes attributes) throws IOException {
                validateEntry(root, entry, attributes);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void validateEntry(Path root, Path entry, BasicFileAttributes attributes) throws IOException {
        if (attributes.isSymbolicLink()) {
            throw invalid("不能复制或递归操作包含符号链接的目录");
        }
        if (!attributes.isDirectory() && !attributes.isRegularFile()) {
            throw invalid("只能操作普通文件或目录");
        }
        checked(root, relative(root, entry));
    }

    private void rejectCopyLinks(Path root, String relative) {
        Path current = root;
        for (Path segment : Path.of(relative.replace('\\', '/'))) {
            // 越界与受保护路径仍由 checked 统一拒绝。
            if (segment.toString().equals("..")) {
                throw forbidden();
            }
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw invalid("复制不允许符号链接");
            }
        }
    }

    private void requireTransfer(Path source, Path target) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(source, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (source.equals(target) || (attributes.isDirectory() && target.startsWith(source))) {
            throw invalid("不能移动或复制到自身或子目录");
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(target.toString());
        }
    }

    private void writeExclusive(Path root, String relative, byte[] bytes) throws IOException {
        Path target = checked(root, relative);
        protectRoot(root, target);
        mutex.execute(target, () -> {
            Path temporary = Files.createTempFile(target.getParent(), ".workspace-", ".tmp");
            try {
                sandbox.writeAtomic(root, relative(root, temporary), bytes);
                // 硬链接一次性发布完整临时文件，目标存在即失败；ATOMIC_MOVE 无法保证不覆盖。
                Files.createLink(checked(root, relative), checked(root, relative(root, temporary)));
            } finally {
                Files.deleteIfExists(checked(root, relative(root, temporary)));
            }
            return null;
        });
    }

    private <T> T mutate(Path root, PerPathMutexQueue.IoOperation<T> operation) throws IOException {
        // ACT: 同工作区变更串行，防止递归操作与子路径保存交错；文件量增大后可换成祖先路径锁。
        return mutex.execute(root, operation);
    }

    static byte[] readLimited(InputStream stream, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 64 * 1024));
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = stream.read(buffer, 0, Math.min(buffer.length, limit - output.size() + 1))) != -1) {
            if ((long) output.size() + count > limit) {
                throw tooLarge();
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private Object fileKey(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
    }

    private String requirePath(String path, boolean allowEmpty) {
        if (path == null || path.length() > 4096 || (!allowEmpty && path.isBlank())) {
            throw invalid("文件路径不能为空且长度不能超过 4096");
        }
        return path;
    }

    private String relative(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private void protectRoot(Path root, Path target) {
        if (root.equals(target)) {
            throw forbidden();
        }
    }

    private static BusinessException tooLarge() {
        return new BusinessException(413, "文件超过体积上限", null);
    }

    private BusinessException invalid(String message) {
        return new BusinessException(400, message, null);
    }

    private BusinessException forbidden() {
        return new BusinessException(403, "路径越界或访问受保护目录", Map.of("code", "EACCES"));
    }

    public record WorkspaceListing(String path, String absolutePath, String parent, List<DirectoryEntry> directories) {
    }

    public record DirectoryEntry(String name, String path) {
    }

    public record FileListing(String directory, boolean empty, List<FileEntry> entries) {
    }

    public record FileEntry(String name, String path, String type) {
    }

    private record CreatedEntry(Path path, Object fileKey) {
    }
}
