package com.example.androidbuttons;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * Обработчик жестов для overlay: перемещение, pinch-to-zoom и выбор состояния.
 */
final class OverlayGestureHandler implements View.OnTouchListener {

    private static final String TAG = "OverlayGestureHandler";

    private enum GestureMode { NONE, MOVE, SCALE }

    private final Context context;
    private final WindowManager windowManager;
    private final OverlayStateAnimator stateAnimator;
    private final int baseWidthPx;
    private final int baseHeightPx;
    private final int[] tmpLocation = new int[2];

    private WindowManager.LayoutParams overlayParams;
    private View overlayView;
    private FrameLayout overlayRoot;
    private ImageView overlayStateStrip;

    private float initialTouchX;
    private float initialTouchY;
    private int initialX;
    private int initialY;

    private float initialDistance = 0f;
    private float initialScale = 1f;
    private boolean isScaling = false;
    private float scalePivotX;
    private float scalePivotY;
    private int initialWidth;
    private int initialHeight;
    private boolean animationPausedForScaling = false;
    private float lastDistanceDuringScale = 0f;
    private float smoothedScale = 1f;
    private long lastScaleTs = 0L;

    private GestureMode gestureMode = GestureMode.NONE;
    private boolean didMoveDuringGesture = false;
    private boolean suppressMoveUntilUp = false;

    OverlayGestureHandler(Context context,
                          WindowManager windowManager,
                          OverlayStateAnimator stateAnimator,
                          int baseWidthPx,
                          int baseHeightPx) {
        this.context = context.getApplicationContext();
        this.windowManager = windowManager;
        this.stateAnimator = stateAnimator;
        this.baseWidthPx = baseWidthPx;
        this.baseHeightPx = baseHeightPx;
    }

    void bind(View overlayView,
              FrameLayout overlayRoot,
              ImageView overlayStateStrip,
              WindowManager.LayoutParams params) {
        this.overlayView = overlayView;
        this.overlayRoot = overlayRoot;
        this.overlayStateStrip = overlayStateStrip;
        this.overlayParams = params;
    }

    void unbind() {
        overlayView = null;
        overlayRoot = null;
        overlayStateStrip = null;
        overlayParams = null;
        gestureMode = GestureMode.NONE;
        suppressMoveUntilUp = false;
    }

    ImageView getOverlayStateStrip() {
        return overlayStateStrip;
    }

    @Override
    public boolean onTouch(View view, MotionEvent event) {
        return handleOverlayTouch(view, event);
    }

    boolean handleOverlayTouch(View view, MotionEvent event) {
        if (overlayParams == null || windowManager == null) {
            return false;
        }

        SharedPreferences prefs = context.getSharedPreferences(AppState.PREFS_NAME, Context.MODE_PRIVATE);
        boolean allowModification = prefs.getBoolean(AppState.KEY_OVERLAY_ALLOW_MODIFICATION, true);

        if (!allowModification) {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                return handleStripTouch(event);
            }
            return true;
        } else if (event.getAction() == MotionEvent.ACTION_UP) {
            Log.d(TAG, "State change blocked in edit mode (tap ignored) — allowing gesture finalization");
        }

        int action = event.getActionMasked();
        int pointerCount = event.getPointerCount();

