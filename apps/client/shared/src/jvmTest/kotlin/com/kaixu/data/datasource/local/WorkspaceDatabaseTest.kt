package com.kaixu.data.datasource.local

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.kaixu.data.datasource.local.database.KaiXuDatabase
import com.kaixu.data.datasource.local.database.migrations.Migration1To2
import com.kaixu.data.datasource.local.database.openDesktopDatabase
import com.kaixu.domain.model.workspaces.WorkspaceKind.COURSE
import com.kaixu.domain.model.workspaces.WorkspaceKind.PROJECT
import com.kaixu.domain.model.workspaces.WorkspaceStatus.ACTIVE
import com.kaixu.domain.model.workspaces.WorkspaceStatus.ARCHIVED
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceDatabaseTest {
    private suspend fun withFile(block: suspend (File) -> Unit) {
        val directory = Files.createTempDirectory("kaixu-workspace-test").toFile()
        try {
            block(directory.resolve("local.db"))
        } finally {
            directory.deleteRecursively()
        }
    }

    private suspend fun <T> File.open(block: suspend (KaiXuDatabase) -> T): T {
        val db = openDesktopDatabase(this)
        try {
            return block(db)
        } finally {
            db.close()
        }
    }

    @Test
    fun archivedCourseAndItsActiveProjectSurviveReopen() = runTest {
        withFile { file ->
            val (archivedCourse, savedProject) = file.open { db ->
                val spaces = LocalDataSources(db).workspaces
                val course = spaces.createWorkspace(COURSE, "  数据库  ")
                val project = spaces.createWorkspace(PROJECT, "数据库", "项目说明", course.id)
                assertEquals("数据库", course.name)
                assertNotEquals(course.id, project.id)
                assertEquals(setOf(course, project), spaces.listWorkspaces().toSet())
                assertTrue(spaces.archiveWorkspace(course.id))
                assertEquals(listOf(project), spaces.listWorkspaces())
                val archived = spaces.listWorkspaces(ARCHIVED).single()
                assertEquals(course.id, archived.id)
                assertEquals(course.createdAt, archived.createdAt)
                assertNotNull(archived.archivedAt)
                // 旧关系可保留，编辑项目说明不会清除已归档课程的引用。
                assertTrue(spaces.updateWorkspace(project.id, project.name, "更新说明", course.id))
                val edited = spaces.listProjectsForCourse(course.id).single()
                assertEquals(project.id, edited.id)
                assertEquals(project.createdAt, edited.createdAt)
                assertEquals("更新说明", edited.description)
                archived to edited
            }
            file.open { db ->
                val spaces = LocalDataSources(db).workspaces
                assertEquals(archivedCourse, spaces.findWorkspaceById(archivedCourse.id))
                assertEquals(savedProject, spaces.findWorkspaceById(savedProject.id))
                assertEquals(listOf(savedProject), spaces.listProjectsForCourse(archivedCourse.id))
                assertFailsWith<IllegalArgumentException> { spaces.createWorkspace(COURSE, archivedCourse.name) }
                assertTrue(spaces.restoreWorkspace(archivedCourse.id))
                val restored = assertNotNull(spaces.findWorkspaceById(archivedCourse.id))
                assertEquals(ACTIVE, restored.status)
                assertNull(restored.archivedAt)
                assertEquals(archivedCourse.createdAt, restored.createdAt)
                assertEquals(setOf(restored, savedProject), spaces.listWorkspaces().toSet())
            }
        }
    }

    @Test
    fun validatesNamesAndCourseReferencesOnCreateAndEdit() = runTest {
        withFile { file ->
            file.open { db ->
                val spaces = LocalDataSources(db).workspaces
                val course = spaces.createWorkspace(COURSE, "课程")
                val project = spaces.createWorkspace(PROJECT, "独立项目")
                assertNull(project.linkedCourseId)
                assertFailsWith<IllegalArgumentException> { spaces.createWorkspace(COURSE, " \t ") }
                assertFailsWith<IllegalArgumentException> { spaces.createWorkspace(COURSE, "字".repeat(101)) }
                assertFailsWith<IllegalArgumentException> { spaces.createWorkspace(COURSE, " 课程 ") }
                assertFailsWith<IllegalArgumentException> { spaces.createWorkspace(COURSE, "另一课程", linkedCourseId = course.id) }
                assertFailsWith<IllegalArgumentException> { spaces.createWorkspace(PROJECT, "另一项目", linkedCourseId = project.id) }
                assertTrue(spaces.archiveWorkspace(course.id))
                assertFailsWith<IllegalArgumentException> { spaces.createWorkspace(PROJECT, "另一项目", linkedCourseId = course.id) }
                assertFailsWith<IllegalArgumentException> { spaces.updateWorkspace(project.id, "改名", null, "missing") }
                assertFailsWith<IllegalArgumentException> { spaces.updateWorkspace(project.id, "改名", null, course.id) }
                assertEquals(project, spaces.findWorkspaceById(project.id))
                assertEquals(2, spaces.listWorkspaces(null).size)
                assertFalse(spaces.updateWorkspace("missing", "名称", null, null))
                val atLimit = "📘".repeat(100)
                assertEquals(atLimit, spaces.createWorkspace(PROJECT, atLimit).name)
                assertFailsWith<IllegalArgumentException> { spaces.createWorkspace(PROJECT, atLimit + "字") }
            }
        }
    }

    @Test
    fun concurrentCreatesKeepOneCourseWithTheSameName() = runTest {
        withFile { file ->
            file.open { db ->
                val spaces = LocalDataSources(db).workspaces
                val start = CompletableDeferred<Unit>()
                val writes = listOf("数据库", " 数据库 ").map { input ->
                    async(Dispatchers.IO) {
                        start.await()
                        try {
                            spaces.createWorkspace(COURSE, input)
                        } catch (duplicate: IllegalArgumentException) {
                            null
                        }
                    }
                }
                start.complete(Unit)
                val saved = writes.awaitAll().filterNotNull().single()
                assertEquals(listOf(saved), spaces.listWorkspaces())
            }
        }
    }

    @Test
    fun failedRelinkPreservesOriginalFieldsAndCanBeRetried() = runTest {
        withFile { file ->
            file.open { db ->
                val spaces = LocalDataSources(db).workspaces
                val first = spaces.createWorkspace(COURSE, "A")
                val second = spaces.createWorkspace(COURSE, "B")
                val project = spaces.createWorkspace(PROJECT, "原项目", "原说明", first.id)
                BundledSQLiteDriver().open(file.absolutePath).use { connection ->
                    connection.execSQL("""CREATE TRIGGER fail_relink BEFORE UPDATE ON workspaces
                        BEGIN SELECT RAISE(ABORT, 'simulated write failure'); END""")
                }
                assertFails { spaces.updateWorkspace(project.id, "改名", null, second.id) }
                assertEquals(project, spaces.findWorkspaceById(project.id))
                assertEquals(listOf(project), spaces.listProjectsForCourse(first.id))
                assertTrue(spaces.listProjectsForCourse(second.id).isEmpty())
                BundledSQLiteDriver().open(file.absolutePath).use { it.execSQL("DROP TRIGGER fail_relink") }
                assertTrue(spaces.updateWorkspace(project.id, "改名", null, second.id))
                assertTrue(spaces.listProjectsForCourse(first.id).isEmpty())
                val moved = spaces.listProjectsForCourse(second.id).single()
                assertEquals(project.id, moved.id)
                assertEquals("改名", moved.name)
                assertEquals(second.id, moved.linkedCourseId)
                assertNull(moved.description)
                assertTrue(spaces.updateWorkspace(moved.id, moved.name, null, null))
                assertNull(spaces.findWorkspaceById(project.id)?.linkedCourseId)
                assertTrue(spaces.listProjectsForCourse(second.id).isEmpty())
                assertEquals(3, spaces.listWorkspaces().size)
            }
        }
    }

    @Test
    fun versionTwoUpgradePreservesTasksEventsAndSessionLinks() = runTest {
        withFile { file ->
            // 使用旧版发布的建表逻辑生成 v2 文件，故意保留当时合法的无父记录工作区 ID。
            BundledSQLiteDriver().open(file.absolutePath).use { connection ->
                connection.execSQL("CREATE TABLE tasks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL)")
                Migration1To2.migrate(connection)
                connection.execSQL("""INSERT INTO tasks
                    (id,title,workspace_id,created_by,status,priority,created_at,updated_at,updated_by,local_revision,sync_state)
                    VALUES ('t','旧任务','legacy-space','owner','TODO','NORMAL',10,11,'owner',1,'LOCAL_ONLY')""")
                connection.execSQL("""INSERT INTO fixed_events
                    (id,title,starts_at,ends_at,workspace_id,created_by,is_override,created_at,updated_at,updated_by,local_revision,sync_state)
                    VALUES ('e','旧事件',10,20,'legacy-space','owner',0,10,10,'owner',0,'LOCAL_ONLY')""")
                connection.execSQL("""INSERT INTO work_sessions
                    (id,task_id,starts_at,ends_at,owner_user_id,status,is_locked,overlap_confirmed,created_at,updated_at,updated_by,local_revision,sync_state)
                    VALUES ('s','t',20,30,'owner','PLANNED',0,0,10,10,'owner',0,'LOCAL_ONLY')""")
                connection.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
                connection.execSQL("INSERT INTO room_master_table VALUES (42, '8aea115eef0304128eb34a29be7b89c9')")
                connection.execSQL("PRAGMA user_version = 2")
            }
            file.open { db ->
                val task = db.taskDao().listByWorkspace("legacy-space").single()
                assertEquals("t", task.id)
                assertEquals("旧任务", task.title)
                assertEquals(1L, task.localRevision)
                val event = db.fixedEventDao().listByWorkspace("legacy-space").single()
                assertEquals("e", event.id)
                assertEquals("旧事件", event.title)
                assertEquals("s", db.workSessionDao().listByTask(task.id).single().id)
                val spaces = LocalDataSources(db).workspaces
                assertTrue(spaces.listWorkspaces(null).isEmpty())
                val first = spaces.createWorkspace(COURSE, "升级后新增")
                assertEquals(first, spaces.findWorkspaceById(first.id))
            }
        }
    }
}
