package com.example.androidbuttons;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Locale;
import android.util.Log;
import java.util.function.Consumer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

// Менеджер TCP-подключения к ESP/железу.
// Держит авто-подключение, сбор/разбор кадров и не блокирует UI.
final class TcpManager {

    // Колбэки для внешнего кода (сейчас не используются, но оставлены на будущее)
    interface Callback {
        void onStart();
        void onStop();
        void onData(String data);
        void onError(String message);
    }

    // Колбэк старта поиска/подключения
    private final Runnable onStart;
    // Колбэк остановки поиска/подключения
    private final Runnable onStop;
    // Колбэк входящих строк (разобранные кадры и служебные сообщения)
    private final Consumer<String> onData;
    // Колбэк ошибок
    private final Consumer<String> onError;
    // Колбэк смены статуса подключения ("connected"/"disconnected")
    private final Consumer<String> onStatus;

    // Поток для подключения и чтения входящих данных
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    // Отдельный поток для записи, чтобы чтение не блокировало отправку
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    // Планировщик для авто-подключения
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private Future<?> task;
    private Socket socket;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean destroyed = new AtomicBoolean(false);

    // Допустимые диапазоны номера локомотива и состояния
    private static final int LOCO_MIN = 1;
    private static final int LOCO_MAX = 8;
    private static final int STATE_MIN = 1;
    private static final int STATE_MAX = 6;

    // Параметры авто-подключения/таймаутов
    private static final long AUTO_RETRY_DELAY_MS = 1000;
    private static final long AUTO_RETRY_MAX_DELAY_MS = 30000;
    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS = 4000;

    // Параметры протокола
    private static final byte FRAME_START = 0x7E;
    private static final int MAX_FRAME_LENGTH = 4096;

    // Авто-подключение
    private ScheduledFuture<?> autoTask;
    private volatile boolean autoMode = false;
    private volatile boolean autoPaused = false;
    private volatile String targetHost = null;
    private volatile int targetPort = -1;
    private volatile boolean connecting = false;
    private volatile boolean searching = false;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile long nextAutoAttemptAt = 0L;
    private static final String TAG = "TcpManager";
    private volatile boolean manualDisconnectRequested = false;

    // Внутренний буфер для приёма фреймов
    private byte[] rxBuf = new byte[2048];
    private int rxSize = 0;

    // Обновление флага "идёт поиск/подключение"
    private void setSearching(boolean s) {
        if (searching == s) return;
        searching = s;
        if (s) {
            onStart.run();
        } else {
            onStop.run();
        }
    }

    // Инициализация менеджера с набором колбэков
    TcpManager(Runnable onStart,
               Runnable onStop,
               Consumer<String> onData,
               Consumer<String> onError,
               Consumer<String> onStatus) {
        this.onStart = onStart;
        this.onStop = onStop;
        this.onData = onData;
        this.onError = onError;
        this.onStatus = onStatus;
    }

