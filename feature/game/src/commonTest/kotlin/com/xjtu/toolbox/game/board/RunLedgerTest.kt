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
        reopened.clear("merge")
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
    fun naturalEndClearsTheLedger() {
        best = 0
        ledger.resume("merge")
        ledger.progress("merge", 8000)
        ledger.finish("merge")
        assertNull(ledger.pending("merge"))
        // 结算页上再按退出就是退出，不该再问一次「要不要上传」
        assertNull(ledger.abandon("merge", 8000))
    }

    @Test
    fun failedSubmitIsKeptAsUnsent() {
        best = 0
        ledger.resume("merge")
        // 结算时先划账，提交失败再记一笔——顺序和 Leaderboard.report 里一致
        ledger.finish("merge")
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
        ledger.clear("merge")
        best = 9000
        ledger.restart("merge", previous = 9000)
        assertNull(ledger.abandon("merge", 9000))
        assertEquals(9001, ledger.abandon("merge", 9001)?.score)
    }
}
