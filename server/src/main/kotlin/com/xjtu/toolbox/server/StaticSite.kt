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

/** `--dist` 目录的只读静态托管 —— serve 模式的前半（`:web` 的 wasm 产物）。 */
internal fun Route.staticSite(root: File) {
    // 根路径单独登记：各版本尾卡选择器对空段的处理不一致。
    get("/") { call.respondStaticFile(root, "/") }
    get("{path...}") { call.respondStaticFile(root, call.request.path()) }
}

/** 托管根目录下的索引文件名（`/` 与目录请求都落到它）。 */
private const val INDEX_FILE = "index.html"

private suspend fun ApplicationCall.respondStaticFile(root: File, requestPath: String) {
    if (requestPath == API_PREFIX || requestPath.startsWith("$API_PREFIX/")) {
        return respondText(ApiErrors.NOT_FOUND_MESSAGE, status = HttpStatusCode.NotFound)
    }
    val file = resolveWithin(root, requestPath)
        ?: return respondText("找不到这个文件：$requestPath", status = HttpStatusCode.NotFound)
    respond(LocalFileContent(file, contentTypeOf(file)))
}

/** 把请求路径解析成托管根目录下的一个已存在文件；解析不出、越界、或不是文件都返回 null。 */
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

/** 按扩展名给 `Content-Type`。 */
private fun contentTypeOf(file: File): ContentType = when (file.extension.lowercase()) {
    "html", "htm" -> ContentType.Text.Html.withCharset(Charsets.UTF_8)
    "js", "mjs" -> ContentType.Text.JavaScript.withCharset(Charsets.UTF_8)
    "css" -> ContentType.Text.CSS.withCharset(Charsets.UTF_8)
    "json", "map" -> ContentType.Application.Json.withCharset(Charsets.UTF_8)
    // Kotlin/Wasm 的产物。发成 octet-stream 的话 instantiateStreaming 会直接拒收 ——
    // 这一行是「托管 :web」能不能成立的关键。
    "wasm" -> ContentType("application", "wasm")
    "png" -> ContentType.Image.PNG
    "webp" -> ContentType("image", "webp")
    "webmanifest" -> ContentType("application", "manifest+json")
    "jpg", "jpeg" -> ContentType.Image.JPEG
    "svg" -> ContentType.Image.SVG
    "ico" -> ContentType.Image.XIcon
    "woff" -> ContentType.Font.Woff
    "woff2" -> ContentType.Font.Woff2
    "ttf" -> ContentType.Font.Ttf
    "txt", "md" -> ContentType.Text.Plain.withCharset(Charsets.UTF_8)
    else -> ContentType.Application.OctetStream
}
