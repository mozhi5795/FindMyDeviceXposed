package com.fyne.findmydevice;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * FindMyDevice Xposed/LSPosed 模块入口
 *
 * 两阶段启动：
 * 1. initZygote - Zygote 进程加载时注入
 * 2. handleLoadPackage - 在系统进程中钩住 SystemServer 启动完成后
 *    触发开机自启广播，确保模块在系统重启后自动运行。
 *
 * 兼容：KernelSU / Magisk + LSPosed
 */
public class FindMyDeviceEntry implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    private static final String TAG = "FindMyDevice";

    @Override
    public void initZygote(IXposedHookZygoteInit.StartupParam startupParam) {
        Log.i(TAG, "FindMyDevice Xposed 模块已注入 Zygote");
        XposedBridge.log("FindMyDevice: 模块已加载，进程: " + startupParam.modulePath);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"android".equals(lpparam.packageName)) {
            return;
        }
        Log.i(TAG, "注入系统进程: " + lpparam.packageName);
        try {
            Class<?> systemServerClass = XposedHelpers.findClass(
                    "com.android.server.SystemServer", lpparam.classLoader);

            XposedHelpers.findAndHookMethod(systemServerClass,
                    "startOtherServices", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Log.i(TAG, "SystemServer 启动完毕，正在初始化模块...");
                    XposedBridge.log("FindMyDevice: 系统服务已启动，初始化守护组件");
                    Context context = getSystemContext(lpparam.classLoader);
                    if (context != null) {
                        triggerBootReceiver(context);
                    }
                }
            });

            Log.i(TAG, "系统进程注入成功");
        } catch (Throwable t) {
            Log.e(TAG, "注入系统进程失败", t);
            XposedBridge.log("FindMyDevice: 注入失败: " + t.getMessage());
        }
    }

    private Context getSystemContext(ClassLoader classLoader) {
        try {
            Class<?> activityThreadClass = XposedHelpers.findClass(
                    "android.app.ActivityThread", classLoader);
            Object activityThread = XposedHelpers.callStaticMethod(
                    activityThreadClass, "currentActivityThread");
            return (Context) XposedHelpers.callMethod(activityThread, "getSystemContext");
        } catch (Throwable t) {
            Log.e(TAG, "获取系统 Context 失败", t);
            return null;
        }
    }

    private void triggerBootReceiver(Context context) {
        try {
            Intent intent = new Intent();
            intent.setClassName(context, "com.fyne.findmydevice.BootReceiver");
            intent.setAction(Intent.ACTION_BOOT_COMPLETED);
            context.sendBroadcast(intent);
            Log.i(TAG, "开机广播已发送至 BootReceiver");
            XposedBridge.log("FindMyDevice: 开机自启广播已发送");
        } catch (Throwable t) {
            Log.e(TAG, "发送开机广播失败", t);
        }
    }
}