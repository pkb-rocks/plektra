package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import android.os.Looper
import android.os.UserManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.backup.BackupManager
import rocks.pkb.plektra.backup.BackupResult
import rocks.pkb.plektra.backup.RestoreManager
import rocks.pkb.plektra.backup.RestoreResult
import rocks.pkb.plektra.data.emoji.EmojiRendering
import rocks.pkb.plektra.data.emoji.EmojiRepository
import rocks.pkb.plektra.data.layout.LayoutFileStore
import rocks.pkb.plektra.migrateLegacyGermanSystemLayoutMapping
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DirectBootKeyboardConfigTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val users get() = shadowOf(context.getSystemService(UserManager::class.java))
    private val deviceRoot get() = context.createDeviceProtectedStorageContext().dataDir
    private val mirror get() = DirectBootKeyboardConfig(context).lockedFilesDir()
    private val credentialBase: Context get() {
        var base: Context = context
        while (base is ContextWrapper) base = base.baseContext
        return base
    }

    @Before
    fun setUp() {
        DirectBootStorage.resetForTesting()
        users.setUserUnlocked(true)
        SettingsManager.getPreferences(context).edit().clear().commit()
        DirectBootKeyboardConfig.awaitPendingRefresh()
    }

    @After
    fun tearDown() {
        DirectBootKeyboardConfig.faultInjector = null
        users.setUserUnlocked(true)
        DirectBootStorage.resetForTesting()
        DirectBootKeyboardConfig.awaitPendingRefresh()
    }

    @Test
    fun snapshotCopiesOnlyInputSettingsAndLayoutFilesAndPreservesCredentialData() {
        val source = SettingsManager.getPreferences(context)
        source.edit().putString("keyboard_layout", "private_custom_layout")
            .putString("physical_keyboard_profile_override", "Q25")
            .putBoolean("shift_tap_latches", true)
            .putLong("long_press_threshold", 420L)
            .putLong("variations_updated", 7L)
            .putFloat("keyboard_bar_text_scale", 1.25f)
            .putBoolean("software_keyboard_number_row_enabled", false)
            .putString("clicks_alt_button_mode", "sym")
            .putString("sym_mappings_custom", "{\"KEYCODE_Q\":\"!\"}")
            // Unknown keys of mirrored families, wrong types and values outside a known set stay behind.
            .putString("software_keyboard_private_note", "$SECRET note")
            .putStringSet("software_keyboard_test_set", setOf("$SECRET set"))
            .putString("bounce_keys_private", "$SECRET bounce")
            .putString("nav_mode_private", "$SECRET nav")
            .putString("clicks_button_mode", "$SECRET mode")
            .putString("software_keyboard_number_row_enabled_text", "$SECRET text")
            .putString("long_press_modifier_note", "$SECRET modifier")
            .putString("snippets_v1", "private text").putString("speech_api_key", "secret")
            .putString("clipboard_history", "private clipboard").putString("auto_correct_custom_de", "private words")
            .putStringSet(SettingsManager.KEY_KEYBOARD_BAR_HIDDEN_APPS, setOf("com.private.app"))
            .putString("launcher_shortcuts", "private launcher").commit()
        val before = source.all.toMap()
        val ceFiles = context.filesDir
        File(ceFiles, "variations.json").writeText("{\"variations\":{}}")
        File(ceFiles, "private_notes.txt").writeText("private file")
        File(ceFiles, "typing_sounds").mkdirs()
        File(ceFiles, "typing_sounds/custom.ogg").writeText("private sound")
        assertTrue(LayoutFileStore.saveLayoutFromJson(context, "private_custom_layout", LAYOUT_JSON)
            is LayoutFileStore.LayoutImportResult.Success)
        // Earlier unversioned copies and stray files are purged.
        val legacyStore = context.createDeviceProtectedStorageContext()
            .getSharedPreferences(DirectBootKeyboardConfig.PREFS_NAME, Context.MODE_PRIVATE)
        legacyStore.edit().putString("snippets", "stale secret").commit()
        File(deviceRoot, "files/direct_boot_files/stale.json").apply { parentFile!!.mkdirs(); writeText("stale secret") }

        assertTrue(DirectBootKeyboardConfig(context).refresh(source, ceFiles))

        val snapshot = DirectBootKeyboardConfig(context).read()
        assertEquals("private_custom_layout", snapshot["keyboard_layout"])
        assertEquals("Q25", snapshot["physical_keyboard_profile_override"])
        assertEquals(true, snapshot["shift_tap_latches"])
        assertEquals(420L, snapshot["long_press_threshold"])
        assertEquals(7L, snapshot["variations_updated"])
        assertEquals(1.25f, snapshot["keyboard_bar_text_scale"])
        assertEquals(false, snapshot["software_keyboard_number_row_enabled"])
        assertEquals("sym", snapshot["clicks_alt_button_mode"])
        assertEquals("{\"KEYCODE_Q\":\"!\"}", snapshot["sym_mappings_custom"])
        assertFalse(snapshot.containsKey("clicks_button_mode"))
        assertNoneInDeviceStorage("secret", "private text", "private clipboard", "private words", "com.private.app",
            "private launcher", "private file", "private sound", SECRET)
        assertEquals(before, source.all)
        val mirrored = mirror.walkTopDown().filter(File::isFile).map { it.relativeTo(mirror).invariantSeparatorsPath }.toSet()
        assertEquals(2, mirrored.size)
        assertTrue("variations.json" in mirrored)
        assertTrue(mirrored.single { it != "variations.json" }.startsWith("keyboard_layouts/custom-"))
        assertTrue(File(ceFiles, "private_notes.txt").isFile)
    }

    @Test
    fun layoutAndKeyboardFilesKeepOnlyValidatedFunctionalData() {
        val layout = """
            {"name": "Cyrillic", "description": "$SECRET description", "author": "$SECRET author",
             "mappings": {
               "KEYCODE_Q": {"lowercase": "й", "uppercase": "Й", "alt": "€", "note": "$SECRET mapping"},
               "KEYCODE_W": {"lowercase": "ц", "uppercase": "Ц", "multiTapEnabled": true,
                             "taps": [{"lowercase": "ц", "uppercase": "Ц"}, {"lowercase": "щ", "uppercase": "Щ"}]},
               "KEYCODE_UNKNOWN_$SECRET": {"lowercase": "x", "uppercase": "X"}
             }}
        """.trimIndent()
        assertTrue(LayoutFileStore.saveLayoutFromJson(context, "cyrillic", layout) is LayoutFileStore.LayoutImportResult.Success)
        val layouts = File(context.filesDir, "keyboard_layouts")
        File(layouts, "custom-${"0".repeat(64)}.json").writeText("{\"name\": \"$SECRET broken\", \"mappings\": ")
        File(layouts, "custom-${"1".repeat(64)}.json").writeText("{\"layout_id\": \"$SECRET\", \"mappings\": {}}")
        // An own layout whose id has spaces and dots stays selectable.
        assertTrue(LayoutFileStore.saveLayoutFromJson(context, "My layout v2.1", LAYOUT_JSON) is LayoutFileStore.LayoutImportResult.Success)
        File(context.filesDir, "variations.json").writeText("""
            {"variations": {"a": ["à", 1, "á"], "$SECRET key": ["x"]}, "staticVariations": [",", "."], "emailVariations": ["@"],
             "layoutVariationOverrides": {"cyrillic": {"e": ["ё"], "$SECRET": ["y"]}, "$SECRET layout": {"e": ["z"]}},
             "notes": "$SECRET variations"}
        """.trimIndent())
        File(context.filesDir, "ctrl_key_mappings.json").writeText("""
            {"comment": "$SECRET nav", "mappings": {
              "KEYCODE_Q": {"type": "keycode", "keycode": "ESCAPE", "label": "$SECRET label", "action": "$SECRET action"},
              "KEYCODE_W": {"type": "action", "action": "move_word_left", "command": "$SECRET command"},
              "KEYCODE_E": {"type": "command", "command": "plektra.toggle_software_keyboard_mode"},
              "KEYCODE_R": {"type": "action", "action": "$SECRET free text"},
              "KEYCODE_T": {"type": "keycode", "keycode": "$SECRET"},
              "KEYCODE_Y": {"type": "$SECRET", "note": "$SECRET"},
              "KEYCODE_N": {"type": "none"},
              "$SECRET": {"type": "action", "action": "copy"}
            }}
        """.trimIndent())
        File(context.filesDir, "locale_layout_mapping.json").writeText("""
            {"ru_RU": "cyrillic", "fr_FR": "My layout v2.1", "de": "qwertz", "description": "$SECRET text",
             "it_IT": "$SECRET layout", "meta": {"x": "$SECRET"}}
        """.trimIndent())

        assertTrue(DirectBootKeyboardConfig.refresh(context))

        assertNoneInDeviceStorage(SECRET)
        val mirroredLayouts = File(mirror, "keyboard_layouts").listFiles()!!
            .filter { it.readText().contains("й") }
        val projected = mirroredLayouts.single().readText()
        listOf("\"Cyrillic\"", "\"cyrillic\"", "й", "Й", "€", "ц", "Ц", "щ", "Щ", "multiTapEnabled")
            .forEach { assertTrue("$it kept", projected.contains(it)) }
        assertEquals(LayoutFileStore.loadLayoutFromFile(LayoutFileStore.getLayoutFile(context, "cyrillic")),
            LayoutFileStore.loadLayoutFromFile(mirroredLayouts.single()))
        val localeMapping = org.json.JSONObject(File(mirror, "locale_layout_mapping.json").readText())
        assertEquals(mapOf("ru_RU" to "cyrillic", "fr_FR" to "My layout v2.1", "de" to "qwertz"),
            localeMapping.keys().asSequence().associateWith(localeMapping::getString))
        val variations = File(mirror, "variations.json").readText()
        listOf("à", "á", "ё", "@", ",").forEach { assertTrue(it, variations.contains(it)) }
        val ctrl = org.json.JSONObject(File(mirror, "ctrl_key_mappings.json").readText()).getJSONObject("mappings")
        assertEquals(setOf("KEYCODE_Q", "KEYCODE_W", "KEYCODE_E", "KEYCODE_N"), ctrl.keys().asSequence().toSet())
        assertEquals("""{"type":"keycode","keycode":"ESCAPE"}""", ctrl.getJSONObject("KEYCODE_Q").toString())
        assertEquals("""{"type":"action","action":"move_word_left"}""", ctrl.getJSONObject("KEYCODE_W").toString())
        assertEquals("plektra.toggle_software_keyboard_mode", ctrl.getJSONObject("KEYCODE_E").getString("command"))

        // Before unlock the IME reads the custom layout from the projection.
        users.setUserUnlocked(false)
        DirectBootStorage.resetForTesting()
        assertEquals("й", LayoutFileStore.loadLayoutFromFile(LayoutFileStore.getLayoutFile(context, "cyrillic"))!!
            [android.view.KeyEvent.KEYCODE_Q]!!.lowercase)
        assertEquals("Cyrillic", LayoutFileStore.getLayoutMetadata(context, "cyrillic")!!.name)
        assertEquals("", LayoutFileStore.getLayoutMetadata(context, "cyrillic")!!.description)
        assertEquals("ä", LayoutFileStore.loadLayoutFromFile(LayoutFileStore.getLayoutFile(context, "My layout v2.1"))!!
            [android.view.KeyEvent.KEYCODE_Q]!!.lowercase)
    }

    @Test
    fun lockedRefreshCannotOverwriteSnapshot() {
        val source = SettingsManager.getPreferences(context)
        source.edit().putString("keyboard_layout", "qwertz").commit()
        val store = DirectBootKeyboardConfig(context)
        assertTrue(store.refresh(source, context.filesDir))
        val before = store.read().toMap()
        users.setUserUnlocked(false)
        assertFalse(store.refresh(source, context.filesDir))
        assertEquals(before, store.read())
    }

    @Test
    fun failedStagingOrPublicationKeepsTheLastCompleteGeneration() {
        val source = SettingsManager.getPreferences(context)
        source.edit().putString("keyboard_layout", "first").commit()
        assertTrue(LayoutFileStore.saveLayoutFromJson(context, "first", LAYOUT_JSON) is LayoutFileStore.LayoutImportResult.Success)
        val store = DirectBootKeyboardConfig(context)
        assertTrue(store.refresh(source, context.filesDir))
        val published = deviceContents()

        // The rebuilds these changes request fail as well.
        DirectBootKeyboardConfig.faultInjector = { if (it == "stage") throw IOException("synthetic stage failure") }
        source.edit().putString("keyboard_layout", "second").commit()
        assertTrue(LayoutFileStore.saveLayoutFromJson(context, "second", LAYOUT_JSON.replace("ä", "ö")) is LayoutFileStore.LayoutImportResult.Success)
        DirectBootKeyboardConfig.awaitPendingRefresh()
        assertEquals(published, deviceContents())
        listOf("capture", "stage", "publish").forEach { stage ->
            DirectBootKeyboardConfig.faultInjector = { if (it == stage) throw IOException("synthetic $stage failure") }
            val failure = runCatching { store.refresh(source, context.filesDir) }.exceptionOrNull()
            assertTrue("$stage failed", failure is IOException)
            assertEquals("$stage leaves the previous generation in place", published, deviceContents())
            assertEquals("first", store.read()["keyboard_layout"])
        }

        DirectBootKeyboardConfig.faultInjector = null
        assertTrue(store.refresh(source, context.filesDir))
        assertEquals("second", store.read()["keyboard_layout"])
        assertEquals(2, File(mirror, "keyboard_layouts").listFiles()!!.size)
        assertEquals(1, File(deviceRoot, "files/direct_boot_files").list()!!.count { it.startsWith("generation-") })
    }

    @Test
    fun rolledBackRestoreLeavesTheSnapshotConsistentWithCredentialStorage() = runBlocking {
        val prefs = SettingsManager.getPreferences(context)
        assertTrue(LayoutFileStore.saveLayoutFromJson(context, "restored", LAYOUT_JSON) is LayoutFileStore.LayoutImportResult.Success)
        prefs.edit().putString("keyboard_layout", "restored").commit()
        val zip = File(context.cacheDir, "direct-boot-restore.zip")
        assertTrue(BackupManager.createBackup(context, Uri.fromFile(zip)) is BackupResult.Success)
        assertTrue(LayoutFileStore.deleteLayout(context, "restored"))
        assertTrue(LayoutFileStore.saveLayoutFromJson(context, "current", LAYOUT_JSON.replace("ä", "ö")) is LayoutFileStore.LayoutImportResult.Success)
        prefs.edit().putString("keyboard_layout", "current").commit()
        assertTrue(DirectBootKeyboardConfig.refreshNow(context))

        var failed = false
        val failing = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val original = super.getSharedPreferences(name, mode)
                if (name != "plektra_prefs") return original
                return object : SharedPreferences by original {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = original.edit()
                        return object : SharedPreferences.Editor by editor {
                            override fun commit(): Boolean {
                                if (!failed) {
                                    failed = true
                                    // Rebuilds during the import wait for it: a pending one and a direct one.
                                    DirectBootKeyboardConfig.requestRefresh(context)
                                    assertFalse(DirectBootKeyboardConfig.refreshNow(context))
                                    val waiting = thread { DirectBootKeyboardConfig.awaitPendingRefresh() }
                                    waiting.join(500)
                                    assertTrue("the pending rebuild waits for the import", waiting.isAlive)
                                    assertFalse(snapshotLayouts().contains("ä"))
                                    throw IllegalStateException("synthetic preference failure")
                                }
                                return editor.commit()
                            }
                        }
                    }
                }
            }
        }
        assertTrue(RestoreManager.restore(failing, Uri.fromFile(zip)) is RestoreResult.Failure)
        assertTrue(failed)

        assertEquals("current", DirectBootKeyboardConfig(context).read()["keyboard_layout"])
        assertTrue(snapshotLayouts().contains("ö"))
        assertFalse(snapshotLayouts().contains("ä"))

        assertTrue(RestoreManager.restore(context, Uri.fromFile(zip)) is RestoreResult.Success)
        assertEquals("restored", DirectBootKeyboardConfig(context).read()["keyboard_layout"])
        assertTrue(snapshotLayouts().contains("ä"))
    }

    @Test
    fun restoreSucceedsOnlyWithAMatchingSnapshotAndKeepsRecoveryDataWhenItsRollbackCannotMatch() = runBlocking {
        val prefs = SettingsManager.getPreferences(context)
        assertTrue(LayoutFileStore.saveLayoutFromJson(context, "restored", LAYOUT_JSON) is LayoutFileStore.LayoutImportResult.Success)
        prefs.edit().putString("keyboard_layout", "restored").commit()
        val zip = File(context.cacheDir, "direct-boot-restore-refresh.zip")
        assertTrue(BackupManager.createBackup(context, Uri.fromFile(zip)) is BackupResult.Success)
        assertTrue(LayoutFileStore.deleteLayout(context, "restored"))
        SettingsManager.setKeyboardLayout(context, "qwertz")
        val published = deviceContents()
        fun recoveryDirs() = context.cacheDir.listFiles().orEmpty().filter { File(it, "rollback").exists() }

        // The rebuild after the import fails once: the import is rolled back and matches the snapshot.
        var failures = 1
        DirectBootKeyboardConfig.faultInjector = { if (it == "capture" && failures-- > 0) throw IOException("synthetic capture failure") }
        assertTrue(RestoreManager.restore(context, Uri.fromFile(zip)) is RestoreResult.Failure)
        assertEquals("qwertz", SettingsManager.getKeyboardLayout(context))
        assertEquals(published, deviceContents())
        assertTrue(recoveryDirs().isEmpty())

        // The rollback cannot be matched either: recovery is incomplete and its data is kept.
        DirectBootKeyboardConfig.faultInjector = { if (it == "capture") throw IOException("synthetic capture failure") }
        val result = RestoreManager.restore(context, Uri.fromFile(zip))
        assertTrue(result is RestoreResult.Failure)
        assertTrue((result as RestoreResult.Failure).reason.contains("recovery is incomplete"))
        assertEquals("qwertz", SettingsManager.getKeyboardLayout(context))
        assertEquals(published, deviceContents())
        assertEquals(1, recoveryDirs().size)
        recoveryDirs().forEach(File::deleteRecursively)
    }

    /** A change during a failing restore waits for the rollback: it is kept, and the restored state is never published. */
    @Test
    fun aChangeDuringAFailingRestoreWaitsForTheRollbackAndIsKept() = runBlocking {
        val prefs = SettingsManager.getPreferences(context)
        assertTrue(LayoutFileStore.saveLayoutFromJson(context, "restored", LAYOUT_JSON) is LayoutFileStore.LayoutImportResult.Success)
        prefs.edit().putString("keyboard_layout", "restored").commit()
        val zip = File(context.cacheDir, "direct-boot-restore-writer.zip")
        assertTrue(BackupManager.createBackup(context, Uri.fromFile(zip)) is BackupResult.Success)
        assertTrue(LayoutFileStore.deleteLayout(context, "restored"))
        SettingsManager.setKeyboardLayout(context, "qwertz")
        DirectBootKeyboardConfig.awaitPendingRefresh()

        val changed = CountDownLatch(1)
        var writer: Thread? = null
        var writerWaited = false
        var armed = true
        DirectBootKeyboardConfig.faultInjector = { stage ->
            if (stage == "publish" && armed) {
                armed = false
                // The restored state is about to be published; a concurrent change starts now.
                writer = thread {
                    DirectBootKeyboardConfig.changeSources(context) {
                        prefs.edit().putString("keyboard_layout", "azerty").commit()
                        changed.countDown()
                    }
                }
                writerWaited = !changed.await(500, TimeUnit.MILLISECONDS)
                throw IOException("synthetic publication failure")
            }
        }
        assertTrue(RestoreManager.restore(context, Uri.fromFile(zip)) is RestoreResult.Failure)
        writer!!.join(5_000)

        assertTrue("the change waits for the rollback", writerWaited)
        assertEquals(0, changed.count)
        assertEquals("azerty", SettingsManager.getKeyboardLayout(context))
        assertEquals("azerty", DirectBootKeyboardConfig(context).read()["keyboard_layout"])
        assertFalse(snapshotLayouts().contains("ä"))
    }

    @Test
    fun changesRefreshTheSnapshotOnASerialBackgroundThread() {
        val threads = Collections.synchronizedList(mutableListOf<String>())
        DirectBootKeyboardConfig.faultInjector = { if (it == "publish") threads += Thread.currentThread().name }
        repeat(5) { SettingsManager.getPreferences(context).edit().putLong("long_press_threshold", 300L + it).commit() }
        assertTrue(LayoutFileStore.saveLayoutFromJson(context, "mine", LAYOUT_JSON) is LayoutFileStore.LayoutImportResult.Success)

        DirectBootKeyboardConfig.awaitPendingRefresh()

        assertTrue(threads.isNotEmpty())
        assertTrue("never on the main thread: $threads", threads.all { it == "DirectBootSnapshot" })
        assertTrue("six changes coalesce: $threads", threads.size <= 2)
        assertEquals(304L, DirectBootKeyboardConfig(context).read()["long_press_threshold"])
        assertEquals(1, File(mirror, "keyboard_layouts").listFiles()!!.size)
    }

    /** Layout selection, saving and deletion are in the snapshot as soon as they return. */
    @Test
    fun snapshotFollowsSettingAndLayoutFileChangesAfterUnlock() {
        SettingsManager.setKeyboardLayout(context, "azerty")
        assertEquals("azerty", DirectBootKeyboardConfig(context).read()["keyboard_layout"])
        SettingsManager.setKeyboardLayoutList(context, listOf("azerty", "qwertz"))
        assertEquals("[\"azerty\",\"qwertz\"]", DirectBootKeyboardConfig(context).read()["keyboard_layout_list"])
        SettingsManager.getPreferences(context).edit().putString("snippets_v1", "private").commit()
        DirectBootKeyboardConfig.awaitPendingRefresh()
        assertNull(DirectBootKeyboardConfig(context).read()["snippets_v1"])

        assertTrue(LayoutFileStore.saveLayoutFromJson(context, "mine", LAYOUT_JSON)
            is LayoutFileStore.LayoutImportResult.Success)
        assertEquals(1, File(mirror, "keyboard_layouts").listFiles().orEmpty().size)
        assertTrue(LayoutFileStore.saveLayoutFromJson(context, "mine", LAYOUT_JSON.replace("ä", "ö"),
            LayoutFileStore.LayoutConflictPolicy.REPLACE) is LayoutFileStore.LayoutImportResult.Success)
        assertTrue(File(mirror, "keyboard_layouts").listFiles()!!.single().readText().contains("ö"))
        assertTrue(LayoutFileStore.deleteLayout(context, "mine"))
        assertEquals(0, File(mirror, "keyboard_layouts").listFiles().orEmpty().size)
        SettingsManager.saveSymMappings(context, mapOf(android.view.KeyEvent.KEYCODE_Q to "§"))
        assertTrue((DirectBootKeyboardConfig(context).read()["sym_mappings_custom"] as String).contains("§"))
    }

    /** A rebuild during a deletion of the selected layout waits for the new selection. */
    @Test
    fun rebuildDuringDeletionOfTheSelectedLayoutNeverPublishesADanglingSelection() {
        assertTrue(LayoutFileStore.saveLayoutFromJson(context, "mine", LAYOUT_JSON) is LayoutFileStore.LayoutImportResult.Success)
        SettingsManager.setKeyboardLayout(context, "mine")
        val before = deviceContents()
        var concurrent: Thread? = null
        DirectBootKeyboardConfig.changeSources(context) {
            assertTrue(LayoutFileStore.deleteLayout(context, "mine"))
            concurrent = thread { assertTrue(DirectBootKeyboardConfig.refreshNow(context)) }
            concurrent!!.join(500)
            assertTrue("the rebuild waits for the change", concurrent!!.isAlive)
            assertEquals(before, deviceContents())
            SettingsManager.setKeyboardLayout(context, "qwerty")
        }
        concurrent!!.join()
        assertEquals("qwerty", DirectBootKeyboardConfig(context).read()["keyboard_layout"])
        assertEquals(0, File(mirror, "keyboard_layouts").listFiles().orEmpty().size)
    }

    @Test
    fun fileWritersWithoutARefreshMarkerStillRefreshTheSnapshot() {
        File(context.filesDir, "locale_layout_mapping.json").writeText("""{"de_DE": "german_multitap_qwertz"}""")
        DirectBootKeyboardConfig.refreshNow(context)
        migrateLegacyGermanSystemLayoutMapping(context)
        DirectBootKeyboardConfig.awaitPendingRefresh()
        assertTrue(File(mirror, "locale_layout_mapping.json").readText().contains("\"qwertz\""))

        SettingsManager.setStaticVariationBarPreset(context, "symbols")
        DirectBootKeyboardConfig.awaitPendingRefresh()
        assertEquals(
            File(context.filesDir, "variations.json").readText().let { org.json.JSONObject(it).getJSONArray("staticVariations").toString() },
            org.json.JSONObject(File(mirror, "variations.json").readText()).getJSONArray("staticVariations").toString()
        )

        File(context.filesDir, "ctrl_key_mappings.json").delete()
        DirectBootKeyboardConfig.refreshNow(context)
        SettingsManager.initializeNavModeMappingsFile(context)
        DirectBootKeyboardConfig.awaitPendingRefresh()
        assertTrue(File(mirror, "ctrl_key_mappings.json").isFile)
    }

    @Test
    fun emojiStyleSurvivesBeforeUnlockAndIsReloadedAfterUnlock() = runBlocking {
        EmojiRendering.setStyle(context, EmojiRendering.Style.SYSTEM)
        DirectBootKeyboardConfig.awaitPendingRefresh()
        users.setUserUnlocked(false)
        DirectBootStorage.resetForTesting()
        // A new process before unlock: nothing cached yet.
        EmojiRendering.reloadStyle(context)

        assertEquals(EmojiRendering.Style.SYSTEM, EmojiRendering.style(context))
        EmojiRepository.getEmojiCategories(context)
        val lockedCategories = EmojiRepository.cachedCategoriesOrNull()

        users.setUserUnlocked(true)
        DirectBootStorage.completeUnlock(context)
        shadowOf(Looper.getMainLooper()).idle()
        EmojiRendering.reloadStyle(context)
        assertEquals(EmojiRendering.Style.SYSTEM, EmojiRendering.style(context))
        assertTrue("same style keeps the inventory", EmojiRepository.cachedCategoriesOrNull() === lockedCategories)

        // A snapshot older than the persisted style is corrected at unlock.
        users.setUserUnlocked(false)
        DirectBootStorage.resetForTesting()
        File(deviceRoot, "files/direct_boot_files").deleteRecursively()
        EmojiRendering.reloadStyle(context)
        assertEquals(EmojiRendering.Style.NOTO, EmojiRendering.style(context))
        EmojiRepository.getEmojiCategories(context)
        users.setUserUnlocked(true)
        DirectBootStorage.completeUnlock(context)
        shadowOf(Looper.getMainLooper()).idle()
        EmojiRendering.reloadStyle(context)
        assertEquals(EmojiRendering.Style.SYSTEM, EmojiRendering.style(context))
        assertNull("inventory built for the bundled font is dropped", EmojiRepository.cachedCategoriesOrNull())
        EmojiRendering.setStyle(context, EmojiRendering.Style.NOTO)
    }

    @Test
    fun lockedPreferencesExposeSnapshotWithDeferredFeaturesOffAndKeepWritesInMemory() {
        SettingsManager.setKeyboardLayout(context, "azerty")
        SettingsManager.getPreferences(context).edit().putBoolean("experimental_suggestions_enabled", true).commit()
        DirectBootKeyboardConfig.awaitPendingRefresh()
        users.setUserUnlocked(false)
        DirectBootStorage.resetForTesting()

        val locked = SettingsManager.getPreferences(context)
        assertEquals("azerty", SettingsManager.getKeyboardLayout(context))
        assertFalse(SettingsManager.isExperimentalSuggestionsEnabled(context))
        locked.edit().putString("snippets_v1", "typed before unlock").commit()
        assertEquals("typed before unlock", locked.getString("snippets_v1", null))
        assertNoneInDeviceStorage("typed before unlock")

        var handoffs = 0
        assertTrue(DirectBootStorage.runAfterUnlock(context) { handoffs++ })
        DirectBootStorage.completeUnlock(context)
        assertEquals("still locked: no handoff", 0, handoffs)
        users.setUserUnlocked(true)
        DirectBootStorage.completeUnlock(context)
        DirectBootStorage.completeUnlock(context)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, handoffs)
        assertTrue(SettingsManager.isExperimentalSuggestionsEnabled(context))
        assertNull("the held instance reads credential storage", locked.getString("snippets_v1", null))
        assertFalse(DirectBootStorage.runAfterUnlock(context) { handoffs++ })
    }

    @Test
    fun aFailingUnlockListenerDoesNotBlockTheOthersAndIsRetriedAlone() {
        users.setUserUnlocked(false)
        DirectBootStorage.resetForTesting()
        val runs = mutableListOf<String>()
        var failNext = true
        assertTrue(DirectBootStorage.runAfterUnlock(context) {
            runs += "failing"
            if (failNext) {
                failNext = false
                throw IllegalStateException("synthetic listener failure")
            }
        })
        assertTrue(DirectBootStorage.runAfterUnlock(context) { runs += "handoff" })

        users.setUserUnlocked(true)
        DirectBootStorage.completeUnlock(context)
        assertEquals(listOf("failing", "handoff"), runs)
        // The next unlock signal retries only the failed listener.
        shadowOf(Looper.getMainLooper()).idle()
        DirectBootStorage.completeUnlock(context)
        DirectBootStorage.completeUnlock(context)
        assertEquals(listOf("failing", "handoff", "failing"), runs)
    }

    @Test
    fun keyboardSurfaceAndClicksButtonSettingsAreMirroredWithKnownValuesOnly() {
        SettingsManager.setClicksButtonMode(context, SettingsManager.ClicksPowerButtonMode.QUICK_LAUNCHER)
        SettingsManager.setClicksMetaButtonMode(context, SettingsManager.ClicksPowerButtonMode.TAB)
        SettingsManager.setClicksAltButtonMode(context, SettingsManager.ClicksPowerButtonMode.ALT)
        SettingsManager.setClicksMicrophoneButtonMode(context, SettingsManager.ClicksPowerButtonMode.SYM)
        SettingsManager.setSoftwareKeyboardModeRuntimeOverride(context, SettingsManager.SoftwareKeyboardMode.FORCE_VIRTUAL)
        SettingsManager.getPreferences(context).edit().putString("field_scoped_virtual_previous_override", "").commit()
        assertTrue(DirectBootKeyboardConfig.refreshNow(context))
        val snapshot = DirectBootKeyboardConfig(context).read()
        assertEquals(mapOf("clicks_button_mode" to "quick_launcher", "clicks_meta_button_mode" to "tab",
            "clicks_alt_button_mode" to "alt", "clicks_microphone_button_mode" to "sym"),
            snapshot.filterKeys { it.startsWith("clicks_") && it.endsWith("_button_mode") })
        assertEquals("force_virtual", snapshot[SettingsManager.KEY_SOFTWARE_KEYBOARD_MODE_RUNTIME_OVERRIDE])
        assertEquals("", snapshot["field_scoped_virtual_previous_override"])

        SettingsManager.getPreferences(context).edit()
            .putString(SettingsManager.KEY_SOFTWARE_KEYBOARD_MODE_RUNTIME_OVERRIDE, "auto")
            .putString("field_scoped_virtual_previous_override", SECRET)
            .putString("clicks_meta_button_mode", SECRET)
            .commit()
        assertTrue(DirectBootKeyboardConfig.refreshNow(context))
        val invalid = DirectBootKeyboardConfig(context).read()
        listOf(SettingsManager.KEY_SOFTWARE_KEYBOARD_MODE_RUNTIME_OVERRIDE, "field_scoped_virtual_previous_override",
            "clicks_meta_button_mode").forEach { assertFalse(it, invalid.containsKey(it)) }
        assertNoneInDeviceStorage(SECRET)
    }

    @Test
    fun profileAndCurrencyEnterTheSnapshotOnlyAsKnownValuesInTheirCanonicalForm() {
        val prefs = SettingsManager.getPreferences(context)
        prefs.edit()
            .putString("physical_keyboard_profile_override", " q25 ")
            .putString("physical_keyboard_currency_symbol", "£")
            .commit()
        assertTrue(DirectBootKeyboardConfig.refreshNow(context))
        val snapshot = DirectBootKeyboardConfig(context).read()
        assertEquals("Q25", snapshot["physical_keyboard_profile_override"])
        assertEquals("£", snapshot["physical_keyboard_currency_symbol"])

        prefs.edit()
            .putString("physical_keyboard_profile_override", SECRET)
            .putString("physical_keyboard_currency_symbol", SECRET)
            .commit()
        assertTrue(DirectBootKeyboardConfig.refreshNow(context))
        val invalid = DirectBootKeyboardConfig(context).read()
        assertFalse(invalid.containsKey("physical_keyboard_profile_override"))
        assertFalse(invalid.containsKey("physical_keyboard_currency_symbol"))
        assertNoneInDeviceStorage(SECRET)
        users.setUserUnlocked(false)
        DirectBootStorage.resetForTesting()
        assertEquals("auto", SettingsManager.getPhysicalKeyboardProfileOverride(context))
        assertEquals("€", SettingsManager.getPhysicalKeyboardCurrencySymbol(context))
    }

    @Test
    fun applicationKeepsItsFrameworkBaseContextForReceiverDispatch() {
        assertFalse((context as ContextWrapper).baseContext is DirectBootStorageContext)
        users.setUserUnlocked(false)
        DirectBootStorage.resetForTesting()
        assertTrue(SettingsManager.getPreferences(context) is SwitchingPreferences)
        assertEquals(DirectBootKeyboardConfig(context).lockedFilesDir(), context.filesDir)
    }

    @Test
    fun reusedEditorDoesNotReplayLockedClearOrWritesAfterUnlock() {
        val credential = MemoryPreferences(mapOf("private" to "keep", "key" to "credential"))
        val held = SwitchingPreferences(MemoryPreferences(mapOf("key" to "locked")))
        val editor = held.edit().clear().putString("key", "before")
        assertTrue(editor.commit())
        held.switchTo(credential, emptySet())
        editor.putString("other", "after").apply()
        assertEquals("keep", credential.getString("private", null))
        assertEquals("credential", credential.getString("key", null))
        assertEquals("after", credential.getString("other", null))
    }

    @Test
    fun pendingMemoryNotificationSurvivesUnlock() {
        val held = SwitchingPreferences(MemoryPreferences(emptyMap<String, Any>()))
        val notified = mutableListOf<String?>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> notified += key }
        held.registerOnSharedPreferenceChangeListener(listener)
        val writer = thread { held.edit().putString("keyboard_layout", "qwertz").commit() }
        writer.join(3000)
        assertFalse(writer.isAlive)
        held.switchTo(MemoryPreferences(emptyMap<String, Any>()), setOf("keyboard_layout"))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(notified.contains("keyboard_layout"))
    }

    /** Editors obtained before unlock write to credential storage when they commit or apply afterwards. */
    @Test
    fun editorsObtainedBeforeUnlockWriteToCredentialStorageAfterIt() {
        SettingsManager.getPreferences(context).edit().putString("snippets_v1", "credential").putString("obsolete", "old").commit()
        context.getSharedPreferences("bfu_clear", Context.MODE_PRIVATE).edit().putInt("old", 1).commit()
        DirectBootKeyboardConfig.awaitPendingRefresh()
        users.setUserUnlocked(false)
        DirectBootStorage.resetForTesting()

        val held = SettingsManager.getPreferences(context)
        val notified = mutableListOf<String?>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> notified += key }
        held.registerOnSharedPreferenceChangeListener(listener)
        // Selected and confirmed before unlock: carried over.
        SettingsManager.setKeyboardLayout(context, "azerty")
        val tags = mutableSetOf("a")
        val committing = held.edit()
            .putString("snippets_v1", "after unlock")
            .putStringSet("tags", tags)
            .remove("obsolete")
            .putString("nulled", null)
        tags += "b"
        val applying = held.edit().putBoolean("shift_tap_latches", true)
        val clearing = context.getSharedPreferences("bfu_clear", Context.MODE_PRIVATE).edit().clear().putInt("new", 2)

        users.setUserUnlocked(true)
        assertFalse(DirectBootStorage.isLocked(context))
        DirectBootStorage.completeUnlock(context)
        shadowOf(Looper.getMainLooper()).idle()
        notified.clear()
        assertTrue(committing.commit())
        applying.apply()
        assertTrue(clearing.commit())
        shadowOf(Looper.getMainLooper()).idle()

        val credential = credentialBase.getSharedPreferences("plektra_prefs", Context.MODE_PRIVATE)
        assertEquals("after unlock", credential.getString("snippets_v1", null))
        assertEquals(setOf("a"), credential.getStringSet("tags", null))
        assertFalse(credential.contains("obsolete"))
        assertFalse(credential.contains("nulled"))
        assertTrue(credential.getBoolean("shift_tap_latches", false))
        assertEquals("azerty", credential.getString("keyboard_layout", null))
        assertEquals(mapOf("new" to 2), credentialBase.getSharedPreferences("bfu_clear", Context.MODE_PRIVATE).all)
        assertTrue("listeners follow: $notified", notified.containsAll(listOf("snippets_v1", "shift_tap_latches")))
        assertTrue(DirectBootKeyboardConfig.refreshNow(context))
        assertEquals("azerty", DirectBootKeyboardConfig(context).read()["keyboard_layout"])
    }

    /** The mapping file is read within the change, so a concurrent change of it is not overwritten. */
    @Test
    fun localeMappingUpdateKeepsAConcurrentChangeOfTheMappingFile() {
        val file = File(context.filesDir, "locale_layout_mapping.json")
        file.writeText("""{"it": "qwerty"}""")
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val concurrent = thread {
            DirectBootKeyboardConfig.changeSources(context) {
                holding.countDown()
                release.await()
                file.writeText("""{"it": "qwerty", "fr_FR": "colemak"}""")
            }
        }
        holding.await()
        val updater = thread { rocks.pkb.plektra.updateLocaleLayoutMapping(context, "de_DE", "qwertz") }
        // Let the update run up to the source lock before the concurrent change writes; changeSources is
        // inlined, so the update waits for that lock in its own frame.
        val deadline = System.currentTimeMillis() + 5_000
        while (updater.state != Thread.State.BLOCKED || updater.stackTrace.firstOrNull()?.methodName != "updateLocaleLayoutMapping") {
            assertTrue("the update reaches the source lock", updater.isAlive && System.currentTimeMillis() < deadline)
            Thread.sleep(10)
        }
        release.countDown()
        concurrent.join(5_000)
        updater.join(5_000)

        val json = org.json.JSONObject(file.readText())
        assertEquals("colemak", json.getString("fr_FR"))
        assertEquals("qwertz", json.getString("de_DE"))
    }

    /**
     * Android installs the providers and starts their work, e.g. WorkManager's database migration on
     * a background thread, after the user is unlocked but before ACTION_USER_UNLOCKED.
     */
    @Test
    fun storageFollowsTheUnlockImmediatelyOnAnyThreadAndHandsOverOnceOnTheMainThread() {
        SettingsManager.getPreferences(context).edit().putString("snippets_v1", "credential").commit()
        users.setUserUnlocked(false)
        DirectBootStorage.resetForTesting()
        val held = SettingsManager.getPreferences(context)
        assertNull(held.getString("snippets_v1", null))
        val handoffThreads = mutableListOf<Thread>()
        assertTrue(DirectBootStorage.runAfterUnlock(context) { handoffThreads += Thread.currentThread() })

        users.setUserUnlocked(true)
        var failure: Throwable? = null
        val paths = mutableListOf<File>()
        thread {
            try {
                paths += context.getDatabasePath("androidx.work.workdb")
                paths += context.noBackupFilesDir
                paths += context.dataDir
                paths += context.getDir("work", Context.MODE_PRIVATE)
                paths += context.filesDir
            } catch (e: Throwable) {
                failure = e
            }
        }.join()

        assertNull(failure)
        assertEquals(credentialBase.filesDir, paths.last())
        assertEquals(credentialBase.getDatabasePath("androidx.work.workdb"), paths.first())
        assertEquals("credential", held.getString("snippets_v1", null))
        assertTrue("handoff waits for the main thread", handoffThreads.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        context.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_USER_UNLOCKED))
        DirectBootStorage.completeUnlock(context)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(Looper.getMainLooper().thread), handoffThreads)
    }

    /** Preferences opened while another thread observes the unlock never stay memory-only. */
    @Test
    fun preferencesOpenedDuringTheUnlockAlwaysReachCredentialStorage() {
        repeat(20) { round ->
            users.setUserUnlocked(false)
            DirectBootStorage.resetForTesting()
            assertTrue(DirectBootStorage.isLocked(context))
            val opened = Collections.synchronizedList(mutableListOf<Pair<String, SharedPreferences>>())
            val start = CyclicBarrier(5)
            val done = CountDownLatch(4)
            repeat(4) { worker ->
                thread {
                    start.await()
                    repeat(10) { index ->
                        val name = "race_${round}_${worker}_$index"
                        opened += name to context.getSharedPreferences(name, Context.MODE_PRIVATE)
                    }
                    done.countDown()
                }
            }
            start.await()
            users.setUserUnlocked(true)
            assertFalse(DirectBootStorage.isLocked(context))
            done.await()

            opened.forEach { (name, prefs) ->
                assertTrue(prefs.edit().putString("after", name).commit())
                assertEquals("$name reaches credential storage", name,
                    credentialBase.getSharedPreferences(name, Context.MODE_PRIVATE).getString("after", null))
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun snapshotLayouts(): String =
        File(mirror, "keyboard_layouts").listFiles().orEmpty().joinToString("\n") { it.readText() }

    private fun deviceContents(): Map<String, String> =
        deviceRoot.walkTopDown().filter(File::isFile).associate { it.relativeTo(deviceRoot).path to it.readText() }

    private fun assertNoneInDeviceStorage(vararg secrets: String) {
        val contents = deviceContents()
        secrets.forEach { secret ->
            contents.forEach { (path, text) -> assertFalse("$secret leaked into $path", text.contains(secret)) }
        }
    }

    private companion object {
        const val SECRET = "PRIVATE-IMPORT-5e1d"
        val LAYOUT_JSON = """{"name": "Mine", "mappings": {"KEYCODE_Q": {"lowercase": "ä", "uppercase": "Ä"}}}"""
    }
}
