package rocks.pkb.plektra

import rocks.pkb.plektra.backup.PreferenceSchemas
import rocks.pkb.plektra.backup.PreferenceValueType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DailyThemeRotationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val target = SettingsManager.KeyboardThemeTarget.HARDWARE
    @Before fun reset() { SettingsManager.getPreferences(context).edit().clear().commit() }
    @Test fun defaultsAreOptional() {
        // *Preserve Plektra's system theme default; daily rotation remains opt-in.*
        assertEquals(SettingsManager.KEYBOARD_THEME_ASSIGNMENT_MODE_FOLLOW_SYSTEM, SettingsManager.getKeyboardThemeAssignmentMode(context, target))
    }
    @Test fun oneThemeRemainsTheSameAndThreeCycleAcrossMissedDays() {
        for (day in 0L..14L) assertEquals(0, DailyThemeRotation.index(day, 0, 1))
        assertEquals(listOf(0,1,2,0,1,2,0), (0L..6L).map { DailyThemeRotation.index(it,0,3) })
        assertEquals(2,DailyThemeRotation.index(-1,0,3))
        assertEquals(1,DailyThemeRotation.index(100,0,3))
    }
    @Test fun selectedThemesPersistDeletedThemesAreSkippedAndCacheInvalidates() {
        val fixed = SettingsManager.defaultKeyboardTheme()
        val first = fixed.copy(background=0xFF112233.toInt())
        val second = fixed.copy(background=0xFF445566.toInt())
        SettingsManager.saveKeyboardTheme(context,"A",first)
        SettingsManager.saveKeyboardTheme(context,"B",second)
        DailyThemeRotation.select(context,target,listOf("A","B"))
        val today=LocalDate.now().toEpochDay()
        assertEquals(first,DailyThemeRotation.theme(context,target,today))
        assertEquals(second,DailyThemeRotation.theme(context,target,today+1))
        assertEquals(listOf("A","B"),DailyThemeRotation.selectedNames(context,target))
        SettingsManager.deleteKeyboardTheme(context,"A")
        assertEquals(second,DailyThemeRotation.theme(context,target,today))
        SettingsManager.deleteKeyboardTheme(context,"B")
        assertNull(DailyThemeRotation.theme(context,target,today))
    }
    @Test fun dailyModePersistsAndFixedModeDoesNotApplyRotation() {
        val fixed=SettingsManager.defaultKeyboardTheme()
        SettingsManager.setKeyboardTheme(context,target,fixed)
        val rotating=fixed.copy(background=0xFF112233.toInt())
        SettingsManager.saveKeyboardTheme(context,"A",rotating)
        DailyThemeRotation.select(context,target,listOf("A"))
        assertEquals(fixed,SettingsManager.getEffectiveKeyboardTheme(context,target))
        SettingsManager.setKeyboardThemeAssignmentMode(context,target,SettingsManager.KEYBOARD_THEME_ASSIGNMENT_MODE_DAILY)
        assertEquals("daily",SettingsManager.getKeyboardThemeAssignmentMode(context,target))
        assertEquals(rotating,SettingsManager.getEffectiveKeyboardTheme(context,target))
        SettingsManager.deleteKeyboardTheme(context,"A")
        assertEquals(fixed,SettingsManager.getEffectiveKeyboardTheme(context,target))
    }
    @Test fun rotationPreferencesAreIncludedInBackup() {
        SettingsManager.KeyboardThemeTarget.entries.forEach {
            assertEquals(PreferenceValueType.STRING,PreferenceSchemas.expectedType("plektra_prefs",DailyThemeRotation.namesKey(it)))
            assertEquals(PreferenceValueType.LONG,PreferenceSchemas.expectedType("plektra_prefs",DailyThemeRotation.anchorKey(it)))
        }
    }
    @Test fun localMidnightHandlesBothDstTransitions() {
        val zone=ZoneId.of("Europe/Paris")
        assertEquals(23*3600000L,DailyThemeRotationScheduler.delayToNextDay(ZonedDateTime.of(2026,3,29,0,0,0,0,zone)))
        assertEquals(25*3600000L,DailyThemeRotationScheduler.delayToNextDay(ZonedDateTime.of(2026,10,25,0,0,0,0,zone)))
    }
    @Test fun targetsKeepIndependentSelectionsAndAnchors() {
        val software=SettingsManager.KeyboardThemeTarget.SOFTWARE
        val theme=SettingsManager.defaultKeyboardTheme()
        SettingsManager.saveKeyboardTheme(context,"A",theme)
        SettingsManager.saveKeyboardTheme(context,"B",theme.copy(background=0xFF123456.toInt()))
        DailyThemeRotation.select(context,target,listOf("A"))
        DailyThemeRotation.select(context,software,listOf("B"))
        assertEquals(listOf("A"),DailyThemeRotation.selectedNames(context,target))
        assertEquals(listOf("B"),DailyThemeRotation.selectedNames(context,software))
    }
    @Test fun invalidOrExcessiveSelectionDoesNotCrashOrResolveUnknownThemes() {
        val prefs=SettingsManager.getPreferences(context)
        prefs.edit().putString(DailyThemeRotation.namesKey(target),"not json").commit()
        assertTrue(DailyThemeRotation.selectedNames(context,target).isEmpty())
        assertNull(DailyThemeRotation.theme(context,target))
        prefs.edit().putString(DailyThemeRotation.namesKey(target),"x".repeat(32769)).commit()
        assertTrue(DailyThemeRotation.selectedNames(context,target).isEmpty())
    }
    @Test fun caseAliasesAreCanonicalizedAndDoNotDuplicateCycleSlots() {
        val theme=SettingsManager.defaultKeyboardTheme()
        SettingsManager.saveKeyboardTheme(context,"Ocean",theme)
        DailyThemeRotation.select(context,target,listOf("ocean","Ocean","OCEAN","missing"))
        assertEquals(listOf("Ocean"),DailyThemeRotation.selectedNames(context,target))
    }
    @Test fun leapDaysAndYearBoundaryAreCalendarDays() {
        val anchor=LocalDate.of(2024,2,28).toEpochDay()
        assertEquals(1,DailyThemeRotation.index(LocalDate.of(2024,2,29).toEpochDay(),anchor,3))
        assertEquals(2,DailyThemeRotation.index(LocalDate.of(2024,3,1).toEpochDay(),anchor,3))
        assertEquals(1,DailyThemeRotation.index(LocalDate.of(2027,1,1).toEpochDay(),LocalDate.of(2026,12,31).toEpochDay(),3))
    }
    @Test fun extremeImportedAnchorsNeverOverflowTheCycleIndex() {
        assertEquals(0,DailyThemeRotation.index(Long.MAX_VALUE,Long.MIN_VALUE,3))
        assertEquals(-1,DailyThemeRotation.index(10,2,0))
    }
    @Test fun existingLocaleLayoutOverridesKeepPriorityOverGlobalRotation() {
        val fixed=SettingsManager.defaultKeyboardTheme()
        val scoped=fixed.copy(background=0xFF112233.toInt())
        SettingsManager.saveKeyboardTheme(context,"Daily",fixed)
        DailyThemeRotation.select(context,target,listOf("Daily"))
        SettingsManager.setKeyboardThemeAssignmentMode(context,target,SettingsManager.KEYBOARD_THEME_ASSIGNMENT_MODE_DAILY)
        SettingsManager.upsertKeyboardThemeLayoutOverride(context,target,"fr","azerty",scoped)
        assertEquals(scoped,SettingsManager.getEffectiveKeyboardTheme(context,target,"fr","azerty"))
        assertEquals(fixed,SettingsManager.getEffectiveKeyboardTheme(context,target,"en","qwerty"))
    }
    @Test fun actualZipRoundTripPreservesOrderAnchorSavedThemesAndMode() = kotlinx.coroutines.runBlocking {
        val first=SettingsManager.defaultKeyboardTheme()
        val second=first.copy(background=0xFF556677.toInt())
        SettingsManager.saveKeyboardTheme(context,"First",first)
        SettingsManager.saveKeyboardTheme(context,"Second",second)
        DailyThemeRotation.select(context,target,listOf("Second","First"))
        SettingsManager.setKeyboardThemeAssignmentMode(context,target,SettingsManager.KEYBOARD_THEME_ASSIGNMENT_MODE_DAILY)
        val anchor=SettingsManager.getPreferences(context).getLong(DailyThemeRotation.anchorKey(target),-1)
        val zip=java.io.File.createTempFile("cycle_",".zip",context.cacheDir)
        try {
            assertTrue(rocks.pkb.plektra.backup.BackupManager.createBackup(context,android.net.Uri.fromFile(zip)) is rocks.pkb.plektra.backup.BackupResult.Success)
            SettingsManager.getPreferences(context).edit().clear().commit()
            assertTrue(rocks.pkb.plektra.backup.RestoreManager.restore(context,android.net.Uri.fromFile(zip)) is rocks.pkb.plektra.backup.RestoreResult.Success)
            assertEquals(listOf("Second","First"),DailyThemeRotation.selectedNames(context,target))
            assertEquals(anchor,SettingsManager.getPreferences(context).getLong(DailyThemeRotation.anchorKey(target),-1))
            assertEquals(SettingsManager.KEYBOARD_THEME_ASSIGNMENT_MODE_DAILY,SettingsManager.getKeyboardThemeAssignmentMode(context,target))
            assertEquals(second,DailyThemeRotation.theme(context,target,anchor))
            assertEquals(first,DailyThemeRotation.theme(context,target,anchor+1))
        } finally {zip.delete()}
    }
}
