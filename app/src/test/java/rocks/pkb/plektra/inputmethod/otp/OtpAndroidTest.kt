package rocks.pkb.plektra.inputmethod.otp

import android.Manifest
import android.app.Notification
import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import android.provider.Telephony
import android.service.notification.StatusBarNotification
import android.text.InputType
import android.view.KeyEvent
import android.content.ClipData
import android.content.ClipboardManager
import rocks.pkb.plektra.inputmethod.AlternateCharacterManager
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import android.widget.TextView
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.KeyboardBarArrangement
import rocks.pkb.plektra.KeyboardBarConfiguration
import rocks.pkb.plektra.inputmethod.CandidatesBarController
import rocks.pkb.plektra.inputmethod.PhysicalKeyboardInputMethodService
import rocks.pkb.plektra.inputmethod.StatusBarController.StatusSnapshot
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OtpAndroidTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val main get() = shadowOf(Looper.getMainLooper())
    private val runtime get() = OtpRuntime.get(context)

    @After
    fun clear() {
        SettingsManager.setOtpEnabled(context, false)
        main.idle()
    }

    @Test
    fun privateSuggestionsAndAllSourcesAreOptIn() {
        assertFalse(SettingsManager.getOtpEnabled(context))
        assertFalse(SettingsManager.getClipboardPasteSuggestion(context))
        OtpSource.entries.forEach { assertFalse(SettingsManager.getOtpSourceEnabled(context, it)) }
    }

    @Test
    fun sourcesRespectAllToggleAndAccessCombinationsBeforeReadingPayloads() {
        for (mask in 0 until 64) {
            fun enabled(bit: Int) = mask and (1 shl bit) != 0
            SettingsManager.setOtpEnabled(context, false)
            main.idle()
            SettingsManager.setOtpEnabled(context, enabled(0))
            OtpSource.entries.forEachIndexed { i, source -> SettingsManager.setOtpSourceEnabled(context, source, enabled(i + 1)) }
            if (enabled(4)) shadowOf(context).grantPermissions(Manifest.permission.RECEIVE_SMS)
            else shadowOf(context).denyPermissions(Manifest.permission.RECEIVE_SMS)
            notificationAccess(enabled(5))
            OtpSource.entries.forEachIndexed { i, source ->
                val access = when (source) {
                    OtpSource.SMS -> enabled(4)
                    OtpSource.NOTIFICATION -> enabled(5)
                    OtpSource.HELPER -> true
                }
                val expected = enabled(0) && enabled(i + 1) && access
                var read = false
                val code = (410000 + mask * 10 + i).toString()
                runtime.receive(source) { read = true; code }
                assertEquals("mask=$mask source=$source read", expected, read)
                assertEquals("mask=$mask source=$source shown", expected, runtime.inbox.current()?.code == code)
                runtime.inbox.current()?.let { runtime.inbox.use(it) { true } }
            }
        }
    }

    @Test
    fun helperAcceptsOnlyTheContractActionAndShortAsciiCodes() {
        SettingsManager.setOtpEnabled(context, true)
        SettingsManager.setOtpSourceEnabled(context, OtpSource.HELPER, true)
        val rows = listOf("1234" to true, "Ab9k" to true, "1357902468" to true, "abc" to false,
            "12345678901" to false, "12-345" to false, "AB 98" to false, " AB98" to false,
            "１２３４" to false, "1\n234" to false, "aBcDe" to true, null to false, 1234 to false)
        val receiver = OtpHelperReceiver()
        for ((value, valid) in rows) {
            val intent = Intent(OtpHelperReceiver.ACTION).setPackage(context.packageName)
            when (value) {
                is String -> intent.putExtra("code", value)
                is Int -> intent.putExtra("code", value)
            }
            receiver.onReceive(context, Intent(intent).setAction("other.action"))
            assertNull(runtime.inbox.current())
            receiver.onReceive(context, intent)
            assertEquals(if (valid) value else null, runtime.inbox.current()?.code)
            runtime.inbox.current()?.let { runtime.inbox.use(it) { true } }
        }
    }

    @Test
    fun smsReassemblesPartsBeforeExtraction() {
        SettingsManager.setOtpEnabled(context, true)
        SettingsManager.setOtpSourceEnabled(context, OtpSource.SMS, true)
        shadowOf(context).grantPermissions(Manifest.permission.RECEIVE_SMS)
        val intent = Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION).apply {
            putExtra("format", "3gpp")
            putExtra("pdus", arrayOf(smsPart(1, "Doğrulama kodu: 684-"), smsPart(2, "912")))
        }
        assertEquals(listOf("Doğrulama kodu: 684-", "912"),
            Telephony.Sms.Intents.getMessagesFromIntent(intent).map { it?.messageBody })
        OtpSmsReceiver().onReceive(context, intent)
        assertEquals("684912", runtime.inbox.current()?.code)
    }

    private fun smsPart(part: Int, body: String): ByteArray {
        // GSM SMS-DELIVER, no SMSC address, UCS-2, 8-bit concatenation UDH.
        val data = byteArrayOf(5, 0, 3, 11, 2, part.toByte()) + body.toByteArray(Charsets.UTF_16BE)
        return byteArrayOf(0, 0x44, 2, 0x81.toByte(), 0x21, 0, 8, 0x52, 0x10, 0x10, 0, 0, 0, 0,
            data.size.toByte()) + data
    }

    @Suppress("DEPRECATION")
    @Test
    fun notificationTitleAndBodyAreExtractedAndRepostsDeduplicate() {
        SettingsManager.setOtpEnabled(context, true)
        SettingsManager.setOtpSourceEnabled(context, OtpSource.NOTIFICATION, true)
        notificationAccess(true)
        val controller = Robolectric.buildService(OtpNotificationListener::class.java).create()
        try {
            val notification = Notification.Builder(context, "otp-test")
                .setContentTitle("Doğrulama kodu").setContentText("887-765").build()
            val posted = StatusBarNotification("example.bank", "example.bank", 5, null, 0, 0, 0,
                notification, android.os.Process.myUserHandle(), 0)
            controller.get().onNotificationPosted(posted)
            assertEquals("887765", runtime.inbox.current()?.code)
            runtime.inbox.current()?.let { runtime.inbox.use(it) { true } }
            controller.get().onNotificationPosted(posted)
            assertNull(runtime.inbox.current())
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun fieldAndBarMatrixTapOnlyCommitsToCurrentEditorAndClears() {
        configure()
        val ctl = Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java).create()
        try {
            val ime = ctl.get()
            val view = requireNotNull(ime.onCreateInputView())
            val fields = listOf(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD to EditorInfo.IME_ACTION_DONE,
                InputType.TYPE_CLASS_NUMBER to EditorInfo.IME_ACTION_NEXT,
                InputType.TYPE_CLASS_TEXT to EditorInfo.IME_ACTION_NONE,
                InputType.TYPE_CLASS_TEXT to EditorInfo.IME_ACTION_SEARCH
            )
            for ((index, field) in fields.withIndex()) for (populated in listOf(false, true)) {
                SettingsManager.setStatusBarVariationsVisible(context, populated)
                val original = focus(ime, field.first, field.second, index * 2)
                val code = (817220 + index * 2 + if (populated) 1 else 0).toString()
                deliver(code)
                val originalChip = view.chip()!!
                assertEquals(code, (originalChip as TextView).text.toString())
                assertEquals("", original.text.toString())
                ime.onFinishInput()
                assertNull(view.chip())
                originalChip.performClick()
                assertEquals("", original.text.toString())
                val focused = focus(ime, field.first, field.second, index * 2 + 1)
                val focusedChip = view.chip()!!
                focusedChip.performClick()
                assertEquals(code, focused.text.toString())
                assertEquals("", original.text.toString())
                assertNull(view.chip())
                focusedChip.performClick()
                assertEquals(code, focused.text.toString())
                deliver(code)
                assertNull(view.chip())
                ime.onFinishInput()
            }
        } finally { ctl.destroy() }
    }

    @Test
    fun emptyStripShowsCenterActionAndClearsOnReplacementExpiryOrDisable() {
        configure()
        SettingsManager.setStatusBarVariationsVisible(context, false)
        val ctl = Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java).create()
        try {
            val ime = ctl.get()
            val view = requireNotNull(ime.onCreateInputView())
            val editor = focus(ime, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                EditorInfo.IME_ACTION_NONE, 3)
            layout(view)
            assertNull(view.chip())
            deliver("654823")
            layout(view)
            val stale = view.chip()!!
            deliver("654824")
            stale.performClick()
            assertEquals("", editor.text.toString())
            assertEquals("654824", (view.chip() as? TextView)?.text.toString())
            main.idleFor(Duration.ofMinutes(2))
            assertNull(view.chip())
            deliver("654825")
            assertNotNull(view.chip())
            SettingsManager.setOtpSourceEnabled(context, OtpSource.HELPER, false)
            main.idle()
            assertNull(view.chip())
        } finally { ctl.destroy() }
    }

    private enum class Dismiss { HOLD, ENTER, SUGGESTION, VARIATION, SYMBOL, ALT, TIMEOUT, LETTER, DIGIT, PUNCTUATION, SPACE, SOFT_CHAR, SOFT_SPACE, INSERT }

    @Test
    fun otpDismissMatrix() {
        configure()
        SettingsManager.setAutoCapitalizeFirstLetter(context, false)
        OtpSource.entries.forEach { SettingsManager.setOtpSourceEnabled(context, it, true) }
        shadowOf(context).grantPermissions(Manifest.permission.RECEIVE_SMS)
        notificationAccess(true)
        val ctl = Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java).create()
        val failures = mutableListOf<String>()
        var rows = 0
        try {
            val ime = ctl.get()
            val root = requireNotNull(ime.onCreateInputView())
            val bar = ReflectionHelpers.getField<CandidatesBarController>(ime, "candidatesBarController")
            for (exit in Dismiss.entries) for (replay in OtpSource.entries) for (changeField in listOf(false, true)) {
                rows++
                val code = (650000 + rows).toString()
                val type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                val action = EditorInfo.IME_ACTION_NONE
                runCatching {
                    val first = focus(ime, type, action, rows * 3)
                    val receivedAt = android.os.SystemClock.elapsedRealtime()
                    runtime.receive(OtpSource.SMS) { code }
                    val original = checkNotNull(root.chip()) { "SMS chip missing" }
                    check(first.text.isEmpty()) { "arrival typed" }
                    val editor = if (changeField) focus(ime, type, action, rows * 3 + 1) else first
                    val target = checkNotNull(root.chip()) { "pending chip lost on focus" }
                    if (changeField) {
                        original.performClick()
                        check(first.text.isEmpty() && editor.text.isEmpty() && root.chip() === target) { "stale tap" }
                    }
                    layout(root)
                    val expected = when (exit) {
                        Dismiss.HOLD -> { check(target.performLongClick()); "" }
                        Dismiss.ENTER -> { ime.onKeyDown(KeyEvent.KEYCODE_ENTER, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)); editor.text.toString() }
                        Dismiss.SUGGESTION -> { bar.onSuggestionCommitted!!.invoke(); "" }
                        Dismiss.VARIATION -> { bar.onVariationSelectedListener!!.onVariationSelected("é"); "" }
                        Dismiss.SYMBOL -> { bar.onSymTextCommitted!!.invoke(); "" }
                        Dismiss.ALT -> {
                            ReflectionHelpers.getField<AlternateCharacterManager>(ime, "alternateCharacterManager").onAltCharInserted!!.invoke('#'); ""
                        }
                        Dismiss.TIMEOUT -> {
                            // Switching fields can advance Robolectric's clock; don't restart the deadline there.
                            main.idleFor(Duration.ofMillis(receivedAt + 59_999 - android.os.SystemClock.elapsedRealtime()))
                            runtime.receive(replay) { code }
                            check(root.chip() === target) { "expired early or duplicate replaced chip" }
                            main.idleFor(Duration.ofMillis(1)); ""
                        }
                        Dismiss.INSERT -> { target.performClick(); code }
                        Dismiss.SOFT_CHAR, Dismiss.SOFT_SPACE -> {
                            val text = if (exit == Dismiss.SOFT_SPACE) " " else "é"
                            bar.onSoftwareKeyboardTextInput!!.invoke(text, ime.currentInputConnection, StatusSnapshot(
                                false, false, false, false, false, false, false, false, false, false, 0))
                            text
                        }
                        else -> {
                            val (key, text) = when (exit) {
                                Dismiss.SPACE -> KeyEvent.KEYCODE_SPACE to " "
                                Dismiss.DIGIT -> KeyEvent.KEYCODE_7 to "7"
                                Dismiss.PUNCTUATION -> KeyEvent.KEYCODE_PERIOD to "."
                                else -> KeyEvent.KEYCODE_A to "a"
                            }
                            val down = KeyEvent(KeyEvent.ACTION_DOWN, key)
                            val up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP)
                            if (!ime.onKeyDown(key, down)) editor.dispatchKeyEvent(down)
                            if (!ime.onKeyUp(key, up)) editor.dispatchKeyEvent(up)
                            text
                        }
                    }
                    main.idle()
                    check(root.chip() == null) { "still shown after $exit" }
                    target.performClick()
                    check(editor.text.toString() == expected) { "unexpected edit" }
                    runtime.receive(replay) { code }
                    check(root.chip() == null) { "duplicate resurrected" }
                    ime.onFinishInput()
                    main.idleFor(Duration.ofMinutes(3))
                    runtime.receive(replay) { code }
                    focus(ime, type, action, rows * 3 + 2)
                    check(root.chip() == null) { "late replay resurrected on refocus" }
                    deliver((660000 + rows).toString())
                    check(root.chip() != null) { "different code suppressed" }
                }.onFailure { failures += "$exit/$replay/change=$changeField: $it" }
                ime.onFinishInput()
            }
        } finally { ctl.destroy() }
        println("OTP dismiss matrix: $rows rows, ${failures.size} failing")
        assertEquals(84, rows)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun copy(text: String, age: Long = 0, sensitive: Boolean = false) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("text", text).apply {
            if (sensitive) description.extras = android.os.PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        })
        // ShadowClipboardManager stamps copies with uptime; Android uses wall time.
        ReflectionHelpers.callInstanceMethod<Unit>(clipboard.primaryClip!!.description, "setTimestamp",
            ReflectionHelpers.ClassParameter.from(Long::class.javaPrimitiveType, System.currentTimeMillis() - age))
        main.idle()
    }

    private fun clipboardToggle(enabled: Boolean) {
        SettingsManager.setClipboardPasteSuggestion(context, enabled)
        main.idle()
    }

    @Test
    fun clipboardCenterSlotMatrix() {
        configure()
        SettingsManager.setAutoCapitalizeFirstLetter(context, false)
        val ctl = Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java).create()
        val failures = mutableListOf<String>()
        var rows = 0
        try {
            val ime = ctl.get()
            val root = requireNotNull(ime.onCreateInputView())
            val bar = ReflectionHelpers.getField<CandidatesBarController>(ime, "candidatesBarController")
            fun clip() = root.centerLabel { it.startsWith("paste") }
            for (exit in Dismiss.entries) for (beforeFocus in listOf(true, false)) {
                rows++
                runCatching {
                    clipboardToggle(true)
                    val text = "paste${rows} " + "private text ".repeat(8)
                    val type = InputType.TYPE_CLASS_TEXT
                    val action = EditorInfo.IME_ACTION_NONE
                    if (beforeFocus) copy(text)
                    val editor = focus(ime, type, action, rows)
                    if (!beforeFocus) copy(text)
                    val slot = checkNotNull(clip()) { "missing clipboard slot" }
                    check(slot.text.length < text.length && editor.text.isEmpty()) { "not a truncated preview" }
                    when (exit) {
                        Dismiss.HOLD -> check(slot.performLongClick())
                        Dismiss.INSERT -> { slot.performClick(); slot.performClick(); check(editor.text.toString() == text) }
                        Dismiss.TIMEOUT -> main.idleFor(Duration.ofSeconds(60))
                        Dismiss.SYMBOL -> bar.onSymTextCommitted!!.invoke()
                        Dismiss.VARIATION -> bar.onVariationSelectedListener!!.onVariationSelected("é")
                        Dismiss.SUGGESTION -> bar.onSuggestionCommitted!!.invoke()
                        Dismiss.ALT -> ReflectionHelpers.getField<AlternateCharacterManager>(ime, "alternateCharacterManager").onAltCharInserted!!.invoke('#')
                        Dismiss.SOFT_SPACE, Dismiss.SOFT_CHAR -> bar.onSoftwareKeyboardTextInput!!.invoke(
                            if (exit == Dismiss.SOFT_SPACE) " " else "é", ime.currentInputConnection,
                            StatusSnapshot(false, false, false, false, false, false, false, false, false, false, 0))
                        else -> {
                            val key = when (exit) {
                                Dismiss.ENTER -> KeyEvent.KEYCODE_ENTER
                                Dismiss.SPACE -> KeyEvent.KEYCODE_SPACE
                                Dismiss.PUNCTUATION -> KeyEvent.KEYCODE_PERIOD
                                Dismiss.DIGIT -> KeyEvent.KEYCODE_7
                                else -> KeyEvent.KEYCODE_A
                            }
                            ime.onKeyDown(key, KeyEvent(KeyEvent.ACTION_DOWN, key))
                            ime.onKeyUp(key, KeyEvent(KeyEvent.ACTION_UP, key))
                        }
                    }
                    main.idle()
                    check(clip() == null) { "not dismissed" }
                    focus(ime, type, action, rows + 200)
                    check(clip() == null) { "resurrected" }
                }.onFailure { failures += "$exit/beforeFocus=$beforeFocus: $it" }
                ime.onFinishInput()
            }
            for (gate in listOf("disabled", "password", "web password", "visible password", "PIN", "old", "sensitive", "OTP", "toggle off", "switch to password", "near expiry")) {
                rows++
                runCatching {
                    clipboardToggle(gate != "disabled")
                    copy("paste-$rows", when (gate) { "old" -> 60001; "near expiry" -> 58000; else -> 0 }, gate == "sensitive")
                    val type = when (gate) {
                        "password" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        "web password" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                        "visible password" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        "PIN" -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                        else -> InputType.TYPE_CLASS_TEXT
                    }
                    focus(ime, type, EditorInfo.IME_ACTION_DONE, rows)
                    if (gate == "OTP") {
                        checkNotNull(clip()); deliver("991234"); checkNotNull(root.chip())
                        check(clip() == null); root.chip()!!.performLongClick(); checkNotNull(clip())
                    } else {
                        when (gate) {
                            "toggle off" -> { checkNotNull(clip()); clipboardToggle(false) }
                            "switch to password" -> {
                                val stale = checkNotNull(clip())
                                val password = focus(ime, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                                    EditorInfo.IME_ACTION_DONE, rows + 100)
                                stale.performClick(); check(password.text.isEmpty())
                            }
                            "near expiry" -> { checkNotNull(clip()); main.idleFor(Duration.ofSeconds(2)) }
                        }
                        check(clip() == null)
                    }
                }.onFailure { failures += "$gate: $it" }
                ime.onFinishInput()
            }
        } finally { ctl.destroy() }
        println("Clipboard slot matrix: $rows rows, ${failures.size} failing")
        assertEquals(39, rows)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun newest64DismissalsSurviveLateCrossSourceReplays() {
        var now = 0L
        val inbox = OtpInbox({ true }, { now })
        repeat(65) { i ->
            check(inbox.offer(OtpSource.HELPER, (710000 + i).toString()))
            inbox.dismiss(inbox.current()!!)
        }
        now += 300_000
        for (i in 1..64) assertFalse(inbox.offer(OtpSource.NOTIFICATION, (710000 + i).toString()))
        assertTrue("oldest fingerprint should be evicted", inbox.offer(OtpSource.SMS, "710000"))
    }

    private enum class Exit { TAP, EXPIRY, REPLACEMENT, FIELD_CHANGE, FINISH }

    @Test
    fun barReturnsToNoChipGeometryAcrossEditorLifecycles() {
        val flags = listOf(false, true)
        val fields = listOf(InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_TEXT,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        val failures = mutableListOf<String>()
        var rows = 0
        configure()
        for (populated in flags) for (suggestions in flags) for (shared in flags) for (strip in flags) {
            KeyboardBarConfiguration.setArrangement(context, if (shared) KeyboardBarArrangement.SHARED else KeyboardBarArrangement.SUGGESTIONS_FIRST)
            SettingsManager.setStatusBarVariationsVisible(context, populated)
            SettingsManager.setStaticVariationBarModeEnabled(context, populated)
            SettingsManager.setSuggestionsEnabled(context, suggestions)
            SettingsManager.setModifierIndicators(context,
                if (strip) setOf(SettingsManager.MODIFIER_INDICATOR_BOTTOM_STRIP) else emptySet())
            val ctl = Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java).create()
            try {
                val ime = ctl.get()
                val bar = requireNotNull(ime.onCreateInputView()) as ViewGroup
                for (field in fields) for (exit in Exit.entries) {
                    rows++
                    fun exercise(withChip: Boolean): List<Int> {
                        val id = rows * 4 + if (withChip) 2 else 0
                        val action = if (field == fields.last()) EditorInfo.IME_ACTION_SEND else EditorInfo.IME_ACTION_DONE
                        val code = (514000 + rows * 2).toString()
                        val editor = focus(ime, field, action, id)
                        val before = geometry(bar)
                        if (withChip) {
                            deliver(code)
                            val during = geometry(bar)
                            if (before.first() == View.VISIBLE) {
                                // The strip can open, but must not displace another visible row.
                                check(before.drop(4).zip(during.drop(4)).none { (old, new) -> old > 0 && new <= 0 })
                            }
                        }
                        val first = bar.chip()
                        check(!withChip || first != null) { "chip not shown" }
                        when (exit) {
                            Exit.TAP -> {
                                if (withChip) first!!.performClick() else ime.currentInputConnection.commitText(code, 1)
                                check(editor.text.toString() == code) { "insert failed" }
                            }
                            Exit.REPLACEMENT -> {
                                if (withChip) {
                                    deliver((code.toInt() + 1).toString())
                                    check(bar.chip() !== first && bar.chip() != null) { "not replaced" }
                                    first!!.performClick()
                                    check(editor.text.isEmpty()) { "stale click inserted" }
                                }
                                main.idleFor(Duration.ofMinutes(2))
                            }
                            Exit.EXPIRY -> main.idleFor(Duration.ofMinutes(2))
                            Exit.FIELD_CHANGE -> {
                                focus(ime, if (field == fields.first()) fields.last() else fields.first(), action, id + 1)
                                check(!withChip || bar.chip() != null) { "pending chip lost on focus" }
                                main.idleFor(Duration.ofMinutes(2))
                            }
                            Exit.FINISH -> {
                                ime.onFinishInput()
                                check(bar.chip() == null) { "chip remained after finish" }
                                main.idleFor(Duration.ofMinutes(2))
                                focus(ime, field, action, id)
                            }
                        }
                        check(bar.chip() == null) { "chip remained after exit" }
                        return geometry(bar).also { ime.onFinishInput() }
                    }
                    val error = runCatching {
                        val expected = exercise(false)
                        val actual = exercise(true)
                        check(expected == actual) { "expected $expected, got $actual" }
                    }.exceptionOrNull()
                    if (error != null) failures += "populated=$populated suggestions=$suggestions shared=$shared " +
                        "strip=$strip field=$field $exit: $error"
                }
            } finally { ctl.destroy() }
        }
        println("OTP bar lifecycle matrix: $rows rows, ${failures.size} failing")
        assertEquals(320, rows)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    // Compare the actual settled chrome and each row (including suggestions, actions and LEDs),
    // not just the absence of a TextView. A GONE parent or a stale zero-height row matters.
    private fun geometry(bar: ViewGroup): List<Int> {
        layout(bar)
        main.idleFor(Duration.ofMillis(600))
        layout(bar)
        if (bar.visibility == View.GONE) return listOf(View.GONE, bar.layoutParams.height)
        return listOf(bar.visibility, bar.layoutParams?.height ?: -2, bar.height, bar.clipBounds?.height() ?: -1) +
            (0 until bar.childCount).map { bar.getChildAt(it).let { v -> if (v.visibility == View.VISIBLE) v.height else -1 } }
    }

    private fun notificationAccess(granted: Boolean) {
        shadowOf(context.getSystemService(android.app.NotificationManager::class.java))
            .setNotificationListenerAccessGranted(ComponentName(context, OtpNotificationListener::class.java), granted)
    }

    private fun configure() {
        SettingsManager.setSoftwareKeyboardMode(context, SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE)
        SettingsManager.setModifierIndicators(context, emptySet())
        SettingsManager.setSuggestionsEnabled(context, false)
        SettingsManager.setOtpEnabled(context, true)
        SettingsManager.setOtpSourceEnabled(context, OtpSource.HELPER, true)
    }

    private fun focus(ime: PhysicalKeyboardInputMethodService, type: Int, action: Int, id: Int): EditText {
        val info = EditorInfo().apply {
            inputType = type
            imeOptions = action
            fieldId = id
            packageName = if (action == EditorInfo.IME_ACTION_SEND) "com.whatsapp" else "test.editor"
        }
        val editor = EditText(context).apply { inputType = type }
        val connection = object : InputConnectionWrapper(editor.onCreateInputConnection(EditorInfo()), false) {
            override fun performEditorAction(editorAction: Int): Boolean = error("OTP must not submit")
            override fun sendKeyEvent(event: android.view.KeyEvent): Boolean = error("OTP must not send key events")
        }
        ReflectionHelpers.setField(ime, "mInputEditorInfo", info)
        ReflectionHelpers.setField(ime, "mStartedInputConnection", connection)
        ime.onStartInput(info, false)
        ime.onStartInputView(info, false)
        main.idle()
        return editor
    }

    private fun deliver(code: String) {
        OtpHelperReceiver().onReceive(context, Intent(OtpHelperReceiver.ACTION).setPackage(context.packageName).putExtra("code", code))
        main.idle()
    }

    private fun layout(view: View) {
        view.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun View.chip(): View? = centerLabel { it.matches(Regex("[0-9]{6}")) }

    private fun View.centerLabel(matches: (String) -> Boolean): TextView? {
        if (visibility != View.VISIBLE || this !is ViewGroup) return null
        val middle = getChildAt(1) as? TextView
        if (childCount == 3 && middle?.visibility == View.VISIBLE && matches(middle.text.toString())) return middle
        return (0 until childCount).firstNotNullOfOrNull { getChildAt(it).centerLabel(matches) }
    }
}
