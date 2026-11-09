package rocks.pkb.plektra.data.layout

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LayoutMappingRepositoryAutoCapTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun bundledLayouts_disableAutoCapitalizationOnlyForUncasedScripts() {
        val uncased = setOf("uyghur", "arabic")
        context.assets.list("common/layouts").orEmpty().map { it.removeSuffix(".json") }.forEach { name ->
            val layout = requireNotNull(JsonLayoutLoader.loadLayout(context.assets, name, context))
            assertEquals(name, name !in uncased, LayoutMappingRepository.supportsAutoCapitalization(layout))
        }
    }

    @Test
    fun layoutsWithoutLetters_keepAutoCapitalization() {
        assertTrue(LayoutMappingRepository.supportsAutoCapitalization(emptyMap()))
        assertTrue(LayoutMappingRepository.supportsAutoCapitalization(
            mapOf(KeyEvent.KEYCODE_COMMA to LayoutMapping(",", ";"))
        ))
        assertFalse(LayoutMappingRepository.supportsAutoCapitalization(
            mapOf(KeyEvent.KEYCODE_COMMA to LayoutMapping(",", ";"), KeyEvent.KEYCODE_N to LayoutMapping("ن", "ن"))
        ))
    }

    @Test
    fun layoutAltOutputs_overrideBaseMappingsOnlyWhileActive() {
        val base = mapOf(KeyEvent.KEYCODE_N to ",", KeyEvent.KEYCODE_R to "3")
        try {
            LayoutMappingRepository.loadLayout(context.assets, "uyghur", context)
            assertEquals(mapOf(KeyEvent.KEYCODE_N to "،", KeyEvent.KEYCODE_R to "3"),
                LayoutMappingRepository.applyAltMappings(base).filterKeys { it in base })
            LayoutMappingRepository.loadLayout(context.assets, "qwerty", context)
            assertEquals(base, LayoutMappingRepository.applyAltMappings(base))
        } finally {
            LayoutMappingRepository.loadLayout(context.assets, "qwerty", context)
        }
    }
}
