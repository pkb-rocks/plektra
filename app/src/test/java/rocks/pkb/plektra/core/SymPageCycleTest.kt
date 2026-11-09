package rocks.pkb.plektra.core

import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.SymPagesConfig
import rocks.pkb.plektra.inputmethod.AlternateCharacterManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SymPageCycleTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        SettingsManager.setSymPagesConfig(
            context,
            SymPagesConfig(deviceEnabled = false, emojiEnabled = true, symbolsEnabled = true)
        )
    }

    @After
    fun tearDown() {
        SettingsManager.setSoftwareKeyboardModeRuntimeOverride(context, null)
        SettingsManager.getPreferences(context).edit().clear().commit()
    }

    private fun controller(): SymLayoutController {
        val prefs = SettingsManager.getPreferences(context)
        return SymLayoutController(context, prefs, AlternateCharacterManager(context.assets, prefs, context))
    }

    @Test
    fun onScreenCycleSkipsEmojiLayerByDefault() {
        SettingsManager.setSoftwareKeyboardModeRuntimeOverride(context, SettingsManager.SoftwareKeyboardMode.FORCE_VIRTUAL)
        val sym = controller()
        assertFalse(SettingsManager.getSoftwareKeyboardEmojiPageInCycle(context))
        assertEquals(2, sym.peekNextSymPage(onScreen = true))
        assertEquals(2, sym.toggleSymPage(onScreen = true))
        assertEquals(0, sym.toggleSymPage(onScreen = true))
        assertEquals(2, sym.nextSoftwareTextSymPage())
    }

    @Test
    fun emojiLayerReturnsToOnScreenCycleWhenEnabled() {
        SettingsManager.setSoftwareKeyboardModeRuntimeOverride(context, SettingsManager.SoftwareKeyboardMode.FORCE_VIRTUAL)
        SettingsManager.setSoftwareKeyboardEmojiPageInCycle(context, true)
        val sym = controller()
        assertEquals(1, sym.toggleSymPage(onScreen = true))
        assertEquals(2, sym.toggleSymPage(onScreen = true))
    }

    @Test
    fun physicalCycleAndDirectEmojiAccessAreUnchangedInEverySurfaceMode() {
        SettingsManager.setSoftwareKeyboardModeRuntimeOverride(context, SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE)
        assertEquals(1, controller().toggleSymPage())
        assertEquals(1, controller().apply { closeSymPage() }.toggleSymPage(onScreen = true))

        SettingsManager.setSoftwareKeyboardModeRuntimeOverride(context, SettingsManager.SoftwareKeyboardMode.FORCE_VIRTUAL)
        val sym = controller()
        sym.closeSymPage()
        assertEquals(1, sym.peekNextSymPage())
        assertEquals(1, sym.toggleSymPage())
        sym.closeSymPage()
        assertTrue(sym.openPage(1))
        assertEquals(1, sym.currentSymPage())
    }

    @Test
    fun fieldScopedVirtualKeyboardRestoresPreviousOverride() {
        SettingsManager.setSoftwareKeyboardModeRuntimeOverride(context, null)
        SettingsManager.beginFieldScopedVirtualKeyboard(context)
        SettingsManager.beginFieldScopedVirtualKeyboard(context)
        assertEquals(SettingsManager.SoftwareKeyboardMode.FORCE_VIRTUAL, SettingsManager.getSoftwareKeyboardModeRuntimeOverride(context))
        assertTrue(SettingsManager.endFieldScopedVirtualKeyboard(context))
        assertNull(SettingsManager.getSoftwareKeyboardModeRuntimeOverride(context))
        assertFalse(SettingsManager.endFieldScopedVirtualKeyboard(context))

        SettingsManager.beginFieldScopedVirtualKeyboard(context)
        SettingsManager.setSoftwareKeyboardModeRuntimeOverride(context, SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE)
        assertTrue(SettingsManager.endFieldScopedVirtualKeyboard(context))
        assertEquals(SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE, SettingsManager.getSoftwareKeyboardModeRuntimeOverride(context))
    }
}
