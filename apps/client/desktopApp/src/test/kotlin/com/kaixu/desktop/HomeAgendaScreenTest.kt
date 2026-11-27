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
import com.kaixu.core.platform.LocalZone
import com.kaixu.data.datasource.local.LocalDataSources
import com.kaixu.data.datasource.local.database.openDesktopDatabase
import com.kaixu.desktop.app.DesktopApp
import com.kaixu.desktop.preview.seedHomeAgendaPreview
import com.kaixu.domain.model.workspaces.WorkspaceKind
import com.kaixu.domain.usecase.scheduling.AgendaSnapshot
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
            val shanghai = LocalZone.of("Asia/Shanghai")
            snapshot = AppGraph(sources).queryHomeAgenda(
                AgendaWindow.around(shanghai.localDateOf(now), shanghai),
                shanghai,
            )
        }
        compose.setContent {
            if (visible.value) CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalViewModelStoreOwner provides owner) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.wrapContentWidth(Alignment.Start, unbounded = unboundedWidth)
                        .width(hostWidth.value).height(hostHeight.value)) {
                        DesktopApp(AppGraph(sources), localDataSources = sources)
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

    private fun screenshot(name: String, tag: String? = null) {
        val destination = System.getenv("KAIXU_SCREENSHOT_DIR") ?: return
        val file = File(destination, "home-agenda-$name.png")
        file.parentFile.mkdirs()
        val target = if (tag != null) compose.onNodeWithTag(tag) else compose.onAllNodes(isRoot()).onFirst()
        Image.makeFromBitmap(target.captureToImage().asSkiaBitmap()).use {
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
        assertTrue("相邻卡片底色应为极浅扁平毛玻璃色而非偏黄或纯白：top=$top",
            top.red in .94f..(.994f) && top.green in .945f..(.996f) && top.blue in .955f..(.999f) && top.blue >= top.red)
        assertTrue("同一张卡片保持扁平柔和毛玻璃漫射，不应出现明显色差：top=$top bottom=$bottom",
            maxOf(abs(top.red - bottom.red), abs(top.green - bottom.green), abs(top.blue - bottom.blue)) < .025f)
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
        assertTrue("左移越过滚轮边界后仍应绘出完整浅色毛玻璃卡面，不能被裁成纯白：outsideLeft=$outsideLeft",
            outsideLeft.red in .94f..(.994f) && outsideLeft.green in .945f..(.996f) && outsideLeft.blue in .955f..(.999f) &&
                outsideLeft.blue >= outsideLeft.red)
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

    @Test fun splitPanelsAndTodayHeaderAdaptWithSmoothRetractionAnimation() {
        start()
        val today = snapshot.window.today
        val expectedDate = "${today.substring(5, 7).toInt()}月${today.substring(8, 10).toInt()}日"
        compose.onNodeWithTag("home-today-header").assertExists()
        compose.onNodeWithTag("home-today-capsule").assertDoesNotExist()
        compose.onNodeWithText(expectedDate).assertExists()
        compose.onNodeWithText("项截止", substring = true).assertExists()

        val screen = compose.onNodeWithTag("home-screen").fetchSemanticsNode().boundsInRoot
        val leftWide = compose.onNodeWithTag("home-left-panel").fetchSemanticsNode().boundsInRoot
        val rightWide = compose.onNodeWithTag("home-secondary-pane").fetchSemanticsNode().boundsInRoot
        assertTrue("宽屏应并排显示左右两个圆角面板", rightWide.left > leftWide.right)
        val gapCenterX = ((leftWide.right + rightWide.left) / 2f).toInt()
        val gapCenterY = screen.center.y.toInt()
        val rootPixels = compose.onRoot().captureToImage().toPixelMap()
        val gapColor = rootPixels[gapCenterX, gapCenterY]
        assertEquals("双面板中间分割区应与窗口背景灰色同色", 243f / 255f, gapColor.red, .015f)
        assertEquals(243f / 255f, gapColor.green, .015f)
        assertEquals(243f / 255f, gapColor.blue, .015f)

        // 缩小到阈值以下：右侧区域隐藏，左侧区域占满宽度。
        compose.runOnIdle { hostWidth.value = 680.dp }
        compose.waitForIdle()
        compose.onNodeWithTag("home-secondary-pane").assertDoesNotExist()
        val narrowScreen = compose.onNodeWithTag("home-screen").fetchSemanticsNode().boundsInRoot
        val leftNarrow = compose.onNodeWithTag("home-left-panel").fetchSemanticsNode().boundsInRoot
        assertEquals("窄窗下左侧面板占满主显示区宽度", narrowScreen.width, leftNarrow.width, 1f)
        screenshot("single-pane")

        // 窗口变宽跨过阈值：左侧面板平滑回缩，右侧面板同步动画进入。
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { hostWidth.value = 1024.dp }
            compose.mainClock.advanceTimeBy(128)
            val leftMid = compose.onNodeWithTag("home-left-panel").fetchSemanticsNode().boundsInRoot
            val rightMid = compose.onNodeWithTag("home-secondary-pane").fetchSemanticsNode().boundsInRoot
            assertTrue("左侧面板回缩应经过中间帧：leftMid=${leftMid.width} target=${leftWide.width} full=${screen.width}",
                leftMid.width > leftWide.width + 15f && leftMid.width < screen.width - 15f)
            assertTrue("右侧面板应随左侧回缩同步展开", rightMid.width > 10f && rightMid.width < rightWide.width - 10f)
            screenshot("split-transition")
            compose.mainClock.advanceTimeBy(900)
            val leftSettled = compose.onNodeWithTag("home-left-panel").fetchSemanticsNode().boundsInRoot
            val rightSettled = compose.onNodeWithTag("home-secondary-pane").fetchSemanticsNode().boundsInRoot
            assertEquals(leftWide.width, leftSettled.width, 1f)
            assertEquals(rightWide.width, rightSettled.width, 1f)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun boundaryOverscrollPullsCardsWithGrowingRangeAndElongatesRingBar() {
        start()
        val lastIndex = snapshot.entries.lastIndex
        wheel(lastIndex - requireNotNull(snapshot.nearestIndex(now)))
        val lastCard = compose.onNodeWithTag("home-agenda-${snapshot.entries[lastIndex].key}")
        val ring = compose.onNodeWithTag("home-boundary-ring-bottom")
        val restCenterY = lastCard.fetchSemanticsNode().boundsInRoot.center.y
        val restRingBounds = ring.fetchSemanticsNode().boundsInRoot
        val restRingHeight = restRingBounds.height

        compose.mainClock.autoAdvance = false
        try {
            // 第一次在底部继续下滑：卡片被向上阻尼拉动，圆环单向向下拉长且顶部端帽保持锚定。
            compose.onNodeWithTag("home-agenda-list").performMouseInput { moveTo(center); scroll(1f) }
            compose.mainClock.advanceTimeBy(80)
            val pull1 = restCenterY - lastCard.fetchSemanticsNode().boundsInRoot.center.y
            val ringBounds1 = ring.fetchSemanticsNode().boundsInRoot
            val ringHeight1 = ringBounds1.height
            assertTrue("底部继续滚动时卡片应向上产生阻尼拉动：pull1=$pull1", pull1 > 6f * compose.density.density)
            assertTrue("圆环状条应随滚动拉长：ringHeight1=$ringHeight1 rest=$restRingHeight", ringHeight1 > restRingHeight + 4f)
            assertEquals("底部提示条起点端帽应保持锚定不动", restRingBounds.top, ringBounds1.top, 1f)

            // 继续累加滚动幅度：卡片拉动范围随幅度变大，圆环状条持续向下拉长，主圆点始终位于底端端帽处。
            compose.onNodeWithTag("home-agenda-list").performMouseInput { scroll(1f); scroll(1f) }
            compose.mainClock.advanceTimeBy(80)
            val pull3 = restCenterY - lastCard.fetchSemanticsNode().boundsInRoot.center.y
            val ringBounds3 = ring.fetchSemanticsNode().boundsInRoot
            val ringHeight3 = ringBounds3.height
            assertTrue("随滚动幅度增加，卡片拉动范围应更大：pull3=$pull3 pull1=$pull1", pull3 > pull1 + 6f * compose.density.density)
            assertTrue("圆环状条应继续拉长：ringHeight3=$ringHeight3 ringHeight1=$ringHeight1", ringHeight3 > ringHeight1 + 8f)
            assertEquals("底部提示条持续拉伸时起点端帽仍应稳固", restRingBounds.top, ringBounds3.top, 1f)
            val bottomPixels = ring.captureToImage().toPixelMap()
            val bottomTipCenter = bottomPixels[bottomPixels.width / 2, bottomPixels.height - bottomPixels.width / 2]
            assertTrue("底部拉伸后最大主圆点应位于底端端帽中心：pixel=$bottomTipCenter",
                minOf(bottomTipCenter.red, bottomTipCenter.green, bottomTipCenter.blue) < .60f)
            screenshot("boundary-stretched")

            // 停止输入但未达 6 格跳转阈值时，卡片弹性回弹至中央槽位。
            compose.mainClock.advanceTimeBy(700)
            assertCentered(lastIndex)
        } finally {
            compose.mainClock.autoAdvance = true
        }

        // 切换到昨日起点，验证顶端提示条单向向上拉伸、底端锚定、最大主圆点始终位于顶端端帽。
        click("home-day-yesterday")
        val topRing = compose.onNodeWithTag("home-boundary-ring-top")
        val topRestBounds = topRing.fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("home-agenda-list").performMouseInput { moveTo(center); scroll(-1f); scroll(-1f); scroll(-1f) }
            compose.mainClock.advanceTimeBy(120)
            val topStretched = topRing.fetchSemanticsNode().boundsInRoot
            assertTrue("顶端提示条应随上滑拉长：stretched=${topStretched.height} rest=${topRestBounds.height}",
                topStretched.height > topRestBounds.height + 16f)
            assertEquals("顶端提示条起点（底端）应保持锚定不动", topRestBounds.bottom, topStretched.bottom, 1f)
            val topPixels = topRing.captureToImage().toPixelMap()
            val topTipCenter = topPixels[topPixels.width / 2, topPixels.width / 2]
            assertTrue("顶端拉伸后最大主圆点应位于顶端端帽中心：pixel=$topTipCenter",
                minOf(topTipCenter.red, topTipCenter.green, topTipCenter.blue) < .60f)
            screenshot("boundary-top-stretched")
            compose.mainClock.advanceTimeBy(700)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun dayAnchorBarSyncsWithWheelAndReturnsToCurrent() {
        start()
        val initial = requireNotNull(snapshot.nearestIndex(now))
        val today = snapshot.window.today
        val yesterday = ShanghaiTime.plusDays(today, -1)
        val tomorrow = ShanghaiTime.plusDays(today, 1)
        val firstYesterday = snapshot.entries.indexOfFirst { ShanghaiTime.localDateOf(it.time) == yesterday }
        val firstTomorrow = snapshot.entries.indexOfFirst { ShanghaiTime.localDateOf(it.time) == tomorrow }

        compose.onNodeWithTag("home-teaching-week-slot").assertExists()
        compose.onNodeWithTag("home-day-switcher").assertExists()
        compose.onNodeWithTag("home-jump-current").assertDoesNotExist()

        click("home-day-yesterday")
        assertCentered(firstYesterday)
        compose.onNodeWithTag("home-jump-current").assertExists()
        screenshot("header-yesterday-jump")

        click("home-day-tomorrow")
        assertCentered(firstTomorrow)
        compose.onNodeWithTag("home-jump-current").assertExists()

        click("home-jump-current")
        assertCentered(initial)
        compose.onNodeWithTag("home-jump-current").assertDoesNotExist()
    }

    @Test fun quickCaptureCapsuleExpandsOnHoverRetractsWhenEmptyAndSavesTaskWithTimeAndWorkspace() {
        val createdSpace = runBlocking {
            sources.workspaces.createWorkspace(WorkspaceKind.COURSE, "软件分析与设计")
        }
        start()

        val capsule = compose.onNodeWithTag("home-capture-capsule")
        compose.onNodeWithTag("home-capture-toggle").assertExists()
        val collapsedWidth = capsule.fetchSemanticsNode().boundsInRoot.width
        assertTrue("静息态胶囊应呈居中圆形小尺寸：width=$collapsedWidth", collapsedWidth < 60f * compose.density.density)

        // 悬停到加号上时左右展开；未输入信息移开后恢复原状。
        capsule.performMouseInput { enter(center) }
        compose.waitForIdle()
        compose.onNodeWithTag("home-capture-step-title").assertExists()
        val expandedWidth = capsule.fetchSemanticsNode().boundsInRoot.width
        assertTrue("悬停后胶囊应向左右显著延伸：expanded=$expandedWidth collapsed=$collapsedWidth",
            expandedWidth > collapsedWidth * 4f)
        screenshot("capture-step1")

        compose.onNodeWithTag("home-today-header").performMouseInput { moveTo(center) }
        compose.mainClock.advanceTimeBy(350)
        compose.waitForIdle()
        compose.onNodeWithTag("home-capture-toggle").assertExists()

        // 输入名称后移开鼠标，仍保留展开状态与输入内容。
        capsule.performMouseInput { moveTo(center) }
        compose.waitForIdle()
        compose.onNodeWithTag("home-capture-title").performTextInput("梳理首页毛玻璃方案")
        compose.onNodeWithTag("home-today-header").performMouseInput { moveTo(center) }
        compose.mainClock.advanceTimeBy(350)
        compose.waitForIdle()
        compose.onNodeWithTag("home-capture-step-title").assertExists()
        compose.onNodeWithTag("home-capture-title").assertTextContains("梳理首页毛玻璃方案")

        // 点击下一步切换到时间与工作区配置态。
        click("home-capture-next")
        compose.onNodeWithTag("home-capture-step-details").assertExists()
        screenshot("capture-step2")

        // 打开时间微面板：无快捷选项、无“（可选）”、支持三栏日期与日历选择及双框时刻。
        click("home-capture-time-btn")
        compose.onNodeWithTag("home-capture-time-popover").assertExists()
        compose.onNodeWithText("截止时间（可选）").assertDoesNotExist()
        compose.onNodeWithText("下周").assertDoesNotExist()
        compose.onNodeWithTag("home-capture-date-year").assertExists()
        compose.onNodeWithTag("home-capture-date-month").assertExists()
        compose.onNodeWithTag("home-capture-date-day").assertExists()

        // 展开日历并点选今天。
        click("home-capture-calendar-toggle")
        compose.onNodeWithTag("home-capture-calendar-grid").assertExists()
        screenshot("capture-task-calendar", tag = "home-capture-time-popover")
        click("home-capture-cal-day-${snapshot.window.today}")
        compose.onNodeWithTag("home-capture-calendar-grid").assertDoesNotExist()

        // 切到具体时刻：确认拆分为小时/分钟双输入框且无快捷时刻按钮。
        click("home-capture-task-exacttime")
        compose.onNodeWithTag("home-capture-task-hour").assertExists()
        compose.onNodeWithTag("home-capture-task-minute").assertExists()
        compose.onNodeWithText("23:59").assertDoesNotExist()
        screenshot("capture-task-exact", tag = "home-capture-time-popover")

        // 切回仅日期截止并确认。
        click("home-capture-task-dateonly")
        screenshot("capture-time-popover", tag = "home-capture-time-popover")
        click("home-capture-time-done")
        compose.onNodeWithTag("home-capture-time-popover").assertDoesNotExist()

        // 打开工作区微面板，选择“软件分析与设计”。
        click("home-capture-workspace-btn")
        compose.onNodeWithTag("home-capture-workspace-popover").assertExists()
        screenshot("capture-workspace-popover", tag = "home-capture-workspace-popover")
        click("home-capture-ws-${createdSpace.id}")
        compose.onNodeWithTag("home-capture-workspace-popover").assertDoesNotExist()

        // 提交并验证持久化到本地数据库且首页同步刷新。
        click("home-capture-submit")
        compose.waitUntil(5_000) {
            runBlocking { sources.tasks.listTasks().any { it.title == "梳理首页毛玻璃方案" } }
        }
        val savedTask = runBlocking { sources.tasks.listTasks().first { it.title == "梳理首页毛玻璃方案" } }
        assertEquals(snapshot.window.today, savedTask.deadlineDate)
        assertNull(savedTask.deadlineAt)
        assertEquals(createdSpace.id, savedTask.workspaceId)
    }

    @Test fun quickCaptureCanCreateIndependentEventLeavingTimeAndWorkspaceEmpty() {
        start()
        click("home-capture-toggle")
        click("home-capture-kind-event")
        compose.onNodeWithTag("home-capture-title").performTextInput("临时同步站会")
        click("home-capture-next")
        compose.onNodeWithTag("home-capture-step-details").assertExists()

        // 时间和工作区均保持默认空态直接提交（若与预览时段冲突则点击仍然保存）。
        click("home-capture-submit")
        compose.waitForIdle()
        if (compose.onAllNodesWithTag("home-capture-conflict-confirm").fetchSemanticsNodes().isNotEmpty()) {
            click("home-capture-conflict-confirm")
        }
        compose.waitUntil(5_000) {
            runBlocking { sources.fixedEvents.listEvents().any { it.title == "临时同步站会" } }
        }
        val savedEvent = runBlocking { sources.fixedEvents.listEvents().first { it.title == "临时同步站会" } }
        assertNull(savedEvent.workspaceId)
        assertTrue(savedEvent.endsAt > savedEvent.startsAt)
    }

    @Test fun quickCaptureEventPopoverUsesSplitDateAndHourMinuteInputsWithoutPresets() {
        start()
        val tomorrow = ShanghaiTime.plusDays(snapshot.window.today, 1)
        click("home-capture-toggle")
        click("home-capture-kind-event")
        compose.onNodeWithTag("home-capture-title").performTextInput("架构评审会")
        click("home-capture-next")
        click("home-capture-time-btn")
        compose.onNodeWithTag("home-capture-time-popover").assertExists()

        // 确认所有快捷选项与“默认下整点”已移除。
        compose.onNodeWithText("默认下整点").assertDoesNotExist()
        compose.onNodeWithText("下整点").assertDoesNotExist()
        compose.onNodeWithText("1小时").assertDoesNotExist()
        compose.onNodeWithText("下周").assertDoesNotExist()
        screenshot("capture-event-popover", tag = "home-capture-time-popover")

        // 手动填写三栏日期与拆分的小时/分钟输入框（无需冒号）。
        compose.onNodeWithTag("home-capture-date-year").performTextReplacement(tomorrow.substring(0, 4))
        compose.onNodeWithTag("home-capture-date-month").performTextReplacement(tomorrow.substring(5, 7))
        compose.onNodeWithTag("home-capture-date-day").performTextReplacement(tomorrow.substring(8, 10))
        compose.onNodeWithTag("home-capture-event-start-hour").performTextReplacement("16")
        compose.onNodeWithTag("home-capture-event-start-minute").performTextReplacement("15")
        compose.onNodeWithTag("home-capture-event-end-hour").performTextReplacement("17")
        compose.onNodeWithTag("home-capture-event-end-minute").performTextReplacement("45")

        click("home-capture-time-done")
        compose.onNodeWithTag("home-capture-time-popover").assertDoesNotExist()

        click("home-capture-submit")
        compose.waitForIdle()
        if (compose.onAllNodesWithTag("home-capture-conflict-confirm").fetchSemanticsNodes().isNotEmpty()) {
            click("home-capture-conflict-confirm")
        }
        compose.waitUntil(5_000) {
            runBlocking { sources.fixedEvents.listEvents().any { it.title == "架构评审会" } }
        }
        val saved = runBlocking { sources.fixedEvents.listEvents().first { it.title == "架构评审会" } }
        assertEquals(ShanghaiTime.parseOrNull("$tomorrow 16:15"), saved.startsAt)
        assertEquals(ShanghaiTime.parseOrNull("$tomorrow 17:45"), saved.endsAt)
    }

    @Test fun workspacePopoverShowsHoverFeedbackAndScrollsWithWheelWhenMoreThanFour() {
        val spaces = runBlocking {
            listOf(
                sources.workspaces.createWorkspace(WorkspaceKind.COURSE, "01 数据库系统原理"),
                sources.workspaces.createWorkspace(WorkspaceKind.COURSE, "02 算法与数据结构"),
                sources.workspaces.createWorkspace(WorkspaceKind.COURSE, "03 软件工程"),
                sources.workspaces.createWorkspace(WorkspaceKind.COURSE, "04 操作系统"),
                sources.workspaces.createWorkspace(WorkspaceKind.PROJECT, "05 KaiXu 课程项目"),
                sources.workspaces.createWorkspace(WorkspaceKind.PROJECT, "06 期末综合实践"),
            )
        }
        start()
        click("home-capture-toggle")
        compose.onNodeWithTag("home-capture-title").performTextInput("测试工作区滚动")
        click("home-capture-next")
        click("home-capture-workspace-btn")

        compose.onNodeWithTag("home-capture-workspace-popover").assertExists()
        compose.onNodeWithText("可空 · 默认独立").assertDoesNotExist()
        compose.onNodeWithText("独立事项（不关联）").assertDoesNotExist()
        compose.onNodeWithTag("home-capture-ws-none").assertTextEquals("独立事项")
        compose.onNodeWithTag("home-capture-workspace-scrollbar").assertExists()

        // 验证鼠标悬停/移出未选中工作区选项时底色平滑过渡且全程无发黑闪烁。
        val secondItem = compose.onNodeWithTag("home-capture-ws-${spaces[1].id}")
        fun sampleRowPixels(): Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color> {
            val pixels = secondItem.captureToImage().toPixelMap()
            val interior = pixels[(pixels.width * .92f).toInt(), pixels.height / 2]
            val topBorder = pixels[pixels.width / 2, 0]
            return interior to topBorder
        }
        val (beforeHover, _) = sampleRowPixels()
        var afterHover = beforeHover
        compose.mainClock.autoAdvance = false
        try {
            secondItem.performMouseInput { enter(center) }
            repeat(8) { step ->
                compose.mainClock.advanceTimeBy(16)
                val (interior, border) = sampleRowPixels()
                assertTrue(
                    "悬停进入第 ${step + 1} 帧底色不得发黑闪烁：interior=$interior",
                    minOf(interior.red, interior.green, interior.blue) >= .90f,
                )
                assertTrue(
                    "悬停进入第 ${step + 1} 帧边框不得发黑闪烁：border=$border",
                    minOf(border.red, border.green, border.blue) >= .80f,
                )
            }
            compose.mainClock.advanceTimeBy(120)
            afterHover = sampleRowPixels().first
            assertTrue(
                "悬停工作区选项后底色应产生可见视觉变化：before=$beforeHover after=$afterHover",
                maxOf(abs(beforeHover.red - afterHover.red), abs(beforeHover.green - afterHover.green), abs(beforeHover.blue - afterHover.blue)) > .018f,
            )

            secondItem.performMouseInput { exit(Offset(-12f, -12f)) }
            repeat(8) { step ->
                compose.mainClock.advanceTimeBy(16)
                val (interior, border) = sampleRowPixels()
                assertTrue(
                    "悬停移出第 ${step + 1} 帧底色不得发黑闪烁：interior=$interior",
                    minOf(interior.red, interior.green, interior.blue) >= .90f,
                )
                assertTrue(
                    "悬停移出第 ${step + 1} 帧边框不得发黑闪烁：border=$border",
                    minOf(border.red, border.green, border.blue) >= .80f,
                )
            }
            compose.mainClock.advanceTimeBy(120)
            val (afterExit, _) = sampleRowPixels()
            assertTrue(
                "悬停移出结束后应平滑恢复原底色：before=$beforeHover afterExit=$afterExit",
                maxOf(abs(beforeHover.red - afterExit.red), abs(beforeHover.green - afterExit.green), abs(beforeHover.blue - afterExit.blue)) < .008f,
            )
        } finally {
            compose.mainClock.autoAdvance = true
        }
        secondItem.performMouseInput { enter(center) }
        compose.waitForIdle()
        screenshot("capture-workspace-hover", tag = "home-capture-workspace-popover")

        // 验证工作区数量大于 4 时区域限高为 4 项，并支持通过滚轮滚动到底部选择第 6 项。
        val listNode = compose.onNodeWithTag("home-capture-workspace-list")
        val listBounds = listNode.fetchSemanticsNode().boundsInRoot
        assertTrue("工作区列表最多同屏显示 4 项：height=${listBounds.height}",
            listBounds.height <= 162f * compose.density.density)
        val firstTag = "home-capture-ws-${spaces[0].id}"
        val sixthTag = "home-capture-ws-${spaces[5].id}"
        val firstBefore = compose.onNodeWithTag(firstTag).fetchSemanticsNode().boundsInRoot
        listNode.performMouseInput {
            moveTo(center)
            repeat(5) {
                scroll(1f)
                advanceEventTime(32)
            }
        }
        compose.waitForIdle()
        screenshot("capture-workspace-scrolled", tag = "home-capture-workspace-popover")
        val firstAfter = compose.onNodeWithTag(firstTag).fetchSemanticsNode().boundsInRoot
        val afterScrollBounds = compose.onNodeWithTag(sixthTag).fetchSemanticsNode().boundsInRoot
        assertTrue("向下滚轮后第 6 个工作区应上移进入可见区：firstBefore=$firstBefore firstAfter=$firstAfter sixthAfter=$afterScrollBounds list=$listBounds",
            afterScrollBounds.height > 0f && afterScrollBounds.top >= listBounds.top - 1f && afterScrollBounds.bottom <= listBounds.bottom + 2f)

        click(sixthTag)
        compose.onNodeWithTag("home-capture-workspace-popover").assertDoesNotExist()
        click("home-capture-submit")
        compose.waitUntil(5_000) {
            runBlocking { sources.tasks.listTasks().any { it.title == "测试工作区滚动" } }
        }
        val saved = runBlocking { sources.tasks.listTasks().first { it.title == "测试工作区滚动" } }
        assertEquals(spaces[5].id, saved.workspaceId)
    }
}

private class HomeTestOwner : LifecycleOwner, ViewModelStoreOwner {
    override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    override val viewModelStore = ViewModelStore()
}
