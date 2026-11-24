package rocks.pkb.plektra.inputmethod
import org.junit.Assert.*
import org.junit.Test

class TranslationLanguageSearchTest {
 @Test fun prefixIgnoresCaseAndAccents() {
  assertTrue(TranslationLanguageSearch.matches("FRANC","Français"))
  assertTrue(TranslationLanguageSearch.matches("ell","Éllinika"))
  assertTrue(TranslationLanguageSearch.matches("ελλην","Ελληνικά"))
 }
 @Test fun localizedNativeEnglishNamesAndCodesCanBeMatched() {
  assertTrue(TranslationLanguageSearch.matches("germ","Allemand","German","Deutsch","de"))
  assertTrue(TranslationLanguageSearch.matches("deu","Allemand","German","Deutsch","de"))
  assertTrue(TranslationLanguageSearch.matches("de","Allemand","German","Deutsch","de"))
  assertTrue(TranslationLanguageSearch.matches("日本","Japanese","日本語","ja"))
 }
 @Test fun matchesPrefixesWithoutAccidentalSubstrings() {
  assertFalse(TranslationLanguageSearch.matches("eng","Bengali","bn"))
  assertFalse(TranslationLanguageSearch.matches("zzzz","English","en"))
  assertTrue(TranslationLanguageSearch.matches("","English"))
 }
 @Test fun rowBelowViewportScrollsVerticallyUntilItsBottomIsVisible() {
  assertEquals(560,TranslationLanguageSearch.visibleOffset(700,760,0,200))
 }
 @Test fun visibleRowsStayPutAndReverseNavigationScrollsBackUp() {
  assertEquals(300,TranslationLanguageSearch.visibleOffset(320,380,300,200))
  assertEquals(40,TranslationLanguageSearch.visibleOffset(40,100,300,200))
  assertEquals(300,TranslationLanguageSearch.visibleOffset(320,380,300,0))
 }
}
