package com.kaixu.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.kaixu.app.di.AppGraph
import com.kaixu.data.datasource.local.LocalDataSources
import com.kaixu.data.datasource.local.database.openDesktopDatabase
import com.kaixu.desktop.app.DesktopApp
import com.kaixu.domain.model.workspaces.WorkspaceKind
import com.kaixu.domain.model.workspaces.WorkspaceStatus
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class WorkspaceScreenTest {
    @get:Rule val compose = createComposeRule()
    private val owner = WorkspaceTestOwner()
    private val visible = mutableStateOf(true)
    private val directory = Files.createTempDirectory("kaixu-workspace-ui").toFile()
    private val file = directory.resolve("test.db")
    private val database = openDesktopDatabase(file)
    private val sources = LocalDataSources(database)

    private fun start() {
        compose.setContent {
            if (visible.value) {
                CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalViewModelStoreOwner provides owner) {
                    Box(Modifier.fillMaxSize()) {
                        DesktopApp(AppGraph(sources.tasks, sources.fixedEvents), sources.workspaces)
                    }
                }
            }
        }
        clickTag("nav-workspaces")
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("新建工作区") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    @After fun close() {
        compose.runOnUiThread {
            visible.value = false
            owner.lifecycle.currentState = Lifecycle.State.DESTROYED
            owner.viewModelStore.clear()
        }
        compose.waitForIdle()
        database.close()
        directory.deleteRecursively()
    }

    private fun click(text: String) {
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick) { it() }
    }

    private fun clickTag(tag: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick) { it() }
    }

    private fun awaitText(text: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun save() {
        click("保存")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("workspace-name").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun screenshot(name: String) {
        val image = compose.onRoot().captureToImage()
        val output = File("build/reports/workspaces/$name.png")
        output.parentFile.mkdirs()
        Image.makeFromBitmap(image.asSkiaBitmap()).use {
            output.writeBytes(requireNotNull(it.encodeToData()).bytes)
        }
    }

    @Test fun courseAndProjectManagementUseSamePersistentRecords() {
        start()
        click("新建工作区")
        compose.onNodeWithTag("workspace-name").performTextInput("数据库")
        save()
        awaitText("← 返回工作区")
        screenshot("course")
        click("创建关联项目")
        compose.onNodeWithTag("workspace-name").performTextInput("数据库课程设计")
        save()
        awaitText("数据库课程设计")
        click("设置")
        click("编辑工作区")
        compose.onNodeWithTag("workspace-description").performTextInput("小组项目")
        save()
        awaitText("小组项目")
        click("设置")
        click("归档工作区")
        click("确认归档")
        awaitText("恢复工作区")
        click("恢复工作区")
        awaitText("编辑工作区")
        click("概览")
        screenshot("project")
        click("← 返回工作区")
        awaitText("新建工作区")
        screenshot("directory")
        val saved = runBlocking { sources.workspaces.listWorkspaces() }
        assertEquals(2, saved.size)
        val project = saved.single { it.kind == WorkspaceKind.PROJECT }
        assertEquals("小组项目", project.description)
        assertEquals(WorkspaceStatus.ACTIVE, project.status)
        assertEquals(saved.single { it.kind == WorkspaceKind.COURSE }.id, project.linkedCourseId)
        // 另开连接重新读取同一文件，检查 UI 操作实际落库。
        val reopened = openDesktopDatabase(file)
        try {
            assertEquals(project, runBlocking { LocalDataSources(reopened).workspaces.findWorkspaceById(project.id) })
        } finally { reopened.close() }
    }

    @Test fun returningFromDetailsRetainsFiltersAndCancelledDraftDoesNotWrite() {
        val course = runBlocking { sources.workspaces.createWorkspace(WorkspaceKind.COURSE, "算法") }
        runBlocking { sources.workspaces.createWorkspace(WorkspaceKind.PROJECT, "其他项目") }
        start()
        compose.onNodeWithTag("workspace-search").performTextInput("算法")
        clickTag("workspace-card-${course.id}")
        awaitText("← 返回工作区")
        click("← 返回工作区")
        compose.onNodeWithTag("workspace-search").assertTextContains("算法")
        compose.onNodeWithText("其他项目").assertDoesNotExist()
        click("新建工作区")
        compose.onNodeWithTag("workspace-name").performTextInput("未保存草稿")
        click("取消")
        click("新建工作区")
        compose.onNodeWithTag("workspace-name").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        click("取消")
        assertEquals(2, runBlocking { sources.workspaces.listWorkspaces(null).size })
    }

    @Test fun longDescriptionKeepsSettingsAccessibleAndFullTextEditable() {
        val description = (1..40).joinToString("\n") { "第 $it 行：需要完整保留的课程说明。" }
        val course = runBlocking {
            sources.workspaces.createWorkspace(WorkspaceKind.COURSE, "长说明课程", description)
        }
        start()
        compose.onNodeWithTag("workspace-card-${course.id}").performClick()
        awaitText("← 返回工作区")

        // 必须先验证可见并实际点击，防止语义回调绕过被长说明挤出屏幕的设置页签。
        compose.onNodeWithText("设置").assertIsDisplayed().performClick()
        compose.onNodeWithText("编辑工作区").assertIsDisplayed().performClick()
        compose.onNodeWithTag("workspace-description").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(description)),
        )
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("概览").assertIsDisplayed().performClick()
        compose.onNodeWithText(description).assertExists()

        val body = compose.onNodeWithTag("workspace-detail-scroll")
        body.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        compose.waitForIdle()
        val range = body.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("完整说明应能在概览内容区域滚动阅读", range.maxValue() > 0f)
        assertEquals("概览能滚动至内容末尾", range.maxValue().toDouble(), range.value().toDouble(), 1.0)
        compose.onNodeWithText("设置").assertIsDisplayed().performClick()
        compose.onNodeWithText("编辑工作区").assertIsDisplayed()
        assertEquals(description, runBlocking { sources.workspaces.findWorkspaceById(course.id)?.description })
    }
}

private class WorkspaceTestOwner : LifecycleOwner, ViewModelStoreOwner {
    override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    override val viewModelStore = ViewModelStore()
}
