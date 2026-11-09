package rocks.pkb.plektra.backup

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import rocks.pkb.plektra.BuildConfig
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.data.layout.LayoutFileStore
import rocks.pkb.plektra.inputmethod.DeviceSpecific
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.time.OffsetDateTime
import java.time.ZoneOffset

object BackupManager {
    private const val TAG = "BackupManager"

    suspend fun createBackup(context: Context, targetUri: Uri): BackupResult = withContext(Dispatchers.IO) {
        val workingDir = File(context.cacheDir, "backup_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val customTypingSoundActive =
                SettingsManager.getTypingSoundMode(context) == SettingsManager.TYPING_SOUND_MODE_CUSTOM
            val hasTypingSoundPack = if (customTypingSoundActive) {
                val storedPackName = SettingsManager.getPreferences(context).getString(
                    SettingsManager.KEY_TYPING_SOUND_CUSTOM_FILE_NAME,
                    null
                )
                if (storedPackName != SettingsManager.TYPING_SOUND_CUSTOM_PACK_DIR) {
                    throw IllegalStateException("Custom typing sound mode has no supported sound pack selected")
                }
                if (!FileBackupHelper.validateTypingSoundPack(context.filesDir)) {
                    throw IllegalStateException("Custom typing sound mode requires a valid sound pack")
                }
                true
            } else {
                FileBackupHelper.hasValidTypingSoundPack(context.filesDir)
            }
            val prefsDir = File(workingDir, "prefs").apply { mkdirs() }
            val filesDir = File(workingDir, "files").apply { mkdirs() }

            val prefComponents = PreferencesBackupHelper.dumpSharedPreferences(
                context,
                prefsDir,
                hasTypingSoundPack
            )
            val fileComponents = FileBackupHelper.snapshotInternalFiles(
                context,
                filesDir,
                includeTypingSoundPack = hasTypingSoundPack
            )
            val exportedThemes = PreferencesBackupHelper.readPreferencesFromBackup(prefsDir)["plektra_prefs"].orEmpty()
                .filterKeys { it.startsWith("keyboard_theme_") }.values.mapNotNull { it.value as? String }
            val imageComponents = ThemeImageBackup.snapshot(context, File(workingDir, "theme-images"), exportedThemes)
            val components = (prefComponents + fileComponents + imageComponents).sorted()

            val metadata = BackupMetadata(
                versionCode = BuildConfig.VERSION_CODE,
                versionName = BuildConfig.VERSION_NAME,
                timestamp = OffsetDateTime.now(ZoneOffset.UTC).toString(),
                components = components,
                sourceDevice = DeviceSpecific.detectedDeviceIdentity()
            )
            File(workingDir, "backup_meta.json").writeText(metadata.toJsonString())

            context.contentResolver.openOutputStream(targetUri)?.use { output ->
                ZipHelper.zip(workingDir, output)
            } ?: return@withContext BackupResult.Failure("Unable to open target destination")

            BackupResult.Success(metadata)
        } catch (e: Exception) {
            Log.e(TAG, "Backup failed", e)
            BackupResult.Failure(e.message ?: "Backup failed")
        } finally {
            workingDir.deleteRecursively()
        }
    }
}

sealed class BackupResult {
    data class Success(val metadata: BackupMetadata) : BackupResult()
    data class Failure(val reason: String) : BackupResult()
}

data class PreferencesRestoreSummary(
    val appliedKeys: List<String>,
    val skippedKeys: List<String>
)

data class FileRestoreSummary(
    val restoredFiles: List<String>,
    val skippedFiles: List<String>,
    val missingDictionaries: List<String> = emptyList()
)

object PreferencesBackupHelper {
    private const val TAG = "PreferencesBackup"
    private val typingSoundPackPreferenceKeys = setOf(
        SettingsManager.KEY_TYPING_SOUND_CUSTOM_FILE_NAME,
        SettingsManager.KEY_TYPING_SOUND_CUSTOM_DISPLAY_NAME
    )

