# 插件自己的 R8 规则：入口类必须保留原类名，宿主按 plugin.json 的 entryClass 反射加载。
-keep class com.xjtu.toolbox.plugin.sample.SamplePlugin { *; }
-keep class com.xjtu.toolbox.plugin.api.** { *; }

# Compose 的常规保留由 androidx consumer 规则负责；这里只补样例入口。
