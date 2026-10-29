package rocks.pkb.plektra

import android.content.Context
import android.os.Looper
import android.provider.Settings
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ImeSettingsObserverTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private fun notifyChange(name: String) {
        context.contentResolver.notifyChange(Settings.Secure.getUriFor(name), null)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun enabledListAndDefaultInputMethodChangesEachNotifyOnce() {
        var calls = 0
        val stop = observeImeSettings(context) { calls++ }

        notifyChange(Settings.Secure.ENABLED_INPUT_METHODS)
        assertEquals(1, calls)
        notifyChange(Settings.Secure.DEFAULT_INPUT_METHOD)
        assertEquals(2, calls)
        stop()
    }

    @Test
    fun otherSettingsAndStoppedObserverDoNotNotify() {
        var calls = 0
        val stop = observeImeSettings(context) { calls++ }

        notifyChange(Settings.Secure.ANDROID_ID)
        assertEquals(0, calls)

        stop()
        notifyChange(Settings.Secure.DEFAULT_INPUT_METHOD)
        assertEquals(0, calls)
    }

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun whileStartedObserverChecksOncePerStartAndOncePerChangeWhileStarted() {
        val owner = TestOwner()
        owner.registry.currentState = Lifecycle.State.CREATED
        var calls = 0
        val stop = observeImeSettingsWhileStarted(context, owner.lifecycle) { calls++ }

        // Below STARTED: no check and no observer.
        notifyChange(Settings.Secure.DEFAULT_INPUT_METHOD)
        assertEquals(0, calls)

        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertEquals(1, calls)

        notifyChange(Settings.Secure.DEFAULT_INPUT_METHOD)
        assertEquals(2, calls)

        // RESUMED and back to STARTED (unfocused multi-window pane): still observing, no new check.
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        assertEquals(2, calls)
        notifyChange(Settings.Secure.ENABLED_INPUT_METHODS)
        assertEquals(3, calls)

        // Stopped: no check, no observer.
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        notifyChange(Settings.Secure.DEFAULT_INPUT_METHOD)
        notifyChange(Settings.Secure.ENABLED_INPUT_METHODS)
        assertEquals(3, calls)

        // Started again (return from Settings, rotation): one more check, then observing again.
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertEquals(4, calls)
        notifyChange(Settings.Secure.DEFAULT_INPUT_METHOD)
        assertEquals(5, calls)

        stop()
        notifyChange(Settings.Secure.DEFAULT_INPUT_METHOD)
        assertEquals(5, calls)
    }

    @Test
    fun whileStartedObserverChecksOnceAtOnceWhenAlreadyStartedAndStopsOnDispose() {
        val owner = TestOwner()
        owner.registry.currentState = Lifecycle.State.STARTED
        var calls = 0
        val stop = observeImeSettingsWhileStarted(context, owner.lifecycle) { calls++ }

        // addObserver on a started lifecycle replays ON_START: a composition created late is checked.
        assertEquals(1, calls)

        notifyChange(Settings.Secure.ENABLED_INPUT_METHODS)
        assertEquals(2, calls)

        stop()
        notifyChange(Settings.Secure.ENABLED_INPUT_METHODS)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        notifyChange(Settings.Secure.ENABLED_INPUT_METHODS)
        assertEquals(2, calls)
    }
}