    fun dumpSharedPreferences(
        context: Context,
        destinationDir: File,
        hasTypingSoundPack: Boolean = FileBackupHelper.hasValidTypingSoundPack(context.filesDir)
    ): List<String> {
        val sharedPrefsDir = File(context.dataDir, "shared_prefs")
        if (!sharedPrefsDir.exists()) {
            return emptyList()
        }

        val components = mutableListOf<String>()
        sharedPrefsDir.listFiles { file -> file.extension == "xml" }?.forEach { file ->
            val prefName = file.name.removeSuffix(".xml")
            if (!BackupPreferenceContract.shouldExportPreferenceFile(prefName)) {
                return@forEach
            }
            val prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
            val prefsJson = buildPreferencesJson(prefName, prefs, hasTypingSoundPack)
            val outFile = File(destinationDir, "$prefName.json")
            outFile.writeText(prefsJson.toString(2))
            components.add("prefs/${outFile.name}")
        }
        return components
    }

    private fun buildPreferencesJson(
        prefName: String,
        prefs: SharedPreferences,
        hasTypingSoundPack: Boolean
    ): JSONObject {
        val json = JSONObject()
        json.put("name", prefName)
        val entries = JSONObject()
        prefs.all.forEach { (key, value) ->
            val requiresTypingSoundPack = prefName == "plektra_prefs" && (
                key in typingSoundPackPreferenceKeys ||
                    key == SettingsManager.KEY_TYPING_SOUND_MODE &&
                    value == SettingsManager.TYPING_SOUND_MODE_CUSTOM
                )
            if (requiresTypingSoundPack && !hasTypingSoundPack) {
                Log.w(TAG, "Not exporting custom typing-sound state without a valid pack")
                return@forEach
            }
            val expectedType = BackupPreferenceContract.expectedExportType(prefName, key)
            if (expectedType == null) {
                Log.i(TAG, "Not exporting unclassified or non-user preference $prefName:$key")
                return@forEach
            }
            val prefValue = PreferenceValue.fromAny(value)?.coerceTo(expectedType)
            if (prefValue == null) {
                Log.w(TAG, "Not exporting preference with incompatible type $prefName:$key")
                return@forEach
            }
            entries.put(key, prefValue.toJson())
        }
        if (prefName == "notes") {
            NotesBackupValidation.validate(prefs.all.mapNotNull { (key, value) ->
                PreferenceValue.fromAny(value)?.let { key to it }
            }.toMap())
        }
        json.put("entries", entries)
        return json
    }

