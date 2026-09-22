package com.xjtu.toolbox.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** [morphPhaseToSegment] 是 PR X 形变动画里唯一的纯逻辑，其余部分都要靠 Canvas 画出来才能看。 */
class MorphingLoaderTest {

    @Test
    fun phaseZero_isSegmentZeroAtStartOfMorph() {
        val (segment, progress) = morphPhaseToSegment(phase = 0f, segmentCount = 3, morphFraction = 0.35f)
        assertEquals(0, segment)
        assertEquals(0f, progress, 0.0001f)
    }

    @Test
    fun withinMorphWindow_progressScalesLinearly() {
        // morphFraction=0.35：段内进度 0.175（一半）时，形变进度应该正好是 0.5。
        val (segment, progress) = morphPhaseToSegment(phase = 0.175f, segmentCount = 3, morphFraction = 0.35f)
        assertEquals(0, segment)
        assertEquals(0.5f, progress, 0.0001f)
    }

    @Test
    fun afterMorphWindow_progressClampsAtOne_thatIsThePause() {
        // 段内进度已经过了 morphFraction，形变早完成了，应该钳在 1f（停在目标形状上）。
        val (segment, progress) = morphPhaseToSegment(phase = 0.9f, segmentCount = 3, morphFraction = 0.35f)
        assertEquals(0, segment)
        assertEquals(1f, progress, 0.0001f)
    }

    @Test
    fun phaseAdvancesIntoNextSegment() {
        val (segment, progress) = morphPhaseToSegment(phase = 1.1f, segmentCount = 3, morphFraction = 0.35f)
        assertEquals(1, segment)
        assertEquals(0.1f / 0.35f, progress, 0.0001f) // 段内进度 0.1，还没到 morphFraction=0.35 的窗口
    }

    @Test
    fun segmentClampsToLastIndex_whenPhaseReachesSegmentCount() {
        // rememberInfiniteTransition 循环时 targetValue 可能短暂等于 segmentCount，不能越界。
        val (segment, _) = morphPhaseToSegment(phase = 3f, segmentCount = 3, morphFraction = 0.35f)
        assertEquals(2, segment)
    }
}
