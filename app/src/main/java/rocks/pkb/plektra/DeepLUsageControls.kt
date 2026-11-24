package rocks.pkb.plektra
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import rocks.pkb.plektra.inputmethod.DeepLUsage
import rocks.pkb.plektra.inputmethod.DeepLCredentials
@Composable internal fun DeepLUsageControls(version:Int) {
 val context=LocalContext.current
 val usage=remember(version){DeepLUsage(context)}
 var snapshot by remember(version){mutableStateOf(usage.cached())}
 var busy by remember(version){mutableStateOf(false)}
 var error by remember(version){mutableStateOf(false)}
 DisposableEffect(usage){onDispose{usage.close()}}
 Text(stringResource(R.string.translation_deepl_optional_hint),style=MaterialTheme.typography.bodySmall)
 if(remember(version){DeepLCredentials(context).read()!=null}) {
  Text(DeepLUsage.label(context,snapshot),style=MaterialTheme.typography.bodySmall)
  TextButton(enabled=!busy,onClick={busy=true;error=false;usage.refresh({snapshot=it;busy=false},{busy=false;error=true})}) {Text(stringResource(if(busy) R.string.translation_working else R.string.translation_deepl_refresh))}
  if(error) Text(stringResource(R.string.translation_deepl_usage_error),style=MaterialTheme.typography.bodySmall)
 }
}
