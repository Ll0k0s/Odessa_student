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

// Менеджер TCP-подключения к серверу локомотивного светофора (ЛС).
// Держит авто-подключение, регистрацию адреса локомотива и разбор пакетов, не блокируя UI.
//
// Формат пакета: DataLen(1) | Header(1) | Data(N) | XOR(1), где DataLen — длина всего пакета.
//   0x91 (клиент → сервер): ADR_MSB, ADR_LSB — регистрация адреса локомотива
//   0x92 (сервер → клиент): ADR_MSB, ADR_LSB, LTFL — значение ЛС для локомотива
//   0x93 (сервер → клиент): LTFL — ping, в ответ шлём 0x91
final class TcpManager {

    // Получатель значения ЛС из пакета 0x92
    interface SignalListener {
        void onSignal(int address, int ltfl);
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

    // Допустимый диапазон адреса локомотива
    static final int ADDRESS_MIN = 1;
    static final int ADDRESS_MAX = 9999;

    // Параметры авто-подключения/таймаутов
    private static final long AUTO_RETRY_DELAY_MS = 1000;
    private static final long AUTO_RETRY_MAX_DELAY_MS = 30000;
    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS = 4000;
    // Как часто повторяем регистрацию 0x91 для проверки связи
    private static final long REGISTER_PERIOD_MS = 30000;
    // Если от сервера ничего не приходит дольше этого времени — считаем связь потерянной.
    // Сервер отвечает на каждый 0x91, так что тишина в 3 периода означает обрыв.
    private static final long IDLE_TIMEOUT_MS = 3 * REGISTER_PERIOD_MS;

    // Заголовки пакетов протокола ЛС
    private static final int HDR_REGISTER = 0x91;
    private static final int HDR_SIGNAL = 0x92;
    private static final int HDR_PING = 0x93;
    // Допустимая длина пакета (DataLen + Header + Data + XOR)
    private static final int MIN_PACKET_LENGTH = 4;
    private static final int MAX_PACKET_LENGTH = 6;

    // Адрес локомотива, для которого запрашиваем ЛС (0 — не задан)
    private volatile int locoAddress = 0;
    private volatile SignalListener signalListener;
    // Время последнего принятого байта
    private volatile long lastRxAt = 0L;
    // Входит ли Header в XOR. Реальный сервер ЛС считает XOR только по Data
    // (как в таблице пакета 0x93), хотя общее правило спецификации включает Header.
    // Начинаем с варианта сервера и подстраиваемся по его пакетам, если он изменится.
    private volatile boolean xorWithHeader = false;

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

