package com.example.androidbuttons;

import android.graphics.Rect;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ArrayAdapter;

import androidx.appcompat.app.AppCompatActivity;

import com.example.androidbuttons.databinding.ActivitySettingsBinding;

// Экран настроек: IP/порт TCP, выбор локомотива, параметры overlay и лог
public class SettingsActivity extends AppCompatActivity {
    private ActivitySettingsBinding binding;
    private final java.util.Timer timer = new java.util.Timer("settings-console", true);
    private final StringBuilder consoleRemainder = new StringBuilder();
    private final java.util.Timer statusTimer = new java.util.Timer("settings-status", true);
    private android.content.SharedPreferences prefs;

    private static final int CONSOLE_MAX_CHARS = 20000; // ограничение размера консоли

    // Временные значения полей (до записи в prefs)
    private String pendingHost;
    private String pendingPort;
    private String pendingOverlayX;
    private String pendingOverlayY;
    private String pendingOverlayScale;
    private boolean pendingDirty = false;
    private boolean suppressWatchers = false;

    // Отслеживание состояния клавиатуры
    private boolean keyboardVisible = false;
    private ViewTreeObserver.OnGlobalLayoutListener keyboardListener;

    // Приём обновлений позиции/масштаба overlay
    private android.content.BroadcastReceiver overlayUpdateReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Делаем лог прокручиваемым
        binding.textConsole.setMovementMethod(new ScrollingMovementMethod());

        // Получаем SharedPreferences
        prefs = getSharedPreferences(AppState.PREFS_NAME, MODE_PRIVATE);

        // Загружаем стартовые значения в поля
        refreshValuesFromPreferences();

