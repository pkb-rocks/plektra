package rocks.pkb.plektra.backlight

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/** Identity of the running device and firmware build that records are bound to. */
internal data class BacklightDeviceBinding(
    /** Hash of values that change with other hardware, another user or a factory reset. */
    val deviceKey: String,
    val buildFingerprint: String
)

/**
 * The timeout to restore. It is written before the first mutation and removed only after the
 * restored value was read back, or when a fresh read shows the vendor value no longer depends on
 * it.
 */
internal data class BacklightRecoveryRecord(
    val binding: BacklightDeviceBinding,
    val originalTimeout: String,
    val phase: Phase,
    val createdAtMillis: Long
) {
    enum class Phase {
        /** Persisted before writing `-1`; the write may or may not have happened. */
        Enabling,

        /** `-1` was read back after the write. */
        Enabled
    }
}

/** The vendor timeout as last read from the device, shown when no fresh read is possible. */
internal data class BacklightVerifiedReading(
    val binding: BacklightDeviceBinding,
    val timeout: String,
    val verifiedAtMillis: Long
)

internal interface BacklightRecoveryStore {
    fun readRecord(): BacklightRecoveryRecord?

    /** Returns false when the record could not be made durable. */
    fun writeRecord(record: BacklightRecoveryRecord): Boolean
    fun clearRecord(): Boolean
    fun readVerified(): BacklightVerifiedReading?
    fun writeVerified(reading: BacklightVerifiedReading): Boolean
}

/**
 * Stores both files in `noBackupFilesDir`, which Android excludes from cloud backup and device
 * transfer, and binds them to [BacklightDeviceBinding] so a copied file is never trusted.
 */
internal class FileBacklightRecoveryStore(private val directory: File) : BacklightRecoveryStore {
    private val recordFile get() = File(directory, "recovery.properties")
    private val verifiedFile get() = File(directory, "last_verified.properties")

    override fun readRecord(): BacklightRecoveryRecord? {
        val properties = load(recordFile) ?: return null
        if (properties.getProperty(KEY_VERSION) != VERSION) return null
        val binding = properties.binding() ?: return null
        val original = properties.getProperty(KEY_ORIGINAL)
            ?.takeIf(UnihertzBacklightVendorCommand::isRestorableTimeout) ?: return null
        val phase = properties.getProperty(KEY_PHASE)
            ?.let { name -> BacklightRecoveryRecord.Phase.entries.firstOrNull { it.name == name } }
            ?: return null
        val createdAt = properties.getProperty(KEY_TIME)?.toLongOrNull() ?: return null
        return BacklightRecoveryRecord(binding, original, phase, createdAt)
    }

    override fun writeRecord(record: BacklightRecoveryRecord): Boolean {
        if (!UnihertzBacklightVendorCommand.isRestorableTimeout(record.originalTimeout)) return false
        val properties = Properties().apply {
            setProperty(KEY_VERSION, VERSION)
            putBinding(record.binding)
            setProperty(KEY_ORIGINAL, record.originalTimeout)
            setProperty(KEY_PHASE, record.phase.name)
            setProperty(KEY_TIME, record.createdAtMillis.toString())
        }
        return save(recordFile, properties) && readRecord() == record
    }

    override fun clearRecord(): Boolean = !recordFile.exists() || recordFile.delete()

    override fun readVerified(): BacklightVerifiedReading? {
        val properties = load(verifiedFile) ?: return null
        if (properties.getProperty(KEY_VERSION) != VERSION) return null
        val binding = properties.binding() ?: return null
        val timeout = properties.getProperty(KEY_TIMEOUT) ?: return null
        val verifiedAt = properties.getProperty(KEY_TIME)?.toLongOrNull() ?: return null
        return BacklightVerifiedReading(binding, timeout, verifiedAt)
    }

    override fun writeVerified(reading: BacklightVerifiedReading): Boolean {
        val properties = Properties().apply {
            setProperty(KEY_VERSION, VERSION)
            putBinding(reading.binding)
            setProperty(KEY_TIMEOUT, reading.timeout)
            setProperty(KEY_TIME, reading.verifiedAtMillis.toString())
        }
        return save(verifiedFile, properties)
    }

    private fun load(file: File): Properties? = try {
        if (!file.isFile) null else Properties().apply { file.inputStream().use(::load) }
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Writes a synced temporary file and moves it over the target in one step. */
    private fun save(file: File, properties: Properties): Boolean {
        val temporary = File(directory, "${file.name}.tmp")
        return try {
            if (!directory.isDirectory && !directory.mkdirs()) return false
            FileOutputStream(temporary).use { stream ->
                properties.store(stream, null)
                stream.flush()
                stream.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
            true
        } catch (_: Exception) {
            temporary.delete()
            false
        }
    }

    private fun Properties.binding(): BacklightDeviceBinding? {
        val device = getProperty(KEY_DEVICE)?.takeIf(String::isNotBlank) ?: return null
        val fingerprint = getProperty(KEY_FINGERPRINT)?.takeIf(String::isNotBlank) ?: return null
        return BacklightDeviceBinding(device, fingerprint)
    }

    private fun Properties.putBinding(binding: BacklightDeviceBinding) {
        setProperty(KEY_DEVICE, binding.deviceKey)
        setProperty(KEY_FINGERPRINT, binding.buildFingerprint)
    }

    private companion object {
        const val VERSION = "1"
        const val KEY_VERSION = "version"
        const val KEY_DEVICE = "device"
        const val KEY_FINGERPRINT = "fingerprint"
        const val KEY_ORIGINAL = "original_timeout"
        const val KEY_PHASE = "phase"
        const val KEY_TIMEOUT = "timeout"
        const val KEY_TIME = "time"
    }
}
