package rocks.pkb.plektra.backlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replies of `service call agui_common 3 s16 keyboard_brightness_timeout`. The single-line cases
 * are adapted from pkb-rocks/plektra#124 (Daniel JB Clark, after PhysiBoard).
 */
class UnihertzVendorParcelTest {

    private fun parse(raw: String?) = UnihertzVendorParcel.parseString(raw)

    @Test
    fun readsTheDisplayLinkedSentinelCapturedFromTheTitan2Elite() {
        val fromDevice = "Result: Parcel(\t00000000 00000002 0031002d 00000000 '........-.1.....')\n"

        assertEquals("-1", parse(fromDevice))
    }

    @Test
    fun readsTheMultiLineStockTimeoutCapturedFromTheTitan2Elite() {
        val fromDevice = "Result: Parcel(\t\n" +
            "0x00000000: 00000000 00000005 00300033 00300030 '........3.0.0.0.'\n" +
            "0x00000010: 00000030                            '0...            ')"

        assertEquals("30000", parse(fromDevice))
    }

    @Test
    fun readsAnOddLengthValueWithoutTrailingPadding() {
        assertEquals("0", parse("Result: Parcel(00000000 00000001 00000030 '..')"))
    }

    @Test
    fun rejectsAReplyWithAnException() {
        assertNull(parse("Result: Parcel(ffffffb5 00000002 0031002d '...')"))
    }

    @Test
    fun rejectsANullString() {
        assertNull(parse("Result: Parcel(00000000 ffffffff '........')"))
    }

    @Test
    fun rejectsOutputWithoutAParcel() {
        assertNull(parse("service: Service agui_common does not exist"))
        assertNull(parse(""))
        assertNull(parse(null))
    }

    @Test
    fun rejectsATruncatedOrImplausiblePayload() {
        assertNull(parse("Result: Parcel(00000000 00000004 00310032 '..')"))
        assertNull(parse("Result: Parcel(00000000 0000ffff 00310032 '..')"))
    }

    @Test
    fun recognisesTheVoidReplyOfASuccessfulWrite() {
        assertTrue(UnihertzVendorParcel.isSuccessfulVoidReply("Result: Parcel(\t00000000    '....')\n"))
        assertFalse(UnihertzVendorParcel.isSuccessfulVoidReply("Result: Parcel(fffffffc '....')"))
        assertFalse(UnihertzVendorParcel.isSuccessfulVoidReply(""))
    }
}
