package com.xjtu.toolbox.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.xjtu.toolbox.auth.AccountType
import com.xjtu.toolbox.home.HomeCard
import com.xjtu.toolbox.home.HomeCardGroupScreen
import com.xjtu.toolbox.home.HomeHost
import com.xjtu.toolbox.platform.dataRootOverride
import com.xjtu.toolbox.ui.theme.XJTUToolBoxTheme
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class HomeSearchGestureJvmTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `空白处长按只在首页进入编辑`() {
        val previousRoot = dataRootOverride
        dataRootOverride = temp.root
        try {
            assertTrue(editingAfterLongPress(searching = false), "首页自身的空白长按仍应进入编辑")
            assertFalse(editingAfterLongPress(searching = true), "搜索层的空白长按不应进入首页编辑")
        } finally {
            dataRootOverride = previousRoot
        }
    }

    private fun editingAfterLongPress(searching: Boolean): Boolean {
        val host = object : HomeHost {
            override val honoredCards = emptySet<HomeCard>()
            override val audience = AccountType.UNDERGRADUATE
            override val greetingName = ""
            override val dateLabel = ""
            override val weekNumber = 1
        }
        lateinit var scene: ImageComposeScene
        var longPressMillis = 0L
        SwingUtilities.invokeAndWait {
            scene = ImageComposeScene(412, 915, Density(1f)) {
                XJTUToolBoxTheme {
                    longPressMillis = LocalViewConfiguration.current.longPressTimeoutMillis
                    HomeCardGroupScreen(host, topActions = {}, searching = searching, searchContent = {})
                }
            }
            scene.render(System.nanoTime())
        }

        fun SemanticsNode.hasEditTitle(): Boolean =
            config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "编辑主页" } ||
                children.any { it.hasEditTitle() }

        try {
            val blank = Offset(3f, 680f)
            SwingUtilities.invokeAndWait { scene.sendPointerEvent(PointerEventType.Press, blank, type = PointerType.Touch) }
            Thread.sleep(longPressMillis + 100L)
            var editing = false
            SwingUtilities.invokeAndWait {
                scene.sendPointerEvent(PointerEventType.Release, blank, type = PointerType.Touch)
                scene.render(System.nanoTime())
                editing = scene.semanticsOwners.any { it.rootSemanticsNode.hasEditTitle() }
            }
            return editing
        } finally {
            SwingUtilities.invokeAndWait { scene.close() }
        }
    }
}
