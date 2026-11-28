package io.granulee.state

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

@ConfigurationProperties("granulee")
data class GranuleeProperties(
    /** Where Granulee keeps its own state: the database and the key file. */
    val dataDir: String = "./data",
    /** Base64 AES key for stored passwords. Empty = a key file is created in the data dir. */
    val secretKey: String = "",
    /** A user name: that account gets a new one-time password at start, printed once. For a lost admin password. */
    val resetAdmin: String = "",
)

/**
 * The data directory, created when it is not there yet. What is kept in it is each feature's own business:
 * every feature names what it stores as a `…Storage` interface, and one package implements them all.
 */
@Component
class DataDir(props: GranuleeProperties) {
    val path: Path = Path.of(props.dataDir).toAbsolutePath().normalize().also { Files.createDirectories(it) }
}
