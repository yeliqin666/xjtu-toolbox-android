package com.xjtu.toolbox.game.board

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xjtu.toolbox.game.GameIds
import com.xjtu.toolbox.game.blocks.BlocksMode
import com.xjtu.toolbox.game.blocks.blocksRecordId
import com.xjtu.toolbox.platform.Log
import com.xjtu.toolbox.util.singletonState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 小游戏全服榜。榜单的入口、询问时机、提交都从这里走，各游戏只管在开局/结束时叫一声。
 *
 * 三条规则（改之前先读）：
 * 1. **够得着才出声**。进不了前 [LIMIT] 名就什么都不做——不弹窗、不上传。
 * 2. **身份只选一次**。玩家选了名字、或者勾了「后续上榜不再提示」，以后上榜就静默更新成绩，
 *    不再打断他。同一个身份在服务端是同一行，只用最高分。
 * 3. **失败了就装作没有这回事**。榜单是锦上添花，网络不通不该让玩家在结算页看到一个报错。
 * 4. **没走完的一局也不白打**。中途退出、进程被杀、上报没成功，都由 [LeaderboardRun] 记一笔账，
 *    下次打开同一个游戏再问一句要不要传（只在超过开局前的历史最高分时才问）。
 *
 * 上传的只有「游戏、分数、名字、一个设备级匿名 ID」，不含学号账号——为什么身份是设备级的、
 * 而不是按账户或学号派生，见 [LeaderboardStore.uid] 的说明。这是本项目唯一把
 * 游戏数据发出设备的地方——[com.xjtu.toolbox.game.GameStore] 里其余数字仍然只在本地。
 */
object Leaderboard {

    private const val TAG = "Leaderboard"

    /** 榜长。同时也是「多少分算上榜」的分数线，服务端用同一个值。 */
    const val LIMIT = 100

    /** 名字最长几个字（按字符数，不按字节——中文名不会只有三四个字符的额度）。 */
    const val MAX_NAME = 12

    val enabled: Boolean get() = LeaderboardApi.configured

    /**
     * 榜单自己的作用域：上报不该跟着某个页面的生命周期一起死。
     *
     * 结算页的上报挂在页面的 `LaunchedEffect` 上，玩家退出得比网络快（拉榜 + 上报两次请求，
     * 超时上限十五秒），协程被取消，这一局就白打了。所以「点上传就退出」那条路走这里。
     */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 玩家主动打开的榜单浮层。全 App 一个，由 [LeaderboardHost] 渲染（挂在导航外层）。 */
    var sheet: Sheet? by singletonState { mutableStateOf(null) }
        private set

    /** 盖在榜单之上的改名框。非空时榜单还在下面，改完回来并刷新。 */
    var renaming: Renaming? by singletonState { mutableStateOf(null) }
        private set

    sealed interface Sheet {
        /** 榜单本身。[stamp] 每改一次名 +1，列表据此重新拉一次。 */
        data class Ranking(val game: String, val stamp: Int = 0) : Sheet
    }

    /** 够上榜时该问玩家一次。交给**发起这一局的那个游戏页面**自己持有并渲染，见 [report]。 */
    data class PendingAsk(val game: String, val score: Int)

    /** 正在改名的现场。[score] 用榜单上那一行的分数——改名只换名字，分数不动。 */
    data class Renaming(val game: String, val current: String?, val score: Int)

    fun openRanking(game: String) {
        if (enabled) sheet = Sheet.Ranking(game)
    }

    /** 从榜单里点「改名」。 */
    fun startRename(game: String, current: String?, score: Int) {
        renaming = Renaming(game, current, score)
    }

    fun cancelRename() {
        renaming = null
    }

    /** 改名成功：关掉改名框，并让榜单重新拉一次——否则榜上还写着旧名字。 */
    fun finishRename() {
        renaming = null
        (sheet as? Sheet.Ranking)?.let { sheet = it.copy(stamp = it.stamp + 1) }
    }

    fun dismiss() {
        sheet = null
        renaming = null
    }

    /**
     * 执行改名。走的是和首次上榜同一条路（[answer]），所以三道闸（本地词表、云审核、昵称独占）
     * 一个不少；服务端按 `(game, uid)` 更新同一行，分数保留。
     *
     * 「后续上榜不再提示」的选择保持不变——改名不该顺手把这个偏好也重置了。
     */
    suspend fun rename(game: String, score: Int, name: String?): AnswerResult {
        val silent = LeaderboardStore.identity(game)?.silent ?: true
        return answer(game, score, name, silent)
    }

