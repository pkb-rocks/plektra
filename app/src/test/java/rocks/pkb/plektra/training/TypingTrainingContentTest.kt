package rocks.pkb.plektra.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TypingTrainingContentTest {

    private val assets get() = RuntimeEnvironment.getApplication().assets

    @Test
    fun everyOfferedContentTypeAndLanguageYieldsTargets() {
        val languages = TypingTrainingContent.languages(assets)
        assertTrue(languages.containsAll(listOf("de", "en", "fr", "ru", "vi", "gd")))
        for (language in languages) {
            val types = TypingTrainingContent.contentTypes(assets, language)
            for (type in types) {
                for (wordList in listOf("1k", "10k")) {
                    val plan = TypingTrainingContent.plan(assets, type, language, wordList, "A", seed = 42, maxTargets = 50)
                    assertTrue("$type/$language/$wordList", plan.targets.isNotEmpty())
                    assertEquals(plan.targets.size, plan.itemOfTarget.size)
                }
            }
        }
        assertEquals(
            listOf(TypingTrainingContent.WORDS, TypingTrainingContent.PHRASES, TypingTrainingContent.CHAT, TypingTrainingContent.TOKENS),
            TypingTrainingContent.contentTypes(assets, "de")
        )
    }

    @Test
    fun chatTargetsContainOnlyTheChosenSpeakersMessages() {
        val plan = TypingTrainingContent.plan(assets, TypingTrainingContent.CHAT, "de", "1k", "B", seed = 7, maxTargets = 50)
        val own = plan.items.filter { it.own }
        assertTrue(own.all { it.speaker == "B" })
        assertTrue(plan.items.any { !it.own })
        assertEquals(own.flatMap { it.text.split(' ').filter(String::isNotEmpty) }, plan.targets)
    }

    @Test
    fun sameSeedGivesSamePlan() {
        val a = TypingTrainingContent.plan(assets, TypingTrainingContent.PHRASES, "en", "1k", "A", seed = 9, maxTargets = 50)
        val b = TypingTrainingContent.plan(assets, TypingTrainingContent.PHRASES, "en", "1k", "A", seed = 9, maxTargets = 50)
        assertEquals(a.targets, b.targets)
    }
}
