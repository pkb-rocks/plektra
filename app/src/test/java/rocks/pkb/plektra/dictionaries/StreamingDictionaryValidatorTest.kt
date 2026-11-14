package rocks.pkb.plektra.dictionaries

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import rocks.pkb.plektra.core.suggestions.DictionaryIndex
import rocks.pkb.plektra.core.suggestions.SerializableDictionaryEntry
import rocks.pkb.plektra.core.suggestions.SymSpellMeta
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalSerializationApi::class)
class StreamingDictionaryValidatorTest {
    private fun sample() = DictionaryIndex(
        mapOf("bonjour" to listOf(SerializableDictionaryEntry("Bonjour", 200, 0)),
            "salut" to listOf(SerializableDictionaryEntry("Salut", 150, 2))),
        mapOf("bo" to listOf(SerializableDictionaryEntry("Bonjour", 200, 0))),
        mapOf("b" to listOf("bonjour")), SymSpellMeta(2, 4))
    @Test fun acceptsCborAndRetainsOnlyRelevantPersonalKeys() {
        assertEquals(setOf("bonjour"), StreamingDictionaryValidator.validate(
            ByteArrayInputStream(Cbor.encodeToByteArray(sample())), setOf("bonjour", "absent")))
    }
    /** Mirrors DictionaryRepository.decodeSerializedDictionary. */
    private fun runtimeDecodes(bytes: ByteArray): Boolean = runCatching {
        if (bytes.isNotEmpty() && bytes[0] == '{'.code.toByte()) Json { ignoreUnknownKeys = true }.decodeFromString<DictionaryIndex>(bytes.decodeToString())
        else Cbor.decodeFromByteArray<DictionaryIndex>(bytes)
    }.isSuccess
    private fun validates(bytes: ByteArray): Boolean = runCatching {
        StreamingDictionaryValidator.validate(bytes.inputStream(), emptySet())
    }.onFailure { assertTrue(it.toString(), it is SerializationException) }.isSuccess
    private fun assertCompatible(name: String, bytes: ByteArray, expected: Boolean) {
        assertEquals("validator: $name", expected, validates(bytes))
        if (expected) assertTrue("runtime: $name", runtimeDecodes(bytes))
    }

