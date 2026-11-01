package com.xjtu.toolbox.agent

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.platform.LocalContext
import com.xjtu.toolbox.nav.DeepLinkRouter
import com.xjtu.toolbox.account.AccountContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.File

@Composable
internal fun rememberAgentDropTarget(): Modifier {
    val activity = LocalActivity.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val target = remember(activity, context, scope) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val drop = event.toAndroidDragEvent()
                val clip = drop.clipData ?: return false
                val items = (0 until clip.itemCount).map { clip.getItemAt(it) }
                val data = DeepLinkRouter.sharedTarget(items.mapNotNull { it.text?.toString() },
                    items.mapNotNull { it.uri?.toString() }) ?: return false
                val permission = activity?.requestDragAndDropPermissions(drop)
                val account = AccountContext.activeAccountId
                scope.launch {
                    var imported: ImportedAgentShare? = null
                    var saved = false
                    try {
                        val material = importAgentShare(context, data.prompt.orEmpty(), data.imageUris, null)
                        imported = material
                        ensureActive()
                        if (account != AccountContext.activeAccountId) return@launch
                        if (material.text.isNotBlank() || material.images.isNotEmpty()) {
                            receiveAgentShare(material.text, material.images)
                            saved = true
                        }
                        if (material.unsupported) com.xjtu.toolbox.platform.showBriefMessage("部分内容暂不支持，请分享文字、链接或图片")
                    } finally {
                        permission?.release()
                        if (!saved) imported?.images?.forEach { File(it).delete() }
                    }
                }
                return true
            }
        }
    }
    return Modifier.dragAndDropTarget(
        shouldStartDragAndDrop = { event -> event.toAndroidDragEvent().clipDescription?.let {
            it.hasMimeType("text/*") || it.hasMimeType("image/*")
        } == true },
        target = target,
    )
}
