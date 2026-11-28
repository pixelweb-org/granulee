package io.granulee.user

import io.granulee.NotSignedInException
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder

/** The signed-in account of a request, and the session it came in with. */
data class Account(val id: String, val username: String, val role: String, val mustChangePassword: Boolean, val sessionHash: String) {
    val admin: Boolean get() = role == Role.ADMIN
}

/** How Spring Security carries an account through a request. */
class AccountAuthentication(private val account: Account) :
    AbstractAuthenticationToken(listOf(SimpleGrantedAuthority("ROLE_" + account.role.uppercase()))) {
    init {
        isAuthenticated = true
    }

    override fun getPrincipal(): Account = account

    override fun getCredentials(): Any? = null

    override fun getName(): String = account.username
}

/**
 * Who is asking. Everything a person owns is looked up through this, so a request only ever reaches its own
 * user's data. A thread that serves no request (a scheduled scan, a running trial) has nobody here, and what asks
 * anyway is refused: such work names its owner itself.
 */
object CurrentUser {
    fun account(): Account? = SecurityContextHolder.getContext().authentication?.principal as? Account

    fun id(): String = account()?.id ?: throw NotSignedInException()
}
