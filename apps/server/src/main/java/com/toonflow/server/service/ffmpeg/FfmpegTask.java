package com.toonflow.server.service.ffmpeg;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次 FFmpeg 执行会话。requestId 相同且工作区相同的任务互斥，
 * 取消时立即终止子进程，SSE 线程据此发送 error 终结帧。
 */
public final class FfmpegTask {
    private final String key;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final CountDownLatch finished = new CountDownLatch(1);
    private volatile Process process;

    public FfmpegTask(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    /** 子进程创建后立即登记，取消请求可能早于 spawn，需要在此补杀。 */
    public void attach(Process process) {
        this.process = process;
        if (cancelled.get()) {
            process.destroyForcibly();
        }
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void cancel() {
        cancelled.set(true);
        Process active = process;
        if (active != null) {
            active.destroy();
            if (active.isAlive()) {
                active.destroyForcibly();
            }
        }
    }

    public void complete() {
        finished.countDown();
    }

    public boolean await(long timeout, TimeUnit unit) {
        try {
            return finished.await(timeout, unit);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