        // Отслеживаем изменения IP (пока только помечаем как pending)
        binding.valueAddrTCP.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                if (suppressWatchers) return;
                pendingHost = String.valueOf(s);
                pendingDirty = true;
            }
        });

        // Отслеживаем изменения порта
        binding.valuePortTCP.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                if (suppressWatchers) return;
                pendingPort = String.valueOf(s);
                pendingDirty = true;
            }
        });

        // X координата overlay (редактирование + немедленное применение)
        binding.valueOverlayX.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                if (suppressWatchers) return;
                pendingOverlayX = String.valueOf(s);
                pendingDirty = true;

                Integer xValue = parseIntSafe(pendingOverlayX, -10000, 10000);
                if (xValue != null) {
                    xValue = eliminateTinyOffset(xValue);
                    prefs.edit().putInt(AppState.KEY_OVERLAY_X, xValue).apply();

                    // Шлём broadcast для мгновенного применения координаты X
                    android.content.Intent intent = new android.content.Intent("com.example.androidbuttons.APPLY_POSITION_NOW");
                    intent.putExtra("x", xValue);
                    sendBroadcast(intent);
                }
            }
        });

        // Y координата overlay (редактирование + немедленное применение)
        binding.valueOverlayY.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                if (suppressWatchers) return;
                pendingOverlayY = String.valueOf(s);
                pendingDirty = true;

                Integer yValue = parseIntSafe(pendingOverlayY, -10000, 10000);
                if (yValue != null) {
                    prefs.edit().putInt(AppState.KEY_OVERLAY_Y, yValue).apply();

                    // Шлём broadcast для мгновенного применения координаты Y
                    android.content.Intent intent = new android.content.Intent("com.example.androidbuttons.APPLY_POSITION_NOW");
                    intent.putExtra("y", yValue);
                    sendBroadcast(intent);
                }
            }
        });

        // Ползунок масштаба overlay
        binding.seekbarOverlayScale.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                // Переводим позицию слайдера в масштаб
                float scale = 0.1f + (progress * 0.05f);
                binding.textScaleValue.setText(String.format(java.util.Locale.US, "%.2f", scale));

                if (fromUser) {
                    pendingOverlayScale = String.valueOf(scale);
                    pendingDirty = true;

                    // Сохраняем масштаб в prefs
                    prefs.edit().putFloat(AppState.KEY_OVERLAY_SCALE, scale).apply();

                    // Шлём broadcast, чтобы сразу изменить размер overlay
                    android.content.Intent intent = new android.content.Intent("com.example.androidbuttons.APPLY_SCALE_NOW");
                    intent.putExtra("scale", scale);
                    sendBroadcast(intent);
                    android.util.Log.d("SettingsActivity", "SeekBar changed scale to: " + scale);
                }
            }

            @Override public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
        });

        // Формируем список Loco1..Loco8 для спиннера
        String[] locoItems = new String[8];
        for (int i = 0; i < 8; i++) locoItems[i] = "Loco" + (i + 1);

        // Настраиваем адаптер спиннера
        ArrayAdapter<String> locoAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                locoItems
        );
        locoAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.spinnerNum.setAdapter(locoAdapter);

        // Выставляем текущий локомотив из AppState
        binding.spinnerNum.setSelection(Math.max(0, AppState.selectedLoco.get() - 1));

        // Сохраняем выбранный локомотив в AppState
        binding.spinnerNum.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                AppState.selectedLoco.set(position + 1);
            }

            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        // Обработка переключателя "Разрешить изменение окна"
        binding.switchAllowOverlayModification.setOnCheckedChangeListener((buttonView, isChecked) -> {
            android.util.Log.e("SettingsActivity", "═══════ SWITCH CLICKED: isChecked=" + isChecked + " ═══════");

            // Сохраняем флаг в prefs синхронно
            prefs.edit()
                    .putBoolean(AppState.KEY_OVERLAY_ALLOW_MODIFICATION, isChecked)
                    .commit();

            boolean saved = prefs.getBoolean(AppState.KEY_OVERLAY_ALLOW_MODIFICATION, true);
            android.util.Log.e("SettingsActivity", "═══════ SAVED VALUE: " + saved + " ═══════");

            String status = isChecked ? "разрешено" : "запрещено";
            android.widget.Toast.makeText(this, "Изменение окна " + status, android.widget.Toast.LENGTH_SHORT).show();

            // Включаем/выключаем элементы редактирования overlay
            applyEditModeEnabled(isChecked);

            // При включении редактирования выставляем зелёное состояние (1)
            if (isChecked) {
                StateBus.publishStripState(1);
            }
        });

        // Восстанавливаем флаг редактирования overlay
        binding.switchAllowOverlayModification.setChecked(
                prefs.getBoolean(AppState.KEY_OVERLAY_ALLOW_MODIFICATION, true)
        );

        // Сразу применяем состояние edit-mode к полям
        applyEditModeEnabled(binding.switchAllowOverlayModification.isChecked());

        // Следим за внешними изменениями флага редактирования (например из сервиса)
        prefs.registerOnSharedPreferenceChangeListener((sharedPreferences, key) -> {
            if (AppState.KEY_OVERLAY_ALLOW_MODIFICATION.equals(key)) {
                boolean allow = sharedPreferences.getBoolean(AppState.KEY_OVERLAY_ALLOW_MODIFICATION, true);
                if (binding.switchAllowOverlayModification.isChecked() != allow) {
                    binding.switchAllowOverlayModification.setChecked(allow);
                }
                applyEditModeEnabled(allow);
            }
        });

        // Периодическая выгрузка лога из общей очереди в textConsole
        timer.scheduleAtFixedRate(new java.util.TimerTask() {
            @Override
            public void run() {
                StringBuilder sb = new StringBuilder();
                while (!AppState.consoleQueue.isEmpty()) {
                    String s = AppState.consoleQueue.poll();
                    if (s == null) break;
                    sb.append(s);
                }
                if (sb.length() > 0) {
                    String out = sb.toString();
                    runOnUiThread(() -> appendColored(out));
                }
            }
        }, 200, 200);

        // Периодическое обновление индикаторов TCP
        statusTimer.scheduleAtFixedRate(new java.util.TimerTask() {
            @Override
            public void run() {
                runOnUiThread(() -> {
                    boolean wasReachable = binding.switchTCPIndicator.isChecked();
                    boolean isReachable = AppState.tcpReachable;

                    // Обновляем индикатор "TCP доступен"
                    binding.switchTCPIndicator.setChecked(isReachable);

                    // Показываем прогресс, если идёт подключение
                    binding.progressBarTCPIndicator.setVisibility(
                            AppState.tcpConnecting ? View.VISIBLE : View.GONE
                    );

                    if (wasReachable != isReachable) {
                        android.util.Log.d(
                                "SettingsActivity",
                                "TCP reachability changed: " + wasReachable + " -> " + isReachable +
                                        " (connecting=" + AppState.tcpConnecting + ")"
                        );
                    }
                });
            }
        }, 0, 100);

        // Отслеживаем появление/скрытие клавиатуры
        setupKeyboardListener();

        // Принимаем broadcast'ы об изменении позиции и масштаба overlay
        setupOverlayUpdateReceiver();
    }

    // Регистрируем приёмник обновлений overlay (координаты и масштаб)
    private void setupOverlayUpdateReceiver() {
        overlayUpdateReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, android.content.Intent intent) {
                if (AppState.ACTION_OVERLAY_UPDATED.equals(intent.getAction())) {

                    // Обновляем X, если пришёл в интенте
                    if (intent.hasExtra(AppState.KEY_OVERLAY_X)) {
                        int x = intent.getIntExtra(AppState.KEY_OVERLAY_X, 0);
                        pendingOverlayX = String.valueOf(x);
                        suppressWatchers = true;
                        updateField(binding.valueOverlayX, pendingOverlayX);
                        suppressWatchers = false;
                        android.util.Log.d("SettingsActivity", "Overlay X updated from broadcast: " + x);
                    }

                    // Обновляем Y, если пришёл в интенте
                    if (intent.hasExtra(AppState.KEY_OVERLAY_Y)) {
                        int y = intent.getIntExtra(AppState.KEY_OVERLAY_Y, 0);
                        pendingOverlayY = String.valueOf(y);
                        suppressWatchers = true;
                        updateField(binding.valueOverlayY, pendingOverlayY);
                        suppressWatchers = false;
                        android.util.Log.d("SettingsActivity", "Overlay Y updated from broadcast: " + y);
                    }

                    // Обновляем масштаб, если он пришёл
                    if (intent.hasExtra(AppState.KEY_OVERLAY_SCALE)) {
                        float scale = intent.getFloatExtra(AppState.KEY_OVERLAY_SCALE, 1.0f);
                        pendingOverlayScale = String.valueOf(scale);
                        int progress = (int) Math.round((scale - 0.1f) / 0.05f);
                        binding.seekbarOverlayScale.setProgress(progress);
                        binding.textScaleValue.setText(String.format(java.util.Locale.US, "%.2f", scale));
                        android.util.Log.d("SettingsActivity", "Overlay scale updated from broadcast: " + scale);
                    }
                }
            }
        };

        android.content.IntentFilter filter = new android.content.IntentFilter(AppState.ACTION_OVERLAY_UPDATED);
        registerReceiver(overlayUpdateReceiver, filter);
    }

    // Включаем/выключаем элементы редактирования overlay (позиция, масштаб)
    private void applyEditModeEnabled(boolean allow) {
        binding.valueOverlayX.setEnabled(allow);
        binding.valueOverlayY.setEnabled(allow);
        binding.seekbarOverlayScale.setEnabled(allow);

        float alpha = allow ? 1f : 0.4f;
        binding.valueOverlayX.setAlpha(alpha);
        binding.valueOverlayY.setAlpha(alpha);
        binding.seekbarOverlayScale.setAlpha(alpha);
        binding.textScaleValue.setAlpha(alpha);
    }

    @Override
    protected void onResume() {
        super.onResume();
        android.util.Log.d(
                "SettingsActivity",
                "onResume() called. Current pending values: " +
                        "X=" + pendingOverlayX + " Y=" + pendingOverlayY +
                        " Scale=" + pendingOverlayScale
        );
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Останавливаем таймеры
        timer.cancel();
        statusTimer.cancel();

        // Убираем слушатель клавиатуры
        if (keyboardListener != null) {
            View root = binding.getRoot();
            root.getViewTreeObserver().removeOnGlobalLayoutListener(keyboardListener);
            keyboardListener = null;
        }

        // Отписываемся от overlay-обновлений
        if (overlayUpdateReceiver != null) {
            unregisterReceiver(overlayUpdateReceiver);
            overlayUpdateReceiver = null;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        android.util.Log.d(
                "SettingsActivity",
                "onPause() called. Saving pending values: " +
                        "X=" + pendingOverlayX + " Y=" + pendingOverlayY +
                        " Scale=" + pendingOverlayScale
        );
        // При уходе с экрана сохраняем pending значения
        applyPendingChanges(true);
    }

    // Добавляет в лог строки с цветовой подсветкой префиксов
    private void appendColored(String text) {
        consoleRemainder.append(text);
        int idx;
        while ((idx = indexOfNewline(consoleRemainder)) >= 0) {
            String line = consoleRemainder.substring(0, idx + 1);
            consoleRemainder.delete(0, idx + 1);

            int color = -1;
            int removeLen = 0;
            if (line.startsWith("[UART→]")) {
                color = 0xFF90EE90; removeLen = "[UART→]".length();
            } else if (line.startsWith("[UART←]")) {
                color = 0xFF006400; removeLen = "[UART←]".length();
            } else if (line.startsWith("[#TCP_TX#]")) {
                color = 0xFF87CEFA; removeLen = "[#TCP_TX#]".length();
            } else if (line.startsWith("[#TCP_RX#]")) {
                color = 0xFF0000FF; removeLen = "[#TCP_RX#]".length();
            }

            if (removeLen > 0 && removeLen <= line.length()) {
                line = line.substring(removeLen);
            }
            if (color != -1) {
                SpannableString ss = new SpannableString(line);
                ss.setSpan(new ForegroundColorSpan(color), 0, ss.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                binding.textConsole.append(ss);
            } else {
                binding.textConsole.append(line);
            }
        }

        int scrollAmount = binding.textConsole.getLayout() != null
                ? binding.textConsole.getLayout().getLineTop(binding.textConsole.getLineCount()) - binding.textConsole.getHeight()
                : 0;
        if (scrollAmount > 0) {
            binding.textConsole.scrollTo(0, scrollAmount);
        }

        trimConsoleIfNeeded();
    }

    // Обрезает лог в textConsole, если превышен лимит символов
    private void trimConsoleIfNeeded() {
        CharSequence cs = binding.textConsole.getText();
        if (cs == null) {
            return;
        }
        if (!(cs instanceof android.text.Editable)) {
            return;
        }
        android.text.Editable editable = (android.text.Editable) cs;
        int len = editable.length();
        if (len <= CONSOLE_MAX_CHARS) {
            return;
        }
        int keepFrom = len - CONSOLE_MAX_CHARS;
        editable.delete(0, keepFrom);
    }



    // Ищем индекс первого '\n' в StringBuilder
    private static int indexOfNewline(StringBuilder sb) {
        for (int i = 0; i < sb.length(); i++) {
            if (sb.charAt(i) == '\n') return i;
        }
        return -1;
    }

    // Отслеживаем появление и скрытие клавиатуры
    private void setupKeyboardListener() {
        View root = binding.getRoot();
        keyboardListener = () -> {
            Rect r = new Rect();
            root.getWindowVisibleDisplayFrame(r);
            int screenHeight = root.getRootView().getHeight();
            int keyboardHeight = screenHeight - r.bottom;
            boolean visible = keyboardHeight > screenHeight * 0.15f;
            if (keyboardVisible != visible) {
                keyboardVisible = visible;
                android.util.Log.d(
                        "SettingsActivity",
                        "Keyboard visibility changed: " + visible +
                                ". Current scale: " + pendingOverlayScale
                );
                // При закрытии клавиатуры применяем отложенные изменения
                if (!visible) {
                    applyPendingChanges(false);
                }
            }
        };
        root.getViewTreeObserver().addOnGlobalLayoutListener(keyboardListener);
    }

    // Читаем актуальные значения из prefs и заливаем в поля
    private void refreshValuesFromPreferences() {
        String host = prefs.getString(AppState.KEY_TCP_HOST, "192.168.2.6");
        int port = prefs.getInt(AppState.KEY_TCP_PORT, 9000);
        int overlayX = eliminateTinyOffset(prefs.getInt(AppState.KEY_OVERLAY_X, 0));
        int overlayY = prefs.getInt(AppState.KEY_OVERLAY_Y, 0);
        float overlayScale = prefs.getFloat(AppState.KEY_OVERLAY_SCALE, 1.0f);

        pendingHost = host;
        pendingPort = String.valueOf(port);
        pendingOverlayX = String.valueOf(overlayX);
        pendingOverlayY = String.valueOf(overlayY);
        pendingOverlayScale = String.valueOf(overlayScale);

        android.util.Log.d(
                "SettingsActivity",
                "Loaded overlay params: X=" + overlayX + " Y=" + overlayY +
                        " Scale=" + overlayScale
        );

        updateField(binding.valueAddrTCP, pendingHost);
        updateField(binding.valuePortTCP, pendingPort);
        updateField(binding.valueOverlayX, pendingOverlayX);
        updateField(binding.valueOverlayY, pendingOverlayY);

        int progress = (int) Math.round((overlayScale - 0.1f) / 0.05f);
        binding.seekbarOverlayScale.setProgress(progress);
        binding.textScaleValue.setText(String.format(java.util.Locale.US, "%.2f", overlayScale));

        pendingDirty = false;
    }

    // Сохраняем pending-значения в prefs (с валидацией)
    private void applyPendingChanges(boolean force) {
        if (!force && !pendingDirty) {
            return;
        }

        // IP
        String hostValue = pendingHost != null ? pendingHost.trim() : "";
        if (hostValue.isEmpty()) {
            hostValue = prefs.getString(AppState.KEY_TCP_HOST, "192.168.2.6");
        }

        // Порт
        Integer portValue = parseIntSafe(pendingPort, 1, 65535);
        if (portValue == null) {
            portValue = prefs.getInt(AppState.KEY_TCP_PORT, 9000);
        }

        // X overlay
        Integer overlayXValue = parseIntSafe(pendingOverlayX, -10000, 10000);
        if (overlayXValue == null) {
            overlayXValue = prefs.getInt(AppState.KEY_OVERLAY_X, 0);
        }
        overlayXValue = eliminateTinyOffset(overlayXValue);

        // Y overlay
        Integer overlayYValue = parseIntSafe(pendingOverlayY, -10000, 10000);
        if (overlayYValue == null) {
            overlayYValue = prefs.getInt(AppState.KEY_OVERLAY_Y, 0);
        }

        // Масштаб overlay
        Float overlayScaleValue = parseFloatSafe(pendingOverlayScale, 0.1f, 5.0f);
        if (overlayScaleValue == null) {
            overlayScaleValue = prefs.getFloat(AppState.KEY_OVERLAY_SCALE, 1.0f);
        }

        boolean changed = false;
        android.content.SharedPreferences.Editor editor = prefs.edit();

        if (!hostValue.equals(prefs.getString(AppState.KEY_TCP_HOST, ""))) {
            editor.putString(AppState.KEY_TCP_HOST, hostValue);
            changed = true;
        }
        if (portValue != prefs.getInt(AppState.KEY_TCP_PORT, 9000)) {
            editor.putInt(AppState.KEY_TCP_PORT, portValue);
            changed = true;
        }
        if (overlayXValue != prefs.getInt(AppState.KEY_OVERLAY_X, 0)) {
            editor.putInt(AppState.KEY_OVERLAY_X, overlayXValue);
            changed = true;
        }
        if (overlayYValue != prefs.getInt(AppState.KEY_OVERLAY_Y, 0)) {
            editor.putInt(AppState.KEY_OVERLAY_Y, overlayYValue);
            changed = true;
        }
        if (Math.abs(overlayScaleValue - prefs.getFloat(AppState.KEY_OVERLAY_SCALE, 1.0f)) > 0.001f) {
            editor.putFloat(AppState.KEY_OVERLAY_SCALE, overlayScaleValue);
            changed = true;
        }

        if (changed) {
            editor.apply();
            android.util.Log.d(
                    "SettingsActivity",
                    "Saved overlay params: X=" + overlayXValue + " Y=" + overlayYValue +
                            " Scale=" + overlayScaleValue
            );
        }

        pendingHost = hostValue;
        pendingPort = String.valueOf(portValue);
        pendingOverlayX = String.valueOf(overlayXValue);
        pendingOverlayY = String.valueOf(overlayYValue);
        pendingOverlayScale = String.valueOf(overlayScaleValue);
        pendingDirty = false;

        updateField(binding.valueAddrTCP, pendingHost);
        updateField(binding.valuePortTCP, pendingPort);
        updateField(binding.valueOverlayX, pendingOverlayX);
        updateField(binding.valueOverlayY, pendingOverlayY);

        int progress = (int) Math.round((overlayScaleValue - 0.1f) / 0.05f);
        binding.seekbarOverlayScale.setProgress(progress);
        binding.textScaleValue.setText(String.format(java.util.Locale.US, "%.2f", overlayScaleValue));
    }

    // Безопасный разбор int в заданном диапазоне
    private Integer parseIntSafe(String value, int min, int max) {
        if (value == null) return null;
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < min || parsed > max) {
                return null;
            }
            return parsed;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    // Сносим мелкий сдвиг координаты к 0
    private int eliminateTinyOffset(int valuePx) {
        return Math.abs(valuePx) <= 12 ? 0 : valuePx;
    }

    // Безопасный разбор float в заданном диапазоне
    private Float parseFloatSafe(String value, float min, float max) {
        if (value == null) return null;
        try {
            float parsed = Float.parseFloat(value.trim());
            if (parsed < min || parsed > max) {
                return null;
            }
            return parsed;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // Обновляем EditText без срабатывания вотчеров
    private void updateField(android.widget.EditText field, String value) {
        String target = value != null ? value : "";
        String current = field.getText() != null ? field.getText().toString() : "";
        if (current.equals(target)) {
            return;
        }
        suppressWatchers = true;
        field.setText(target);
        field.setSelection(target.length());
        suppressWatchers = false;
    }

    // Посылаем сервису команду пересоздать overlay (сейчас не используется)
    private void restartOverlayService() {
        android.content.Intent recreateIntent = new android.content.Intent(this, FloatingOverlayService.class);
        recreateIntent.setAction(FloatingOverlayService.ACTION_RECREATE_OVERLAY);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(recreateIntent);
        } else {
            startService(recreateIntent);
        }
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    @Override
    public boolean onSupportNavigateUp() {
        getOnBackPressedDispatcher().onBackPressed();
        return true;
    }
}
