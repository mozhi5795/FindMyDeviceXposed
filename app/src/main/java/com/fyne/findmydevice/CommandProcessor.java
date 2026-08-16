package com.fyne.findmydevice;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraCharacteristics;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.telephony.SmsManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 远程指令处理器
 *
 * 解析 SMS 远程指令并执行对应操作。
 *
 * 支持指令：
 *   LOCATE         获取位置并回复 SMS
 *   ALARM/SIREN    最大音量警报音 30 秒
 *   RING           强制响铃（静音也会响）
 *   LOCK           锁屏（需设备管理员权限）
 *   WIPE           恢复出厂设置（危险，需二次确认）
 *   CAMERA/PHOTO   远程前置拍照
 *   INFO           获取设备信息
 *   SILENT/MUTE    设为静音
 *   VIBRATE#秒    震动N秒
 *   URL#地址       在手机上打开网页
 *   BATTERY        获取电量信息
 *   HELP           帮助列表
 *   CONFIRM_WIPE   确认擦除数据
 */
public class CommandProcessor {

    private static final String TAG = "FindMyDevice_Cmd";
    private final Context context;

    // 全局单例管理正在播放的声音，避免多实例叠加停不下来
    private static MediaPlayer sActivePlayer;
    private static android.media.ToneGenerator sActiveTone;
    private static AudioManager sVolumeManager;
    private static int sSavedVolume = -1;
    private static android.os.Handler sSoundHandler;
    private static Runnable sSoundStopper;

    public CommandProcessor(Context context) {
        this.context = context;
    }

    /**
     * 停止所有正在播放的声音并恢复音量（STOP/SILENT 指令、新指令播放前调用）
     */
    public static void stopAllSounds() {
        try {
            if (sActivePlayer != null) {
                if (sActivePlayer.isPlaying()) sActivePlayer.stop();
                sActivePlayer.release();
                sActivePlayer = null;
                Log.i(TAG, "已停止 MediaPlayer");
            }
        } catch (Throwable ignored) {}
        try {
            if (sActiveTone != null) {
                sActiveTone.stopTone();
                sActiveTone.release();
                sActiveTone = null;
                Log.i(TAG, "已停止 ToneGenerator");
            }
        } catch (Throwable ignored) {}
        try {
            if (sSoundHandler != null && sSoundStopper != null) {
                sSoundHandler.removeCallbacks(sSoundStopper);
                sSoundHandler = null;
                sSoundStopper = null;
            }
        } catch (Throwable ignored) {}
        // 恢复音量
        if (sVolumeManager != null && sSavedVolume >= 0) {
            try {
                sVolumeManager.setStreamVolume(AudioManager.STREAM_MUSIC, sSavedVolume, 0);
            } catch (Throwable ignored) {}
            sSavedVolume = -1;
        }
    }

    public void executeCommand(String commandPart, String senderNumber) {
        if (commandPart == null || commandPart.isEmpty()) {
            sendSms(context, senderNumber, "[FindMyDevice] 空指令，发送 #FMD#HELP# 获取帮助");
            return;
        }

        // 来自服务器（Web 看板）的指令没有号码，不需要 SMS 回复
        boolean fromServer = (senderNumber == null || senderNumber.isEmpty());

        String[] parts = commandPart.split("#", 2);
        String command = parts[0].trim().toUpperCase();
        String parameter = (parts.length > 1) ? parts[1].trim() : "";

        Log.i(TAG, "执行指令: " + command + " 参数: " + parameter);

        switch (command) {
            case "LOCATE":
            case "LOCATION":
            case "GPS":
                handleLocate(senderNumber);
                break;

            case "ALARM":
            case "SIREN":
                handleAlarm();
                if (!fromServer) {
                    sendSms(context, senderNumber, "[FindMyDevice] 警报已触发（30秒后自动停止）");
                }
                break;

            case "RING":
                handleRing();
                if (!fromServer) {
                    sendSms(context, senderNumber, "[FindMyDevice] 设备正在响铃（30秒后自动停止）");
                }
                break;

            case "LOCK":
                handleLock();
                if (!fromServer) {
                    sendSms(context, senderNumber, "[FindMyDevice] 设备已锁定");
                }
                break;

            case "WIPE":
            case "FACTORY_RESET":
                handleWipe(senderNumber);
                break;

            case "CAMERA":
            case "PHOTO":
                handleCamera(senderNumber);
                break;

            case "INFO":
                handleInfo(senderNumber);
                break;

            case "STOP":
            case "STOPSOUND":
            case "STOP_SOUND":
                stopAllSounds();
                if (!fromServer) {
                    sendSms(context, senderNumber, "[FindMyDevice] 已停止所有声音");
                }
                break;

            case "SILENT":
            case "MUTE":
                stopAllSounds();
                handleSilent();
                if (!fromServer) {
                    sendSms(context, senderNumber, "[FindMyDevice] 设备已设为静音");
                }
                break;

            case "VIBRATE":
                handleVibrate(parameter);
                sendSms(context, senderNumber, "[FindMyDevice] 震动已触发");
                break;

            case "HELP":
                handleHelp(senderNumber);
                break;

            case "URL":
                if (!parameter.isEmpty()) {
                    handleOpenUrl(parameter);
                    sendSms(context, senderNumber, "[FindMyDevice] 已打开: " + parameter);
                }
                break;

            case "BATTERY":
                handleBattery(senderNumber);
                break;

            default:
                sendSms(context, senderNumber,
                        "[FindMyDevice] 未知指令: " + command
                        + "\n发送 #FMD#HELP# 获取指令列表");
                break;
        }
    }

