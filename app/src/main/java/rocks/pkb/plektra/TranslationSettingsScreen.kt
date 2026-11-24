package rocks.pkb.plektra
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun TranslationSettingsScreen(onBack:()->Unit) {
 val context=LocalContext.current
 Scaffold(topBar={TopAppBar(title={Text(stringResource(R.string.translation_settings_title),maxLines=2,overflow=TextOverflow.Ellipsis)},navigationIcon={IconButton(onClick=onBack){Icon(Icons.AutoMirrored.Filled.ArrowBack,stringResource(R.string.settings_back_content_description))}})}){padding->
  LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(vertical=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
   item{Text(stringResource(R.string.translation_settings_intro),Modifier.padding(horizontal=16.dp),style=MaterialTheme.typography.bodyMedium)}
   item{TranslationEngineSettingsCard()}
   item{TextButton(onClick={openSettingsPage(context,SettingsPage(SettingsDestination.Customization,SettingsActivity.CUSTOMIZATION_DESTINATION_STATUS_BAR_BUTTONS))},modifier=Modifier.padding(horizontal=16.dp)){Text(stringResource(R.string.translation_settings_buttons))}}
  }
 }
}
