package com.example.androidbuttons;

import android.app.Notification;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;

import com.example.androidbuttons.core.AppContracts;
import com.example.androidbuttons.core.AppGraph;
import com.example.androidbuttons.core.OverlaySettingsRepository;
import com.example.androidbuttons.core.OverlayStateStore;

/**
 * Foreground overlay service that shows a diagnostic strip on top of every screen.
 */
public class FloatingOverlayService extends Service {

    private static final String TAG = "FloatingOverlayService";
    public static final String ACTION_RECREATE_OVERLAY = "com.example.androidbuttons.action.RECREATE_OVERLAY";

    private static final long HEARTBEAT_INTERVAL_MS = 3000L;
    private static final long STRIP_ANIM_DURATION = 1000L;
    private static final int BASE_WIDTH_PX = 100;
    private static final int BASE_HEIGHT_PX = 430;
    private static final float ALPHA_EDIT_MODE = 0.7f;
    private static final float ALPHA_NORMAL = 1.0f;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean overlayAttached = new AtomicBoolean(false);

    private BroadcastReceiver scaleReceiver;
    private BroadcastReceiver positionReceiver;
    private OverlaySettingsRepository overlaySettingsRepository;
    private OverlaySettingsRepository.OverlaySettings overlaySettings;
    private OverlaySettingsRepository.Listener overlaySettingsListener;
    private OverlayStateStore overlayStateStore;

    private WindowManager windowManager;
    private View overlayView;
    private WindowManager.LayoutParams overlayParams;
    private FrameLayout overlayRoot;
    private ImageView overlayStateStrip;
    private OverlayStateAnimator stateAnimator;
    private OverlayGestureHandler gestureHandler;

