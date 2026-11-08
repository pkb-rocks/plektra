package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.os.Looper
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.inputmethod.aospkeyboard.KeyCommit
import rocks.pkb.plektra.inputmethod.aospkeyboard.KeyboardGeometrySnapshot
import rocks.pkb.plektra.inputmethod.aospkeyboard.TimerEvent
import rocks.pkb.plektra.inputmethod.aospkeyboard.TouchSample
import rocks.pkb.plektra.inputmethod.aospkeyboard.TouchTraceHub
import rocks.pkb.plektra.inputmethod.aospkeyboard.TouchTraceSink
import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AlternateCharacterManagerLongPressTraceTest {

    private lateinit var context: Context
    private lateinit var prefs: android.content.SharedPreferences
    private val timers = mutableListOf<TimerEvent>()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefs = context.getSharedPreferences("alt_trace_tests", Context.MODE_PRIVATE)
        prefs.edit().clear().putLong("long_press_threshold", 300L).commit()
        SettingsManager.setLongPressModifier(context, "alt")
        TouchTraceHub.sink = object : TouchTraceSink {
            override fun onGeometry(snapshot: KeyboardGeometrySnapshot) = Unit
            override fun onTouch(sample: TouchSample) = Unit
            override fun onKeyCommitted(commit: KeyCommit) = Unit
            override fun onTimer(event: TimerEvent) {
                timers += event
            }
        }
        TouchTraceHub.editorAllowsTrace = true
    }

    @After
    fun tearDown() {
        TouchTraceHub.sink = null
        TouchTraceHub.editorAllowsTrace = false
    }

    @Test
    fun heldKeyRecordsScheduledAndFired() {
        val manager = AlternateCharacterManager(context.assets, prefs, context)
        press(manager)
        shadowOf(Looper.getMainLooper()).idleFor(310, TimeUnit.MILLISECONDS)
        manager.handleKeyUp(KeyEvent.KEYCODE_A, symKeyActive = false)

        assertEquals(listOf("scheduled", "fired"), timers.map { it.kind })
        assertEquals(300L, timers.first().thresholdMs)
        assertEquals(true, timers.last().keyStillPressed)
        assertEquals(1, timers.map { it.generation }.distinct().size)
    }

    @Test
    fun releaseBeforeThresholdRecordsCancellation() {
        val manager = AlternateCharacterManager(context.assets, prefs, context)
        press(manager)
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
        manager.handleKeyUp(KeyEvent.KEYCODE_A, symKeyActive = false)
        shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)

        assertEquals(listOf("scheduled", "cancelled"), timers.map { it.kind })
        assertEquals("key_up", timers.last().reason)
    }

    @Test
    fun noTraceWithoutTrainingEditor() {
        TouchTraceHub.editorAllowsTrace = false
        val manager = AlternateCharacterManager(context.assets, prefs, context)
        press(manager)
        shadowOf(Looper.getMainLooper()).idleFor(310, TimeUnit.MILLISECONDS)

        assertEquals(emptyList<TimerEvent>(), timers)
    }

    private fun press(manager: AlternateCharacterManager) {
        manager.handleKeyWithAltMapping(
            keyCode = KeyEvent.KEYCODE_A,
            event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A),
            capsLockEnabled = false,
            inputConnection = noOpInputConnection(),
            layoutChar = 'a'
        )
    }

    private fun noOpInputConnection(): InputConnection =
        Proxy.newProxyInstance(InputConnection::class.java.classLoader, arrayOf(InputConnection::class.java)) { _, method, _ ->
            when (method.returnType) {
                java.lang.Boolean.TYPE -> true
                Integer.TYPE -> 0
                else -> null
            }
        } as InputConnection
}
