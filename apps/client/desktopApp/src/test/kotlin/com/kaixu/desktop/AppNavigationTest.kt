package com.kaixu.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.graphics.asSkiaBitmap
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
