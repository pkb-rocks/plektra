package rocks.pkb.plektra
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import rocks.pkb.plektra.inputmethod.TranslationEngines
import rocks.pkb.plektra.inputmethod.ExpressiveTranslation
@Composable internal fun TranslationEngineSettingsCard(onChanged:()->Unit={}) {
    val context=LocalContext.current
    var engine by remember {mutableStateOf(TranslationEngines.selected(context))}
    var punctuation by remember {mutableStateOf(SettingsManager.getPreferences(context).getBoolean(rocks.pkb.plektra.inputmethod.TranslationPunctuation.PREFERENCE,true))}
    var configuringDeepL by remember {mutableStateOf(false)}
    var selectDeepLAfterSave by remember {mutableStateOf(false)}
    var credentialVersion by remember {mutableStateOf(0)}
    var expressive by remember {mutableStateOf(SettingsManager.getPreferences(context).getBoolean(ExpressiveTranslation.PREFERENCE,false))}
    OutlinedCard(Modifier.fillMaxWidth().padding(horizontal=16.dp)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.translation_engine_title),style=MaterialTheme.typography.titleMedium)
            listOf(TranslationEngines.GOOGLE to R.string.translation_engine_google,TranslationEngines.BERGAMOT to R.string.translation_engine_bergamot,TranslationEngines.DEEPL to R.string.translation_engine_deepl).forEach { (id,label) ->
                Row(Modifier.fillMaxWidth().clickable {if(id==TranslationEngines.DEEPL && rocks.pkb.plektra.inputmethod.DeepLCredentials(context).read()==null) {selectDeepLAfterSave=true;configuringDeepL=true}
                    else {engine=id;SettingsManager.getPreferences(context).edit().putString(TranslationEngines.PREFERENCE,id).apply();onChanged()}},verticalAlignment=Alignment.CenterVertically) {
                    RadioButton(selected=engine==id,onClick=null)
                    Text(stringResource(label),Modifier.padding(start=8.dp))
                }
            }
            OutlinedButton(onClick={selectDeepLAfterSave=false;configuringDeepL=true},modifier=Modifier.fillMaxWidth()) {Text(stringResource(R.string.translation_deepl_configure))}
            DeepLUsageControls(credentialVersion)
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text(stringResource(R.string.translation_expressive_title),Modifier.weight(1f))
                Switch(expressive,onCheckedChange={expressive=it;SettingsManager.getPreferences(context).edit().putBoolean(ExpressiveTranslation.PREFERENCE,it).apply()})
            }
            Text(stringResource(R.string.translation_expressive_hint),style=MaterialTheme.typography.bodySmall)
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text(stringResource(R.string.translation_punctuation_title),Modifier.weight(1f))
                Switch(punctuation,onCheckedChange={checked->punctuation=checked;SettingsManager.getPreferences(context).edit().putBoolean(rocks.pkb.plektra.inputmethod.TranslationPunctuation.PREFERENCE,checked).apply()})
            }
            Text(stringResource(R.string.translation_punctuation_hint),style=MaterialTheme.typography.bodySmall)
        }
    }
    if(configuringDeepL) DeepLSettingsDialog(onDismiss={configuringDeepL=false;selectDeepLAfterSave=false},onSaved={
        configuringDeepL=false
        credentialVersion++
        if(selectDeepLAfterSave && rocks.pkb.plektra.inputmethod.DeepLCredentials(context).read()!=null){
            engine=TranslationEngines.DEEPL
            SettingsManager.getPreferences(context).edit().putString(TranslationEngines.PREFERENCE,engine).apply();onChanged()
        }
        selectDeepLAfterSave=false
        if(engine==TranslationEngines.DEEPL && rocks.pkb.plektra.inputmethod.DeepLCredentials(context).read()==null) {
            engine=TranslationEngines.GOOGLE
            SettingsManager.getPreferences(context).edit().putString(TranslationEngines.PREFERENCE,engine).apply();onChanged()
        }
    })
}
