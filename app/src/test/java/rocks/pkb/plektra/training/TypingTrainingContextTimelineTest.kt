package rocks.pkb.plektra.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import rocks.pkb.plektra.inputmethod.aospkeyboard.KeyboardGeometrySnapshot

class TypingTrainingContextTimelineTest {

    private var now = 1_000L
    private var seq = 0L
    private val timeline = TypingTrainingContextTimeline(clock = { now }, nextSeq = { ++seq })

    @Test
    fun recordsOnlyChangedPartialContexts() {
        timeline.onWindow(mapOf("orientation" to "portrait", "windowWidthPx" to "1080"))
        timeline.onFold(mapOf("foldState" to "no_fold"))
        now = 2_000
        timeline.onWindow(mapOf("orientation" to "portrait", "windowWidthPx" to "1080"))
        timeline.onFold(mapOf("foldState" to "unfolded"))
        now = 3_000
        timeline.onWindow(mapOf("orientation" to "landscape", "windowWidthPx" to "2400"))

        assertEquals(listOf("window", "fold", "fold", "window"), timeline.changes.map { it.reason })
        assertEquals(listOf(1L, 2L, 3L, 4L), timeline.changes.map { it.seq })
        assertEquals(listOf(1_000L, 1_000L, 2_000L, 3_000L), timeline.changes.map { it.uptimeMs })
        assertEquals("unfolded", timeline.changes[2].values["foldState"])
    }

    @Test
    fun keyboardEntriesIgnoreRevisionOnlyChanges() {
        timeline.onKeyboardGeometry(geometry(revision = 1, shifted = false))
        timeline.onKeyboardGeometry(geometry(revision = 2, shifted = false))
        timeline.onKeyboardGeometry(geometry(revision = 3, shifted = true))
        timeline.onKeyboardGeometry(geometry(revision = 4, shifted = true, layoutName = "qwerty"))

        assertEquals(listOf("1", "3", "4"), timeline.changes.map { it.values["geometryRevision"] })
        assertEquals("qwerty", timeline.changes.last().values["layoutName"])
    }

    @Test
    fun inputDeviceEpisodesTrackConnectAndDisconnect() {
        timeline.startDevices(listOf(device(id = 3, descriptor = "builtin")))
        now = 5_000
        timeline.onDeviceAdded(device(id = 9, descriptor = "usb"))
        now = 6_000
        timeline.onDeviceChanged(device(id = 9, descriptor = "usb"))
        timeline.onDeviceChanged(device(id = 9, descriptor = "usb", name = "USB Keyboard 2"))
        now = 7_000
        timeline.onDeviceRemoved(9)
        now = 8_000
        timeline.onDeviceAdded(device(id = 9, descriptor = "usb"))

        val episodes = timeline.inputDeviceEpisodes
        assertEquals(listOf(1, 2, 3), episodes.map { it.id })
        assertNull(episodes[0].connectedUptimeMs)
        assertNull(episodes[0].disconnectedUptimeMs)
        assertEquals(5_000L, episodes[1].connectedUptimeMs)
        assertEquals(7_000L, episodes[1].disconnectedUptimeMs)
        assertEquals(8_000L, episodes[2].connectedUptimeMs)
        assertEquals(
            listOf("input_device_added", "input_device_changed", "input_device_removed", "input_device_added"),
            timeline.changes.map { it.reason }
        )
        assertEquals(listOf(2, 2, 2, 3), timeline.changes.map { it.deviceEpisodeId })
        assertEquals("usb", timeline.changes[2].values["descriptor"])
    }

    @Test
    fun deviceThatStopsBeingAKeyboardEndsItsEpisode() {
        timeline.startDevices(listOf(device(id = 3, descriptor = "builtin")))
        now = 4_000
        timeline.onDeviceChanged(null, deviceId = 3)

        assertEquals(4_000L, timeline.inputDeviceEpisodes.single().disconnectedUptimeMs)
        assertEquals("input_device_removed", timeline.changes.single().reason)
    }

    @Test
    fun overflowIsCountedInsteadOfStored() {
        val bounded = TypingTrainingContextTimeline(clock = { now }, nextSeq = { ++seq }, maxChanges = 2)
        repeat(5) { bounded.onWindow(mapOf("windowWidthPx" to it.toString())) }

        assertEquals(2, bounded.changes.size)
        assertEquals(5L, bounded.counts()[TypingTrainingContextTimeline.KIND])
        assertEquals(3L, bounded.dropped()[TypingTrainingContextTimeline.KIND])
    }

    @Test
    fun episodesBeyondTheLimitAreCountedInsteadOfStored() {
        val bounded = TypingTrainingContextTimeline(clock = { now }, nextSeq = { ++seq }, maxEpisodes = 2)
        repeat(3) { bounded.onDeviceAdded(device(it, "kb-$it")) }

        assertEquals(2, bounded.inputDeviceEpisodes.size)
        assertEquals(3L, bounded.counts()[TypingTrainingContextTimeline.KIND_EPISODES])
        assertEquals(1L, bounded.dropped()[TypingTrainingContextTimeline.KIND_EPISODES])
        assertNull(bounded.changes.last().deviceEpisodeId)
    }

    private fun geometry(revision: Int, shifted: Boolean, layoutName: String = "qwertz") = KeyboardGeometrySnapshot(
        revision = revision, uptimeMs = now, layoutName = layoutName, layoutStyle = "EXTENDED_ISO",
        shifted = shifted, numberRow = false, nearestKeyTouch = true,
        viewWidthPx = 1080, viewHeightPx = 600, viewLeftOnScreenPx = 0, viewTopOnScreenPx = 1800,
        xdpi = 400f, ydpi = 400f, keys = emptyList()
    )

    private fun device(id: Int, descriptor: String, name: String = "Keyboard") = TrainingInputDevice(
        id = id, descriptor = descriptor, name = name, vendorId = 1, productId = 2,
        keyboardType = 2, sources = 0x101, external = true, virtual = false
    )
}
