package rocks.pkb.plektra
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import rocks.pkb.plektra.inputmethod.TranslationPunctuation

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun TranslationSettingsScreen(onBack:()->Unit){
 val context=LocalContext.current
 var punctuation by remember{mutableStateOf(SettingsManager.getPreferences(context).getBoolean(TranslationPunctuation.PREFERENCE,true))}
 Scaffold(topBar={TopAppBar(title={Text(stringResource(R.string.translation_settings_title))},navigationIcon={IconButton(onClick=onBack){Icon(Icons.AutoMirrored.Filled.ArrowBack,stringResource(R.string.settings_back_content_description))}})}){padding->
  Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
   Text(stringResource(R.string.translation_settings_intro))
   OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
    Text(stringResource(R.string.translation_settings_engine),style=MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.translation_settings_offline))
    Row(verticalAlignment=Alignment.CenterVertically){Text(stringResource(R.string.translation_settings_punctuation),Modifier.weight(1f));Switch(punctuation,{value->punctuation=value;SettingsManager.getPreferences(context).edit().putBoolean(TranslationPunctuation.PREFERENCE,value).apply()})}
   }}
  }
 }
}
