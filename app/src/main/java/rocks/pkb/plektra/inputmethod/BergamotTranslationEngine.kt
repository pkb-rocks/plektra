package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import org.json.JSONArray
import java.io.*
import java.net.*
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream

internal object TranslationEngines {
    const val PREFERENCE = "translation_engine"
    const val GOOGLE = "google"
    const val BERGAMOT = "bergamot"
    const val DEEPL = "deepl"
    fun selected(context: Context) = rocks.pkb.plektra.SettingsManager.getPreferences(context)
        .getString(PREFERENCE, GOOGLE).takeIf { it == BERGAMOT || it == DEEPL } ?: GOOGLE
    fun create(context: Context, source: String, target: String): TranslationEngine {
        val engine=when(selected(context)) {
            BERGAMOT->BergamotTranslationEngine(context,source,target)
            DEEPL->DeepLTranslationEngine(context,source,target)
            else->LocalTranslationEngine(source,target)
        }
        return decorate(context,engine,source,target)
    }
    fun createDeepL(context:Context,source:String,target:String):TranslationEngine = decorate(context,DeepLTranslationEngine(context,source,target),source,target)
    private fun decorate(context:Context,engine:TranslationEngine,source:String,target:String):TranslationEngine {
        val expressive=if(rocks.pkb.plektra.SettingsManager.getPreferences(context).getBoolean(ExpressiveTranslation.PREFERENCE,false))
            ExpressiveTranslationEngine(engine,source,target) else engine
        return if(rocks.pkb.plektra.SettingsManager.getPreferences(context).getBoolean(TranslationPunctuation.PREFERENCE,true)) PunctuationTranslationEngine(expressive,target) else expressive
    }
}

