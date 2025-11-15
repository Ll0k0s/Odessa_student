package com.example.androidbuttons;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.LinkedBlockingQueue;

// Буфер строк для консоли, группирует сообщения перед выводом
class DataBuffer implements AutoCloseable {

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

    DataBuffer(int maxFlushBytes, StringConsumer consumer) {
        // Гарантируем минимальный размер пакета
        this.maxFlushBytes = Math.max(64, maxFlushBytes);
        this.consumer = consumer;
        // Периодический слив очереди
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override public void run() { flush(); }
        }, 100, 100);
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
        timer.cancel();
        flush();
    }
}
