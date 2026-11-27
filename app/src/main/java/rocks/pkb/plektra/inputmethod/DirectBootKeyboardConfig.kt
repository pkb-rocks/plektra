package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.content.SharedPreferences
import android.os.UserManager
import android.util.Log
import androidx.annotation.VisibleForTesting
import org.json.JSONArray
import org.json.JSONObject
import rocks.pkb.plektra.KeyboardBarArrangement
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.backup.PreferenceSchemas
import rocks.pkb.plektra.backup.PreferenceValueType
import rocks.pkb.plektra.data.layout.LayoutFileStore
import rocks.pkb.plektra.data.mappings.KeyMappingLoader
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Device-protected copy of the layout data and input settings that the IME needs before the user
 * unlocks, inspired by Mircea Horea IONICĂ (mionica), https://github.com/mionica/pastiera/commit/5da24e7634.
 * It is rebuilt from credential-protected storage after unlock and on every relevant change.
 * Only allowlisted settings and the validated functional parts of the keyboard files enter it.
 *
 * Each rebuild is staged as a complete generation (settings and files) and published by
 * atomically replacing a small pointer, so a reader sees either the previous or the new generation.
 * Writers of the sources go through [changeSources], so a rebuild never sees a change half done.
 */
internal class DirectBootKeyboardConfig(private val context: Context) {
    private val root = File(context.createDeviceProtectedStorageContext().filesDir, ROOT_DIR)

    /** Publishes a new generation when the projection of the sources differs from the current one. */
    fun refresh(source: SharedPreferences, sourceFilesDir: File): Boolean {
        if (!isUserUnlocked(context)) return false
        synchronized(LOCK) {
            val wanted = synchronized(SOURCE_LOCK) { project(source, sourceFilesDir) }
            val current = currentGeneration()
            // Also republishes when anything was written into the generation before unlock.
            val generation = if (current != null && contentOf(current) == wanted) current else publish(wanted)
            removeAllBut(generation)
        }
        return true
    }

    /** Settings of the published generation; empty before the first publication. */
    fun read(): Map<String, *> = currentGeneration()
        ?.let { runCatching { decodeSettings(File(it, SETTINGS_FILE).readText()) }.getOrNull() }
        .orEmpty()

    /** Files directory of the published generation, or an empty one before the first publication. */
    fun lockedFilesDir(): File = File(currentGeneration() ?: File(root, UNPUBLISHED_DIR), FILES_SUBDIR).apply { mkdirs() }

    private fun currentGeneration(): File? {
        val name = runCatching { File(root, POINTER_FILE).readText().trim() }.getOrNull() ?: return null
        if (!GENERATION_NAME.matches(name)) return null
        return File(root, name).takeIf { File(it, SETTINGS_FILE).isFile }
    }

    /** Reads the sources; a read error fails the rebuild, while invalid content is left out. */
    private fun project(source: SharedPreferences, sourceFilesDir: File): Map<String, String> {
        faultInjector?.invoke("capture")
        val settings = source.all.mapValues { (key, value) ->
            CANONICAL_VALUES[key]?.let { canonical -> (value as? String)?.let(canonical) } ?: value
        }.filter { (key, value) -> isSnapshotValue(key, value) }
        val content = sortedMapOf(SETTINGS_FILE to encodeSettings(settings))
        val layoutIds = context.assets.list(BUNDLED_LAYOUTS_DIR).orEmpty().map { it.removeSuffix(".json") }.toMutableSet()
        // Layouts in safe storage win over a legacy file that maps to the same storage id.
        File(sourceFilesDir, LAYOUTS_DIR).listFiles { file -> file.isFile && file.name.endsWith(".json") && !file.name.startsWith(".") }
            ?.sortedWith(compareBy<File> { !LayoutFileStore.isSafeStorageFile(it) }.thenBy { it.name })
            ?.forEach { file ->
                val layout = LayoutFileStore.directBootProjection(file, file.readText()) ?: return@forEach
                if (content.putIfAbsent("$FILES_SUBDIR/$LAYOUTS_DIR/${layout.fileName}", layout.json) == null) {
                    layoutIds += layout.layoutId
                }
            }
        ROOT_FILES.forEach { (name, projection) ->
            val file = File(sourceFilesDir, name).takeIf { it.isFile } ?: return@forEach
            val text = file.readText()
            runCatching { projection(JSONObject(text), layoutIds) }
                .onSuccess { content["$FILES_SUBDIR/$name"] = it.toString(2) }
                .onFailure { Log.w(TAG, "Not copying invalid $name") }
        }
        return content
    }

