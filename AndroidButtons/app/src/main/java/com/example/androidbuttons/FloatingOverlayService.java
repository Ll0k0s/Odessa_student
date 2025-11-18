package com.example.androidbuttons;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.appcompat.content.res.AppCompatResources;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Foreground-сервис поверх всех экранов, показывающий диагностическую полоску.
 * Управляет отображением состояния, жестами перемещения/масштабирования и прозрачностью.
 */
public class FloatingOverlayService extends Service {

	private static final String TAG = "FloatingOverlayService";
	public static final String ACTION_RECREATE_OVERLAY = "com.example.androidbuttons.action.RECREATE_OVERLAY";
	private static final String CHANNEL_ID = "overlay_probe_channel";
	private static final int NOTIFICATION_ID = 1001;
	private static final long HEARTBEAT_INTERVAL_MS = 3000L;
	private static final long STRIP_ANIM_DURATION = 1000L;  // Увеличено для более плавной анимации

	// Базовые размеры overlay в пикселях (соотношение 476×2047 сведено к 100×430)
	private static final int BASE_WIDTH_PX = 100;
	private static final int BASE_HEIGHT_PX = 430;

	// Прозрачность в режимах
	private static final float ALPHA_EDIT_MODE = 0.7f;
	private static final float ALPHA_NORMAL = 1.0f;

	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	private final AtomicBoolean overlayAttached = new AtomicBoolean(false);

	// Listener для изменений preferences (ВАЖНО: хранить сильную ссылку, иначе GC удалит!)
	private android.content.SharedPreferences.OnSharedPreferenceChangeListener preferenceListener;

	// BroadcastReceiver’ы для мгновенного применения позиции/масштаба
	private android.content.BroadcastReceiver scaleReceiver;
	private android.content.BroadcastReceiver positionReceiver;

	private WindowManager windowManager;
	private View overlayView;
	private WindowManager.LayoutParams overlayParams;
	private FrameLayout overlayRoot;
	private ImageView overlayStateStrip;
	private ImageView overlayCrossfadeView;
	private ValueAnimator overlayAnimator;
	private int currentState = 0;
	private int lastResId = 0;

	// Поля для отслеживания перемещения и изменения размера
	private float initialTouchX;
	private float initialTouchY;
	private int initialX;
	private int initialY;

	// Поля для pinch-to-zoom (масштабирование двумя пальцами)
	private float initialDistance = 0;
	private float initialScale = 1.0f;
	private boolean isScaling = false;
	private float scalePivotX, scalePivotY; // Центр масштабирования на экране
	private int initialWidth, initialHeight; // Размеры окна в начале масштабирования
	private boolean animationPausedForScaling = false; // Флаг паузы анимации во время масштабирования
	// Дополнительно для сглаживания pinch-to-zoom
	private float lastDistanceDuringScale = 0f;
	private float smoothedScale = 1.0f;
	private long lastScaleTs = 0L;

	// Режим жеста: запрещаем одновременное перемещение и масштабирование
	private enum GestureMode { NONE, MOVE, SCALE }
	private GestureMode gestureMode = GestureMode.NONE;

	// Флаги для подавления лишних MOVE после pinch-жеста
	private boolean didMoveDuringGesture = false; // трекер реального движения
	private boolean suppressMoveUntilUp = false;  // блокировка MOVE до окончательного отпускания

	/**
	 * Слушатель событий от основной активити. Каждый вызов транслируем на главный поток, так как
	 * StateBus может прислать событие из произвольного потока.
	 */
	private final StateBus.StripStateListener stripStateListener = state ->
		mainHandler.post(() -> {
			android.content.SharedPreferences p = getSharedPreferences(AppState.PREFS_NAME, MODE_PRIVATE);
			boolean allowModification = p.getBoolean(AppState.KEY_OVERLAY_ALLOW_MODIFICATION, true);
			if (allowModification) {
				// Если уже что-то отображено — блокируем, иначе разрешаем первый paint
				if (currentState != 0) {
					return;
				}
			}
			updateOverlayState(state);
		});

