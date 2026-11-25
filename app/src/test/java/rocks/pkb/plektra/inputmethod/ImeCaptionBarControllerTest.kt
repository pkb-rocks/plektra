package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.view.WindowInsets
import android.view.WindowInsetsController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rocks.pkb.plektra.SettingsManager
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImeCaptionBarControllerTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val calls = mutableListOf<String>()
    private fun window(name: String) = Proxy.newProxyInstance(
        WindowInsetsController::class.java.classLoader,
        arrayOf(WindowInsetsController::class.java)
    ) { _, method, args ->
        if (method.name == "hide" || method.name == "show") {
            assertEquals(WindowInsets.Type.captionBar(), args!![0])
            calls += "$name.${method.name}"
        }
        if (method.returnType == Int::class.javaPrimitiveType) 0 else null
    } as WindowInsetsController
    private val insets = window("a")

    @Before
    fun setUp() {
        prefs().edit().clear().commit()
    }

    @After
    fun tearDown() {
        prefs().edit().clear().commit()
    }

    @Test
    fun touchDevicesKeepTheBarUntilTheUserHidesIt() {
        DeviceSpecific.clearTestOverrides()
        assertFalse(SettingsManager.getHideImeCaptionBar(context))
        SettingsManager.setPhysicalKeyboardProfileOverride(context, "key2")
        assertFalse("a profile choice is no built-in keyboard", SettingsManager.getHideImeCaptionBar(context))
        assertFalse("the default is not persisted", prefs().contains(SettingsManager.KEY_HIDE_IME_CAPTION_BAR))
        SettingsManager.setHideImeCaptionBar(context, true)
        assertTrue(SettingsManager.getHideImeCaptionBar(context))
    }

    @Test
    fun builtInKeyboardDevicesHideTheBarUntilTheUserChoosesOtherwise() {
        DeviceSpecific.setBuildFingerprintForTests(
            brand = "unihertz", manufacturer = "unihertz", model = "Titan 2 Elite",
            device = "titan2", product = "titan2", board = "G72BoardV1"
        )
        try {
            assertTrue(SettingsManager.getHideImeCaptionBar(context))
            assertFalse("the default is not persisted", prefs().contains(SettingsManager.KEY_HIDE_IME_CAPTION_BAR))
            SettingsManager.setHideImeCaptionBar(context, false)
            assertFalse("a stored opt-out wins", SettingsManager.getHideImeCaptionBar(context))
            SettingsManager.setHideImeCaptionBar(context, true)
            assertTrue(SettingsManager.getHideImeCaptionBar(context))
        } finally {
            DeviceSpecific.clearTestOverrides()
        }
    }

    @Test
    fun showsTheBarAgainOnlyAfterHidingItItself() {
        val controller = ImeCaptionBarController()
        controller.update(insets, hide = false, captionBarVisible = true)
        assertEquals(emptyList<String>(), calls)

        assertFalse(controller.update(insets, hide = true, captionBarVisible = true))
        // Every window show applies the choice again; the bar is now hidden by Plektra.
        controller.update(insets, hide = true, captionBarVisible = false)
        assertTrue(controller.hiddenByPlektra)
        // A profile change or opt-out restores the bar once.
        controller.update(insets, hide = false, captionBarVisible = false)
        controller.update(insets, hide = false, captionBarVisible = true)
        assertEquals(listOf("a.hide", "a.hide", "a.show"), calls)
        assertFalse(controller.hiddenByPlektra)
    }

    @Test
    fun aBarHiddenBeforeStaysWithTheSystemOrOem() {
        val controller = ImeCaptionBarController()
        assertFalse(controller.update(insets, hide = true, captionBarVisible = false))
        assertFalse(controller.hiddenByPlektra)
        controller.update(insets, hide = false, captionBarVisible = false)
        assertEquals("neither taken over nor shown on opt-out", emptyList<String>(), calls)

        // Shown again by its owner: from then on the hide is Plektra's.
        controller.update(insets, hide = true, captionBarVisible = true)
        controller.update(insets, hide = false, captionBarVisible = false)
        assertEquals(listOf("a.hide", "a.show"), calls)
    }

    @Test
    fun ownershipStaysWithTheWindowPlektraHidItIn() {
        val controller = ImeCaptionBarController()
        val other = window("b")
        controller.update(insets, hide = true, captionBarVisible = true)
        // A new IME window: its bar was never Plektra's.
        controller.update(other, hide = false, captionBarVisible = false)
        assertFalse(controller.hiddenByPlektra)
        // Back on the first window the old hide is no longer claimed either.
        controller.update(insets, hide = false, captionBarVisible = false)
        assertEquals(listOf("a.hide"), calls)

        controller.update(other, hide = true, captionBarVisible = true)
        controller.update(other, hide = false, captionBarVisible = false)
        assertEquals(listOf("a.hide", "b.hide", "b.show"), calls)
    }

    @Test
    fun anUnknownBarStateWaitsInsteadOfClaimingTheHide() {
        val controller = ImeCaptionBarController()
        assertTrue("try again once attached", controller.update(insets, hide = true, captionBarVisible = null))
        assertFalse(controller.update(insets, hide = false, captionBarVisible = null))
        assertTrue(calls.isEmpty())
        assertFalse(controller.hiddenByPlektra)

        assertFalse(controller.update(insets, hide = true, captionBarVisible = true))
        // Owned already: unknown state does not stop re-applying or restoring.
        controller.update(insets, hide = true, captionBarVisible = null)
        controller.update(insets, hide = false, captionBarVisible = null)
        assertEquals(listOf("a.hide", "a.hide", "a.show"), calls)
    }

    @Test
    fun leavesTheBarAloneBeforeApi34OrWithoutAWindow() {
        ImeCaptionBarController(sdkInt = 33).update(insets, hide = true, captionBarVisible = true)
        ImeCaptionBarController().update(null, hide = true, captionBarVisible = true)
        assertTrue(calls.isEmpty())
    }

    private fun prefs() = context.getSharedPreferences("plektra_prefs", Context.MODE_PRIVATE)
}
