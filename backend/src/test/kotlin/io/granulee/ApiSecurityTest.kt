package io.granulee

import io.granulee.clickhouse.ChConnection
import io.granulee.clickhouse.ChConnections
import io.granulee.clickhouse.Endpoint
import io.granulee.clickhouse.FakeClickHouse
import io.granulee.recommendation.DismissedStore
import io.granulee.scan.Snapshot
import io.granulee.scan.SnapshotStore
import io.granulee.source.SourceRepository
import io.granulee.sqlite.SqliteDatabase
import io.granulee.trial.Trial
import io.granulee.trial.TrialRepository
import io.granulee.trial.TrialRequest
import io.granulee.user.AdminAnnouncer
import io.granulee.user.SessionCookie
import io.granulee.user.UserService
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The one-time passwords a start made, kept for the test instead of printed: none reaches the build's output. */
class RecordingAnnouncer : AdminAnnouncer {
    val announced = mutableListOf<Pair<String, String>>()

    override fun announce(username: String, password: String) {
        announced.add(username to password)
    }
}

/**
 * The whole application over HTTP, with its real security and its real SQLite storage in an empty data directory,
 * and a stand-in for the ClickHouse servers. Three people use it: the admin of the first start, Alice and Bob.
 * Alice has a data source, a scan of it and a trial. Nothing of that may reach Bob or the admin, by any route.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ApiSecurityTest.Stubs::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiSecurityTest {
    @TestConfiguration
    class Stubs {
        @Bean
        @Primary
        fun recordingAnnouncer() = RecordingAnnouncer()

        @Bean
        @Primary
        fun fakeConnections(): ChConnections = object : ChConnections() {
            override fun get(endpoint: Endpoint): ChConnection = clickHouse
            override fun probe(endpoint: Endpoint): ChConnection = clickHouse
        }
    }

    companion object {
        val clickHouse = FakeClickHouse().answer("version()", mapOf("v" to "25.8.1.1"))

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            val dir = Files.createTempDirectory("granulee-test").toString()
            registry.add("granulee.data-dir") { dir }
        }

        const val ADMIN_PASSWORD = "the admin's own password"
        const val ALICE_PASSWORD = "alice's own password"
        const val BOB_PASSWORD = "bob's own password"
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var announcer: RecordingAnnouncer
    @Autowired lateinit var database: SqliteDatabase
    @Autowired lateinit var users: UserService
    @Autowired lateinit var sources: SourceRepository
    @Autowired lateinit var snapshots: SnapshotStore
    @Autowired lateinit var trials: TrialRepository
    @Autowired lateinit var dismissed: DismissedStore

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    lateinit var mappings: RequestMappingHandlerMapping

    /** One browser: it keeps the session cookie it was given and sends the CSRF token the way the page does. */
    inner class Browser(private val csrf: Boolean = true) {
        private var session: String? = null

        fun call(method: HttpMethod, path: String, body: String? = null): MockHttpServletResponse {
            var builder = request(method, path).accept(MediaType.APPLICATION_JSON)
            session?.let { builder = builder.cookie(Cookie(SessionCookie.NAME, it)) }
            if (csrf && method != HttpMethod.GET) builder = builder.cookie(Cookie("XSRF-TOKEN", "token")).header("X-XSRF-TOKEN", "token")
            if (body != null) builder = builder.contentType(MediaType.APPLICATION_JSON).content(body)
            val response = mvc.perform(builder).andReturn().response
            response.getHeaders("Set-Cookie").firstOrNull { it.startsWith(SessionCookie.NAME + "=") }?.let { header ->
                session = header.substringAfter("=").substringBefore(";").ifEmpty { null }
            }
            return response
        }

        fun get(path: String) = call(HttpMethod.GET, path)

        fun post(path: String, body: String = "{}") = call(HttpMethod.POST, path, body)

        fun signIn(username: String, password: String) = post("/api/auth/login", """{"username":"$username","password":"$password"}""")

        /** Signs in with a one-time password and chooses one's own, as the page makes a new user do. */
        fun firstSignIn(username: String, oneTime: String, own: String): Browser {
            assertEquals(200, signIn(username, oneTime).status)
            assertEquals(200, post("/api/auth/password", """{"current":"$oneTime","password":"$own"}""").status)
            return this
        }
    }

    private fun MockHttpServletResponse.id(): String = Regex("\"id\":\"([^\"]+)\"").find(contentAsString)!!.groupValues[1]

    private lateinit var admin: Browser
    private lateinit var alice: Browser
    private lateinit var bob: Browser
    private lateinit var aliceSource: String
    private lateinit var aliceId: String
    private val aliceTrial = "a11c"

    @BeforeAll
    fun people() {
        admin = Browser().firstSignIn("admin", announcer.announced.single().second, ADMIN_PASSWORD)
        aliceId = admin.post("/api/users", """{"username":"alice","password":"one-time for alice","role":"user"}""").also { assertEquals(200, it.status) }.id()
        assertEquals(200, admin.post("/api/users", """{"username":"bob","password":"one-time for bob","role":"user"}""").status)
        alice = Browser().firstSignIn("alice", "one-time for alice", ALICE_PASSWORD)
        bob = Browser().firstSignIn("bob", "one-time for bob", BOB_PASSWORD)

        val created = alice.post("/api/sources", """{"name":"prod","protocol":"http","host":"clickhouse.test","port":8123,"username":"granulee","password":"ch-secret","trialsEnabled":true}""")
        assertEquals(200, created.status, created.contentAsString)
        aliceSource = created.id()
        snapshots.save(Snapshot(sourceId = aliceSource, scannedAt = "2026-11-21T10:00:00Z"))
        dismissed.add(aliceSource, "analytics", "events", "codec:url")
        trials.add(
            Trial(
                id = aliceTrial, ownerId = aliceId, name = "Alice's trial", sourceId = aliceSource, sourceName = "prod", database = "analytics", table = "events",
                status = "completed", error = null, createdAt = "2026-11-21T10:00:00Z", finishedAt = "2026-11-21T10:05:00Z", progress = 100, steps = emptyList(),
                request = TrialRequest(sourceId = aliceSource, database = "analytics", table = "events"), scratchDatabase = "scratch",
                baseTable = "events_trial_a11c_base", candTable = "events_trial_a11c_cand", scratchBytes = 0, changedKeys = 0, changedColumns = 0, result = null, log = emptyList(),
            ),
        )
    }

    // ---- every route ----

    private data class Route(val method: HttpMethod, val pattern: String) {
        override fun toString() = "$method $pattern"
    }

    private fun apiRoutes(): List<Route> = mappings.handlerMethods.keys.flatMap { info ->
        info.pathPatternsCondition!!.patternValues.flatMap { pattern -> info.methodsCondition.methods.map { Route(HttpMethod.valueOf(it.name), pattern) } }
    }.filter { it.pattern.startsWith("/api/") }.sortedBy { it.toString() }

    /** The route with Alice's ids in every place that takes one: in the path, in the query and in the body. */
    private fun Browser.callWithAlicesIds(route: Route): MockHttpServletResponse {
        val id = if (route.pattern.startsWith("/api/trials")) aliceTrial else if (route.pattern.startsWith("/api/users")) aliceId else aliceSource
        val path = route.pattern.replace("{id}", id).replace("{db}", "analytics").replace("{table}", "events")
        val query = "?source=$aliceSource&database=analytics&table=events&hash=1"
        val body = """{"id":"$aliceSource","sourceId":"$aliceSource","sql":"","candidates":[]}"""
        return call(route.method, path + query, if (route.method == HttpMethod.GET || route.method == HttpMethod.DELETE) null else body)
    }

    /** The routes that name no source and no trial. A new route has to be put here or be covered by the ones that do. */
    private val withoutIds = setOf(
        "GET /api/health", "POST /api/auth/login", "POST /api/auth/logout", "GET /api/auth/me", "POST /api/auth/password",
        "GET /api/sources", "POST /api/sources", "POST /api/scan",
    )

    private fun namesAnId(route: Route) = "{id}" in route.pattern || route.toString() in setOf(
        "GET /api/overview", "GET /api/opportunities", "GET /api/trials", "GET /api/trials/plan", "POST /api/trials", "POST /api/sources/test",
    )

    @Test
    fun `without a session every route but the health check and the sign-in answers 401`() {
        val anonymous = Browser()
        val routes = apiRoutes()
        assertTrue(routes.size > 30, "the routes were not found: $routes")
        val open = routes.filter { it.toString() != "POST /api/auth/login" && anonymous.callWithAlicesIds(it).status != 401 }
        assertEquals(listOf("GET /api/health"), open.map { it.toString() })
        assertEquals(401, anonymous.get("/api/no-such-route").status)
        // What the page itself is made of holds no data and has to load before anybody can sign in.
        assertTrue(anonymous.get("/users").status != 401)
    }

    @Test
    fun `every route that names a source or a trial answers 404 for somebody else's, for a user and for an admin alike`() {
        val before = Triple(sources.all(), trials.all(), dismissed.of(aliceSource, "analytics", "events"))
        val routes = apiRoutes().filter { !it.pattern.startsWith("/api/users") }
        val unsorted = routes.filter { !namesAnId(it) && it.toString() !in withoutIds }
        assertEquals(emptyList(), unsorted, "routes this test does not know: decide whether they name a source or a trial")

        for ((who, browser) in listOf("bob" to bob, "admin" to admin)) {
            val reached = routes.filter(::namesAnId).mapNotNull { route ->
                val response = browser.callWithAlicesIds(route)
                if (response.status == 404) null else "$who: $route answered ${response.status} ${response.contentAsString.take(120)}"
            }
            assertEquals(emptyList(), reached)
        }
        // And nothing of Alice's was touched on the way.
        assertEquals(before, Triple(sources.all(), trials.all(), dismissed.of(aliceSource, "analytics", "events")))
        assertNotNull(snapshots.of(aliceSource))

        // The same calls reach Alice's own things, so the 404s above are about whose they are.
        assertEquals(200, alice.get("/api/sources/$aliceSource/databases").status)
        assertEquals(200, alice.get("/api/trials/$aliceTrial").status)
        assertEquals(200, alice.get("/api/overview?source=$aliceSource").status)
        // An id nobody has reads the same as somebody else's.
        assertEquals(
            bob.get("/api/trials/$aliceTrial").contentAsString.replace(aliceTrial, "ffff"),
            bob.get("/api/trials/ffff").contentAsString,
        )
    }

    @Test
    fun `lists hold one's own things only`() {
        assertTrue(aliceSource in alice.get("/api/sources").contentAsString)
        assertTrue(aliceTrial in alice.get("/api/trials").contentAsString)
        for (other in listOf(bob, admin)) {
            assertEquals("[]", other.get("/api/sources").contentAsString)
            assertEquals("[]", other.post("/api/scan").contentAsString)
            assertEquals("[]", other.get("/api/opportunities").contentAsString)
            val overview = other.get("/api/overview").contentAsString
            assertTrue("\"sources\":[]" in overview && "prod" !in overview, overview)
            val listed = other.get("/api/trials").contentAsString
            assertTrue("\"trials\":[]" in listed, listed)
        }
        // Bob may call his own source what Alice calls hers, and still only has his.
        val bobs = bob.post("/api/sources", """{"name":"prod","protocol":"http","host":"other.test","port":8123,"username":"granulee"}""")
        assertEquals(200, bobs.status, bobs.contentAsString)
        assertEquals(listOf(bobs.id()), Regex("\"id\":\"([^\"]+)\"").findAll(bob.get("/api/sources").contentAsString).map { it.groupValues[1] }.toList())
        assertFalse(bobs.id() in alice.get("/api/sources").contentAsString)
        assertEquals(204, bob.call(HttpMethod.DELETE, "/api/sources/${bobs.id()}").status)
    }

    // ---- signing in ----

    @Test
    fun `the first start made one admin, and its password is nowhere but in the announcement`() {
        val (username, password) = announcer.announced.single()
        assertEquals("admin", username)
        assertEquals(24, password.length)
        Files.list(database.file.parent).use { files ->
            files.filter { Files.isRegularFile(it) }.forEach { file ->
                assertFalse(password in String(Files.readAllBytes(file), Charsets.ISO_8859_1), "$file holds the first password")
            }
        }
        val listed = admin.get("/api/users").contentAsString
        assertFalse(password in listed || "argon2" in listed || "passwordHash" in listed, listed)
        assertFalse("argon2" in admin.get("/api/auth/me").contentAsString)
        // It was a one-time password: it does not sign in any more.
        assertEquals(401, Browser().signIn("admin", password).status)
    }

    @Test
    fun `a wrong password, an unknown name and a deactivated account get the same answer`() {
        assertEquals(200, admin.post("/api/users", """{"username":"dave","password":"one-time for dave"}""").status)
        val dave = Browser().firstSignIn("dave", "one-time for dave", "dave's own password")
        val daveId = dave.get("/api/auth/me").id()
        assertEquals(200, dave.get("/api/sources").status)

        assertEquals(200, admin.post("/api/users/$daveId/deactivate").status)
        // The session he had is over at once, and so is signing in.
        assertEquals(401, dave.get("/api/sources").status)
        val answers = listOf(
            Browser().signIn("dave", "dave's own password"),
            Browser().signIn("alice", "not alice's password"),
            Browser().signIn("nobody-by-that-name", "whatever it may be"),
        ).map { it.status to it.contentAsString }
        assertEquals(listOf(401 to """{"message":"Wrong user name or password"}"""), answers.distinct())

        assertEquals(200, admin.post("/api/users/$daveId/activate").status)
        assertEquals(200, Browser().signIn("dave", "dave's own password").status)
    }

    @Test
    fun `a session is a cookie the page's scripts cannot read, and signing out ends it`() {
        val browser = Browser()
        val response = browser.signIn("bob", BOB_PASSWORD)
        val cookie = response.getHeaders("Set-Cookie").single { it.startsWith(SessionCookie.NAME) }
        assertTrue("HttpOnly" in cookie && "SameSite=Lax" in cookie && "Path=/" in cookie, cookie)
        assertFalse(BOB_PASSWORD in response.contentAsString)
        assertEquals(200, browser.get("/api/auth/me").status)

        assertEquals(204, browser.post("/api/auth/logout").status)
        assertEquals(401, browser.get("/api/auth/me").status)
        // A made-up cookie is nobody.
        assertEquals(401, mvc.perform(request(HttpMethod.GET, "/api/sources").cookie(Cookie(SessionCookie.NAME, "made-up"))).andReturn().response.status)
    }

    @Test
    fun `a request that changes something needs the CSRF token`() {
        val forgetful = Browser(csrf = false)
        assertEquals(403, forgetful.signIn("bob", BOB_PASSWORD).status)
        assertEquals(403, forgetful.post("/api/scan").status)
        assertEquals(403, forgetful.call(HttpMethod.DELETE, "/api/sources/$aliceSource").status)
        // Reading sets the token cookie the page sends back.
        assertTrue(mvc.perform(request(HttpMethod.GET, "/api/auth/me")).andReturn().response.getHeaders("Set-Cookie").any { it.startsWith("XSRF-TOKEN=") })
    }

    @Test
    fun `an account with a one-time password reaches nothing until it has chosen its own`() {
        assertEquals(200, admin.post("/api/users", """{"username":"carol","password":"one-time for carol"}""").status)
        val carol = Browser()
        assertTrue("\"mustChangePassword\":true" in carol.signIn("carol", "one-time for carol").contentAsString)
        assertEquals(403 to """{"message":"Change your password first"}""", carol.get("/api/sources").let { it.status to it.contentAsString })
        assertEquals(403, carol.post("/api/sources", """{"name":"x"}""").status)
        assertEquals(200, carol.get("/api/auth/me").status)
        assertEquals(400, carol.post("/api/auth/password", """{"current":"one-time for carol","password":"short"}""").status)
        assertEquals(200, carol.post("/api/auth/password", """{"current":"one-time for carol","password":"carol's own password"}""").status)
        assertEquals("[]", carol.get("/api/sources").contentAsString)

        // An admin's reset puts the account back there and ends its sessions.
        val carolId = carol.get("/api/auth/me").id()
        assertEquals(200, admin.post("/api/users/$carolId/password", """{"password":"another one-time"}""").status)
        assertEquals(401, carol.get("/api/sources").status)
        assertEquals(403, Browser().also { it.signIn("carol", "another one-time") }.get("/api/sources").status)
    }

    @Test
    fun `only an admin manages the accounts, and never away the last admin`() {
        for (path in listOf("/api/users")) assertEquals(403, bob.get(path).status)
        assertEquals(403, bob.post("/api/users", """{"username":"eve","password":"one-time for eve","role":"admin"}""").status)
        assertEquals(403, bob.post("/api/users/$aliceId/deactivate").status)
        assertEquals(403, bob.post("/api/users/$aliceId/password", """{"password":"bob chose this one"}""").status)
        assertEquals(200, Browser().signIn("alice", ALICE_PASSWORD).status)

        val adminId = admin.get("/api/auth/me").id()
        assertEquals(400, admin.post("/api/users/$adminId/deactivate").status)
        assertEquals(400, admin.post("/api/users", """{"username":"ALICE","password":"one-time for alice"}""").status)
        assertEquals(400, admin.post("/api/users", """{"username":"frank","password":"too short"}""").status)
    }

    @Test
    fun `guessing is slowed down after five failures in a row`() {
        val guesser = Browser()
        repeat(5) { assertEquals(401, guesser.signIn("grace", "guess number $it ....").status) }
        val refused = guesser.signIn("grace", "guess number six")
        assertEquals(429, refused.status)
        assertEquals("60", refused.getHeader("Retry-After"))
        // Another name is not held up by it.
        assertEquals(200, Browser().signIn("alice", ALICE_PASSWORD).status)
    }
}
