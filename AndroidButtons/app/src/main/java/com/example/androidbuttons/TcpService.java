package com.example.androidbuttons;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;

import com.example.androidbuttons.core.AppGraph;
import com.example.androidbuttons.core.ConsoleLogRepository;
import com.example.androidbuttons.core.TcpConfigRepository;
import com.example.androidbuttons.core.TcpStatusStore;

/**
 * Background service that owns TcpManager lifecycle and exposes it to bound clients.
 */
public class TcpService extends Service {

    public interface TcpDataListener {
        void onTcpData(String line);
    }

    private static final long HEALTH_INTERVAL_MS = 1000L;

    private final IBinder binder = new LocalBinder();
    private Handler mainHandler;
    private TcpManager tcpManager;
    private TcpConfigRepository tcpConfigRepository;
    private TcpConfigRepository.TcpConfig currentConfig;
    private TcpConfigRepository.Listener configListener;
    private TcpStatusStore tcpStatusStore;
    private ConsoleLogRepository consoleLogRepository;
    private TcpDataListener dataListener;
    private Runnable healthRunnable;
    private String activeHost;
    private int activePort;

    public class LocalBinder extends Binder {
        public TcpService getService() {
            return TcpService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        AppGraph graph = AppGraph.get();
        tcpConfigRepository = graph.tcpConfig();
        tcpStatusStore = graph.tcpStatuses();
        consoleLogRepository = graph.consoleLog();
        initTcpManager();

        currentConfig = tcpConfigRepository.get();
        applyConfig(currentConfig);

        configListener = config -> mainHandler.post(() -> applyConfig(config));
        tcpConfigRepository.addListener(configListener);

        healthRunnable = new Runnable() {
            @Override
            public void run() {
                boolean alive = tcpManager != null && tcpManager.checkConnectionAlive();
                TcpStatusStore.TcpStatus status = tcpStatusStore.get();
                if (status.reachable != alive) {
                    tcpStatusStore.update(status.withReachable(alive));
                }
                mainHandler.postDelayed(this, HEALTH_INTERVAL_MS);
            }
        };
        mainHandler.postDelayed(healthRunnable, HEALTH_INTERVAL_MS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensureAutoConnect();
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        ensureAutoConnect();
        return binder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (healthRunnable != null) {
            mainHandler.removeCallbacks(healthRunnable);
        }
        if (tcpManager != null) {
            tcpManager.disableAutoConnect();
            tcpManager.shutdown();
        }
        if (configListener != null && tcpConfigRepository != null) {
            tcpConfigRepository.removeListener(configListener);
        }
    }

    public void setTcpDataListener(@Nullable TcpDataListener listener) {
        dataListener = listener;
    }

    public boolean sendControl(int loco, int state) {
        if (tcpManager == null || !tcpManager.connectionActive()) {
            return false;
        }
        tcpManager.sendControl(loco, state);
        return true;
    }

    private void initTcpManager() {
        tcpManager = new TcpManager(
                () -> postStatus(TcpStatusStore.TcpStatus.connecting()),
                () -> postStatus(TcpStatusStore.TcpStatus.disconnected()),
                this::dispatchTcpData,
                error -> consoleLogRepository.append("[#TCP_ERR#]" + error + "\n"),
                status -> {
                    if ("connected".equals(status)) {
                        postStatus(TcpStatusStore.TcpStatus.connected());
                    } else {
                        postStatus(TcpStatusStore.TcpStatus.disconnected());
                    }
                    consoleLogRepository.append("[#TCP_STATUS#]" + status + "\n");
                }
        );
    }

    private void dispatchTcpData(String line) {
        consoleLogRepository.append(line);
        TcpDataListener listener = dataListener;
        if (listener != null) {
            mainHandler.post(() -> listener.onTcpData(line));
        }
    }

    private void applyConfig(@Nullable TcpConfigRepository.TcpConfig config) {
        if (config == null || tcpManager == null) {
            return;
        }
        currentConfig = config;
        String host = normalizeHost(config.host);
        int port = normalizePort(config.port);
        boolean firstStart = activeHost == null;
        boolean changed = !host.equals(activeHost) || port != activePort;
        activeHost = host;
        activePort = port;
        if (firstStart || changed) {
            tcpManager.disableAutoConnect();
            tcpManager.disconnect();
            tcpManager.enableAutoConnect(host, port);
        } else {
            tcpManager.updateTarget(host, port);
        }
    }

    private void ensureAutoConnect() {
        if (tcpManager == null || activeHost == null) {
            return;
        }
        tcpManager.enableAutoConnect(activeHost, activePort);
    }

    private void postStatus(TcpStatusStore.TcpStatus status) {
        mainHandler.post(() -> tcpStatusStore.update(status));
    }

    private String normalizeHost(String host) {
        return (host == null || host.trim().isEmpty()) ? "192.168.2.6" : host.trim();
    }

    private int normalizePort(int port) {
        return port <= 0 ? 9000 : port;
    }
}
