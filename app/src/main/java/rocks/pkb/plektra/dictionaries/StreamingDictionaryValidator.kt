package rocks.pkb.plektra.dictionaries

import android.util.JsonReader
import android.util.JsonToken
import kotlinx.serialization.SerializationException
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PushbackInputStream

/** Validate legacy CBOR/JSON without expanding prefix and SymSpell indexes.
 * Only normalized keys matching personal entries are retained for deduplication.
 */
internal object StreamingDictionaryValidator {
    data class Entry(val key: String, val word: String, val frequency: Int, val source: Int)
    fun validate(source: InputStream, personalKeys: Set<String>,
        onEntry: ((Entry) -> Unit)? = null, checkpoint: () -> Unit = {}): Set<String> {
        try {
            val input = PushbackInputStream(BufferedInputStream(source), 1)
            var first = input.read()
            while (first in listOf(9, 10, 13, 32)) first = input.read()
            require(first >= 0) { "Empty dictionary" }
            input.unread(first)
            return if (first == '{'.code) json(input, personalKeys, onEntry, checkpoint) else CborReader(input, personalKeys, onEntry, checkpoint).read()
        } catch (error: java.util.concurrent.CancellationException) {
            throw error
        } catch (error: SerializationException) {
            throw error
        } catch (error: Exception) {
            throw SerializationException("Invalid dictionary structure", error)
        }
    }

