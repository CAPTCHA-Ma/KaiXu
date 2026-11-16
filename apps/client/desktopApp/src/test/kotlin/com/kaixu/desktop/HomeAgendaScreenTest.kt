package com.kaixu.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.kaixu.app.di.AppGraph
import com.kaixu.data.datasource.local.LocalDataSources
import com.kaixu.data.datasource.local.database.openDesktopDatabase
import com.kaixu.data.datasource.local.entity.LOCAL_ACTOR_ID
import com.kaixu.desktop.app.DesktopApp
import com.kaixu.desktop.preview.seedHomeAgendaPreview
import com.kaixu.domain.usecase.scheduling.AgendaSnapshot
import com.kaixu.domain.usecase.scheduling.AgendaUseCase
import com.kaixu.domain.usecase.scheduling.AgendaWindow
import com.kaixu.domain.validation.scheduling.ShanghaiTime
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.abs

class HomeAgendaScreenTest {
    @get:Rule val compose = createComposeRule()
    private val owner = HomeTestOwner()
    private val visible = mutableStateOf(true)
    private val directory = Files.createTempDirectory("kaixu-home-ui").toFile()
    private val database = openDesktopDatabase(directory.resolve("home.db"))
    private val sources = LocalDataSources(database)
    private val now = System.currentTimeMillis()
    private lateinit var snapshot: AgendaSnapshot
    private val hostWidth = mutableStateOf(1024.dp)
    private val hostHeight = mutableStateOf(768.dp)

    @After fun close() {
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnUiThread {
            owner.lifecycle.currentState = Lifecycle.State.DESTROYED
            owner.viewModelStore.clear()
        }
        database.close()
        directory.deleteRecursively()
    }

    private fun start(unboundedWidth: Boolean = false) {
        runBlocking {
            seedHomeAgendaPreview(sources, now)
            snapshot = AgendaUseCase(sources.tasks, sources.fixedEvents, sources.workSessions, sources.workspaces)(
                LOCAL_ACTOR_ID, AgendaWindow.around(ShanghaiTime.localDateOf(now)))
        }
        compose.setContent {
            if (visible.value) CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalViewModelStoreOwner provides owner) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.wrapContentWidth(Alignment.Start, unbounded = unboundedWidth)
                        .width(hostWidth.value).height(hostHeight.value)) {
                        DesktopApp(AppGraph(sources.tasks, sources.fixedEvents), localDataSources = sources)
                    }
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("home-agenda-list").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun click(tag: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
    }

    private fun screenshot(name: String) {
        val destination = System.getenv("KAIXU_SCREENSHOT_DIR") ?: return
        val file = File(destination, "home-agenda-$name.png")
        file.parentFile.mkdirs()
        Image.makeFromBitmap(compose.onRoot().captureToImage().asSkiaBitmap()).use {
            file.writeBytes(requireNotNull(it.encodeToData()).bytes)
        }
    }

    private fun wheel(cards: Int) {
        repeat(abs(cards)) {
            compose.onNodeWithTag("home-agenda-list").performMouseInput {
                moveTo(center)
                scroll(if (cards > 0) 1f else -1f)
            }
            compose.waitForIdle()
        }
    }

    private fun assertCentered(index: Int) {
        val viewport = compose.onNodeWithTag("home-agenda-viewport").fetchSemanticsNode().boundsInRoot
        val card = compose.onNodeWithTag("home-agenda-${snapshot.entries[index].key}").fetchSemanticsNode().boundsInRoot
        assertTrue("每次切换后应有完整卡片居中：card=$card viewport=$viewport", abs(card.center.y - viewport.center.y) < 3f)
    }

