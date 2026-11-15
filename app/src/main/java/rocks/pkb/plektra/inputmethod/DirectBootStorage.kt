package rocks.pkb.plektra.inputmethod

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.WeakHashMap

/**
 * Process-wide Direct Boot state: until the user unlocks, storage of the application and the IME
 * resolves to the device-protected keyboard snapshot and to memory. Storage switches to
 * credential-protected storage on whichever thread first observes the unlock, since Android
 * installs providers and starts their work before ACTION_USER_UNLOCKED. The unlock listeners then
 * run on the main thread until each has completed once.
 */
internal object DirectBootStorage {
    private const val TAG = "DirectBootStorage"
    private const val CREDENTIAL_SETTINGS = "plektra_prefs"
    private const val LOCKED_MESSAGE = "Credential-protected storage is unavailable before the user unlocks"
    private val lock = Any()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    @Volatile
    private var state: Boolean? = null // null: undecided, true: locked, false: unlocked
    private var appContext: Context? = null
    private var unlockReceiver: BroadcastReceiver? = null
    private var lockedFiles: File? = null
    private val preferences = mutableMapOf<String, SwitchingPreferences>()
    private val unlockListeners = mutableListOf<() -> Unit>()
    private var runningUnlockListeners = false

    /** Selections made before unlock that the user expects to keep, e.g. a layout switched by Ctrl+Space. */
    private val carriedOverSettings = setOf("keyboard_layout")

    /** True until the user unlocks; the first observation of the unlock switches storage. */
    fun isLocked(context: Context): Boolean {
        if (state == false) return false
        synchronized(lock) { return checkLocked(context) }
    }

    /** Runs [listener] on the main thread after the unlock; false, without running it, when already unlocked. */
    fun runAfterUnlock(context: Context, listener: () -> Unit): Boolean {
        synchronized(lock) {
            if (!checkLocked(context)) return false
            unlockListeners += listener
            return true
        }
    }

    fun removeUnlockListener(listener: () -> Unit) {
        synchronized(lock) { unlockListeners -= listener }
    }

    /**
     * Runs the pending unlock listeners now; ignored while the user is still locked. A listener that
     * fails does not keep the others from running and is retried on the next call.
     */
    fun completeUnlock(context: Context) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { completeUnlock(context) }
            return
        }
        if (isLocked(context) || runningUnlockListeners) return
        runningUnlockListeners = true
        try {
            synchronized(lock) { unlockListeners.toList() }.forEach { listener ->
                try {
                    listener()
                    synchronized(lock) { unlockListeners -= listener }
                } catch (e: Exception) {
                    Log.e(TAG, "Unlock handoff failed; retried on the next unlock signal", e)
                }
            }
        } finally {
            runningUnlockListeners = false
        }
    }

    /** Memory-backed preferences while locked, null once credential-protected storage is available. */
    fun lockedPreferences(base: Context, name: String): SharedPreferences? = synchronized(lock) {
        if (!checkLocked(base)) return null
        preferences.getOrPut(name) {
            val initial = if (name == CREDENTIAL_SETTINGS) {
                DirectBootKeyboardConfig(base).read() + DirectBootKeyboardConfig.LOCKED_FEATURE_OVERRIDES
            } else {
                emptyMap()
            }
            SwitchingPreferences(MemoryPreferences(initial))
        }
    }

    /** The published snapshot's files while locked, null once credential-protected storage is available. */
    fun lockedFilesDir(base: Context): File? = synchronized(lock) {
        if (!checkLocked(base)) return null
        lockedFiles ?: DirectBootKeyboardConfig(base).lockedFilesDir().also { lockedFiles = it }
    }

    fun lockedCacheDir(base: Context): File = base.createDeviceProtectedStorageContext().cacheDir

    fun unavailable(): Nothing = throw IllegalStateException(LOCKED_MESSAGE)

    private fun checkLocked(context: Context): Boolean {
        when (state) {
            false -> return false
            null -> {
                val locked = !DirectBootKeyboardConfig.isUserUnlocked(context)
                state = locked
                if (locked) watchForUnlock(context)
                return locked
            }
            else -> {
                if (!DirectBootKeyboardConfig.isUserUnlocked(context)) return true
                switchToCredentialStorage(context)
                return false
            }
        }
    }

    /** Called with [lock] held; holders of locked preferences read the credential files afterwards. */
    private fun switchToCredentialStorage(context: Context) {
        state = false
        lockedFiles = null
        unlockReceiver?.let { receiver -> runCatching { appContext?.unregisterReceiver(receiver) } }
        unlockReceiver = null
        val credential = appContext ?: context.applicationContext ?: context
        preferences.forEach { (name, prefs) ->
            val target = credential.getSharedPreferences(name, Context.MODE_PRIVATE)
            prefs.switchTo(target, if (name == CREDENTIAL_SETTINGS) carriedOverSettings else emptySet())
        }
        preferences.clear()
        mainHandler.post { completeUnlock(credential) }
    }

    private fun watchForUnlock(context: Context) {
        val app = context.applicationContext ?: context
        appContext = app
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_USER_UNLOCKED) completeUnlock(context)
            }
        }
        unlockReceiver = receiver
        ContextCompat.registerReceiver(app, receiver, IntentFilter(Intent.ACTION_USER_UNLOCKED), ContextCompat.RECEIVER_NOT_EXPORTED)
        // Close the race where unlock occurred between the initial check and registration.
        if (DirectBootKeyboardConfig.isUserUnlocked(app)) mainHandler.post { completeUnlock(app) }
    }

    @VisibleForTesting
    fun resetForTesting() {
        synchronized(lock) {
            unlockReceiver?.let { receiver -> runCatching { appContext?.unregisterReceiver(receiver) } }
            unlockReceiver = null
            appContext = null
            state = null
            lockedFiles = null
            preferences.clear()
            unlockListeners.clear()
            runningUnlockListeners = false
        }
    }
}

