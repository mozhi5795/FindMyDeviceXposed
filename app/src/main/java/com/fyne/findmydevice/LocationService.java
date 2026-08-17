package com.fyne.findmydevice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 定位前台服务
 *
 * 功能：
 * 1. 持续监听 GPS/Network 位置
 * 2. 响应 SMS 单次定位请求，回复位置短信
 * 3. 定时上报位置到 Web 服务器（可选）
 * 4. 定时轮询 Web 服务器获取待执行指令（可选）
 * 5. 播放警报音能力（提供给 CommandProcessor）
 */
public class LocationService extends Service {

    private static final String TAG = "FindMyDevice_LocSvc";

    // Actions
    public static final String ACTION_GET_LOCATION   = "com.fyne.findmydevice.GET_LOCATION";
    public static final String ACTION_START_POLLING  = "com.fyne.findmydevice.START_POLLING";
    public static final String ACTION_STOP_POLLING   = "com.fyne.findmydevice.STOP_POLLING";

    public static final String EXTRA_CALLBACK_NUMBER = "extra_callback_number";

    private static final int NOTIFICATION_ID = 1001;

    private static final long SINGLE_LOCATION_TIMEOUT_MS = 15 * 1000; // 15秒
    private static final long POLL_INTERVAL_MS          = 15 * 1000;  // 15秒

    private LocationManager locationManager;
    private ScheduledExecutorService scheduler;
    private String pendingCallbackNumber;
    private Location bestLocation;

    // true: 定位结果用于 HTTP 上报服务器（轮询时无位置主动请求）
    // false: 定位结果用于 SMS 回复（LOCATE 指令）
    private boolean pendingReportMode = false;

