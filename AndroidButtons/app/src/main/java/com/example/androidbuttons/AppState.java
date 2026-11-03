package com.example.androidbuttons;

import android.content.SharedPreferences;

import java.util.concurrent.LinkedBlockingQueue;

public final class AppState {

    // Запрещаем создавать экземпляры
    private AppState() {}

    // Заранее запрограммированные локомотивы: название и DCC-адрес по умолчанию (1..9999).
    // Адрес каждого можно поменять в настройках — он хранится в prefs.
    public static final String[] LOCO_NAMES = new String[] {
        "ТЕП70-145",
        "М62-1511",
        "М62-1391",
        "М62-1458",
        "ЧМЕ3-3019",
        "ЧМЕ3-4537",
        "ТГК2 3169",
        "ЧМЕ2-305"
    };
    public static final int[] LOCO_ADDRESSES = new int[] {
        145, 1511, 1391, 1458, 3019, 4537, 3169, 305
    };

    // Последнее значение ЛС от сервера для выбранного локомотива (в виде состояния полосы 1..6)
    public static volatile int lastSignalState = 0;

    // Очередь лога между сервисами и экраном настроек
    public static final LinkedBlockingQueue<String> consoleQueue = new LinkedBlockingQueue<>();

    // Имя SharedPreferences
    public static final String PREFS_NAME = "androidbuttons_prefs";

    // Ключи настроек TCP
    public static final String KEY_TCP_HOST = "tcp_host";
    public static final String KEY_TCP_PORT = "tcp_port";

    // Адрес выбранного локомотива (его регистрируем на сервере ЛС)
    public static final String KEY_LOCO_ADDRESS = "loco_address";
    // Выбранный пункт списка: 0..7 — локомотив из списка, 8 — «Інша адреса»
    public static final String KEY_LOCO_INDEX = "loco_index";
    // Адрес, введённый для пункта «Інша адреса»
    public static final String KEY_LOCO_CUSTOM_ADDRESS = "loco_custom_address";
    // Изменённый адрес локомотива из списка: префикс + индекс
    private static final String KEY_LOCO_PRESET_ADDRESS_PREFIX = "loco_preset_address_";
    // Индекс пункта «Інша адреса» в списке
    public static final int CUSTOM_LOCO_INDEX = 8;

    // Сервер ЛС по умолчанию
    public static final String DEFAULT_TCP_HOST = "192.168.0.100";
    public static final int DEFAULT_TCP_PORT = 21202;
    public static final int DEFAULT_LOCO_ADDRESS = LOCO_ADDRESSES[0];

    // Старые значения по умолчанию (сервер ESP) — заменяются на новые при первом запуске
    private static final String LEGACY_TCP_HOST = "192.168.2.6";
    private static final int LEGACY_TCP_PORT = 9000;
    private static final String KEY_LS_MIGRATED = "ls_server_migrated";
    private static final String LEGACY_KEY_SELECTED_LOCO = "selected_loco";

    // Ключи позиции и масштаба overlay
    public static final String KEY_OVERLAY_X = "overlay_x";
    public static final String KEY_OVERLAY_Y = "overlay_y";
    public static final String KEY_OVERLAY_SCALE = "overlay_scale";
    public static final String KEY_OVERLAY_ALLOW_MODIFICATION = "overlay_allow_modification";

    // Action для обновления overlay из сервиса
    public static final String ACTION_OVERLAY_UPDATED = "com.example.androidbuttons.OVERLAY_UPDATED";

    // Флаги состояния TCP
    public static volatile boolean tcpConnecting = false;
    public static volatile boolean tcpConnected = false;
    public static volatile boolean tcpReachable = false;

    public static int getLocoAddress(SharedPreferences prefs) {
        return prefs.getInt(KEY_LOCO_ADDRESS, DEFAULT_LOCO_ADDRESS);
    }

    // Текущий адрес локомотива из списка (изменённый пользователем или по умолчанию)
    public static int getPresetAddress(SharedPreferences prefs, int index) {
        return prefs.getInt(KEY_LOCO_PRESET_ADDRESS_PREFIX + index, LOCO_ADDRESSES[index]);
    }

    public static void setPresetAddress(SharedPreferences prefs, int index, int address) {
        String key = KEY_LOCO_PRESET_ADDRESS_PREFIX + index;
        if (address == LOCO_ADDRESSES[index]) {
            prefs.edit().remove(key).apply();
        } else {
            prefs.edit().putInt(key, address).apply();
        }
    }

    // Выбранный пункт списка. Если ещё не сохранён — ищем по адресу, иначе «Інша адреса».
    public static int getSelectedLocoIndex(SharedPreferences prefs) {
        int index = prefs.getInt(KEY_LOCO_INDEX, -1);
        if (index >= 0 && index <= CUSTOM_LOCO_INDEX) return index;
        int address = getLocoAddress(prefs);
        for (int i = 0; i < LOCO_ADDRESSES.length; i++) {
            if (getPresetAddress(prefs, i) == address) return i;
        }
        return CUSTOM_LOCO_INDEX;
    }

    // Перевод LTFL (0..5) в состояние полосы overlay (1..6).
    // 1..5 совпадают; 0 (не определено) показываем как «все огни» (6).
    public static int stripStateForLtfl(int ltfl) {
        return (ltfl >= 1 && ltfl <= 5) ? ltfl : 6;
    }

    // Один раз заменяем сохранённый адрес старого ESP-сервера на сервер ЛС
    public static void migrateTcpDefaults(SharedPreferences prefs) {
        if (prefs.getBoolean(KEY_LS_MIGRATED, false)) return;
        SharedPreferences.Editor e = prefs.edit();
        String host = prefs.getString(KEY_TCP_HOST, null);
        if (host == null || LEGACY_TCP_HOST.equals(host.trim())) {
            e.putString(KEY_TCP_HOST, DEFAULT_TCP_HOST);
        }
        int port = prefs.getInt(KEY_TCP_PORT, -1);
        if (port == -1 || port == LEGACY_TCP_PORT) {
            e.putInt(KEY_TCP_PORT, DEFAULT_TCP_PORT);
        }
        // Раньше хранился номер локомотива в списке (1..8), теперь — его адрес
        int legacyLoco = prefs.getInt(LEGACY_KEY_SELECTED_LOCO, 0);
        if (!prefs.contains(KEY_LOCO_ADDRESS) && legacyLoco >= 1 && legacyLoco <= LOCO_ADDRESSES.length) {
            e.putInt(KEY_LOCO_ADDRESS, LOCO_ADDRESSES[legacyLoco - 1]);
        }
        e.putBoolean(KEY_LS_MIGRATED, true);
        e.commit();
    }
}
