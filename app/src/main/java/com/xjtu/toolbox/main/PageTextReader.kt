package com.xjtu.toolbox.main

import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull

/**
 * 没登记快照的页面：从界面的无障碍语义树里读出屏幕上看得见的文字（读屏软件读的就是这份），按从上到下、
 * 从左到右排好。只在主线程调。
 *
 * 隐私：页面上有密码框时整页不读（返回 [PASSWORD]）；输入框里填的内容不读，只读它的提示文字。
 */
internal object PageTextReader {
    const val PASSWORD = "\u0000password"
    private const val MAX_CHARS = 3000

    fun read(window: Window?): String? {
        val decor = window?.decorView ?: return null
        val root = findRoot(decor) ?: return null
        val height = decor.height
        val items = mutableListOf<Triple<Float, Float, String>>()
        var password = false
        fun walk(node: SemanticsNode) {
            val c = node.config
            if (c.contains(SemanticsProperties.Password)) password = true
            val b = node.boundsInWindow
            val onScreen = b.height > 0f && b.bottom > 0f && b.top < height
            if (onScreen && !c.contains(SemanticsProperties.EditableText)) {
                val text = c.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }?.trim().orEmpty()
                    .ifEmpty { c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")?.trim().orEmpty() }
                if (text.isNotEmpty()) items += Triple(b.top, b.left, text)
            }
            node.children.forEach(::walk)
        }
        runCatching { walk(root.semanticsOwner.unmergedRootSemanticsNode) }.onFailure { return null }
        if (password) return PASSWORD
        val lines = items
            .sortedWith(compareBy({ (it.first / 24f).toInt() }, { it.second }))
            .map { it.third }
            .fold(mutableListOf<String>()) { acc, s -> if (acc.lastOrNull() != s) acc += s; acc }
        return lines.joinToString("\n").take(MAX_CHARS).takeIf { it.isNotBlank() }
    }

    private fun findRoot(view: View): RootForTest? {
        if (view is RootForTest) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findRoot(view.getChildAt(i))?.let { return it }
        return null
    }
}
