package rocks.pkb.plektra

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import rocks.pkb.plektra.ui.theme.AppSettingsStyle
import rocks.pkb.plektra.ui.theme.PlektraTheme

/**
 * Synthetic renders of the full-screen settings header around display cutouts (drawn
 * black), compact and wide, both layout styles, with and without the open search.
 * Runs only when PLEKTRA_FULLSCREEN_RENDER names an output directory.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsFullscreenRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Context = ApplicationProvider.getApplicationContext()

    private class Shot(val name: String, val cutouts: List<Rect>, val wide: Boolean = false, val tabs: Boolean = false)

    private var current by mutableStateOf<Pair<Shot, AppSettingsStyle>?>(null)

    @Composable
    private fun Page(tabs: Boolean, title: String, back: Boolean = true) {
        Column(Modifier.fillMaxSize()) {
            PlektraSettingsHeader(
                title = title,
                onBack = if (back) ({}) else null,
                bottom = if (tabs) ({
                    TabRow(selectedTabIndex = 0) {
                        Tab(selected = true, onClick = {}, text = { Text("Software") })
                        Tab(selected = false, onClick = {}, text = { Text("Hardware") })
                    }
                }) else null,
                actions = { IconButton(onClick = {}) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") } }
            )
            SettingsSectionDivider(label = "Behaviour")
            SettingsToggleRow(title = "Enable kill switch", description = "Example row", checked = true, onCheckedChange = {})
            SettingsToggleRow(title = "Confirm before closing", checked = false, onCheckedChange = {})
        }
    }

    private fun render(out: File, shots: List<Shot>, search: Boolean) {
        compose.setContent {
            val (shot, style) = current ?: return@setContent
            key(shot.name, style) {
                PlektraTheme(darkTheme = true, dynamicColor = false, settingsStyle = style, settingsWindow = true) {
                    CompositionLocalProvider(LocalDisplayCutoutRects provides shot.cutouts) {
                        Surface(color = MaterialTheme.colorScheme.background) {
                            Box(Modifier.fillMaxSize()) {
                                if (shot.wide) {
                                    Row(Modifier.fillMaxSize()) {
                                        Box(Modifier.weight(1f).fillMaxHeight()) { Page(false, "Settings", back = true) }
                                        CompositionLocalProvider(LocalSettingsDetailRoot provides true) {
                                            Box(Modifier.weight(1f).fillMaxHeight()) { Page(shot.tabs, "Kill Switch") }
                                        }
                                    }
                                } else {
                                    Page(shot.tabs, "Kill Switch")
                                }
                                val density = LocalDensity.current
                                shot.cutouts.forEach { r ->
                                    Box(
                                        Modifier
                                            .offset { IntOffset(r.left, r.top) }
                                            .size(with(density) { r.width().toDp() }, with(density) { r.height().toDp() })
                                            .background(Color.Black)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        for (style in AppSettingsStyle.entries) for (shot in shots) {
            SettingsManager.getPreferences(context).edit().clear().commit()
            SettingsManager.setSettingsFullscreenEnabled(context, true)
            compose.runOnUiThread { current = shot to style }
            compose.waitForIdle()
            var suffix = ""
            if (search && !shot.wide) {
                compose.onNodeWithContentDescription(context.getString(R.string.settings_search_hint)).performClick()
                compose.waitForIdle()
                compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).performTextInput("theme")
                compose.waitForIdle()
                suffix = "-search"
            }
            compose.runOnUiThread {
                val root = compose.activity.window.decorView
                val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(android.graphics.Canvas(bitmap))
                File(out, "${shot.name}$suffix-${style.storageValue}.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        }
    }

    /** Runs without rendering: the synthetic page composes and shows the header search. */
    @Test
    @Config(sdk = [33], qualifiers = "w411dp-h360dp-xhdpi")
    fun theSyntheticPageComposesInFullScreen() {
        SettingsManager.getPreferences(context).edit().clear().commit()
        SettingsManager.setSettingsFullscreenEnabled(context, true)
        compose.setContent { PlektraTheme(settingsWindow = true) { Page(tabs = true, title = "Kill Switch") } }
        compose.onNodeWithContentDescription(context.getString(R.string.settings_search_hint)).assertExists()
        compose.onNodeWithContentDescription("Refresh").assertExists()
    }

    private fun outDir(): File {
        val dir = System.getenv("PLEKTRA_FULLSCREEN_RENDER")
        assumeTrue(!dir.isNullOrBlank())
        return File(dir!!).apply { mkdirs() }
    }

    private fun px(dp: Int) = dp * 2

    @Test
    @Config(sdk = [33], qualifiers = "w411dp-h360dp-xhdpi")
    fun compact() {
        val out = outDir()
        val shots = listOf(
            Shot("compact-1-no-cutout", emptyList()),
            Shot("compact-2-centre-hole", listOf(Rect(px(190), px(10), px(222), px(42)))),
            Shot("compact-3-corner-start", listOf(Rect(0, 0, px(64), px(40)))),
            Shot("compact-4-corner-end-tabs", listOf(Rect(px(360), 0, px(411), px(44))), tabs = true)
        )
        render(out, shots, search = false)
    }

    @Test
    @Config(sdk = [33], qualifiers = "w411dp-h600dp-xhdpi")
    fun compactSearch() {
        render(outDir(), listOf(Shot("compact-5-centre-hole", listOf(Rect(px(190), px(10), px(222), px(42))))), search = true)
    }

    @Test
    @Config(sdk = [33], qualifiers = "w840dp-h360dp-xhdpi")
    fun wide() {
        val out = outDir()
        val shots = listOf(
            Shot("wide-1-fold-centre-hole", listOf(Rect(px(600), px(10), px(632), px(42))), wide = true, tabs = true),
            Shot("wide-2-landscape-corner-end", listOf(Rect(px(796), 0, px(840), px(48))), wide = true),
            Shot("wide-3-landscape-corner-start", listOf(Rect(0, 0, px(44), px(48))), wide = true)
        )
        render(out, shots, search = false)
    }
}
