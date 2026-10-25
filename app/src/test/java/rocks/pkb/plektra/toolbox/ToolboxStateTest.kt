package rocks.pkb.plektra.toolbox

import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.inputmethod.PrivilegedCommand
import moe.shizuku.manager.adb.EmbeddedAdbInit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ToolboxStateTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `journal records before the command and forgets after a failure`() {
        RemovalJournal.clear(context)
        RemovalJournal.record(
            context,
            RemovalJournal.Record("com.agui.factorytest", PackageState.ACTIVE, RemovalJournal.Action.DISABLED, 1L)
        )
        // One record per package: acting again replaces rather than stacks.
        RemovalJournal.record(
            context,
            RemovalJournal.Record("com.agui.factorytest", PackageState.DISABLED, RemovalJournal.Action.UNINSTALLED, 2L)
        )
        val all = RemovalJournal.all(context)
        assertEquals(1, all.size)
        assertEquals(RemovalJournal.Action.UNINSTALLED, all.single().action)
        assertEquals(PackageState.DISABLED, all.single().previousState)

        RemovalJournal.forget(context, "com.agui.factorytest")
        assertTrue(RemovalJournal.all(context).isEmpty())
    }

    @Test
    fun `an unreadable journal starts empty`() {
        EmbeddedAdbInit.prefs(context).edit().putString("toolbox_removal_journal", "{not json").commit()
        assertTrue(RemovalJournal.all(context).isEmpty())
        RemovalJournal.clear(context)
    }

    @Test
    fun `toolbox state lives in the broker prefs file that backups exclude`() {
        RemovalJournal.record(
            context,
            RemovalJournal.Record("com.agui.factorytest", PackageState.ACTIVE, RemovalJournal.Action.DISABLED, 1L)
        )
        assertTrue(EmbeddedAdbInit.prefs(context).contains("toolbox_removal_journal"))
        assertFalse(SettingsManager.getPreferences(context).contains("toolbox_removal_journal"))
        RemovalJournal.clear(context)
    }

    @Test
    fun `a pending change stores a kind and a deadline, never a command line`() {
        assertNull(RevertibleChange.pending(context))
        EmbeddedAdbInit.prefs(context).edit()
            .putString("toolbox_pending_revert", """{"kind":"DENSITY","deadline":123}""").commit()
        val pending = RevertibleChange.pending(context)
        assertNotNull(pending)
        assertEquals(RevertibleChange.Kind.DENSITY, pending!!.kind)
        assertEquals(0L, pending.remainingMs(now = 200L))
        assertEquals(23L, pending.remainingMs(now = 100L))
        // The revert is a fixed command, whatever the stored text says.
        assertEquals(PrivilegedCommand.ResetDensity, RevertibleChange.Kind.DENSITY.revert)

        // A stored kind this build does not know, or an old shape carrying a command string, is ignored.
        EmbeddedAdbInit.prefs(context).edit()
            .putString("toolbox_pending_revert", """{"id":"display_density","apply":"x","revert":"rm -rf /"}""").commit()
        assertNull(RevertibleChange.pending(context))
        EmbeddedAdbInit.prefs(context).edit().remove("toolbox_pending_revert").commit()
    }

    @Test
    fun `a density revert goes back to the earlier override, and to stock only without one`() {
        // The user had 295 on a 300 panel: Apply must remember it, and the revert restores it.
        val override = DisplayDensity.Info(physical = 300, current = 295)
        assertEquals(295, DisplayDensity.previousOverride(override))
        val withOverride = RevertibleChange.Pending(
            RevertibleChange.Kind.DENSITY, 1L, DisplayDensity.previousOverride(override)
        )
        assertEquals("wm density 295", withOverride.revert.commandLine("p.q"))

        // No override in force: the revert is the plain reset.
        val stock = DisplayDensity.Info(physical = 300, current = 300)
        assertNull(DisplayDensity.previousOverride(stock))
        val noOverride = RevertibleChange.Pending(
            RevertibleChange.Kind.DENSITY, 1L, DisplayDensity.previousOverride(stock)
        )
        assertEquals(PrivilegedCommand.ResetDensity, noOverride.revert)
    }

    @Test
    fun `the earlier density survives the preferences round trip and is range checked`() {
        val prefs = EmbeddedAdbInit.prefs(context)
        prefs.edit().putString("toolbox_pending_revert", """{"kind":"DENSITY","deadline":5,"previous_dpi":295}""").commit()
        val pending = RevertibleChange.pending(context)!!
        assertEquals(295, pending.previousDpi)
        assertEquals("wm density 295", pending.revert.commandLine("p.q"))

        // A stored value outside the command's bounds, or no value at all, reverts to stock.
        for (stored in listOf("""{"kind":"DENSITY","deadline":5,"previous_dpi":99999}""",
            """{"kind":"DENSITY","deadline":5,"previous_dpi":-3}""", """{"kind":"DENSITY","deadline":5}""")) {
            prefs.edit().putString("toolbox_pending_revert", stored).commit()
            val read = RevertibleChange.pending(context)!!
            assertNull(read.previousDpi)
            assertEquals(PrivilegedCommand.ResetDensity, read.revert)
        }
        prefs.edit().remove("toolbox_pending_revert").commit()
    }

    @Test
    fun `a second apply is refused while an earlier change waits, and leaves it untouched`() {
        val prefs = EmbeddedAdbInit.prefs(context)
        val stored = """{"kind":"DENSITY","deadline":4102444800000,"previous_dpi":295}"""
        prefs.edit().putString("toolbox_pending_revert", stored).commit()

        // The first, unconfirmed value must never become the "earlier override" of a second apply.
        val outcome = RevertibleChange.apply(
            context, RevertibleChange.Kind.DENSITY, PrivilegedCommand.SetDensity(320), trigger = null,
            previousDpi = 310
        )
        assertEquals(RevertibleChange.Outcome.Failed(RevertibleChange.CHANGE_WAITING), outcome)
        assertEquals(stored, prefs.getString("toolbox_pending_revert", null))
        assertEquals(295, RevertibleChange.pending(context)!!.previousDpi)
        prefs.edit().remove("toolbox_pending_revert").commit()
    }

    @Test
    fun `status bar toggle defaults to shown and only hides on a Titan 2 Elite`() {
        SettingsManager.getPreferences(context).edit().remove(SettingsManager.KEY_SHOW_STATUS_BAR).commit()
        assertTrue(SettingsManager.getShowStatusBar(context))
        SettingsManager.setShowStatusBar(context, false)
        assertFalse(SettingsManager.getShowStatusBar(context))
        // Robolectric is not a Titan 2 Elite, so a restored "off" must not hide the bar here.
        assertFalse(SettingsManager.isStatusBarHidden(context))
        SettingsManager.setShowStatusBar(context, true)
    }

    @Test
    fun `key inventory sends only the Fn key to the Fn layer`() {
        val keys = KeyInventory.keys(context)
        val byLabel = keys.associateBy { it.label }
        assertEquals(KeyInventory.Editor.FnLayer, byLabel.getValue("Fn").editor)
        // Issue #18: the orange side key and Space were routed to the wrong settings page.
        assertEquals(KeyInventory.Editor.None, byLabel.getValue("Orange side key").editor)
        assertEquals(KeyInventory.Editor.None, byLabel.getValue("Space").editor)
        assertEquals(1, keys.count { it.editor == KeyInventory.Editor.FnLayer })
    }
}
