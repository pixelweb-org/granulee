package io.granulee.user

/** What an account may do: an admin also manages the accounts. Nobody sees another account's data sources. */
object Role {
    const val ADMIN = "admin"
    const val USER = "user"
}

/** An account as it is stored. The password is an Argon2id hash. */
data class User(
    val id: String,
    val username: String,
    val passwordHash: String,
    val role: String, // admin | user
    val active: Boolean,
    /** Set while the password is one somebody else chose or saw: the first start's, or one an admin set. */
    val mustChangePassword: Boolean,
    val createdAt: String,
    val lastLoginAt: String? = null,
)

/** Where the accounts are kept. User names are unique without regard to case. */
interface UserStorage {
    fun all(): List<User>

    fun find(id: String): User?

    fun findByName(username: String): User?

    fun count(): Int

    fun add(user: User)

    fun update(user: User)
}

/** A signed-in browser. Only the SHA-256 of its token is stored, so a copy of the storage opens no session. */
data class Session(val tokenHash: String, val userId: String, val createdAt: Long, val lastSeenAt: Long)

/** Where the sessions are kept. Times are epoch seconds. */
interface SessionStorage {
    fun find(tokenHash: String): Session?

    fun add(session: Session)

    fun touch(tokenHash: String, lastSeenAt: Long)

    fun remove(tokenHash: String)

    /** Ends every session of a user, except the one named. */
    fun removeOf(userId: String, exceptTokenHash: String? = null)

    /** Drops the sessions that have run out: unused since `idleBefore`, or older than `createdBefore`. */
    fun removeExpired(idleBefore: Long, createdBefore: Long)
}

/** The account as the API shows it: never the hash. */
data class UserDto(
    val id: String,
    val username: String,
    val role: String,
    val active: Boolean,
    val mustChangePassword: Boolean,
    val createdAt: String,
    val lastLoginAt: String?,
)

fun User.toDto() = UserDto(id, username, role, active, mustChangePassword, createdAt, lastLoginAt)
