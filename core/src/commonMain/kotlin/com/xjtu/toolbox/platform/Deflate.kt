package com.xjtu.toolbox.platform

/**
 * 平台能力：**zlib 格式的压缩 / 解压**（分享码本体的那一步）。
 *
 * > ⚠️ 这里原先写的是「原始 deflate，无 zlib 头」—— **那句是错的**，2026-10-15 更正（D12 ②）：
 * > `new java.util.zip.Deflater(Deflater.BEST_COMPRESSION)` 产出的是**带 zlib 头的流**
 * > （`0x78 0xDA` + deflate + 4 字节 Adler-32）；`Inflater` 也因此会做头部校验
 * >（喂它一串随便的字节得到的是 `DataFormatException: incorrect header check`）。
 * > 这正是**两端互认**的前提：谁都得按 zlib 流来编、来解。逐字节的实测记在
 * > `ZlibCodec` 的文件头与 `ShareCodeInteropJvmTest` 里。
 *
 * ## 为什么要有这条缝
 *
 * 匹配交友的分享码是「一段明文 → 压缩 → Base64」拼出来的（见 `:core` 的 `MatchProfile`）。
 * 那一步原先在 `:app` 里直接用 `java.util.zip.Deflater` / `Inflater` —— 它们是 JVM 的类，
 * 在 `:core` 的 commonMain 里不存在（`:core` 要编到 wasmJs）。所以把这一件事切成平台能力，
 * 算法本体留在共享层：
 *
 * | | 这一端 | 怎么实现 |
 * |---|---|---|
 * | Android（`:app`） | `java.util.zip.Deflater` / `Inflater` | **与搬迁前逐字相同**的参数（`BEST_COMPRESSION`、4KB 分块循环） |
 * | jvm（`:desktop`） | 同一份 `java.util.zip` | 同上（桌面要算分享码） |
 * | wasmJs（`:web`） | [ZlibCodec]（纯 Kotlin，`commonMain`） | 2026-10-15（D12 ②）接上：**三端都能出码了**，`deflateAvailable` 在三端都是 true |
 *
 * ## 三端「互相解得开」是怎么钉住的（而不是「压缩字节相同」）
 *
 * Android/jvm 走 zlib（动态 Huffman + 惰性匹配），wasmJs 走 [ZlibCodec]（固定 Huffman + 贪心匹配）
 * ⇒ 同一段明文**压出来的字节不同**（压缩率略低）。这不是互认失败：分享码的语义是
 * 「**对方手里那条码解得开**」，两个方向都钉在 `:core:jvmTest` 的 `ShareCodeInteropJvmTest` 里
 * （jvm 编出来的码 → 纯 Kotlin 解得回原档案；纯 Kotlin 编出来的码 → `java.util.zip` 解得回原档案）。
 * 为什么**不**把同步链改成浏览器的 `CompressionStream`（异步）：那要一路把 `MatchProfile.encode`
 * 与屏上的调用点改成 `suspend`，而这条链在共享层是同步的（`MatchProfile.encode` 立刻要那个字符串
 * 去画二维码）。纯 Kotlin 那一份没有这个代价。
 */

/**
 * 压一段字节（**zlib 流**，与 `java.util.zip.Deflater(BEST_COMPRESSION)` 同一种封装）。
 *
 * @return null = 这一端压不了（今天三端都能，不会走到），或压缩过程中出错。
 */
expect fun deflateBytes(raw: ByteArray): ByteArray?

/**
 * 解压一段 zlib 流。
 *
 * @param maxBytes 输出上限：分享码来自别人，别让一段几百字节的输入膨胀成几十兆。
 *   达到上限就停下并返回已经解出来的那一部分（与搬迁前 `MatchProfile.inflate` 的循环同一条判据）。
 * @return null = 这一端解不了（今天三端都能），或输入根本不是 zlib 流 / 数据损坏。
 */
expect fun inflateBytes(bytes: ByteArray, maxBytes: Int): ByteArray?

/** 这一端有没有上面那两件。`false` 的端上 [deflateBytes] / [inflateBytes] 永远给 null。 */
expect val deflateAvailable: Boolean
