package rocks.pkb.plektra.backlight

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileBacklightRecoveryStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val binding = BacklightDeviceBinding("device-a", "build-a")
    private val directory get() = File(folder.root, "keyboard_backlight")
    private fun store() = FileBacklightRecoveryStore(directory)

    @Test
    fun recordSurvivesANewStoreInstance() {
        val record = BacklightRecoveryRecord(binding, "30000", BacklightRecoveryRecord.Phase.Enabling, 42L)

        assertTrue(store().writeRecord(record))

        assertEquals(record, store().readRecord())
        assertFalse(File(directory, "recovery.properties.tmp").exists())
    }

    @Test
    fun anUnrestorableOriginalIsNeitherWrittenNorRead() {
        assertFalse(store().writeRecord(BacklightRecoveryRecord(binding, "-1", BacklightRecoveryRecord.Phase.Enabling, 1L)))
        assertNull(store().readRecord())

        directory.mkdirs()
        File(directory, "recovery.properties").writeText(
            "version=1\ndevice=device-a\nfingerprint=build-a\noriginal_timeout=30000;x\nphase=Enabled\ntime=1\n"
        )
        assertNull(store().readRecord())
    }

    @Test
    fun corruptOrForeignVersionFilesAreIgnored() {
        directory.mkdirs()
        File(directory, "recovery.properties").writeText("version=2\ndevice=device-a\n")
        File(directory, "last_verified.properties").writeText("\\u00zz")

        assertNull(store().readRecord())
        assertNull(store().readVerified())
    }

    @Test
    fun clearingRemovesOnlyTheRecord() {
        val store = store()
        store.writeRecord(BacklightRecoveryRecord(binding, "0", BacklightRecoveryRecord.Phase.Enabled, 1L))
        store.writeVerified(BacklightVerifiedReading(binding, "-1", 2L))

        assertTrue(store.clearRecord())

        assertNull(store.readRecord())
        assertEquals(BacklightVerifiedReading(binding, "-1", 2L), store.readVerified())
    }

    @Test
    fun anUnconfirmedWriteSurvivesANewStoreInstance() {
        val record = BacklightRecoveryRecord(
            binding, "30000", BacklightRecoveryRecord.Phase.Enabling, 1L,
            BacklightRecoveryRecord.UnconfirmedWrite("-1", 12)
        )

        assertTrue(store().writeRecord(record))

        assertEquals(record, store().readRecord())
    }

    @Test
    fun newDirectoriesAndEveryReplacementAndDeletionAreSynced() {
        val synced = mutableListOf<File>()
        val store = FileBacklightRecoveryStore(directory) { synced += it }
        val record = BacklightRecoveryRecord(binding, "30000", BacklightRecoveryRecord.Phase.Enabling, 1L)

        assertTrue(store.writeRecord(record))
        assertEquals(listOf(folder.root.absoluteFile, directory), synced)

        synced.clear()
        assertTrue(store.clearRecord())
        assertEquals(listOf(directory), synced)
    }

    @Test
    fun aDirectorySyncFailureIsReportedAsAFailedWriteOrDeletion() {
        var failing = false
        val store = FileBacklightRecoveryStore(directory) { if (failing) throw java.io.IOException("fsync") }
        val record = BacklightRecoveryRecord(binding, "30000", BacklightRecoveryRecord.Phase.Enabling, 1L)

        failing = true
        assertFalse(store.writeRecord(record))

        failing = false
        assertTrue(store.writeRecord(record))
        failing = true
        assertFalse(store.clearRecord())
    }

    @Test
    fun failedParentSyncIsRetriedEvenForAnExistingDirectoryAndANewStore() {
        val parent = folder.root.absoluteFile
        var attempts = 0
        val sync: (File) -> Unit = { directory ->
            if (directory == parent) {
                attempts++
                throw java.io.IOException("parent fsync")
            }
        }
        val record = BacklightRecoveryRecord(binding, "30000", BacklightRecoveryRecord.Phase.Enabling, 1L)
        val first = FileBacklightRecoveryStore(directory, sync)
        assertFalse(first.writeVerified(BacklightVerifiedReading(binding, "30000", 1L)))
        assertTrue(directory.isDirectory)
        assertFalse(first.writeRecord(record))
        assertFalse(FileBacklightRecoveryStore(directory, sync).writeRecord(record))
        assertEquals(3, attempts)
        assertNull(first.readRecord())
    }

    @Test
    fun theDefaultDirectorySyncWorksOnARealDirectory() {
        assertTrue(store().writeRecord(BacklightRecoveryRecord(binding, "0", BacklightRecoveryRecord.Phase.Enabled, 1L)))
        assertTrue(store().clearRecord())
    }
}