    private fun contentOf(generation: File): Map<String, String> =
        generation.walkTopDown().filter(File::isFile)
            .associate { it.relativeTo(generation).invariantSeparatorsPath to it.readText() }

    private fun publish(content: Map<String, String>): File {
        root.mkdirs()
        val generation = File(root, "$GENERATION_PREFIX${UUID.randomUUID()}")
        try {
            content.forEach { (path, text) ->
                val file = File(generation, path)
                file.parentFile?.mkdirs()
                FileOutputStream(file).use { output ->
                    output.write(text.toByteArray(Charsets.UTF_8))
                    output.fd.sync()
                }
                faultInjector?.invoke("stage")
            }
            faultInjector?.invoke("publish")
            LayoutFileStore.writeAtomically(File(root, POINTER_FILE), generation.name.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            generation.deleteRecursively()
            throw e
        }
        return generation
    }

    /** Removes older generations, aborted stages and the earlier unversioned copies. */
    private fun removeAllBut(generation: File) {
        root.listFiles()?.forEach { file ->
            if (file.name != generation.name && file.name != POINTER_FILE) file.deleteRecursively()
        }
        val deviceContext = context.createDeviceProtectedStorageContext()
        deviceContext.deleteSharedPreferences(PREFS_NAME)
    }

    companion object {
        /** Earlier unversioned settings copy; removed on publication. */
        const val PREFS_NAME = "direct_boot_keyboard_config"
        private const val TAG = "DirectBootKeyboardConfig"
        private const val ROOT_DIR = "direct_boot_files"
        private const val POINTER_FILE = "current"
        private const val GENERATION_PREFIX = "generation-"
        private val GENERATION_NAME = Regex("$GENERATION_PREFIX[0-9a-f-]{36}")
        private const val UNPUBLISHED_DIR = "unpublished"
        private const val SETTINGS_FILE = "settings.json"
        private const val FILES_SUBDIR = "files"
        private const val LAYOUTS_DIR = "keyboard_layouts"
        private const val BUNDLED_LAYOUTS_DIR = "common/layouts"
        private const val REFRESH_DELAY_MS = 250L
        private val LOCK = Any()

        /** Held by writers of the sources and while a rebuild reads them. */
        @PublishedApi
        internal val SOURCE_LOCK = Any()
        private val refreshExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "DirectBootSnapshot").apply { isDaemon = true }
        }
        private val pendingRefresh = AtomicReference<Context?>(null)

        @VisibleForTesting
        @Volatile
        var faultInjector: ((stage: String) -> Unit)? = null

        private val LOCALE = Regex("[a-z]{2,3}([_-][A-Za-z0-9]{2,8})*")
        private val ACTION_ID = Regex("[a-z0-9_]{1,64}")
        private val COMMAND_ID = Regex("[A-Za-z0-9_.:-]{1,128}")

