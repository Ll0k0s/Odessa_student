package com.example.androidbuttons;

import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.androidbuttons.core.AppContracts;
import com.example.androidbuttons.core.AppGraph;
import com.example.androidbuttons.core.ConsoleLogRepository;
import com.example.androidbuttons.core.OverlaySettingsRepository;
import com.example.androidbuttons.core.OverlayStateStore;
import com.example.androidbuttons.core.ProtocolConstraints;
import com.example.androidbuttons.core.ServiceLaunchers;
import com.example.androidbuttons.core.TcpConfigRepository;
import com.example.androidbuttons.core.TcpStatusStore;
import com.example.androidbuttons.databinding.ActivitySettingsBinding;

import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

// Экран настроек: IP/порт TCP, выбор локомотива, параметры overlay и лог
public class SettingsActivity extends AppCompatActivity {
    private static final int CONSOLE_MAX_CHARS = 20000;

    private ActivitySettingsBinding binding;
    private final Timer consoleTimer = new Timer("settings-console", true);
    private final StringBuilder consoleRemainder = new StringBuilder();

    private OverlaySettingsRepository overlaySettingsRepository;
    private OverlaySettingsRepository.Listener overlaySettingsListener;
    private OverlaySettingsRepository.OverlaySettings overlaySettings;

    private TcpConfigRepository tcpConfigRepository;
    private TcpConfigRepository.Listener tcpConfigListener;
    private TcpConfigRepository.TcpConfig tcpConfig;

    private ConsoleLogRepository consoleLogRepository;
    private TcpStatusStore tcpStatusStore;
    private TcpStatusStore.Listener tcpStatusListener;
    private OverlayStateStore overlayStateStore;

    private String pendingHost;
    private String pendingPort;
    private String pendingOverlayX;
    private String pendingOverlayY;
    private String pendingOverlayScale;
    private boolean pendingDirty;
    private boolean suppressWatchers;
    private boolean suppressOverlaySwitch;

    private boolean keyboardVisible;
    private ViewTreeObserver.OnGlobalLayoutListener keyboardListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.textConsole.setMovementMethod(new ScrollingMovementMethod());

