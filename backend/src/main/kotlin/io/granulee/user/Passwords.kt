package io.granulee.user

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.stereotype.Component
import java.security.SecureRandom

/** Hashes and checks account passwords with Argon2id, and makes up the one-time ones. */
@Component
class Passwords {
    private val encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()
    private val random = SecureRandom()

    // What a sign-in with an unknown name is checked against, so it takes as long as one with a wrong password.
    private val nobody = encoder.encode(generate())!!

    fun hash(password: String): String = encoder.encode(password)!!

    fun matches(password: String, hash: String?): Boolean = encoder.matches(password, hash ?: nobody) && hash != null

    /** 24 letters and digits: about 142 bits. */
    fun generate(): String = (1..24).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")

    companion object {
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        const val MIN_LENGTH = 12
        const val MAX_LENGTH = 256

        /** What keeps a password from being accepted, worded for the person choosing it; null when nothing does. */
        fun problem(username: String, password: String): String? = when {
            password.length < MIN_LENGTH -> "The password must have at least $MIN_LENGTH characters"
            password.length > MAX_LENGTH -> "The password must have at most $MAX_LENGTH characters"
            password.equals(username, ignoreCase = true) -> "The password must not be the user name"
            else -> null
        }
    }
}
