package rocks.pkb.plektra.core

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import rocks.pkb.plektra.EmojiPagesSettings
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.SymPagesConfig
import rocks.pkb.plektra.data.emoji.EmojiRepository
import rocks.pkb.plektra.data.emoji.EmojiSkinTone
import rocks.pkb.plektra.data.emoji.RecentEmojiManager
import rocks.pkb.plektra.data.symbols.Kaomoji
import rocks.pkb.plektra.data.symbols.SymbolPages
import rocks.pkb.plektra.data.symbols.SymbolSearch
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
        private const val SECTION_KAOMOJI_RECENTS = "kaomoji_recents"
        private const val SECTION_KAOMOJI = "kaomoji"
        private const val SECTION_SYMBOLS = "symbols"

        /** What the paged layer shown now holds, for the key grid's text size; null when not paged. */
        @Volatile
        var shownPagedKind: SymPagedLayer.Kind? = null
            private set

        /**
         * The keys of the generated page or the variants shown now that hold nothing: drawn
         * empty on screen, not as letters they wouldn't type. Empty on the layer's own page.
         */
        @Volatile
        var shownFreeKeys: Set<Int> = emptySet()
            private set
    }

    /** A paged layer's search key was pressed: the input method opens the picker's search. */
    var onPagedSearchRequested: (() -> Unit)? = null

    /** The edit key on the variants of a mapped emoji: the input method opens that key's customization. */
    var onKeyCustomizationRequested: ((page: Int, keyCode: Int) -> Unit)? = null

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

    // ---- Emoji and symbols pages turned with Q and P ----

    private var pagedLayerShown: SymPage? = null
    private var pageIndex = 0
    private var cachedPages: List<SymPagedLayer.Page>? = null
    /** What [cachedPages] were built from: they are rebuilt when it changes. */
    private var cachedPagesSource: PagesSource? = null
    /** An emoji's variants (skin tones), shown on pages of their own while chosen; null when not. */
    private var variants: SymPagedLayer.Variants? = null
    /**
     * A pressed emoji with variants: typed when its key comes up, its variants when held. Any
     * other key going down first types it, so fast typing keeps the order of the presses.
     */
    private var pending: PendingEmoji? = null
    /** The key whose hold opened the variants: its release is spent. */
    private var variantsHeldKey = KeyEvent.KEYCODE_UNKNOWN

    private data class PendingEmoji(val keyCode: Int, val emoji: String, val kind: SymPagedLayer.Kind, val recorded: Boolean)

    /**
     * The emoji offered (they change with the emoji font), the default skin tone and the kaomoji
     * placement: settings and the font can change while the keyboard stays open.
     */
    private data class PagesSource(
        val emoji: List<EmojiRepository.EmojiCategory>?,
        val tone: Int,
        val kaomoji: EmojiPagesSettings.KaomojiPlacement
    ) {
        override fun equals(other: Any?): Boolean =
            other is PagesSource && other.emoji === emoji && other.tone == tone && other.kaomoji == kaomoji

        override fun hashCode(): Int = System.identityHashCode(emoji) * 31 + tone * 7 + kaomoji.hashCode()
    }

    /** The text page shown as pages now, or null when the current page isn't paged. */
    private fun pagedLayer(): SymPage? {
        val page = when (currentPageType()) {
            SymPage.EMOJI -> SymPage.EMOJI.takeIf { EmojiPagesSettings.emojiPagesEnabled(context) }
            SymPage.SYMBOLS -> SymPage.SYMBOLS.takeIf { EmojiPagesSettings.symbolPagesEnabled(context) }
            else -> null
        }
        if (page != pagedLayerShown) {
            pagedLayerShown = page
            resetPagedState()
        }
        return page
    }

    private fun resetPagedState() {
        pageIndex = 0
        cachedPages = null
        cachedPagesSource = null
        variants = null
        pending = null
        variantsHeldKey = KeyEvent.KEYCODE_UNKNOWN
    }

    private fun pagesFor(layer: SymPage): List<SymPagedLayer.Page> {
        val source = PagesSource(
            emoji = if (layer == SymPage.EMOJI) emojiCategories() else null,
            tone = EmojiSkinTone.get(context),
            kaomoji = EmojiPagesSettings.kaomojiPlacement(context)
        )
        cachedPages?.takeIf { source == cachedPagesSource }?.let { return it }
        if (cachedPages != null) {
            // The offered items changed under an open layer: start over on its own page.
            pageIndex = 0
            variants = null
        }
        val placement = source.kaomoji
        val sections = mutableListOf<SymPagedLayer.Section>()
        val pages = if (layer == SymPage.EMOJI) {
            RecentEmojiManager.getOfferedRecentEmojis(context, SymPagedLayer.PER_PAGE).takeIf { it.isNotEmpty() }?.let {
                sections += SymPagedLayer.Section(SymPagedLayer.SECTION_RECENTS, SymPagedLayer.Kind.EMOJI, it)
            }
            val tone = source.tone
            source.emoji.orEmpty().forEach { category ->
                sections += SymPagedLayer.Section(
                    category.id,
                    SymPagedLayer.Kind.EMOJI,
                    category.emojis.map { EmojiSkinTone.preferred(it, tone) }
                )
            }
            if (placement == EmojiPagesSettings.KaomojiPlacement.EMOJI) sections += kaomojiSections()
            SymPagedLayer.buildPages(alternateCharacterManager.getSymMappings(), SymPagedLayer.Kind.EMOJI, sections)
        } else {
            SymbolSearch.recents.get(context).filter(SymbolSearch::canRender).take(SymPagedLayer.PER_PAGE).takeIf { it.isNotEmpty() }?.let {
                sections += SymPagedLayer.Section(SymPagedLayer.SECTION_RECENTS, SymPagedLayer.Kind.SYMBOLS, it)
            }
            sections += SymPagedLayer.Section(SECTION_SYMBOLS, SymPagedLayer.Kind.SYMBOLS, SymbolPages.ALL)
            if (placement == EmojiPagesSettings.KaomojiPlacement.SYMBOLS) sections += kaomojiSections()
            SymPagedLayer.buildPages(alternateCharacterManager.getSymMappings2(), SymPagedLayer.Kind.SYMBOLS, sections)
        }
        cachedPages = pages
        cachedPagesSource = source
        return pages
    }

    private fun kaomojiSections(): List<SymPagedLayer.Section> = buildList {
        Kaomoji.recents.get(context).take(SymPagedLayer.PER_PAGE).takeIf { it.isNotEmpty() }?.let {
            add(SymPagedLayer.Section(SECTION_KAOMOJI_RECENTS, SymPagedLayer.Kind.KAOMOJI, it))
        }
        add(SymPagedLayer.Section(SECTION_KAOMOJI, SymPagedLayer.Kind.KAOMOJI, Kaomoji.all(context)))
    }

    private fun emojiCategories(): List<EmojiRepository.EmojiCategory> =
        EmojiRepository.cachedCategoriesOrNull() ?: runCatching {
            kotlinx.coroutines.runBlocking { EmojiRepository.getEmojiCategories(context) }
        }.getOrDefault(emptyList())

    private fun currentPage(layer: SymPage): SymPagedLayer.Page? {
        val pages = pagesFor(layer)
        if (pages.isEmpty()) return null
        pageIndex = pageIndex.coerceIn(0, pages.lastIndex)
        return pages[pageIndex]
    }

    private fun pagedMappings(layer: SymPage, shiftPressed: Boolean): Map<Int, String> {
        val page = currentPage(layer) ?: return emptyMap<Int, String>().also { shownFreeKeys = emptySet() }
        variants?.let { shown ->
            shownPagedKind = SymPagedLayer.Kind.EMOJI
            return SymPagedLayer.variantKeys(shown).also { shownFreeKeys = SymPagedLayer.freeKeys(it) }
        }
        shownPagedKind = page.kind
        if (page.mapping == null) {
            return SymPagedLayer.keys(page).also { shownFreeKeys = SymPagedLayer.freeKeys(it) }
        }
        // The layer's own page shows its mapping as Shift has it now; its other keys type as before
        shownFreeKeys = emptySet()
        return SymPagedLayer.keys(page.copy(mapping = mappingsForPage(layer, shiftPressed)))
    }

    /** Q/P: the page before/after; with [section], the previous/next section's first page. */
    fun turnPage(by: Int, section: Boolean = false): Boolean {
        val layer = pagedLayer() ?: return false
        variants?.let { shown ->
            // Q and P turn the variants' pages; Q on their first page goes back to the layer
            val next = shown.page + by
            variants = if (next < 0 || shown.pageCount <= 1 && by > 0) null else shown.copy(page = next.mod(shown.pageCount))
            return true
        }
        // The recents stay as they were when the page opened, so pages don't shift under you
        val pages = pagesFor(layer)
        pageIndex = if (section) SymPagedLayer.jumpSection(pages, pageIndex, by) else SymPagedLayer.turn(pages, pageIndex, by)
        return true
    }

    private fun variantsOf(emoji: String): List<String> {
        val entry = EmojiRepository.findEntry(emoji) ?: return emptyList()
        return (listOf(entry.base) + entry.variants).distinct().takeIf { it.size > 1 }.orEmpty()
    }

    /**
     * Holding an emoji: its variants on pages of their own, all of them. [mappedKey] is the key
     * whose own mapping holds the emoji: its variants offer that key's customization.
     */
    private fun showVariants(emoji: String, mappedKey: Int? = null): Boolean {
        val shown = variantsOf(emoji).takeIf { it.isNotEmpty() } ?: return false
        variants = SymPagedLayer.Variants(shown, customizableKey = mappedKey)
        return true
    }

    /** The page number of [layer] for the customization screen (1 emoji, 2 symbols). */
    private fun customizationPage(layer: SymPage): Int = if (layer == SymPage.EMOJI) 1 else 2

    /** The item [keyCode] types on [page]: its own mapping (as Shift has it) or a generated item. */
    private fun itemOnKey(layer: SymPage, page: SymPagedLayer.Page, keyCode: Int, shiftPressed: Boolean): String? =
        if (page.mapping != null) {
            mappingsForPage(layer, shiftPressed)[keyCode]?.takeUnless { SymPagedLayer.isNavigationKey(page, keyCode) }
        } else {
            SymPagedLayer.keys(page)[keyCode]?.takeUnless { SymPagedLayer.isNavigationKey(page, keyCode) }
        }

    /** Types the pressed emoji still waiting for its key to come up, when another key goes down. */
    private fun resolvePending(
        keyCode: Int,
        inputConnection: InputConnection?,
        updateStatusBar: () -> Unit,
        onSymbolCommitted: () -> Unit
    ) {
        val waiting = pending ?: return
        if (waiting.keyCode == keyCode) return
        pending = null
        commitPaged(waiting.emoji, waiting.kind, inputConnection, updateStatusBar, onSymbolCommitted, waiting.recorded)
    }

    private fun recordTyped(kind: SymPagedLayer.Kind, text: String) {
        when (kind) {
            SymPagedLayer.Kind.EMOJI -> RecentEmojiManager.addRecentEmoji(context, text)
            SymPagedLayer.Kind.SYMBOLS -> SymbolSearch.recents.add(context, text)
            SymPagedLayer.Kind.KAOMOJI -> Kaomoji.recents.add(context, text)
        }
    }

    private fun commitPaged(
        text: String,
        kind: SymPagedLayer.Kind,
        inputConnection: InputConnection?,
        updateStatusBar: () -> Unit,
        onSymbolCommitted: () -> Unit,
        record: Boolean = true,
        touch: Boolean = false
    ) {
        val committed = inputConnection?.commitText(text, 1) == true
        if (committed) {
            if (record) recordTyped(kind, text)
            onSymbolCommitted()
        }
        if (variants != null) variants = null
        if (SettingsManager.symAutoClosesAfterCommit(context, touch)) {
            closeSymAndUpdate(updateStatusBar)
        } else {
            updateStatusBar()
        }
    }

    /**
     * A key on a paged layer: the arrows, search, an emoji's variants, or the item on the key.
     * Returns NOT_HANDLED for what the layer's own page types as before.
     */
    private fun handlePagedKey(
        layer: SymPage,
        keyCode: Int,
        event: KeyEvent?,
        inputConnection: InputConnection?,
        shiftPressed: Boolean,
        updateStatusBar: () -> Unit,
        onSymbolCommitted: () -> Unit,
        touch: Boolean = false
    ): SymKeyResult {
        val repeat = event?.repeatCount ?: 0
        val page = currentPage(layer) ?: return SymKeyResult.NOT_HANDLED
        variants?.let { shown ->
            if (keyCode == variantsHeldKey) return SymKeyResult.CONSUME
            when (keyCode) {
                SymPagedLayer.PREVIOUS_KEY, SymPagedLayer.NEXT_KEY -> {
                    if (repeat == 0 && turnPage(if (keyCode == SymPagedLayer.NEXT_KEY) 1 else -1)) updateStatusBar()
                    return SymKeyResult.CONSUME
                }
                SymPagedLayer.EDIT_KEY -> {
                    val mappedKey = shown.customizableKey
                    if (repeat == 0 && mappedKey != null) {
                        variants = null
                        updateStatusBar()
                        onKeyCustomizationRequested?.invoke(customizationPage(layer), mappedKey)
                    }
                    return SymKeyResult.CONSUME
                }
            }
            val chosen = SymPagedLayer.variantKeys(shown)[keyCode]?.takeIf { keyCode in SymPagedLayer.SLOTS }
                ?: return SymKeyResult.CONSUME
            if (repeat == 0) commitPaged(chosen, SymPagedLayer.Kind.EMOJI, inputConnection, updateStatusBar, onSymbolCommitted, touch = touch)
            return SymKeyResult.CONSUME
        }
        when {
            keyCode == SymPagedLayer.PREVIOUS_KEY || keyCode == SymPagedLayer.NEXT_KEY -> {
                val by = if (keyCode == SymPagedLayer.NEXT_KEY) 1 else -1
                if (turnPage(by, section = shiftPressed)) updateStatusBar()
                return SymKeyResult.CONSUME
            }
            keyCode == SymPagedLayer.SEARCH_KEY && page.mapping == null -> {
                if (repeat == 0) onPagedSearchRequested?.invoke()
                return SymKeyResult.CONSUME
            }
        }
        val ownPage = page.mapping != null
        val text = itemOnKey(layer, page, keyCode, shiftPressed) ?: return if (
            !ownPage && (keyCode in SymPagedLayer.SLOTS || mappingsForPage(layer, shiftPressed).containsKey(keyCode))
        ) {
            SymKeyResult.CONSUME // a free key on a generated page types nothing
        } else {
            SymKeyResult.NOT_HANDLED
        }
        // An emoji with variants, on a generated page or mapped on the layer's own page: typed
        // when its key comes up, its variants when held. Everything else types as before.
        if (variantsOf(text).isNotEmpty()) {
            val waiting = pending
            if (repeat == 0) {
                pending = PendingEmoji(keyCode, text, SymPagedLayer.Kind.EMOJI, recorded = !ownPage)
            } else if (waiting != null && waiting.keyCode == keyCode) {
                pending = null
                if (showVariants(waiting.emoji, mappedKey = keyCode.takeIf { ownPage })) {
                    variantsHeldKey = keyCode
                    updateStatusBar()
                }
            }
            return SymKeyResult.CONSUME
        }
        if (ownPage) return SymKeyResult.NOT_HANDLED // the layer's own page: as before
        if (repeat == 0) commitPaged(text, page.kind, inputConnection, updateStatusBar, onSymbolCommitted, touch = touch)
        return SymKeyResult.CONSUME
    }

    /**
     * A key coming up on a paged layer: a pressed emoji with variants that wasn't held long
     * enough to show them is typed now. True when the release belonged to the paged layer.
     */
    fun handlePagedKeyUp(
        keyCode: Int,
        inputConnection: InputConnection?,
        updateStatusBar: () -> Unit,
        onSymbolCommitted: () -> Unit = {}
    ): Boolean {
        if (variantsHeldKey != KeyEvent.KEYCODE_UNKNOWN && keyCode == variantsHeldKey) {
            variantsHeldKey = KeyEvent.KEYCODE_UNKNOWN
            return true
        }
        val waiting = pending?.takeIf { it.keyCode == keyCode } ?: return false
        pending = null
        commitPaged(waiting.emoji, waiting.kind, inputConnection, updateStatusBar, onSymbolCommitted, waiting.recorded)
        return true
    }

    /**
     * A key of a paged layer tapped on screen: the arrows, search, the variants' keys and the
     * edit key are handled here (true); anything else is typed by the caller. A variant typed
     * here closes SYM by the same touch rule as the caller's typing.
     */
    fun handlePagedTouchTap(keyCode: Int, inputConnection: InputConnection?, updateStatusBar: () -> Unit): Boolean =
        handlePagedTouchTap(keyCode, inputConnection, updateStatusBar, onSymbolCommitted = {})

    fun handlePagedTouchTap(
        keyCode: Int,
        inputConnection: InputConnection?,
        updateStatusBar: () -> Unit,
        onSymbolCommitted: () -> Unit
    ): Boolean {
        val layer = pagedLayer() ?: return false
        val page = currentPage(layer) ?: return false
        if (variants != null) {
            return handlePagedKey(
                layer, keyCode, null, inputConnection, false, updateStatusBar, onSymbolCommitted, touch = true
            ) == SymKeyResult.CONSUME
        }
        if (!SymPagedLayer.isNavigationKey(page, keyCode)) {
            val text = SymPagedLayer.keys(page)[keyCode]
            if (page.mapping == null && text != null) recordTyped(page.kind, text)
            return false
        }
        return handlePagedKey(layer, keyCode, null, inputConnection, false, updateStatusBar, onSymbolCommitted, touch = true) ==
            SymKeyResult.CONSUME
    }

    /**
     * A key of a paged layer held on screen: an emoji's variants (also of an emoji mapped on the
     * layer's own page), or a section jump on the arrows. True when the layer used the hold;
     * false leaves it to the caller (the customization of a key on the layer's own page).
     */
    fun handlePagedTouchLongPress(keyCode: Int): Boolean {
        val layer = pagedLayer() ?: return false
        val page = currentPage(layer) ?: return false
        // On the variants and the generated pages a hold never opens the customization of
        // the key's own mapping, which those pages don't show.
        if (variants != null) return true
        return when (keyCode) {
            SymPagedLayer.PREVIOUS_KEY -> turnPage(-1, section = true)
            SymPagedLayer.NEXT_KEY -> turnPage(1, section = true)
            else -> {
                val ownPage = page.mapping != null
                val text = itemOnKey(layer, page, keyCode, shiftPressed = false)
                val shown = text != null && showVariants(text, mappedKey = keyCode.takeIf { ownPage })
                shown || !ownPage
            }
        }
    }

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
        pagedLayer()?.let { return pagedMappings(it, shiftPressed) }
        shownPagedKind = null
        shownFreeKeys = emptySet()
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
        if ((event?.repeatCount ?: 0) == 0 && !KeyEvent.isModifierKey(keyCode)) {
            resolvePending(keyCode, inputConnection, updateStatusBar, onSymbolCommitted)
            // Typing the waiting emoji can close SYM (auto-close): this key then types as
            // the keyboard without SYM does.
            if (!isSymActive()) return SymKeyResult.NOT_HANDLED
        }
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

        pagedLayer()?.let { layer ->
            val result = handlePagedKey(layer, keyCode, event, inputConnection, shiftPressed, updateStatusBar, onSymbolCommitted)
            if (result != SymKeyResult.NOT_HANDLED) return result
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
        // Every page change opens the paged layers on their own page again.
        pagedLayerShown = null
        resetPagedState()
    }

}
