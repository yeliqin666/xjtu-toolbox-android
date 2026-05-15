import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import kotlinx.browser.window
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.w3c.dom.url.URLSearchParams

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val params = URLSearchParams(document.location?.search)
    val screen = params.get("screen") ?: "schedule"
    val darkMode = params.get("dark")?.toBooleanStrictOrNull() ?: false

    val fontUrl = "https://cdn.jsdelivr.net/npm/@fontsource/noto-sans-sc@5/files/noto-sans-sc-chinese-simplified-400-normal.woff2"
    window.fetch(fontUrl)
        .then { response -> response.arrayBuffer() }
        .then { buf ->
            val bytes = Int8Array(buf.unsafeCast<ArrayBuffer>()).unsafeCast<ByteArray>()
            launchCompose(screen, darkMode, FontFamily(Font("NotoSansSC", bytes)))
            null
        }
        .catch {
            launchCompose(screen, darkMode, null)
            null
        }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun launchCompose(screen: String, darkMode: Boolean, cjkFont: FontFamily?) {
    ComposeViewport(viewportContainerId = "ComposeTarget") {
        DemoApp(screen = screen, darkMode = darkMode, cjkFont = cjkFont)
    }
}