    @Test fun acceptsLegacyJson() {
        val bytes = Json.encodeToString(sample()).toByteArray()
        assertEquals(setOf("salut"), StreamingDictionaryValidator.validate(bytes.inputStream(), setOf("salut")))
    }
    @Test fun rejectsLeadingWhitespaceBecauseRuntimeWouldDecodeItAsCbor() {
        val json = Json.encodeToString(sample())
        for (prefix in listOf(" ", "\n", " \n", "\t", "\r\n")) {
            assertCompatible("prefix ${prefix.map { it.code }}", (prefix + json).toByteArray(), false)
            assertFalse(runtimeDecodes((prefix + json).toByteArray()))
        }
    }
    @Test fun jsonNumbersFollowRuntimeRules() {
        fun entry(number: String) = "{\"normalizedIndex\":{\"a\":[{\"word\":\"a\",\"frequency\":$number,\"source\":0}]},\"prefixCache\":{}}"
        val accepted = listOf("1", "-0", "1e2", "1E2", "1e+2", "1e-0", "10e-1", "0e10", "2e9", "2147483647", "-2147483648")
        val rejected = listOf("1.0", "1.5", "1e-1", "0e400", "2147483648", "-2147483649", "3e9", "1e2.0", "\"1\"")
        for (number in accepted) assertCompatible(number, entry(number).toByteArray(), true)
        for (number in rejected) assertCompatible(number, entry(number).toByteArray(), false)
        assertFalse(runtimeDecodes(entry("1.0").toByteArray()))
        for (field in listOf("source", "maxEditDistance")) {
            val json = if (field == "source") entry("0").replace("\"source\":0", "\"source\":1.0")
                else "{\"normalizedIndex\":{},\"prefixCache\":{},\"symMeta\":{\"maxEditDistance\":2.0,\"prefixLength\":4}}"
            assertCompatible(field, json.toByteArray(), false)
            assertFalse(runtimeDecodes(json.toByteArray()))
        }
    }
    @Test fun jsonEscapesFollowRuntimeRules() {
        fun entry(word: String, extra: String = "") = "{\"normalizedIndex\":{\"a\":[{\"word\":\"$word\",\"frequency\":1,\"source\":0$extra}]},\"prefixCache\":{}}"
        for (word in listOf("a\\nb", "\\\"q\\\"", "a\\u00e9", "a\\/b", "\\\\")) assertCompatible(word, entry(word).toByteArray(), true)
        for (json in listOf(entry("a\\qb"), entry("a\\u00g9"), entry("a", ",\"x\":\"\\q\""), entry("a").replace("\"a\":[", "\"a\\q\":["))) {
            assertCompatible(json, json.toByteArray(), false)
            assertFalse(json, runtimeDecodes(json.toByteArray()))
        }
    }
    @Test fun emptyPersonalDictionaryStillValidatesEntireFile() {
        assertEquals(emptySet<String>(), StreamingDictionaryValidator.validate(
            Cbor.encodeToByteArray(sample()).inputStream(), emptySet()))
    }
    @Test fun rejectsTruncatedCborRatherThanInstallingIt() {
        val bytes = Cbor.encodeToByteArray(sample())
        try { StreamingDictionaryValidator.validate(bytes.copyOf(bytes.size - 1).inputStream(), emptySet()); fail("truncated") }
        catch (_: SerializationException) { }
    }
    @Test fun rejectsMissingIndexesWrongEntryTypesAndInvalidSource() {
        val invalid = listOf("{}", "{\"normalizedIndex\":[],\"prefixCache\":{}}",
            "{\"normalizedIndex\":{\"a\":[{\"word\":\"a\",\"frequency\":2,\"source\":7}]},\"prefixCache\":{}}")
        for (json in invalid) {
            try { StreamingDictionaryValidator.validate(json.byteInputStream(), emptySet()); fail(json) }
            catch (_: SerializationException) { }
        }
    }
    @Test fun rejectsTrailingCborData() {
        try { StreamingDictionaryValidator.validate((Cbor.encodeToByteArray(sample()) + byteArrayOf(0)).inputStream(), emptySet()); fail("trailing") }
        catch (_: SerializationException) { }
    }
    private fun cborText(value: String) = value.toByteArray().let { byteArrayOf((0x60 + it.size).toByte()) + it }
    private fun chunkedText(vararg chunks: String) =
        byteArrayOf(0x7f) + chunks.fold(ByteArray(0)) { acc, chunk -> acc + cborText(chunk) } + byteArrayOf(0xff.toByte())
    /** {normalizedIndex: {key: [{word, frequency: 7, source: 1}]}, prefixCache: {}, symDeletes: {deleteKey: [deleteValue]}} */
    private fun cborDictionary(indexKey: ByteArray, key: ByteArray, word: ByteArray, deleteKey: ByteArray, deleteValue: ByteArray) =
        byteArrayOf(0xa3.toByte()) + indexKey + byteArrayOf(0xa1.toByte()) + key + byteArrayOf(0x81.toByte(), 0xa3.toByte()) +
            cborText("word") + word + cborText("frequency") + byteArrayOf(7) + cborText("source") + byteArrayOf(1) +
            cborText("prefixCache") + byteArrayOf(0xa0.toByte()) +
            cborText("symDeletes") + byteArrayOf(0xa1.toByte()) + deleteKey + byteArrayOf(0x81.toByte()) + deleteValue

