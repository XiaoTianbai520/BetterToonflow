package com.toonflow.server.controller.ffmpeg;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.toonflow.server.common.ApiResponse;
import com.toonflow.server.common.BusinessException;
import com.toonflow.server.common.PathSandbox;
import com.toonflow.server.service.ffmpeg.FfmpegCallPlan;
import com.toonflow.server.service.ffmpeg.FfmpegExecuteRequest;
import com.toonflow.server.service.ffmpeg.FfmpegExecuteService;
import com.toonflow.server.service.ffmpeg.FfmpegPathGuard;
import com.toonflow.server.service.ffmpeg.FfmpegTask;
import com.toonflow.server.service.ffmpeg.FfmpegToolLocator;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * POST /api/ffmpeg/execute：按 operation.method 严格分流。
 *
 * <p>cancel / prepare 走 JSON 模式，其余 20 个执行与查询方法走 SSE 模式；
 * 两种模式的响应头、状态码与终结帧形态完全不同，禁止混用。</p>
 */
@RestController
public class FfmpegExecuteController {
    private static final long HEARTBEAT_MILLIS = 1000;
    private final PathSandbox sandbox;
    private final FfmpegPathGuard guard;
    private final FfmpegExecuteService executeService;
    private final ObjectMapper json;
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "ffmpeg-execute-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public FfmpegExecuteController(PathSandbox sandbox, FfmpegPathGuard guard, FfmpegExecuteService executeService,
            ObjectMapper json) {
        this.sandbox = sandbox;
        this.guard = guard;
        this.executeService = executeService;
        this.json = json;
    }

    @PostMapping("/api/ffmpeg/execute")
    public Object execute(@RequestBody(required = false) JsonNode body, HttpServletResponse response) throws IOException {
        FfmpegExecuteRequest request = FfmpegExecuteRequest.parse(body);
        Path root = workspace(request.directory());
        String key = root + "\0" + request.requestId();
        String method = request.operation().method();
        if ("cancel".equals(method)) {
            executeService.cancel(key);
            return new ApiResponse<>(200, null, "成功");
        }
        if ("prepare".equals(method)) {
            executeService.requireTools();
            return new ApiResponse<>(200, null, "成功");
        }
        if (!FfmpegCallPlan.RUN_METHODS.contains(method) && !FfmpegCallPlan.QUERY_METHODS.contains(method)) {
            throw new BusinessException(400, "FFmpeg 执行方法不可用：" + method, null);
        }
        FfmpegCallPlan plan = plan(root, request);
        executeService.validate(root, plan, request.operation());
        FfmpegTask task = executeService.register(key);
        FfmpegToolLocator.Tools tools;
        try {
            tools = executeService.requireTools();
        } catch (RuntimeException exception) {
            executeService.unregister(key, task);
            throw exception;
        }

        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        SseEmitter emitter = new SseEmitter(0L);
        emitter.onCompletion(task::cancel);
        emitter.onError(error -> task.cancel());
        emitter.onTimeout(task::cancel);
        FfmpegExecuteService.Sink sink = (event, args) -> write(emitter, event, args);
        ScheduledFuture<?> beat = heartbeat.scheduleAtFixedRate(() -> keepalive(emitter, task),
                HEARTBEAT_MILLIS, HEARTBEAT_MILLIS, TimeUnit.MILLISECONDS);
        Thread.ofVirtual().name("ffmpeg-execute").start(() -> {
            try {
                executeService.execute(root, request, plan, task, tools, sink);
            } catch (ClientGone exception) {
                task.cancel();
            } catch (BusinessException | IOException exception) {
                writeQuietly(emitter, exception.getMessage() == null ? "FFmpeg 执行失败" : exception.getMessage());
            } catch (RuntimeException exception) {
                writeQuietly(emitter, "FFmpeg 执行失败");
            } finally {
                beat.cancel(false);
                task.complete();
                executeService.unregister(key, task);
                try {
                    emitter.complete();
                } catch (RuntimeException ignored) {
                    // 连接已关闭，complete 无副作用。
                }
            }
        });
        return emitter;
    }

    private Path workspace(String directory) throws IOException {
        try {
            return sandbox.root(Path.of(directory));
        } catch (InvalidPathException exception) {
            throw new BusinessException(400, "参数错误", null);
        }
    }

    private FfmpegCallPlan plan(Path root, FfmpegExecuteRequest request) {
        try {
            FfmpegCallPlan plan = new FfmpegCallPlan(root, guard, request.options());
            for (FfmpegExecuteRequest.Call call : request.calls()) {
                plan.apply(call);
            }
            return plan;
        } catch (IOException exception) {
            throw new BusinessException(400, "FFmpeg 工作目录无效", null);
        }
    }

    private void write(SseEmitter emitter, String event, List<Object> args) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("event", event);
        frame.put("args", args);
        String payload;
        try {
            payload = json.writeValueAsString(frame);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("FFmpeg 事件序列化失败", exception);
        }
        try {
            // 契约帧为 "data: {...}"，Spring 不额外补空格，这里显式前置。
            emitter.send(SseEmitter.event().data(" " + payload));
        } catch (IOException | IllegalStateException exception) {
            throw new ClientGone();
        }
    }

    private void writeQuietly(SseEmitter emitter, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("name", "Error");
        error.put("message", message);
        try {
            write(emitter, "error", List.of(error));
        } catch (RuntimeException ignored) {
            // 客户端已断开，无需再写终结帧。
        }
    }

    private void keepalive(SseEmitter emitter, FfmpegTask task) {
        try {
            // 与契约一致输出 ": keepalive\n\n"。
            emitter.send(SseEmitter.event().comment(" keepalive"));
        } catch (IOException | IllegalStateException exception) {
            task.cancel();
        }
    }

    @PreDestroy
    void shutdown() {
        heartbeat.shutdownNow();
    }

    /** 客户端断开：中止写流即可让执行线程结束，不再尝试发送任何帧。 */
    private static final class ClientGone extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
