package com.example.androidbuttons;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;

// Стартовый ярлык приложения: проверяем разрешение, запускаем оверлей и прячем себя
public class LauncherActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Проверяем разрешение на показ поверх других приложений
        if (!canDrawOverlays()) {
            // Если нет разрешения — открываем системные настройки и выходим
            displayOverlayPermissionSettings();
            return;
        }

        // Запускаем сервис оверлея
        startOverlayService();

        // Открываем основную активити с флагом «спрятать после загрузки»
        Intent mainIntent = new Intent(this, MainActivity.class);
        mainIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        mainIntent.putExtra(MainActivity.EXTRA_HIDE_AFTER_BOOT, true);
        startActivity(mainIntent);

        // Закрываем эту активити без анимации
        finish();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Обновляем текущий Intent, если активити пересоздали через ярлык
        setIntent(intent);
    }

    // Открываем системное окно выдачи разрешения на оверлей
    private void displayOverlayPermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())
            );
            startActivity(intent);
        }
        // После перехода в настройки сразу закрываемся
        finish();
        overridePendingTransition(0, 0);
    }

    // Стартуем foreground-сервис оверлея
    private void startOverlayService() {
        Context appCtx = getApplicationContext();
        Intent svcIntent = new Intent(appCtx, FloatingOverlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appCtx.startForegroundService(svcIntent);
        } else {
            appCtx.startService(svcIntent);
        }
    }

    // Проверяем, можно ли рисовать поверх других приложений
    private boolean canDrawOverlays() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        return Settings.canDrawOverlays(this);
    }
}
