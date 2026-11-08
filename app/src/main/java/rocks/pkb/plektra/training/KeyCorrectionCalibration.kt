package rocks.pkb.plektra.training

import android.content.res.AssetManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import rocks.pkb.plektra.inputmethod.aospkeyboard.KeyCommit
import rocks.pkb.plektra.inputmethod.aospkeyboard.KeyCorrection
import rocks.pkb.plektra.inputmethod.aospkeyboard.KeyCorrectionProfile
import rocks.pkb.plektra.inputmethod.aospkeyboard.KeyCorrectionValidation
import rocks.pkb.plektra.inputmethod.aospkeyboard.KeyOffset
import rocks.pkb.plektra.inputmethod.aospkeyboard.KeyboardGeometrySnapshot
import rocks.pkb.plektra.inputmethod.aospkeyboard.TouchSample
import rocks.pkb.plektra.inputmethod.aospkeyboard.TraceKey
import java.io.File
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Derives per-profile touch models (mean offset per key, spread) from the user's own
 * typing-training sessions. Each tap is attributed to the character the user meant by aligning
 * the typed text to the target text; mistakes therefore count towards the intended key.
 */
object KeyCorrectionCalibration {

    data class Tap(
        val profileKey: String,
        val session: Int,
        val char: String,
        val dxMm: Float,
        val dyMm: Float,
        val x: Float,
        val y: Float,
        val geometry: KeyboardGeometrySnapshot,
        /** Letters of the current word typed before this tap. */
        val prefix: String
    )

    @Serializable
    private class Posture(val orientation: String = "portrait", val screenWidthDp: Int = 0, val screenHeightDp: Int = 0)

    @Serializable
    private class Context(val display: Map<String, String> = emptyMap())

    @Serializable
    private class Session(
        val seed: Long = 0,
        val wordList: String = "",
        val sessionMode: String = "time-60s",
        val plannedTargets: List<String> = emptyList(),
        val typedText: String = "",
        val ignoreAccents: Boolean = false,
        val posture: Posture = Posture(),
        val context: Context? = null,
        val geometries: List<KeyboardGeometrySnapshot> = emptyList(),
        val touches: List<TouchSample> = emptyList(),
        val commits: List<KeyCommit> = emptyList()
    )

    private val json = Json { ignoreUnknownKeys = true }
    private const val SHRINK = 15f
    private const val MIN_SAMPLES = 5

    fun calibrate(files: List<File>, assets: AssetManager, nowEpochMs: Long): List<KeyCorrectionProfile> {
        val taps = files.flatMapIndexed { index, file ->
            val session = runCatching { json.decodeFromString(Session.serializer(), file.readText()) }.getOrNull()
                ?: return@flatMapIndexed emptyList()
            taps(session, index, assets)
        }
        return taps.groupBy { it.profileKey }.map { (key, profileTaps) ->
            val model = fit(key, profileTaps, nowEpochMs)
            val validation = validate(profileTaps)
            model.copy(
                offsets = if (validation?.offsetsEnabled == true) model.measuredOffsets else emptyMap(),
                sessions = profileTaps.map { it.session }.distinct().size,
                validation = validation
            )
        }.sortedBy { it.key }
    }

    /** Mean offset per key (shrunk towards zero for few samples) and residual spread. */
    private fun fit(key: String, taps: List<Tap>, nowEpochMs: Long): KeyCorrectionProfile {
        val offsets = taps.groupBy { it.char }.filterValues { it.size >= MIN_SAMPLES }.mapValues { (_, list) ->
            val shrink = list.size / (list.size + SHRINK)
            KeyOffset(list.map { it.dxMm }.average().toFloat() * shrink, list.map { it.dyMm }.average().toFloat() * shrink, list.size)
        }
        val residuals = taps.map { tap ->
            val offset = offsets[tap.char]
            (tap.dxMm - (offset?.dxMm ?: 0f)) to (tap.dyMm - (offset?.dyMm ?: 0f))
        }
        return KeyCorrectionProfile(
            key = key,
            sigmaXMm = sqrt(residuals.map { it.first * it.first }.average()).toFloat().coerceIn(1.2f, 8f),
            sigmaYMm = sqrt(residuals.map { it.second * it.second }.average()).toFloat().coerceIn(1.2f, 8f),
            offsets = offsets,
            sessions = 0,
            taps = taps.size,
            createdAtEpochMs = nowEpochMs,
            measuredOffsets = offsets
        )
    }

