package com.kaixu.data.datasource.local

import com.kaixu.data.datasource.local.database.openDesktopDatabase
import com.kaixu.data.datasource.local.entity.TaskEntity
import com.kaixu.data.repository.DefaultTaskRepository
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomDatabaseTest {
    @Test
    fun repositoryDataSurvivesDatabaseReopen() = runTest {
        val directory = Files.createTempDirectory("kaixu-room-test").toFile()
        val file = directory.resolve("nested/tasks.db")
        try {
            val id = openDesktopDatabase(file).let { database ->
                try {
                    val repository = DefaultTaskRepository(RoomTaskDataSource(database.taskDao()))
                    repository.create("重启后仍保留")
                    repository.tasks.value.single().id
                } finally {
                    database.close()
                }
            }
            val database = openDesktopDatabase(file)
            try {
                val repository = DefaultTaskRepository(RoomTaskDataSource(database.taskDao()))
                repository.refresh()
                assertEquals(id, repository.tasks.value.single().id)
                assertEquals("重启后仍保留", repository.tasks.value.single().title)
                repository.create("新任务")
                assertEquals(2, repository.tasks.value.size)
                assertTrue(repository.tasks.value.first().id != id)
            } finally {
                database.close()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun daoCrudAndSeparateFiles() = runTest {
        val directory = Files.createTempDirectory("kaixu-room-crud").toFile()
        try {
            val database = openDesktopDatabase(directory.resolve("local.db"))
            val other = openDesktopDatabase(directory.resolve("other.db"))
            try {
                val dao = database.taskDao()
                val entity = TaskEntity(title = "原始标题")
                dao.insert(entity)
                val id = entity.id
                assertNotNull(dao.findById(id))
                assertEquals(1, dao.updateTitle(id, "修改后"))
                assertEquals("修改后", dao.findById(id)?.title)
                assertTrue(other.taskDao().list().isEmpty())
                assertEquals(1, dao.deleteById(id))
                assertNull(dao.findById(id))
                assertEquals(0, dao.updateTitle(id, "不存在"))
                assertEquals(0, dao.deleteById(id))
            } finally {
                database.close()
                other.close()
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
