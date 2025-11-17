package com.example.androidbuttons;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.androidbuttons.core.AppContracts;
import com.example.androidbuttons.core.AppGraph;
import com.example.androidbuttons.core.ConsoleLogRepository;
import com.example.androidbuttons.core.OverlaySettingsRepository;
import com.example.androidbuttons.core.OverlayStateStore;
import com.example.androidbuttons.core.TcpConfigRepository;
import com.example.androidbuttons.core.TcpStatusStore;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends AppCompatActivity {

    public static final String EXTRA_OPEN_SETTINGS = "com.example.androidbuttons.EXTRA_OPEN_SETTINGS";
    public static final String EXTRA_HIDE_AFTER_BOOT = "com.example.androidbuttons.EXTRA_HIDE_AFTER_BOOT";

    private static final String TAG_SERVICE = "MainActivitySvc";
    private static final String DEFAULT_HOST = "192.168.2.6";
    private static final int DEFAULT_PORT = 9000;
    private static final int REQUEST_OVERLAY_PERMISSION = 1001;

    private TcpManager tcpManager;
    private DataBuffer uiBuffer;
    private ActivityResultLauncher<Intent> settingsLauncher;
    private boolean overlayPermissionRequested = false;
    private int currentState = 0;
    private boolean settingsLaunched = false;
    private java.util.Timer tcpStatusTimer;
    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();
    private Boolean lastStatusConnected = null;

    private OverlaySettingsRepository overlaySettingsRepository;
    private OverlaySettingsRepository.OverlaySettings overlaySettings;
    private OverlayStateStore overlayStateStore;
    private TcpConfigRepository tcpConfigRepository;
    private TcpConfigRepository.TcpConfig currentTcpConfig;
    private final AtomicInteger selectedLoco = new AtomicInteger(1);
    private TcpStatusStore tcpStatusStore;
    private ConsoleLogRepository consoleLogRepository;

    private final OverlayStateStore.SelectionListener overlaySelectionListener = state ->
            runOnUiThread(() -> handleOverlaySelection(state));

    private final OverlaySettingsRepository.Listener overlaySettingsListener = settings ->
            runOnUiThread(() -> overlaySettings = settings);

    private final TcpConfigRepository.Listener tcpConfigListener = config ->
            runOnUiThread(() -> applyTcpConfig(config, true));

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        settingsLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    settingsLaunched = false;
                    if (!isFinishing()) {
                        moveTaskToBack(true);
                    }
                }
        );

        AppGraph graph = AppGraph.get();
        overlaySettingsRepository = graph.overlaySettings();
        overlaySettings = overlaySettingsRepository.get();
        overlayStateStore = graph.overlayStates();
        tcpConfigRepository = graph.tcpConfig();
        currentTcpConfig = tcpConfigRepository.get();
        selectedLoco.set(currentTcpConfig.selectedLoco);
        tcpStatusStore = graph.tcpStatuses();
        consoleLogRepository = graph.consoleLog();

        overlaySettingsRepository.addListener(overlaySettingsListener);
        tcpConfigRepository.addListener(tcpConfigListener);

        uiBuffer = new DataBuffer(256, consoleLogRepository::append);

        tcpManager = new TcpManager(
                () -> runOnUiThread(() -> tcpStatusStore.update(TcpStatusStore.TcpStatus.connecting())),
                () -> runOnUiThread(() -> tcpStatusStore.update(TcpStatusStore.TcpStatus.disconnected())),
                data -> handleTcpPayload(data, selectedLoco.get()),
                error -> {
                    // suppress UI noise
                },
                status -> runOnUiThread(() -> handleTcpStatus(status))
        );

        applyTcpConfig(currentTcpConfig, true);

        updateStateFromExternal(1);
        overlayStateStore.publishSelection(1);
        Log.d(AppContracts.LOG_TAG_MAIN, "Initial state set to 1 (green)");

        tcpStatusTimer = new java.util.Timer();
        tcpStatusTimer.scheduleAtFixedRate(new java.util.TimerTask() {
            @Override
            public void run() {
                performTcpHealthCheck("tick");
            }
        }, 1000, 1000);
    }

    private void handleTcpPayload(String data, int locoTarget) {
        if (data == null || data.isEmpty()) {
            return;
        }
        String[] lines = data.split("\n");
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            String ln = line.trim();
            if (ln.isEmpty()) {
                continue;
            }

            if (ln.startsWith("[TCP]")) {
                uiBuffer.offer(ln + "\n");
                continue;
            }

            int locoVal = extractDecimal(ln, "loco=");
            if (locoVal <= 0 || locoVal != locoTarget) {
                continue;
            }
            int stateVal = extractDecimal(ln, "state=");
            if (stateVal < 1 || stateVal > 5) {
                continue;
            }

            uiBuffer.offer("[#TCP_RX#]" + "Rx: loco" + locoVal + " -> state" + stateVal + "\n");
            final int stateCopy = stateVal;
            runOnUiThread(() -> updateStateFromExternal(stateCopy));
        }
    }

    private void performTcpHealthCheck(String phase) {
        if (Looper.getMainLooper().isCurrentThread()) {
            runOffUi(() -> performTcpHealthCheck(phase + "_bg"));
            return;
        }
        boolean connectionAlive = tcpManager.checkConnectionAlive();
        TcpStatusStore.TcpStatus currentStatus = tcpStatusStore.get();
        if (currentStatus.reachable != connectionAlive) {
            tcpStatusStore.update(currentStatus.withReachable(connectionAlive));
        }
    }

    private void runOffUi(Runnable r) {
        bgExecutor.execute(r);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (tcpStatusTimer != null) {
            tcpStatusTimer.cancel();
            tcpStatusTimer = null;
        }

        if (tcpManager != null) {
            tcpManager.shutdown();
        }

        bgExecutor.shutdownNow();
        uiBuffer.close();

        overlaySettingsRepository.removeListener(overlaySettingsListener);
        tcpConfigRepository.removeListener(tcpConfigListener);

        try {
            Intent svcIntent = new Intent(this, FloatingOverlayService.class);
            stopService(svcIntent);
        } catch (Exception ignored) {
            // no-op
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        ensureOverlayServiceRunning();
        overlayStateStore.addSelectionListener(overlaySelectionListener);
        if (currentState > 0) {
            overlayStateStore.publish(currentState);
        }
        applyTcpConfig(currentTcpConfig, false);
        processLaunchFlags(getIntent(), false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        overlayStateStore.removeSelectionListener(overlaySelectionListener);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        processLaunchFlags(intent, true);
    }

    private void processLaunchFlags(@Nullable Intent sourceIntent, boolean fromNewIntent) {
        if (sourceIntent == null) {
            return;
        }
        boolean openRequested = sourceIntent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false);
        boolean hideRequested = sourceIntent.getBooleanExtra(EXTRA_HIDE_AFTER_BOOT, false);

        if (openRequested) {
            sourceIntent.removeExtra(EXTRA_OPEN_SETTINGS);
            settingsLaunched = true;
            launchSettingsActivity();
            return;
        }

        if (hideRequested) {
            sourceIntent.removeExtra(EXTRA_HIDE_AFTER_BOOT);
            settingsLaunched = false;
            moveTaskToBack(true);
            return;
        }

        if (!fromNewIntent && !settingsLaunched) {
            settingsLaunched = true;
            launchSettingsActivity();
        }
    }

    private void launchSettingsActivity() {
        Intent intent = new Intent(this, SettingsActivity.class);
        if (settingsLauncher != null) {
            settingsLauncher.launch(intent);
        } else {
            startActivity(intent);
        }
        overridePendingTransition(0, 0);
    }

    private void handleOverlaySelection(int state) {
        if (state < 1 || state > 5) {
            return;
        }
        applyStripState(state, true);
    }

    private void applyStripState(int state, boolean sendCommands) {
        if (state < 1 || state > 5) {
            return;
        }
        if (isOverlayInEditMode()) {
            Log.d(TAG_SERVICE, "applyStripState ignored in edit mode state=" + state);
            return;
        }
        currentState = state;
        if (sendCommands) {
            sendExclusiveRelays(state);
        }
        overlayStateStore.publish(state);
    }

    private void updateStateFromExternal(int state) {
        if (state < 1 || state > 5 || state == currentState) {
            return;
        }
        if (isOverlayInEditMode()) {
            Log.d(TAG_SERVICE, "External state update ignored (edit mode) state=" + state);
            return;
        }
        currentState = state;
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
        int pos = idx + token.length();
        int value = 0;
        boolean has = false;
        while (pos < line.length()) {
            char c = line.charAt(pos);
            if (c >= '0' && c <= '9') {
                value = value * 10 + (c - '0');
                has = true;
                pos++;
            } else {
                break;
            }
        }
        return has ? value : -1;
    }

    private void ensureOverlayServiceRunning() {
        try {
            Context appCtx = getApplicationContext();
            if (!canDrawOverlays()) {
                Log.w(TAG_SERVICE, "Overlay permission missing, requesting");
                requestOverlayPermission();
                return;
            }

            overlayPermissionRequested = false;

            Intent svcIntent = new Intent(appCtx, FloatingOverlayService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appCtx.startForegroundService(svcIntent);
            } else {
                appCtx.startService(svcIntent);
            }
            Log.i(TAG_SERVICE, "FloatingOverlayService start requested");
        } catch (Exception ex) {
            Log.e(TAG_SERVICE, "Failed to start FloatingOverlayService", ex);
        }
    }

    private boolean canDrawOverlays() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        return Settings.canDrawOverlays(this);
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        if (overlayPermissionRequested) {
            Log.i(TAG_SERVICE, "Overlay permission dialog already shown");
            return;
        }
        overlayPermissionRequested = true;
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION);
            Log.i(TAG_SERVICE, "Requesting overlay permission via settings");
        } catch (ActivityNotFoundException ex) {
            overlayPermissionRequested = false;
            Log.e(TAG_SERVICE, "Overlay permission settings unavailable", ex);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            overlayPermissionRequested = false;
            if (canDrawOverlays()) {
                Log.i(TAG_SERVICE, "Overlay permission granted");
                ensureOverlayServiceRunning();
            } else {
                Log.w(TAG_SERVICE, "Overlay permission still missing after user interaction");
            }
        }
    }

    private void sendExclusiveRelays(int active) {
        int loco = selectedLoco.get();
        int state = Math.max(1, Math.min(5, active));
        tcpManager.sendControl(loco, state);
        if (tcpManager.connectionActive()) {
            uiBuffer.offer("[#TCP_TX#]" + "Tx: loco" + loco + " -> state" + state + "\n");
        }
    }

    private void applyTcpConfig(@Nullable TcpConfigRepository.TcpConfig config, boolean restart) {
        if (config == null) {
            return;
        }
        currentTcpConfig = config;
        selectedLoco.set(config.selectedLoco);
        String host = config.host != null ? config.host.trim() : "";
        if (host.isEmpty()) {
            host = DEFAULT_HOST;
        }
        int port = config.port <= 0 ? DEFAULT_PORT : config.port;
        if (restart) {
            tcpManager.disableAutoConnect();
            tcpManager.disconnect();
            tcpManager.enableAutoConnect(host, port);
        } else {
            tcpManager.updateTarget(host, port);
        }
    }

    private boolean isOverlayInEditMode() {
        return overlaySettings != null && overlaySettings.editAllowed;
    }

    private void handleTcpStatus(String status) {
        boolean connected = "connected".equals(status);
        tcpStatusStore.update(connected
                ? TcpStatusStore.TcpStatus.connected()
                : TcpStatusStore.TcpStatus.disconnected());
        if (lastStatusConnected == null || !lastStatusConnected.equals(connected)) {
            lastStatusConnected = connected;
            if (connected) {
                uiBuffer.offer("[#TCP_STATUS#]TCP connected\n");
            } else {
                uiBuffer.offer("[#TCP_STATUS#]TCP disconnected\n");
            }
        }
    }
}
