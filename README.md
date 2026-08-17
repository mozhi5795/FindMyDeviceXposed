# FindMyDevice — 手机丢了也能找到

一个 Android Xposed 模块 + Web 地图看板。手机被偷/弄丢后，用另一台手机发短信或打开网页就能定位、响警报、锁屏。

---

## 一句话说明

- **在手机上装一个模块** → 后台轮询等待指令
- **发短信 `#FMD#LOCATE#`** → 手机自动回你一条带地图链接的位置短信
- **或打开 Web 看板** → 地图上标位置，点按钮发指令（定位/响铃/警报/锁屏/震动/静音）
- **不开 GPS** → 只有收到定位指令才临时开一下，定位完就关，省电

---

## 需要什么

| 东西 | 说明 |
|------|------|
| 🏠 **一台有 Root 的手机** | 已刷 KernelSU 或 Magisk |
| 🛠️ **LSPosed 框架** | 用来跑 Xposed 模块（作用域选 System Framework + System UI） |
| 💻 **（可选）Web 服务器** | Cloudflare Workers（免费）或自己的服务器 |
| 📱 **另一台手机** | 用来发短信遥控（或者直接用 Web 看板） |

---

## 安装步骤

### 1️⃣ 装 KSU 保活模块（推荐，可选）

把 `FindMyDevice-KSU-v1.0.zip` 刷进 KernelSU：

```
KernelSU Manager → 模块 → 本地安装 → 选 ZIP → 重启
```

这个模块负责：
- **开机自动启动轮询服务**，不等你解锁屏幕
- **锁住进程优先级**，系统杀不掉
- **30 秒看门狗**，挂了自动重启
- **Shell 后备轮询**，Java 服务还没起来时也能响应定位指令

> ⚠️ Android 14+ 强制前台服务要有通知，所以通知栏会有一条「查找设备运行中」的提示，低优先级、静音、不震动。

### 2️⃣ 装 APK + 激活 LSPosed

```bash
# 安装 APK（用仓库里编译好的 app-release.apk）
adb install app-release.apk
# 或者直接拷到手机上安装
```

然后在 LSPosed Manager 里：
1. 启用 **FindMyDevice 远程查找** 模块
2. 作用域勾选 **系统框架（System Framework）** 和 **系统界面（System UI）**
3. 重启手机

### 3️⃣ 首次配置

打开手机上的 **FindMyDevice** App（桌面图标或 LSPosed 里打开）：
1. **授权号码** — 填你另一台手机号（逗号分隔多个）
2. **启用 Web 看板** — 如果有部署看板就填上地址、勾上
3. **激活设备管理员** — 点按钮，按系统提示激活（锁屏/WIPE 需要）
4. 保存

### 4️⃣ 解锁一次

重启后 **解锁屏幕一次**，服务自动上线。以后每次重启都这样——解锁即认，不需要再打开 App。

---

## SMS 指令

从另一台手机发短信到被找手机：

| 发这个 | 干啥 | 收到啥回复 |
|--------|------|-----------|
| `#FMD#LOCATE#` | 📍 获取位置 | 经纬度 + 地图链接 + 地址 |
| `#FMD#ALARM#` | 🔔 最大音量警报 30 秒 | 确认消息 |
| `#FMD#RING#` | 📞 强制响铃（静音模式也响） | 确认消息 |
| `#FMD#LOCK#` | 🔒 锁屏 | 确认消息 |
| `#FMD#WIPE#` | 💀 **恢复出厂设置！（危险）** | 二次确认提示 |
| `#FMD#CONFIRM_WIPE#` | 确认擦除 | 执行结果 |
| `#FMD#CAMERA#` | 📸 前置拍照 | 确认消息 |
| `#FMD#INFO#` | ℹ️ 设备信息 | 型号、系统版本、IMEI |
| `#FMD#SILENT#` | 🔇 静音 | 确认消息 |
| `#FMD#VIBRATE#5#` | 📳 震动 5 秒 | 确认消息 |
| `#FMD#URL#https://...` | 🌐 打开网页 | 确认消息 |
| `#FMD#BATTERY#` | 🔋 电量 | 百分比 + 温度 + 电压 |
| `#FMD#HELP#` | ❓ 帮助 | 指令列表 |

> 指令前缀 `#FMD#` 可以在 App 设置里改成你喜欢的。

