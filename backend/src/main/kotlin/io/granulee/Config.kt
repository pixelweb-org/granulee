package io.granulee

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@ConfigurationProperties("granulee")
data class GranuleeProperties(
    /** Where Granulee keeps its own state: sources, scan snapshots, trials. */
    val dataDir: String = "./data",
    /** Base64 AES key for stored passwords. Empty = a key file is created in the data dir. */
    val secretKey: String = "",
)

/** JSON files in the data directory. Granulee has little state, so no database of its own. */
@Component
class JsonStore(props: GranuleeProperties) {
    val mapper: ObjectMapper = jacksonObjectMapper()
    val dir: Path = Path.of(props.dataDir).toAbsolutePath().normalize().also { Files.createDirectories(it) }

    fun <T> read(name: String, type: Class<T>): T? {
        val file = dir.resolve(name)
        if (!Files.exists(file)) return null
        return mapper.readValue(file.toFile(), type)
    }

    @Synchronized
    fun write(name: String, value: Any) {
        val file = dir.resolve(name)
        Files.createDirectories(file.parent)
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        mapper.writeValue(tmp.toFile(), value)
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun delete(name: String) {
        Files.deleteIfExists(dir.resolve(name))
    }
}

/** AES-256-GCM for the ClickHouse passwords kept in sources.json. */
@Component
class Crypto(props: GranuleeProperties, store: JsonStore) {
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
