package rocks.pkb.plektra.core

import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.data.emoji.EmojiRendering
import rocks.pkb.plektra.data.emoji.EmojiRepository
import rocks.pkb.plektra.data.emoji.RecentEmojiManager
import rocks.pkb.plektra.data.emoji.TestEmojiFont
import rocks.pkb.plektra.inputmethod.AlternateCharacterManager

/**
 * The emoji pages with the bundled emoji font, as the hardware keyboard drives them: key
 * rollover, the variants of mapped emoji, variants beyond one page and the offered recents.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SymPagedLayerVariantsTest {
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
        TestEmojiFont.install(context)
        val prefs = SettingsManager.getPreferences(context)
        controller = SymLayoutController(context, prefs, AlternateCharacterManager(context.assets, prefs, context))
    }

    @After
    fun restoreBundledFont() {
        EmojiRendering.setStyle(context, EmojiRendering.Style.NOTO)
    }

    private fun down(keyCode: Int, repeat: Int = 0, shift: Boolean = false) = controller.handleKeyWhenActive(
        keyCode, KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, repeat), connection,
        ctrlLatchActive = false, altLatchActive = false, updateStatusBar = {}, shiftPressed = shift
    )

    private fun up(keyCode: Int) = controller.handlePagedKeyUp(keyCode, connection, {})

    private fun mappings() = controller.currentSymMappings()!!

    /** The first page of People & Body (no recents: smileys, then people). */
    private fun openPeoplePage() {
        controller.openEmojiPage()
        down(KeyEvent.KEYCODE_P, shift = true)
        down(KeyEvent.KEYCODE_P, shift = true)
        assertEquals("👋", mappings()[KeyEvent.KEYCODE_W])
    }

    @Test
    fun rolloverTypesEveryPressedEmojiInTheOrderOfThePresses() {
        openPeoplePage()
        val keys = mappings()
        down(KeyEvent.KEYCODE_W)
        down(KeyEvent.KEYCODE_E)
        up(KeyEvent.KEYCODE_W)
        up(KeyEvent.KEYCODE_E)
        assertEquals(listOf(keys[KeyEvent.KEYCODE_W], keys[KeyEvent.KEYCODE_E]), commits)

        // Released in the other order: still in the order of the presses
        commits.clear()
        down(KeyEvent.KEYCODE_W)
        down(KeyEvent.KEYCODE_E)
        up(KeyEvent.KEYCODE_E)
        up(KeyEvent.KEYCODE_W)
        assertEquals(listOf(keys[KeyEvent.KEYCODE_W], keys[KeyEvent.KEYCODE_E]), commits)

        // A page turn while an emoji is down types it first; its release then types nothing more
        commits.clear()
        down(KeyEvent.KEYCODE_W)
        down(KeyEvent.KEYCODE_P)
        up(KeyEvent.KEYCODE_W)
        assertEquals(listOf(keys[KeyEvent.KEYCODE_W]), commits)

        // Another key's repeat doesn't cut a hold short: the held emoji still opens its tones
        commits.clear()
        controller.closeSymPage()
        RecentEmojiManager.clearRecentEmojis(context)
        openPeoplePage()
        down(KeyEvent.KEYCODE_W)
        down(KeyEvent.KEYCODE_E, repeat = 1)
        down(KeyEvent.KEYCODE_W, repeat = 1)
        assertTrue(commits.isEmpty())
        assertEquals("👋🏻", mappings()[KeyEvent.KEYCODE_E])
    }

    @Test
    fun withAutoCloseTheSecondPressedKeyTypesAsWithoutSym() {
        openPeoplePage()
        SettingsManager.setSymAutoClose(context, true)
        val keys = mappings()
        down(KeyEvent.KEYCODE_W)
        // E going down types the waiting 👋, which closes SYM: E is no SYM key any more
        assertEquals(SymLayoutController.SymKeyResult.NOT_HANDLED, down(KeyEvent.KEYCODE_E))
        assertEquals(listOf(keys[KeyEvent.KEYCODE_W]), commits)
        assertFalse(controller.isSymActive())
        // Neither release types anything more
        assertFalse(up(KeyEvent.KEYCODE_W))
        assertFalse(up(KeyEvent.KEYCODE_E))
        assertEquals(listOf(keys[KeyEvent.KEYCODE_W]), commits)
    }

    @Test
    fun aSkinToneTappedOnScreenClosesSymOnlyByTheTouchRule() {
        fun tapTone(autoClose: Boolean, onTouch: Boolean): Boolean {
            commits.clear()
            controller.closeSymPage()
            RecentEmojiManager.clearRecentEmojis(context)
            SettingsManager.setSymAutoClose(context, false)
            openPeoplePage()
            SettingsManager.setSymAutoClose(context, autoClose)
            SettingsManager.setSymAutoCloseOnTouch(context, onTouch)
            assertTrue(controller.handlePagedTouchLongPress(KeyEvent.KEYCODE_W))
            var committed = 0
            assertTrue(controller.handlePagedTouchTap(KeyEvent.KEYCODE_E, connection, {}, onSymbolCommitted = { committed++ }))
            assertEquals(listOf("👋🏻"), commits)
            assertEquals(1, committed)
            return controller.isSymActive()
        }
        assertTrue("touch auto-close off keeps the page", tapTone(autoClose = true, onTouch = false))
        assertFalse(tapTone(autoClose = true, onTouch = true))
        assertTrue(tapTone(autoClose = false, onTouch = true))

        // A tone chosen with the hardware keys follows the general option alone
        commits.clear()
        controller.closeSymPage()
        RecentEmojiManager.clearRecentEmojis(context)
        SettingsManager.setSymAutoClose(context, false)
        openPeoplePage()
        SettingsManager.setSymAutoClose(context, true)
        SettingsManager.setSymAutoCloseOnTouch(context, false)
        down(KeyEvent.KEYCODE_W)
        down(KeyEvent.KEYCODE_W, repeat = 1)
        up(KeyEvent.KEYCODE_W)
        down(KeyEvent.KEYCODE_E)
        assertEquals(listOf("👋🏻"), commits)
        assertFalse(controller.isSymActive())
    }

    @Test
    fun aMappedEmojiWithSkinTonesShowsThemWhenHeldAndOffersItsCustomization() {
        controller.openEmojiPage()
        val home = mappings()
        val key = home.entries.first { (keyCode, text) ->
            keyCode in SymPagedLayer.SLOTS && EmojiRepository.findEntry(text)?.variants?.isNotEmpty() == true
        }.key
        val emoji = home.getValue(key)

        // A short press types the mapped emoji once, when the key comes up
        down(key)
        assertTrue(commits.isEmpty())
        assertTrue(up(key))
        assertEquals(listOf(emoji), commits)

        // Held: its tones instead of repeating it, with the key's customization on A
        commits.clear()
        down(key)
        down(key, repeat = 1)
        down(key, repeat = 2)
        assertTrue("hold must not type repeats", commits.isEmpty())
        val variants = mappings()
        assertEquals(emoji, variants[KeyEvent.KEYCODE_W])
        assertEquals(SymPagedLayer.EDIT_LABEL, variants[KeyEvent.KEYCODE_A])
        assertTrue(up(key))
        assertTrue(commits.isEmpty())

        var customized: Pair<Int, Int>? = null
        controller.onKeyCustomizationRequested = { page, keyCode -> customized = page to keyCode }
        down(KeyEvent.KEYCODE_A)
        assertEquals(1 to key, customized)
        assertEquals(emoji, mappings()[key])

        // Held on screen: the tones too, rather than the customization
        assertTrue(controller.handlePagedTouchLongPress(key))
        assertEquals(SymPagedLayer.EDIT_LABEL, mappings()[KeyEvent.KEYCODE_A])
        down(KeyEvent.KEYCODE_E)
        assertEquals(listOf(EmojiRepository.findEntry(emoji)!!.variants.first()), commits)
    }

    @Test
    fun aMappedEmojiWithoutTonesKeepsTypingAndTheHoldKeepsTheCustomization() {
        controller.openEmojiPage()
        val (key, text) = mappings().entries.first { (keyCode, text) ->
            keyCode in SymPagedLayer.SLOTS && EmojiRepository.findEntry(text)?.variants?.isEmpty() != false
        }
        down(key)
        assertEquals(listOf(text), commits)
        assertFalse(controller.handlePagedTouchLongPress(key))
    }

    @Test
    fun everyVariantOfAnEmojiWithMoreThanOnePageOfThemIsReachable() {
        val handshake = EmojiRepository.findEntry("🤝")!!
        val options = listOf(handshake.base) + handshake.variants
        assertEquals(26, options.size)
        controller.openEmojiPage()
        var key: Int? = null
        repeat(250) {
            if (key == null) {
                key = mappings().entries.firstOrNull { it.value == "🤝" }?.key
                if (key == null) down(KeyEvent.KEYCODE_P)
            }
        }
        assertNotNull(key)
        assertTrue(controller.handlePagedTouchLongPress(key!!))

        val first = mappings()
        assertEquals(SymPagedLayer.NEXT_LABEL, first[KeyEvent.KEYCODE_P])
        val shown = SymPagedLayer.SLOTS.mapNotNull { first[it] }.toMutableList()
        down(KeyEvent.KEYCODE_P)
        val second = mappings()
        shown += SymPagedLayer.SLOTS.mapNotNull { second[it] }
        assertEquals(options, shown)

        // The last mixed tone types; Q goes back a page of tones, then back to the emoji
        down(SymPagedLayer.SLOTS[options.size - SymPagedLayer.PER_PAGE - 1])
        assertEquals(listOf(options.last()), commits)
        assertTrue(controller.handlePagedTouchLongPress(key!!))
        down(KeyEvent.KEYCODE_P)
        down(KeyEvent.KEYCODE_Q)
        assertEquals(first, mappings())
        down(KeyEvent.KEYCODE_Q)
        assertEquals("🤝", mappings()[key!!])
    }

    @Test
    fun recentsShowOnlyWhatTheEmojiFontInUseOffers() = runBlocking {
        val newHand = "🫹" // 🫹, Unicode 18: the bundled font draws it, Robolectric's system font doesn't
        RecentEmojiManager.addRecentEmoji(context, "🧡") // offered with either font
        RecentEmojiManager.addRecentEmoji(context, newHand)
        controller.openEmojiPage()
        down(KeyEvent.KEYCODE_P)
        assertEquals(newHand, mappings()[KeyEvent.KEYCODE_W])

        // Switched to the system font while the emoji pages stay open
        EmojiRendering.setStyle(context, EmojiRendering.Style.SYSTEM)
        val offered = EmojiRepository.getEmojiCategories(context).flatMap { it.emojis }.map { it.base }
        assertFalse("test system font lacks the new hand", newHand in offered)
        assertEquals(listOf("🧡"), RecentEmojiManager.getRecentEmojiCategory(context)!!.emojis.map { it.base })
        assertEquals(listOf("🧡"), RecentEmojiManager.getOfferedRecentEmojis(context))
        val shown = (0 until 3).flatMap {
            down(KeyEvent.KEYCODE_P)
            mappings().values
        }
        assertFalse("the open pages follow the font", newHand in shown)
    }

    @Test
    fun newHandsCarryTheirOwnSkinTones() {
        assertEquals("🫹", EmojiRepository.findEntry("🫹🏻")!!.base)
        assertEquals(5, EmojiRepository.findEntry("🫺")!!.variants.size)
        assertFalse(EmojiRepository.findEntry("💑")!!.variants.any { it.startsWith("🫹") })
    }
}
