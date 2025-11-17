package com.example.androidbuttons.core;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.util.concurrent.CopyOnWriteArrayList;

import static com.example.androidbuttons.core.ProtocolConstraints.clampLoco;

/**
 * Stores TCP host/port and currently selected locomotive number.
 */
public final class TcpConfigRepository {

    private static final String PREFS_NAME = "tcp_config";
    private static final String KEY_HOST = "tcp_host";
    private static final String KEY_PORT = "tcp_port";
    private static final String KEY_LOCO = "tcp_loco";

    private static final String DEFAULT_HOST = "192.168.2.6";
    private static final int DEFAULT_PORT = 9000;
    private static final int DEFAULT_LOCO = ProtocolConstraints.LOCO_MIN;

    private final SharedPreferences prefs;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    TcpConfigRepository(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public TcpConfig get() {
        String host = prefs.getString(KEY_HOST, DEFAULT_HOST);
        int port = prefs.getInt(KEY_PORT, DEFAULT_PORT);
        int loco = prefs.getInt(KEY_LOCO, DEFAULT_LOCO);
        return new TcpConfig(host, port, loco);
    }

    public void updateHostAndPort(@NonNull String host, int port) {
        String normalizedHost = host == null ? DEFAULT_HOST : host.trim();
        int safePort = port < 1 || port > 65535 ? DEFAULT_PORT : port;
        TcpConfig current = get();
        if (current.host.equals(normalizedHost) && current.port == safePort) {
            return;
        }
        prefs.edit()
                .putString(KEY_HOST, normalizedHost)
                .putInt(KEY_PORT, safePort)
                .apply();
        notifyListeners(get());
    }

    public void setSelectedLoco(int loco) {
        int clamped = clampLoco(loco);
        if (prefs.getInt(KEY_LOCO, DEFAULT_LOCO) == clamped) {
            return;
        }
        prefs.edit().putInt(KEY_LOCO, clamped).apply();
        notifyListeners(get());
    }

    public void addListener(@NonNull Listener listener) {
        listeners.addIfAbsent(listener);
    }

    public void removeListener(@NonNull Listener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners(TcpConfig config) {
        for (Listener listener : listeners) {
            listener.onTcpConfigChanged(config);
        }
    }

    public interface Listener {
        void onTcpConfigChanged(TcpConfig config);
    }

    public static final class TcpConfig {
        public final String host;
        public final int port;
        public final int selectedLoco;

        public TcpConfig(String host, int port, int selectedLoco) {
            this.host = host;
            this.port = port;
            this.selectedLoco = selectedLoco;
        }
    }
}
