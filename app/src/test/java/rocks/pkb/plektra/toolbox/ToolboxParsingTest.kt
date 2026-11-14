package rocks.pkb.plektra.toolbox

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolboxParsingTest {

    @Test
    fun `density output gives stock and override`() {
        val stock = "Physical density: 420\n"
        assertEquals(420, DisplayDensity.parsePhysical(stock))
        assertNull(DisplayDensity.parseOverride(stock))

        val overridden = "Physical density: 420\nOverride density: 360\n"
        assertEquals(420, DisplayDensity.parsePhysical(overridden))
        assertEquals(360, DisplayDensity.parseOverride(overridden))
        assertNull(DisplayDensity.parsePhysical("error"))
    }

    @Test
    fun `density range is 60 to 140 percent of stock`() {
        assertEquals(252..588, DisplayDensity.range(420))
        assertTrue(DisplayDensity.Info(physical = 420, current = 360).isOverridden)
        assertFalse(DisplayDensity.Info(physical = 420, current = 420).isOverridden)
    }

    @Test
    fun `tweak output is parsed in command order`() {
        val state = SystemTweaks.parse("0.5\n1\nnull\n")!!
        assertEquals(SystemTweaks.AnimationSpeed.FAST, state.animation)
        assertEquals(true, state.toggles[SystemTweaks.Toggle.NOTIFICATION_HISTORY])
        // "null" is what settings prints for a key that was never written: shipped state, not on.
        assertEquals(false, state.toggles[SystemTweaks.Toggle.ONE_HANDED_MODE])
        assertNull(SystemTweaks.parse(""))
    }

    @Test
    fun `animation speed snaps to the nearest choice`() {
        assertEquals(SystemTweaks.AnimationSpeed.NORMAL, SystemTweaks.AnimationSpeed.nearest(1.5f))
        assertEquals(SystemTweaks.AnimationSpeed.OFF, SystemTweaks.AnimationSpeed.nearest(0.1f))
    }

    @Test
    fun `the one-handed toggle uses the AOSP key`() {
        // PhysiBoard wrote one_handed_enabled, which nothing reads.
        assertEquals("one_handed_mode_enabled", SystemTweaks.Toggle.ONE_HANDED_MODE.key)
    }

    @Test
    fun `fn revert restores a usable capture and otherwise turns the remap off`() {
        assertEquals(0 to 0, FnCtrlRemap.revertTarget(false, 1, 1))
        assertEquals(0 to 0, FnCtrlRemap.revertTarget(true, FnCtrlRemap.VALUE_UNSET, FnCtrlRemap.VALUE_UNSET))
        assertEquals(1 to 3, FnCtrlRemap.revertTarget(true, 1, 3))
        assertEquals(0 to 0, FnCtrlRemap.revertTarget(true, -5, 99999))
    }

    @Test
    fun `fn writes go to the privileged backend whenever the settings route is missing or fails`() {
        fun write(canWrite: Boolean, settingsWorks: Boolean, hasBackend: Boolean, backendWorks: Boolean): Pair<FnCtrlRemap.Result, List<String>> {
            val ran = mutableListOf<String>()
            val result = FnCtrlRemap.writeWithFallback(
                canWriteSettings = canWrite,
                viaSettings = { ran += "settings"; settingsWorks },
                hasBackend = hasBackend,
                viaBackend = { ran += "backend"; backendWorks }
            )
            return result to ran
        }

        // No "Modify system settings": the backend is tried (this was the reset that never was).
        assertEquals(FnCtrlRemap.Result.SUCCESS to listOf("backend"), write(false, false, true, true))
        assertEquals(FnCtrlRemap.Result.FAILED to listOf("backend"), write(false, false, true, false))
        // Granted and working: the backend is not touched.
        assertEquals(FnCtrlRemap.Result.SUCCESS to listOf("settings"), write(true, true, true, false))
        // Granted but the write did not take: fall through to the backend.
        assertEquals(FnCtrlRemap.Result.SUCCESS to listOf("settings", "backend"), write(true, false, true, true))
        // Neither route: ask for the permission; a failed settings write alone is a failure.
        assertEquals(FnCtrlRemap.Result.NEEDS_PERMISSION to emptyList<String>(), write(false, false, false, false))
        assertEquals(FnCtrlRemap.Result.FAILED to listOf("settings"), write(true, false, false, false))
    }

    @Test
    fun `short names keep only the tail of an activity`() {
        assertEquals("ShortcutActivity", KeyInventory.shortName("com.agui.shortcutsettings.ShortcutActivity"))
        assertEquals("plain", KeyInventory.shortName("plain"))
    }

    @Test
    fun `a Shift key the vendor Key setting turned into Alt says so`() {
        val shiftRight = KeyEvent.KEYCODE_SHIFT_RIGHT
        // Seen on a Titan 2: persist.sys.agui_programmable_shift_r_key = 58 (ALT_RIGHT).
        assertEquals("Types Alt (vendor Key setting)", KeyInventory.modifierBinding(null, "58", shiftRight))
        assertEquals("Types Shift", KeyInventory.modifierBinding(null, null, shiftRight))
        assertEquals("Types Shift", KeyInventory.modifierBinding(null, "$shiftRight", shiftRight))
        assertEquals("Vendor remapping enabled", KeyInventory.modifierBinding("1", "58", shiftRight))
    }
}
