package rocks.pkb.plektra.inputmethod

import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.InputConnection
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import rocks.pkb.plektra.AltModifierBinding
import rocks.pkb.plektra.KeyboardBarConfiguration
import rocks.pkb.plektra.KeyboardBarProfile
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.core.InputContextState
import rocks.pkb.plektra.core.ModifierStateController

/**
 * Replays Q25 hardware key events through the real service and checks the Android status-bar
 * modifier icon after every event. Expectations name the icon the service shows: "-" for none,
 * otherwise S/C/A joined with "+", with "!" for a locked modifier; "*" leaves a step unchecked.
 * Every step also checks that the icon only names modifiers whose keys the case has pressed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Q25ModifierIndicatorMatrixTest {

    private class Step(val label: String, val run: () -> Unit, val expected: String)

    private class Case(
        val name: String,
        val prefix: String,
        val expectedText: String?,
        val steps: List<Step>,
        val expectedTextEnd: String? = null
    )

    private val committed = StringBuilder()
    private lateinit var service: PhysicalKeyboardInputMethodService
    private var time = 10_000L

    @After
    fun tearDown() {
        DeviceSpecific.clearTestOverrides()
    }

    @Test
    fun q25ModifierIndicatorsReflectOnlyPressedModifiers() {
        val cases = buildCases()
        val failures = mutableListOf<String>()
        var checkedSteps = 0
        FIELDS.forEach { (fieldName, inputType) ->
            startService(inputType)
            cases.forEach { case ->
                resetBetweenCases()
                case.prefix.forEach { tapLetter(it) }
                committed.clear()
                val pressed = mutableSetOf<Char>()
                case.steps.forEach { step ->
                    step.run()
                    pressed += pressedModifiers(step.label)
                    val shown = statusIcon()
                    checkedSteps++
                    if (step.expected != "*" && shown != step.expected) {
                        failures += "$fieldName/${case.name}: after ${step.label} expected ${step.expected} but icon was $shown"
                    }
                    val unpressed = shown.filter { it in "SCA" && it !in pressed }
                    if (unpressed.isNotEmpty()) {
                        failures += "$fieldName/${case.name}: after ${step.label} icon $shown names unpressed $unpressed"
                    }
                }
                if (case.expectedText != null && committed.toString() != case.expectedText) {
                    failures += "$fieldName/${case.name}: committed '$committed' expected '${case.expectedText}'"
                }
                if (case.expectedTextEnd != null && !committed.endsWith(case.expectedTextEnd)) {
                    failures += "$fieldName/${case.name}: committed '$committed' expected to end with '${case.expectedTextEnd}'"
                }
            }
        }
        println("Q25 modifier indicator matrix: ${cases.size * FIELDS.size} cases, $checkedSteps steps, ${failures.size} failures")
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun autoCapitalizationArmedDuringShiftChordSurvivesShiftRelease() {
        startService(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        SettingsManager.setAutoCapitalizeAfterPeriod(RuntimeEnvironment.getApplication(), true)
        resetBetweenCases()
        "ab".forEach { tapLetter(it) }
        keyDown(KeyEvent.KEYCODE_ALT_LEFT, ALT)
        keyDown(KeyEvent.KEYCODE_M, ALT)
        keyUp(KeyEvent.KEYCODE_M, ALT)
        keyUp(KeyEvent.KEYCODE_ALT_LEFT, 0)
        keyDown(KeyEvent.KEYCODE_SHIFT_LEFT, SHIFT)
        keyDown(KeyEvent.KEYCODE_SPACE, SHIFT)
        keyUp(KeyEvent.KEYCODE_SPACE, SHIFT)
        keyUp(KeyEvent.KEYCODE_SHIFT_LEFT, 0)
        val afterRelease = statusIcon()
        tapLetter('h')

        assertTrue("icon after Shift release was $afterRelease", afterRelease == "S")
        assertTrue("committed '$committed'", committed.endsWith(". H"))
    }

    private fun buildCases(): List<Case> {
        val cases = mutableListOf<Case>()
        val followUps = listOf(
            Triple("letter", KeyEvent.KEYCODE_H, "h"),
            Triple("space", KeyEvent.KEYCODE_SPACE, " "),
            Triple("backspace", KeyEvent.KEYCODE_DEL, null),
            Triple("symbol", KeyEvent.KEYCODE_H, ":")
        )
        Q25_ALT_SYMBOLS.forEach { (key, symbol) ->
            followUps.forEach { (followName, followKey, followText) ->
                val altHeldFollow = if (followName == "symbol") {
                    listOf(tap(followKey, ALT, "alt+H", "A"), up(KeyEvent.KEYCODE_ALT_LEFT, 0, "alt up", "-"))
                } else {
                    listOf(up(KeyEvent.KEYCODE_ALT_LEFT, 0, "alt up", "-"), tap(followKey, 0, followName, "-"))
                }
                cases += Case(
                    "altHeld ${KeyEvent.keyCodeToString(key)} then $followName", "ab",
                    if (followText == null) "" else symbol + followText,
                    listOf(down(KeyEvent.KEYCODE_ALT_LEFT, ALT, "alt down", "A"), tap(key, ALT, "alt+$symbol", "A")) + altHeldFollow
                )
                val oneShotFollow = if (followName == "symbol") {
                    listOf(tap(KeyEvent.KEYCODE_ALT_LEFT, ALT, "alt tap", "A"), tap(followKey, 0, ":", "-"))
                } else {
                    listOf(tap(followKey, 0, followName, "-"))
                }
                cases += Case(
                    "altOneShot ${KeyEvent.keyCodeToString(key)} then $followName", "ab",
                    (if (followText == null) "" else symbol + followText) + "j",
                    listOf(tap(KeyEvent.KEYCODE_ALT_LEFT, ALT, "alt tap", "A"), tap(key, 0, symbol, "-")) + oneShotFollow +
                        tap(KeyEvent.KEYCODE_J, 0, "j", "-")
                )
            }
        }
        cases += Case(
            "altLocked colons then unlock", "ab", "::h",
            listOf(
                tap(KeyEvent.KEYCODE_ALT_LEFT, ALT, "alt tap", "A"),
                tap(KeyEvent.KEYCODE_ALT_LEFT, ALT, "alt tap", "A!"),
                tap(KeyEvent.KEYCODE_H, 0, ":", "A!"),
                tap(KeyEvent.KEYCODE_H, 0, ":", "A!"),
                tap(KeyEvent.KEYCODE_ALT_LEFT, ALT, "alt tap", "-"),
                tap(KeyEvent.KEYCODE_H, 0, "h", "-")
            )
        )
        cases += Case(
            "sym tap then H then letter", "ab", ":h",
            listOf(
                tap(KeyEvent.KEYCODE_ALT_RIGHT, SYM, "sym tap", "*"),
                tap(KeyEvent.KEYCODE_H, 0, "sym H", "*"),
                tap(KeyEvent.KEYCODE_H, 0, "h", "-")
            )
        )
        cases += Case(
            "shift tap then letter", "ab", "Hh",
            listOf(tap(KeyEvent.KEYCODE_SHIFT_LEFT, SHIFT, "shift tap", "S"), tap(KeyEvent.KEYCODE_H, 0, "H", "-"), tap(KeyEvent.KEYCODE_H, 0, "h", "-"))
        )
        cases += Case(
            "shift held letter", "ab", "Hh",
            listOf(
                down(KeyEvent.KEYCODE_SHIFT_LEFT, SHIFT, "shift down", "S"),
                tap(KeyEvent.KEYCODE_H, SHIFT, "shift+H", "S"),
                up(KeyEvent.KEYCODE_SHIFT_LEFT, 0, "shift up", "-"),
                tap(KeyEvent.KEYCODE_H, 0, "h", "-")
            )
        )
        listOf(
            "period" to KeyEvent.KEYCODE_PERIOD,
            "comma" to KeyEvent.KEYCODE_COMMA,
            "space" to KeyEvent.KEYCODE_SPACE,
            "backspace" to KeyEvent.KEYCODE_DEL,
            "zero" to KeyEvent.KEYCODE_0
        ).forEach { (name, key) ->
            cases += Case(
                "shift held $name then letter", "ab", null,
                listOf(
                    down(KeyEvent.KEYCODE_SHIFT_LEFT, SHIFT, "shift down", "S"),
                    tap(key, SHIFT, "shift+$name", "S"),
                    up(KeyEvent.KEYCODE_SHIFT_LEFT, 0, "shift up", "-"),
                    tap(KeyEvent.KEYCODE_H, 0, "h", "-")
                ),
                expectedTextEnd = "h"
            )
        }
        cases += Case(
            "shift and alt held symbol then letter", "ab", ":h",
            listOf(
                down(KeyEvent.KEYCODE_SHIFT_LEFT, SHIFT, "shift down", "S"),
                down(KeyEvent.KEYCODE_ALT_LEFT, SHIFT or ALT, "alt down", "S+A"),
                tap(KeyEvent.KEYCODE_H, SHIFT or ALT, "shift+alt+H", "S+A"),
                up(KeyEvent.KEYCODE_ALT_LEFT, SHIFT, "alt up", "S"),
                up(KeyEvent.KEYCODE_SHIFT_LEFT, 0, "shift up", "-"),
                tap(KeyEvent.KEYCODE_H, 0, "h", "-")
            )
        )
        cases += Case(
            "shift tap then alt tap symbol", "ab", null,
            listOf(
                tap(KeyEvent.KEYCODE_SHIFT_LEFT, SHIFT, "shift tap", "S"),
                tap(KeyEvent.KEYCODE_ALT_LEFT, ALT, "alt tap", "S+A"),
                tap(KeyEvent.KEYCODE_H, 0, ":", "*")
            )
        )
        listOf("A" to KeyEvent.KEYCODE_A, "C" to KeyEvent.KEYCODE_C, "V" to KeyEvent.KEYCODE_V).forEach { (name, key) ->
            cases += Case(
                "ctrl held $name then letter", "ab", null,
                listOf(
                    down(KeyEvent.KEYCODE_SHIFT_RIGHT, Q25_CTRL, "ctrl down", "C"),
                    tap(key, Q25_CTRL, "ctrl+$name", "C"),
                    up(KeyEvent.KEYCODE_SHIFT_RIGHT, 0, "ctrl up", "-"),
                    tap(KeyEvent.KEYCODE_H, 0, "h", "-")
                )
            )
            cases += Case(
                "ctrl tap $name then letter", "ab", null,
                listOf(
                    tap(KeyEvent.KEYCODE_SHIFT_RIGHT, Q25_CTRL, "ctrl tap", "C"),
                    tap(key, 0, "ctrl+$name", "-"),
                    tap(KeyEvent.KEYCODE_H, 0, "h", "-")
                )
            )
        }
        return cases
    }

    private fun pressedModifiers(label: String): Set<Char> = buildSet {
        if (label.startsWith("shift")) add('S')
        if (label.startsWith("alt") || label.contains("+alt")) add('A')
        if (label.startsWith("ctrl")) add('C')
    }

    private fun down(code: Int, meta: Int, label: String, expected: String) = Step(label, { keyDown(code, meta) }, expected)
    private fun up(code: Int, meta: Int, label: String, expected: String) = Step(label, { keyUp(code, meta) }, expected)
    private fun tap(code: Int, meta: Int, label: String, expected: String) = Step(label, { keyDown(code, meta); keyUp(code, meta) }, expected)

    private fun tapLetter(ch: Char) {
        val code = KeyEvent.KEYCODE_A + (ch - 'a')
        keyDown(code, 0)
        keyUp(code, 0)
    }

    private fun keyDown(code: Int, meta: Int) {
        val event = KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0, meta)
        if (!service.onKeyDown(code, event)) appendUnhandledKey(event)
        time += 30
    }

    private fun keyUp(code: Int, meta: Int) {
        service.onKeyUp(code, KeyEvent(time, time, KeyEvent.ACTION_UP, code, 0, meta))
        time += 300
    }

    // Keys the service declines reach the app, which inserts their plain character.
    private fun appendUnhandledKey(event: KeyEvent) {
        when (event.keyCode) {
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.KEYCODE_CTRL_LEFT,
            KeyEvent.KEYCODE_CTRL_RIGHT, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> Unit
            KeyEvent.KEYCODE_DEL -> if (committed.isNotEmpty()) committed.setLength(committed.length - 1)
            else -> event.unicodeChar.takeIf { it != 0 }?.let { committed.append(it.toChar()) }
        }
    }

    private fun statusIcon(): String {
        shadowOf(Looper.getMainLooper()).idle()
        val resId = field("lastSystemStatusIconResId") as Int? ?: return "-"
        val name = service.resources.getResourceEntryName(resId)
        val match = Regex("ic_status_modifiers_s(\\d)_c(\\d)_a(\\d)").matchEntire(name) ?: return name
        return listOf("S", "C", "A").mapIndexedNotNull { index, letter ->
            when (match.groupValues[index + 1]) {
                "1" -> letter
                "2" -> "$letter!"
                else -> null
            }
        }.joinToString("+").ifEmpty { "-" }
    }

    private fun startService(inputType: Int) {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.getPreferences(context).edit().clear().commit()
        DeviceSpecific.clearTestOverrides()
        DeviceSpecific.setBuildFingerprintForTests("zinwa", "zinwa", "Q25", "Q25", "q25")
        SettingsManager.setPhysicalKeyboardProfileOverride(context, "Q25")
        SettingsManager.setAltModifierBinding(context, AltModifierBinding.DeviceSym)
        SettingsManager.setAutoCapitalizeFirstLetter(context, false)
        SettingsManager.setAutoCapitalizeAfterPeriod(context, false)
        // Alt+0 is otherwise the speech shortcut, which clears Alt by design.
        SettingsManager.setAltCtrlSpeechShortcutEnabled(context, false)
        SettingsManager.setModifierIndicators(context, setOf(SettingsManager.MODIFIER_INDICATOR_MENU_BAR))
        KeyboardBarConfiguration.setSlimProfile(context, KeyboardBarProfile(false, false, false, true, false))
        val inputConnection = Proxy.newProxyInstance(
            InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java)
        ) { _, method, args ->
            when (method.name) {
                "commitText" -> { committed.append(args!![0].toString()); true }
                "getTextBeforeCursor" -> committed.takeLast(args!![0] as Int).toString()
                "getTextAfterCursor" -> ""
                "deleteSurroundingText" -> {
                    val count = args!![0] as Int
                    committed.delete(maxOf(0, committed.length - count), committed.length)
                    true
                }
                "getExtractedText" -> ExtractedText().apply {
                    text = committed.toString()
                    selectionStart = committed.length
                    selectionEnd = committed.length
                }
                else -> when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> true
                    Int::class.javaPrimitiveType -> 0
                    else -> null
                }
            }
        } as InputConnection
        val info = EditorInfo().apply {
            this.inputType = inputType
            packageName = "com.launcher.test"
        }
        service = Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java).create().get()
        setField("mInputConnection", inputConnection)
        setField("mStartedInputConnection", inputConnection)
        setField("mInputEditorInfo", info)
        setField("physicalKeyboardProfileOverride", "Q25")
        service.onStartInput(info, false)
        service.onStartInputView(info, false)
        setField("isInputViewActive", true)
        setField("inputContextState", InputContextState.fromEditorInfo(info))
    }

    private fun resetBetweenCases() {
        val modifiers = field("modifierStateController") as ModifierStateController
        modifiers.clearShiftState(resetPressedState = true)
        modifiers.clearCtrlState(resetPressedState = true)
        modifiers.clearAltState(resetPressedState = true)
        setField("shiftLayerLatched", false)
        setField("altModifierLayerLatched", false)
        time += 5_000
        tapLetter('a')
        committed.clear()
    }

    private fun setField(name: String, value: Any?) {
        findField(name).set(service, value)
    }

    private fun field(name: String): Any? = findField(name).get(service)

    private fun findField(name: String): java.lang.reflect.Field {
        var type: Class<*>? = service.javaClass
        while (type != null) {
            try {
                return type.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                type = type.superclass
            }
        }
        error("No field $name")
    }

    private companion object {
        val ALT = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        val SYM = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON
        val SHIFT = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        val Q25_CTRL = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_RIGHT_ON

        val FIELDS = listOf(
            "text" to InputType.TYPE_CLASS_TEXT,
            "launcherSearch" to (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_FILTER or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        )

        val Q25_ALT_SYMBOLS = listOf(
            KeyEvent.KEYCODE_Q to "#", KeyEvent.KEYCODE_W to "1", KeyEvent.KEYCODE_E to "2", KeyEvent.KEYCODE_R to "3",
            KeyEvent.KEYCODE_T to "(", KeyEvent.KEYCODE_Y to ")", KeyEvent.KEYCODE_U to "_", KeyEvent.KEYCODE_I to "-",
            KeyEvent.KEYCODE_O to "+", KeyEvent.KEYCODE_P to "@", KeyEvent.KEYCODE_A to "*", KeyEvent.KEYCODE_S to "4",
            KeyEvent.KEYCODE_D to "5", KeyEvent.KEYCODE_F to "6", KeyEvent.KEYCODE_G to "/", KeyEvent.KEYCODE_H to ":",
            KeyEvent.KEYCODE_J to ";", KeyEvent.KEYCODE_K to "'", KeyEvent.KEYCODE_L to "\"", KeyEvent.KEYCODE_Z to "7",
            KeyEvent.KEYCODE_X to "8", KeyEvent.KEYCODE_C to "9", KeyEvent.KEYCODE_0 to "0", KeyEvent.KEYCODE_GRAVE to "€",
            KeyEvent.KEYCODE_V to "?", KeyEvent.KEYCODE_B to "!", KeyEvent.KEYCODE_N to ",", KeyEvent.KEYCODE_M to "."
        )
    }
}
