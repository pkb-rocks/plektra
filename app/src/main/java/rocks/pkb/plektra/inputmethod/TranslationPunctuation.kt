package rocks.pkb.plektra.inputmethod

/** Preserve expressive marks only where their ordering is unambiguous. Grammar stays with the engine. */
internal object TranslationPunctuation {
 const val PREFERENCE="translation_preserve_punctuation"
 private val emphasis=Regex("[!?]{1,64}|…{1,16}|\\.{3,32}")
 private fun kind(value:String)=when { '?' in value->0;'!' in value->1;else->2 }
 fun restore(source:String,result:String):String {
  val original=emphasis.findAll(source).toList();val translated=emphasis.findAll(result).toList()
  var output=result
  if(original.size==translated.size && original.indices.all{kind(original[it].value)==kind(translated[it].value)}) {
   val text=StringBuilder(result)
   for(i in original.indices.reversed())text.replace(translated[i].range.first,translated[i].range.last+1,original[i].value)
   output=text.toString()
  }
  val last=original.lastOrNull() ?: return output
  val suffix=source.substring(last.range.last+1)
  if(suffix.any { !it.isWhitespace() && it !in "\"»”’')]}" })return output
  val tail=Regex("([.!?…]+)([ \u00a0\u202f]*[\"»”’')\\]\\}]*)(\\s*)$").find(output)
  if(tail!=null) return output.replaceRange(tail.range.first,tail.range.first+tail.groupValues[1].length,last.value)
  val closing=Regex("[\"»”’')\\]\\}]*\\s*$").find(output) ?: return output+last.value
  return output.substring(0,closing.range.first)+last.value+closing.value
 }
 fun typography(text:String,target:String):String = if(target=="fr") {
  text.replace(Regex("«[ \u00a0\u202f]*"),"«\u00a0")
   .replace(Regex("[ \u00a0\u202f]*»"),"\u00a0»")
   .replace(Regex("(?<=\\S)[ \u00a0\u202f]*([!?;:]+)(?=[\\s\u00a0\u202f]|$|[\"»”')\\]])"),"\u00a0$1")
 } else if(target=="en") text.replace(Regex("[ \u00a0\u202f]+([!?;:]+)"),"$1") else text

}

internal class PunctuationTranslationEngine(private val delegate:TranslationEngine,private val target:String=""):TranslationEngine {
 @Volatile private var closed=false
 override val isLibre get()=delegate.isLibre
 override val attribution get()=delegate.attribution
 override val requiresOnlineConsent get()=delegate.requiresOnlineConsent
 override val configurationIssue get()=delegate.configurationIssue
 override val failureMessage get()=delegate.failureMessage
 override fun available(done:(Boolean)->Unit,failed:()->Unit)=delegate.available({if(!closed)done(it)},{if(!closed)failed()})
 override fun download(wifiOnly:Boolean,done:()->Unit,failed:()->Unit)=delegate.download(wifiOnly,{if(!closed)done()},{if(!closed)failed()})
 override fun translate(text:String,done:(String)->Unit,failed:()->Unit) {
  if(closed)return
  val breaks=Regex("\\r\\n|\\r|\\n").findAll(text).toList()
  if(breaks.size>128){failed();return}
  val lines=mutableListOf<String>();var start=0
  for(separator in breaks){lines.add(text.substring(start,separator.range.first));start=separator.range.last+1}
  lines.add(text.substring(start))
  val inputs=lines.filterNot{it.isBlank()}.map{it.trim()}
  if(inputs.isEmpty()){done(text);return}
  delegate.translateBatch(inputs,{ translated ->
   if(!closed) {
    if(translated.size!=inputs.size){failed();return@translateBatch}
    val result=StringBuilder();var index=0
    for((position,original) in lines.withIndex()) {
     if(original.isBlank())result.append(original) else {
      result.append(original.takeWhile{it.isWhitespace()})
      result.append(TranslationPunctuation.typography(TranslationPunctuation.restore(original.trim(),translated[index++]),target))
      result.append(original.takeLastWhile{it.isWhitespace()})
     }
     breaks.getOrNull(position)?.let{result.append(it.value)}
    }
    if(result.length<=32000)done(result.toString()) else failed()
   }
  },{if(!closed)failed()},{closed})
 }
 override fun close(){closed=true;delegate.close()}
}