/** Routes storage to [DirectBootStorage] while the user is locked; plain delegation afterwards. */
internal class DirectBootStorageContext(base: Context) : ContextWrapper(base) {
    private val locked: Boolean get() = DirectBootStorage.isLocked(baseContext)

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        DirectBootStorage.lockedPreferences(baseContext, name) ?: super.getSharedPreferences(name, mode)

    override fun deleteSharedPreferences(name: String): Boolean =
        if (locked) false else super.deleteSharedPreferences(name)

    override fun getFilesDir(): File = DirectBootStorage.lockedFilesDir(baseContext) ?: super.getFilesDir()

    override fun getCacheDir(): File = if (locked) DirectBootStorage.lockedCacheDir(baseContext) else super.getCacheDir()

    override fun getNoBackupFilesDir(): File = if (locked) DirectBootStorage.unavailable() else super.getNoBackupFilesDir()

    override fun getDataDir(): File = if (locked) DirectBootStorage.unavailable() else super.getDataDir()

    override fun getDir(name: String, mode: Int): File = if (locked) DirectBootStorage.unavailable() else super.getDir(name, mode)

    override fun getDatabasePath(name: String): File = if (locked) DirectBootStorage.unavailable() else super.getDatabasePath(name)

    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
        if (locked) DirectBootStorage.unavailable() else super.openOrCreateDatabase(name, mode, factory)

    override fun openOrCreateDatabase(
        name: String,
        mode: Int,
        factory: SQLiteDatabase.CursorFactory?,
        errorHandler: DatabaseErrorHandler?
    ): SQLiteDatabase = if (locked) DirectBootStorage.unavailable() else super.openOrCreateDatabase(name, mode, factory, errorHandler)

    override fun deleteDatabase(name: String): Boolean = if (locked) false else super.deleteDatabase(name)

    override fun databaseList(): Array<String> = if (locked) emptyArray() else super.databaseList().map { it }.toTypedArray()

    override fun getFileStreamPath(name: String): File = if (locked) File(filesDir, name) else super.getFileStreamPath(name)

