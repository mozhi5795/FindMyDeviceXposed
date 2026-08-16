package com.fyne.findmydevice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
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
    public static final String ACTION_START_MONITOR  = "com.fyne.findmydevice.START_MONITOR";
    public static final String ACTION_STOP_MONITOR   = "com.fyne.findmydevice.STOP_MONITOR";
    public static final String ACTION_GET_LOCATION   = "com.fyne.findmydevice.GET_LOCATION";
    public static final String ACTION_START_POLLING  = "com.fyne.findmydevice.START_POLLING";
    public static final String ACTION_STOP_POLLING   = "com.fyne.findmydevice.STOP_POLLING";

    public static final String EXTRA_CALLBACK_NUMBER = "extra_callback_number";

    private static final int NOTIFICATION_ID = 1001;

    private static final long LOCATION_UPDATE_MS       = 30 * 1000;  // 30秒
    private static final long LOCATION_MIN_DISTANCE_M   = 10;         // 10米
    private static final long SINGLE_LOCATION_TIMEOUT_MS = 15 * 1000; // 15秒
    private static final long POLL_INTERVAL_MS          = 15 * 1000;  // 15秒

    private LocationManager locationManager;
    private ScheduledExecutorService scheduler;
    private Handler mainHandler;

    private String pendingCallbackNumber;
    private Location bestLocation;

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
        mainHandler = new Handler(Looper.getMainLooper());
        createNotificationChannel();
        NotificationHelper.createNotificationChannels(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            Log.i(TAG, "服务重启，恢复监听");
            if (!tryStartForeground()) return START_NOT_STICKY;
            startMonitoring();
            return START_STICKY;
        }

        String action = intent.getAction();
        Log.i(TAG, "onStartCommand: " + action);

        switch (action) {
            case ACTION_START_MONITOR:
                if (!tryStartForeground()) return START_NOT_STICKY;
                startMonitoring();
                break;

            case ACTION_STOP_MONITOR:
                stopMonitoring();
                stopForeground(true);
                stopSelf();
                break;

            case ACTION_GET_LOCATION:
                String callback = intent.getStringExtra(EXTRA_CALLBACK_NUMBER);
                if (!tryStartForeground()) return START_NOT_STICKY;
                requestSingleLocation(callback);
                break;

            case ACTION_START_POLLING:
                if (!tryStartForeground()) return START_NOT_STICKY;
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
        stopMonitoring();
        stopPolling();
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
        }
    }

    // ==================== 持续监听 ====================

    private void startMonitoring() {
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        LOCATION_UPDATE_MS,
                        LOCATION_MIN_DISTANCE_M,
                        locationListener,
                        Looper.getMainLooper());
                Log.i(TAG, "GPS 监听已启动");
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        LOCATION_UPDATE_MS,
                        LOCATION_MIN_DISTANCE_M,
                        locationListener,
                        Looper.getMainLooper());
                Log.i(TAG, "Network 监听已启动");
            }
        } catch (SecurityException e) {
            Log.e(TAG, "定位权限不足", e);
        }
    }

    private void stopMonitoring() {
        try {
            locationManager.removeUpdates(locationListener);
            locationManager.removeUpdates(singleLocationListener);
        } catch (Throwable ignored) {}
        Log.i(TAG, "位置监听已停止");
    }

    // ==================== 单次定位（SMS 响应） ====================

    private void requestSingleLocation(String callbackNumber) {
        this.pendingCallbackNumber = callbackNumber;
        this.bestLocation = null;

        Log.i(TAG, "单次定位请求，回呼: " + callbackNumber);

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
            onLocationReceived(cachedLocation);
            return;
        }

        // 请求新定位
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

        // 超时降级：15秒后用缓存或报错
        final Location fallbackLocation = cachedLocation;
        if (fallbackLocation != null) {
            scheduler.schedule(() -> {
                if (pendingCallbackNumber != null) {
                    onLocationReceived(fallbackLocation);
                }
            }, SINGLE_LOCATION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } else {
            scheduler.schedule(() -> {
                if (pendingCallbackNumber != null) {
                    sendLocationFailed("定位超时，请确保 GPS 已开启");
                }
            }, SINGLE_LOCATION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void onLocationReceived(Location location) {
        if (location == null || pendingCallbackNumber == null) return;

        bestLocation = location;
        Log.i(TAG, "获取到位置: " + formatLocation(location));

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

        try {
            locationManager.removeUpdates(singleLocationListener);
        } catch (Throwable ignored) {}
    }

    private void sendLocationFailed(String reason) {
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
     * 轮询服务器：
     * 1. 上报当前位置
     * 2. 拉取待执行指令
     * 3. 执行指令并上报结果
     */
    private void pollServer() {
        SharedPreferences prefs = ConfigManager.getPreferences(this);
        String serverUrl = ConfigManager.getServerUrl(this);
        String deviceToken = ConfigManager.getDeviceToken(this);

        if (serverUrl == null || serverUrl.isEmpty()) return;

        // 1. 上报位置
        Location loc = bestLocation;
        if (loc != null) {
            reportLocation(serverUrl, deviceToken, loc);
        }

        // 2. 拉取指令
        fetchCommands(serverUrl, deviceToken);
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
                // 获取位置并报告（不需要 SMS 回复，因为已经通过 polling 上报）
                if (bestLocation != null) {
                    Log.i(TAG, "服务器指令: 上报当前位置");
                } else {
                    Log.w(TAG, "服务器指令: 暂无位置数据");
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

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            bestLocation = location;
            Log.d(TAG, "位置更新: " + formatLocation(location));

            // 如果开启了自动上报，立即上报
            SharedPreferences prefs = ConfigManager.getPreferences(LocationService.this);
            if (prefs.getBoolean(ConfigManager.KEY_AUTO_REPORT, false)) {
                String serverUrl = ConfigManager.getServerUrl(LocationService.this);
                String token = ConfigManager.getDeviceToken(LocationService.this);
                if (!serverUrl.isEmpty()) {
                    reportLocation(serverUrl, token, location);
                }
            }
        }

        @Override
        public void onProviderDisabled(String provider) {
            Log.w(TAG, "定位源已关闭: " + provider);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
            Log.d(TAG, "定位源状态: " + provider + " -> " + status);
        }

        @Override
        public void onProviderEnabled(String provider) {
            Log.i(TAG, "定位源已开启: " + provider);
        }
    };

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