package rocks.pkb.plektra.inputmethod.suggestions

import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import rocks.pkb.plektra.SettingLinkIds
import rocks.pkb.plektra.SettingLinkRegistry
import rocks.pkb.plektra.SettingsDestination
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.backup.BackupManager
import rocks.pkb.plektra.backup.BackupResult
import rocks.pkb.plektra.backup.RestoreManager
import rocks.pkb.plektra.backup.RestoreResult
import rocks.pkb.plektra.core.AutoCorrectionManager
import rocks.pkb.plektra.core.AutoSpaceTracker
import rocks.pkb.plektra.core.InputContextState
import rocks.pkb.plektra.core.ModifierStateController
import rocks.pkb.plektra.core.NavModeController
import rocks.pkb.plektra.core.suggestions.AutoReplaceController
import rocks.pkb.plektra.core.suggestions.CurrentWordTracker
import rocks.pkb.plektra.core.suggestions.FakeDictionaryRepository
import rocks.pkb.plektra.core.suggestions.SuggestionEngine
import rocks.pkb.plektra.core.suggestions.SuggestionKind
import rocks.pkb.plektra.core.suggestions.SuggestionResult
import rocks.pkb.plektra.core.suggestions.SuggestionSettings
import rocks.pkb.plektra.core.suggestions.SuggestionSource
import rocks.pkb.plektra.inputmethod.InputEventRouter
import rocks.pkb.plektra.inputmethod.PhysicalKeyboardInputMethodService
import rocks.pkb.plektra.inputmethod.suggestions.ui.FullSuggestionsBar
import java.io.File
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SuggestionSpacingBehaviorTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        SettingsManager.getPreferences(context).edit().clear().commit()
        AutoSpaceTracker.clear()
    }

    @After
    fun tearDown() {
        AutoSpaceTracker.clear()
    }

    @Test
    fun defaultSetting_tapSuggestionAddsSpaceAndBaselineCommaFollowsSpace() {
        assertTrue(SettingsManager.getAutoSpaceAfterSuggestion(context))

        val inputConnection = FakeInputConnection(context, "hel")
        val button = renderSingleSuggestionButton(inputConnection, "hello")

        button.performClick()

        assertEquals("hello ", inputConnection.text)
        assertTrue(AutoSpaceTracker.isPending())

        commitCommaViaRouter(inputConnection)
        assertEquals("hello ,", inputConnection.text)
    }

    @Test
    fun optionOff_tapSuggestionThenCommaCommitsWithoutAnySpaces() {
        SettingsManager.setAutoSpaceAfterSuggestion(context, false)
        AutoSpaceTracker.markAutoSpace()

        val inputConnection = FakeInputConnection(context, "hel")
        val button = renderSingleSuggestionButton(inputConnection, "hello")

        button.performClick()

        assertEquals("hello", inputConnection.text)
        assertFalse(AutoSpaceTracker.isPending())

        commitCommaViaRouter(inputConnection)
        assertEquals("hello,", inputConnection.text)
        assertFalse(AutoSpaceTracker.isPending())
    }

    @Test
    fun optionOff_keyboardActivationOfSuggestionViewCommitsWithoutSpace() {
        SettingsManager.setAutoSpaceAfterSuggestion(context, false)
        AutoSpaceTracker.markAutoSpace()

        val inputConnection = FakeInputConnection(context, "hel")
        val button = renderSingleSuggestionButton(inputConnection, "hello")
        assertTrue(button.isFocusable)

        val downHandled = button.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        val upHandled = button.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))

        assertTrue(downHandled || upHandled)
        assertEquals("hello", inputConnection.text)
        assertFalse(AutoSpaceTracker.isPending())
    }

    @Test
    fun trackpadSwipeAccept_respectsAutoSpaceAfterSuggestionOffAndOn() {
        val serviceController = Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java).create()
        val service = serviceController.get()
        try {
            val editorInfo = EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_TEXT
                packageName = "rocks.pkb.plektra.test"
            }

            // Option OFF: swipe-accept suggestion -> comma directly attached without space
            SettingsManager.setAutoSpaceAfterSuggestion(context, false)
            AutoSpaceTracker.markAutoSpace()
            val icOff = FakeInputConnection(context, "hel")
            bindServiceForSuggestionSwipe(service, icOff, editorInfo, listOf("hello"))

            invokeAcceptSuggestionAtIndex(service, 1)
            shadowOf(android.os.Looper.getMainLooper()).idle()

            assertEquals("hello", icOff.text)
            assertFalse(AutoSpaceTracker.isPending())

            val commaDown = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_COMMA)
            val handledComma = service.onKeyDown(KeyEvent.KEYCODE_COMMA, commaDown)
            if (!handledComma) {
                icOff.commitText(",", 1)
            }
            assertEquals("hello,", icOff.text)

            // Option ON (baseline): swipe-accept suggestion appends space and marks auto-space
            SettingsManager.setAutoSpaceAfterSuggestion(context, true)
            AutoSpaceTracker.clear()
            val icOn = FakeInputConnection(context, "hel")
            bindServiceForSuggestionSwipe(service, icOn, editorInfo, listOf("hello"))

            invokeAcceptSuggestionAtIndex(service, 1)
            shadowOf(android.os.Looper.getMainLooper()).idle()

            assertEquals("hello ", icOn.text)
            assertTrue(AutoSpaceTracker.isPending())

            // Option ON with existing whitespace after cursor: no duplicate space
            AutoSpaceTracker.markAutoSpace()
            val icBeforeSpace = FakeInputConnection(context, "hel", " world")
            bindServiceForSuggestionSwipe(service, icBeforeSpace, editorInfo, listOf("hello"))

            invokeAcceptSuggestionAtIndex(service, 1)
            shadowOf(android.os.Looper.getMainLooper()).idle()

            assertEquals("hello world", icBeforeSpace.text)
            assertFalse(AutoSpaceTracker.isPending())
        } finally {
            serviceController.destroy()
        }
    }

    @Test
    fun optionOff_suffixTypingAfterAcceptedSuggestionAppendsDirectly() {
        SettingsManager.setAutoSpaceAfterSuggestion(context, false)
        val inputConnection = FakeInputConnection(context, "pla")

        val committed = SuggestionButtonHandler.commitSuggestion(
            context = context,
            inputConnection = inputConnection,
            suggestion = "play",
            shouldDisableAutoCapitalize = true
        )

        assertTrue(committed)
        assertEquals("play", inputConnection.text)

        inputConnection.commitText("ing", 1)
        assertEquals("playing", inputConnection.text)
    }

    @Test
    fun optionOff_commaSpaceStillAppliesWhenExplicitlyEnabled() {
        SettingsManager.setAutoSpaceAfterSuggestion(context, false)
        SettingsManager.setCommaSpace(context, true)
        val inputConnection = FakeInputConnection(context, "hel")

        SuggestionButtonHandler.commitSuggestion(
            context = context,
            inputConnection = inputConnection,
            suggestion = "hello",
            shouldDisableAutoCapitalize = true
        )
        assertEquals("hello", inputConnection.text)

        commitCommaViaRouter(inputConnection)
        assertEquals("hello, ", inputConnection.text)
    }

    @Test
    fun optionOff_autoSpacePunctuationDoesNotInsertUnsolicitedSpace() {
        SettingsManager.setAutoSpaceAfterSuggestion(context, false)
        SettingsManager.setAutoSpacePunctuation(context, ",")
        val inputConnection = FakeInputConnection(context, "hel")

        SuggestionButtonHandler.commitSuggestion(
            context = context,
            inputConnection = inputConnection,
            suggestion = "hello",
            shouldDisableAutoCapitalize = true
        )
        assertEquals("hello", inputConnection.text)
        assertFalse(AutoSpaceTracker.isPending())

        commitCommaViaRouter(inputConnection)
        assertEquals("hello,", inputConnection.text)
    }

    @Test
    fun optionOff_doesNotChangeAutoReplaceOnSpaceBoundary() {
        SettingsManager.setAutoSpaceAfterSuggestion(context, false)
        val repository = FakeDictionaryRepository().apply {
            addTestEntry("hello", 250, SuggestionSource.MAIN)
            isReady = true
        }
        val engine = SuggestionEngine(repository, locale = Locale.ENGLISH)
        val settings = SuggestionSettings(
            autoReplaceOnSpaceEnter = true,
            maxAutoReplaceDistance = 1
        )
        val controller = AutoReplaceController(repository, engine, { settings })
        val tracker = CurrentWordTracker(onWordChanged = {}, onWordReset = {})
        val inputConnection = FakeInputConnection(context, "helpo")
        tracker.setWord("helpo", notify = false)

        val result = controller.handleBoundary(
            keyCode = KeyEvent.KEYCODE_SPACE,
            event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE),
            tracker = tracker,
            inputConnection = inputConnection
        )

        assertTrue(result.replaced)
        assertTrue(result.committed)
        assertEquals("hello ", inputConnection.text)
    }

    @Test
    fun optionOn_apostropheSuggestionAndWhitespaceAfterCursorSkipTrailingSpace() {
        SettingsManager.setAutoSpaceAfterSuggestion(context, true)
        AutoSpaceTracker.markAutoSpace()
        val apostropheIc = FakeInputConnection(context, "l")
        SuggestionButtonHandler.commitSuggestion(
            context = context,
            inputConnection = apostropheIc,
            suggestion = "l'",
            shouldDisableAutoCapitalize = true
        )
        assertEquals("l'", apostropheIc.text)
        assertFalse(AutoSpaceTracker.isPending())

        AutoSpaceTracker.markAutoSpace()
        val midSentenceIc = FakeInputConnection(context, "hel", " world")
        SuggestionButtonHandler.commitSuggestion(
            context = context,
            inputConnection = midSentenceIc,
            suggestion = "hello",
            shouldDisableAutoCapitalize = true
        )
        assertEquals("hello world", midSentenceIc.text)
        assertFalse(AutoSpaceTracker.isPending())
    }

    @Test
    fun settingPersistsIsDiscoverableAndSurvivesBackupRestore() = runBlocking {
        assertTrue(SettingsManager.getAutoSpaceAfterSuggestion(context))
        SettingsManager.setAutoSpaceAfterSuggestion(context, false)
        assertFalse(SettingsManager.getAutoSpaceAfterSuggestion(context))

        val entry = SettingLinkRegistry.byId(SettingLinkIds.TEXT_INPUT_AUTO_SPACE_AFTER_SUGGESTION)
        assertNotNull(entry)
        assertEquals(SettingsDestination.TextInput, entry!!.route.destination)
        assertTrue(
            SettingLinkRegistry.search(context, "suggestion")
                .any { it.id == SettingLinkIds.TEXT_INPUT_AUTO_SPACE_AFTER_SUGGESTION }
        )
        assertTrue(
            SettingLinkRegistry.search(context, "space")
                .any { it.id == SettingLinkIds.TEXT_INPUT_AUTO_SPACE_AFTER_SUGGESTION }
        )

        val germanContext = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocale(Locale.GERMAN) }
        )
        assertTrue(
            SettingLinkRegistry.search(germanContext, "Vorschlag")
                .any { it.id == SettingLinkIds.TEXT_INPUT_AUTO_SPACE_AFTER_SUGGESTION }
        )
        assertTrue(
            SettingLinkRegistry.search(germanContext, "Leerzeichen")
                .any { it.id == SettingLinkIds.TEXT_INPUT_AUTO_SPACE_AFTER_SUGGESTION }
        )

        val backupZip = File.createTempFile("suggestion_spacing_backup_", ".zip", context.cacheDir)
        try {
            val backupResult = BackupManager.createBackup(context, Uri.fromFile(backupZip))
            assertTrue(backupResult is BackupResult.Success)

            SettingsManager.getPreferences(context).edit().clear().commit()
            assertTrue(SettingsManager.getAutoSpaceAfterSuggestion(context))

            val restoreResult = RestoreManager.restore(context, Uri.fromFile(backupZip))
            val restored = restoreResult as RestoreResult.Success
            assertTrue(restored.preferencesSummary.skippedKeys.isEmpty())
            assertFalse(SettingsManager.getAutoSpaceAfterSuggestion(context))
        } finally {
            backupZip.delete()
        }
    }

    private fun renderSingleSuggestionButton(
        inputConnection: InputConnection,
        suggestion: String
    ): TextView {
        val bar = FullSuggestionsBar(context)
        val frame = bar.ensureView()
        bar.update(
            suggestions = listOf(suggestion),
            shouldShow = true,
            inputConnection = inputConnection,
            listener = null,
            shouldDisableSuggestions = false,
            addWordCandidate = null,
            onAddUserWord = null,
            onAddUserWordSubstitutionRequested = null,
            onSuggestionCommitted = null,
            onHideSuggestion = null,
            onDeleteUserSuggestion = null,
            canDeleteUserSuggestion = null
        )
        val container = frame.getChildAt(0) as LinearLayout
        val buttons = (0 until container.childCount)
            .mapNotNull { container.getChildAt(it) as? TextView }
        return buttons.single { it.text.toString() == suggestion }
    }

    private fun commitCommaViaRouter(inputConnection: FakeInputConnection) {
        val router = InputEventRouter(
            context,
            NavModeController(context, ModifierStateController(300L))
        )
        val handled = router.handleBoundaryText(
            context = context,
            text = ",",
            inputConnection = inputConnection,
            shouldDisableSuggestions = true,
            isAutoCorrectEnabled = true,
            autoCorrectionManager = AutoCorrectionManager(context),
            updateStatusBar = {}
        )
        if (!handled) {
            inputConnection.commitText(",", 1)
        }
    }

    private fun bindServiceForSuggestionSwipe(
        service: PhysicalKeyboardInputMethodService,
        inputConnection: InputConnection,
        editorInfo: EditorInfo,
        suggestions: List<String>
    ) {
        setField(service, "mInputConnection", inputConnection)
        setField(service, "mStartedInputConnection", inputConnection)
        setField(service, "mInputEditorInfo", editorInfo)
        setField(service, "isInputViewActive", true)
        setField(service, "inputContextState", InputContextState.fromEditorInfo(editorInfo))
        setField(
            service,
            "latestSuggestionResults",
            suggestions.map {
                SuggestionResult(
                    candidate = it,
                    distance = 1,
                    score = 10.0,
                    source = SuggestionSource.MAIN,
                    kind = SuggestionKind.CURRENT_WORD
                )
            }
        )
    }

    private fun invokeAcceptSuggestionAtIndex(
        service: PhysicalKeyboardInputMethodService,
        third: Int
    ) {
        val method = PhysicalKeyboardInputMethodService::class.java
            .getDeclaredMethod("acceptSuggestionAtIndex", Int::class.javaPrimitiveType)
        method.isAccessible = true
        method.invoke(service, third)
    }

    private fun setField(target: Any, name: String, value: Any?) {
        var clazz: Class<*>? = target.javaClass
        while (clazz != null) {
            val field = clazz.declaredFields.firstOrNull { it.name == name }
            if (field != null) {
                field.isAccessible = true
                field.set(target, value)
                return
            }
            clazz = clazz.superclass
        }
        error("Field '$name' not found on ${target.javaClass.name}")
    }

    private class FakeInputConnection(
        context: Context,
        initialBefore: String,
        initialAfter: String = ""
    ) : BaseInputConnection(View(context), true) {
        private val before = StringBuilder(initialBefore)
        private val after = StringBuilder(initialAfter)

        val text: String
            get() = before.toString() + after.toString()

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence =
            before.takeLast(n)

        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence =
            after.take(n)

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            if (beforeLength > 0) {
                val deleteStart = (before.length - beforeLength).coerceAtLeast(0)
                before.delete(deleteStart, before.length)
            }
            if (afterLength > 0) {
                val deleteEnd = afterLength.coerceAtMost(after.length)
                after.delete(0, deleteEnd)
            }
            return true
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            before.append(text ?: "")
            return true
        }

        override fun finishComposingText(): Boolean = true
    }
}