/** A pair is published only after each decompressed file passes its fixed size and SHA-256. */
internal class BergamotModelStore(private val context: Context) {
    data class FileSpec(val name: String,val url: String,val size: Long,val sha: String)
    private val root = File(context.noBackupFilesDir,"bergamot-v1")
    fun specs(source: String,target: String): List<FileSpec> {
        require(setOf(source,target) == setOf("fr","en"))
        val data=JSONObject(context.assets.open("bergamot/models.json").bufferedReader().use {it.readText()}).getJSONObject(source+target)
        return data.keys().asSequence().map { key ->
            val f=data.getJSONObject(key);FileSpec(f.getString("name").substringAfterLast('/'),f.getString("name"),f.getLong("size"),f.getString("expectedSha256Hash"))
        }.toList()
    }
    fun file(source:String,target:String,name:String):File {
        require(name.matches(Regex("[a-zA-Z0-9._-]+")))
        return File(File(root,source+target),name)
    }
    fun valid(file:File,spec:FileSpec):Boolean = file.isFile && file.length()==spec.size &&
        file.inputStream().use { hash(it) } == spec.sha
    private fun hash(input:InputStream):String {
        val digest=MessageDigest.getInstance("SHA-256");val b=ByteArray(65536)
        while(true){val n=input.read(b);if(n<0)break;digest.update(b,0,n)}
        return digest.digest().joinToString(""){"%02x".format(it)}
    }
    fun install(source:String,target:String,spec:FileSpec,input:InputStream,alive:()->Boolean) {
        val destination=file(source,target,spec.name);destination.parentFile!!.mkdirs()
        val temp=File.createTempFile("download-",".part",destination.parentFile)
        try {
            val peek=PushbackInputStream(BufferedInputStream(input),2)
            val first=peek.read();val second=peek.read();if(second>=0)peek.unread(second);if(first>=0)peek.unread(first)
            val decoded=if(first==31 && second==139) GZIPInputStream(peek) else peek
            decoded.use { src -> temp.outputStream().use { dst ->
                val b=ByteArray(65536);var count=0L
                while(true){check(alive());val n=src.read(b);if(n<0)break;count+=n;require(count<=spec.size);dst.write(b,0,n)}
            } }
            check(alive());require(valid(temp,spec));java.nio.file.Files.move(temp.toPath(),destination.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }
}

internal class BergamotTranslationEngine(private val context:Context,private val source:String,private val target:String):TranslationEngine {
    override val isLibre get()=true
    private val main=Handler(Looper.getMainLooper())
    private val io=Executors.newSingleThreadExecutor()
    private val clients=Executors.newCachedThreadPool()
    private val store=BergamotModelStore(context)
    @Volatile private var closed=false
    private var server:ServerSocket?=null
    private var web:WebView?=null
    private var success:((String)->Unit)?=null
    private var failure:(()->Unit)?=null
    private var timeout:Runnable?=null
    private fun ui(action:()->Unit){main.post {if(!closed)action()}}
    private fun background(failed:()->Unit,action:()->Unit){io.execute {try{action()}catch(_:Exception){ui(failed)}}}
    override fun available(done:(Boolean)->Unit,failed:()->Unit) = background(failed) {
        val ready=store.specs(source,target).all {store.valid(store.file(source,target,it.name),it)}
        ui {done(ready)}
    }
    override fun download(wifiOnly:Boolean,done:()->Unit,failed:()->Unit) = background(failed) {
        if(wifiOnly) {
            val manager=context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps=manager.getNetworkCapabilities(manager.activeNetwork)
            check(caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true)
        }
        for(spec in store.specs(source,target)) {
            if(closed)return@background
            if(store.valid(store.file(source,target,spec.name),spec))continue
            val connection=URL(spec.url).openConnection() as HttpURLConnection
            connection.connectTimeout=20000;connection.readTimeout=30000
            connection.setRequestProperty("Accept-Encoding","identity")
            try {require(connection.responseCode==200);store.install(source,target,spec,connection.inputStream){!closed}}
            finally {connection.disconnect()}
        }
        ui(done)
    }
    override fun translate(text:String,done:(String)->Unit,failed:()->Unit) {
        if(closed)return
        translateBatch(listOf(text),{done(it.single())},failed)
    }
    override fun translateBatch(texts:List<String>,done:(List<String>)->Unit,failed:()->Unit,cancelled:()->Boolean) {
        if(closed || cancelled())return
        if(texts.isEmpty()){done(emptyList());return}
        if(texts.size>129 || texts.sumOf{it.length}>8000){failed();return}
        val payload=JSONArray(texts).toString()
        success={ value ->
            if(!cancelled()) {
                val results=runCatching { val array=JSONArray(value);List(array.length()){array.getString(it)} }.getOrNull()
                if(results==null || results.size!=texts.size || results.any{it.isBlank()} || results.sumOf{it.length}>32000)failed() else done(results)
            }
        };failure={if(!cancelled())failed()}
        try {
            server=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
            val socket=server!!;clients.execute {while(!closed)try{val c=socket.accept();clients.execute {serve(c)}}catch(_:Exception){break}}
            val origin="http://127.0.0.1:${socket.localPort}"
            web=WebView(context).apply {
                settings.javaScriptEnabled=true;settings.allowFileAccess=false;settings.allowContentAccess=false
                addJavascriptInterface(object {
                    @JavascriptInterface fun ready(){ui {web?.evaluateJavascript("runTranslations(${JSONObject.quote(source)},${JSONObject.quote(target)},$payload)",null)}}
                    @JavascriptInterface fun result(value:String){ui {finish(value)}}
                    @JavascriptInterface fun error(){ui {finish(null)}}
                },"Bergamot")
                webViewClient=object:WebViewClient(){override fun shouldOverrideUrlLoading(v:WebView,r:android.webkit.WebResourceRequest)= !r.url.toString().startsWith(origin+"/")}
                loadUrl("$origin/engine.html")
            }
            timeout=Runnable {finish(null)}.also {main.postDelayed(it,60000)}
        } catch(_:Exception){finish(null)}
    }
    private fun finish(value:String?) {
        if(closed)return
        val done=success;val failed=failure;success=null;failure=null
        timeout?.let(main::removeCallbacks);timeout=null
        web?.destroy();web=null;try{server?.close()}catch(_:Exception){};server=null
        if(value.isNullOrBlank() || value.length>200000)failed?.invoke() else done?.invoke(value)
    }
    private fun serve(socket:Socket) {try{socket.use { c ->
        c.soTimeout=15000
        val r=c.getInputStream().bufferedReader();val first=r.readLine() ?: return
        val path=first.substringAfter("GET ","").substringBefore(' ')
        require(path.matches(Regex("/[a-zA-Z0-9._/-]+")) && !path.contains(".."))
        var line:String?;do{line=r.readLine()}while(line!=null && line.isNotEmpty())
        val bytes=when {
            path=="/registry.json" -> {
                val data=JSONObject(context.assets.open("bergamot/models.json").bufferedReader().use{it.readText()})
                val pair=source+target;val item=data.getJSONObject(pair)
                for(key in item.keys()){val f=item.getJSONObject(key);f.put("name","http://127.0.0.1:${c.localPort}/models/"+f.getString("name").substringAfterLast('/'))}
                JSONObject().put(pair,item).toString().toByteArray()
            }
            path.startsWith("/models/") -> store.file(source,target,path.removePrefix("/models/")).readBytes()
            else -> context.assets.open("bergamot"+path).use{it.readBytes()}
        }
        val mime=when {path.endsWith(".wasm")->"application/wasm";path.endsWith(".js")->"text/javascript";path.endsWith(".html")->"text/html; charset=utf-8";else->"application/json"}
        val out=c.getOutputStream()
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nContent-Length: ${bytes.size}\r\nContent-Security-Policy: default-src 'self'; script-src 'self' 'unsafe-inline' 'wasm-unsafe-eval'; connect-src 'self'; worker-src 'self'\r\nConnection: close\r\n\r\n").toByteArray());out.write(bytes);out.flush()
    }}catch(_:Exception){} }
    override fun close() {
        closed=true;success=null;failure=null;timeout?.let(main::removeCallbacks)
        web?.destroy();web=null;try{server?.close()}catch(_:Exception){}
        io.shutdownNow();clients.shutdownNow()
    }
}