    /**
     * Leave-one-session-out replay: offsets are only enabled when they save errors on sessions
     * they were not fitted on. Needs at least two sessions in the profile.
     */
    private fun validate(taps: List<Tap>): KeyCorrectionValidation? {
        val sessions = taps.map { it.session }.distinct()
        if (sessions.size < 2) return null
        var hitTest = 0
        var without = 0
        var with = 0
        for (heldOut in sessions) {
            val model = fit("", taps.filter { it.session != heldOut }, 0)
            val noOffsets = model.copy(offsets = emptyMap())
            for (tap in taps.filter { it.session == heldOut }) {
                val geometric = geometricKeyIndex(tap)
                if (!tap.geometry.keys[geometric].matches(tap.char)) hitTest++
                if (!decideMatches(tap, geometric, noOffsets)) without++
                if (!decideMatches(tap, geometric, model)) with++
            }
        }
        return KeyCorrectionValidation(taps.size, hitTest, without, with, offsetsEnabled = with < without)
    }

    private fun decideMatches(tap: Tap, geometric: Int, profile: KeyCorrectionProfile): Boolean {
        val candidates = tap.geometry.keys.filter { it.type == "CHAR" && it.output.singleOrNull()?.isLetterOrDigit() == true }
            .map { KeyCorrection.Candidate(it.index, it.output, it.hit[0], it.hit[1], it.hit[2], it.hit[3]) }
        val chosen = KeyCorrection.decide(tap.x, tap.y, candidates, geometric, tap.geometry.xdpi, tap.geometry.ydpi, profile, tap.prefix).index
        return tap.geometry.keys.getOrNull(chosen)?.matches(tap.char) == true
    }

    /** Mirrors the on-screen hit test: the key whose hit rectangle contains the touch, else the nearest. */
    private fun geometricKeyIndex(tap: Tap): Int {
        val keys = tap.geometry.keys
        keys.firstOrNull { tap.x >= it.hit[0] && tap.x <= it.hit[2] && tap.y >= it.hit[1] && tap.y <= it.hit[3] }?.let { return it.index }
        return keys.minBy { key ->
            val dx = tap.x - (key.hit[0] + key.hit[2]) / 2f
            val dy = tap.y - (key.hit[1] + key.hit[3]) / 2f
            dx * dx + dy * dy
        }.index
    }

    private fun TraceKey.matches(char: String): Boolean = output.lowercase() == char

