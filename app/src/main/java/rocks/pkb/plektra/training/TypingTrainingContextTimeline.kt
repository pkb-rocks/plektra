package rocks.pkb.plektra.training

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import kotlinx.serialization.Serializable
import rocks.pkb.plektra.inputmethod.aospkeyboard.KeyboardGeometrySnapshot
import rocks.pkb.plektra.inputmethod.aospkeyboard.TouchTraceHub

/** One observed change of part of the session context; [values] holds only that part. */
@Serializable
data class ContextChange(
    /** Shared trace sequence, comparable with hardware keys, decisions and timers. */
    val seq: Long,
    val uptimeMs: Long,
    /**
     * `fold`, `window` (orientation, size, density), `keyboard` (layout, layer, view geometry),
     * `input_device_added`, `input_device_removed` or `input_device_changed`.
     */
    val reason: String,
    val values: Map<String, String> = emptyMap(),
    /** [InputDeviceEpisode.id] for input-device changes. */
    val deviceEpisodeId: Int? = null
)

/** A keyboard input device from connection (or session start) until removal (or session end). */
@Serializable
data class InputDeviceEpisode(
    val id: Int,
    /** Runtime id; may be reused after reconnecting. [descriptor] stays stable across reconnects. */
    val androidDeviceId: Int,
    val descriptor: String,
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val keyboardType: Int,
    val sources: Int,
    val external: Boolean,
    val virtual: Boolean,
    /** Null when the device was already present at session start. */
    val connectedUptimeMs: Long?,
    /** Null when the device was still present at session end. */
    val disconnectedUptimeMs: Long? = null
)

/** Device identity as far as the input-device listener needs it; free of Android types. */
data class TrainingInputDevice(
    val id: Int,
    val descriptor: String,
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val keyboardType: Int,
    val sources: Int,
    val external: Boolean,
    val virtual: Boolean
)

/**
 * Records context changes during a typing-training session: each partial context is deduplicated
 * against its last recorded value, so only real changes appear. Main thread only.
 */
