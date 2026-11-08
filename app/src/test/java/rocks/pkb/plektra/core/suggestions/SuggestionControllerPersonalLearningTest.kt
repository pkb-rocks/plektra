package rocks.pkb.plektra.core.suggestions

import android.view.KeyEvent
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rocks.pkb.plektra.backup.LearningOperationGate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
class SuggestionControllerPersonalLearningTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private lateinit var store:WordUsageStore
    private val controllers=mutableListOf<SuggestionController>()
    private var enabled=true
    private var suggestions=true
    private val repository=FakeDictionaryRepository().apply {isReady=true;addTestEntry("planet",100);addTestEntry("planer",100)}
    @Before fun setup() {store=WordUsageStore(context);store.clearAll()}
    @After fun cleanup() {controllers.forEach {it.destroy()};store.clearAll();store.close()}
    private fun controller(words:WordUsageRepository=store)=SuggestionController(context,context.assets,
        settingsProvider={SuggestionSettings(textReplacementsEnabled=false,suggestionsEnabled=suggestions)},
        isEnabled={enabled},onSuggestionsUpdated={},currentLocale=Locale.ENGLISH,
        dictionaryRepositoryFactory={_,_,_,_,_->repository},
        nextWordPredictorOverride=NextWordPredictor(Mockito.mock(UserNGramRepository::class.java),asyncLearning=false),
        wordUsageRepositoryOverride=words).also {controllers.add(it)}
    private fun type(controller:SuggestionController,word:String) {word.forEach {controller.onCharacterCommitted(it.toString(),null)}}
    private fun space(controller:SuggestionController) {controller.onBoundaryKey(KeyEvent.KEYCODE_SPACE,null,null)}

    @Test fun completedTypedWordIsLearnedOnceAndRepeatedEmptyBoundariesDoNotDuplicateIt() {
        val c=controller();type(c,"planet");space(c);space(c)
        assertEquals(1,store.getExactUsage(Locale.ENGLISH,"planet")!!.count)
        assertEquals(100,repository.getExactWordFrequency("planet"))
    }
    @Test fun disabledSuggestionSettingDoesNotLearn() {
        suggestions=false;val c=controller();type(c,"planet");space(c)
        assertNull(store.getExactUsage(Locale.ENGLISH,"planet"))
    }
    @Test fun disabledInputContextDoesNotLearnEvenIfTrackerWasPopulatedBeforeTheContextChanged() {
        val c=controller();type(c,"planet");enabled=false;space(c)
        assertNull(store.getExactUsage(Locale.ENGLISH,"planet"))
    }
    @Test fun backupOrResetSuspensionRejectsNewLearning()=runBlocking {
        val c=controller();type(c,"planet")
        LearningOperationGate.withExclusiveOperation {space(c)}
        assertNull(store.getExactUsage(Locale.ENGLISH,"planet"))
    }
    @Test fun localeChangesKeepPersonalCountsIndependent() {
        val c=controller();type(c,"planet");space(c)
        c.updateLocale(Locale.FRENCH);type(c,"planet");space(c)
        assertEquals(1,store.getExactUsage(Locale.ENGLISH,"planet")!!.count)
        assertEquals(1,store.getExactUsage(Locale.FRENCH,"planet")!!.count)
    }
    @Test fun forgettingAWordRemovesItsPersonalBoostWithoutChangingTheDictionary() {
        val c=controller();type(c,"planet");space(c);c.dismissSuggestion("planet")
        assertNull(store.getExactUsage(Locale.ENGLISH,"planet"))
        assertEquals(100,repository.getExactWordFrequency("planet"))
    }

    @Test fun learningFailureDoesNotAbortTheBoundaryOrTheNextWord() {
        val failing=object:WordUsageRepository by store {
            override fun learn(locale:Locale,word:String,nowMs:Long) {throw IllegalStateException("synthetic write failure")}
        }
        val c=controller(failing);type(c,"planet")
        val result=c.onBoundaryKey(KeyEvent.KEYCODE_SPACE,null,null)
        assertFalse(result.committed)
        type(c,"planer");space(c)
        assertNull(store.getExactUsage(Locale.ENGLISH,"planet"))
    }

    private fun editor(text:String,cursor:Int=text.length):android.view.inputmethod.BaseInputConnection {
        val contents=android.text.SpannableStringBuilder(text)
        android.text.Selection.setSelection(contents,cursor)
        return object:android.view.inputmethod.BaseInputConnection(android.view.View(context),true) {
            override fun getEditable():android.text.Editable=contents
        }
    }
    @Test fun acceptedWordLearnsActualCommittedTextOnceAndClearsItsOldPrefix() {
        val c=controller();type(c,"pla")
        c.onSuggestionCommitted(editor("planet "))
        space(c)
        assertEquals(1,store.getExactUsage(Locale.ENGLISH,"planet")!!.count)
        assertNull(store.getExactUsage(Locale.ENGLISH,"pla"))
    }
    @Test fun acceptanceWithoutAnInsertedBoundaryWaitsForTheNextBoundary() {
        val c=controller();type(c,"pla")
        val input=editor("planet ",6)
        c.onSuggestionCommitted(input)
        assertNull(store.getExactUsage(Locale.ENGLISH,"planet"))
        c.readInitialContext(input);space(c)
        assertEquals(1,store.getExactUsage(Locale.ENGLISH,"planet")!!.count)
        assertNull(store.getExactUsage(Locale.ENGLISH,"pla"))
    }
    @Test fun excludedContextDoesNotReadAcceptedText() {
        val c=controller();type(c,"pla");enabled=false
        val input=object:android.view.inputmethod.BaseInputConnection(android.view.View(context),true) {
            override fun getTextBeforeCursor(length:Int,flags:Int):CharSequence {throw AssertionError("Excluded editor must not be read")}
        }
        c.onSuggestionCommitted(input)
        assertNull(store.getExactUsage(Locale.ENGLISH,"pla"))
    }
    @Test fun acceptedTruncatedLongWordIsNotLearnedAsItsShorterTail() {
        val c=controller();c.onSuggestionCommitted(editor("a".repeat(200)+" "))
        assertNull(store.getExactUsage(Locale.ENGLISH,"a".repeat(48)))
    }
    @Test fun unusedAndDisabledControllerDoesNotCreateAPersistentLearningStore() {
        PersonalWordLearning.closeForTests(context)
        val field=PersonalWordLearning.javaClass.getDeclaredField("stores").apply {isAccessible=true}
        val registered=field.get(PersonalWordLearning) as Map<*,*>
        val before=registered.size
        val c=SuggestionController(context,context.assets,
            settingsProvider={SuggestionSettings(suggestionsEnabled=false)},isEnabled={false},
            onSuggestionsUpdated={},currentLocale=Locale.ENGLISH,
            dictionaryRepositoryFactory={_,_,_,_,_->repository},
            nextWordPredictorOverride=NextWordPredictor(Mockito.mock(UserNGramRepository::class.java),asyncLearning=false))
        try {
            c.onCharacterCommitted("synthetic",null)
            c.onBoundaryKey(KeyEvent.KEYCODE_SPACE,null,null)
            assertEquals(before,registered.size)
        } finally {c.destroy()}
    }
}
