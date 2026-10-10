package com.xjtu.toolbox.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.io.File

/**
 * `--dist` 目录的**只读**静态托管 —— serve 模式的前半（`:web` 的 wasm 产物）。
 *
 * 语义与 `web/tools/serve-same-origin.py`（serve 模式的雏形，那个脚本的静态那半）逐条对齐：
 * `/` → `index.html`；找不到的文件 404；`/api/…` **优先**于静态。
 *
 * ## 为什么不用 Ktor 的 `staticFiles`
 *
 * 它按 Ktor 自己的 mime 表发 `Content-Type`，而 Kotlin/Wasm 的产物是 `.wasm`：
 * 浏览器只认 `application/wasm`（`WebAssembly.instantiateStreaming` 对别的一律拒收），
 * Ktor 的表里有没有这一项不是我们说了算的。这里自己写一张**小而不含糊**的对照表，
 * 并由 `:server:test` 钉住 `.wasm` / `.js` / `.html` 三个（托管 wasm 就是这一步的**全部目的**，
 * 发错 mime 等于什么都没做）。
 *
 * ## 只读 + 不越界
 *
 * 只做 `GET`，只读文件，绝不落盘（serve 模式要写东西的地方是 `:data` 的会话存储，不是产物目录）。
 * 路径一律 `canonicalFile` 之后要求落在 `--dist` 里面 —— 目录穿越（`..`、`%2e%2e`、
 * 符号链接）在这里被挡死，`:server:test` 里有一条钉着它。
 */
internal fun Route.staticSite(root: File) {
    // 根路径单独登记一条常数路由：不依赖尾卡选择器对「一个段都没有」的处理（各家版本的口径不一样，
    // 而 `/` 是 serve 模式最常被打开的那一个路径 —— 它必须是确定的）。
    get("/") { call.respondStaticFile(root, "/") }
    get("{path...}") { call.respondStaticFile(root, call.request.path()) }
}

/** 托管根目录下的索引文件名（`/` 与目录请求都落到它）。 */
private const val INDEX_FILE = "index.html"

private suspend fun ApplicationCall.respondStaticFile(root: File, requestPath: String) {
    // `/api/…` 绝不走静态。闸门挂在 `/api` 那条路由上，正常情况下 `/api/…` 根本不会落到这里；
    // 这一句是**纵深防御**：只要静态目录将来（或因为某次路由改动）成了 `/api/…` 的落点，
    // 它就会变成一条绕过令牌的旁路 —— 而产物目录里将来完全可能有人放个 `api/` 进去。
    if (requestPath == API_PREFIX || requestPath.startsWith("$API_PREFIX/")) {
        return respondText(ApiErrors.NOT_FOUND_MESSAGE, status = HttpStatusCode.NotFound)
    }
    val file = resolveWithin(root, requestPath)
        ?: return respondText("找不到这个文件：$requestPath", status = HttpStatusCode.NotFound)
    respond(LocalFileContent(file, contentTypeOf(file)))
}

/**
 * 把请求路径解析成托管根目录下的一个**已存在**文件；解析不出、越界、或不是文件都返回 null。
 *
 * @param requestPath 形如 `/composeResources/foo.png`（Ktor 已按 URL 规则解码过）
 */
private fun resolveWithin(root: File, requestPath: String): File? {
    if (!root.isDirectory) return null
    // NUL 字节进不了文件系统（JDK 会抛 InvalidPathException，而它是 Unchecked）：直接当找不到
    if ('\u0000' in requestPath) return null
    val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return null
    val relative = requestPath.trim('/')
    val target = runCatching {
        (if (relative.isEmpty()) canonicalRoot else File(canonicalRoot, relative)).canonicalFile
    }.getOrNull() ?: return null
    // 唯一一条越界判据：canonical 之后必须真的在根目录里（`..` 与符号链接都在这一步被解开）
    if (!target.toPath().startsWith(canonicalRoot.toPath())) return null
    if (target.isDirectory) return File(target, INDEX_FILE).takeIf { it.isFile }
    return target.takeIf { it.isFile }
}

/**
 * 按扩展名给 `Content-Type`。
 *
 * 只列 `:web` 的产物**真的会用到**的那几种（外加一两张图），没列到的一律
 * `application/octet-stream`（保守：不猜、不把未知文件当 HTML 发出去）。
 */
private fun contentTypeOf(file: File): ContentType = when (file.extension.lowercase()) {
    "html", "htm" -> ContentType.Text.Html.withCharset(Charsets.UTF_8)
    "js", "mjs" -> ContentType.Text.JavaScript.withCharset(Charsets.UTF_8)
    "css" -> ContentType.Text.CSS.withCharset(Charsets.UTF_8)
    "json", "map" -> ContentType.Application.Json.withCharset(Charsets.UTF_8)
    // Kotlin/Wasm 的产物。发成 octet-stream 的话 instantiateStreaming 会直接拒收 ——
    // 这一行是「托管 :web」能不能成立的关键。
    "wasm" -> ContentType("application", "wasm")
    "png" -> ContentType.Image.PNG
    "jpg", "jpeg" -> ContentType.Image.JPEG
    "svg" -> ContentType.Image.SVG
    "ico" -> ContentType.Image.XIcon
    "woff" -> ContentType.Font.Woff
    "woff2" -> ContentType.Font.Woff2
    "ttf" -> ContentType.Font.Ttf
    "txt", "md" -> ContentType.Text.Plain.withCharset(Charsets.UTF_8)
    else -> ContentType.Application.OctetStream
}
