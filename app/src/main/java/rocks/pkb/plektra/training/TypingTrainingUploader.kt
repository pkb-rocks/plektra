package rocks.pkb.plektra.training

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/**
 * Shares stored typing-training sessions with the maintainer. Each session is gzip-compressed
 * and age-encrypted on the device, so the server only ever receives ciphertext.
 */
class TypingTrainingUploader(
    private val baseUrl: String,
    recipient: String,
    private val appVersion: String,
    private val uploadedLog: File,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
) {
    data class Result(val uploaded: Int, val alreadyKnown: Int, val failed: Int)

    private val encryptor = AgeEncryptor(recipient)

    fun uploadedIds(): Set<String> =
        if (uploadedLog.exists()) uploadedLog.readLines().filter { it.isNotBlank() }.toSet() else emptySet()

    /** Uploads every session not yet confirmed by the server. Blocking; call off the main thread. */
    fun uploadAll(sessions: List<File>, onProgress: (done: Int, total: Int) -> Unit): Result {
        val confirmed = uploadedIds().toMutableSet()
        // A session deleted meanwhile is skipped.
        val pending = sessions.mapNotNull { file -> runCatching { file to sessionId(file.readBytes()) }.getOrNull() }
            .filter { (_, id) -> id !in confirmed }
        if (pending.isEmpty()) return Result(0, 0, 0)
        val known = runCatching { knownIds(pending.map { it.second }) }.getOrDefault(emptySet())
        var uploaded = 0
        var failed = 0
        known.forEach(::markUploaded)
        val toSend = pending.filter { (_, id) -> id !in known }
        toSend.forEachIndexed { index, (file, id) ->
            onProgress(index, toSend.size)
            if (runCatching { upload(id, file.readBytes()) }.isSuccess) {
                markUploaded(id)
                uploaded++
            } else {
                failed++
            }
        }
        onProgress(toSend.size, toSend.size)
        return Result(uploaded, known.size, failed)
    }

    private fun knownIds(ids: List<String>): Set<String> {
        val body = json.encodeToString(KnownRequest.serializer(), KnownRequest(ids)).toRequestBody(JSON)
        client.newCall(Request.Builder().url("$baseUrl/v1/sessions/known").post(body).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return json.decodeFromString(KnownResponse.serializer(), response.body?.string().orEmpty()).known.toSet()
        }
    }

    private fun upload(id: String, plaintext: ByteArray) {
        val ciphertext = encryptor.encrypt(gzip(plaintext))
        val request = Request.Builder()
            .url("$baseUrl/v1/sessions/$id")
            .header("X-Plektra-Version", appVersion)
            .put(ciphertext.toRequestBody(AGE))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        }
    }

    private fun markUploaded(id: String) {
        uploadedLog.parentFile?.mkdirs()
        uploadedLog.appendText("$id\n")
    }

    @Serializable
    private data class KnownRequest(val ids: List<String>)

    @Serializable
    private data class KnownResponse(val known: List<String> = emptyList())

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val JSON = "application/json".toMediaType()
        private val AGE = "application/octet-stream".toMediaType()

        /** Stable, content-derived id: re-sharing the same session is recognised as a duplicate. */
        fun sessionId(plaintext: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(plaintext).joinToString("") { "%02x".format(it) }

        private fun gzip(bytes: ByteArray): ByteArray =
            ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()
    }
}
