package rocks.pkb.plektra

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Spanned
import android.util.Log
import android.util.LruCache
import android.widget.TextView
import android.widget.Toast
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.image.AsyncDrawable
import io.noties.markwon.image.AsyncDrawableLoader
import io.noties.markwon.image.AsyncDrawableScheduler
import io.noties.markwon.image.DrawableUtils
import io.noties.markwon.image.ImageSpanFactory
import org.commonmark.node.Image
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Image import and preview for notes. Copies run on one background thread that outlives
 * activity recreation; everything else runs on the main thread.
 *
 * The Markdown reference goes into the note when the keyboard commits the image, so a copy
 * that finishes later always belongs to that note, whichever note is open by then. A copy
 * that fails removes its reference again; a stored image that no note or undo history refers
 * to any more is deleted. Only images this process stored are ever deleted, because the notes
 * collection can be swapped temporarily, for example by a restore.
 */
internal object NotesAttachments {
    private const val TAG = "NotesAttachments"
    private const val MAX_PREVIEW_SIDE = 2048

    /** Background work: copying and decoding. Tests replace it. */
    internal var executor: Executor = Executors.newSingleThreadExecutor { Thread(it, "notes-attachments") }

    /** An open editor that may show or remember a reference. */
    interface Host {
        /** Whether the editor text or its undo history refers to [reference]. */
        fun refersTo(reference: String): Boolean

        /** Removes [reference] if the host shows note [noteId]; false when it shows another note. */
        fun removeReference(noteId: String, reference: String, replacedText: String): Boolean

        fun onAttachmentStored()
    }

    private class Owner(val context: Context, val store: NotesAttachmentStore, val prefs: SharedPreferences, val noteId: String, val replacedText: String)

    private val main = Handler(Looper.getMainLooper())
    private val hosts = linkedSetOf<Host>()
    private val pending = mutableMapOf<String, Owner>()
    private val stored = mutableMapOf<String, Owner>()
    private val previews = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun register(host: Host) { hosts += host }

    fun unregister(host: Host) {
        hosts -= host
        sweep()
    }

    fun isPending(name: String) = name in pending

    /**
     * Copies [uri] into the store as [name] for note [noteId], whose body already refers to it.
     * [release] runs once the content is no longer read.
     */
    fun start(
        context: Context,
        store: NotesAttachmentStore,
        prefs: SharedPreferences,
        noteId: String,
        name: String,
        uri: Uri,
        replacedText: String,
        release: () -> Unit
    ) {
        val owner = Owner(context.applicationContext, store, prefs, noteId, replacedText)
        pending[name] = owner
        val resolver = owner.context.contentResolver
        try {
            executor.execute {
                val failure = try {
                    val input = resolver.openInputStream(uri) ?: throw NotesAttachmentStore.ImportException(NotesAttachmentStore.Failure.FAILED)
                    input.use { store.import(it, name) }
                    null
                } catch (e: NotesAttachmentStore.ImportException) {
                    e.failure
                } catch (e: Exception) {
                    Log.w(TAG, "Image import failed (${e.javaClass.simpleName})")
                    NotesAttachmentStore.Failure.FAILED
                } finally {
                    runCatching(release)
                }
                main.post { finish(name, failure) }
            }
        } catch (e: RejectedExecutionException) {
            runCatching(release)
            finish(name, NotesAttachmentStore.Failure.FAILED)
        }
    }

    private fun finish(name: String, failure: NotesAttachmentStore.Failure?) {
        val owner = pending.remove(name) ?: return
        if (failure == null) {
            stored[name] = Owner(owner.context, owner.store, owner.prefs, owner.noteId, "")
            hosts.toList().forEach { it.onAttachmentStored() }
            sweep()
            return
        }
        owner.store.delete(name)
        val reference = NotesAttachmentStore.reference(name)
        if (hosts.none { it.removeReference(owner.noteId, reference, owner.replacedText) }) {
            val notes = NotesRepository(owner.prefs)
            notes.all().firstOrNull { it.id == owner.noteId }?.let { note ->
                referenceRange(note.body, reference)?.let { range ->
                    val body = note.body.replaceRange(range, owner.replacedText)
                    notes.save(note.copy(body = body, cursor = note.cursor.coerceAtMost(body.length)))
                }
            }
        }
        showFailure(owner.context, failure)
    }

