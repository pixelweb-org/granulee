package io.granulee.trial

import io.granulee.clickhouse.ChException
import io.granulee.clickhouse.FakeClickHouse
import io.granulee.clickhouse.Row
import io.granulee.facts.QueryFacts
import io.granulee.facts.TableFacts
import io.granulee.sql.TrialIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A whole run against a stand-in server: what is sent, in which order, and what the trial says about itself meanwhile. */
class TrialRunTest {
    private val request = TrialRequest(
        sourceId = "s1", database = "analytics", table = "events", partitionKey = "toYYYYMM(ts)", sortingKey = "id, ts", rows = 1000, runsPerQuery = 2,
        columns = listOf(
            TrialColumn("id", "UInt64", ""),
            TrialColumn("ts", "DateTime", ""),
            TrialColumn("url", "String", "ZSTD(3)"),
            TrialColumn("country", "LowCardinality(Nullable(String))", ""),
        ),
        addIndexes = listOf(TrialIndex("idx_country", "country", "set(100)", 4)),
        codecBakeoff = true, plainDefaults = true,
    )

    private fun trial(request: TrialRequest) = Trial(
        id = "7f3a", ownerId = "u1", name = "t", sourceId = "s1", sourceName = "local", database = "analytics", table = "events", status = "running", error = null,
        createdAt = "2026-11-21T10:00:00Z", finishedAt = null, progress = 0,
        steps = TrialPhase.of(request).map { TrialStep(it.label(request), "pending") },
        request = request, scratchDatabase = "scratch", baseTable = "events_trial_7f3a_base", candTable = "events_trial_7f3a_cand",
        scratchBytes = 0, changedKeys = 0, changedColumns = 0, result = null, log = emptyList(),
    )

    private fun column(name: String, type: String, codec: String = "", sorting: Int = 0, kind: String = ""): Row = mapOf(
        "name" to name, "type" to type, "compression_codec" to codec, "default_kind" to kind, "default_expression" to if (kind.isEmpty()) "" else "toDate(ts)",
        "compressed" to 0, "uncompressed" to 0, "is_in_sorting_key" to sorting, "is_in_partition_key" to 0,
    )

    private fun parts(rows: Long, compressed: Long): Row =
        mapOf("rows" to rows, "compressed" to compressed, "uncompressed" to compressed * 4, "on_disk" to compressed + 10, "parts" to 1, "pk" to 64, "index_bytes" to 0)

    /** A server that has analytics.events with four stored columns, and scratch tables that weigh something once filled. */
    private fun server(extraColumn: Row? = null) = FakeClickHouse()
        .answer(
            "FROM system.tables WHERE database = 'analytics' AND name = 'events'",
            mapOf("engine_full" to "MergeTree PARTITION BY toYYYYMM(ts) ORDER BY (id, ts) SETTINGS index_granularity = 4096", "partition_key" to "toYYYYMM(ts)", "sorting_key" to "id, ts", "primary_key" to "id, ts"),
        )
        .answer("AS compressed FROM system.parts WHERE active AND database = 'analytics' AND table = 'events'", mapOf("rows" to 6000, "compressed" to 8_000_000))
        .answer("FROM system.columns WHERE database = 'analytics' AND table = 'events' ORDER BY position") {
            listOfNotNull(column("id", "UInt64", sorting = 1), column("ts", "DateTime", sorting = 1), column("url", "String", "CODEC(ZSTD(1))"), column("country", "Nullable(String)"), column("day", "Date", kind = "ALIAS"), extraColumn)
        }
        .answer("SELECT toInt64(count()) AS c FROM `scratch`.`events_trial_7f3a_base`", mapOf("c" to 1000))
        .answer("FROM system.parts WHERE active AND database = 'scratch' AND table = 'events_trial_7f3a_base'", parts(1000, 5000))
        .answer("FROM system.parts WHERE active AND database = 'scratch' AND table = 'events_trial_7f3a_cand'", parts(1000, 4000))
        .answer("FROM system.parts WHERE active AND database = 'scratch' AND table = 'events_trial_7f3a_plain'", parts(1000, 9000))
        .answer("AS uncompressed FROM system.columns WHERE database = 'scratch' AND table = 'events_trial_7f3a_cand'") {
            listOf(mapOf("name" to "id", "compressed" to 500, "uncompressed" to 8000), mapOf("name" to "url", "compressed" to 3000, "uncompressed" to 20000), mapOf("name" to "country", "compressed" to 0, "uncompressed" to 0))
        }
        .answer("FROM system.settings", mapOf("name" to "use_query_cache"))
        .answer("EXPLAIN json", mapOf("explain" to "[]"))
        .answer("FROM system.columns\nWHERE database = 'scratch' AND table = 'events_trial_7f3a_codecs'") {
            // url is the larger column, so it comes first: c0. LZ4 comes out exactly as large as the server's default.
            listOf(
                mapOf("name" to "c0_v0", "compression_codec" to "CODEC(ZSTD(1))", "compressed" to 3400, "uncompressed" to 20000),
                mapOf("name" to "c0_v1", "compression_codec" to "CODEC(ZSTD(3))", "compressed" to 3000, "uncompressed" to 20000),
                mapOf("name" to "c0_v2", "compression_codec" to "CODEC(LZ4)", "compressed" to 5200, "uncompressed" to 20000),
                mapOf("name" to "c1_v0", "compression_codec" to "", "compressed" to 500, "uncompressed" to 8000),
                mapOf("name" to "c1_v1", "compression_codec" to "CODEC(LZ4)", "compressed" to 500, "uncompressed" to 8000),
                mapOf("name" to "c1_v2", "compression_codec" to "CODEC(ZSTD(1))", "compressed" to 420, "uncompressed" to 8000),
                mapOf("name" to "c1_v4", "compression_codec" to "CODEC(T64, ZSTD(1))", "compressed" to 300, "uncompressed" to 8000),
            )
        }

