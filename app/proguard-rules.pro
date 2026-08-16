# Xposed/LSPosed 模块不需要混淆
-keep class com.fyne.findmydevice.** { *; }
-keep class de.robv.android.xposed.** { *; }

# 保留所有 native 方法
-keepclasseswithmembernames class * {
    native <methods>;
}

# 保留 JSON 相关类
-keep class org.json.** { *; }