    fun showFailure(context: Context, failure: NotesAttachmentStore.Failure) {
        val message = when (failure) {
            NotesAttachmentStore.Failure.UNSUPPORTED -> context.getString(R.string.notes_image_unsupported)
            NotesAttachmentStore.Failure.TOO_LARGE -> context.getString(R.string.notes_image_too_large,
                (NotesAttachmentStore.MAX_IMAGE_BYTES / (1024 * 1024)).toInt())
            NotesAttachmentStore.Failure.FAILED -> context.getString(R.string.notes_image_import_failed)
        }
        Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
    }

    /** Where [body] holds the Markdown image that points at [reference], or null if it was edited. */
    fun referenceRange(body: CharSequence, reference: String): IntRange? =
        Regex("!\\[[^\\]\\n]*\\]\\(" + Regex.escape(reference) + "\\)").find(body)?.range

    private fun sweep() {
        stored.entries.removeAll { (name, owner) ->
            val reference = NotesAttachmentStore.reference(name)
            val used = hosts.any { it.refersTo(reference) } ||
                NotesRepository(owner.prefs).all().any { reference in it.body }
            if (!used) owner.store.delete(name)
            !used
        }
    }

    /** Markwon plugin that shows note attachments in the preview and nothing else. */
    fun previewPlugin(context: Context, store: NotesAttachmentStore): AbstractMarkwonPlugin = object : AbstractMarkwonPlugin() {
        private val loader = PreviewLoader(context.applicationContext, store)

        override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
            builder.asyncDrawableLoader(loader)
        }

        override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
            builder.setFactory(Image::class.java, ImageSpanFactory())
        }

        override fun beforeSetText(textView: TextView, markdown: Spanned) {
            AsyncDrawableScheduler.unschedule(textView)
        }

        override fun afterSetText(textView: TextView) {
            AsyncDrawableScheduler.schedule(textView)
        }
    }

    private class PreviewLoader(private val context: Context, private val store: NotesAttachmentStore) : AsyncDrawableLoader() {
        private val requests = mutableMapOf<AsyncDrawable, AtomicBoolean>()

        override fun load(drawable: AsyncDrawable) {
            val file = store.resolve(drawable.destination) ?: return
            previews.get(file.name)?.let { drawable.setResult(result(it)); return }
            if (!file.isFile) return
            val cancelled = AtomicBoolean(false)
            requests[drawable] = cancelled
            try {
                executor.execute {
                    val bitmap = if (cancelled.get()) null else decode(file.path)
                    main.post {
                        if (requests[drawable] === cancelled) requests.remove(drawable)
                        if (bitmap != null) {
                            previews.put(file.name, bitmap)
                            if (!cancelled.get() && drawable.isAttached) drawable.setResult(result(bitmap))
                        }
                    }
                }
            } catch (e: RejectedExecutionException) {
                requests.remove(drawable)
            }
        }

        override fun cancel(drawable: AsyncDrawable) {
            requests.remove(drawable)?.set(true)
        }

        override fun placeholder(drawable: AsyncDrawable): Drawable? = null

        // Markwon sizes the image from the result's bounds, so they must be set.
        private fun result(bitmap: Bitmap): Drawable =
            BitmapDrawable(context.resources, bitmap).also(DrawableUtils::applyIntrinsicBoundsIfEmpty)

        private fun decode(path: String): Bitmap? = try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_PREVIEW_SIDE) sample *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        } catch (e: OutOfMemoryError) {
            null
        }
    }
}
