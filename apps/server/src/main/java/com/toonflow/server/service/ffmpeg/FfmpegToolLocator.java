package com.toonflow.server.service.ffmpeg;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * 定位 FFmpeg / FFprobe 可执行文件并读取版本。
 * bundled 只接受下载目录内的程序，system 只查 PATH，auto 优先下载版再回退系统安装版。
 */
@Component
public class FfmpegToolLocator {
    private static final List<String> TOOLS = List.of("ffmpeg", "ffprobe");
    private static final long VERSION_TIMEOUT_SECONDS = 15;
    private final FfmpegCatalog catalog;

    public FfmpegToolLocator(FfmpegCatalog catalog) {
        this.catalog = catalog;
    }

    /** 已就绪的二进制绝对路径，供执行器启动子进程。 */
    public record Tools(Path ffmpeg, Path ffprobe) {
    }

    public Map<String, FfmpegModels.ToolBinary> statuses(String mode) {
        Map<String, FfmpegModels.ToolBinary> statuses = new LinkedHashMap<>();
        for (String tool : TOOLS) {
            statuses.put(tool, status(tool, mode));
        }
        return statuses;
    }

    /** FFmpeg 与 FFprobe 必须同时可用，任一缺失都视为未就绪。 */
    public Tools ready(String mode) {
        Map<String, FfmpegModels.ToolBinary> statuses = statuses(mode);
        FfmpegModels.ToolBinary ffmpeg = statuses.get("ffmpeg");
        FfmpegModels.ToolBinary ffprobe = statuses.get("ffprobe");
        if (ffmpeg.path() == null || ffmpeg.error() != null || ffprobe.path() == null || ffprobe.error() != null) {
            return null;
        }
        return new Tools(Path.of(ffmpeg.path()), Path.of(ffprobe.path()));
    }

    private FfmpegModels.ToolBinary status(String tool, String mode) {
        String executable = FfmpegCatalog.executableName(tool);
        Path bundled = catalog.installDirectory().resolve(executable);
        boolean hasBundled = !"system".equals(mode) && Files.isRegularFile(bundled) && Files.isExecutable(bundled);
        Path path = hasBundled ? bundled : "bundled".equals(mode) ? null : which(executable);
        if (path == null) {
            return new FfmpegModels.ToolBinary(null, null, null, "未找到可用程序");
        }
        String origin = hasBundled ? "bundled" : "system";
        try {
            return new FfmpegModels.ToolBinary(path.toString(), origin, readVersion(path, tool), null);
        } catch (IOException exception) {
            return new FfmpegModels.ToolBinary(path.toString(), origin, null, exception.getMessage());
        }
    }

    /** 读取 `<tool> -version` 首行；输出必须自称对应工具版本，防止误用同名脚本。 */
    private String readVersion(Path path, String tool) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(path.toString(), "-version");
        builder.redirectErrorStream(false);
        builder.environment().remove("FFREPORT");
        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (var stream = process.getInputStream()) {
                output.append(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // 进程被强杀时读取会失败，版本判定交给退出码处理。
            }
        });
        try {
            if (!process.waitFor(VERSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException(tool + " 版本检测超时，请检查平台兼容性或重新下载");
            }
            reader.join(2000);
            if (process.exitValue() != 0) {
                throw new IOException(tool + " 无法运行，请检查平台兼容性或重新下载");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException(tool + " 版本检测被中断");
        }
        String version = output.toString().lines().findFirst().orElse("");
        if (!version.startsWith(tool + " version ")) {
            throw new IOException(tool + " 无法运行，请检查平台兼容性或重新下载");
        }
        return version;
    }

    private Path which(String executable) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return null;
        }
        String suffix = FfmpegCatalog.isWindows() ? ".exe" : "";
        for (String directory : path.split(File.pathSeparator)) {
            if (directory.isBlank()) {
                continue;
            }
            for (String name : suffix.isEmpty() ? List.of(executable) : List.of(executable, executable + suffix)) {
                Path candidate = Path.of(directory, name);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }
}