    private fun taps(session: Session, sessionIndex: Int, assets: AssetManager): List<Tap> {
        if (session.geometries.isEmpty()) return emptyList()
        val geometries = session.geometries.associateBy { it.revision }
        val targets = session.plannedTargets.ifEmpty { replayWordTargets(session, assets) }
        if (targets.isEmpty()) return emptyList()
        // With ignored accents the participant may have typed e for é: compare base letters.
        fun fold(text: String) = TypingTrainingAlignment.fold(text, ignoreCase = true, ignoreAccents = session.ignoreAccents)
        // Folding keeps lengths, so the unfolded texts share offsets and still name the real key (ü, not u).
        fun lower(text: String) = TypingTrainingAlignment.fold(text, ignoreCase = true, ignoreAccents = false)
        val typed = fold(session.typedText.replace('\n', ' '))
        val rawTarget = lower(targets.joinToString(" ")).take(typed.length * 2 + 64)
        val target = fold(rawTarget)

        // Taps that produced text, in order, with their touch-down sample.
        val commits = session.commits.filter { commit ->
            commit.via == "tap" &&
                geometries[commit.geometryRevision]?.keys?.getOrNull(commit.keyIndex)?.type in TEXT_KEY_TYPES
        }
        val downs = session.touches.filter { it.phase == "down" || it.phase == "pointer_down" }
        val used = BooleanArray(downs.size)
        val commitDown = commits.map { commit ->
            val index = downs.indices.lastOrNull { i ->
                !used[i] && downs[i].uiKeyIndex == commit.keyIndex &&
                    downs[i].geometryRevision == commit.geometryRevision && downs[i].eventTimeMs <= commit.uptimeMs
            }
            index?.let { used[it] = true; downs[it] }
        }
        val rawOutput = lower(commits.joinToString("") { it.output })
        val output = fold(rawOutput)
        val typedToCommit = align(output, typed)
        val typedToTarget = align(target, typed)

        val smallestWidth = session.context?.display?.get("smallestScreenWidthDp")?.toIntOrNull()
            ?: minOf(session.posture.screenWidthDp, session.posture.screenHeightDp)
        val landscape = session.posture.orientation == "landscape"
        return typed.indices.mapNotNull { j ->
            val targetIndex = typedToTarget[j].takeIf { it >= 0 } ?: return@mapNotNull null
            val intended = rawTarget[targetIndex]
            if (intended == ' ' || typed[j] == ' ') return@mapNotNull null
            val commitIndex = typedToCommit[j].takeIf { it >= 0 && output[it] == typed[j] } ?: return@mapNotNull null
            // A base letter typed on purpose for an accented one (u for ü) is no miss of the ü key.
            if (rawOutput[commitIndex] != intended && output[commitIndex] == target[targetIndex]) return@mapNotNull null
            val down = commitDown[commitIndex] ?: return@mapNotNull null
            val geometry = geometries[down.geometryRevision] ?: return@mapNotNull null
            val key = geometry.keys.firstOrNull { it.type == "CHAR" && it.output.lowercase() == intended.toString() }
                ?: return@mapNotNull null
            val mmX = 25.4f / geometry.xdpi
            val mmY = 25.4f / geometry.ydpi
            val wordStart = typed.lastIndexOf(' ', j - 1) + 1
            Tap(
                profileKey = KeyCorrection.profileKey(smallestWidth, landscape, geometry.layoutName, geometry.layoutStyle),
                session = sessionIndex,
                char = intended.toString(),
                dxMm = (down.x - (key.hit[0] + key.hit[2]) / 2f) * mmX,
                dyMm = (down.y - (key.hit[1] + key.hit[3]) / 2f) * mmY,
                x = down.x,
                y = down.y,
                geometry = geometry,
                prefix = typed.substring(wordStart, j).filter { it.isLetter() }
            )
        }
    }

    /** Sessions before schema 5 only stored the seed; frequent-word targets are replayed from it. */
    private fun replayWordTargets(session: Session, assets: AssetManager): List<String> {
        val words = runCatching {
            assets.open("typing-training/${session.wordList}.txt").use { it.readBytes() }.toString(Charsets.UTF_8)
                .lines().filter { it.isNotBlank() }
        }.getOrNull() ?: return emptyList()
        val random = Random(session.seed)
        return List(if (session.sessionMode == "words-50") 50 else 400) { words[random.nextInt(words.size)] }
    }

    /**
     * Semi-global edit alignment: every character of [b] is aligned, [a] may end early.
     * Returns for each index of [b] the aligned index in [a], or -1 for an inserted character.
     */
    internal fun align(a: String, b: String): IntArray {
        val n = a.length
        val m = b.length
        val d = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) d[i][0] = i
        for (j in 0..m) d[0][j] = j
        for (i in 1..n) for (j in 1..m) {
            d[i][j] = minOf(d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1, d[i - 1][j] + 1, d[i][j - 1] + 1)
        }
        var i = (0..n).minBy { d[it][m] }
        var j = m
        val result = IntArray(m) { -1 }
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && d[i][j] == d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1 -> {
                    result[j - 1] = i - 1; i--; j--
                }
                j > 0 && d[i][j] == d[i][j - 1] + 1 -> j--
                else -> i--
            }
        }
        return result
    }

    private val TEXT_KEY_TYPES = setOf("CHAR", "SPACE", "COMMA", "PERIOD")
}