        ServiceLaunchers.ensureTcpServiceRunning(this);
        initGraph();
        refreshValuesFromState();
        setupHostAndPortWatchers();
        setupOverlayPositionWatchers();
        setupOverlayScaleSeekBar();
        setupLocoSpinner();
        setupOverlaySwitch();
        startConsolePump();
        setupKeyboardListener();
        updateStatusIndicators(tcpStatusStore.get());
    }

    private void initGraph() {
        AppGraph graph = AppGraph.get();
        overlaySettingsRepository = graph.overlaySettings();
        tcpConfigRepository = graph.tcpConfig();
        consoleLogRepository = graph.consoleLog();
        tcpStatusStore = graph.tcpStatuses();
        overlayStateStore = graph.overlayStates();

        overlaySettings = overlaySettingsRepository.get();
        tcpConfig = tcpConfigRepository.get();

        overlaySettingsListener = settings -> runOnUiThread(() -> handleOverlaySettingsChanged(settings));
        tcpConfigListener = config -> runOnUiThread(() -> handleTcpConfigChanged(config));
        tcpStatusListener = status -> runOnUiThread(() -> updateStatusIndicators(status));

        overlaySettingsRepository.addListener(overlaySettingsListener);
        tcpConfigRepository.addListener(tcpConfigListener);
        tcpStatusStore.addListener(tcpStatusListener);
    }

    private void handleOverlaySettingsChanged(OverlaySettingsRepository.OverlaySettings settings) {
        overlaySettings = settings;
        if (!keyboardVisible) {
            updateOverlayFields(settings);
        }
        boolean editModeEnabled = settings.editModeEnabled;
        if (binding.switchAllowOverlayModification.isChecked() != editModeEnabled) {
            suppressOverlaySwitch = true;
            binding.switchAllowOverlayModification.setChecked(editModeEnabled);
            suppressOverlaySwitch = false;
        }
        applyEditModeEnabled(editModeEnabled);
    }

    private void handleTcpConfigChanged(TcpConfigRepository.TcpConfig config) {
        tcpConfig = config;
        if (!keyboardVisible) {
            updateTcpFields(config);
        }
        int targetSelection = ProtocolConstraints.locoIndex(config.selectedLoco);
        if (binding.spinnerNum.getSelectedItemPosition() != targetSelection) {
            binding.spinnerNum.setSelection(targetSelection, false);
        }
    }

    private void setupHostAndPortWatchers() {
        binding.valueAddrTCP.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                if (suppressWatchers) return;
                pendingHost = String.valueOf(s);
                pendingDirty = true;
            }
        });

        binding.valuePortTCP.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                if (suppressWatchers) return;
                pendingPort = String.valueOf(s);
                pendingDirty = true;
            }
        });
    }

    private void setupOverlayPositionWatchers() {
        binding.valueOverlayX.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                if (suppressWatchers) return;
                pendingOverlayX = String.valueOf(s);
                pendingDirty = true;

                Integer xValue = parseIntSafe(pendingOverlayX, -10000, 10000);
                if (xValue != null) {
                    int sanitized = eliminateTinyOffset(xValue);
                    int currentY = currentOverlaySettings().y;
                    overlaySettingsRepository.setPosition(sanitized, currentY);
                    sendPositionBroadcast(sanitized, null);
                }
            }
        });

        binding.valueOverlayY.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                if (suppressWatchers) return;
                pendingOverlayY = String.valueOf(s);
                pendingDirty = true;

                Integer yValue = parseIntSafe(pendingOverlayY, -10000, 10000);
                if (yValue != null) {
                    int currentX = eliminateTinyOffset(currentOverlaySettings().x);
                    overlaySettingsRepository.setPosition(currentX, yValue);
                    sendPositionBroadcast(null, yValue);
                }
            }
        });
    }

    private void setupOverlayScaleSeekBar() {
        binding.seekbarOverlayScale.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float scale = 0.1f + (progress * 0.05f);
                binding.textScaleValue.setText(String.format(Locale.US, "%.2f", scale));
                if (fromUser) {
                    pendingOverlayScale = String.valueOf(scale);
                    pendingDirty = true;
                    overlaySettingsRepository.setScale(scale);
                    sendScaleBroadcast(scale);
                }
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void setupLocoSpinner() {
        String[] locoItems = new String[ProtocolConstraints.LOCOMOTIVE_COUNT];
        for (int i = 0; i < ProtocolConstraints.LOCOMOTIVE_COUNT; i++) {
            locoItems[i] = "Loco" + (ProtocolConstraints.LOCO_MIN + i);
        }
        ArrayAdapter<String> locoAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                locoItems
        );
        locoAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.spinnerNum.setAdapter(locoAdapter);
        binding.spinnerNum.setSelection(ProtocolConstraints.locoIndex(currentTcpConfig().selectedLoco), false);
        binding.spinnerNum.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int loco = ProtocolConstraints.locoFromIndex(position);
                TcpConfigRepository.TcpConfig current = currentTcpConfig();
                if (current.selectedLoco != loco) {
                    tcpConfigRepository.setSelectedLoco(loco);
                }
            }

            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    private void setupOverlaySwitch() {
        boolean editModeEnabled = currentOverlaySettings().editModeEnabled;
        suppressOverlaySwitch = true;
        binding.switchAllowOverlayModification.setChecked(editModeEnabled);
        suppressOverlaySwitch = false;
        applyEditModeEnabled(editModeEnabled);

        binding.switchAllowOverlayModification.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressOverlaySwitch) {
                return;
            }
            overlaySettingsRepository.setEditModeEnabled(isChecked);
            String status = isChecked ? "разрешено" : "запрещено";
            Toast.makeText(this, "Изменение окна " + status, Toast.LENGTH_SHORT).show();
            if (isChecked) {
                overlayStateStore.publish(1);
            }
        });
    }

    private void startConsolePump() {
        consoleTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                String drained = consoleLogRepository.drainAll();
                if (!drained.isEmpty()) {
                    runOnUiThread(() -> appendColored(drained));
                }
            }
        }, 200, 200);
    }

    private void updateOverlayFields(OverlaySettingsRepository.OverlaySettings settings) {
        pendingOverlayX = String.valueOf(eliminateTinyOffset(settings.x));
        pendingOverlayY = String.valueOf(settings.y);
        pendingOverlayScale = String.format(Locale.US, "%.2f", settings.scale);
        updateField(binding.valueOverlayX, pendingOverlayX);
        updateField(binding.valueOverlayY, pendingOverlayY);
        int progress = (int) Math.round((settings.scale - 0.1f) / 0.05f);
        binding.seekbarOverlayScale.setProgress(progress);
        binding.textScaleValue.setText(pendingOverlayScale);
    }

    private void updateTcpFields(TcpConfigRepository.TcpConfig config) {
        pendingHost = config.host;
        pendingPort = String.valueOf(config.port);
        updateField(binding.valueAddrTCP, pendingHost);
        updateField(binding.valuePortTCP, pendingPort);
    }

    private void updateStatusIndicators(TcpStatusStore.TcpStatus status) {
        binding.switchTCPIndicator.setChecked(status.reachable);
        binding.progressBarTCPIndicator.setVisibility(status.connecting ? View.VISIBLE : View.GONE);
    }

    // Включаем/выключаем элементы редактирования overlay (позиция, масштаб)
    private void applyEditModeEnabled(boolean editModeEnabled) {
        binding.valueOverlayX.setEnabled(editModeEnabled);
        binding.valueOverlayY.setEnabled(editModeEnabled);
        binding.seekbarOverlayScale.setEnabled(editModeEnabled);

        float alpha = editModeEnabled ? 1f : 0.4f;
        binding.valueOverlayX.setAlpha(alpha);
        binding.valueOverlayY.setAlpha(alpha);
        binding.seekbarOverlayScale.setAlpha(alpha);
        binding.textScaleValue.setAlpha(alpha);
    }

    @Override
    protected void onResume() {
        super.onResume();
        android.util.Log.d(AppContracts.LOG_TAG_SETTINGS, "onResume() called");
    }

    @Override
    protected void onPause() {
        super.onPause();
        applyPendingChanges(true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        consoleTimer.cancel();
        if (overlaySettingsListener != null) {
            overlaySettingsRepository.removeListener(overlaySettingsListener);
        }
        if (tcpConfigListener != null) {
            tcpConfigRepository.removeListener(tcpConfigListener);
        }
        if (tcpStatusListener != null) {
            tcpStatusStore.removeListener(tcpStatusListener);
        }
        if (keyboardListener != null) {
            View root = binding.getRoot();
            root.getViewTreeObserver().removeOnGlobalLayoutListener(keyboardListener);
            keyboardListener = null;
        }
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

    private void trimConsoleIfNeeded() {
        CharSequence cs = binding.textConsole.getText();
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

    private static int indexOfNewline(StringBuilder sb) {
        for (int i = 0; i < sb.length(); i++) {
            if (sb.charAt(i) == '\n') return i;
        }
        return -1;
    }

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
                if (!visible) {
                    applyPendingChanges(false);
                }
            }
        };
        root.getViewTreeObserver().addOnGlobalLayoutListener(keyboardListener);
    }

    private void refreshValuesFromState() {
        updateOverlayFields(currentOverlaySettings());
        updateTcpFields(currentTcpConfig());
        pendingDirty = false;
    }

    private void applyPendingChanges(boolean force) {
        if (!force && !pendingDirty) {
            return;
        }

        String hostValue = pendingHost != null ? pendingHost.trim() : "";
        if (hostValue.isEmpty()) {
            hostValue = currentTcpConfig().host;
        }

        Integer portValue = parseIntSafe(pendingPort, 1, 65535);
        if (portValue == null) {
            portValue = currentTcpConfig().port;
        }

        Integer overlayXValue = parseIntSafe(pendingOverlayX, -10000, 10000);
        if (overlayXValue == null) {
            overlayXValue = currentOverlaySettings().x;
        }
        overlayXValue = eliminateTinyOffset(overlayXValue);

        Integer overlayYValue = parseIntSafe(pendingOverlayY, -10000, 10000);
        if (overlayYValue == null) {
            overlayYValue = currentOverlaySettings().y;
        }

        Float overlayScaleValue = parseFloatSafe(pendingOverlayScale, 0.1f, 5.0f);
        if (overlayScaleValue == null) {
            overlayScaleValue = currentOverlaySettings().scale;
        }

        TcpConfigRepository.TcpConfig currentTcp = currentTcpConfig();
        OverlaySettingsRepository.OverlaySettings currentOverlay = currentOverlaySettings();

        if (!hostValue.equals(currentTcp.host) || portValue != currentTcp.port) {
            tcpConfigRepository.updateHostAndPort(hostValue, portValue);
        }
        if (overlayXValue != currentOverlay.x || overlayYValue != currentOverlay.y) {
            overlaySettingsRepository.setPosition(overlayXValue, overlayYValue);
            sendPositionBroadcast(overlayXValue, overlayYValue);
        }
        if (Math.abs(overlayScaleValue - currentOverlay.scale) > 0.001f) {
            overlaySettingsRepository.setScale(overlayScaleValue);
            sendScaleBroadcast(overlayScaleValue);
        }

        pendingHost = hostValue;
        pendingPort = String.valueOf(portValue);
        pendingOverlayX = String.valueOf(overlayXValue);
        pendingOverlayY = String.valueOf(overlayYValue);
        pendingOverlayScale = String.format(Locale.US, "%.2f", overlayScaleValue);
        pendingDirty = false;

        updateField(binding.valueAddrTCP, pendingHost);
        updateField(binding.valuePortTCP, pendingPort);
        updateField(binding.valueOverlayX, pendingOverlayX);
        updateField(binding.valueOverlayY, pendingOverlayY);
        int progress = (int) Math.round((overlayScaleValue - 0.1f) / 0.05f);
        binding.seekbarOverlayScale.setProgress(progress);
        binding.textScaleValue.setText(pendingOverlayScale);
    }

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

    private int eliminateTinyOffset(int valuePx) {
        return Math.abs(valuePx) <= 12 ? 0 : valuePx;
    }

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

    private void updateField(EditText field, String value) {
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

    private void sendPositionBroadcast(Integer x, Integer y) {
        Intent intent = new Intent(AppContracts.ACTION_APPLY_POSITION);
        if (x != null) {
            intent.putExtra(AppContracts.EXTRA_OVERLAY_X, x);
        }
        if (y != null) {
            intent.putExtra(AppContracts.EXTRA_OVERLAY_Y, y);
        }
        sendBroadcast(intent);
    }

    private void sendScaleBroadcast(float scale) {
        Intent intent = new Intent(AppContracts.ACTION_APPLY_SCALE);
        intent.putExtra(AppContracts.EXTRA_OVERLAY_SCALE, scale);
        sendBroadcast(intent);
    }

    private OverlaySettingsRepository.OverlaySettings currentOverlaySettings() {
        if (overlaySettings == null) {
            overlaySettings = overlaySettingsRepository.get();
        }
        return overlaySettings;
    }

    private TcpConfigRepository.TcpConfig currentTcpConfig() {
        if (tcpConfig == null) {
            tcpConfig = tcpConfigRepository.get();
        }
        return tcpConfig;
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
