package io.granulee.trial

import io.granulee.NotFoundException
import io.granulee.user.CurrentUser
import org.springframework.stereotype.Component
import java.security.SecureRandom

/** Where the trials are kept. */
interface TrialStorage {
    fun all(): List<Trial>

    /** Stores the trial, in place of the one with its id if there is one. */
    fun save(trial: Trial)

    fun remove(id: String)
}

/**
 * The trials of every user, in memory and in the storage. Every change is stored at once: a run reports its
 * progress through here. A trial belongs to the user who started it. `mine` and `owned` are what a request uses;
 * `all`, `get` and the updates are for the run itself, which goes on without anybody signed in.
 */
@Component
class TrialRepository(private val storage: TrialStorage) {
    private val lock = Any()
    private val random = SecureRandom()
    private var trials: List<Trial> = storage.all()

    fun all(): List<Trial> = synchronized(lock) { trials }

    /** The signed-in user's trials. */
    fun mine(): List<Trial> = CurrentUser.id().let { owner -> all().filter { it.ownerId == owner } }

    /** One of the signed-in user's trials. Somebody else's is as absent as one that does not exist. */
    fun owned(id: String): Trial = mine().firstOrNull { it.id == id } ?: throw NotFoundException("Trial #$id does not exist")

    fun get(id: String): Trial = all().firstOrNull { it.id == id } ?: throw NotFoundException("Trial #$id does not exist")

    /** Four hex digits no trial has yet: they are part of the names of its scratch tables. */
    fun newId(): String = synchronized(lock) {
        generateSequence { String.format("%04x", random.nextInt(0x10000)) }.first { candidate -> trials.none { it.id == candidate } }
    }

    fun add(trial: Trial) = synchronized(lock) {
        storage.save(trial)
        trials = trials + trial
    }

    fun remove(id: String) = synchronized(lock) {
        storage.remove(id)
        trials = trials.filter { it.id != id }
    }

    fun update(id: String, change: (Trial) -> Trial): Trial = synchronized(lock) {
        val current = trials.first { it.id == id }
        val next = change(current)
        storage.save(next)
        trials = trials.map { if (it.id == id) next else it }
        next
    }

    fun updateAll(change: (Trial) -> Trial) = synchronized(lock) {
        trials = trials.map { current -> change(current).also { if (it != current) storage.save(it) } }
    }
}
