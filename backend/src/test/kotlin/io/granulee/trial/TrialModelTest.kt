package io.granulee.trial

import io.granulee.sql.TrialIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrialModelTest {
    private val request = TrialRequest(
        sourceId = "s1", database = "analytics", table = "events", sortingKey = "user_id, event_time", rows = 1_000_000,
        columns = listOf(TrialColumn("user_id", "UInt64", "")),
    )

    @Test
    fun `a request that can start has no problem`() {
        assertNull(request.problem())
        assertNull(request.copy(sampling = "where", where = "user_id % 2 = 0").problem())
        assertNull(request.copy(addIndexes = listOf(TrialIndex("idx_a", "a", "minmax"), TrialIndex("idx_b", "b", "set(10)"))).problem())
    }

    @Test
    fun `what keeps a trial from starting is said in the order a person fills the form`() {
        assertEquals("Pick a table for the trial", request.copy(table = " ").problem())
        assertEquals("Pick a table for the trial", request.copy(database = "", rows = 0).problem())
        assertEquals("Rows to copy must be at least 1", request.copy(rows = 0, runsPerQuery = 99).problem())
        assertEquals("Runs per query must be 1 to 20", request.copy(runsPerQuery = 0).problem())
        assertEquals("Runs per query must be 1 to 20", request.copy(runsPerQuery = 21).problem())
        assertEquals("Unknown sampling method", request.copy(sampling = "random").problem())
        assertEquals("Enter the WHERE clause for the sample", request.copy(sampling = "where", where = " ").problem())
        assertEquals("The WHERE clause must be a single expression", request.copy(where = "1; DROP TABLE t").problem())
        assertEquals("The candidate schema has no columns", request.copy(columns = emptyList()).problem())
        assertEquals("Index name \"1x\" must be letters, digits and underscores", request.copy(addIndexes = listOf(TrialIndex("1x", "a", "minmax"))).problem())
        assertEquals("Two new indexes share a name", request.copy(addIndexes = listOf(TrialIndex("idx", "a", "minmax"), TrialIndex(" idx ", "b", "minmax"))).problem())
    }

    @Test
    fun `the steps of a trial are the ones its request asks for`() {
        val all = request.copy(plainDefaults = true, codecBakeoff = true)
        assertEquals(
            listOf(
                "Create baseline and candidate tables", "Copy 1.0 M rows to baseline", "Copy the same rows to candidate",
                "Copy the same rows to a table with plain defaults", "Full merge, measure sizes", "Scan every column", "Replay queries",
                "Compare codecs for the largest columns",
            ),
            TrialPhase.of(all).map { it.label(all) },
        )
        val least = request.copy(optimizeFinal = false, columnScans = false, replayQueries = false)
        assertEquals(
            listOf("Create baseline and candidate tables", "Copy 1.0 M rows to baseline", "Copy the same rows to candidate", "Measure sizes"),
            TrialPhase.of(least).map { it.label(least) },
        )
    }

    private fun trial(status: String = "running", apply: String? = null, optimize: String? = null) = Trial(
        id = "7f3a", name = "t", sourceId = "s1", sourceName = "local", database = "analytics", table = "events", status = status, error = null,
        createdAt = "2026-11-21T10:00:00Z", finishedAt = null, progress = 38,
        steps = listOf(TrialStep("a", "done"), TrialStep("b", "running"), TrialStep("c", "pending")),
        request = request, scratchDatabase = "scratch", baseTable = "events_trial_7f3a_base", candTable = "events_trial_7f3a_cand",
        scratchBytes = 0, changedKeys = 0, changedColumns = 0, result = null, log = emptyList(),
        apply = apply?.let { TrialApply(it, "2026-11-21T11:00:00Z", null, null, "ALTER …", 1, 2) },
        optimize = optimize?.let { TrialOptimize(it, "2026-11-21T11:30:00Z", null, null, "OPTIMIZE …") },
    )

    @Test
    fun `a trial that was running when Granulee stopped has failed, and its running step with it`() {
        val now = "2026-11-21T12:00:00Z"
        val restarted = trial().afterRestart(now)
        assertEquals("failed", restarted.status)
        assertEquals(now, restarted.finishedAt)
        assertEquals(listOf("done", "failed", "pending"), restarted.steps.map { it.state })
        assertTrue(restarted.error.orEmpty().startsWith("Granulee restarted while this trial was running."))
    }

    @Test
    fun `an apply or an OPTIMIZE that was running has failed, the finished trial around it stays`() {
        val now = "2026-11-21T12:00:00Z"
        val applying = trial("completed", apply = "running").afterRestart(now)
        assertEquals(listOf("completed", "failed", now), listOf(applying.status, applying.apply?.status, applying.apply?.finishedAt))
        assertTrue(applying.apply?.error.orEmpty().contains("check system.mutations"))

        val optimizing = trial("completed", apply = "completed", optimize = "running").afterRestart(now)
        assertEquals(listOf("completed", "completed", "failed"), listOf(optimizing.status, optimizing.apply?.status, optimizing.optimize?.status))
        assertTrue(optimizing.optimize?.error.orEmpty().contains("check system.merges"))
    }

    @Test
    fun `what had come to rest is left as it is`() {
        listOf(trial("completed"), trial("failed"), trial("cancelled"), trial("completed", apply = "completed", optimize = "completed")).forEach {
            assertTrue(!it.hasWorkRunning())
            assertEquals(it, it.afterRestart("2026-11-21T12:00:00Z"))
        }
        assertTrue(trial().hasWorkRunning() && trial("completed", apply = "running").hasWorkRunning() && trial("completed", "completed", "running").hasWorkRunning())
    }

    @Test
    fun `the scratch tables of a trial carry its id`() {
        val tables = ScratchTables(trial())
        assertEquals("`scratch`.`events_trial_7f3a_base`", tables.base)
        assertEquals("`scratch`.`events_trial_7f3a_plain`", tables.plain)
        assertEquals(
            listOf("events_trial_7f3a_base", "events_trial_7f3a_cand", "events_trial_7f3a_base_ins", "events_trial_7f3a_plain", "events_trial_7f3a_codecs", "events_trial_7f3a_probe")
                .map { "`scratch`.`$it`" },
            tables.all,
        )
        assertEquals("scratch.events_trial_7f3a_cand", tables.described(tables.candName))
    }
}
