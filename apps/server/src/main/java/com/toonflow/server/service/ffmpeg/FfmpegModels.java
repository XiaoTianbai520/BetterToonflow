package com.toonflow.server.service.ffmpeg;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/** FFmpeg 状态、下载进度与工具信息的对外结构，字段与 apiContract 3.4.3 / 3.4.4 一致。 */
public final class FfmpegModels {
    private FfmpegModels() {
    }

    /** 下载状态快照；phase 取值 idle/downloading/verifying/installing/completed/error/cancelled。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DownloadState(String phase, String file, long received, Long total, String error) {
        public static DownloadState idle() {
            return new DownloadState("idle", null, 0L, null, null);
        }

        public boolean busy() {
            return "downloading".equals(phase) || "verifying".equals(phase) || "installing".equals(phase);
        }
    }

    /** 下载线路；available=false 表示尚未提供已核验的同版本构建。 */
    public record Source(String id, String label, String description, boolean available, String homepage) {
    }

    /** 单个可执行文件的状态；origin 为 bundled（下载版）或 system（系统安装版）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolBinary(String path, String origin, String version, String error) {
    }

    /** FFmpeg 运行方式与下载线路配置；mode 为 auto/system/bundled。 */
    public record Config(String mode, String source) {
    }

    /** GET /api/ffmpeg/status 的完整响应体。 */
    public record Status(
            String platform,
            String arch,
            String target,
            boolean supported,
            String directory,
            String version,
            List<Source> sources,
            Config config,
            Map<String, ToolBinary> tools,
            DownloadState download) {
    }
}
