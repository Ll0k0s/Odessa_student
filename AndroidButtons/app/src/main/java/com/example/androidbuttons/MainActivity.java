package com.example.androidbuttons;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
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

import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends AppCompatActivity {

    public static final String EXTRA_OPEN_SETTINGS = "com.example.androidbuttons.EXTRA_OPEN_SETTINGS";
    public static final String EXTRA_HIDE_AFTER_BOOT = "com.example.androidbuttons.EXTRA_HIDE_AFTER_BOOT";

    private static final String TAG_SERVICE = "MainActivitySvc";
    private static final int REQUEST_OVERLAY_PERMISSION = 1001;

    private TcpService tcpService;
    private boolean tcpServiceBound;
    private DataBuffer uiBuffer;
    private ActivityResultLauncher<Intent> settingsLauncher;
    private boolean overlayPermissionRequested = false;
    private int currentState = 0;
    private boolean settingsLaunched = false;

    private OverlaySettingsRepository overlaySettingsRepository;
    private OverlaySettingsRepository.OverlaySettings overlaySettings;
    private OverlayStateStore overlayStateStore;
    private TcpConfigRepository tcpConfigRepository;
    private TcpConfigRepository.TcpConfig currentTcpConfig;
    private final AtomicInteger selectedLoco = new AtomicInteger(1);
    private ConsoleLogRepository consoleLogRepository;

    private final OverlayStateStore.SelectionListener overlaySelectionListener = state ->
            runOnUiThread(() -> handleOverlaySelection(state));

    private final OverlaySettingsRepository.Listener overlaySettingsListener = settings ->
            runOnUiThread(() -> overlaySettings = settings);

    private final TcpConfigRepository.Listener tcpConfigListener = config ->
            runOnUiThread(() -> applyTcpConfig(config));

    private final TcpService.TcpDataListener tcpDataListener = line ->
            runOnUiThread(() -> handleTcpPayload(line));

    private final android.content.ServiceConnection tcpServiceConnection = new android.content.ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            TcpService.LocalBinder binder = (TcpService.LocalBinder) service;
            tcpService = binder.getService();
            tcpServiceBound = true;
            tcpService.setTcpDataListener(tcpDataListener);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (tcpService != null) {
                tcpService.setTcpDataListener(null);
            }
            tcpServiceBound = false;
            tcpService = null;
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

        AppGraph graph = AppGraph.get();
        overlaySettingsRepository = graph.overlaySettings();
        overlaySettings = overlaySettingsRepository.get();
        overlayStateStore = graph.overlayStates();
        tcpConfigRepository = graph.tcpConfig();
        currentTcpConfig = tcpConfigRepository.get();
        selectedLoco.set(currentTcpConfig.selectedLoco);
        consoleLogRepository = graph.consoleLog();

        overlaySettingsRepository.addListener(overlaySettingsListener);
        tcpConfigRepository.addListener(tcpConfigListener);

        uiBuffer = new DataBuffer(256, consoleLogRepository::append);

        applyTcpConfig(currentTcpConfig);
        startTcpService();

        updateStateFromExternal(1);
        overlayStateStore.publishSelection(1);
        Log.d(AppContracts.LOG_TAG_MAIN, "Initial state set to 1 (green)");
    }

    private void handleTcpPayload(String data) {
        if (data == null || data.isEmpty()) {
            return;
        }
        int locoTarget = selectedLoco.get();
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unbindTcpService();
        stopTcpService();
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
    protected void onStart() {
        super.onStart();
        bindTcpService();
    }

    @Override
    protected void onStop() {
        super.onStop();
        unbindTcpService();
    }

    @Override
    protected void onResume() {
        super.onResume();

        ensureOverlayServiceRunning();
        overlayStateStore.addSelectionListener(overlaySelectionListener);
        if (currentState > 0) {
            overlayStateStore.publish(currentState);
        }
        applyTcpConfig(currentTcpConfig);
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

    private void startTcpService() {
        try {
            Context appCtx = getApplicationContext();
            Intent svcIntent = new Intent(appCtx, TcpService.class);
            appCtx.startService(svcIntent);
            Log.i(TAG_SERVICE, "TcpService start requested");
        } catch (Exception ex) {
            Log.e(TAG_SERVICE, "Failed to start TcpService", ex);
        }
    }

    private void stopTcpService() {
        try {
            Intent svcIntent = new Intent(this, TcpService.class);
            stopService(svcIntent);
            Log.i(TAG_SERVICE, "TcpService stop requested");
        } catch (Exception ex) {
            Log.w(TAG_SERVICE, "Failed to stop TcpService", ex);
        }
    }

    private void bindTcpService() {
        if (tcpServiceBound) {
            return;
        }
        try {
            Intent intent = new Intent(this, TcpService.class);
            boolean bound = bindService(intent, tcpServiceConnection, Context.BIND_AUTO_CREATE);
            tcpServiceBound = bound;
            Log.i(TAG_SERVICE, "bindTcpService result=" + bound);
        } catch (Exception ex) {
            tcpServiceBound = false;
            Log.e(TAG_SERVICE, "Failed to bind TcpService", ex);
        }
    }

    private void unbindTcpService() {
        if (!tcpServiceBound) {
            return;
        }
        try {
            unbindService(tcpServiceConnection);
            Log.i(TAG_SERVICE, "TcpService unbound");
        } catch (Exception ex) {
            Log.w(TAG_SERVICE, "unbindTcpService failed", ex);
        } finally {
            tcpServiceBound = false;
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
        boolean queued = tcpService != null && tcpService.sendControl(loco, state);
        if (queued) {
            uiBuffer.offer("[#TCP_TX#]" + "Tx: loco" + loco + " -> state" + state + "\n");
        }
    }

    private void applyTcpConfig(@Nullable TcpConfigRepository.TcpConfig config) {
        if (config == null) {
            return;
        }
        currentTcpConfig = config;
        selectedLoco.set(config.selectedLoco);
    }

    private boolean isOverlayInEditMode() {
        return overlaySettings != null && overlaySettings.editAllowed;
    }
}
