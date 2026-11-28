package io.granulee.user

import jakarta.servlet.FilterChain
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseCookie
import org.springframework.security.authorization.AuthorizationDecision
import org.springframework.security.authorization.AuthorizationManager
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.intercept.AuthorizationFilter
import org.springframework.security.web.access.intercept.RequestAuthorizationContext
import org.springframework.security.web.csrf.CsrfException
import org.springframework.web.filter.OncePerRequestFilter

/** The session cookie: read here, set at sign-in, cleared at sign-out. JavaScript never sees it. */
object SessionCookie {
    const val NAME = "granulee_session"

    fun token(request: HttpServletRequest): String? = request.cookies?.firstOrNull { it.name == NAME }?.value

    fun set(request: HttpServletRequest, response: HttpServletResponse, token: String) = write(request, response, token, Sessions.LIFETIME.seconds)

    fun clear(request: HttpServletRequest, response: HttpServletResponse) = write(request, response, "", 0)

    private fun write(request: HttpServletRequest, response: HttpServletResponse, value: String, maxAge: Long) {
        val cookie = ResponseCookie.from(NAME, value).httpOnly(true).secure(request.isSecure).sameSite("Lax").path("/").maxAge(maxAge).build()
        response.addHeader("Set-Cookie", cookie.toString())
    }
}

/** Turns the session cookie of a request into its signed-in account. */
class SessionFilter(private val sessions: Sessions) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        // Spring Security empties the holder again when the request is over.
        SessionCookie.token(request)?.let(sessions::resolve)?.let { account ->
            SecurityContextHolder.getContext().authentication = AccountAuthentication(account)
        }
        chain.doFilter(request, response)
    }
}

/**
 * Who may reach what. Open are only the health check, the sign-in, and the page's own files, which hold no data.
 * Everything else under /api needs a session, and an account that still has a one-time password reaches nothing
 * but the endpoints that let it choose its own. Requests that change something need the CSRF token as well.
 */
@Configuration
@EnableWebSecurity
class SecurityConfiguration {
    private fun account() = CurrentUser.account()

    /** Signed in, with a password of one's own. */
    private val ready = AuthorizationManager<RequestAuthorizationContext> { _, _ -> AuthorizationDecision(account()?.mustChangePassword == false) }
    private val readyAdmin = AuthorizationManager<RequestAuthorizationContext> { _, _ ->
        AuthorizationDecision(account()?.let { it.admin && !it.mustChangePassword } == true)
    }

    @Bean
    fun securityFilterChain(http: HttpSecurity, sessions: Sessions): SecurityFilterChain {
        http {
            authorizeHttpRequests {
                authorize("/api/health", permitAll)
                authorize(HttpMethod.POST, "/api/auth/login", permitAll)
                authorize("/api/auth/**", authenticated)
                authorize("/api/users/**", readyAdmin)
                authorize("/api/**", ready)
                authorize(anyRequest, permitAll)
            }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            formLogin { disable() }
            httpBasic { disable() }
            logout { disable() }
            exceptionHandling {
                authenticationEntryPoint = org.springframework.security.web.AuthenticationEntryPoint { _, response, _ ->
                    error(response, HttpStatus.UNAUTHORIZED, "Sign in first")
                }
                accessDeniedHandler = org.springframework.security.web.access.AccessDeniedHandler { _, response, denied ->
                    val account = account()
                    when {
                        denied is CsrfException -> error(response, HttpStatus.FORBIDDEN, "The page is out of date. Reload it and try again.")
                        account == null -> error(response, HttpStatus.UNAUTHORIZED, "Sign in first")
                        account.mustChangePassword -> error(response, HttpStatus.FORBIDDEN, "Change your password first")
                        else -> error(response, HttpStatus.FORBIDDEN, "Your account may not do this")
                    }
                }
            }
            addFilterBefore<AuthorizationFilter>(SessionFilter(sessions))
        }
        // The CSRF token is a cookie the page reads and sends back as the X-XSRF-TOKEN header.
        // (The Kotlin DSL of this release has no word for either of these two.)
        http.csrf { it.spa() }
        http.requestCache { it.disable() }
        return http.build()
    }

    private fun error(response: HttpServletResponse, status: HttpStatus, message: String) {
        response.status = status.value()
        response.contentType = "application/json"
        response.characterEncoding = "UTF-8"
        response.writer.write("""{"message":"$message"}""")
    }
}
