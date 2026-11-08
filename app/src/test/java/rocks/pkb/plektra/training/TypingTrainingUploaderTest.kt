package rocks.pkb.plektra.training

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TypingTrainingUploaderTest {

    @get:Rule
    val temp = TemporaryFolder()
    private val server = MockWebServer()
    private val recipient = "age12uugjmvru09n47ef260u9kmwrdxtg857ku54d8vxff9cyrg9u9rqstcysa"

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.shutdown()

    private fun uploader() = TypingTrainingUploader(
        baseUrl = server.url("/").toString().trimEnd('/'),
        recipient = recipient,
        appVersion = "26.11-test",
        uploadedLog = temp.root.resolve("shared-ids.txt")
    )

    @Test
    fun skipsKnownSessionsAndUploadsOthersAsAgeCiphertext() {
        val known = temp.newFile("a.json").apply { writeText("""{"a":1}""") }
        val fresh = temp.newFile("b.json").apply { writeText("""{"b":2}""") }
        val knownId = TypingTrainingUploader.sessionId(known.readBytes())
        server.enqueue(MockResponse().setBody("""{"known":["$knownId"]}"""))
        server.enqueue(MockResponse().setResponseCode(201))

        val result = uploader().uploadAll(listOf(known, fresh)) { _, _ -> }

        assertEquals(TypingTrainingUploader.Result(uploaded = 1, alreadyKnown = 1, failed = 0), result)
        assertEquals("/v1/sessions/known", server.takeRequest().path)
        val put = server.takeRequest()
        assertEquals("/v1/sessions/${TypingTrainingUploader.sessionId(fresh.readBytes())}", put.path)
        assertEquals("26.11-test", put.getHeader("X-Plektra-Version"))
        assertTrue(put.body.readUtf8Line()!!.startsWith("age-encryption.org/v1"))
        assertEquals(2, uploader().uploadedIds().size)
    }

    @Test
    fun alreadySharedSessionsCauseNoRequests() {
        val file = temp.newFile("a.json").apply { writeText("{}") }
        temp.root.resolve("shared-ids.txt").writeText(TypingTrainingUploader.sessionId(file.readBytes()) + "\n")

        val result = uploader().uploadAll(listOf(file)) { _, _ -> }

        assertEquals(TypingTrainingUploader.Result(0, 0, 0), result)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun failedUploadIsReportedAndNotMarkedShared() {
        val file = temp.newFile("a.json").apply { writeText("{}") }
        server.enqueue(MockResponse().setBody("""{"known":[]}"""))
        server.enqueue(MockResponse().setResponseCode(500))

        val result = uploader().uploadAll(listOf(file)) { _, _ -> }

        assertEquals(1, result.failed)
        assertTrue(uploader().uploadedIds().isEmpty())
    }
}