class TypingTrainingContextTimeline(
    private val clock: () -> Long = SystemClock::uptimeMillis,
    private val nextSeq: () -> Long = TouchTraceHub::nextSeq,
    private val maxChanges: Int = MAX_CHANGES,
    private val maxEpisodes: Int = MAX_EPISODES
) {
    private val recorded = mutableListOf<ContextChange>()
    private val lastByReason = HashMap<String, Map<String, String>>()
    private val episodes = mutableListOf<InputDeviceEpisode>()
    private val openEpisodeByDeviceId = HashMap<Int, Int>()
    private val devicesByEpisode = HashMap<Int, TrainingInputDevice>()
    private var observed = 0L
    private var droppedChanges = 0L
    private var droppedEpisodes = 0L

    val changes: List<ContextChange> get() = recorded

    val inputDeviceEpisodes: List<InputDeviceEpisode> get() = episodes

    fun onFold(values: Map<String, String>) {
        recordIfChanged("fold", values)
    }

    fun onWindow(values: Map<String, String>) {
        recordIfChanged("window", values)
    }

    /** Geometry revisions change on every redraw-relevant update; only keyboard-relevant fields count. */
    fun onKeyboardGeometry(snapshot: KeyboardGeometrySnapshot) {
        val values = sortedMapOf(
            "layoutName" to snapshot.layoutName,
            "layoutStyle" to snapshot.layoutStyle,
            "shifted" to snapshot.shifted.toString(),
            "numberRow" to snapshot.numberRow.toString(),
            "nearestKeyTouch" to snapshot.nearestKeyTouch.toString(),
            "viewWidthPx" to snapshot.viewWidthPx.toString(),
            "viewHeightPx" to snapshot.viewHeightPx.toString(),
            "viewLeftOnScreenPx" to snapshot.viewLeftOnScreenPx.toString(),
            "viewTopOnScreenPx" to snapshot.viewTopOnScreenPx.toString(),
            "keyCount" to snapshot.keys.size.toString()
        )
        if (lastByReason["keyboard"] == values) return
        lastByReason["keyboard"] = values
        record("keyboard", values + ("geometryRevision" to snapshot.revision.toString()))
    }

    /** Devices present when the session starts; they open episodes without a change entry. */
    fun startDevices(devices: List<TrainingInputDevice>) {
        devices.forEach { openEpisode(it, connectedUptimeMs = null) }
    }

    fun onDeviceAdded(device: TrainingInputDevice) {
        if (device.id in openEpisodeByDeviceId) {
            onDeviceChanged(device)
            return
        }
        val now = clock()
        val episode = openEpisode(device, connectedUptimeMs = now)
        record("input_device_added", deviceValues(device), episode?.id, now)
    }

    fun onDeviceRemoved(deviceId: Int) {
        val episodeId = openEpisodeByDeviceId.remove(deviceId) ?: return
        val now = clock()
        val index = episodes.indexOfFirst { it.id == episodeId }
        val episode = episodes[index].copy(disconnectedUptimeMs = now)
        episodes[index] = episode
        devicesByEpisode.remove(episodeId)
        record(
            "input_device_removed",
            mapOf("androidDeviceId" to deviceId.toString(), "descriptor" to episode.descriptor),
            episodeId,
            now
        )
    }

    /** A changed device that stopped being a keyboard counts as removed. */
    fun onDeviceChanged(device: TrainingInputDevice?, deviceId: Int = device?.id ?: -1) {
        if (device == null) {
            onDeviceRemoved(deviceId)
            return
        }
        val episodeId = openEpisodeByDeviceId[device.id]
        if (episodeId == null) {
            onDeviceAdded(device)
            return
        }
        if (devicesByEpisode[episodeId] == device) return
        devicesByEpisode[episodeId] = device
        record("input_device_changed", deviceValues(device), episodeId)
    }

    fun counts(): Map<String, Long> = mapOf(KIND to observed, KIND_EPISODES to episodes.size + droppedEpisodes)

    fun dropped(): Map<String, Long> = mapOf(KIND to droppedChanges, KIND_EPISODES to droppedEpisodes)

    fun limits(): Map<String, Int> = mapOf(KIND to maxChanges, KIND_EPISODES to maxEpisodes)

    private fun recordIfChanged(reason: String, values: Map<String, String>) {
        if (lastByReason[reason] == values) return
        lastByReason[reason] = values
        record(reason, values)
    }

    private fun record(reason: String, values: Map<String, String>, episodeId: Int? = null, now: Long = clock()) {
        observed++
        if (recorded.size >= maxChanges) {
            droppedChanges++
            return
        }
        recorded += ContextChange(nextSeq(), now, reason, values.toSortedMap(), episodeId)
    }

    /** Beyond [maxEpisodes] a device is only counted; its changes are recorded without an episode. */
    private fun openEpisode(device: TrainingInputDevice, connectedUptimeMs: Long?): InputDeviceEpisode? {
        if (episodes.size >= maxEpisodes) {
            droppedEpisodes++
            return null
        }
        val episode = InputDeviceEpisode(
            id = episodes.size + 1,
            androidDeviceId = device.id,
            descriptor = device.descriptor,
            name = device.name,
            vendorId = device.vendorId,
            productId = device.productId,
            keyboardType = device.keyboardType,
            sources = device.sources,
            external = device.external,
            virtual = device.virtual,
            connectedUptimeMs = connectedUptimeMs
        )
        episodes += episode
        openEpisodeByDeviceId[device.id] = episode.id
        devicesByEpisode[episode.id] = device
        return episode
    }

    private fun deviceValues(device: TrainingInputDevice): Map<String, String> = mapOf(
        "androidDeviceId" to device.id.toString(),
        "descriptor" to device.descriptor,
        "name" to device.name,
        "vendorId" to device.vendorId.toString(),
        "productId" to device.productId.toString(),
        "keyboardType" to device.keyboardType.toString(),
        "sources" to "0x" + Integer.toHexString(device.sources),
        "external" to device.external.toString(),
        "virtual" to device.virtual.toString()
    )

    companion object {
        const val KIND = "context_change"
        const val KIND_EPISODES = "input_device_episode"
        const val MAX_CHANGES = 2_000
        const val MAX_EPISODES = 200
    }
}

/** Feeds input-device connects, disconnects and changes into a [TypingTrainingContextTimeline]. */
class TypingTrainingInputDeviceObserver(
    context: Context,
    private val timeline: TypingTrainingContextTimeline
) : InputManager.InputDeviceListener {
    private val inputManager = context.getSystemService(InputManager::class.java)

    fun start() {
        timeline.startDevices(InputDevice.getDeviceIds().toList().mapNotNull { trainingDevice(it) })
        inputManager?.registerInputDeviceListener(this, Handler(Looper.getMainLooper()))
    }

    fun stop() {
        inputManager?.unregisterInputDeviceListener(this)
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        trainingDevice(deviceId)?.let(timeline::onDeviceAdded)
    }

    override fun onInputDeviceRemoved(deviceId: Int) = timeline.onDeviceRemoved(deviceId)

    override fun onInputDeviceChanged(deviceId: Int) = timeline.onDeviceChanged(trainingDevice(deviceId), deviceId)

    companion object {
        /** Keyboards only, matching [TypingTrainingContext.inputDevices]. */
        fun trainingDevice(deviceId: Int): TrainingInputDevice? {
            val device = InputDevice.getDevice(deviceId) ?: return null
            val isKeyboard = (device.sources and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD ||
                device.keyboardType != InputDevice.KEYBOARD_TYPE_NONE
            if (!isKeyboard) return null
            return TrainingInputDevice(
                id = device.id,
                descriptor = device.descriptor.orEmpty(),
                name = device.name.orEmpty(),
                vendorId = device.vendorId,
                productId = device.productId,
                keyboardType = device.keyboardType,
                sources = device.sources,
                external = device.isExternal,
                virtual = device.isVirtual
            )
        }
    }
}