    private val facts = TableFacts(
        "analytics", "events", "MergeTree", "", "toYYYYMM(ts)", "id, ts", "id, ts", 6000, 8_000_000, 32_000_000, 8_000_010, 4, 2, emptyList(), emptyList(),
        queries = listOf(QueryFacts("SELECT count() FROM analytics.events_buf WHERE id = 1 FORMAT JSON", "SELECT count() FROM analytics.events_buf WHERE id = ?", 30, database = "analytics")),
        queryTotal = 40, buffers = listOf("analytics.events_buf"),
    )

    private class Outcome(val state: Trial, val result: TrialResult)

    private fun run(ch: FakeClickHouse, request: TrialRequest = this.request, cancelAfter: Int = Int.MAX_VALUE): Outcome {
        var state = trial(request)
        val statements = TrialStatements("7f3a", ch, cancelled = { ch.executed.size >= cancelAfter }, running = {})
        val journal = TrialJournal(TrialPhase.of(request)) { change -> state = change(state) }
        val result = TrialRun(state, ch, statements, journal) { facts }.execute()
        return Outcome(state, result)
    }

    @Test
    fun `the baseline is the production schema, the candidate has the request's changes, both as plain MergeTree with wide parts`() {
        val ch = server()
        run(ch)
        assertEquals(
            """
            CREATE TABLE `scratch`.`events_trial_7f3a_base`
            (
                `id` UInt64,
                `ts` DateTime,
                `url` String CODEC(ZSTD(1)),
                `country` Nullable(String),
                `day` Date ALIAS toDate(ts)
            )
            ENGINE = MergeTree
            PARTITION BY toYYYYMM(ts)
            ORDER BY (id, ts)
            SETTINGS min_bytes_for_wide_part = 0, min_rows_for_wide_part = 0, index_granularity = 4096
            """.trimIndent(),
            ch.executed[1],
        )
        assertEquals(
            """
            CREATE TABLE `scratch`.`events_trial_7f3a_cand`
            (
                `id` UInt64,
                `ts` DateTime,
                `url` String CODEC(ZSTD(3)),
                `country` LowCardinality(Nullable(String)),
                `day` Date ALIAS toDate(ts),
                INDEX `idx_country` country TYPE set(100) GRANULARITY 4
            )
            ENGINE = MergeTree
            PARTITION BY toYYYYMM(ts)
            ORDER BY (id, ts)
            SETTINGS min_bytes_for_wide_part = 0, min_rows_for_wide_part = 0, index_granularity = 4096
            """.trimIndent(),
            ch.executed[2],
        )
    }

