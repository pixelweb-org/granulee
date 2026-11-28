package io.granulee.user

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64

/**
 * Sign-in sessions. The browser holds a random token in a cookie; the storage holds its hash, so sessions outlive
 * a restart. A session ends when it was not used for a week, after thirty days at the latest, at sign-out, and
 * when its account is deactivated or gets a new password.
 */
@Component
class Sessions(private val sessions: SessionStorage, private val users: UserStorage) {
    private val random = SecureRandom()

    /** Epoch seconds; a test moves it. */
    internal var clock: () -> Long = { System.currentTimeMillis() / 1000 }

    /** Starts a session and returns the token for the cookie. */
    fun open(userId: String): String {
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        val now = clock()
        sessions.add(Session(hash(token), userId, now, now))
        return token
    }

    /** The account a token stands for, when its session is still good and the account may sign in. */
    fun resolve(token: String): Account? {
        if (token.isBlank()) return null
        val tokenHash = hash(token)
        val session = sessions.find(tokenHash) ?: return null
        val now = clock()
        if (now - session.lastSeenAt > IDLE.seconds || now - session.createdAt > LIFETIME.seconds) {
            sessions.remove(tokenHash)
            return null
        }
        val user = users.find(session.userId)?.takeIf { it.active } ?: return null
        // Not on every request: once a minute tells idle from in use well enough.
        if (now - session.lastSeenAt >= 60) sessions.touch(tokenHash, now)
        return Account(user.id, user.username, user.role, user.mustChangePassword, tokenHash)
    }

    fun close(tokenHash: String) = sessions.remove(tokenHash)

    @Scheduled(initialDelay = 60_000, fixedDelay = 3_600_000)
    fun sweep() {
        val now = clock()
        sessions.removeExpired(idleBefore = now - IDLE.seconds, createdBefore = now - LIFETIME.seconds)
    }

    companion object {
        val IDLE: Duration = Duration.ofDays(7)
        val LIFETIME: Duration = Duration.ofDays(30)

        fun hash(token: String): String =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
