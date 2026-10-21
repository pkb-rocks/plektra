package rocks.pkb.plektra

import android.content.Context
import moe.shizuku.manager.adb.EmbeddedAdbInit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * The broker keeps its private ADB key and last verdict in [EmbeddedAdbInit.PREFS_NAME]. Both backup
 * rule files include every preferences file, so this one has to be excluded by name: a key restored
 * onto another phone is useless, and it should never sit in a cloud backup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BrokerBackupRulesTest {

    /** Shared-pref excludes per top-level section; empty-set entry for a section that has no excludes. */
    private fun excludedSharedPrefsBySection(context: Context, xmlRes: Int): Map<String, Set<String>> {
        val parser = context.resources.getXml(xmlRes)
        val bySection = linkedMapOf<String, MutableSet<String>>()
        var section: String? = null
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    // full-backup-content (backup_rules.xml) has no sub-sections; it counts as one.
                    "full-backup-content", "cloud-backup", "device-transfer", "cross-platform-transfer" -> {
                        section = parser.name
                        bySection.getOrPut(section) { mutableSetOf() }
                    }
                    "exclude" -> if (section != null &&
                        parser.getAttributeValue(null, "domain") == "sharedpref"
                    ) {
                        bySection.getOrPut(section) { mutableSetOf() } += parser.getAttributeValue(null, "path")
                    }
                }
            } else if (parser.eventType == XmlPullParser.END_TAG &&
                parser.name in setOf("full-backup-content", "cloud-backup", "device-transfer", "cross-platform-transfer")
            ) {
                section = null
            }
            parser.next()
        }
        return bySection
    }

    private fun excludedSharedPrefs(context: Context, xmlRes: Int): Set<String> =
        excludedSharedPrefsBySection(context, xmlRes).values.flatten().toSet()

    @Test
    fun fullBackupRulesExcludeTheBrokerPrefs() {
        val excluded = excludedSharedPrefs(RuntimeEnvironment.getApplication(), R.xml.backup_rules)
        assertTrue(excluded.toString(), "${EmbeddedAdbInit.PREFS_NAME}.xml" in excluded)
    }

    /**
     * A missing section means that mode is fully enabled for all content, so every section the file
     * declares must carry the exclude, and the two sections that matter on a phone-to-phone move
     * (cloud backup and device transfer) must exist. No cross-platform-transfer section is declared
     * (it needs an iOS bundleId/teamId); if one is ever added, it is checked here too.
     */
    @Test
    fun dataExtractionRulesExcludeTheBrokerPrefsInEverySection() {
        val bySection = excludedSharedPrefsBySection(RuntimeEnvironment.getApplication(), R.xml.data_extraction_rules)
        val file = "${EmbeddedAdbInit.PREFS_NAME}.xml"
        assertTrue(bySection.toString(), "cloud-backup" in bySection)
        assertTrue(bySection.toString(), "device-transfer" in bySection)
        for ((section, excluded) in bySection) {
            assertTrue("$section: $excluded", file in excluded)
        }
    }
}
