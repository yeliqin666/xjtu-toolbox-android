package com.xjtu.toolbox.game.board

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 待上报账本的语义：只记破纪录的一局、正常结算要划账、异常退出要能被下一次打开捞回来。 */
class RunLedgerTest {

    private val store = mutableMapOf<String, String>()
    private var best = 0

    private val ledger = RunLedger(
        bestScore = { best },
        read = { store[it] },
        write = { key, value -> if (value == null) store.remove(key) else store[key] = value },
    )

    @Test
    fun abandonOnlyWhenBeatingTheBaseline() {
        best = 5000
        ledger.resume("merge")
        // 没超过开局前的最高分：不配问，也不留账
        assertNull(ledger.abandon("merge", 4000))
        assertNull(ledger.pending("merge"))
        // 超过了：给出一条待确认，并落盘
        assertEquals(RunOffer("merge", 9000, RunEnd.ABANDONED), ledger.abandon("merge", 9000))
        assertEquals(9000, ledger.pending("merge")?.score)
    }

    @Test
    fun interruptedRunSurvivesUntilAskedAgain() {
        best = 1000
        ledger.resume("merge")
        ledger.progress("merge", 4000)
        // persist 只写盘，不弹窗
        assertEquals(RunEnd.INTERRUPTED, ledger.pending("merge")?.end)
        // 进程被杀：新开一个账本（内存状态全丢），靠盘上那笔捞回来
        val reopened = RunLedger({ best }, { store[it] }, { key, value -> if (value == null) store.remove(key) else store[key] = value })
        assertEquals(RunOffer("merge", 4000, RunEnd.INTERRUPTED), reopened.resume("merge"))
        // 问一次还在，直到玩家答完 / 决定不传
        assertEquals(4000, reopened.pending("merge")?.score)
        reopened.settle("merge", 4000)
        assertNull(reopened.pending("merge"))
    }

    @Test
    fun progressWritesOnlyNewHighs() {
        best = 100
        ledger.resume("merge")
        ledger.progress("merge", 200)
        ledger.progress("merge", 150) // 低于已经记下的分数，不该覆盖
        assertEquals(200, ledger.pending("merge")?.score)
        ledger.progress("merge", 300)
        assertEquals(300, ledger.pending("merge")?.score)
    }

    @Test
    fun naturalEndKeepsTheAccountUntilItIsSettled() {
        best = 0
        ledger.resume("merge")
        ledger.progress("merge", 8000)
        ledger.end("merge", 8000)
        // 走到结算了，成绩却还没有去处（要拉榜、要问人、要等提交）：账得留着，只把原因改掉
        assertEquals(RunOffer("merge", 8000, RunEnd.ENDED), ledger.pending("merge"))
        // 结算页上再按退出就是退出，不该再问一次「要不要上传」
        assertNull(ledger.abandon("merge", 8000))
        // 玩家答完（这次不上传）才划账
        ledger.settle("merge", 8000)
        assertNull(ledger.pending("merge"))
    }

    @Test
    fun failedSubmitIsKeptAsUnsent() {
        best = 0
        ledger.resume("merge")
        // 结算先打标，提交失败再记一笔——顺序和 Leaderboard.report 里一致
        ledger.end("merge", 8000)
        ledger.keepUnsent("merge", 8000)
        assertEquals(RunEnd.UNSENT, ledger.pending("merge")?.end)
        // UNSENT 不再看基线：这局当初已经过了「够格」那道判断
        best = 80_000
        assertEquals(8000, ledger.resume("merge")?.score)
    }

    @Test
    fun restartRaisesTheBaseline() {
        best = 1000
        ledger.resume("merge")
        assertEquals(9000, ledger.abandon("merge", 9000)?.score)
        // 玩家选了不上传：账清掉，但本地最高分已经是 9000，下一局得超过它才值得再问
        ledger.settle("merge", 9000)
        best = 9000
        ledger.restart("merge", previous = 9000)
        assertNull(ledger.abandon("merge", 9000))
        assertEquals(9001, ledger.abandon("merge", 9001)?.score)
    }

    @Test
    fun settlingAnOlderRunDoesNotWipeANewerOne() {
        best = 0
        ledger.resume("merge")
        // 点了上传就重开，后台提交还没回来；新一局又打出更高的分
        ledger.progress("merge", 9000)
        ledger.restart("merge", previous = 9000)
        ledger.progress("merge", 10_000)
        // 旧提交这时才回来：成功或失败都不能动 10_000 那一笔
        ledger.settle("merge", 9000)
        assertEquals(10_000, ledger.pending("merge")?.score)
        ledger.keepUnsent("merge", 9000)
        assertEquals(10_000, ledger.pending("merge")?.score)
    }

    @Test
    fun aPoorerRunDoesNotDiscardAHigherUnsentScore() {
        best = 0
        ledger.resume("merge")
        ledger.end("merge", 9000)
        ledger.keepUnsent("merge", 9000) // 这一局没传成功
        // 下一局只打了 100 分，正常结算也不够格
        ledger.restart("merge", previous = 9000)
        ledger.end("merge", 100)
        ledger.settle("merge", 100)
        assertEquals(RunOffer("merge", 9000, RunEnd.UNSENT), ledger.pending("merge"))
    }

    @Test
    fun undoReopensTheRunAndKeepsTheOpeningBaseline() {
        best = 100
        ledger.resume("merge")
        ledger.progress("merge", 500)
        ledger.end("merge", 500)
        // 结算之后又「撤销一步」：这一局继续，账本也得回到进行中
        ledger.reopen("merge")
        // 基线仍是开局那个 100，不是刚结束的 500——否则续打的分数就不记账了
        ledger.progress("merge", 600)
        assertEquals(600, ledger.pending("merge")?.score)
        assertEquals(RunOffer("merge", 600, RunEnd.ABANDONED), ledger.abandon("merge", 600))
    }
}
