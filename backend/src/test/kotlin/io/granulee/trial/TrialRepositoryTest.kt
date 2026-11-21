package io.granulee.trial

import io.granulee.NotFoundException
import io.granulee.state.GranuleeProperties
import io.granulee.state.JsonStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TrialRepositoryTest {
    @TempDir
    lateinit var dir: Path

    private fun repository() = TrialRepository(JsonStore(GranuleeProperties(dataDir = dir.toString())))

    private fun trial(id: String) = Trial(
        id = id, name = "t", sourceId = "s1", sourceName = "local", database = "analytics", table = "events", status = "running", error = null,
        createdAt = "2026-11-21T10:00:00Z", finishedAt = null, progress = 0, steps = emptyList(), request = TrialRequest(), scratchDatabase = "scratch",
        baseTable = "events_trial_${id}_base", candTable = "events_trial_${id}_cand", scratchBytes = 0, changedKeys = 0, changedColumns = 0, result = null, log = emptyList(),
    )

    @Test
    fun `every change is on disk at once`() {
        val trials = repository()
        trials.add(trial("0001"))
        trials.add(trial("0002"))
        val changed = trials.update("0001") { it.copy(progress = 38) }
        assertEquals(38, changed.progress)
        assertEquals(listOf(38, 0), repository().all().map { it.progress })

        trials.updateAll { it.copy(status = "failed") }
        trials.remove("0002")
        assertEquals(listOf("0001" to "failed"), repository().all().map { it.id to it.status })
    }

    @Test
    fun `a trial that does not exist is not found`() {
        assertEquals("Trial #zzzz does not exist", assertFailsWith<NotFoundException> { repository().get("zzzz") }.message)
        assertFailsWith<NoSuchElementException> { repository().update("zzzz") { it } }
    }

    @Test
    fun `a new id is four hex digits no trial has`() {
        val trials = repository()
        repeat(50) {
            val id = trials.newId()
            assertTrue(Regex("[0-9a-f]{4}").matches(id) && trials.all().none { it.id == id })
            trials.add(trial(id))
        }
    }
}
