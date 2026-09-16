package me.raddatz.cindy.core.persistence

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import me.raddatz.cindy.core.CindyJson
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Minimal JSON persistence in a single file (iOS: the app's Documents directory; on Android pass
 * a file under `Context.filesDir`). Dates are ISO-8601, see [CindyJson].
 */
class JSONFileStore<T>(
    val file: File,
    private val serializer: KSerializer<T>,
    private val json: Json = CindyJson,
) {
    /** The stored value, or `null` when the file is missing or cannot be decoded. */
    fun load(): T? =
        try {
            if (!file.isFile) null else json.decodeFromString(serializer, file.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }

    /**
     * Writes atomically: a temporary file next to the target, then a rename over it.
     * @throws IOException when the file cannot be written (e.g. the directory does not exist).
     */
    fun save(value: T) {
        val text = json.encodeToString(serializer, value)
        val directory = file.absoluteFile.parentFile ?: throw IOException("No parent directory for $file")
        val temp = File.createTempFile(".${file.name}", ".tmp", directory)
        try {
            temp.writeText(text, Charsets.UTF_8)
            try {
                Files.move(
                    temp.toPath(), file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    fun delete() {
        file.delete()
    }
}

/** `JSONFileStore<List<WorkoutRecord>>(file)` without spelling out the serializer. */
inline fun <reified T> JSONFileStore(file: File, json: Json = CindyJson): JSONFileStore<T> =
    JSONFileStore(file, serializer<T>(), json)
