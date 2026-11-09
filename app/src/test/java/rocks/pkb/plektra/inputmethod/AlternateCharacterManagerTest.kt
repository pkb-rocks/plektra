package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.os.Looper
import android.view.KeyEvent
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.InputConnection
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.data.layout.LayoutMappingRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        LayoutMappingRepository.loadLayout(context.assets, "qwerty", context)
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
        LayoutMappingRepository.loadLayout(context.assets, "qwerty", context)
        SettingsManager.resetVariationsToDefault(context)
        SettingsManager.setLongPressModifier(context, "alt")
    }

    @Test
    fun uyghurAltLongPress_usesLayoutPunctuation() {
        SettingsManager.setLongPressModifier(context, "alt")
        LayoutMappingRepository.loadLayout(context.assets, "uyghur", context)
        val manager = AlternateCharacterManager(context.assets, prefs, context)
        val recorder = RecordingInputConnection()
        val inputConnection = recorder.asProxy()

        listOf(KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_V, KeyEvent.KEYCODE_J).forEach { key ->
            manager.handleKeyWithAltMapping(key, KeyEvent(KeyEvent.ACTION_DOWN, key), false, inputConnection)
            shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.MILLISECONDS)
            manager.handleKeyUp(key, symKeyActive = false)
        }

        assertEquals("،؟؛", recorder.text)
    }

    @Test
    fun shiftLongPress_replacesWholeMultiCharacterOutput() {
        SettingsManager.setLongPressModifier(context, "shift")
        LayoutMappingRepository.loadLayout(context.assets, "uyghur", context)
        val (recorder, manager) = pressShiftedUyghurL(prefix = "ب")
        assertEquals("بلا", recorder.text)
        shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.MILLISECONDS)
        assertEquals("بل", recorder.text)
        manager.handleKeyUp(KeyEvent.KEYCODE_L, symKeyActive = false)
    }

    @Test
    fun altLongPress_replacesWholeMultiCharacterOutput() {
        SettingsManager.setLongPressModifier(context, "alt")
        LayoutMappingRepository.loadLayout(context.assets, "uyghur", context)
        val (recorder, manager) = pressShiftedUyghurL(prefix = "ب") {
            addAltKeyMapping(KeyEvent.KEYCODE_L, "x")
        }
        shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.MILLISECONDS)
        assertEquals("بx", recorder.text)
        manager.handleKeyUp(KeyEvent.KEYCODE_L, symKeyActive = false)
    }

    @Test
    fun altLongPress_withoutExtractedTextDeletesInsertedLength() {
        SettingsManager.setLongPressModifier(context, "alt")
        LayoutMappingRepository.loadLayout(context.assets, "uyghur", context)
        val (recorder, manager) = pressShiftedUyghurL(prefix = "ب", extractedTextAvailable = false) {
            addAltKeyMapping(KeyEvent.KEYCODE_L, "x")
        }
        shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.MILLISECONDS)
        assertEquals("بx", recorder.text)
        assertEquals(listOf(2 to 0), recorder.deleteCalls)
        manager.handleKeyUp(KeyEvent.KEYCODE_L, symKeyActive = false)
    }

    @Test
    fun symLongPress_replacesWholeMultiCharacterOutput() {
        SettingsManager.setLongPressModifier(context, "sym")
        LayoutMappingRepository.loadLayout(context.assets, "uyghur", context)
        val (recorder, manager) = pressShiftedUyghurL(prefix = "ب")
        shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.MILLISECONDS)
        assertTrue(recorder.text, recorder.text.startsWith("ب"))
        assertTrue(recorder.text, recorder.text.length > 1)
        assertFalse(recorder.text, recorder.text.contains('ل') || recorder.text.contains('ا'))
        manager.handleKeyUp(KeyEvent.KEYCODE_L, symKeyActive = false)
    }

    private fun pressShiftedUyghurL(
        prefix: String,
        extractedTextAvailable: Boolean = true,
        configure: AlternateCharacterManager.() -> Unit = {}
    ): Pair<RecordingInputConnection, AlternateCharacterManager> {
        val recorder = RecordingInputConnection(extractedTextAvailable)
        val inputConnection = recorder.asProxy()
        inputConnection.commitText(prefix, 1)
        val manager = AlternateCharacterManager(context.assets, prefs, context).apply(configure)
        val event = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_L, 0, KeyEvent.META_SHIFT_ON)
        manager.handleKeyWithAltMapping(KeyEvent.KEYCODE_L, event, false, inputConnection)
        assertEquals(prefix + "لا", recorder.text)
        return recorder to manager
    }

    @Test
    fun shiftLongPress_afterLaterText_replacesAtInsertionInOneEdit() {
        SettingsManager.setLongPressModifier(context, "shift")
        LayoutMappingRepository.loadLayout(context.assets, "uyghur", context)
        val (recorder, manager) = pressShiftedUyghurL(prefix = "ب")
        recorder.asProxy().commitText("د", 1)
        recorder.editsOutsideBatch = 0

        shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.MILLISECONDS)

        assertEquals("بلد", recorder.text)
        assertEquals(3, recorder.selectionStart)
        assertEquals(3, recorder.selectionEnd)
        assertEquals(0, recorder.editsOutsideBatch)
        assertTrue(recorder.deleteCalls.isEmpty())
        manager.handleKeyUp(KeyEvent.KEYCODE_L, symKeyActive = false)
    }

    @Test
    fun altLongPress_afterLaterText_replacesAtInsertion() {
        SettingsManager.setLongPressModifier(context, "alt")
        LayoutMappingRepository.loadLayout(context.assets, "uyghur", context)
        val (recorder, manager) = pressShiftedUyghurL(prefix = "") {
            addAltKeyMapping(KeyEvent.KEYCODE_L, "😀")
        }
        recorder.asProxy().commitText("د", 1)

        shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.MILLISECONDS)

        assertEquals("😀د", recorder.text)
        assertEquals(3, recorder.selectionStart)
        manager.handleKeyUp(KeyEvent.KEYCODE_L, symKeyActive = false)
    }

    @Test
    fun shiftLongPress_withCursorMovedBeforeInsertion_changesNothing() {
        SettingsManager.setLongPressModifier(context, "shift")
        LayoutMappingRepository.loadLayout(context.assets, "uyghur", context)
        val committedNormally = mutableListOf<String>()
        val (recorder, manager) = pressShiftedUyghurL(prefix = "😀") {
            onNormalCharCommitted = { committedNormally += it }
        }
        recorder.asProxy().setSelection(2, 2)

        shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.MILLISECONDS)

        assertEquals("😀لا", recorder.text)
        assertEquals(2, recorder.selectionStart)
        assertTrue(recorder.deleteCalls.isEmpty())
        manager.handleKeyUp(KeyEvent.KEYCODE_L, symKeyActive = false)
        assertEquals(listOf("لا"), committedNormally)
    }

    @Test
    fun altLongPress_withChangedInsertion_changesNothing() {
        SettingsManager.setLongPressModifier(context, "alt")
        LayoutMappingRepository.loadLayout(context.assets, "uyghur", context)
        val (recorder, manager) = pressShiftedUyghurL(prefix = "ب") {
            addAltKeyMapping(KeyEvent.KEYCODE_L, "x")
        }
        recorder.replaceTextDirectly(1, 3, "دد")

        shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.MILLISECONDS)

        assertEquals("بدد", recorder.text)
        assertTrue(recorder.deleteCalls.isEmpty())
        manager.handleKeyUp(KeyEvent.KEYCODE_L, symKeyActive = false)
    }

    @Test
    fun altLongPress_withoutExtractedText_usesTextBeforeCursor() {
        SettingsManager.setLongPressModifier(context, "alt")
        LayoutMappingRepository.loadLayout(context.assets, "uyghur", context)
        val (recorder, manager) = pressShiftedUyghurL(prefix = "ب", extractedTextAvailable = false) {
            addAltKeyMapping(KeyEvent.KEYCODE_L, "x")
        }
        recorder.textBeforeCursorAvailable = true
        shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.MILLISECONDS)
        assertEquals("بx", recorder.text)
        manager.handleKeyUp(KeyEvent.KEYCODE_L, symKeyActive = false)
    }

    @Test
    fun altLongPress_withoutExtractedText_keepsTextWhenCursorTextDiffers() {
        SettingsManager.setLongPressModifier(context, "alt")
        LayoutMappingRepository.loadLayout(context.assets, "uyghur", context)
        val (recorder, manager) = pressShiftedUyghurL(prefix = "😀", extractedTextAvailable = false) {
            addAltKeyMapping(KeyEvent.KEYCODE_L, "x")
        }
        recorder.textBeforeCursorAvailable = true
        recorder.asProxy().setSelection(2, 2)

        shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.MILLISECONDS)

        assertEquals("😀لا", recorder.text)
        assertTrue(recorder.deleteCalls.isEmpty())
        manager.handleKeyUp(KeyEvent.KEYCODE_L, symKeyActive = false)
    }

    @Test
    fun shiftLongPress_whenEditorRejectsReplacement_insertsNothing() {
        SettingsManager.setLongPressModifier(context, "shift")
        LayoutMappingRepository.loadLayout(context.assets, "uyghur", context)
        val committedNormally = mutableListOf<String>()
        val (recorder, manager) = pressShiftedUyghurL(prefix = "ب") {
            onNormalCharCommitted = { committedNormally += it }
        }
        recorder.asProxy().commitText("د", 1)
        recorder.composingRegionResult = false

        shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.MILLISECONDS)

        assertEquals("بلاد", recorder.text)
        manager.handleKeyUp(KeyEvent.KEYCODE_L, symKeyActive = false)
        assertEquals(listOf("لا"), committedNormally)
    }

    @Test
    fun altLongPress_whenEditorRejectsDeletion_insertsNothing() {
        SettingsManager.setLongPressModifier(context, "alt")
        LayoutMappingRepository.loadLayout(context.assets, "uyghur", context)
        val (recorder, manager) = pressShiftedUyghurL(prefix = "ب") {
            addAltKeyMapping(KeyEvent.KEYCODE_L, "x")
        }
        recorder.deleteResult = false

        shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.MILLISECONDS)

        assertEquals("بلا", recorder.text)
        manager.handleKeyUp(KeyEvent.KEYCODE_L, symKeyActive = false)
    }

    @Test
    fun altLongPress_replacesSurrogatePairBeforeLaterText() {
        SettingsManager.setLongPressModifier(context, "alt")
        val recorder = RecordingInputConnection()
        val inputConnection = recorder.asProxy()
        val manager = AlternateCharacterManager(context.assets, prefs, context).apply {
            addAltKeyMapping(KeyEvent.KEYCODE_E, "x")
        }
        inputConnection.commitText("a😀", 1)
        manager.scheduleLongPressOnly(KeyEvent.KEYCODE_E, inputConnection, "😀")
        inputConnection.commitText("b", 1)

        shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.MILLISECONDS)

        assertEquals("axb", recorder.text)
        assertEquals(3, recorder.selectionStart)
        manager.handleKeyUp(KeyEvent.KEYCODE_E, symKeyActive = false)
    }

    @Test
    fun shiftLongPress_onOverlappingKeys_replacesEachInsertion() {
        SettingsManager.setLongPressModifier(context, "shift")
        LayoutMappingRepository.loadLayout(context.assets, "uyghur", context)
        val (recorder, manager) = pressShiftedUyghurL(prefix = "ب")
        manager.handleKeyWithAltMapping(
            KeyEvent.KEYCODE_D, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_D), false, recorder.asProxy()
        )
        assertEquals("بلاد", recorder.text)

        shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.MILLISECONDS)

        assertEquals("بلژ", recorder.text)
        assertEquals(3, recorder.selectionStart)
        manager.handleKeyUp(KeyEvent.KEYCODE_L, symKeyActive = false)
        manager.handleKeyUp(KeyEvent.KEYCODE_D, symKeyActive = false)
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

    private fun pressT(
        manager: AlternateCharacterManager,
        inputConnection: InputConnection,
        shiftOneShot: Boolean
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

    @Test
    fun shiftLongPress_autoCapitalizedLetterTogglesToLowercase() {
        SettingsManager.setLongPressModifier(context, "shift")
        val recorder = RecordingInputConnection()
        val inputConnection = recorder.asProxy()
        val manager = AlternateCharacterManager(context.assets, prefs, context).apply {
            addAltKeyMapping(KeyEvent.KEYCODE_T, "(")
        }

        // Auto-capitalization arrives as a one-shot Shift on the typed letter.
        pressT(manager, inputConnection, shiftOneShot = true)
        assertEquals("T", recorder.text)
        shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.MILLISECONDS)
        assertEquals("t", recorder.text)
        shadowOf(Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS)
        assertEquals("t", recorder.text)
    }

    @Test
    fun shiftLongPress_lowercaseLetterStillGivesUppercase() {
        SettingsManager.setLongPressModifier(context, "shift")
        val recorder = RecordingInputConnection()
        val inputConnection = recorder.asProxy()
        val manager = AlternateCharacterManager(context.assets, prefs, context).apply {
            addAltKeyMapping(KeyEvent.KEYCODE_T, "(")
        }

        pressT(manager, inputConnection, shiftOneShot = false)
        shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.MILLISECONDS)
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
        var extractedTextAvailable: Boolean = true,
        var textBeforeCursorAvailable: Boolean = false
    ) {
        var composingRegionResult = true
        var deleteResult = true
        private var batchDepth = 0
        /** Text edits made while no batch edit was open. */
        var editsOutsideBatch = 0

        var text: String = ""
        var selectionStart: Int = 0
            private set
        var selectionEnd: Int = 0
            private set
        private var composingStart: Int = -1
        private var composingEnd: Int = -1
        val composingRegions = mutableListOf<Pair<Int, Int>>()
        val deleteCalls = mutableListOf<Pair<Int, Int>>()

        fun replaceTextDirectly(start: Int, end: Int, replacement: String) {
            text = text.replaceRange(start, end, replacement)
            val lengthDelta = replacement.length - (end - start)
            selectionStart += lengthDelta
            selectionEnd += lengthDelta
        }

        // One editor, one connection: the manager tells editors apart by connection identity.
        private val proxy: InputConnection by lazy { createProxy() }

        fun asProxy(): InputConnection = proxy

        private fun createProxy(): InputConnection {
            return Proxy.newProxyInstance(
                InputConnection::class.java.classLoader,
                arrayOf(InputConnection::class.java)
            ) { _, method, args ->
                if (method.name in setOf("commitText", "deleteSurroundingText", "setComposingRegion", "setSelection") &&
                    batchDepth == 0
                ) {
                    editsOutsideBatch++
                }
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
                    "setComposingRegion" -> if (!composingRegionResult) false else {
                        composingStart = args?.getOrNull(0) as Int
                        composingEnd = args.getOrNull(1) as Int
                        composingRegions += composingStart to composingEnd
                        true
                    }
                    "deleteSurroundingText" -> if (!deleteResult) false else {
                        val before = args?.getOrNull(0) as Int
                        val after = args.getOrNull(1) as Int
                        deleteCalls += before to after
                        val start = (selectionStart - before).coerceAtLeast(0)
                        val end = (selectionEnd + after).coerceAtMost(text.length)
                        text = text.removeRange(start, end)
                        selectionStart = start
                        selectionEnd = start
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
                        batchDepth--
                        true
                    }
                    "getTextBeforeCursor" -> if (textBeforeCursorAvailable) {
                        val length = args?.getOrNull(0) as Int
                        text.substring((selectionStart - length).coerceAtLeast(0), selectionStart)
                    } else {
                        null
                    }
                    "getExtractedText" -> if (extractedTextAvailable) {
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
