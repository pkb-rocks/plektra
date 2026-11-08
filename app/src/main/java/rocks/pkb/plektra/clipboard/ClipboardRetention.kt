package rocks.pkb.plektra.clipboard

import android.content.Context
import rocks.pkb.plektra.SettingsManager

/**
 * Retention rules for clipboard history.
 * @param normalMinutes Normal retention in minutes; zero keeps unpinned entries until deleted.
 * @param sensitiveMinutes Sensitive retention, clamped to 1–120 minutes; applies even to legacy pinned rows.
 */
internal data class ClipboardRetention(val normalMinutes: Long, val sensitiveMinutes: Long) {
    private val sensitiveMillis = sensitiveMinutes.coerceIn(1, 120) * 60_000L

    fun normalCutoff(now: Long): Long =
        if (normalMinutes > 0) now - normalMinutes * 60_000L else Long.MIN_VALUE

    fun sensitiveCutoff(now: Long): Long = now - sensitiveMillis

    fun isExpired(entry: ClipboardHistoryEntry, now: Long): Boolean =
        if (entry.isSensitive) entry.timeStamp <= sensitiveCutoff(now)
        else !entry.isPinned && entry.timeStamp <= normalCutoff(now)

    fun expiresAt(entry: ClipboardHistoryEntry): Long = when {
        entry.isSensitive -> entry.timeStamp + sensitiveMillis
        !entry.isPinned && normalMinutes > 0 -> entry.timeStamp + normalMinutes * 60_000L
        else -> Long.MAX_VALUE
    }

    companion object {
        fun fromSettings(context: Context) = ClipboardRetention(
            SettingsManager.getClipboardRetentionTime(context),
            SettingsManager.getSensitiveClipboardRetentionTime(context)
        )
    }
}
