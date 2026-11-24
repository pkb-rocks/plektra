package rocks.pkb.plektra.inputmethod.otp

import java.util.Locale

/** Require a code label next to the number, not merely somewhere in the message. */
internal object OtpExtractor {
    private const val LABEL = "(?:otp|pin|one[- ]time password|codes?|kod(?:u(?:nuz(?:dur)?)?)?|doğrulama|[sş]ifre(?:niz)?)"
    private const val DIGITS = "(?:[0-9]{3,4}[-‑–][0-9]{3,4}|[0-9]{4,8})"
    private val afterLabel = Regex("(?<![\\p{L}\\p{N}_])$LABEL(?=\\P{L}|$)\\s*(?:is\\s+)?[:=]?\\s*[\\[(]?($DIGITS)")
    private val beforeLabel = Regex("($DIGITS)(?:['’](?:ü|u|i|ı))?\\s+(?:(?:is|as)\\s+(?:your|the)\\s+(?:[\\p{L}]+\\s+){0,2})?" +
        "(?:(?:one[- ]time|verification|authentication|security|login|onay|giriş|doğrulama|güvenlik)\\s+)*$LABEL(?![\\p{L}\\p{N}_])")
    private val nonAuthPrefix = Regex("\\b(?:discount|coupon|promo|postal|zip|area|error|invoice|order|tracking|referral|product|hata|posta|kampanya|indirim|sipariş)\\s+$")
    private val invalidBefore = Regex("[\\p{L}\\p{N}_+\\-‑–/.,$€£₺]\\z|[0-9]\\h+\\z")
    private val invalidAfter = Regex("^[\\p{L}\\p{N}_/\\-‑–]|^[.,][0-9]|^\\h+[0-9]|^\\s*(?:tl|try|usd|eur|gbp)\\b|^\\s*[$€£₺]")

    fun extract(body: CharSequence): String? {
        val text = body.toString().lowercase(Locale.ROOT).replace("i\u0307", "i")
        return (afterLabel.findAll(text) + beforeLabel.findAll(text)).mapNotNull { hit ->
            val digits = hit.groups[1] ?: return@mapNotNull null
            if (invalidBefore.containsMatchIn(text.take(digits.range.first)) ||
                invalidAfter.containsMatchIn(text.substring(digits.range.last + 1)) ||
                nonAuthPrefix.containsMatchIn(text.take(hit.range.first))) return@mapNotNull null
            digits.value.filter { it in '0'..'9' }.takeUnless { it.length == 4 && it.toInt() in 1900..2099 }
        }.distinct().take(2).toList().singleOrNull()
    }
}