    @Test fun centersTheCurrentEntryAndOpensItsOriginalRecordWithoutLosingScroll() {
        start()
        val target = snapshot.entries[requireNotNull(snapshot.nearestIndex(now))]
        val tag = "home-agenda-${target.key}"
        val viewport = compose.onNodeWithTag("home-agenda-viewport").fetchSemanticsNode().boundsInRoot
        val card = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        assertTrue("默认事项应在视口中央：card=$card viewport=$viewport", abs(card.center.y - viewport.center.y) < 3f)
        screenshot("centered")
        click(tag)
        compose.onNodeWithTag("record-title").assertExists()
        click("titlebar-back")
        compose.onNodeWithTag(tag).assertExists()
        wheel(-requireNotNull(snapshot.nearestIndex(now)))
        screenshot("yesterday")
        val firstTag = "home-agenda-${snapshot.entries.first().key}"
        val beforeDetails = compose.onNodeWithTag(firstTag).fetchSemanticsNode().boundsInRoot
        click(firstTag)
        compose.onNodeWithTag("record-title").assertExists()
        click("titlebar-back")
        val afterDetails = compose.onNodeWithTag(firstTag).fetchSemanticsNode().boundsInRoot
        assertTrue("详情返回应保留用户浏览位置", abs(beforeDetails.center.y - afterDetails.center.y) < 3f)
        assertCentered(0)
        compose.onNodeWithTag("home-agenda-now").assertDoesNotExist()
        compose.onNodeWithTag("home-open-schedule").assertDoesNotExist()
        compose.onNodeWithText("今日日程").assertDoesNotExist()
    }

    @Test fun wheelMovesOneCardPerNotchAndKeepsTheSameSlotsInBothDirections() {
        start()
        val initial = requireNotNull(snapshot.nearestIndex(now))
        assertCentered(initial)
        wheel(1)
        assertCentered(initial + 1)
        screenshot("next-card")
        wheel(-1)
        assertCentered(initial)
        // 同一帧内的连续输入不能丢失张数，也不能停在两张卡片之间。
        compose.onNodeWithTag("home-agenda-list").performMouseInput {
            moveTo(center)
            repeat(3) { scroll(1f) }
        }
        compose.waitForIdle()
        assertCentered(initial + 3)
        wheel(-3)
        assertCentered(initial)
    }

