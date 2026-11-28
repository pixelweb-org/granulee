package io.granulee.user

import io.granulee.BadRequestException
import io.granulee.InMemorySessionStorage
import io.granulee.InMemoryUserStorage
import io.granulee.NotFoundException
import io.granulee.SignedIn
import io.granulee.state.GranuleeProperties
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserServiceTest {
    private val users = InMemoryUserStorage()
    private val sessionStorage = InMemorySessionStorage()
    private val passwords = Passwords()
    private val announced = mutableListOf<Pair<String, String>>()

    /** A start of Granulee on the same storage. */
    private fun start(resetAdmin: String = "") =
        UserService(users, sessionStorage, passwords, { name, password -> announced.add(name to password) }, GranuleeProperties(resetAdmin = resetAdmin))

    private fun admin() = users.findByName("admin")!!.let { Account(it.id, it.username, it.role, it.mustChangePassword, "admin-session") }

    @Test
    fun `the first start makes one admin with a random password, announced once and stored as a hash only`() {
        val service = start()
        val (name, password) = announced.single()
        assertEquals("admin", name)
        assertTrue(Regex("[A-Za-z0-9]{24}").matches(password))
        val admin = users.all().single()
        assertEquals(listOf("admin", Role.ADMIN, true, true), listOf(admin.username, admin.role, admin.active, admin.mustChangePassword))
        assertTrue(admin.passwordHash.startsWith("\$argon2id\$") && password !in admin.toString())
        assertNotNull(service.authenticate("admin", password))

        // The next start announces nothing and makes no second admin, and another first start gets another password.
        start()
        assertEquals(1, announced.size)
        assertEquals(1, users.count())
        val elsewhere = mutableListOf<String>()
        UserService(InMemoryUserStorage(), InMemorySessionStorage(), passwords, { _, p -> elsewhere.add(p) }, GranuleeProperties())
        assertTrue(elsewhere.single() != password)
    }

    @Test
    fun `the console shows the password once, and the account's DTO never shows the hash`() {
        val out = ByteArrayOutputStream()
        val console = System.out
        System.setOut(PrintStream(out))
        try {
            ConsoleAnnouncer().announce("admin", "not-a-real-password-1234")
        } finally {
            System.setOut(console)
        }
        assertEquals(1, Regex("not-a-real-password-1234").findAll(out.toString()).count())
        assertTrue("admin" in out.toString() && "only once" in out.toString())
        start()
        assertTrue("argon2" !in users.all().single().toDto().toString())
    }

    @Test
    fun `signing in needs the right password of an active account, whatever the case of the name`() {
        val service = start()
        val password = announced.single().second
        assertNull(users.all().single().lastLoginAt)
        assertEquals("admin", service.authenticate(" Admin ", password)?.username)
        assertNotNull(users.all().single().lastLoginAt)
        assertNull(service.authenticate("admin", password + "x"))
        assertNull(service.authenticate("admin", ""))
        assertNull(service.authenticate("nobody", password))
        users.update(users.all().single().copy(active = false))
        assertNull(service.authenticate("admin", password))
    }

    @Test
    fun `an admin adds accounts, whose first password is one-time`() {
        val service = start()
        val ann = service.create(" ann ", "one-time for ann", Role.USER)
        assertEquals(listOf("ann", Role.USER, true, true), listOf(ann.username, ann.role, ann.active, ann.mustChangePassword))
        assertEquals(listOf("admin", "ann"), service.all().map { it.username })
        assertEquals(ann.id, service.authenticate("ann", "one-time for ann")?.id)

        fun refused(name: String, password: String = "one-time password", role: String = Role.USER) =
            assertFailsWith<BadRequestException> { service.create(name, password, role) }.message
        assertEquals("A user named ANN already exists", refused("ANN"))
        assertEquals("A user name is 2 to 64 letters, digits, dots, dashes, underscores or @", refused("a b"))
        assertEquals("A user name is 2 to 64 letters, digits, dots, dashes, underscores or @", refused(""))
        assertEquals("Unknown role", refused("ben", role = "root"))
        assertEquals("The password must have at least 12 characters", refused("ben", "short"))
        assertEquals("The password must not be the user name", refused("benjamin.long", "Benjamin.Long"))
        assertEquals("The password must have at most 256 characters", refused("ben", "x".repeat(257)))
    }

    @Test
    fun `a user's own new password needs the current one, ends the one-time state and every other session`() {
        val service = start()
        val oneTime = announced.single().second
        val sessions = Sessions(sessionStorage, users)
        val here = sessions.open(admin().id)
        val elsewhere = sessions.open(admin().id)
        val account = sessions.resolve(here)!!
        assertTrue(account.mustChangePassword)

        fun refused(current: String, password: String) = assertFailsWith<BadRequestException> { service.changePassword(account, current, password) }.message
        assertEquals("The current password is not right", refused("wrong", "a password of my own"))
        assertEquals("The new password must differ from the current one", refused(oneTime, oneTime))
        assertEquals("The password must have at least 12 characters", refused(oneTime, "short"))

        assertTrue(!service.changePassword(account, oneTime, "a password of my own").mustChangePassword)
        assertNull(service.authenticate("admin", oneTime))
        assertNotNull(service.authenticate("admin", "a password of my own"))
        assertEquals(false, sessions.resolve(here)?.mustChangePassword)
        assertNull(sessions.resolve(elsewhere))
    }

    @Test
    fun `an admin deactivates and resets other accounts, but not the own one and never the last admin`() {
        val service = start()
        val admin = admin()
        val ann = service.create("ann", "one-time for ann", Role.USER)
        val sessions = Sessions(sessionStorage, users)
        val annsSession = sessions.open(ann.id)

        assertTrue(!service.setActive(admin, ann.id, false).active)
        assertNull(sessions.resolve(annsSession))
        assertNull(service.authenticate("ann", "one-time for ann"))
        assertEquals(setOf(admin.id), service.activeIds())
        assertTrue(service.setActive(admin, ann.id, true).active)
        assertEquals(setOf(admin.id, ann.id), service.activeIds())

        val again = sessions.open(ann.id)
        users.update(users.find(ann.id)!!.copy(mustChangePassword = false))
        assertTrue(service.resetPassword(admin, ann.id, "another one-time").mustChangePassword)
        assertNull(sessions.resolve(again))
        assertNotNull(service.authenticate("ann", "another one-time"))
        assertEquals("The password must have at least 12 characters", assertFailsWith<BadRequestException> { service.resetPassword(admin, ann.id, "short") }.message)

        assertEquals("You cannot deactivate your own account", assertFailsWith<BadRequestException> { service.setActive(admin, admin.id, false) }.message)
        assertEquals("Change your own password under Change password", assertFailsWith<BadRequestException> { service.resetPassword(admin, admin.id, "another one-time") }.message)
        // A second admin may not remove the first while being deactivated itself would leave none.
        val second = service.create("second", "one-time for second", Role.ADMIN)
        service.setActive(admin, second.id, false)
        assertEquals("admin is the last active admin", assertFailsWith<BadRequestException> { service.setActive(SignedIn.account(second.id, Role.ADMIN), admin.id, false) }.message)
        assertFailsWith<NotFoundException> { service.setActive(admin, "nobody", false) }
    }

    @Test
    fun `GRANULEE_RESET_ADMIN gives a named account a new one-time password and makes it an active admin`() {
        val service = start()
        val first = announced.single().second
        val ann = service.create("ann", "one-time for ann", Role.USER)
        service.setActive(admin(), ann.id, false)
        val session = Sessions(sessionStorage, users).open(ann.id)

        start(resetAdmin = "nobody")
        assertEquals(1, announced.size)

        val restarted = start(resetAdmin = "Ann")
        val (name, password) = announced.last()
        assertEquals(listOf("ann", 2), listOf(name, announced.size))
        val reset = restarted.authenticate("ann", password)!!
        assertEquals(listOf(Role.ADMIN, true, true), listOf(reset.role, reset.active, reset.mustChangePassword))
        assertNull(restarted.authenticate("ann", "one-time for ann"))
        assertNull(sessionStorage.find(Sessions.hash(session)))
        // The other accounts are left alone.
        assertNotNull(restarted.authenticate("admin", first))
    }
}

