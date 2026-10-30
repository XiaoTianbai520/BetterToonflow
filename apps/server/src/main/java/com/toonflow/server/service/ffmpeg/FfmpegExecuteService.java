package com.toonflow.server.service.ffmpeg;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.toonflow.server.common.BusinessException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

/**
 * FFmpeg 执行编排：JSON 模式（prepare/cancel）与 SSE 模式（run/query）共用同一套参数校验与任务注册。
 * 终结帧只发一次，客户端断开时通过 {@link FfmpegTask} 终止子进程。
 */
@Service
public class FfmpegExecuteService {
    private final FfmpegInstallService install;
    private final FfmpegEventHub events;
    private final FfmpegToolLocator locator;
    private final FfmpegPathGuard guard;
    private final FfmpegProcessRunner runner;
    private final FfmpegProbeRunner probe;
    private final FfmpegCapabilityReader capabilities;
    private final FfmpegScreenshotRecipe screenshots;
    private final ObjectMapper json;
    private final ConcurrentHashMap<String, FfmpegTask> running = new ConcurrentHashMap<>();

    /** SSE 数据帧出口，由控制器负责序列化与写流。 */
    @FunctionalInterface
    public interface Sink {
        void send(String event, List<Object> args);
    }

    public FfmpegExecuteService(FfmpegInstallService install, FfmpegEventHub events, FfmpegToolLocator locator,
            FfmpegPathGuard guard, FfmpegProcessRunner runner, FfmpegProbeRunner probe,
            FfmpegCapabilityReader capabilities, FfmpegScreenshotRecipe screenshots, ObjectMapper json) {
        this.install = install;
        this.events = events;
        this.locator = locator;
        this.guard = guard;
        this.runner = runner;
        this.probe = probe;
        this.capabilities = capabilities;
        this.screenshots = screenshots;
        this.json = json;
    }

    /** prepare 与执行前置检查：二进制未就绪时广播 required 并返回 424。 */
    public FfmpegToolLocator.Tools requireTools() {
        FfmpegToolLocator.Tools tools = locator.ready(install.config().mode());
        if (tools == null) {
            events.requireFfmpeg();
            throw new BusinessException(424, "当前操作需要 FFmpeg，请在插件市场下载安装或配置可用版本后重试。",
                    Map.of("code", "FFMPEG_REQUIRED"));
        }
        return tools;
    }

    public FfmpegTask register(String key) {
        FfmpegTask task = new FfmpegTask(key);
        if (running.putIfAbsent(key, task) != null) {
            throw new BusinessException(409, "FFmpeg 任务已在运行", null);
        }
        return task;
    }

    public void unregister(String key, FfmpegTask task) {
        running.remove(key, task);
    }

    /** 显式取消：终止子进程并等待其退出，随后由 SSE 线程发送 error 终结帧。 */
    public void cancel(String key) {
        FfmpegTask task = running.get(key);
        if (task != null) {
            task.cancel();
            task.await(10, TimeUnit.SECONDS);
        }
    }

    /** 建连前校验：不满足条件直接返回 400/403，避免把错误伪装成流内事件。 */
    public void validate(Path root, FfmpegCallPlan plan, FfmpegExecuteRequest.Call operation) throws IOException {
        String method = operation.method();
        if (FfmpegCallPlan.SAVE_METHODS.contains(method) || FfmpegCallPlan.CONCAT_METHODS.contains(method)) {
            guard.file(root, operation.text(0));
            if (!plan.hasInput()) {
                throw new BusinessException(400, "未指定输入文件", null);
            }
            return;
        }
        if (FfmpegCallPlan.SCREENSHOT_METHODS.contains(method)) {
            if (!plan.hasInput()) {
                throw new BusinessException(400, "未指定输入文件", null);
            }
            return;
        }
        if (FfmpegCallPlan.QUERY_METHODS.contains(method)) {
            if ("ffprobe".equals(method) && !plan.hasInput()) {
                throw new BusinessException(400, "未指定输入文件", null);
            }
            return;
        }
        if ("run".equals(method) && !plan.hasTarget()) {
            throw new BusinessException(400, "未指定输出文件", null);
        }
    }