    /**
     * 一局结束、成绩已落本地后调用。够上榜就返回一条待询问的 [PendingAsk]，否则返回 null。
     *
     * 返回值而不是直接弹窗是有意的：这个函数会等一次网络，而玩家可能在这期间就退出页面了。
     * 弹窗若挂在全局，就会飘到已经返回的别的页面上。所以由调用方把它放进自己的状态里，
     * 页面一销毁，弹窗跟着消失。
     *
     * 已经选过身份且勾了「不再提示」的，这里直接静默提交并返回 null，不再打断玩家。
     */
    suspend fun report(game: String, score: Int): PendingAsk? {
        if (!enabled || score <= 0) return null
        // 这一局正常走到结算了：先前给「异常退出」留的账一笔勾销。下面真没传成功的话再重新记
        LeaderboardRun.finish(game)
        val uid = LeaderboardStore.uid()
        // 先看一眼榜：分数够不着就闭嘴。这也是唯一一次「为了决定要不要说话」而发的请求。
        val snapshot = runCatching { LeaderboardApi.board(game, uid, LIMIT) }
            // 拉不到榜不等于没资格上榜：判不出分数线的时候按够格处理。宁可多问一句，
            // 也不能因为一次网络抖动就把人家一整局的分数丢掉（#602 的另一半）
            .onFailure { Log.w(TAG, "拉榜失败，按够格处理：$game", it) }
            .getOrNull()
        if (snapshot != null && !snapshot.qualifies(score)) return null

        val identity = LeaderboardStore.identity(game)
        return if (identity == null || !identity.silent) {
            PendingAsk(game, score)
        } else {
            val error = runCatching { LeaderboardApi.submit(game, uid, identity.name, score) }
                .onFailure { Log.w(TAG, "静默上报失败：$game", it) }
                .exceptionOrNull()
            when {
                error == null -> null
                // 本机记下的名字被服务端拒了（多半是词表更新过，或名字被取消了独占）：忘掉它，
                // 这次直接问玩家重新起一个，否则这个人以后每次上榜都会静静地失败
                error is LeaderboardError && error.nameProblem -> {
                    LeaderboardStore.forget(game)
                    PendingAsk(game, score)
                }
                // 网络 / 服务端故障：留一笔账，下次进这个游戏再问要不要补传
                else -> {
                    LeaderboardRun.keepUnsent(game, score)
                    null
                }
            }
        }
    }

    /**
     * 「本局要不要上传」里选了上传：沿用已有身份（没选过名字就匿名），失败留档下次补。
     *
     * 和 [answer] 的区别是**不等结果**：玩家点了上传就要退出 / 重开，页面马上销毁，
     * 挂在页面作用域上的协程会被取消。所以这里用榜单自己的作用域发出去，
     * 结果只影响盘上那笔账还在不在，不影响玩家看到的界面。
     */
    fun submitQuietly(game: String, score: Int) {
        if (!enabled || score <= 0) return
        val uid = LeaderboardStore.uid()
        val identity = LeaderboardStore.identity(game)
        background.launch {
            val error = runCatching { LeaderboardApi.submit(game, uid, identity?.name, score) }
                .onFailure { Log.w(TAG, "后台上报失败，留档待补：$game", it) }
                .exceptionOrNull()
            if (error == null) {
                LeaderboardRun.clear(game)
            } else {
                if (error is LeaderboardError && error.nameProblem) LeaderboardStore.forget(game)
                LeaderboardRun.keepUnsent(game, score)
            }
        }
    }

    /**
     * 询问里按了上榜。选择先落本地再上传——上传失败也不该重复问一遍同样的问题。
     *
     * 这里不负责关弹窗：弹窗挂在发起这一局的那个页面上，由它自己收（见 [LeaderboardAskDialog]）。
     */
    suspend fun answer(
        game: String,
        score: Int,
        name: String?,
        silent: Boolean,
    ): AnswerResult {
        val uid = LeaderboardStore.uid()
        LeaderboardStore.remember(game, name, silent)
        val error = runCatching { LeaderboardApi.submit(game, uid, name, score) }
            .onFailure { Log.w(TAG, "上榜失败：$game", it) }
            .exceptionOrNull()
        return when {
            error == null -> AnswerResult.OK
            // 服务端有话说（名字有问题、或者审核没跑成要稍后再试）就原样转给玩家
            error is LeaderboardError && error.showableProblem ->
                AnswerResult.Rejected(error.message ?: "这个名字不合适，换一个")
            // 网络 / 服务端故障：弹窗里已经说清楚了，但玩家可能直接关掉，留笔账下次补
            else -> {
                LeaderboardRun.keepUnsent(game, score)
                AnswerResult.Failed
            }
        }
    }

    /** [answer] 的三种结果，弹窗据此决定是关掉、留在原地报错、还是留在原地让玩家处理。 */
    sealed interface AnswerResult {
        data object OK : AnswerResult

        /** 服务端明确说了理由（名字被拒／被占用／审核没跑成）。[message] 直接展示给玩家。 */
        data class Rejected(val message: String) : AnswerResult

        /** 网络或服务端故障，与名字无关。 */
        data object Failed : AnswerResult
    }

    /**
     * 名字清洗：去掉换行和控制字符、把连续空白并成一个空格、掐到 [MAX_NAME] 个字。
     * 空串、或者干脆就叫「匿名」的，都当没起名字——否则榜上分不出谁是真匿名。
     */
    fun sanitizeName(raw: String): String? {
        val cleaned = raw
            .map { if (it.isISOControl() || it.isWhitespace()) ' ' else it }
            .joinToString("")
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .trim()
            .take(MAX_NAME)
        return cleaned.takeIf { it.isNotEmpty() && it != LeaderboardStore.ANONYMOUS }
    }
}

/** 榜单标题。方块按玩法分榜，名字要带上玩法，不然看不出是哪个榜。 */
fun boardTitle(game: String): String = when (game) {
    GameIds.MERGE -> "合成西交大"
    GameIds.G2048 -> "GPA 2048"
    GameIds.HOP -> "跳一跳"
    blocksRecordId(BlocksMode.MARATHON) -> "方块 · 马拉松"
    blocksRecordId(BlocksMode.ULTRA) -> "方块 · 冲分"
    blocksRecordId(BlocksMode.RISE) -> "方块 · 无尽抬升"
    else -> "小游戏"
}