---

## Web 看板部署

推荐用 **Cloudflare Workers**，免费、自带 HTTPS、国内也能访问。

### Cloudflare Workers 部署

```bash
# 1. 安装 Wrangler
npm install -g wrangler
wrangler login

# 2. 创建 KV 命名空间
wrangler kv:namespace create FMD_KV
# → 输出类似: 🌀  id = "abc123..."
# 把 id 填进 server/wrangler.toml 的 id 字段

# 3. 部署
cd server
wrangler deploy

# 完成后得到:
# https://findmydevice.你的子域名.workers.dev
```

### 也可以 GitHub 自动部署

在 Cloudflare Dashboard：
1. **Workers & Pages → 创建 → 连接到 Git**
2. 选这个仓库，根目录填 `server/`
3. 构建命令留空
4. 以后每次 `git push` 自动部署

### 首次打开看板

访问 Worker 地址：
1. 自动跳到 `/setup` → 设置管理员密码
2. 设好后跳转登录页
3. 登录 → 看到地图和设备列表

### 手机端填地址

在 App 设置里：
1. 勾选 **启用 Web 看板**
2. 填入 `https://你的域名.workers.dev`
3. 保存

---

## 项目结构

```
FindMyDeviceXposed/
├── app/                                   # Android 手机端
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── assets/xposed_init             # Xposed 入口
│   │   ├── java/com/fyne/findmydevice/
│   │   │   ├── FindMyDeviceEntry.java     # Xposed 入口 → 注入系统进程
│   │   │   ├── BootReceiver.java          # 开机广播 → 启动轮询
│   │   │   ├── SmsReceiver.java           # 拦截短信 → 匹配指令
│   │   │   ├── CommandProcessor.java      # 执行指令（定位/响铃/锁屏等）
│   │   │   ├── LocationService.java       # 轮询服务器 + 单次定位
│   │   │   ├── ConfigManager.java         # 配置读写（DE 存储，解锁前后都可读）
│   │   │   ├── NotificationHelper.java    # 通知渠道管理
│   │   │   ├── DeviceAdminReceiver.java   # 设备管理员（锁屏/WIPE）
│   │   │   └── MainActivity.java          # 配置界面
│   │   └── res/                           # 布局/字符串/主题
│   └── build.gradle.kts
├── server/                                # Web 看板
│   ├── worker.js                          # Cloudflare Worker（主文件）
│   ├── server.js                          # Node.js 版（本地测试用）
│   ├── wrangler.toml                      # Wrangler 配置
│   └── public/                            # 前端页面（Node.js 版用）
│       ├── index.html
│       ├── style.css
│       └── app.js
├── ksu_module/                            # KernelSU 保活模块
│   ├── module.prop
│   └── service.sh                         # 开机启动 + 保活
├── build.gradle.kts
├── settings.gradle.kts
├── build.ps1                              # Windows 一键编译脚本
└── README.md
```

---

## 自己编译

Windows：
```powershell
.\build.ps1              # Debug APK + 自动打包 KSU ZIP
.\build.ps1 -Release     # Release APK + KSU ZIP
```

Linux/macOS：
```bash
cd FindMyDeviceXposed
./gradlew assembleRelease
cd ksu_module && zip -r ../FindMyDevice-KSU.zip * && cd ..
```

编译好的文件：
- **APK**: `app/build/outputs/apk/release/app-release.apk`
- **KSU ZIP**: `FindMyDevice-KSU-v1.0.zip`

---

## 注意事项

⚠️ **安全：**
- 授权号码别填错，不然别人也能遥控你的手机
- Web 看板**必须 HTTPS**，否则位置信息会被中间人看到
- WIPE 是**恢复出厂设置**，所有数据都没了，需要二次确认
- 模块会拦截以 `#FMD#` 开头的短信，不影响其他短信

📱 **兼容：**
- Android 8.0+（API 26+）
- 需要 LSPosed（或其他 Xposed 兼容框架）
- 测试通过：KernelSU + LSPosed

🔋 **省电：**
- 不开 GPS 持续定位，只有收到 LOCATE 指令才临时开一下
- 手机每 5 秒一次 HTTP 轮询检查指令（省电模式）
- Web 看板每 5 秒刷新设备状态

---

## License

MIT