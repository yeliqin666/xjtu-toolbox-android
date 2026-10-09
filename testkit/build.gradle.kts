// :testkit —— **假的校园上游**（图书馆座位系统 + 可选的统一认证），只给测试与离屏证据用。
//
// 为什么它是一个独立模块，而不是塞在某个模块的 test 源集里：
//
//   1. **页面原文只能有一份**。[LibraryFakeUpstream] 自己的 KDoc 就写着这条：`:data:jvmTest`
//      的契约测试与桌面端的离屏渲染证据**必须看到同一批页面**，否则「桌面端真登进去的图书馆」
//      那张 PNG 证明的就不是真站点的形状；
//   2. **跨模块共享不了 test 源集**。Gradle 的 `java-test-fixtures` 插件对 KMP 不生效，
//      而手搓 `jvmTestFixtures` 配置 + 变体属性在 KMP 里又脆又难维护；
//   3. **它绝不能进任何交付物**。放进 `:data` 的 jvmMain 就会跟着 `.deb` / `tar.gz`
//      一起发出去——一个「任何密码都收」的假统一认证不该出现在用户机器上。
//      所以它是一条**独立的、谁都不会依赖到生产构件里**的编译单元。
//
// 它没有依赖，也不需要：两个夹具只用 JDK 的 `com.sun.net.httpserver` 与 `java.security`
// （这正是它们能搬出 `:data` 的原因；搬之前它们就在 `:data:jvmTest` 里，同样零依赖）。
//
// 消费者：
//   - `:data:jvmTest`（`testImplementation(project(":testkit"))`）—— 契约测试；
//   - `:desktop` 的 **test** 源集 —— 离屏渲染证据（`renderScreens` 与 `DesktopAuthLibraryJvmTest`）。
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    // 与其余模块对齐：miuix 各模块都是 JDK 21 工具链编译的，字节码目标不一致会直接编译失败。
    jvmToolchain(21)
}
