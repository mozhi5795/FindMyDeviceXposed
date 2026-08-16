package com.fyne.findmydevice;

import android.Manifest;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * FindMyDevice 配置界面
 *
 * LSPosed 模块管理器打开跳转至此界面，
 * 用于配置开机自启、SMS 远程控制、服务器地址等。
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_PERMISSIONS = 100;

    // 需要动态申请的运行时权限
    private static final String[] NEEDED_PERMISSIONS = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS, // Android 13+
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
    };

    private SharedPreferences prefs;

    private CheckBox chkBootStart;
    private CheckBox chkSmsControl;
    private CheckBox chkAllowAll;
    private CheckBox chkAutoReport;
    private CheckBox chkServerPoll;

    private EditText etCommandPrefix;
    private EditText etAuthorizedNumbers;
    private EditText etServerUrl;

    private TextView tvStatus;
    private TextView tvDeviceToken;
    private Button btnActivateAdmin;
    private Button btnSave;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = ConfigManager.getPreferences(this);

        initViews();
        loadConfig();

        // 动态申请运行时权限
        requestNeededPermissions();
    }

    private void initViews() {
        chkBootStart = findViewById(R.id.chk_boot_start);
        chkSmsControl = findViewById(R.id.chk_sms_control);
        chkAllowAll = findViewById(R.id.chk_allow_all);
        chkAutoReport = findViewById(R.id.chk_auto_report);
        chkServerPoll = findViewById(R.id.chk_server_poll);

        etCommandPrefix = findViewById(R.id.et_command_prefix);
        etAuthorizedNumbers = findViewById(R.id.et_authorized_numbers);
        etServerUrl = findViewById(R.id.et_server_url);

        tvStatus = findViewById(R.id.tv_status);
        tvDeviceToken = findViewById(R.id.tv_device_token);
        btnActivateAdmin = findViewById(R.id.btn_activate_admin);
        btnSave = findViewById(R.id.btn_save);

        // 服务端开关联动
        chkServerPoll.setOnCheckedChangeListener((buttonView, isChecked) -> {
            etServerUrl.setEnabled(isChecked);
            if (isChecked) {
                // 启用时自动开启自动上报
                chkAutoReport.setChecked(true);
            }
        });

        btnActivateAdmin.setOnClickListener(v -> activateDeviceAdmin());

        btnSave.setOnClickListener(v -> saveConfig());
    }

    private void loadConfig() {
        chkBootStart.setChecked(prefs.getBoolean(ConfigManager.KEY_BOOT_START, true));
        chkSmsControl.setChecked(prefs.getBoolean(ConfigManager.KEY_SMS_CONTROL, true));
        chkAllowAll.setChecked(prefs.getBoolean(ConfigManager.KEY_ALLOW_ALL_SENDERS, false));
        chkAutoReport.setChecked(prefs.getBoolean(ConfigManager.KEY_AUTO_REPORT, false));
        chkServerPoll.setChecked(ConfigManager.isServerPollEnabled(this));

        etCommandPrefix.setText(prefs.getString(
                ConfigManager.KEY_COMMAND_PREFIX, ConfigManager.DEFAULT_COMMAND_PREFIX));
        etAuthorizedNumbers.setText(prefs.getString(
                ConfigManager.KEY_AUTHORIZED_NUMBERS, ""));
        etServerUrl.setText(ConfigManager.getServerUrl(this));

        // 根据开关状态控制编辑框
        etServerUrl.setEnabled(chkServerPoll.isChecked());

        // 显示设备标识
        String token = ConfigManager.getDeviceToken(this);
        tvDeviceToken.setText("设备标识: " + token);

        // 检查设备管理员状态
        updateDeviceAdminStatus();
    }

    /**
     * 动态申请运行时权限（Android 6.0+ 必需）
     */
    private void requestNeededPermissions() {
        List<String> missing = new ArrayList<>();
        for (String permission : NEEDED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }
        if (!missing.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    missing.toArray(new String[0]), REQ_PERMISSIONS);
        }
    }

    /**
     * 检查定位权限是否已授予
     */
    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void saveConfig() {
        SharedPreferences.Editor editor = prefs.edit();

        editor.putBoolean(ConfigManager.KEY_BOOT_START, chkBootStart.isChecked());
        editor.putBoolean(ConfigManager.KEY_SMS_CONTROL, chkSmsControl.isChecked());
        editor.putBoolean(ConfigManager.KEY_ALLOW_ALL_SENDERS, chkAllowAll.isChecked());
        editor.putBoolean(ConfigManager.KEY_AUTO_REPORT, chkAutoReport.isChecked());
        editor.putBoolean(ConfigManager.KEY_SERVER_POLL_ENABLED, chkServerPoll.isChecked());

        String prefix = etCommandPrefix.getText().toString().trim();
        if (prefix.isEmpty()) {
            prefix = ConfigManager.DEFAULT_COMMAND_PREFIX;
        }
        editor.putString(ConfigManager.KEY_COMMAND_PREFIX, prefix);

        editor.putString(ConfigManager.KEY_AUTHORIZED_NUMBERS,
                etAuthorizedNumbers.getText().toString().trim());

        editor.putString(ConfigManager.KEY_SERVER_URL,
                etServerUrl.getText().toString().trim());

        editor.apply();

        // 根据配置变化启停服务（先检查定位权限，防止闪退）
        if (chkBootStart.isChecked()) {
            if (hasLocationPermission()) {
                startMonitorService();
            } else {
                Toast.makeText(this, "请先授权定位权限（点击右上角或系统设置）",
                        Toast.LENGTH_LONG).show();
                requestNeededPermissions();
            }
        }

        if (chkServerPoll.isChecked()) {
            startPollingService();
        } else {
            Intent intent = new Intent(this, LocationService.class);
            intent.setAction(LocationService.ACTION_STOP_POLLING);
            startService(intent);
        }

        Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show();
    }

    private void startMonitorService() {
        Intent intent = new Intent(this, LocationService.class);
        intent.setAction(LocationService.ACTION_START_MONITOR);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void startPollingService() {
        Intent intent = new Intent(this, LocationService.class);
        intent.setAction(LocationService.ACTION_START_POLLING);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void activateDeviceAdmin() {
        ComponentName admin = new ComponentName(this, FmdDeviceAdminReceiver.class);
        DevicePolicyManager dpm = (DevicePolicyManager)
                getSystemService(DEVICE_POLICY_SERVICE);

        if (dpm != null && !dpm.isAdminActive(admin)) {
            Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin);
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "激活设备管理员权限后，可通过远程指令锁屏或清除数据");
            startActivity(intent);
        } else {
            Toast.makeText(this, "设备管理员已激活", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateDeviceAdminStatus() {
        ComponentName admin = new ComponentName(this, FmdDeviceAdminReceiver.class);
        DevicePolicyManager dpm = (DevicePolicyManager)
                getSystemService(DEVICE_POLICY_SERVICE);

        boolean isActive = dpm != null && dpm.isAdminActive(admin);
        if (isActive) {
            tvStatus.setText("设备管理员: 已激活 ✓");
            tvStatus.setTextColor(0xFF4CAF50);
            btnActivateAdmin.setText("已激活（点击重新申请）");
        } else {
            tvStatus.setText("设备管理员: 未激活 ✗（锁屏/WIPE功能不可用）");
            tvStatus.setTextColor(0xFFFF5722);
            btnActivateAdmin.setText("激活设备管理员");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateDeviceAdminStatus();
        // 从系统设置返回时，如果已授权权限，自动启动服务
        if (prefs.getBoolean(ConfigManager.KEY_BOOT_START, true)
                && hasLocationPermission()
                && chkBootStart != null && chkBootStart.isChecked()) {
            startMonitorService();
        }
    }
}