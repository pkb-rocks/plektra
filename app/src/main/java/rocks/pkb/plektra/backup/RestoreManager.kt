package rocks.pkb.plektra.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import rocks.pkb.plektra.DeviceIdentitySnapshot
import rocks.pkb.plektra.AppBroadcastActions
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.inputmethod.DeviceSpecific
import rocks.pkb.plektra.inputmethod.subtype.AdditionalSubtypeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

object RestoreManager {
    private const val TAG = "RestoreManager"
    private const val USER_DICTIONARY_PREF_KEY = "plektra_prefs:user_dictionary_entries"
    private const val CUSTOM_INPUT_STYLES_PREF_KEY = "plektra_prefs:custom_input_styles"
    private const val USER_DEFAULTS_FILE_NAME = "user_defaults.json"

    enum class PostRestoreAction {
        REFRESH_USER_DICTIONARY,
        REGISTER_CUSTOM_INPUT_STYLES
    }

    enum class ImportMode {
        UNCHANGED,
        ADAPT_TO_CURRENT_DEVICE
    }

    data class DeviceChange(
        val source: DeviceIdentitySnapshot,
        val target: DeviceIdentitySnapshot
    )

    private data class PostRestoreTriggerRule(
        val action: PostRestoreAction,
        val matchingAppliedPrefKeys: Set<String> = emptySet(),
        val matchingRestoredFileNames: Set<String> = emptySet()
    )

    private val postRestoreTriggerRules = listOf(
        PostRestoreTriggerRule(
            action = PostRestoreAction.REFRESH_USER_DICTIONARY,
            matchingAppliedPrefKeys = setOf(USER_DICTIONARY_PREF_KEY),
            matchingRestoredFileNames = setOf(USER_DEFAULTS_FILE_NAME)
        ),
        PostRestoreTriggerRule(
            action = PostRestoreAction.REGISTER_CUSTOM_INPUT_STYLES,
            matchingAppliedPrefKeys = setOf(CUSTOM_INPUT_STYLES_PREF_KEY)
        )
    )

    suspend fun inspect(context: Context, sourceUri: Uri): RestoreInspectionResult =
        withContext(Dispatchers.IO) {
            val workingDir = File(context.cacheDir, "restore_inspection_${System.currentTimeMillis()}")
                .apply { mkdirs() }
            val extractedDir = File(workingDir, "unzipped").apply { mkdirs() }
            try {
                context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    ZipHelper.unzip(input, extractedDir)
                } ?: return@withContext RestoreInspectionResult.Failure("Unable to open source backup")

                val (metadata, _) = readAndValidateBackup(extractedDir)
                val currentDevice = DeviceSpecific.detectedDeviceIdentity()
                RestoreInspectionResult.Success(
                    metadata = metadata,
                    deviceChange = detectDeviceChange(metadata?.sourceDevice, currentDevice)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Restore inspection failed", e)
                RestoreInspectionResult.Failure(e.message ?: "Unable to inspect backup")
            } finally {
                workingDir.deleteRecursively()
            }
        }

    suspend fun restore(
        context: Context,
        sourceUri: Uri,
        importMode: ImportMode = ImportMode.UNCHANGED
    ): RestoreResult = withContext(Dispatchers.IO) {
        val workingDir = File(context.cacheDir, "restore_${System.currentTimeMillis()}").apply { mkdirs() }
        val extractedDir = File(workingDir, "unzipped").apply { mkdirs() }
        var retainRecoveryData = false

        try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                ZipHelper.unzip(input, extractedDir)
            } ?: return@withContext RestoreResult.Failure("Unable to open source backup")

            val (metadata, prefsData) = readAndValidateBackup(extractedDir)
            val filesDir = File(extractedDir, "files")

