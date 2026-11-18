package com.kaixu.domain.usecase.scheduling

import com.kaixu.app.di.LocalSchedulingGraph
import com.kaixu.data.datasource.local.LocalDataSources
import com.kaixu.data.datasource.local.database.openDesktopDatabase
import com.kaixu.data.datasource.local.entity.LOCAL_ACTOR_ID
import com.kaixu.domain.model.scheduling.EditScope
import com.kaixu.domain.model.scheduling.FixedEventDraft
import com.kaixu.domain.validation.scheduling.ShanghaiTime
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FixedEventScheduleRoomRegressionTest {
    private fun millis(value: String): Long = requireNotNull(ShanghaiTime.parseOrNull(value))

    private fun graph(sources: LocalDataSources) = LocalSchedulingGraph(sources, LOCAL_ACTOR_ID) { millis ->
        Instant.ofEpochMilli(millis).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString()
    }

    @Test
    fun editingFirstOccurrenceAndFutureLeavesAValidDatabaseAfterReopen() = runTest {
        val directory = Files.createTempDirectory("kaixu-recurring-first").toFile()
        val file = directory.resolve("test.db")
        try {
            val oldId: String
            val newId: String
            val database = openDesktopDatabase(file)
            try {
                val sources = LocalDataSources(database)
                val schedule = graph(sources).fixedEventSchedule
                val initial = FixedEventDraft(
                    title = "原课程",
                    startsAt = millis("2026-11-02 09:00"),
                    endsAt = millis("2026-11-02 10:00"),
                    repeatWeekdays = setOf(1),
                    repeatUntil = "2026-11-30",
                )
                val old = assertIs<FixedEventSaveResult.Saved>(schedule.create(initial)).event
                oldId = old.id
                val updated = assertIs<FixedEventSaveResult.Saved>(
                    schedule.update(old, initial.copy(title = "新课程"), "2026-11-02", EditScope.THIS_AND_FUTURE),
                ).event
                newId = updated.id
                assertTrue(newId != oldId)
            } finally {
                database.close()
            }

            val reopened = openDesktopDatabase(file)
            try {
                val sources = LocalDataSources(reopened)
                // Reading every row is essential: Room constructs entities and validates their dates here.
                assertNull(sources.fixedEvents.findEventById(oldId))
                assertNotNull(sources.fixedEvents.findEventById(newId))
                val events = sources.fixedEvents.listEvents()
                assertEquals(listOf(newId), events.map { it.id })
                val occurrences = graph(sources).fixedEventSchedule.occurrences(
                    millis("2026-11-02 00:00"), millis("2026-12-01 00:00"),
                )
                assertEquals(5, occurrences.size)
                assertTrue(occurrences.all { it.seriesId == newId && it.title == "新课程" })
            } finally {
                reopened.close()
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
