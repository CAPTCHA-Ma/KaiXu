package com.kaixu.desktop

import com.kaixu.data.datasource.local.LocalDataSources
import com.kaixu.data.datasource.local.database.openDesktopDatabase
import com.kaixu.data.datasource.local.entity.LOCAL_ACTOR_ID
import com.kaixu.data.datasource.local.entity.newLocalId
import com.kaixu.desktop.presentation.ui.records.saveLocalTaskRecordEdit
import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.model.tasks.TaskPriority
import com.kaixu.domain.model.tasks.TaskStatus
import com.kaixu.domain.model.workspaces.WorkspaceKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files

class TaskRecordEditorTest {
    private val directory = Files.createTempDirectory("kaixu-task-record-edit").toFile()
    private val db = openDesktopDatabase(directory.resolve("local.db"))
    private val sources = LocalDataSources(db)

    @After
    fun close() {
        db.close()
        directory.deleteRecursively()
    }

    @Test
    fun sameIdEditPreservesAttributionDatePrecisionAndOtherFields() = runBlocking {
        val course = sources.workspaces.createWorkspace(WorkspaceKind.COURSE, "课程")
        val original = sources.tasks.createTask(Task(
            newLocalId(), "原标题", description = "原说明", workspaceId = course.id,
            assigneeUserId = LOCAL_ACTOR_ID, priority = TaskPriority.HIGH,
            deadlineDate = "2026-11-30", estimatedMinutes = 90, blockedNote = "等待资料",
            sourceExcerpt = "原文", sourceUrl = "https://example.com",
        ))
        val createdAt = db.taskDao().findById(original.id)!!.createdAt
        saveLocalTaskRecordEdit(original, "  编辑标题  ", "编辑说明", TaskStatus.IN_PROGRESS, sources)
        assertEquals(
            original.copy(title = "编辑标题", description = "编辑说明", status = TaskStatus.IN_PROGRESS),
            sources.tasks.findTaskById(original.id),
        )
        assertEquals(createdAt, db.taskDao().findById(original.id)!!.createdAt)
        assertEquals(1, sources.tasks.listTasksByWorkspace(course.id).size)

        val exact = sources.tasks.createTask(Task(
            newLocalId(), "时刻截止", deadlineAt = 1_900_000_123_456L,
        ))
        saveLocalTaskRecordEdit(exact, "时刻截止已编辑", "", exact.status, sources)
        assertEquals(exact.copy(title = "时刻截止已编辑"), sources.tasks.findTaskById(exact.id))
    }

    @Test
    fun completedTasksKeepCompletionTimeAndCompletionTransitionsAreRejected() = runBlocking {
        val completed = sources.tasks.createTask(Task(
            newLocalId(), "已完成任务", status = TaskStatus.COMPLETED, completedAt = 1_800_000_000_000L,
        ))
        saveLocalTaskRecordEdit(completed, "补充标题", "补充说明", TaskStatus.COMPLETED, sources)
        val edited = completed.copy(title = "补充标题", description = "补充说明")
        assertEquals(edited, sources.tasks.findTaskById(completed.id))
        rejected { saveLocalTaskRecordEdit(edited, edited.title, "补充说明", TaskStatus.TODO, sources) }
        assertEquals(edited, sources.tasks.findTaskById(completed.id))
        val todo = sources.tasks.createTask(Task(newLocalId(), "待办任务"))
        rejected { saveLocalTaskRecordEdit(todo, todo.title, "", TaskStatus.COMPLETED, sources) }
        assertEquals(todo, sources.tasks.findTaskById(todo.id))
    }

    @Test
    fun foreignCreatorArchivedWorkspaceAndMissingWorkspaceAreReadOnly() = runBlocking {
        val foreign = sources.tasks.createTask(Task(newLocalId(), "其他创建者", createdBy = newLocalId()))
        rejected { saveLocalTaskRecordEdit(foreign, "尝试编辑", "", foreign.status, sources) }
        assertEquals(foreign, sources.tasks.findTaskById(foreign.id))
        val course = sources.workspaces.createWorkspace(WorkspaceKind.COURSE, "归档课程")
        val archived = sources.tasks.createTask(Task(newLocalId(), "课程任务", workspaceId = course.id))
        sources.workspaces.archiveWorkspace(course.id)
        rejected { saveLocalTaskRecordEdit(archived, "尝试编辑", "", archived.status, sources) }
        assertEquals(archived, sources.tasks.findTaskById(archived.id))
        val missing = sources.tasks.createTask(Task(newLocalId(), "旧归属任务", workspaceId = newLocalId()))
        rejected { saveLocalTaskRecordEdit(missing, "尝试编辑", "", missing.status, sources) }
        assertEquals(missing, sources.tasks.findTaskById(missing.id))
    }

    @Test
    fun mergesLatestUneditedFieldsButRejectsConflictingEditedFields() = runBlocking {
        val original = sources.tasks.createTask(Task(newLocalId(), "原任务", description = "原说明"))
        val concurrent = original.copy(
            priority = TaskPriority.HIGH, deadlineAt = 1_900_000_123_456L,
            status = TaskStatus.COMPLETED, completedAt = 1_800_000_000_000L,
        )
        sources.tasks.updateTask(concurrent)
        saveLocalTaskRecordEdit(original, "改标题", "原说明", original.status, sources)
        val merged = concurrent.copy(title = "改标题")
        assertEquals(merged, sources.tasks.findTaskById(original.id))
        rejected { saveLocalTaskRecordEdit(original, "另一个标题", "原说明", original.status, sources) }
        assertEquals(merged, sources.tasks.findTaskById(original.id))
    }

    @Test
    fun blankAndOverlongTitleDoNotWriteAnyFields() = runBlocking {
        val original = sources.tasks.createTask(Task(newLocalId(), "原任务"))
        rejected { saveLocalTaskRecordEdit(original, "  ", "不应保存", TaskStatus.IN_PROGRESS, sources) }
        rejected { saveLocalTaskRecordEdit(original, "字".repeat(121), "不应保存", TaskStatus.IN_PROGRESS, sources) }
        assertEquals(original, sources.tasks.findTaskById(original.id))
        assertEquals(0L, db.taskDao().findById(original.id)!!.localRevision)
    }

    private suspend fun rejected(action: suspend () -> Unit) {
        try {
            action()
            fail("应拒绝此次编辑")
        } catch (_: IllegalArgumentException) {
            // 领域和权限校验失败应在事务写入之前退出。
        }
    }
}
