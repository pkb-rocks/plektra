package rocks.pkb.plektra

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import rocks.pkb.plektra.statistics.TypingStatisticsStore
import java.text.NumberFormat

@Composable
fun TypingStatisticsScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context.applicationContext) { TypingStatisticsStore.get(context) }
    val counts by store.snapshot.collectAsState()
    val locale = context.resources.configuration.locales[0]
    val format = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    val installedAt = remember(context.applicationContext) { TypingStatisticsStore.installationStartedAt(context) }
    val installationDate = remember(installedAt, locale) {
        java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, locale).format(java.util.Date(installedAt))
    }
    Scaffold(topBar = {
        Surface(tonalElevation = 1.dp) {
            Row(modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.settings_back_content_description))
                }
                Text(stringResource(R.string.typing_statistics_title), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).padding(start = 8.dp))
            }
        }
    }) { padding ->
        Column(modifier = modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
            .padding(16.dp)) {
            if (installedAt > 0) {
                Text(stringResource(R.string.typing_statistics_since_installation, installationDate),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp))
            }
            StatisticsCountRow(stringResource(R.string.typing_statistics_total_words), format.format(counts.totalWords))
            HorizontalDivider()
            StatisticsCountRow(stringResource(R.string.typing_statistics_manual_words), format.format(counts.manualWords))
            HorizontalDivider()
            StatisticsCountRow(stringResource(R.string.typing_statistics_suggested_words), format.format(counts.suggestedWords))
        }
    }
}

@Composable
private fun StatisticsCountRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.titleLarge)
    }
}
