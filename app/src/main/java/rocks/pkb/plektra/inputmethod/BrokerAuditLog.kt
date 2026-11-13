package rocks.pkb.plektra.inputmethod

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/**
 * An app-private record of what the ADB broker did:
 * each broker command by its allowlisted [PrivilegedCommand.id], each refusal, each grant and
 * revoke, pairings, key changes and unpairing, with the time and the result. The broker screen
 * shows it, so misuse is visible after the fact.
 *
 * It holds no command output and no user text: ids are constants and details are short failure
 * reasons. It lives in [Context.getNoBackupFilesDir], which is app-private and never backed up or
 * moved to another phone, and keeps the last [MAX_ENTRIES] entries. A run of identical entries
 * within [COALESCE_MS] is kept as one entry with a count, so the screen's own checks do not push
 * everything else out.
 */
object BrokerAuditLog {

    const val MAX_ENTRIES = 200
    const val FILE_NAME = "broker_audit.jsonl"
    private const val COALESCE_MS = 10 * 60_000L
    private const val MAX_DETAIL = 160

    enum class Kind { COMMAND, REFUSED, PAIRING, KEY, UNPAIR, WIRELESS_DEBUGGING }

    data class Entry(
        val atMs: Long,
        val kind: Kind,
        val id: String,
        val ok: Boolean,
        val detail: String?,
        val count: Int = 1,
    )

    private val lock = Any()

    private val _version = MutableStateFlow(0L)

    /** Bumped on every change, so a screen can reload. */
    val version: StateFlow<Long> = _version.asStateFlow()

    fun record(context: Context, kind: Kind, id: String, ok: Boolean, detail: String? = null) {
        val now = System.currentTimeMillis()
        val clean = detail?.replace(Regex("\\s+"), " ")?.trim()?.take(MAX_DETAIL)?.ifEmpty { null }
        synchronized(lock) {
            val entries = read(context).toMutableList()
            val last = entries.lastOrNull()
            if (last != null && last.kind == kind && last.id == id && last.ok == ok &&
                last.detail == clean && now - last.atMs in 0..COALESCE_MS
            ) {
                entries[entries.lastIndex] = last.copy(atMs = now, count = last.count + 1)
            } else {
                entries += Entry(now, kind, id, ok, clean)
            }
            write(context, entries.takeLast(MAX_ENTRIES))
        }
        _version.value = _version.value + 1
    }

    /** Oldest first. Never throws: an unreadable log reads as empty. */
    fun entries(context: Context): List<Entry> = synchronized(lock) { read(context) }

    fun clear(context: Context) {
        synchronized(lock) { runCatching { file(context).delete() } }
        _version.value = _version.value + 1
    }

    private fun file(context: Context) = File(context.applicationContext.noBackupFilesDir, FILE_NAME)

    private fun read(context: Context): List<Entry> = runCatching {
        val f = file(context)
        if (!f.exists()) return@runCatching emptyList()
        f.readLines().mapNotNull { line -> runCatching { parse(line) }.getOrNull() }
    }.getOrDefault(emptyList())

    private fun write(context: Context, entries: List<Entry>) {
        runCatching {
            val f = file(context)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(entries.joinToString("\n") { format(it) } + "\n")
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        }
    }

    private fun format(e: Entry): String = JSONObject().apply {
        put("t", e.atMs)
        put("k", e.kind.name)
        put("id", e.id)
        put("ok", e.ok)
        e.detail?.let { put("d", it) }
        if (e.count > 1) put("n", e.count)
    }.toString()

    private fun parse(line: String): Entry? {
        if (line.isBlank()) return null
        val o = JSONObject(line)
        return Entry(
            atMs = o.getLong("t"),
            kind = Kind.valueOf(o.getString("k")),
            id = o.getString("id"),
            ok = o.getBoolean("ok"),
            detail = if (o.has("d")) o.getString("d") else null,
            count = o.optInt("n", 1),
        )
    }
}
