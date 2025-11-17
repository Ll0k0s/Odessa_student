package com.example.androidbuttons;

import android.app.Notification;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
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

    private SharedPreferences.OnSharedPreferenceChangeListener preferenceListener;
    private BroadcastReceiver scaleReceiver;
    private BroadcastReceiver positionReceiver;

    private WindowManager windowManager;
    private View overlayView;
    private WindowManager.LayoutParams overlayParams;
    private FrameLayout overlayRoot;
    private ImageView overlayStateStrip;
    private OverlayStateAnimator stateAnimator;
    private OverlayGestureHandler gestureHandler;

    private final StateBus.StripStateListener stripStateListener = state ->
        mainHandler.post(() -> {
            SharedPreferences prefs = getSharedPreferences(AppState.PREFS_NAME, MODE_PRIVATE);
            boolean allowModification = prefs.getBoolean(AppState.KEY_OVERLAY_ALLOW_MODIFICATION, true);
            int shownState = stateAnimator != null ? stateAnimator.getCurrentState() : 0;
            if (allowModification && shownState != 0) {
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
                    BASE_WIDTH_PX,
                    BASE_HEIGHT_PX
            );
        }

        SharedPreferences prefs = getSharedPreferences(AppState.PREFS_NAME, MODE_PRIVATE);
        preferenceListener = (sharedPreferences, key) -> {
            if (AppState.KEY_OVERLAY_ALLOW_MODIFICATION.equals(key)) {
                boolean allow = sharedPreferences.getBoolean(AppState.KEY_OVERLAY_ALLOW_MODIFICATION, true);
                float targetAlpha = allow ? ALPHA_EDIT_MODE : ALPHA_NORMAL;
                mainHandler.post(() -> setOverlayAlpha(targetAlpha));
            }
        };
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener);

        scaleReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!"com.example.androidbuttons.APPLY_SCALE_NOW".equals(intent.getAction())) {
                    return;
                }
                float scale = intent.getFloatExtra("scale", 1.0f);
                if (gestureHandler != null) {
                    gestureHandler.applyScale(scale);
                }
                Log.d(TAG, "Scale applied immediately: " + scale);
            }
        };
        registerReceiver(scaleReceiver, new IntentFilter("com.example.androidbuttons.APPLY_SCALE_NOW"));

        positionReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!"com.example.androidbuttons.APPLY_POSITION_NOW".equals(intent.getAction())) {
                    return;
                }
                if (overlayParams == null || windowManager == null || overlayView == null) {
                    return;
                }
                if (intent.hasExtra("x")) {
                    int logicalX = intent.getIntExtra("x", overlayParams.x);
                    overlayParams.x = logicalX == 0 ? -computeLeftCompensation() : logicalX;
                }
                if (intent.hasExtra("y")) {
                    overlayParams.y = intent.getIntExtra("y", overlayParams.y);
                }
                try {
                    windowManager.updateViewLayout(overlayView, overlayParams);
                    Log.d(TAG, "Position applied immediately: x=" + overlayParams.x + " y=" + overlayParams.y);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to apply position", e);
                }
            }
        };
        registerReceiver(positionReceiver, new IntentFilter("com.example.androidbuttons.APPLY_POSITION_NOW"));
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

        SharedPreferences prefs = getSharedPreferences(AppState.PREFS_NAME, MODE_PRIVATE);
        if (preferenceListener != null) {
            prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener);
            preferenceListener = null;
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

            SharedPreferences prefs = getSharedPreferences(AppState.PREFS_NAME, MODE_PRIVATE);
            boolean allow = prefs.getBoolean(AppState.KEY_OVERLAY_ALLOW_MODIFICATION, true);
            setOverlayAlpha(allow ? ALPHA_EDIT_MODE : ALPHA_NORMAL);

            int existingState = StateBus.getCurrentState();
            if (existingState <= 0) {
                StateBus.publishStripState(1);
                existingState = 1;
            }
            updateOverlayState(existingState);

            float currentScale = prefs.getFloat(AppState.KEY_OVERLAY_SCALE, 1.0f);
            if (gestureHandler != null) {
                gestureHandler.applyScale(currentScale);
            }

            refreshOverlayStatus();
            StateBus.registerStateListener(stripStateListener);
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
        StateBus.unregisterStateListener(stripStateListener);
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

        SharedPreferences prefs = getSharedPreferences(AppState.PREFS_NAME, MODE_PRIVATE);
        float scale = prefs.getFloat(AppState.KEY_OVERLAY_SCALE, 1.0f);
        params.width = Math.round(BASE_WIDTH_PX * scale);
        params.height = Math.round(BASE_HEIGHT_PX * scale);

        int savedX = prefs.getInt(AppState.KEY_OVERLAY_X, -computeLeftCompensation());
        int savedY = prefs.getInt(AppState.KEY_OVERLAY_Y, 0);
        params.x = savedX == 0 ? -computeLeftCompensation() : savedX;
        params.y = savedY;
        return params;
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
