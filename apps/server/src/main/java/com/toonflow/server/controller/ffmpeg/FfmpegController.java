package com.toonflow.server.controller.ffmpeg;

import com.fasterxml.jackson.databind.JsonNode;
import com.toonflow.server.common.ApiResponse;
import com.toonflow.server.common.BusinessException;
import com.toonflow.server.service.ffmpeg.FfmpegEventHub;
import com.toonflow.server.service.ffmpeg.FfmpegInstallService;
import com.toonflow.server.service.ffmpeg.FfmpegModels;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** FFmpeg 二进制状态、下载与全局通知流（apiContract 3.4.3 ~ 3.4.7）。 */
@RestController
public class FfmpegController {
    private static final int MAX_SOURCE_LENGTH = 40;
    private final FfmpegInstallService installService;
    private final FfmpegEventHub eventHub;

    public FfmpegController(FfmpegInstallService installService, FfmpegEventHub eventHub) {
        this.installService = installService;
        this.eventHub = eventHub;
    }

    @GetMapping("/api/ffmpeg/status")
    public ApiResponse<FfmpegModels.Status> status(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.success(installService.status());
    }

    @GetMapping("/api/ffmpeg/progress")
    public ApiResponse<FfmpegModels.DownloadState> progress(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.success(installService.progress());
    }

    @PostMapping("/api/ffmpeg/download")
    public ApiResponse<FfmpegModels.DownloadState> download(@RequestBody(required = false) JsonNode body) {
        JsonNode source = body == null ? null : body.get("source");
        if (source == null || !source.isTextual()) {
            throw new BusinessException(400, "参数错误", null);
        }
        String id = source.asText();
        if (id.isEmpty() || id.length() > MAX_SOURCE_LENGTH) {
            throw new BusinessException(400, "参数错误", null);
        }
        FfmpegModels.DownloadState state = installService.download(id);
        return new ApiResponse<>(200, state, "已开始下载 FFmpeg");
    }

    @PostMapping("/api/ffmpeg/cancel")
    public ApiResponse<FfmpegModels.DownloadState> cancel() {
        return new ApiResponse<>(200, installService.cancel(), "已取消下载");
    }

    /** 全局状态通知流：首帧 connected、20s 保活、FFmpeg 缺失时推送 required。 */
    @GetMapping("/api/ffmpeg/events")
    public SseEmitter events(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        response.setCharacterEncoding("UTF-8");
        return eventHub.subscribe();
    }
}