    @Test fun resistancePreviewsThenAnimatesThroughIntermediateFramesAndFinishesCentered() {
        start()
        val initial = requireNotNull(snapshot.nearestIndex(now))
        val initialTag = "home-agenda-${snapshot.entries[initial].key}"
        val before = compose.onNodeWithTag(initialTag).fetchSemanticsNode().boundsInRoot.center.y
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("home-agenda-list").performMouseInput { moveTo(center); scroll(.4f) }
            compose.mainClock.advanceTimeBy(112)
            val pulled = compose.onNodeWithTag(initialTag).fetchSemanticsNode().boundsInRoot.center.y
            assertTrue("未到阈值时应有小幅阻尼位移", before - pulled in .1f..14f)
            screenshot("resistance")
            compose.onNodeWithTag("home-agenda-list").performMouseInput { scroll(.6f) }
            compose.mainClock.advanceTimeBy(112)
            val moving = compose.onNodeWithTag(initialTag).fetchSemanticsNode().boundsInRoot.center.y
            assertTrue("切换时应经过中间位置，不能直接跳到下一张", before - moving in 14f..100f)
            screenshot("moving")
            compose.mainClock.advanceTimeBy(1_200)
            assertCentered(initial + 1)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun anotherWheelInputDuringMotionDoesNotRestartTheSlowBeginning() {
        start()
        val initial = requireNotNull(snapshot.nearestIndex(now))
        val anchor = compose.onNodeWithTag("home-agenda-${snapshot.entries[initial + 1].key}")
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("home-agenda-list").performMouseInput { moveTo(center); scroll(1f) }
            compose.mainClock.advanceTimeBy(96)
            val earlier = anchor.fetchSemanticsNode().boundsInRoot.center.y
            compose.mainClock.advanceTimeBy(16)
            val beforeInput = anchor.fetchSemanticsNode().boundsInRoot.center.y
            val previousMovement = earlier - beforeInput
            assertTrue(previousMovement > 1f)
            compose.onNodeWithTag("home-agenda-list").performMouseInput { scroll(1f) }
            compose.mainClock.advanceTimeBy(16)
            val afterInput = anchor.fetchSemanticsNode().boundsInRoot.center.y
            assertTrue("新输入后应沿用速度，不能重新从缓慢起步开始", beforeInput - afterInput >= previousMovement * .7f)
            screenshot("continuous")
            compose.mainClock.advanceTimeBy(1_200)
            assertCentered(initial + 2)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun arrivingCardOvershootsAndRetractsWhileItsLeftEdgeStaysAnchored() {
        start()
        val initial = requireNotNull(snapshot.nearestIndex(now))
        val centered = compose.onNodeWithTag("home-agenda-${snapshot.entries[initial].key}").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        try {
            for (direction in listOf(1, -1)) {
                val target = if (direction > 0) initial + 1 else initial
                val card = compose.onNodeWithTag("home-agenda-${snapshot.entries[target].key}")
                compose.onNodeWithTag("home-agenda-list").performMouseInput { moveTo(center); scroll(direction.toFloat()) }
                var peakTravel = 0f
                var peakWidth = centered.width
                repeat(36) { frame ->
                    compose.mainClock.advanceTimeBy(16)
                    val bounds = card.fetchSemanticsNode().boundsInRoot
                    assertEquals("移动和回弹期间左侧保持对齐", centered.left, bounds.left, 1f)
                    peakTravel = maxOf(peakTravel, (centered.center.y - bounds.center.y) * direction)
                    peakWidth = maxOf(peakWidth, bounds.width)
                    if (frame == 15 && direction > 0) screenshot("arrival-overshoot")
                }
                assertTrue("越过中央后再回位，幅度约几像素：$peakTravel", peakTravel in 1f..8f * compose.density.density)
                assertTrue("右边缘应稍微展开再收回", peakWidth > centered.width + .5f * compose.density.density)
                assertTrue("展开幅度需克制", peakWidth < centered.width * 1.015f)
                assertCentered(target)
                assertEquals(centered.width, card.fetchSemanticsNode().boundsInRoot.width, .5f)
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun wheelCardsShareTheirLeftEdgeAndRecedeOnTheRightWithUniformSurfaceColor() {
        start()
        val initial = requireNotNull(snapshot.nearestIndex(now))
        fun bounds(index: Int) = compose.onNodeWithTag("home-agenda-${snapshot.entries[index].key}")
            .fetchSemanticsNode().boundsInRoot
        val centerBounds = bounds(initial)
        val neighbor = bounds(initial + 1)
        assertTrue("中央卡片应最大", centerBounds.width > neighbor.width)
        for (index in initial - 1..initial + 1) {
            assertEquals("上下卡片左边缘应对齐", centerBounds.left, bounds(index).left, 1f)
        }
        assertTrue("相邻卡片右边缘应向内缩进", centerBounds.right > neighbor.right)
        assertTrue("相邻卡片只应轻微缩小", neighbor.width / centerBounds.width > .92f)
        screenshot("wheel")

        // 把正在进行的卡片移到相邻槽位，检查其顶部和底部颜色一致，且与其他卡片共用同一纸面底色。
        wheel(1)
        val card = compose.onNodeWithTag("home-agenda-${snapshot.entries[initial].key}")
        fun blankColors(): Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color> {
            val pixels = card.captureToImage().toPixelMap()
            return pixels[pixels.width / 2, (pixels.height * .10f).toInt()] to
                pixels[pixels.width / 2, (pixels.height * .90f).toInt()]
        }
        val (top, bottom) = blankColors()
        assertEquals("相邻卡片底色保持完全不透明", 248f / 255f, top.red, .005f)
        assertEquals(246f / 255f, top.green, .005f)
        assertEquals(241f / 255f, top.blue, .005f)
        assertTrue("同一张卡片不应再被上下渐变遮罩染成不同颜色",
            maxOf(abs(top.red - bottom.red), abs(top.green - bottom.green), abs(top.blue - bottom.blue)) < .012f)
        card.performMouseInput { moveTo(center) }
        val hovered = blankColors().first
        assertEquals("悬停不能给滚动中的卡片覆盖灰色色层", top.red, hovered.red, .002f)
        assertEquals(top.green, hovered.green, .002f)
        assertEquals(top.blue, hovered.blue, .002f)
    }

    @Test fun resizingPreservesThreeCardsAndReservesTenPercentAboveAndBelow() {
        start()
        val initial = requireNotNull(snapshot.nearestIndex(now))
        fun geometry(): List<androidx.compose.ui.geometry.Rect> = (initial - 1..initial + 1).map {
            compose.onNodeWithTag("home-agenda-${snapshot.entries[it].key}").fetchSemanticsNode().boundsInRoot
        }
        fun gaps(cards: List<androidx.compose.ui.geometry.Rect>) = listOf(
            cards[1].top - cards[0].bottom, cards[2].top - cards[1].bottom,
        )
        fun assertReservedSpace() {
            val screen = compose.onNodeWithTag("home-screen").fetchSemanticsNode().boundsInRoot
            val pane = compose.onNodeWithTag("home-agenda-pane").fetchSemanticsNode().boundsInRoot
            assertEquals("上方预留约 10%", screen.height * .1f, pane.top - screen.top, 1f)
            assertEquals("下方预留约 10%", screen.height * .1f, screen.bottom - pane.bottom, 1f)
            geometry().forEach { card ->
                assertTrue("中央三张完整显示在卡片流区域内", card.top >= pane.top && card.bottom <= pane.bottom)
                assertTrue("相邻卡片不能被裁成残片", card.height > 95f * compose.density.density)
            }
        }
        val original = geometry()
        val originalGaps = gaps(original)
        assertReservedSpace()
        originalGaps.forEach { assertEquals("相邻卡片边缘间距固定为 14 dp", 14f * compose.density.density, it, 1.5f) }
        screenshot("reserved-space")
        compose.runOnIdle { hostHeight.value = 620.dp }
        compose.waitForIdle()
        assertCentered(initial)
        val shorter = geometry()
        gaps(shorter).forEachIndexed { index, gap -> assertEquals(originalGaps[index], gap, 1f) }
        assertEquals("改变区域高度不能缩放中央卡片", original[1].height, shorter[1].height, 1f)
        assertEquals("改变区域高度不能改变卡片宽度", original[1].width, shorter[1].width, 1f)
        assertReservedSpace()
        screenshot("short-window")
        compose.runOnIdle { hostWidth.value = 900.dp; hostHeight.value = 768.dp }
        compose.waitForIdle()
        assertCentered(initial)
        gaps(geometry()).forEachIndexed { index, gap -> assertEquals(originalGaps[index], gap, 1f) }
        assertReservedSpace()
    }

    @Test fun hoverFollowsThePointerSmoothlyAndReturnsToItsOriginalSlot() {
        start()
        val initial = requireNotNull(snapshot.nearestIndex(now))
        val card = compose.onNodeWithTag("home-agenda-${snapshot.entries[initial].key}")
        val original = card.fetchSemanticsNode().boundsInRoot
        fun move(x: Float, y: Float) = compose.onRoot().performMouseInput { moveTo(Offset(x, y)) }
        compose.mainClock.autoAdvance = false
        try {
            move(original.left + original.width * .2f, original.top + original.height * .2f)
            compose.mainClock.advanceTimeBy(64)
            val entering = card.fetchSemanticsNode().boundsInRoot
            compose.mainClock.advanceTimeBy(600)
            val left = card.fetchSemanticsNode().boundsInRoot
            assertTrue("悬停放大应经过中间帧", entering.width > original.width && entering.width < left.width)
            assertTrue("放大保持轻微", left.width / original.width in 1.015f..1.025f)
            assertTrue("指针在左上方时卡片跟随左上偏移", left.center.x < original.center.x - 1f && left.center.y < original.center.y - .5f)
            screenshot("hover-left")
            compose.mainClock.advanceTimeBy(400)
            assertEquals("指针不动时不能持续漂移或反复触发", left, card.fetchSemanticsNode().boundsInRoot)

            move(original.left + original.width * .8f, original.top + original.height * .8f)
            compose.mainClock.advanceTimeBy(32)
            val following = card.fetchSemanticsNode().boundsInRoot
            compose.mainClock.advanceTimeBy(600)
            val right = card.fetchSemanticsNode().boundsInRoot
            assertTrue("跟随移动不能瞬间跳到另一侧", following.center.x < right.center.x)
            assertTrue("指针在右下方时卡片跟随右下偏移", right.center.x > original.center.x + 1f && right.center.y > original.center.y + .5f)
            assertTrue("偏移始终克制", right.center.x - original.center.x < 6.5f * compose.density.density)
            screenshot("hover-right")

            move(original.right + 60f, original.center.y)
            compose.mainClock.advanceTimeBy(32)
            assertTrue("移出后平滑收回", card.fetchSemanticsNode().boundsInRoot.width > original.width)
            compose.mainClock.advanceTimeBy(700)
            val restored = card.fetchSemanticsNode().boundsInRoot
            assertEquals(original.width, restored.width, .1f)
            assertEquals(original.left, restored.left, .1f)
            assertEquals(original.top, restored.top, .1f)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun wideCardCanFollowLeftBeyondTheViewportWithoutLosingItsSurface() {
        // 测试渲染窗口只需显示左侧，内部按宽桌面布局以复现放大后的横向溢出。
        hostWidth.value = 1800.dp
        start(unboundedWidth = true)
        val initial = requireNotNull(snapshot.nearestIndex(now))
        val card = compose.onNodeWithTag("home-agenda-${snapshot.entries[initial].key}")
        val original = card.fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("home-agenda-viewport").fetchSemanticsNode().boundsInRoot
        compose.onRoot().performMouseInput { moveTo(Offset(original.left + 1f, original.center.y)) }
        compose.waitForIdle()
        screenshot("wide-hover-left-edge")
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        val outsideLeft = pixels[(viewport.left - compose.density.density).toInt(), original.center.y.toInt()]
        assertEquals("左移越过滚轮边界后仍应绘出完整卡片纸面，不能被裁成白色", 248f / 255f, outsideLeft.red, .012f)
        assertEquals(246f / 255f, outsideLeft.green, .012f)
        assertEquals(241f / 255f, outsideLeft.blue, .012f)
        val above = pixels[original.center.x.toInt(), (viewport.top - 2f * compose.density.density).toInt()]
        assertEquals("仍保留垂直裁剪，不侵占顶部预留区域", 1f, above.red, .002f)
        assertEquals(1f, above.green, .002f)
        compose.onRoot().performMouseInput {
            moveTo(Offset(original.left + 100f, viewport.top - 2f * compose.density.density))
            scroll(1f)
        }
        compose.waitForIdle()
        assertCentered(initial)
    }

    @Test fun hoveredNeighborCanBeClickedAndWheelClearsHoverWithoutLeavingResidualOffset() {
        start()
        val initial = requireNotNull(snapshot.nearestIndex(now))
        val card = compose.onNodeWithTag("home-agenda-${snapshot.entries[initial + 1].key}")
        val original = card.fetchSemanticsNode().boundsInRoot
        card.performMouseInput { moveTo(center) }
        compose.waitForIdle()
        assertTrue("非中央卡片也支持悬停放大", card.fetchSemanticsNode().boundsInRoot.width > original.width)
        card.performMouseInput { click() }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("record-title").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("record-title").assertExists()
        click("titlebar-back")

        val centerCard = compose.onNodeWithTag("home-agenda-${snapshot.entries[initial].key}")
        val base = centerCard.fetchSemanticsNode().boundsInRoot
        centerCard.performMouseInput { moveTo(Offset(width * .25f, height * .25f)) }
        compose.waitForIdle()
        assertTrue(centerCard.fetchSemanticsNode().boundsInRoot.width > base.width)
        centerCard.performMouseInput { scroll(1f) }
        compose.waitForIdle()
        assertCentered(initial + 1)
        val after = card.fetchSemanticsNode().boundsInRoot
        assertEquals("滚动结束不遗留悬停位移", base.left, after.left, .5f)
        assertEquals("滚动结束恢复滚轮原尺寸", base.width, after.width, .5f)
    }

    @Test fun reachingTomorrowRequiresAdditionalWheelInputBeforeOpeningFullSchedule() {
        start()
        wheel(snapshot.entries.lastIndex - requireNotNull(snapshot.nearestIndex(now)))
        screenshot("tomorrow")
        compose.onNodeWithTag("schedule-create").assertDoesNotExist()
        wheel(1)
        compose.onNodeWithTag("schedule-create").assertDoesNotExist()
        wheel(5)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("schedule-create").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("schedule-create").assertExists()
    }

    @Test fun yesterdayBoundaryAlsoOpensFullScheduleAndOrdinaryScrollStaysOnHome() {
        start()
        wheel(-1)
        compose.onNodeWithTag("home-screen").assertExists()
        compose.onNodeWithTag("schedule-create").assertDoesNotExist()
        wheel(-(requireNotNull(snapshot.nearestIndex(now)) - 1))
        wheel(-6)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("schedule-create").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("schedule-create").assertExists()
    }
}

private class HomeTestOwner : LifecycleOwner, ViewModelStoreOwner {
    override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    override val viewModelStore = ViewModelStore()
}
