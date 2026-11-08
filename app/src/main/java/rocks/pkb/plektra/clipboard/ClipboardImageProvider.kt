package rocks.pkb.plektra.clipboard

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Serves stored clipboard images to editors that receive them through commitContent.
 * Not exported: an editor reads a single image only through the temporary grant that
 * comes with the commit; res/xml/clipboard_image_paths.xml limits it to the image directory.
 */
class ClipboardImageProvider : FileProvider() {
    companion object {
        fun authority(context: Context): String = "${context.packageName}.clipboardimages"

        fun uriFor(context: Context, file: File): Uri = getUriForFile(context, authority(context), file)
    }
}
