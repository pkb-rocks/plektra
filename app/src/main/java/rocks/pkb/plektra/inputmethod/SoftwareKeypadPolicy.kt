package rocks.pkb.plektra.inputmethod

import android.text.InputType
import rocks.pkb.plektra.SymPagesConfig

/** On-screen layout family: the full keyboard or a compact keypad. */
enum class SoftwareKeypad { FULL, NUMBER, PHONE }

object SoftwareKeypadPolicy {
    fun autoKeypadFor(inputType: Int): SoftwareKeypad = when (inputType and InputType.TYPE_MASK_CLASS) {
        InputType.TYPE_CLASS_NUMBER -> SoftwareKeypad.NUMBER
        InputType.TYPE_CLASS_PHONE -> SoftwareKeypad.PHONE
        else -> SoftwareKeypad.FULL
    }

    /** Hardware devices type digits physically; the keypad opens there only when asked to. */
    fun shouldAutoOpenOnHardware(inputType: Int, autoOpenEnabled: Boolean): Boolean =
        autoOpenEnabled && autoKeypadFor(inputType) != SoftwareKeypad.FULL
}

/**
 * Automatic keypad on a hardware keyboard while app/Android display defaults are respected.
 *
 * Android does not tell an IME whether a show request answers its own requestShowSelf. Plektra
 * therefore requests no own display while the decision is open, so every new show request comes
 * from the app or Android and opens the keypad. Plektra's own show requests, the first hardware
 * key and the end of the field settle the decision without a keypad; a configuration reshow and an
 * input restart leave it as it is.
 */
class HardwareKeypadAutoOpen {
    var awaitingShowRequest = false
        private set

    fun begin() {
        awaitingShowRequest = true
    }

    fun settle() {
        awaitingShowRequest = false
    }

    /** Whether this show request opens the keypad. */
    fun onShowRequested(configChange: Boolean): Boolean {
        if (!awaitingShowRequest || configChange) return false
        awaitingShowRequest = false
        return true
    }
}

/** Field-scoped keypad choice: a manual layout lasts until the editor changes; a restart keeps it. */
class SoftwareKeypadSession {
    var fieldInputType: Int = InputType.TYPE_NULL
        private set
    var manualKeypad: SoftwareKeypad? = null
        private set

    fun onFieldStarted(inputType: Int, restarting: Boolean) {
        if (!restarting) manualKeypad = null
        fieldInputType = inputType
    }

    fun onFieldFinished() {
        manualKeypad = null
        fieldInputType = InputType.TYPE_NULL
    }

    fun select(keypad: SoftwareKeypad) {
        manualKeypad = keypad
    }

    fun resolved(): SoftwareKeypad = manualKeypad ?: SoftwareKeypadPolicy.autoKeypadFor(fieldInputType)
}

/** Entries of the page chooser opened by holding the bottom-left layout key. */
object SoftwareKeyboardPageChoices {
    const val LETTERS = "letters"
    const val NUMBER = "number"
    const val PHONE = "phone"
    private const val PAGE_PREFIX = "page:"

    data class Choice(val id: String, val label: String)

    data class Labels(
        val letters: String,
        val number: String,
        val phone: String,
        val emojiLayer: String,
        val symbols: String,
        val device: String,
        val clipboard: String,
        val emojiPicker: String
    )

    fun pageId(page: Int): String = "$PAGE_PREFIX$page"

    fun pageFromId(id: String): Int? = id.removePrefix(PAGE_PREFIX).takeIf { id.startsWith(PAGE_PREFIX) }?.toIntOrNull()

    fun keypadFromId(id: String): SoftwareKeypad? = when (id) {
        LETTERS -> SoftwareKeypad.FULL
        NUMBER -> SoftwareKeypad.NUMBER
        PHONE -> SoftwareKeypad.PHONE
        else -> null
    }

    /**
     * Layouts first, then the SYM pages of the on-screen cycle. The emoji picker stays available
     * as the regular emoji entry even when it is not part of the cycle.
     */
    fun build(cycleConfig: SymPagesConfig, labels: Labels): List<Choice> {
        val layouts = listOf(
            Choice(LETTERS, labels.letters),
            Choice(NUMBER, labels.number),
            Choice(PHONE, labels.phone)
        )
        val pages = cycleConfig.enabledOrderedPages().mapNotNull { page ->
            when (page) {
                SymPagesConfig.PAGE_EMOJI -> Choice(pageId(1), labels.emojiLayer)
                SymPagesConfig.PAGE_SYMBOLS -> Choice(pageId(2), labels.symbols)
                SymPagesConfig.PAGE_CLIPBOARD -> Choice(pageId(3), labels.clipboard)
                SymPagesConfig.PAGE_EMOJI_PICKER -> Choice(pageId(4), labels.emojiPicker)
                SymPagesConfig.PAGE_DEVICE -> Choice(pageId(5), labels.device)
                else -> null
            }
        }
        val picker = Choice(pageId(4), labels.emojiPicker)
        return layouts + pages + if (pages.none { it.id == picker.id }) listOf(picker) else emptyList()
    }
}