        /**
         * Variations, nav mode and locale-to-layout mappings, reduced to the entries their loaders
         * accept; a layout is referenced only by the id of a bundled or projected layout.
         */
        private val ROOT_FILES: Map<String, (JSONObject, Set<String>) -> JSONObject> = mapOf(
            "variations.json" to { json, layoutIds ->
                JSONObject().apply {
                    json.optJSONObject("variations")?.let { put("variations", variationsByCharacter(it)) }
                    listOf("staticVariations", "staticVariationsShift", "staticVariationsAlt", "emailVariations").forEach { key ->
                        json.optJSONArray(key)?.let { put(key, strings(it)) }
                    }
                    json.optJSONObject("layoutVariationOverrides")?.let { overrides ->
                        put("layoutVariationOverrides", JSONObject().apply {
                            overrides.keys().forEach { layout ->
                                if (layout in layoutIds) overrides.optJSONObject(layout)?.let { put(layout, variationsByCharacter(it)) }
                            }
                        })
                    }
                }
            },
            "ctrl_key_mappings.json" to { json, _ ->
                val mappings = json.getJSONObject("mappings")
                JSONObject().put("mappings", JSONObject().apply {
                    mappings.keys().forEach { key ->
                        val mapping = mappings.optJSONObject(key)?.takeIf { KeyMappingLoader.isCtrlKeyName(key) } ?: return@forEach
                        val value = { field: String, valid: (String) -> Boolean ->
                            (mapping.opt(field) as? String)?.takeIf(valid)
                        }
                        val projected = when (val type = mapping.opt("type")) {
                            "action" -> value("action", ACTION_ID::matches)?.let { JSONObject().put("type", type).put("action", it) }
                            "keycode" -> value("keycode", KeyMappingLoader::isCtrlTargetKeycode)
                                ?.let { JSONObject().put("type", type).put("keycode", it) }
                            "command" -> value("command", COMMAND_ID::matches)?.let { JSONObject().put("type", type).put("command", it) }
                            "native_ctrl", "none" -> JSONObject().put("type", type)
                            else -> null
                        }
                        projected?.let { put(key, it) }
                    }
                })
            },
            "locale_layout_mapping.json" to { json, layoutIds ->
                JSONObject().apply {
                    json.keys().forEach { locale ->
                        val layout = json.opt(locale) as? String
                        if (LOCALE.matches(locale) && layout in layoutIds) put(locale, layout)
                    }
                }
            }
        )

        private fun strings(array: JSONArray) = JSONArray().apply {
            for (index in 0 until array.length()) (array.opt(index) as? String)?.takeIf { it.isNotEmpty() }?.let { put(it) }
        }

        /** Only single base characters are read; see VariationRepository. */
        private fun variationsByCharacter(json: JSONObject) = JSONObject().apply {
            json.keys().forEach { key -> if (key.length == 1) json.optJSONArray(key)?.let { put(key, strings(it)) } }
        }

        private fun oneOf(values: Iterable<String>): (Any?) -> Boolean = values.toSet().let { allowed -> { it in allowed } }

        private val softwareKeyboardModes = SettingsManager.SoftwareKeyboardMode.entries.map { it.storageValue }
        private val softwareKeyboardOverrides = softwareKeyboardModes - SettingsManager.SoftwareKeyboardMode.AUTO.storageValue
        private val clicksButtonModes = oneOf(SettingsManager.ClicksPowerButtonMode.entries.map { it.persistedValue })

        /** Settings whose getters also accept other spellings; they enter in the canonical one, unknown text not at all. */
        private val CANONICAL_VALUES: Map<String, (String) -> String?> = mapOf(
            "physical_keyboard_profile_override" to SettingsManager::canonicalPhysicalKeyboardProfileOverride,
            "physical_keyboard_currency_symbol" to SettingsManager::canonicalPhysicalKeyboardCurrencySymbol
        )

