package rocks.pkb.plektra.training

import kotlinx.serialization.Serializable
import rocks.pkb.plektra.inputmethod.aospkeyboard.HardwareKeyDecision
import rocks.pkb.plektra.inputmethod.aospkeyboard.HardwareKeySample
import rocks.pkb.plektra.inputmethod.aospkeyboard.KeyCommit
import rocks.pkb.plektra.inputmethod.aospkeyboard.KeyboardGeometrySnapshot
import rocks.pkb.plektra.inputmethod.aospkeyboard.TimerEvent
import rocks.pkb.plektra.inputmethod.aospkeyboard.TouchSample
import rocks.pkb.plektra.inputmethod.aospkeyboard.TraceDiagnostic
import rocks.pkb.plektra.inputmethod.aospkeyboard.TouchTraceSink

/**
 * Collects traced input for one typing-training session. Main thread only.
 *
 * Every list is bounded; events beyond a limit are counted in [captureStats] instead of being
 * stored, so a truncated session stays recognisable.
 */
class TypingTrainingRecorder(
    val timeline: TypingTrainingContextTimeline = TypingTrainingContextTimeline(),
    private val onUserDelete: (uptimeMs: Long) -> Unit = {}
) : TouchTraceSink {
    val geometries = mutableListOf<KeyboardGeometrySnapshot>()
    val touches = mutableListOf<TouchSample>()
    val commits = mutableListOf<KeyCommit>()
    val hardwareKeys = mutableListOf<HardwareKeySample>()
    val keyDecisions = mutableListOf<HardwareKeyDecision>()
    val timerEvents = mutableListOf<TimerEvent>()
    val diagnostics = mutableListOf<TraceDiagnostic>()
    val textEdits = mutableListOf<TextEdit>()
    val backspaceMarks = mutableListOf<BackspaceMark>()

    private val counts = sortedMapOf<String, Long>()
    private val dropped = sortedMapOf<String, Long>()
    private var firstSeq = 0L
    private var lastSeq = 0L

    override fun onGeometry(snapshot: KeyboardGeometrySnapshot) {
        add(KIND_GEOMETRY, geometries, snapshot)
        timeline.onKeyboardGeometry(snapshot)
    }

    override fun onTouch(sample: TouchSample) {
        add(KIND_TOUCH, touches, sample)
    }

    override fun onKeyCommitted(commit: KeyCommit) {
        add(KIND_COMMIT, commits, commit)
    }

    override fun onHardwareKey(sample: HardwareKeySample) {
        noteSeq(sample.seq)
        add(KIND_HARDWARE_KEY, hardwareKeys, sample)
    }

    override fun onUserDelete(uptimeMs: Long) = onUserDelete.invoke(uptimeMs)

    override fun onDiagnostic(event: TraceDiagnostic) {
        add(KIND_DIAGNOSTIC, diagnostics, event)
    }

    override fun onKeyDecision(decision: HardwareKeyDecision) {
        noteSeq(decision.seq)
        add(KIND_KEY_DECISION, keyDecisions, decision)
    }

    override fun onTimer(event: TimerEvent) {
        noteSeq(event.seq)
        add(KIND_TIMER, timerEvents, event)
    }

    fun onTextEdit(edit: TextEdit) {
        add(KIND_TEXT_EDIT, textEdits, edit)
    }

    fun onBackspaceMark(mark: BackspaceMark) {
        add(KIND_BACKSPACE_MARK, backspaceMarks, mark)
    }

    /** Counts of recorded and dropped events. */
    fun captureStats(): CaptureStats {
        timeline.changes.forEach { noteSeq(it.seq) }
        return CaptureStats(
            countsByKind = (counts + timeline.counts()).toSortedMap(),
            droppedByKind = (dropped + timeline.dropped()).filterValues { it > 0 }.toSortedMap(),
            limits = LIMITS + timeline.limits(),
            firstSeq = firstSeq,
            lastSeq = lastSeq
        )
    }

    private fun noteSeq(seq: Long) {
        if (seq <= 0) return
        if (firstSeq == 0L || seq < firstSeq) firstSeq = seq
        if (seq > lastSeq) lastSeq = seq
    }

    private fun <T> add(kind: String, list: MutableList<T>, item: T) {
        counts[kind] = (counts[kind] ?: 0L) + 1
        if (list.size < LIMITS.getValue(kind)) list += item else dropped[kind] = (dropped[kind] ?: 0L) + 1
    }

    companion object {
        const val KIND_GEOMETRY = "geometry"
        const val KIND_TOUCH = "touch"
        const val KIND_COMMIT = "commit"
        const val KIND_HARDWARE_KEY = "hardware_key"
        const val KIND_KEY_DECISION = "key_decision"
        const val KIND_TIMER = "timer"
        const val KIND_DIAGNOSTIC = "diagnostic"
        const val KIND_TEXT_EDIT = "text_edit"
        const val KIND_BACKSPACE_MARK = "backspace_mark"

        /** Generous for a 60-second session; touches include MOVE and historical samples. */
        val LIMITS: Map<String, Int> = sortedMapOf(
            KIND_GEOMETRY to 1_000,
            KIND_TOUCH to 200_000,
            KIND_COMMIT to 20_000,
            KIND_HARDWARE_KEY to 40_000,
            KIND_KEY_DECISION to 20_000,
            KIND_TIMER to 40_000,
            KIND_DIAGNOSTIC to 5_000,
            KIND_TEXT_EDIT to 20_000,
            KIND_BACKSPACE_MARK to 5_000
        )
    }
}

