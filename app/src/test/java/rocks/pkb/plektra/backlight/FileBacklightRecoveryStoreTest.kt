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
}
