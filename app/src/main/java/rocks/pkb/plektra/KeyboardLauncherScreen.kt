package rocks.pkb.plektra

import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import android.view.KeyEvent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import rocks.pkb.plektra.commands.*
import rocks.pkb.plektra.inputmethod.ClicksLauncherButtonAccessibilityService

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun KeyboardLauncherScreen(onBack:()->Unit) {
    val context=LocalContext.current
    val prefs=remember(context){SettingsManager.getPreferences(context)}
    var enabled by remember {mutableStateOf(KeyboardLauncherStore.enabled(context))}
    var trigger by remember {mutableStateOf(KeyboardLauncherStore.trigger(context))}
    var delay by remember {mutableIntStateOf(KeyboardLauncherStore.delay(context))}
    var assignments by remember {mutableStateOf(KeyboardLauncherStore.read(context))}
    var selected by remember {mutableStateOf<Int?>(null)}
    var longView by remember {mutableStateOf(false)}
    // Navigation remains configured on the official Nav screen.
    val commands=remember {CommandRegistry(context).getCommands(CommandSurface.AssignedKey).filter {it.source!=CommandSourceId.NavActions && CommandCapability.RequiresEditableText !in it.capabilities}}
    val names=commands.associateBy {it.id}
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick=onBack){Text("‹")}
            Text(stringResource(R.string.keyboard_launcher_title),style=MaterialTheme.typography.headlineSmall,modifier=Modifier.weight(1f))
        }
        OutlinedCard {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().settingRow("personal_launcher.enabled"),verticalAlignment=Alignment.CenterVertically) {
                    Text(stringResource(R.string.keyboard_launcher_title),modifier=Modifier.weight(1f))
                    Switch(checked=enabled,onCheckedChange={enabled=it;KeyboardLauncherBridge.cancel();prefs.edit().putBoolean(KeyboardLauncherStore.ENABLED,it).apply()})
                }
                Text(stringResource(R.string.keyboard_launcher_hint),style=MaterialTheme.typography.bodyMedium)
                Column(Modifier.settingRow("personal_launcher.trigger")) {
                    listOf(KeyEvent.KEYCODE_SHIFT_LEFT to R.string.keyboard_launcher_left_shift,KeyEvent.KEYCODE_SHIFT_RIGHT to R.string.keyboard_launcher_right_shift,KeyEvent.KEYCODE_CTRL_LEFT to R.string.keyboard_launcher_fn).forEach {(code,label)->
                        fun choose(){KeyboardLauncherBridge.cancel();trigger=code;prefs.edit().putInt(KeyboardLauncherStore.TRIGGER,code).apply()}
                        Row(Modifier.fillMaxWidth().clickable(enabled=enabled){choose()},verticalAlignment=Alignment.CenterVertically) {
                            RadioButton(selected=trigger==code,onClick={choose()},enabled=enabled)
                            Text(stringResource(label))
                        }
                    }
                }
                Column(Modifier.settingRow("personal_launcher.delay")) {
                    Text(stringResource(R.string.keyboard_launcher_delay,delay))
                    Slider(value=delay.toFloat(),onValueChange={delay=(it/50).toInt()*50},onValueChangeFinished={KeyboardLauncherBridge.cancel();prefs.edit().putInt(KeyboardLauncherStore.DELAY,delay).apply()},valueRange=250f..800f,steps=10,enabled=enabled)
                }
            }
        }
        if(BuildConfig.ENABLE_ACCESSIBILITY_SERVICE) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.keyboard_launcher_access_title),style=MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.keyboard_launcher_access_hint),style=MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick={context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))}) {Text(stringResource(R.string.keyboard_launcher_access_title))}
                }
            }
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(false,true).forEachIndexed {i,long ->SegmentedButton(selected=longView==long,onClick={longView=long},shape=SegmentedButtonDefaults.itemShape(i,2)) {Text(stringResource(if(long)R.string.keyboard_launcher_long else R.string.keyboard_launcher_short))}}
        }
        Column(Modifier.settingRow("personal_launcher.assignments"),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("QWERTYUIOP","ASDFGHJKL","ZXCVBNM").forEach {row ->
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    row.forEach {letter ->
                        val key=KeyEvent.KEYCODE_A+(letter-'A')
                        val mapping=assignments[key]?.let {if(longView)it.longPress else it}
                        OutlinedCard(onClick={selected=key},modifier=Modifier.weight(1f)) {
                            Column(Modifier.fillMaxWidth().padding(vertical=8.dp,horizontal=2.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                                Text(letter.toString(),style=MaterialTheme.typography.titleMedium)
                                Text(mapping?.let {names[it.value]?.label} ?: "—",maxLines=2,overflow=TextOverflow.Ellipsis,textAlign=TextAlign.Center,style=MaterialTheme.typography.labelSmall,modifier=Modifier.heightIn(min=32.dp))
                            }
                        }
                    }
                }
            }
        }
    }
    selected?.let {key ->
        LauncherAssignmentDialog(key,assignments[key] ?: LauncherMapping(""),longView,commands,onDismiss={selected=null}) {mapping ->
            KeyboardLauncherBridge.cancel()
            assignments=assignments+(key to mapping)
            KeyboardLauncherStore.save(context,assignments)
            selected=null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun LauncherAssignmentDialog(key:Int,initial:LauncherMapping,longInitially:Boolean,commands:List<CommandTarget>,onDismiss:()->Unit,onSave:(LauncherMapping)->Unit) {
    var short by remember {mutableStateOf(initial.value)}
    var held by remember {mutableStateOf(initial.longPress?.value ?: "")}
    var long by remember {mutableStateOf(longInitially)}
    var query by remember {mutableStateOf("")}
    val value=if(long)held else short
    val selected=commands.firstOrNull {it.id==value}
    val results=remember(commands,query) {commands.filter {command -> (listOf(command.label,command.subtitle ?: "")+command.searchTokens).any {it.contains(query.trim(),ignoreCase=true)}}}
    LauncherDialogFrame(onDismiss=onDismiss) {
        Surface(Modifier.widthIn(max=560.dp).fillMaxWidth().heightIn(max=maxHeight),shape=MaterialTheme.shapes.extraLarge,color=MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.nav_mode_configure_key,KeyEvent.keyCodeToString(key).removePrefix("KEYCODE_")),style=MaterialTheme.typography.headlineSmall)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(false,true).forEachIndexed {i,v ->SegmentedButton(selected=long==v,onClick={long=v},shape=SegmentedButtonDefaults.itemShape(i,2)) {Text(stringResource(if(v)R.string.keyboard_launcher_long else R.string.keyboard_launcher_short))}}
                }
                if(value.isNotEmpty()) {
                    Surface(color=MaterialTheme.colorScheme.secondaryContainer,shape=MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text(stringResource(R.string.keyboard_launcher_selected),style=MaterialTheme.typography.labelMedium)
                            Text(selected?.label ?: stringResource(R.string.keyboard_launcher_missing_command),style=MaterialTheme.typography.titleMedium)
                            selected?.subtitle?.let {Text(it)}
                        }
                    }
                }
                OutlinedTextField(value=query,onValueChange={query=it},singleLine=true,label={Text(stringResource(R.string.keyboard_launcher_search))},modifier=Modifier.fillMaxWidth())
                LazyColumn(Modifier.fillMaxWidth().weight(1f,fill=false)) {
                    item {TextButton(onClick={if(long)held="" else short=""}) {Text(stringResource(R.string.keyboard_launcher_remove))}}
                    items(results,key={it.id}) {command ->
                        Row(Modifier.fillMaxWidth().clickable {if(long)held=command.id else short=command.id}.padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                            RadioButton(selected=value==command.id,onClick={if(long)held=command.id else short=command.id})
                            Column(Modifier.weight(1f)) {Text(command.label);command.subtitle?.let {Text(it,style=MaterialTheme.typography.bodySmall)}}
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                    TextButton(onClick=onDismiss){Text(stringResource(R.string.cancel))}
                    TextButton(onClick={onSave(LauncherMapping(short,held.takeIf {it.isNotEmpty()}?.let(::LauncherMapping)))}) {Text(stringResource(R.string.save))}
                }
            }
        }
    }
}
