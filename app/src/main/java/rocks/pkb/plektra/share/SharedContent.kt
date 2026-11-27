package rocks.pkb.plektra.share

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.IntentCompat
import java.util.Locale

/** One entry of an incoming share. */
sealed interface SharedItem {
    val id: Int

    data class Text(
        override val id: Int,
        val text: String,
        val html: String?,
        val subject: String?
    ) : SharedItem

    data class Stream(
        override val id: Int,
        val uri: Uri,
        val mimeType: String,
        val displayName: String?,
        val size: Long?
    ) : SharedItem {
        val isImage: Boolean get() = mimeType.startsWith("image/")
    }
}

/**
 * The accepted entries of an incoming share. [rejectedCount] counts references that are not
 * readable through another app's content provider (for example `file:` paths or Plektra's own
 * providers) and were therefore dropped.
 */
data class SharePayload(val items: List<SharedItem>, val rejectedCount: Int) {
    val imageCount: Int get() = items.count { it is SharedItem.Stream && it.isImage }
}

internal object ShareIntentParser {
    /** Upper bound so a malicious sender cannot make the screen enumerate unbounded lists. */
    const val MAX_ITEMS = 200

    fun parse(context: Context, intent: Intent): SharePayload {
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) {
            return SharePayload(emptyList(), 0)
        }
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { it.isNotBlank() }
        val html = intent.getStringExtra(Intent.EXTRA_HTML_TEXT)?.takeIf { it.isNotBlank() }
        val texts: List<String> = if (action == Intent.ACTION_SEND_MULTIPLE) {
            intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT)?.mapNotNull { it?.toString() }
                ?: listOfNotNull(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
        } else {
            listOfNotNull(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
        }.filter { it.isNotBlank() }

        val streams = linkedSetOf<Uri>()
        if (action == Intent.ACTION_SEND_MULTIPLE) {
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.filterNotNull()?.let(streams::addAll)
        } else {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(streams::add)
        }
        if (streams.isEmpty()) {
            // Some senders only fill ClipData; Android also mirrors EXTRA_STREAM there.
            intent.clipData?.let { clip ->
                for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(streams::add)
            }
        }

        val items = mutableListOf<SharedItem>()
        var rejected = 0
        var nextId = 0
        val singleDeclaredType = intent.type?.takeIf { streams.size == 1 }
        for (uri in streams) {
            if (!isReadableForeignContentUri(context, uri)) {
                rejected++
                continue
            }
            if (items.size >= MAX_ITEMS) {
                rejected++
                continue
            }
            items += describeStream(context.contentResolver, nextId++, uri, singleDeclaredType)
        }
        texts.forEachIndexed { index, text ->
            if (items.size >= MAX_ITEMS) {
                rejected++
            } else {
                items += SharedItem.Text(
                    id = nextId++,
                    text = text,
                    html = html.takeIf { index == 0 && texts.size == 1 },
                    subject = subject
                )
            }
        }
        return SharePayload(items, rejected)
    }

    /**
     * Only `content:` URIs of other apps are read. A `file:` URI or one of Plektra's own
     * providers would be read with Plektra's privileges and could export its private files.
     */
    fun isReadableForeignContentUri(context: Context, uri: Uri): Boolean {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
        // ContentResolver drops a "<userId>@" prefix before it opens the provider; Android 13's
        // PackageManager does not. Both checks must see the authority the resolver will use.
        // The prefix itself stays allowed: shares from a work profile use it legitimately.
        val authority = uri.authority?.let(::authorityWithoutUserId)?.takeIf { it.isNotBlank() } ?: return false
        val ownPackage = context.packageName
        if (authority.split(';').any { it == ownPackage || it.startsWith("$ownPackage.") }) return false
        val owner = runCatching {
            context.packageManager.resolveContentProvider(authority, 0)?.packageName
        }.getOrNull()
        return owner != ownPackage
    }

    /** Mirrors `ContentProvider.getAuthorityWithoutUserId`. */
    fun authorityWithoutUserId(authority: String): String = authority.substring(authority.lastIndexOf('@') + 1)

