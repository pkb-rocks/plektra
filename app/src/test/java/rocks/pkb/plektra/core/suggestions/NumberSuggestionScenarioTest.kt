package rocks.pkb.plektra.core.suggestions

import android.content.Context
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import java.util.Locale
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
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.core.Punctuation
import rocks.pkb.plektra.data.layout.LayoutMappingRepository
import rocks.pkb.plektra.data.mappings.DeviceSymMappingRepository
import rocks.pkb.plektra.inputmethod.suggestions.SuggestionButtonHandler

/**
 * Scenario matrix for number suggestions on BlackBerry-style keyboards.
 * Everything is observed through the suggestion bar contents and the editor text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NumberSuggestionScenarioTest {

    private lateinit var context: Context
    private val controllers = mutableListOf<SuggestionController>()
    private lateinit var q25DigitMap: Map<Char, Char>

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        SettingsManager.getPreferences(context).edit().clear().commit()
        LayoutMappingRepository.loadLayout(context.assets, "qwerty")
        q25DigitMap = digitMapFor("Q25")
    }

    @After
    fun tearDown() {
        controllers.forEach(SuggestionController::destroy)
        SettingsManager.getPreferences(context).edit().clear().commit()
    }

    // ---------------------------------------------------------------------------------
    // Suggestion bar matrix
    // ---------------------------------------------------------------------------------

    private data class BarCase(
        val typed: String,
        val number: String?,
        val top: Boolean? = null
    )

    private val barCases = listOf(
        // Pure digit keys, with a real 0
        BarCase("w00", "100", top = true),
        BarCase("e00", "200", top = true),
        BarCase("r00", "300", top = true),
        BarCase("s00", "400", top = true),
        BarCase("d00", "500", top = true),
        BarCase("f00", "600", top = true),
        BarCase("z00", "700", top = true),
        BarCase("x00", "800", top = true),
        BarCase("c00", "900", top = true),
        BarCase("w0", "10"),
        BarCase("wer", "123"),
        BarCase("sdf", "456"),
        BarCase("zxc", "789"),
        BarCase("dxz", "587", top = true),
        BarCase("ed0", "250"),
        // Case-insensitive
        BarCase("W00", "100", top = true),
        BarCase("E00", "200", top = true),
        BarCase("RED", "325"),
        BarCase("DxZ", "587", top = true),
        BarCase("wEr", "123"),
        // Real words that are also numbers: number never above a real-word candidate
        BarCase("red", "325", top = false),
        BarCase("see", "422", top = false),
        BarCase("wed", "125", top = false),
        BarCase("dex", "528"),
        BarCase("fez", "627"),
        BarCase("w", "1", top = false),
        // Separators kept exactly as typed
        BarCase("ed0.000", "250.000", top = true),
        BarCase("ed0,000", "250,000", top = true),
        BarCase("w,500", "1,500", top = true),
        BarCase("w.d", "1.5"),
        BarCase("w.e.r", "1.2.3"),
        BarCase("e,d00.00", "2,500.00", top = true),
        BarCase("dxz.w00", "587.100", top = true),
        // Leading / consecutive separators and word prefixes are not part of the number
        BarCase(".w00", "100", top = true),
        BarCase(",d", "5"),
        BarCase("w..e00", "200", top = true),
        BarCase("w,,e00", "200", top = true),
        BarCase("hello.w00", "100", top = true),
        BarCase("red.w00", "100", top = true),
        BarCase("$" + "w00", "100", top = true),
        // Not a number: non-digit letters, nothing to convert, or real digits only
        BarCase("abc", null),
        BarCase("hello", null),
        BarCase("w0a", null),
        BarCase("wq0", null),
        BarCase("wet", null),
        BarCase("0", null),
        BarCase("100", null),
        BarCase("1.500", null),
        BarCase("w'0", null),
        // Trailing separator: nothing offered until the next digit
        BarCase("w00.", null),
        BarCase("w00,", null),
        BarCase("ed0.", null),
        // Very long input
        BarCase("w" + "0".repeat(47), "1" + "0".repeat(47), top = true),
        BarCase("w" + "0".repeat(48), null),
        BarCase("w" + "0".repeat(30) + "." + "0".repeat(30), null)
    )

    @Test
    fun suggestionBarMatrix() {
        val failures = mutableListOf<String>()
        barCases.forEach { case ->
            val off = typeAndReadBar(case.typed, numberSuggestions = false)
            val onHarness = Harness(numberSuggestions = true, autoCorrect = false).apply { type(case.typed) }
            val on = onHarness.bar()
            val addButton = onHarness.addWordCandidate()
                ?.takeUnless { add -> off.any { it.candidate.equals(add, ignoreCase = true) } }
            val visibleSlots = if (addButton != null) MAX_SUGGESTIONS - 1 else MAX_SUGGESTIONS
            val onCandidates = on.map { it.candidate }
            val offCandidates = off.map { it.candidate }
            val label = "'${case.typed}' off=$offCandidates on=$onCandidates"

            if (case.number == null) {
                if (onCandidates != offCandidates) failures += "$label: expected unchanged bar"
                return@forEach
            }
            val index = onCandidates.indexOf(case.number)
            if (index < 0) {
                failures += "$label: missing ${case.number}"
                return@forEach
            }
            val wordsOnly = onCandidates.filterIndexed { i, _ -> i != index }
            if (wordsOnly != offCandidates.take(wordsOnly.size)) {
                failures += "$label: word candidates changed"
            }
            val segment = case.typed.takeLastWhile { it.isLetterOrDigit() }
            val strongBefore = off.take(visibleSlots - 1).indexOfLast { isRealWord(it, segment) } + 1
            if (index != strongBefore) {
                failures += "$label: number at $index, expected right after real words ($strongBefore)"
            }
            if (index >= visibleSlots) failures += "$label: number hidden behind the add-word button"
            when (case.top) {
                true -> if (index != 0) failures += "$label: expected number on top"
                false -> if (index == 0) failures += "$label: expected number below word candidates"
                null -> Unit
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    /**
     * A word that completes what was typed, or is one typo away from it. Fuzzy completions
     * such as "reduce" for "r00" are not real alternatives to the number.
     */
    private fun isRealWord(result: SuggestionResult, typed: String): Boolean {
        if (result.kind != SuggestionKind.CURRENT_WORD) return false
        val candidate = result.candidate.lowercase()
        val input = typed.lowercase()
        return candidate.startsWith(input) || levenshtein(input, candidate) <= 1
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val curr = IntArray(b.length + 1)
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            }
            prev = curr
        }
        return prev[b.length]
    }

    @Test
    fun realWordsWithDigitTyposStillOutrankTheNumber() {
        val harness = Harness(numberSuggestions = true, autoCorrect = true)
        harness.type("w0rd")
        assertEquals("word", harness.bar().first().candidate)
        harness.type(" ")
        assertEquals("word ", harness.editor.text)
    }

    @Test
    fun numberIsNeverShownWhenFeatureIsOff() {
        listOf("w00", "red", "ed0.000", "w,500", "dxz").forEach { typed ->
            val bar = typeAndReadBar(typed, numberSuggestions = false)
            assertFalse(typed, bar.any { it.kind == SuggestionKind.NUMBER })
            assertFalse(typed, bar.any { it.candidate.all { c -> c.isDigit() || c == '.' || c == ',' } })
        }
    }

    @Test
    fun emptyInputOffersNothingAndCommitsNothing() {
        val harness = Harness(numberSuggestions = true, autoCorrect = true)
        harness.type("")
        assertTrue(harness.bar().isEmpty())
        harness.type(" ")
        assertEquals(" ", harness.editor.text)
    }

    @Test
    fun backspaceUpdatesNumberSuggestion() {
        val harness = Harness(numberSuggestions = true, autoCorrect = false)
        harness.type("w00")
        assertEquals("100", harness.bar().first().candidate)
        harness.backspace()
        assertEquals("10", harness.bar().first().candidate)
    }

    @Test
    fun titan2AltLayerIsReusedInsteadOfQ25Digits() {
        val harness = Harness(numberSuggestions = true, autoCorrect = true, digitMap = digitMapFor("titan2"))
        harness.type("xcv")
        assertEquals("789", harness.bar().first().candidate)
        harness.type(" ")
        assertEquals("789 ", harness.editor.text)
    }

    // ---------------------------------------------------------------------------------
    // Autocorrect on/off x boundary matrix
    // ---------------------------------------------------------------------------------

    private data class CommitCase(
        val typed: String,
        val autoCorrect: Boolean,
        val expected: String,
        val numberSuggestions: Boolean = true
    )

    private val commitCases = listOf(
        CommitCase("w00 ", true, "100 "),
        CommitCase("w00 ", false, "w00 "),
        CommitCase("e00 ", true, "200 "),
        CommitCase("e00 ", false, "e00 "),
        CommitCase("r00 ", true, "300 "),
        CommitCase("W00 ", true, "100 "),
        CommitCase("dxz ", true, "587 "),
        CommitCase("dxz ", false, "dxz "),
        CommitCase("zxc ", true, "789 "),
        CommitCase("w0 ", true, "10 "),
        // Real words always win
        CommitCase("red ", true, "red "),
        CommitCase("red ", false, "red "),
        CommitCase("Red ", true, "Red "),
        CommitCase("see ", true, "see "),
        CommitCase("dex ", true, "dex "),
        CommitCase("fez ", true, "fez "),
        CommitCase("wed ", true, "wed "),
        // A normal correction ranks above the number
        CommitCase("sxd ", true, "sad "),
        // Too short to be clearly a number
        CommitCase("w ", true, "w "),
        CommitCase("sd ", true, "sd "),
        // Separators
        CommitCase("ed0.000 ", true, "250.000 "),
        CommitCase("ed0.000 ", false, "ed0.000 "),
        CommitCase("ed0,000 ", true, "250,000 "),
        CommitCase("w,500 ", true, "1,500 "),
        CommitCase("e,d00.00 ", true, "2,500.00 "),
        CommitCase("ed0. ", true, "250. "),
        CommitCase("w00, ", true, "100, "),
        CommitCase("w.. ", true, "w.. "),
        CommitCase("w00.. ", true, "w00.. "),
        CommitCase(".w00 ", true, ".100 "),
        CommitCase("hello.w00 ", true, "hello.100 "),
        CommitCase("red.w00 ", true, "red.100 "),
        // '.' and ',' are separators, never a commit trigger
        CommitCase("w00.", true, "w00."),
        CommitCase("w00,", true, "w00,"),
        // Other boundaries commit like Space
        CommitCase("w00\n", true, "100\n"),
        CommitCase("w00!", true, "100!"),
        CommitCase("w00?", true, "100?"),
        // Not numbers
        CommitCase("abc ", true, "abc "),
        CommitCase("100 ", true, "100 "),
        CommitCase("wq0 ", true, "wq0 "),
        // Feature off
        CommitCase("w00 ", true, "w00 ", numberSuggestions = false),
        CommitCase("ed0.000 ", true, "ed0.000 ", numberSuggestions = false),
        CommitCase("dxz ", true, "dxz ", numberSuggestions = false),
        // Several numbers in a row
        CommitCase("w00 e00 ", true, "100 200 "),
        CommitCase("red w00 ", true, "red 100 ")
    )

    @Test
    fun autoCorrectBoundaryMatrix() {
        val failures = mutableListOf<String>()
        commitCases.forEach { case ->
            val harness = Harness(case.numberSuggestions, case.autoCorrect)
            harness.type(case.typed)
            if (harness.editor.text != case.expected) {
                failures += "'${case.typed.replace("\n", "\\n")}' ac=${case.autoCorrect} " +
                    "numbers=${case.numberSuggestions}: got '${harness.editor.text.replace("\n", "\\n")}', " +
                    "expected '${case.expected.replace("\n", "\\n")}'"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun backspaceUndoRestoresTypedNumberLikeNormalAutoCorrect() {
        listOf(
            "w00" to "100 ",
            "ed0.000" to "250.000 ",
            "w,500" to "1,500 ",
            "ed0." to "250. "
        ).forEach { (typed, converted) ->
            val harness = Harness(numberSuggestions = true, autoCorrect = true)
            harness.type("$typed ")
            assertEquals(typed, converted, harness.editor.text)
            assertTrue(typed, harness.undoBackspace())
            assertEquals(typed, typed, harness.editor.text)
            harness.type(" ")
            assertEquals("rejected after undo: $typed", "$typed ", harness.editor.text)
        }
    }

    @Test
    fun undoIsUnavailableWhenNothingWasConverted() {
        val harness = Harness(numberSuggestions = true, autoCorrect = true)
        harness.type("red ")
        assertFalse(harness.undoBackspace())
        assertEquals("red ", harness.editor.text)
    }

    // ---------------------------------------------------------------------------------
    // Tapping the suggestion
    // ---------------------------------------------------------------------------------

    @Test
    fun tappingNumberReplacesTheWholeTypedToken() {
        listOf(
            Triple("w00", "100", "100 "),
            Triple("ed0.000", "250.000", "250.000 "),
            Triple("w,500", "1,500", "1,500 "),
            Triple(".w00", "100", ".100 "),
            Triple("hello.w00", "100", "hello.100 "),
            Triple("dxz.w00", "587.100", "587.100 "),
            Triple("red", "325", "325 ")
        ).forEach { (typed, number, expected) ->
            val harness = Harness(numberSuggestions = true, autoCorrect = false)
            harness.type(typed)
            assertTrue("$typed bar=${harness.bar()}", harness.bar().any { it.candidate == number })
            harness.tap(number)
            assertEquals(typed, expected, harness.editor.text)
        }
    }

    @Test
    fun tappingAWordSuggestionIsUnchanged() {
        val harness = Harness(numberSuggestions = true, autoCorrect = false)
        harness.type("red")
        harness.tap("reduce")
        assertEquals("reduce ", harness.editor.text)
    }

    // ---------------------------------------------------------------------------------
    // Harness
    // ---------------------------------------------------------------------------------

    private fun typeAndReadBar(typed: String, numberSuggestions: Boolean): List<SuggestionResult> {
        val harness = Harness(numberSuggestions, autoCorrect = false)
        harness.type(typed)
        return harness.bar()
    }

    private fun digitMapFor(profile: String): Map<Char, Char> = NumberSuggestions.digitMap(
        DeviceSymMappingRepository.load(context.assets, context, profile),
        LayoutMappingRepository::getLowercase
    )

    private fun dictionary() = FakeDictionaryRepository().apply {
        isReady = true
        addTestEntry("red", 200)
        addTestEntry("reduce", 220)
        addTestEntry("see", 230)
        addTestEntry("seen", 210)
        addTestEntry("dex", 100)
        addTestEntry("fez", 90)
        addTestEntry("wed", 150)
        addTestEntry("we", 250)
        addTestEntry("hello", 240)
        addTestEntry("sad", 200)
        addTestEntry("word", 220)
    }

    private inner class Harness(
        numberSuggestions: Boolean,
        autoCorrect: Boolean,
        digitMap: Map<Char, Char> = q25DigitMap
    ) {
        val editor = Editor(context)
        private val controller = SuggestionController(
            context = context,
            assets = context.assets,
            settingsProvider = {
                SuggestionSettings(
                    textReplacementsEnabled = false,
                    suggestionsEnabled = true,
                    autoReplaceOnSpaceEnter = autoCorrect,
                    maxSuggestions = MAX_SUGGESTIONS,
                    numberSuggestionsEnabled = numberSuggestions
                )
            },
            onSuggestionsUpdated = {},
            currentLocale = Locale.ENGLISH,
            dictionaryRepositoryFactory = { _, _, _, _, _ -> dictionary() },
            numberDigitMapProvider = { digitMap }
        ).also { controllers += it }

        fun type(text: String) {
            text.forEach { ch ->
                when {
                    ch == ' ' -> controller.onBoundaryKey(KeyEvent.KEYCODE_SPACE, null, editor)
                    ch == '\n' -> controller.onBoundaryKey(KeyEvent.KEYCODE_ENTER, null, editor)
                    ch in Punctuation.BOUNDARY -> controller.onBoundaryKey(
                        keyCodeFor(ch), null, editor, boundaryCharOverride = ch
                    )
                    else -> {
                        editor.commitText(ch.toString(), 1)
                        controller.onCharacterCommitted(ch.toString(), editor)
                    }
                }
            }
        }

        fun backspace() {
            controller.refreshFromInputConnection(editor)
            editor.deleteSurroundingText(1, 0)
            controller.onCursorMoved(editor)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))
        }

        fun addWordCandidate(): String? = controller.pendingAddWord()

        fun undoBackspace(): Boolean = controller.handleBackspaceUndo(KeyEvent.KEYCODE_DEL, editor)

        fun bar(): List<SuggestionResult> {
            controller.awaitSuggestionJobForTests()
            shadowOf(Looper.getMainLooper()).idle()
            return controller.currentSuggestions()
        }

        fun tap(candidate: String) {
            SuggestionButtonHandler.createSuggestionClickListener(
                suggestion = candidate,
                inputConnection = editor,
                shouldDisableAutoCapitalize = true
            ).onClick(View(context))
        }

        private fun keyCodeFor(ch: Char): Int = when (ch) {
            '.' -> KeyEvent.KEYCODE_PERIOD
            ',' -> KeyEvent.KEYCODE_COMMA
            ';' -> KeyEvent.KEYCODE_SEMICOLON
            '/' -> KeyEvent.KEYCODE_SLASH
            else -> KeyEvent.KEYCODE_UNKNOWN
        }
    }

    private class Editor(context: Context) : BaseInputConnection(View(context), true) {
        private val buffer = StringBuilder()

        val text: String
            get() = buffer.toString()

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = buffer.takeLast(n)

        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            val start = (buffer.length - beforeLength).coerceAtLeast(0)
            buffer.delete(start, buffer.length)
            return true
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            buffer.append(text ?: "")
            return true
        }

        override fun sendKeyEvent(event: KeyEvent?): Boolean = true

        override fun beginBatchEdit(): Boolean = true

        override fun endBatchEdit(): Boolean = true
    }

    private companion object {
        const val MAX_SUGGESTIONS = 3
    }
}