    private boolean isPolling = false;

    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        public LocationService getService() {
            return LocationService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "定位服务创建");
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        scheduler = Executors.newScheduledThreadPool(2);
        createNotificationChannel();
        NotificationHelper.createNotificationChannels(this);
    }

    // KSU 模块启动标记：跳过前台通知，由 root 保活
    public static final String EXTRA_FROM_KSU = "from_ksu";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 检查是否由 KSU 模块启动（root 保活，不显示通知）
        boolean fromKsu = intent != null && intent.getBooleanExtra(EXTRA_FROM_KSU, false);

        if (intent == null || intent.getAction() == null) {
            Log.i(TAG, "服务重启" + (fromKsu ? "（KSU 保活模式）" : ""));
            if (!fromKsu && !tryStartForeground()) return START_NOT_STICKY;
            if (!isPolling) {
                startPolling();
            }
            return START_STICKY;
        }

        String action = intent.getAction();
        Log.i(TAG, "onStartCommand: " + action + (fromKsu ? " (KSU)" : ""));

        switch (action) {
            case ACTION_GET_LOCATION:
                String callback = intent.getStringExtra(EXTRA_CALLBACK_NUMBER);
                if (!fromKsu && !tryStartForeground()) return START_NOT_STICKY;
                requestSingleLocation(callback);
                break;

            case ACTION_START_POLLING:
                if (!fromKsu && !tryStartForeground()) return START_NOT_STICKY;
                if (!isPolling) {
                    startPolling();
                }
                break;

            case ACTION_STOP_POLLING:
                stopPolling();
                break;
        }

        return START_STICKY;
    }

    /**
     * 尝试进入前台。若缺少定位权限（Android 14+ location FGS 强制要求），
     * 捕获 SecurityException 并优雅停止，避免闪退。
     */
    private boolean tryStartForeground() {
        try {
            startForeground(NOTIFICATION_ID, buildNotification());
            return true;
        } catch (SecurityException e) {
            Log.e(TAG, "无法启动前台服务：缺少定位权限", e);
            NotificationHelper.showNotification(this,
                    "定位权限未授予",
                    "请打开 FindMyDevice 并授权定位权限后再启动",
                    NotificationHelper.CHANNEL_COMMAND);
            stopSelf();
            return false;
        } catch (Throwable t) {
            Log.e(TAG, "启动前台服务失败", t);
            stopSelf();
            return false;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "定位服务销毁");
        stopPolling();
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
        }
    }

    // ==================== 单次定位 ====================

    private void requestSingleLocation(String callbackNumber) {
        this.pendingCallbackNumber = callbackNumber;
        this.pendingReportMode = false;
        this.bestLocation = null;

        Log.i(TAG, "单次定位请求（SMS 回复），回呼: " + callbackNumber);

        Location cachedLocation = getCachedLocation();
        if (cachedLocation != null) {
            onLocationReceived(cachedLocation);
            return;
        }
        requestFreshLocation();
    }

    /**
     * 主动请求一次定位用于 HTTP 上报（服务器看板 LOCATE 或首次上报）
     */
    private void requestLocationForReport() {
        this.pendingCallbackNumber = null;
        this.pendingReportMode = true;

        Log.i(TAG, "单次定位请求（HTTP 上报）");

        Location cachedLocation = getCachedLocation();
        if (cachedLocation != null) {
            onLocationReceived(cachedLocation);
            return;
        }
        requestFreshLocation();
    }

    private Location getCachedLocation() {
        Location cachedLocation = null;
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                cachedLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            }
            if (cachedLocation == null && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                cachedLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }
        } catch (SecurityException ignored) {}

        // 缓存位置在5分钟内可用
        if (cachedLocation != null && (System.currentTimeMillis() - cachedLocation.getTime() < 5 * 60 * 1000)) {
            return cachedLocation;
        }
        return null;
    }

    private void requestFreshLocation() {
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER,
                        singleLocationListener, Looper.getMainLooper());
            } else if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER,
                        singleLocationListener, Looper.getMainLooper());
            }
        } catch (SecurityException e) {
            sendLocationFailed("定位权限不足");
            return;
        }

        // 超时降级：15秒后仍未定位成功
        scheduler.schedule(() -> {
            if (bestLocation == null && (pendingCallbackNumber != null || pendingReportMode)) {
                sendLocationFailed("定位超时，请确保 GPS 已开启");
            }
        }, SINGLE_LOCATION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private void onLocationReceived(Location location) {
        if (location == null) return;

        bestLocation = location;
        Log.i(TAG, "获取到位置: " + formatLocation(location));

        try {
            locationManager.removeUpdates(singleLocationListener);
        } catch (Throwable ignored) {}

        if (pendingReportMode) {
            // HTTP 上报到服务器
            pendingReportMode = false;
            reportLocationToServer(location);
            return;
        }

        if (pendingCallbackNumber == null) return;

        String mapsUrl = String.format("https://maps.google.com/maps?q=%.6f,%.6f",
                location.getLatitude(), location.getLongitude());
        String accuracy = location.hasAccuracy() ? location.getAccuracy() + "米" : "未知";
        String dateStr = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date(location.getTime()));

        String message = String.format(
                "[FindMyDevice] 位置: %.6f, %.6f\n精度: %s\n时间: %s\n地图: %s\n地址: %s",
                location.getLatitude(), location.getLongitude(),
                accuracy, dateStr, mapsUrl,
                getAddressFromLocation(location));

        CommandProcessor.sendSms(this, pendingCallbackNumber, message);
        pendingCallbackNumber = null;
    }

    private void sendLocationFailed(String reason) {
        Log.w(TAG, "定位失败: " + reason);
        if (pendingReportMode) {
            pendingReportMode = false;
            return;
        }
        if (pendingCallbackNumber != null) {
            CommandProcessor.sendSms(this, pendingCallbackNumber,
                    "[FindMyDevice] 定位失败: " + reason);
            pendingCallbackNumber = null;
        }
    }

    // ==================== 服务器轮询 ====================

    private void startPolling() {
        isPolling = true;
        Log.i(TAG, "服务器轮询已启动（间隔 " + POLL_INTERVAL_MS/1000 + " 秒）");

        scheduler.scheduleWithFixedDelay(() -> {
            if (!isPolling) return;
            try {
                pollServer();
            } catch (Throwable t) {
                Log.w(TAG, "轮询异常", t);
            }
        }, 5 * 1000, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void stopPolling() {
        isPolling = false;
        Log.i(TAG, "服务器轮询已停止");
    }

    /**
     * 轮询服务器：只拉取远程指令（不再持续上报位置，省电）
     * 轮询到 LOCATE 指令时会触发单次定位并上报结果。
     */
    private void pollServer() {
        String serverUrl = ConfigManager.getServerUrl(this);
        String deviceToken = ConfigManager.getDeviceToken(this);

        if (serverUrl == null || serverUrl.isEmpty()) return;

        // 只拉取远程指令，不再自动上报位置（由 LOCATE 指令触发单次定位）
        fetchCommands(serverUrl, deviceToken);
    }

    /**
     * 将位置上报到服务器（供单次定位成功后调用）
     */
    private void reportLocationToServer(Location location) {
        String serverUrl = ConfigManager.getServerUrl(this);
        String deviceToken = ConfigManager.getDeviceToken(this);
        if (serverUrl == null || serverUrl.isEmpty() || location == null) return;
        reportLocation(serverUrl, deviceToken, location);
    }

    private void reportLocation(String serverUrl, String token, Location loc) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("token", token);
            payload.put("lat", loc.getLatitude());
            payload.put("lng", loc.getLongitude());
            payload.put("accuracy", loc.hasAccuracy() ? loc.getAccuracy() : 0);
            payload.put("time", System.currentTimeMillis());
            payload.put("provider", loc.getProvider());
            payload.put("battery", getBatteryLevel());
            payload.put("model", Build.MODEL);

            String json = payload.toString();
            URL url = new URL(serverUrl + "/api/report");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            OutputStream os = conn.getOutputStream();
            os.write(json.getBytes("UTF-8"));
            os.close();

            int code = conn.getResponseCode();
            conn.disconnect();
            Log.d(TAG, "位置上报: " + code);
        } catch (Throwable t) {
            Log.w(TAG, "位置上报失败", t);
        }
    }

    private void fetchCommands(String serverUrl, String token) {
        try {
            URL url = new URL(serverUrl + "/api/commands?token=" + token);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            int code = conn.getResponseCode();
            if (code != 200) {
                conn.disconnect();
                return;
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            conn.disconnect();

            String response = sb.toString().trim();
            if (response.isEmpty()) return;

            // 解析命令列表
            JSONArray commands = new JSONArray(response);
            for (int i = 0; i < commands.length(); i++) {
                JSONObject cmd = commands.getJSONObject(i);
                String cmdId = cmd.getString("id");
                String action = cmd.getString("action");
                String parameter = cmd.optString("parameter", "");

                Log.i(TAG, "收到服务器指令: " + action + " id=" + cmdId);

                // 执行指令
                String result;
                try {
                    executeServerCommand(action, parameter);
                    result = "ok";
                } catch (Throwable t) {
                    Log.e(TAG, "执行服务器指令失败", t);
                    result = "error: " + t.getMessage();
                }

                // 报告结果
                reportCommandResult(serverUrl, token, cmdId, result);
            }
        } catch (Throwable t) {
            Log.w(TAG, "拉取指令失败", t);
        }
    }

    private void executeServerCommand(String action, String parameter) {
        switch (action.toUpperCase()) {
            case "LOCATE":
            case "LOCATION":
                // 立即上报当前位置；若暂无位置则主动请求一次
                if (bestLocation != null) {
                    Log.i(TAG, "服务器指令: 上报当前位置");
                    reportLocationToServer(bestLocation);
                } else {
                    Log.i(TAG, "服务器指令: 主动请求定位");
                    requestLocationForReport();
                }
                break;

            case "ALARM":
                Log.i(TAG, "服务器指令: 触发警报");
                triggerAlarm();
                break;

            case "RING":
                Log.i(TAG, "服务器指令: 强制响铃");
                triggerRing();
                break;

            case "LOCK":
                Log.i(TAG, "服务器指令: 锁屏");
                triggerLock();
                break;

            case "SILENT":
                Log.i(TAG, "服务器指令: 静音");
                triggerSilent();
                break;

            case "NOTIFY":
                // 在手机上显示通知
                if (!parameter.isEmpty()) {
                    NotificationHelper.showAlert(this, "远程消息", parameter);
                }
                break;

            case "STOP":
            case "STOPSOUND":
            case "STOP_SOUND":
                CommandProcessor.stopAllSounds();
                break;

            case "VIBRATE":
                int seconds = 3;
                if (!parameter.isEmpty()) {
                    try { seconds = Integer.parseInt(parameter); } catch (NumberFormatException ignored) {}
                }
                triggerVibrate(seconds);
                break;

            case "OPEN_URL":
                if (!parameter.isEmpty()) {
                    triggerOpenUrl(parameter);
                }
                break;
        }
    }

    private void reportCommandResult(String serverUrl, String token,
                                      String cmdId, String result) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("token", token);
            payload.put("commandId", cmdId);
            payload.put("result", result);
            payload.put("time", System.currentTimeMillis());

            String json = payload.toString();
            URL url = new URL(serverUrl + "/api/commands/result");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            OutputStream os = conn.getOutputStream();
            os.write(json.getBytes("UTF-8"));
            os.close();

            int code = conn.getResponseCode();
            conn.disconnect();
            Log.d(TAG, "指令结果上报: " + code);
        } catch (Throwable t) {
            Log.w(TAG, "指令结果上报失败", t);
        }
    }

    // ==================== 服务端指令执行实现 ====================

    private void triggerAlarm() {
        new CommandProcessor(this).executeCommand("ALARM", "");
    }

    private void triggerRing() {
        new CommandProcessor(this).executeCommand("RING", "");
    }

    private void triggerLock() {
        new CommandProcessor(this).executeCommand("LOCK", "");
    }

    private void triggerSilent() {
        new CommandProcessor(this).executeCommand("SILENT", "");
    }

    private void triggerVibrate(int seconds) {
        new CommandProcessor(this).executeCommand("VIBRATE#" + seconds, "");
    }

    private void triggerOpenUrl(String url) {
        new CommandProcessor(this).executeCommand("URL#" + url, "");
    }

    // ==================== 监听器 ====================

    private final LocationListener singleLocationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            if (bestLocation == null
                    || (location.hasAccuracy() && location.getAccuracy() < bestLocation.getAccuracy())) {
                bestLocation = location;
            }
            // GPS 精度足够时立即返回
            if (LocationManager.GPS_PROVIDER.equals(location.getProvider())
                    && location.hasAccuracy() && location.getAccuracy() < 50) {
                onLocationReceived(location);
            }
        }

        @Override public void onProviderDisabled(String provider) {}
        @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
        @Override public void onProviderEnabled(String provider) {}
    };

    // ==================== 辅助方法 ====================

    public static String formatLocation(Location location) {
        if (location == null) return "未知位置";
        return String.format("(%.6f, %.6f) +/-%.0fm %s",
                location.getLatitude(), location.getLongitude(),
                location.hasAccuracy() ? location.getAccuracy() : 0,
                location.getProvider());
    }

    private String getAddressFromLocation(Location location) {
        try {
            URL url = new URL(String.format(
                    "https://nominatim.openstreetmap.org/reverse?format=json&lat=%.6f&lon=%.6f&zoom=18&addressdetails=1",
                    location.getLatitude(), location.getLongitude()));
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "FindMyDevice/1.0");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            conn.disconnect();

            String json = sb.toString();
            int start = json.indexOf("\"display_name\"");
            if (start > 0) {
                start = json.indexOf('"', start + 15) + 1;
                int end = json.indexOf('"', start);
                if (end > start) {
                    return json.substring(start, end);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "获取地址失败", t);
        }
        return "地址解析失败";
    }

    private int getBatteryLevel() {
        Intent batIntent = registerReceiver(null,
                new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (batIntent != null) {
            int level = batIntent.getIntExtra("level", -1);
            int scale = batIntent.getIntExtra("scale", -1);
            if (scale > 0) return (int) ((float) level / scale * 100);
        }
        return -1;
    }

    public Location getCurrentLocation() {
        return bestLocation;
    }

    // ==================== 通知 ====================

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NotificationHelper.CHANNEL_SERVICE,
                    "查找设备服务",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("后台定位服务通知");
            channel.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, NotificationHelper.CHANNEL_SERVICE);
        } else {
            b = new Notification.Builder(this);
        }

        return b
                .setContentTitle("查找设备运行中")
                .setContentText("后台定位监听已启动")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(pi)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW)
                .build();
    }
}