    @Test
    fun `the same sample goes into every table, the candidate's through a cast where a type changes`() {
        val ch = server()
        run(ch)
        val inserts = ch.sent.filter { it.sql.startsWith("INSERT INTO") }
        assertEquals(
            listOf(
                "INSERT INTO `scratch`.`events_trial_7f3a_base` (`id`, `ts`, `url`, `country`) SELECT `id`, `ts`, `url`, `country` FROM `analytics`.`events` WHERE _part_offset % 6 = 0 LIMIT 1000",
                "INSERT INTO `scratch`.`events_trial_7f3a_cand` (`id`, `ts`, `url`, `country`) SELECT `id`, `ts`, `url`, CAST(`country` AS LowCardinality(Nullable(String))) AS `country` FROM `scratch`.`events_trial_7f3a_base`",
                "INSERT INTO `scratch`.`events_trial_7f3a_plain` (`id`, `ts`, `url`, `country`) SELECT `id`, `ts`, `url`, `country` FROM `scratch`.`events_trial_7f3a_base`",
                "INSERT INTO `scratch`.`events_trial_7f3a_base_ins` (`id`, `ts`, `url`, `country`) SELECT `id`, `ts`, `url`, `country` FROM `scratch`.`events_trial_7f3a_base`",
            ),
            inserts.take(4).map { it.sql },
        )
        assertTrue(inserts.all { it.settings == mapOf("max_partitions_per_insert_block" to "0") })
        // The table with plain defaults has no codecs and no indexes.
        val plain = ch.executed.first { it.startsWith("CREATE TABLE `scratch`.`events_trial_7f3a_plain`") }
        assertTrue("CODEC" !in plain && "INDEX `" !in plain, plain)
    }

    @Test
    fun `the steps follow one another, and every statement carries the next query id of the trial`() {
        val ch = server()
        run(ch)
        fun at(text: String) = ch.executed.indexOfFirst { it.contains(text) }.also { assertTrue(it >= 0, "no statement with $text") }
        val order = listOf(
            "CREATE DATABASE IF NOT EXISTS `scratch`", "CREATE TABLE `scratch`.`events_trial_7f3a_base`", "INSERT INTO `scratch`.`events_trial_7f3a_base` ",
            "INSERT INTO `scratch`.`events_trial_7f3a_cand`", "CREATE TABLE `scratch`.`events_trial_7f3a_plain`", "DROP TABLE IF EXISTS `scratch`.`events_trial_7f3a_base_ins` SYNC",
            "OPTIMIZE TABLE `scratch`.`events_trial_7f3a_base` FINAL", "OPTIMIZE TABLE `scratch`.`events_trial_7f3a_cand` FINAL", "OPTIMIZE TABLE `scratch`.`events_trial_7f3a_plain` FINAL",
            "SELECT count() FROM `scratch`.`events_trial_7f3a_base` WHERE NOT ignore(`id`)", "SELECT count() FROM `scratch`.`events_trial_7f3a_base` WHERE id = 1",
            "SYSTEM FLUSH LOGS", "CREATE TABLE `scratch`.`events_trial_7f3a_probe`", "CREATE TABLE `scratch`.`events_trial_7f3a_codecs`", "OPTIMIZE TABLE `scratch`.`events_trial_7f3a_codecs` FINAL",
        ).map(::at)
        assertEquals(order.sorted(), order)

        // The run's own statements are numbered without a gap; the few that are not part of it carry no id.
        val ids = ch.sent.filter { it.executed }.mapNotNull { it.queryId }
        assertEquals(ids.indices.map { "granulee-7f3a-${it + 1}" }, ids)
        assertEquals(
            listOf("CREATE DATABASE", "SYSTEM FLUSH LOGS", "DROP TABLE IF EXISTS `scratch`.`events_trial_7f3a_codecs`", "DROP TABLE IF EXISTS `scratch`.`events_trial_7f3a_probe`"),
            ch.sent.filter { it.executed && it.queryId == null }.map { s -> listOf("CREATE DATABASE", "SYSTEM FLUSH LOGS").firstOrNull { s.sql.startsWith(it) } ?: s.sql.removeSuffix(" SYNC") },
        )
    }

