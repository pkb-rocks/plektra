package rikka.shizuku

import moe.shizuku.server.IRemoteProcess

/** Builds the real client-side process wrapper around a test double of the Shizuku server. */
internal fun newShizukuRemoteProcessForTest(remote: IRemoteProcess): ShizukuRemoteProcess =
    ShizukuRemoteProcess(remote)

/** What Shizuku does on binder death: the wrapper drops its remote reference. */
internal fun ShizukuRemoteProcess.simulateBinderDeathForTest() {
    ShizukuRemoteProcess::class.java.getDeclaredField("remote").apply { isAccessible = true }.set(this, null)
}