    private final OverlayStateStore.Listener stripStateListener = state ->
            mainHandler.post(() -> {
                boolean editMode = overlaySettings != null && overlaySettings.editAllowed;
                int shownState = stateAnimator != null ? stateAnimator.getCurrentState() : 0;
                if (editMode && shownState != 0) {
                    Log.d(TAG, "stripStateListener: ignore state=" + state + " (edit mode, current=" + shownState + ")");
                    return;
                }
                updateOverlayState(state);
            });

    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            Log.d(TAG, "heartbeat attached=" + overlayAttached.get());
            refreshOverlayStatus();
            mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "onCreate");
        OverlayNotificationHelper.ensureChannel(this);
        AppGraph graph = AppGraph.get();
        overlaySettingsRepository = graph.overlaySettings();
        overlaySettings = overlaySettingsRepository.get();
        overlaySettingsListener = settings -> mainHandler.post(() -> applyOverlaySettings(settings));
        overlaySettingsRepository.addListener(overlaySettingsListener);
        overlayStateStore = graph.overlayStates();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) {
            Log.e(TAG, "WindowManager unavailable");
        }
        stateAnimator = new OverlayStateAnimator(this, STRIP_ANIM_DURATION);
        if (windowManager != null) {
            gestureHandler = new OverlayGestureHandler(
                    this,
                    windowManager,
                    stateAnimator,
                    overlaySettingsRepository,
                    overlayStateStore,
                    BASE_WIDTH_PX,
                    BASE_HEIGHT_PX
            );
        }

        scaleReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!AppContracts.ACTION_APPLY_SCALE.equals(intent.getAction())) {
                    return;
                }
                float fallbackScale = overlaySettings != null ? overlaySettings.scale : 1.0f;
                float scale = intent.getFloatExtra(AppContracts.EXTRA_OVERLAY_SCALE, fallbackScale);
                if (gestureHandler != null) {
                    gestureHandler.applyScale(scale);
                }
                Log.d(TAG, "Scale applied immediately: " + scale);
            }
        };
        registerReceiver(scaleReceiver, new IntentFilter(AppContracts.ACTION_APPLY_SCALE));

        positionReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!AppContracts.ACTION_APPLY_POSITION.equals(intent.getAction())) {
                    return;
                }
                if (overlayParams == null || windowManager == null || overlayView == null) {
                    return;
                }
                if (intent.hasExtra(AppContracts.EXTRA_OVERLAY_X)) {
                    int logicalX = intent.getIntExtra(AppContracts.EXTRA_OVERLAY_X, overlayParams.x);
                    overlayParams.x = logicalX == 0 ? -computeLeftCompensation() : logicalX;
                }
                if (intent.hasExtra(AppContracts.EXTRA_OVERLAY_Y)) {
                    overlayParams.y = intent.getIntExtra(AppContracts.EXTRA_OVERLAY_Y, overlayParams.y);
                }
                try {
                    windowManager.updateViewLayout(overlayView, overlayParams);
                    Log.d(TAG, "Position applied immediately: x=" + overlayParams.x + " y=" + overlayParams.y);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to apply position", e);
                }
            }
        };
        registerReceiver(positionReceiver, new IntentFilter(AppContracts.ACTION_APPLY_POSITION));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand flags=" + flags + " startId=" + startId);
        Notification notification = OverlayNotificationHelper.buildForegroundNotification(this);
        startForeground(OverlayNotificationHelper.getNotificationId(), notification);
        if (intent != null && ACTION_RECREATE_OVERLAY.equals(intent.getAction())) {
            mainHandler.post(() -> {
                detachOverlay();
                maybeAttachOverlay();
            });
        } else {
            mainHandler.post(this::maybeAttachOverlay);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy");
        mainHandler.removeCallbacks(heartbeatRunnable);
        detachOverlay();

        if (overlaySettingsRepository != null && overlaySettingsListener != null) {
            overlaySettingsRepository.removeListener(overlaySettingsListener);
        }
        if (scaleReceiver != null) {
            try {
                unregisterReceiver(scaleReceiver);
            } catch (IllegalArgumentException ignore) {
            }
            scaleReceiver = null;
        }
        if (positionReceiver != null) {
            try {
                unregisterReceiver(positionReceiver);
            } catch (IllegalArgumentException ignore) {
            }
            positionReceiver = null;
        }

        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void maybeAttachOverlay() {
        if (overlayAttached.get()) {
            return;
        }
        if (windowManager == null) {
            Log.w(TAG, "maybeAttachOverlay: WindowManager null");
            return;
        }
        if (!canDrawOverlays()) {
            Log.w(TAG, "maybeAttachOverlay: overlay permission missing");
            return;
        }

        try {
            LayoutInflater inflater = LayoutInflater.from(this);
            overlayView = inflater.inflate(R.layout.overlay_probe, null, false);
            overlayRoot = overlayView.findViewById(R.id.overlayProbeRoot);
            if (overlayRoot == null && overlayView instanceof FrameLayout) {
                overlayRoot = (FrameLayout) overlayView;
            }
            overlayParams = buildDefaultLayoutParams();
            windowManager.addView(overlayView, overlayParams);
            overlayAttached.set(true);

            overlayStateStrip = overlayView.findViewById(R.id.overlayStateStrip);
            if (stateAnimator != null) {
                stateAnimator.bind(overlayRoot, overlayStateStrip);
            }
            if (gestureHandler != null) {
                gestureHandler.bind(overlayView, overlayRoot, overlayStateStrip, overlayParams);
                if (overlayStateStrip != null) {
                    overlayStateStrip.setOnTouchListener(gestureHandler);
                }
            }

            OverlaySettingsRepository.OverlaySettings settingsSnapshot = currentOverlaySettings();
            setOverlayAlpha(settingsSnapshot.editAllowed ? ALPHA_EDIT_MODE : ALPHA_NORMAL);

            int existingState = overlayStateStore.getCurrentState();
            if (existingState <= 0) {
                overlayStateStore.publish(1);
                existingState = 1;
            }
            updateOverlayState(existingState);

            if (gestureHandler != null) {
                gestureHandler.applyScale(settingsSnapshot.scale);
            }

            refreshOverlayStatus();
            overlayStateStore.addListener(stripStateListener);
            mainHandler.removeCallbacks(heartbeatRunnable);
            mainHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS);
        } catch (RuntimeException ex) {
            Log.e(TAG, "Failed to attach overlay", ex);
            overlayAttached.set(false);
            overlayView = null;
        }
    }

    private void detachOverlay() {
        if (!overlayAttached.get()) {
            overlayView = null;
            return;
        }
        if (windowManager != null && overlayView != null) {
            try {
                windowManager.removeViewImmediate(overlayView);
                Log.i(TAG, "Overlay detached");
            } catch (IllegalArgumentException ex) {
                Log.w(TAG, "Overlay already removed", ex);
            }
        }
        overlayStateStore.removeListener(stripStateListener);
        overlayAttached.set(false);
        overlayView = null;
        overlayParams = null;
        overlayRoot = null;
        overlayStateStrip = null;
        if (gestureHandler != null) {
            gestureHandler.unbind();
        }
        if (stateAnimator != null) {
            stateAnimator.unbind();
        }
    }

    private void refreshOverlayStatus() {
        if (overlayStateStrip == null) {
            return;
        }
        CharSequence timestamp = DateFormat.format("HH:mm:ss", System.currentTimeMillis());
        overlayStateStrip.setContentDescription(
                getString(R.string.overlay_state_strip_cd_with_time, timestamp));
    }

    private void setOverlayAlpha(float alpha) {
        if (overlayView == null) {
            return;
        }
        overlayView.setAlpha(alpha);
    }

    private void updateOverlayState(int state) {
        if (stateAnimator == null) {
            Log.w(TAG, "updateOverlayState: animator not ready, skip state=" + state);
            return;
        }
        stateAnimator.updateState(state);
    }

    private void applyOverlaySettings(OverlaySettingsRepository.OverlaySettings settings) {
        overlaySettings = settings;
        float targetAlpha = settings.editAllowed ? ALPHA_EDIT_MODE : ALPHA_NORMAL;
        setOverlayAlpha(targetAlpha);
        if (overlayParams == null || windowManager == null || overlayView == null) {
            return;
        }
        overlayParams.x = settings.x == 0 ? -computeLeftCompensation() : settings.x;
        overlayParams.y = settings.y;
        overlayParams.width = Math.round(BASE_WIDTH_PX * settings.scale);
        overlayParams.height = Math.round(BASE_HEIGHT_PX * settings.scale);
        try {
            windowManager.updateViewLayout(overlayView, overlayParams);
            if (gestureHandler != null) {
                gestureHandler.applyScale(settings.scale);
            }
        } catch (Exception ex) {
            Log.e(TAG, "Failed to apply overlay settings", ex);
        }
    }

    private WindowManager.LayoutParams buildDefaultLayoutParams() {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.setTitle("OverlayProbe");
        params.windowAnimations = 0;

        OverlaySettingsRepository.OverlaySettings settingsSnapshot = currentOverlaySettings();
        params.width = Math.round(BASE_WIDTH_PX * settingsSnapshot.scale);
        params.height = Math.round(BASE_HEIGHT_PX * settingsSnapshot.scale);
        int savedX = settingsSnapshot.x;
        params.x = savedX == 0 ? -computeLeftCompensation() : savedX;
        params.y = settingsSnapshot.y;
        return params;
    }

    private OverlaySettingsRepository.OverlaySettings currentOverlaySettings() {
        if (overlaySettings != null) {
            return overlaySettings;
        }
        return overlaySettingsRepository != null
            ? overlaySettingsRepository.get()
            : new OverlaySettingsRepository.OverlaySettings(-computeLeftCompensation(), 0, 1.0f, true);
    }

    private boolean canDrawOverlays() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        return Settings.canDrawOverlays(this);
    }

    private int computeLeftCompensation() {
        return 10;
    }
}
