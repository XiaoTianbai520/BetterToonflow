package com.toonflow.server.service.ffmpeg;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * FFmpeg 6.1.1 预编译包目录：平台目标、SHA-256 校验值与下载线路。
 * 校验值来自 ffmpeg-static 上游 b6.1.1 Release，下载后必须逐字节校验，避免镜像串包。
 */
@Component
public class FfmpegCatalog {
    public static final String VERSION = "6.1.1";

    /** 单个可执行程序在压缩包中的文件名与 gzip 原始摘要。 */
    public record Asset(String fileName, String sha256) {
    }

    /** 平台构建；键为平台工具名 ffmpeg / ffprobe。 */
    public record Build(String version, Map<String, Asset> tools) {
    }

    /** 下载线路及其 URL 前缀，不对外暴露前缀。 */
    public record DownloadSource(String id, String label, String description, boolean available,
            String homepage, String urlPrefix) {
        FfmpegModels.Source toPublic() {
            return new FfmpegModels.Source(id, label, description, available, homepage);
        }
    }

    private static final Map<String, Build> BUILDS = builds();
    private static final List<DownloadSource> SOURCES = List.of(
            new DownloadSource("npmmirror", "阿里镜像（推荐）", "国内下载源，建议优先使用", true,
                    "https://npmmirror.com/", "https://cdn.npmmirror.com/binaries/ffmpeg-static/b6.1.1/"),
            new DownloadSource("github", "GitHub 上游", "ffmpeg-static 项目发布的预编译程序", true,
                    "https://github.com/eugeneware/ffmpeg-static/releases/tag/b6.1.1",
                    "https://github.com/eugeneware/ffmpeg-static/releases/download/b6.1.1/"),
            // unpkg 未提供 b6.1.1 的 .gz 构建，保持不可用，避免下载到无法校验的产物。
            new DownloadSource("unpkg", "unpkg CDN", "该线路暂未提供已核验的同版本构建，暂不支持自动下载", false,
                    "https://unpkg.com/", "https://unpkg.com/ffmpeg-static@5.2.0/"));

    private final Path dataDirectory;

    public FfmpegCatalog(@Value("${toonflow.data-dir}") String dataDirectory) {
        this.dataDirectory = Path.of(dataDirectory).toAbsolutePath().normalize();
    }

    public String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "win32";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "darwin";
        }
        return "linux";
    }

    public String arch() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return switch (arch) {
            case "amd64", "x86_64", "x64" -> "x64";
            case "aarch64", "arm64" -> "arm64";
            default -> arch;
        };
    }

    public String target() {
        return platform() + "-" + arch();
    }

    /** 当前平台的可下载构建；不支持时返回 null（status.supported=false）。 */
    public Build build() {
        return BUILDS.get(target());
    }

    public List<DownloadSource> sources() {
        return SOURCES;
    }

    public DownloadSource source(String id) {
        return SOURCES.stream().filter(item -> item.id().equals(id)).findFirst().orElse(null);
    }

    /** 下载版安装目录：{dataDir}/ffmpeg/{target}，与旧实现的目录布局保持一致。 */
    public Path installDirectory() {
        return dataDirectory.resolve("ffmpeg").resolve(target());
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    public static String executableName(String tool) {
        return isWindows() ? tool + ".exe" : tool;
    }

    private static Map<String, Build> builds() {
        Map<String, Build> builds = new LinkedHashMap<>();
        builds.put("win32-x64", new Build(VERSION, tools(
                "ffmpeg-win32-x64.gz", "8883a3dffbd0a16cf4ef95206ea05283f78908dbfb118f73c83f4951dcc06d77",
                "ffprobe-win32-x64.gz", "f309e6223ad89d2fe54bccd420a7709b66fd27540674e92309578ed491a43c8d")));
        builds.put("darwin-x64", new Build(VERSION, tools(
                "ffmpeg-darwin-x64.gz", "929b375c1182d956c51f7ac25e0b2b0411fb01f6f407aa15c9758efeb4242106",
                "ffprobe-darwin-x64.gz", "d4da574d6e2e197bd259b47d69cf262df9e312af24ad960444f6d806d3d4c186")));
        builds.put("darwin-arm64", new Build(VERSION, tools(
                "ffmpeg-darwin-arm64.gz", "8923876afa8db5585022d7860ec7e589af192f441c56793971276d450ed3bbfa",
                "ffprobe-darwin-arm64.gz", "d986a8ec7b030899fe66a8a288ed809a3543338705a3ce178cfb85869c5d80be")));
        builds.put("linux-x64", new Build(VERSION, tools(
                "ffmpeg-linux-x64.gz", "bfe8a8fc511530457b528c48d77b5737527b504a3797a9bc4866aeca69c2dffa",
                "ffprobe-linux-x64.gz", "25d9b6ccb05e3d9de9e04e31e2506d8dd7f9f0418981965ac6df12e8d3afd067")));
        builds.put("linux-arm64", new Build(VERSION, tools(
                "ffmpeg-linux-arm64.gz", "754a678672298bc68156adff58aa7385a592c2b30b1d0ae8750c45c915c4bac0",
                "ffprobe-linux-arm64.gz", "2ab6aba60ee84412dff9188720703376cb4e7aaf7e0b5e43aa8249f2acae5bf8")));
        return Map.copyOf(builds);
    }

    private static Map<String, Asset> tools(String ffmpegFile, String ffmpegHash, String ffprobeFile,
            String ffprobeHash) {
        return Map.of(
                "ffmpeg", new Asset(ffmpegFile, ffmpegHash),
                "ffprobe", new Asset(ffprobeFile, ffprobeHash));
    }
}
