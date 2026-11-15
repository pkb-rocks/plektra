package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import android.app.Activity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ImeChromeTransitionTest {
    @Test fun expandingViewportKeepsSoftwareKeysAtTheirFinalHeight() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val chrome = ImeChromeLayout(activity.get()).apply { orientation = LinearLayout.VERTICAL }
        val row = View(activity.get())
        val surface = View(activity.get()).apply { visibility = View.GONE }
        chrome.addView(row, LinearLayout.LayoutParams(-1, 80))
        chrome.addView(surface, LinearLayout.LayoutParams(-1, 600))
        chrome.surfaceView = surface
        activity.get().setContentView(chrome)
        val width = View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY)
        val height = View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST)
        chrome.measure(width, height)
        chrome.layout(0, 0, 600, chrome.measuredHeight)
        surface.visibility = View.VISIBLE
        chrome.softwareKeyboardModeActive = true
        chrome.measure(width, height)
        chrome.layout(0, 0, 600, chrome.measuredHeight)
        assertEquals(600, surface.measuredHeight)
        assertEquals(chrome.height, surface.bottom)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(250))
        chrome.measure(width, height)
        chrome.layout(0, 0, 600, chrome.measuredHeight)
        assertEquals(0, row.top)
        assertEquals(chrome.height, surface.bottom)
        activity.pause().stop().destroy()
    }

    @Test fun constrainedWindowReservesTheSuggestionRowBeforeScalingKeys() {
        val chrome = ImeChromeLayout(RuntimeEnvironment.getApplication()).apply { orientation = LinearLayout.VERTICAL }
        val row = View(chrome.context)
        val surface = View(chrome.context)
        chrome.addView(row, LinearLayout.LayoutParams(-1, 80))
        chrome.addView(surface, LinearLayout.LayoutParams(-1, 600))
        chrome.surfaceView = surface
        chrome.softwareKeyboardModeActive = true
        chrome.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.AT_MOST))
        chrome.layout(0, 0, 600, chrome.measuredHeight)
        assertEquals(400, chrome.height)
        assertEquals(0, row.top)
        assertEquals(80, row.height)
        assertEquals(320, surface.height)
        assertEquals(chrome.height, surface.bottom)
    }

    @Test
    @Config(qualifiers = "sw700dp-w700dp-h1000dp")
    fun unfoldedDisplayKeepsSuggestionsAboveTheScaledSurfaceWithinHalfHeight() {
        val context = RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(context.packageManager).setSystemFeature(
            android.content.pm.PackageManager.FEATURE_SENSOR_HINGE_ANGLE, true)
        val windowManager = org.mockito.Mockito.mock(android.view.WindowManager::class.java)
        org.mockito.Mockito.`when`(windowManager.maximumWindowMetrics).thenReturn(
            android.view.WindowMetrics(android.graphics.Rect(0, 0, 1400, 2000), android.view.WindowInsets.Builder().build()))
        // The application's base context wraps ContextImpl for Direct Boot storage routing.
        var impl: android.content.Context = context
        while (impl is android.content.ContextWrapper) impl = impl.baseContext
        org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowContextImpl>(impl)
            .setSystemService(android.content.Context.WINDOW_SERVICE, windowManager)
        val limit = 1000
        val chrome = ImeChromeLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val row = View(context)
        val surface = View(context)
        chrome.addView(row, LinearLayout.LayoutParams(-1, 80))
        chrome.addView(surface, LinearLayout.LayoutParams(-1, limit * 2))
        chrome.surfaceView = surface
        chrome.softwareKeyboardModeActive = true
        chrome.measure(View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        chrome.layout(0, 0, 700, chrome.measuredHeight)
        assertEquals(limit, chrome.height)
        assertEquals(0, row.top)
        assertEquals(80, row.height)
        assertEquals(limit - 80, surface.height)
        assertEquals(limit, surface.bottom)
    }

    @Test fun compactRowsStayAtTheBottomOfAnExpandedWindow() {
        val context = RuntimeEnvironment.getApplication()
        val chrome = ImeChromeLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val row = View(context)
        chrome.addView(row, LinearLayout.LayoutParams(-1, 80))
        chrome.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY))
        chrome.layout(0, 0, 600, 500)
        assertEquals(500, row.bottom)
        assertEquals(420, row.top)
    }
}
