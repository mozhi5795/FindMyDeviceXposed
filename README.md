# FindMyDevice - LSPosed 远程查找模块 + Web 看板

## 功能概述

手机端 Xposed 模块 + 服务端 Web 看板，实现：

**手机端（LSPosed 模块）：**
- ✅ 开机自启（BOOT_COMPLETED 广播 + Xposed 系统进程注入）
- ✅ GPS/Network 双定位
- ✅ SMS 远程指令控制（另一台手机发短信查位置/响警报/锁屏等）
- ✅ 定时上报位置到 Web 服务器
- ✅ 轮询 Web 服务器指令并执行
- ✅ 警报声/响铃/震动/静音远程触发

**Web 看板（Node.js 服务端）：**
- ✅ 实时地图显示设备位置（Leaflet + OpenStreetMap）
- ✅ 设备在线状态/电量/位置信息
- ✅ 远程指令一键发送（定位/警报/响铃/锁屏/静音/震动）
- ✅ 指令执行历史
- ✅ 设备移动轨迹回放

## 架构

```
┌─────────────────────┐     SMS 指令      ┌──────────────┐
│  被找手机            │ ◄─────────────── │  你的另一台   │
│  LSPosed 模块运行中   │ ────────────────►│  手机         │
│                      │  SMS 回复位置信息  │              │
│  定时上报位置 ────────┼──────────────────►└──────────────┘
│                      │                   ┌──────────────┐
│  轮询待执行指令 ◄─────┼───────────────────│  Web 看板    │
│  执行结果上报 ────────┼──────────────────►│  Node.js     │
└─────────────────────┘                   │  + 浏览器地图 │
                                          └──────────────┘
```

## 手机端模块安装

### 前置条件
1. 手机已 Root（KernelSU / Magisk）
2. 已安装 LSPosed 框架（推荐 LSPosed-v1.9.2+）

### 编译安装
```bash
# 用 Android Studio 打开 FindMyDeviceXposed 目录
# 或命令行编译：
cd FindMyDeviceXposed
./gradlew assembleRelease
# 生成的 APK 在 app/build/outputs/apk/release/
```

### LSPosed 激活
1. 安装 APK
2. 打开 LSPosed 模块管理器
3. 启用「FindMyDevice 远程查找」模块
4. 推荐作用域：**系统框架（System Framework）** 和 **系统界面（System UI）**
5. 重启手机

### 首次配置
1. 打开模块的配置界面（桌面图标或 LSPosed 中打开）
2. 在「授权号码」中添加你的另一台手机号（用逗号分隔）
3. 激活「设备管理员」权限（用于远程锁屏/清除数据）
4. 如使用 Web 看板，填入服务器地址并启用

## SMS 远程指令

从另一台手机发送短信到被找手机：

| 指令 | 作用 | 回复 |
|------|------|------|
| `#FMD#LOCATE#` | 获取当前位置 | 经纬度 + 地图链接 + 地址 |
| `#FMD#ALARM#` | 最大音量警报 30秒 | 确认信息 |
| `#FMD#RING#` | 强制响铃 30秒 | 确认信息 |
| `#FMD#LOCK#` | 锁屏 | 确认信息 |
| `#FMD#WIPE#` | **恢复出厂设置（危险！）** | 二次确认提示 |
| `#FMD#CONFIRM_WIPE#` | 确认擦除数据 | 执行结果 |
| `#FMD#CAMERA#` | 远程拍照 | 确认信息 |
| `#FMD#INFO#` | 获取设备信息 | 型号、IMEI、系统版本 |
| `#FMD#SILENT#` | 设为静音 | 确认信息 |
| `#FMD#VIBRATE#5#` | 震动5秒 | 确认信息 |
| `#FMD#URL#https://...` | 打开网页 | 确认信息 |
| `#FMD#BATTERY#` | 获取电量 | 电量百分比、温度 |
| `#FMD#HELP#` | 获取帮助 | 指令列表 |

> **提示**：可在模块设置中自定义命令前缀（默认 `#FMD#`）和授权号码列表。

## 🔐 登录认证

Web 看板需要登录才能使用，防止别人乱发指令。

**首次部署后，第一次访问会自动跳到 `/setup` 页面**，让你设置管理员密码。

之后每次访问看板都需要输入密码登录。手机端上报位置不受影响（使用设备 token 认证）。

## Web 看板部署

提供两种部署方式，推荐 Cloudflare Workers。

