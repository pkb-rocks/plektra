package rocks.pkb.plektra.inputmethod.speech

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class OpenAiLiveSpeechClientTest {
    private fun request() = SpeechRequest(SpeechProvider.OPENAI, SpeechModels.openAiDefaults.first(), "test-key", "de-DE", 1)

    @Test fun streamsBeforeStopAndReplacesPartialWithFinalTranscript() = runBlocking {
        MockWebServer().use { server ->
            val configuration = CompletableDeferred<JsonObject>()
            val appended = CompletableDeferred<Unit>()
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(socket: WebSocket, text: String) {
                    val event = Json.parseToJsonElement(text).jsonObject
                    when (event.getValue("type").jsonPrimitive.content) {
                        "session.update" -> {
                            configuration.complete(event)
                            socket.send("""{"type":"session.updated"}""")
                        }
                        "input_audio_buffer.append" -> {
                            appended.complete(Unit)
                            socket.send("""{"type":"conversation.item.input_audio_transcription.delta","item_id":"one","delta":"Hallo We"}""")
                        }
                        "input_audio_buffer.commit" -> socket.send("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"one","transcript":"Hallo Welt.","usage":{"input_tokens":4,"output_tokens":3}}""")
                    }
                }
            }))
            val stop = CompletableDeferred<Unit>()
            val partial = CompletableDeferred<String>()
            val recorder = object : SpeechAudioRecorder {
                override suspend fun record(silenceMs: Long, onLevel: (Float, Long?) -> Unit): RecordedSpeech = error("Batch recording")
                override suspend fun recordStreaming(silenceMs: Long, onLevel: (Float, Long?) -> Unit,
                    onPcm: suspend (ByteArray) -> Unit): RecordedSpeech {
                    onPcm(ByteArray(4800))
                    stop.await()
                    return RecordedSpeech(ByteArray(4800), 24000)
                }
                override fun finish() { stop.complete(Unit) }
            }
            var finished = false
            val task = async { OpenAiLiveSpeechClient(url = server.url("/").toString())
                .transcribe(request(), recorder, { _, _ -> }, { partial.complete(it) }, { finished = true }) }
            assertEquals("Hallo We", withTimeout(3000) { partial.await() })
            assertFalse(finished)
            assertFalse(task.isCompleted)
            recorder.finish()
            val result = withTimeout(3000) { task.await() }
            assertEquals("Hallo Welt.", result.result.text)
            assertEquals(100L, result.audio.durationMs)
            assertEquals(4L, result.result.inputTokens)
            val input = configuration.await().getValue("session").jsonObject.getValue("audio").jsonObject.getValue("input").jsonObject
            assertEquals(24000, input.getValue("format").jsonObject.getValue("rate").jsonPrimitive.int)
            assertEquals(JsonNull, input["turn_detection"])
            assertEquals("de", input.getValue("transcription").jsonObject.getValue("languages").jsonArray.single().jsonPrimitive.content)
            assertEquals("Bearer test-key", server.takeRequest(3, TimeUnit.SECONDS)!!.getHeader("Authorization"))
        }
    }

    @Test fun capturesFirstChunksWhileServerConfigurationIsPending() = runBlocking {
        MockWebServer().use { server ->
            val configured = CompletableDeferred<WebSocket>()
            val received = Channel<String>(Channel.UNLIMITED)
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(socket: WebSocket, text: String) {
                    val event = Json.parseToJsonElement(text).jsonObject
                    when (event.getValue("type").jsonPrimitive.content) {
                        "session.update" -> configured.complete(socket)
                        "input_audio_buffer.append" -> received.trySend(event.getValue("audio").jsonPrimitive.content)
                        "input_audio_buffer.commit" -> socket.send("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"one","transcript":"Anfang"}""")
                    }
                }
            }))
            val captured = CompletableDeferred<Unit>()
            val stop = CompletableDeferred<Unit>()
            val recorder = object : SpeechAudioRecorder {
                override suspend fun record(silenceMs: Long, onLevel: (Float, Long?) -> Unit): RecordedSpeech = error("Batch recording")
                override suspend fun recordStreaming(silenceMs: Long, onLevel: (Float, Long?) -> Unit,
                    onPcm: suspend (ByteArray) -> Unit): RecordedSpeech {
                    onPcm(byteArrayOf(1, 2))
                    onPcm(byteArrayOf(3, 4))
                    captured.complete(Unit)
                    stop.await()
                    return RecordedSpeech(byteArrayOf(1, 2, 3, 4), 24000)
                }
                override fun finish() { stop.complete(Unit) }
            }
            val task = async { OpenAiLiveSpeechClient(url = server.url("/").toString())
                .transcribe(request(), recorder, { _, _ -> }, {}, {}) }
            withTimeout(3000) {
                captured.await()
                val socket = configured.await()
                assertTrue(received.tryReceive().isFailure)
                socket.send("""{"type":"session.updated"}""")
                assertEquals("AQI=", received.receive())
                assertEquals("AwQ=", received.receive())
                recorder.finish()
                assertEquals("Anfang", task.await().result.text)
            }
        }
    }

    @Test fun invalidSessionStopsLocalCaptureAndDoesNotSendAudio() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(socket: WebSocket, text: String) {
                    socket.send("""{"type":"error","error":{"code":"model_not_found","message":"Private provider details"}}""")
                }
            }))
            var stopped = false
            val recorder = object : SpeechAudioRecorder {
                override suspend fun record(silenceMs: Long, onLevel: (Float, Long?) -> Unit): RecordedSpeech = error("Batch recording")
                override suspend fun recordStreaming(silenceMs: Long, onLevel: (Float, Long?) -> Unit,
                    onPcm: suspend (ByteArray) -> Unit): RecordedSpeech {
                    try {
                        onPcm(ByteArray(4800))
                        awaitCancellation()
                    } finally { stopped = true }
                }
                override fun finish() {}
            }
            try {
                withTimeout(3000) { OpenAiLiveSpeechClient(url = server.url("/").toString())
                    .transcribe(request(), recorder, { _, _ -> }, { fail("Unexpected partial") }, {}) }
                fail("Expected model error")
            } catch (error: SpeechRealtimeException) {
                assertEquals("model_not_found", error.code)
                assertFalse(error.message!!.contains("Private"))
            }
            assertTrue(stopped)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun cancellationStopsCaptureAndDoesNotReconnect() = runBlocking {
        MockWebServer().use { server ->
            val configured = CompletableDeferred<Unit>()
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(socket: WebSocket, text: String) {
                    if (text.contains("session.update")) {
                        socket.send("""{"type":"session.updated"}""")
                        configured.complete(Unit)
                    }
                }
            }))
            val recording = CompletableDeferred<Unit>()
            var stopped = false
            val recorder = object : SpeechAudioRecorder {
                override suspend fun record(silenceMs: Long, onLevel: (Float, Long?) -> Unit): RecordedSpeech = error("Batch recording")
                override suspend fun recordStreaming(silenceMs: Long, onLevel: (Float, Long?) -> Unit,
                    onPcm: suspend (ByteArray) -> Unit): RecordedSpeech {
                    try {
                        onPcm(ByteArray(4800))
                        recording.complete(Unit)
                        awaitCancellation()
                    } finally { stopped = true }
                }
                override fun finish() {}
            }
            val task = launch { OpenAiLiveSpeechClient(url = server.url("/").toString())
                .transcribe(request(), recorder, { _, _ -> }, { fail("Unexpected text") }, {}) }
            withTimeout(3000) { recording.await(); configured.await() }
            task.cancelAndJoin()
            assertTrue(stopped)
            assertEquals(1, server.requestCount)
        }
    }
}
