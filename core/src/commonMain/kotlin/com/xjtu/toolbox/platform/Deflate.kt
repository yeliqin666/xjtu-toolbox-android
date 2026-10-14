package com.xjtu.toolbox.platform

/**
 * 平台能力：**原始 deflate 压缩 / 解压**（分享码本体的那一步）。
 *
 * ## 为什么要有这条缝
 *
 * 匹配交友的分享码是「一段明文 → deflate → Base64」拼出来的（见 `:core` 的 `MatchProfile`）。
 * 那一步原先在 `:app` 里直接用 `java.util.zip.Deflater` / `Inflater` —— 它们是 JVM 的类，
 * 在 `:core` 的 commonMain 里不存在（`:core` 要编到 wasmJs）。所以把「压缩」这一件事切成
 * 平台能力，算法本体留在共享层：
 *
 * | | 这一端 | 怎么实现 |
 * |---|---|---|
 * | Android（`:app`） | `java.util.zip.Deflater` / `Inflater` | **与搬迁前逐字相同**的参数（`BEST_COMPRESSION`、4KB 分块循环） |
 * | jvm（`:desktop`） | 同一份 `java.util.zip` | 同上（桌面要算分享码） |
 * | wasmJs（`:web`） | **没有**：两件都返回 null | 见下面那条 TODO |
 *
 * ## 为什么不做成异步
 *
 * 浏览器侧其实有 `CompressionStream('deflate')`，但它是**流式异步**的（要么 `awaiter`，要么
 * 攒完整段再读），而分享码这条路在本仓库里是「编码→立刻拿到字符串→画二维码」的同步链
 * （`MatchProfile.encode`）。把一个同步算法换成 `suspend` 会一路改到屏与 ViewModel，
 * 而 `:web` 的匹配交友屏（2026-10-14 起也接上了）**不靠这条路**：它落一张如实说明页 ——
 * 所以还是不值得先付这笔账。
 *
 * **TODO（Web 端的匹配交友）**：`AppRoute.Match` 在 `:web` 上今天落的就是那张**如实说明页**
 *（「这一屏要在能出码的端上用」）。真要把它做成能出码的屏，这个
 * actual 有两条路：① 用 `CompressionStream` + `DecompressionStream`，把 `MatchProfile.encode`
 * / `decode` 收成 `suspend`（连带屏上那两处调用点）；② 换成一份纯 Kotlin 的 deflate
 * 实现（进 commonMain，三端同一份）。在那之前，`deflateAvailable` 是 false，
 * 屏上**如实说这一端生成不了码**，而不是画一张空的 / 假的码。
 */

/**
 * deflate 一段字节（原始 deflate，无 zlib 头 / 无 gzip 头 —— 与 `java.util.zip.Deflater` 的
 * 默认构造器同一种封装）。
 *
 * @return null = 这一端没有 deflate（wasmJs，见文件头的 TODO），或压缩过程中出错。
 */
expect fun deflateBytes(raw: ByteArray): ByteArray?

/**
 * 解压一段原始 deflate 字节。
 *
 * @param maxBytes 输出上限：分享码来自别人，别让一段几百字节的输入膨胀成几十兆。
 *   达到上限就停下并返回已经解出来的那一部分（与搬迁前 `MatchProfile.inflate` 的循环同一条判据）。
 * @return null = 这一端没有 inflate（wasmJs）或输入根本不是 deflate。
 */
expect fun inflateBytes(bytes: ByteArray, maxBytes: Int): ByteArray?

/** 这一端有没有上面那两件。`false` 的端上 [deflateBytes] / [inflateBytes] 永远给 null。 */
expect val deflateAvailable: Boolean
