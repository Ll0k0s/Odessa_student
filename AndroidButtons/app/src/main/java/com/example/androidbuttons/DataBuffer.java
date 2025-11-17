package com.example.androidbuttons;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.LinkedBlockingQueue;

// Буфер строк для консоли, группирует сообщения перед выводом
class DataBuffer implements AutoCloseable {

    private static final long FLUSH_INTERVAL_MS = 100L;

    // Получатель готового блока текста
    interface StringConsumer { void accept(String s); }

    // Очередь накопленных строк
    private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
    // Максимальный размер пакета при сливе
    private final int maxFlushBytes;
    // Колбэк для готового блока
    private final StringConsumer consumer;
    // Таймер фоновой отправки
    private final Timer timer = new Timer("ui-buf", true);
    private volatile boolean closed = false;

    DataBuffer(int maxFlushBytes, StringConsumer consumer) {
        // Гарантируем минимальный размер пакета
        this.maxFlushBytes = Math.max(64, maxFlushBytes);
        this.consumer = consumer;
        // Запускаем цикл ручного параллельного планирования, чтобы избежать scheduleAtFixedRate
        scheduleNextFlush();
    }

    private void scheduleNextFlush() {
        if (closed) return;
        long safeDelay = Math.max(1L, FLUSH_INTERVAL_MS);
        timer.schedule(new TimerTask() {
            @Override public void run() {
                try {
                    flush();
                } finally {
                    scheduleNextFlush();
                }
            }
        }, safeDelay);
    }

    // Кладём строку в очередь
    void offer(String s) {
        // Пустые строки пропускаем
        if (s == null || s.isEmpty()) return;
        queue.offer(s);
    }

    // Собираем и отправляем накопленные строки одним блоком
    private void flush() {
        // Если очереди пусто — выходим
        if (queue.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        // Читаем пока есть данные и не превышен лимит
        while (!queue.isEmpty() && sb.length() < maxFlushBytes) {
            String s = queue.poll();
            if (s == null) break;
            sb.append(s);
        }
        // Отдаём блок потребителю
        if (sb.length() > 0 && consumer != null) consumer.accept(sb.toString());
    }

    @Override
    public void close() {
        // Останавливаем таймер и досливаем остатки
        closed = true;
        timer.cancel();
        flush();
    }
}
