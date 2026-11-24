package rocks.pkb.plektra.inputmethod
import org.junit.Assert.*
import org.junit.Test
class TranslationPunctuationTest {
 @Test fun expressiveMarksSurviveNormalizationWithoutRestoringCommasOrChangingGrammar() {
  assertEquals("Yes!!",TranslationPunctuation.restore("Oui!!","Yes."))
  assertEquals("Really?! Yes!!",TranslationPunctuation.restore("Vraiment?! Oui!!","Really? Yes!"))
  assertEquals("Thanks…",TranslationPunctuation.restore("Merci…","Thanks..."))
  assertEquals("If you want, come.",TranslationPunctuation.restore("Si tu veux viens.","If you want, come."))
 }
 @Test fun punctuationStaysInsideClosingQuotesAndFrenchUsesOrdinaryNbsp() {
  assertEquals("“Yes!!”",TranslationPunctuation.restore("« Oui!! »","“Yes.”"))
  assertEquals("«\u00a0Oui\u00a0!!\u00a0»",TranslationPunctuation.typography("« Oui!! »","fr"))
  assertEquals("Why?!",TranslationPunctuation.typography("Why\u00a0?!","en"))
  assertEquals("https://example.org 12:30",TranslationPunctuation.typography("https://example.org 12:30","fr"))
 }
 @Test fun terminalPunctuationDoesNotDuplicateBeforeSpacedClosingQuote() {
  assertEquals("«\u00a0Oui\u00a0!!\u00a0»",TranslationPunctuation.typography(TranslationPunctuation.restore("« Oui!! »","« Oui. »"),"fr"))
 }

}