    // Запрос ручного подключения к указанному хосту/порту
    // Валидация параметров, закрытие старого сокета и запуск фонового потока.
    synchronized void connect(String host, int port) {
        if (destroyed.get()) return;
        if (host == null || host.trim().isEmpty() || port < 1 || port > 65535) return;
        if (connecting) return;

        // Проверяем, что мы не уже подключены к тому же адресу
        if (isConnected()) {
            try {
                String curHost = socket.getInetAddress() != null
                        ? socket.getInetAddress().getHostAddress()
                        : null;
                int curPort = socket.getPort();
                if (curHost != null && curHost.equals(host) && curPort == port) {
                    System.out.println("[TCP][CONNECT] skip (already connected) host=" + host + " port=" + port);
                    return;
                }
            } catch (Throwable ignored) {
            }
            // Адрес/порт изменились — закрываем старый сокет
            System.out.println("[TCP][CONNECT] host/port changed oldHost=" +
                    safeHost() + " oldPort=" + safePort() +
                    " newHost=" + host + " newPort=" + port);
            disconnect();
        }

        System.out.println("[TCP][CONNECT] attempt host=" + host + " port=" + port);
        connecting = true;
        running.set(true);
        setSearching(true);

        // Запоминаем целевые параметры для авто-режима и логов
        targetHost = host;
        targetPort = port;

        if (executor.isShutdown()) {
            // Менеджер уже выключен, ничего не делаем
            connecting = false;
            running.set(false);
            setSearching(false);
            return;
        }

        task = executor.submit(() -> {
            boolean normalClose = false;
            try {
                // Создаём и подключаем новый сокет
                Socket newSock = new Socket();
                newSock.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                newSock.setSoTimeout(READ_TIMEOUT_MS);
                newSock.setTcpNoDelay(true);
                synchronized (this) {
                    socket = newSock;
                }

                setSearching(false);
                if (onStatus != null) onStatus.accept("connected");
                if (onData != null) onData.accept("[TCP] Connected to " + host + ":" + port + "\n");
                System.out.println("[TCP][CONNECT] success host=" + host + " port=" + port);
                noteAutoSuccess();

                // Читаем поток и передаём байты в парсер фреймов
                InputStream in = new BufferedInputStream(newSock.getInputStream());
                byte[] buf = new byte[512];

                while (running.get() && !destroyed.get()) {
                    try {
                        int n = in.read(buf);
                        if (n == -1) {
                            normalClose = true;
                            break;
                        }
                        if (n > 0) {
                            try {
                                System.out.println("TCP RX (" + n + " bytes): " + toHex(buf, 0, n));
                            } catch (Throwable ignored) {
                            }
                            feedRx(buf, 0, n);
                            drainFrames();
                        }
                    } catch (SocketTimeoutException timeout) {
                        if (!running.get() || destroyed.get()) break;
                    }
                }
            } catch (IOException e) {
                System.out.println("[TCP][CONNECT] error host=" + host + " port=" + port + " msg=" + e.getMessage());
                if (onData != null) onData.accept("[TCP] Connection error: " + e.getMessage() + "\n");
                if (onError != null) onError.accept(e.getMessage());
            } finally {
                // Любое завершение цикла чтения приводит к закрытию сокета и обновлению статуса
                closeQuietly();
                running.set(false);
                connecting = false;

                boolean manual = manualDisconnectRequested;
                manualDisconnectRequested = false;

                if (autoMode && !destroyed.get()) {
                    if (manual) {
                        noteManualDisconnect();
                    } else if (normalClose) {
                        noteGracefulClose();
                    } else {
                        noteAutoFailure();
                    }
                }

                if (onStatus != null) onStatus.accept("disconnected");
                if (onData != null) {
                    String hostStr = targetHost != null ? targetHost : host;
                    int portVal = targetPort > 0 ? targetPort : port;
                    onData.accept("[TCP] Disconnected from " + hostStr + ":" + portVal +
                            (normalClose ? " (normal)" : " (error)") + "\n");
                }
                System.out.println("[TCP][DISCONNECT] closed host=" + targetHost +
                        " port=" + targetPort + " normalClose=" + normalClose);
            }
        });
    }

    // Ручное отключение клиента и остановка фонового потока чтения
    synchronized void disconnect() {
        System.out.println("[TCP][DISCONNECT] manual request host=" + targetHost + " port=" + targetPort + " connected=" + isConnected());
        if (isConnected() && onData != null) {
            onData.accept("[TCP] Manual disconnect from " + targetHost + ":" + targetPort + "\n");
        }
        boolean activeTask = task != null && !task.isDone();
        if (activeTask) manualDisconnectRequested = true;
        running.set(false);
        if (task != null) task.cancel(true);
        closeQuietly();
        connecting = false;
        if (autoMode && !activeTask) {
            noteManualDisconnect();
        }
        if (onStatus != null) onStatus.accept("disconnected");
    }


