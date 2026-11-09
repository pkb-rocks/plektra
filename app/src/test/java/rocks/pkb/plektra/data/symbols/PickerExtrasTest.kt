package rocks.pkb.plektra.data.symbols

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rocks.pkb.plektra.EmojiPagesSettings
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.data.emoji.EmojiRepository

/** What the keyboard's picker and the SYM customization's pickers offer beside the emoji. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PickerExtrasTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun kaomojiComeAsACategoryAndAsSearchResultsWhenTurnedOn() {
        SettingsManager.getPreferences(context).edit().clear().commit()
        Kaomoji.recents.add(context, "(ツ)")
        val extras = PickerExtras.load(context)
        val kaomoji = extras.categories.first { it.id == EmojiRepository.KAOMOJI_CATEGORY_ID }.emojis.map { it.base }
        assertEquals("recent ones first", "(ツ)", kaomoji.first())
        assertTrue(Kaomoji.all(context).all { it in kaomoji })
        val tableFlip = extras.search(context, "table flip")
        assertTrue(tableFlip.isNotEmpty() && tableFlip.all { it.categoryId == EmojiRepository.KAOMOJI_CATEGORY_ID })

        EmojiPagesSettings.setPickerSymbolsAndKaomoji(context, false)
        assertTrue(PickerExtras.load(context).categories.isEmpty())
        assertTrue(PickerExtras.load(context).search(context, "table flip").isEmpty())
    }
}
