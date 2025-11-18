package com.example.androidbuttons;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    public static final String EXTRA_OPEN_SETTINGS = "com.example.androidbuttons.EXTRA_OPEN_SETTINGS";
    public static final String EXTRA_HIDE_AFTER_BOOT = "com.example.androidbuttons.EXTRA_HIDE_AFTER_BOOT";

    private static final String TAG_SERVICE = "MainActivitySvc";
    private static final int REQUEST_OVERLAY_PERMISSION = 1001;

    private TcpManager tcpManager;
    private DataBuffer uiBuffer;
    private SharedPreferences prefs;
    private ActivityResultLauncher<Intent> settingsLauncher;
    private boolean overlayPermissionRequested = false;
    private int currentState = 0;
    private boolean settingsLaunched = false;
    private java.util.Timer tcpStatusTimer;
    private final java.util.concurrent.ExecutorService bgExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private Boolean lastStatusConnected = null;

    private final StateBus.OverlaySelectionListener overlaySelectionListener = state ->
            runOnUiThread(() -> handleOverlaySelection(state));

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener = (sharedPrefs, key) -> {
        if (sharedPrefs == null || key == null) {
            return;
        }

        // Реакция только на изменения IP/порта
        if (AppState.KEY_TCP_HOST.equals(key) || AppState.KEY_TCP_PORT.equals(key)) {
            String host = sharedPrefs.getString(AppState.KEY_TCP_HOST, "192.168.2.6");
            int port = sharedPrefs.getInt(AppState.KEY_TCP_PORT, 9000);
            if (host != null) {
                host = host.trim();
            }

            // Отключаем старое авто-подключение и разрываем текущее соединение
            tcpManager.disableAutoConnect();
            tcpManager.disconnect();

            // Запускаем авто-подключение к новому адресу
            tcpManager.enableAutoConnect(host, port);
        }
    };


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

        uiBuffer = new DataBuffer(256, data -> AppState.consoleQueue.offer(data));

        tcpManager = new TcpManager(
                () -> runOnUiThread(() -> {
                    AppState.tcpConnecting = true;
                }),
                () -> runOnUiThread(() -> {
                    AppState.tcpConnecting = false;
                }),
                data -> {
                    if (data == null || data.isEmpty()) {
                        return;
                    }
                    String[] lines = data.split("\n");
                    int locoTarget = AppState.selectedLoco.get();
                    for (String line : lines) {
                        if (line == null) {
                            continue;
                        }
                        String ln = line.trim();
                        if (ln.isEmpty()) {
                            continue;
                        }

                        if (ln.startsWith("[TCP]")) {
                            continue;
                        }

                        int locoVal = extractDecimal(ln, "loco=");
                        if (locoVal <= 0 || locoVal != locoTarget) {
                            continue;
                        }
                        int stateVal = extractDecimal(ln, "state=");
                        // Диапазон от железа: 1..5
                        if (stateVal < 1 || stateVal > 5) {
                            continue;
                        }

                        final int stateCopy = stateVal;
                        runOnUiThread(() -> updateStateFromExternal(stateCopy));
                    }
                },
                error -> {
                    // suppress UI noise
                },
                status -> runOnUiThread(() -> {
                    boolean connected = "connected".equals(status);
                    AppState.tcpConnected = connected;
                    AppState.tcpReachable = connected;

                    if (lastStatusConnected == null || !lastStatusConnected.equals(connected)) {
                        lastStatusConnected = connected;
                    }
                })
        );

        prefs = getSharedPreferences(AppState.PREFS_NAME, MODE_PRIVATE);
        prefs.registerOnSharedPreferenceChangeListener(prefListener);
        String initHost = prefs.getString(AppState.KEY_TCP_HOST, "192.168.2.6");
        int initPort = prefs.getInt(AppState.KEY_TCP_PORT, 9000);
        if (initHost != null) initHost = initHost.trim();
        tcpManager.enableAutoConnect(initHost, initPort);

        updateStateFromExternal(1);
        StateBus.publishOverlaySelection(1);

        tcpStatusTimer = new java.util.Timer();
        tcpStatusTimer.scheduleAtFixedRate(new java.util.TimerTask() {
            @Override
            public void run() {
                performTcpHealthCheck("tick");
            }
        }, 1000, 1000);
    }

    private void performTcpHealthCheck(String phase) {
        // Никогда не выполняем работу на UI-потоке
        if (android.os.Looper.getMainLooper().isCurrentThread()) {
            runOffUi(() -> performTcpHealthCheck(phase + "_bg"));
            return;
        }

        // Пассивно проверяем, живо ли текущее соединение
        boolean connectionAlive = tcpManager.checkConnectionAlive();

        // Обновляем только флаги, без дополнительных TCP-подключений
        AppState.tcpConnected = connectionAlive;
        AppState.tcpReachable = connectionAlive;
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

        // Корректно гасим TcpManager (авто-подключение, соединение и его потоки)
        if (tcpManager != null) {
            tcpManager.shutdown();
        }

        bgExecutor.shutdownNow();

        if (prefs != null) {
            try {
                prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
            } catch (Exception ignored) {
                // no-op
            }
        }
        uiBuffer.close();

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

        if (prefs == null) {
            prefs = getSharedPreferences(AppState.PREFS_NAME, MODE_PRIVATE);
            prefs.registerOnSharedPreferenceChangeListener(prefListener);
        }

        String host = prefs.getString(AppState.KEY_TCP_HOST, "192.168.2.6");
        int port = prefs.getInt(AppState.KEY_TCP_PORT, 9000);
        if (host != null) host = host.trim();
        tcpManager.updateTarget(host, port);

        ensureOverlayServiceRunning();
        StateBus.registerSelectionListener(overlaySelectionListener);
        if (currentState > 0) {
            StateBus.publishStripState(currentState);
        }

        processLaunchFlags(getIntent(), false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        StateBus.unregisterSelectionListener(overlaySelectionListener);
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
        currentState = state;
        if (sendCommands) {
            sendExclusiveRelays(state);
        }
        StateBus.publishStripState(state);
    }

    private void updateStateFromExternal(int state) {
        if (state < 1 || state > 5) {
            return;
        }
        if (state == currentState) {
            return;
        }
        boolean allowModification = prefs.getBoolean(AppState.KEY_OVERLAY_ALLOW_MODIFICATION, true);
        if (allowModification) {
            return;
        }
        currentState = state;
        StateBus.publishStripState(state);
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
        } catch (Exception ex) {
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
            return;
        }
        overlayPermissionRequested = true;
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION);
        } catch (ActivityNotFoundException ex) {
            overlayPermissionRequested = false;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            overlayPermissionRequested = false;
            if (canDrawOverlays()) {
                ensureOverlayServiceRunning();
            }
        }
    }

    private void sendExclusiveRelays(int active) {
        int loco = AppState.selectedLoco.get();
        // Диапазон протокола: 1..5
        int state = Math.max(1, Math.min(5, active));
        tcpManager.sendControl(loco, state);
        if (tcpManager.connectionActive()) {
        }
    }
}