    // Тихое закрытие сокета без лишних исключений в логах
    private synchronized void closeQuietly() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            socket = null;
        }
    }

    // Быстрая проверка факта подключения к сокету
    public synchronized boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    // Пассивная проверка, что соединение ещё живо
    // Не шлёт данные, опирается только на флаги сокета.
    public synchronized boolean checkConnectionAlive() {
        if (socket == null || socket.isClosed() || !socket.isConnected()) {
            return false;
        }
        return !socket.isInputShutdown() && !socket.isOutputShutdown();
    }

    // Проверка доступности конечной точки host:port отдельным коротким подключением
    boolean isEndpointReachable(int timeoutMs) {
        if (destroyed.get()) return false;
        if (isConnected()) {
            return true;
        }
        String host = targetHost;
        int port = targetPort;
        if (host == null || host.trim().isEmpty() || port < 1 || port > 65535) {
            return false;
        }

        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress(host, port), Math.max(100, timeoutMs));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // Текущий целевой host (для UI/логов)
    synchronized String getTargetHost() {
        return targetHost;
    }

    // Текущий целевой port (для UI/логов)
    synchronized int getTargetPort() {
        return targetPort;
    }

    // Упрощённый флаг активности соединения
    synchronized boolean connectionActive() {
        return isConnected();
    }

    // Сборка кадра управления: 0x7E | loco(1) | len(2 BE) | data(N) | CRC8
    byte[] buildControlFrame(int loco, int state) {
        int l = Math.max(LOCO_MIN, Math.min(LOCO_MAX, loco));
        int st = Math.max(STATE_MIN, Math.min(STATE_MAX, state));

        // Пока payload = один байт состояния
        byte[] payload = new byte[]{(byte) st};
        int len = payload.length;

        byte lenHi = (byte) ((len >> 8) & 0xFF);
        byte lenLo = (byte) (len & 0xFF);

        byte[] crcBuf = new byte[3 + len];
        crcBuf[0] = (byte) (l & 0xFF);
        crcBuf[1] = lenHi;
        crcBuf[2] = lenLo;
        if (len > 0) System.arraycopy(payload, 0, crcBuf, 3, len);

        byte crc = crc8(crcBuf, 0, crcBuf.length);

        byte[] frame = new byte[1 + crcBuf.length + 1];
        frame[0] = FRAME_START;
        System.arraycopy(crcBuf, 0, frame, 1, crcBuf.length);
        frame[frame.length - 1] = crc;
        return frame;
    }

    // Утилита: отдаёт собранный кадр в hex-виде для логов
    String controlFrameHex(int loco, int state) {
        byte[] frame = buildControlFrame(loco, state);
        return toHex(frame, 0, frame.length);
    }

    // Асинхронная отправка управляющего кадра
    // Без повторов, ошибки только логируем через onError.
    void sendControl(int loco, int state) {
        if (destroyed.get()) return;
        if (!isConnected()) return;
        if (writer.isShutdown()) return;

        final byte[] frame = buildControlFrame(loco, state);
        System.out.println("[TCP][SEND] frame: " + toHex(frame, 0, frame.length));
        writer.submit(() -> {
            try {
                Socket sck;
                synchronized (this) {
                    sck = socket;
                }
                if (sck == null || sck.isClosed() || !sck.isConnected()) {
                    System.out.println("[TCP][SEND] socket not connected");
                    return;
                }
                sck.getOutputStream().write(frame);
                sck.getOutputStream().flush();
                System.out.println("[TCP][SEND] frame sent");
            } catch (IOException e) {
                System.out.println("[TCP][SEND] error: " + e.getMessage());
                if (onError != null) onError.accept("TCP TX error: " + e.getMessage());
                running.set(false);
                closeQuietly();
            }
        });
    }

    // Включение авто-подключения с периодом 1 секунду
    void enableAutoConnect(String host, int port) {
        if (destroyed.get()) return;

        targetHost = host;
        targetPort = port;
        autoMode = true;
        autoPaused = false;
        consecutiveFailures.set(0);
        nextAutoAttemptAt = 0L;
        manualDisconnectRequested = false;

        if (autoTask != null) {
            autoTask.cancel(false);
            autoTask = null;
        }

        System.out.println("[TCP][AUTO] enable host=" + host + " port=" + port);

        if (scheduler.isShutdown()) {
            return;
        }

        autoTask = scheduler.scheduleWithFixedDelay(() -> {
            try {
                if (!autoMode || autoPaused || destroyed.get()) {
                    setSearching(false);
                    return;
                }
                long now = System.currentTimeMillis();
                if (now < nextAutoAttemptAt) {
                    setSearching(false);
                    return;
                }
                String h = targetHost;
                int p = targetPort;
                if (h == null || h.trim().isEmpty() || p < 1 || p > 65535) return;

                if (isConnected()) {
                    setSearching(false);
                    return;
                }

                if (!connecting) {
                    System.out.println("[TCP][AUTO] attempt connect host=" + h + " port=" + p);
                    setSearching(true);
                    connect(h, p);
                }
            } catch (Throwable t) {
                // намеренно подавляем любые сбои в таймере
            }
        }, AUTO_RETRY_DELAY_MS, AUTO_RETRY_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    // Полное выключение авто-подключения
    void disableAutoConnect() {
        autoMode = false;
        autoPaused = false;
        if (autoTask != null) {
            autoTask.cancel(false);
            autoTask = null;
        }
        consecutiveFailures.set(0);
        nextAutoAttemptAt = 0L;
        manualDisconnectRequested = false;
        setSearching(false);
        System.out.println("[TCP][AUTO] disabled host=" + targetHost + " port=" + targetPort);
    }

    // Пауза/возобновление авто-подключения (например, при ручной паузе в UI)
    void pauseAuto(boolean paused) {
        autoPaused = paused;
        if (paused) setSearching(false);
        System.out.println("[TCP][AUTO] pause=" + paused + " host=" + targetHost + " port=" + targetPort);
    }

    // Обновление целевого host/port без немедленного переподключения
    void updateTarget(String host, int port) {
        targetHost = host;
        targetPort = port;
    }

    // Добавление новых байтов в буфер приёма
    private void feedRx(byte[] src, int off, int len) {
        ensureCapacity(rxSize + len);
        System.arraycopy(src, off, rxBuf, rxSize, len);
        rxSize += len;
    }

    // Расширение буфера при необходимости
    private void ensureCapacity(int need) {
        if (need <= rxBuf.length) return;
        int cap = rxBuf.length;
        while (cap < need) cap *= 2;
        byte[] nb = new byte[cap];
        System.arraycopy(rxBuf, 0, nb, 0, rxSize);
        rxBuf = nb;
    }

    // Разбор буфера на кадры протокола: 0x7E | loco(1) | len(2 BE) | data(N) | CRC8
    private void drainFrames() {
        int i = 0;
        while (true) {
            // Ищем стартовый байт
            while (i < rxSize && rxBuf[i] != FRAME_START) i++;
            if (i >= rxSize) {
                rxSize = 0;
                return;
            }

            // Сдвигаем буфер так, чтобы кадр начинался с 0
            if (i > 0) {
                System.arraycopy(rxBuf, i, rxBuf, 0, rxSize - i);
                rxSize -= i;
                i = 0;
            }

            // Ждём заголовок: минимум 5 байт (1 + 1 + 2 + 1)
            if (rxSize < 5) return;

            int loco = rxBuf[1] & 0xFF;
            int len = ((rxBuf[2] & 0xFF) << 8) | (rxBuf[3] & 0xFF);
            int total = 1 + 1 + 2 + len + 1;

            // Защита от мусорной длины
            if (len < 0 || len > MAX_FRAME_LENGTH) {
                consume(1);
                continue;
            }

            // Ждём пока придут все байты кадра
            if (rxSize < total) return;

            // Проверяем CRC по loco+len+data
            byte crcExpected = rxBuf[total - 1];
            byte crc = crc8(rxBuf, 1, 3 + len);
            if (crc != crcExpected) {
                consume(1);
                continue;
            }

            // Валидный кадр
            if (len == 1) {
                int state = rxBuf[4] & 0xFF;
                String line = String.format(Locale.US,
                        "cmd=0x%02X loco=%d state=%d\n", loco, loco, state);
                safeOnData(line);
            } else {
                String line = String.format(Locale.US,
                        "cmd=0x%02X len=%d data=%s\n",
                        loco, len, toHex(rxBuf, 4, len));
                safeOnData(line);
            }

            // Съедаем обработанный кадр и продолжаем
            consume(total);
        }
    }

    // Сдвиг буфера после обработки части данных
    private void consume(int n) {
        if (n >= rxSize) {
            rxSize = 0;
            return;
        }
        System.arraycopy(rxBuf, n, rxBuf, 0, rxSize - n);
        rxSize -= n;
    }

    // Безопасный вызов onData с защитой от исключений в обработчике
    private void safeOnData(String s) {
        try {
            if (onData != null) onData.accept(s);
        } catch (Throwable ignored) {
        }
    }

    // Подсчёт CRC8 с полиномом 0x31 (как на стороне ESP)
    private static byte crc8(byte[] buf, int off, int len) {
        int crc = 0x00;
        for (int i = off; i < off + len; i++) {
            crc ^= (buf[i] & 0xFF);
            for (int b = 0; b < 8; b++) {
                if ((crc & 0x80) != 0) {
                    crc = ((crc << 1) ^ 0x31) & 0xFF;
                } else {
                    crc = (crc << 1) & 0xFF;
                }
            }
        }
        return (byte) (crc & 0xFF);
    }

    // Перевод байтов в строку вида "7E 01 00 01 FF"
    private static String toHex(byte[] buf, int off, int len) {
        StringBuilder sb = new StringBuilder(len * 3);
        for (int i = 0; i < len; i++) {
            sb.append(String.format(Locale.US, "%02X", buf[off + i] & 0xFF));
            if (i + 1 < len) sb.append(' ');
        }
        return sb.toString();
    }

    private long computeBackoffDelayMs(int attempts) {
        long shifted = AUTO_RETRY_DELAY_MS *
                (1L << Math.min(5, Math.max(0, attempts - 1)));
        return Math.min(AUTO_RETRY_MAX_DELAY_MS, shifted);
    }

    private void noteAutoSuccess() {
        consecutiveFailures.set(0);
        nextAutoAttemptAt = System.currentTimeMillis();
    }

    private void noteAutoFailure() {
        int attempts = consecutiveFailures.incrementAndGet();
        nextAutoAttemptAt =
                System.currentTimeMillis() + computeBackoffDelayMs(attempts);
    }

    private void noteGracefulClose() {
        consecutiveFailures.set(0);
        nextAutoAttemptAt =
                System.currentTimeMillis() + AUTO_RETRY_DELAY_MS;
    }

    private void noteManualDisconnect() {
        consecutiveFailures.set(0);
        nextAutoAttemptAt =
                System.currentTimeMillis() + AUTO_RETRY_DELAY_MS;
    }

    private String safeHost() {
        try {
            return socket != null && socket.getInetAddress() != null
                    ? socket.getInetAddress().getHostAddress()
                    : "null";
        } catch (Throwable t) {
            return "error";
        }
    }

    private int safePort() {
        try {
            return socket != null ? socket.getPort() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    // Отправка серверу уведомления о штатном завершении клиента
    void sendClientShutdownNotice() {
        if (!isConnected()) {
            return;
        }

        Socket sck;
        synchronized (this) {
            sck = socket;
        }
        if (sck == null || sck.isClosed() || !sck.isConnected()) {
            return;
        }

        try {
            // Текстовое уведомление для сервера
            String msg = "[CLIENT] DISCONNECT\n";
            sck.getOutputStream().write(msg.getBytes("UTF-8"));
            sck.getOutputStream().flush();
            System.out.println("[TCP][SEND] shutdown notice: " + msg);
            System.out.println("[TCP][GOODBYE] disconnect notice sent");
        } catch (Exception e) {
            // Если не получилось — просто логируем и продолжаем гаситься
            System.out.println("[TCP][GOODBYE] send failed: " + e.getMessage());
        }
    }

    // Полное завершение менеджера и фоновых потоков
    void shutdown() {
        // Отключаем авто-подключение
        disableAutoConnect();

        // Рвём текущее соединение (если есть)
        disconnect();

        // Останавливаем все executor’ы
        try { executor.shutdownNow(); } catch (Exception ignored) {}
        try { writer.shutdownNow(); } catch (Exception ignored) {}
        try { scheduler.shutdownNow(); } catch (Exception ignored) {}

        // Сбрасываем флаги состояния
        running.set(false);
        connecting = false;
        searching = false;
    }


}