	private final Runnable heartbeatRunnable = new Runnable() {
		@Override
		public void run() {
			refreshOverlayStatus();
			mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS);
		}
	};

	@Override
	public void onCreate() {
		super.onCreate();
		ensureChannel();
		windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

		android.content.SharedPreferences prefs = getSharedPreferences(AppState.PREFS_NAME, MODE_PRIVATE);

		// Слушатель изменений SharedPreferences для автоматического применения прозрачности
		preferenceListener = (sharedPreferences, key) -> {
			if (AppState.KEY_OVERLAY_ALLOW_MODIFICATION.equals(key)) {
				boolean allow = sharedPreferences.getBoolean(AppState.KEY_OVERLAY_ALLOW_MODIFICATION, true);
				float targetAlpha = allow ? ALPHA_EDIT_MODE : ALPHA_NORMAL;

				// Применяем прозрачность немедленно через mainHandler
				mainHandler.post(() -> {
					if (overlayView != null) {
						overlayView.setAlpha(targetAlpha);
					}
				});
			}
		};
		prefs.registerOnSharedPreferenceChangeListener(preferenceListener);

		// Регистрируем receiver для немедленного применения масштаба
		scaleReceiver = new android.content.BroadcastReceiver() {
			@Override
			public void onReceive(android.content.Context context, android.content.Intent intent) {
				if ("com.example.androidbuttons.APPLY_SCALE_NOW".equals(intent.getAction())) {
					float scale = intent.getFloatExtra("scale", 1.0f);
					applyScale(scale);
				}
			}
		};
		android.content.IntentFilter scaleFilter = new android.content.IntentFilter("com.example.androidbuttons.APPLY_SCALE_NOW");
		registerReceiver(scaleReceiver, scaleFilter);

		// Регистрируем receiver для немедленного применения позиции
		positionReceiver = new android.content.BroadcastReceiver() {
			@Override
			public void onReceive(android.content.Context context, android.content.Intent intent) {
				if ("com.example.androidbuttons.APPLY_POSITION_NOW".equals(intent.getAction())) {
					if (overlayParams == null || windowManager == null) return;

					if (intent.hasExtra("x")) {
						int x = intent.getIntExtra("x", overlayParams.x);
						int compensatedX = (x == 0) ? -computeLeftCompensation() : x;
						overlayParams.x = compensatedX;
					}
					if (intent.hasExtra("y")) {
						overlayParams.y = intent.getIntExtra("y", overlayParams.y);
					}

					try {
						windowManager.updateViewLayout(overlayView, overlayParams);
					} catch (Exception e) {
					}
				}
			}
		};
		android.content.IntentFilter positionFilter = new android.content.IntentFilter("com.example.androidbuttons.APPLY_POSITION_NOW");
		registerReceiver(positionReceiver, positionFilter);

		boolean allowAtStart = prefs.getBoolean(AppState.KEY_OVERLAY_ALLOW_MODIFICATION, true);
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		Notification notification = buildNotification();
		startForeground(NOTIFICATION_ID, notification);

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
		mainHandler.removeCallbacks(heartbeatRunnable);
		detachOverlay();

		// Снимаем listener преференсов
		if (preferenceListener != null) {
			try {
				android.content.SharedPreferences prefs = getSharedPreferences(AppState.PREFS_NAME, MODE_PRIVATE);
				prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener);
			} catch (Exception e) {
			}
			preferenceListener = null;
		}

		// Снимаем broadcast receiver масштаба
		if (scaleReceiver != null) {
			try {
				unregisterReceiver(scaleReceiver);
			} catch (IllegalArgumentException e) {
			}
			scaleReceiver = null;
		}

		// Снимаем broadcast receiver позиции
		if (positionReceiver != null) {
			try {
				unregisterReceiver(positionReceiver);
			} catch (IllegalArgumentException e) {
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

	/**
	 * Пытаемся прикрепить overlay к WindowManager. Метод безопасно выходит, если уже прикреплены
	 * либо нет разрешения. Все операции оборачиваем в try/catch — WindowManager бросает исключения
	 * при изменении конфигурации/разрешений.
	 */
	private void maybeAttachOverlay() {
		if (overlayAttached.get()) {
			return;
		}
		if (windowManager == null) {
			return;
		}
		if (!canDrawOverlays()) {
			return;
		}

		try {
			LayoutInflater inflater = LayoutInflater.from(this);
			overlayView = inflater.inflate(R.layout.overlay_probe, null, false);
			overlayRoot = overlayView.findViewById(R.id.overlayProbeRoot);
			if (overlayRoot == null && overlayView instanceof FrameLayout) {
				overlayRoot = (FrameLayout) overlayView;
			}
			overlayCrossfadeView = null;
			overlayAnimator = null;

			overlayParams = buildDefaultLayoutParams();
			windowManager.addView(overlayView, overlayParams);
			overlayAttached.set(true);

			// КРИТИЧНО: перечитываем актуальное значение разрешения ПРЯМО СЕЙЧАС
			android.content.SharedPreferences currentPrefs = getSharedPreferences(AppState.PREFS_NAME, MODE_PRIVATE);
			boolean currentAllow = currentPrefs.getBoolean(AppState.KEY_OVERLAY_ALLOW_MODIFICATION, true);
			float actualAlpha = currentAllow ? ALPHA_EDIT_MODE : ALPHA_NORMAL;

			setOverlayAlpha(actualAlpha);

			// --- ИНИЦИАЛИЗАЦИЯ СТАРТОВОГО СОСТОЯНИЯ ---
			int existing = StateBus.getCurrentState();
			if (existing <= 0) {
				StateBus.publishStripState(1); // фиксируем зелёный глобально и для других компонентов
				existing = 1;
			}
			updateOverlayState(existing);

			// Применяем скругления с текущим масштабом
			float currentScale = currentPrefs.getFloat(AppState.KEY_OVERLAY_SCALE, 1.0f);
			updateCornerRadius(currentScale);

			setupOverlayInteractions();
			refreshOverlayStatus();
			StateBus.registerStateListener(stripStateListener);
			mainHandler.removeCallbacks(heartbeatRunnable);
			mainHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS);
		} catch (RuntimeException ex) {
			overlayAttached.set(false);
			overlayView = null;
		}
	}

	/**
	 * Настраивает обработчики жестов полосы для перемещения и изменения размера.
	 */
	private void setupOverlayInteractions() {
		if (overlayView == null) {
			return;
		}
		overlayStateStrip = overlayView.findViewById(R.id.overlayStateStrip);
		if (overlayStateStrip != null) {
			overlayStateStrip.setOnTouchListener(this::handleOverlayTouch);
		}
	}

	/**
	 * Обрабатывает касания по полосе и переводит их в условные зоны (1..5).
	 * Сейчас используется только при отключённом режиме редактирования.
	 */
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

		android.content.SharedPreferences p = getSharedPreferences(AppState.PREFS_NAME, MODE_PRIVATE);
		boolean allowModification = p.getBoolean(AppState.KEY_OVERLAY_ALLOW_MODIFICATION, true);
		if (allowModification) {
			return true;
		}
		if (zone != currentState) {
			updateOverlayState(zone);
			StateBus.publishOverlaySelection(zone);
		}
		return true;
	}

	/**
	 * Обрабатывает touch события для перемещения (1 палец) и масштабирования (2 пальца) overlay окна.
	 * Если в настройках отключено "Разрешить изменение окна", все касания игнорируются.
	 */
	private boolean handleOverlayTouch(View view, MotionEvent event) {
		if (overlayParams == null || windowManager == null) {
			return false;
		}

		android.content.SharedPreferences prefs = getSharedPreferences(AppState.PREFS_NAME, MODE_PRIVATE);
		boolean allowModification = prefs.getBoolean(AppState.KEY_OVERLAY_ALLOW_MODIFICATION, true);

		if (!allowModification) {
			if (event.getAction() == MotionEvent.ACTION_UP) {
				return handleStripTouch(event);
			}
			return true;
		} else {
			if (event.getAction() == MotionEvent.ACTION_UP) {
			}
		}

		int action = event.getActionMasked();
		int pointerCount = event.getPointerCount();

		switch (action) {
			case MotionEvent.ACTION_DOWN:
				if (suppressMoveUntilUp && gestureMode == GestureMode.NONE) {
				}
				if (suppressMoveUntilUp) {
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
					float moved = Math.abs(event.getRawX(0) - initialTouchX) + Math.abs(event.getRawY(0) - initialTouchY);
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
						scalePivotX = (event.getRawX(0) + event.getRawX(1)) / 2f;
						scalePivotY = (event.getRawY(0) + event.getRawY(1)) / 2f;
						pauseAnimation();
						animationPausedForScaling = true;
					}
				}
				return true;

			case MotionEvent.ACTION_MOVE:
				if (gestureMode == GestureMode.SCALE && isScaling && pointerCount == 2) {
					float currentDistance = getDistance(event);
					if (initialDistance > 0) {
						if (lastDistanceDuringScale > 0) {
							float rawDelta = Math.abs(currentDistance - lastDistanceDuringScale) / lastDistanceDuringScale;
							if (rawDelta > 0.40f) {
								lastDistanceDuringScale = currentDistance;
								return true;
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
							return true;
						}
						int newWidth = Math.round(BASE_WIDTH_PX * smoothedScale);
						int newHeight = Math.round(BASE_HEIGHT_PX * smoothedScale);
						float pivotRelativeX = scalePivotX - initialX;
						float pivotRelativeY = scalePivotY - initialY;
						float pivotRatioX = pivotRelativeX / initialWidth;
						float pivotRatioY = pivotRelativeY / initialHeight;
						int newX = Math.round(scalePivotX - (newWidth * pivotRatioX));
						int newY = Math.round(scalePivotY - (newHeight * pivotRatioY));
						applyScaleWithPosition(smoothedScale, newX, newY);
						lastDistanceDuringScale = currentDistance;
					}
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
					}
				}
				return true;

			case MotionEvent.ACTION_POINTER_UP:
				if (gestureMode == GestureMode.SCALE) {
					isScaling = false;
					saveOverlayScale();
					if (animationPausedForScaling) {
						resumeAnimation();
						animationPausedForScaling = false;
					}
					gestureMode = GestureMode.NONE;
				}
				return true;

			case MotionEvent.ACTION_UP:
				if (gestureMode == GestureMode.SCALE) {
					isScaling = false;
					saveOverlayScale();
					if (animationPausedForScaling) {
						resumeAnimation();
						animationPausedForScaling = false;
					}
				} else if (gestureMode == GestureMode.MOVE) {
					android.content.SharedPreferences p = getSharedPreferences(AppState.PREFS_NAME, MODE_PRIVATE);
					int storedX = eliminateTinyOffset(p.getInt(AppState.KEY_OVERLAY_X, overlayParams.x));
					int storedY = p.getInt(AppState.KEY_OVERLAY_Y, overlayParams.y);
					boolean coordsChanged = (storedX != eliminateTinyOffset(overlayParams.x)) || (storedY != overlayParams.y);
					float totalDelta = Math.abs(event.getRawX() - initialTouchX) + Math.abs(event.getRawY() - initialTouchY);
					if (didMoveDuringGesture || coordsChanged || totalDelta >= dpToPx(2)) {
						saveOverlayPosition();
					}
				}
				gestureMode = GestureMode.NONE;
				if (pointerCount <= 1) {
					suppressMoveUntilUp = false;
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

	/**
	 * Вычисляет расстояние между двумя пальцами для pinch-to-zoom
	 */
	private float getDistance(MotionEvent event) {
		if (event.getPointerCount() < 2) {
			return 0;
		}
		float x = event.getX(0) - event.getX(1);
		float y = event.getY(0) - event.getY(1);
		return (float) Math.sqrt(x * x + y * y);
	}

	/**
	 * Применяет новый масштаб к overlay окну
	 */
	private void applyScale(float newScale) {
		if (overlayParams == null || windowManager == null || overlayView == null) {
			return;
		}

		int savedX = overlayParams.x;
		int savedY = overlayParams.y;

		int newWidth = Math.round(BASE_WIDTH_PX * newScale);
		int newHeight = Math.round(BASE_HEIGHT_PX * newScale);

		overlayParams.width = newWidth;
		overlayParams.height = newHeight;
		overlayParams.x = savedX;
		overlayParams.y = savedY;

		updateCornerRadius(newScale);

		try {
			windowManager.updateViewLayout(overlayView, overlayParams);
		} catch (Exception e) {
		}
	}

	/**
	 * Применяет новый масштаб и позицию к overlay окну одновременно
	 */
	private void applyScaleWithPosition(float newScale, int newX, int newY) {
		if (overlayParams == null || windowManager == null) {
			return;
		}

		int newWidth = Math.round(BASE_WIDTH_PX * newScale);
		int newHeight = Math.round(BASE_HEIGHT_PX * newScale);

		overlayParams.width = newWidth;
		overlayParams.height = newHeight;
		overlayParams.x = newX;
		overlayParams.y = newY;

		updateCornerRadius(newScale);

		try {
			windowManager.updateViewLayout(overlayView, overlayParams);
		} catch (Exception e) {
		}
	}

	/**
	 * Обновляет радиус скругления углов overlay окна пропорционально масштабу.
	 * Базовый радиус - 6dp, увеличивается/уменьшается с масштабом.
	 */
	private void updateCornerRadius(float scale) {
		if (overlayRoot == null) {
			return;
		}

		float baseRadiusDp = 6f;
		float density = getResources().getDisplayMetrics().density;
		float baseRadiusPx = baseRadiusDp * density;
		float newRadiusPx = baseRadiusPx * scale;

		android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
		background.setColor(0xFF121212);
		background.setCornerRadius(newRadiusPx);

		overlayRoot.setBackground(background);
		overlayRoot.setClipToOutline(true);

	}

	/**
	 * Сохраняет текущий масштаб в SharedPreferences
	 */
	private void saveOverlayScale() {
		if (overlayParams == null) {
			return;
		}

		float currentScale = (float) overlayParams.width / BASE_WIDTH_PX;
		currentScale = Math.round(currentScale * 100f) / 100f;

		android.content.SharedPreferences prefs = getSharedPreferences(AppState.PREFS_NAME, MODE_PRIVATE);
		prefs.edit()
				.putFloat(AppState.KEY_OVERLAY_SCALE, currentScale)
				.apply();

		android.content.Intent intent = new android.content.Intent(AppState.ACTION_OVERLAY_UPDATED);
		intent.putExtra(AppState.KEY_OVERLAY_SCALE, currentScale);
		sendBroadcast(intent);
	}

	/**
	 * Устанавливает прозрачность overlay окна
	 */
	private void setOverlayAlpha(float alpha) {
		if (overlayView == null) {
			return;
		}
		overlayView.setAlpha(alpha);
	}

	/**
	 * Сохраняет текущую позицию overlay окна в SharedPreferences.
	 * Размеры не сохраняются, так как вычисляются из scale.
	 */
	private void saveOverlayPosition() {
		if (overlayParams == null) {
			return;
		}

		android.content.SharedPreferences prefs = getSharedPreferences(AppState.PREFS_NAME, MODE_PRIVATE);
		int logicalX = overlayParams.x;
		if (logicalX < 0 && Math.abs(logicalX) <= computeLeftCompensation() + 2) {
			logicalX = 0;
		}
		int clampedX = eliminateTinyOffset(logicalX);

		prefs.edit()
				.putInt(AppState.KEY_OVERLAY_X, clampedX)
				.putInt(AppState.KEY_OVERLAY_Y, overlayParams.y)
				.apply();

		android.content.Intent intent = new android.content.Intent(AppState.ACTION_OVERLAY_UPDATED);
		intent.putExtra(AppState.KEY_OVERLAY_X, clampedX);
		intent.putExtra(AppState.KEY_OVERLAY_Y, overlayParams.y);
		sendBroadcast(intent);
	}

	private void refreshOverlayStatus() {
		if (overlayStateStrip == null) {
			return;
		}
		CharSequence timestamp = DateFormat.format("HH:mm:ss", System.currentTimeMillis());
		overlayStateStrip.setContentDescription(
				getString(R.string.overlay_state_strip_cd_with_time, timestamp));
	}

	/**
	 * Снимает overlay и очищает все ссылки/обработчики, чтобы не допустить утечек WindowManager.
	 */
	private void detachOverlay() {
		if (!overlayAttached.get()) {
			overlayView = null;
			return;
		}
		cancelOverlayAnimator();
		if (windowManager != null && overlayView != null) {
			try {
				windowManager.removeViewImmediate(overlayView);
			} catch (IllegalArgumentException ex) {
			}
		}
		StateBus.unregisterStateListener(stripStateListener);
		overlayAttached.set(false);
		overlayView = null;
		overlayParams = null;
		overlayRoot = null;
		overlayStateStrip = null;
		overlayCrossfadeView = null;
		currentState = 0;
		lastResId = 0;
	}

	/**
	 * Создаёт малошумное foreground-уведомление. Канал создаётся отдельно, но здесь задаём иконку
	 * и PendingIntent для возврата в приложение.
	 */
	private Notification buildNotification() {
		Intent launchIntent = new Intent(this, MainActivity.class);
		launchIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

		PendingIntent contentIntent = PendingIntent.getActivity(
				this,
				0,
				launchIntent,
				Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
						? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
						: PendingIntent.FLAG_UPDATE_CURRENT
		);

		return new NotificationCompat.Builder(this, CHANNEL_ID)
				.setContentTitle(getString(R.string.app_name))
				.setSmallIcon(R.drawable.ic_notification_light)
				.setPriority(NotificationCompat.PRIORITY_MIN)
				.setCategory(NotificationCompat.CATEGORY_SERVICE)
				.setSilent(true)
				.setVisibility(NotificationCompat.VISIBILITY_SECRET)
				.setContentIntent(contentIntent)
				.setOngoing(true)
				.build();
	}

	/**
	 * Формирует параметры расположения overlay.
	 * Позиция и масштаб восстанавливаются из SharedPreferences.
	 */
	private WindowManager.LayoutParams buildDefaultLayoutParams() {
		int layoutType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
				? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
				: WindowManager.LayoutParams.TYPE_PHONE;

		android.content.SharedPreferences prefs = getSharedPreferences(AppState.PREFS_NAME, MODE_PRIVATE);
		int defaultX = 0;
		int defaultY = 0;
		float defaultScale = 1.0f;

		int savedX = eliminateTinyOffset(prefs.getInt(AppState.KEY_OVERLAY_X, defaultX));
		int savedY = prefs.getInt(AppState.KEY_OVERLAY_Y, defaultY);
		float savedScale = prefs.getFloat(AppState.KEY_OVERLAY_SCALE, defaultScale);

		int finalWidth = Math.round(BASE_WIDTH_PX * savedScale);
		int finalHeight = Math.round(BASE_HEIGHT_PX * savedScale);

		if (!prefs.contains(AppState.KEY_OVERLAY_X)) {
			prefs.edit()
					.putInt(AppState.KEY_OVERLAY_X, defaultX)
					.putInt(AppState.KEY_OVERLAY_Y, defaultY)
					.putFloat(AppState.KEY_OVERLAY_SCALE, defaultScale)
					.apply();
		}

		WindowManager.LayoutParams params = new WindowManager.LayoutParams(
				finalWidth,
				finalHeight,
				layoutType,
				WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
						| WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
						| WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
				PixelFormat.TRANSLUCENT
		);
		params.gravity = Gravity.LEFT | Gravity.TOP;

		if (savedX == 0) {
			int compensation = computeLeftCompensation();
			params.x = -compensation;
		} else {
			params.x = savedX;
		}
		params.y = savedY;
		params.setTitle("FloatingOverlayProbe");
		return params;
	}

	private int dpToPx(int dp) {
		float density = getResources().getDisplayMetrics().density;
		return Math.round(dp * density);
	}

	private int eliminateTinyOffset(int valuePx) {
		return Math.abs(valuePx) <= 12 ? 0 : valuePx;
	}

	/**
	 * Пытаемся оценить системный левый inset (жестовая зона/скошенный край).
	 */
	private int computeLeftCompensation() {
		return 10; // px
	}

	private boolean canDrawOverlays() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
			return true;
		}
		return Settings.canDrawOverlays(this);
	}

	/**
	 * Создаёт/пересоздаёт канал уведомлений с минимальной важностью.
	 */
	private void ensureChannel() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
			return;
		}
		NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		if (manager == null) {
			return;
		}
		NotificationChannel channel = manager.getNotificationChannel(CHANNEL_ID);
		boolean needCreate = channel == null;
		if (!needCreate && channel.getImportance() > NotificationManager.IMPORTANCE_MIN) {
			manager.deleteNotificationChannel(CHANNEL_ID);
			needCreate = true;
		}
		if (needCreate) {
			channel = new NotificationChannel(
					CHANNEL_ID,
					"Overlay probe",
					NotificationManager.IMPORTANCE_MIN
			);
			channel.enableLights(false);
			channel.enableVibration(false);
			channel.setSound(null, null);
			channel.setDescription("Минимальный сервис для диагностики убиваний системой");
			channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
			manager.createNotificationChannel(channel);
		}
	}

	/**
	 * Обновляет изображение полосы.
	 */
	private void updateOverlayState(int state) {
		if (overlayStateStrip == null) {
			return;
		}
		int resId = resolveDrawableForState(state);
		if (resId == 0) {
			return;
		}
		if (state == currentState && resId == lastResId && overlayStateStrip.getDrawable() != null) {
			return;
		}
		Drawable newDrawable = AppCompatResources.getDrawable(this, resId);
		if (newDrawable == null) {
			return;
		}
		if (overlayRoot == null || overlayStateStrip.getDrawable() == null || currentState == 0) {
			cancelOverlayAnimator();
			overlayStateStrip.setAlpha(1f);
			overlayStateStrip.setImageDrawable(newDrawable);
		} else {
			startStripCrossfade(newDrawable);
		}
		currentState = state;
		lastResId = resId;
	}

	/**
	 * Плавно переключает изображение полосы с помощью дополнительного ImageView.
	 */
	private void startStripCrossfade(Drawable newDrawable) {
		cancelOverlayAnimator();
		if (overlayRoot == null) {
			overlayStateStrip.setImageDrawable(newDrawable);
			overlayStateStrip.setAlpha(1f);
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

		overlayCrossfadeView = new ImageView(this);
		int width = overlayStateStrip.getWidth();
		int height = overlayStateStrip.getHeight();
		FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
				width > 0 ? width : FrameLayout.LayoutParams.MATCH_PARENT,
				height > 0 ? height : FrameLayout.LayoutParams.MATCH_PARENT,
				Gravity.TOP | Gravity.START);
		overlayCrossfadeView.setLayoutParams(lp);
		overlayCrossfadeView.setScaleType(overlayStateStrip.getScaleType());
		overlayCrossfadeView.setAdjustViewBounds(overlayStateStrip.getAdjustViewBounds());
		overlayCrossfadeView.setImageDrawable(newDrawable);
		overlayCrossfadeView.setAlpha(0f);
		overlayRoot.setClipToPadding(false);
		overlayRoot.setClipChildren(false);
		overlayRoot.addView(overlayCrossfadeView);

		overlayAnimator = ValueAnimator.ofFloat(0f, 1f);
		overlayAnimator.setDuration(STRIP_ANIM_DURATION);
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
				finishStripCrossfade(newDrawable);
			}

			@Override
			public void onAnimationCancel(Animator animation) {
				finishStripCrossfade(newDrawable);
			}
		});
		overlayAnimator.start();
	}

	/**
	 * Завершает анимацию: переносит итоговый drawable на основной ImageView и убирает временное.
	 */
	private void finishStripCrossfade(Drawable finalDrawable) {
		overlayStateStrip.setImageDrawable(finalDrawable);
		overlayStateStrip.setAlpha(1f);
		if (overlayRoot != null && overlayCrossfadeView != null) {
			overlayRoot.removeView(overlayCrossfadeView);
		}
		overlayCrossfadeView = null;
		overlayAnimator = null;
	}

	private void cancelOverlayAnimator() {
		if (overlayAnimator != null) {
			overlayAnimator.cancel();
		} else if (overlayRoot != null && overlayCrossfadeView != null) {
			overlayRoot.removeView(overlayCrossfadeView);
			overlayCrossfadeView = null;
		}
	}

	/**
	 * Приостанавливает анимацию overlay окна
	 */
	private void pauseAnimation() {
		if (overlayAnimator != null && overlayAnimator.isRunning()) {
			overlayAnimator.pause();
		}
	}

	/**
	 * Возобновляет анимацию overlay окна
	 */
	private void resumeAnimation() {
		if (overlayAnimator != null && overlayAnimator.isPaused()) {
			overlayAnimator.resume();
		}
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
