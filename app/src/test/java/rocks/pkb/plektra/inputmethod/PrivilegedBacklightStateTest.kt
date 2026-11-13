package rocks.pkb.plektra.inputmethod

import android.Manifest
import android.content.Context
import android.provider.Settings
import rocks.pkb.plektra.SettingsManager
import moe.shizuku.manager.adb.EmbeddedAdbInit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PrivilegedBacklightStateTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        EmbeddedAdbInit.prefs(context).edit().clear().commit()
        SettingsManager.getPreferences(context).edit().clear().commit()
        Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 0)
    }

    @Test
    fun smartBacklightIsOffByDefaultAndRoundTrips() {
        assertFalse(SettingsManager.getSmartBacklightEnabled(context))
        SettingsManager.setSmartBacklightEnabled(context, true)
        assertTrue(SettingsManager.getSmartBacklightEnabled(context))
    }

    @Test
    fun appliedFlagAndObservedValueLiveInTheBackupExcludedBrokerPrefs() {
        assertFalse(PrivilegedDiagnostics.backlightApplied(context))
        assertNull(PrivilegedDiagnostics.observedBacklightValue(context))

        PrivilegedDiagnostics.setBacklightApplied(context, true)
        PrivilegedDiagnostics.recordObservedBacklightValue(context, "-1")

        assertTrue(PrivilegedDiagnostics.backlightApplied(context))
        assertEquals("-1", PrivilegedDiagnostics.observedBacklightValue(context)?.first)
        assertTrue(EmbeddedAdbInit.prefs(context).contains("privileged_backlight_applied"))
        assertFalse(SettingsManager.getPreferences(context).contains("privileged_backlight_applied"))
    }

    /** An install that turned the backlight on before holders existed keeps the tile's permission. */
    @Test
    fun applyAllRegistersTheBacklightAsAWriteSecureSettingsHolder() {
        // the tile's own switch off, so only Smart backlight holds it.
        SettingsManager.setBacklightTileEnabled(context, false)
        SettingsManager.setSmartBacklightEnabled(context, true)
        PrivilegedSetup.applyAll(context, "test", BrokerTrigger.forTest(BrokerTrigger.Kind.TAP))
        assertTrue(WriteSecureSettingsHolders.BACKLIGHT in WriteSecureSettingsHolders.holders(context))

        SettingsManager.setSmartBacklightEnabled(context, false)
        PrivilegedSetup.applyAll(context, "test", BrokerTrigger.forTest(BrokerTrigger.Kind.TAP))
        assertFalse(WriteSecureSettingsHolders.anyNeeded(context))
    }

    // ---- the keyboard-light tile's own holder ------------------------------

    private fun grantWriteSecureSettings() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
    }

    private val tap get() = BrokerTrigger.forTest(BrokerTrigger.Kind.TAP)

    @Test
    fun theTileSwitchStartsOffOnAFreshInstallAndIsStored() {
        assertFalse(SettingsManager.getBacklightTileEnabled(context))
        assertTrue(SettingsManager.getPreferences(context).contains("backlight_tile_enabled"))
        // Settled once: Smart backlight going on later does not flip it.
        SettingsManager.setSmartBacklightEnabled(context, true)
        assertFalse(SettingsManager.getBacklightTileEnabled(context))
    }

    /** An upgrade where the tile already worked keeps a working tile. */
    @Test
    fun theTileSwitchStartsOnWhereTheTileAlreadyWorked() {
        SettingsManager.setSmartBacklightEnabled(context, true)
        assertTrue(SettingsManager.getBacklightTileEnabled(context))

        SettingsManager.getPreferences(context).edit().clear().commit()
        grantWriteSecureSettings()
        assertTrue(SettingsManager.getBacklightTileEnabled(context))
    }

    @Test
    fun holderSyncIncludesTheTileSwitch() {
        SettingsManager.setBacklightTileEnabled(context, true)
        PrivilegedSetup.syncFeatureHolders(context)
        assertEquals(
            setOf(WriteSecureSettingsHolders.BACKLIGHT_TILE),
            WriteSecureSettingsHolders.holders(context)
        )

        SettingsManager.setBacklightTileEnabled(context, false)
        PrivilegedSetup.syncFeatureHolders(context)
        assertFalse(WriteSecureSettingsHolders.anyNeeded(context))
    }

    /** Re-pairing (applyAll) restores the tile's holder after a reset cleared them. */
    @Test
    fun applyAllKeepsTheTileHolder() {
        SettingsManager.setBacklightTileEnabled(context, true)
        WriteSecureSettingsHolders.clear(context)
        PrivilegedSetup.applyAll(context, "test", tap)
        assertTrue(WriteSecureSettingsHolders.BACKLIGHT_TILE in WriteSecureSettingsHolders.holders(context))
    }

    @Test
    fun smartBacklightOffWithTheTileOnKeepsThePermission() {
        grantWriteSecureSettings()
        SettingsManager.setSmartBacklightEnabled(context, true)
        SettingsManager.setBacklightTileEnabled(context, true)
        // The tile's holder was never recorded (as on an upgrade before any applyAll); the switch
        // going off must still see it.
        WriteSecureSettingsHolders.set(context, WriteSecureSettingsHolders.BACKLIGHT, true)

        SettingsManager.setSmartBacklightEnabled(context, false)
        val queued = PrivilegedSetup.setWriteSecureSettingsNeeded(
            context, WriteSecureSettingsHolders.BACKLIGHT, false, tap
        )

        assertFalse("no revoke queued", queued)
        assertEquals(
            setOf(WriteSecureSettingsHolders.BACKLIGHT_TILE),
            WriteSecureSettingsHolders.holders(context)
        )
    }

    @Test
    fun theTileSwitchOffWithNothingElseRevokes() {
        grantWriteSecureSettings()
        SettingsManager.setBacklightTileEnabled(context, true)
        PrivilegedSetup.syncFeatureHolders(context)

        SettingsManager.setBacklightTileEnabled(context, false)
        val queued = PrivilegedSetup.setWriteSecureSettingsNeeded(
            context, WriteSecureSettingsHolders.BACKLIGHT_TILE, false, tap
        )

        assertTrue("the revoke is queued", queued)
        assertFalse(WriteSecureSettingsHolders.anyNeeded(context))
    }

    @Test
    fun theTileSwitchOffKeepsThePermissionWhileSmartBacklightHoldsIt() {
        grantWriteSecureSettings()
        SettingsManager.setSmartBacklightEnabled(context, true)
        SettingsManager.setBacklightTileEnabled(context, false)

        val queued = PrivilegedSetup.setWriteSecureSettingsNeeded(
            context, WriteSecureSettingsHolders.BACKLIGHT_TILE, false, tap
        )

        assertFalse(queued)
        assertEquals(setOf(WriteSecureSettingsHolders.BACKLIGHT), WriteSecureSettingsHolders.holders(context))
    }

    @Test
    fun aBlockedBrokerRecordsTheReasonAgainstTheBacklightStep() {
        KeyboardBacklightManager.applyAlwaysOn(context, BrokerTrigger.forTest(BrokerTrigger.Kind.TAP))

        val outcome = requireNotNull(
            PrivilegedDiagnostics.last(context, PrivilegedDiagnostics.Step.BACKLIGHT)
        )
        assertFalse(outcome.ok)
        assertEquals(PrivilegedDiagnostics.REASON_NOT_PAIRED, outcome.reason)
    }

    @Test
    fun theShellRefusesToRunWhileTheBrokerIsBlocked() {
        val result = PrivilegedShell.run(
            context, PrivilegedCommand.ReadBacklightTimeout, BrokerTrigger.forTest(BrokerTrigger.Kind.TAP)
        )

        assertFalse(result.ok)
        assertEquals(EmbeddedAdbShell.REASON_NOT_PAIRED, result.failure)
        assertEquals(EmbeddedAdbShell.BrokerStatus.NOT_PAIRED, result.status)
    }

    @Test
    fun theKeyboardLightSwitchHookRefusesValuesOtherThanZeroAndOne() {
        assertFalse(KeyboardBacklightManager.writeKeyboardLightSwitch(context, 2))
        assertFalse(KeyboardBacklightManager.writeKeyboardLightSwitch(context, -1))
        assertEquals(
            KeyboardBacklightManager.KEYBOARD_LIGHT_ON,
            KeyboardBacklightManager.readKeyboardLightSwitch(context)
        )
    }

    @Test
    fun theKeyboardLightSwitchHookWritesTheVendorRow() {
        assertTrue(KeyboardBacklightManager.writeKeyboardLightSwitch(context, 0))
        assertEquals(0, KeyboardBacklightManager.readKeyboardLightSwitch(context))
        assertEquals(
            0,
            Settings.Global.getInt(context.contentResolver, "agui_keyboard_background_light", 1)
        )
        assertTrue(KeyboardBacklightManager.writeKeyboardLightSwitch(context, 1))
        assertEquals(1, KeyboardBacklightManager.readKeyboardLightSwitch(context))
    }
}
