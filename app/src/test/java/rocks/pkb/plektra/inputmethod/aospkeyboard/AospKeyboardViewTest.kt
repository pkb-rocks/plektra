package rocks.pkb.plektra.inputmethod.aospkeyboard

import android.content.Context
import android.graphics.RectF
import android.os.Looper
import android.os.VibratorManager
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.MotionEvent.PointerCoords
import android.view.MotionEvent.PointerProperties
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import rocks.pkb.plektra.SettingsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AospKeyboardViewTest {

    private class RecordingListener : AospKeyboardView.Listener {
        val texts = mutableListOf<String>()
        var shiftCount = 0
        var symbolCount = 0
        var languageSwitchCount = 0
        var cursorDelta = 0
        var backspaceCount = 0
        var enterCount = 0
        var ctrlCount = 0
        val soundKeyCodes = mutableListOf<Int>()
        val symbolLongPressKeyCodes = mutableListOf<Int>()
        val symbolTexts = mutableListOf<String>()
        val modifierDownKeyCodes = mutableListOf<Int>()
        val modifierUpKeyCodes = mutableListOf<Int>()

        override fun onText(text: String) { texts += text }
        override fun onBackspace() { backspaceCount++ }
        override fun onEnter() { enterCount++ }
        override fun onShift() { shiftCount++ }
        override fun onSymbols() { symbolCount++ }
        override fun onCtrl() { ctrlCount++ }
        override fun onLanguageSwitch() { languageSwitchCount++ }
        override fun onCursorMove(delta: Int) { cursorDelta += delta }
        override fun onKeyPressSound(keyCode: Int) { soundKeyCodes += keyCode }
        override fun onSymbolLongPress(keyCode: Int): Boolean {
            symbolLongPressKeyCodes += keyCode
            return true
        }
        override fun onSymbolText(text: String): Boolean {
            symbolTexts += text
            return true
        }
        override fun onModifierKeyDown(keyCode: Int): Boolean {
            modifierDownKeyCodes += keyCode
            return true
        }
        override fun onModifierKeyUp(keyCode: Int): Boolean {
            modifierUpKeyCodes += keyCode
            return true
        }
    }

    @Test
    fun nonLatinProfilesRenderAndCommitTheirOwnMappingsInEverySoftwareStyle() {
        val context = RuntimeEnvironment.getApplication()
        val layouts = listOf("russian_standard", "russian_jcuken", "russian_translit", "ukrainian",
            "serbian_cyrillic", "bulgarian_phonetic", "bulgarian_phonetic_traditional", "Cyrillic_Translite", "uyghur")
        layouts.forEach { layout ->
            val mappings = requireNotNull(rocks.pkb.plektra.data.layout.JsonLayoutLoader.loadLayout(context.assets, layout, context))
            AospKeyboardView.SoftwareLayoutStyle.entries.forEach { style ->
                val listener = RecordingListener()
                val view = measuredKeyboard().apply { layoutName = layout; layoutStyle = style; this.listener = listener }
                for (shift in listOf(false, true)) {
                    view.shifted = shift
                    for (letter in 'a'..'z') {
                        val mapping = mappings[KeyEvent.KEYCODE_A + (letter - 'a')] ?: continue
                        val expected = if (shift) mapping.uppercase else mapping.lowercase
                        val key = requireNotNull(view.visibleAccessibilitySnapshots().find { it.resourceName == "plektra_key_$letter" })
                        assertEquals("$layout/$style/$letter shift=$shift", expected, key.label)
                        assertTrue(view.performAccessibilityClickForResourceName(key.resourceName))
                        assertEquals(expected, listener.texts.last())
                    }
                }
                view.layoutName = "qwerty"
                assertEquals("Q", view.visibleAccessibilitySnapshots().first { it.resourceName == "plektra_key_q" }.label)
            }
        }
    }

    @Test
    fun customCyrillicProfileKeepsItsCaseSensitiveNameAndMultitapAlternates() {
        val context = RuntimeEnvironment.getApplication()
        val name = "Русский Custom Profile"
        val mapping = rocks.pkb.plektra.data.layout.LayoutMapping("й", "Й", true,
            listOf(rocks.pkb.plektra.data.layout.TapMapping("й", "Й"), rocks.pkb.plektra.data.layout.TapMapping("ё", "Ё")))
        assertTrue(rocks.pkb.plektra.data.layout.LayoutFileStore.saveLayout(context, name, mapOf(KeyEvent.KEYCODE_Q to mapping)))
        val view = measuredKeyboard().apply { layoutName = name }
        assertEquals("й", view.visibleAccessibilitySnapshots().first { it.resourceName == "plektra_key_q" }.label)
        assertEquals("ё", displayHint(view, keyForLabel(view, "q")))
        view.shifted = true
        assertEquals("Й", view.visibleAccessibilitySnapshots().first { it.resourceName == "plektra_key_q" }.label)
        assertEquals("Ё", displayHint(view, keyForLabel(view, "q")))
        view.symPageActive = true
        view.symPageLabels = mapOf(KeyEvent.KEYCODE_Q to "@")
        assertEquals("@", symPageLabel(view, keyForLabel(view, "q")))
        assertTrue(rocks.pkb.plektra.data.layout.LayoutFileStore.saveLayout(context, name,
            mapOf(KeyEvent.KEYCODE_Q to rocks.pkb.plektra.data.layout.LayoutMapping("жж", "ЖЖ"))))
        view.layoutName = name
        view.symPageActive = false
        assertEquals("ЖЖ", view.visibleAccessibilitySnapshots().first { it.resourceName == "plektra_key_q" }.label)
    }

    @Test
    fun keyTouchUsesConfiguredCustomTapDuration() {
        val context = RuntimeEnvironment.getApplication() as android.app.Application
        SettingsManager.setTapHapticUseSystem(context, false)
        SettingsManager.setTapHapticDurationMs(context, 40L)
        org.robolectric.shadows.ShadowVibrator.reset()
        val vibrator = (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
            .defaultVibrator
        val shadowVibrator = Shadows.shadowOf(vibrator).apply { setHasVibrator(true) }
        val view = measuredKeyboard()
        val (x, y) = centerOfLabel(view, "a")

        view.dispatchTouchEvent(motion(MotionEvent.ACTION_DOWN, x, y, 0L))

        assertEquals(40L, shadowVibrator.milliseconds)
        view.dispatchTouchEvent(motion(MotionEvent.ACTION_CANCEL, x, y, 10L))
    }

    @Test
    fun accessibilityIdsFollowLogicalLettersAcrossQwertyQwertzAndAzerty() {
        val view = measuredKeyboard()
        val qwertyY = accessibilitySnapshot(view, "plektra_key_y")
        val qwertyYId = qwertyY.virtualId

        view.layoutName = "qwertz"
        val qwertzY = accessibilitySnapshot(view, "plektra_key_y")

        assertEquals(qwertyYId, accessibilityVirtualId(view, "plektra_key_y"))
        assertNotEquals(qwertyY.bounds.top, qwertzY.bounds.top)

        val qwertzABounds = accessibilitySnapshot(view, "plektra_key_a").bounds
        view.layoutName = "azerty"
        val azertyABounds = accessibilitySnapshot(view, "plektra_key_a").bounds

        assertNotEquals(qwertzABounds.top, azertyABounds.top)
        assertEquals("plektra_key_a", accessibilitySnapshot(view, "plektra_key_a").resourceName)
    }

    @Test
    fun everyVisibleKeyHasUniqueAccessibleIdentityLabelAndBounds() {
        val view = measuredKeyboard().apply {
            layoutName = "german_multitap_qwertz"
            layoutStyle = AospKeyboardView.SoftwareLayoutStyle.FULL_ISO
            includeNumberRow = true
        }

        val snapshots = view.visibleAccessibilitySnapshots()

        assertEquals(keys(view).size, snapshots.size)
        assertEquals(snapshots.size, snapshots.map { it.virtualId }.distinct().size)
        assertEquals(snapshots.size, snapshots.map { it.resourceName }.distinct().size)
        assertTrue(snapshots.all { it.label.isNotBlank() })
        assertTrue(snapshots.all { it.bounds.width() > 0 && it.bounds.height() > 0 })
        assertTrue(snapshots.any { it.resourceName == "plektra_key_shift" })
        assertTrue(snapshots.any { it.resourceName == "plektra_key_shift_right" })
    }

    @Test
    fun accessibilitySemanticsTrackModifierAndSymStatesWithoutChangingId() {
        val view = measuredKeyboard()
        val eId = accessibilityVirtualId(view, "plektra_key_e")

        assertEquals("e", accessibilitySnapshot(view, "plektra_key_e").label)

        view.shifted = true
        assertEquals(eId, accessibilityVirtualId(view, "plektra_key_e"))
        assertEquals("E", accessibilitySnapshot(view, "plektra_key_e").label)

        view.shifted = false
        view.ctrlPreviewActive = true
        view.ctrlPreviewLabels = mapOf(KeyEvent.KEYCODE_E to "Move up")
        assertEquals("Move up", accessibilitySnapshot(view, "plektra_key_e").label)

        view.ctrlPreviewActive = false
        view.symPageActive = true
        view.symPageTextLabels = mapOf("e" to "€")
        assertEquals("€", accessibilitySnapshot(view, "plektra_key_e").label)

        view.symPageActive = false
        view.altPreviewActive = true
        view.altPreviewLabels = mapOf(KeyEvent.KEYCODE_E to "~")
        assertEquals("~", accessibilitySnapshot(view, "plektra_key_e").label)
        assertEquals(eId, accessibilityVirtualId(view, "plektra_key_e"))
    }

    @Test
    fun accessibilityNodesExposeBoundsAndSelectedPressedDisabledStates() {
        val view = measuredKeyboard()
        val ctrlNode = accessibilitySnapshot(view, "plektra_key_ctrl")
        val ctrlBounds = ctrlNode.bounds

        assertTrue(ctrlBounds.width() > 0)
        assertTrue(ctrlBounds.height() > 0)
        assertTrue(ctrlNode.clickable)
        assertTrue(ctrlNode.enabled)
        assertFalse(ctrlNode.selected)

        view.ctrlOneShot = true
        assertTrue(accessibilitySnapshot(view, "plektra_key_ctrl").selected)

        val (x, y) = centerOfLabel(view, "a")
        view.dispatchTouchEvent(motion(MotionEvent.ACTION_DOWN, x, y, 0L))
        assertEquals("Pressed", accessibilitySnapshot(view, "plektra_key_a").stateDescription)
        view.dispatchTouchEvent(motion(MotionEvent.ACTION_CANCEL, x, y, 10L))

        view.isEnabled = false
        val disabledNode = accessibilitySnapshot(view, "plektra_key_a")
        assertFalse(disabledNode.enabled)
        assertFalse(disabledNode.clickable)
        assertEquals("Disabled", disabledNode.stateDescription)
        assertFalse(view.performAccessibilityClickForResourceName("plektra_key_a"))
    }

    @Test
    fun accessibilityClickDispatchesCharacterBackspaceAndModifierLikeTouch() {
        val listener = RecordingListener()
        val view = measuredKeyboard().apply { this.listener = listener }
        assertTrue(view.performAccessibilityClickForResourceName("plektra_key_e"))
        assertTrue(view.performAccessibilityClickForResourceName("plektra_key_backspace"))
        assertTrue(view.performAccessibilityClickForResourceName("plektra_key_ctrl"))

        assertEquals(listOf("e"), listener.texts)
        assertEquals(1, listener.backspaceCount)
        assertEquals(listOf(KeyEvent.KEYCODE_CTRL_LEFT), listener.modifierDownKeyCodes)
        assertEquals(listOf(KeyEvent.KEYCODE_CTRL_LEFT), listener.modifierUpKeyCodes)
    }

    @Test
    fun germanMultitapQwertzCompact_normalizesToQwertzSoftwareGeometry() {
        val view = measuredKeyboard().apply {
            layoutName = "german_multitap_qwertz"
        }

        val labels = labels(view)

        assertTrue(!labels.contains("ä"))
        assertTrue(!labels.contains("ö"))
        assertTrue(!labels.contains("ü"))
        assertTrue(labels.containsAll(listOf("q", "w", "e", "r", "t", "z", "u", "i", "o", "p")))
    }

    @Test
    fun germanMultitapQwertzExtendedIso_rendersVisibleUmlautKeys() {
        val view = measuredKeyboard().apply {
            layoutName = "german_multitap_qwertz"
            layoutStyle = AospKeyboardView.SoftwareLayoutStyle.EXTENDED_ISO
        }

        val labels = labels(view)

        assertTrue(labels.containsAll(listOf("ä", "ö", "ü")))
        assertTrue(labels.containsAll(listOf("q", "w", "e", "r", "t", "z", "u", "i", "o", "p")))
    }

    @Test
    fun numberRowDisabledByDefaultInView_doesNotRenderDigits() {
        val view = measuredKeyboard()

        assertTrue(!labels(view).contains("1"))
        assertTrue(!labels(view).contains("0"))
    }

    @Test
    fun numberRowExtendedIso_alignsWithElevenKeyTopRow() {
        val view = measuredKeyboard().apply {
            layoutName = "german_multitap_qwertz"
            layoutStyle = AospKeyboardView.SoftwareLayoutStyle.EXTENDED_ISO
            includeNumberRow = true
        }

        val labels = labels(view)

        assertTrue(labels.containsAll(listOf("1", "0", "+", "ü")))
        assertEquals(leftOfLabel(view, "q"), leftOfLabel(view, "1"), 0.1f)
        assertEquals(leftOfLabel(view, "ü"), leftOfLabel(view, "+"), 0.1f)
    }

    @Test
    fun longPressDefaultSelection_survivesMoveOutsidePopup_andCommitsFirstAlternateOnRelease() {
        val listener = RecordingListener()
        val view = measuredKeyboard().apply {
            layoutName = "german_multitap_qwertz"
            longPressTimeoutMs = 50L
            longPressAlternatesProvider = { output -> if (output == "u") listOf("ü") else emptyList() }
            this.listener = listener
        }
        val (x, y) = centerOfLabel(view, "u")

        view.dispatchTouchEvent(motion(MotionEvent.ACTION_DOWN, x, y, 0L))
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.MILLISECONDS)
        view.dispatchTouchEvent(motion(MotionEvent.ACTION_MOVE, x, y, 70L))
        view.dispatchTouchEvent(motion(MotionEvent.ACTION_UP, x, y, 80L))

        assertEquals(listOf("ü"), listener.texts)
    }

    @Test
    fun touchDown_emitsKeyPressSoundImmediately() {
        val listener = RecordingListener()
        val view = measuredKeyboard().apply {
            this.listener = listener
        }
        val (x, y) = centerOfLabel(view, "a")

        view.dispatchTouchEvent(motion(MotionEvent.ACTION_DOWN, x, y, 0L))

        assertEquals(listOf(KeyEvent.KEYCODE_A), listener.soundKeyCodes)
    }

    @Test
    fun shiftKeyTap_notifiesListener() {
        val listener = RecordingListener()
        val view = measuredKeyboard().apply {
            this.listener = listener
        }
        val (x, y) = centerOfLabel(view, "⇧")

        view.dispatchTouchEvent(motion(MotionEvent.ACTION_DOWN, x, y, 0L))
        view.dispatchTouchEvent(motion(MotionEvent.ACTION_UP, x, y, 20L))

        assertEquals(1, listener.shiftCount)
    }

    @Test
    fun symPageShiftLongPress_doesNotOpenSymbolPicker() {
        val listener = RecordingListener()
        val view = measuredKeyboard().apply {
            symPageActive = true
            longPressTimeoutMs = 50L
            this.listener = listener
        }
        val (x, y) = centerOfLabel(view, "⇧")

        view.dispatchTouchEvent(motion(MotionEvent.ACTION_DOWN, x, y, 0L))
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.MILLISECONDS)
        view.dispatchTouchEvent(motion(MotionEvent.ACTION_UP, x, y, 70L))

        assertTrue(listener.symbolLongPressKeyCodes.isEmpty())
        assertEquals(1, listener.shiftCount)
    }

    @Test
    fun symPageCharacterLongPress_opensSymbolPicker() {
        val listener = RecordingListener()
        val view = measuredKeyboard().apply {
            symPageActive = true
            longPressTimeoutMs = 50L
            this.listener = listener
        }
        val (x, y) = centerOfLabel(view, "a")

        view.dispatchTouchEvent(motion(MotionEvent.ACTION_DOWN, x, y, 0L))
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.MILLISECONDS)
        view.dispatchTouchEvent(motion(MotionEvent.ACTION_UP, x, y, 70L))

        assertEquals(listOf(KeyEvent.KEYCODE_A), listener.symbolLongPressKeyCodes)
        assertEquals(emptyList<String>(), listener.texts)
    }

    @Test
    fun symPageTextLabels_commitExactVirtualSymbolText() {
        val listener = RecordingListener()
        val view = measuredKeyboard().apply {
            layoutName = "german_multitap_qwertz"
            layoutStyle = AospKeyboardView.SoftwareLayoutStyle.EXTENDED_ISO
            symPageActive = true
            symPageTextLabels = mapOf("ü" to "🙃")
            this.listener = listener
        }
        val (x, y) = centerOfLabel(view, "ü")

        view.dispatchTouchEvent(motion(MotionEvent.ACTION_DOWN, x, y, 0L))
        view.dispatchTouchEvent(motion(MotionEvent.ACTION_UP, x, y, 20L))

        assertEquals(listOf("🙃"), listener.symbolTexts)
        assertEquals(emptyList<String>(), listener.texts)
    }

    @Test
    fun activeSymPage_replacesCtrlSlotWithTappableZero() {
        val listener = RecordingListener()
        val view = measuredKeyboard().apply {
            symPageActive = true
            symPageLabels = mapOf(KeyEvent.KEYCODE_CTRL_LEFT to "0")
            this.listener = listener
        }
        val (x, y) = centerOfLabel(view, "CTRL")

        assertEquals("0", accessibilitySnapshot(view, "plektra_key_ctrl").label)
        view.dispatchTouchEvent(motion(MotionEvent.ACTION_DOWN, x, y, 0L))
        view.dispatchTouchEvent(motion(MotionEvent.ACTION_UP, x, y, 20L))

        assertEquals(listOf("0"), listener.symbolTexts)
        assertEquals(0, listener.ctrlCount)
        assertTrue(view.performAccessibilityClickForResourceName("plektra_key_ctrl"))
        assertEquals(listOf("0", "0"), listener.symbolTexts)
        assertEquals(0, listener.ctrlCount)
    }

    @Test
    fun heldSymPreview_replacesCtrlSlotWithTappableZero() {
        val listener = RecordingListener()
        val view = measuredKeyboard().apply {
            symPreviewLabels = mapOf(KeyEvent.KEYCODE_CTRL_LEFT to "0")
            this.listener = listener
        }
        val (symX, symY) = centerOfLabel(view, "SYM")
        val (ctrlX, ctrlY) = centerOfLabel(view, "CTRL")

        view.dispatchTouchEvent(motion(MotionEvent.ACTION_DOWN, symX, symY, 0L))
        assertEquals("0", previewLabel(view, keyForLabel(view, "CTRL")))
        view.dispatchTouchEvent(
            multiPointerMotion(
                actionMasked = MotionEvent.ACTION_POINTER_DOWN,
                actionIndex = 1,
                coordinates = listOf(symX to symY, ctrlX to ctrlY),
                offsetMs = 10L
            )
        )
        view.dispatchTouchEvent(
            multiPointerMotion(
                actionMasked = MotionEvent.ACTION_POINTER_UP,
                actionIndex = 1,
                coordinates = listOf(symX to symY, ctrlX to ctrlY),
                offsetMs = 20L
            )
        )
        view.dispatchTouchEvent(motion(MotionEvent.ACTION_UP, symX, symY, 30L))

        assertEquals(listOf("0"), listener.symbolTexts)
        assertEquals(0, listener.ctrlCount)
    }

    @Test
    fun ctrlPreviewActive_hidesSymPageTextLabels() {
        val view = measuredKeyboard().apply {
            layoutName = "german_multitap_qwertz"
            layoutStyle = AospKeyboardView.SoftwareLayoutStyle.EXTENDED_ISO
            symPageActive = true
            symPageLabels = mapOf(KeyEvent.KEYCODE_U to "❤️")
            symPageTextLabels = mapOf("ü" to "🙃")
            ctrlPreviewActive = true
            ctrlPreviewLabels = mapOf(KeyEvent.KEYCODE_U to "OK")
        }
        val uKey = keyForLabel(view, "u")
        val umlautKey = keyForLabel(view, "ü")

        assertEquals(null, symPageLabel(view, uKey))
        assertEquals("OK", previewLabel(view, uKey))
        assertEquals(null, symPageLabel(view, umlautKey))
        assertEquals(null, previewLabel(view, umlautKey))
    }

    @Test
    fun displayHint_usesCurrentLongPressProvider_evenWhenShiftedHintMatchesLabel() {
        val view = measuredKeyboard().apply {
            shifted = true
            longPressAlternatesProvider = { output -> if (output == "e") listOf("E") else emptyList() }
        }
        val key = keyForLabel(view, "e")

        assertEquals("E", displayHint(view, key))
    }

    @Test
    fun displayHint_usesAltProviderInsteadOfStaticNumberRow() {
        val view = measuredKeyboard().apply {
            longPressAlternatesProvider = { output -> if (output == "u") listOf("-") else emptyList() }
        }
        val key = keyForLabel(view, "u")

        assertEquals("-", displayHint(view, key))
    }

    @Test
    fun secondFingerShortPress_commitsHeldCharacterThenSecondCharacter() {
        val listener = RecordingListener()
        val view = measuredKeyboard().apply {
            longPressTimeoutMs = 50L
            longPressAlternatesProvider = { output -> if (output == "e") listOf("é") else emptyList() }
            this.listener = listener
        }
        val (kx, ky) = centerOfLabel(view, "k")
        val (ex, ey) = centerOfLabel(view, "e")

        view.dispatchTouchEvent(motion(MotionEvent.ACTION_DOWN, kx, ky, 0L))
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.MILLISECONDS)
        view.dispatchTouchEvent(
            multiPointerMotion(
                actionMasked = MotionEvent.ACTION_POINTER_DOWN,
                actionIndex = 1,
                coordinates = listOf(kx to ky, ex to ey),
                offsetMs = 70L
            )
        )
        view.dispatchTouchEvent(
            multiPointerMotion(
                actionMasked = MotionEvent.ACTION_MOVE,
                actionIndex = 0,
                coordinates = listOf(kx to ky, ex to ey),
                offsetMs = 80L
            )
        )
        view.dispatchTouchEvent(
            multiPointerMotion(
                actionMasked = MotionEvent.ACTION_POINTER_UP,
                actionIndex = 0,
                coordinates = listOf(kx to ky, ex to ey),
                offsetMs = 90L
            )
        )
        view.dispatchTouchEvent(motion(MotionEvent.ACTION_UP, ex, ey, 100L))

        assertEquals(listOf("k", "e"), listener.texts)
    }

    @Test
    fun secondFingerLongPress_commitsHeldCharacterThenSecondCharacterVariation() {
        val listener = RecordingListener()
        val view = measuredKeyboard().apply {
            longPressTimeoutMs = 50L
            longPressAlternatesProvider = { output -> if (output == "e") listOf("é") else emptyList() }
            this.listener = listener
        }
        val (kx, ky) = centerOfLabel(view, "k")
        val (ex, ey) = centerOfLabel(view, "e")

        view.dispatchTouchEvent(motion(MotionEvent.ACTION_DOWN, kx, ky, 0L))
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.MILLISECONDS)
        view.dispatchTouchEvent(
            multiPointerMotion(
                actionMasked = MotionEvent.ACTION_POINTER_DOWN,
                actionIndex = 1,
                coordinates = listOf(kx to ky, ex to ey),
                offsetMs = 70L
            )
        )
        view.dispatchTouchEvent(
            multiPointerMotion(
                actionMasked = MotionEvent.ACTION_MOVE,
                actionIndex = 0,
                coordinates = listOf(kx to ky, ex to ey),
                offsetMs = 80L
            )
        )
        view.dispatchTouchEvent(
            multiPointerMotion(
                actionMasked = MotionEvent.ACTION_POINTER_UP,
                actionIndex = 0,
                coordinates = listOf(kx to ky, ex to ey),
                offsetMs = 90L
            )
        )
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.MILLISECONDS)
        view.dispatchTouchEvent(motion(MotionEvent.ACTION_UP, ex, ey, 150L))

        assertEquals(listOf("k", "é"), listener.texts)
    }

    @Test
    fun fullSoftwareStylesUseCustomCyrillicPunctuationPositions() {
        val context = RuntimeEnvironment.getApplication()
        val name = "Cyrillic punctuation"
        val mapping = rocks.pkb.plektra.data.layout.LayoutMapping("х", "Х")
        assertTrue(rocks.pkb.plektra.data.layout.LayoutFileStore.saveLayout(context, name, mapOf(
            KeyEvent.KEYCODE_Q to rocks.pkb.plektra.data.layout.LayoutMapping("й", "Й"),
            KeyEvent.KEYCODE_P to rocks.pkb.plektra.data.layout.LayoutMapping("п", "П"),
            KeyEvent.KEYCODE_COMMA to mapping, KeyEvent.KEYCODE_PERIOD to mapping,
            KeyEvent.KEYCODE_LEFT_BRACKET to mapping, KeyEvent.KEYCODE_APOSTROPHE to mapping,
            KeyEvent.KEYCODE_SEMICOLON to mapping, KeyEvent.KEYCODE_SLASH to mapping)))
        for (style in AospKeyboardView.SoftwareLayoutStyle.entries) {
            val listener = RecordingListener()
            val view = measuredKeyboard().apply { layoutName = name; layoutStyle = style; this.listener = listener }
            if (style == AospKeyboardView.SoftwareLayoutStyle.COMPACT) {
                assertEquals("х", displayHint(view, keyForLabel(view, "p")))
            }
            for (base in (if (style in listOf(AospKeyboardView.SoftwareLayoutStyle.FULL_ANSI, AospKeyboardView.SoftwareLayoutStyle.FULL_ISO)) listOf("[", "'", ";", "/") else emptyList()) + listOf(",", ".")) {
                val key = keyForLabel(view, base)
                val method = AospKeyboardView::class.java.getDeclaredMethod("displayLabel", field<Any>(key, "spec").javaClass)
                method.isAccessible = true
                assertEquals("х", method.invoke(view, field<Any>(key, "spec")))
                val (x, y) = centerOfLabel(view, base)
                view.dispatchTouchEvent(motion(MotionEvent.ACTION_DOWN, x, y, 0L))
                view.dispatchTouchEvent(motion(MotionEvent.ACTION_UP, x, y, 20L))
                assertEquals("х", listener.texts.last())
            }
        }
    }

    @Test
    fun bulgarianSoftwareProfileExposesItsCompleteAlphabetAndPopupCommitsExtraLetter() {
        val context = RuntimeEnvironment.getApplication()
        val mappings = requireNotNull(rocks.pkb.plektra.data.layout.JsonLayoutLoader.loadLayout(context.assets, "bulgarian_phonetic", context))
        val available = mappings.values.flatMap { listOf(it.lowercase) + it.taps.map { tap -> tap.lowercase } }.joinToString("").toSet()
        assertTrue("Missing Bulgarian letters", available.containsAll("абвгдежзийклмнопрстуфхцчшщъьюя".toSet()))
        val listener = RecordingListener()
        val view = measuredKeyboard().apply { layoutName = "bulgarian_phonetic"; longPressTimeoutMs = 50L; this.listener = listener }
        val (x, y) = centerOfLabel(view, "z")
        view.dispatchTouchEvent(motion(MotionEvent.ACTION_DOWN, x, y, 0L))
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.MILLISECONDS)
        val panel = field<Any>(view, "moreKeysPanelState")
        val rect = field<RectF>(panel, "popupRectInView")
        view.dispatchTouchEvent(motion(MotionEvent.ACTION_MOVE, rect.centerX(), rect.centerY(), 65L))
        view.dispatchTouchEvent(motion(MotionEvent.ACTION_UP, rect.centerX(), rect.centerY(), 70L))
        assertEquals(listOf("ж"), listener.texts)
    }

    @Test
    fun everyBundledCyrillicMultitapLetterCanBeSelectedFromItsPopupWithAndWithoutShift() {
        val context = RuntimeEnvironment.getApplication()
        for (layout in listOf("russian_standard", "russian_jcuken", "russian_translit", "ukrainian",
            "serbian_cyrillic", "bulgarian_phonetic", "bulgarian_phonetic_traditional", "Cyrillic_Translite")) {
            val mappings = requireNotNull(rocks.pkb.plektra.data.layout.JsonLayoutLoader.loadLayout(context.assets, layout, context))
            for (shift in listOf(false, true)) {
                for ((code, mapping) in mappings.filterKeys { it in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z }) {
                    for (tap in mapping.taps.drop(1)) {
                        val expected = if (shift) tap.uppercase else tap.lowercase
                        val listener = RecordingListener()
                        val view = measuredKeyboard().apply { layoutName = layout; shifted = shift; longPressTimeoutMs = 50L; this.listener = listener }
                        val (x, y) = centerOfLabel(view, ('a' + code - KeyEvent.KEYCODE_A).toString())
                        view.dispatchTouchEvent(motion(MotionEvent.ACTION_DOWN, x, y, 0L))
                        Shadows.shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.MILLISECONDS)
                        val panel = field<Any>(view, "moreKeysPanelState")
                        val choices = field<List<Any>>(panel, "keys")
                        val index = choices.indexOfFirst { field<String>(it, "output") == expected }
                        assertTrue("$layout missing popup output $expected", index >= 0)
                        val rect = field<RectF>(panel, "popupRectInView")
                        val px = rect.left + field<Float>(panel, "padding") + (index + .5f) * field<Float>(panel, "keyWidth")
                        view.dispatchTouchEvent(motion(MotionEvent.ACTION_MOVE, px, rect.centerY(), 65L))
                        view.dispatchTouchEvent(motion(MotionEvent.ACTION_UP, px, rect.centerY(), 70L))
                        assertEquals("$layout/$expected", expected, listener.texts.last())
                    }
                }
            }
        }
    }

    @Test
    fun narrowKeyboardWrapsManyCyrillicAlternatesAndCanSelectLastRow() {
        val context = RuntimeEnvironment.getApplication()
        val letters = "абвгдежзийклмнопрстуфхцчшщъьюя"
        val mapping = rocks.pkb.plektra.data.layout.LayoutMapping("я", "Я", true,
            letters.map { rocks.pkb.plektra.data.layout.TapMapping(it.toString(), it.uppercase()) })
        assertTrue(rocks.pkb.plektra.data.layout.LayoutFileStore.saveLayout(context, "Many Cyrillic taps", mapOf(KeyEvent.KEYCODE_Q to mapping)))
        val listener = RecordingListener()
        val view = measuredKeyboard().apply {
            layoutName = "Many Cyrillic taps"; longPressTimeoutMs = 50L; this.listener = listener
            longPressLayerAlternatesProvider = { listOf("@", "#", "€").map { AospKeyboardView.LongPressLayerAlternative(it, it) } }
        }
        view.layout(0, 0, 240, 240)
        val (x, y) = centerOfLabel(view, "q")
        view.dispatchTouchEvent(motion(MotionEvent.ACTION_DOWN, x, y, 0L))
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.MILLISECONDS)
        val panel = field<Any>(view, "moreKeysPanelState")
        val rect = field<RectF>(panel, "popupRectInView")
        assertTrue(rect.width() <= view.width)
        assertTrue("Popup must remain inside its IME window", rect.top >= 0f)
        assertTrue(rect.bottom <= view.rootView.height)
        val layerRect = field<RectF>(panel, "layerPopupRectInView")
        assertTrue(layerRect.top >= 0f && layerRect.bottom <= view.rootView.height)
        assertFalse("Character cells must not be covered by modifier layer", RectF.intersects(rect, layerRect))
        val choices = field<List<Any>>(panel, "keys")
        val columns = field<Int>(panel, "columns")
        assertTrue(choices.lastIndex >= columns)
        for (index in listOf(0, choices.size / 2, choices.lastIndex, choices.lastIndex)) {
            if (listener.texts.isNotEmpty()) {
                view.dispatchTouchEvent(motion(MotionEvent.ACTION_DOWN, x, y, 0L))
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.MILLISECONDS)
            }
            val px = rect.left + field<Float>(panel, "padding") + (index % columns + .5f) * field<Float>(panel, "keyWidth")
            val py = if (listener.texts.size == 3) rect.bottom - field<Float>(panel, "padding") - 2f else rect.top + field<Float>(panel, "padding") + (index / columns + .5f) * field<Float>(panel, "keyHeight")
            view.dispatchTouchEvent(motion(MotionEvent.ACTION_MOVE, px, py, 65L))
            view.dispatchTouchEvent(motion(MotionEvent.ACTION_UP, px, py, 70L))
            assertEquals(field<String>(choices[index], "output"), listener.texts.last())
        }
        assertEquals("ю", listener.texts.last())
    }

    private fun measuredKeyboard(): AospKeyboardView {
        val context = RuntimeEnvironment.getApplication()
        val parent = FrameLayout(context)
        val view = AospKeyboardView(context)
        parent.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        parent.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY)
        )
        parent.layout(0, 0, 1000, 240)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, 1000, 240)
        return view
    }

    private fun accessibilityVirtualId(view: AospKeyboardView, resourceName: String): Int =
        requireNotNull(view.accessibilityVirtualIdForResourceName(resourceName))

    private fun accessibilitySnapshot(view: AospKeyboardView, resourceName: String) =
        requireNotNull(view.accessibilitySnapshotForResourceName(resourceName))

    private fun motion(action: Int, x: Float, y: Float, offsetMs: Long): MotionEvent =
        MotionEvent.obtain(0L, offsetMs, action, x, y, 0)

    private fun multiPointerMotion(
        actionMasked: Int,
        actionIndex: Int,
        coordinates: List<Pair<Float, Float>>,
        offsetMs: Long
    ): MotionEvent {
        val properties = coordinates.indices.map { index ->
            PointerProperties().apply {
                id = index
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }.toTypedArray()
        val coords = coordinates.map { (x, y) ->
            PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = 1f
                size = 1f
            }
        }.toTypedArray()
        val action = actionMasked or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        return MotionEvent.obtain(
            0L,
            offsetMs,
            action,
            coordinates.size,
            properties,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            0,
            0
        )
    }

    private fun labels(view: AospKeyboardView): List<String> = keys(view).map { key ->
        field<Any>(key, "spec").let { spec -> field<String>(spec, "label") }
    }

    private fun centerOfLabel(view: AospKeyboardView, label: String): Pair<Float, Float> {
        val hitRect = field<RectF>(keyForLabel(view, label), "hitRect")
        return hitRect.centerX() to hitRect.centerY()
    }

    private fun leftOfLabel(view: AospKeyboardView, label: String): Float =
        field<RectF>(keyForLabel(view, label), "hitRect").left

    private fun keyForLabel(view: AospKeyboardView, label: String): Any = keys(view).first { key ->
        val spec = field<Any>(key, "spec")
        field<String>(spec, "label") == label
    }

    private fun keys(view: AospKeyboardView): List<Any> = field(view, "keys")

    private fun displayHint(view: AospKeyboardView, key: Any): String {
        val method = AospKeyboardView::class.java.getDeclaredMethod("displayHint", key.javaClass)
        method.isAccessible = true
        return method.invoke(view, key) as String
    }

    private fun symPageLabel(view: AospKeyboardView, key: Any): String? {
        val method = AospKeyboardView::class.java.getDeclaredMethod("symPageLabelFor", key.javaClass)
        method.isAccessible = true
        return method.invoke(view, key) as String?
    }

    private fun previewLabel(view: AospKeyboardView, key: Any): String? {
        val method = AospKeyboardView::class.java.getDeclaredMethod("previewLabelFor", key.javaClass)
        method.isAccessible = true
        return method.invoke(view, key) as String?
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(target: Any, name: String): T {
        val declaredField = target.javaClass.getDeclaredField(name)
        declaredField.isAccessible = true
        return declaredField.get(target) as T
    }
}
