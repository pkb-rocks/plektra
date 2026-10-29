package rocks.pkb.plektra

import android.net.Uri
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppAppearanceTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setup() {
        SettingsManager.getPreferences(context).edit().clear().commit()
    }

    @Test
    fun systemPaletteRemainsUnmodifiedUntilCustomColoursAreEnabled() {
        val base = lightColorScheme()
        assertFalse(AppAppearance.read(context).enabled)
        assertSame(base, AppAppearance.palette(base, false, AppAppearanceColors()))
    }

    @Test
    fun storedColoursAreOpaqueAndResetPreservesKeyboardThemes() {
        val prefs = SettingsManager.getPreferences(context)
        prefs.edit().putString("keyboard_theme_custom", "keep").commit()
        AppAppearance.save(context, AppAppearanceColors(true, 0x00123456, 0x00567890))
        val value = AppAppearance.read(context)
        assertTrue(value.enabled)
        assertEquals(0xff123456.toInt(), value.main)
        assertEquals(0xff567890.toInt(), value.selection)

        AppAppearance.reset(context)
        assertFalse(AppAppearance.read(context).enabled)
        assertEquals("keep", prefs.getString("keyboard_theme_custom", null))
    }

    @Test
    fun selectionColourCanChangeWithoutChangingTheMainColour() {
        val first = AppAppearanceColors(true, 0xff176b9b.toInt(), 0xff83cce5.toInt())
        val a = AppAppearance.palette(lightColorScheme(), false, first)
        val b = AppAppearance.palette(lightColorScheme(), false, first.copy(selection = 0xffd7a348.toInt()))
        assertEquals(a.primary, b.primary)
        assertEquals(a.surface, b.surface)
        assertNotEquals(a.secondaryContainer, b.secondaryContainer)
    }

    @Test
    fun extremeAndSaturatedColoursKeepReadableTextInBothSystemModes() {
        val colours = listOf(0xff000000, 0xffffffff, 0xffffff00, 0xff0000ff, 0xffff0000, 0xff00ff00)
        for (dark in listOf(false, true)) {
            for (main in colours) {
                for (selection in colours) {
                    val p = AppAppearance.palette(
                        if (dark) darkColorScheme() else lightColorScheme(), dark,
                        AppAppearanceColors(true, main.toInt(), selection.toInt())
                    )
                    val textPairs = listOf(
                        p.onPrimary to p.primary, p.onPrimaryContainer to p.primaryContainer,
                        p.onSecondary to p.secondary, p.onSecondaryContainer to p.secondaryContainer,
                        p.onTertiary to p.tertiary, p.onTertiaryContainer to p.tertiaryContainer,
                        p.onBackground to p.background, p.onSurface to p.surface,
                        p.onSurfaceVariant to p.surfaceVariant,
                        p.onSurface to p.surfaceDim, p.onSurface to p.surfaceBright,
                        p.onSurface to p.surfaceContainerLowest, p.onSurface to p.surfaceContainerLow,
                        p.onSurface to p.surfaceContainer, p.onSurface to p.surfaceContainerHigh,
                        p.onSurface to p.surfaceContainerHighest,
                        p.primary to p.surface, p.secondary to p.surface
                    )
                    textPairs.forEachIndexed { index, (ink, background) ->
                        assertTrue("dark=$dark, main=$main, selection=$selection, pair=$index",
                            ColorUtils.calculateContrast(ink.toArgb(), background.toArgb()) >= 4.5)
                    }
                }
            }
        }
    }

    @Test
    fun savedPrototypeAppearanceLinksStillResolve() {
        val id = "advanced.appearance"
        assertEquals(id, SettingLinkRegistry.parseSettingLinkUri(Uri.parse("https://pkb.rocks/settings/$id")))
        assertEquals(SettingsDestination.Advanced, SettingLinkRegistry.byId(id)?.route?.destination)
    }

    @Test
    fun malformedImportedTypesFallBackWithoutCrashingTheApplicationTheme() {
        SettingsManager.getPreferences(context).edit()
            .putString(AppAppearance.ENABLED, "bad")
            .putString(AppAppearance.MAIN, "bad")
            .putString(AppAppearance.SELECTION, "bad")
            .commit()
        assertEquals(AppAppearanceColors(), AppAppearance.read(context))
    }
}
