package com.example.androidbuttons;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.androidbuttons.core.AppContracts;
import com.example.androidbuttons.core.AppGraph;
import com.example.androidbuttons.core.OverlayStateStore;
import com.example.androidbuttons.core.ServiceLaunchers;

public class MainActivity extends AppCompatActivity {

    public static final String EXTRA_OPEN_SETTINGS = "com.example.androidbuttons.EXTRA_OPEN_SETTINGS";
    public static final String EXTRA_HIDE_AFTER_BOOT = "com.example.androidbuttons.EXTRA_HIDE_AFTER_BOOT";

    private static final String TAG_SERVICE = "MainActivitySvc";
    private static final int REQUEST_OVERLAY_PERMISSION = 1001;

    private ActivityResultLauncher<Intent> settingsLauncher;
    private boolean overlayPermissionRequested;
    private boolean settingsLaunched;
    private OverlayStateStore overlayStateStore;

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
        overlayStateStore = graph.overlayStates();

        ServiceLaunchers.ensureTcpServiceRunning(this);
        Log.d(AppContracts.LOG_TAG_MAIN, "MainActivity ready");
    }

    @Override
    protected void onResume() {
        super.onResume();
        ensureOverlayServiceRunning();
        processLaunchFlags(getIntent(), false);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        processLaunchFlags(intent, true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopOverlayService();
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
            Log.e(TAG_SERVICE, "Failed to request overlay permission", ex);
            overlayPermissionRequested = false;
        }
    }

    private void stopOverlayService() {
        try {
            Intent svcIntent = new Intent(this, FloatingOverlayService.class);
            stopService(svcIntent);
        } catch (Exception ignored) {
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            overlayPermissionRequested = false;
            if (!canDrawOverlays()) {
                Log.w(TAG_SERVICE, "Overlay permission still missing after dialog");
            } else {
                ensureOverlayServiceRunning();
            }
        }
    }
}
