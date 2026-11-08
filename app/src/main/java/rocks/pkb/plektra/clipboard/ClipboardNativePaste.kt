package rocks.pkb.plektra.clipboard

import android.content.Context
import rocks.pkb.plektra.SettingsManager

/**
 * Context-menu action for Plektra's own paste shortcuts, which let the editor paste the
 * system clipboard itself. Ctrl+V keeps the editor's normal paste; Ctrl+Shift+V pastes as
 * plain text unless that is switched off in the clipboard settings.
 */
internal object ClipboardNativePaste {
    fun actionId(context: Context, shiftActive: Boolean): Int =
        if (shiftActive && SettingsManager.getClipboardCtrlShiftVPlainText(context)) {
            android.R.id.pasteAsPlainText
        } else {
            android.R.id.paste
        }
}
