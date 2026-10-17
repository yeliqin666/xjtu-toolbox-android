package com.xjtu.toolbox.desktop

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xjtu.toolbox.bulletin.Bulletin
import com.xjtu.toolbox.bulletin.BulletinApi
import com.xjtu.toolbox.bulletin.BulletinFeed
import com.xjtu.toolbox.bulletin.BulletinLevel
import com.xjtu.toolbox.bulletin.BulletinRules
import com.xjtu.toolbox.bulletin.BulletinStore
import com.xjtu.toolbox.feedback.AppFeedbackSource
import com.xjtu.toolbox.feedback.FeedbackStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 桌面端的公告宿主面 —— 首页顶部那条堆叠条 + 冷启动那一条弹框（两处画法都在 `:core`）。 */
@Stable
internal class DesktopBulletins(
    private val scope: CoroutineScope,
    private val versionName: String = DesktopBuildConfig.VERSION_NAME,
    private val fetch: suspend (String) -> BulletinApi.FetchResult? = { BulletinApi.fetch(it) },
) {

    private val store = BulletinStore()

    /** 首页顶部那条堆叠条的内容（空 = 不画那条）。 */
    var heroBulletins by mutableStateOf<List<Bulletin>>(emptyList())
        private set

    /** 冷启动要弹框的那一条公告；null = 不弹。 */
    var launchBulletin by mutableStateOf<Bulletin?>(null)
        private set

    /** 弹框的开关（`BulletinLaunchDialog` 收 `MutableState<Boolean>`）。 */
    val showLaunchDialog = mutableStateOf(false)

    /** 冷启动那一枪：先拿本地缓存铺一遍（没网也得显示上一条真看过的），再去拉一次； 拉到了就把新 JSON 落盘并换成它，拉不到就用缓存。 */
    suspend fun check() {
        applyHero(store.peekCached())
        val fetched = runCatching { fetch(versionName) }.getOrNull()
        if (fetched != null) store.cachedJson = fetched.rawJson
        applyHero(fetched?.items ?: store.peekCached())
        BulletinRules.pickLaunchDialog(heroBulletins)?.let { picked ->
            launchBulletin = picked
            showLaunchDialog.value = true
        }
    }

    /** 点公告条上的某一条。 */
    fun onTap(bulletin: Bulletin) {
        // 投票：点开就是那张问卷（`:app` 同一条），关掉它等于「已答」由 submitPoll 记账。
        if (bulletin.isPoll) {
            launchBulletin = bulletin
            showLaunchDialog.value = true
            return
        }
        bulletin.url?.takeIf { it.isNotBlank() }?.let { openInBrowser(it) }
    }

    /** 关掉公告条上的一条（✕）—— 分档记账的判据在 `:core` 的 [BulletinFeed.dismiss]。 */
    fun dismissHero(bulletin: Bulletin) {
        BulletinFeed.dismiss(store, bulletin)
        refreshHero()
    }

    /** 关掉冷启动那个弹框（点「稍后」/点外面）。 */
    fun dismissDialog(bulletin: Bulletin) {
        BulletinFeed.dismissDialog(store, bulletin)
        // 只有「必须升级」那一条会因此换掉公告条的账（与 `:app` 的 `dismissBulletinDialog` 同形）
        if (bulletin.level == BulletinLevel.FORCE_UPDATE) refreshHero()
        closeDialog()
    }

    /** 弹框里点了「确认」（不是投票的那些）。 */
    fun confirmDialog(bulletin: Bulletin) {
        closeDialog()
        if (bulletin.level == BulletinLevel.FORCE_UPDATE) {
            bulletin.url?.takeIf { it.isNotBlank() }?.let { openInBrowser(it) }
        }
    }

    /** 投票答完：本地先标记已答（免得反复重弹），提交走 `:data` 的反馈后端（与 `:app` 的 `LaunchNotices.submitPoll` 同一张表、同一个匿名标识）。 */
    fun submitPoll(bulletin: Bulletin, selected: List<String>) {
        scope.launch {
            runCatching {
                AppFeedbackSource.submit(
                    ticket = FeedbackStore.newTicket(),
                    category = "投票·${bulletin.id}",
                    content = selected.joinToString("、"),
                    contact = "",
                    anonId = FeedbackStore.anonId(),
                    version = versionName,
                )
            }
        }
        store.ack(bulletin.id)
        refreshHero()
        closeDialog()
    }

    private fun closeDialog() {
        showLaunchDialog.value = false
        launchBulletin = null
    }

    private fun refreshHero() = applyHero(store.peekCached())

    private fun applyHero(items: List<Bulletin>) {
        heroBulletins = BulletinFeed.visible(
            store = store,
            cached = items,
            currentVersion = versionName,
            // 桌面包没有内部版本号（见类 KDoc 第 1 条）
            currentVersionCode = null,
        )
    }
}
