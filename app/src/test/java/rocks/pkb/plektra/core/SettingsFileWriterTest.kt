package rocks.pkb.plektra.core

import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rocks.pkb.plektra.DefaultUserDefaultsStore
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsFileWriterTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun overlappingWritersPublishOnlyCompletePayloads() {
        val target = File(folder.root, "variations.json")
        target.writeText("old")
        val staged = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val slow = executor.submit {
                target.writeSettingsFileAtomically { out ->
                    out.write("slow ".toByteArray())
                    staged.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    out.write("complete".toByteArray())
                }
            }

            assertTrue(staged.await(10, TimeUnit.SECONDS))
            assertEquals("old", target.readText())
            target.writeSettingsFileAtomically("fast complete")
            assertEquals("fast complete", target.readText())
            release.countDown()
            slow.get(10, TimeUnit.SECONDS)
            assertEquals("slow complete", target.readText())
            assertEquals(listOf(target.name), folder.root.list()!!.toList())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun failureAfterPartialWritePreservesOriginalAndCleansStaging() {
        val target = File(folder.root, "ctrl_key_mappings.json")
        target.writeText("old")

        assertThrows(IOException::class.java) {
            target.writeSettingsFileAtomically { out ->
                out.write("partial".toByteArray())
                throw IOException("injected failure")
            }
        }

        assertEquals("old", target.readText())
        assertEquals(listOf(target.name), folder.root.list()!!.toList())
    }

    @Test
    fun lateRepositoryInitializerPreservesSettingsUiSave() {
        val target = File(folder.root, "user_defaults.json")
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFileStreamPath(name: String) = File(folder.root, name)
        }
        val store = DefaultUserDefaultsStore(context)
        store.addOrBump("testpersistentword")
        assertTrue(store.loadEntries().any { it.word == "testpersistentword" })
        val uiContents = target.readText()

        initializeUserDefaultsFile(target) { error("Existing file must bypass the asset") }

        assertEquals(uiContents, target.readText())
    }

    @Test
    fun missingAssetFallbackAndRetryableInitialization() {
        val target = File(folder.root, "user_defaults.json")
        assertThrows(FileNotFoundException::class.java) {
            initializeUserDefaultsFile(target) { throw FileNotFoundException("missing") }
        }
        assertFalse(target.exists())

        initializeUserDefaultsFile(target, useEmptyFallback = true) { throw FileNotFoundException("missing") }
        assertEquals("[]", target.readText())
    }
}
