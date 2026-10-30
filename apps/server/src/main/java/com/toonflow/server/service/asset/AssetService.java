package com.toonflow.server.service.asset;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.toonflow.server.common.BusinessException;
import com.toonflow.server.common.PathSandbox;
import com.toonflow.server.common.PerPathMutexQueue;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AssetService {
    private static final int MAX_BYTES = 100 * 1024 * 1024;
    private final SandboxedFiles files;
    private final PathSandbox sandbox;
    private final PerPathMutexQueue mutex;

    public AssetService(SandboxedFiles files, PathSandbox sandbox, PerPathMutexQueue mutex) {
        this.files = files;
        this.sandbox = sandbox;
        this.mutex = mutex;
    }

    public Map<String, List<AssetEntry>> list() throws IOException {
        Path root = files.root("assets");
        return mutex.execute(root, () -> Map.of("entries", listDirectory(root, root)));
    }

    private List<AssetEntry> listDirectory(Path root, Path directory) throws IOException {
        List<AssetEntry> entries = new ArrayList<>();
        for (Path child : files.children(root, directory)) {
            boolean isDirectory = Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS);
            entries.add(new AssetEntry(child.getFileName().toString(), SandboxedFiles.relative(root, child),
                    isDirectory ? "directory" : "file", isDirectory ? listDirectory(root, child) : null));
        }
        return entries;
    }

    public Path read(String path) throws IOException {
        return files.regularFile(files.root("assets"), path, 4096);
    }

    public void save(String path, InputStream input, long contentLength) throws IOException {
        Path root = files.root("assets");
        files.resolve(root, path, 4096);
        if (contentLength > MAX_BYTES) {
            throw new BusinessException(413, "资产不能超过 100MB", null);
        }
        byte[] content = SandboxedFiles.readLimited(input, MAX_BYTES);
        if (content.length == 0) {
            throw new BusinessException(400, "文件内容不能为空", null);
        }
        mutex.execute(root, () -> {
            Path target = files.resolve(root, path, 4096);
            if (!target.getParent().equals(root)) {
                sandbox.createDirectories(root, SandboxedFiles.relative(root, target.getParent()));
            }
            files.create(root, path, content);
            return null;
        });
    }

    public void mkdir(String path) throws IOException {
        Path root = files.root("assets");
        mutex.execute(root, () -> {
            Files.createDirectory(files.resolve(root, path, 4096));
            return null;
        });
    }

    public void rename(String path, String target) throws IOException {
        Path root = files.root("assets");
        mutex.execute(root, () -> {
            Path source = files.resolve(root, path, 4096);
            Path destination = files.resolve(root, target, 4096);
            if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS) && destination.startsWith(source)) {
                throw new BusinessException(400, "不能移动目录至自身或子目录", null);
            }
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                throw new java.nio.file.FileAlreadyExistsException(destination.toString());
            }
            Files.move(source, destination);
            return null;
        });
    }

    public void remove(String path) throws IOException {
        Path root = files.root("assets");
        mutex.execute(root, () -> {
            files.removeTree(root, files.resolve(root, path, 4096));
            return null;
        });
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AssetEntry(String name, String path, String type, List<AssetEntry> children) {
    }
}
