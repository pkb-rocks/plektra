package rocks.pkb.plektra.clipboard

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** A preview of the *system* clip, independent of history. Never coerce URIs or retain a full paste. */
internal class RecentPaste(context: Context, private val onChanged: () -> Unit) {
    class Entry(val timestamp: Long, val label: String, val deadline: Long)

    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = Runnable(onChanged)
    private val changed = ClipboardManager.OnPrimaryClipChangedListener {
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }
    private var listening = false
    private var preview: Entry? = null

    fun current(allowed: Boolean): Entry? {
        if (allowed != listening) {
            if (allowed) clipboard.addPrimaryClipChangedListener(changed)
            else clipboard.removePrimaryClipChangedListener(changed)
            listening = allowed
        }
        handler.removeCallbacks(refresh)
        val next = if (allowed) readPreview() else null
        preview = next
        if (next != null) handler.postDelayed(refresh, (next.deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        return next
    }

    private fun readPreview(): Entry? {
        val description = try { clipboard.primaryClipDescription } catch (_: SecurityException) { null } ?: return null
        if (!eligible(description)) return null
        val now = SystemClock.elapsedRealtime()
        preview?.takeIf { it.timestamp == description.timestamp }?.let {
            if (now < it.deadline) return it
            dismiss(it)
            return null
        }
        val text = textFor(description.timestamp) ?: return null
        val prefix = text.take(32).toString().replace('\n', ' ').replace('\r', ' ')
        val label = if (text.length > 32) "$prefix…" else prefix
        val remaining = WINDOW_MS - (System.currentTimeMillis() - description.timestamp)
        return Entry(description.timestamp, label, now + remaining)
    }

    private fun eligible(description: ClipDescription) = description.timestamp > 0 &&
        description.timestamp != dismissedTimestamp && System.currentTimeMillis() - description.timestamp in 0 until WINDOW_MS &&
        description.hasMimeType("text/*") && description.extras?.getBoolean("android.content.extra.IS_SENSITIVE") != true

    private fun textFor(timestamp: Long): CharSequence? {
        val clip = try { clipboard.primaryClip } catch (_: SecurityException) { null } ?: return null
        if (clip.description.timestamp != timestamp || !eligible(clip.description) || clip.itemCount == 0) return null
        return clip.getItemAt(0).text?.takeIf { it.isNotBlank() }
    }

    fun use(entry: Entry, commit: (String) -> Boolean) {
        if (entry !== preview || SystemClock.elapsedRealtime() >= entry.deadline) return
        val text = textFor(entry.timestamp) ?: return
        if (commit(text.toString())) dismiss(entry)
    }

    fun dismiss(entry: Entry) {
        if (entry === preview) dismissedTimestamp = entry.timestamp
    }

    private companion object {
        const val WINDOW_MS = 60_000L
        // Survives an IME service restart, contains no clipboard text and needs no storage.
        var dismissedTimestamp = -1L
    }
}
