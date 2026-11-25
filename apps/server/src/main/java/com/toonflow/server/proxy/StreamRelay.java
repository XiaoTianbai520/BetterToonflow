package com.toonflow.server.proxy;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 三种流式协议的透传中继：NDJSON、SSE 与前导 \n 保活 Chunked JSON。
 * 收到任何字节立即写入并 flush，绝不整段缓冲；单次读取强制 120s 空闲超时（上游 20s 心跳会重置计时）；
 * 前端连接写入失败或空闲超时时关闭上游流，由 java.net.http 取消底层交换，使 Bun 侧触发 AbortController。
 */
public final class StreamRelay {
    private static final Logger LOG = LoggerFactory.getLogger(StreamRelay.class);
    private static final int BUFFER_SIZE = 8192;
    private static final int FAILURE_BODY_LIMIT = 1024 * 1024;
    /** 单次读取空闲超时 120s：正常长耗时任务每 20s 收到保活心跳会重置计时。 */
    private static final long READ_IDLE_TIMEOUT_MS = 120_000L;
    private static final ScheduledThreadPoolExecutor IDLE_WATCHDOG = createIdleWatchdog();

    private static ScheduledThreadPoolExecutor createIdleWatchdog() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "stream-relay-idle-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    public enum Protocol {
        NDJSON("application/x-ndjson; charset=utf-8"),
        SSE("text/event-stream; charset=utf-8"),
        CHUNKED_JSON("application/json; charset=utf-8");

        private final String contentType;

        Protocol(String contentType) {
            this.contentType = contentType;
        }

        public String contentType() {
            return contentType;
        }
    }

    private StreamRelay() {
    }

    public static void relay(HttpResponse<InputStream> upstream, HttpServletResponse response, Protocol protocol) {
        InputStream body = upstream.body();
        try {
            if (upstream.statusCode() < 200 || upstream.statusCode() >= 300) {
                relayFailure(upstream, body, response);
                return;
            }
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(protocol.contentType());
            response.setHeader("Cache-Control", "no-cache, no-transform");
            response.setHeader("Connection", "keep-alive");
            response.setHeader("X-Accel-Buffering", "no");
            // 先发出响应头，客户端才能立刻进入流式读取状态。
            response.flushBuffer();
            copy(body, response.getOutputStream());
        } catch (ClientDisconnectedException exception) {
            LOG.info("event=proxy_client_disconnected");
        } catch (IOException exception) {
            LOG.error("event=proxy_stream_failed errorType={}", exception.getClass().getSimpleName());
        } finally {
            close(body);
        }
    }

    private static void copy(InputStream source, ServletOutputStream sink) throws IOException {
        if (source == null) {
            return;
        }
        byte[] buffer = new byte[BUFFER_SIZE];
        ReadState state = new ReadState();
        Thread readerThread = Thread.currentThread();
        while (true) {
            long currentSeq = state.beginRead();
            ScheduledFuture<?> idleTask = IDLE_WATCHDOG.schedule(() -> {
                if (state.tryTimeout(currentSeq, readerThread)) {
                    Thread.startVirtualThread(() -> close(source));
                }
            }, READ_IDLE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            int read = -1;
            IOException readFailure = null;
            boolean timedOut;
            try {
                read = source.read(buffer);
            } catch (IOException exception) {
                readFailure = exception;
            } finally {
                timedOut = state.endRead(idleTask);
            }
            if (timedOut) {
                LOG.warn("event=proxy_stream_idle_timeout idleTimeoutMs={}", READ_IDLE_TIMEOUT_MS);
                return;
            }
            if (readFailure != null) {
                // 响应头已发出，无法再改写状态码；结束当前流交由客户端按协议处理。
                LOG.info("event=proxy_upstream_closed errorType={}", readFailure.getClass().getSimpleName());
                return;
            }
            if (read < 0) {
                return;
            }
            if (read == 0) {
                continue;
            }
            try {
                sink.write(buffer, 0, read);
                sink.flush();
            } catch (IOException exception) {
                throw new ClientDisconnectedException();
            }
        }
    }

    /** 建连前错误（含 balanceEvents 的 404）按上游状态码与正文原样透传。 */
    private static void relayFailure(HttpResponse<InputStream> upstream, InputStream body, HttpServletResponse response)
            throws IOException {
        byte[] payload = body == null ? new byte[0] : body.readNBytes(FAILURE_BODY_LIMIT);
        response.setStatus(upstream.statusCode());
        response.setContentType(upstream.headers().firstValue("content-type")
                .orElse("application/json; charset=utf-8"));
        response.setHeader("Cache-Control", "no-store");
        response.setContentLength(payload.length);
        response.getOutputStream().write(payload);
        response.flushBuffer();
    }

    private static void close(InputStream body) {
        if (body == null) {
            return;
        }
        try {
            body.close();
        } catch (IOException exception) {
            LOG.debug("event=proxy_upstream_close_failed errorType={}", exception.getClass().getSimpleName());
        }
    }

    private static final class ReadState {
        private final ReentrantLock lock = new ReentrantLock();
        private boolean reading;
        private boolean idleTimedOut;
        private long sequence;

        private long beginRead() {
            lock.lock();
            try {
                reading = true;
                sequence++;
                return sequence;
            } finally {
                lock.unlock();
            }
        }

        private boolean tryTimeout(long expectedSeq, Thread readerThread) {
            lock.lock();
            try {
                if (!reading || sequence != expectedSeq) {
                    return false;
                }
                reading = false;
                idleTimedOut = true;
                readerThread.interrupt();
                return true;
            } finally {
                lock.unlock();
            }
        }

        private boolean endRead(ScheduledFuture<?> idleTask) {
            idleTask.cancel(false);
            lock.lock();
            try {
                reading = false;
                if (idleTimedOut) {
                    // 清理空闲超时触发的中断标志，避免污染后续关闭流程或容器线程状态。
                    Thread.interrupted();
                    return true;
                }
                return false;
            } finally {
                lock.unlock();
            }
        }
    }

    private static final class ClientDisconnectedException extends IOException {
        private static final long serialVersionUID = 1L;
    }
}
