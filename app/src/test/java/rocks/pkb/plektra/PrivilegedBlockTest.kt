package rocks.pkb.plektra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import rocks.pkb.plektra.inputmethod.PrivilegedAccessMode
import rocks.pkb.plektra.inputmethod.ShizukuState

/** Which blocked-state message a toolbox screen picks; a Shizuku-only user never gets pairing. */
class PrivilegedBlockTest {

    @Test
    fun shizukuOnlyNeverGetsPairing() {
        val expected = mapOf(
            ShizukuState.NOT_ALLOWED to PrivilegedBlock.SHIZUKU_NOT_ALLOWED,
            ShizukuState.DENIED_PERMANENTLY to PrivilegedBlock.SHIZUKU_DENIED_PERMANENTLY,
            ShizukuState.NOT_RUNNING to PrivilegedBlock.SHIZUKU_NOT_RUNNING,
            ShizukuState.NOT_INSTALLED to PrivilegedBlock.SHIZUKU_NOT_INSTALLED,
            ShizukuState.TOO_OLD to PrivilegedBlock.SHIZUKU_TOO_OLD,
            ShizukuState.READY to PrivilegedBlock.SHIZUKU_FAILED,
        )
        assertEquals(ShizukuState.entries.toSet(), expected.keys)
        expected.forEach { (state, block) ->
            assertEquals(state.toString(), block, PrivilegedBlock.choose(PrivilegedAccessMode.SHIZUKU_ONLY, state))
        }
    }

    @Test
    fun automaticKeepsPairingAndMentionsShizukuUnlessItIsTheRoute() {
        ShizukuState.entries.filter { it != ShizukuState.READY }.forEach { state ->
            assertEquals(PrivilegedBlock.PAIRING_OR_SHIZUKU, PrivilegedBlock.choose(PrivilegedAccessMode.AUTOMATIC, state))
        }
        // Automatic with Shizuku ready routes through Shizuku, so pairing is no remedy.
        assertEquals(
            PrivilegedBlock.SHIZUKU_FAILED,
            PrivilegedBlock.choose(PrivilegedAccessMode.AUTOMATIC, ShizukuState.READY)
        )
    }

    @Test
    fun brokerOnlyIsAlwaysPairing() {
        ShizukuState.entries.forEach { state ->
            assertEquals(PrivilegedBlock.PAIRING, PrivilegedBlock.choose(PrivilegedAccessMode.BROKER_ONLY, state))
        }
    }

    @Test
    fun onlyPairingBlocksUseTheScreensOwnLine() {
        val own = 12345
        PrivilegedBlock.entries.forEach { block ->
            val pairing = block == PrivilegedBlock.PAIRING || block == PrivilegedBlock.PAIRING_OR_SHIZUKU
            if (pairing) assertEquals(own, block.bodyRes(own)) else assertNotEquals(own, block.bodyRes(own))
        }
    }
}
