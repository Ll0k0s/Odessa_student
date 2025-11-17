package com.example.androidbuttons.core;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe buffer for log lines consumed by Settings screen or other observers.
 */
public final class ConsoleLogRepository {

    private static final int MAX_LINES = 10_000;

    private final Queue<String> queue = new LinkedList<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    public void append(String line) {
        if (line == null || line.isEmpty()) {
            return;
        }
        synchronized (queue) {
            queue.add(line);
            while (queue.size() > MAX_LINES) {
                queue.poll();
            }
        }
        for (Listener listener : listeners) {
            listener.onLogAppended(line);
        }
    }

    public String drainAll() {
        StringBuilder sb = new StringBuilder();
        synchronized (queue) {
            while (!queue.isEmpty()) {
                sb.append(queue.poll());
            }
        }
        return sb.toString();
    }

    public void addListener(Listener listener) {
        listeners.addIfAbsent(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public interface Listener {
        void onLogAppended(String line);
    }
}
