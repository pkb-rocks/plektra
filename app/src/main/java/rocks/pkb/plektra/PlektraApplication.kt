package rocks.pkb.plektra

import android.app.Application
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import rocks.pkb.plektra.inputmethod.subtype.AdditionalSubtypeUtils
import rocks.pkb.plektra.inputmethod.DirectBootKeyboardConfig
import rocks.pkb.plektra.inputmethod.DirectBootStorage
import rocks.pkb.plektra.inputmethod.DirectBootStorageContext
import rocks.pkb.plektra.inputmethod.otp.OtpRuntime
import android.content.Context
import android.content.SharedPreferences

class PlektraApplication : Application() {
    internal val otpRuntime by lazy { OtpRuntime(this) }
    private var unlockedInitialized = false
    private var directBootConfigListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    // ActivityThread casts Application.baseContext to ContextImpl when dispatching receivers.
    // Keep that framework context intact and route only the application's storage APIs.
    private val storageContext by lazy { DirectBootStorageContext(baseContext) }

    override fun getSharedPreferences(name: String, mode: Int) = storageContext.getSharedPreferences(name, mode)
    override fun deleteSharedPreferences(name: String) = storageContext.deleteSharedPreferences(name)
    override fun getFilesDir() = storageContext.filesDir
    override fun getCacheDir() = storageContext.cacheDir
    override fun getNoBackupFilesDir() = storageContext.noBackupFilesDir
    override fun getDataDir() = storageContext.dataDir
    override fun getDir(name: String, mode: Int) = storageContext.getDir(name, mode)
    override fun getDatabasePath(name: String) = storageContext.getDatabasePath(name)
    override fun openOrCreateDatabase(name: String, mode: Int, factory: android.database.sqlite.SQLiteDatabase.CursorFactory?) =
        storageContext.openOrCreateDatabase(name, mode, factory)
    override fun openOrCreateDatabase(name: String, mode: Int, factory: android.database.sqlite.SQLiteDatabase.CursorFactory?, errorHandler: android.database.DatabaseErrorHandler?) =
        storageContext.openOrCreateDatabase(name, mode, factory, errorHandler)
    override fun deleteDatabase(name: String) = storageContext.deleteDatabase(name)
    override fun databaseList() = storageContext.databaseList()
    override fun getFileStreamPath(name: String) = storageContext.getFileStreamPath(name)
    override fun openFileInput(name: String) = storageContext.openFileInput(name)
    override fun openFileOutput(name: String, mode: Int) = storageContext.openFileOutput(name, mode)
    override fun deleteFile(name: String) = storageContext.deleteFile(name)
    override fun fileList() = storageContext.fileList()

    override fun onCreate() {
        super.onCreate()
        // The bundled emoji font comes from the APK assets and is usable before unlock.
        rocks.pkb.plektra.data.emoji.EmojiRendering.ensureInitialized(this)
        // Before unlock only the device-protected keyboard snapshot is readable.
        if (!DirectBootStorage.runAfterUnlock(this, ::initializeUnlockedApplication)) {
            initializeUnlockedApplication()
        }
    }

    /** Every step is idempotent: a failed run is repeated on the next unlock signal. */
    private fun initializeUnlockedApplication() {
        if (unlockedInitialized) return
        val preferences = SettingsManager.getPreferences(this)
        DirectBootKeyboardConfig.requestRefresh(this)
        if (directBootConfigListener == null) {
            directBootConfigListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == null || DirectBootKeyboardConfig.isSnapshotKey(key)) DirectBootKeyboardConfig.requestRefresh(this)
            }.also { preferences.registerOnSharedPreferenceChangeListener(it) }
        }
        SettingsManager.initializeAltShiftLayoutSwitchDefault(this)
        // Re-read in case the snapshot predates the persisted style.
        rocks.pkb.plektra.data.emoji.EmojiRendering.reloadStyle(this)
        SettingsManager.enforceTitan2EliteRoundedCornersOnce(this)
        SettingsManager.migrateCompactSoftwareKeyHeightOnce(this)
        AppPackageChangeMonitor.register(this)
        ClicksPowerKeyboardController.initialize(this)
        publishSoftwareKeyboardModeShortcut()
        Handler(Looper.getMainLooper()).post {
            AdditionalSubtypeUtils.registerAdditionalSubtypes(this)
        }
        unlockedInitialized = true
    }

    private fun publishSoftwareKeyboardModeShortcut() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
            return
        }
        val shortcutManager = getSystemService(ShortcutManager::class.java) ?: return
        val shortcut = ShortcutInfo.Builder(this, SOFTWARE_KEYBOARD_MODE_SHORTCUT_ID)
            .setShortLabel(getString(R.string.software_keyboard_mode_toggle_shortcut_short))
            .setLongLabel(getString(R.string.software_keyboard_mode_toggle_shortcut_long))
            .setIcon(Icon.createWithResource(this, R.mipmap.ic_launcher))
            .setIntent(
                Intent(SoftwareKeyboardModeActions.ACTION_TOGGLE)
                    .setClass(this, SoftwareKeyboardModeActionActivity::class.java)
            )
            .build()
        runCatching {
            shortcutManager.removeDynamicShortcuts(listOf(SOFTWARE_KEYBOARD_MODE_SHORTCUT_ID))
            shortcutManager.addDynamicShortcuts(listOf(shortcut))
        }
    }

    companion object {
        private const val SOFTWARE_KEYBOARD_MODE_SHORTCUT_ID = "software_keyboard_mode_toggle"
    }
}