/**
 * Recorded and dropped events per kind. A count larger than the stored list means the limit was
 * hit; nothing is shortened silently.
 */
@Serializable
data class CaptureStats(
    val countsByKind: Map<String, Long> = emptyMap(),
    val droppedByKind: Map<String, Long> = emptyMap(),
    val limits: Map<String, Int> = emptyMap(),
    /** Range of trace sequence numbers seen; 0 when none. */
    val firstSeq: Long = 0,
    val lastSeq: Long = 0,
    val instrumentationVersion: Int = INSTRUMENTATION_VERSION
) {
    companion object {
        const val INSTRUMENTATION_VERSION = 1
    }
}

@Serializable
data class TypingTrainingDevice(
    val manufacturer: String,
    val model: String,
    val sdkInt: Int,
    val appVersion: String,
    val densityDpi: Int,
    val xdpi: Float,
    val ydpi: Float
)

@Serializable
data class TypingTrainingPosture(
    /** Stable key for per-posture geometry profiles, e.g. `unfolded-portrait-qwertz-EXTENDED_ISO`. */
    val profileKey: String,
    val foldState: String,
    val foldingFeatures: List<String>,
    val orientation: String,
    val windowWidthPx: Int,
    val windowHeightPx: Int,
    val screenWidthDp: Int,
    val screenHeightDp: Int,
    /** Self-reported by the participant: two_thumbs, one_thumb or index_finger. */
    val typingPosture: String
)

@Serializable
data class TypingTrainingSession(
    /**
     * 6 adds [contextChanges], [inputDeviceEpisodes], [keyDecisions], [timerEvents], [captureStats],
     * seq/origin on hardware keys and build provenance in [context]; all optional for older sessions.
     */
    val schemaVersion: Int = SCHEMA_VERSION,
    val startedAtEpochMs: Long,
    val firstInputUptimeMs: Long?,
    val endedUptimeMs: Long,
    val language: String,
    val wordList: String,
    val wordListSha256: String,
    val seed: Long,
    val sessionMode: String,
    val targetWords: List<String>,
    val typedText: String,
    val blockedEdits: Int,
    /** Target words were shown in lower case and case differences are not errors. */
    val ignoreCase: Boolean,
    /** Accents and other diacritics were not errors (é counts as e). */
    val ignoreAccents: Boolean = false,
    /** Blocked Backspace presses: the participant noticed an error at this point. */
    val backspaceMarks: List<BackspaceMark>,
    val device: TypingTrainingDevice,
    val posture: TypingTrainingPosture,
    /** Differs from [posture] when the device was folded, unfolded or rotated mid-session. */
    val postureAtEnd: TypingTrainingPosture,
    val geometries: List<KeyboardGeometrySnapshot>,
    val touches: List<TouchSample>,
    val commits: List<KeyCommit>,
    val hardwareKeys: List<HardwareKeySample> = emptyList(),
    /** Every change the editor saw, accepted or blocked: ground truth for any input source. */
    val textEdits: List<TextEdit> = emptyList(),
    /** Long-press timer firings and main-thread stalls during the session. */
    val diagnostics: List<TraceDiagnostic> = emptyList(),
    val context: TypingTrainingContext? = null,
    val contextAtEnd: TypingTrainingContext? = null,
    /** `words`, `phrases`, `chat` or `tokens`. */
    val contentType: String = "words",
    /** Chat speaker the participant typed (A or B). */
    val speaker: String? = null,
    /** Phrases, tokens or chat messages shown, including the chat partner's context messages. */
    val contentItems: List<TrainingContentItem> = emptyList(),
    /** Complete planned target tokens; [targetWords] only holds the part reached. */
    val plannedTargets: List<String> = emptyList(),
    /** Fold, window, keyboard and input-device changes observed during the session (schema 6). */
    val contextChanges: List<ContextChange> = emptyList(),
    /** Keyboard input devices present or connected during the session (schema 6). */
    val inputDeviceEpisodes: List<InputDeviceEpisode> = emptyList(),
    /** Filter and remapping decisions about [hardwareKeys], linked by seq (schema 6). */
    val keyDecisions: List<HardwareKeyDecision> = emptyList(),
    /** Hardware long-press timer lifecycle (schema 6). */
    val timerEvents: List<TimerEvent> = emptyList(),
    /** Null before schema 6. */
    val captureStats: CaptureStats? = null
) {
    companion object {
        const val SCHEMA_VERSION = 6
    }
}

@Serializable
data class TextEdit(
    val uptimeMs: Long,
    val start: Int,
    val end: Int,
    val replaced: String,
    val inserted: String,
    /** `append`, `ime_edit` (long press, variation, multi-tap) or `blocked` (edit away from the end). */
    val outcome: String
)

@Serializable
data class BackspaceMark(
    val uptimeMs: Long,
    /** Length of the typed text when Backspace was pressed. */
    val textLength: Int,
    /** Target word the mark refers to (display alignment). */
    val targetIndex: Int,
    /** Characters of that word typed so far; equals the typed word length after a space. */
    val charInWord: Int,
    /** True when Backspace followed a space, i.e. the mark refers to the previous word. */
    val afterSpace: Boolean
)
