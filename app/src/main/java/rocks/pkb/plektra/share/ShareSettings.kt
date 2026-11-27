package rocks.pkb.plektra.share

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import rocks.pkb.plektra.SettingsManager

/** Where the share target stores files and pictures. */
object ShareSettings {
    /**
     * The folder grant belongs to this installation, so its URI lives in its own preferences
     * file, which Android backup, device transfer and Plektra's backup all leave out.
     */
    const val LOCAL_PREFERENCES = "share_local"
    const val KEY_SAVE_FOLDER_URI = "share_save_folder_uri"
    const val KEY_GALLERY_ALBUM = "share_gallery_album"

    const val GALLERY_ALBUM_NAME = "Plektra"

    sealed interface SaveFolder {
        data object Downloads : SaveFolder
        /**
         * [accessible]: Plektra still holds the write grant. [label] is null when the folder
         * cannot be reached, for example because it was deleted.
         */
        data class Tree(val uri: Uri, val accessible: Boolean, val label: String?) : SaveFolder {
            val usable: Boolean get() = accessible && label != null
        }
    }

    /** The chosen folder from local preferences only; null means Downloads. Cheap enough for main. */
    fun storedFolderUri(context: Context): Uri? =
        localPreferences(context).getString(KEY_SAVE_FOLDER_URI, null)?.let { runCatching { Uri.parse(it) }.getOrNull() }

    /** Includes the grant check and the provider's folder label: call off the main thread. */
    fun saveFolder(context: Context): SaveFolder {
        val uri = storedFolderUri(context) ?: return SaveFolder.Downloads
        val accessible = hasPersistedWriteAccess(context, uri)
        return SaveFolder.Tree(uri, accessible, if (accessible) treeLabel(context, uri) else null)
    }

    /** Takes a durable grant for [treeUri] and releases the grant of the folder it replaces. */
    fun selectSaveFolder(context: Context, treeUri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(treeUri, flags)
        val previous = (saveFolder(context) as? SaveFolder.Tree)?.uri
        localPreferences(context).edit()
            .putString(KEY_SAVE_FOLDER_URI, treeUri.toString())
            .apply()
        if (previous != null && previous != treeUri) releaseGrant(context, previous)
    }

    fun useDownloads(context: Context) {
        val previous = (saveFolder(context) as? SaveFolder.Tree)?.uri
        localPreferences(context).edit().remove(KEY_SAVE_FOLDER_URI).apply()
        if (previous != null) releaseGrant(context, previous)
    }

    /**
     * Where the folder picker opens: the current folder, otherwise Documents. The storage root
     * itself cannot be chosen on current Android versions.
     */
    fun pickerStartUri(current: SaveFolder): Uri = when (current) {
        is SaveFolder.Tree -> runCatching {
            DocumentsContract.buildDocumentUriUsingTree(current.uri, DocumentsContract.getTreeDocumentId(current.uri))
        }.getOrNull()
        SaveFolder.Downloads -> null
    } ?: DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:Documents")

    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    fun localPreferences(context: Context): SharedPreferences =
        context.getSharedPreferences(LOCAL_PREFERENCES, Context.MODE_PRIVATE)

    fun galleryAlbumEnabled(context: Context): Boolean =
        SettingsManager.getPreferences(context).getBoolean(KEY_GALLERY_ALBUM, false)

    fun setGalleryAlbumEnabled(context: Context, enabled: Boolean) {
        SettingsManager.getPreferences(context).edit().putBoolean(KEY_GALLERY_ALBUM, enabled).apply()
    }

    /** MediaStore relative path of shared pictures, e.g. `Pictures/` or `Pictures/Plektra/`. */
    fun galleryRelativePath(context: Context): String =
        if (galleryAlbumEnabled(context)) "Pictures/$GALLERY_ALBUM_NAME/" else "Pictures/"

    internal fun hasPersistedWriteAccess(context: Context, treeUri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == treeUri && it.isWritePermission }

    internal fun treeLabel(context: Context, treeUri: Uri): String? = runCatching {
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri)
        )
        context.contentResolver.query(
            documentUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    private fun releaseGrant(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }
}