    private fun describeStream(
        resolver: ContentResolver,
        id: Int,
        uri: Uri,
        declaredType: String?
    ): SharedItem.Stream {
        var name: String? = null
        var size: Long? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex).takeIf { it >= 0 }
                    }
                }
        }
        if (name == null) name = uri.lastPathSegment?.substringAfterLast('/')
        val mimeType = ShareMimeTypes.resolve(
            providerType = runCatching { resolver.getType(uri) }.getOrNull(),
            declaredType = declaredType,
            displayName = name
        )
        return SharedItem.Stream(id, uri, mimeType, name, size)
    }
}

internal object ShareMimeTypes {
    const val OCTET_STREAM = "application/octet-stream"

    /** The provider's type wins; the sender's declared type and the name extension are fallbacks. */
    fun resolve(providerType: String?, declaredType: String?, displayName: String?): String {
        normalize(providerType)?.takeIf { it != OCTET_STREAM }?.let { return it }
        normalize(declaredType)?.takeIf { !it.contains('*') && it != OCTET_STREAM }?.let { return it }
        val extension = displayName?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()
        if (extension.isNotEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)?.let { return it }
        }
        return OCTET_STREAM
    }

    fun normalize(type: String?): String? = type
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.takeIf { it.matches(MIME_PATTERN) }

    fun extensionFor(mimeType: String): String? =
        MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)

    private val MIME_PATTERN = Regex("^[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9*][a-z0-9!#$&^_.+*-]*$")
}

internal object ShareFileNames {
    /** Leaves room below the common 255-byte file name limit for collision suffixes. */
    const val MAX_NAME_BYTES = 200

    private val reservedCharacters = Regex("[\\\\/:*?\"<>|]")
    private val invisibleCharacters = Regex("[\\p{Cc}\\p{Cf}]")
    private val whitespace = Regex("\\s+")
    private val extensionPattern = Regex("^[A-Za-z0-9]{1,10}$")

    /**
     * Makes [rawName] safe as a single file name: no path, no reserved or invisible characters
     * (including bidirectional overrides), no leading dot, bounded length. A missing extension is
     * added from [mimeType] when Android knows one.
     */
    fun sanitize(rawName: String?, mimeType: String, fallbackBase: String): String {
        var name = rawName.orEmpty()
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(whitespace, " ")
            .replace(invisibleCharacters, "")
            .replace(reservedCharacters, "_")
            .trim(' ', '.')
        if (name.isEmpty()) name = sanitizeBase(fallbackBase)
        var extension = extensionOf(name)
        var base = if (extension != null) name.dropLast(extension.length + 1).trimEnd(' ', '.') else name
        if (base.isEmpty()) base = sanitizeBase(fallbackBase)
        if (extension == null) {
            extension = ShareMimeTypes.extensionFor(mimeType)?.takeIf { extensionPattern.matches(it) }
        }
        val suffix = extension?.let { ".$it" }.orEmpty()
        return truncateUtf8(base, MAX_NAME_BYTES - suffix.toByteArray().size) + suffix
    }

    fun extensionOf(name: String): String? {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return null
        return name.substring(dot + 1).takeIf { extensionPattern.matches(it) }
    }

    private fun sanitizeBase(base: String): String = base
        .replace(invisibleCharacters, "")
        .replace(reservedCharacters, "_")
        .trim(' ', '.')
        .ifEmpty { "Plektra" }

    private fun truncateUtf8(text: String, maxBytes: Int): String {
        if (text.toByteArray().size <= maxBytes) return text
        val builder = StringBuilder()
        var bytes = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val chars = Character.charCount(codePoint)
            val size = text.substring(index, index + chars).toByteArray().size
            if (bytes + size > maxBytes) break
            builder.append(text, index, index + chars)
            bytes += size
            index += chars
        }
        return builder.toString().trimEnd(' ', '.').ifEmpty { "Plektra" }
    }
}
