package rocks.pkb.plektra.inputmethod.otp

import org.junit.Assert.*
import org.junit.Test

class OtpInboxTest {
    @Test
    fun extractsEnglishAndTurkishCodesWithoutTakingUnrelatedNumbers() {
        val rows = listOf(
            "Your verification code is 823491. It expires in 10 minutes." to "823491",
            "<#> 630812 is your Example login code. a1b2c3d4" to "630812",
            "Your OTP: 0942" to "0942",
            "One-time password: 58702441" to "58702441",
            "Use 90017 as your security code" to "90017",
            "Verification code\n755140" to "755140",
            "Example: 123-456 is your authentication code" to "123456",
            "OTP: [8362–2741]" to "83622741",
            "Don't share your code.\nCode: 728198" to "728198",
            "WhatsApp code: 385-562. Don't share this code with others." to "385562",
            "Telegram login code: 83915. Do not give this code to anyone." to "83915",
            "Security code: 334899. For help call 1-800-555-0123." to "334899",
            "Payment of $2345. Your OTP is 641239." to "641239",
            "Code: 437825; issued 2025-08-05" to "437825",
            "Verification code: 903618. Card ending in 3417." to "903618",
            "Code: 0012" to "0012",
            "Your PIN is 15584" to "15584",
            "Your one time code is (627444)." to "627444",
            "Doğrulama kodunuz: 228573. Kimseyle paylaşmayın. B001" to "228573",
            "TEK KULLANIMLIK ŞİFRENİZ: 7639" to "7639",
            "Onay kodu 673-481, 3 dakika geçerlidir." to "673481",
            "714058 giriş kodunuzdur." to "714058",
            "Doğrulama: 377515" to "377515",
            "Şifre: 08615. Müşteri hizmetleri 0850 555 20 20." to "08615",
            "KOD: 774-128" to "774128",
            "Güvenlik kodunuz 46017542" to "46017542",
            "123456 doğrulama kodu ile işleminizi onaylayın." to "123456",
            "İşlem tutarı 8745 TL. Tek kullanımlık şifre: 651281" to "651281",
            "Example: 3784'ü onay kodu olarak girin." to "3784",
            "" to null,
            "739212" to null,
            "Meeting in room 4301" to null,
            "Discount code: 236789" to null,
            "Postal code: 34728" to null,
            "Hata kodu: 3928" to null,
            "Kampanya kodu: 5302" to null,
            "Invoice code: 123456" to null,
            "Verification date: 2025-10-02" to null,
            "Your code expires on 13/02/2025" to null,
            "Code: 2025-10-02" to null,
            "Code: 2025" to null,
            "Code: 1999" to null,
            "Code: 123" to null,
            "Code: 123456789" to null,
            "Code: ABC123456" to null,
            "Code: 1,234.99" to null,
            "Code: €3274" to null,
            "Kod: 12345,50 TL" to null,
            "Kod: 1692 TL" to null,
            "Code: +15555550123" to null,
            "Code: 555-123-4567" to null,
            "Code: 0850 555 20 20" to null,
            "Kodunuz için +90 532 777 1234 arayın." to null,
            "Your code is ready. Call 12345678 for help." to null,
            "Your code expired. Order 601444 has shipped." to null,
            "Kodunuz 15 dakika geçerlidir. Tutar 2428 TL." to null,
            "OTP: https://example.net/456731" to null,
            "Code: 3841 or 8273 is your code" to null
        )
        val failures = rows.mapNotNull { (body, expected) ->
            val actual = OtpExtractor.extract(body)
            if (expected == actual) null else "$body: expected $expected, got $actual"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun allOrderedSourcePairsDeduplicateWithoutRenewalIncludingAfterUseOrReplacement() {
        for (first in OtpSource.entries) for (second in OtpSource.entries) {
            var now = 100L
            val inbox = OtpInbox({ true }, { now })
            assertTrue(inbox.offer(first, "654321"))
            val original = inbox.current()!!
            now += 1_000
            assertFalse(inbox.offer(second, "654321"))
            assertSame(original, inbox.current())
            assertTrue(inbox.offer(second, "789654"))
            val replacement = inbox.current()!!
            assertFalse(inbox.offer(first, "654321"))
            assertSame(replacement, inbox.current())
            assertFalse(inbox.use(original) { fail("stale chip committed"); true })
            assertFalse(inbox.use(replacement) { false })
            assertSame(replacement, inbox.current())
            assertTrue(inbox.use(replacement) { it == "789654" })
            assertNull(inbox.current())
            assertFalse(inbox.offer(second, "789654"))
            now = 120_100
            assertTrue(inbox.offer(first, "654321"))
            now += 59_999
            assertNotNull(inbox.current())
            now++
            assertNull(inbox.current())
        }
    }

    @Test
    fun aDuplicateFromAnIndependentSourceCanKeepTheChipEligible() {
        val enabled = OtpSource.entries.toMutableSet()
        val inbox = OtpInbox(enabled::contains, { 0L })
        assertTrue(inbox.offer(OtpSource.SMS, "651281"))
        assertFalse(inbox.offer(OtpSource.NOTIFICATION, "651281"))
        enabled.remove(OtpSource.SMS)
        assertNotNull(inbox.current())
        enabled.remove(OtpSource.NOTIFICATION)
        assertNull(inbox.current())
        enabled.addAll(OtpSource.entries)
        assertNull(inbox.current())
    }
}
