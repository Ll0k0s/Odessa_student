package com.example.androidbuttons.core;

/**
 * Defines shared protocol ranges for locomotive and strip state values.
 */
public final class ProtocolConstraints {

    public static final int LOCO_MIN = 1;
    public static final int LOCO_MAX = 8;
    public static final int STATE_MIN = 1;
    public static final int STATE_MAX = 5;

    public static final int LOCOMOTIVE_COUNT = LOCO_MAX - LOCO_MIN + 1;

    private ProtocolConstraints() {
    }

    public static int clampLoco(int value) {
        return Math.max(LOCO_MIN, Math.min(LOCO_MAX, value));
    }

    public static int clampState(int value) {
        return Math.max(STATE_MIN, Math.min(STATE_MAX, value));
    }

    public static boolean isValidLoco(int value) {
        return value >= LOCO_MIN && value <= LOCO_MAX;
    }

    public static boolean isValidState(int value) {
        return value >= STATE_MIN && value <= STATE_MAX;
    }

    public static int locoIndex(int locoValue) {
        return clampLoco(locoValue) - LOCO_MIN;
    }

    public static int locoFromIndex(int index) {
        return clampLoco(LOCO_MIN + Math.max(0, index));
    }
}
