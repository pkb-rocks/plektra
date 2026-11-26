package rocks.pkb.plektra.backlight

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
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
 * it. It is trusted only on the device and build in [binding].
 */
internal data class BacklightRecoveryRecord(
    val binding: BacklightDeviceBinding,
    val originalTimeout: String,
    val phase: Phase,
    val createdAtMillis: Long,
    /** A write that was sent but not confirmed, and may therefore still take effect. */
    val unconfirmedWrite: UnconfirmedWrite? = null
) {
    /**
     * Killing a `service call` client does not cancel a binder transaction the vendor service has
     * already received, so a later read of the old value does not prove the write is gone. The
     * entry is dropped only after a completed reply, or after a reboot ([bootCount] changed),
     * which ends every pending transaction.
     */
    data class UnconfirmedWrite(val value: String, val bootCount: Int?)

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
internal class FileBacklightRecoveryStore(
    private val directory: File,
    private val syncDirectory: (File) -> Unit = ::syncDirectoryChannel
) : BacklightRecoveryStore {
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
        val unconfirmed = properties.getProperty(KEY_UNCONFIRMED_VALUE)?.let { value ->
            if (value != UnihertzBacklightVendorCommand.TIMEOUT_FOLLOW_DISPLAY &&
                !UnihertzBacklightVendorCommand.isRestorableTimeout(value)
            ) {
                return null
            }
            val boot = properties.getProperty(KEY_UNCONFIRMED_BOOT)
            BacklightRecoveryRecord.UnconfirmedWrite(value, boot?.let { it.toIntOrNull() ?: return null })
        }
        return BacklightRecoveryRecord(binding, original, phase, createdAt, unconfirmed)
    }

    override fun writeRecord(record: BacklightRecoveryRecord): Boolean {
        if (!UnihertzBacklightVendorCommand.isRestorableTimeout(record.originalTimeout)) return false
        val properties = Properties().apply {
            setProperty(KEY_VERSION, VERSION)
            putBinding(record.binding)
            setProperty(KEY_ORIGINAL, record.originalTimeout)
            setProperty(KEY_PHASE, record.phase.name)
            setProperty(KEY_TIME, record.createdAtMillis.toString())
            record.unconfirmedWrite?.let { unconfirmed ->
                setProperty(KEY_UNCONFIRMED_VALUE, unconfirmed.value)
                unconfirmed.bootCount?.let { setProperty(KEY_UNCONFIRMED_BOOT, it.toString()) }
            }
        }
        return save(recordFile, properties) && readRecord() == record
    }

    override fun clearRecord(): Boolean {
        val file = recordFile
        if (!file.exists()) return true
        return try {
            Files.delete(file.toPath())
            syncDirectory(directory)
            true
        } catch (_: Exception) {
            false
        }
    }

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

    /**
     * Writes a synced temporary file, moves it over the target in one step and syncs the
     * directory, so the new entry survives a power loss before the caller relies on it.
     */
    private fun save(file: File, properties: Properties): Boolean {
        val temporary = File(directory, "${file.name}.tmp")
        return try {
            ensureDirectory()
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
            syncDirectory(directory)
            true
        } catch (_: Exception) {
            temporary.delete()
            false
        }
    }

    /** Re-establish parent-entry durability even after a previous creation sync failed. */
    private fun ensureDirectory() {
        val parent = directory.absoluteFile.parentFile ?: throw IOException("no parent directory")
        if (!parent.isDirectory) {
            if (!parent.mkdirs()) throw IOException("cannot create $parent")
            parent.parentFile?.let(syncDirectory)
        }
        if (!directory.mkdir() && !directory.isDirectory) throw IOException("cannot create $directory")
        syncDirectory(parent)
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
        const val KEY_UNCONFIRMED_VALUE = "unconfirmed_value"
        const val KEY_UNCONFIRMED_BOOT = "unconfirmed_boot"
    }
}

/** fsync of a directory; throws when the directory entry cannot be made durable. */
internal fun syncDirectoryChannel(directory: File) {
    FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
}
