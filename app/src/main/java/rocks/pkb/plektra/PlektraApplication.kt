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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.content.SharedPreferences

class PlektraApplication : Application() {
    private var unlockedInitialized = false
    private var directBootConfigListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun onCreate() {
        super.onCreate()
        if (!DirectBootKeyboardConfig.isUserUnlocked(this)) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (!unlockedInitialized && intent.action == Intent.ACTION_USER_UNLOCKED && DirectBootKeyboardConfig.isUserUnlocked(context)) {
                        unregisterReceiver(this)
                        initializeUnlockedApplication()
                    }
                }
            }
            ContextCompat.registerReceiver(this, receiver, IntentFilter(Intent.ACTION_USER_UNLOCKED), ContextCompat.RECEIVER_NOT_EXPORTED)
            // Close the race where unlock occurred between the initial check and registration.
            if (DirectBootKeyboardConfig.isUserUnlocked(this)) {
                unregisterReceiver(receiver)
                initializeUnlockedApplication()
            }
            return
        }
        initializeUnlockedApplication()
    }

    private fun initializeUnlockedApplication() {
        if (unlockedInitialized) return
        unlockedInitialized = true
        val preferences = SettingsManager.getPreferences(this)
        val config = DirectBootKeyboardConfig(this)
        config.refresh(preferences)
        directBootConfigListener = SharedPreferences.OnSharedPreferenceChangeListener { source, key ->
            if (key == null || key in DirectBootKeyboardConfig.KEYS) config.refresh(source)
        }.also { preferences.registerOnSharedPreferenceChangeListener(it) }
        SettingsManager.initializeAltShiftLayoutSwitchDefault(this)
        SettingsManager.enforceTitan2EliteRoundedCornersOnce(this)
        // Restore the vendor keyboard switch if a ring process ended while it was darkened.
        rocks.pkb.plektra.ring.RingBacklight.restoreLater(this)
        AppPackageChangeMonitor.register(this)
        ClicksPowerKeyboardController.initialize(this)
        publishSoftwareKeyboardModeShortcut()
        Handler(Looper.getMainLooper()).post {
            AdditionalSubtypeUtils.registerAdditionalSubtypes(this)
        }
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