    override fun openFileInput(name: String): FileInputStream {
        if (!locked) return super.openFileInput(name)
        val file = File(filesDir, name)
        if (!file.isFile) throw FileNotFoundException(name)
        return FileInputStream(file)
    }

    // The published snapshot stays unchanged before unlock.
    override fun openFileOutput(name: String, mode: Int): FileOutputStream =
        if (locked) DirectBootStorage.unavailable() else super.openFileOutput(name, mode)

    override fun deleteFile(name: String): Boolean = if (locked) false else super.deleteFile(name)

    override fun fileList(): Array<String> = (if (locked) filesDir.list() else super.fileList()).orEmpty().map { it }.toTypedArray()
}

/**
 * Keeps one [SharedPreferences] identity across the unlock: holders obtained before unlock read the
 * credential-protected file afterwards, and registered listeners move along. Editors record their
 * changes and apply them to the storage current at commit, also when obtained before unlock.
 */
internal class SwitchingPreferences(initial: SharedPreferences) : SharedPreferences {
    @Volatile
    private var target: SharedPreferences = initial
    // Held while switching and while a commit writes to memory; the credential target is final.
    private val switchLock = Any()
    private val listeners = WeakHashMap<SharedPreferences.OnSharedPreferenceChangeListener, Unit>()
    // The backing preferences hold listeners weakly; this forwarder stays referenced here.
    private val forwarder = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        synchronized(listeners) { listeners.keys.toList() }.forEach { it.onSharedPreferenceChanged(this, key) }
    }

    init {
        initial.registerOnSharedPreferenceChangeListener(forwarder)
    }

    /**
     * Called once, under the [DirectBootStorage] lock. String values of [carriedOver] written before
     * unlock are newer than the credential copy and are copied into it; other writes are dropped.
     */
    fun switchTo(credential: SharedPreferences, carriedOver: Set<String>) {
        synchronized(switchLock) {
            val memory = target
            val carried = (memory as? MemoryPreferences)?.written(carriedOver).orEmpty().filterValues { it is String }
            if (carried.isNotEmpty()) {
                credential.edit().apply { carried.forEach { (key, value) -> putString(key, value as String) } }.apply()
            }
            memory.unregisterOnSharedPreferenceChangeListener(forwarder)
            target = credential
            credential.registerOnSharedPreferenceChangeListener(forwarder)
        }
    }

    override fun getAll(): Map<String, *> = target.all
    override fun getString(key: String?, defValue: String?): String? = target.getString(key, defValue)
    override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? = target.getStringSet(key, defValues)
    override fun getInt(key: String?, defValue: Int): Int = target.getInt(key, defValue)
    override fun getLong(key: String?, defValue: Long): Long = target.getLong(key, defValue)
    override fun getFloat(key: String?, defValue: Float): Float = target.getFloat(key, defValue)
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = target.getBoolean(key, defValue)
    override fun contains(key: String?): Boolean = target.contains(key)
    override fun edit(): SharedPreferences.Editor = RecordingEditor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        synchronized(listeners) { listeners[listener] = Unit }
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    private inner class RecordingEditor : SharedPreferences.Editor {
        private val changes = LinkedHashMap<String, Any?>() // null: removal
        private var clear = false

        override fun putString(key: String, value: String?) = apply { synchronized(changes) { changes[key] = value } }
        override fun putStringSet(key: String, values: Set<String>?) = apply { synchronized(changes) { changes[key] = values?.toSet() } }
        override fun putInt(key: String, value: Int) = apply { synchronized(changes) { changes[key] = value } }
        override fun putLong(key: String, value: Long) = apply { synchronized(changes) { changes[key] = value } }
        override fun putFloat(key: String, value: Float) = apply { synchronized(changes) { changes[key] = value } }
        override fun putBoolean(key: String, value: Boolean) = apply { synchronized(changes) { changes[key] = value } }
        override fun remove(key: String) = apply { synchronized(changes) { changes[key] = null } }
        override fun clear() = apply { synchronized(changes) { clear = true } }
        override fun commit(): Boolean = write { it.commit() }
        override fun apply() {
            write { it.apply(); true }
        }

        /** Listeners are notified outside [switchLock], so they may take the [DirectBootStorage] lock. */
        private fun write(store: (SharedPreferences.Editor) -> Boolean): Boolean {
            val (clearNow, pending) = synchronized(changes) {
                (clear to LinkedHashMap(changes)).also {
                    clear = false
                    changes.clear()
                }
            }
            var changedKeys: List<String>? = null
            val credential = synchronized(switchLock) {
                when (val current = target) {
                    is MemoryPreferences -> {
                        changedKeys = current.write(clearNow, pending)
                        null
                    }
                    else -> current
                }
            }
            changedKeys?.let { keys ->
                // Notify this stable wrapper even if the memory forwarder was removed at unlock.
                val notify = {
                    synchronized(listeners) { listeners.keys.toList() }.forEach { listener ->
                        keys.forEach { key -> listener.onSharedPreferenceChanged(this@SwitchingPreferences, key) }
                    }
                }
                if (Looper.myLooper() == Looper.getMainLooper()) notify()
                else Handler(Looper.getMainLooper()).post(notify)
            }
            credential ?: return true
            val editor = credential.edit()
            if (clearNow) editor.clear()
            pending.forEach { (key, value) ->
                @Suppress("UNCHECKED_CAST")
                when (value) {
                    null -> editor.remove(key)
                    is String -> editor.putString(key, value)
                    is Set<*> -> editor.putStringSet(key, value as Set<String>)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                }
            }
            return store(editor)
        }
    }
}

