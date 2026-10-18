package rocks.pkb.plektra.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTrackpadTerminalAppsTest {

    @Test
    fun defaultListHoldsTheTerminalAndSshApps() {
        assertEquals(
            setOf(
                "com.termux",
                "app.getmoshi.android",
                "com.stably.orca.mobile",
                "com.android.virtualization.terminal",
                "in.sunilpaulmathew.ashell",
                "in.hridayan.ashell",
                "org.connectbot",
                "com.sonelli.juicessh",
                "com.server.auditor.ssh.client",
                "com.termoneplus",
                "tech.ula",
            ),
            ScreenTrackpadTerminalApps.DEFAULT_PACKAGES
        )
        // Facebook Messenger's package is easy to confuse with Orca's.
        assertFalse("com.facebook.orca" in ScreenTrackpadTerminalApps.DEFAULT_PACKAGES)
    }

    @Test
    fun membershipIsAnExactPackageMatch() {
        val list = setOf("com.termux", "org.connectbot")

        assertTrue(ScreenTrackpadTerminalApps.isListed(list, "com.termux"))
        assertFalse(ScreenTrackpadTerminalApps.isListed(list, "com.termux.api"))
        assertFalse(ScreenTrackpadTerminalApps.isListed(list, "com.termu"))
        assertFalse(ScreenTrackpadTerminalApps.isListed(list, "COM.TERMUX"))
        assertFalse(ScreenTrackpadTerminalApps.isListed(list, null))
        assertFalse(ScreenTrackpadTerminalApps.isListed(list, ""))
        assertFalse(ScreenTrackpadTerminalApps.isListed(emptySet(), "com.termux"))
    }

    @Test
    fun listedAppWithoutAnEditableFieldOpensOnlyWithAnInputConnection() {
        assertTrue(ScreenTrackpadTerminalApps.mayOpenOverlay(editableFieldFocused = false, hasInputConnection = true, appListed = true))
        assertFalse(ScreenTrackpadTerminalApps.mayOpenOverlay(editableFieldFocused = false, hasInputConnection = false, appListed = true))
    }

    @Test
    fun nonListedAppWithoutAnEditableFieldNeverOpens() {
        assertFalse(ScreenTrackpadTerminalApps.mayOpenOverlay(editableFieldFocused = false, hasInputConnection = true, appListed = false))
        assertFalse(ScreenTrackpadTerminalApps.mayOpenOverlay(editableFieldFocused = false, hasInputConnection = false, appListed = false))
    }

    @Test
    fun editableFieldBehaviourIsUnchangedWhetherOrNotTheAppIsListed() {
        assertTrue(ScreenTrackpadTerminalApps.mayOpenOverlay(editableFieldFocused = true, hasInputConnection = true, appListed = false))
        assertTrue(ScreenTrackpadTerminalApps.mayOpenOverlay(editableFieldFocused = true, hasInputConnection = true, appListed = true))
    }

    @Test
    fun onlyAStartedOwnConnectionCountsAsAnInputConnection() {
        val own = Any()
        val binding = Any()

        assertTrue(ScreenTrackpadTerminalApps.hasStartedInputConnection(true, own, binding))
        // The binding's fallback connection is what getCurrentInputConnection() returns when the
        // focused view gave none.
        assertFalse(ScreenTrackpadTerminalApps.hasStartedInputConnection(true, binding, binding))
        assertFalse(ScreenTrackpadTerminalApps.hasStartedInputConnection(true, null, binding))
        // After onFinishInput or onUnbindInput the leftover connection is stale.
        assertFalse(ScreenTrackpadTerminalApps.hasStartedInputConnection(false, own, binding))
        assertTrue(ScreenTrackpadTerminalApps.hasStartedInputConnection(true, own, null))
    }

    @Test
    fun sanitizeDropsInvalidNamesAndCapsTheCount() {
        val valid = setOf("com.termux", "org.connectbot", "a.b", "tech.ula", "com.example.app_2")
        val invalid = setOf("", " ", "termux", "com.", ".com", "com..x", "1com.x", "com.x y", "com.x\n", "a".repeat(256))

        assertEquals(valid, ScreenTrackpadTerminalApps.sanitize(valid + invalid))

        val many = (0 until ScreenTrackpadTerminalApps.MAX_PACKAGES * 3).map { "com.example.app$it" }.toSet()
        assertEquals(ScreenTrackpadTerminalApps.MAX_PACKAGES, ScreenTrackpadTerminalApps.sanitize(many).size)
    }
}
