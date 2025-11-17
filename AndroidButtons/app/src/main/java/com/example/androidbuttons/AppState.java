package com.example.androidbuttons;

public final class AppState {

    // Запрещаем создавать экземпляры
    private AppState() {}

    // Имя SharedPreferences
    public static final String PREFS_NAME = "androidbuttons_prefs";

    // Ключи настроек TCP
    public static final String KEY_TCP_HOST = "tcp_host";
    public static final String KEY_TCP_PORT = "tcp_port";
    public static final String DEFAULT_TCP_HOST = "192.168.2.6";
    public static final int DEFAULT_TCP_PORT = 9000;
}
