package io.granulee.state

import io.granulee.recommendation.DismissedFile
import io.granulee.scan.HistoryFile
import io.granulee.scan.Snapshot
import io.granulee.source.SourceFile
import io.granulee.trial.TrialFile
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The files under resources/state are what release 0.19.1 wrote into its data directory (the stored password
 * replaced, and dismissed.json filled by hand in its format). Whatever the classes are called and wherever they
 * live, these files have to load, and written back they have to be the same bytes: that is the format production
 * has on disk.
 */
class StoredFilesTest {
    @TempDir
    lateinit var dir: Path

    private fun fixture(name: String): String = checkNotNull(javaClass.getResource("/state/$name")) { "missing fixture $name" }.readText()

    private fun <T : Any> roundTrip(name: String, type: Class<T>): T {
        val original = fixture(name)
        Files.writeString(dir.resolve(name), original)
        val store = JsonStore(GranuleeProperties(dataDir = dir.toString()))
        val loaded = assertNotNull(store.read(name, type))
        store.write("again-$name", loaded)
        assertEquals(original, Files.readString(dir.resolve("again-$name")), "$name does not come back as it was stored")
        return loaded
    }

    @Test
    fun `sources load and are written back unchanged`() {
        val source = roundTrip("sources.json", SourceFile::class.java).sources.single()
        assertEquals("local", source.name)
        assertEquals("healthy", source.status.state)
        assertEquals(64, source.lastScan?.durationMs)
    }

    @Test
    fun `a scan snapshot loads and is written back unchanged`() {
        val snapshot = roundTrip("snapshot.json", Snapshot::class.java)
        assertEquals("ok", snapshot.queryLog?.state)
        val events = assertNotNull(snapshot.table("analytics", "events"))
        assertEquals(listOf("analytics.events_buf"), events.buffers)
        assertNotNull(events.queries.first().filters)
    }

    @Test
    fun `trials load and are written back unchanged`() {
        val trials = roundTrip("trials.json", TrialFile::class.java).trials
        assertEquals(listOf("completed", "completed", "completed"), trials.map { it.status })
        val applied = trials.first { it.apply != null }
        assertEquals("completed", applied.optimize?.status)
        assertEquals(11, applied.result?.codecs?.size)
        assertNotNull(trials.first { it.table == "events" }.result?.queries?.first()?.baseExplain)
    }

    @Test
    fun `the size history and the dismissed recommendations load and are written back unchanged`() {
        assertEquals(664705678, roundTrip("history.json", HistoryFile::class.java).points.single().compressedBytes)
        assertEquals(setOf("codec:url", "sorting-key"), roundTrip("dismissed.json", DismissedFile::class.java).dismissed.values.single())
    }

    @Test
    fun `a file that is not there is nothing, and a directory lists its files`() {
        val store = JsonStore(GranuleeProperties(dataDir = dir.toString()))
        assertEquals(null, store.read("sources.json", SourceFile::class.java))
        assertEquals(emptyList(), store.list("snapshots"))
        store.write("snapshots/b.json", Snapshot(sourceId = "b"))
        store.write("snapshots/a.json", Snapshot(sourceId = "a"))
        assertEquals(listOf("a.json", "b.json"), store.list("snapshots"))
        store.delete("snapshots/a.json")
        assertEquals(listOf("b.json"), store.list("snapshots"))
    }
}
