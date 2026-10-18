package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.os.Looper
import android.view.KeyEvent
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.InputConnection
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.data.layout.LayoutMappingRepository
import rocks.pkb.plektra.core.DeferredPunctuationSpaceTracker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AlternateCharacterManagerTest {

    private lateinit var context: Context
    private lateinit var prefs: android.content.SharedPreferences

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefs = context.getSharedPreferences("alt_sym_manager_tests", Context.MODE_PRIVATE)
        prefs.edit().clear().putLong("long_press_threshold", 50L).commit()
        SettingsManager.setLongPressModifier(context, "variations")
        SettingsManager.saveVariations(
            context,
            variations = mapOf(
                "u" to listOf("ü"),
                "U" to listOf("Ü")
            )
        )
    }

    @After
    fun tearDown() {
        SettingsManager.resetVariationsToDefault(context)
        SettingsManager.setLongPressModifier(context, "alt")
        SettingsManager.setLongPressCycleNext(context, "off")
        SettingsManager.setLongPressCycleEnd(context, "stop")
        SettingsManager.setSpaceAfterPunctuation(context, "")
        DeferredPunctuationSpaceTracker.clear()
    }

    private fun pressT(
        manager: AlternateCharacterManager,
        inputConnection: InputConnection,
        shiftOneShot: Boolean = false
    ) {
        manager.handleKeyWithAltMapping(
            keyCode = KeyEvent.KEYCODE_T,
            inputConnection = inputConnection,
            event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_T),
            capsLockEnabled = false,
            shiftOneShot = shiftOneShot,
            layoutChar = 't'
        )
    }

    private fun pressU(
        manager: AlternateCharacterManager,
        inputConnection: InputConnection,
        shiftOneShot: Boolean = false
    ) {
        manager.handleKeyWithAltMapping(
            keyCode = KeyEvent.KEYCODE_U,
            inputConnection = inputConnection,
            event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_U),
            capsLockEnabled = false,
            shiftOneShot = shiftOneShot,
            layoutChar = 'u'
        )
    }

    /** Pins SYM page 1 (emoji) for [keyCode]: [lower] unshifted, [upper] shifted or none. */
    @Suppress("UNCHECKED_CAST")
    private fun setSymPageOne(
        manager: AlternateCharacterManager,
        keyCode: Int = KeyEvent.KEYCODE_T,
        lower: String,
        upper: String?
    ) {
        fun map(name: String): MutableMap<Int, String> {
            val field = AlternateCharacterManager::class.java.getDeclaredField(name)
            field.isAccessible = true
            return field.get(manager) as MutableMap<Int, String>
        }
        map("symKeyMap")[keyCode] = lower
        if (upper == null) map("symKeyMapUppercase").remove(keyCode) else map("symKeyMapUppercase")[keyCode] = upper
    }

    private fun cycleManager(first: String, next: String, end: String): AlternateCharacterManager {
        SettingsManager.setLongPressModifier(context, first)
        SettingsManager.setLongPressCycleNext(context, next)
        SettingsManager.setLongPressCycleEnd(context, end)
        return AlternateCharacterManager(context.assets, prefs, context).apply {
            addAltKeyMapping(KeyEvent.KEYCODE_T, "(")
        }
    }

    private fun idle(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)
    }

    @Test
    fun longPressCycle_stepsThroughShiftThenAltAndStops() {
        val recorder = RecordingInputConnection()
        val inputConnection = recorder.asProxy()
        val manager = cycleManager(first = "shift", next = "alt", end = "stop")

        pressT(manager, inputConnection)
        assertEquals("t", recorder.text)
        idle(60)
        assertEquals("T", recorder.text)
        idle(50)
        assertEquals("(", recorder.text)
        idle(500)
        assertEquals("(", recorder.text)
    }

    @Test
    fun longPressCycle_altThenShiftReturnsToTypedCharacterAndStops() {
        val recorder = RecordingInputConnection()
        val inputConnection = recorder.asProxy()
        val manager = cycleManager(first = "alt", next = "shift", end = "original")
        val normalCommits = mutableListOf<String>()
        manager.onNormalCharCommitted = { normalCommits += it }

        pressT(manager, inputConnection)
        idle(60)
        assertEquals("(", recorder.text)
        idle(50)
        assertEquals("T", recorder.text)
        idle(50)
        assertEquals("t", recorder.text)
        idle(500)
        assertEquals("t", recorder.text)

        manager.handleKeyUp(KeyEvent.KEYCODE_T, symKeyActive = false)
        assertEquals(listOf("t"), normalCommits)
    }

    @Test
    fun longPressCycle_loopKeepsCyclingWhileHeld() {
        val recorder = RecordingInputConnection()
        val inputConnection = recorder.asProxy()
        val manager = cycleManager(first = "shift", next = "alt", end = "loop")

        pressT(manager, inputConnection)
        idle(60)
        assertEquals("T", recorder.text)
        idle(50)
        assertEquals("(", recorder.text)
        idle(50)
        assertEquals("t", recorder.text)
        idle(50)
        assertEquals("T", recorder.text)
    }

    @Test
    fun longPressCycle_releaseKeepsShownCharacter() {
        val recorder = RecordingInputConnection()
        val inputConnection = recorder.asProxy()
        val manager = cycleManager(first = "shift", next = "alt", end = "loop")
        val altInserted = mutableListOf<Char>()
        manager.onAltCharInserted = { altInserted += it }

        pressT(manager, inputConnection)
        idle(60)
        manager.handleKeyUp(KeyEvent.KEYCODE_T, symKeyActive = false)
        idle(500)

        assertEquals("T", recorder.text)
        assertEquals(listOf('T'), altInserted)
    }

    @Test
    fun longPressCycle_punctuationIsRecommittedThroughAltPathOnRelease() {
        val recorder = RecordingInputConnection()
        val inputConnection = recorder.asProxy()
        inputConnection.commitText("a ", 1)
        val manager = cycleManager(first = "shift", next = "alt", end = "stop")
        val altInserted = mutableListOf<Char>()
        manager.onAltCharInserted = { altInserted += it }

        pressT(manager, inputConnection)
        idle(110)
        assertEquals("a (", recorder.text)
        manager.handleKeyUp(KeyEvent.KEYCODE_T, symKeyActive = false)

        assertEquals("a (", recorder.text)
        assertEquals(listOf(1 to 0), recorder.deleteCalls)
        assertEquals(listOf('('), altInserted)
    }

    @Test
    fun longPressCycle_colonRecommitIsOneBatchEditAndKeepsDeferredSpace() {
        SettingsManager.setSpaceAfterPunctuation(context, ":")
        DeferredPunctuationSpaceTracker.clear()
        val recorder = RecordingInputConnection()
        val inputConnection = recorder.asProxy()
        inputConnection.commitText("a", 1)
        val manager = cycleManager(first = "shift", next = "alt", end = "stop").apply {
            addAltKeyMapping(KeyEvent.KEYCODE_H, ":")
        }
        // Same as the service: a long-press character counts as committed text.
        manager.onAltCharInserted = { DeferredPunctuationSpaceTracker.onTextCommitted(context, it.toString()) }

        manager.handleKeyWithAltMapping(
            keyCode = KeyEvent.KEYCODE_H,
            inputConnection = inputConnection,
            event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_H),
            capsLockEnabled = false,
            layoutChar = 'h'
        )
        idle(110)
        assertEquals("a:", recorder.text)
        manager.handleKeyUp(KeyEvent.KEYCODE_H, symKeyActive = false)

        assertEquals("a:", recorder.text)
        assertEquals(listOf(1 to 0), recorder.deleteCalls)
        assertEquals("delete and re-commit must share one batch edit", listOf(1), recorder.deleteBatchDepths)
        assertTrue(DeferredPunctuationSpaceTracker.isPending())
        // Anchored reads keep the whole-document request; only the final-choice check is bounded.
        assertTrue(0 in recorder.extractedHints)
        assertTrue(512 in recorder.extractedHints)

        DeferredPunctuationSpaceTracker.prepareForTextCommit(context, inputConnection, "b")
        inputConnection.commitText("b", 1)
        assertEquals("a: b", recorder.text)
    }

    @Test
    fun longPressCycle_autoCapitalizedOriginalLoopsThroughLowercase() {
        val recorder = RecordingInputConnection()
        val inputConnection = recorder.asProxy()
        val manager = cycleManager(first = "shift", next = "alt", end = "loop")

        pressT(manager, inputConnection, shiftOneShot = true)
        assertEquals("T", recorder.text)
        idle(60)
        assertEquals("t", recorder.text)
        idle(50)
        assertEquals("(", recorder.text)
        idle(50)
        assertEquals("T", recorder.text)
        idle(50)
        assertEquals("t", recorder.text)
    }

    @Test
    fun longPressCycle_autoCapitalizedOriginalShiftThenSymStopsOnSym() {
        val recorder = RecordingInputConnection()
        val inputConnection = recorder.asProxy()
        val manager = cycleManager(first = "shift", next = "alt", end = "stop")

        pressT(manager, inputConnection, shiftOneShot = true)
        assertEquals("T", recorder.text)
        // The first step is a real change (T -> t), not a pause on T.
        idle(60)
        assertEquals("t", recorder.text)
        idle(50)
        assertEquals("(", recorder.text)
        idle(500)
        assertEquals("(", recorder.text)
    }

    @Test
    fun longPressCycle_variationsThenShiftAddsCombinedStep() {
        val recorder = RecordingInputConnection()
        val inputConnection = recorder.asProxy()
        val manager = cycleManager(first = "variations", next = "shift", end = "stop")

        pressU(manager, inputConnection)
        idle(60)
        assertEquals("ü", recorder.text)
        idle(50)
        assertEquals("U", recorder.text)
        idle(50)
        assertEquals("Ü", recorder.text)
        idle(500)
        assertEquals("Ü", recorder.text)
    }

    @Test
    fun longPressCycle_combinedStepJoinsTheLoopAndOriginal() {
        val recorder = RecordingInputConnection()
        val inputConnection = recorder.asProxy()
        val manager = cycleManager(first = "variations", next = "shift", end = "loop")

        pressU(manager, inputConnection)
        idle(60)
        assertEquals("ü", recorder.text)
        idle(50)
        assertEquals("U", recorder.text)
        idle(50)
        assertEquals("Ü", recorder.text)
        idle(50)
        assertEquals("u", recorder.text)
        idle(50)
        assertEquals("ü", recorder.text)
    }

    @Test
    fun longPressCycle_autoCapitalizedOriginalCombinedStepGoesLowercase() {
        val recorder = RecordingInputConnection()
        val inputConnection = recorder.asProxy()
        val manager = cycleManager(first = "variations", next = "shift", end = "stop")

        pressU(manager, inputConnection, shiftOneShot = true)
        assertEquals("U", recorder.text)
        idle(60)
        assertEquals("Ü", recorder.text)
        idle(50)
        assertEquals("u", recorder.text)
        idle(50)
        assertEquals("ü", recorder.text)
        idle(500)
        assertEquals("ü", recorder.text)
    }

    @Test
    fun longPressCycle_shiftThenSymAddsUppercaseSymStepWhenMapped() {
        val recorder = RecordingInputConnection()
        val inputConnection = recorder.asProxy()
        val manager = cycleManager(first = "shift", next = "sym_emoji", end = "stop")
        setSymPageOne(manager, lower = "{", upper = "}")

        pressT(manager, inputConnection)
        idle(60)
        assertEquals("T", recorder.text)
        idle(50)
        assertEquals("{", recorder.text)
        idle(50)
        assertEquals("}", recorder.text)
        idle(500)
        assertEquals("}", recorder.text)
    }

    @Test
    fun longPressCycle_shiftThenSymWithoutUppercaseSymHasNoCombinedStep() {
        val recorder = RecordingInputConnection()
        val inputConnection = recorder.asProxy()
        val manager = cycleManager(first = "shift", next = "sym_emoji", end = "original")
        setSymPageOne(manager, lower = "{", upper = null)

        pressT(manager, inputConnection)
        idle(60)
        assertEquals("T", recorder.text)
        idle(50)
        assertEquals("{", recorder.text)
        // The combined step would repeat "{", so the cycle goes back to the typed letter.
        idle(50)
        assertEquals("t", recorder.text)
        idle(500)
        assertEquals("t", recorder.text)
    }

    @Test
    fun longPressCycle_autoCapitalizedOriginalShiftThenSymCombinesWithLowercase() {
        val recorder = RecordingInputConnection()
        val inputConnection = recorder.asProxy()
        val manager = cycleManager(first = "shift", next = "sym_emoji", end = "stop")
        setSymPageOne(manager, lower = "{", upper = "}")

        pressT(manager, inputConnection, shiftOneShot = true)
        assertEquals("T", recorder.text)
        idle(60)
        assertEquals("t", recorder.text)
        idle(50)
        assertEquals("}", recorder.text)
        idle(50)
        assertEquals("{", recorder.text)
    }

    @Test
    fun longPressCycle_shiftThenDeviceSymHasNoCombinedStep() {
        val recorder = RecordingInputConnection()
        val inputConnection = recorder.asProxy()
        val manager = cycleManager(first = "shift", next = "alt", end = "original")

        pressT(manager, inputConnection)
        idle(60)
        assertEquals("T", recorder.text)
        idle(50)
        assertEquals("(", recorder.text)
        idle(50)
        assertEquals("t", recorder.text)
    }

    @Test
    fun longPressCycle_pairWithoutShiftKeepsTwoSteps() {
        val recorder = RecordingInputConnection()
        val inputConnection = recorder.asProxy()
        val manager = cycleManager(first = "variations", next = "sym_emoji", end = "original")
        setSymPageOne(manager, keyCode = KeyEvent.KEYCODE_U, lower = "{", upper = "}")

        pressU(manager, inputConnection)
        idle(60)
        assertEquals("ü", recorder.text)
        idle(50)
        assertEquals("{", recorder.text)
        idle(50)
        assertEquals("u", recorder.text)
    }

    @Test
    fun longPressCycle_offKeepsSingleStepLongPress() {
        val recorder = RecordingInputConnection()
        val inputConnection = recorder.asProxy()
        val manager = cycleManager(first = "alt", next = "off", end = "loop")

        pressT(manager, inputConnection)
        idle(60)
        assertEquals("(", recorder.text)
        idle(500)
        assertEquals("(", recorder.text)
    }

    @Test
    fun variationsLongPress_replacesFirstCharacterUsingComposingRegion() {
        val recorder = RecordingInputConnection()
        val inputConnection = recorder.asProxy()
        val manager = AlternateCharacterManager(context.assets, prefs, context)

        val consumed = manager.handleKeyWithAltMapping(
            keyCode = KeyEvent.KEYCODE_U,
            inputConnection = inputConnection,
            event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_U),
            capsLockEnabled = false,
            shiftOneShot = false,
            layoutChar = 'u'
        )

        assertTrue(consumed)
        assertEquals("u", recorder.text)

        shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.MILLISECONDS)

        assertEquals("ü", recorder.text)
        assertEquals(listOf(0 to 1), recorder.composingRegions)
        assertEquals(emptyList<Pair<Int, Int>>(), recorder.deleteCalls)
    }

    @Test
    fun variationsLongPress_replacesOriginalCharacterAndPreservesLaterInput() {
        val recorder = RecordingInputConnection()
        val inputConnection = recorder.asProxy()
        val manager = AlternateCharacterManager(context.assets, prefs, context)

        manager.handleKeyWithAltMapping(
            keyCode = KeyEvent.KEYCODE_U,
            inputConnection = inputConnection,
            event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_U),
            capsLockEnabled = false,
            layoutChar = 'u'
        )
        inputConnection.commitText("3", 1)

        shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.MILLISECONDS)

        assertEquals("ü3", recorder.text)
        assertEquals(2, recorder.selectionStart)
        assertEquals(2, recorder.selectionEnd)
        assertEquals(listOf(0 to 1), recorder.composingRegions)
        assertEquals(emptyList<Pair<Int, Int>>(), recorder.deleteCalls)
    }

    @Test
    fun variationsLongPress_preservesLaterInputForShiftedCharacter() {
        val recorder = RecordingInputConnection()
        val inputConnection = recorder.asProxy()
        val manager = AlternateCharacterManager(context.assets, prefs, context)

        manager.handleKeyWithAltMapping(
            keyCode = KeyEvent.KEYCODE_U,
            inputConnection = inputConnection,
            event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_U),
            capsLockEnabled = false,
            shiftOneShot = true,
            layoutChar = 'u'
        )
        inputConnection.commitText("3", 1)

        shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.MILLISECONDS)

        assertEquals("Ü3", recorder.text)
        assertEquals(2, recorder.selectionStart)
        assertEquals(2, recorder.selectionEnd)
    }

    @Test
    fun variationsLongPress_doesNotTouchLaterInputWhenOriginalCharacterChanged() {
        val recorder = RecordingInputConnection()
        val inputConnection = recorder.asProxy()
        val manager = AlternateCharacterManager(context.assets, prefs, context)

        manager.handleKeyWithAltMapping(
            keyCode = KeyEvent.KEYCODE_U,
            inputConnection = inputConnection,
            event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_U),
            capsLockEnabled = false,
            layoutChar = 'u'
        )
        inputConnection.commitText("3", 1)
        recorder.replaceTextDirectly(0, 1, "x")

        shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.MILLISECONDS)

        assertEquals("x3", recorder.text)
        assertEquals(emptyList<Pair<Int, Int>>(), recorder.composingRegions)
        assertEquals(emptyList<Pair<Int, Int>>(), recorder.deleteCalls)
    }

    @Test
    fun variationsLongPress_withoutAnchorDoesNotReplaceLaterInput() {
        val recorder = RecordingInputConnection(extractedTextAvailable = false)
        val inputConnection = recorder.asProxy()
        val manager = AlternateCharacterManager(context.assets, prefs, context)

        manager.handleKeyWithAltMapping(
            keyCode = KeyEvent.KEYCODE_U,
            inputConnection = inputConnection,
            event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_U),
            capsLockEnabled = false,
            layoutChar = 'u'
        )
        inputConnection.commitText("3", 1)
        recorder.extractedTextAvailable = true

        shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.MILLISECONDS)

        assertEquals("u3", recorder.text)
        assertEquals(emptyList<Pair<Int, Int>>(), recorder.composingRegions)
        assertEquals(emptyList<Pair<Int, Int>>(), recorder.deleteCalls)
    }

    @Test
    fun altMappedPunctuationIsOfferedToBoundaryHandlerBeforeDirectCommit() {
        val recorder = RecordingInputConnection()
        val inputConnection = recorder.asProxy()
        inputConnection.commitText("teh", 1)
        val requested = mutableListOf<String>()
        val manager = AlternateCharacterManager(context.assets, prefs, context).apply {
            onBoundaryTextRequested = { text, connection ->
                requested += text
                connection.commitText("?", 1)
                true
            }
        }

        val handled = manager.handleAltCombination(
            keyCode = KeyEvent.KEYCODE_X,
            inputConnection = inputConnection,
            event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_X),
            mappingsOverride = mapOf(KeyEvent.KEYCODE_X to "?"),
            defaultHandler = { _, _ -> false }
        )

        assertTrue(handled)
        assertEquals(listOf("?"), requested)
        assertEquals("teh?", recorder.text)
    }

    @Test
    fun altMappedApostropheRemainsAnInWordCharacter() {
        val recorder = RecordingInputConnection()
        val inputConnection = recorder.asProxy()
        inputConnection.commitText("l", 1)
        val requested = mutableListOf<String>()
        val manager = AlternateCharacterManager(context.assets, prefs, context).apply {
            onBoundaryTextRequested = { text, _ ->
                requested += text
                true
            }
        }

        val handled = manager.handleAltCombination(
            keyCode = KeyEvent.KEYCODE_S,
            inputConnection = inputConnection,
            event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_S),
            mappingsOverride = mapOf(KeyEvent.KEYCODE_S to "'"),
            defaultHandler = { _, _ -> false }
        )

        assertTrue(handled)
        assertTrue(requested.isEmpty())
        assertEquals("l'", recorder.text)
    }

    @Test
    fun shiftLongPress_autoCapitalizedLetterTogglesToLowercase() {
        val recorder = RecordingInputConnection()
        val inputConnection = recorder.asProxy()
        val manager = cycleManager(first = "shift", next = "off", end = "stop")

        // Auto-capitalization arrives as a one-shot Shift on the typed letter.
        pressT(manager, inputConnection, shiftOneShot = true)
        assertEquals("T", recorder.text)
        idle(60)
        assertEquals("t", recorder.text)
        idle(500)
        assertEquals("t", recorder.text)
    }

    @Test
    fun shiftLongPress_lowercaseLetterStillGivesUppercase() {
        val recorder = RecordingInputConnection()
        val inputConnection = recorder.asProxy()
        val manager = cycleManager(first = "shift", next = "off", end = "stop")

        pressT(manager, inputConnection)
        idle(60)
        assertEquals("T", recorder.text)
    }


    @Test
    fun shiftLongPress_togglesGermanMultiTapCharactersWithoutChangingTheLetter() {
        assertMultiTapCasePairs("german_multitap_qwertz", listOf(
            Triple(KeyEvent.KEYCODE_A, "ä", "Ä"),
            Triple(KeyEvent.KEYCODE_O, "ö", "Ö"),
            Triple(KeyEvent.KEYCODE_U, "ü", "Ü"),
            Triple(KeyEvent.KEYCODE_S, "ß", "ẞ")
        ))
    }

    @Test
    fun shiftLongPress_preservesTurkishMultiTapCasePairs() {
        assertMultiTapCasePairs("turkish_multitap", listOf(
            Triple(KeyEvent.KEYCODE_I, "i", "İ"),
            Triple(KeyEvent.KEYCODE_I, "ı", "I"),
            Triple(KeyEvent.KEYCODE_I, "î", "Î"),
            Triple(KeyEvent.KEYCODE_S, "ş", "Ş")
        ))
    }

    private fun assertMultiTapCasePairs(layoutName: String, pairs: List<Triple<Int, String, String>>) {
        val previousLayout = LayoutMappingRepository.getLayout()
        try {
            LayoutMappingRepository.loadLayout(context.assets, layoutName, context)
            SettingsManager.setLongPressModifier(context, "shift")
            for ((keyCode, lower, upper) in pairs) {
                for ((typed, expected) in listOf(lower to upper, upper to lower)) {
                    val recorder = RecordingInputConnection()
                    val inputConnection = recorder.asProxy()
                    val manager = AlternateCharacterManager(context.assets, prefs, context)
                    inputConnection.commitText(typed, 1)
                    manager.scheduleLongPressOnly(keyCode, inputConnection, typed)
                    shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.MILLISECONDS)
                    assertEquals("$layoutName: $typed", expected, recorder.text)
                    manager.handleKeyUp(keyCode, symKeyActive = false)
                }
            }
        } finally {
            LayoutMappingRepository.setLayout(previousLayout)
        }
    }

    private class RecordingInputConnection(
        var extractedTextAvailable: Boolean = true
    ) {
        val extractedHints = mutableListOf<Int>()
        var text: String = ""
        var selectionStart: Int = 0
            private set
        var selectionEnd: Int = 0
            private set
        private var composingStart: Int = -1
        private var composingEnd: Int = -1
        val composingRegions = mutableListOf<Pair<Int, Int>>()
        val deleteCalls = mutableListOf<Pair<Int, Int>>()
        val deleteBatchDepths = mutableListOf<Int>()
        private var batchDepth = 0

        fun replaceTextDirectly(start: Int, end: Int, replacement: String) {
            text = text.replaceRange(start, end, replacement)
            val lengthDelta = replacement.length - (end - start)
            selectionStart += lengthDelta
            selectionEnd += lengthDelta
        }

        fun asProxy(): InputConnection {
            return Proxy.newProxyInstance(
                InputConnection::class.java.classLoader,
                arrayOf(InputConnection::class.java)
            ) { _, method, args ->
                when (method.name) {
                    "commitText" -> {
                        val committed = args?.getOrNull(0)?.toString().orEmpty()
                        if (composingStart >= 0 && composingEnd >= composingStart) {
                            text = text.replaceRange(composingStart, composingEnd, committed)
                            selectionStart = composingStart + committed.length
                            selectionEnd = selectionStart
                            composingStart = -1
                            composingEnd = -1
                        } else {
                            text = text.substring(0, selectionStart) +
                                committed +
                                text.substring(selectionEnd)
                            selectionStart += committed.length
                            selectionEnd = selectionStart
                        }
                        true
                    }
                    "setComposingRegion" -> {
                        composingStart = args?.getOrNull(0) as Int
                        composingEnd = args.getOrNull(1) as Int
                        composingRegions += composingStart to composingEnd
                        true
                    }
                    "deleteSurroundingText" -> {
                        val before = args?.getOrNull(0) as Int
                        val after = args.getOrNull(1) as Int
                        deleteCalls += before to after
                        deleteBatchDepths += batchDepth
                        val start = (selectionStart - before).coerceAtLeast(0)
                        val end = (selectionEnd + after).coerceAtMost(text.length)
                        val selected = text.substring(selectionStart, selectionEnd)
                        text = text.substring(0, start) + selected + text.substring(end)
                        selectionStart = start
                        selectionEnd = start + selected.length
                        true
                    }
                    "finishComposingText" -> {
                        composingStart = -1
                        composingEnd = -1
                        true
                    }
                    "setSelection" -> {
                        selectionStart = args?.getOrNull(0) as Int
                        selectionEnd = args.getOrNull(1) as Int
                        true
                    }
                    "beginBatchEdit" -> {
                        batchDepth++
                        true
                    }
                    "endBatchEdit" -> {
                        if (batchDepth > 0) batchDepth--
                        batchDepth > 0
                    }
                    "getExtractedText" -> if (extractedTextAvailable) {
                        (args?.getOrNull(0) as? android.view.inputmethod.ExtractedTextRequest)
                            ?.let { extractedHints += it.hintMaxChars }
                        ExtractedText().apply {
                            this.text = this@RecordingInputConnection.text
                            selectionStart = this@RecordingInputConnection.selectionStart
                            selectionEnd = this@RecordingInputConnection.selectionEnd
                        }
                    } else {
                        null
                    }
                    else -> defaultValue(method.returnType)
                }
            } as InputConnection
        }

        private fun defaultValue(type: Class<*>): Any? {
            return when {
                type == Boolean::class.javaPrimitiveType -> false
                type == Int::class.javaPrimitiveType -> 0
                type == Long::class.javaPrimitiveType -> 0L
                type == Float::class.javaPrimitiveType -> 0f
                type == Double::class.javaPrimitiveType -> 0.0
                type == Short::class.javaPrimitiveType -> 0.toShort()
                type == Byte::class.javaPrimitiveType -> 0.toByte()
                type == Char::class.javaPrimitiveType -> 0.toChar()
                else -> null
            }
        }
    }
}
