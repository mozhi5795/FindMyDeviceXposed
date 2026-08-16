package com.fyne.findmydevice;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 配置管理器
 * 使用 SharedPreferences 持久化模块配置
 */
public class ConfigManager {

    private static final String PREF_NAME = "findmydevice_config";

    // 配置键
    public static final String KEY_BOOT_START = "boot_start";
    public static final String KEY_SMS_CONTROL = "sms_control";
    public static final String KEY_COMMAND_PREFIX = "command_prefix";
    public static final String KEY_AUTHORIZED_NUMBERS = "authorized_numbers";
    public static final String KEY_ALLOW_ALL_SENDERS = "allow_all_senders";
    public static final String KEY_AUTO_REPORT = "auto_report";
    public static final String KEY_REPORT_URL = "report_url";
    public static final String KEY_REPORT_INTERVAL = "report_interval";
    public static final String KEY_DEVICE_IMEI = "device_imei";
    public static final String KEY_DEVICE_IMSI = "device_imsi";
    public static final String KEY_SERVER_POLL_ENABLED = "server_poll_enabled";
    public static final String KEY_SERVER_URL = "server_url";
    public static final String KEY_DEVICE_TOKEN = "device_token";
    public static final String KEY_BOOT_START_TIME = "boot_start_time";

    // 默认值
    public static final String DEFAULT_COMMAND_PREFIX = "#FMD#";
    public static final long DEFAULT_REPORT_INTERVAL_MS = 5 * 60 * 1000; // 5分钟
    public static final long DEFAULT_POLL_INTERVAL_MS = 30 * 1000;       // 30秒

    private static SharedPreferences prefs;

    public static SharedPreferences getPreferences(Context context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        }
        return prefs;
    }

    public static void setBootStartTime(Context context, long time) {
        getPreferences(context).edit().putLong(KEY_BOOT_START_TIME, time).apply();
    }

    public static long getBootStartTime(Context context) {
        return getPreferences(context).getLong(KEY_BOOT_START_TIME, 0);
    }

    public static String getServerUrl(Context context) {
        return getPreferences(context).getString(KEY_SERVER_URL, "");
    }

    public static void setServerUrl(Context context, String url) {
        getPreferences(context).edit().putString(KEY_SERVER_URL, url).apply();
    }

    public static String getDeviceToken(Context context) {
        String token = getPreferences(context).getString(KEY_DEVICE_TOKEN, "");
        if (token.isEmpty()) {
            // 首次运行时自动生成设备标识
            token = "device_" + System.currentTimeMillis();
            getPreferences(context).edit().putString(KEY_DEVICE_TOKEN, token).apply();
        }
        return token;
    }

    public static boolean isServerPollEnabled(Context context) {
        return getPreferences(context).getBoolean(KEY_SERVER_POLL_ENABLED, false);
    }

    /**
     * 待确认操作的存储结构
     */
    public static class PendingAction {
        public String action;
        public String senderNumber;
        public long timestamp;

        public PendingAction(String action, String senderNumber, long timestamp) {
            this.action = action;
            this.senderNumber = senderNumber;
            this.timestamp = timestamp;
        }
    }

    public static void setPendingAction(Context context, String action, String senderNumber) {
        getPreferences(context).edit()
                .putString("pending_action", action)
                .putString("pending_sender", senderNumber)
                .putLong("pending_timestamp", System.currentTimeMillis())
                .apply();
    }

    public static PendingAction getPendingAction(Context context) {
        String action = getPreferences(context).getString("pending_action", null);
        String sender = getPreferences(context).getString("pending_sender", null);
        long timestamp = getPreferences(context).getLong("pending_timestamp", 0);
        if (action == null || sender == null) return null;
        return new PendingAction(action, sender, timestamp);
    }

    public static void clearPendingAction(Context context) {
        getPreferences(context).edit()
                .remove("pending_action")
                .remove("pending_sender")
                .remove("pending_timestamp")
                .apply();
    }
}