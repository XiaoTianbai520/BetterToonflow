package com.toonflow.server.service.ffmpeg;

import com.toonflow.server.common.BusinessException;
import com.toonflow.server.entity.SysSetting;
import com.toonflow.server.mapper.SysSettingMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * FFmpeg 二进制下载、校验、安装与状态查询。
 * 单进程模型下同一时刻只允许一个下载任务；安装阶段不响应取消，避免留下半套程序。
 */
@Service
public class FfmpegInstallService {
    private static final Logger LOG = LoggerFactory.getLogger(FfmpegInstallService.class);
    private static final long MAX_COMPRESSED_BYTES = 128L * 1024 * 1024;
    private static final long MAX_EXTRACTED_BYTES = 256L * 1024 * 1024;
    private static final long REPORT_STEP_BYTES = 256L * 1024;
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(15);
    private static final List<String> TOOLS = List.of("ffmpeg", "ffprobe");

    private final FfmpegCatalog catalog;
    private final FfmpegToolLocator locator;
    private final SysSettingMapper settingMapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile FfmpegModels.DownloadState state = FfmpegModels.DownloadState.idle();
    private volatile boolean cancelRequested;
    private volatile Thread worker;
    private volatile InputStream activeStream;

    public FfmpegInstallService(FfmpegCatalog catalog, FfmpegToolLocator locator, SysSettingMapper settingMapper) {
        this.catalog = catalog;
        this.locator = locator;
        this.settingMapper = settingMapper;
    }

    public FfmpegModels.Status status() {
        FfmpegModels.Config config = config();
        FfmpegCatalog.Build build = catalog.build();
        return new FfmpegModels.Status(
                catalog.platform(),
                catalog.arch(),
                catalog.target(),
                build != null,
                catalog.installDirectory().toString(),
                build == null ? "" : build.version(),
                catalog.sources().stream().map(FfmpegCatalog.DownloadSource::toPublic).toList(),
                config,
                locator.statuses(config.mode()),
                state);
    }

    public FfmpegModels.DownloadState progress() {
        return state;
    }

    /** 运行方式与下载线路来自 sys_setting 的 ffmpeg 记录；历史值 download 归一为 bundled。 */
    public FfmpegModels.Config config() {
        SysSetting setting = settingMapper.selectById("ffmpeg");
        String mode = "auto";
        String source = "npmmirror";
        if (setting != null && setting.getSettingValue() != null && setting.getSettingValue().isObject()) {
            var value = setting.getSettingValue();
            mode = switch (value.path("mode").asText("")) {
                case "system" -> "system";
                case "bundled", "download" -> "bundled";
                default -> "auto";
            };
            String configured = value.path("source").asText("");
            if (catalog.source(configured) != null) {
                source = configured;
            }
        }
        return new FfmpegModels.Config(mode, source);
    }

    public synchronized FfmpegModels.DownloadState download(String sourceId) {
        FfmpegCatalog.DownloadSource source = catalog.source(sourceId);
        if (source == null || !source.available()) {
            throw new BusinessException(400, "此下载源暂不可用，请选择其他下载源", null);
        }
        FfmpegCatalog.Build build = catalog.build();
        if (build == null) {
            throw new BusinessException(400, "暂不支持自动下载 " + catalog.target() + " 版本", null);
        }
        if (!running.compareAndSet(false, true)) {
            throw new BusinessException(409, "正在下载，请等待完成或取消后再试", null);
        }
        cancelRequested = false;
        state = new FfmpegModels.DownloadState("downloading", null, 0L, null, null);
        worker = Thread.ofVirtual().name("ffmpeg-install").start(() -> install(source, build));
        return state;
    }

    public synchronized FfmpegModels.DownloadState cancel() {
        if (!running.get() || "installing".equals(state.phase())) {
            return state;
        }
        cancelRequested = true;
        state = new FfmpegModels.DownloadState("cancelled", null, 0L, null, null);
        closeQuietly(activeStream);
        Thread active = worker;
        if (active != null) {
            active.interrupt();
        }
        return state;
    }