    @Test
    fun `a logged query is pointed at each table in turn and runs where the session that logged it was`() {
        val ch = server()
        val outcome = run(ch)
        val replayed = ch.sent.filter { it.executed && it.sql.endsWith("WHERE id = 1") }
        assertEquals(
            listOf("base", "cand", "plain", "base", "cand", "plain").map { "SELECT count() FROM `scratch`.`events_trial_7f3a_$it` WHERE id = 1" },
            replayed.map { it.sql },
        )
        assertTrue(replayed.all { it.database == "analytics" && it.settings == mapOf("use_query_cache" to "0", "max_execution_time" to "120") })

        val q = outcome.result.queries.single()
        assertEquals(listOf("Q1", "SELECT count() FROM analytics.events_buf WHERE id = ?"), listOf(q.label, q.query))
        assertEquals(0.75, q.share)
        assertEquals(listOf(250.0, 250.0, 250.0), listOf(q.baseMs, q.candMs, q.plainMs))
        assertEquals(listOf(250.0, 250.0), q.baseRuns)
        assertNotNull(q.baseExplain)
        assertNotNull(q.plainExplain)
        // This server has no EXPLAIN ANALYZE, and its query_log has nothing on the runs.
        assertNull(q.baseAnalyze)
        assertNull(q.baseCpuMs)
    }

    @Test
    fun `the codecs of the largest columns are compared in the candidate's row order`() {
        val ch = server()
        val outcome = run(ch)
        val lab = ch.executed.first { it.startsWith("CREATE TABLE `scratch`.`events_trial_7f3a_codecs`") }
        assertTrue(lab.contains("    `id` UInt64,\n    `ts` DateTime,\n    `c0_v0` String CODEC(ZSTD(1)),\n    `c0_v1` String CODEC(ZSTD(3)),"), lab)
        assertTrue(lab.endsWith("PARTITION BY toYYYYMM(ts)\nORDER BY (id, ts)\nSETTINGS min_bytes_for_wide_part = 0, min_rows_for_wide_part = 0, index_granularity = 4096"), lab)

        val (url, id) = outcome.result.codecs
        assertEquals(listOf("url", "String", "ZSTD(1)", "ZSTD(3)", "ZSTD(3)"), listOf(url.name, url.type, url.production, url.candidate, url.best))
        assertEquals(listOf("ZSTD(3)", "ZSTD(1)", "LZ4"), url.variants.map { it.codec })
        // The plain codec that weighs exactly as much as the default is the default, and is not listed twice.
        assertEquals(listOf("id", "", "T64, ZSTD(1)", "LZ4"), listOf(id.name, id.production, id.best, id.defaultIs))
        assertEquals(listOf("T64, ZSTD(1)", "ZSTD(1)", ""), id.variants.map { it.codec })
        assertTrue(outcome.state.log.last().message.startsWith("Compared 11 codec variants for the 2 largest columns in the candidate's row order: a better one for 2"), outcome.state.log.last().message)
    }

    @Test
    fun `the trial tells how far it is and what it did`() {
        val outcome = run(server())
        assertEquals(List(8) { "done" }.dropLast(1) + "running", outcome.state.steps.map { it.state })
        assertEquals(97, outcome.state.progress)
        assertEquals(listOf(0, 2), listOf(outcome.state.changedKeys, outcome.state.changedColumns))
        assertEquals(5010L + 4010 + 9010, outcome.state.scratchBytes)
        assertEquals(
            listOf(
                "Created both tables in scratch",
                "Copied 1,000 rows from analytics.events into the baseline (0.3 s)",
                "Copied the same rows from the baseline into the candidate (0.3 s), so both hold identical data",
                "Copied the same rows into a table with plain defaults: no skip indexes, the server's default codec (0.3 s)",
                "Timed the same insert into a second baseline table (0.3 s) to compare insert throughput",
                "Fully merged both tables and read sizes from system.parts and system.columns",
                "Scanned every column 2 times per table",
                "Replayed 1 queries × 2 runs per table, taking turns, and read their plans with EXPLAIN",
            ),
            outcome.state.log.dropLast(1).map { it.message },
        )
        assertEquals(listOf("size", "ratio", "query", "read", "parts", "insert", "merge", "pk"), outcome.result.metrics.map { it.key })
        assertTrue(outcome.result.alterDdl.contains("MODIFY COLUMN `country` LowCardinality(Nullable(String))"))
    }

