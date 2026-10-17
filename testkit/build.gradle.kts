// :testkit —— 假的校园上游，只给测试与离屏证据用；⚠️ 绝不能进任何交付物（假统一认证收任何密码）。
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    // JVM 21（与 miuix 对齐）。
    jvmToolchain(21)
}
