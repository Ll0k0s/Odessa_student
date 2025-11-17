package com.example.androidbuttons;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.appcompat.content.res.AppCompatResources;

/**
 * Отвечает за смену состояний полосы overlay и их анимацию.
 */
final class OverlayStateAnimator {

    private static final String TAG = "OverlayStateAnimator";

    private final Context context;
    private final long animDurationMs;

    private FrameLayout overlayRoot;
    private ImageView overlayStateStrip;
    private ImageView overlayCrossfadeView;
    private ValueAnimator overlayAnimator;
    private int currentState = 0;
    private int lastResId = 0;

    OverlayStateAnimator(Context context, long animDurationMs) {
        this.context = context.getApplicationContext();
        this.animDurationMs = animDurationMs;
    }

    void bind(FrameLayout root, ImageView strip) {
        this.overlayRoot = root;
        this.overlayStateStrip = strip;
    }

    void unbind() {
        cancelAnimator();
        overlayRoot = null;
        overlayStateStrip = null;
        overlayCrossfadeView = null;
        currentState = 0;
        lastResId = 0;
    }

    int getCurrentState() {
        return currentState;
    }

    void updateState(int state) {
        if (overlayStateStrip == null) {
            Log.d(TAG, "updateState: view null, skip state=" + state);
            return;
        }
        int resId = resolveDrawableForState(state);
        if (resId == 0) {
            Log.w(TAG, "updateState: unresolved drawable for state=" + state);
            return;
        }
        if (state == currentState && resId == lastResId && overlayStateStrip.getDrawable() != null) {
            Log.d(TAG, "updateState: no-op (same state=" + state + ")");
            return;
        }
        Drawable newDrawable = AppCompatResources.getDrawable(context, resId);
        if (newDrawable == null) {
            Log.w(TAG, "updateState: drawable load failed resId=" + resId + " state=" + state);
            return;
        }
        if (overlayRoot == null || overlayStateStrip.getDrawable() == null || currentState == 0) {
            cancelAnimator();
            overlayStateStrip.setAlpha(1f);
            overlayStateStrip.setImageDrawable(newDrawable);
            Log.d(TAG, "updateState: applied immediately state=" + state + " resId=" + resId);
        } else {
            startCrossfade(newDrawable);
        }
        currentState = state;
        lastResId = resId;
    }

    void pauseAnimation() {
        if (overlayAnimator != null && overlayAnimator.isRunning()) {
            overlayAnimator.pause();
            Log.d(TAG, "Animation paused");
        }
    }

    void resumeAnimation() {
        if (overlayAnimator != null && overlayAnimator.isPaused()) {
            overlayAnimator.resume();
            Log.d(TAG, "Animation resumed");
        }
    }

    void cancelAnimator() {
        if (overlayAnimator != null) {
            overlayAnimator.cancel();
            overlayAnimator = null;
        }
        if (overlayRoot != null && overlayCrossfadeView != null) {
            overlayRoot.removeView(overlayCrossfadeView);
            overlayCrossfadeView = null;
        }
    }

    private void startCrossfade(Drawable newDrawable) {
        cancelAnimator();
        if (overlayRoot == null || overlayStateStrip == null) {
            return;
        }
        Drawable oldDrawable = overlayStateStrip.getDrawable();
        if (oldDrawable == null) {
            overlayStateStrip.setImageDrawable(newDrawable);
            overlayStateStrip.setAlpha(1f);
            return;
        }
        overlayStateStrip.setAlpha(1f);
        overlayStateStrip.setImageDrawable(oldDrawable);

        overlayCrossfadeView = new ImageView(context);
        int width = overlayStateStrip.getWidth();
        int height = overlayStateStrip.getHeight();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                width > 0 ? width : FrameLayout.LayoutParams.MATCH_PARENT,
                height > 0 ? height : FrameLayout.LayoutParams.MATCH_PARENT
        );
        overlayCrossfadeView.setLayoutParams(lp);
        overlayCrossfadeView.setScaleType(overlayStateStrip.getScaleType());
        overlayCrossfadeView.setAdjustViewBounds(overlayStateStrip.getAdjustViewBounds());
        overlayCrossfadeView.setImageDrawable(newDrawable);
        overlayCrossfadeView.setAlpha(0f);
        overlayRoot.setClipToPadding(false);
        overlayRoot.setClipChildren(false);
        overlayRoot.addView(overlayCrossfadeView);

        overlayAnimator = ValueAnimator.ofFloat(0f, 1f);
        overlayAnimator.setDuration(animDurationMs);
        overlayAnimator.addUpdateListener(anim -> {
            float t = (float) anim.getAnimatedValue();
            if (t <= 0.5f) {
                float local = t / 0.5f;
                float eased = local * local * (3f - 2f * local);
                overlayCrossfadeView.setAlpha(eased);
                overlayStateStrip.setAlpha(1f);
            } else {
                float local = (t - 0.5f) / 0.5f;
                float eased = local * local * (3f - 2f * local);
                overlayCrossfadeView.setAlpha(1f);
                overlayStateStrip.setAlpha(1f - eased);
            }
        });
        overlayAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                finishCrossfade(newDrawable);
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                finishCrossfade(newDrawable);
            }
        });
        overlayAnimator.start();
    }

    private void finishCrossfade(Drawable finalDrawable) {
        if (overlayStateStrip != null) {
            overlayStateStrip.setImageDrawable(finalDrawable);
            overlayStateStrip.setAlpha(1f);
        }
        if (overlayRoot != null && overlayCrossfadeView != null) {
            overlayRoot.removeView(overlayCrossfadeView);
        }
        overlayCrossfadeView = null;
        overlayAnimator = null;
    }

    private int resolveDrawableForState(int state) {
        switch (state) {
            case 1:
                return R.drawable.state_01_green;
            case 2:
                return R.drawable.state_02_yellow;
            case 3:
                return R.drawable.state_03_red_yellow;
            case 4:
                return R.drawable.state_04_red;
            case 5:
                return R.drawable.state_05_white;
            default:
                return 0;
        }
    }
}
