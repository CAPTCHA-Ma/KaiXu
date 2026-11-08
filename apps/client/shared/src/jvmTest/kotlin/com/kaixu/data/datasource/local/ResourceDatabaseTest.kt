package com.kaixu.data.datasource.local

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.kaixu.data.datasource.local.database.KaiXuDatabase
import com.kaixu.data.datasource.local.database.openDesktopDatabase
import com.kaixu.data.datasource.local.entity.LOCAL_ACTOR_ID
import com.kaixu.domain.model.resources.ResourceKind.LINK
import com.kaixu.domain.model.resources.ResourceKind.TEXT
import com.kaixu.domain.model.workspaces.WorkspaceKind.COURSE
import com.kaixu.domain.model.workspaces.WorkspaceKind.PROJECT
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResourceDatabaseTest {
    private suspend fun withFile(block: suspend (File) -> Unit) {
        val directory = Files.createTempDirectory("kaixu-resources-test").toFile()
        try { block(directory.resolve("local.db")) } finally { directory.deleteRecursively() }
    }

    private suspend fun <T> File.open(block: suspend (KaiXuDatabase) -> T): T {
        val database = openDesktopDatabase(this)
        try { return block(database) } finally { database.close() }
    }

    @Test
    fun linkAndTextKeepIdentityScopeAndRevisionAfterEditAndReopen() = runTest {
        withFile { file ->
            val saved = file.open { database ->
                val sources = LocalDataSources(database)
                val course = sources.workspaces.createWorkspace(COURSE, "数据库")
                val project = sources.workspaces.createWorkspace(PROJECT, "相关项目", linkedCourseId = course.id)
                val link = sources.resources.createResource(course.id, "  手册  ", null, LINK, "https://example.com/manual")
                val text = sources.resources.createResource(course.id, "学习笔记", "说明", TEXT, textContent = "第一行\n第二行")
                val projectNote = sources.resources.createResource(project.id, "项目资料", null, TEXT, textContent = "项目独有内容")
                assertEquals("手册", link.name)
                assertEquals(LOCAL_ACTOR_ID, link.createdBy)
                assertEquals(setOf(link, text), sources.resources.listResourcesByWorkspace(course.id).toSet())
                assertEquals(listOf(projectNote), sources.resources.listResourcesByWorkspace(project.id))
                val edited = sources.resources.updateResource(text.id, "修订笔记", null, TEXT, null, "保留原记录", text.localRevision)
                assertEquals(text.id, edited.id)
                assertEquals(text.workspaceId, edited.workspaceId)
                assertEquals(text.createdAt, edited.createdAt)
                assertEquals(text.localRevision + 1, edited.localRevision)
                assertEquals(edited, sources.resources.updateResource(edited.id, edited.name, null, TEXT, null, edited.textContent, edited.localRevision))
                Triple(link, edited, projectNote)
            }
            file.open { database ->
                val resources = LocalDataSources(database).resources
                assertEquals(saved.first, resources.findResourceById(saved.first.id))
                assertEquals(saved.second, resources.findResourceById(saved.second.id))
                assertEquals(saved.third, resources.findResourceById(saved.third.id))
                assertNull(resources.findResourceById("missing"))
                assertEquals(2, resources.listResourcesByWorkspace(saved.first.workspaceId).size)
            }
        }
    }

    @Test
    fun rejectsInvalidContentsMissingWorkspaceAndWritesToArchivedWorkspace() = runTest {
        withFile { file -> file.open { database ->
            val sources = LocalDataSources(database)
            val course = sources.workspaces.createWorkspace(COURSE, "课程")
            assertFailsWith<IllegalArgumentException> { sources.resources.createResource(course.id, " \t", null, TEXT, textContent = "正文") }
            assertFailsWith<IllegalArgumentException> { sources.resources.createResource(course.id, "空文本", null, TEXT, textContent = " \n") }
            for (url in listOf("javascript:alert(1)", "file:///C:/private.txt", "ftp://example.com", "https://", "https://example.com/a b", "https://user:pass@example.com", "https://example.com:99999")) {
                assertFailsWith<IllegalArgumentException> { sources.resources.createResource(course.id, "无效链接", null, LINK, url) }
            }
            assertFailsWith<IllegalArgumentException> { sources.resources.createResource(course.id, "混用字段", null, LINK, "https://example.com", "正文") }
            assertFailsWith<IllegalStateException> { sources.resources.createResource("missing", "孤立资料", null, TEXT, textContent = "正文") }
            val resource = sources.resources.createResource(course.id, "历史资料", null, TEXT, textContent = "归档保留")
            sources.workspaces.archiveWorkspace(course.id)
            assertFailsWith<IllegalStateException> { sources.resources.createResource(course.id, "新资料", null, TEXT, textContent = "正文") }
            assertFailsWith<IllegalStateException> { sources.resources.updateResource(resource.id, "不应写入", null, TEXT, null, "改写", resource.localRevision) }
            assertEquals(listOf(resource), sources.resources.listResourcesByWorkspace(course.id))
            assertEquals(resource, sources.resources.findResourceById(resource.id))
            sources.workspaces.restoreWorkspace(course.id)
            assertEquals("恢复可编辑", sources.resources.updateResource(resource.id, "恢复可编辑", null, TEXT, null, resource.textContent, resource.localRevision).name)
        } }
    }

    @Test
    fun failedSaveAndStaleDraftDoNotOverwriteSavedResource() = runTest {
        withFile { file -> file.open { database ->
            val sources = LocalDataSources(database)
            val course = sources.workspaces.createWorkspace(COURSE, "课程")
            val original = sources.resources.createResource(course.id, "原资料", "原说明", LINK, "https://example.com/original")
            BundledSQLiteDriver().open(file.absolutePath).use {
                it.execSQL("CREATE TRIGGER fail_resource_update BEFORE UPDATE ON resources BEGIN SELECT RAISE(ABORT, 'simulated write failure'); END")
            }
            assertFails { sources.resources.updateResource(original.id, "修改", null, TEXT, null, "正文", original.localRevision) }
            assertEquals(original, sources.resources.findResourceById(original.id))
            BundledSQLiteDriver().open(file.absolutePath).use { it.execSQL("DROP TRIGGER fail_resource_update") }
            val updated = sources.resources.updateResource(original.id, "已修改", null, TEXT, null, "新正文", original.localRevision)
            assertNull(updated.url)
            assertFailsWith<IllegalStateException> { sources.resources.updateResource(original.id, "过期覆盖", null, LINK, original.url, null, original.localRevision) }
            assertEquals(updated, sources.resources.findResourceById(original.id))
        } }
    }

    @Test
    fun versionThreeUpgradePreservesAllExistingTablesAndAllowsResources() = runTest {
        withFile { file ->
            // 按已提交的 v3 Room schema 建立旧数据库，不依赖当前 v4 建表逻辑。
            val schema = File("schemas/com.kaixu.data.datasource.local.database.KaiXuDatabase/3.json")
            check(schema.isFile) { "缺少已发布的 v3 schema：${schema.absolutePath}" }
            val schemaText = schema.readText()
            BundledSQLiteDriver().open(file.absolutePath).use { connection ->
                var table = ""
                for (line in schemaText.lines()) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("\"tableName\":")) table = trimmed.substringAfter(": \"").substringBefore('"')
                    if (trimmed.startsWith("\"createSql\":")) {
                        val sql = trimmed.substringAfter(": \"").substringBeforeLast('"').replace("\${TABLE_NAME}", table)
                        check('\\' !in sql) { "v3 fixture SQL 需要显式 JSON 解码" }
                        connection.execSQL(sql)
                    }
                }
                connection.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
                connection.execSQL("INSERT INTO room_master_table VALUES (42, 'c11b8dcdd5b16c57aa4f906f7cf85aaf')")
                connection.execSQL("""INSERT INTO workspaces
                    (id,owner_user_id,kind,visibility,name,status,created_at,updated_at,updated_by,local_revision,sync_state)
                    VALUES ('course','$LOCAL_ACTOR_ID','COURSE','PERSONAL','升级前课程','ACTIVE',10,11,'$LOCAL_ACTOR_ID',1,'LOCAL_ONLY')""")
                connection.execSQL("""INSERT INTO tasks
                    (id,title,workspace_id,created_by,status,priority,created_at,updated_at,updated_by,local_revision,sync_state)
                    VALUES ('task','升级前任务','course','$LOCAL_ACTOR_ID','TODO','NORMAL',10,11,'$LOCAL_ACTOR_ID',1,'LOCAL_ONLY')""")
                connection.execSQL("""INSERT INTO fixed_events
                    (id,title,starts_at,ends_at,workspace_id,created_by,is_override,created_at,updated_at,updated_by,local_revision,sync_state)
                    VALUES ('event','升级前事件',10,20,'course','$LOCAL_ACTOR_ID',0,10,11,'$LOCAL_ACTOR_ID',1,'LOCAL_ONLY')""")
                connection.execSQL("""INSERT INTO work_sessions
                    (id,task_id,starts_at,ends_at,owner_user_id,status,is_locked,overlap_confirmed,created_at,updated_at,updated_by,local_revision,sync_state)
                    VALUES ('session','task',20,30,'$LOCAL_ACTOR_ID','PLANNED',0,0,10,11,'$LOCAL_ACTOR_ID',1,'LOCAL_ONLY')""")
                connection.execSQL("PRAGMA user_version = 3")
            }
            file.open { database ->
                val sources = LocalDataSources(database)
                assertEquals("升级前课程", sources.workspaces.findWorkspaceById("course")?.name)
                assertEquals("升级前任务", sources.tasks.findTaskById("task")?.title)
                assertEquals("升级前事件", sources.fixedEvents.findEventById("event")?.title)
                assertEquals("task", sources.workSessions.findWorkSessionById("session")?.taskId)
                assertTrue(sources.resources.listResourcesByWorkspace("course").isEmpty())
                val created = sources.resources.createResource("course", "升级后资料", null, TEXT, textContent = "新增资料，保留旧数据")
                assertNotNull(sources.resources.findResourceById(created.id))
            }
        }
    }
}
