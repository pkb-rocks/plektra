package rocks.pkb.plektra.clipboard

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.widget.ImageView
import android.widget.Switch
import rocks.pkb.plektra.R
import rocks.pkb.plektra.SettingLinkIds
import rocks.pkb.plektra.SettingsActivity

/** Controls shared by the clipboard panel in the keyboard and the clipboard popup. */
internal object ClipboardPanelControls {
    /** Colors [switch] from the keyboard theme, so its state reads clearly on any background. */
    fun tint(switch: Switch, accent: Int, ink: Int) {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        switch.thumbTintList = ColorStateList(states, intArrayOf(accent, ink))
        // The platform track is drawn at about 30 % opacity, too faint on dark keyboards.
        val density = switch.resources.displayMetrics.density
        fun track(color: Int) = GradientDrawable().apply {
            cornerRadius = 8 * density
            setSize((36 * density).toInt(), (14 * density).toInt())
            setColor(color)
        }
        switch.trackDrawable = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), track(withAlpha(accent, 0x8C)))
            addState(intArrayOf(), track(withAlpha(ink, 0x73)))
        }
        switch.trackTintList = null
    }

    /** A gear that opens the clipboard settings. */
    fun settingsButton(context: Context, sizePx: Int, paddingPx: Int): ImageView = ImageView(context).apply {
        setImageResource(R.drawable.ic_settings_24)
        contentDescription = context.getString(R.string.clipboard_settings_open)
        tooltipText = contentDescription
        setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
        minimumWidth = sizePx
        minimumHeight = sizePx
        isClickable = true
        isFocusable = true
        setOnClickListener { openSettings(context) }
    }

    private fun withAlpha(color: Int, alpha: Int) = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    fun openSettings(context: Context) {
        context.startActivity(Intent(context, SettingsActivity::class.java).apply {
            data = Uri.parse("plektra://setting/${SettingLinkIds.MAIN_CLIPBOARD}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
