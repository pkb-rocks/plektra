package rocks.pkb.plektra.training

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rocks.pkb.plektra.LocalizedComponentActivity
import rocks.pkb.plektra.R
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.inputmethod.aospkeyboard.KeyCorrectionProfile
import rocks.pkb.plektra.inputmethod.aospkeyboard.KeyCorrectionProfiles
import rocks.pkb.plektra.ui.theme.PlektraTheme
import java.io.File
import kotlin.math.hypot

/** Key correction (#176): switch, calibration from typing-training sessions and profile details. */
class KeyCorrectionActivity : LocalizedComponentActivity() {

    private var enabled by mutableStateOf(false)
    private var calibrating by mutableStateOf(false)
    private var status by mutableStateOf<String?>(null)
    private val profiles = mutableStateListOf<KeyCorrectionProfile>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        enabled = SettingsManager.getSoftwareKeyboardKeyCorrectionEnabled(this)
        reloadProfiles()
        setContent { PlektraTheme { Screen() } }
    }

    private fun reloadProfiles() {
        profiles.clear()
        profiles += KeyCorrectionProfiles.all(this).values.sortedBy { it.key }
    }

    private fun sessionFiles(): List<File> =
        File(filesDir, "typing-training").listFiles { file -> file.extension == "json" && file.name.startsWith("session-") }
            ?.sortedBy { it.name }.orEmpty()

    private fun calibrate() {
        calibrating = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                KeyCorrectionCalibration.calibrate(sessionFiles(), assets, System.currentTimeMillis())
            }
            KeyCorrectionProfiles.save(this@KeyCorrectionActivity, result)
            reloadProfiles()
            calibrating = false
            status = getString(R.string.typing_training_calibration_done, result.size, result.sumOf { it.taps })
        }
    }

    private fun reset() {
        KeyCorrectionProfiles.save(this, emptyList())
        reloadProfiles()
        status = getString(R.string.key_correction_reset_done)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Screen() {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.software_keyboard_key_correction_title)) },
                    navigationIcon = {
                        IconButton(onClick = { finish() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.settings_back_content_description))
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.software_keyboard_key_correction_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.software_keyboard_key_correction_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = enabled, onCheckedChange = {
                        enabled = it
                        SettingsManager.setSoftwareKeyboardKeyCorrectionEnabled(this@KeyCorrectionActivity, it)
                    })
                }
                Text(
                    stringResource(R.string.key_correction_calibration_explanation),
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(onClick = { calibrate() }, enabled = !calibrating, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.typing_training_calibration_run))
                }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (profiles.isEmpty()) {
                    Text(
                        stringResource(R.string.key_correction_no_profiles),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                profiles.forEach { ProfileCard(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        startActivity(Intent(this@KeyCorrectionActivity, TypingTrainingActivity::class.java))
                    }) { Text(stringResource(R.string.key_correction_open_training)) }
                    if (profiles.isNotEmpty()) {
                        TextButton(onClick = { reset() }) { Text(stringResource(R.string.key_correction_reset)) }
                    }
                }
            }
        }
    }

    @Composable
    private fun ProfileCard(profile: KeyCorrectionProfile) {
        var expanded by remember { mutableStateOf(false) }
        Card(modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(profile.key, style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.key_correction_profile_summary, profile.sessions, profile.taps, profile.sigmaXMm, profile.sigmaYMm),
                    style = MaterialTheme.typography.bodySmall
                )
                val validation = profile.validation
                Text(
                    when {
                        validation == null -> stringResource(R.string.key_correction_offsets_unvalidated)
                        validation.offsetsEnabled -> stringResource(R.string.key_correction_offsets_enabled)
                        else -> stringResource(R.string.key_correction_offsets_disabled)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (validation?.offsetsEnabled == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                validation?.let {
                    fun pct(errors: Int) = 100f * errors / it.taps.coerceAtLeast(1)
                    Text(
                        stringResource(
                            R.string.key_correction_validation,
                            it.taps, pct(it.hitTestErrors), pct(it.withoutOffsetErrors), pct(it.withOffsetErrors)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (expanded) {
                    profile.measuredOffsets.entries
                        .sortedByDescending { hypot(it.value.dxMm, it.value.dyMm) }
                        .forEach { (key, offset) ->
                            Text(
                                stringResource(R.string.key_correction_offset_row, key, offset.dxMm, offset.dyMm, offset.samples),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                }
            }
        }
    }
}
