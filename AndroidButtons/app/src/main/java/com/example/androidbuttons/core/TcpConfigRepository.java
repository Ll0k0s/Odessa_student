package com.example.androidbuttons.core;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.util.concurrent.CopyOnWriteArrayList;

import static com.example.androidbuttons.AppState.DEFAULT_TCP_HOST;
import static com.example.androidbuttons.AppState.DEFAULT_TCP_PORT;
import static com.example.androidbuttons.AppState.KEY_TCP_HOST;
import static com.example.androidbuttons.AppState.KEY_TCP_PORT;
import static com.example.androidbuttons.core.ProtocolConstraints.clampLoco;

/**
 * Stores TCP host/port and currently selected locomotive number.
 */
public final class TcpConfigRepository {

    private static final String PREFS_NAME = "tcp_config";
    private static final String KEY_LOCO = "tcp_loco";

    private static final int DEFAULT_LOCO = ProtocolConstraints.LOCO_MIN;

    private final SharedPreferences prefs;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    TcpConfigRepository(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public TcpConfig get() {
        String host = prefs.getString(KEY_TCP_HOST, DEFAULT_TCP_HOST);
        int port = prefs.getInt(KEY_TCP_PORT, DEFAULT_TCP_PORT);
        int loco = prefs.getInt(KEY_LOCO, DEFAULT_LOCO);
        return new TcpConfig(host, port, loco);
    }

    public void updateHostAndPort(@NonNull String host, int port) {
        String normalizedHost = host == null ? DEFAULT_TCP_HOST : host.trim();
        int safePort = port < 1 || port > 65535 ? DEFAULT_TCP_PORT : port;
        TcpConfig current = get();
        if (current.host.equals(normalizedHost) && current.port == safePort) {
            return;
        }
        prefs.edit()
                .putString(KEY_TCP_HOST, normalizedHost)
                .putInt(KEY_TCP_PORT, safePort)
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