    private fun json(input: InputStream, keys: Set<String>, onEntry: ((Entry) -> Unit)?, checkpoint: () -> Unit): Set<String> {
        val found = mutableSetOf<String>()
        val fields = mutableSetOf<String>()
        val reader = JsonReader(InputStreamReader(input, Charsets.UTF_8))
        reader.beginObject()
        while (reader.hasNext()) {
            val field = reader.nextName()
            require(fields.add(field))
            when (field) {
                "normalizedIndex", "prefixCache" -> {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        checkpoint()
                        val key = reader.nextName()
                        if (field == "normalizedIndex" && key in keys) found.add(key)
                        reader.beginArray()
                        while (reader.hasNext()) {
                            val entryFields = mutableSetOf<String>()
                            var word = ""; var frequency = 0; var source = 0
                            val emit = field == "normalizedIndex" && onEntry != null
                            reader.beginObject()
                            while (reader.hasNext()) {
                                val name = reader.nextName()
                                require(entryFields.add(name))
                                when (name) {
                                    "word" -> { require(reader.peek() == JsonToken.STRING); if (emit) word = reader.nextString() else reader.skipValue(); entryFields.add(name) }
                                    "frequency" -> { require(reader.peek() == JsonToken.NUMBER); frequency = reader.nextInt(); entryFields.add(name) }
                                    "source" -> { require(reader.peek() == JsonToken.NUMBER); source = reader.nextInt(); require(source in 0..2); entryFields.add(name) }
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()
                            require(entryFields.containsAll(listOf("word", "frequency", "source")))
                            if (emit) onEntry?.invoke(Entry(key, word, frequency, source))
                        }
                        reader.endArray()
                    }
                    reader.endObject()
                }
                "symDeletes" -> {
                    if (reader.peek() == JsonToken.NULL) reader.nextNull() else {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            checkpoint(); reader.nextName(); reader.beginArray()
                            while (reader.hasNext()) { require(reader.peek() == JsonToken.STRING); reader.skipValue() }
                            reader.endArray()
                        }
                        reader.endObject()
                    }
                }
                "symMeta" -> {
                    if (reader.peek() == JsonToken.NULL) reader.nextNull() else {
                        val metadata = mutableSetOf<String>()
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val name = reader.nextName()
                            if (name == "maxEditDistance" || name == "prefixLength") { require(reader.peek() == JsonToken.NUMBER); reader.nextInt(); metadata.add(name) }
                            else reader.skipValue()
                        }
                        reader.endObject()
                        require(metadata.containsAll(listOf("maxEditDistance", "prefixLength")))
                    }
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        require(reader.peek() == JsonToken.END_DOCUMENT)
        require(fields.containsAll(listOf("normalizedIndex", "prefixCache")))
        return found
    }

    private class CborReader(val input: PushbackInputStream, val keys: Set<String>, val onEntry: ((Entry) -> Unit)?, val checkpoint: () -> Unit) {
        private fun byte(): Int = input.read().also { require(it >= 0) { "Truncated CBOR" } }
        private fun header(expected: Int): Long {
            val first = byte()
            require(first ushr 5 == expected)
            return when (val additional = first and 31) {
                in 0..23 -> additional.toLong()
                24 -> byte().toLong()
                25 -> (byte().toLong() shl 8) or byte().toLong()
                26 -> (0 until 4).fold(0L) { value, _ -> (value shl 8) or byte().toLong() }
                27 -> (0 until 8).fold(0L) { value, _ ->
                    require(value <= Long.MAX_VALUE ushr 8)
                    (value shl 8) or byte().toLong()
                }.also { require(it >= 0) }
                31 -> -1L
                else -> error("Invalid CBOR length")
            }
        }
        private fun container(type: Int, block: () -> Unit) {
            val size = header(type)
            if (size >= 0) {
                var remaining = size
                while (remaining-- > 0) { checkpoint(); block() }
            } else {
                while (true) {
                    checkpoint()
                    val next = byte()
                    if (next == 255) break
                    input.unread(next)
                    block()
                }
            }
        }
        private fun text(): String {
            val size = header(3)
            require(size in 0..65536) { "Invalid dictionary key length" }
            val bytes = ByteArray(size.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val count = input.read(bytes, offset, bytes.size - offset)
                require(count > 0)
                offset += count
            }
            return bytes.toString(Charsets.UTF_8)
        }
        private fun skipText() {
            val size = header(3)
            require(size >= 0)
            var remaining = size
            while (remaining > 0) {
                val count = input.skip(remaining)
                if (count > 0) remaining -= count else { byte(); remaining-- }
            }
        }
        private fun integer(): Long {
            val first = byte(); input.unread(first)
            val type = first ushr 5
            require(type == 0 || type == 1)
            val value = header(type)
            require(value in 0..Int.MAX_VALUE.toLong())
            return if (type == 0) value else -1L - value
        }
        private fun nullable(block: () -> Unit) {
            val next = byte()
            if (next != 246) { input.unread(next); block() }
        }
        fun read(): Set<String> {
            val fields = mutableSetOf<String>()
            val found = mutableSetOf<String>()
            container(5) {
                val field = text()
                require(fields.add(field))
                when (field) {
                    "normalizedIndex", "prefixCache" -> container(5) {
                        val key = text()
                        if (field == "normalizedIndex" && key in keys) found.add(key)
                        container(4) {
                            val entryFields = mutableSetOf<String>()
                            var word = ""; var frequency = 0; var source = 0
                            val emit = field == "normalizedIndex" && onEntry != null
                            container(5) {
                                val name = text()
                                require(entryFields.add(name))
                                when (name) {
                                    "word" -> if (emit) word = text() else skipText()
                                    "frequency" -> frequency = integer().toInt()
                                    "source" -> { source = integer().toInt(); require(source in 0..2) }
                                    else -> error("Unknown CBOR entry field")
                                }
                            }
                            require(entryFields.containsAll(listOf("word", "frequency", "source")))
                            if (emit) onEntry?.invoke(Entry(key, word, frequency, source))
                        }
                    }
                    "symDeletes" -> nullable { container(5) { skipText(); container(4) { skipText() } } }
                    "symMeta" -> nullable {
                        val metadata = mutableSetOf<String>()
                        container(5) {
                            val name = text()
                            require(name == "maxEditDistance" || name == "prefixLength")
                            require(metadata.add(name)); integer()
                        }
                        require(metadata.containsAll(listOf("maxEditDistance", "prefixLength")))
                    }
                    else -> error("Unknown CBOR dictionary field")
                }
            }
            require(fields.containsAll(listOf("normalizedIndex", "prefixCache")))
            require(input.read() == -1) { "Trailing CBOR data" }
            return found
        }
    }
}
