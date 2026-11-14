package rocks.pkb.plektra.dictionaries

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import rocks.pkb.plektra.core.suggestions.DictionaryIndex
import rocks.pkb.plektra.core.suggestions.SerializableDictionaryEntry
import rocks.pkb.plektra.core.suggestions.SymSpellMeta
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
    @Test fun acceptsLegacyJsonWithWhitespace() {
        val bytes = (" \n" + Json.encodeToString(sample())).toByteArray()
        assertEquals(setOf("salut"), StreamingDictionaryValidator.validate(bytes.inputStream(), setOf("salut")))
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
            try {
                DictionaryFileInstaller.install(Cbor.encodeToByteArray(sample()).inputStream(), target, emptySet(),
                    checkpoint = { throw java.util.concurrent.CancellationException("synthetic cancellation") })
                fail("cancelled")
            } catch (_: java.util.concurrent.CancellationException) { }
            assertEquals("old", target.readText())
            assertEquals(listOf(target.name), dir.listFiles()!!.map { it.name })
        } finally { target.delete(); dir.delete() }
    }

    @Test fun malformedJsonCannotReplaceAValidInstalledDictionary() {
        val invalid = listOf(
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
