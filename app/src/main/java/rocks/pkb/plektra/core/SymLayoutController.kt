package rocks.pkb.plektra.core

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.SymPagesConfig
import rocks.pkb.plektra.inputmethod.AlternateCharacterManager

class SymLayoutController(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val alternateCharacterManager: AlternateCharacterManager,
    /**
     * False while the keyboard bar is blocked. Clipboard and emoji picker exist only as bar UI
     * and would capture keys invisibly; the key-remapping pages keep working as hardware layers.
     */
    private val canOpenPickerPages: () -> Boolean = { true }
) {

    companion object {
        private const val PREF_CURRENT_SYM_PAGE = "current_sym_page"
    }

    private enum class SymPage {
        DEVICE,
        EMOJI,
        SYMBOLS,
        CLIPBOARD,
        EMOJI_PICKER
    }

    enum class SymKeyResult {
        NOT_HANDLED,
        CONSUME,
        CALL_SUPER
    }

    private var symPage: Int = prefs.getInt(PREF_CURRENT_SYM_PAGE, 0)

    init {
        alignSymPageToConfig(SettingsManager.getSymPagesConfig(context))
    }

    fun currentSymPage(): Int {
        if (isPickerPage(symPage) && !canOpenPickerPages()) closeSymPage()
        alignSymPageToConfig()
        return symPage
    }

    fun isSymActive(): Boolean = currentSymPage() > 0

    /** [onScreen]: the on-screen SYM key, whose cycle may leave out the emoji layer; else the physical SYM key. */
    fun toggleSymPage(onScreen: Boolean = false): Int {
        alignSymPageToConfig()
        val pages = openablePages(cycleConfig(onScreen))
        symPage = nextSymPageValue(pages)
        persistSymPage()
        return symPage
    }

    fun peekNextSymPage(onScreen: Boolean = false): Int {
        alignSymPageToConfig()
        return nextSymPageValue(openablePages(cycleConfig(onScreen)))
    }

    private fun cycleConfig(onScreen: Boolean): SymPagesConfig =
        if (onScreen) SettingsManager.getOnScreenSymPageCycleConfig(context) else SettingsManager.getSymPagesConfig(context)

    private fun openablePages(config: SymPagesConfig): List<SymPage> =
        buildActivePages(config).filter { canOpenPickerPages() || !isPickerPage(it.toPrefValue()) }

    fun closeSymPage(): Boolean {
        if (symPage == 0) {
            return false
        }
        symPage = 0
        persistSymPage()
        return true
    }
    
    fun openClipboardPage(): Boolean {
        if (!canOpenPickerPages()) return false
        val clipboardPageValue = SymPage.CLIPBOARD.toPrefValue()
        
        // Toggle behavior: open if closed, close if already open
        // Always allow direct access to clipboard page, even if disabled in cycling settings
        if (symPage == clipboardPageValue) {
            closeSymPage()
            return false
        }
        symPage = clipboardPageValue
        persistSymPage()
        return true
    }

    fun openEmojiPickerPage(): Boolean {
        if (!canOpenPickerPages()) return false
        val emojiPickerPageValue = SymPage.EMOJI_PICKER.toPrefValue()
        
        // Toggle behavior: open if closed, close if already open
        // Always allow direct access to emoji picker page
        if (symPage == emojiPickerPageValue) {
            closeSymPage()
            return false
        }
        symPage = emojiPickerPageValue
        persistSymPage()
        return true
    }

    fun openEmojiPage(): Boolean {
        val emojiPageValue = SymPage.EMOJI.toPrefValue()

        if (symPage == emojiPageValue) {
            closeSymPage()
            return false
        }
        symPage = emojiPageValue
        persistSymPage()
        return true
    }

    fun openSymbolsPage(): Boolean {
        val symbolsPageValue = SymPage.SYMBOLS.toPrefValue()
        
        // Toggle behavior: open if closed, close if already open
        // Always allow direct access to symbols page, even if disabled in cycling settings
        if (symPage == symbolsPageValue) {
            closeSymPage()
            return false
        }
        symPage = symbolsPageValue
        persistSymPage()
        return true
    }

    /** Opens a page directly, e.g. from the on-screen page chooser; unlike the toggles it never closes. */
    fun openPage(page: Int): Boolean {
        if (page !in 1..5 || symPage == page) return false
        if (isPickerPage(page) && !canOpenPickerPages()) return false
        symPage = page
        persistSymPage()
        return true
    }

    fun reset() {
        symPage = 0
        persistSymPage()
    }

    fun restoreSymPageIfNeeded(onStatusBarUpdate: () -> Unit) {
        val restoreSymPage = SettingsManager.getRestoreSymPage(context)
        if (restoreSymPage > 0) {
            val config = SettingsManager.getSymPagesConfig(context)
            val pages = buildActivePages(config)
            val allowedValues = pages.map { it.toPrefValue() }
            symPage = when {
                restoreSymPage in allowedValues -> restoreSymPage
                allowedValues.isNotEmpty() -> allowedValues.first()
                else -> 0
            }
            persistSymPage()
            SettingsManager.clearRestoreSymPage(context)
            Handler(Looper.getMainLooper()).post {
                onStatusBarUpdate()
            }
        }
    }

    fun emojiMapText(): String {
        return if (currentPageType() == SymPage.EMOJI) alternateCharacterManager.buildEmojiMapText() else ""
    }

    // Adapted from Mircea Horea IONICĂ (mionica), pastiera 38be1cfb53.
    fun currentSymMappings(shiftPressed: Boolean = false): Map<Int, String>? {
        return when (currentPageType()) {
            SymPage.DEVICE -> alternateCharacterManager.getDeviceSymMappings()
            SymPage.EMOJI -> mappingsForPage(SymPage.EMOJI, shiftPressed)
            SymPage.SYMBOLS -> mappingsForPage(SymPage.SYMBOLS, shiftPressed)
            SymPage.CLIPBOARD -> null // Clipboard doesn't use mappings
            SymPage.EMOJI_PICKER -> null // Emoji picker doesn't use mappings
            else -> null
        }
    }

    fun previewChordMappings(shiftPressed: Boolean): Map<Int, String> {
        val pageToUse = when (currentPageType()) {
            SymPage.DEVICE, SymPage.EMOJI, SymPage.SYMBOLS -> currentPageType()
            else -> preferredChordPage()
        } ?: return emptyMap()

        return mappingsForPage(pageToUse, shiftPressed)
    }

    fun previewNextSoftwareSymPageMappings(shiftPressed: Boolean): Map<Int, String> {
        val nextTextPage = nextSoftwareTextPageType() ?: return emptyMap()
        return mappingsForPage(nextTextPage, shiftPressed)
    }

    fun nextSoftwareTextSymPage(): Int {
        return nextSoftwareTextPageType()?.toPrefValue() ?: 0
    }

    private fun nextSoftwareTextPageType(): SymPage? {
        alignSymPageToConfig()
        val pages = buildActivePages(cycleConfig(onScreen = true))
        if (pages.isEmpty()) {
            return null
        }
        val cycle = listOf(null) + pages
        val currentPage = currentPageType()
        val currentIndex = cycle.indexOf(currentPage).takeIf { it >= 0 } ?: 0
        return (1..cycle.size).asSequence()
            .map { offset -> cycle[(currentIndex + offset) % cycle.size] }
            .firstOrNull { it == SymPage.DEVICE || it == SymPage.EMOJI || it == SymPage.SYMBOLS }
    }

    private fun nextSymPageValue(pages: List<SymPage>): Int {
        val cycle = mutableListOf(0)
        cycle.addAll(pages.map { it.toPrefValue() })
        if (cycle.size <= 1) {
            return 0
        }
        val currentIndex = cycle.indexOf(symPage).takeIf { it >= 0 } ?: 0
        val nextIndex = (currentIndex + 1) % cycle.size
        return cycle[nextIndex]
    }

    private fun mappingsForPage(pageToUse: SymPage, shiftPressed: Boolean): Map<Int, String> {
        return when (pageToUse) {
            SymPage.DEVICE -> alternateCharacterManager.getDeviceSymMappings()
            SymPage.EMOJI -> if (shiftPressed) {
                alternateCharacterManager.getSymMappings() + alternateCharacterManager.getSymMappingsUppercase()
            } else {
                alternateCharacterManager.getSymMappings()
            }
            SymPage.SYMBOLS -> if (shiftPressed) {
                alternateCharacterManager.getSymMappings2() + alternateCharacterManager.getSymMappings2Uppercase()
            } else {
                alternateCharacterManager.getSymMappings2()
            }
            else -> emptyMap()
        }
    }

    /**
     * Resolves the character for a physical SYM+key chord without opening
     * the visual SYM layout. If a text SYM page is already active, use it.
     * Otherwise use the first enabled text page in configured order.
     */
    fun resolveChordSymbol(keyCode: Int, shiftPressed: Boolean): String? {
        val pageToUse = when (currentPageType()) {
            SymPage.DEVICE, SymPage.EMOJI, SymPage.SYMBOLS -> currentPageType()
            else -> preferredChordPage()
        } ?: return null

        return when (pageToUse) {
            SymPage.DEVICE -> alternateCharacterManager.getDeviceSymMappings()[keyCode]
            SymPage.EMOJI -> {
                if (shiftPressed) {
                    alternateCharacterManager.getSymMappingsUppercase()[keyCode] ?: alternateCharacterManager.getSymMappings()[keyCode]
                } else {
                    alternateCharacterManager.getSymMappings()[keyCode]
                }
            }
            SymPage.SYMBOLS -> {
                if (shiftPressed) {
                    alternateCharacterManager.getSymMappings2Uppercase()[keyCode] ?: alternateCharacterManager.getSymMappings2()[keyCode]
                } else {
                    alternateCharacterManager.getSymMappings2()[keyCode]
                }
            }
            else -> null
        }
    }

    fun handleKeyWhenActive(
        keyCode: Int,
        event: KeyEvent?,
        inputConnection: InputConnection?,
        ctrlLatchActive: Boolean,
        altLatchActive: Boolean,
        updateStatusBar: () -> Unit,
        handleBoundaryText: (String, InputConnection?) -> Boolean = { _, _ -> false },
        shiftPressed: Boolean = false,
        onSymbolCommitted: () -> Unit = {}
    ): SymKeyResult {
        val autoCloseEnabled = SettingsManager.getSymAutoClose(context)
        val page = currentPageType()

        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                closeSymAndUpdate(updateStatusBar)
                return SymKeyResult.CALL_SUPER
            }
            KeyEvent.KEYCODE_ENTER -> {
                if (autoCloseEnabled) {
                    closeSymAndUpdate(updateStatusBar)
                    return SymKeyResult.CALL_SUPER
                }
            }
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> {
                closeSymAndUpdate(updateStatusBar)
                return SymKeyResult.NOT_HANDLED
            }
        }

        val symChar = when (page) {
            SymPage.DEVICE, SymPage.EMOJI, SymPage.SYMBOLS -> mappingsForPage(page, shiftPressed)[keyCode]
            else -> null
        }

        if (symChar != null && inputConnection != null) {
            val committed = handleBoundaryText(symChar, inputConnection) ||
                (symChar.length == 1 && SettingsManager.shouldApplyFrenchPunctuationSpacing(context) &&
                    rocks.pkb.plektra.core.Punctuation.commitFrenchSpacedPunctuation(inputConnection, symChar[0])) ||
                inputConnection.commitText(symChar, 1)
            if (committed) {
                onSymbolCommitted()
                updateStatusBar()
            }
            if (autoCloseEnabled) {
                closeSymAndUpdate(updateStatusBar)
            }
            return SymKeyResult.CONSUME
        }

        return SymKeyResult.NOT_HANDLED
    }

    fun handleKeyUp(keyCode: Int, shiftPressed: Boolean): Boolean {
        return alternateCharacterManager.handleKeyUp(keyCode, isSymActive(), shiftPressed)
    }

    fun emojiMapTextForLayout(): String = alternateCharacterManager.buildEmojiMapText()

    private fun closeSymAndUpdate(updateStatusBar: () -> Unit) {
        if (closeSymPage()) {
            updateStatusBar()
        }
    }

    private fun buildActivePages(config: SymPagesConfig = SettingsManager.getSymPagesConfig(context)): List<SymPage> {
        return config.enabledOrderedPages().mapNotNull { pageId ->
            when (pageId) {
                SymPagesConfig.PAGE_DEVICE -> SymPage.DEVICE
                SymPagesConfig.PAGE_EMOJI -> SymPage.EMOJI
                SymPagesConfig.PAGE_SYMBOLS -> SymPage.SYMBOLS
                SymPagesConfig.PAGE_CLIPBOARD -> SymPage.CLIPBOARD
                SymPagesConfig.PAGE_EMOJI_PICKER -> SymPage.EMOJI_PICKER
                else -> null
            }
        }
    }

    private fun preferredChordPage(config: SymPagesConfig = SettingsManager.getSymPagesConfig(context)): SymPage? {
        return buildActivePages(config).firstOrNull {
            it == SymPage.DEVICE || it == SymPage.EMOJI || it == SymPage.SYMBOLS
        }
    }

    private fun currentPageType(): SymPage? {
        alignSymPageToConfig()
        return when (symPage) {
            5 -> SymPage.DEVICE
            1 -> SymPage.EMOJI
            2 -> SymPage.SYMBOLS
            3 -> SymPage.CLIPBOARD
            4 -> SymPage.EMOJI_PICKER
            else -> null
        }
    }

    private fun isPickerPage(page: Int): Boolean =
        page == SymPage.CLIPBOARD.toPrefValue() || page == SymPage.EMOJI_PICKER.toPrefValue()

    private fun SymPage.toPrefValue(): Int = when (this) {
        SymPage.DEVICE -> 5
        SymPage.EMOJI -> 1
        SymPage.SYMBOLS -> 2
        SymPage.CLIPBOARD -> 3
        SymPage.EMOJI_PICKER -> 4
    }

    private fun alignSymPageToConfig(config: SymPagesConfig = SettingsManager.getSymPagesConfig(context)) {
        val allowedValues = buildActivePages(config).map { it.toPrefValue() }
        if (allowedValues.isEmpty()) {
            if (symPage != 0 && symPage !in 2..5) {
                // Allow symbols page (2), clipboard page (3) and emoji picker page (4) even if all cycling pages are disabled
                symPage = 0
                persistSymPage()
            }
            return
        }

        if (symPage == 0) {
            return
        }

        // Allow symbols page (2), clipboard page (3) and emoji picker page (4) to remain active even if disabled in cycling settings
        if (symPage in 2..5) {
            return
        }

        if (symPage !in allowedValues) {
            symPage = allowedValues.first()
            persistSymPage()
        }
    }

    private fun persistSymPage() {
        prefs.edit().putInt(PREF_CURRENT_SYM_PAGE, symPage).apply()
    }

}
