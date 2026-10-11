package com.xjtu.toolbox.account

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.xjtu.toolbox.hello.HelloProfileStore

/**
 * `:core` 那个头像照片槽位（`AccountPhotoSource`）的 Android 实现。
 *
 * 与搬迁前 `AccountManagerScreen` 里那段逐字同源：学工系统（hello）的证件照按账号各自缓存，
 * 没登录过 hello 的账号自然取不到 ⇒ 返回 null，屏那边回落到首字母圆。
 *
 * 为什么它留在 `:app` 而不是跟着屏进 `:core`：`HelloProfileStore` 要 `Context` 与
 * `android.graphics.Bitmap`，而 `:core` 的 commonMain 还要编到 wasmJs。桌面端不传这个槽位
 * （它没有学工系统的证件照缓存）—— 见 `AccountPhotoSource` 的 KDoc。
 */
internal object AppAccountPhotoSource : AccountPhotoSource {

    @Composable
    override fun photo(accountId: String): ImageBitmap? {
        val context = LocalContext.current
        // 与搬迁前同一个 remember 键（按账号），同一个「解不出来就当没有」的判据。
        return remember(accountId) { decodeAvatar(context, accountId) }
    }

    private fun decodeAvatar(context: Context, accountId: String): ImageBitmap? =
        runCatching { HelloProfileStore.cachedAvatarFor(context, accountId) }
            .getOrNull()
            ?.asImageBitmap()
}