        View touchView = (view != null) ? view : overlayView;

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                if (suppressMoveUntilUp && gestureMode == GestureMode.NONE) {
                    Log.d(TAG, "ACTION_DOWN ignored (suppressMoveUntilUp still true) — resetting now");
                }
                if (suppressMoveUntilUp) {
                    Log.d(TAG, "Clearing suppressMoveUntilUp on new ACTION_DOWN");
                    suppressMoveUntilUp = false;
                }
                initialTouchX = event.getRawX();
                initialTouchY = event.getRawY();
                initialX = overlayParams.x;
                initialY = overlayParams.y;
                gestureMode = GestureMode.MOVE;
                isScaling = false;
                didMoveDuringGesture = false;
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (pointerCount == 2 && gestureMode == GestureMode.MOVE) {
                    float moved = Math.abs(getRawXCompat(touchView, event, 0) - initialTouchX)
                            + Math.abs(getRawYCompat(touchView, event, 0) - initialTouchY);
                    if (moved < dpToPx(6)) {
                        gestureMode = GestureMode.SCALE;
                        isScaling = true;
                        suppressMoveUntilUp = true;
                        initialDistance = getDistance(event);
                        initialScale = prefs.getFloat(AppState.KEY_OVERLAY_SCALE, 1.0f);
                        lastDistanceDuringScale = initialDistance;
                        smoothedScale = initialScale;
                        lastScaleTs = System.nanoTime();
                        initialWidth = overlayParams.width;
                        initialHeight = overlayParams.height;
                        initialX = overlayParams.x;
                        initialY = overlayParams.y;
                        scalePivotX = (getRawXCompat(touchView, event, 0) + getRawXCompat(touchView, event, 1)) / 2f;
                        scalePivotY = (getRawYCompat(touchView, event, 0) + getRawYCompat(touchView, event, 1)) / 2f;
                        stateAnimator.pauseAnimation();
                        animationPausedForScaling = true;
                        Log.d(TAG, "Scale start pivot=(" + scalePivotX + "," + scalePivotY + ") scale=" + initialScale);
                    }
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (gestureMode == GestureMode.SCALE && isScaling && pointerCount == 2) {
                    handleScaleMove(touchView, event);
                } else if (gestureMode == GestureMode.MOVE && pointerCount == 1 && !suppressMoveUntilUp) {
                    float deltaX = event.getRawX() - initialTouchX;
                    float deltaY = event.getRawY() - initialTouchY;
                    int newX = (int) (initialX + deltaX);
                    int newY = (int) (initialY + deltaY);
                    if (newX != overlayParams.x || newY != overlayParams.y) {
                        didMoveDuringGesture = true;
                    }
                    overlayParams.x = newX;
                    overlayParams.y = newY;
                    try {
                        windowManager.updateViewLayout(overlayView, overlayParams);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to update overlay position", e);
                    }
                }
                return true;

            case MotionEvent.ACTION_POINTER_UP:
                if (gestureMode == GestureMode.SCALE) {
                    isScaling = false;
                    saveOverlayScale();
                    resumeAnimationIfNeeded();
                    gestureMode = GestureMode.NONE;
                }
                return true;

            case MotionEvent.ACTION_UP:
                if (gestureMode == GestureMode.SCALE) {
                    isScaling = false;
                    saveOverlayScale();
                    resumeAnimationIfNeeded();
                } else if (gestureMode == GestureMode.MOVE) {
                    handleMoveUp(touchView, event);
                }
                gestureMode = GestureMode.NONE;
                if (pointerCount <= 1) {
                    if (suppressMoveUntilUp) {
                        Log.d(TAG, "All fingers up — clearing suppressMoveUntilUp");
                    }
                    suppressMoveUntilUp = false;
                }
                if (touchView != null && action == MotionEvent.ACTION_UP) {
                    touchView.performClick();
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                isScaling = false;
                gestureMode = GestureMode.NONE;
                if (pointerCount <= 1) suppressMoveUntilUp = false;
                return true;
        }

        return false;
    }

    private void handleScaleMove(View touchView, MotionEvent event) {
        float currentDistance = getDistance(event);
        if (initialDistance <= 0) {
            return;
        }
        if (lastDistanceDuringScale > 0) {
            float rawDelta = Math.abs(currentDistance - lastDistanceDuringScale) / lastDistanceDuringScale;
            if (rawDelta > 0.40f) {
                lastDistanceDuringScale = currentDistance;
                return;
            }
        }
        float targetScale = initialScale * (currentDistance / initialDistance);
        targetScale = Math.max(0.1f, Math.min(5.0f, targetScale));
        long now = System.nanoTime();
        float dtMs = (now - lastScaleTs) / 1_000_000f;
        lastScaleTs = now;
        float alpha = Math.min(1f, dtMs / 50f);
        smoothedScale = smoothedScale + alpha * (targetScale - smoothedScale);
        if (Math.abs(targetScale - smoothedScale) < 0.005f) {
            lastDistanceDuringScale = currentDistance;
            return;
        }
        int newWidth = Math.round(baseWidthPx * smoothedScale);
        int newHeight = Math.round(baseHeightPx * smoothedScale);
        float pivotRelativeX = scalePivotX - initialX;
        float pivotRelativeY = scalePivotY - initialY;
        float pivotRatioX = pivotRelativeX / initialWidth;
        float pivotRatioY = pivotRelativeY / initialHeight;
        int newX = Math.round(scalePivotX - (newWidth * pivotRatioX));
        int newY = Math.round(scalePivotY - (newHeight * pivotRatioY));
        applyScaleWithPosition(smoothedScale, newX, newY);
        lastDistanceDuringScale = currentDistance;
    }

