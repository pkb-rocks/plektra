package rocks.pkb.plektra.inputmethod

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.*

/** Only model downloads use the network. Translation is delegated to the local model. */
internal interface TranslationEngine : AutoCloseable {
    val isLibre: Boolean get() = false
    val attribution:String? get()=null
    val requiresOnlineConsent:Boolean get()=false
    val configurationIssue:Int? get()=null
    val failureMessage:Int? get()=null
    fun available(done: (Boolean) -> Unit, failed: () -> Unit)
    fun download(wifiOnly: Boolean, done: () -> Unit, failed: () -> Unit)
    fun translate(text: String, done: (String) -> Unit, failed: () -> Unit)
    fun translateBatch(texts: List<String>, done: (List<String>) -> Unit, failed: () -> Unit, cancelled: () -> Boolean = { false }) {
        val results = mutableListOf<String>()
        fun next() {
            if (cancelled()) return
            if (results.size == texts.size) { done(results); return }
            translate(texts[results.size], { if (!cancelled()) { results.add(it); next() } }, { if (!cancelled()) failed() })
        }
        next()
    }
}

internal class LocalTranslationEngine(private val source: String, private val target: String) : TranslationEngine {
    private val manager = RemoteModelManager.getInstance()
    private var translator: Translator? = null
    private var closed = false
    private fun required() = setOf(source, target) - TranslateLanguage.ENGLISH
    override fun available(done: (Boolean) -> Unit, failed: () -> Unit) {
        manager.getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { models -> if (!closed) done(models.map { it.language }.containsAll(required())) }
            .addOnFailureListener { if (!closed) failed() }
    }
    override fun download(wifiOnly: Boolean, done: () -> Unit, failed: () -> Unit) {
        val conditions = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()
        manager.getDownloadedModels(TranslateRemoteModel::class.java).addOnSuccessListener { installed ->
            if (closed) return@addOnSuccessListener
            val remaining = (required() - installed.map { it.language }.toSet()).toMutableList()
            fun next() {
                if (closed) return
                val language = remaining.removeFirstOrNull()
                if (language == null) { done(); return }
                val model = TranslateRemoteModel.Builder(language).build()
                fun request() {
                    if (closed) return
                    manager.download(model,conditions).addOnSuccessListener { next() }
                        .addOnFailureListener { if (!closed) failed() }
                }
                if (wifiOnly) request() else {
                    // A queued DownloadManager request retains its initial Wi-Fi constraint.
                    // Reset only a missing model before explicitly permitted mobile download.
                    manager.deleteDownloadedModel(model).addOnSuccessListener { request() }
                        .addOnFailureListener { if (!closed) failed() }
                }
            }
            next()
        }.addOnFailureListener { if (!closed) failed() }
    }
    override fun translate(text: String, done: (String) -> Unit, failed: () -> Unit) {
        if (closed) return
        val client = translator ?: Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build()).also { translator = it }
        client.translate(text).addOnSuccessListener { if (!closed) done(it) }
            .addOnFailureListener { if (!closed) failed() }
    }
    override fun close() { closed = true; translator?.close(); translator = null }
}