    fun readPreferencesFromBackup(prefsDir: File): Map<String, Map<String, PreferenceValue>> {
        if (!prefsDir.exists() || !prefsDir.isDirectory) {
            return emptyMap()
        }
        val result = mutableMapOf<String, Map<String, PreferenceValue>>()
        prefsDir.listFiles { file -> file.extension == "json" }?.forEach { file ->
            val targetName = PreferenceSchemas.restorePreferenceFileName(file.nameWithoutExtension)
            if (!BackupPreferenceContract.shouldExportPreferenceFile(targetName)) return@forEach
            try {
                val content = file.readText()
                val json = JSONObject(content)
                require(json.optString("name", file.nameWithoutExtension) == file.nameWithoutExtension) {
                    "Preference file name does not match its contents"
                }
                val entriesJson = json.getJSONObject("entries")
                val entries = mutableMapOf<String, PreferenceValue>()
                val keys = entriesJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val targetKey = PreferenceSchemas.restoreKey(targetName, key)
                    if (!PreferenceSchemas.isRecognized(targetName, targetKey)) continue
                    val valueJson = entriesJson.getJSONObject(key)
                    if (valueJson.optString("type").equals("string_set", ignoreCase = true)) {
                        val items = valueJson.getJSONArray("value")
                        require((0 until items.length()).all { items.get(it) is String }) {
                            "Invalid string set: $key"
                        }
                    }
                    val prefValue = PreferenceValue.fromJson(valueJson)
                        ?: throw IOException("Invalid preference type: $key")
                    requireValidPreferenceValue(prefValue)
                    val coerced = prefValue.coerceTo(PreferenceSchemas.expectedType(targetName, targetKey))
                    require(coerced != null) {
                        "Incompatible preference type: $key"
                    }
                    requireValidPreferenceValue(coerced)
                    if (prefValue.value is Number && coerced.value is Number) {
                        require(prefValue.value.toString().toBigDecimal().compareTo(
                            coerced.value.toString().toBigDecimal()) == 0) { "Lossy preference conversion: $key" }
                    }
                    entries[key] = prefValue
                }
                if (targetName == "notes") NotesBackupValidation.validate(entries)
                result[file.nameWithoutExtension] = entries
            } catch (e: Exception) {
                throw IOException("Invalid preferences backup: ${file.name}", e)
            }
        }
        return PreferenceSchemas.normalizeForRestore(result)
    }

    private fun requireValidPreferenceValue(value: PreferenceValue) {
        val valid = when (value.type) {
            PreferenceValueType.BOOLEAN -> value.value is Boolean
            PreferenceValueType.INT -> value.value is Number &&
                value.value.toString().toBigDecimal().compareTo(value.value.toInt().toBigDecimal()) == 0
            PreferenceValueType.LONG -> value.value is Number &&
                value.value.toString().toBigDecimal().compareTo(value.value.toLong().toBigDecimal()) == 0
            PreferenceValueType.FLOAT -> value.value is Number && value.value.toFloat().isFinite()
            PreferenceValueType.STRING -> value.value is String
            PreferenceValueType.STRING_SET -> value.value is Set<*> && value.value.all { it is String }
        }
        require(valid) { "Invalid preference value" }
    }

    fun restorePreferences(
        context: Context,
        backedUpPrefs: Map<String, Map<String, PreferenceValue>>,
        excludedKeys: Set<String> = emptySet(),
        hasRestoredTypingSoundPack: Boolean = false
    ): PreferencesRestoreSummary {
        val applied = mutableListOf<String>()
        val skipped = mutableListOf<String>()

        PreferenceSchemas.normalizeForRestore(backedUpPrefs).forEach { (prefName, entries) ->
            if (!BackupPreferenceContract.shouldExportPreferenceFile(prefName)) {
                skipped.addAll(entries.keys.map { "$prefName:$it" })
                return@forEach
            }
            if (prefName == "notes") NotesBackupValidation.validate(entries)
            val prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
            val editor = prefs.edit()

            entries.forEach { (key, value) ->
                val qualifiedKey = "$prefName:$key"
                if (excludedKeys.contains(qualifiedKey)) {
                    skipped.add(qualifiedKey)
                    return@forEach
                }
                val expectedType = PreferenceSchemas.expectedType(prefName, key)
                val recognized = PreferenceSchemas.isRecognized(prefName, key)
                if (!recognized) {
                    Log.w(TAG, "Ignoring unknown preference key $key for $prefName")
                    skipped.add(qualifiedKey)
                    return@forEach
                }

                if (!isTypingSoundPreferenceRestorable(
                        prefName,
                        key,
                        value,
                        hasRestoredTypingSoundPack
                    )
                ) {
                    skipped.add(qualifiedKey)
                    return@forEach
                }

                val coerced = value.coerceTo(expectedType)
                if (coerced == null) {
                    skipped.add(qualifiedKey)
                    return@forEach
                }

                val restoredKey = PreferenceSchemas.restoreKey(prefName, key)
                when (coerced.type) {
                    PreferenceValueType.BOOLEAN -> editor.putBoolean(restoredKey, coerced.value as Boolean)
                    PreferenceValueType.INT -> editor.putInt(restoredKey, (coerced.value as Number).toInt())
                    PreferenceValueType.LONG -> editor.putLong(restoredKey, (coerced.value as Number).toLong())
                    PreferenceValueType.FLOAT -> editor.putFloat(restoredKey, (coerced.value as Number).toFloat())
                    PreferenceValueType.STRING -> {
                        val restoredValue = when {
                            key == "pastierina_mode_override" && coerced.value == "pastierina" -> "slim"
                            else -> coerced.value?.toString()
                        }
                        editor.putString(restoredKey, restoredValue)
                    }
                    PreferenceValueType.STRING_SET -> {
                        val setValue = (coerced.value as? Set<*>)?.mapNotNull { it?.toString() }?.toSet()
                        if (setValue != null) {
                            editor.putStringSet(restoredKey, setValue)
                        } else {
                            skipped.add(qualifiedKey)
                        }
                    }
                }
                applied.add(qualifiedKey)
            }
            // Use commit() instead of apply() to ensure values are written synchronously
            // This ensures listeners are called immediately and UI updates work correctly
            check(editor.commit()) { "Unable to save restored preferences" }
        }

        return PreferencesRestoreSummary(appliedKeys = applied, skippedKeys = skipped)
    }

    private fun isTypingSoundPreferenceRestorable(
        prefName: String,
        key: String,
        value: PreferenceValue,
        hasRestoredTypingSoundPack: Boolean
    ): Boolean {
        if (prefName != "plektra_prefs") return true
        return when (key) {
            SettingsManager.KEY_TYPING_SOUND_CUSTOM_FILE_NAME ->
                value.value == SettingsManager.TYPING_SOUND_CUSTOM_PACK_DIR && hasRestoredTypingSoundPack
            SettingsManager.KEY_TYPING_SOUND_CUSTOM_DISPLAY_NAME -> hasRestoredTypingSoundPack
            SettingsManager.KEY_TYPING_SOUND_MODE ->
                value.value != SettingsManager.TYPING_SOUND_MODE_CUSTOM || hasRestoredTypingSoundPack
            else -> true
        }
    }
}

