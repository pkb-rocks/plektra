package rocks.pkb.plektra.core

import rocks.pkb.plektra.inputmethod.DirectBootKeyboardConfig
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Publishes a complete file or throws before replacing the previous file. */
internal fun File.writeSettingsFileAtomically(write: (OutputStream) -> Unit) {
    val staged = File.createTempFile("settings-", ".tmp", requireNotNull(absoluteFile.parentFile))
    try {
        FileOutputStream(staged).use { output ->
            write(output)
            output.fd.sync()
        }
        Files.move(staged.toPath(), toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        staged.delete()
    }
}

internal fun File.writeSettingsFileAtomically(text: String) =
    writeSettingsFileAtomically { it.write(text.toByteArray(Charsets.UTF_8)) }

internal fun initializeUserDefaultsFile(
    file: File,
    useEmptyFallback: Boolean = false,
    openAsset: () -> InputStream
) {
    // User words are not projected to Direct Boot, but restore shares this lock.
    synchronized(DirectBootKeyboardConfig.SOURCE_LOCK) {
        if (file.exists()) return
        val asset = try {
            openAsset()
        } catch (missing: FileNotFoundException) {
            if (!useEmptyFallback) throw missing
            file.writeSettingsFileAtomically("[]")
            return
        }
        asset.use { input ->
            file.writeSettingsFileAtomically { output -> input.copyTo(output) }
        }
    }
}
