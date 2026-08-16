package com.fyne.findmydevice;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 设备管理员接收器
 * 用于远程锁屏和清除数据功能
 * 需要在设置中激活设备管理员权限
 */
public class FmdDeviceAdminReceiver extends DeviceAdminReceiver {

    private static final String TAG = "FindMyDevice_Admin";

    @Override
    public void onEnabled(Context context, Intent intent) {
        Log.i(TAG, "设备管理员权限已激活");
        NotificationHelper.showNotification(context,
                "设备管理员已激活",
                "远程锁屏和清除数据功能可用",
                NotificationHelper.CHANNEL_COMMAND);
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        Log.w(TAG, "设备管理员权限已取消");
        NotificationHelper.showNotification(context,
                "设备管理员已取消",
                "远程锁屏和清除数据功能将不可用",
                NotificationHelper.CHANNEL_COMMAND);
    }

    @Override
    public CharSequence onDisableRequested(Context context, Intent intent) {
        // 当用户尝试取消设备管理员时显示提示
        return "取消后远程锁屏和清除数据功能将失效，请谨慎操作";
    }
}