package io.granulee.user

import io.granulee.BadRequestException
import io.granulee.NotFoundException
import io.granulee.state.GranuleeProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

private val USERNAME = Regex("^[A-Za-z0-9][A-Za-z0-9._@-]{1,63}$")

/** The accounts: the first one at the first start, the ones an admin adds, their passwords and whether they may sign in. */
@Component
class UserService(
    private val users: UserStorage,
    private val sessions: SessionStorage,
    private val passwords: Passwords,
    announcer: AdminAnnouncer,
    props: GranuleeProperties,
) {
    private val log = LoggerFactory.getLogger(UserService::class.java)

    init {
        if (users.count() == 0) {
            // The first start: one admin, whose password nobody chose and only the console sees.
            val password = passwords.generate()
            users.add(User(newId(), FIRST_ADMIN, passwords.hash(password), Role.ADMIN, active = true, mustChangePassword = true, createdAt = now()))
            announcer.announce(FIRST_ADMIN, password)
        } else if (props.resetAdmin.isNotBlank()) {
            val user = users.findByName(props.resetAdmin.trim())
            if (user == null) {
                log.warn("GRANULEE_RESET_ADMIN names {}, which is not a user. Nothing was reset.", props.resetAdmin.trim())
            } else {
                val password = passwords.generate()
                users.update(user.copy(passwordHash = passwords.hash(password), role = Role.ADMIN, active = true, mustChangePassword = true))
                sessions.removeOf(user.id)
                announcer.announce(user.username, password)
                log.warn("The password of {} was reset because GRANULEE_RESET_ADMIN is set. Remove the setting now, or every start resets it again.", user.username)
            }
        }
    }

    fun all(): List<User> = users.all().sortedBy { it.username.lowercase() }

    fun find(id: String): User = users.find(id) ?: throw NotFoundException("User $id does not exist")

    /** The ids of the accounts that may sign in. Work that runs by itself is only done for these. */
    fun activeIds(): Set<String> = users.all().filter { it.active }.map { it.id }.toSet()

    /** The account these credentials open, or null. An unknown name costs as much time as a wrong password. */
    fun authenticate(username: String, password: String): User? {
        val user = users.findByName(username.trim())
        if (!passwords.matches(password, user?.passwordHash) || user == null || !user.active) return null
        return user.copy(lastLoginAt = now()).also(users::update)
    }

    /** A new account. Its password is one the admin chose, so it has to be changed at the first sign-in. */
    fun create(username: String, password: String, role: String): User {
        val name = username.trim()
        if (!USERNAME.matches(name)) throw BadRequestException("A user name is 2 to 64 letters, digits, dots, dashes, underscores or @")
        if (role != Role.ADMIN && role != Role.USER) throw BadRequestException("Unknown role")
        if (users.findByName(name) != null) throw BadRequestException("A user named $name already exists")
        Passwords.problem(name, password)?.let { throw BadRequestException(it) }
        return User(newId(), name, passwords.hash(password), role, active = true, mustChangePassword = true, createdAt = now()).also(users::add)
    }

    /** Lets an account sign in again, or shuts it out and ends its sessions. Its data sources and trials stay. */
    fun setActive(actor: Account, id: String, active: Boolean): User {
        val user = find(id)
        if (user.active == active) return user
        if (!active) {
            if (user.id == actor.id) throw BadRequestException("You cannot deactivate your own account")
            if (user.role == Role.ADMIN && users.all().count { it.active && it.role == Role.ADMIN } <= 1) {
                throw BadRequestException("${user.username} is the last active admin")
            }
        }
        val changed = user.copy(active = active).also(users::update)
        if (!active) sessions.removeOf(id)
        return changed
    }

    /** An admin gives another account a new password, which that user has to change at the next sign-in. */
    fun resetPassword(actor: Account, id: String, password: String): User {
        val user = find(id)
        if (user.id == actor.id) throw BadRequestException("Change your own password under Change password")
        Passwords.problem(user.username, password)?.let { throw BadRequestException(it) }
        val changed = user.copy(passwordHash = passwords.hash(password), mustChangePassword = true).also(users::update)
        sessions.removeOf(id)
        return changed
    }

    /** A user's own new password. Every other session of the account ends. */
    fun changePassword(actor: Account, current: String, password: String): User {
        val user = find(actor.id)
        if (!passwords.matches(current, user.passwordHash)) throw BadRequestException("The current password is not right")
        if (password == current) throw BadRequestException("The new password must differ from the current one")
        Passwords.problem(user.username, password)?.let { throw BadRequestException(it) }
        val changed = user.copy(passwordHash = passwords.hash(password), mustChangePassword = false).also(users::update)
        sessions.removeOf(user.id, exceptTokenHash = actor.sessionHash)
        return changed
    }

    private fun newId() = UUID.randomUUID().toString()

    private fun now() = Instant.now().toString()

    companion object {
        const val FIRST_ADMIN = "admin"
    }
}