class SessionsTest {
    private val users = InMemoryUserStorage().apply { add(User("u1", "ann", "hash", Role.USER, active = true, mustChangePassword = false, createdAt = "2026-11-21T10:00:00Z")) }
    private val storage = InMemorySessionStorage()
    private var now = 1_000_000L
    private val sessions = Sessions(storage, users).also { it.clock = { now } }
    private val day = 86_400L

    @Test
    fun `a token opens its session, and only its hash is stored`() {
        val token = sessions.open("u1")
        assertTrue(token.length >= 43 && token != sessions.open("u1"))
        assertEquals(Account("u1", "ann", Role.USER, false, Sessions.hash(token)), sessions.resolve(token))
        assertTrue(storage.sessions.keys.none { it == token } && Sessions.hash(token) in storage.sessions)
        assertNull(sessions.resolve("made-up"))
        assertNull(sessions.resolve(""))
        sessions.close(Sessions.hash(token))
        assertNull(sessions.resolve(token))
    }

    @Test
    fun `a session runs out after a week without use, and after thirty days however much it is used`() {
        val idle = sessions.open("u1")
        val busy = sessions.open("u1")
        repeat(4) {
            now += 6 * day
            assertNotNull(sessions.resolve(busy), "day ${6 * (it + 1)}")
        }
        assertNull(sessions.resolve(idle))
        now += 6 * day + 1
        assertNull(sessions.resolve(busy))
        assertTrue(storage.sessions.isEmpty())
    }

