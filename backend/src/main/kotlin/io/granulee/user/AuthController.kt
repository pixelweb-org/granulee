package io.granulee.user

import io.granulee.NotSignedInException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

data class LoginRequest(val username: String = "", val password: String = "")

data class PasswordChange(val current: String = "", val password: String = "")

/** The signed-in account as the page needs it. */
data class Me(val id: String, val username: String, val role: String, val mustChangePassword: Boolean)

private fun User.toMe() = Me(id, username, role, mustChangePassword)

/** Signing in and out, and one's own password. */
@RestController
@RequestMapping("/api/auth")
class AuthController(private val users: UserService, private val sessions: Sessions, private val throttle: LoginThrottle) {
    private fun account() = CurrentUser.account() ?: throw NotSignedInException()

    @PostMapping("/login")
    fun login(@RequestBody login: LoginRequest, request: HttpServletRequest, response: HttpServletResponse): ResponseEntity<Any> {
        val address = request.remoteAddr.orEmpty()
        val wait = throttle.waitSeconds(login.username, address)
        if (wait > 0) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", wait.toString())
                .body(mapOf("message" to "Too many failed sign-ins. Try again in ${if (wait < 90) "a minute" else "${(wait + 59) / 60} minutes"}."))
        }
        val user = users.authenticate(login.username, login.password)
        if (user == null) {
            throttle.failed(login.username, address)
            // The same answer for an unknown name, a wrong password and a deactivated account.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("message" to "Wrong user name or password"))
        }
        throttle.succeeded(login.username)
        SessionCookie.set(request, response, sessions.open(user.id))
        return ResponseEntity.ok(user.toMe())
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout(request: HttpServletRequest, response: HttpServletResponse) {
        sessions.close(account().sessionHash)
        SessionCookie.clear(request, response)
    }

    @GetMapping("/me")
    fun me(): Me = account().let { Me(it.id, it.username, it.role, it.mustChangePassword) }

    @PostMapping("/password")
    fun changePassword(@RequestBody change: PasswordChange): Me = users.changePassword(account(), change.current, change.password).toMe()
}

data class NewUser(val username: String = "", val password: String = "", val role: String = Role.USER)

data class NewPassword(val password: String = "")

/** What an admin does with the accounts. Reachable for admins only; see SecurityConfiguration. */
@RestController
@RequestMapping("/api/users")
class UserController(private val users: UserService) {
    private fun account() = CurrentUser.account() ?: throw NotSignedInException()

    @GetMapping
    fun list(): List<UserDto> = users.all().map { it.toDto() }

    @PostMapping
    fun create(@RequestBody user: NewUser): UserDto = users.create(user.username, user.password, user.role).toDto()

    @PostMapping("/{id}/activate")
    fun activate(@PathVariable id: String): UserDto = users.setActive(account(), id, true).toDto()

    @PostMapping("/{id}/deactivate")
    fun deactivate(@PathVariable id: String): UserDto = users.setActive(account(), id, false).toDto()

    @PostMapping("/{id}/password")
    fun resetPassword(@PathVariable id: String, @RequestBody password: NewPassword): UserDto = users.resetPassword(account(), id, password.password).toDto()
}
