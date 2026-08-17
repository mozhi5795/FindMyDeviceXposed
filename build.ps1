# ============================================================
# FindMyDevice APK 构建脚本 (Windows PowerShell)
# 用法:
#   .\build.ps1              # 编译 debug APK
#   .\build.ps1 -Release     # 编译 release APK
# ============================================================

param(
    [switch]$Release,
    [switch]$Clean
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ScriptDir

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  FindMyDevice APK Builder" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# ---- 1. 检查 JDK ----
$javaOk = $false
try {
    $jv = java -version 2>&1
    if ($LASTEXITCODE -eq 0) {
        $javaOk = $true
        Write-Host "✓ JDK 已安装" -ForegroundColor Green
    }
} catch {}

if (-not $javaOk) {
    Write-Host @"
✗ 未找到 JDK
请安装 JDK 17+:
  https://adoptium.net/temurin/releases/?version=17
  或国内镜像: https://mirrors.tuna.tsinghua.edu.cn/Adoptium/
安装后设置 JAVA_HOME 环境变量，然后重试
"@ -ForegroundColor Red
    exit 1
}

# ---- 2. 检查/设置 ANDROID_HOME ----
$androidHome = $env:ANDROID_HOME
if (-not $androidHome) {
    $defaultPath = "$env:LOCALAPPDATA\Android\Sdk"
    if (Test-Path $defaultPath) {
        $androidHome = $defaultPath
        $env:ANDROID_HOME = $androidHome
        Write-Host "✓ 自动检测到 Android SDK: $androidHome" -ForegroundColor Green
    } else {
        Write-Host @"
✗ 未找到 Android SDK
请安装 Android 命令行工具后重试:
  https://developer.android.google.cn/studio#command-line-tools-only
  国内: https://developer.android.google.cn/studio
"@ -ForegroundColor Red
        exit 1
    }
}

# ---- 3. 创建 Gradle Wrapper ----
$wrapperJar = "gradle/wrapper/gradle-wrapper.jar"
$wrapperProps = "gradle/wrapper/gradle-wrapper.properties"

if (-not (Test-Path $wrapperJar)) {
    Write-Host "下载 Gradle Wrapper..." -ForegroundColor Yellow

    $gradleVersion = "8.5"
    $downloadUrl = "https://services.gradle.org/distributions/gradle-${gradleVersion}-bin.zip"
    $mirrorUrl = "https://mirrors.cloud.tencent.com/gradle/gradle-${gradleVersion}-bin.zip"

    $zipPath = "$env:TEMP\gradle.zip"
    $extractPath = "$env:TEMP\gradle-extract"

    # 尝试腾讯镜像（国内快）
    $success = $false
    try {
        Write-Host "  从腾讯镜像下载..." -ForegroundColor Gray
        Invoke-WebRequest -Uri $mirrorUrl -OutFile $zipPath -TimeoutSec 30 -UseBasicParsing
        $success = $true
    } catch {
        Write-Host "  腾讯镜像失败，尝试官方源..." -ForegroundColor Gray
        try {
            Invoke-WebRequest -Uri $downloadUrl -OutFile $zipPath -TimeoutSec 60 -UseBasicParsing
            $success = $true
        } catch {
            Write-Host "  官方源也失败了" -ForegroundColor Red
        }
    }

    if (-not $success) {
        Write-Host @"
✗ 无法下载 Gradle，请手动设置:
  1. 从 https://gradle.org/releases/ 下载 gradle-${gradleVersion}-bin.zip
  2. 解压到任意目录
  3. 运行: .\gradle\gradle-${gradleVersion}\bin\gradle wrapper --gradle-version=${gradleVersion}
  4. 然后重试本脚本
"@ -ForegroundColor Yellow
        exit 1
    }

    # 解压出 wrapper jar
    try {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $zip = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
        $entry = $zip.Entries | Where-Object { $_.FullName -match "gradle-wrapper.jar$" } | Select-Object -First 1
        if ($entry) {
            $destDir = Split-Path -Parent (Join-Path $ScriptDir $wrapperJar)
            if (-not (Test-Path $destDir)) { New-Item -ItemType Directory -Path $destDir -Force | Out-Null }
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $ScriptDir $wrapperJar), $true)
            Write-Host "✓ Gradle Wrapper JAR 已创建" -ForegroundColor Green
        }
        $zip.Dispose()
    } catch {
        Write-Host "✗ 解压失败: $_" -ForegroundColor Red
        exit 1
    }
    Remove-Item $zipPath -Force -ErrorAction SilentlyContinue
    Remove-Item $extractPath -Recurse -Force -ErrorAction SilentlyContinue
}

