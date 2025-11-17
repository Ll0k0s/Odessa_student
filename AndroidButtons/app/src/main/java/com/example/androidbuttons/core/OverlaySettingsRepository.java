package com.example.androidbuttons.core;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Persists and broadcasts overlay positioning, scaling and edit-mode flags.
 */
public final class OverlaySettingsRepository {

    public static final String PREFS_NAME = "overlay_settings";

    private static final String KEY_X = "overlay_x";
    private static final String KEY_Y = "overlay_y";
    private static final String KEY_SCALE = "overlay_scale";
    private static final String KEY_ALLOW_EDIT = "overlay_allow_edit";

    private final SharedPreferences prefs;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    OverlaySettingsRepository(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public OverlaySettings get() {
        int x = prefs.getInt(KEY_X, -10);
        int y = prefs.getInt(KEY_Y, 0);
        float scale = prefs.getFloat(KEY_SCALE, 1.0f);
        boolean editAllowed = prefs.getBoolean(KEY_ALLOW_EDIT, true);
        return new OverlaySettings(x, y, scale, editAllowed);
    }

    public void update(@NonNull OverlaySettings settings) {
        OverlaySettings current = get();
        if (current.equals(settings)) {
            return;
        }
        prefs.edit()
                .putInt(KEY_X, settings.x)
                .putInt(KEY_Y, settings.y)
                .putFloat(KEY_SCALE, settings.scale)
                .putBoolean(KEY_ALLOW_EDIT, settings.editAllowed)
                .apply();
        notifyListeners(settings);
    }

    public void setEditAllowed(boolean allowed) {
        if (prefs.getBoolean(KEY_ALLOW_EDIT, true) == allowed) {
            return;
        }
        prefs.edit().putBoolean(KEY_ALLOW_EDIT, allowed).apply();
        notifyListeners(get());
    }

    public void setPosition(int x, int y) {
        OverlaySettings settings = get();
        if (settings.x == x && settings.y == y) {
            return;
        }
        prefs.edit()
                .putInt(KEY_X, x)
                .putInt(KEY_Y, y)
                .apply();
        notifyListeners(get());
    }

    public void setScale(float scale) {
        float clamped = Math.max(0.1f, Math.min(5f, scale));
        if (Float.compare(clamped, prefs.getFloat(KEY_SCALE, 1.0f)) == 0) {
            return;
        }
        prefs.edit().putFloat(KEY_SCALE, clamped).apply();
        notifyListeners(get());
    }

    public void addListener(@NonNull Listener listener) {
        listeners.addIfAbsent(listener);
    }

    @MainThread
    public void removeListener(@NonNull Listener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners(OverlaySettings settings) {
        for (Listener listener : listeners) {
            listener.onOverlaySettingsChanged(settings);
        }
    }

    public interface Listener {
        void onOverlaySettingsChanged(OverlaySettings settings);
    }

    public static final class OverlaySettings {
        public final int x;
        public final int y;
        public final float scale;
        public final boolean editAllowed;

        public OverlaySettings(int x, int y, float scale, boolean editAllowed) {
            this.x = x;
            this.y = y;
            this.scale = scale;
            this.editAllowed = editAllowed;
        }

        public OverlaySettings withPosition(int newX, int newY) {
            return new OverlaySettings(newX, newY, scale, editAllowed);
        }

        public OverlaySettings withScale(float newScale) {
            float clamped = Math.max(0.1f, Math.min(5f, newScale));
            return new OverlaySettings(x, y, clamped, editAllowed);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof OverlaySettings)) return false;
            OverlaySettings other = (OverlaySettings) obj;
            return x == other.x
                    && y == other.y
                    && Float.compare(scale, other.scale) == 0
                    && editAllowed == other.editAllowed;
        }

        @Override
        public int hashCode() {
            int result = x;
            result = 31 * result + y;
            result = 31 * result + Float.floatToIntBits(scale);
            result = 31 * result + (editAllowed ? 1 : 0);
            return result;
        }
    }
}
