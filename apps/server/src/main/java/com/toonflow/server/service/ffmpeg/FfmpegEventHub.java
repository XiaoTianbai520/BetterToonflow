package com.toonflow.server.service.ffmpeg;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 全局 FFmpeg 状态通知流：前端只建立一条长连接，后端在二进制缺失时主动唤醒安装引导弹窗。
 */
@Component
public class FfmpegEventHub {
    private static final long KEEPALIVE_SECONDS = 20;
    private static final String REQUIRED_FRAME = "{\"type\":\"required\"}";
    private final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "ffmpeg-events-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public FfmpegEventHub() {
        heartbeat.scheduleAtFixedRate(this::keepalive, KEEPALIVE_SECONDS, KEEPALIVE_SECONDS, TimeUnit.SECONDS);
    }

    /** 建立连接：首帧固定为注释帧，后续仅推送 required 与保活注释。 */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitter.onCompletion(() -> subscribers.remove(emitter));
        emitter.onError(error -> subscribers.remove(emitter));
        emitter.onTimeout(() -> subscribers.remove(emitter));
        subscribers.add(emitter);
        // Spring 的注释帧按 ":" + comment 输出，补前导空格以对齐契约的 ": connected\n\n"。
        if (!write(emitter, () -> emitter.send(SseEmitter.event().comment(" connected")))) {
            subscribers.remove(emitter);
            emitter.complete();
        }
        return emitter;
    }

    /** FFmpeg 未就绪时向所有连接广播；无连接时静默丢弃，前端下次打开面板会重新拉取状态。 */
    public void requireFfmpeg() {
        for (SseEmitter emitter : subscribers) {
            // 契约帧为 "data: {...}"，Spring 不额外补空格，这里显式前置。
            write(emitter, () -> emitter.send(SseEmitter.event().data(" " + REQUIRED_FRAME)));
        }
    }

    private void keepalive() {
        for (SseEmitter emitter : subscribers) {
            write(emitter, () -> emitter.send(SseEmitter.event().comment(" keepalive")));
        }
    }

    private boolean write(SseEmitter emitter, SseFrame frame) {
        try {
            frame.write();
            return true;
        } catch (IOException | IllegalStateException exception) {
            subscribers.remove(emitter);
            try {
                emitter.complete();
            } catch (RuntimeException ignored) {
                // 连接已由容器回收，无需再次关闭。
            }
            return false;
        }
    }

    @PreDestroy
    void shutdown() {
        heartbeat.shutdownNow();
        subscribers.forEach(SseEmitter::complete);
        subscribers.clear();
    }

    @FunctionalInterface
    private interface SseFrame {
        void write() throws IOException;
    }
}
