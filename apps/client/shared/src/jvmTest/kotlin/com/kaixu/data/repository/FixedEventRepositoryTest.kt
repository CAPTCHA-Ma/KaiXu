package com.kaixu.data.repository

import com.kaixu.data.datasource.local.RoomFixedEventDataSource
import com.kaixu.data.datasource.local.database.openDesktopDatabase
import com.kaixu.domain.model.scheduling.FixedEvent
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FixedEventRepositoryTest {
    @Test
    fun createUpdateDeleteAndQueriesStayConsistent() = runTest {
        val directory = Files.createTempDirectory("kaixu-fixed-event-repo").toFile()
        val database = openDesktopDatabase(directory.resolve("repo.db"))
        try {
            val repository = DefaultFixedEventRepository(RoomFixedEventDataSource(database.fixedEventDao()))
            repository.refresh()

            val created = repository.create(
                FixedEvent(id = "", title = "课程", startsAt = 1_000, endsAt = 2_000, workspaceId = "w1"),
            )
            assertEquals(listOf(created), repository.events.value)

            assertTrue(repository.update(created.copy(title = "课程（改）", startsAt = 3_000, endsAt = 4_000)))
            val updated = repository.events.value.single()
            assertEquals("课程（改）", updated.title)
            assertEquals(3_000, updated.startsAt)

            assertEquals(created.id, repository.findById(created.id)?.id)
            assertEquals(listOf(created.id), repository.listByWorkspace("w1").map { it.id })
            assertEquals(listOf(created.id), repository.listInRange(created.createdBy, 3_500, 5_000).map { it.id })
            assertTrue(repository.listInRange(created.createdBy, 4_000, 5_000).isEmpty())

            assertTrue(repository.deleteById(created.id))
            assertTrue(repository.events.value.isEmpty())
            assertNull(repository.findById(created.id))
            assertFalse(repository.deleteById(created.id))
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }
}