        // Периодическая регистрация: сервер отвечает 0x92, это же служит проверкой связи
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                if (isConnected()) sendRegister();
            } catch (Throwable ignored) {
            }
        }, REGISTER_PERIOD_MS, REGISTER_PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    void setSignalListener(SignalListener listener) {
        signalListener = listener;
    }

    // Смена адреса локомотива. Новый адрес сразу регистрируется на сервере.
    void setLocoAddress(int address) {
        if (address < ADDRESS_MIN || address > ADDRESS_MAX) return;
        if (address == locoAddress) return;
        locoAddress = address;
        System.out.println("[TCP][LS] loco address=" + address);
        if (isConnected()) sendRegister();
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

                // Новое соединение — старые недочитанные байты не нужны
                rxSize = 0;
                lastRxAt = System.currentTimeMillis();

                // Сразу регистрируем адрес локомотива, чтобы сервер прислал текущее ЛС
                sendRegister();

                // Читаем поток и передаём байты в парсер пакетов
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
                            lastRxAt = System.currentTimeMillis();
                            String hex = toHex(buf, 0, n);
                            System.out.println("[TCP][RX] " + n + " bytes: " + hex);
                            safeOnData("[#TCP_RX#]" + hex + "\n");
                            feedRx(buf, 0, n);
                            drainPackets();
                        }
                    } catch (SocketTimeoutException timeout) {
                        if (!running.get() || destroyed.get()) break;
                        long idle = System.currentTimeMillis() - lastRxAt;
                        if (idle > IDLE_TIMEOUT_MS) {
                            throw new IOException("no data from server for " + (idle / 1000) + " s");
                        }
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

    // Сборка пакета регистрации 0x91: 05 | 91 | ADR_MSB | ADR_LSB | XOR.
    // Старшие биты ADR_MSB (правило Z21 для адресов >= 128) здесь не ставятся.
    // withHeader: XOR = Header^DB0^DB1 (общее правило спецификации), иначе XOR = DB0^DB1.
    static byte[] buildRegisterPacket(int address, boolean withHeader) {
        byte[] p = new byte[5];
        p[0] = 0x05;
        p[1] = (byte) HDR_REGISTER;
        p[2] = (byte) ((address >> 8) & 0xFF);
        p[3] = (byte) (address & 0xFF);
        p[4] = withHeader ? xor(p, 1, 3) : xor(p, 2, 2);
        return p;
    }

    // Регистрация текущего адреса локомотива на сервере
    void sendRegister() {
        int address = locoAddress;
        if (address < ADDRESS_MIN || address > ADDRESS_MAX) return;
        sendPacket(buildRegisterPacket(address, xorWithHeader));
    }

    // Асинхронная отправка пакета. Без повторов, ошибки только логируем через onError.
    private void sendPacket(byte[] frame) {
        if (destroyed.get()) return;
        if (!isConnected()) return;
        if (writer.isShutdown()) return;

        String hex = toHex(frame, 0, frame.length);
        System.out.println("[TCP][SEND] frame: " + hex);
        writer.submit(() -> {
            try {
                Socket sck;
                synchronized (this) {
                    sck = socket;
                }
                if (sck == null || sck.isClosed() || !sck.isConnected()) {
                    System.out.println("[TCP][SEND] socket not connected");
                    safeOnData("TX не відправлено (немає з'єднання): " + hex + "\n");
                    return;
                }
                sck.getOutputStream().write(frame);
                sck.getOutputStream().flush();
                System.out.println("[TCP][SEND] frame sent");
                // В консоль пишем только после реальной записи в сокет
                safeOnData("[#TCP_TX#]" + hex + "\n");
            } catch (IOException e) {
                System.out.println("[TCP][SEND] error: " + e.getMessage());
                safeOnData("TX помилка: " + hex + " — " + e.getMessage() + "\n");
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

    // Длина пакета для известного заголовка, или -1 если заголовок неизвестен
    private static int expectedLength(int header) {
        switch (header) {
            case HDR_REGISTER: return 5;
            case HDR_SIGNAL: return 6;
            case HDR_PING: return 4;
            default: return -1;
        }
    }

    // Разбор буфера на пакеты ЛС: DataLen | Header | Data(N) | XOR.
    // Маркера начала в протоколе нет, поэтому начало пакета узнаём по паре DataLen + Header:
    // всё, что не похоже на известный пакет, пропускаем по одному байту.
    private void drainPackets() {
        while (rxSize > 0) {
            int len = rxBuf[0] & 0xFF;
            if (len < MIN_PACKET_LENGTH || len > MAX_PACKET_LENGTH) {
                consume(1);
                continue;
            }

            // Ждём заголовок
            if (rxSize < 2) return;
            if (expectedLength(rxBuf[1] & 0xFF) != len) {
                consume(1);
                continue;
            }

            // Ждём, пока придёт весь пакет
            if (rxSize < len) return;

            int variant = checksumVariant(rxBuf, len);
            if (variant == XOR_BAD) {
                System.out.println("[TCP][RX] bad XOR: " + toHex(rxBuf, 0, len));
                consume(1);
                continue;
            }

            // Считаем XOR так же, как сервер: по его пакетам видно, входит ли Header в сумму
            boolean serverWithHeader = variant == XOR_WITH_HEADER;
            if (serverWithHeader != xorWithHeader) {
                xorWithHeader = serverWithHeader;
                String mode = serverWithHeader ? "Header^Data" : "тільки Data";
                System.out.println("[TCP][LS] server XOR mode: " + mode);
                safeOnData("ЛС: сервер рахує XOR як " + mode + ", відправляю так само\n");
                boolean isPing = (rxBuf[1] & 0xFF) == HDR_PING;
                handlePacket(rxBuf, len);
                consume(len);
                // Прежние 0x91 сервер мог отбросить — регистрируемся заново
                // (на ping handlePacket уже ответил регистрацией)
                if (!isPing) sendRegister();
                continue;
            }

            handlePacket(rxBuf, len);
            consume(len);
        }
    }

    private static final int XOR_BAD = 0;
    private static final int XOR_WITH_HEADER = 1;
    private static final int XOR_DATA_ONLY = 2;

    // Как посчитан XOR в пакете. По общему правилу спецификации это Header^Data,
    // но в таблице пакета 0x93 указано "XOR = DB0" (без Header) — принимаем оба варианта.
    // Header всегда ненулевой, поэтому оба варианта сразу совпасть не могут.
    private static int checksumVariant(byte[] p, int len) {
        byte expected = p[len - 1];
        int dataCount = len - 3;
        if (xor(p, 1, dataCount + 1) == expected) return XOR_WITH_HEADER;
        if (dataCount > 0 && xor(p, 2, dataCount) == expected) return XOR_DATA_ONLY;
        return XOR_BAD;
    }

    // Обработка одного проверенного пакета
    private void handlePacket(byte[] p, int len) {
        int header = p[1] & 0xFF;
        if (header == HDR_SIGNAL) {
            // Старшие биты адреса по протоколу не используются, но маскируем на всякий случай
            int address = (((p[2] & 0xFF) << 8) | (p[3] & 0xFF)) & 0x3FFF;
            int ltfl = p[4] & 0xFF;
            System.out.println("[TCP][LS] signal address=" + address + " ltfl=" + ltfl);
            safeOnData(String.format(Locale.US, "ЛС: адреса=%d LTFL=%d (%s)\n",
                    address, ltfl, ltflName(ltfl)));
            SignalListener l = signalListener;
            if (l != null) {
                try {
                    l.onSignal(address, ltfl);
                } catch (Throwable ignored) {
                }
            }
        } else if (header == HDR_PING) {
            safeOnData("ЛС: ping\n");
            sendRegister();
        }
        // 0x91 сервер не шлёт — игнорируем
    }

    static String ltflName(int ltfl) {
        switch (ltfl) {
            case 0: return "не визначено";
            case 1: return "зелений";
            case 2: return "жовтий";
            case 3: return "жовтий з червоним";
            case 4: return "червоний";
            case 5: return "білий";
            default: return "?";
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

    // XOR байтов buf[off .. off+len-1]
    private static byte xor(byte[] buf, int off, int len) {
        int x = 0;
        for (int i = off; i < off + len; i++) {
            x ^= (buf[i] & 0xFF);
        }
        return (byte) x;
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
