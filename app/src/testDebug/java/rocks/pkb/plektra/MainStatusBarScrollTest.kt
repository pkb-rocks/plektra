package rocks.pkb.plektra

import android.Manifest
import android.animation.ValueAnimator
import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.core.view.WindowCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import rocks.pkb.plektra.ui.theme.AppAppearancePreferences
import rocks.pkb.plektra.ui.theme.AppDarkSurfaceStyle
import rocks.pkb.plektra.ui.theme.AppThemeMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w360dp-h480dp-notnight")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class MainStatusBarScrollTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<MainActivity>? = null
    private var workManagerStatic: org.mockito.MockedStatic<WorkManager>? = null
    private var previousDurationScale: Float = 1f

    @Before
    fun setup() {
        previousDurationScale = ValueAnimator.getDurationScale()
        ReflectionHelpers.callStaticMethod<Unit>(
            ValueAnimator::class.java,
            "setDurationScale",
            ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType, 0f)
        )
        SettingsManager.getPreferences(context).edit().clear().commit()
        SettingsManager.setTutorialCompleted(context)
        SettingsManager.markWhatsNewSeen(context, BuildConfig.VERSION_NAME)
        shadowOf(context.applicationContext as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val mockWorkManager = Mockito.mock(WorkManager::class.java)
        workManagerStatic = Mockito.mockStatic(WorkManager::class.java).also { mockStatic ->
            mockStatic.`when`<WorkManager> { WorkManager.getInstance(Mockito.any(Context::class.java)) }
                .thenReturn(mockWorkManager)
        }
    }

    @After
    fun tearDown() {
        scenario?.close()
        scenario = null
        workManagerStatic?.close()
        workManagerStatic = null
        ReflectionHelpers.callStaticMethod<Unit>(
            ValueAnimator::class.java,
            "setDurationScale",
            ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType, previousDurationScale)
        )
    }

    private fun launchMain() {
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        compose.waitForIdle()
    }

    @Test
    fun lightModeStatusIconsFollowScrollableDarkHeaderTopAndScrolledDown() {
        SettingsManager.setAppAppearancePreferences(
            context,
            AppAppearancePreferences(AppThemeMode.LIGHT, AppDarkSurfaceStyle.STANDARD, dynamicColor = false)
        )

        launchMain()

        // At top: dark CustomTopBar is visible behind status bar -> light status icons, dark nav icons
        val topHeaderBounds = compose.onNodeWithText("Plektra").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Expected header visible at top, bounds=$topHeaderBounds", topHeaderBounds.bottom > 0f)
        scenario!!.onActivity { activity ->
            val insets = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            assertFalse("Dark header at top requires light status bar icons", insets.isAppearanceLightStatusBars)
            assertTrue("Light Scaffold requires dark navigation bar icons", insets.isAppearanceLightNavigationBars)
        }

        // Scroll down past the dark header -> light content behind status bar -> dark status icons
        compose.onNodeWithText(context.getString(R.string.last_keyboard_event_title)).performScrollTo()
        compose.waitForIdle()

        val scrolledHeaderBounds = compose.onNodeWithText("Plektra").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Expected header scrolled out of top area, bounds=$scrolledHeaderBounds",
            scrolledHeaderBounds.bottom <= 0f
        )
        compose.onNodeWithText("Plektra").assertIsNotDisplayed()
        scenario!!.onActivity { activity ->
            val insets = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            assertTrue(
                "Scrolled light content behind status bar requires dark status bar icons",
                insets.isAppearanceLightStatusBars
            )
            assertTrue(insets.isAppearanceLightNavigationBars)
        }

        // Scroll back to top -> dark header behind status bar again -> light status icons restored
        compose.onNodeWithText("Plektra").performScrollTo()
        compose.waitForIdle()

        val restoredHeaderBounds = compose.onNodeWithText("Plektra").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Expected header visible again at top, bounds=$restoredHeaderBounds", restoredHeaderBounds.bottom > 0f)
        scenario!!.onActivity { activity ->
            val insets = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            assertFalse(
                "Scrolling back to dark header must restore light status bar icons",
                insets.isAppearanceLightStatusBars
            )
            assertTrue(insets.isAppearanceLightNavigationBars)
        }
    }

    @Test
    fun darkOledModeKeepsLightStatusIconsAtTopAndWhenScrolled() {
        SettingsManager.setAppAppearancePreferences(
            context,
            AppAppearancePreferences(AppThemeMode.DARK, AppDarkSurfaceStyle.OLED, dynamicColor = false)
        )

        launchMain()

        compose.onNodeWithText("Plektra").assertIsDisplayed()
        scenario!!.onActivity { activity ->
            val insets = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            assertFalse(insets.isAppearanceLightStatusBars)
            assertFalse(insets.isAppearanceLightNavigationBars)
        }

        compose.onNodeWithText(context.getString(R.string.last_keyboard_event_title)).performScrollTo()
        compose.waitForIdle()

        compose.onNodeWithText("Plektra").assertIsNotDisplayed()
        scenario!!.onActivity { activity ->
            val insets = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            assertFalse("Dark mode must keep light status icons when scrolled", insets.isAppearanceLightStatusBars)
            assertFalse("Dark mode must keep light nav icons when scrolled", insets.isAppearanceLightNavigationBars)
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h480dp-night")
    fun forcedLightOnDarkOsFollowsScrollableDarkHeaderTopAndScrolledDown() {
        SettingsManager.setAppAppearancePreferences(
            context,
            AppAppearancePreferences(AppThemeMode.LIGHT, AppDarkSurfaceStyle.STANDARD, dynamicColor = false)
        )

        launchMain()

        val topBounds = compose.onNodeWithText("Plektra").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(topBounds.bottom > 0f)
        scenario!!.onActivity { activity ->
            val insets = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            assertFalse(insets.isAppearanceLightStatusBars)
            assertTrue(insets.isAppearanceLightNavigationBars)
        }

        compose.onNodeWithText(context.getString(R.string.last_keyboard_event_title)).performScrollTo()
        compose.waitForIdle()

        assertTrue(compose.onNodeWithText("Plektra").fetchSemanticsNode().boundsInRoot.bottom <= 0f)
        scenario!!.onActivity { activity ->
            val insets = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            assertTrue(insets.isAppearanceLightStatusBars)
            assertTrue(insets.isAppearanceLightNavigationBars)
        }
    }
}
