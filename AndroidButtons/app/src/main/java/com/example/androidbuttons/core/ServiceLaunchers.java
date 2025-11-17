package com.example.androidbuttons.core;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.content.ContextCompat;

import com.example.androidbuttons.TcpService;

/**
 * Centralized helpers for starting foreground/background services.
 */
public final class ServiceLaunchers {

    private ServiceLaunchers() {
    }

    public static void ensureTcpServiceRunning(Context context) {
        Context app = context.getApplicationContext();
        Intent intent = new Intent(app, TcpService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(app, intent);
        } else {
            app.startService(intent);
        }
    }
}
