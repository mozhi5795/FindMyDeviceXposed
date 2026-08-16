package com.fyne.findmydevice;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.util.Log;
import android.provider.Telephony;

/**
 * SMS 广播接收器
 *
 * 拦截收到的短信，匹配远程控制指令并执行。
 * 当短信以命令前缀开头且发送者已授权时，
 * 拦截该短信并执行对应远程操作。
 *
 * 指令格式：#FMD#指令#[参数]
 * 例如：#FMD#LOCATE# 获取位置回短信
 *       #FMD#ALARM#  最大音量报警30秒
 */
public class SmsReceiver extends BroadcastReceiver {

    private static final String TAG = "FindMyDevice_SMS";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) {
            return;
        }

        SharedPreferences prefs = ConfigManager.getPreferences(context);
        if (!prefs.getBoolean(ConfigManager.KEY_SMS_CONTROL, true)) {
            return;
        }

        String commandPrefix = prefs.getString(
                ConfigManager.KEY_COMMAND_PREFIX, ConfigManager.DEFAULT_COMMAND_PREFIX);
        String authorizedNumbers = prefs.getString(
                ConfigManager.KEY_AUTHORIZED_NUMBERS, "");

        String messageBody = null;
        String senderNumber = null;

        Bundle bundle = intent.getExtras();
        if (bundle != null) {
            Object[] pdus = (Object[]) bundle.get("pdus");
            if (pdus != null) {
                for (Object pdu : pdus) {
                    SmsMessage sms = SmsMessage.createFromPdu((byte[]) pdu);
                    if (sms != null) {
                        senderNumber = sms.getOriginatingAddress();
                        if (sms.getMessageBody() != null) {
                            messageBody = sms.getMessageBody();
                        }
                    }
                }
            }
        }

        if (messageBody == null || senderNumber == null) {
            return;
        }

        Log.i(TAG, "收到来自 " + senderNumber + " 的短信");

        if (!messageBody.startsWith(commandPrefix)) {
            return;
        }

        String commandPart = messageBody.substring(commandPrefix.length());

        // 特殊处理二次确认指令 CONFIRM_WIPE
        if (commandPart.trim().equalsIgnoreCase("CONFIRM_WIPE")) {
            abortBroadcast();
            new CommandProcessor(context).handleConfirmAction(senderNumber);
            return;
        }

        // 验证发送者是否在授权列表
        if (!isAuthorized(senderNumber, authorizedNumbers, prefs)) {
            Log.w(TAG, "未授权发送者: " + senderNumber);
            CommandProcessor.sendSms(context, senderNumber,
                    "FMD: 未授权号码，指令已被忽略");
            return;
        }

        Log.i(TAG, "执行远程指令: " + commandPart);

        // 拦截广播，不显示该短信到通知栏
        abortBroadcast();

        new CommandProcessor(context).executeCommand(commandPart, senderNumber);
    }

    private boolean isAuthorized(String sender, String authorizedList,
                                  SharedPreferences prefs) {
        if (authorizedList == null || authorizedList.trim().isEmpty()) {
            return prefs.getBoolean(ConfigManager.KEY_ALLOW_ALL_SENDERS, false);
        }

        String normalizedSender = normalizeNumber(sender);
        String[] numbers = authorizedList.split(",");

        for (String number : numbers) {
            String normalized = normalizeNumber(number.trim());
            if (!normalized.isEmpty() && normalizedSender.endsWith(normalized)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeNumber(String number) {
        if (number == null) return "";
        return number.replaceAll("[^0-9+]", "");
    }
}