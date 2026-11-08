package rocks.pkb.plektra.training

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class AgeEncryptorTest {

    // Throwaway key pair used only by this test.
    private val testRecipient = "age12uugjmvru09n47ef260u9kmwrdxtg857ku54d8vxff9cyrg9u9rqstcysa"
    private val testIdentity = "AGE-SECRET-KEY-1WCGW9LCN5X69D8UFDW80Q2EH4CNSEHC9GZAYFJW644LPZGYT2EDQQPYP2U"

    @Test
    fun decodesRecipientToThirtyTwoBytes() {
        assertEquals(32, AgeEncryptor.decodeRecipient(testRecipient).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsRecipientWithBrokenChecksum() {
        AgeEncryptor.decodeRecipient(testRecipient.dropLast(1) + "q")
    }

    @Test
    fun headerFollowsAgeV1Layout() {
        val text = AgeEncryptor(testRecipient).encrypt(ByteArray(10)).toString(Charsets.ISO_8859_1)
        val lines = text.lines()
        assertEquals("age-encryption.org/v1", lines[0])
        assertTrue(lines[1].startsWith("-> X25519 "))
        assertTrue(lines[3].startsWith("--- "))
    }

    @Test
    fun referenceAgeCliDecryptsEmptySingleAndMultiChunkPayloads() {
        val age = listOf("/opt/homebrew/bin/age", "/usr/local/bin/age", "/usr/bin/age").map(::File).firstOrNull { it.canExecute() }
        assumeTrue("age CLI not installed", age != null)
        val identityFile = File.createTempFile("age-identity", ".txt").apply { writeText("$testIdentity\n"); deleteOnExit() }
        for (size in listOf(0, 1, 64 * 1024, 64 * 1024 + 1, 200_000)) {
            val plaintext = Random(size).nextBytes(size)
            val encrypted = File.createTempFile("age-test", ".age").apply { writeBytes(AgeEncryptor(testRecipient).encrypt(plaintext)); deleteOnExit() }
            val process = ProcessBuilder(age!!.path, "-d", "-i", identityFile.path, encrypted.path).start()
            val decrypted = process.inputStream.readBytes()
            val error = process.errorStream.readBytes().toString(Charsets.UTF_8)
            assertEquals("age exit for size $size: $error", 0, process.waitFor())
            assertArrayEquals("payload of size $size", plaintext, decrypted)
        }
    }
}