        /**
         * Input and keyboard-surface settings without user content. Types follow the backup
         * schema; the keys it does not export are typed here. Values outside a known set are left out.
         */
        private val SNAPSHOT_SCHEMA: Map<String, Pair<PreferenceValueType, (Any?) -> Boolean>> = run {
            val localTypes = mapOf(
                SettingsManager.KEY_KEYBOARD_LAYOUT_AUTO_MAPPING_UPDATED to PreferenceValueType.LONG,
                "keyboard_bar_arrangement" to PreferenceValueType.STRING,
                "keyboard_bar_slim_suggestions" to PreferenceValueType.BOOLEAN,
                "keyboard_bar_slim_actions" to PreferenceValueType.BOOLEAN,
                "keyboard_bar_slim_bottom" to PreferenceValueType.BOOLEAN,
                "keyboard_bar_slim_android" to PreferenceValueType.BOOLEAN,
                "keyboard_bar_slim_inline" to PreferenceValueType.BOOLEAN,
                rocks.pkb.plektra.T2eCornerCalibration.KEY to PreferenceValueType.STRING
            )
            val values: Map<String, (Any?) -> Boolean> = mapOf(
                "clicks_button_mode" to clicksButtonModes,
                "clicks_meta_button_mode" to clicksButtonModes,
                "clicks_alt_button_mode" to clicksButtonModes,
                "clicks_microphone_button_mode" to clicksButtonModes,
                "software_keyboard_mode" to oneOf(softwareKeyboardModes),
                SettingsManager.KEY_SOFTWARE_KEYBOARD_MODE_RUNTIME_OVERRIDE to oneOf(softwareKeyboardOverrides),
                // Empty when no override was active before the field-scoped keypad.
                "field_scoped_virtual_previous_override" to oneOf(softwareKeyboardOverrides + ""),
                "software_keyboard_layout_style" to oneOf(SettingsManager.SoftwareKeyboardLayoutStyle.entries.map { it.storageValue }),
                "keyboard_bar_arrangement" to oneOf(KeyboardBarArrangement.entries.map { it.name })
            ) + CANONICAL_VALUES.mapValues { (_, canonical) -> { value: Any? -> value is String && canonical(value) == value } }
            listOf(
                "keyboard_layout", "keyboard_layout_list", "keyboard_layout_auto_by_locale",
                SettingsManager.KEY_KEYBOARD_LAYOUT_AUTO_MAPPING_UPDATED, "variations_updated", "nav_mode_mappings_updated",
                "physical_keyboard_profile_override", "physical_keyboard_currency_symbol", "emoji_style",
                "sym_mappings_custom", "sym_mappings_page2_custom", "sym_pages_config", "sym_auto_close", "sym_auto_close_on_touch",
                "restore_sym_page", "sym_edit_shortcuts",
                "alt_modifier_binding", "long_press_modifier", "long_press_threshold",
                "shift_tap_latches", "alt_tap_latches", "ctrl_tap_latches", "clear_alt_on_space",
                "alt_latch_stays_on_space", "ctrl_latch_stays_on_space",
                "smart_alt_off_after_opening", "smart_ctrl_off_after_shortcut",
                "shift_backspace_delete", "alt_backspace_delete", "backspace_at_start_delete",
                "alt_shift_layout_switch", "alt_enter_layout_switch", "ctrl_space_layout_switch",
                "alt_space_previous_language", "ctrl_language_previous", "toast_on_layout_switch",
                "layout_aware_ctrl_shortcuts", "software_ctrl_shortcut_display", "global_variation_layout_override",
                "custom_input_styles", "hidden_system_input_styles", "input_style_suggestion_locales",
                "auto_capitalize_first_letter", "auto_capitalize_after_period", "double_space_to_period",
                "spaced_hyphen_to_en_dash", "spaced_hyphen_dash_style", "mid_word_quote_to_apostrophe",
                "french_punctuation_spacing", "french_punctuation_only_french", "comma_space",
                "auto_space_punctuation", "space_after_punctuation", "smart_quotes", "smart_quotes_style",
                "swipe_to_delete", "swipe_to_delete_provider", "swipe_incremental_threshold",
                "auto_show_keyboard", "auto_show_respect_app_visibility", "hardware_keypad_auto_open", "hardware_only_mode",
                "slim_mode_override", "slim_mode_active", "overlapping_keys_enabled",
                "experimental_candidates_view_enabled", "modifier_indicator_mode", "status_bar_variations_visible",
                "tap_haptic_use_system", "tap_haptic_duration_ms",
                "app_enter_behavior_enabled", "app_enter_behavior_preset",
                "clicks_overlapping_keys_enabled", "clicks_overlapping_keys_mode", "clicks_number_row_input_mode",
                "clicks_number_row_repeat_enabled", "clicks_close_input_on_disconnect", "clicks_show_keyboard_only_with_text_focus",
                "clicks_button_mode", "clicks_meta_button_mode", "clicks_alt_button_mode", "clicks_microphone_button_mode",
                "software_keyboard_mode", SettingsManager.KEY_SOFTWARE_KEYBOARD_MODE_RUNTIME_OVERRIDE,
                "field_scoped_virtual_previous_override", "software_keyboard_mode_toggle_toasts",
                "software_keyboard_layout_style", "software_keyboard_number_row_enabled", "software_keyboard_key_hints_enabled",
                "software_keyboard_emoji_page_in_cycle", "software_keyboard_nearest_key_touch_enabled",
                "software_keyboard_left_modifier_key", "software_keyboard_right_modifier_key",
                "software_keyboard_long_press_layer_popup_enabled", "software_keyboard_long_press_layer_popup_below_key",
                "bounce_keys_enabled", "bounce_keys_delay_ms", "bounce_keys_character_keys_enabled",
                "bounce_keys_modifier_keys_enabled", "bounce_keys_space_enabled", "bounce_keys_enter_enabled",
                "bounce_keys_backspace_enabled",
                "static_variation_bar_mode", "static_variation_bar_preset", "static_variation_bar_base_layer_enabled",
                "static_variation_bar_modifier_hold_restoration",
                "dynamic_variation_bar_slot_count", "dynamic_variation_bar_resize_to_content", "shared_row_variations_first",
                "nav_mode_enabled", "nav_mode_ctrl_hold_enabled",
                "titan2_layout_enabled", "titan2_elite_rounded_corner_insets", "titan2_elite_fill_bottom_corners",
                "titan2_elite_top_corner_multiplier", "titan2_elite_max_icon_shrink", rocks.pkb.plektra.T2eCornerCalibration.KEY,
                "accessibility_live_announcements_enabled", "accessibility_read_second_row_enabled",
                "accessibility_suggestions_announcement_delay_ms",
                "status_bar_slot_left", "status_bar_slot_right_1", "status_bar_slot_right_2",
                "status_bar_slots_left", "status_bar_slots_right", "slim_status_bar_slots_left", "slim_status_bar_slots_right",
                "keyboard_bar_arrangement", "keyboard_bar_show_on_launcher", rocks.pkb.plektra.KeyboardBarTypography.KEY,
                "keyboard_bar_slim_suggestions", "keyboard_bar_slim_actions", "keyboard_bar_slim_bottom",
                "keyboard_bar_slim_android", "keyboard_bar_slim_inline"
            ).associateWith { key ->
                val type = checkNotNull(PreferenceSchemas.expectedType("plektra_prefs", key) ?: localTypes[key]) { key }
                type to (values[key] ?: { _: Any? -> true })
            }
        }

