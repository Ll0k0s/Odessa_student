package com.example.androidbuttons;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

/**
 * Утилита для создания foreground-уведомления оверлея и канала самого низкого приоритета.
 */
final class OverlayNotificationHelper {

    static final String CHANNEL_ID = "overlay_probe_channel";
    private static final int NOTIFICATION_ID = 1001;

    private OverlayNotificationHelper() {}

    static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
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

    static Notification buildForegroundNotification(Context context) {
        Intent launchIntent = new Intent(context, MainActivity.class);
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                context,
                0,
                launchIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                        : PendingIntent.FLAG_UPDATE_CURRENT
        );

        return new NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.app_name))
                .setSmallIcon(R.drawable.ic_notification_light)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setSilent(true)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .build();
    }

    static int getNotificationId() {
        return NOTIFICATION_ID;
    }
}
