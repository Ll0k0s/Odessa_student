package com.example.androidbuttons;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

// Активити-ярлык: запускает сервис оверлея и открывает настройки
public class SettingsShortcutActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Запуск сервиса оверлея
        startOverlayService();

        // Открытие MainActivity с флагом показа настроек
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true);
        startActivity(intent);

        // Закрытие ярлыка без анимации
        finish();
        overridePendingTransition(0, 0);
    }

    // Запуск FloatingOverlayService в нужном режиме для версии Android
    private void startOverlayService() {
        Context appCtx = getApplicationContext();
        Intent svcIntent = new Intent(appCtx, FloatingOverlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appCtx.startForegroundService(svcIntent);
        } else {
            appCtx.startService(svcIntent);
        }
    }
}
