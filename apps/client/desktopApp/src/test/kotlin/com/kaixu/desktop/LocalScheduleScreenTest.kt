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
