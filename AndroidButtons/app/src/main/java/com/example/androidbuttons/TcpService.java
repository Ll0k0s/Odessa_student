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
import com.example.androidbuttons.core.OverlaySettingsRepository;
import com.example.androidbuttons.core.OverlayStateStore;
import com.example.androidbuttons.core.ProtocolConstraints;
import com.example.androidbuttons.core.TcpConfigRepository;
import com.example.androidbuttons.core.TcpStatusStore;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Background service that owns TcpManager lifecycle and exposes it to bound clients.
 */
public class TcpService extends Service {

    private static final long HEALTH_INTERVAL_MS = 1000L;

    private final IBinder binder = new LocalBinder();
    private Handler mainHandler;
    private TcpManager tcpManager;
    private TcpConfigRepository tcpConfigRepository;
    private TcpConfigRepository.TcpConfig currentConfig;
    private TcpConfigRepository.Listener configListener;
    private TcpStatusStore tcpStatusStore;
    private ConsoleLogRepository consoleLogRepository;
    private OverlayStateStore overlayStateStore;
    private OverlayStateStore.SelectionListener overlaySelectionListener;
    private OverlaySettingsRepository overlaySettingsRepository;
    private OverlaySettingsRepository.Listener overlaySettingsListener;
    private OverlaySettingsRepository.OverlaySettings overlaySettings;
    private Runnable healthRunnable;
    private String activeHost;
    private int activePort;
    private final AtomicInteger selectedState = new AtomicInteger(ProtocolConstraints.STATE_MIN);
    private final AtomicInteger selectedLoco = new AtomicInteger(ProtocolConstraints.LOCO_MIN);

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
        overlaySettingsRepository = graph.overlaySettings();
        overlaySettings = overlaySettingsRepository.get();
        overlaySettingsListener = settings -> mainHandler.post(() -> overlaySettings = settings);
        overlaySettingsRepository.addListener(overlaySettingsListener);
        overlayStateStore = graph.overlayStates();
        overlaySelectionListener = state -> mainHandler.post(() -> handleOverlaySelection(state));
        overlayStateStore.addSelectionListener(overlaySelectionListener);
        int initialState = overlayStateStore.getCurrentState();
        if (!ProtocolConstraints.isValidState(initialState)) {
            initialState = ProtocolConstraints.STATE_MIN;
        }
        selectedState.set(initialState);
        overlayStateStore.publish(initialState);
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
        if (overlaySelectionListener != null && overlayStateStore != null) {
            overlayStateStore.removeSelectionListener(overlaySelectionListener);
        }
        if (overlaySettingsListener != null && overlaySettingsRepository != null) {
            overlaySettingsRepository.removeListener(overlaySettingsListener);
        }
    }

    public boolean sendControl(int loco, int state) {
        if (tcpManager == null || !tcpManager.connectionActive()) {
            return false;
        }
        int normalizedLoco = ProtocolConstraints.clampLoco(loco);
        int normalizedState = ProtocolConstraints.clampState(state);
        tcpManager.sendControl(normalizedLoco, normalizedState);
        consoleLogRepository.append("[#TCP_TX#]Tx: loco" + normalizedLoco + " -> state" + normalizedState + "\n");
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
        processTcpPayload(line);
    }

    private void applyConfig(@Nullable TcpConfigRepository.TcpConfig config) {
        if (config == null || tcpManager == null) {
            return;
        }
        currentConfig = config;
        selectedLoco.set(ProtocolConstraints.clampLoco(config.selectedLoco));
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

    private void handleOverlaySelection(int state) {
        if (!ProtocolConstraints.isValidState(state) || overlayStateStore == null) {
            return;
        }
        if (isOverlayInEditMode()) {
            consoleLogRepository.append("[#TCP_EDIT#]Overlay selection ignored (edit mode)\n");
            return;
        }
        int normalized = ProtocolConstraints.clampState(state);
        selectedState.set(normalized);
        overlayStateStore.publish(normalized);
        boolean sent = sendControl(selectedLoco.get(), normalized);
        if (!sent) {
            consoleLogRepository.append("[#TCP_WARN#]Control not sent (connection inactive)\n");
        }
    }

    private void processTcpPayload(@Nullable String data) {
        if (data == null || data.isEmpty()) {
            return;
        }
        int locoTarget = selectedLoco.get();
        String[] lines = data.split("\n");
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("[TCP]")) {
                continue;
            }
            int locoVal = extractDecimal(trimmed, "loco=");
            if (!ProtocolConstraints.isValidLoco(locoVal) || locoVal != locoTarget) {
                continue;
            }
            int stateVal = extractDecimal(trimmed, "state=");
            if (!ProtocolConstraints.isValidState(stateVal)) {
                continue;
            }
            consoleLogRepository.append("[#TCP_RX#]Rx: loco" + locoVal + " -> state" + stateVal + "\n");
            int normalized = ProtocolConstraints.clampState(stateVal);
            mainHandler.post(() -> handleRemoteState(normalized));
        }
    }

    private void handleRemoteState(int state) {
        if (overlayStateStore == null || !ProtocolConstraints.isValidState(state)) {
            return;
        }
        if (isOverlayInEditMode()) {
            consoleLogRepository.append("[#TCP_RX#]Remote state ignored (edit mode)\n");
            return;
        }
        selectedState.set(state);
        overlayStateStore.publish(state);
    }

    private int extractDecimal(String line, String token) {
        if (line == null || token == null) {
            return -1;
        }
        int idx = line.indexOf(token);
        if (idx < 0) {
            return -1;
        }
        int cursor = idx + token.length();
        int value = 0;
        boolean found = false;
        while (cursor < line.length()) {
            char c = line.charAt(cursor);
            if (c >= '0' && c <= '9') {
                value = value * 10 + (c - '0');
                found = true;
                cursor++;
            } else {
                break;
            }
        }
        return found ? value : -1;
    }

    private boolean isOverlayInEditMode() {
        return overlaySettings != null && overlaySettings.editModeEnabled;
    }
}