            val snapshot = RestoreSnapshot(context, File(workingDir, "rollback"))
            try {
                val fileSummary = FileBackupHelper.restoreFiles(context, filesDir)
                val excludedKeys = BackupPreferencePolicy.runtimeDerivedKeys +
                    if (importMode == ImportMode.ADAPT_TO_CURRENT_DEVICE) {
                        BackupPreferencePolicy.targetDeviceDerivedKeys
                    } else {
                        emptySet()
                    }
                val prefsSummary = PreferencesBackupHelper.restorePreferences(
                    context,
                    prefsData,
                    excludedKeys = excludedKeys,
                    hasRestoredTypingSoundPack = fileSummary.restoredFiles.any { path ->
                        path.startsWith(
                            "${SettingsManager.TYPING_SOUND_CUSTOM_DIR}/" +
                                "${SettingsManager.TYPING_SOUND_CUSTOM_PACK_DIR}/"
                        )
                    }
                )
                // A backup from a build that allowed both may carry Screen trackpad and
                // Trackpad Gestures on together; keep exactly one (Screen trackpad wins).
                rocks.pkb.plektra.TrackpadFeatureExclusivity.reconcile(context)
                val postRestoreActions = collectTriggeredPostRestoreActions(prefsSummary, fileSummary)
                notifyPostRestoreEffects(context, postRestoreActions)
                if (fileSummary.restoredFiles.any { it.startsWith("dictionaries_serialized/") }) {
                    rocks.pkb.plektra.dictionaries.DictionaryRepositoryManager.notifyDictionariesChanged(context)
                }

                RestoreResult.Success(
                    metadata = metadata,
                    preferencesSummary = prefsSummary,
                    fileSummary = fileSummary,
                    postActionsTriggered = postRestoreActions
                )
            } catch (failure: Exception) {
                try {
                    snapshot.rollback()
                } catch (rollbackFailure: Exception) {
                    failure.addSuppressed(rollbackFailure)
                    retainRecoveryData = true
                    throw IOException("Import failed and recovery is incomplete. Recovery data retained in " +
                        "cache/${workingDir.name}/rollback", failure)
                }
                throw failure
            }
        } catch (e: Exception) {
            Log.e(TAG, "Restore failed", e)
            RestoreResult.Failure(e.message ?: "Restore failed")
        } finally {
            extractedDir.deleteRecursively()
            if (!retainRecoveryData) workingDir.deleteRecursively()
        }
    }

    private fun readAndValidateBackup(
        extractedDir: File
    ): Pair<BackupMetadata?, Map<String, Map<String, PreferenceValue>>> {
        val metadataFile = File(extractedDir, "backup_meta.json")
        val metadata = BackupMetadata.fromFile(metadataFile)
        require(!metadataFile.exists() || metadata != null) { "Invalid backup metadata" }
        val prefs = PreferencesBackupHelper.readPreferencesFromBackup(File(extractedDir, "prefs"))
        val fileCount = FileBackupHelper.validateRestoreFiles(File(extractedDir, "files"))
        require(prefs.values.any { it.isNotEmpty() } || fileCount > 0) {
            "Backup contains no supported settings or user files"
        }
        return metadata to prefs
    }

    private class RestoreSnapshot(private val context: Context, private val directory: File) {
        private val preferenceFiles = listOf("plektra_prefs", "notes").associateWith { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).all.mapValues { (_, value) -> PreferenceValue.fromAny(value)!! }
        }
        private val roots = FileBackupHelper.managedRestoreRoots()

        init {
            directory.mkdirs()
            val entries = org.json.JSONObject()
            preferenceFiles.forEach { (name, preferences) ->
                val values = org.json.JSONObject()
                preferences.forEach { (key, value) -> values.put(key, value.toJson()) }
                entries.put(name, values)
            }
            File(directory, "preferences.json").writeText(entries.toString())
            roots.forEach { name ->
                val source = File(context.filesDir, name)
                if (source.exists()) copy(source, File(directory, name))
            }
        }

        fun rollback() {
            roots.forEach { name ->
                val target = File(context.filesDir, name)
                check(!target.exists() || target.deleteRecursively()) { "Unable to roll back $name" }
                val original = File(directory, name)
                if (original.exists()) copy(original, target)
            }
            preferenceFiles.forEach { (name, preferences) ->
                val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
                preferences.forEach { (key, value) ->
                    when (value.type) {
                        PreferenceValueType.BOOLEAN -> editor.putBoolean(key, value.value as Boolean)
                        PreferenceValueType.INT -> editor.putInt(key, value.value as Int)
                        PreferenceValueType.LONG -> editor.putLong(key, value.value as Long)
                        PreferenceValueType.FLOAT -> editor.putFloat(key, value.value as Float)
                        PreferenceValueType.STRING -> editor.putString(key, value.value as String)
                        PreferenceValueType.STRING_SET -> editor.putStringSet(key,
                            (value.value as Set<*>).map { it as String }.toSet())
                    }
                }
                check(editor.commit()) { "Unable to roll back restored preferences" }
            }
        }

        private fun copy(source: File, target: File) {
            if (source.isDirectory) {
                if (!source.copyRecursively(target, overwrite = true)) throw IOException("Unable to stage restore")
            } else {
                target.parentFile?.mkdirs()
                source.copyTo(target, overwrite = true)
            }
        }
    }

    internal fun detectDeviceChange(
        source: DeviceIdentitySnapshot?,
        target: DeviceIdentitySnapshot
    ): DeviceChange? {
        source ?: return null
        val changed = if (source.stableId != null && target.stableId != null) {
            source.stableId != target.stableId
        } else {
            fun hardwareIdentity(device: DeviceIdentitySnapshot): List<String>? {
                if (device.model.isBlank() || device.device.isBlank()) return null
                return listOf(device.manufacturer, device.model, device.device)
                    .map { it.lowercase(java.util.Locale.ROOT) }
            }
            val sourceIdentity = hardwareIdentity(source) ?: return null
            val targetIdentity = hardwareIdentity(target) ?: return null
            sourceIdentity != targetIdentity
        }
        return if (changed) DeviceChange(source, target) else null
    }

    internal fun shouldNotifyUserDictionaryRefresh(
        preferencesSummary: PreferencesRestoreSummary,
        fileSummary: FileRestoreSummary
    ): Boolean {
        return collectTriggeredPostRestoreActions(preferencesSummary, fileSummary)
            .contains(PostRestoreAction.REFRESH_USER_DICTIONARY)
    }

    internal fun collectTriggeredPostRestoreActions(
        preferencesSummary: PreferencesRestoreSummary,
        fileSummary: FileRestoreSummary
    ): Set<PostRestoreAction> {
        val appliedPrefKeys = preferencesSummary.appliedKeys.toSet()
        val restoredFiles = fileSummary.restoredFiles

        return postRestoreTriggerRules.mapNotNullTo(linkedSetOf()) { rule ->
            val prefMatch = rule.matchingAppliedPrefKeys.any(appliedPrefKeys::contains)
            val fileMatch = rule.matchingRestoredFileNames.any { fileName ->
                restoredFiles.any { restored ->
                    restored == fileName || restored.endsWith("/$fileName")
                }
            }
            if (prefMatch || fileMatch) rule.action else null
        }
    }

    private fun notifyPostRestoreEffects(
        context: Context,
        actions: Set<PostRestoreAction>
    ) {
        Log.i(TAG, "Restore post-actions triggered: $actions")
        if (actions.isEmpty()) return

        actions.forEach { action ->
            when (action) {
                PostRestoreAction.REFRESH_USER_DICTIONARY -> {
                    val intent = Intent(AppBroadcastActions.USER_DICTIONARY_UPDATED).apply {
                        setPackage(context.packageName)
                    }
                    context.sendBroadcast(intent)
                    Log.i(TAG, "Sent user dictionary refresh broadcast after restore")
                }
                PostRestoreAction.REGISTER_CUSTOM_INPUT_STYLES -> {
                    AdditionalSubtypeUtils.registerAdditionalSubtypes(context.applicationContext)
                    Log.i(TAG, "Registered custom input styles after restore")
                }
            }
        }
    }
}

sealed class RestoreInspectionResult {
    data class Success(
        val metadata: BackupMetadata?,
        val deviceChange: RestoreManager.DeviceChange?
    ) : RestoreInspectionResult()

    data class Failure(val reason: String) : RestoreInspectionResult()
}

sealed class RestoreResult {
    data class Success(
        val metadata: BackupMetadata?,
        val preferencesSummary: PreferencesRestoreSummary,
        val fileSummary: FileRestoreSummary,
        val postActionsTriggered: Set<RestoreManager.PostRestoreAction>
    ) : RestoreResult()

    data class Failure(val reason: String) : RestoreResult()
}
