package rocks.pkb.plektra.inputmethod

import java.util.Locale

/** Deliberately small FR/EN lexicon. No guessing of sentence alignment or unknown words. */
internal object ExpressiveTranslation {
    const val PREFERENCE = "translation_preserve_expressive_tone"
    private data class Target(val word:String,val index:Int)
    private data class Rule(val source:String,val pattern:Regex,val lengths:List<Int>,val targets:List<Target>)
    private data class Match(val rule:Rule,val extra:Int,val capitals:Boolean)
    private val words=Regex("[\\p{L}\\p{M}]+")
    private val french=listOf(
        Rule("oui",Regex("(o+)(u+)(i+)"),listOf(1,1,1),listOf(Target("yes",1),Target("yeah",1),Target("yep",1))),
        Rule("non",Regex("(n+)(o+)(n+)"),listOf(1,1,1),listOf(Target("no",1))),
        Rule("salut",Regex("(s+)(a+)(l+)(u+)(t+)"),listOf(1,1,1,1,1),listOf(Target("hello",4),Target("hi",1)))
    )
    private val english=listOf(
        Rule("yes",Regex("(y+)(e+)(s+)"),listOf(1,1,1),listOf(Target("oui",2))),
        Rule("no",Regex("(n+)(o+)"),listOf(1,1),listOf(Target("non",1))),
        Rule("hi",Regex("(h+)(i+)"),listOf(1,1),listOf(Target("salut",3),Target("bonjour",5))),
        Rule("hello",Regex("(h+)(e+)(l{2,})(o+)"),listOf(1,1,2,1),listOf(Target("salut",3),Target("bonjour",5)))
    )
    internal class Plan internal constructor(val input:String,private val restoreText:(String)->String) {
        fun restore(translated:String)=restoreText(translated)
    }
    fun prepare(text:String,source:String,target:String):Plan {
        val rules=when(source to target){"fr" to "en"->french;"en" to "fr"->english;else->emptyList()}
        if(rules.isEmpty())return Plan(text){it}
        val tokens=words.findAll(text).toList()
        fun repeated(word:String,rule:Rule):Match? {
            val lower=word.lowercase(Locale.ROOT)
            val groups=rule.pattern.matchEntire(lower)?.groupValues?.drop(1) ?: return null
            val allowed=when(rule.source){"oui"->setOf(2);"non","yes"->setOf(1,2);"salut"->setOf(3,4);"no","hi"->setOf(1);"hello"->setOf(3);else->emptySet()}
            if(groups.indices.any {it !in allowed && groups[it].length!=rule.lengths[it]})return null
            val extra=allowed.maxOfOrNull {groups[it].length-rule.lengths[it]} ?: 0
            if(extra<2)return null
            val upper=word.uppercase(Locale.ROOT)
            val title=lower.replaceFirstChar {it.titlecase(Locale.ROOT)}
            if(word!=lower && word!=upper && word!=title)return null
            return Match(rule,extra.coerceAtMost(11),word==upper)
        }
        val candidates=tokens.mapNotNull { token -> rules.firstNotNullOfOrNull {repeated(token.value,it)}?.let {token to it} }
        // If an ordinary or elongated occurrence competes with this word, its target
        // position cannot be established safely without alignment data. Leave it alone.
        val unique=candidates.filter { (_,m) ->
            tokens.count {t->t.value.lowercase(Locale.ROOT)==m.rule.source || repeated(t.value,m.rule)!=null}==1
        }
        val safe=unique.filter { (_,m)-> unique.none { (_,other)->other!==m && other.rule.targets.any {a->m.rule.targets.any {b->a.word==b.word}}} }
        if(safe.isEmpty())return Plan(text){it}
        val normalized=StringBuilder(text)
        for((token,m) in safe.asReversed()) {
            val word=when {m.capitals->m.rule.source.uppercase(Locale.ROOT);token.value.first().isUpperCase()->m.rule.source.replaceFirstChar{it.titlecase(Locale.ROOT)};else->m.rule.source}
            normalized.replace(token.range.first,token.range.last+1,word)
        }
        return Plan(normalized.toString()) { translated ->
            val targetTokens=words.findAll(translated).toList()
            val replacements=safe.mapNotNull { (original,m) ->
                val matches=targetTokens.mapNotNull {t->m.rule.targets.firstOrNull {it.word==t.value.lowercase(Locale.ROOT)}?.let {t to it}}
                if(matches.size!=1)return@mapNotNull null
                val (token,t)=matches.single()
                val value=if(m.capitals)token.value.uppercase(Locale.ROOT) else token.value
                val index=t.index
                val stretched=value.take(index+1)+value[index].toString().repeat(m.extra)+value.drop(index+1)
                val emphatic=Regex("^[\\s\\p{Zs}]*([!?]{1,64})").find(text.substring(original.range.last+1))?.groupValues?.get(1)
                val tail=translated.substring(token.range.last+1)
                val punctuation=Regex("^([\\s\\p{Zs}]*)([.!?,;:]+)").find(tail)
                when {
                    emphatic!=null && punctuation!=null -> (token.range.first..(token.range.last+punctuation.value.length)) to (stretched+punctuation.groupValues[1]+emphatic)
                    emphatic!=null && tail.isBlank() -> token.range to (stretched+emphatic)
                    else -> token.range to stretched
                }
            }
            val result=StringBuilder(translated)
            for((range,value) in replacements.sortedByDescending {it.first.first})result.replace(range.first,range.last+1,value)
            result.toString().takeIf {it.length<=32000} ?: translated
        }
    }
}

/** Only the translation payload changes. Consent, engine attribution and cancellation stay delegated. */
internal class ExpressiveTranslationEngine(private val delegate:TranslationEngine,private val source:String,private val target:String):TranslationEngine {
    private var closed=false
    override val isLibre get()=delegate.isLibre
    override val attribution get()=delegate.attribution
    override val requiresOnlineConsent get()=delegate.requiresOnlineConsent
    override val configurationIssue get()=delegate.configurationIssue
    override val failureMessage get()=delegate.failureMessage
    override fun available(done:(Boolean)->Unit,failed:()->Unit){if(!closed)delegate.available({if(!closed)done(it)},{if(!closed)failed()})}
    override fun download(wifiOnly:Boolean,done:()->Unit,failed:()->Unit){if(!closed)delegate.download(wifiOnly,{if(!closed)done()},{if(!closed)failed()})}
    override fun translate(text:String,done:(String)->Unit,failed:()->Unit) {
        if(closed)return
        val plan=ExpressiveTranslation.prepare(text,source,target)
        delegate.translate(plan.input,{if(!closed)done(plan.restore(it))},{if(!closed)failed()})
    }
    override fun translateBatch(texts:List<String>,done:(List<String>)->Unit,failed:()->Unit,cancelled:()->Boolean) {
        if(closed || cancelled())return
        val plans=texts.map{ExpressiveTranslation.prepare(it,source,target)}
        delegate.translateBatch(plans.map{it.input},{ results ->
            if(!closed && !cancelled()) {
                if(results.size!=plans.size)failed() else done(results.mapIndexed{index,result->plans[index].restore(result)})
            }
        },{if(!closed && !cancelled())failed()},{closed || cancelled()})
    }
    override fun close(){closed=true;delegate.close()}
}
