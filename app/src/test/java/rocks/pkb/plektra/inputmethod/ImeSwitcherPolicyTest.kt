package rocks.pkb.plektra.inputmethod

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeSwitcherPolicyTest {
    @Test fun byDefaultTheSwitcherStaysInTheMenu() {
        assertFalse(ImeSwitcherPolicy.showOnSurfaces(androidRequested = false, preferInBar = false, surfacesBlocked = false))
    }

    @Test fun androidsRequestOverridesTheMenuPreference() {
        assertTrue(ImeSwitcherPolicy.showOnSurfaces(androidRequested = true, preferInBar = false, surfacesBlocked = false))
    }

    @Test fun theUserMayKeepItInTheBarWithoutARequest() {
        assertTrue(ImeSwitcherPolicy.showOnSurfaces(androidRequested = false, preferInBar = true, surfacesBlocked = false))
    }

    @Test fun blockedSurfacesShowNoSwitcherAtAll() {
        for (requested in listOf(true, false)) for (inBar in listOf(true, false)) {
            assertFalse(ImeSwitcherPolicy.showOnSurfaces(requested, inBar, surfacesBlocked = true))
        }
    }
}