        /**
         * Applied over the snapshot before unlock: features backed by dictionaries, user text,
         * clipboard, speech or app launches stay off until credential-protected storage is available.
         */
        val LOCKED_FEATURE_OVERRIDES: Map<String, Any> = listOf(
            "experimental_suggestions_enabled", "suggestions_enabled", "auto_correct_enabled",
            "snippets_enabled", "emoji_shortcodes_enabled", "symbol_shortcodes_enabled",
            "clipboard_history_enabled", "alt_ctrl_speech_shortcut", "launcher_shortcuts_enabled", "power_shortcuts_enabled"
        ).associateWith { false }

        fun isSnapshotKey(key: String?): Boolean = key != null && key in SNAPSHOT_SCHEMA

        private fun isSnapshotValue(key: String, value: Any?): Boolean {
            val (type, valid) = SNAPSHOT_SCHEMA[key] ?: return false
            val typed = when (type) {
                PreferenceValueType.BOOLEAN -> value is Boolean
                PreferenceValueType.INT -> value is Int
                PreferenceValueType.LONG -> value is Long
                PreferenceValueType.FLOAT -> value is Float
                PreferenceValueType.STRING -> value is String
                PreferenceValueType.STRING_SET -> value is Set<*> && value.all { it is String }
            }
            return typed && valid(value)
        }

        fun isUserUnlocked(context: Context): Boolean =
            context.getSystemService(UserManager::class.java)?.isUserUnlocked == true

        /** Rebuilds the snapshot from credential-protected storage now; a no-op before unlock. */
        fun refresh(context: Context): Boolean {
            if (DirectBootStorage.isLocked(context)) return false
            return DirectBootKeyboardConfig(context)
                .refresh(SettingsManager.getPreferences(context), context.filesDir)
        }

        /**
         * Applies a change of the layouts, mappings or layout selection and publishes the result
         * before returning, so a selection that succeeded is also the one used before the next
         * unlock. A rebuild waits until the change is complete; nested changes publish once. A failed
         * rebuild keeps the previous snapshot and is retried once shortly afterwards.
         */
        inline fun <T> changeSources(context: Context, change: () -> T): T {
            if (Thread.holdsLock(SOURCE_LOCK)) return change()
            try {
                return synchronized(SOURCE_LOCK) { change() }
            } finally {
                if (!refreshNow(context)) requestRefresh(context)
            }
        }