    private void install(FfmpegCatalog.DownloadSource source, FfmpegCatalog.Build build) {
        Path destination = catalog.installDirectory();
        Path temporary = null;
        boolean preserveBackup = false;
        try {
            Path parent = destination.getParent();
            Files.createDirectories(parent);
            Path root = parent.toRealPath();
            Path target = root.resolve(destination.getFileName().toString());
            requireDirectory(target);
            temporary = Files.createTempDirectory(root, ".install-");
            Path staged = Files.createDirectory(temporary.resolve("bin"));
            for (String tool : TOOLS) {
                checkCancelled();
                FfmpegCatalog.Asset asset = build.tools().get(tool);
                if (asset == null) {
                    throw new IOException("当前平台缺少 " + tool + " 构建");
                }
                downloadAsset(source, tool, asset, temporary, staged);
            }
            checkCancelled();
            // 两个程序都验证完毕再整体替换；提交开始后不再响应取消。
            report("installing", null, 0L, null);
            Path previous = temporary.resolve("previous");
            boolean existed = requireDirectory(target);
            if (existed) {
                Files.move(target, previous, StandardCopyOption.ATOMIC_MOVE);
            }
            try {
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException exception) {
                if (existed) {
                    try {
                        Files.move(previous, target, StandardCopyOption.ATOMIC_MOVE);
                    } catch (IOException restoreFailure) {
                        preserveBackup = true;
                        throw new IOException("安装失败且无法恢复旧版本，旧文件保留在 " + previous, exception);
                    }
                }
                throw exception;
            }
            state = new FfmpegModels.DownloadState("completed", null, 0L, null, null);
        } catch (CancelledException exception) {
            state = new FfmpegModels.DownloadState("cancelled", null, 0L, null, null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            state = new FfmpegModels.DownloadState("cancelled", null, 0L, null, null);
        } catch (IOException | RuntimeException exception) {
            if (cancelRequested) {
                state = new FfmpegModels.DownloadState("cancelled", null, 0L, null, null);
            } else {
                String message = exception.getMessage() == null ? "FFmpeg 安装失败" : exception.getMessage();
                LOG.warn("event=ffmpeg_install_failed errorType={}", exception.getClass().getSimpleName());
                state = new FfmpegModels.DownloadState("error", null, 0L, null, message);
            }
        } finally {
            activeStream = null;
            worker = null;
            running.set(false);
            if (temporary != null && !preserveBackup) {
                cleanup(temporary);
            }
        }
    }

    private void downloadAsset(FfmpegCatalog.DownloadSource source, String tool, FfmpegCatalog.Asset asset,
            Path temporary, Path staged) throws IOException, InterruptedException {
        report("downloading", tool, 0L, null);
        URI uri = URI.create(source.urlPrefix() + asset.fileName());
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(DOWNLOAD_TIMEOUT)
                .header("Accept", "*/*")
                .GET()
                .build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            closeQuietly(response.body());
            throw new IOException(tool + " 下载失败（HTTP " + response.statusCode() + "）");
        }
        Long total = response.headers().firstValueAsLong("content-length").stream().boxed()
                .filter(length -> length > 0).findFirst().orElse(null);
        MessageDigest digest = sha256();
        Path compressed = temporary.resolve(asset.fileName());
        long received = 0;
        long reported = 0;
        try (InputStream body = response.body();
                OutputStream output = Files.newOutputStream(compressed, StandardOpenOption.CREATE_NEW)) {
            activeStream = body;
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = body.read(buffer)) >= 0) {
                checkCancelled();
                received += read;
                if (received > MAX_COMPRESSED_BYTES) {
                    throw new IOException("下载文件超过允许大小");
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
                if (received - reported >= REPORT_STEP_BYTES) {
                    reported = received;
                    report("downloading", tool, received, total);
                }
            }
        } finally {
            activeStream = null;
        }
        report("verifying", tool, received, total);
        String hash = HexFormat.of().formatHex(digest.digest());
        if (!hash.equals(asset.sha256())) {
            throw new IOException(tool + " 文件校验失败，请换源重试");
        }
        Path binary = staged.resolve(FfmpegCatalog.executableName(tool));
        extract(compressed, binary);
        if (!FfmpegCatalog.isWindows()) {
            Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        requireRunnable(binary, tool);
        Files.deleteIfExists(compressed);
    }

    private void extract(Path compressed, Path binary) throws IOException {
        try (InputStream input = new GZIPInputStream(Files.newInputStream(compressed));
                OutputStream output = Files.newOutputStream(binary, StandardOpenOption.CREATE_NEW)) {
            byte[] buffer = new byte[64 * 1024];
            long extracted = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                checkCancelled();
                extracted += read;
                if (extracted > MAX_EXTRACTED_BYTES) {
                    throw new IOException("解压文件超过允许大小");
                }
                output.write(buffer, 0, read);
            }
        }
    }

    private void requireRunnable(Path binary, String tool) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(binary.toString(), "-version");
        builder.redirectErrorStream(false);
        builder.environment().remove("FFREPORT");
        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (InputStream stream = process.getInputStream()) {
                output.append(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // 版本检测失败由退出码统一判定。
            }
        });
        try {
            if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException(tool + " 无法运行，请检查平台兼容性或重新下载");
            }
            reader.join(2000);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException(tool + " 版本检测被中断");
        }
        String version = output.toString().lines().findFirst().orElse("");
        if (process.exitValue() != 0 || !version.startsWith(tool + " version ")) {
            throw new IOException(tool + " 无法运行，请检查平台兼容性或重新下载");
        }
    }

    private boolean requireDirectory(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new IOException("安装目录必须是普通文件夹：" + path);
        }
        return true;
    }

    private void checkCancelled() throws CancelledException {
        if (cancelRequested) {
            throw new CancelledException();
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new CancelledException();
        }
    }

    private void report(String phase, String file, long received, Long total) {
        state = new FfmpegModels.DownloadState(phase, file, received, total, null);
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("缺少 SHA-256 实现", exception);
        }
    }

    private void cleanup(Path temporary) {
        try (var paths = Files.walk(temporary)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    LOG.warn("event=ffmpeg_temp_cleanup_failed path={}", path.getFileName());
                }
            });
        } catch (IOException exception) {
            LOG.warn("event=ffmpeg_temp_cleanup_failed errorType={}", exception.getClass().getSimpleName());
        }
    }

    private void closeQuietly(InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException ignored) {
            // 关闭用于打断阻塞读取，失败不影响取消结果。
        }
    }

    /** 下载被取消；与真正的 IO 失败区分，避免取消被记录成 error 状态。 */
    private static final class CancelledException extends IOException {
        private static final long serialVersionUID = 1L;
    }
}
