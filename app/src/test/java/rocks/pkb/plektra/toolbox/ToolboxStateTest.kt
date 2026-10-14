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