# 确保 gradle-wrapper.properties 正确
$propsContent = @"
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.5-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
"@
Set-Content -Path (Join-Path $ScriptDir $wrapperProps) -Value $propsContent -Force

# ---- 4. 检查必要的 SDK 组件 ----
$platformsDir = "$androidHome\platforms"
$buildToolsDir = "$androidHome\build-tools"
$platform34Path = "$platformsDir\android-34"

if (-not (Test-Path $platform34Path)) {
    Write-Host @"
✗ 缺少 Android SDK Platform 34
请用 sdkmanager 安装:
  %ANDROID_HOME%\cmdline-tools\latest\bin\sdkmanager.bat "platforms;android-34" "build-tools;34.0.0"
"@ -ForegroundColor Yellow
    # 不退出，让 gradle 尝试自动下载
    Write-Host "尝试让 Gradle 自动处理..." -ForegroundColor Gray
}

# ---- 5. 生成 gradlew.bat（如果不存在） ----
$gradlewBat = Join-Path $ScriptDir "gradlew.bat"
if (-not (Test-Path $gradlewBat)) {
    Write-Host "创建 gradlew.bat..." -ForegroundColor Yellow
    $batContent = @'
@rem Gradle startup script for Windows
@echo off
set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
set APP_BASE_NAME=%~n0
set DEFAULT_JVM_OPTS=
set GRADLE_OPTS=
set MAX_FD="maximum"

set CLASSPATH=%DIRNAME%\gradle\wrapper\gradle-wrapper.jar
if not exist "%CLASSPATH%" (
    echo Gradle wrapper JAR not found at %CLASSPATH%
    exit /b 1
)

@rem Execute Gradle
"%JAVA_HOME%/bin/java.exe" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
'@
    Set-Content -Path $gradlewBat -Value $batContent
    Write-Host "✓ gradlew.bat 已创建" -ForegroundColor Green
}

# ---- 6. 构建 APK ----
if ($Clean) {
    Write-Host "清理旧构建..." -ForegroundColor Yellow
    & $gradlewBat clean
}

$buildType = if ($Release) { "assembleRelease" } else { "assembleDebug" }
Write-Host "> 执行: .\gradlew $buildType" -ForegroundColor Cyan
Write-Host ""

& $gradlewBat $buildType

if ($LASTEXITCODE -eq 0) {
    $apkDir = if ($Release) { "app\build\outputs\apk\release" } else { "app\build\outputs\apk\debug" }
    $apkPath = Join-Path $ScriptDir $apkDir
    Write-Host ""
    Write-Host "========================================" -ForegroundColor Green
    Write-Host "  ✓ APK 构建成功!" -ForegroundColor Green
    if (Test-Path $apkPath) {
        $apks = Get-ChildItem $apkPath -Filter "*.apk"
        foreach ($apk in $apks) {
            Write-Host "  APK: $($apk.FullName)" -ForegroundColor Green
        }
    }

    # ---- 打包 KSU 保活模块 ----
    $ksuSource = Join-Path $ScriptDir "ksu_module"
    $ksuZip = Join-Path $ScriptDir "FindMyDevice-KSU-v1.0.zip"
    if (Test-Path $ksuSource) {
        Write-Host ""
        Write-Host "--- 打包 KSU 保活模块 ---" -ForegroundColor Cyan
        if (Test-Path $ksuZip) { Remove-Item $ksuZip -Force }
        $tmp = Join-Path $env:TEMP "fmd_ksu_zip"
        if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
        New-Item -ItemType Directory -Path $tmp -Force | Out-Null
        Copy-Item "$ksuSource\module.prop" "$tmp\"
        Copy-Item "$ksuSource\service.sh" "$tmp\"
        Compress-Archive -Path "$tmp\*" -DestinationPath $ksuZip -Force
        Remove-Item $tmp -Recurse -Force
        Write-Host "  KSU: $ksuZip" -ForegroundColor Green
    }

    Write-Host "========================================" -ForegroundColor Green
} else {
    Write-Host ""
    Write-Host "========================================" -ForegroundColor Red
    Write-Host "  ✗ 构建失败" -ForegroundColor Red
    Write-Host "  检查上面的错误信息" -ForegroundColor Red
    Write-Host "========================================" -ForegroundColor Red
}