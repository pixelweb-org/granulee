package io.granulee.user

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Slows guessing down. After five failed sign-ins in a row for a user name, the name has to wait a minute, and
 * twice as long after every further failure, up to fifteen minutes; an address gets the same after twenty.
 * Nothing is locked for good, so nobody can shut an admin out by guessing. Kept in memory: a restart forgets it.
 */
@Component
class LoginThrottle {
    private class Entry(var failures: Int = 0, var blockedUntil: Long = 0, var lastAt: Long = 0)

    private val entries = ConcurrentHashMap<String, Entry>()

    /** Epoch seconds; a test moves it. */
    internal var clock: () -> Long = { System.currentTimeMillis() / 1000 }

    /** How many seconds a sign-in with this name from this address still has to wait; 0 when it may go ahead. */
    fun waitSeconds(username: String, address: String): Long {
        val now = clock()
        return listOf(name(username), address(address)).maxOf { key -> ((entries[key]?.blockedUntil ?: 0) - now).coerceAtLeast(0) }
    }

    fun failed(username: String, address: String) {
        val now = clock()
        if (entries.size > MAX_ENTRIES) entries.entries.removeIf { now - it.value.lastAt > FORGET_AFTER }
        note(name(username), NAME_LIMIT, now)
        note(address(address), ADDRESS_LIMIT, now)
    }

    fun succeeded(username: String) {
        entries.remove(name(username))
    }

    private fun note(key: String, limit: Int, now: Long) {
        entries.compute(key) { _, old ->
            val entry = old?.takeIf { now - it.lastAt <= FORGET_AFTER } ?: Entry()
            entry.failures++
            entry.lastAt = now
            if (entry.failures >= limit) entry.blockedUntil = now + (60L shl (entry.failures - limit).coerceAtMost(4)).coerceAtMost(MAX_WAIT)
            entry
        }
    }

    private fun name(username: String) = "name:" + username.trim().lowercase().take(64)

    private fun address(address: String) = "address:$address"

    private companion object {
        const val NAME_LIMIT = 5
        const val ADDRESS_LIMIT = 20
        const val MAX_WAIT = 900L
        const val FORGET_AFTER = 3600L
        const val MAX_ENTRIES = 10_000
    }
}