    public void execute(Path root, FfmpegExecuteRequest request, FfmpegCallPlan plan, FfmpegTask task,
            FfmpegToolLocator.Tools tools, Sink sink) throws IOException {
        FfmpegExecuteRequest.Call operation = request.operation();
        String method = operation.method();
        if (FfmpegCallPlan.QUERY_METHODS.contains(method)) {
            Object data = "ffprobe".equals(method)
                    ? probe.probe(tools.ffprobe(), Path.of(plan.source()), operation.args(), plan.cwd(), task)
                    : capabilities.read(tools.ffmpeg(), method, plan.cwd(), task);
            sink.send("result", List.of(data));
            return;
        }
        if (FfmpegCallPlan.SCREENSHOT_METHODS.contains(method)) {
            List<String> filenames = screenshots.prepare(root, plan.cwd(), plan, operation, tools.ffprobe(), task);
            sink.send("filenames", List.of(filenames));
            runFfmpeg(plan, tools, task, sink);
            return;
        }
        if (FfmpegCallPlan.CONCAT_METHODS.contains(method)) {
            concat(root, plan, operation, tools, task);
            runFfmpeg(plan, tools, task, sink);
            return;
        }
        if (FfmpegCallPlan.SAVE_METHODS.contains(method)) {
            plan.addOutput(guard.file(root, operation.text(0)).toString());
            runFfmpeg(plan, tools, task, sink);
            return;
        }
        if ("run".equals(method)) {
            runFfmpeg(plan, tools, task, sink);
            return;
        }
        throw new BusinessException(400, "FFmpeg 执行方法不可用：" + method, null);
    }

    private void concat(Path root, FfmpegCallPlan plan, FfmpegExecuteRequest.Call operation,
            FfmpegToolLocator.Tools tools, FfmpegTask task) throws IOException {
        plan.addOutput(guard.file(root, operation.text(0)).toString());
        Map<String, Object> metadata = probe.metadata(tools.ffprobe(), Path.of(plan.source()), plan.cwd());
        boolean hasAudio = hasStream(metadata, "audio");
        boolean hasVideo = hasStream(metadata, "video");
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("n", plan.inputCount());
        options.put("v", hasVideo ? 1 : 0);
        options.put("a", hasAudio ? 1 : 0);
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("filter", "concat");
        spec.put("options", options);
        plan.apply(new FfmpegExecuteRequest.Call("complexFilter", List.of(json.valueToTree(spec)), Set.of()));
    }

    private void runFfmpeg(FfmpegCallPlan plan, FfmpegToolLocator.Tools tools, FfmpegTask task, Sink sink)
            throws IOException {
        Double duration = plan.source() == null ? null
                : probe.duration(tools.ffprobe(), Path.of(plan.source()), plan.cwd());
        List<String> arguments = plan.arguments();
        FfmpegProcessRunner.StreamOptions options = new FfmpegProcessRunner.StreamOptions(
                plan.cwd(), plan.niceness(), plan.timeout(), plan.stdoutLines(), duration);
        FfmpegProcessRunner.Listener listener = new FfmpegProcessRunner.Listener() {
            @Override
            public void start(String command) {
                sink.send("start", List.of(command));
            }

            @Override
            public void stderr(String line) {
                sink.send("stderr", List.of(line));
            }

            @Override
            public void codecData(Map<String, Object> data) {
                sink.send("codecData", List.of(data));
            }

            @Override
            public void progress(Map<String, Object> progress) {
                sink.send("progress", List.of(progress));
            }
        };
        FfmpegProcessRunner.ProcessResult result = runner.run(tools.ffmpeg(), arguments, options, listener, task);
        if (result.cancelled()) {
            sink.send("error", List.of(error("AbortError", "FFmpeg 已取消"), result.stdout(), result.stderr()));
            return;
        }
        if (result.timedOut()) {
            sink.send("error", List.of(error("Error", "process ran into a timeout (" + plan.timeout() + "s)"),
                    result.stdout(), result.stderr()));
            return;
        }
        if (result.exitCode() != 0) {
            String detail = FfmpegProcessRunner.extractError(result.stderr());
            String message = "ffmpeg exited with code " + result.exitCode() + (detail.isEmpty() ? "" : ": " + detail);
            sink.send("error", List.of(error("Error", message), result.stdout(), result.stderr()));
            return;
        }
        sink.send("end", List.of(result.stdout(), result.stderr()));
    }

    private boolean hasStream(Map<String, Object> metadata, String type) {
        if (metadata.get("streams") instanceof List<?> streams) {
            for (Object item : streams) {
                if (item instanceof Map<?, ?> stream && type.equals(stream.get("codec_type"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private Map<String, Object> error(String name, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("name", name);
        error.put("message", message);
        return error;
    }
}
