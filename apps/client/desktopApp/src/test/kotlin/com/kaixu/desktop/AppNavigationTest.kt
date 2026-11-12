package com.kaixu.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.kaixu.desktop.app.DesktopApp
import com.kaixu.app.di.AppGraph
import com.kaixu.data.datasource.TaskDataSource
import com.kaixu.data.datasource.local.LocalDataSources
import com.kaixu.data.datasource.local.database.openDesktopDatabase
import com.kaixu.domain.model.tasks.Task
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.jetbrains.skia.Image
import java.io.File

/** 用实际导航和页面测试往返后的草稿与失败状态，不复制导航逻辑。 */
class AppNavigationTest {
    @get:Rule
    val compose = createComposeRule()
    private val owner = TestHostOwner()
    private val showApp = mutableStateOf(true)
    private val directory = Files.createTempDirectory("kaixu-navigation-test").toFile()
    private val database = openDesktopDatabase(directory.resolve("navigation.db"))
    private val source = LocalDataSources(database).tasks
    private var failNextSave = false
    private val graph = AppGraph(object : TaskDataSource by source {
        override suspend fun createTask(value: Task): Task {
            if (failNextSave) {
                failNextSave = false
                error("测试保存失败")
            }
            return source.createTask(value)
        }
    })

    @After
    fun clearHost() {
        try {
            compose.runOnIdle { showApp.value = false }
            compose.waitForIdle()
            compose.runOnUiThread {
                owner.lifecycle.currentState = Lifecycle.State.DESTROYED
                owner.viewModelStore.clear()
            }
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }

    private fun startApp(fullNavigation: Boolean = false) {
        // UI 测试没有真实 Window，显式提供窗口通常提供的宿主能力。
        compose.setContent {
            if (showApp.value) {
                CompositionLocalProvider(
                    LocalLifecycleOwner provides owner,
                    LocalViewModelStoreOwner provides owner,
                ) {
                    DesktopApp(graph = graph, localDataSources = if (fullNavigation) LocalDataSources(database) else null)
                }
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("任务标题") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("模拟下一次保存失败").assertDoesNotExist()
        compose.onNodeWithText("任务保存在本机，重启后可继续查看").assertExists()
    }

    @Test
    fun hoveringChromePreviewsWithoutNavigatingAndClickCommitsThePageTitle() {
        startApp(fullNavigation = true)
        val caption = compose.onNodeWithTag("titlebar-caption")
        fun foregroundInk(): Int {
            val pixels = caption.captureToImage().toPixelMap()
            return (0 until pixels.width).sumOf { x ->
                (0 until pixels.height).count { y ->
                    val color = pixels[x, y]
                    maxOf(color.red, color.green, color.blue) < .25f
                }
            }
        }
        caption.assertContentDescriptionEquals("任务")
        // 禁用的历史按钮不产生预览。
        compose.onNodeWithTag("titlebar-back").performMouseInput { enter(center) }
        caption.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "当前页面"))
        caption.performMouseInput { moveTo(center) }

        compose.onNodeWithTag("titlebar-settings").performMouseInput { moveTo(center) }
        caption.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "预览：设置"))
        caption.assertContentDescriptionEquals("任务")
        compose.onNodeWithText("任务标题").assertExists()
        screenshot("caption-preview-settings")
        caption.performMouseInput { moveTo(center) }
        caption.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "当前页面"))
        caption.assertContentDescriptionEquals("任务")

        compose.onNodeWithTag("nav-workspaces").performMouseInput { moveTo(center) }
        caption.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "预览：工作区"))
        clickTag("nav-workspaces")
        caption.assertContentDescriptionEquals("工作区")
        caption.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "当前页面"))
        screenshot("caption-committed-workspaces")
        val workspaceInk = foregroundInk()
        caption.performMouseInput { moveTo(center) }
        compose.onNodeWithTag("titlebar-settings").performMouseInput { moveTo(center) }
        screenshot("caption-preview-settings-from-workspaces")
        caption.performMouseInput { moveTo(center) }

        compose.onNodeWithTag("titlebar-back").performMouseInput { moveTo(center) }
        caption.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "预览：任务"))
        clickTag("titlebar-back")
        caption.assertContentDescriptionEquals("任务")
        caption.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "当前页面"))
        caption.performMouseInput { moveTo(center) }
        // 用独立显示时的字形墨量检查较长的新标题，防止右侧再次被动画裁剪。
        compose.onNodeWithTag("nav-workspaces").performMouseInput { moveTo(center) }
        val previewInk = foregroundInk()
        assertTrue("预览应完整显示工作区三个字", previewInk >= workspaceInk * .9f)
        screenshot("caption-preview-workspaces")
        caption.performMouseInput { moveTo(center) }
    }

    @Test
    fun quickHoverChangesRollVerticallyAndPreserveTheFadedPageTitle() {
        startApp(fullNavigation = true)
        val caption = compose.onNodeWithTag("titlebar-caption")
        fun pixels(): ByteArray = Image.makeFromBitmap(caption.captureToImage().asSkiaBitmap()).use {
            requireNotNull(it.encodeToData()).bytes
        }
        val restingPixels = pixels()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("titlebar-settings").performMouseInput { enter(center) }
            compose.mainClock.advanceTimeBy(400)
            val heldPixels = pixels()
            val before = caption.captureToImage().toPixelMap()
            caption.performMouseInput { moveTo(center) }
            compose.mainClock.advanceTimeBy(220)
            assertArrayEquals("跨越按钮空隙时不应回弹", heldPixels, pixels())

            compose.onNodeWithTag("nav-schedule").performMouseInput { moveTo(center) }
            compose.mainClock.advanceTimeBy(96)
            val rolling = caption.captureToImage().toPixelMap()
            for (x in 0 until before.width / 2 - 16) {
                for (y in 0 until before.height) {
                    org.junit.Assert.assertEquals("左侧页面标题应保持淡出状态", before[x, y], rolling[x, y])
                }
            }
            fun inkRows(pixels: androidx.compose.ui.graphics.PixelMap): List<Int> =
                (0 until pixels.height).filter { y ->
                    (pixels.width / 2 until pixels.width).any { x ->
                        val color = pixels[x, y]
                        maxOf(color.red, color.green, color.blue) < .9f
                    }
                }
            // 字体轮廓和协程首帧调度会随平台变化，检查多个动画中间帧，
            // 并用两个标题各自静止时的字形边界作参考，避免依赖单帧的像素阈值。
            val rollingFrames = mutableListOf(rolling)
            screenshot("caption-vertical-roll")
            repeat(2) { index ->
                compose.mainClock.advanceTimeBy(48)
                rollingFrames.add(caption.captureToImage().toPixelMap())
                screenshot("caption-vertical-roll-${index + 1}")
            }
            compose.mainClock.advanceTimeBy(300)
            val restingRows = inkRows(before) + inkRows(caption.captureToImage().toPixelMap())
            assertTrue("静止标题应包含可见文字", restingRows.isNotEmpty())
            val rollingRows = rollingFrames.map(::inkRows).filter { it.isNotEmpty() }
            assertTrue("新文字应从上方进入", rollingRows.any { it.first() < restingRows.min() })
            assertTrue("原文字应向下退出", rollingRows.any { it.last() > restingRows.max() })
            caption.assertContentDescriptionEquals("任务")

            // 连续打断纵向替换，再在途中点击；旧预览不得覆盖实际页面。
            compose.onNodeWithTag("nav-workspaces").performMouseInput { moveTo(center) }
            compose.mainClock.advanceTimeBy(64)
            compose.onNodeWithTag("nav-schedule").performMouseInput { moveTo(center) }
            compose.mainClock.advanceTimeBy(64)
            clickTag("nav-schedule")
            compose.mainClock.advanceTimeBy(700)
            caption.assertContentDescriptionEquals("日程")
            caption.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "当前页面"))

            clickTag("titlebar-back")
            compose.mainClock.advanceTimeBy(700)
            caption.performMouseInput { moveTo(center) }
            compose.onNodeWithTag("titlebar-settings").performMouseInput { moveTo(center) }
            compose.mainClock.advanceTimeBy(400)
            caption.performMouseInput { moveTo(center) }
            compose.mainClock.advanceTimeBy(700)
            assertArrayEquals("离开超过宽限时间后应恢复页面标题", restingPixels, pixels())
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun longAndShortPreviewTitlesFinishAlignmentWithTheVerticalTransition() {
        startApp(fullNavigation = true)
        val caption = compose.onNodeWithTag("titlebar-caption")
        fun pixels(): ByteArray = Image.makeFromBitmap(caption.captureToImage().asSkiaBitmap()).use {
            requireNotNull(it.encodeToData()).bytes
        }
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("titlebar-settings").performMouseInput { enter(center) }
            compose.mainClock.advanceTimeBy(400)
            for (tag in listOf("nav-workspaces", "titlebar-settings", "nav-workspaces", "nav-schedule")) {
                compose.onNodeWithTag(tag).performMouseInput { moveTo(center) }
                // 给鼠标事件、重组和动画首帧留出调度余量；仍短于旧的两段 220 ms 动画。
                compose.mainClock.advanceTimeBy(336)
                val transitionFinished = pixels()
                compose.mainClock.advanceTimeBy(400)
                assertArrayEquals("长短标题应在纵向切换后完成对齐：$tag", transitionFinished, pixels())
            }
            caption.performMouseInput { moveTo(center) }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun interruptedTitlePreviewRestoresItsPixelsAndClickCanCommitMidAnimation() {
        startApp(fullNavigation = true)
        val caption = compose.onNodeWithTag("titlebar-caption")
        fun pixels(): ByteArray = Image.makeFromBitmap(caption.captureToImage().asSkiaBitmap()).use {
            requireNotNull(it.encodeToData()).bytes
        }
        val restingPixels = pixels()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("titlebar-settings").performMouseInput { enter(center) }
            compose.mainClock.advanceTimeBy(160)
            assertFalse("预览动画应该已改变标题图像", restingPixels.contentEquals(pixels()))
            screenshot("caption-moving")
            // 动画中途换预览，然后移开，最终应逐像素恢复当前页面标题。
            compose.onNodeWithTag("nav-workspaces").performMouseInput { moveTo(center) }
            compose.mainClock.advanceTimeBy(80)
            caption.performMouseInput { moveTo(center) }
            compose.mainClock.advanceTimeBy(600)
            assertArrayEquals(restingPixels, pixels())
            caption.assertContentDescriptionEquals("任务")

            compose.onNodeWithTag("titlebar-settings").performMouseInput { moveTo(center) }
            compose.mainClock.advanceTimeBy(160)
            clickTag("titlebar-settings")
            compose.mainClock.advanceTimeBy(600)
            caption.assertContentDescriptionEquals("设置")
            caption.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "当前页面"))
            screenshot("caption-committed-settings")
            caption.performMouseInput { moveTo(center) }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun titleBarSettingsBackForwardPreserveTaskDraft() {
        startApp()
        compose.onNodeWithTag("titlebar-back").assertIsNotEnabled()
        compose.onNodeWithTag("titlebar-forward").assertIsNotEnabled()
        compose.onNodeWithText("任务标题").performTextInput("窗口导航草稿")
        clickTag("titlebar-settings")
        compose.onNodeWithText("设置与说明").assertExists()
        compose.onNodeWithTag("titlebar-settings").assertIsSelected()
        clickTag("titlebar-settings") // 重复点击不得再添加设置页。
        screenshot("settings")
        clickTag("titlebar-back")
        compose.onNodeWithText("任务标题").assertTextContains("窗口导航草稿")
        compose.onNodeWithTag("titlebar-back").assertIsNotEnabled()
        compose.onNodeWithTag("titlebar-forward").assertIsEnabled()
        compose.onNodeWithTag("titlebar-settings").assertIsNotSelected()
        screenshot("tasks")
        clickTag("titlebar-forward")
        compose.onNodeWithText("设置与说明").assertExists()
        compose.onNodeWithTag("titlebar-forward").assertIsNotEnabled()
        compose.onNodeWithText("返回任务").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("任务标题").assertTextContains("窗口导航草稿")
    }

    @Test
    fun globalHistoryIncludesSidebarAndNewNavigationDropsForwardBranch() {
        startApp(fullNavigation = true)
        compose.onNodeWithText("任务标题").performTextInput("保留任务草稿")
        clickTag("nav-workspaces")
        compose.onNodeWithTag("workspace-search").performTextInput("过滤条件")
        clickTag("titlebar-settings")
        compose.onNodeWithText("返回工作区").assertExists()
        clickTag("titlebar-back")
        compose.onNodeWithTag("workspace-search").assertTextContains("过滤条件")
        clickTag("titlebar-forward")
        compose.onNodeWithText("设置与说明").assertExists()
        clickTag("titlebar-back")
        clickTag("nav-schedule")
        compose.onNodeWithTag("titlebar-forward").assertIsNotEnabled()
        compose.onNodeWithText("固定事件", substring = false).assertExists()
        clickTag("titlebar-back")
        compose.onNodeWithTag("workspace-search").assertTextContains("过滤条件")
        clickTag("titlebar-back")
        compose.onNodeWithText("任务标题").assertTextContains("保留任务草稿")
        compose.onNodeWithTag("titlebar-back").assertIsNotEnabled()
    }

    @Test
    fun historyReturnsToTheExactRecordWhenDifferentTasksShareTheSameRoutePattern() {
        startApp(fullNavigation = true)
        for (title in listOf("记录甲", "记录乙")) {
            compose.onNodeWithText("任务标题").performTextInput(title)
            compose.onNodeWithText("新增任务").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("已创建：$title").fetchSemanticsNodes().isNotEmpty() }
        }
        fun assertRecord(title: String) {
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("record-title").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("record-title").assertTextEquals(title)
            compose.onNodeWithTag("nav-task-editor").assertIsSelected()
        }
        compose.onNodeWithText("记录甲", substring = false).performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertRecord("记录甲")
        clickTag("nav-task-editor")
        compose.onNodeWithText("记录乙", substring = false).performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertRecord("记录乙")
        clickTag("titlebar-back")
        clickTag("titlebar-back")
        assertRecord("记录甲")
        clickTag("titlebar-forward")
        compose.onNodeWithText("个人任务（2）").assertExists()
        clickTag("titlebar-forward")
        assertRecord("记录乙")
        compose.onNodeWithTag("titlebar-forward").assertIsNotEnabled()
    }

    private fun clickTag(tag: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
    }

    private fun screenshot(name: String) {
        val outputDirectory = System.getenv("KAIXU_SCREENSHOT_DIR") ?: return
        val bitmap = compose.onRoot().captureToImage().asSkiaBitmap()
        val output = File(outputDirectory, "titlebar-$name.png")
        output.parentFile.mkdirs()
        Image.makeFromBitmap(bitmap).use { output.writeBytes(requireNotNull(it.encodeToData()).bytes) }
    }

    @Test
    fun draftAndSavedTasksSurviveSettingsRoundTrip() {
        startApp()
        compose.onNodeWithText("任务标题").performTextInput("整理导航笔记")
        openSettingsAndReturn()
        compose.onNodeWithText("任务标题").assertTextContains("整理导航笔记")
        compose.onNodeWithText("新增任务").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("已创建：整理导航笔记").fetchSemanticsNodes().isNotEmpty()
        }
        openSettingsAndReturn()
        compose.onNodeWithText("个人任务（1）").assertExists()
        compose.onNodeWithText("整理导航笔记", substring = false).assertExists()
    }

    @Test
    fun failedSaveAndDraftSurviveSettingsRoundTripAndCanRetry() {
        startApp()
        failNextSave = true
        compose.onNodeWithText("任务标题").performTextInput("保留失败草稿")
        compose.onNodeWithText("新增任务").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("保存失败，输入已保留。请重试。").fetchSemanticsNodes().isNotEmpty()
        }
        openSettingsAndReturn()
        compose.onNodeWithText("任务标题").assertTextContains("保留失败草稿")
        compose.onNodeWithText("保存失败，输入已保留。请重试。").assertExists()
        compose.onNodeWithText("个人任务（0）").assertExists()
        compose.onNodeWithText("新增任务").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("已创建：保留失败草稿").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("个人任务（1）").assertExists()
    }

    private fun openSettingsAndReturn() {
        // 导航改变 Lifecycle，使用在 UI 线程执行的语义点击。
        compose.onNodeWithText("打开设置").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("设置与说明").assertExists()
        compose.onNodeWithText("返回任务").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("KaiXu 个人任务").assertExists()
    }
}

private class TestHostOwner : LifecycleOwner, ViewModelStoreOwner {
    override val lifecycle = LifecycleRegistry.createUnsafe(this).apply {
        currentState = Lifecycle.State.RESUMED
    }
    override val viewModelStore = ViewModelStore()
}
