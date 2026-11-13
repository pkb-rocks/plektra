package rocks.pkb.plektra.dictionaries

import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.io.File
import java.io.InputStream

/** Stage and validate before replacing a working file. Validation retains only
 * relevant personal-word keys, never a full expanded dictionary index.
 */
internal object DictionaryFileInstaller {
    fun install(input: InputStream, target: File, personalKeys: Set<String>, checkpoint: () -> Unit = {}): Set<String> {
        val staged = File.createTempFile("dictionary-import-", ".tmp", target.parentFile)
        try {
            FileOutputStream(staged).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    checkpoint()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
            val found = staged.inputStream().use { StreamingDictionaryValidator.validate(it, personalKeys, checkpoint = checkpoint) }
            // Android API 29+ supports atomic replacement in the same directory.
            // If the filesystem rejects it, propagate the error and retain the old file.
            checkpoint()
            Files.move(staged.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return found
        } finally {
            staged.delete()
        }
    }
}
