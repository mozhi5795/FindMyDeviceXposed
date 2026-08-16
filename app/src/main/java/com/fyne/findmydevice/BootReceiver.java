package com.fyne.findmydevice;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

/**
 * 开机自启广播接收器
 * 收到 BOOT_COMPLETED 后启动定位前台服务和服务器轮询。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "FindMyDevice_Boot";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        Log.i(TAG, "收到广播: " + action);

        if (action == null) return;

        switch (action) {
            case Intent.ACTION_BOOT_COMPLETED:
            case "android.intent.action.QUICKBOOT_POWERON":
            case "com.htc.intent.action.QUICKBOOT_POWERON":
                handleBoot(context);
                break;
        }
    }

    private void handleBoot(Context context) {
        Log.i(TAG, "系统启动完成，启动 FindMyDevice 守护");

        SharedPreferences prefs = ConfigManager.getPreferences(context);
        if (!prefs.getBoolean(ConfigManager.KEY_BOOT_START, true)) {
            Log.i(TAG, "开机自启已禁用");
            return;
        }

        // 启动定位服务
        Intent serviceIntent = new Intent(context, LocationService.class);
        serviceIntent.setAction(LocationService.ACTION_START_MONITOR);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }

        ConfigManager.setBootStartTime(context, System.currentTimeMillis());
        Log.i(TAG, "定位服务已启动");

        // 如果配置了 Web 服务器地址，同时启动服务器轮询
        if (ConfigManager.isServerPollEnabled(context)) {
            Intent pollIntent = new Intent(context, LocationService.class);
            pollIntent.setAction(LocationService.ACTION_START_POLLING);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(pollIntent);
            } else {
                context.startService(pollIntent);
            }
        }
    }
}