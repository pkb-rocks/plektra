package rocks.pkb.plektra.training

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rocks.pkb.plektra.inputmethod.aospkeyboard.HardwareKeyDecision
import rocks.pkb.plektra.inputmethod.aospkeyboard.HardwareKeySample
import rocks.pkb.plektra.inputmethod.aospkeyboard.KeyCommit
import rocks.pkb.plektra.inputmethod.aospkeyboard.KeyboardGeometrySnapshot
import rocks.pkb.plektra.inputmethod.aospkeyboard.TimerEvent
import rocks.pkb.plektra.inputmethod.aospkeyboard.TouchSample
import rocks.pkb.plektra.inputmethod.aospkeyboard.TraceKey

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TypingTrainingSessionSchemaTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** The activity's writer settings; strict about unknown keys. */
    private val json = Json { encodeDefaults = true }

    @Test
    fun schema5SessionStillDecodesWithEmptySchema6Fields() {
        val session = json.decodeFromString(TypingTrainingSession.serializer(), SCHEMA_5_SESSION)

        assertEquals(5, session.schemaVersion)
        assertEquals(1, session.hardwareKeys.size)
        assertEquals(0L, session.hardwareKeys.single().seq)
        assertEquals("device", session.hardwareKeys.single().origin)
        assertEquals("long_press_fired", session.diagnostics.single().type)
        assertTrue(session.contextChanges.isEmpty())
        assertTrue(session.inputDeviceEpisodes.isEmpty())
        assertTrue(session.keyDecisions.isEmpty())
        assertTrue(session.timerEvents.isEmpty())
        assertNull(session.captureStats)
    }

    @Test
    fun schema6SessionRoundTrips() {
        val session = schema6Session()

        val encoded = json.encodeToString(TypingTrainingSession.serializer(), session)
        val decoded = json.decodeFromString(TypingTrainingSession.serializer(), encoded)

        assertEquals(session, decoded)
        val fields = Json.parseToJsonElement(encoded).jsonObject
        assertEquals("6", fields.getValue("schemaVersion").toString())
        listOf("contextChanges", "inputDeviceEpisodes", "keyDecisions", "timerEvents", "captureStats").forEach {
            assertTrue(it, it in fields)
        }
    }

    @Test
    fun keyCorrectionCalibrationReadsSchema6Sessions() {
        val file = folder.newFile("session.json")
        file.writeText(json.encodeToString(TypingTrainingSession.serializer(), schema6Session()))

        val profiles = KeyCorrectionCalibration.calibrate(
            listOf(file),
            RuntimeEnvironment.getApplication().assets,
            nowEpochMs = 0
        )

        assertEquals(1, profiles.size)
    }

    @Test
    fun ignoredAccentsStillAttributeTapsToTheAccentedKey() {
        fun calibrate(typed: String): List<rocks.pkb.plektra.inputmethod.aospkeyboard.KeyCorrectionProfile> {
            val base = schema6Session()
            val geometry = base.geometries.single().copy(
                keys = listOf(
                    TraceKey(0, "CHAR", "u", "u", listOf(0f, 0f, 100f, 100f), listOf(5f, 5f, 95f, 95f)),
                    TraceKey(1, "CHAR", "ü", "ü", listOf(100f, 0f, 200f, 100f), listOf(105f, 5f, 195f, 95f))
                )
            )
            val key = if (typed == "ü") 1 else 0
            val session = base.copy(
                ignoreAccents = true,
                typedText = List(5) { typed }.joinToString(" "),
                plannedTargets = List(5) { "ü" },
                geometries = listOf(geometry),
                touches = List(5) { i ->
                    base.touches.first().copy(eventTimeMs = 1_000L + i * 200, downTimeMs = 1_000L + i * 200, x = key * 100 + 60f, uiKeyIndex = key)
                },
                commits = List(5) { i -> KeyCommit(1, 1_010L + i * 200, key, typed) }
            )
            val file = folder.newFile("accents-$typed.json")
            file.writeText(json.encodeToString(TypingTrainingSession.serializer(), session))
            return KeyCorrectionCalibration.calibrate(listOf(file), RuntimeEnvironment.getApplication().assets, nowEpochMs = 0)
        }

        assertEquals(setOf("ü"), calibrate("ü").single().measuredOffsets.keys)
        // u typed on purpose for ü is no miss of the ü key.
        assertTrue(calibrate("u").isEmpty())
    }

    private fun schema6Session(): TypingTrainingSession {
        val v5 = json.decodeFromString(TypingTrainingSession.serializer(), SCHEMA_5_SESSION)
        val geometry = KeyboardGeometrySnapshot(
            revision = 1, uptimeMs = 900, layoutName = "qwertz", layoutStyle = "EXTENDED_ISO",
            shifted = false, numberRow = false, nearestKeyTouch = true,
            viewWidthPx = 1080, viewHeightPx = 600, viewLeftOnScreenPx = 0, viewTopOnScreenPx = 1800,
            xdpi = 400f, ydpi = 400f,
            keys = listOf(
                TraceKey(0, "CHAR", "a", "a", listOf(0f, 0f, 100f, 100f), listOf(5f, 5f, 95f, 95f)),
                TraceKey(1, "CHAR", "b", "b", listOf(100f, 0f, 200f, 100f), listOf(105f, 5f, 195f, 95f))
            )
        )
        fun down(time: Long, x: Float, key: Int) = TouchSample(
            geometryRevision = 1, phase = "down", pointerId = 0, eventTimeMs = time, downTimeMs = time,
            historical = false, x = x, y = 50f, pressure = 1f, touchMajor = 10f, touchMinor = 10f, uiKeyIndex = key
        )
        val recorder = TypingTrainingRecorder(TypingTrainingContextTimeline(clock = { 1_000 }, nextSeq = { 7 }))
        recorder.onGeometry(geometry)
        return v5.copy(
            schemaVersion = TypingTrainingSession.SCHEMA_VERSION,
            typedText = "ab",
            plannedTargets = listOf("ab"),
            geometries = listOf(geometry),
            touches = listOf(down(1_000, 52f, 0), down(1_200, 148f, 1)),
            commits = listOf(KeyCommit(1, 1_010, 0, "a"), KeyCommit(1, 1_210, 1, "b")),
            hardwareKeys = v5.hardwareKeys.map { it.copy(seq = 3) },
            contextChanges = recorder.timeline.changes,
            inputDeviceEpisodes = listOf(
                InputDeviceEpisode(1, 5, "abc", "Keyboard", 1, 2, 2, 0x101, true, false, connectedUptimeMs = null)
            ),
            keyDecisions = listOf(
                HardwareKeyDecision(4, 3, 1_001, "down", "bounce_keys", "suppressed", "character", 29, deltaMs = 12, thresholdMs = 40)
            ),
            timerEvents = listOf(
                TimerEvent(5, 1, "hardware_long_press", "scheduled", 29, 1_001, 300, 1_001, 1_301, causeSeq = 3, mode = "alt")
            ),
            captureStats = recorder.captureStats()
        )
    }

    private companion object {
        /** Shape written by the schema 5 recorder, trimmed to one entry per list. */
        val SCHEMA_5_SESSION = """
            {
              "schemaVersion": 5,
              "startedAtEpochMs": 1793653214450,
              "firstInputUptimeMs": 1000,
              "endedUptimeMs": 61000,
              "language": "de",
              "wordList": "de-1k",
              "wordListSha256": "00",
              "seed": 42,
              "sessionMode": "time-60s",
              "targetWords": ["ab"],
              "typedText": "a",
              "blockedEdits": 0,
              "ignoreCase": true,
              "ignoreAccents": false,
              "backspaceMarks": [],
              "device": {"manufacturer": "m", "model": "x", "sdkInt": 35, "appVersion": "26.09", "densityDpi": 420, "xdpi": 400.0, "ydpi": 400.0},
              "posture": {"profileKey": "no_fold-portrait", "foldState": "no_fold", "foldingFeatures": [], "orientation": "portrait",
                "windowWidthPx": 1080, "windowHeightPx": 2400, "screenWidthDp": 411, "screenHeightDp": 914, "typingPosture": "two_thumbs"},
              "postureAtEnd": {"profileKey": "no_fold-portrait", "foldState": "no_fold", "foldingFeatures": [], "orientation": "portrait",
                "windowWidthPx": 1080, "windowHeightPx": 2400, "screenWidthDp": 411, "screenHeightDp": 914, "typingPosture": "two_thumbs"},
              "geometries": [],
              "touches": [],
              "commits": [],
              "hardwareKeys": [{"action": "down", "keyCode": 29, "scanCode": 30, "repeatCount": 0, "metaState": 0, "flags": 8,
                "eventTimeMs": 999, "downTimeMs": 999, "deviceId": 5, "deviceName": "Keyboard", "vendorId": 1, "productId": 2,
                "baseChar": "a", "processedUptimeMs": 1000}],
              "textEdits": [{"uptimeMs": 1001, "start": 0, "end": 0, "replaced": "", "inserted": "a", "outcome": "append"}],
              "diagnostics": [{"type": "long_press_fired", "uptimeMs": 1300, "durationMs": 300, "keyCode": 29, "detail": "threshold=300 mode=alt pressed=false"}],
              "context": null,
              "contextAtEnd": null,
              "contentType": "words",
              "speaker": null,
              "contentItems": [],
              "plannedTargets": ["ab"]
            }
        """.trimIndent()
    }
}
