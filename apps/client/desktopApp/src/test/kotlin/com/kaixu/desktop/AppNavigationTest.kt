package com.kaixu.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.kaixu.desktop.app.DesktopApp
import com.kaixu.app.di.AppGraph
import org.junit.After
import org.junit.Rule
import org.junit.Test

/** 用实际导航和页面测试往返后的草稿与失败状态，不复制导航逻辑。 */
class AppNavigationTest {
    @get:Rule
    val compose = createComposeRule()
    private val owner = TestHostOwner()
    private val showApp = mutableStateOf(true)
    private val graph = AppGraph()

    @After
    fun clearHost() {
        compose.runOnIdle { showApp.value = false }
        compose.waitForIdle()
        compose.runOnUiThread {
            owner.lifecycle.currentState = Lifecycle.State.DESTROYED
            owner.viewModelStore.clear()
        }
    }

    private fun startApp() {
        // UI 测试没有真实 Window，显式提供窗口通常提供的宿主能力。
        compose.setContent {
            if (showApp.value) {
                CompositionLocalProvider(
                    LocalLifecycleOwner provides owner,
                    LocalViewModelStoreOwner provides owner,
                ) {
                    DesktopApp(graph = graph)
                }
            }
        }
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
        compose.onNodeWithText("模拟下一次保存失败").performClick()
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
