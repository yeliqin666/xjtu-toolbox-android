package com.xjtu.toolbox.account

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.xjtu.toolbox.hello.HelloProfileStore

/** `:core` 那个头像照片槽位（`AccountPhotoSource`）的 Android 实现。 */
internal object AppAccountPhotoSource : AccountPhotoSource {

    @Composable
    override fun photo(accountId: String): ImageBitmap? {
        val context = LocalContext.current
        return remember(accountId) { decodeAvatar(context, accountId) }
    }

    private fun decodeAvatar(context: Context, accountId: String): ImageBitmap? =
        runCatching { HelloProfileStore.cachedAvatarFor(context, accountId) }
            .getOrNull()
            ?.asImageBitmap()
}
