package rocks.pkb.plektra.inputmethod.aospkeyboard

import android.content.Context
import android.content.res.Configuration
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import rocks.pkb.plektra.SettingsManager

/** Stores calibrated key-correction profiles and picks the one matching the current keyboard. */
object KeyCorrectionProfiles {
    private val json = Json { ignoreUnknownKeys = true }
    private var cachedJson: String? = null
    private var cached: Map<String, KeyCorrectionProfile> = emptyMap()

    fun all(context: Context): Map<String, KeyCorrectionProfile> {
        val raw = SettingsManager.getSoftwareKeyboardKeyCorrectionProfiles(context)
        if (raw != cachedJson) {
            cached = raw?.let {
                runCatching { json.decodeFromString(ListSerializer(KeyCorrectionProfile.serializer()), it) }.getOrNull()
            }.orEmpty().associateBy { it.key }
            cachedJson = raw
        }
        return cached
    }

    fun save(context: Context, profiles: Collection<KeyCorrectionProfile>) {
        SettingsManager.setSoftwareKeyboardKeyCorrectionProfiles(
            context,
            json.encodeToString(ListSerializer(KeyCorrectionProfile.serializer()), profiles.sortedBy { it.key })
        )
    }

    fun forView(context: Context, layoutName: String, layoutStyle: String): KeyCorrectionProfile? {
        val configuration = context.resources.configuration
        val key = KeyCorrection.profileKey(
            configuration.smallestScreenWidthDp,
            configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
            layoutName,
            layoutStyle
        )
        return all(context)[key]
    }
}
