package io.granulee.user

import org.springframework.stereotype.Component

/** Where a one-time password made at start is shown. It is shown here once and kept nowhere but as a hash. */
fun interface AdminAnnouncer {
    fun announce(username: String, password: String)
}

/** Standard output: the console, or `docker logs`. Not the logger, whose lines may be shipped elsewhere. */
@Component
class ConsoleAnnouncer : AdminAnnouncer {
    override fun announce(username: String, password: String) {
        val line = "=".repeat(72)
        println(
            """
            |$line
            |  Granulee sign-in
            |
            |    User name:  $username
            |    Password:   $password
            |
            |  This password is shown only once and has to be changed at the first sign-in.
            |$line
            """.trimMargin(),
        )
    }
}
