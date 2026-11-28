package io.granulee.trial

import io.granulee.NotFoundException
import io.granulee.NotSignedInException
import io.granulee.InMemoryTrialStorage
import io.granulee.SignedIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TrialRepositoryTest {
    private val storage = InMemoryTrialStorage()

    private fun repository() = TrialRepository(storage)

    private fun trial(id: String) = Trial(
        id = id, ownerId = "u1", name = "t", sourceId = "s1", sourceName = "local", database = "analytics", table = "events", status = "running", error = null,
        createdAt = "2026-11-21T10:00:00Z", finishedAt = null, progress = 0, steps = emptyList(), request = TrialRequest(), scratchDatabase = "scratch",
        baseTable = "events_trial_${id}_base", candTable = "events_trial_${id}_cand", scratchBytes = 0, changedKeys = 0, changedColumns = 0, result = null, log = emptyList(),
    )

    @Test
    fun `every change is stored at once`() {
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
    fun `a request reaches its own user's trials only, and the run reaches them all`() {
        val trials = repository()
        trials.add(trial("0001"))
        trials.add(trial("0002").copy(ownerId = "u2"))
        SignedIn.`as`("u1") {
            assertEquals(listOf("0001"), trials.mine().map { it.id })
            assertEquals("0001", trials.owned("0001").id)
            // Somebody else's trial reads exactly like one that does not exist.
            assertEquals("Trial #0002 does not exist", assertFailsWith<NotFoundException> { trials.owned("0002") }.message)
        }
        SignedIn.nobody()
        assertFailsWith<NotSignedInException> { trials.mine() }
        assertFailsWith<NotSignedInException> { trials.owned("0001") }
        assertEquals(listOf("0001", "0002"), trials.all().map { it.id })
        assertEquals("u2", trials.get("0002").ownerId)
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
