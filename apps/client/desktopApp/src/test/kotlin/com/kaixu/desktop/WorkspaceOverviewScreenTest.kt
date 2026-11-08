package com.kaixu.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.kaixu.app.di.AppGraph
import com.kaixu.data.datasource.local.LocalDataSources
import com.kaixu.data.datasource.local.database.openDesktopDatabase
import com.kaixu.data.datasource.local.entity.LOCAL_ACTOR_ID
import com.kaixu.data.datasource.local.entity.newLocalId
import com.kaixu.desktop.app.DesktopApp
import com.kaixu.desktop.presentation.ui.records.formatEventEditTime
import com.kaixu.domain.model.resources.ResourceKind
import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.domain.model.scheduling.WorkSession
import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.model.workspaces.WorkspaceKind
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class WorkspaceOverviewScreenTest {
    @get:Rule val compose = createComposeRule()
    private val owner = OverviewTestOwner()
    private val visible = mutableStateOf(true)
    private val directory = Files.createTempDirectory("kaixu-overview-ui").toFile()
    private val database = openDesktopDatabase(directory.resolve("test.db"))
    private val sources = LocalDataSources(database)

    @After
    fun close() {
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

    private fun clickTag(tag: String, scroll: Boolean = false) {
        val node = compose.onNodeWithTag(tag)
        if (scroll) node.performScrollTo()
        node.performSemanticsAction(SemanticsActions.OnClick) { it() }
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitEnabled(text: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitTagText(tag: String, text: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag(tag) and hasText(text)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun detailScrollPosition(): Float =
        compose.onNodeWithTag("workspace-detail-scroll").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun screenshot(name: String) {
        val image = compose.onRoot().captureToImage()
        val output = File("build/reports/workspaces/$name.png")
        output.parentFile.mkdirs()
        Image.makeFromBitmap(image.asSkiaBitmap()).use {
            output.writeBytes(requireNotNull(it.encodeToData()).bytes)
        }
    }

    private fun startApp() {
        compose.setContent {
            if (visible.value) {
                CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalViewModelStoreOwner provides owner) {
                    Box(Modifier.fillMaxSize()) {
                        DesktopApp(AppGraph(sources.tasks), localDataSources = sources)
                    }
                }
            }
        }
    }

    @Test
    fun overviewAndGlobalEntryReadOriginalRecordsAndKeepFiltersAfterReturning() {
        val fixture = runBlocking {
            val course = sources.workspaces.createWorkspace(WorkspaceKind.COURSE, "数据库")
            val project = sources.workspaces.createWorkspace(WorkspaceKind.PROJECT, "其他项目")
            val task = sources.tasks.createTask(Task(
                newLocalId(), "同一任务", description = "原记录说明", workspaceId = course.id,
            ))
            val datedTask = sources.tasks.createTask(Task(
                newLocalId(), "有截止任务", workspaceId = course.id,
                deadlineDate = LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(2).toString(),
            ))
            val otherTask = sources.tasks.createTask(Task(newLocalId(), "其他项目任务", workspaceId = project.id))
            val independentTask = sources.tasks.createTask(Task(newLocalId(), "独立任务"))
            val start = Instant.now().toEpochMilli() + 60_000
            val event = sources.fixedEvents.createEvent(FixedEvent(
                newLocalId(), "数据库研讨", start, start + 3_600_000,
                description = "教室 A", workspaceId = course.id,
            ))
            val otherEvent = sources.fixedEvents.createEvent(FixedEvent(
                newLocalId(), "其他项目日程", start, start + 60_000, workspaceId = project.id,
            ))
            val independentEvent = sources.fixedEvents.createEvent(FixedEvent(
                newLocalId(), "独立日程", start, start + 60_000,
            ))
            val ownSession = sources.workSessions.createWorkSession(WorkSession(
                newLocalId(), task.id, start, start + 60_000, ownerUserId = LOCAL_ACTOR_ID, note = "我的复习计划",
            ))
            val anotherOwnersSession = sources.workSessions.createWorkSession(WorkSession(
                newLocalId(), task.id, start, start + 60_000, ownerUserId = newLocalId(), note = "其他人的私人记录",
            ))
            val otherWorkspaceSession = sources.workSessions.createWorkSession(WorkSession(
                newLocalId(), otherTask.id, start, start + 60_000,
            ))
            val independentSession = sources.workSessions.createWorkSession(WorkSession(
                newLocalId(), independentTask.id, start, start + 60_000,
            ))
            OverviewFixture(
                course.id, task, datedTask.id, otherTask.id, independentTask.id, event, ownSession,
                listOf(anotherOwnersSession.id, otherWorkspaceSession.id, independentSession.id),
                listOf(otherEvent.id, independentEvent.id),
            )
        }
        startApp()
        awaitEnabled(fixture.task.title)
        click(fixture.task.title)
        awaitTag("record-title")
        compose.onNodeWithTag("record-title").assertTextEquals(fixture.task.title)
        compose.onNodeWithText("原记录说明").assertExists()
        compose.onNodeWithText("数据库").assertExists()
        click("← 返回列表")
        clickTag("nav-workspaces")
        awaitEnabled("新建工作区")
        compose.onNodeWithTag("workspace-search").performTextInput("数据库")
        clickTag("workspace-card-${fixture.courseId}")
        awaitTag("overview-task-${fixture.task.id}")
        compose.onNodeWithTag("overview-task-${fixture.otherTaskId}").assertDoesNotExist()
        compose.onNodeWithTag("overview-task-${fixture.independentTaskId}").assertDoesNotExist()
        fixture.hiddenEventIds.forEach { compose.onNodeWithTag("overview-event-$it").assertDoesNotExist() }
        fixture.hiddenSessionIds.forEach { compose.onNodeWithTag("overview-session-$it").assertDoesNotExist() }
        clickTag("overview-status-TODO", scroll = true)
        awaitTag("overview-deadline-NO_DEADLINE")
        clickTag("overview-deadline-NO_DEADLINE", scroll = true)
        awaitTag("overview-task-${fixture.task.id}")
        compose.onNodeWithTag("overview-task-${fixture.datedTaskId}").assertDoesNotExist()
        clickTag("overview-task-${fixture.task.id}", scroll = true)
        awaitTag("record-title")
        compose.onNodeWithTag("record-title").assertTextEquals(fixture.task.title)
        clickTag("edit-record")
        compose.onNodeWithTag("task-edit-title").performTextReplacement("已更新同一任务")
        compose.onNodeWithTag("task-edit-description").performTextReplacement("更新后的原记录说明")
        clickTag("task-edit-save")
        awaitTagText("record-title", "已更新同一任务")
        click("← 返回列表")
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag("overview-task-${fixture.task.id}") and hasText("已更新同一任务"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("overview-status-TODO").assertIsSelected()
        compose.onNodeWithTag("overview-deadline-NO_DEADLINE").assertIsSelected()
        compose.onNodeWithTag("overview-task-${fixture.datedTaskId}").assertDoesNotExist()
        compose.onNodeWithText("本地概览").performScrollTo()
        screenshot("overview")
        clickTag("overview-event-${fixture.event.id}", scroll = true)
        awaitTag("record-title")
        compose.onNodeWithTag("record-title").assertTextEquals("数据库研讨")
        compose.onNodeWithText("教室 A").assertExists()
        clickTag("edit-record")
        compose.onNodeWithTag("event-edit-title").performTextReplacement("已更新数据库研讨")
        compose.onNodeWithTag("event-edit-end").performTextReplacement(formatEventEditTime(fixture.event.startsAt))
        click("保存")
        awaitTag("event-edit-error")
        assertEquals(fixture.event, runBlocking { sources.fixedEvents.findEventById(fixture.event.id) })
        compose.onNodeWithTag("event-edit-end").performTextReplacement(formatEventEditTime(fixture.event.endsAt))
        click("保存")
        awaitTag("event-conflict-dialog")
        assertEquals(fixture.event, runBlocking { sources.fixedEvents.findEventById(fixture.event.id) })
        click("仍然保存")
        awaitTagText("record-title", "已更新数据库研讨")
        click("← 返回列表")
        awaitTag("overview-session-${fixture.ownSession.id}")
        awaitEnabled("刷新概览")
        compose.onNodeWithTag("overview-session-${fixture.ownSession.id}").performScrollTo()
        compose.waitForIdle()
        val scheduleScrollPosition = detailScrollPosition()
        assertTrue("日程区域应处于向下滚动后的位置", scheduleScrollPosition > 0f)
        screenshot("overview-schedule")
        clickTag("overview-session-${fixture.ownSession.id}")
        awaitTag("record-title")
        compose.onNodeWithTag("record-title").assertTextEquals("我的工作时段")
        compose.onNodeWithText("我的复习计划").assertExists()
        compose.onNodeWithText("已更新同一任务").assertExists()
        compose.onNodeWithText("其他人的私人记录").assertDoesNotExist()
        click("← 返回列表")
        awaitTag("overview-task-${fixture.task.id}")
        awaitEnabled("刷新概览")
        compose.waitForIdle()
        assertEquals(
            "从工作时段详情返回后保留概览滚动位置",
            scheduleScrollPosition.toDouble(), detailScrollPosition().toDouble(), 1.0,
        )
        compose.onNodeWithTag("overview-status-TODO").assertIsSelected()
        compose.onNodeWithTag("overview-deadline-NO_DEADLINE").assertIsSelected()
        click("← 返回工作区")
        compose.onNodeWithTag("workspace-search").assertTextContains("数据库")
        clickTag("nav-task-editor")
        awaitEnabled("已更新同一任务")
        compose.onNodeWithText(fixture.task.title).assertDoesNotExist()
        click("已更新同一任务")
        awaitTag("record-title")
        compose.onNodeWithTag("record-title").assertTextEquals("已更新同一任务")
        compose.onNodeWithText("更新后的原记录说明").assertExists()
        compose.onNodeWithText("数据库").assertExists()
        assertEquals(4, runBlocking { sources.tasks.listTasks().size })
        assertEquals(fixture.courseId, runBlocking { sources.tasks.findTaskById(fixture.task.id)?.workspaceId })
        assertEquals("已更新同一任务", runBlocking { sources.tasks.findTaskById(fixture.task.id)?.title })
        assertEquals(null, runBlocking { sources.tasks.findTaskById(fixture.independentTaskId)?.workspaceId })
        click("← 返回列表")
        clickTag("nav-schedule")
        awaitEnabled("已更新数据库研讨")
        clickTag("schedule-event-${fixture.event.id}")
        awaitTagText("record-title", "已更新数据库研讨")
        val reopened = openDesktopDatabase(directory.resolve("test.db"))
        try {
            val persisted = LocalDataSources(reopened)
            assertEquals(
                fixture.event.copy(title = "已更新数据库研讨"),
                runBlocking { persisted.fixedEvents.findEventById(fixture.event.id) },
            )
            assertEquals(
                fixture.task.copy(title = "已更新同一任务", description = "更新后的原记录说明"),
                runBlocking { persisted.tasks.findTaskById(fixture.task.id) },
            )
        } finally {
            reopened.close()
        }
    }

    @Test
    fun workspaceResourcesSaveOriginalRecordsAndKeepListContext() {
        val course = runBlocking {
            val local = sources.workspaces.createWorkspace(WorkspaceKind.COURSE, "资料课程")
            val other = sources.workspaces.createWorkspace(WorkspaceKind.PROJECT, "其他工作区")
            repeat(3) { index ->
                sources.resources.createResource(local.id, "原有资料 ${index + 1}", null, ResourceKind.TEXT, textContent = "正文")
            }
            sources.resources.createResource(other.id, "其他工作区资料", null, ResourceKind.TEXT, textContent = "隔离")
            local
        }
        startApp()
        clickTag("nav-workspaces")
        awaitEnabled("新建工作区")
        clickTag("workspace-card-${course.id}")
        awaitEnabled("刷新概览")
        awaitTagText("resource-count", "资料 · 3")
        compose.onNodeWithTag("workspace-detail-scroll")
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 10_000f) }
        compose.onNodeWithText("全部资料").assertIsDisplayed()
        click("全部资料")
        awaitTag("resource-search")
        compose.onNodeWithTag("resource-search").performTextInput("复验")
        clickTag("resource-create", scroll = true)
        compose.onNodeWithTag("resource-name").performTextInput("复验资料")
        compose.onNodeWithTag("resource-url").performTextInput("file:///local.txt")
        clickTag("resource-save")
        awaitTag("resource-save-error")
        assertEquals(3, runBlocking { sources.resources.listResourcesByWorkspace(course.id).size })
        compose.onNodeWithTag("resource-url").performTextReplacement("https://example.org/notes")
        clickTag("resource-save")
        awaitTag("resource-record-url")
        compose.onNodeWithTag("resource-record-url").assertTextEquals("https://example.org/notes")
        val saved = runBlocking { sources.resources.listResourcesByWorkspace(course.id).single { it.name == "复验资料" } }

        clickTag("resource-edit")
        clickTag("resource-kind-TEXT")
        compose.onNodeWithTag("resource-name").performTextReplacement("复验资料已更新")
        compose.onNodeWithTag("resource-text").performTextInput("编辑后的资料正文")
        clickTag("resource-save")
        awaitTag("resource-record-text")
        compose.onNodeWithTag("resource-record-text").assertTextEquals("编辑后的资料正文")
        click("← 返回资料列表")
        awaitTagText("resource-${saved.id}", "复验资料已更新")
        compose.onNodeWithTag("resource-search").assertTextContains("复验")
        compose.onNodeWithTag("resource-${saved.id}").assertTextContains("复验资料已更新")
        compose.onNodeWithText("其他工作区资料").assertDoesNotExist()
        click("概览")
        awaitTagText("resource-count", "资料 · 4")
        val records = runBlocking { sources.resources.listResourcesByWorkspace(course.id) }
        assertEquals(4, records.size)
        compose.onNodeWithTag("resource-count").assertTextEquals("资料 · 4")
        compose.onNodeWithTag("resource-${records.last().id}").assertDoesNotExist()
        clickTag("resource-${saved.id}", scroll = true)
        awaitTag("resource-record-text")
        compose.onNodeWithTag("resource-record-title").assertTextEquals("复验资料已更新")
        screenshot("overview-resource")
        val reopened = openDesktopDatabase(directory.resolve("test.db"))
        try {
            assertEquals(records.single { it.id == saved.id },
                runBlocking { LocalDataSources(reopened).resources.findResourceById(saved.id) })
        } finally {
            reopened.close()
        }
    }
}

private data class OverviewFixture(
    val courseId: String,
    val task: Task,
    val datedTaskId: String,
    val otherTaskId: String,
    val independentTaskId: String,
    val event: FixedEvent,
    val ownSession: WorkSession,
    val hiddenSessionIds: List<String>,
    val hiddenEventIds: List<String>,
)

private class OverviewTestOwner : LifecycleOwner, ViewModelStoreOwner {
    override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    override val viewModelStore = ViewModelStore()
}
