package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.content.SharedPreferences
import android.os.UserManager
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowContextImpl
import org.robolectric.util.reflector.Direct
import org.robolectric.util.reflector.ForType
import org.robolectric.util.reflector.Reflector.reflector
import java.io.File

/**
 * Rejects credential-protected storage while the user is locked, as Android does; Robolectric
 * itself does not. Every rejected access is recorded, also when the caller swallows the exception.
 */
@Implements(className = ShadowContextImpl.CLASS_NAME, isInAndroidSdk = false)
class CredentialStorageLockShadow : ShadowContextImpl() {
    @field:RealObject
    private lateinit var realContext: Context

    companion object {
        val violations = mutableListOf<String>()
    }

    private fun guard(access: String) {
        if (realContext.isDeviceProtectedStorage) return
        val users = RuntimeEnvironment.getApplication().getSystemService(UserManager::class.java)
        if (users.isUserUnlocked) return
        violations += access
        throw IllegalStateException("$access is credential-protected and unavailable before unlock")
    }

    @Implementation
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        guard("SharedPreferences $name")
        return super.getSharedPreferences(name, mode)
    }

    @Implementation
    override fun getDatabasePath(name: String): File {
        guard("database $name")
        return super.getDatabasePath(name)
    }

    @Implementation
    protected fun getFilesDir(): File {
        guard("filesDir")
        return reflector(ContextImplReflector::class.java, realContext).getFilesDir()
    }

    @Implementation
    protected fun getCacheDir(): File {
        guard("cacheDir")
        return reflector(ContextImplReflector::class.java, realContext).getCacheDir()
    }

    @Implementation
    protected fun getNoBackupFilesDir(): File {
        guard("noBackupFilesDir")
        return reflector(ContextImplReflector::class.java, realContext).getNoBackupFilesDir()
    }

    @ForType(className = ShadowContextImpl.CLASS_NAME)
    interface ContextImplReflector {
        @Direct fun getFilesDir(): File
        @Direct fun getCacheDir(): File
        @Direct fun getNoBackupFilesDir(): File
    }
}
