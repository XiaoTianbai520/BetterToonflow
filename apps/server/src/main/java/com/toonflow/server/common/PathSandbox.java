package com.toonflow.server.common;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class PathSandbox {
    private static final Logger LOG = LoggerFactory.getLogger(PathSandbox.class);
    private final PerPathMutexQueue mutex;

    public PathSandbox(PerPathMutexQueue mutex) {
        this.mutex = mutex;
    }

    /** 仅用于服务端配置的根目录初始化，不接受客户端传入的目录。 */
    public Path initializeRoot(Path configuredDirectory) throws IOException {
        Path normalized = configuredDirectory.toAbsolutePath().normalize();
        rejectProtected(normalized);
        Files.createDirectories(normalized);
        return root(normalized);
    }

    /** root 必须由上层从已授权工作区或服务端配置取得，不能直接信任请求参数。 */
    public Path root(Path directory) throws IOException {
        rejectProtected(directory);
        Path real = directory.toRealPath();
        rejectProtected(real);
        if (!Files.isDirectory(real)) {
            throw new java.nio.file.NotDirectoryException(real.toString());
        }
        return real;
    }

    /** 对新文件逐段验证已存在的祖先；不存在的尾段仅允许在授权根内创建。 */
    public Path resolve(Path directory, String relativePath) throws IOException {
        Path authorizedRoot = root(directory);
        Path relative = Path.of(relativePath.replace('\\', '/'));
        if (relative.isAbsolute()) {
            throw forbidden();
        }
        rejectProtected(relative);
        Path current = authorizedRoot;
        for (Path segment : relative) {
            String name = segment.toString();
            // Windows 禁止 ADS、尾随点/空格等可别名访问，避免绕过受保护目录与路径锁。
            if (name.contains(":") || name.endsWith(".") || name.endsWith(" ")) {
                if (!name.equals(".")) {
                    throw forbidden();
                }
            }
            current = current.resolve(segment).normalize();
            if (!current.startsWith(authorizedRoot)) {
                throw forbidden();
            }
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current)) {
                    throw forbidden();
                }
                current = current.toRealPath();
                if (!current.startsWith(authorizedRoot)) {
                    throw forbidden();
                }
                rejectProtected(current);
            }
        }
        return current;
    }

    public Path createDirectories(Path directory, String relativePath) throws IOException {
        Path authorizedRoot = root(directory);
        Path target = resolve(authorizedRoot, relativePath);
        Path current = authorizedRoot;
        for (Path segment : authorizedRoot.relativize(target)) {
            current = resolve(authorizedRoot, authorizedRoot.relativize(current.resolve(segment)).toString());
            try {
                Files.createDirectory(current);
            } catch (java.nio.file.FileAlreadyExistsException exception) {
                if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw exception;
                }
            }
            resolve(authorizedRoot, authorizedRoot.relativize(current).toString());
        }
        return current;
    }

    public byte[] read(Path directory, String relativePath) throws IOException {
        return Files.readAllBytes(resolve(directory, relativePath));
    }

    public List<String> listFiles(Path directory, String relativePath) throws IOException {
        Path parent = resolve(directory, relativePath);
        List<String> names = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(parent)) {
            for (Path entry : stream) {
                Path checked = resolve(directory, root(directory).relativize(entry).toString());
                if (Files.isRegularFile(checked, LinkOption.NOFOLLOW_LINKS)) {
                    names.add(checked.getFileName().toString());
                }
            }
        }
        return names;
    }

    public boolean synchronizeFile(Path directory, String relativePath, byte[] content) throws IOException {
        Path target = resolve(directory, relativePath);
        return mutex.execute(target, () -> {
            Path checked = resolve(directory, relativePath);
            if (Files.exists(checked, LinkOption.NOFOLLOW_LINKS)
                    && RevisionUtils.sha256(Files.readAllBytes(checked)).equals(RevisionUtils.sha256(content))) {
                return false;
            }
            replace(directory, relativePath, content);
            return true;
        });
    }

    public void writeAtomic(Path directory, String relativePath, byte[] content) throws IOException {
        Path target = resolve(directory, relativePath);
        mutex.execute(target, () -> {
            replace(directory, relativePath, content);
            return null;
        });
    }

    public String writeIfRevision(Path directory, String relativePath, byte[] content, String expectedRevision)
            throws IOException {
        Path target = resolve(directory, relativePath);
        return mutex.execute(target, () -> {
            Path current = resolve(directory, relativePath);
            String revision = Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    ? RevisionUtils.sha256(Files.readAllBytes(current)) : null;
            RevisionUtils.requireMatch(expectedRevision, revision);
            replace(directory, relativePath, content);
            return RevisionUtils.sha256(content);
        });
    }

    public void deleteFile(Path directory, String relativePath) throws IOException {
        Path target = resolve(directory, relativePath);
        mutex.execute(target, () -> {
            Path checked = resolve(directory, relativePath);
            if (checked.equals(root(directory))) {
                throw forbidden();
            }
            if (Files.isDirectory(checked, LinkOption.NOFOLLOW_LINKS)) {
                throw new BusinessException(400, "此操作只允许删除文件", null);
            }
            Files.deleteIfExists(checked);
            return null;
        });
    }

    private void replace(Path directory, String relativePath, byte[] content) throws IOException {
        Path target = resolve(directory, relativePath);
        if (target.equals(root(directory))) {
            throw forbidden();
        }
        Path temporary = Files.getFileStore(target.getParent()).supportsFileAttributeView("posix")
                ? Files.createTempFile(target.getParent(), ".snapshot-", ".tmp",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                : Files.createTempFile(target.getParent(), ".snapshot-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            target = resolve(directory, relativePath);
            // 不降级为非原子 move；不支持时由调用方进入失败恢复流程。
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException exception) {
                LOG.error("event=atomic_temp_cleanup_failed errorType={}", exception.getClass().getSimpleName());
                throw exception;
            }
        }
    }

    private void rejectProtected(Path path) {
        for (Path segment : path) {
            String name = segment.toString();
            if (name.equals("..") || name.equalsIgnoreCase(".toonflow")) {
                throw forbidden();
            }
        }
    }

    private BusinessException forbidden() {
        return new BusinessException(403, "路径越界或访问受保护目录", Map.of("code", "EACCES"));
    }
}
