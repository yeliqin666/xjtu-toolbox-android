# Wear 模块的 ProGuard/R8 规则。
#
# 补这个文件的原因：wear/build.gradle.kts 的 release 构建引用了它，但它从未被提交，
# 导致 assembleRelease 直接失败（:wear:minifyReleaseWithR8 报
# "Supplied proguard configuration does not exist"）。
# 该模块此前只验证过 compileDebug，release 路径没走通过。
#
# 目前 :wear 只有一个骨架 Activity，没有反射、序列化或 JNI，
# 因此除保留崩溃栈可读性外不需要额外 keep 规则。等真正接入数据层时再往下加。

# ── 调试信息（与 app 模块保持一致，保证线上崩溃栈能还原行号）──
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
