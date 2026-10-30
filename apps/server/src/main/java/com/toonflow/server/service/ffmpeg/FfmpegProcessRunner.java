package com.toonflow.server.service.ffmpeg;

import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;

/**
 * 启动 ffmpeg / ffprobe 子进程并回传事件。
 * 子进程登记在 {@link FfmpegTask} 上，客户端断连或显式取消时立即终止，避免留下孤儿进程。
 */
@Service
public class FfmpegProcessRunner {
    private static final int MAX_STDOUT_BYTES = 8 * 1024 * 1024;
    private static final int MAX_RING_LINES = 100_000;

    public record ProcessResult(int exitCode, String stdout, String stderr, boolean timedOut, boolean cancelled) {
    }

    public record CaptureResult(int exitCode, String stdout, String stderr) {
    }

    public record StreamOptions(Path cwd, int niceness, Double timeoutSeconds, int stdoutLines,
            Double durationSeconds) {
    }

    /** 事件出口；实现方负责序列化与写流，写失败时抛出异常即可中止任务。 */
    public interface Listener {
        void start(String command);

        void stderr(String line);

        void codecData(Map<String, Object> data);

        void progress(Map<String, Object> progress);
    }

    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "ffmpeg-timeout-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    public ProcessResult run(Path binary, List<String> args, StreamOptions options, Listener listener,
            FfmpegTask task) throws IOException {
        List<String> command = command(binary, args, options.niceness());
        ProcessBuilder builder = new ProcessBuilder(command).directory(options.cwd().toFile());
        builder.redirectErrorStream(false);
        Process process;
        try {
            process = builder.start();
        } catch (IOException exception) {
            throw new IOException("无法启动 FFmpeg，请确认二进制可用", exception);
        }
        task.attach(process);
        listener.start("ffmpeg " + String.join(" ", args));

        StringBuilder stdout = new StringBuilder();
        Thread reader = Thread.ofVirtual().start(() -> drain(process.getInputStream(), stdout));
        AtomicBoolean timedOut = new AtomicBoolean(false);
        ScheduledFuture<?> timer = null;
        if (options.timeoutSeconds() != null && options.timeoutSeconds() > 0) {
            long millis = (long) (options.timeoutSeconds() * 1000);
            timer = watchdog.schedule(() -> {
                timedOut.set(true);
                process.destroyForcibly();
            }, millis, TimeUnit.MILLISECONDS);
        }

        Deque<String> ring = new ArrayDeque<>();
        FfmpegOutputParser parser = new FfmpegOutputParser(options.durationSeconds());
        try {
            try (BufferedReader stream = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = stream.readLine()) != null) {
                    append(ring, line, options.stdoutLines());
                    listener.stderr(line);
                    if (parser.acceptCodecLine(line)) {
                        listener.codecData(parser.codecData());
                    }
                    Map<String, Object> progress = parser.progress(line);
                    if (progress != null) {
                        listener.progress(progress);
                    }
                }
            }
            int exitCode = process.waitFor();
            reader.join(2000);
            String stderr = String.join("\n", ring);
            if (timedOut.get()) {
                return new ProcessResult(exitCode, stdout.toString(), stderr, true, false);
            }
            return new ProcessResult(exitCode, stdout.toString(), stderr, false, task.isCancelled());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("FFmpeg 执行被中断");
        } catch (RuntimeException exception) {
            // 事件写流失败（多为客户端断开）时立即终止子进程。
            process.destroyForcibly();
            throw exception;
        } finally {
            if (timer != null) {
                timer.cancel(false);
            }
        }
    }

    /** 一次性抓取 stdout 的命令（ffprobe、能力查询），带硬超时。 */
    public CaptureResult capture(Path binary, List<String> args, Path cwd, long timeoutSeconds, FfmpegTask task)
            throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command(binary, args, 0)).directory(cwd.toFile());
        builder.redirectErrorStream(false);
        Process process;
        try {
            process = builder.start();
        } catch (IOException exception) {
            throw new IOException("无法启动 " + binary.getFileName() + "，请确认二进制可用", exception);
        }
        if (task != null) {
            task.attach(process);
        }
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        Thread out = Thread.ofVirtual().start(() -> drain(process.getInputStream(), stdout));
        Thread err = Thread.ofVirtual().start(() -> drain(process.getErrorStream(), stderr));
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("命令执行超时：" + binary.getFileName());
            }
            out.join(2000);
            err.join(2000);
            return new CaptureResult(process.exitValue(), stdout.toString(), stderr.toString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("命令执行被中断");
        }
    }

    public static String extractError(String stderr) {
        Deque<String> messages = new ArrayDeque<>();
        for (String line : stderr.split("\\r\\n|\\r|\\n")) {
            if (line.isEmpty()) {
                continue;
            }
            if (line.charAt(0) == ' ' || line.charAt(0) == '[') {
                messages.clear();
            } else {
                messages.add(line);
            }
        }
        return String.join("\n", new ArrayList<>(messages));
    }

    private List<String> command(Path binary, List<String> args, int niceness) {
        List<String> command = new ArrayList<>();
        if (niceness != 0 && !FfmpegCatalog.isWindows()) {
            command.add("nice");
            command.add("-n");
            command.add(String.valueOf(niceness));
        }
        command.add(binary.toString());
        command.addAll(args);
        return command;
    }

    private void drain(InputStream stream, StringBuilder target) {
        byte[] buffer = new byte[8192];
        try (stream) {
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                synchronized (target) {
                    if (target.length() < MAX_STDOUT_BYTES) {
                        target.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                    }
                }
            }
        } catch (IOException ignored) {
            // 进程被强杀时读取会失败，输出内容按已读取部分返回。
        }
    }

    private void append(Deque<String> ring, String line, int maxLines) {
        int limit = maxLines <= 0 ? MAX_RING_LINES : Math.max(1, maxLines);
        ring.addLast(line);
        while (ring.size() > limit) {
            ring.removeFirst();
        }
    }

    @PreDestroy
    void shutdown() {
        watchdog.shutdownNow();
    }
}
