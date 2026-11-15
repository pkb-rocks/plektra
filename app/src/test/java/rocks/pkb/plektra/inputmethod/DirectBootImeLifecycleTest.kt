package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Looper
import android.os.UserManager
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowInputMethodManager
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.core.InputContextState
import rocks.pkb.plektra.data.layout.LayoutFileStore
import rocks.pkb.plektra.data.layout.LayoutMappingRepository
import rocks.pkb.plektra.inputmethod.aospkeyboard.AospKeyboardView
import java.io.File
import java.lang.reflect.Proxy

/**
 * The real IME lifecycle before the first unlock after boot: only the device-protected snapshot
 * is readable, credential-protected storage is rejected like on Android, and unlock hands the
 * running service over to normal operation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [CredentialStorageLockShadow::class, DirectBootImeLifecycleTest.SubtypeShadow::class])
class DirectBootImeLifecycleTest {
    @Implements(InputMethodManager::class)
    class SubtypeShadow : ShadowInputMethodManager() {
        companion object {
            var enabled = emptyList<InputMethodSubtype>()
        }

        @Implementation
        protected fun getEnabledInputMethodSubtypeList(info: InputMethodInfo, implicit: Boolean): List<InputMethodSubtype> = enabled

        @Implementation
        protected fun setInputMethodAndSubtype(token: IBinder, id: String, subtype: InputMethodSubtype) {
            setCurrentInputMethodSubtype(subtype)
        }

        @Implementation
        protected fun setAdditionalInputMethodSubtypes(id: String, subtypes: Array<InputMethodSubtype>) = Unit
    }

    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val users get() = shadowOf(app.getSystemService(UserManager::class.java))
    private val imm get() = app.getSystemService(InputMethodManager::class.java)
    private val customSubtype = subtype("ru_RU", CUSTOM, additional = true)
    private val germanSubtype = subtype("de_DE", "qwertz")
    private var controller: ServiceController<PhysicalKeyboardInputMethodService>? = null
    private lateinit var service: PhysicalKeyboardInputMethodService
    private val committed = mutableListOf<String>()

    @Before
    fun setUp() {
        DirectBootStorage.resetForTesting()
        users.setUserUnlocked(true)
        CredentialStorageLockShadow.violations.clear()
        DeviceSpecific.clearTestOverrides()
        val prefs = SettingsManager.getPreferences(app)
        prefs.edit().clear().commit()
        assertTrue(LayoutFileStore.saveLayoutFromJson(app, CUSTOM, CUSTOM_JSON) is LayoutFileStore.LayoutImportResult.Success)
        SettingsManager.setAutoCapitalizeFirstLetter(app, false)
        SettingsManager.setAutoCapitalizeAfterPeriod(app, false)
        SettingsManager.setCtrlSpaceLayoutSwitchEnabled(app, true)
        SettingsManager.setToastOnLayoutSwitchEnabled(app, false)
        SettingsManager.setKeyboardLayout(app, CUSTOM)
        prefs.edit()
            .putString("snippets_v1", PRIVATE_SNIPPET)
            .putString("auto_correct_custom_de", PRIVATE_REPLACEMENT)
            .putString("auto_correct_custom_ru", "{\"йц\": \"$PRIVATE_REPLACEMENT\"}")
            .putString("auto_correct_enabled_languages", "ru")
            .putStringSet(SettingsManager.KEY_KEYBOARD_BAR_HIDDEN_APPS, setOf(PRIVATE_APP))
            .putBoolean("experimental_suggestions_enabled", true)
            .commit()
        shadowOf(imm).setInputMethodInfoList(
            listOf(InputMethodInfo(app.packageName, PhysicalKeyboardInputMethodService::class.java.name, "Plektra", null))
        )
        SubtypeShadow.enabled = listOf(customSubtype, germanSubtype)
        shadowOf(imm).setCurrentInputMethodSubtype(customSubtype)
        // Written by the IME's first start after installation, before any reboot.
        SettingsManager.initializeNavModeMappingsFile(app)
        DirectBootKeyboardConfig.awaitPendingRefresh()
    }

    @After
    fun tearDown() {
        try {
            controller?.destroy()
        } finally {
            users.setUserUnlocked(true)
            DirectBootStorage.resetForTesting()
            DeviceSpecific.clearTestOverrides()
        }
    }

    @Test
    fun lockedServiceTypesWithLastCustomLayoutOnHardwareAndScreenWithoutCredentialAccess() {
        lockAfterReboot()
        startService()

        type(KeyEvent.KEYCODE_Q)
        type(KeyEvent.KEYCODE_W)
        time += 200
        service.onKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent(time, time, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 0))
        type(KeyEvent.KEYCODE_Q, KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON)
        service.onKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent(time, time + 400, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 0))
        assertEquals(listOf("й", "ц", "Й"), committed)
        committed.clear()
        type(KeyEvent.KEYCODE_E, KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON)
        assertEquals(listOf("€"), committed)

        val screen = AospKeyboardView(service).apply { layoutName = CUSTOM }
        assertEquals("й", screen.profileMappingForText("q")?.lowercase)
        val bar = field("candidatesBarController") as CandidatesBarController
        committed.clear()
        assertTrue(bar.onSoftwareKeyboardTextInput!!.invoke("ц", connection, StatusBarController.StatusSnapshot(
            capsLockEnabled = false, shiftPhysicallyPressed = false, shiftOneShot = false,
            ctrlLatchActive = false, ctrlPhysicallyPressed = false, ctrlOneShot = false,
            ctrlLatchFromNavMode = false, altLatchActive = false, altPhysicallyPressed = false,
            altOneShot = false, symPage = 0, activeKeyboardLayoutName = CUSTOM)))
        assertEquals(listOf("ц"), committed)

        assertEquals(emptyList<String>(), CredentialStorageLockShadow.violations)
        // Robolectric hands the service the application's base; on Android it gets its own one.
        assertTrue(service.baseContext is DirectBootStorageContext)
        assertFalse(field("clipboardHistoryStarted") as Boolean)
        assertFalse(SettingsManager.isExperimentalSuggestionsEnabled(service))
        assertEquals(null, SettingsManager.getPreferences(service).getString("snippets_v1", null))
    }

    @Test
    fun lockedServiceSwitchesBetweenAllConfiguredLayouts() {
        lockAfterReboot()
        startService()

        ctrlSpace()
        assertEquals(germanSubtype, imm.currentInputMethodSubtype)
        subtypeChanged(germanSubtype)
        type(KeyEvent.KEYCODE_Y)
        assertEquals("z", committed.last())

        ctrlSpace()
        assertEquals(customSubtype, imm.currentInputMethodSubtype)
        subtypeChanged(customSubtype)
        type(KeyEvent.KEYCODE_Q)
        assertEquals("й", committed.last())
        assertEquals(emptyList<String>(), CredentialStorageLockShadow.violations)
    }

    @Test
    fun lockedBoundaryKeysOnHardwareAndScreenStartNoDictionaryAndWriteNothing() {
        lockAfterReboot()
        val before = deviceInventory()
        startService()

        type(KeyEvent.KEYCODE_Q)
        type(KeyEvent.KEYCODE_W)
        type(KeyEvent.KEYCODE_SPACE)
        type(KeyEvent.KEYCODE_Q)
        type(KeyEvent.KEYCODE_PERIOD)
        type(KeyEvent.KEYCODE_ENTER)
        type(KeyEvent.KEYCODE_DEL)
        val bar = field("candidatesBarController") as CandidatesBarController
        type(KeyEvent.KEYCODE_W)
        listOf(" ", ",", "\n").forEach { text ->
            bar.onSoftwareKeyboardTextInput!!.invoke(text, connection, StatusBarController.StatusSnapshot(
                capsLockEnabled = false, shiftPhysicallyPressed = false, shiftOneShot = false,
                ctrlLatchActive = false, ctrlPhysicallyPressed = false, ctrlOneShot = false,
                ctrlLatchFromNavMode = false, altLatchActive = false, altPhysicallyPressed = false,
                altOneShot = false, symPage = 0, activeKeyboardLayoutName = CUSTOM))
            shadowOf(Looper.getMainLooper()).idle()
        }

        // Backspace deletes through the connection; no text replacement before unlock.
        assertEquals("йц й.\nц ,\n", committed.joinToString(""))
        val suggestions = field("suggestionController")!!
        assertEquals(null, suggestions.javaClass.getDeclaredField("currentLoadJob").apply { isAccessible = true }.get(suggestions))
        val after = deviceInventory()
        assertEquals("device-protected storage unchanged", emptySet<String>(),
            (before.keys + after.keys).filter { before[it] != after[it] }.toSet())
        assertEquals(emptyList<String>(), CredentialStorageLockShadow.violations)

        unlock()
        committed.clear()
        type(KeyEvent.KEYCODE_Q)
        type(KeyEvent.KEYCODE_W)
        type(KeyEvent.KEYCODE_SPACE)
        assertTrue("text replacement after unlock: $committed", committed.joinToString("").contains(PRIVATE_REPLACEMENT))
    }

    @Test
    fun lockedServiceUsesALocaleMappingWrittenForAnInactiveLocale() {
        val french = subtype("fr_FR", layout = null)
        SubtypeShadow.enabled = listOf(customSubtype, french)
        val prefs = SettingsManager.getPreferences(app)
        assertEquals(null, prefs.getString("input_style_suggestion_locales", null))
        val markerBefore = prefs.all[SettingsManager.KEY_KEYBOARD_LAYOUT_AUTO_MAPPING_UPDATED]
        // Written in the settings while ru_RU is active: no IME marker for fr_FR.
        rocks.pkb.plektra.updateLocaleLayoutMapping(app, "fr_FR", CUSTOM)
        assertEquals(markerBefore, prefs.all[SettingsManager.KEY_KEYBOARD_LAYOUT_AUTO_MAPPING_UPDATED])
        assertEquals(null, prefs.getString("input_style_suggestion_locales", null))
        DirectBootKeyboardConfig.awaitPendingRefresh()

        lockAfterReboot()
        startService()
        ctrlSpace()
        assertEquals(french, imm.currentInputMethodSubtype)
        subtypeChanged(french)
        type(KeyEvent.KEYCODE_Q)
        assertEquals("й", committed.last())
        assertEquals(emptyList<String>(), CredentialStorageLockShadow.violations)
    }

    @Test
    fun unlockHandsTheRunningServiceOverOnceAndRestoresCredentialSettings() {
        lockAfterReboot()
        startService()
        // Written before unlock: stays in memory and never reaches device-protected storage.
        SettingsManager.getPreferences(service).edit().putString("snippets_v1", "typed before unlock").commit()

        unlock()
        unlock()
        service.onStartInput(editorInfo(), false)

        assertTrue(field("clipboardHistoryStarted") as Boolean)
        assertTrue(SettingsManager.isExperimentalSuggestionsEnabled(service))
        assertEquals(PRIVATE_SNIPPET, SettingsManager.getPreferences(service).getString("snippets_v1", null))
        committed.clear()
        type(KeyEvent.KEYCODE_Q)
        assertEquals(listOf("й"), committed)
        // Listeners moved to credential storage: a later setting change still reaches the service.
        SettingsManager.getPreferences(app).edit().putBoolean("shift_tap_latches", true).commit()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue((field("modifierStateController") as rocks.pkb.plektra.core.ModifierStateController).shiftTapLatches)
        assertEquals(emptyList<String>(), CredentialStorageLockShadow.violations)
        assertNoPrivateContentInDeviceStorage("typed before unlock")
    }

    /** Default installation without a nav mode file: the locked start reads the assets and writes nothing. */
    @Test
    fun lockedStartWithoutNavModeFileLeavesThePublishedGenerationByteIdentical() {
        assertTrue(SettingsManager.getNavModeMappingsFile(app).delete())
        assertTrue(DirectBootKeyboardConfig.refreshNow(app))
        lockAfterReboot()
        val before = deviceBytes()
        assertFalse(before.keys.any { it.endsWith("ctrl_key_mappings.json") })

        startService()
        type(KeyEvent.KEYCODE_Q)
        type(KeyEvent.KEYCODE_SPACE)
        type(KeyEvent.KEYCODE_W)
        type(KeyEvent.KEYCODE_DEL)

        assertEquals(listOf("й", " ", "ц"), committed)
        val after = deviceBytes()
        assertEquals(before.keys, after.keys)
        before.forEach { (path, bytes) -> assertTrue("$path unchanged", bytes.contentEquals(after[path])) }
        assertEquals(emptyList<String>(), CredentialStorageLockShadow.violations)

        unlock()
        assertTrue("created after unlock", SettingsManager.getNavModeMappingsFile(app).isFile)
    }

    /** A layout switched before unlock stays after unlock, also when the subtype does not name one. */
    @Test
    fun layoutSwitchedBeforeUnlockIsKeptWithItsSubtypeAfterUnlock() {
        val french = subtype("fr_FR", layout = null)
        SubtypeShadow.enabled = listOf(customSubtype, french)
        SettingsManager.setKeyboardLayoutAutoByLocale(app, false)
        lockAfterReboot()
        startService()

        ctrlSpace()
        assertEquals(french, imm.currentInputMethodSubtype)
        subtypeChanged(french)
        type(KeyEvent.KEYCODE_Q)
        assertEquals("a", committed.last())

        unlock()
        service.onStartInput(editorInfo(), false)
        committed.clear()
        type(KeyEvent.KEYCODE_Q)
        assertEquals(listOf("a"), committed)
        assertEquals(french, imm.currentInputMethodSubtype)
        assertEquals("azerty", SettingsManager.getKeyboardLayout(app))
        val suggestions = field("suggestionController")!!
        assertEquals("fr", (suggestions.javaClass.getDeclaredField("currentLocale").apply { isAccessible = true }
            .get(suggestions) as java.util.Locale).language)
        DirectBootKeyboardConfig.awaitPendingRefresh()
        assertEquals("azerty", DirectBootKeyboardConfig(app).read()["keyboard_layout"])
        assertEquals(emptyList<String>(), CredentialStorageLockShadow.violations)
    }

    /** A keypad that was open for a field at the reboot is closed before and after unlock. */
    @Test
    fun fieldScopedKeypadEndsWithTheRestartBeforeAndAfterUnlock() {
        SettingsManager.setSoftwareKeyboardMode(app, SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE)
        SettingsManager.beginFieldScopedVirtualKeyboard(app)
        DirectBootKeyboardConfig.awaitPendingRefresh()
        assertEquals("force_virtual", DirectBootKeyboardConfig(app).read()[SettingsManager.KEY_SOFTWARE_KEYBOARD_MODE_RUNTIME_OVERRIDE])
        lockAfterReboot()
        startService()

        assertEquals(SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE, SettingsManager.resolveEffectiveSoftwareKeyboardMode(service))
        unlock()
        assertEquals(SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE, SettingsManager.resolveEffectiveSoftwareKeyboardMode(service))
        assertFalse(SettingsManager.getPreferences(app).contains("field_scoped_virtual_previous_override"))
        assertEquals(null, SettingsManager.getSoftwareKeyboardModeRuntimeOverride(app))
        assertEquals(emptyList<String>(), CredentialStorageLockShadow.violations)
    }

    /** Switching the storage notifies no listener: the handoff moves the surface to the credential mode. */
    @Test
    fun handoffSwitchesTheSurfaceWhenTheCredentialModeDiffersFromTheLockedOne() {
        SettingsManager.setSoftwareKeyboardMode(app, SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE)
        DirectBootKeyboardConfig.awaitPendingRefresh()
        lockAfterReboot()
        startService()
        // Switched to the on-screen keyboard before unlock; only memory holds that.
        SettingsManager.setSoftwareKeyboardModeRuntimeOverride(service, SettingsManager.SoftwareKeyboardMode.FORCE_VIRTUAL)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        assertEquals(null, field("pendingKeyboardSurfaceTransition"))

        users.setUserUnlocked(true)
        DirectBootStorage.completeUnlock(service)
        assertEquals(SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE, SettingsManager.resolveEffectiveSoftwareKeyboardMode(service))
        assertTrue("surface switches back to hardware", field("pendingKeyboardSurfaceTransition") != null)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        assertEquals(null, field("pendingKeyboardSurfaceTransition"))
        committed.clear()
        type(KeyEvent.KEYCODE_Q)
        assertEquals(listOf("й"), committed)
        assertEquals(emptyList<String>(), CredentialStorageLockShadow.violations)
    }

    /** Static and email variations cached from the snapshot before unlock follow the credential file afterwards. */
    @Test
    fun handoffReplacesStaticAndEmailVariationsCachedFromTheSnapshot() {
        val variations = SettingsManager.getVariationsFile(app)
        DirectBootKeyboardConfig.changeSources(app) {
            variations.writeText("""{"staticVariations": ["◆"], "emailVariations": ["@old"]}""")
        }
        // Changed in credential storage after the last publication.
        variations.writeText("""{"staticVariations": ["★"], "emailVariations": ["@new"]}""")
        lockAfterReboot()
        startService()
        val bar = field("candidatesBarController")!!.member("inputStatusBar")!!.member("variationBarView")
            as rocks.pkb.plektra.inputmethod.ui.VariationBarView
        assertEquals(listOf("◆"), bar.resolveStaticVariations(statusSnapshot(email = false)))
        assertEquals(listOf("@old"), bar.resolveStaticVariations(statusSnapshot(email = true)))

        unlock()

        assertEquals(listOf("★"), bar.resolveStaticVariations(statusSnapshot(email = false)))
        assertEquals(listOf("@new"), bar.resolveStaticVariations(statusSnapshot(email = true)))
        assertEquals(emptyList<String>(), CredentialStorageLockShadow.violations)
    }

    @Test
    fun serviceDestroyedBeforeUnlockLeavesNoHandoffBehind() {
        lockAfterReboot()
        startService()
        controller!!.destroy()
        controller = null

        unlock()

        assertEquals(emptyList<String>(), CredentialStorageLockShadow.violations)
        assertFalse(field("clipboardHistoryStarted") as Boolean)
    }

    @Test
    fun serviceStartedAfterUnlockRunsNormallyWithoutHandoff() {
        startService()
        assertTrue(field("clipboardHistoryStarted") as Boolean)
        type(KeyEvent.KEYCODE_Q)
        assertEquals(listOf("й"), committed)
    }

    private fun lockAfterReboot() {
        assertNoPrivateContentInDeviceStorage()
        users.setUserUnlocked(false)
        DirectBootStorage.resetForTesting()
        // A new process: nothing loaded from credential storage yet.
        LayoutMappingRepository.loadLayout(app.assets, "qwerty")
        AutoCorrector.loadCorrections(app.assets)
    }

    private fun deviceBytes(): Map<String, ByteArray> {
        val root = app.createDeviceProtectedStorageContext().dataDir
        return root.walkTopDown().filter(File::isFile).associate { it.relativeTo(root).path to it.readBytes() }
    }

    private fun deviceInventory(): Map<String, String> {
        val root = app.createDeviceProtectedStorageContext().dataDir
        return root.walkTopDown().filter(File::isFile).associate { it.relativeTo(root).path to it.readText() }
    }

    private fun unlock() {
        users.setUserUnlocked(true)
        app.sendBroadcast(Intent(Intent.ACTION_USER_UNLOCKED))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private lateinit var connection: InputConnection

    private fun editorInfo() = EditorInfo().apply {
        inputType = InputType.TYPE_CLASS_TEXT
        packageName = "rocks.pkb.plektra.test"
    }

    private fun startService() {
        controller = Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java).create()
        service = controller!!.get()
        connection = recordingConnection()
        val info = editorInfo()
        setField("mInputConnection", connection)
        setField("mStartedInputConnection", connection)
        setField("mInputEditorInfo", info)
        service.window.window!!.attributes = service.window.window!!.attributes.apply { token = android.os.Binder() }
        service.setInputView(service.onCreateInputView())
        service.onStartInput(info, false)
        service.onStartInputView(info, false)
        setField("isInputViewActive", true)
        setField("inputContextState", InputContextState.fromEditorInfo(info))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun recordingConnection(): InputConnection = Proxy.newProxyInstance(
        InputConnection::class.java.classLoader, arrayOf(InputConnection::class.java)
    ) { _, method, args ->
        when {
            method.name == "commitText" -> { committed += args!![0].toString(); true }
            method.name == "getTextBeforeCursor" -> committed.joinToString("")
            method.name == "getTextAfterCursor" -> ""
            method.returnType == Boolean::class.javaPrimitiveType -> true
            method.returnType == Int::class.javaPrimitiveType -> 0
            else -> null
        }
    } as InputConnection

    private var time = 10_000L

    private fun type(keyCode: Int, meta: Int = 0) {
        time += 200
        service.onKeyDown(keyCode, KeyEvent(time, time, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        service.onKeyUp(keyCode, KeyEvent(time, time + 50, KeyEvent.ACTION_UP, keyCode, 0, meta))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun ctrlSpace() {
        type(KeyEvent.KEYCODE_SPACE, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(350))
    }

    /** Device-protected storage holds only the snapshot: no private value, before or after unlock. */
    private fun assertNoPrivateContentInDeviceStorage(vararg extra: String) {
        val root = app.createDeviceProtectedStorageContext().dataDir
        val contents = root.walkTopDown().filter(File::isFile).joinToString("\n") { it.readText() }
        (listOf(PRIVATE_SNIPPET, PRIVATE_REPLACEMENT, PRIVATE_APP) + extra).forEach { secret ->
            assertFalse("$secret leaked into device-protected storage", contents.contains(secret))
        }
        assertTrue("snapshot keeps the custom layout", contents.contains("\"$CUSTOM\""))
    }

    private fun subtype(locale: String, layout: String?, additional: Boolean = false) =
        InputMethodSubtype.InputMethodSubtypeBuilder()
            .setSubtypeLocale(locale).setSubtypeMode("keyboard")
            .setSubtypeExtraValue(listOfNotNull(layout?.let { "KeyboardLayoutSet=$it" }, "isAdditionalSubtype".takeIf { additional })
                .joinToString(",")).build()

    private fun subtypeChanged(subtype: InputMethodSubtype) {
        PhysicalKeyboardInputMethodService::class.java
            .getDeclaredMethod("onCurrentInputMethodSubtypeChanged", InputMethodSubtype::class.java)
            .apply { isAccessible = true }.invoke(service, subtype)
    }

    private fun statusSnapshot(email: Boolean) = StatusBarController.StatusSnapshot(
        capsLockEnabled = false, shiftPhysicallyPressed = false, shiftOneShot = false,
        ctrlLatchActive = false, ctrlPhysicallyPressed = false, ctrlOneShot = false,
        ctrlLatchFromNavMode = false, altLatchActive = false, altPhysicallyPressed = false,
        altOneShot = false, symPage = 0, activeKeyboardLayoutName = CUSTOM, isEmailField = email)

    private fun Any.member(name: String): Any? = javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this)

    private fun field(name: String): Any? = findField(name).get(service)

    private fun setField(name: String, value: Any?) = findField(name).set(service, value)

    private fun findField(name: String): java.lang.reflect.Field {
        var cls: Class<*>? = service.javaClass
        while (cls != null) {
            try {
                return cls.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        error(name)
    }

    private companion object {
        const val CUSTOM = "bfu_custom_cyrillic"
        const val PRIVATE_SNIPPET = "PRIVATE-SNIPPET-7f3a"
        const val PRIVATE_REPLACEMENT = "PRIVATE-REPLACEMENT-91c2"
        const val PRIVATE_APP = "com.private.banking.example"
        val CUSTOM_JSON = """
            {"name": "BFU Custom", "mappings": {
              "KEYCODE_Q": {"lowercase": "й", "uppercase": "Й"},
              "KEYCODE_W": {"lowercase": "ц", "uppercase": "Ц"},
              "KEYCODE_E": {"lowercase": "у", "uppercase": "У", "alt": "€"}
            }}
        """.trimIndent()
    }
}
