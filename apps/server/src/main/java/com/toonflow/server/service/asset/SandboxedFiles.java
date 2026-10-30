package com.toonflow.server.service.asset;

import com.toonflow.server.common.BusinessException;
import com.toonflow.server.common.PathSandbox;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 素材与技能共用的有界读取、排他创建和沙箱遍历。 */
@Component
public class SandboxedFiles {
    private final PathSandbox sandbox;
    private final Path dataDirectory;

    public SandboxedFiles(PathSandbox sandbox, @Value("${toonflow.data-dir}") String dataDirectory) {
        this.sandbox = sandbox;
        this.dataDirectory = Path.of(dataDirectory);
    }

    public Path root(String name) throws IOException {
        Path data = sandbox.initializeRoot(dataDirectory);
        return sandbox.createDirectories(data, name);
    }

    public Path resolve(Path root, String path, int maxLength) throws IOException {
        if (path == null || path.isEmpty() || path.length() > maxLength || path.indexOf('\0') >= 0) {
            throw new BusinessException(400, "文件路径非法", null);
        }
        Path target = sandbox.resolve(root, path);
        if (target.equals(root)) {
            throw new BusinessException(403, "禁止操作根目录", Map.of("code", "EPERM"));
        }
        // Windows 设备名和通配符也不能成为跨平台技能包中的真实文件名。
        for (String part : path.replace('\\', '/').split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.matches(".*[<>\"|?*\\x00-\\x1f].*")
                    || part.matches("(?i)(con|prn|aux|nul|conin\\$|conout\\$|com[1-9¹²³]|lpt[1-9¹²³])(\\..*)?")) {
                throw new BusinessException(400, "文件路径非法", null);
            }
        }
        return target;
    }

    public Path regularFile(Path root, String path, int maxLength) throws IOException {
        Path target = resolve(root, path, maxLength);
        var attributes = Files.readAttributes(target, java.nio.file.attribute.BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) {
            throw new BusinessException(400, "目标必须是普通文件", null);
        }
        return target;
    }

    public List<Path> children(Path root, Path directory) throws IOException {
        List<Path> children = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(directory)) {
            for (Path child : stream) {
                children.add(resolve(root, relative(root, child), 4096));
            }
        }
        children.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return children;
    }

    public List<Path> tree(Path root, String excludedName) throws IOException {
        List<Path> entries = new ArrayList<>();
        List<Path> directories = new ArrayList<>(List.of(root));
        for (int index = 0; index < directories.size(); index++) {
            for (Path child : children(root, directories.get(index))) {
                if (child.getFileName().toString().equalsIgnoreCase(excludedName)) {
                    continue;
                }
                if (entries.size() >= 2000) {
                    throw new BusinessException(413, "技能包条目超过 2000 个", null);
                }
                entries.add(child);
                if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    directories.add(child);
                } else {
                    regularFile(root, relative(root, child), 4096);
                }
            }
        }
        return entries;
    }

    public void removeTree(Path root, Path target) throws IOException {
        Path checked = resolve(root, relative(root, target), 4096);
        // 删除前验证整棵树，防止遇到非法条目时已删掉部分正常文件。
        List<Path> entries = new ArrayList<>();
        List<Path> pending = new ArrayList<>(List.of(checked));
        for (int index = 0; index < pending.size(); index++) {
            Path current = pending.get(index);
            entries.add(current);
            if (Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                pending.addAll(children(root, current));
            }
        }
        for (int index = entries.size() - 1; index >= 0; index--) {
            Files.delete(resolve(root, relative(root, entries.get(index)), 4096));
        }
    }

    public byte[] read(Path root, String path, int maxLength, int limit) throws IOException {
        Path target = regularFile(root, path, maxLength);
        if (Files.size(target) > limit) {
            throw new BusinessException(413, "文件超过体积上限", null);
        }
        try (InputStream input = Files.newInputStream(target)) {
            return readLimited(input, limit);
        }
    }

    public static byte[] readLimited(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer, 0, Math.min(buffer.length, limit - output.size() + 1))) != -1) {
            if ((long) output.size() + count > limit) {
                throw new BusinessException(413, "文件超过体积上限", null);
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    public void create(Path root, String path, byte[] content) throws IOException {
        Path target = resolve(root, path, 4096);
        boolean created = false;
        try {
            try (var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                created = true;
                output.write(content);
            }
        } catch (IOException | RuntimeException exception) {
            if (created) {
                try {
                    Files.delete(target);
                } catch (IOException cleanup) {
                    exception.addSuppressed(cleanup);
                }
            }
            throw exception;
        }
    }

    public static String relative(Path root, Path target) {
        return root.relativize(target).toString().replace('\\', '/');
    }
}