    private void handleMoveUp(View touchView, MotionEvent event) {
        SharedPreferences p = context.getSharedPreferences(AppState.PREFS_NAME, Context.MODE_PRIVATE);
        int storedX = eliminateTinyOffset(p.getInt(AppState.KEY_OVERLAY_X, overlayParams.x));
        int storedY = p.getInt(AppState.KEY_OVERLAY_Y, overlayParams.y);
        boolean coordsChanged = (storedX != eliminateTinyOffset(overlayParams.x)) || (storedY != overlayParams.y);
        float totalDelta = Math.abs(getRawXCompat(touchView, event, 0) - initialTouchX)
            + Math.abs(getRawYCompat(touchView, event, 0) - initialTouchY);
        if (didMoveDuringGesture || coordsChanged || totalDelta >= dpToPx(2)) {
            saveOverlayPosition();
        } else {
            Log.d(TAG, "ACTION_UP MOVE: no significant movement (" + totalDelta + ") — position unchanged");
        }
    }

    private void resumeAnimationIfNeeded() {
        if (animationPausedForScaling) {
            stateAnimator.resumeAnimation();
            animationPausedForScaling = false;
        }
    }

    private boolean handleStripTouch(MotionEvent event) {
        if (event == null || overlayStateStrip == null) {
            return false;
        }
        int action = event.getActionMasked();
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE) {
            return action == MotionEvent.ACTION_UP;
        }
        int height = overlayStateStrip.getHeight();
        if (height <= 0) {
            return true;
        }
        float y = event.getY();
        int zone = (int) (y / (height / 5f)) + 1;
        if (zone < 1) zone = 1; else if (zone > 5) zone = 5;

