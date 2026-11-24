package rocks.pkb.plektra
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import rocks.pkb.plektra.inputmethod.DeepLCredentials
@Composable internal fun DeepLSettingsDialog(onDismiss:()->Unit,onSaved:()->Unit) {
 val context=LocalContext.current
 val store=remember{DeepLCredentials(context)}
 val existing=remember{store.read()}
 var key by remember{mutableStateOf("")}
 val verifier=remember{rocks.pkb.plektra.inputmethod.DeepLAccountCheck()}
 var checking by remember{mutableStateOf(false)}
 var failure by remember{mutableStateOf<Int?>(null)}
 DisposableEffect(verifier){onDispose{verifier.close()}}
 var error by remember{mutableStateOf(false)}
 androidx.compose.ui.window.Dialog(onDismissRequest=onDismiss,
  properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=true)) {
  val dialogView=androidx.compose.ui.platform.LocalView.current
  val density=androidx.compose.ui.platform.LocalDensity.current
  var availableHeightPx by remember { mutableIntStateOf(context.resources.displayMetrics.heightPixels) }
  DisposableEffect(dialogView) {
   val window=(dialogView.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
   val previous=window?.attributes?.softInputMode
   window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
   window?.setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT,android.view.WindowManager.LayoutParams.MATCH_PARENT)
   window?.setGravity(android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL)
   val visibleFrame=android.graphics.Rect()
   val listener=android.view.ViewTreeObserver.OnGlobalLayoutListener {
    dialogView.getWindowVisibleDisplayFrame(visibleFrame)
    if(visibleFrame.height()>0)availableHeightPx=visibleFrame.height()
   }
   dialogView.viewTreeObserver.addOnGlobalLayoutListener(listener)
   onDispose {
    if(dialogView.viewTreeObserver.isAlive)dialogView.viewTreeObserver.removeOnGlobalLayoutListener(listener)
    if(previous!=null)window?.setSoftInputMode(previous)
   }
  }
  BoxWithConstraints(Modifier.fillMaxWidth().height(with(density){availableHeightPx.toDp()}).systemBarsPadding().padding(horizontal=16.dp,vertical=4.dp),contentAlignment=androidx.compose.ui.Alignment.Center) {
   val compact=maxHeight<200.dp
   Surface(modifier=Modifier.widthIn(max=560.dp).fillMaxWidth().heightIn(max=maxHeight),shape=MaterialTheme.shapes.extraLarge) {
    Column(Modifier.padding(horizontal=16.dp,vertical=if(compact)0.dp else 16.dp)) {
     if(!compact) Text(stringResource(R.string.translation_deepl_configure),style=MaterialTheme.typography.headlineSmall,modifier=Modifier.padding(bottom=12.dp))
     Column(Modifier.weight(1f,fill=false).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
   OutlinedTextField(value=key,onValueChange={key=it;error=false;failure=null},enabled=!checking,singleLine=true,visualTransformation=PasswordVisualTransformation(),
    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,autoCorrectEnabled=false),
    label={Text(stringResource(R.string.translation_deepl_key))},isError=error,modifier=Modifier.fillMaxWidth())
   Text(stringResource(R.string.translation_deepl_notice))
   TextButton(onClick={context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse("https://www.deepl.com/pro-api")))}) {Text(stringResource(R.string.translation_deepl_signup))}
   Text(stringResource(R.string.translation_deepl_offer),style=MaterialTheme.typography.bodySmall)

   if(existing!=null) Text(stringResource(R.string.translation_deepl_key_saved),style=MaterialTheme.typography.bodySmall)
   Text(stringResource(R.string.translation_deepl_auto_account),style=MaterialTheme.typography.bodySmall)
   Text(stringResource(R.string.translation_deepl_private),style=MaterialTheme.typography.bodySmall)
   if(error) Text(stringResource(R.string.translation_deepl_key_input_error),color=MaterialTheme.colorScheme.error)
   failure?.let{Text(stringResource(it),color=MaterialTheme.colorScheme.error)}
   if(existing!=null) TextButton(enabled=!checking,onClick={store.delete();onSaved()}) {Text(stringResource(R.string.translation_deepl_delete))}
     }
     Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End,verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
      TextButton(onClick=onDismiss){Text(stringResource(android.R.string.cancel))}
      TextButton(enabled=!checking&&(key.isNotBlank() || existing!=null),onClick={
  val value=key.trim().ifEmpty{existing!!.key}
  if(value.length !in 8..256 || value.any{it.code !in 33..126}){error=true;return@TextButton}
  checking=true;error=false;failure=null
  val preferred=if(key.isBlank())existing!!.free else value.endsWith(":fx")
  verifier.verify(DeepLCredentials.Entry(value,preferred),{verified->
   val saved=runCatching{store.write(verified.credential.key,verified.credential.free)}
   if(saved.isSuccess){
    verified.usage?.let{snapshot->runCatching{rocks.pkb.plektra.inputmethod.DeepLUsage(context).use{it.cacheVerified(verified.credential,snapshot)}}}
    onSaved()
   }else{checking=false;failure=R.string.translation_deepl_key_storage_error}
  },{message->checking=false;failure=message})
 }){Text(stringResource(if(checking)R.string.translation_deepl_checking else R.string.translation_deepl_save))}
     }
    }
   }
  }
 }
}
