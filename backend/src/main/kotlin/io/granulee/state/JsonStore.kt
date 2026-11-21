package io.granulee.state

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

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
    // The files' format belongs to this store alone; the mapper of the HTTP API can change without touching them.
    private val mapper = jacksonObjectMapper()
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

    /** The names of the files in a directory of the store; none when it does not exist yet. */
    fun list(directory: String): List<String> {
        val path = dir.resolve(directory)
        if (!Files.isDirectory(path)) return emptyList()
        return Files.list(path).use { files -> files.filter { Files.isRegularFile(it) }.map { it.fileName.toString() }.sorted().toList() }
    }
}
