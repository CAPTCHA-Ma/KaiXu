package com.kaixu.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
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
import com.kaixu.desktop.presentation.ui.scheduling.formatEventEditTime
import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.domain.model.scheduling.WorkSession
import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.model.workspaces.WorkspaceKind
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

class LocalScheduleScreenTest {
    @get:Rule val compose = createComposeRule()
    private val owner = ScheduleTestOwner()
    private val visible = mutableStateOf(true)
    private val directory = Files.createTempDirectory("kaixu-schedule-ui").toFile()
    private val database = openDesktopDatabase(directory.resolve("test.db"))
    private val sources = LocalDataSources(database)
    private val zone = ZoneId.of("Asia/Shanghai")

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

    private fun clickTag(tag: String, scroll: Boolean = false) {
        val node = compose.onNodeWithTag(tag)
        if (scroll) node.performScrollTo()
        node.performSemanticsAction(SemanticsActions.OnClick) { it() }
    }

    private fun click(text: String) {
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick) { it() }
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun startApp() {
        compose.setContent {
            if (visible.value) {
                CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalViewModelStoreOwner provides owner) {
                    Box(Modifier.fillMaxSize()) {
                        DesktopApp(AppGraph(sources.tasks, sources.fixedEvents), localDataSources = sources)
                    }
                }
            }
        }
    }

    private fun at(hour: Int): Long =
        LocalDate.now(zone).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun recurringOccurrenceEditsItsSelectedDateAndPreservesExceptionFields() {
        val today = LocalDate.now(zone)
        val first = today.minusWeeks(1).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val (series, exception) = runBlocking {
            val workspace = sources.workspaces.createWorkspace(WorkspaceKind.COURSE, "课程")
            val event = sources.fixedEvents.createEvent(FixedEvent(
                newLocalId(), "系列课程", first, first + 3_600_000L,
                workspaceId = workspace.id, reminderOffsetMinutes = 5,
                repeatWeekdays = setOf(today.dayOfWeek.value), repeatUntil = today.plusWeeks(1).toString(),
            ))
            val override = sources.fixedEvents.createEvent(FixedEvent(
                newLocalId(), "本次课程", at(10), at(11), description = "本次说明",
                workspaceId = workspace.id, reminderOffsetMinutes = 30,
                seriesId = event.id, originalDate = today.toString(), isOverride = true,
            ))
            event to override
        }
        startApp()
        clickTag("nav-schedule")
        val occurrenceTag = "schedule-entry-${series.id}@$today"
        awaitTag(occurrenceTag)
        clickTag(occurrenceTag)
        awaitTag("event-editor-title")
        compose.onNodeWithTag("event-editor-title").assertTextContains("本次课程")
        compose.onNodeWithTag("event-editor-description").assertTextContains("本次说明")
        compose.onNodeWithTag("event-editor-start").assertTextContains(formatEventEditTime(at(10)))
        compose.onNodeWithTag("event-editor-reminder").assertTextEquals("站内提醒：提前 30 分钟")
        compose.onNodeWithTag("event-editor-title").performTextReplacement("更新本次课程")
        clickTag("event-editor-save")
        compose.waitUntil(5_000) {
            runBlocking { sources.fixedEvents.findEventById(exception.id)?.title == "更新本次课程" }
        }
        val saved = runBlocking { sources.fixedEvents.findEventById(exception.id) }
        assertEquals("本次说明", saved?.description)
        assertEquals(30, saved?.reminderOffsetMinutes)
        assertEquals("系列课程", runBlocking { sources.fixedEvents.findEventById(series.id)?.title })
    }

    @Test
    fun cancellingRecurringOccurrenceKeepsSeriesAndOtherDates() {
        val today = LocalDate.now(zone)
        val first = today.minusWeeks(1).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val series = runBlocking {
            sources.fixedEvents.createEvent(FixedEvent(
                newLocalId(), "周课程", first, first + 3_600_000L,
                repeatWeekdays = setOf(today.dayOfWeek.value), repeatUntil = today.plusWeeks(1).toString(),
            ))
        }
        startApp()
        clickTag("nav-schedule")
        val tag = "schedule-entry-${series.id}@$today"
        awaitTag(tag)
        clickTag(tag)
        awaitTag("event-editor-start")
        compose.onNodeWithTag("event-editor-start").assertTextContains(formatEventEditTime(at(9)))
        compose.onNodeWithText("修改范围").assertExists()
        clickTag("event-editor-delete")
        awaitTag("event-delete-dialog")
        click("确认删除")
        compose.waitUntil(5_000) {
            runBlocking { sources.fixedEvents.listOverridesIncludingDeleted().any {
                it.seriesId == series.id && it.originalDate == today.toString() && it.isCancelled
            } }
        }
        assertNotNull(runBlocking { sources.fixedEvents.findEventById(series.id) })
        awaitTag("schedule-empty")
    }

    @Test
    fun archivedAndSharedRecurringOccurrencesRemainReadOnly() {
        val today = LocalDate.now(zone)
        val (archived, shared) = runBlocking {
            val workspace = sources.workspaces.createWorkspace(WorkspaceKind.COURSE, "只读课程")
            val archivedEvent = sources.fixedEvents.createEvent(FixedEvent(
                newLocalId(), "归档重复课程", at(9), at(10), workspaceId = workspace.id,
                repeatWeekdays = setOf(today.dayOfWeek.value), repeatUntil = today.plusWeeks(1).toString(),
            ))
            sources.workspaces.archiveWorkspace(workspace.id)
            val active = sources.workspaces.createWorkspace(WorkspaceKind.PROJECT, "共享项目")
            val sharedEvent = sources.fixedEvents.createEvent(FixedEvent(
                newLocalId(), "共享重复课程", at(10), at(11), workspaceId = active.id, createdBy = "bob",
                repeatWeekdays = setOf(today.dayOfWeek.value), repeatUntil = today.plusWeeks(1).toString(),
            ))
            archivedEvent to sharedEvent
        }
        startApp()
        clickTag("nav-schedule")
        val archivedTag = "schedule-entry-${archived.id}@$today"
        awaitTag(archivedTag)
        clickTag(archivedTag)
        awaitTag("schedule-occurrence-read-only")
        compose.onNodeWithText("此课次只读。", substring = true).assertExists()
        compose.onNodeWithTag("event-editor-dialog").assertDoesNotExist()
        click("关闭")
        val sharedTag = "schedule-entry-${shared.id}@$today"
        awaitTag(sharedTag)
        clickTag(sharedTag)
        awaitTag("schedule-occurrence-read-only")
        compose.onNodeWithTag("event-editor-dialog").assertDoesNotExist()
        assertNotNull(runBlocking { sources.fixedEvents.findEventById(archived.id) })
    }

    @Test
    fun scheduleAggregatesTypesFiltersByWorkspaceAndOpensDetails() {
        val fixture = runBlocking {
            val active = sources.workspaces.createWorkspace(WorkspaceKind.PROJECT, "进行中项目")
            val archived = sources.workspaces.createWorkspace(WorkspaceKind.COURSE, "归档课程")
            sources.workspaces.archiveWorkspace(archived.id)
            val deadlineTask = sources.tasks.createTask(Task(
                newLocalId(), "本周报告", workspaceId = active.id,
                deadlineDate = LocalDate.now(zone).toString(),
            ))
            val ownEvent = sources.fixedEvents.createEvent(FixedEvent(
                newLocalId(), "我的课程", at(9), at(10), workspaceId = active.id,
            ))
            val sharedEvent = sources.fixedEvents.createEvent(FixedEvent(
                newLocalId(), "共享组会", at(10), at(11), workspaceId = active.id, createdBy = "bob",
            ))
            val archivedEvent = sources.fixedEvents.createEvent(FixedEvent(
                newLocalId(), "归档安排", at(11), at(12), workspaceId = archived.id, createdBy = "bob",
            ))
            val session = sources.workSessions.createWorkSession(WorkSession(
                newLocalId(), deadlineTask.id, at(12), at(13), ownerUserId = LOCAL_ACTOR_ID, note = "专注复习",
            ))
            ScheduleFixture(active.id, archived.id, deadlineTask, ownEvent, sharedEvent, archivedEvent, session)
        }

        startApp()
        clickTag("nav-schedule")
        awaitTag("schedule-create")

        compose.onNodeWithTag("schedule-entry-${fixture.ownEvent.id}").assertTextContains("固定事件", substring = true)
        compose.onNodeWithTag("schedule-entry-${fixture.sharedEvent.id}").assertTextContains("共享事件", substring = true)
        compose.onNodeWithTag("schedule-entry-${fixture.session.id}")
            .assertTextContains("我的工作时段（仅我）", substring = true)
        compose.onNodeWithTag("schedule-deadline-${fixture.deadlineTask.id}")
            .assertTextContains("截止要求", substring = true)
        compose.onNodeWithText("截止要求（不进入执行时间）").assertExists()

        clickTag("schedule-mode-week")
        awaitTag("schedule-day-${LocalDate.now(zone)}")
        compose.onNodeWithTag("schedule-entry-${fixture.ownEvent.id}").assertExists()
        compose.onNodeWithTag("schedule-deadline-${fixture.deadlineTask.id}").assertExists()
        clickTag("schedule-mode-day")
        awaitTag("schedule-entry-${fixture.ownEvent.id}")

        clickTag("schedule-filter-${fixture.activeWorkspaceId}")
        awaitTag("schedule-entry-${fixture.ownEvent.id}")
        compose.onNodeWithTag("schedule-entry-${fixture.sharedEvent.id}").assertExists()
        compose.onNodeWithTag("schedule-entry-${fixture.archivedEvent.id}").assertDoesNotExist()
        compose.onNodeWithTag("schedule-deadline-${fixture.deadlineTask.id}").assertExists()

        clickTag("schedule-filter-${fixture.archivedWorkspaceId}")
        awaitTag("schedule-entry-${fixture.archivedEvent.id}")
        compose.onNodeWithTag("schedule-entry-${fixture.archivedEvent.id}").assertTextContains("已归档", substring = true)
        compose.onNodeWithTag("schedule-entry-${fixture.ownEvent.id}").assertDoesNotExist()
        compose.onNodeWithTag("schedule-deadline-${fixture.deadlineTask.id}").assertDoesNotExist()

        clickTag("schedule-filter-all")
        awaitTag("schedule-entry-${fixture.ownEvent.id}")

        clickTag("schedule-entry-${fixture.ownEvent.id}")
        awaitTag("record-title")
        compose.onNodeWithTag("record-title").assertTextEquals("我的课程")
        compose.onNodeWithText("记录类型").assertExists()
        compose.onNodeWithText("执行时间").assertExists()
        click("← 返回列表")

        clickTag("schedule-deadline-${fixture.deadlineTask.id}")
        awaitTag("record-title")
        compose.onNodeWithTag("record-title").assertTextEquals("本周报告")
        compose.onNodeWithText("截止要求").assertExists()
        click("← 返回列表")

        clickTag("schedule-entry-${fixture.session.id}")
        awaitTag("record-title")
        compose.onNodeWithTag("record-title").assertTextEquals("我的工作时段")
        compose.onNodeWithText("执行时间").assertExists()
        compose.onNodeWithText("截止要求").assertExists()
        compose.onNodeWithText("本周报告").assertExists()
    }
}

private data class ScheduleFixture(
    val activeWorkspaceId: String,
    val archivedWorkspaceId: String,
    val deadlineTask: Task,
    val ownEvent: FixedEvent,
    val sharedEvent: FixedEvent,
    val archivedEvent: FixedEvent,
    val session: WorkSession,
)

private class ScheduleTestOwner : LifecycleOwner, ViewModelStoreOwner {
    override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    override val viewModelStore = ViewModelStore()
}
