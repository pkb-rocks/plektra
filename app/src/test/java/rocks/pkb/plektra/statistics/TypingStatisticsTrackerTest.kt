package rocks.pkb.plektra.statistics

import org.junit.Assert.assertEquals
import org.junit.Test

class TypingStatisticsTrackerTest {
    private var manual = 0
    private var suggested = 0
    private val tracker = TypingStatisticsTracker { m, s -> manual += m; suggested += s }

    @Test fun boundaryCountsManualWordOnlyOnce() {
        tracker.onTextCommitted("Bonjour")
        repeat(3) { tracker.finishWord() }
        assertEquals(1, manual)
        assertEquals(0, suggested)
    }

    @Test fun partialWordCompletedBySuggestionIsNotCountedManually() {
        tracker.onTextCommitted("Bon")
        tracker.onSuggestionCommitted("Bonjour")
        tracker.finishWord()
        assertEquals(0, manual)
        assertEquals(1, suggested)
    }

    @Test fun multiwordSuggestionCountsWordsRatherThanGestures() {
        tracker.onTextCommitted("Je vais ")
        tracker.onTextCommitted("à")
        tracker.onSuggestionCommitted("à la plage")
        tracker.onTextCommitted(" ")
        tracker.finishWord()
        assertEquals(2, manual)
        assertEquals(3, suggested)
        assertEquals(5, manual + suggested)
    }

    @Test fun deletingEntireUnfinishedWordDoesNotCountIt() {
        tracker.onTextCommitted("mot")
        repeat(3) { tracker.onBackspace() }
        tracker.finishWord()
        assertEquals(0, manual + suggested)
    }

    @Test fun correctionWithinPendingWordStillCountsOneWord() {
        tracker.onTextCommitted("clavieq")
        tracker.onBackspace()
        tracker.onTextCommitted("r")
        tracker.finishWord()
        assertEquals(1, manual)
    }

    @Test fun multitapReplacementDoesNotSplitWord() {
        tracker.onTextCommitted("cafe", "cafe", "")
        tracker.onTextCommitted("\bÉ", "cafÉ", "")
        tracker.finishWord()
        assertEquals(1, manual)
    }

    @Test fun punctuationSeparatesWordsButNotInternalApostrophesOrHyphens() {
        tracker.onTextCommitted("l’été, porte-monnaie! 11 heures.")
        assertEquals(4, manual)
        tracker.onSuggestionCommitted("l’été porte-monnaie, 11 heures!")
        assertEquals(4, suggested)
    }

    @Test fun symbolsAndEmptySuggestionsAreNotWords() {
        tracker.onTextCommitted("😀 !!! -- ")
        tracker.onSuggestionCommitted("😀 !")
        tracker.finishWord()
        assertEquals(0, manual + suggested)
    }

    @Test fun editingExistingWordIsNotAnotherNewManualWord() {
        tracker.onTextCommitted("r", "clavier", "")
        tracker.finishWord()
        tracker.onTextCommitted("x", "ax", "bc")
        tracker.finishWord()
        assertEquals(0, manual)
    }

    @Test fun apostropheNotifiedBeforeEditorCommitDoesNotDoubleCountCompletion() {
        tracker.onTextCommitted("l", "l", "")
        tracker.onTextCommitted("'", "l", "")
        tracker.onTextCommitted("é", "l’é", "")
        tracker.onSuggestionCommitted("l’été")
        tracker.finishWord()
        assertEquals(0, manual)
        assertEquals(1, suggested)
    }

    @Test fun movingToAnotherNewWordFinishesPendingWord() {
        tracker.onTextCommitted("a", "a", "")
        tracker.onTextCommitted("b", "b", "")
        tracker.finishWord()
        assertEquals(2, manual)
    }

    @Test fun boundedEditorContextDoesNotSplitLongWord() {
        var text = ""
        repeat(100) {
            text += "a"
            tracker.onTextCommitted("a", text.takeLast(48), "", contextLimit = 48)
        }
        tracker.finishWord()
        assertEquals(1, manual)
    }

    @Test fun repeatedDeletionAfterCommittedWordDoesNotDecreaseCumulativeCount() {
        tracker.onTextCommitted("mot ")
        repeat(4) { tracker.onBackspace() }
        tracker.finishWord()
        assertEquals(1, manual)
    }
    @Test fun fieldEntryIntoExistingWordDoesNotCountAnEditAsANewWord() {
        tracker.onEditorStarted(hasExistingWord = true)
        tracker.onTextCommitted("s")
        tracker.finishWord()
        tracker.onTextCommitted("new ")
        assertEquals(1, manual)
    }

    @Test fun echoedOwnWordDoesNotFinishItBeforeSuggestionAcceptance() {
        tracker.onTextCommitted("hel")
        tracker.onEditorWordObserved("hel")
        tracker.onSuggestionCommitted("hello")
        assertEquals(0, manual)
        assertEquals(1, suggested)
    }

    @Test fun observedCursorNavigationFinishesNewWordAndMarksExistingTarget() {
        tracker.onTextCommitted("new")
        tracker.onEditorWordObserved("existing")
        tracker.onTextCommitted("s")
        tracker.finishWord()
        assertEquals(1, manual)
    }

    @Test fun boundedObservationOfLongPendingWordDoesNotSplitIt() {
        tracker.onTextCommitted("a".repeat(100))
        tracker.onEditorWordObserved("a".repeat(48))
        tracker.finishWord()
        assertEquals(1, manual)
    }

}
