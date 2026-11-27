package rocks.pkb.plektra.share

import android.net.Uri
import androidx.core.content.FileProvider

/**
 * Plektra's FileProvider. For clipboard copies it reports the MIME type the clip announces;
 * AndroidX derives the type from the file extension only, which can disagree with the content.
 */
class PlektraFileProvider : FileProvider() {
    override fun getType(uri: Uri): String? {
        val context = context ?: return super.getType(uri)
        return ShareClipboardCache.mimeTypeFor(context, uri) ?: super.getType(uri)
    }
}
