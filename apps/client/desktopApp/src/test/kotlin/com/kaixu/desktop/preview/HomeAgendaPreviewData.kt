package com.kaixu.desktop.preview

import com.kaixu.data.datasource.local.LocalDataSources
import com.kaixu.data.datasource.local.database.defaultDesktopDatabaseFile
import com.kaixu.data.datasource.local.database.openDesktopDatabase
import com.kaixu.domain.model.scheduling.FixedEvent
import com.kaixu.domain.model.scheduling.WorkSession
import com.kaixu.domain.model.tasks.Task
import com.kaixu.domain.model.tasks.TaskStatus
import com.kaixu.domain.validation.scheduling.ShanghaiTime
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID

private const val PreviewNote = "首页布局预览数据"
private const val Minute = 60_000L

/** 仅用于显式执行的开发命令及隔离库测试；正常启动不会自动造数据。重复执行不重复插入。 */
internal suspend fun seedHomeAgendaPreview(sources: LocalDataSources, now: Long) {
    val today = ShanghaiTime.localDateOf(now)
    fun id(key: String) = UUID.nameUUIDFromBytes("kaixu-home-preview:$key".toByteArray()).toString()
    sources.writeTransaction {
        for (offset in -1L..1L) {
            val date = ShanghaiTime.plusDays(today, offset)
            val midnight = ShanghaiTime.atLocalDate(date, 0)
            val taskTitles = listOf("提交数据库实验报告", "整理项目需求与用例", "完成课程阅读笔记")
            val eventTitles = listOf("数据库原理课堂", "项目小组例会", "晚间复盘")
            for (index in 0..2) {
                val taskId = id("$date-task-$index")
                if (sources.tasks.findTaskById(taskId) == null) {
                    sources.tasks.createTask(Task(
                        id = taskId, title = "[预览] ${taskTitles[index]}", description = PreviewNote,
                        deadlineAt = midnight + listOf(12 * 60, 17 * 60, 21 * 60 + 30)[index] * Minute,
                        status = if (offset < 0 && index == 0) TaskStatus.COMPLETED else TaskStatus.TODO,
                        completedAt = if (offset < 0 && index == 0) midnight + 11 * 60 * Minute else null,
                    ))
                }
                val eventId = id("$date-event-$index")
                val eventStart = midnight + listOf(8 * 60, 13 * 60 + 30, 20 * 60)[index] * Minute
                if (sources.fixedEvents.findEventById(eventId) == null) {
                    sources.fixedEvents.createEvent(FixedEvent(eventId, "[预览] ${eventTitles[index]}",
                        eventStart, eventStart + 45 * Minute, description = PreviewNote))
                }
                val sessionId = id("$date-session-$index")
                val sessionStart = midnight + listOf(9 * 60 + 30, 15 * 60, 18 * 60 + 30)[index] * Minute
                if (sources.workSessions.findWorkSessionById(sessionId) == null) {
                    sources.workSessions.createWorkSession(WorkSession(sessionId, taskId,
                        sessionStart, sessionStart + 60 * Minute, note = "专注准备 · $PreviewNote"))
                }
            }
        }
        val liveTaskId = id("$today-live-task")
        if (sources.tasks.findTaskById(liveTaskId) == null) {
            sources.tasks.createTask(Task(liveTaskId, "[预览] 完善首页视觉方案", description = PreviewNote,
                status = TaskStatus.IN_PROGRESS, deadlineAt = now + 120 * Minute))
        }
        val liveSessionId = id("$today-live-session")
        if (sources.workSessions.findWorkSessionById(liveSessionId) == null) {
            sources.workSessions.createWorkSession(WorkSession(liveSessionId, liveTaskId,
                now - 15 * Minute, now + 45 * Minute, note = "布局与卡片样式评估 · $PreviewNote"))
        }
    }
}

/** 可选第一个参数指定隔离数据库；不传参数时使用桌面应用的本地库。 */
fun main(args: Array<String>) = runBlocking {
    val file = args.firstOrNull()?.let(::File) ?: defaultDesktopDatabaseFile()
    val database = openDesktopDatabase(file)
    try {
        seedHomeAgendaPreview(LocalDataSources(database), System.currentTimeMillis())
        println("首页预览安排已写入：${file.absolutePath}；所有新增记录标记为 [预览]。")
    } finally {
        database.close()
    }
}
