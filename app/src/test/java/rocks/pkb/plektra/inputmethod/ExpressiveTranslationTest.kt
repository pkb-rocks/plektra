package rocks.pkb.plektra.inputmethod
import org.junit.Assert.*
import org.junit.Test

class ExpressiveTranslationTest {
 @Test fun elongatedYesRetainsEmphasisAndSourceExclamationCount() {
  val p=ExpressiveTranslation.prepare("Ouiiiiii!!","fr","en")
  assertEquals("Oui!!",p.input);assertEquals("Yeeeeees!!",p.restore("Yes!"))
 }
 @Test fun expressiveWordInsideSentenceChangesOnlyItsUniqueTranslatedWord() {
  val p=ExpressiveTranslation.prepare("Ouiiii, je viens demain.","fr","en")
  assertEquals("Oui, je viens demain.",p.input)
  assertEquals("Yeeees, I am coming tomorrow.",p.restore("Yes, I am coming tomorrow."))
 }
 @Test fun reverseAndCapitalsAndGreetingAreHandled() {
  assertEquals("OUIIII",ExpressiveTranslation.prepare("YEEEES","en","fr").restore("Oui"))
  assertEquals("Noooon!!",ExpressiveTranslation.prepare("Noooo!!","en","fr").restore("Non!"))
  assertEquals("Helloooo",ExpressiveTranslation.prepare("Saluuuut","fr","en").restore("Hello"))
 }
 @Test fun ordinaryWordsProperNamesAndUnsupportedLanguagesRemainUntouched() {
  for(text in listOf("Bonjour, oui, non et salut.","Hello!", "Bookkeeper", "boooon", "Noon", "Ouii", "oUIIII")) {
   val p=ExpressiveTranslation.prepare(text,"fr","en");assertEquals(text,p.input);assertEquals("A normal translation.",p.restore("A normal translation."))
  }
  val german=ExpressiveTranslation.prepare("Ouiiiii!!","fr","de");assertEquals("Ouiiiii!!",german.input);assertEquals("Ja!",german.restore("Ja!"))
 }
 @Test fun repeatedSourceOrAmbiguousTargetDoesNotGuessAlignment() {
  val ambiguous=ExpressiveTranslation.prepare("Ouiiii, oui.","fr","en");assertEquals("Ouiiii, oui.",ambiguous.input)
  assertEquals("Yes, yes.",ambiguous.restore("Yes, yes."))
  val target=ExpressiveTranslation.prepare("Ouiiii!","fr","en");assertEquals("Yes, yes!",target.restore("Yes, yes!"))
  val greeting=ExpressiveTranslation.prepare("Helloooo, hiiii!","en","fr");assertEquals("Helloooo, hiiii!",greeting.input)
 }
 @Test fun multipleDistinctExpressionsAndUnicodeNeighboursStaySeparate() {
  val p=ExpressiveTranslation.prepare("Ouiiii! Noooon!!","fr","en")
  assertEquals("Oui! Non!!",p.input);assertEquals("Yeeees! Noooo!!",p.restore("Yes! No!"))
  assertEquals("éouiinin",ExpressiveTranslation.prepare("éouiinin","fr","en").input)
 }
 @Test fun alreadyExpressiveModelOutputIsNotAmplifiedAgainAndExpansionIsBounded() {
  val p=ExpressiveTranslation.prepare("Ou"+"i".repeat(1000),"fr","en")
  assertEquals("Oui",p.input);assertEquals("Yeeees!",p.restore("Yeeees!"))
  assertEquals("Y"+"e".repeat(12)+"s",p.restore("Yes"))
 }
 @Test fun wrapperUsesSameConsentProviderAndCancellationContract() {
  val fake=object:TranslationEngine {
   override val isLibre=true;var input="";var pending:((String)->Unit)?=null;var closed=false;var downloads=0
   override fun available(done:(Boolean)->Unit,failed:()->Unit){done(true)}
   override fun download(wifiOnly:Boolean,done:()->Unit,failed:()->Unit){downloads++;assertTrue(wifiOnly);done()}
   override fun translate(text:String,done:(String)->Unit,failed:()->Unit){input=text;pending=done}
   override fun close(){closed=true}
  }
  val engine=ExpressiveTranslationEngine(fake,"fr","en");var value="";assertTrue(engine.isLibre)
  engine.download(true,{},{});engine.translate("Ouiiii!!",{value=it},{fail()});assertEquals("Oui!!",fake.input)
  fake.pending!!("Yes!");assertEquals("Yeeees!!",value)
  engine.close();assertTrue(fake.closed);fake.pending!!("No!");assertEquals("Yeeees!!",value)
  engine.download(true,{},{});assertEquals(1,fake.downloads)
 }

 @Test fun nonBreakingFrenchSpacesPreserveExpressivePunctuation() {
  for(space in listOf(" ","\u00a0","\u202f")) {
   val p=ExpressiveTranslation.prepare("Ouiiii"+space+"!!","fr","en")
   assertEquals("Yeeees!!",p.restore("Yes!"))
  }
  val p=ExpressiveTranslation.prepare("Yeeeees!!","en","fr")
  assertEquals("Ouiiiii\u00a0!!",p.restore("Oui\u00a0!"))
 }
 @Test fun consonantStuttersAreNotTreatedAsStretchedGreetings() {
  for(text in listOf("Sssalut!","Hhhello!","ouuuui!","Yyyyes!")) {
   val language=if(text.startsWith("S") || text.startsWith("o"))"fr" else "en"
   assertEquals(text,ExpressiveTranslation.prepare(text,language,if(language=="fr")"en" else "fr").input)
  }
 }

 @Test fun modelPeriodDoesNotEraseOriginalExpressivePunctuation() {
  assertEquals("Yeeeeees!!",ExpressiveTranslation.prepare("Ouiiiiii!!","fr","en").restore("Yes."))
  assertEquals("Hiiii!",ExpressiveTranslation.prepare("Saluuuut!","fr","en").restore("Hi."))
  assertEquals("Ouiiiii!!",ExpressiveTranslation.prepare("Yeeeees!!","en","fr").restore("Oui."))
 }
}
