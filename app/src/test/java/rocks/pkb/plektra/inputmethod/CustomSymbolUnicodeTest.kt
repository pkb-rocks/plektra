package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.core.ModifierStateController
import rocks.pkb.plektra.core.SymLayoutController
import java.lang.reflect.Proxy
import java.util.Locale

/**
 * Tests for custom Unicode symbol support:
 * - Unicode code entry (U+XXXX)
 * - Shift uppercase transformation with Turkish locale support
 * - Multi-codepoint grapheme support (emoji, flags, variation selectors)
 * - Real symbol-page input path (SymLayoutController + Shift one-shot)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class CustomSymbolUnicodeTest {

    @Test
    fun unicodeCodeEntry_valid_shouldParse() {
        // Basic Latin
        assertEquals("Ö", parseUnicodeCode("U+00D6"))
        assertEquals("Ö", parseUnicodeCode("u+00d6"))
        
        // Emoji
        assertEquals("😀", parseUnicodeCode("U+1F600"))
        assertEquals("🔥", parseUnicodeCode("u+1f525"))
    }

    @Test
    fun unicodeCodeEntry_invalid_shouldReject() {
        // Out of range
        assertNull(parseUnicodeCode("U+110000"))
        
        // Surrogate range
        assertNull(parseUnicodeCode("U+D800"))
        assertNull(parseUnicodeCode("U+DFFF"))
        
        // Invalid format
        assertNull(parseUnicodeCode("U+"))
        assertNull(parseUnicodeCode("U+GGGG"))
    }

    @Test
    fun shiftUppercase_standardLocale_shouldTransform() {
        val result = toUppercaseWithLocale("ö", isTurkish = false)
        assertEquals("Ö", result)
        
        assertEquals("Ç", toUppercaseWithLocale("ç", isTurkish = false))
        assertEquals("Ş", toUppercaseWithLocale("ş", isTurkish = false))
        assertEquals("Ğ", toUppercaseWithLocale("ğ", isTurkish = false))
    }

    @Test
    fun shiftUppercase_turkishLocale_shouldTransformI() {
        // Turkish: ı→I, i→İ
        assertEquals("I", toUppercaseWithLocale("ı", isTurkish = true))
        assertEquals("İ", toUppercaseWithLocale("i", isTurkish = true))
    }

    @Test
    fun shiftUppercase_standardLocale_shouldTransformI() {
        // Standard: i→I
        assertEquals("I", toUppercaseWithLocale("i", isTurkish = false))
    }

    @Test
    fun shiftUppercase_noCase_shouldLeaveUnchanged() {
        // Emoji, digits, punctuation have no case
        assertEquals("😀", toUppercaseWithLocale("😀", isTurkish = false))
        assertEquals("123", toUppercaseWithLocale("123", isTurkish = false))
        assertEquals("!", toUppercaseWithLocale("!", isTurkish = false))
    }

    @Test
    fun shiftUppercase_multiChar_shouldTransform() {
        // ß→SS is multi-char uppercase (German sharp s)
        val result = toUppercaseWithLocale("ß", isTurkish = false)
        assertEquals("SS", result)
    }

    @Test
    fun customMapping_persistence_shouldSurviveBackup() {
        val context: Context = RuntimeEnvironment.getApplication()
        
        // Save custom mappings
        val mappings = mapOf(
            android.view.KeyEvent.KEYCODE_Q to "😀",
            android.view.KeyEvent.KEYCODE_W to "Ö",
            android.view.KeyEvent.KEYCODE_E to "U+1F525" // This would be parsed by UI
        )
        SettingsManager.saveSymMappings(context, mappings)
        
        // Verify saved
        val loaded = SettingsManager.getSymMappings(context)
        assertEquals(3, loaded.size)
        assertEquals("😀", loaded[android.view.KeyEvent.KEYCODE_Q])
        assertEquals("Ö", loaded[android.view.KeyEvent.KEYCODE_W])
    }

    /**
     * Real symbol-page path: custom `ö` + Shift one-shot commits `Ö` and clears one-shot.
     * Mirrors PhysicalKeyboardInputMethodService wiring
     * (`shiftPressed = shiftOneShot`, `onSymbolCommitted = consumeShiftOneShot`).
     */
    @Test
    fun symbolPage_customOe_withShiftOneShot_commitsUppercaseAndClearsOneShot() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.saveSymMappingsPage2(context, mapOf(KeyEvent.KEYCODE_A to "ö"))
        SettingsManager.setSymAutoClose(context, false)
        val prefs = SettingsManager.getPreferences(context)
        val manager = AlternateCharacterManager(context.assets, prefs, context)
        manager.reloadSymMappings2()

        val controller = SymLayoutController(context, prefs, manager)
        controller.openSymbolsPage()

        val commits = mutableListOf<String>()
        val connection = recordingConnection(commits)
        val modifiers = ModifierStateController(doubleTapThreshold = 300L)
        modifiers.handleShiftKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT)
        modifiers.handleShiftKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT)
        assertTrue("Shift one-shot should be active before symbol key", modifiers.shiftOneShot)

        assertEquals(
            "Shifted display mapping should be Ö",
            "Ö",
            controller.currentSymMappings(shiftPressed = true)!![KeyEvent.KEYCODE_A]
        )

        controller.handleKeyWhenActive(
            keyCode = KeyEvent.KEYCODE_A,
            event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A),
            inputConnection = connection,
            ctrlLatchActive = false,
            altLatchActive = false,
            updateStatusBar = {},
            shiftPressed = modifiers.shiftOneShot,
            onSymbolCommitted = { modifiers.consumeShiftOneShot() }
        )

        assertEquals(listOf("Ö"), commits)
        assertFalse("Shift one-shot should clear after symbol commit", modifiers.shiftOneShot)
        assertEquals("ö", SettingsManager.getSymMappingsPage2(context)[KeyEvent.KEYCODE_A])
    }

    @Test
    fun emojiPage_customEmoji_withShiftOneShot_staysUnchanged() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.saveSymMappings(context, mapOf(KeyEvent.KEYCODE_A to "😀"))
        SettingsManager.setSymAutoClose(context, false)
        val prefs = SettingsManager.getPreferences(context)
        val manager = AlternateCharacterManager(context.assets, prefs, context)
        manager.reloadSymMappings()

        val controller = SymLayoutController(context, prefs, manager)
        controller.openEmojiPage()

        val commits = mutableListOf<String>()
        val connection = recordingConnection(commits)
        val modifiers = ModifierStateController(doubleTapThreshold = 300L)
        modifiers.handleShiftKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT)
        modifiers.handleShiftKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT)
        assertTrue(modifiers.shiftOneShot)

        controller.handleKeyWhenActive(
            keyCode = KeyEvent.KEYCODE_A,
            event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A),
            inputConnection = connection,
            ctrlLatchActive = false,
            altLatchActive = false,
            updateStatusBar = {},
            shiftPressed = modifiers.shiftOneShot,
            onSymbolCommitted = { modifiers.consumeShiftOneShot() }
        )

        assertEquals(listOf("😀"), commits)
        assertFalse(modifiers.shiftOneShot)
    }

    /**
     * Old-format backup: only the lowercase mapping is stored in prefs JSON.
     * reloadSymMappings must derive uppercase without requiring a re-save.
     */
    @Test
    fun oldFormatBackup_reloadDerivesUppercaseWithoutResave() {
        val context = RuntimeEnvironment.getApplication()
        val mappingsObject = JSONObject().put("KEYCODE_A", "ö")
        val jsonObject = JSONObject().put("mappings", mappingsObject)
        SettingsManager.getPreferences(context).edit()
            .putString("sym_mappings_page2_custom", jsonObject.toString())
            .commit()

        val prefs = SettingsManager.getPreferences(context)
        val manager = AlternateCharacterManager(context.assets, prefs, context)
        manager.reloadSymMappings2()

        assertEquals("ö", manager.getSymMappings2()[KeyEvent.KEYCODE_A])
        assertEquals("Ö", manager.getSymMappings2Uppercase()[KeyEvent.KEYCODE_A])

        val controller = SymLayoutController(context, prefs, manager)
        controller.openSymbolsPage()
        val commits = mutableListOf<String>()
        controller.handleKeyWhenActive(
            keyCode = KeyEvent.KEYCODE_A,
            event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A),
            inputConnection = recordingConnection(commits),
            ctrlLatchActive = false,
            altLatchActive = false,
            updateStatusBar = {},
            shiftPressed = true
        )
        assertEquals(listOf("Ö"), commits)
        assertEquals("ö", SettingsManager.getSymMappingsPage2(context)[KeyEvent.KEYCODE_A])
    }

    // Helper functions matching the implementation

    private fun parseUnicodeCode(code: String): String? {
        if (!code.matches(Regex("^[Uu]\\+[0-9A-Fa-f]{1,6}$"))) {
            return null
        }
        
        val codeStr = code.substring(2)
        return try {
            val codePoint = codeStr.toInt(16)
            if (codePoint !in 0..0x10FFFF || (codePoint in 0xD800..0xDFFF)) {
                null
            } else {
                String(Character.toChars(codePoint))
            }
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun toUppercaseWithLocale(text: String, isTurkish: Boolean): String {
        if (text.isEmpty()) return text
        
        return if (isTurkish) {
            val locale = Locale("tr", "TR")
            text.uppercase(locale)
        } else {
            text.uppercase()
        }
    }

    private fun recordingConnection(commits: MutableList<String>): InputConnection {
        return Proxy.newProxyInstance(
            InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java)
        ) { _, method, args ->
            if (method.name == "commitText") commits.add(args!![0].toString())
            when (method.returnType) {
                java.lang.Boolean.TYPE -> true
                java.lang.Integer.TYPE -> 0
                else -> null
            }
        } as InputConnection
    }
}
