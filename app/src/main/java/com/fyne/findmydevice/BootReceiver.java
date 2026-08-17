package com.fyne.findmydevice;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.content.ContextCompat;

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

    private boolean hasLocationPermission(Context context) {
        return ContextCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void handleBoot(Context context) {
        Log.i(TAG, "系统启动完成，启动 FindMyDevice 守护");

        SharedPreferences prefs = ConfigManager.getPreferences(context);
        if (!prefs.getBoolean(ConfigManager.KEY_BOOT_START, true)) {
            Log.i(TAG, "开机自启已禁用");
            return;
        }

        // 开机时无法弹权限框，若定位权限未授予则跳过启动（避免闪退）
        if (!hasLocationPermission(context)) {
            Log.w(TAG, "定位权限未授予，跳过开机自启（用户需打开 App 授权后生效）");
            return;
        }

        // 启动轮询服务（监听服务器指令，不再持续 GPS 定位，省电）
        Intent pollIntent = new Intent(context, LocationService.class);
        pollIntent.setAction(LocationService.ACTION_START_POLLING);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(pollIntent);
        } else {
            context.startService(pollIntent);
        }

        ConfigManager.setBootStartTime(context, System.currentTimeMillis());
        Log.i(TAG, "轮询服务已启动（省电模式：不持续监听 GPS）");
    }
}