    // ==================== 指令实现 ====================

    private void handleLocate(String senderNumber) {
        Intent intent = new Intent(context, LocationService.class);
        intent.setAction(LocationService.ACTION_GET_LOCATION);
        intent.putExtra(LocationService.EXTRA_CALLBACK_NUMBER, senderNumber);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    private void handleAlarm() {
        try {
            // 停止之前的任何声音，避免叠加
            stopAllSounds();

            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;

            sVolumeManager = am;
            sSavedVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            int maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            am.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0);

            // 依次尝试：闹钟音 → 通知音 → 铃声 → ToneGenerator 兜底
            boolean played = playWithFallback(
                    new Uri[]{
                            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    },
                    AudioManager.STREAM_MUSIC,
                    true);

            if (!played) {
                playFallbackTone();
            }

            wakeScreen();
        } catch (Throwable t) {
            Log.e(TAG, "播放警报失败，尝试兜底音调", t);
            playFallbackTone();
        }
    }

    private void handleRing() {
        try {
            // 停止之前的任何声音，避免叠加
            stopAllSounds();

            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
                int maxRing = am.getStreamMaxVolume(AudioManager.STREAM_RING);
                am.setStreamVolume(AudioManager.STREAM_RING, maxRing, 0);
            }

            // 依次尝试：铃声 → 通知音 → 闹钟音 → ToneGenerator 兜底
            boolean played = playWithFallback(
                    new Uri[]{
                            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    },
                    AudioManager.STREAM_RING,
                    false);

            if (!played) {
                playFallbackTone();
            }

            wakeScreen();
        } catch (Throwable t) {
            Log.e(TAG, "响铃失败，尝试兜底音调", t);
            playFallbackTone();
        }
    }

    /**
     * 尝试用 MediaPlayer 播放第一个可用的系统铃声（单例管理，可被 STOP 停止）。
     * 全部失败返回 false（调用方可用 ToneGenerator 兜底）。
     */
    private boolean playWithFallback(Uri[] uris, int streamType, boolean restoreMusicVolume) {
        for (Uri uri : uris) {
            if (uri == null) continue;
            try {
                MediaPlayer mp = new MediaPlayer();
                mp.setDataSource(context, uri);
                mp.setAudioStreamType(streamType);
                mp.setLooping(true);
                mp.setVolume(1.0f, 1.0f);
                mp.prepare();
                mp.start();

                // 记录为当前活动播放器
                sActivePlayer = mp;
                Log.i(TAG, "正在播放铃声: " + uri);

                // 30 秒后自动停止（也可被 STOP 指令提前停止）
                scheduleAutoStop(restoreMusicVolume);
                return true;
            } catch (Throwable t) {
                Log.w(TAG, "铃声播放失败: " + uri + " -> " + t.getMessage());
            }
        }
        return false;
    }

    /**
     * 安排 30 秒后自动停止当前声音
     */
    private void scheduleAutoStop(boolean restoreMusicVolume) {
        android.os.Handler handler = new android.os.Handler(context.getMainLooper());
        sSoundHandler = handler;
        Runnable stopper = () -> {
            Log.i(TAG, "30秒超时，自动停止声音");
            stopAllSounds();
        };
        sSoundStopper = stopper;
        handler.postDelayed(stopper, 30000);
    }

    /**
     * ToneGenerator 兜底：不依赖任何音频文件，100% 可发声（单例管理，可被 STOP 停止）
     */
    private void playFallbackTone() {
        try {
            android.media.ToneGenerator tg =
                    new android.media.ToneGenerator(AudioManager.STREAM_MUSIC, 100);
            sActiveTone = tg;
            // 使用持续告警音，响 30 秒
            tg.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 30000);
            Log.i(TAG, "使用 ToneGenerator 播放告警音");

            scheduleAutoStop(false);
        } catch (Throwable t) {
            Log.e(TAG, "ToneGenerator 播放失败", t);
        }
    }

    private void handleLock() {
        DevicePolicyManager dpm = (DevicePolicyManager)
                context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(context, FmdDeviceAdminReceiver.class);

        if (dpm != null && dpm.isAdminActive(admin)) {
            dpm.lockNow();
            Log.i(TAG, "设备已锁定");
        } else {
            Log.w(TAG, "未激活设备管理员权限，无法锁屏");
            // 至少尝试点亮并立即关屏
            wakeScreen();
        }
    }

    private void handleWipe(String senderNumber) {
        sendSms(context, senderNumber,
                "[FindMyDevice] 警告！你请求了恢复出厂设置。\n"
                + "此操作将清除所有数据！\n"
                + "如确认，请在5分钟内发送 #FMD#CONFIRM_WIPE#");
        ConfigManager.setPendingAction(context, "wipe", senderNumber);
    }

    private void confirmWipe() {
        DevicePolicyManager dpm = (DevicePolicyManager)
                context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(context, FmdDeviceAdminReceiver.class);

        if (dpm != null && dpm.isAdminActive(admin)) {
            dpm.wipeData(DevicePolicyManager.WIPE_EXTERNAL_STORAGE);
            Log.i(TAG, "设备已恢复出厂设置");
        } else {
            Log.w(TAG, "未激活设备管理员权限，无法清除数据");
        }
    }

    private void handleCamera(String senderNumber) {
        try {
            CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (cm == null) {
                sendSms(context, senderNumber, "[FindMyDevice] 摄像头不可用");
                return;
            }

            String frontCameraId = null;
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics cc = cm.getCameraCharacteristics(id);
                Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    frontCameraId = id;
                    break;
                }
            }

            if (frontCameraId != null) {
                File dir = new File(context.getExternalFilesDir(null), "RemotePhotos");
                if (!dir.exists()) dir.mkdirs();
                String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
                File photo = new File(dir, "FMD_" + ts + ".jpg");
                sendSms(context, senderNumber,
                        "[FindMyDevice] 已尝试远程拍照，照片保存在: " + photo.getAbsolutePath());
            } else {
                sendSms(context, senderNumber, "[FindMyDevice] 未找到前置摄像头");
            }
        } catch (CameraAccessException e) {
            sendSms(context, senderNumber, "[FindMyDevice] 摄像头被占用");
        } catch (SecurityException e) {
            sendSms(context, senderNumber, "[FindMyDevice] 摄像头权限不足");
        } catch (Throwable t) {
            sendSms(context, senderNumber, "[FindMyDevice] 远程拍照失败: " + t.getMessage());
        }
    }

    private void handleInfo(String senderNumber) {
        try {
            TelephonyManager tm = (TelephonyManager)
                    context.getSystemService(Context.TELEPHONY_SERVICE);
            StringBuilder sb = new StringBuilder();
            sb.append("[FindMyDevice] 设备信息\n");
            sb.append("型号: ").append(Build.MODEL).append("\n");
            sb.append("品牌: ").append(Build.MANUFACTURER).append("\n");
            sb.append("系统: Android ").append(Build.VERSION.RELEASE).append("\n");
            sb.append("SDK: ").append(Build.VERSION.SDK_INT).append("\n");
            if (tm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    sb.append("IMEI: ").append(tm.getImei()).append("\n");
                }
                sb.append("运营商: ").append(tm.getNetworkOperatorName()).append("\n");
            }
            sendSms(context, senderNumber, sb.toString());
        } catch (Throwable t) {
            sendSms(context, senderNumber, "[FindMyDevice] 获取信息失败: " + t.getMessage());
        }
    }

    private void handleSilent() {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            am.setRingerMode(AudioManager.RINGER_MODE_SILENT);
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
            am.setStreamVolume(AudioManager.STREAM_ALARM, 0, 0);
        }
    }

    private void handleVibrate(String parameter) {
        Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            long duration = 3000;
            if (!parameter.isEmpty()) {
                try { duration = Long.parseLong(parameter) * 1000; } catch (NumberFormatException ignored) {}
            }
            duration = Math.min(duration, 30000);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(duration);
            }
        }
    }

    private void handleHelp(String senderNumber) {
        String help = "[FindMyDevice] 远程指令列表\n"
                + "格式: #FMD#指令#参数\n\n"
                + "LOCATE      获取当前位置（回复SMS）\n"
                + "ALARM       触发最大音量警报（30秒）\n"
                + "RING        强制响铃（静音也会响）\n"
                + "LOCK        锁屏（需激活设备管理员）\n"
                + "WIPE        恢复出厂设置（危险！）\n"
                + "CAMERA      远程前置拍照\n"
                + "INFO        获取设备信息\n"
                + "SILENT      设为静音\n"
                + "VIBRATE#N   震动N秒\n"
                + "URL#地址    打开网页\n"
                + "BATTERY     获取电量\n"
                + "HELP        本帮助信息";
        sendSms(context, senderNumber, help);
    }

    private void handleOpenUrl(String url) {
        try {
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://" + url;
            }
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable t) {
            Log.e(TAG, "打开 URL 失败", t);
        }
    }

    private void handleBattery(String senderNumber) {
        try {
            Intent batIntent = context.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (batIntent == null) return;

            int level = batIntent.getIntExtra("level", -1);
            int scale = batIntent.getIntExtra("scale", -1);
            int temperature = batIntent.getIntExtra("temperature", 0);
            int voltage = batIntent.getIntExtra("voltage", 0);
            int status = batIntent.getIntExtra("status", -1);

            String statusStr;
            switch (status) {
                case android.os.BatteryManager.BATTERY_STATUS_CHARGING:
                    statusStr = "充电中"; break;
                case android.os.BatteryManager.BATTERY_STATUS_FULL:
                    statusStr = "已满电"; break;
                case android.os.BatteryManager.BATTERY_STATUS_DISCHARGING:
                    statusStr = "放电中"; break;
                case android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING:
                    statusStr = "未充电"; break;
                default: statusStr = "未知";
            }

            int pct = (int) ((float) level / scale * 100);
            float tempC = temperature / 10f;

            String msg = String.format(
                    "[FindMyDevice] 电池状态\n电量: %d%%\n状态: %s\n温度: %.1f°C\n电压: %dmV",
                    pct, statusStr, tempC, voltage);
            sendSms(context, senderNumber, msg);
        } catch (Throwable t) {
            sendSms(context, senderNumber, "[FindMyDevice] 获取电量失败");
        }
    }

    /**
     * 处理二次确认指令 (CONFIRM_WIPE)
     */
    public void handleConfirmAction(String senderNumber) {
        ConfigManager.PendingAction pending = ConfigManager.getPendingAction(context);
        if (pending == null) {
            sendSms(context, senderNumber, "[FindMyDevice] 没有待确认的操作");
            return;
        }
        if (!senderNumber.equals(pending.senderNumber)) {
            sendSms(context, senderNumber, "[FindMyDevice] 此号码无权确认操作");
            return;
        }
        if (System.currentTimeMillis() - pending.timestamp > 5 * 60 * 1000) {
            ConfigManager.clearPendingAction(context);
            sendSms(context, senderNumber, "[FindMyDevice] 操作已超时，请重新发送指令");
            return;
        }

        switch (pending.action) {
            case "wipe":
                confirmWipe();
                sendSms(context, senderNumber, "[FindMyDevice] 恢复出厂设置已执行");
                break;
            default:
                sendSms(context, senderNumber, "[FindMyDevice] 未知确认操作");
        }
        ConfigManager.clearPendingAction(context);
    }

    // ==================== 工具方法 ====================

    public static void sendSms(Context context, String destination, String message) {
        try {
            SmsManager sms = SmsManager.getDefault();
            if (message.length() > 160) {
                sms.sendMultipartTextMessage(destination, null,
                        sms.divideMessage(message), null, null);
            } else {
                sms.sendTextMessage(destination, null, message, null, null);
            }
            Log.i(TAG, "SMS 已发送到 " + destination + ": " + message.length() + " 字符");
        } catch (SecurityException e) {
            Log.e(TAG, "发送 SMS 权限不足", e);
        } catch (Throwable t) {
            Log.e(TAG, "发送 SMS 失败", t);
        }
    }

    private void wakeScreen() {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            PowerManager.WakeLock wl = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP
                    | PowerManager.ON_AFTER_RELEASE,
                    "FindMyDevice:WakeLock");
            wl.acquire(10000);
        }
    }
}