object FileBackupHelper {
    private const val TAG = "FileBackupHelper"
    private val allowedFiles = setOf(
        "ctrl_key_mappings.json",
        "variations.json",
        "user_defaults.json",
        "locale_layout_mapping.json",
        DictionaryPackageBackup.MANIFEST
    )
    private val allowedDirectories = setOf(
        "keyboard_layouts",
        DictionaryPackageBackup.DIRECTORY
    )

    internal fun validateRestoreFiles(filesDir: File): Int {
        validateTypingSoundPack(filesDir)
        DictionaryPackageBackup.validate(filesDir)
        var count = 0
        filesDir.walkTopDown().filter { it.isFile }.forEach { file ->
            val relative = file.toRelativeString(filesDir).replace("\\", "/")
            if (shouldBackupPath(relative, includeTypingSoundPack = true)) {
                require(!file.extension.equals("json", ignoreCase = true) || isJsonValid(file)) {
                    "Invalid JSON file in backup: $relative"
                }
                if (relative.startsWith("keyboard_layouts/")) {
                    require(file.extension.equals("json", ignoreCase = true) &&
                        file.inputStream().use { LayoutFileStore.loadLayoutFromStream(it) } != null) {
                        "Invalid keyboard layout in backup: $relative"
                    }
                }
                if (relative in allowedFiles && relative != "user_defaults.json") {
                    JSONObject(file.readText())
                }
                count++
            }
        }
        return count
    }

    internal fun managedRestoreRoots(): Set<String> =
        allowedFiles + allowedDirectories + SettingsManager.TYPING_SOUND_CUSTOM_DIR

