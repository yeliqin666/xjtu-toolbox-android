package com.xjtu.toolbox.web

import com.xjtu.toolbox.game.GameIds
import com.xjtu.toolbox.game.GameStore
import com.xjtu.toolbox.web.testsources.WebSourceSnapshot
import kotlinx.browser.localStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 「合成西交大」在浏览器上真能跑的那两块本端自己的逻辑（屏见 `WebGameMerge.kt`）。 */
class WebGameMergeTest {

    // ── 1. 布局坐标 → CSS px ──

    @Test
    fun `density = 设备像素比时布局坐标就是 CSS px`() {
        // 形状甲：容器在 Compose 坐标里的高度 = CSS 视口高度（density 已经把设备像素除掉了）
        val rect = mergeFrameCssRect(
            topPx = 64f, leftPx = 0f, widthPx = 390f, heightPx = 664f,
            containerHeightPx = 720, viewportHeightCssPx = 720.0,
        )
        assertEquals(MergeFrameRect(top = 64.0, left = 0.0, width = 390.0, height = 664.0), rect)
    }

    @Test
    fun `density = 1 时布局坐标是设备像素，要除回设备像素比`() {
        // 形状乙：同一个视口（720 CSS px、dpr = 2）在 Compose 坐标里是 1440 ⇒ 倍数 1/2
        val rect = mergeFrameCssRect(
            topPx = 128f, leftPx = 0f, widthPx = 780f, heightPx = 1328f,
            containerHeightPx = 1440, viewportHeightCssPx = 720.0,
        )
        assertEquals(MergeFrameRect(top = 64.0, left = 0.0, width = 390.0, height = 664.0), rect)
    }

    @Test
    fun `第一帧还没量出画布时退化成按 CSS px 算（倍数取 1）`() {
        val rect = mergeFrameCssRect(
            topPx = 64f, leftPx = 0f, widthPx = 390f, heightPx = 664f,
            containerHeightPx = 0, viewportHeightCssPx = 720.0,
        )
        assertEquals(64.0, rect.top)
        assertEquals(390.0, rect.width)
    }

    // ── 2. 与游戏页那份记录的对齐 ──

    /** App 侧那份记录的落盘键。 */
    private val appBestKey = "xjtu-toolbox.games.best_${GameIds.MERGE}"

    private fun withCleanScoreKeys(body: () -> Unit) {
        val savedApp = localStorage.getItem(appBestKey)
        val savedPage = localStorage.getItem(MERGE_GAME_HIGHSCORE_KEY)
        localStorage.removeItem(appBestKey)
        localStorage.removeItem(MERGE_GAME_HIGHSCORE_KEY)
        try {
            body()
        } finally {
            listOfNotNull(
                appBestKey to savedApp,
                MERGE_GAME_HIGHSCORE_KEY to savedPage,
            ).forEach { (key, value) ->
                if (value == null) localStorage.removeItem(key) else localStorage.setItem(key, value)
            }
        }
    }

    @Test
    fun `进屏把 App 侧那份最高分镜像给游戏页，离屏把游戏页的成绩收回 App 侧`() = withCleanScoreKeys {
        // 一开始两边都是空的
        assertEquals(0, GameStore.bestScore(GameIds.MERGE))
        assertEquals(0, MergeGameScoreBridge.pageBest())

        // App 侧有记录（合集页看到的那一行）⇒ 进屏镜像进游戏页那个键
        GameStore.submitScore(GameIds.MERGE, 500)
        MergeGameScoreBridge.mirrorAppBestToPage()
        assertEquals(500, MergeGameScoreBridge.pageBest(), "游戏页那个键没跟上 App 侧那份记录")

        // 用户在游戏页里打出了更高分（游戏页自己写那个键）⇒ 离屏收回 App 侧
        localStorage.setItem(MERGE_GAME_HIGHSCORE_KEY, "900")
        MergeGameScoreBridge.collectPageBestIntoApp()
        assertEquals(900, GameStore.bestScore(GameIds.MERGE), "游戏页打出的成绩没收回 App 侧")
    }

    @Test
    fun `两边取大：镜像不覆盖游戏页里更大的那份，收回也不把记录改小`() = withCleanScoreKeys {
        // 游戏页那份更大（上次在浏览器里单独打开过游戏页）⇒ 镜像不覆盖它
        localStorage.setItem(MERGE_GAME_HIGHSCORE_KEY, "900")
        GameStore.submitScore(GameIds.MERGE, 500)
        MergeGameScoreBridge.mirrorAppBestToPage()
        assertEquals(900, MergeGameScoreBridge.pageBest())

        // 反过来：App 侧那份更大（用户在 App / 别的浏览器里打过分）⇒ 收回也不把它改小
        GameStore.submitScore(GameIds.MERGE, 1200)
        localStorage.setItem(MERGE_GAME_HIGHSCORE_KEY, "100")
        MergeGameScoreBridge.collectPageBestIntoApp()
        assertEquals(1200, GameStore.bestScore(GameIds.MERGE))
    }

    // ── 3. 那个键必须与游戏页的 STORAGE_KEY 相同 ──

    @Test
    fun `游戏页那个最高分键与 index.js 里的 STORAGE_KEY 逐字一致`() {
        val source = WebSourceSnapshot.INDEX
        val found = Regex("""const STORAGE_KEY = '([^']+)'""").find(source)
        assertNotNull(
            found,
            "游戏页的 index.js 里找不到 `const STORAGE_KEY = '…'` —— 这段解析要跟着改" +
                "（那份 JS 在 web/build.gradle.kts 的 webTestSourceSnapshotFiles 里）",
        )
        val inGame = found.groupValues[1]
        assertEquals(
            inGame,
            MERGE_GAME_HIGHSCORE_KEY,
            "本端的 MERGE_GAME_HIGHSCORE_KEY 与游戏页的 STORAGE_KEY 不一致：这一屏镜像成绩、" +
                "收回成绩都靠这一个键，对不上就等于两边各记一份（合集页那一行永远不涨）",
        )
        assertTrue(
            inGame.isNotBlank() && "/" !in inGame,
            "游戏页那个键的形状不像一个 localStorage 键名：$inGame",
        )
    }
}
