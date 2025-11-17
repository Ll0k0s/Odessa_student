package com.example.androidbuttons.core;

import android.content.Context;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;

/**
 * Central place that wires repositories/services available across the app.
 * Keeps initialization logic away from random singletons.
 */
public final class AppGraph {

    private static volatile AppGraph instance;

    private final OverlaySettingsRepository overlaySettingsRepository;
    private final TcpConfigRepository tcpConfigRepository;
    private final ConsoleLogRepository consoleLogRepository;
    private final OverlayStateStore overlayStateStore;
    private final TcpStatusStore tcpStatusStore;

    private AppGraph(Context appContext) {
        overlaySettingsRepository = new OverlaySettingsRepository(appContext);
        tcpConfigRepository = new TcpConfigRepository(appContext);
        consoleLogRepository = new ConsoleLogRepository();
        overlayStateStore = new OverlayStateStore();
        tcpStatusStore = new TcpStatusStore();
    }

    @MainThread
    public static synchronized void initialize(@NonNull Context appContext) {
        if (instance == null) {
            Context safeContext = appContext.getApplicationContext();
            instance = new AppGraph(safeContext);
        }
    }

    public static AppGraph get() {
        AppGraph local = instance;
        if (local == null) {
            throw new IllegalStateException("AppGraph is not initialized. Call AppGraph.initialize() in Application.onCreate()");
        }
        return local;
    }

    public OverlaySettingsRepository overlaySettings() {
        return overlaySettingsRepository;
    }

    public TcpConfigRepository tcpConfig() {
        return tcpConfigRepository;
    }

    public ConsoleLogRepository consoleLog() {
        return consoleLogRepository;
    }

    public OverlayStateStore overlayStates() {
        return overlayStateStore;
    }

    public TcpStatusStore tcpStatuses() {
        return tcpStatusStore;
    }
}
