package com.example.androidbuttons.core;

/**
 * Shared constants for intent actions and log tags.
 */
public final class AppContracts {

    private AppContracts() {}

    public static final String ACTION_APPLY_SCALE = "com.example.androidbuttons.APPLY_SCALE_NOW";
    public static final String ACTION_APPLY_POSITION = "com.example.androidbuttons.APPLY_POSITION_NOW";
    public static final String ACTION_OVERLAY_UPDATED = "com.example.androidbuttons.OVERLAY_UPDATED";

    public static final String EXTRA_OVERLAY_X = "com.example.androidbuttons.extra.OVERLAY_X";
    public static final String EXTRA_OVERLAY_Y = "com.example.androidbuttons.extra.OVERLAY_Y";
    public static final String EXTRA_OVERLAY_SCALE = "com.example.androidbuttons.extra.OVERLAY_SCALE";

    public static final String LOG_TAG_SETTINGS = "SettingsActivity";
    public static final String LOG_TAG_MAIN = "MainActivity";
    public static final String LOG_TAG_OVERLAY_SERVICE = "FloatingOverlayService";
}
