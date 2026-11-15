package com.kaixu.desktop

import com.kaixu.desktop.presentation.ui.home.CardScrollMotion
import com.kaixu.desktop.presentation.ui.home.wheelCardPose
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class CardScrollMotionTest {
    private val frame = 1f / 120f

    private fun settle(motion: CardScrollMotion) {
        repeat(360) { if (motion.isRunning) motion.advance(frame) }
        assertFalse("停止输入后应在有限时间内停稳", motion.isRunning)
    }

    @Test fun startsWithResistanceThenSettlesExactlyOnTheRequestedCard() {
        val motion = CardScrollMotion(10, 30)
        motion.input(11)
        motion.advance(.016f)
        val firstStep = motion.position - 10
        repeat(2) { motion.advance(.016f) }
        val before = motion.position
        motion.advance(.016f)
        assertTrue(firstStep > 0)
        assertTrue("起步比后续移动更克制", motion.position - before > firstStep * 2)
        assertTrue(motion.position < 11)
        settle(motion)
        assertEquals(11f, motion.position, 0f)
        assertEquals(0f, motion.velocity, 0f)
    }

    @Test fun belowThresholdOnlyPullsSlightlyAndReturnsWithoutChangingCards() {
        val motion = CardScrollMotion(10, 30)
        motion.input(10, .6f)
        repeat(12) { motion.advance(frame) }
        assertTrue(motion.position > 10)
        assertTrue(motion.position < 10 + CardScrollMotion.ResistanceDistance)
        assertEquals(10, motion.targetIndex)
        settle(motion)
        assertEquals(10f, motion.position, 0f)
    }

    @Test fun continuousInputPreservesVelocityAcrossCardsAndOnlyStopsAtTheFinalTarget() {
        val motion = CardScrollMotion(10, 30)
        var previousPosition = motion.position
        var maximumSpeed = 0f
        for (target in 11..15) {
            val beforeInput = motion.velocity
            motion.input(target)
            assertEquals("新输入不得把现有速度重置为零", beforeInput, motion.velocity, 0f)
            repeat(12) {
                motion.advance(frame)
                assertTrue("连续向下滚动不应反向回弹", motion.position >= previousPosition)
                if (motion.position > 11 && motion.position < 14) {
                    assertTrue("经过中间卡片时不应重复缓停", motion.velocity > 3f)
                }
                maximumSpeed = maxOf(maximumSpeed, motion.velocity)
                previousPosition = motion.position
            }
        }
        assertTrue(maximumSpeed > 5f)
        motion.advance(.064f)
        repeat(360) {
            if (motion.isRunning) {
                motion.advance(frame)
                assertTrue("最终回弹幅度应克制", motion.position <= 15.12f)
                previousPosition = motion.position
            }
        }
        assertFalse(motion.isRunning)
        assertEquals(15f, motion.position, 0f)
    }

    @Test fun inputDuringTheFinalSlowdownContinuesFromItsExistingPositionAndVelocity() {
        val motion = CardScrollMotion(10, 30)
        motion.input(11)
        repeat(16) { motion.advance(frame) }
        val position = motion.position
        val speed = motion.velocity
        assertTrue(position > 10 && position < 11)
        assertTrue(speed > 0)
        motion.input(12)
        assertEquals(position, motion.position, 0f)
        assertEquals(speed, motion.velocity, 0f)
        motion.advance(frame)
        assertTrue(motion.position > position)
        settle(motion)
        assertEquals(12f, motion.position, 0f)
    }

    @Test fun reversalAndEndpointsStayBoundedAndFinishOnAnIntegerCard() {
        val motion = CardScrollMotion(1, 3)
        motion.input(3)
        repeat(12) { motion.advance(frame) }
        val speed = motion.velocity
        motion.input(0)
        assertEquals(speed, motion.velocity, 0f)
        repeat(360) {
            motion.advance(frame)
            assertTrue(motion.position in -CardScrollMotion.BoundaryTravel..3f + CardScrollMotion.BoundaryTravel)
        }
        assertEquals(0f, motion.position, 0f)
        motion.input(0, -1f)
        settle(motion)
        assertEquals(0f, motion.position, 0f)
        assertTrue(abs(motion.velocity) < .001f)
    }

    @Test fun smallInputNearArrivalDoesNotRestartOrAmplifyTheRebound() {
        val motion = CardScrollMotion(10, 30)
        val baseline = CardScrollMotion(10, 30)
        motion.input(11)
        baseline.input(11)
        repeat(22) { motion.advance(frame); baseline.advance(frame) }
        // 小数输入没有切换目标，不应重复弹射或增加回弹幅度。
        motion.input(11, .2f)
        repeat(360) {
            motion.advance(frame)
            baseline.advance(frame)
            assertEquals(baseline.position, motion.position, .001f)
        }
        assertEquals(11f, motion.position, 0f)
        assertFalse(motion.isRunning)
    }

    @Test fun fasterResponseMakesTheSwitchDecisiveWithoutALongStoppingTail() {
        val motion = CardScrollMotion(10, 30)
        motion.input(11)
        repeat(10) { motion.advance(frame) }
        assertTrue("约 80 ms 后应已明显切换，而不是仍停留在轻微拖动", motion.position > 10.22f)
        repeat(56) { motion.advance(frame) }
        assertFalse("单张切换应及时收尾", motion.isRunning)
        assertEquals(11f, motion.position, 0f)
    }

    @Test fun eachDirectionBrieflyOvershootsThenReturnsWithASmallRightEdgeStretch() {
        for (direction in listOf(-1, 1)) {
            val motion = CardScrollMotion(10, 30)
            val target = 10 + direction
            motion.input(target)
            var peak = 0f
            var stretch = 0f
            var returned = false
            repeat(90) {
                motion.advance(frame)
                val overshoot = (motion.position - target) * direction
                peak = maxOf(peak, overshoot)
                stretch = maxOf(stretch, motion.arrivalStretch)
                if (overshoot > .005f && motion.velocity * direction < 0f) returned = true
            }
            assertTrue("应有一次可感知但轻微的过冲：$peak", peak in .02f.. .06f)
            assertTrue("卡片右侧轻微展开，不应夸张拉伸", stretch in .003f.. .015f)
            assertTrue("越过落点后应被拉回", returned)
            assertFalse(motion.isRunning)
            assertEquals(target.toFloat(), motion.position, 0f)
            assertEquals(0f, motion.arrivalStretch, 0f)
        }
    }

    @Test fun wheelDepthFadePreservesReadableNeighborsAndStillClearsViewportEdges() {
        fun pose(distance: Float, height: Float = 1200f) = wheelCardPose(distance, height, 104f, 14f)
        for (height in listOf(340f, 428f, 1200f)) {
            for (distance in listOf(-1f, -.5f, 0f, .5f, 1f)) {
                assertEquals("中央三个槽位不受距离或边缘淡出影响", 1f, pose(distance, height).alpha, 0f)
            }
            assertTrue("离开相邻槽位时平滑开始淡出", pose(1.0001f, height).alpha > .999f)
        }
        assertTrue(pose(2f).alpha in .75f.. .85f)
        assertEquals(pose(2f).alpha, pose(-2f).alpha, 0f)
        assertEquals("靠近视口边缘仍需完全淡出", 0f, pose(3f, 620f).alpha, 0f)
    }
}
