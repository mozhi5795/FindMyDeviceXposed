package com.fyne.findmydevice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 通知辅助类
 * 统一管理通知渠道和通知发送
 */
public class NotificationHelper {

    public static final String CHANNEL_SERVICE = "findmydevice_service";
    public static final String CHANNEL_ALERT = "findmydevice_alert";
    public static final String CHANNEL_COMMAND = "findmydevice_command";

    private static boolean channelsCreated = false;

    /**
     * 创建所有通知渠道（只需创建一次）
     */
    public static void createNotificationChannels(Context context) {
        if (channelsCreated) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;

        // 服务通知（低优先级，持续存在）
        NotificationChannel serviceChannel = new NotificationChannel(
                CHANNEL_SERVICE,
                "查找设备服务",
                NotificationManager.IMPORTANCE_LOW
        );
        serviceChannel.setDescription("后台定位服务通知");
        serviceChannel.setShowBadge(false);
        nm.createNotificationChannel(serviceChannel);

        // 警报通知（高优先级）
        NotificationChannel alertChannel = new NotificationChannel(
                CHANNEL_ALERT,
                "远程警报",
                NotificationManager.IMPORTANCE_HIGH
        );
        alertChannel.setDescription("远程触发警报时发出高优先级通知");
        alertChannel.setShowBadge(true);
        alertChannel.enableVibration(true);
        nm.createNotificationChannel(alertChannel);

        // 指令通知（中等优先级）
        NotificationChannel cmdChannel = new NotificationChannel(
                CHANNEL_COMMAND,
                "远程指令",
                NotificationManager.IMPORTANCE_DEFAULT
        );
        cmdChannel.setDescription("远程指令执行结果通知");
        cmdChannel.setShowBadge(false);
        nm.createNotificationChannel(cmdChannel);

        channelsCreated = true;
    }

    /**
     * 发送通知
     */
    public static void showNotification(Context context, String title,
                                         String content, String channelId) {
        createNotificationChannels(context);

        Intent intent = new Intent(context, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(context, channelId);
        } else {
            builder = new Notification.Builder(context);
        }

        Notification notification = builder
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build();

        NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify((int) System.currentTimeMillis(), notification);
        }
    }

    /**
     * 发送高优先级警报通知
     */
    public static void showAlert(Context context, String title, String content) {
        showNotification(context, title, content, CHANNEL_ALERT);
    }
}