    @Test fun acceptsChunkedCborTextLikeRuntime() {
        val bytes = cborDictionary(chunkedText("normalized", "Index"), chunkedText("caf", "é"),
            chunkedText("Ca", "f", "é"), chunkedText("cf"), chunkedText("ca", "fé"))
        assertTrue(runtimeDecodes(bytes))
        assertEquals("Café", Cbor.decodeFromByteArray<DictionaryIndex>(bytes).normalizedIndex.getValue("café").single().word)
        val entries = mutableListOf<StreamingDictionaryValidator.Entry>()
        assertEquals(setOf("café"), StreamingDictionaryValidator.validate(bytes.inputStream(), setOf("café"), onEntry = { entries += it }))
        assertEquals(listOf(StreamingDictionaryValidator.Entry("café", "Café", 7, 1)), entries)
        // A multibyte character split across chunks decodes after concatenation, as in kotlinx.
        val é = "é".toByteArray()
        val split = byteArrayOf(0x7f, 0x61, é[0], 0x61, é[1], 0xff.toByte())
        val splitWord = cborDictionary(cborText("normalizedIndex"), cborText("e"), split, cborText("e"), split)
        assertCompatible("split UTF-8", splitWord, true)
    }
    @Test fun acceptsTaggedCborStringsLikeRuntime() {
        val tagged = byteArrayOf(0xd8.toByte(), 0x20) + cborText("normalizedIndex")
        assertCompatible("tagged key", cborDictionary(tagged, cborText("a"), cborText("a"), cborText("a"), cborText("a")), true)
    }
    @Test fun rejectsMalformedChunkedCborText() {
        val empty = byteArrayOf(0x7f, 0xff.toByte())
        val nested = byteArrayOf(0x7f) + chunkedText("a") + byteArrayOf(0xff.toByte())
        val byteChunk = byteArrayOf(0x7f, 0x41, 'a'.code.toByte(), 0xff.toByte())
        for ((name, word) in listOf("empty" to empty, "nested" to nested, "byte chunk" to byteChunk)) {
            assertCompatible(name, cborDictionary(cborText("normalizedIndex"), cborText("a"), word, cborText("a"), cborText("a")), false)
        }
        assertFalse(runtimeDecodes(cborDictionary(cborText("normalizedIndex"), cborText("a"), empty, cborText("a"), cborText("a"))))
    }

    @Test fun streams150000EntriesWithoutRetainingTheirIndexes() {
        val context = RuntimeEnvironment.getApplication()
        val file = File.createTempFile("large-cbor-", ".dict", context.cacheDir)
        try {
            file.outputStream().buffered().use { output ->
                fun text(value: String) {
                    val bytes = value.toByteArray()
                    if (bytes.size < 24) output.write(0x60 + bytes.size)
                    else { output.write(0x78); output.write(bytes.size) }
                    output.write(bytes)
                }
                output.write(0xbf); text("normalizedIndex"); output.write(0xbf)
                repeat(150000) { index ->
                    text("word$index"); output.write(0x81); output.write(0xa3)
                    text("word"); text("word$index")
                    text("frequency"); output.write(0x18); output.write(200)
                    text("source"); output.write(0)
                }
                output.write(0xff); text("prefixCache"); output.write(0xa0); output.write(0xff)
            }
            assertEquals(setOf("word1", "word149999"), file.inputStream().use {
                StreamingDictionaryValidator.validate(it, setOf("word1", "word149999", "absent")) })
        } finally { file.delete() }
    }
    @Test fun invalidImportPreservesExistingDictionaryAndRemovesTemporaryFile() {
        val dir = File(RuntimeEnvironment.getApplication().cacheDir, "invalid-import").apply { mkdirs() }
        val target = File(dir, "en_extended.dict").apply { writeText("old valid file") }
        try {
            try { DictionaryFileInstaller.install("{}".byteInputStream(), target, emptySet()); fail("invalid") }
            catch (_: SerializationException) { }
            assertEquals("old valid file", target.readText())
            assertEquals(listOf(target.name), dir.listFiles()!!.map { it.name })
        } finally { target.delete(); dir.delete() }
    }
    @Test fun interruptedCopyPreservesExistingDictionary() {
        val dir = File(RuntimeEnvironment.getApplication().cacheDir, "interrupted-import").apply { mkdirs() }
        val target = File(dir, "en_extended.dict").apply { writeText("old") }
        try {
            val broken = object : InputStream() { override fun read(): Int = throw IOException("synthetic") }
            try { DictionaryFileInstaller.install(broken, target, emptySet()); fail("copy") }
            catch (_: IOException) { }
            assertEquals("old", target.readText())
            assertEquals(listOf(target.name), dir.listFiles()!!.map { it.name })
        } finally { target.delete(); dir.delete() }
    }
    @Test fun validatedImportReplacesFileAndReturnsDeduplicationMatches() {
        val dir = File(RuntimeEnvironment.getApplication().cacheDir, "valid-import").apply { mkdirs() }
        val target = File(dir, "en_extended.dict").apply { writeText("old") }
        try {
            val bytes = Cbor.encodeToByteArray(sample())
            assertEquals(setOf("bonjour"), DictionaryFileInstaller.install(bytes.inputStream(), target, setOf("bonjour")))
            assertArrayEquals(bytes, target.readBytes())
            assertEquals(listOf(target.name), dir.listFiles()!!.map { it.name })
        } finally { target.delete(); dir.delete() }
    }
    @Test fun cancelledImportKeepsExistingFileAndRemovesStagingFile() {
        val dir = File(RuntimeEnvironment.getApplication().cacheDir, "cancelled-import").apply { mkdirs() }
        val target = File(dir, "en_base.dict").apply { writeText("old") }
        try {
            var replaced = false
            try {
                DictionaryFileInstaller.install(Cbor.encodeToByteArray(sample()).inputStream(), target, emptySet(),
                    checkpoint = { throw java.util.concurrent.CancellationException("synthetic cancellation") },
                    onReplaced = { replaced = true })
                fail("cancelled")
            } catch (_: java.util.concurrent.CancellationException) { }
            assertFalse(replaced)
            assertEquals("old", target.readText())
            assertEquals(listOf(target.name), dir.listFiles()!!.map { it.name })
        } finally { target.delete(); dir.delete() }
    }

