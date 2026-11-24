package rocks.pkb.plektra.inputmethod

import java.text.Normalizer
import java.util.Locale

internal object TranslationLanguageSearch {
    private val marks = Regex("\\p{M}+")
    private fun fold(value: String) = Normalizer.normalize(value,Normalizer.Form.NFD)
        .replace(marks, "").lowercase(Locale.ROOT).trim()
    fun matches(query: String,vararg names: String): Boolean {
        val prefix = fold(query)
        return prefix.isEmpty() || names.any { fold(it).startsWith(prefix) }
    }
    fun visibleOffset(top: Int,bottom: Int,current: Int,height: Int): Int = when {
        height <= 0 -> current
        top < current -> top.coerceAtLeast(0)
        bottom > current+height -> (bottom-height).coerceAtLeast(0)
        else -> current
    }
}
