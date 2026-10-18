package com.homelabhub.internal.legacy;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Rejects a second power command for the same target while one is still running (double click, retry loop). */
@Component
public class InFlightGuard {

    private final Set<String> running = ConcurrentHashMap.newKeySet();

    /** @return a handle to close when finished, or null when the target is already busy */
    public Handle tryAcquire(String key) {
        return running.add(key) ? new Handle(key) : null;
    }

    public final class Handle implements AutoCloseable {
        private final String key;

        private Handle(String key) {
            this.key = key;
        }

        @Override
        public void close() {
            running.remove(key);
        }
    }
}