    fun snapshotInternalFiles(
        context: Context,
        destinationDir: File,
        includeTypingSoundPack: Boolean = hasValidTypingSoundPack(context.filesDir)
    ): List<String> {
        val base = context.filesDir
        if (!base.exists()) {
            return emptyList()
        }

        val components = mutableListOf<String>()
        base.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val relative = file.toRelativeString(base).replace("\\", "/")
                if (relative.startsWith("${DictionaryPackageBackup.DIRECTORY}/") ||
                    relative == DictionaryPackageBackup.MANIFEST ||
                    !shouldBackupPath(relative, includeTypingSoundPack)) {
                    return@forEach
                }
                val target = File(destinationDir, relative)
                target.parentFile?.mkdirs()
                file.copyTo(target, overwrite = true)
                components.add("files/$relative")
            }
        components.addAll(DictionaryPackageBackup.snapshot(context, destinationDir).map { "files/$it" })
        return components.distinct()
    }

    fun restoreFiles(context: Context, extractedFilesDir: File): FileRestoreSummary {
        val restored = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        var restoredVariations = false

        if (!extractedFilesDir.exists()) {
            ensureDefaults(context)
            return FileRestoreSummary(restoredFiles = restored, skippedFiles = skipped)
        }
        validateTypingSoundPack(extractedFilesDir)
        DictionaryPackageBackup.validate(extractedFilesDir)

        val targetRoot = context.filesDir
        val backups = mutableListOf<Pair<File, File>>()

        try {
            extractedFilesDir.walkTopDown()
                .filter { it.isFile }
                .forEach { source ->
                    val relative = source.toRelativeString(extractedFilesDir).replace("\\", "/")
                    if (relative == DictionaryPackageBackup.MANIFEST || isTypingSoundPackPath(relative)) {
                        return@forEach
                    }
                    if (!shouldBackupPath(relative, includeTypingSoundPack = true)) {
                        skipped.add(relative)
                        return@forEach
                    }

                    if (source.extension.equals("json", ignoreCase = true) && !isJsonValid(source)) {
                        Log.w(TAG, "Skipping invalid JSON file from backup: $relative")
                        skipped.add(relative)
                        return@forEach
                    }

                    val target = File(targetRoot, relative)
                    target.parentFile?.mkdirs()
                    if (target.exists()) {
                        val backupFile = File.createTempFile("restore_backup_", ".bak", context.cacheDir)
                        target.copyTo(backupFile, overwrite = true)
                        backups.add(target to backupFile)
                    }
                    
                    // Special handling for variations.json: merge with defaults instead of overwriting
                    if (relative.equals("variations.json", ignoreCase = true)) {
                        mergeVariationsFile(context, source, target)
                        restoredVariations = true
                    } else {
                        source.copyTo(target, overwrite = true)
                    }
                    restored.add(relative)
                }
            restored.addAll(
                replaceTypingSoundPack(
                    targetRoot = targetRoot,
                    rollbackRoot = context.cacheDir,
                    extractedFilesRoot = extractedFilesDir
                )
            )
        } catch (e: Exception) {
            backups.reversed().forEach { (target, backup) ->
                runCatching { backup.copyTo(target, overwrite = true) }
            }
            throw e
        } finally {
            backups.forEach { (_, backup) -> backup.delete() }
            ensureDefaults(context)
            if (restoredVariations) {
                runCatching { SettingsManager.notifyVariationsUpdated(context) }
            }
        }

        return FileRestoreSummary(restoredFiles = restored, skippedFiles = skipped,
            missingDictionaries = DictionaryPackageBackup.missing(context, extractedFilesDir))
    }

    internal fun replaceTypingSoundPack(
        targetRoot: File,
        rollbackRoot: File,
        extractedFilesRoot: File,
        installStagedPack: (File, File) -> Unit = { staging, target ->
            if (!staging.renameTo(target) && !staging.copyRecursively(target, overwrite = true)) {
                throw IOException("Unable to install restored typing sound pack")
            }
        }
    ): List<String> {
        if (!validateTypingSoundPack(extractedFilesRoot)) return emptyList()

        val relativePackPath =
            "${SettingsManager.TYPING_SOUND_CUSTOM_DIR}/${SettingsManager.TYPING_SOUND_CUSTOM_PACK_DIR}"
        val sourcePack = File(extractedFilesRoot, relativePackPath)
        val targetSoundRoot = File(targetRoot, SettingsManager.TYPING_SOUND_CUSTOM_DIR).apply { mkdirs() }
        val targetPack = File(targetSoundRoot, SettingsManager.TYPING_SOUND_CUSTOM_PACK_DIR)
        val uniqueSuffix = System.nanoTime().toString()
        val stagingPack = File(targetSoundRoot, "${SettingsManager.TYPING_SOUND_CUSTOM_PACK_DIR}_restore_$uniqueSuffix")
        val rollbackPack = File(rollbackRoot, "typing_sound_pack_rollback_$uniqueSuffix")
        val restoredPaths = sourcePack.walkTopDown()
            .filter(File::isFile)
            .map { file -> file.toRelativeString(extractedFilesRoot).replace("\\", "/") }
            .toList()

        try {
            if (!sourcePack.copyRecursively(stagingPack, overwrite = true)) {
                throw IOException("Unable to stage restored typing sound pack")
            }
            if (targetPack.exists() && !targetPack.copyRecursively(rollbackPack, overwrite = true)) {
                throw IOException("Unable to preserve existing typing sound pack")
            }

            try {
                if (targetPack.exists() && !targetPack.deleteRecursively()) {
                    throw IOException("Unable to remove existing typing sound pack")
                }
                installStagedPack(stagingPack, targetPack)
            } catch (error: Exception) {
                targetPack.deleteRecursively()
                if (rollbackPack.exists() && !rollbackPack.copyRecursively(targetPack, overwrite = true)) {
                    error.addSuppressed(IOException("Unable to roll back existing typing sound pack"))
                }
                throw error
            }
            return restoredPaths
        } finally {
            stagingPack.deleteRecursively()
            rollbackPack.deleteRecursively()
        }
    }

    private fun shouldBackupPath(relative: String, includeTypingSoundPack: Boolean): Boolean {
        val normalized = relative.removePrefix("./")
        if (allowedFiles.contains(normalized)) {
            return true
        }
        if (includeTypingSoundPack && isTypingSoundPackPath(normalized)) {
            return true
        }
        return allowedDirectories.any { dir ->
            normalized == dir || normalized.startsWith("$dir/")
        }
    }

    internal fun hasValidTypingSoundPack(root: File): Boolean =
        runCatching { validateTypingSoundPack(root) }.getOrDefault(false)

    internal fun validateTypingSoundPack(root: File): Boolean {
        val typingSoundRoot = File(root, SettingsManager.TYPING_SOUND_CUSTOM_DIR)
        if (!typingSoundRoot.exists()) return false

        val canonicalRootPath = typingSoundRoot.canonicalFile.toPath()
        val files = typingSoundRoot.walkTopDown().filter(File::isFile).toList()
        if (files.isEmpty()) return false
        if (files.size > SettingsManager.TYPING_SOUND_MAX_PACK_FILES) {
            throw IllegalArgumentException("Typing sound pack has too many files")
        }

        var totalBytes = 0L
        var hasNormalSound = false
        files.forEach { file ->
            val canonicalFilePath = file.canonicalFile.toPath()
            if (!canonicalFilePath.startsWith(canonicalRootPath)) {
                throw IllegalArgumentException("Typing sound pack escapes its storage directory")
            }
            val relative = file.toRelativeString(root).replace("\\", "/")
            if (!isTypingSoundPackPath(relative)) {
                throw IllegalArgumentException("Unsupported typing sound pack path: $relative")
            }
            if (file.length() > SettingsManager.TYPING_SOUND_MAX_FILE_BYTES) {
                throw IllegalArgumentException("Typing sound pack file exceeds size limit")
            }
            if (!TypingSoundAudioValidator.hasSupportedAudioContent(file)) {
                throw IllegalArgumentException("Typing sound pack contains invalid audio content")
            }
            totalBytes += file.length()
            if (totalBytes > SettingsManager.TYPING_SOUND_MAX_PACK_BYTES) {
                throw IllegalArgumentException("Typing sound pack exceeds total size limit")
            }
            if (relative.split('/')[2] == "normal") {
                hasNormalSound = true
            }
        }
        if (!hasNormalSound) {
            throw IllegalArgumentException("Typing sound pack has no normal-key sound")
        }
        return true
    }

    private fun isTypingSoundPackPath(relative: String): Boolean {
        val parts = relative.split('/')
        return parts.size == 4 &&
            parts[0] == SettingsManager.TYPING_SOUND_CUSTOM_DIR &&
            parts[1] == SettingsManager.TYPING_SOUND_CUSTOM_PACK_DIR &&
            parts[2] in SettingsManager.TYPING_SOUND_GROUPS &&
            parts[3].substringAfterLast('.', "").lowercase() in SettingsManager.TYPING_SOUND_AUDIO_EXTENSIONS
    }

    private fun isJsonValid(file: File): Boolean {
        return try {
            val text = file.readText()
            val trimmed = text.trim()
            if (trimmed.startsWith("[")) {
                org.json.JSONArray(trimmed)
            } else {
                JSONObject(trimmed)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun ensureDefaults(context: Context) {
        try {
            LayoutFileStore.getLayoutsDirectory(context)
            SettingsManager.initializeNavModeMappingsFile(context)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to ensure default config files", e)
        }
    }

    /**
     * Merges variations.json from backup with current/default values.
     * Preserves default keys (like emailVariations) that may not exist in older backups.
     */
    private fun mergeVariationsFile(context: Context, backupFile: File, targetFile: File) {
        try {
            // Load current/default JSON (from file or assets)
            val currentJson = loadCurrentVariationsJson(context)
            
            // Load backup JSON
            val backupJsonString = backupFile.readText()
            val backupJson = JSONObject(backupJsonString)
            
            // Create merged JSON starting with current/default
            val mergedJson = if (currentJson != null) {
                JSONObject(currentJson.toString())
            } else {
                JSONObject()
            }
            
            // Merge backup values into merged JSON
            // This preserves user customizations from backup while keeping defaults for missing keys
            val backupKeys = backupJson.keys()
            while (backupKeys.hasNext()) {
                val key = backupKeys.next()
                val backupValue = backupJson.get(key)
                mergedJson.put(key, backupValue)
            }
            
            // Write merged JSON to target file
            targetFile.writeText(mergedJson.toString(2))
            Log.d(TAG, "Merged variations.json from backup, preserving default keys")
        } catch (e: Exception) {
            Log.e(TAG, "Error merging variations.json, falling back to direct copy", e)
            // Fallback: if merge fails, copy directly
            backupFile.copyTo(targetFile, overwrite = true)
        }
    }
    
    /**
     * Loads current variations.json from file or assets (default).
     */
    private fun loadCurrentVariationsJson(context: Context): JSONObject? {
        return try {
            val variationsFile = File(context.filesDir, "variations.json")
            val jsonString = if (variationsFile.exists()) {
                variationsFile.readText()
            } else {
                // Load from assets (default)
                context.assets.open("common/variations/variations.json").bufferedReader().use { it.readText() }
            }
            JSONObject(jsonString)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading current variations.json", e)
            null
        }
    }
}

internal object TypingSoundAudioValidator {
    private const val MAX_PROBE_BYTES = 128 * 1024
    private const val MAX_SAMPLE_PROBE_BYTES = 1024 * 1024

    fun hasSupportedAudioContent(file: File): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        val probe = readProbe(file) ?: return false
        val hasExpectedContainer = when (file.extension.lowercase()) {
            "ogg" -> probe.startsWithAscii("OggS") && (
                probe.containsAscii("vorbis") ||
                    probe.containsAscii("OpusHead") ||
                    probe.containsBytes(
                        byteArrayOf(
                            0x7f,
                            'F'.code.toByte(),
                            'L'.code.toByte(),
                            'A'.code.toByte(),
                            'C'.code.toByte()
                        )
                    )
                )
            "wav" ->
                probe.startsWithAscii("RIFF") &&
                    probe.hasAsciiAt("WAVE", 8) &&
                    probe.containsAscii("fmt ")
            "mp3" -> probe.containsMpegAudioFrame()
            "m4a" -> probe.hasAsciiAt("ftyp", 4) && (
                probe.containsAscii("M4A ") ||
                    probe.containsAscii("isom") ||
                    probe.containsAscii("mp42") ||
                    probe.containsAscii("mp41")
                )
            else -> false
        }
        return hasExpectedContainer && hasDecodableAudioSample(file)
    }

    private fun hasDecodableAudioSample(file: File): Boolean = runCatching {
        val extractor = MediaExtractor()
        try {
            FileInputStream(file).use { input ->
                extractor.setDataSource(input.fd)
                val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                    val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    mime?.startsWith("audio/") == true
                } ?: return@runCatching false
                extractor.selectTrack(trackIndex)
                val sampleBuffer = ByteBuffer.allocate(
                    minOf(file.length(), MAX_SAMPLE_PROBE_BYTES.toLong()).toInt()
                )
                extractor.readSampleData(sampleBuffer, 0) > 0
            }
        } finally {
            extractor.release()
        }
    }.getOrDefault(false)

    private fun readProbe(file: File): ByteArray? = runCatching {
        val targetSize = minOf(file.length(), MAX_PROBE_BYTES.toLong()).toInt()
        val probe = ByteArray(targetSize)
        FileInputStream(file).use { input ->
            var offset = 0
            while (offset < probe.size) {
                val read = input.read(probe, offset, probe.size - offset)
                if (read < 0) break
                if (read == 0) continue
                offset += read
            }
            if (offset == probe.size) probe else probe.copyOf(offset)
        }
    }.getOrNull()

    private fun ByteArray.startsWithAscii(value: String): Boolean = hasAsciiAt(value, 0)

    private fun ByteArray.hasAsciiAt(value: String, offset: Int): Boolean {
        val expected = value.toByteArray(Charsets.US_ASCII)
        if (offset < 0 || offset + expected.size > size) return false
        return expected.indices.all { index -> this[offset + index] == expected[index] }
    }

    private fun ByteArray.containsAscii(value: String): Boolean =
        containsBytes(value.toByteArray(Charsets.US_ASCII))

    private fun ByteArray.containsBytes(value: ByteArray): Boolean {
        if (value.isEmpty() || value.size > size) return false
        return (0..size - value.size).any { offset ->
            value.indices.all { index -> this[offset + index] == value[index] }
        }
    }

    private fun ByteArray.containsMpegAudioFrame(): Boolean {
        if (size < 4) return false
        for (index in 0 until size - 3) {
            val first = this[index].toInt() and 0xff
            val second = this[index + 1].toInt() and 0xff
            val third = this[index + 2].toInt() and 0xff
            val sync = first == 0xff && second and 0xe0 == 0xe0
            val validVersion = second and 0x18 != 0x08
            val validLayer = second and 0x06 != 0
            val validBitrate = third and 0xf0 != 0 && third and 0xf0 != 0xf0
            val validSampleRate = third and 0x0c != 0x0c
            if (sync && validVersion && validLayer && validBitrate && validSampleRate) return true
        }
        return false
    }
}
