package rocks.pkb.plektra.backlight

import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import rocks.pkb.plektra.ShizukuStatus

class KeyboardBacklightControllerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val verified = BacklightDeviceBinding(
        deviceKey = "device-a",
        buildFingerprint = "Unihertz/Titan_2_EEA/Titan_2:16/BP2A.250605.031.A3/V02.00.02:user/release-keys"
    )
    private lateinit var shell: FakeVendorShell
    private lateinit var store: RecordingStore
    private var binding = verified
    private var now = 1_000L
    private var boot: Int? = 7

    @Before
    fun setUp() {
        store = RecordingStore(FileBacklightRecoveryStore(File(folder.root, "keyboard_backlight")))
        shell = FakeVendorShell(store)
    }

    private fun controller() = KeyboardBacklightController(
        shell = shell,
        store = store,
        binding = { binding },
        bootCount = { boot },
        clock = { now },
        ioDispatcher = Dispatchers.Unconfined
    )

    @Test
    fun enablePersistsTheOriginalBeforeWritingAndVerifiesTheResult() = runBlocking {
        shell.timeout = "30000"

        val result = controller().enable()

        assertEquals(BacklightOutcome.Enabled, result.outcome)
        assertEquals(BacklightObservation.Fresh("-1", now), result.observation)
        assertEquals("30000", shell.recordOriginalAtFirstWrite)
        assertEquals(BacklightRecoveryRecord.Phase.Enabled, store.readRecord()?.phase)
        assertEquals("30000", store.readRecord()?.originalTimeout)
        assertEquals(listOf("-1"), shell.writes)
    }

    @Test
    fun disableRestoresTheExactOriginalAndRemovesTheRecord() = runBlocking {
        shell.timeout = "10000"
        val controller = controller()
        controller.enable()

        val result = controller.disable()

        assertEquals(BacklightOutcome.Disabled, result.outcome)
        assertEquals("10000", shell.timeout)
        assertEquals(listOf("-1", "10000"), shell.writes)
        assertNull(store.readRecord())
        assertFalse(result.observation.followsDisplay)
    }

    @Test
    fun disableRestoresALightThatWasSwitchedOff() = runBlocking {
        shell.timeout = "0"
        val controller = controller()
        controller.enable()

        controller.disable()

        assertEquals("0", shell.timeout)
    }

    @Test
    fun repeatedEnableKeepsTheFirstOriginal() = runBlocking {
        shell.timeout = "30000"
        val controller = controller()
        controller.enable()

        val again = controller.enable()

        assertEquals(BacklightOutcome.Enabled, again.outcome)
        assertEquals("30000", store.readRecord()?.originalTimeout)
        assertEquals(listOf("-1"), shell.writes)
    }

    @Test
    fun anInterruptedEnableKeepsItsOriginalWhenTheWriteHadLanded() = runBlocking {
        shell.timeout = "-1"
        store.writeRecord(record("15000", BacklightRecoveryRecord.Phase.Enabling))

        val refreshed = controller().refresh()

        assertEquals(BacklightObservation.Fresh("-1", now), refreshed.observation)
        assertEquals(BacklightRecoveryRecord.Phase.Enabled, store.readRecord()?.phase)
        assertEquals("15000", store.readRecord()?.originalTimeout)
        assertTrue(shell.writes.isEmpty())
    }

    @Test
    fun anInterruptedEnableWhoseWriteNeverLandedIsCleanedUp() = runBlocking {
        shell.timeout = "15000"
        store.writeRecord(record("15000", BacklightRecoveryRecord.Phase.Enabling))

        controller().refresh()

        assertNull(store.readRecord())
    }

    @Test
    fun aRecordFailureLeavesTheDeviceUntouched() = runBlocking {
        shell.timeout = "30000"
        store.failWrites = true

        val result = controller().enable()

        assertEquals(BacklightOutcome.RecordFailed, result.outcome)
        assertTrue(shell.writes.isEmpty())
    }

    @Test
    fun aFailedReadLeavesTheDeviceUntouched() = runBlocking {
        shell.timeout = "30000"
        shell.readFails = true

        val result = controller().enable()

        assertEquals(BacklightOutcome.ReadFailed, result.outcome)
        assertTrue(shell.writes.isEmpty())
        assertNull(store.readRecord())
    }

    @Test
    fun anUnverifiedServiceIsNeitherReadNorWritten() = runBlocking {
        shell.serviceList = "13\tagui_common: [com.example.Other]"

        val result = controller().enable()

        assertEquals(BacklightOutcome.ServiceUnverified, result.outcome)
        assertEquals(listOf("list"), shell.calls)
    }

    @Test
    fun unverifiedFirmwareFailsClosedWithoutShellAccess() = runBlocking {
        binding = verified.copy(buildFingerprint = "Unihertz/Titan_2_EEA/Titan_2:16/X/V02.00.03:user/release-keys")
        val controller = controller()

        assertFalse(controller.isOffered())
        assertEquals(BacklightOutcome.UnsupportedFirmware, controller.enable().outcome)
        assertTrue(shell.calls.isEmpty())
    }

    @Test
    fun aFirmwareUpdateKeepsTheRecordAndOnlyPointsToTheSystemSettings() = runBlocking {
        shell.timeout = "30000"
        controller().enable()
        binding = verified.copy(buildFingerprint = "Unihertz/Titan_2_EEA/Titan_2:16/X/V02.00.03:user/release-keys")
        shell.calls.clear()

        val controller = controller()
        val result = controller.disable()

        assertTrue(controller.isOffered())
        assertEquals(BacklightSupport.FirmwareChanged, result.support)
        assertFalse(result.canChange)
        assertEquals("30000", result.recovery?.originalTimeout)
        assertEquals(BacklightObservation.Unknown, result.observation)
        assertTrue(shell.calls.isEmpty())
    }

    @Test
    fun aRecordFromAnotherDeviceIsIgnored() = runBlocking {
        shell.timeout = "-1"
        store.writeRecord(record("30000", BacklightRecoveryRecord.Phase.Enabled).copy(
            binding = verified.copy(deviceKey = "device-b")
        ))

        val result = controller().disable()

        assertEquals(BacklightOutcome.NoOriginalToRestore, result.outcome)
        assertNull(result.recovery)
        assertTrue(shell.writes.isEmpty())
    }

    @Test
    fun alreadyDisplayLinkedWithoutAnOriginalIsReportedAndNotRecorded() = runBlocking {
        shell.timeout = "-1"
        val controller = controller()

        assertEquals(BacklightOutcome.AlreadyFollowingWithoutOriginal, controller.enable().outcome)
        assertNull(store.readRecord())
        assertEquals(BacklightOutcome.NoOriginalToRestore, controller.disable().outcome)
        assertTrue(shell.writes.isEmpty())
    }

    @Test
    fun anUnrestorableCurrentValueIsNotReplaced() = runBlocking {
        shell.timeout = "5000ms"

        val result = controller().enable()

        assertEquals(BacklightOutcome.UnrestorableCurrentValue, result.outcome)
        assertTrue(shell.writes.isEmpty())
    }

    @Test
    fun anUnconfirmedWriteKeepsTheRecordUntilAReadSettlesIt() = runBlocking {
        shell.timeout = "30000"
        shell.failReadsAfterWrite = true
        val controller = controller()

        val uncertain = controller.enable()

        assertEquals(BacklightOutcome.Uncertain, uncertain.outcome)
        assertEquals(BacklightRecoveryRecord.Phase.Enabling, store.readRecord()?.phase)
        assertEquals("30000", store.readRecord()?.originalTimeout)

        shell.failReadsAfterWrite = false
        val settled = controller.refresh()
        assertEquals(BacklightObservation.Fresh("-1", now), settled.observation)
        assertEquals(BacklightRecoveryRecord.Phase.Enabled, store.readRecord()?.phase)
    }

    @Test
    fun aRejectedWriteReportsTheUnchangedValue() = runBlocking {
        shell.timeout = "30000"
        shell.rejectWrites = true

        val result = controller().enable()

        assertEquals(BacklightOutcome.WriteFailed, result.outcome)
        assertEquals(BacklightObservation.Fresh("30000", now), result.observation)
        assertNull(store.readRecord())
    }

    @Test
    fun aValueChangedOutsidePlektraSupersedesTheRecord() = runBlocking {
        shell.timeout = "30000"
        val controller = controller()
        controller.enable()
        shell.timeout = "5000"

        val result = controller.refresh()

        assertNull(result.recovery)
        assertNull(store.readRecord())
        assertEquals(BacklightOutcome.Disabled, controller.disable().outcome)
        assertEquals(listOf("-1"), shell.writes)
    }

    @Test
    fun withoutShizukuTheLastVerifiedValueIsShownAsSuch() = runBlocking {
        shell.timeout = "30000"
        val controller = controller()
        controller.enable()
        now = 5_000L
        shell.status = ShizukuStatus.NotConnected
        shell.calls.clear()

        val result = controller.refresh()

        assertEquals(BacklightOutcome.ShizukuUnavailable, result.outcome)
        assertEquals(BacklightObservation.LastVerified("-1", 1_000L), result.observation)
        assertEquals(ShizukuStatus.NotConnected, result.shizuku)
        assertFalse(result.canChange)
        assertTrue(shell.calls.isEmpty())
        assertEquals(BacklightOutcome.ShizukuUnavailable, controller.disable().outcome)
        assertEquals("-1", shell.timeout)
    }

    @Test
    fun cancellationDuringTheWriteStillCompletesTheBookkeeping() = runBlocking {
        shell.timeout = "30000"
        val controller = controller()
        var job: Job? = null
        shell.onWrite = { job?.cancel() }
        val operation = async(start = CoroutineStart.LAZY) { controller.enable() }
        job = operation

        operation.start()
        runCatching { operation.await() }

        assertEquals("-1", shell.timeout)
        val record = store.readRecord()
        assertNotNull(record)
        assertEquals(BacklightRecoveryRecord.Phase.Enabled, record?.phase)
        assertEquals("30000", record?.originalTimeout)
    }

    @Test
    fun anUnconfirmedEnableKeepsTheOriginalWhenTheWriteLandsLate() = runBlocking {
        shell.timeout = "30000"
        shell.delayWrites = true
        val controller = controller()

        val first = controller.enable()

        assertEquals(BacklightOutcome.Uncertain, first.outcome)
        assertEquals(BacklightObservation.Fresh("30000", now), first.observation)
        assertEquals("30000", store.readRecord()?.originalTimeout)
        assertEquals("-1", store.readRecord()?.unconfirmedWrite?.value)

        // An immediate read of the old value proves nothing: the record stays, writes stay blocked.
        assertEquals(BacklightOutcome.Uncertain, controller.refresh().outcome)
        assertNotNull(store.readRecord()?.unconfirmedWrite)
        assertEquals(BacklightOutcome.Uncertain, controller.enable().outcome)
        assertEquals(BacklightOutcome.Uncertain, controller.disable().outcome)
        assertEquals(listOf("-1"), shell.writes)

        shell.completeDelayedWrite()
        shell.delayWrites = false
        assertEquals(BacklightOutcome.Uncertain, controller.disable().outcome)
        boot = 8
        val restored = controller.disable()

        assertEquals(BacklightOutcome.Disabled, restored.outcome)
        assertEquals("30000", shell.timeout)
        assertEquals(listOf("-1", "30000"), shell.writes)
        assertNull(store.readRecord())
    }

    @Test
    fun anUnconfirmedWriteIsReleasedOnlyByAReboot() = runBlocking {
        shell.timeout = "30000"
        shell.delayWrites = true
        val controller = controller()
        controller.enable()
        shell.delayWrites = false

        boot = 8
        val afterReboot = controller.refresh()

        assertNull(afterReboot.recovery)
        assertNull(store.readRecord())
        assertEquals(BacklightOutcome.Enabled, controller.enable().outcome)
        assertEquals("30000", store.readRecord()?.originalTimeout)
    }

    @Test
    fun withoutABootCountAnUnconfirmedWriteStaysBlocking() = runBlocking {
        boot = null
        shell.timeout = "30000"
        shell.delayWrites = true
        val controller = controller()
        controller.enable()
        shell.delayWrites = false

        assertEquals(BacklightOutcome.Uncertain, controller.enable().outcome)
        assertEquals(listOf("-1"), shell.writes)
    }

    @Test
    fun anUnconfirmedRestoreKeepsTheRecordUntilTheDeviceReboots() = runBlocking {
        shell.timeout = "10000"
        val controller = controller()
        controller.enable()
        shell.delayWrites = true

        assertEquals(BacklightOutcome.Uncertain, controller.disable().outcome)
        assertEquals("10000", store.readRecord()?.unconfirmedWrite?.value)

        shell.completeDelayedWrite()
        assertEquals(BacklightOutcome.Uncertain, controller.refresh().outcome)
        boot = 8
        assertNull(controller.refresh().recovery)
        assertNull(store.readRecord())
    }

    @Test
    fun aFailedDeletionBlocksANewEnableInsteadOfReusingASupersededOriginal() = runBlocking {
        shell.timeout = "30000"
        val controller = controller()
        controller.enable()
        shell.timeout = "5000"
        store.failClears = true

        val result = controller.enable()

        assertEquals(BacklightOutcome.RecordFailed, result.outcome)
        assertEquals(listOf("-1"), shell.writes)
        assertEquals("5000", shell.timeout)
    }

    @Test
    fun aRecordFromAnotherBuildOfThisDeviceIsNeverRestored() = runBlocking {
        shell.timeout = "-1"
        store.writeRecord(
            record("15000", BacklightRecoveryRecord.Phase.Enabled).copy(
                binding = verified.copy(buildFingerprint = "other-build")
            )
        )

        val result = controller().disable()

        assertEquals(BacklightSupport.Supported, result.support)
        assertEquals(BacklightOutcome.NoOriginalToRestore, result.outcome)
        assertNull(result.recovery)
        assertTrue(shell.writes.isEmpty())
    }

    @Test
    fun matchingExternalValueCannotReleaseAnUnconfirmedEnable() = runBlocking {
        shell.timeout = "30000"
        shell.delayWrites = true
        controller().enable()
        shell.timeout = "-1" // Another writer matches the pending value before that call completes.
        shell.delayWrites = false
        val restartedController = controller()

        assertEquals(BacklightOutcome.Uncertain, restartedController.disable().outcome)
        assertEquals("30000", store.readRecord()?.originalTimeout)
        assertEquals(listOf("-1"), shell.writes)
        shell.completeDelayedWrite()
        assertEquals(BacklightOutcome.Uncertain, restartedController.disable().outcome)
        assertNotNull(store.readRecord())
    }

    @Test
    fun matchingExternalValueCannotReleaseAnUnconfirmedRestore() = runBlocking {
        shell.timeout = "30000"
        controller().enable()
        shell.delayWrites = true
        controller().disable()
        shell.timeout = "30000"
        shell.delayWrites = false

        assertEquals(BacklightOutcome.Uncertain, controller().enable().outcome)
        shell.timeout = "5000"
        assertEquals(BacklightOutcome.Uncertain, controller().enable().outcome)
        assertEquals(listOf("-1", "30000"), shell.writes)
        assertEquals("30000", store.readRecord()?.originalTimeout)
    }

    @Test
    fun failedRecoveryDeletionIsReportedAfterRestoreAndOnRefresh() = runBlocking {
        shell.timeout = "30000"
        controller().enable()
        store.failClears = true

        val disabled = controller().disable()
        assertEquals("30000", shell.timeout)
        assertEquals(BacklightObservation.Fresh("30000", now), disabled.observation)
        assertEquals(BacklightOutcome.RecordFailed, disabled.outcome)
        assertNotNull(store.readRecord())
        assertEquals(BacklightOutcome.RecordFailed, controller().refresh().outcome)
    }

    private fun record(original: String, phase: BacklightRecoveryRecord.Phase) =
        BacklightRecoveryRecord(verified, original, phase, createdAtMillis = 1L)

    private class RecordingStore(private val delegate: BacklightRecoveryStore) : BacklightRecoveryStore by delegate {
        var failWrites = false
        var failClears = false

        override fun writeRecord(record: BacklightRecoveryRecord): Boolean =
            !failWrites && delegate.writeRecord(record)

        override fun clearRecord(): Boolean = !failClears && delegate.clearRecord()
    }

    /** Simulates the vendor key-value store behind `agui_common` and the shell around it. */
    private class FakeVendorShell(private val store: BacklightRecoveryStore) : BacklightShell {
        var status = ShizukuStatus.Connected
        var timeout: String? = null
        var serviceList = "0\tAService: [com.agui.server.IAService]\n13\tagui_common: [com.agui.ext.common.IACommonService]\n"
        var readFails = false
        var failReadsAfterWrite = false
        var rejectWrites = false

        /** The write reaches the vendor service only later and the client reports no result. */
        var delayWrites = false
        private var delayedValue: String? = null
        var onWrite: () -> Unit = {}
        var recordOriginalAtFirstWrite: String? = null
        val writes = mutableListOf<String>()
        val calls = mutableListOf<String>()
        private var written = false

        override fun status() = status

        override fun run(command: UnihertzBacklightVendorCommand): BacklightShellResult = when (command) {
            UnihertzBacklightVendorCommand.ListServices -> {
                calls += "list"
                BacklightShellResult(true, 0, serviceList)
            }
            UnihertzBacklightVendorCommand.ReadTimeout -> {
                calls += "read"
                if (readFails || (failReadsAfterWrite && written)) {
                    BacklightShellResult(false, null, "")
                } else {
                    BacklightShellResult(true, 0, parcel(timeout))
                }
            }
            is UnihertzBacklightVendorCommand.WriteTimeout -> {
                calls += "write"
                if (writes.isEmpty()) recordOriginalAtFirstWrite = store.readRecord()?.originalTimeout
                val value = command.argv().last()
                writes += value
                written = true
                onWrite()
                when {
                    delayWrites -> {
                        delayedValue = value
                        BacklightShellResult(false, null, "")
                    }
                    rejectWrites -> BacklightShellResult(true, 0, "Result: Parcel(ffffffb5 '....')\n")
                    else -> {
                        timeout = value
                        BacklightShellResult(true, 0, "Result: Parcel(\t00000000    '....')\n")
                    }
                }
            }
        }

        fun completeDelayedWrite() {
            timeout = requireNotNull(delayedValue)
            delayedValue = null
        }

        private fun parcel(value: String?): String {
            if (value == null) return "Result: Parcel(00000000 ffffffff '........')"
            val words = mutableListOf("00000000", "%08x".format(value.length))
            value.chunked(2).forEach { pair ->
                val low = pair[0].code
                val high = pair.getOrNull(1)?.code ?: 0
                words += "%08x".format((high shl 16) or low)
            }
            if (value.length % 2 == 0) words += "00000000"
            return "Result: Parcel(\t${words.joinToString(" ")} '....')\n"
        }
    }
}
