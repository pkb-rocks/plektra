package rocks.pkb.plektra.inputmethod

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputConnection
import rocks.pkb.plektra.SettingsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import org.mockito.Mockito.mock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ScreenTrackpadControllerTest {

    private data class Replay(val keyCode: Int, val down: KeyEvent, val up: KeyEvent?)

    private val replays = mutableListOf<Replay>()
    private lateinit var controller: ScreenTrackpadController

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("plektra_prefs", android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        replays.clear()
        controller = ScreenTrackpadController(
            service = context,
            handler = Handler(Looper.getMainLooper()),
            inputConnectionProvider = { null as InputConnection? },
            isShiftActive = { false },
            isEditableFieldFocused = { true },
            replayKey = { keyCode, down, up -> replays += Replay(keyCode, down, up) }
        )
    }

    private fun key(action: Int, keyCode: Int, time: Long = 1000L, repeat: Int = 0) =
        KeyEvent(time, time, action, keyCode, repeat)

    private fun down(keyCode: Int, time: Long = 1000L) = key(KeyEvent.ACTION_DOWN, keyCode, time)
    private fun up(keyCode: Int, time: Long = 1100L) = key(KeyEvent.ACTION_UP, keyCode, time)

    private fun controllerDown(keyCode: Int, event: KeyEvent = down(keyCode)) =
        controller.onKeyDown(keyCode, event, keyCode, event)

    private fun controllerUp(keyCode: Int, event: KeyEvent = up(keyCode)) =
        controller.onKeyUp(keyCode, event, keyCode, event)

    @Test
    fun disabledTrackpadNeverConsumesKeys() {
        assertFalse(controllerDown(KeyEvent.KEYCODE_SPACE))
        assertFalse(controllerUp(KeyEvent.KEYCODE_SPACE))
        assertTrue(replays.isEmpty())
    }

    @Test
    fun holdModeSwallowsTheTriggerAndReplaysAQuickTap() {
        SettingsManager.setScreenTrackpadEnabled(RuntimeEnvironment.getApplication(), true)

        assertTrue(controllerDown(KeyEvent.KEYCODE_SPACE))
        val upEvent = up(KeyEvent.KEYCODE_SPACE)
        assertTrue(controllerUp(KeyEvent.KEYCODE_SPACE, upEvent))

        assertEquals(1, replays.size)
        assertEquals(KeyEvent.KEYCODE_SPACE, replays[0].keyCode)
        assertEquals(KeyEvent.ACTION_DOWN, replays[0].down.action)
        assertEquals(upEvent, replays[0].up)
        assertFalse(controller.isActive)
    }

    @Test
    fun holdModeChordReplaysOnlyTheTriggerDownAndLetsTheRealUpThrough() {
        SettingsManager.setScreenTrackpadEnabled(RuntimeEnvironment.getApplication(), true)

        assertTrue(controllerDown(KeyEvent.KEYCODE_SPACE))
        assertFalse(controllerDown(KeyEvent.KEYCODE_A, down(KeyEvent.KEYCODE_A, 1050L)))

        assertEquals(1, replays.size)
        assertEquals(null, replays[0].up)
        assertFalse(controllerUp(KeyEvent.KEYCODE_SPACE))
    }

    @Test
    fun nonTriggerKeysPassThrough() {
        SettingsManager.setScreenTrackpadEnabled(RuntimeEnvironment.getApplication(), true)

        assertFalse(controllerDown(KeyEvent.KEYCODE_A))
        assertFalse(controllerUp(KeyEvent.KEYCODE_A))
    }

    @Test
    fun triggerKeyFollowsTheConfiguredChoice() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setScreenTrackpadEnabled(context, true)
        SettingsManager.setScreenTrackpadTriggerKey(context, SettingsManager.SCREEN_TRACKPAD_TRIGGER_SHIFT_LEFT)

        assertFalse(controllerDown(KeyEvent.KEYCODE_SPACE))
        assertTrue(controllerDown(KeyEvent.KEYCODE_SHIFT_LEFT))
        assertFalse(controllerDown(KeyEvent.KEYCODE_SHIFT_RIGHT, down(KeyEvent.KEYCODE_SHIFT_RIGHT, 1010L)))
    }

    @Test
    fun ctrlSpaceIsNotATrigger() {
        SettingsManager.setScreenTrackpadEnabled(RuntimeEnvironment.getApplication(), true)
        val ctrlSpace = KeyEvent(1000L, 1000L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE, 0, KeyEvent.META_CTRL_ON)

        assertFalse(controller.onKeyDown(KeyEvent.KEYCODE_SPACE, ctrlSpace, KeyEvent.KEYCODE_SPACE, ctrlSpace))
    }

    @Test
    fun doubleTapModeLetsAFirstTapTypeNormally() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setScreenTrackpadEnabled(context, true)
        SettingsManager.setScreenTrackpadActivation(context, SettingsManager.SCREEN_TRACKPAD_ACTIVATION_DOUBLE_TAP)

        assertFalse(controllerDown(KeyEvent.KEYCODE_SPACE))
        assertFalse(controllerUp(KeyEvent.KEYCODE_SPACE))
        assertTrue(replays.isEmpty())
    }

    @Test
    fun hintIsAlwaysShownInStickyModes() {
        assertTrue(ScreenTrackpadController.shouldShowHint(sticky = true, hintSetting = false))
        assertTrue(ScreenTrackpadController.shouldShowHint(sticky = true, hintSetting = true))
        assertTrue(ScreenTrackpadController.shouldShowHint(sticky = false, hintSetting = true))
        assertFalse(ScreenTrackpadController.shouldShowHint(sticky = false, hintSetting = false))
    }

    @Test
    fun overlayWindowNeverKeepsTheScreenOn() {
        val flags = ScreenTrackpadController.OVERLAY_WINDOW_FLAGS
        assertEquals(0, flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        assertTrue(flags and android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
    }

    @Test
    fun hintIsAlwaysShownInAListedAppWithoutAField() {
        assertTrue(ScreenTrackpadController.shouldShowHint(sticky = false, hintSetting = false, withoutField = true))
        assertFalse(ScreenTrackpadController.shouldShowHint(sticky = false, hintSetting = false, withoutField = false))
    }

    private fun holdController(
        editable: Boolean,
        listed: Boolean,
        connection: InputConnection?,
        editableNow: () -> Boolean = { editable },
        started: (() -> Boolean)? = null,
    ): ScreenTrackpadController {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setScreenTrackpadEnabled(context, true)
        ShadowSettings.setCanDrawOverlays(true)
        return ScreenTrackpadController(
            service = context,
            handler = Handler(Looper.getMainLooper()),
            inputConnectionProvider = { connection },
            isShiftActive = { false },
            isEditableFieldFocused = editableNow,
            replayKey = { keyCode, down, up -> replays += Replay(keyCode, down, up) },
            isTerminalApp = { listed },
            hasStartedInputConnection = started ?: { connection != null },
        )
    }

    private fun holdTrigger(c: ScreenTrackpadController) {
        val d = down(KeyEvent.KEYCODE_SPACE)
        assertTrue(c.onKeyDown(KeyEvent.KEYCODE_SPACE, d, KeyEvent.KEYCODE_SPACE, d))
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
    }

    @Test
    fun holdInAListedAppWithoutAFieldOpensTheOverlayAndReleaseClosesIt() {
        val c = holdController(editable = false, listed = true, connection = mock(InputConnection::class.java))

        holdTrigger(c)
        assertTrue(c.isActive)
        assertTrue(replays.isEmpty())

        assertTrue(c.onKeyUp(KeyEvent.KEYCODE_SPACE, up(KeyEvent.KEYCODE_SPACE), KeyEvent.KEYCODE_SPACE, up(KeyEvent.KEYCODE_SPACE)))
        assertFalse(c.isActive)
    }

    @Test
    fun holdInANonListedAppWithoutAFieldStillTypesTheKey() {
        val c = holdController(editable = false, listed = false, connection = mock(InputConnection::class.java))

        holdTrigger(c)
        assertFalse(c.isActive)
        assertEquals(1, replays.size)
    }

    @Test
    fun holdInAListedAppWithoutAnInputConnectionStillTypesTheKey() {
        val c = holdController(editable = false, listed = true, connection = null)

        holdTrigger(c)
        assertFalse(c.isActive)
        assertEquals(1, replays.size)
    }

    @Test
    fun holdOverAnEditableFieldOpensTheOverlayInAnyApp() {
        listOf(true, false).forEach { listed ->
            val c = holdController(editable = true, listed = listed, connection = mock(InputConnection::class.java))
            holdTrigger(c)
            assertTrue("listed=$listed", c.isActive)
            c.deactivate()
        }
        assertTrue(replays.isEmpty())
    }

    @Test
    fun deactivateClosesAListedAppOverlay() {
        val c = holdController(editable = false, listed = true, connection = mock(InputConnection::class.java))
        holdTrigger(c)
        assertTrue(c.isActive)

        // onFinishInput and onWindowHidden both call this.
        c.deactivate()
        assertFalse(c.isActive)
    }

    @Test
    fun holdInAListedAppWithOnlyTheBindingFallbackConnectionStillTypesTheKey() {
        // The provider is non-null (getCurrentInputConnection() falls back to the binding's), but
        // no started connection exists: the overlay must not open.
        val c = holdController(
            editable = false, listed = true, connection = mock(InputConnection::class.java),
            started = { false },
        )

        holdTrigger(c)
        assertFalse(c.isActive)
        assertEquals(1, replays.size)
    }

    @Test
    fun aTouchClosesTheOverlayOnceTheStartedConnectionIsGone() {
        var started = true
        val c = holdController(
            editable = false, listed = true, connection = mock(InputConnection::class.java),
            started = { started },
        )
        holdTrigger(c)
        assertTrue(c.isActive)

        started = false
        c.handleTouch(MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0))
        assertFalse(c.isActive)
    }

    @Test
    fun theHintAppearsWhenAnEditableFieldTurnsIntoAFieldlessViewUnderAnOpenOverlay() {
        SettingsManager.setScreenTrackpadHintEnabled(RuntimeEnvironment.getApplication(), false)
        var editable = true
        val c = holdController(
            editable = true, listed = true, connection = mock(InputConnection::class.java),
            editableNow = { editable },
        )
        holdTrigger(c)
        assertTrue(c.isActive)
        assertFalse(c.hintVisible)

        // A touch while the field is still there changes nothing.
        c.handleTouch(MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0))
        assertFalse(c.hintVisible)

        // restartInput swapped in a TYPE_NULL view (no onFinishInput): the next touch adds the pill.
        editable = false
        c.handleTouch(MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_UP, 0f, 0f, 0))
        c.handleTouch(MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0))
        assertTrue(c.isActive)
        assertTrue(c.hintVisible)
    }

    @Test
    fun holdWithoutEditableFieldFallsBackToATypedKeyAndNeverOpensTheOverlay() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.setScreenTrackpadEnabled(context, true)
        shadowOf(context).grantPermissions(android.Manifest.permission.SYSTEM_ALERT_WINDOW)
        val noField = ScreenTrackpadController(
            service = context,
            handler = Handler(Looper.getMainLooper()),
            inputConnectionProvider = { null as InputConnection? },
            isShiftActive = { false },
            isEditableFieldFocused = { false },
            replayKey = { keyCode, down, up -> replays += Replay(keyCode, down, up) },
        )
        val d = down(KeyEvent.KEYCODE_SPACE)
        assertTrue(noField.onKeyDown(KeyEvent.KEYCODE_SPACE, d, KeyEvent.KEYCODE_SPACE, d))
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))

        assertFalse(noField.isActive)
        assertEquals(1, replays.size)
    }
}