        SharedPreferences p = context.getSharedPreferences(AppState.PREFS_NAME, Context.MODE_PRIVATE);
        boolean allowModification = p.getBoolean(AppState.KEY_OVERLAY_ALLOW_MODIFICATION, true);
        if (allowModification) {
            Log.d(TAG, "State tap ignored (edit mode) zone=" + zone);
            return true;
        }
        if (zone != stateAnimator.getCurrentState()) {
            Log.i(TAG, "Overlay selects state=" + zone);
            stateAnimator.updateState(zone);
            StateBus.publishOverlaySelection(zone);
        }
        return true;
    }

    void applyScale(float newScale) {
        if (overlayParams == null || windowManager == null || overlayView == null) {
            return;
        }
        int savedX = overlayParams.x;
        int savedY = overlayParams.y;
        int newWidth = Math.round(baseWidthPx * newScale);
        int newHeight = Math.round(baseHeightPx * newScale);
        overlayParams.width = newWidth;
        overlayParams.height = newHeight;
        overlayParams.x = savedX;
        overlayParams.y = savedY;
        updateCornerRadius(newScale);
        try {
            windowManager.updateViewLayout(overlayView, overlayParams);
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply scale", e);
        }
    }

    private void applyScaleWithPosition(float newScale, int newX, int newY) {
        if (overlayParams == null || windowManager == null || overlayView == null) {
            return;
        }
        int newWidth = Math.round(baseWidthPx * newScale);
        int newHeight = Math.round(baseHeightPx * newScale);
        overlayParams.width = newWidth;
        overlayParams.height = newHeight;
        overlayParams.x = newX;
        overlayParams.y = newY;
        updateCornerRadius(newScale);
        try {
            windowManager.updateViewLayout(overlayView, overlayParams);
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply scale with position", e);
        }
    }

    private void updateCornerRadius(float scale) {
        if (overlayRoot == null) {
            return;
        }
        float baseRadiusDp = 6f;
        float density = context.getResources().getDisplayMetrics().density;
        float baseRadiusPx = baseRadiusDp * density;
        float newRadiusPx = baseRadiusPx * scale;

        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(0xFF121212);
        background.setCornerRadius(newRadiusPx);

        overlayRoot.setBackground(background);
        overlayRoot.setClipToOutline(true);

        Log.d(TAG, "Corner radius updated to: " + newRadiusPx + "px (scale=" + scale + ")");
    }

    private void saveOverlayScale() {
        if (overlayParams == null) {
            return;
        }
        float currentScale = (float) overlayParams.width / baseWidthPx;
        currentScale = Math.round(currentScale * 100f) / 100f;

        SharedPreferences prefs = context.getSharedPreferences(AppState.PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putFloat(AppState.KEY_OVERLAY_SCALE, currentScale)
                .apply();

        Log.d(TAG, "Overlay scale saved: " + currentScale + " (width=" + overlayParams.width + ")");

        android.content.Intent intent = new android.content.Intent(AppState.ACTION_OVERLAY_UPDATED);
        intent.putExtra(AppState.KEY_OVERLAY_SCALE, currentScale);
        context.sendBroadcast(intent);
    }

    private void saveOverlayPosition() {
        if (overlayParams == null) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(AppState.PREFS_NAME, Context.MODE_PRIVATE);
        int logicalX = overlayParams.x;
        if (logicalX < 0 && Math.abs(logicalX) <= computeLeftCompensation() + 2) {
            logicalX = 0;
        }
        int clampedX = eliminateTinyOffset(logicalX);

        prefs.edit()
                .putInt(AppState.KEY_OVERLAY_X, clampedX)
                .putInt(AppState.KEY_OVERLAY_Y, overlayParams.y)
                .apply();

        Log.d(TAG, "Overlay position saved: x=" + clampedX + " y=" + overlayParams.y);

        android.content.Intent intent = new android.content.Intent(AppState.ACTION_OVERLAY_UPDATED);
        intent.putExtra(AppState.KEY_OVERLAY_X, clampedX);
        intent.putExtra(AppState.KEY_OVERLAY_Y, overlayParams.y);
        context.sendBroadcast(intent);
    }

    private float getDistance(MotionEvent event) {
        if (event.getPointerCount() < 2) {
            return 0f;
        }
        float x = event.getX(0) - event.getX(1);
        float y = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(x * x + y * y);
    }

    private int dpToPx(int dp) {
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    private int eliminateTinyOffset(int valuePx) {
        return Math.abs(valuePx) <= 12 ? 0 : valuePx;
    }

    private int computeLeftCompensation() {
        return 10;
    }

    private float getRawXCompat(View touchView, MotionEvent event, int pointerIndex) {
        if (event.getPointerCount() == 0) {
            return 0f;
        }
        if (pointerIndex >= event.getPointerCount()) {
            pointerIndex = event.getPointerCount() - 1;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return event.getRawX(pointerIndex);
        }
        View reference = (touchView != null) ? touchView : overlayView;
        if (reference != null) {
            reference.getLocationOnScreen(tmpLocation);
            return tmpLocation[0] + event.getX(pointerIndex);
        }
        return event.getX(pointerIndex);
    }

    private float getRawYCompat(View touchView, MotionEvent event, int pointerIndex) {
        if (event.getPointerCount() == 0) {
            return 0f;
        }
        if (pointerIndex >= event.getPointerCount()) {
            pointerIndex = event.getPointerCount() - 1;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return event.getRawY(pointerIndex);
        }
        View reference = (touchView != null) ? touchView : overlayView;
        if (reference != null) {
            reference.getLocationOnScreen(tmpLocation);
            return tmpLocation[1] + event.getY(pointerIndex);
        }
        return event.getY(pointerIndex);
    }
}
