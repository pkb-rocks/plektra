package rocks.pkb.plektra

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ClipboardSettingLinkTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun clipboardSettingsHaveTheirOwnPageAndOldLinksStillArrive() {
        listOf(
            SettingLinkIds.MAIN_CLIPBOARD,
            SettingLinkIds.CLIPBOARD_HISTORY_ENABLED,
            SettingLinkIds.CLIPBOARD_IMAGE_HISTORY,
            SettingLinkIds.CLIPBOARD_RETENTION_TIME,
            SettingLinkIds.CLIPBOARD_SENSITIVE_RETENTION_TIME,
            SettingLinkIds.CLIPBOARD_PASTE_PLAIN_TEXT,
            SettingLinkIds.CLIPBOARD_CTRL_SHIFT_V_PLAIN_TEXT
        ).forEach { id ->
            assertEquals(id, SettingsDestination.Clipboard, SettingLinkRegistry.byId(id)?.route?.destination)
        }
        mapOf(
            "advanced.clipboard_retention_time" to SettingLinkIds.CLIPBOARD_RETENTION_TIME,
            "advanced.sensitive_clipboard_retention_time" to SettingLinkIds.CLIPBOARD_SENSITIVE_RETENTION_TIME,
            "advanced.clipboard_image_history" to SettingLinkIds.CLIPBOARD_IMAGE_HISTORY
        ).forEach { (old, new) -> assertEquals(new, SettingLinkRegistry.byId(old)?.id) }
    }

    @Test
    fun searchFindsThePasteShortcutSetting() {
        val results = SettingLinkRegistry.search(context, context.getString(R.string.clipboard_ctrl_shift_v_title))
        assertTrue(results.any { it.id == SettingLinkIds.CLIPBOARD_CTRL_SHIFT_V_PLAIN_TEXT })
        assertTrue(SettingLinkRegistry.search(context, "clipboard").any { it.id == SettingLinkIds.MAIN_CLIPBOARD })
    }
}
