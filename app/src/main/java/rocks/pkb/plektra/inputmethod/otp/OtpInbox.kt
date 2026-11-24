package rocks.pkb.plektra.inputmethod.otp

import java.security.MessageDigest

internal enum class OtpSource { SMS, NOTIFICATION, HELPER }

/** Main-thread, memory-only inbox. Remember fingerprints (not used/replaced plaintext) for dedupe. */
internal class OtpInbox(private val enabled: (OtpSource) -> Boolean, private val now: () -> Long) {
    class Entry(val code: String, val expiresAt: Long, source: OtpSource) {
        val sources = mutableSetOf(source)
    }

    // Sources (especially Helper) don't share a message ID. A delayed repost of a dismissed
    // code must lose even after the short replacement-dedupe window or an editor restart.
    // Only user dismissals, insertions and actual timeouts enter this process-memory set.
    private val dismissed = linkedSetOf<List<Byte>>()
    fun wasDismissed(code: String) = validCode.matches(code) && fingerprint(code) in dismissed
    private val seen = mutableMapOf<List<Byte>, Long>()
    private fun fingerprint(code: String) = MessageDigest.getInstance("SHA-256").digest(code.toByteArray()).toList()
    private var active: Entry? = null

    fun current(): Entry? {
        val time = now()
        seen.values.removeAll { it <= time }
        active?.let { entry ->
            entry.sources.retainAll(enabled)
            if (entry.expiresAt <= time) retire(entry)
            else if (entry.sources.isEmpty()) active = null
        }
        return active
    }

    fun offer(source: OtpSource, code: String): Boolean {
        if (!enabled(source) || !validCode.matches(code)) return false
        val previous = current()
        val fingerprint = fingerprint(code)
        if (fingerprint in dismissed) return false
        if (seen.containsKey(fingerprint)) {
            if (previous?.code == code) previous.sources += source
            return false
        }
        val expiresAt = now() + WINDOW_MS
        if (seen.size >= MAX_RECENT) seen.remove(seen.keys.first())
        seen[fingerprint] = expiresAt
        active = Entry(code, now() + DISPLAY_MS, source)
        return true
    }

    fun use(entry: Entry, commit: (String) -> Boolean): Boolean {
        if (current() !== entry || !commit(entry.code)) return false
        retire(entry)
        return true
    }

    fun dismiss(entry: Entry) {
        if (current() === entry) retire(entry)
    }

    private fun retire(entry: Entry) {
        dismissed += fingerprint(entry.code)
        if (dismissed.size > MAX_RECENT) dismissed.remove(dismissed.first())
        if (active === entry) active = null
    }

    fun nextExpiry(): Long? = listOfNotNull(active?.expiresAt, seen.values.minOrNull()).minOrNull()

    private companion object {
        const val DISPLAY_MS = 60_000L
        const val WINDOW_MS = 120_000L
        const val MAX_RECENT = 64
        val validCode = Regex("[a-zA-Z0-9]{4,10}")
    }
}