### 方案一：Cloudflare Workers（推荐）—— 支持 GitHub 自动部署

#### 方法 A：Wrangler CLI 部署

```bash
# 1. 安装 Wrangler
npm install -g wrangler
wrangler login

# 2. 创建 KV 命名空间
wrangler kv:namespace create FMD_KV
# → 输出类似: 🌀  id = "abc123..."
# 把这个 id 填入 server/wrangler.toml 的 id 字段

# 3. 部署
cd server
wrangler deploy

# 完成后得到:
# https://findmydevice.你的子域名.workers.dev
```

#### 方法 B：GitHub → Cloudflare 自动部署（推荐）

1. **把 server/ 目录推送到 GitHub 仓库**

```bash
# 在 GitHub 上创建新仓库
# 然后在本地:
cd server
git init
git add .
git commit -m "初始提交"
git remote add origin https://github.com/你的用户名/你的仓库名.git
git push -u origin main
```

2. **在 Cloudflare 控制台连接 GitHub**

   - 登录 [Cloudflare Dashboard](https://dash.cloudflare.com/)
   - 进入 **Workers & Pages**
   - 点击 **创建 → 连接到 Git**
   - 授权 GitHub 账号，选择刚才的仓库
   - 构建配置：
     - **根目录**：`server/`
     - **构建命令**：留空（Worker 无需构建）
     - **发布命令**：留空
   - 点击 **保存并部署**

3. **后续更新**

每次改完代码：

```bash
git add .
git commit -m "更新功能"
git push
```

Cloudflare 会自动重新部署，无需手动操作。

#### 首次设置

部署成功后访问 Worker 地址：
1. **第一次访问** → 自动跳转到 `/setup` 页面
2. 设置管理员密码（至少6位）
3. 设置成功后自动跳转到登录页
4. 输入密码登录 → 进入看板

#### 手机端配置

在手机模块设置中填入 Worker 地址（如 `https://findmydevice.你的子域名.workers.dev`），启用 Web 看板。

### 方案二：Node.js 本地运行（仅供测试）

Node.js 版**没有登录功能**，仅用于临时测试。

```bash
cd server
npm install --omit=dev
node server.js
```
打开 `http://localhost:3000`

### 方案三：Cloudflare Tunnel（自建服务器 + CF 隧道）

```bash
# 电脑运行 Node.js（仅供测试）
cd server && node server.js &

# cloudflared 暴露
cloudflared tunnel --url http://localhost:3000
```
得到一个临时 `https://xxx.trycloudflare.com` 地址。

### 手机端配置（通用）

在模块设置中：
1. 勾选「启用 Web 看板」
2. 填入看板地址
3. 点击保存

## 项目结构

```
FindMyDeviceXposed/
├── app/                          # Android 模块
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/xposed_init    # Xposed 入口声明
│       ├── java/com/fyne/findmydevice/
│       │   ├── FindMyDeviceEntry.java    # Xposed 入口
│       │   ├── BootReceiver.java         # 开机自启
│       │   ├── SmsReceiver.java          # SMS 拦截
│       │   ├── LocationService.java      # 定位服务 + 轮询
│       │   ├── CommandProcessor.java     # 指令处理
│       │   ├── ConfigManager.java        # 配置管理
│       │   ├── NotificationHelper.java   # 通知辅助
│       │   ├── DeviceAdminReceiver.java  # 设备管理员
│       │   └── MainActivity.java         # 配置界面
│       └── res/
│           ├── layout/activity_main.xml
│           ├── values/strings.xml
│           ├── values/themes.xml
│           └── xml/device_admin.xml
├── server/                       # Web 看板服务端
│   ├── package.json
│   ├── server.js                 # Express + Socket.IO
│   └── public/
│       ├── index.html            # 看板页面
│       ├── style.css             # 暗色主题样式
│       └── app.js                # 前端逻辑
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

## 注意事项

⚠️ **安全警告：**
- SMS 授权号码务必填写正确，避免他人恶意控制
- Web 看板在外网使用时必须配置 HTTPS，否则位置信息可能被中间人窃取
- WIPE（恢复出厂设置）为危险操作，需要二次确认
- 该模块会拦截指定前缀的短信，可能影响正常短信接收

📱 **兼容性：**
- 支持 Android 8.0+（API 26+）
- 需要 LSPosed 框架（或其他 Xposed 兼容框架）
- 在 KernelSU + LSPosed 环境下测试通过

## License

MIT