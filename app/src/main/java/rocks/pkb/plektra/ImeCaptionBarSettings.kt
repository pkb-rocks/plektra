package rocks.pkb.plektra

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

internal object ImeCaptionBarSettings {
    fun canControlCaptionBar(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    fun unihertzIntent(): Intent = Intent("com.android.settings.GESTURE_NAVIGATION_SETTINGS").apply {
        addCategory(Intent.CATEGORY_DEFAULT)
        putExtra(":settings:fragment_args_key", "agui_hide_ime_caption_bar")
    }

    fun open(context: Context) {
        val intent = if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
            Intent("com.samsung.intent.action.VIRTUAL_KEYBOARD_SETTINGS")
        } else {
            unihertzIntent()
        }
        try {
            context.startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
    }
}
