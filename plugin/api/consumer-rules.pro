# 插件 API 是宿主与插件之间唯一的跨 classloader 契约。
# 宿主 R8 改名后会找不到这些类；插件自己 R8 时也要保留，否则插件 APK 里
# 的接口副本会被改名，和宿主留下的原类名对不上。
-keep class com.xjtu.toolbox.plugin.api.** { *; }
