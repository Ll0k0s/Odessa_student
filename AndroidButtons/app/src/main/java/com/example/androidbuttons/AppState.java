package com.example.androidbuttons;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.LinkedBlockingQueue;

public final class AppState {

    // Запрещаем создавать экземпляры
    private AppState() {}

    // Текущий выбранный локомотив (1..8)
    public static final AtomicInteger selectedLoco = new AtomicInteger(1);

    // Очередь лога между сервисами и экраном настроек
    public static final LinkedBlockingQueue<String> consoleQueue = new LinkedBlockingQueue<>();

    // Имя SharedPreferences
    public static final String PREFS_NAME = "androidbuttons_prefs";

    // Ключи настроек TCP
    public static final String KEY_TCP_HOST = "tcp_host";
    public static final String KEY_TCP_PORT = "tcp_port";

    // TCP по умолчанию (для инициализации)
    public static final String DEFAULT_TCP_HOST = "192.168.2.6";
    public static final int DEFAULT_TCP_PORT = 9000;

    // Ключи позиции и масштаба overlay
    public static final String KEY_OVERLAY_X = "overlay_x";
    public static final String KEY_OVERLAY_Y = "overlay_y";
    public static final String KEY_OVERLAY_SCALE = "overlay_scale";
    public static final String KEY_OVERLAY_ALLOW_MODIFICATION = "overlay_allow_modification";

    // Action для обновления overlay из сервиса
    public static final String ACTION_OVERLAY_UPDATED = "com.example.androidbuttons.OVERLAY_UPDATED";

    // Флаги состояния TCP
    public static volatile boolean tcpConnecting = false;
    public static volatile boolean tcpConnected = false;
    public static volatile boolean tcpReachable = false;
}
