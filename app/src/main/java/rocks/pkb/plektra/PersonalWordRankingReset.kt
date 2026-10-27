package rocks.pkb.plektra

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import rocks.pkb.plektra.backup.LearningOperationGate
import rocks.pkb.plektra.core.suggestions.PersonalWordLearning

@Composable
internal fun PersonalWordRankingReset() {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    TextButton(onClick={confirming=true;failed=false},enabled=!busy,
        modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp)) {
        Text(stringResource(R.string.personal_ranking_reset))
    }
    if(confirming) AlertDialog(
        onDismissRequest={if(!busy) confirming=false},
        title={Text(stringResource(R.string.personal_ranking_reset))},
        text={Column {
            Text(stringResource(R.string.personal_ranking_reset_message))
            if(failed) Text(stringResource(R.string.personal_ranking_reset_failed),color=MaterialTheme.colorScheme.error)
        }},
        confirmButton={TextButton(enabled=!busy,onClick={
            busy=true;failed=false
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { LearningOperationGate.withExclusiveOperation {
                        LearningOperationGate.awaitPendingLearning()
                        PersonalWordLearning.store(context).clearAll()
                    } }
                    confirming=false
                } catch(error:CancellationException) { throw error }
                  catch(_:Exception) { failed=true }
                finally { busy=false }
            }
        }) {Text(stringResource(R.string.auto_space_punctuation_reset))}},
        dismissButton={TextButton(enabled=!busy,onClick={confirming=false}) {Text(stringResource(R.string.cancel))}}
    )
}