/**
 * Preferences held only in memory before unlock. At the unlock only the selected layout
 * (`keyboard_layout`) is carried over into credential-protected storage; other changes are dropped.
 */
internal class MemoryPreferences(initial: Map<String, *>) : SharedPreferences {
    private val values = HashMap<String, Any?>(initial)
    private val writtenKeys = HashSet<String>()
    private val listeners = WeakHashMap<SharedPreferences.OnSharedPreferenceChangeListener, Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun getAll(): Map<String, *> = synchronized(values) { HashMap(values) }
    override fun getString(key: String?, defValue: String?): String? = get(key) as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? = (get(key) as? Set<String>)?.toSet() ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = get(key) as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = get(key) as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = get(key) as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = get(key) as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = synchronized(values) { values.containsKey(key) }
    private fun get(key: String?): Any? = synchronized(values) { values[key] }

    fun written(keys: Set<String>): Map<String, Any?> = synchronized(values) {
        writtenKeys.filter { it in keys }.associateWith { values[it] }
    }

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        synchronized(listeners) { listeners[listener] = Unit }
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = LinkedHashMap<String, Any?>()
        private var clear = false
        override fun putString(key: String, value: String?) = apply { changes[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) = apply { changes[key] = values?.toSet() }
        override fun putInt(key: String, value: Int) = apply { changes[key] = value }
        override fun putLong(key: String, value: Long) = apply { changes[key] = value }
        override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
        override fun remove(key: String) = apply { changes[key] = null }
        override fun clear() = apply { clear = true }
        override fun apply() {
            commit()
        }

        override fun commit(): Boolean {
            notifyChanged(write(clear, changes))
            return true
        }
    }

    /** Applies an editor's changes, a null value removing its key; returns the changed keys. */
    fun write(clear: Boolean, changes: Map<String, Any?>): List<String> = synchronized(values) {
        val keys = mutableListOf<String>()
        if (clear && values.isNotEmpty()) {
            keys += values.keys
            values.clear()
        }
        changes.forEach { (key, value) ->
            if (value == null) values.remove(key) else values[key] = value
            keys += key
        }
        keys.distinct().also { writtenKeys += it }
    }

    fun notifyChanged(keys: List<String>) {
        if (keys.isEmpty()) return
        val notify = {
            synchronized(listeners) { listeners.keys.toList() }.forEach { listener ->
                keys.forEach { key -> listener.onSharedPreferenceChanged(this, key) }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) notify() else mainHandler.post(notify)
    }
}
