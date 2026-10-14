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

/**
 * 桌面端的公告宿主面 —— 首页顶部那条堆叠条 + 冷启动那一条弹框（两处画法都在 `:core`）。
 *
 * ## 与 `:app` 的关系
 *
 * `:app` 那一份是 `main/LaunchNotices`，它同时管三件事：公告、自动更新检查、本地更新日志。
 * 桌面这一端**只有公告那一半** —— 更新域在桌面是自己的机制（`DesktopUpdate`，`.deb`/tar.gz
 * 没有「下载并安装 APK」那条路），本地更新日志属 `:app` 的 `AppChangelog`。
 *
 * 公告这条链上，除了这个类剩下的都已经是共享的：
 *
 * | 那一层 | 在哪 |
 * |---|---|
 * | 模型、解析、时间窗/版本/隐藏判据 | `:core` 的 `Bulletin` / `BulletinRules` |
 * | 本地状态（缓存 JSON、关过的、确认过的、进程内推迟的） | `:core` 的 [BulletinStore]（键值缝，store 名与键名与 Android 同一个） |
 * | 「此刻该显示哪些」「关掉一条意味着什么」 | `:core` 的 [BulletinFeed]（本轮从 `:app` 搬进去的那一半） |
 * | 取数 | `:data` 的 [BulletinApi]（公开 JSON，不需要会话） |
 * | 两处画法 | `:core` 的 `BulletinNoticePanel` / `BulletinLaunchDialog` |
 *
 * ## 三处如实的差异
 *
 * 1. **版本号**：UA 里带桌面包自己的版本（[DesktopBuildConfig.VERSION_NAME]），与
 *    `DesktopFeedback` 填 `versionLabel` 用的是同一个常量。桌面包**没有** `VERSION_CODE`
 *    （内部版本号是 Android 构建线的概念）⇒ [BulletinFeed.visible] 的 `currentVersionCode`
 *    传 `null`：那一档判据（`targetVersionCode`）在桌面上不参与筛选。
 * 2. **点公告做什么**：投票 → 开弹框；带链接的 → 交给系统浏览器（这一端没有内置浏览器）。
 *    `UPDATE` / `FORCE_UPDATE` 那两条在 `:app` 会先走一次更新检查再弹更新框，桌面**不做**
 *    —— 这一端的更新是 `DesktopUpdate` 那套（还没接进外壳，见那里的 KDoc），所以这里退成
 *    「打开公告里那个链接」。不假装弹了一个能装的更新框。
 * 3. **收纳里的「工具箱公告」**：`:app` 会顺手把这些公告写进 `InboxStore` 的 BULLETIN 分类。
 *    桌面的消息收纳是 `:data` 的 `AppInboxSource`（一网通办那四路），没有公告那一类
 *    —— 把公告写进那一类的是各端自己的 `LaunchNotices`（`:app` 那份干的就是这个），桌面这一端
 *    还没有那一步。所以桌面的收纳页看不到公告，这是如实的缺口。⚠️ `OwnInbox`（那个 `bulletin`
 *    映射的工厂）2026-10-14 已搬进 `:core`（§16.5 的第 2 条）—— 它**不在这条缺口的路上**：
 *    桌面要补的是「冷启动把公告写进 `InboxStore`」那一步，不是那个工厂在哪。
 *
 * ## 取数为什么会失败（以及那不算坏）
 *
 * [BulletinApi] 打的是 Gitee / GitHub 的 raw JSON：拿不到就退回本地缓存（[BulletinStore.peekCached]），
 * 缓存也没有就是一个字都不显示 —— 与 `:app` 同一条口径。离屏证据那一枪把 [fetch] 换成夹具
 * （见 `RenderScreens`），因为证据运行不该去拉真的公告、更不该把真的公告画进图里。
 *
 * @param scope 提交投票那条协程（与 `:app` 的 `LaunchNotices` 同为「发出去就不管」）。
 * @param versionName UA 里的版本号；证据运行会传一个夹具版本。
 * @param fetch 取数那一枪；默认是真站点，证据运行换成假上游。
 */
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

    /**
     * 冷启动那一枪：先拿本地缓存铺一遍（没网也得显示上一条真看过的），再去拉一次；
     * 拉到了就把新 JSON 落盘并换成它，拉不到就用缓存。最后挑一条要弹框的。
     *
     * 顺序与 `:app` 的 `LaunchNotices.check()` 逐条对应（那一边中间还夹着更新检查那一半）。
     */
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
        // 其余：公告里带了链接就交给系统浏览器（这一端没有内置浏览器，与 WebVPN 那一屏同一条口径）。
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

    /**
     * 投票答完：本地先标记已答（免得反复重弹），提交走 `:data` 的反馈后端
     * （与 `:app` 的 `LaunchNotices.submitPoll` 同一张表、同一个匿名标识）。失败静默丢弃。
     */
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
