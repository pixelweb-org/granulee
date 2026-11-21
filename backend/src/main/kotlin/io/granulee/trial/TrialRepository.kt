package io.granulee.trial

import io.granulee.NotFoundException
import io.granulee.state.JsonStore
import org.springframework.stereotype.Component
import java.security.SecureRandom

data class TrialFile(val trials: List<Trial> = emptyList())

/** The trials, in memory and as trials.json. Every change is written at once: a run reports its progress through here. */
@Component
class TrialRepository(private val store: JsonStore) {
    private val lock = Any()
    private val random = SecureRandom()
    private var trials: List<Trial> = store.read(FILE, TrialFile::class.java)?.trials ?: emptyList()

    fun all(): List<Trial> = synchronized(lock) { trials }

    fun get(id: String): Trial = all().firstOrNull { it.id == id } ?: throw NotFoundException("Trial #$id does not exist")

    /** Four hex digits no trial has yet: they are part of the names of its scratch tables. */
    fun newId(): String = synchronized(lock) {
        generateSequence { String.format("%04x", random.nextInt(0x10000)) }.first { candidate -> trials.none { it.id == candidate } }
    }

    fun add(trial: Trial) = synchronized(lock) { save(trials + trial) }

    fun remove(id: String) = synchronized(lock) { save(trials.filter { it.id != id }) }

    fun update(id: String, change: (Trial) -> Trial): Trial = synchronized(lock) {
        val current = trials.first { it.id == id }
        val next = change(current)
        save(trials.map { if (it.id == id) next else it })
        next
    }

    fun updateAll(change: (Trial) -> Trial) = synchronized(lock) { save(trials.map(change)) }

    private fun save(next: List<Trial>) {
        trials = next
        store.write(FILE, TrialFile(next))
    }

    private companion object {
        const val FILE = "trials.json"
    }
}