        /**
         * Runs [transaction] as one unit towards writers of the sources and rebuilds: none of them
         * interleaves until it returns. `publish` publishes the sources' current state on the calling
         * thread and returns false when that fails. Not to be called within [changeSources].
         */
        fun <T> sourceTransaction(context: Context, transaction: (publish: () -> Boolean) -> T): T {
            check(!Thread.holdsLock(SOURCE_LOCK)) { "A source transaction cannot run within a change of the sources" }
            val config = DirectBootKeyboardConfig(context)
            val publish = {
                !DirectBootStorage.isLocked(context) &&
                    runCatching { config.refresh(SettingsManager.getPreferences(context), context.filesDir) }
                        .onFailure { Log.e(TAG, "Keeping the previous Direct Boot snapshot", it) }
                        .getOrDefault(false)
            }
            // Same order as a rebuild; refresh() re-enters both locks.
            return synchronized(LOCK) { synchronized(SOURCE_LOCK) { transaction(publish) } }
        }

        /**
         * Rebuilds the snapshot shortly afterwards on a serial background thread; requests made in
         * the meantime are coalesced. For settings whose loss on a sudden process death is harmless.
         */
        fun requestRefresh(context: Context) {
            if (DirectBootStorage.isLocked(context)) return
            if (pendingRefresh.getAndSet(context.applicationContext ?: context) == null) {
                refreshExecutor.schedule(::runPendingRefresh, REFRESH_DELAY_MS, TimeUnit.MILLISECONDS)
            }
        }

        /**
         * Runs a pending and then a fresh rebuild before returning; true when the published
         * snapshot matches the sources. Within a change, the change publishes on completion.
         */
        fun refreshNow(context: Context): Boolean {
            if (Thread.holdsLock(SOURCE_LOCK) || DirectBootStorage.isLocked(context)) return false
            return refreshExecutor.submit<Boolean> {
                runPendingRefresh()
                runCatching { refresh(context) }
                    .onFailure { Log.e(TAG, "Keeping the previous Direct Boot snapshot", it) }
                    .getOrDefault(false)
            }.get()
        }

        /** Waits until every requested rebuild ran. */
        @VisibleForTesting
        fun awaitPendingRefresh() {
            refreshExecutor.submit(::runPendingRefresh).get()
        }

        private fun runPendingRefresh() {
            val context = pendingRefresh.getAndSet(null) ?: return
            runCatching { refresh(context) }.onFailure { Log.e(TAG, "Keeping the previous Direct Boot snapshot", it) }
        }

        private fun encodeSettings(values: Map<String, *>): String = JSONObject().apply {
            values.toSortedMap().forEach { (key, value) ->
                val (type, encoded) = when (value) {
                    is String -> "string" to value
                    is Boolean -> "boolean" to value
                    is Int -> "int" to value
                    is Long -> "long" to value
                    is Float -> "float" to value.toDouble()
                    is Set<*> -> "set" to JSONArray(value.filterIsInstance<String>().sorted())
                    else -> return@forEach
                }
                put(key, JSONObject().put("type", type).put("value", encoded))
            }
        }.toString(2)

        private fun decodeSettings(text: String): Map<String, Any> {
            val json = JSONObject(text)
            return json.keys().asSequence().filter(::isSnapshotKey).mapNotNull { key ->
                val entry = json.getJSONObject(key)
                val value: Any = when (entry.getString("type")) {
                    "string" -> entry.getString("value")
                    "boolean" -> entry.getBoolean("value")
                    "int" -> entry.getInt("value")
                    "long" -> entry.getLong("value")
                    "float" -> entry.getDouble("value").toFloat()
                    "set" -> entry.getJSONArray("value").let { array -> (0 until array.length()).map(array::getString).toSet() }
                    else -> return@mapNotNull null
                }
                (key to value).takeIf { isSnapshotValue(key, value) }
            }.toMap()
        }
    }
}