    @Test
    fun `a deactivated account's session is nobody, and the sweep drops what has run out`() {
        val token = sessions.open("u1")
        users.update(users.find("u1")!!.copy(active = false))
        assertNull(sessions.resolve(token))
        users.update(users.find("u1")!!.copy(active = true))
        assertNotNull(sessions.resolve(token))

        now += 8 * day
        val fresh = sessions.open("u1")
        sessions.sweep()
        assertEquals(setOf(Sessions.hash(fresh)), storage.sessions.keys)
    }
}

class LoginThrottleTest {
    private var now = 1_000_000L
    private val throttle = LoginThrottle().also { it.clock = { now } }

    @Test
    fun `five failures in a row make a name wait, longer each time, and a success clears it`() {
        repeat(4) { throttle.failed("Ann", "10.0.0.1") }
        assertEquals(0, throttle.waitSeconds("ann", "10.0.0.1"))
        throttle.failed("ann ", "10.0.0.2")
        assertEquals(60, throttle.waitSeconds("ANN", "10.0.0.9"))
        assertEquals(0, throttle.waitSeconds("ben", "10.0.0.1"))
        now += 60
        assertEquals(0, throttle.waitSeconds("ann", "10.0.0.1"))
        throttle.failed("ann", "10.0.0.1")
        assertEquals(120, throttle.waitSeconds("ann", "10.0.0.1"))
        repeat(10) { throttle.failed("ann", "10.0.0.3") }
        assertEquals(900, throttle.waitSeconds("ann", "10.0.0.1"))
        throttle.succeeded("ann")
        assertEquals(0, throttle.waitSeconds("ann", "10.0.0.1"))
    }

    @Test
    fun `twenty failures from one address make the address wait, whatever the names, and an hour forgets`() {
        repeat(20) { throttle.failed("name$it", "10.0.0.1") }
        assertEquals(60, throttle.waitSeconds("somebody-else", "10.0.0.1"))
        assertEquals(0, throttle.waitSeconds("somebody-else", "10.0.0.2"))
        now += 3601
        throttle.failed("name0", "10.0.0.1")
        assertEquals(0, throttle.waitSeconds("somebody-else", "10.0.0.1"))
    }
}
