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
import android.widget.EditText
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

    @Test
    fun nullPostCommitReadback_allAcceptPathsOnAndOff_writesSingleSpaceWithoutFallbackEvents() {
        val serviceController = Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java).create()
        val service = serviceController.get()
        try {
            val editorInfo = EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_TEXT
                packageName = "rocks.pkb.plektra.test"
            }
            SettingsManager.setAutoSpacePunctuation(context, ",")

            val acceptPaths: List<Pair<String, (InputConnection) -> Unit>> = listOf(
                "tap" to { ic ->
                    renderSingleSuggestionButton(ic, "hello").performClick()
                },
                "keyboard_enter" to { ic ->
                    val button = renderSingleSuggestionButton(ic, "hello")
                    val downHandled = button.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                    val upHandled = button.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                    assertTrue("Expected ENTER activation on suggestion button", downHandled || upHandled)
                },
                "trackpad_swipe" to { ic ->
                    bindServiceForSuggestionSwipe(service, ic, editorInfo, listOf("hello"))
                    invokeAcceptSuggestionAtIndex(service, 1)
                    shadowOf(android.os.Looper.getMainLooper()).idle()
                }
            )

            for ((pathName, accept) in acceptPaths) {
                // Option ON: writes word + single space; null post-commit readbacks must NOT trigger retry or SPACE key events
                SettingsManager.setAutoSpaceAfterSuggestion(context, true)
                AutoSpaceTracker.clear()
                val icOn = DelayedReadbackEditorInputConnection(
                    context = context,
                    initialBefore = "hel",
                    nullReadbackAfterCommit = true
                )

                accept(icOn)

                assertEquals("$pathName ON should write exactly one space", "hello ", icOn.text)
                assertEquals("$pathName ON should commit space once", 1, icOn.spaceCommitAttempts)
                assertEquals("$pathName ON should not send fallback SPACE key events", 0, icOn.spaceKeyEventCount)
                assertTrue("$pathName ON should keep AutoSpaceTracker pending after confirmed space write", AutoSpaceTracker.isPending())

                icOn.recoverReadback()
                commitCommaViaRouter(icOn)
                assertEquals("$pathName ON should replace auto-space with comma once readback recovers", "hello, ", icOn.text)

                // Option OFF: writes word only, clears stale tracker, no space commits or key events
                SettingsManager.setAutoSpaceAfterSuggestion(context, false)
                AutoSpaceTracker.markAutoSpace()
                val icOff = DelayedReadbackEditorInputConnection(
                    context = context,
                    initialBefore = "hel",
                    nullReadbackAfterCommit = true
                )

                accept(icOff)

                assertEquals("$pathName OFF should write word without space", "hello", icOff.text)
                assertEquals("$pathName OFF should not commit space", 0, icOff.spaceCommitAttempts)
                assertEquals("$pathName OFF should not send SPACE key events", 0, icOff.spaceKeyEventCount)
                assertFalse("$pathName OFF should clear stale AutoSpaceTracker", AutoSpaceTracker.isPending())

                icOff.recoverReadback()
                commitCommaViaRouter(icOff)
                assertEquals("$pathName OFF should attach comma directly", "hello,", icOff.text)
            }
        } finally {
            serviceController.destroy()
        }
    }

    @Test
    fun trailingSpaceRetry_handlesSilentDropFailedCommitAndKeyEventFallback() {
        SettingsManager.setAutoSpaceAfterSuggestion(context, true)

        // 1. Existing tap-retry case: first space commit returns true but editor silently drops it (non-null readback proves absence)
        AutoSpaceTracker.clear()
        val silentDropIc = DelayedReadbackEditorInputConnection(
            context = context,
            initialBefore = "hel",
            spaceCommitMode = { attempt ->
                if (attempt == 1) SpaceCommitMode.DROP_AND_RETURN_TRUE else SpaceCommitMode.WRITE_AND_SUCCEED
            }
        )
        assertTrue(
            SuggestionButtonHandler.commitSuggestion(
                context = context,
                inputConnection = silentDropIc,
                suggestion = "hello",
                shouldDisableAutoCapitalize = true
            )
        )
        assertEquals("hello ", silentDropIc.text)
        assertEquals(2, silentDropIc.spaceCommitAttempts)
        assertEquals(0, silentDropIc.spaceKeyEventCount)
        assertTrue(AutoSpaceTracker.isPending())

        // 2. Real failure on first commit (returns false) + null readback -> retries commit once; second commit succeeds with null readback
        AutoSpaceTracker.clear()
        val firstFailThenNullSuccessIc = DelayedReadbackEditorInputConnection(
            context = context,
            initialBefore = "hel",
            nullReadbackAfterCommit = true,
            spaceCommitMode = { attempt ->
                if (attempt == 1) SpaceCommitMode.FAIL_AND_RETURN_FALSE else SpaceCommitMode.WRITE_AND_SUCCEED
            }
        )
        assertTrue(
            SuggestionButtonHandler.commitSuggestion(
                context = context,
                inputConnection = firstFailThenNullSuccessIc,
                suggestion = "hello",
                shouldDisableAutoCapitalize = true
            )
        )
        assertEquals("hello ", firstFailThenNullSuccessIc.text)
        assertEquals(2, firstFailThenNullSuccessIc.spaceCommitAttempts)
        assertEquals(0, firstFailThenNullSuccessIc.spaceKeyEventCount)
        assertTrue(AutoSpaceTracker.isPending())

        // 3. Both space commits fail (return false) -> falls back to SPACE key events and confirms via readback
        AutoSpaceTracker.clear()
        val keyEventFallbackIc = DelayedReadbackEditorInputConnection(
            context = context,
            initialBefore = "hel",
            spaceCommitMode = { SpaceCommitMode.FAIL_AND_RETURN_FALSE },
            handleSpaceKeyEvent = true
        )
        assertTrue(
            SuggestionButtonHandler.commitSuggestion(
                context = context,
                inputConnection = keyEventFallbackIc,
                suggestion = "hello",
                shouldDisableAutoCapitalize = true
            )
        )
        assertEquals("hello ", keyEventFallbackIc.text)
        assertEquals(2, keyEventFallbackIc.spaceCommitAttempts)
        assertEquals(2, keyEventFallbackIc.spaceKeyEventCount)
        assertTrue(AutoSpaceTracker.isPending())

        // 4. All space writes fail (commits return false, key events rejected/ignored) -> clears AutoSpaceTracker for both ABSENT and null readback
        for (nullAfterCommit in listOf(false, true)) {
            AutoSpaceTracker.markAutoSpace()
            val totalFailureIc = DelayedReadbackEditorInputConnection(
                context = context,
                initialBefore = "hel",
                nullReadbackAfterCommit = nullAfterCommit,
                spaceCommitMode = { SpaceCommitMode.FAIL_AND_RETURN_FALSE },
                handleSpaceKeyEvent = false
            )
            assertTrue(
                SuggestionButtonHandler.commitSuggestion(
                    context = context,
                    inputConnection = totalFailureIc,
                    suggestion = "hello",
                    shouldDisableAutoCapitalize = true
                )
            )
            assertEquals("hello", totalFailureIc.text)
            assertEquals(2, totalFailureIc.spaceCommitAttempts)
            assertEquals(2, totalFailureIc.spaceKeyEventCount)
            assertFalse("nullAfterCommit=$nullAfterCommit should clear AutoSpaceTracker on failed space write", AutoSpaceTracker.isPending())
        }
    }

    @Test
    fun realEditTextInputConnection_handlesComposingSelectionAndMidWordCursorOnAndOff() {
        SettingsManager.setAutoSpacePunctuation(context, ",")

        fun createRealEditor(initialText: String, selectionStart: Int, selectionEnd: Int = selectionStart): Pair<EditText, InputConnection> {
            val editText = EditText(context)
            editText.setText(initialText)
            editText.setSelection(selectionStart, selectionEnd)
            val ic = editText.onCreateInputConnection(EditorInfo())
            assertNotNull("EditText should provide a real EditableInputConnection", ic)
            return editText to ic!!
        }

        // 1. Active composing region ON & OFF
        SettingsManager.setAutoSpaceAfterSuggestion(context, true)
        AutoSpaceTracker.clear()
        val (composingOnEdit, composingOnIc) = createRealEditor("say hel", 7)
        assertTrue(composingOnIc.setComposingRegion(4, 7))
        assertEquals(4, BaseInputConnection.getComposingSpanStart(composingOnEdit.editableText))
        assertEquals(7, BaseInputConnection.getComposingSpanEnd(composingOnEdit.editableText))

        assertTrue(
            SuggestionButtonHandler.commitSuggestion(
                context = context,
                inputConnection = composingOnIc,
                suggestion = "hello",
                shouldDisableAutoCapitalize = true
            )
        )
        assertEquals("say hello ", composingOnEdit.text.toString())
        assertEquals(10, composingOnEdit.selectionStart)
        assertEquals(10, composingOnEdit.selectionEnd)
        assertEquals(-1, BaseInputConnection.getComposingSpanStart(composingOnEdit.editableText))
        assertEquals(-1, BaseInputConnection.getComposingSpanEnd(composingOnEdit.editableText))
        assertTrue(AutoSpaceTracker.isPending())
        commitCommaViaRouter(composingOnIc)
        assertEquals("say hello, ", composingOnEdit.text.toString())

        SettingsManager.setAutoSpaceAfterSuggestion(context, false)
        AutoSpaceTracker.markAutoSpace()
        val (composingOffEdit, composingOffIc) = createRealEditor("say hel", 7)
        assertTrue(composingOffIc.setComposingRegion(4, 7))

        assertTrue(
            SuggestionButtonHandler.commitSuggestion(
                context = context,
                inputConnection = composingOffIc,
                suggestion = "hello",
                shouldDisableAutoCapitalize = true
            )
        )
        assertEquals("say hello", composingOffEdit.text.toString())
        assertEquals(9, composingOffEdit.selectionStart)
        assertEquals(9, composingOffEdit.selectionEnd)
        assertEquals(-1, BaseInputConnection.getComposingSpanStart(composingOffEdit.editableText))
        assertEquals(-1, BaseInputConnection.getComposingSpanEnd(composingOffEdit.editableText))
        assertFalse(AutoSpaceTracker.isPending())
        commitCommaViaRouter(composingOffIc)
        assertEquals("say hello,", composingOffEdit.text.toString())

        // 2. Non-collapsed selection (full word and partial word) ON & OFF
        SettingsManager.setAutoSpaceAfterSuggestion(context, true)
        AutoSpaceTracker.clear()
        val (selectedOnEdit, selectedOnIc) = createRealEditor("say hel", 4, 7)
        assertTrue(
            SuggestionButtonHandler.commitSuggestion(
                context = context,
                inputConnection = selectedOnIc,
                suggestion = "hello",
                shouldDisableAutoCapitalize = true
            )
        )
        assertEquals("say hello ", selectedOnEdit.text.toString())
        assertEquals(10, selectedOnEdit.selectionStart)
        assertEquals(10, selectedOnEdit.selectionEnd)
        assertTrue(AutoSpaceTracker.isPending())

        SettingsManager.setAutoSpaceAfterSuggestion(context, false)
        AutoSpaceTracker.markAutoSpace()
        val (selectedOffEdit, selectedOffIc) = createRealEditor("say helo", 4, 6)
        assertTrue(
            SuggestionButtonHandler.commitSuggestion(
                context = context,
                inputConnection = selectedOffIc,
                suggestion = "hello",
                shouldDisableAutoCapitalize = true
            )
        )
        assertEquals("say hello", selectedOffEdit.text.toString())
        assertEquals(9, selectedOffEdit.selectionStart)
        assertEquals(9, selectedOffEdit.selectionEnd)
        assertFalse(AutoSpaceTracker.isPending())

        // 3. Collapsed mid-word cursor without and with trailing whitespace
        SettingsManager.setAutoSpaceAfterSuggestion(context, true)
        AutoSpaceTracker.clear()
        val (midWordOnEdit, midWordOnIc) = createRealEditor("helo", 2)
        assertTrue(
            SuggestionButtonHandler.commitSuggestion(
                context = context,
                inputConnection = midWordOnIc,
                suggestion = "hello",
                shouldDisableAutoCapitalize = true
            )
        )
        assertEquals("hello ", midWordOnEdit.text.toString())
        assertEquals(6, midWordOnEdit.selectionStart)
        assertEquals(6, midWordOnEdit.selectionEnd)
        assertTrue(AutoSpaceTracker.isPending())

        AutoSpaceTracker.markAutoSpace()
        val (midWordSpaceEdit, midWordSpaceIc) = createRealEditor("helo world", 2)
        assertTrue(
            SuggestionButtonHandler.commitSuggestion(
                context = context,
                inputConnection = midWordSpaceIc,
                suggestion = "hello",
                shouldDisableAutoCapitalize = true
            )
        )
        assertEquals("hello world", midWordSpaceEdit.text.toString())
        assertEquals(5, midWordSpaceEdit.selectionStart)
        assertEquals(5, midWordSpaceEdit.selectionEnd)
        assertFalse(AutoSpaceTracker.isPending())

        SettingsManager.setAutoSpaceAfterSuggestion(context, false)
        AutoSpaceTracker.markAutoSpace()
        val (midWordOffEdit, midWordOffIc) = createRealEditor("helo", 2)
        assertTrue(
            SuggestionButtonHandler.commitSuggestion(
                context = context,
                inputConnection = midWordOffIc,
                suggestion = "hello",
                shouldDisableAutoCapitalize = true
            )
        )
        assertEquals("hello", midWordOffEdit.text.toString())
        assertEquals(5, midWordOffEdit.selectionStart)
        assertEquals(5, midWordOffEdit.selectionEnd)
        assertFalse(AutoSpaceTracker.isPending())
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

    private fun commitCommaViaRouter(inputConnection: InputConnection) {
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

    private enum class SpaceCommitMode {
        WRITE_AND_SUCCEED,
        DROP_AND_RETURN_TRUE,
        FAIL_AND_RETURN_FALSE
    }

    private class DelayedReadbackEditorInputConnection(
        context: Context,
        initialBefore: String,
        initialAfter: String = "",
        private val nullReadbackAfterCommit: Boolean = false,
        private val spaceCommitMode: (attempt: Int) -> SpaceCommitMode = { SpaceCommitMode.WRITE_AND_SUCCEED },
        private val handleSpaceKeyEvent: Boolean = true
    ) : BaseInputConnection(View(context), true) {
        private val before = StringBuilder(initialBefore)
        private val after = StringBuilder(initialAfter)
        private var readbackUnavailable = false

        var spaceCommitAttempts: Int = 0
            private set

        var spaceKeyEventCount: Int = 0
            private set

        val text: String
            get() = before.toString() + after.toString()

        fun recoverReadback() {
            readbackUnavailable = false
        }

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? {
            if (readbackUnavailable) return null
            return before.takeLast(n)
        }

        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? {
            if (readbackUnavailable) return null
            return after.take(n)
        }

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
            val committedText = text?.toString().orEmpty()
            if (committedText == " ") {
                spaceCommitAttempts++
                return when (spaceCommitMode(spaceCommitAttempts)) {
                    SpaceCommitMode.WRITE_AND_SUCCEED -> {
                        before.append(" ")
                        if (nullReadbackAfterCommit) {
                            readbackUnavailable = true
                        }
                        true
                    }
                    SpaceCommitMode.DROP_AND_RETURN_TRUE -> {
                        if (nullReadbackAfterCommit) {
                            readbackUnavailable = true
                        }
                        true
                    }
                    SpaceCommitMode.FAIL_AND_RETURN_FALSE -> {
                        if (nullReadbackAfterCommit) {
                            readbackUnavailable = true
                        }
                        false
                    }
                }
            }
            before.append(committedText)
            if (nullReadbackAfterCommit) {
                readbackUnavailable = true
            }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent?): Boolean {
            if (event?.keyCode == KeyEvent.KEYCODE_SPACE) {
                spaceKeyEventCount++
                if (!handleSpaceKeyEvent) {
                    return false
                }
                if (event.action == KeyEvent.ACTION_DOWN) {
                    before.append(" ")
                }
                return true
            }
            return super.sendKeyEvent(event)
        }

        override fun finishComposingText(): Boolean = true
    }
}