    @Test
    fun `a trial that asks for less does less`() {
        val ch = server()
        val small = request.copy(plainDefaults = false, codecBakeoff = false, columnScans = false, replayQueries = false, measureInserts = false, optimizeFinal = false)
        val outcome = run(ch, small)
        assertEquals(
            listOf("CREATE DATABASE", "CREATE TABLE", "CREATE TABLE", "INSERT INTO", "INSERT INTO"),
            ch.executed.map { it.substringBefore(" `").substringBefore(" IF") },
        )
        assertEquals(listOf("done", "done", "done", "running"), outcome.state.steps.map { it.state })
        assertEquals("Read sizes from system.parts and system.columns", outcome.state.log.last().message)
        assertEquals(listOf("size", "ratio", "parts", "pk"), outcome.result.metrics.map { it.key })
        assertTrue(outcome.result.queries.isEmpty() && outcome.result.codecs.isEmpty())
    }

    @Test
    fun `a table with JSON columns is copied into compact parts with few threads and made wide before the merge`() {
        val ch = server(extraColumn = column("payload", "JSON(max_dynamic_paths = 64)"))
        val json = request.copy(columns = request.columns + TrialColumn("payload", "JSON(max_dynamic_paths = 64)", ""), plainDefaults = false, codecBakeoff = false, replayQueries = false, columnScans = false, measureInserts = false)
        val outcome = run(ch, json)
        assertTrue(ch.executed[1].endsWith("SETTINGS min_bytes_for_wide_part = 1099511627776, min_rows_for_wide_part = 0, index_granularity = 4096"), ch.executed[1])
        assertTrue(ch.sent.filter { it.queryId != null }.all { it.settings["max_threads"] == "2" && it.settings["max_insert_threads"] == "1" })
        val alters = ch.executed.filter { it.startsWith("ALTER TABLE") }
        assertEquals(listOf("base", "cand").map { "ALTER TABLE `scratch`.`events_trial_7f3a_$it` MODIFY SETTING min_bytes_for_wide_part = 0, min_rows_for_wide_part = 0" }, alters)
        assertTrue(ch.executed.indexOf(alters.last()) < ch.executed.indexOfFirst { it.startsWith("OPTIMIZE") })
        assertTrue(outcome.state.log.first().message.startsWith("The table has JSON or Dynamic columns"))
    }

    @Test
    fun `a sample that selects nothing ends the run`() {
        val ch = server().apply { sent.clear() }
        val empty = FakeClickHouse().answer("SELECT toInt64(count()) AS c FROM `scratch`.`events_trial_7f3a_base`", mapOf("c" to 0))
        // The stand-in answers by its first matching rule: the empty count goes in front of the rest.
        val rules = empty.answer("", { sql -> ch.query(sql) })
        val e = assertFailsWith<ChException> { run(rules) }
        assertEquals("The sample selected no rows from analytics.events", e.message)
        assertTrue(rules.executed.none { it.startsWith("INSERT INTO `scratch`.`events_trial_7f3a_cand`") })
    }

    @Test
    fun `a cancelled trial sends nothing more`() {
        val ch = server()
        assertFailsWith<TrialCancelled> { run(ch, cancelAfter = 4) }
        // CREATE DATABASE, both tables and the first insert went out; the candidate's insert did not.
        assertEquals(4, ch.executed.size)
        assertTrue(ch.executed.last().startsWith("INSERT INTO `scratch`.`events_trial_7f3a_base`"))
    }

    @Test
    fun `a statement that fails because the trial was cancelled meanwhile counts as cancelled, any other as failed`() {
        var cancelled = false
        val ch = FakeClickHouse().answer("OPTIMIZE") {
            cancelled = true
            throw ChException("Query was cancelled")
        }.refuse("INSERT", "Memory limit exceeded")
        val running = mutableListOf<String?>()
        val statements = TrialStatements("7f3a", ch, cancelled = { cancelled }, running = { running.add(it) })
        assertEquals("Memory limit exceeded", assertFailsWith<ChException> { statements.exec("INSERT INTO t SELECT 1") }.message)
        assertFailsWith<TrialCancelled> { statements.exec("OPTIMIZE TABLE t FINAL") }
        assertFailsWith<TrialCancelled> { statements.exec("SELECT 1") }
        // Each statement that went out was announced with its id and taken back when it ended.
        assertEquals(listOf("granulee-7f3a-1", null, "granulee-7f3a-2", null), running)
    }
}