    @Test fun replacementNotifiesEvenIfCancelledRightAfterTheSwap() {
        val dir = File(RuntimeEnvironment.getApplication().cacheDir, "notified-import").apply { mkdirs() }
        val target = File(dir, "en_base.dict").apply { writeText("old") }
        val bytes = Cbor.encodeToByteArray(sample())
        var notified = false
        try {
            kotlinx.coroutines.runBlocking {
                val job = launch(kotlinx.coroutines.Dispatchers.IO) {
                    val importJob = coroutineContext.job
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val importContext = coroutineContext
                        DictionaryFileInstaller.install(bytes.inputStream(), target, emptySet(),
                            checkpoint = { importContext.ensureActive() },
                            onReplaced = {
                                // Cancellation arriving after the swap cannot suppress the notification.
                                importJob.cancel()
                                assertArrayEquals(bytes, target.readBytes())
                                notified = true
                            })
                    }
                    fail("withContext must report the cancellation")
                }
                job.join()
                assertTrue(job.isCancelled)
            }
            assertTrue(notified)
            assertArrayEquals(bytes, target.readBytes())
            assertEquals(listOf(target.name), dir.listFiles()!!.map { it.name })
        } finally { target.delete(); dir.delete() }
    }

    @Test fun malformedJsonCannotReplaceAValidInstalledDictionary() {
        val invalid = listOf(
            " \n{\"normalizedIndex\":{},\"prefixCache\":{}}",
            "{\"normalizedIndex\":{\"a\":[{\"word\":\"a\",\"frequency\":1.0,\"source\":0}]},\"prefixCache\":{}}",
            "{\"normalizedIndex\":{\"a\":[{\"word\":\"a\",\"word\":\"b\",\"frequency\":1,\"source\":0}]},\"prefixCache\":{}}",
            "{\"normalizedIndex\":{},\"prefixCache\":{}} trailing"
        )
        val dir = File(RuntimeEnvironment.getApplication().cacheDir, "malformed-json").apply { mkdirs() }
        val target = File(dir, "en_base.dict").apply { writeText("old") }
        try {
            for (json in invalid) {
                try { DictionaryFileInstaller.install(json.byteInputStream(), target, emptySet()); fail("invalid") }
                catch (_: SerializationException) { }
                assertEquals("old", target.readText())
                assertEquals(listOf(target.name), dir.listFiles()!!.map { it.name })
            }
        } finally { target.delete(); dir.delete() }
    }

}
