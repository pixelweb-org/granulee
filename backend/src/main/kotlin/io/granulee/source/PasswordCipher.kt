package io.granulee.source

import io.granulee.state.GranuleeProperties
import io.granulee.state.JsonStore
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-256-GCM for the ClickHouse passwords kept in sources.json. */
@Component
class PasswordCipher(props: GranuleeProperties, store: JsonStore) {
    private val key: SecretKeySpec
    private val random = SecureRandom()

    init {
        val raw = if (props.secretKey.isNotBlank()) {
            Base64.getDecoder().decode(props.secretKey.trim())
        } else {
            val file = store.dir.resolve("secret.key")
            if (!Files.exists(file)) {
                val fresh = ByteArray(32).also { random.nextBytes(it) }
                Files.writeString(file, Base64.getEncoder().encodeToString(fresh))
                runCatching { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")) }
            }
            Base64.getDecoder().decode(Files.readString(file).trim())
        }
        require(raw.size == 32) { "granulee.secret-key must be 32 bytes, base64-encoded" }
        key = SecretKeySpec(raw, "AES")
    }

    fun encrypt(plain: String): String {
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(plain.toByteArray()))
    }

    fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        val bytes = Base64.getDecoder().decode(stored)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, 12))
        return String(cipher.doFinal(bytes, 12, bytes.size - 12))
    }
}
