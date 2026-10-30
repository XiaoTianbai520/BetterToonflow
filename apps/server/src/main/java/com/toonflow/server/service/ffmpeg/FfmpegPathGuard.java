package com.toonflow.server.service.ffmpeg;

import com.fasterxml.jackson.databind.JsonNode;
import com.toonflow.server.common.BusinessException;
import com.toonflow.server.common.PathSandbox;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * FFmpeg 命令链的文件路径守卫：只允许访问已授权工作区内的普通路径。
 * 相对路径按工作区解析，绝对路径必须落在工作区内，符号链接与受保护目录由 PathSandbox 统一拦截。
 */
@Component
public class FfmpegPathGuard {
    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x1f]");
    private static final Pattern SCHEME = Pattern.compile("^[a-z][a-z\\d+.-]*:", Pattern.CASE_INSENSITIVE);
    private static final Pattern DRIVE = Pattern.compile("^[a-z]:[\\\\/]", Pattern.CASE_INSENSITIVE);
    private final PathSandbox sandbox;

    public FfmpegPathGuard(PathSandbox sandbox) {
        this.sandbox = sandbox;
    }

    /** 输入/输出文件：允许不存在（输出文件），但父级真实路径必须在工作区内。 */
    public Path file(Path root, String value) throws IOException {
        Path resolved = resolve(root, value);
        if (resolved.equals(root)) {
            throw new BusinessException(400, "FFmpeg 文件路径不能是工作区根目录", null);
        }
        return resolved;
    }

    /** 目录类参数（cwd、截图目录）：必须已存在且为目录。 */
    public Path directory(Path root, String value) throws IOException {
        Path resolved = resolve(root, value);
        if (!Files.isDirectory(resolved, LinkOption.NOFOLLOW_LINKS)) {
            throw new BusinessException(400, "FFmpeg 目录不存在：" + value, null);
        }
        return resolved;
    }

    /** 允许指向工作区根目录本身的路径（例如截图输出目录 "."）。 */
    public Path path(Path root, String value) throws IOException {
        return resolve(root, value);
    }

    private Path resolve(Path root, String value) throws IOException {
        if (value == null || value.isEmpty() || CONTROL.matcher(value).find()) {
            throw new BusinessException(400, "FFmpeg 文件路径无效", null);
        }
        if (SCHEME.matcher(value).find() && !DRIVE.matcher(value).find()) {
            throw new BusinessException(400, "FFmpeg 文件路径无效，不能使用协议或设备路径", null);
        }
        Path requested;
        try {
            requested = Path.of(value.replace('\\', '/')).normalize();
        } catch (InvalidPathException exception) {
            throw new BusinessException(400, "FFmpeg 文件路径无效", null);
        }
        if (requested.isAbsolute()) {
            Path absolute = requested.toAbsolutePath().normalize();
            if (!absolute.startsWith(root)) {
                throw forbidden();
            }
            requested = root.relativize(absolute);
        }
        String relative = requested.toString();
        if (relative.isEmpty()) {
            return root;
        }
        if (relative.startsWith("..")) {
            throw forbidden();
        }
        return sandbox.resolve(root, relative);
    }

    private BusinessException forbidden() {
        return new BusinessException(403, "FFmpeg 只能操作当前工作区内的文件", Map.of("code", "EACCES"));
    }
}
