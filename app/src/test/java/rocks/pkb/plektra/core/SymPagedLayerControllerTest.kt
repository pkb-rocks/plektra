package rocks.pkb.plektra.core

import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rocks.pkb.plektra.EmojiPagesSettings
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.data.emoji.EmojiRendering
import rocks.pkb.plektra.data.emoji.EmojiRepository
import rocks.pkb.plektra.data.symbols.Kaomoji
import rocks.pkb.plektra.data.symbols.SymbolSearch
import rocks.pkb.plektra.inputmethod.AlternateCharacterManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SymPagedLayerControllerTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val commits = mutableListOf<String>()
    private val connection = Proxy.newProxyInstance(
        InputConnection::class.java.classLoader, arrayOf(InputConnection::class.java)
    ) { _, method, args ->
        if (method.name == "commitText") commits.add(args!![0].toString())
        when (method.returnType) { java.lang.Boolean.TYPE -> true; java.lang.Integer.TYPE -> 0; else -> null }
    } as InputConnection
    private lateinit var controller: SymLayoutController

    @Before
    fun setUp() {
        SettingsManager.getPreferences(context).edit().clear().commit()
        context.getSharedPreferences("recent_emojis_prefs", 0).edit().clear().commit()
        SettingsManager.setSymAutoClose(context, false)
        EmojiRendering.setStyle(context, EmojiRendering.Style.SYSTEM)
        val prefs = SettingsManager.getPreferences(context)
        controller = SymLayoutController(context, prefs, AlternateCharacterManager(context.assets, prefs, context))
    }

    private fun down(keyCode: Int, repeat: Int = 0, shift: Boolean = false) = controller.handleKeyWhenActive(
        keyCode, KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, repeat), connection,
        ctrlLatchActive = false, altLatchActive = false, updateStatusBar = {}, shiftPressed = shift
    )

    @Test
    fun symbolsPageTurnsWithQAndPTypesSymbolsAndReachesKaomoji() {
        controller.openSymbolsPage()
        val home = controller.currentSymMappings()!!
        assertEquals(SymPagedLayer.PREVIOUS_LABEL, home[KeyEvent.KEYCODE_Q])
        assertEquals(SymPagedLayer.NEXT_LABEL, home[KeyEvent.KEYCODE_P])

        down(KeyEvent.KEYCODE_P)
        assertEquals("@", controller.currentSymMappings()!![KeyEvent.KEYCODE_W])
        assertEquals(SymPagedLayer.SEARCH_LABEL, controller.currentSymMappings()!![KeyEvent.KEYCODE_A])
        down(KeyEvent.KEYCODE_E)
        assertEquals(listOf("#"), commits)
        assertEquals("#", SymbolSearch.recents.get(context).first())

        var searched = 0
        controller.onPagedSearchRequested = { searched++ }
        down(KeyEvent.KEYCODE_A)
        assertEquals(1, searched)

        // Shift+P: the next section; past the symbols come the kaomoji
        down(KeyEvent.KEYCODE_P, shift = true)
        assertEquals(Kaomoji.all(context).first(), controller.currentSymMappings()!![KeyEvent.KEYCODE_W])
        assertEquals(SymPagedLayer.Kind.KAOMOJI, SymLayoutController.shownPagedKind)

        EmojiPagesSettings.setKaomojiPlacement(context, EmojiPagesSettings.KaomojiPlacement.OFF)
        controller.closeSymPage()
        controller.openSymbolsPage()
        // Round from the own page back to the last symbol page: recents, symbols, no kaomoji
        down(KeyEvent.KEYCODE_Q)
        assertEquals("∪", controller.currentSymMappings()!![KeyEvent.KEYCODE_M])
        assertEquals(SymPagedLayer.Kind.SYMBOLS, SymLayoutController.shownPagedKind)
    }

    @Test
    fun turnedOffPagesKeepTheMappingOnQAndP() {
        EmojiPagesSettings.setSymbolPagesEnabled(context, false)
        controller.openSymbolsPage()
        val mappings = controller.currentSymMappings()!!
        assertTrue(mappings[KeyEvent.KEYCODE_Q] != SymPagedLayer.PREVIOUS_LABEL)
    }

    @Test
    fun holdingAnEmojiShowsItsSkinTonesOnePerKey() {
        val people = runBlocking { EmojiRepository.getEmojiCategories(context) }
            .firstOrNull { it.id == "PEOPLE_AND_BODY" }
        assumeTrue(people != null && people.emojis.first().variants.isNotEmpty())
        val first = people!!.emojis.first()
        val smileysPresent = runBlocking { EmojiRepository.getEmojiCategories(context) }.first().id == "SMILEYS_AND_EMOTION"

        controller.openEmojiPage()
        down(KeyEvent.KEYCODE_P, shift = true)
        if (smileysPresent) down(KeyEvent.KEYCODE_P, shift = true)
        assertEquals(first.base, controller.currentSymMappings()!![KeyEvent.KEYCODE_W])

        // A short press types the emoji when the key comes up
        down(KeyEvent.KEYCODE_W)
        assertTrue(commits.isEmpty())
        assertTrue(controller.handlePagedKeyUp(KeyEvent.KEYCODE_W, connection, {}))
        assertEquals(listOf(first.base), commits)

        // Held: its variants, one per key; the release is spent, then a tone is typed
        down(KeyEvent.KEYCODE_W)
        down(KeyEvent.KEYCODE_W, repeat = 1)
        val variants = controller.currentSymMappings()!!
        assertEquals(first.base, variants[KeyEvent.KEYCODE_W])
        assertEquals(first.variants[0], variants[KeyEvent.KEYCODE_E])
        assertTrue(controller.handlePagedKeyUp(KeyEvent.KEYCODE_W, connection, {}))
        down(KeyEvent.KEYCODE_E)
        assertEquals(listOf(first.base, first.variants[0]), commits)
        assertEquals(first.base, controller.currentSymMappings()!![KeyEvent.KEYCODE_W])
    }
}
