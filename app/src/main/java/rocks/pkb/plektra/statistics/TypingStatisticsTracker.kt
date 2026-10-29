package rocks.pkb.plektra.statistics

/** Counts newly produced words; cursor inspection alone never produces a count. */
class TypingStatisticsTracker(private val record: (manual: Int, suggested: Int) -> Unit) {
    private val pending = StringBuilder()
    private var editingExistingWord = false
    private var previousBeforeCursor: String? = null

    fun onTextCommitted(text: CharSequence, beforeCursor: String? = null, afterCursor: String? = null, contextLimit: Int = 128) {
        val inserted = normalizeApostrophes(text.toString())
        val observedBefore = beforeCursor?.let(::normalizeApostrophes)
        val observedAfter = afterCursor?.let(::normalizeApostrophes)
        val isReplacement = inserted.startsWith('\b')
        if (!isReplacement && pending.isNotEmpty() && observedBefore != null &&
            previousBeforeCursor != null && observedBefore.endsWith(inserted) &&
            observedBefore != (previousBeforeCursor + inserted).let {
                if (observedBefore.length == contextLimit) it.takeLast(contextLimit) else it
            }) {
            finishWord()
        }
        if (pending.isEmpty() && !isReplacement && (observedBefore != null || observedAfter != null)) {
            val beforeInsertion = observedBefore?.takeIf { it.endsWith(inserted) }?.dropLast(inserted.length)
            editingExistingWord = beforeInsertion?.lastOrNull()?.let(::isWordCharacter) == true ||
                observedAfter?.firstOrNull()?.let(::isWordCharacter) == true
        }
        inserted.forEach { char ->
            when {
                char == '\b' -> onBackspace()
                char.isLetterOrDigit() -> pending.append(char)
                isConnector(char) && pending.isNotEmpty() -> pending.append(char)
                else -> finishWord()
            }
        }
        // Apostrophe tracking may be notified before the editor commits the key.
        previousBeforeCursor = if (observedBefore != null && inserted.length == 1 &&
            isConnector(inserted[0]) && !observedBefore.endsWith(inserted)) {
            observedBefore + inserted
        } else observedBefore
    }

    fun onEditorStarted(hasExistingWord: Boolean) {
        finishWord()
        editingExistingWord = hasExistingWord
    }

    /** Reuse an existing cursor inspection; an echo of our own pending word is not navigation. */
    fun onEditorWordObserved(word: String?) {
        val observed = normalizeApostrophes(word.orEmpty())
        val current = pending.toString()
        if (current.isNotEmpty() && (observed == current ||
                (observed.length >= 48 && current.endsWith(observed)))) return
        finishWord()
        editingExistingWord = observed.any { it.isLetterOrDigit() }
    }

    fun onBackspace() {
        if (pending.isNotEmpty()) pending.deleteCharAt(pending.length - 1)
        previousBeforeCursor = previousBeforeCursor?.dropLast(1)
    }

    fun finishWord() {
        if (!editingExistingWord && pending.any { it.isLetterOrDigit() }) record(1, 0)
        pending.clear()
        editingExistingWord = false
        previousBeforeCursor = null
    }

    fun onSuggestionCommitted(text: String) {
        // The unfinished prefix is replaced by the suggestion, not counted separately.
        pending.clear()
        editingExistingWord = false
        previousBeforeCursor = null
        val count = WORD.findAll(text).count()
        if (count > 0) record(0, count)
    }

    private fun normalizeApostrophes(text: String) = text.replace('’', '\'').replace('‘', '\'').replace('ʼ', '\'')

    private fun isConnector(char: Char) = char in "'’‘ʼ-"
    private fun isWordCharacter(char: Char) = char.isLetterOrDigit() || isConnector(char)

    companion object {
        private val WORD = Regex("[\\p{L}\\p{N}]+(?:['’‘ʼ-][\\p{L}\\p{N}]+)*")
    }
}
