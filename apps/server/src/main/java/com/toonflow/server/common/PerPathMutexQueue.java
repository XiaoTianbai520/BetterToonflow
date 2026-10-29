package com.toonflow.server.common;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

@Component
public class PerPathMutexQueue {
    private final ConcurrentHashMap<Path, Entry> entries = new ConcurrentHashMap<>();

    /** 调用方传入 PathSandbox 规范化后的路径；公平锁让同路径等待者按队列进入。 */
    public <T> T execute(Path path, IoOperation<T> operation) throws IOException {
        Path key = path.toAbsolutePath().normalize();
        Entry entry = entries.compute(key, (ignored, current) -> {
            Entry result = current == null ? new Entry() : current;
            result.references++;
            return result;
        });
        entry.lock.lock();
        try {
            return operation.run();
        } finally {
            entry.lock.unlock();
            entries.compute(key, (ignored, current) -> --current.references == 0 ? null : current);
        }
    }

    @FunctionalInterface
    public interface IoOperation<T> {
        T run() throws IOException;
    }

    private static final class Entry {
        private final ReentrantLock lock = new ReentrantLock(true);
        private int references;
    }
}
