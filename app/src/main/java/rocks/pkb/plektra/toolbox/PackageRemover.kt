package rocks.pkb.plektra.toolbox

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import rocks.pkb.plektra.inputmethod.BrokerTrigger
import rocks.pkb.plektra.inputmethod.EmbeddedAdbShell
import rocks.pkb.plektra.inputmethod.PrivilegedCommand
import rocks.pkb.plektra.inputmethod.PrivilegedShell

/** What a package is currently doing, read from the phone rather than assumed. */
enum class PackageState {
    /** Installed and running normally. */
    ACTIVE,
    /** Present but disabled: gone from the drawer, not executing. */
    DISABLED,
    /** Uninstalled for this user. Still on /system, so it can be restored. */
    UNINSTALLED,
    /** Not on this device at all. */
    ABSENT
}

/**
 * Applies catalog-approved package changes through the paired ADB broker.
 *
 * Three rules hold everything else up:
 *
 *  1. **Only catalog packages reach a command line.** The broker is a shell, so an
 *     unvalidated package name is a command-injection primitive. Every mutation goes through a
 *     [PrivilegedCommand] whose `commandLine` re-checks [BloatCatalog.isRemovable] immediately
 *     before the command can run, and [mutate] checks it first, not merely at the point the UI offered the choice.
 *  2. **The journal is written before the command, not after.** A change that lands but is
 *     never recorded is a change the user cannot undo.
 *  3. **The outcome is confirmed through the local PackageManager**, not by trusting what the
 *     shell printed. A fake or confused adbd can say anything; [PackageManager] cannot be
 *     told what to report. (Catalog packages are declared in the manifest's `<queries>` so they
 *     are visible to that check.)
 *
 * BLOCKING — every call does network IO through the broker. Never call from the main thread,
 * and never from the IME input path.
 */
object PackageRemover {
    private const val TAG = "PackageRemover"

    sealed interface Result {
        data object Success : Result
        data object NotPaired : Result
        data class Refused(val reason: String) : Result
        data class Failed(val reason: String) : Result
    }

    /**
     * Catalog state plus anything vendor-shaped the catalog has never heard of.
     *
     * The second half only means something because every Titan 2 Elite ships identically: an
     * unrecognised vendor package is evidence the firmware moved, not noise.
     */
    data class Census(
        val states: Map<String, PackageState>,
        val unrecognised: List<String>
    )

    /**
     * Cheap pre-flight before an action is attempted: is a key stored at all.
     *
     * Deliberately NOT a readiness claim for the UI — a stored key does not mean the phone
     * accepts it. Screens use rememberVerifiedBrokerStatus, which actually connects.
     */
    internal fun isReady(context: Context): Boolean = EmbeddedAdbShell.isPaired(context)

    /** A read: the open screen's trigger is enough ([PrivilegedCommand.readOnly]). */
    fun census(context: Context, trigger: BrokerTrigger?): Census? {
        val states = readStates(context, trigger) ?: return null
        val unrecognised = lastEverything
            .filter { BloatCatalog.isVendorNamespace(it) && !BloatCatalog.isCatalogued(it) }
            .sorted()
        return Census(states = states, unrecognised = unrecognised)
    }

    /** Every package the last census saw, kept so [census] needs no second round trip. */
    @Volatile
    private var lastEverything: Set<String> = emptySet()

    /**
     * States for every catalog package, in one broker round trip.
     *
     * Read through the shell because a disabled or uninstalled-for-user package is awkward to
     * classify for the whole catalog at once. Display data only: it never decides what runs.
     */
    fun readStates(context: Context, trigger: BrokerTrigger?): Map<String, PackageState>? {
        if (!isReady(context)) return null
        val out = PrivilegedShell.run(context, PrivilegedCommand.ListPackageStates, trigger).output ?: return null

        fun section(from: String, to: String?): Set<String> {
            val start = out.indexOf(from).takeIf { it >= 0 }?.plus(from.length) ?: return emptySet()
            val end = to?.let { out.indexOf(it, start) }?.takeIf { it >= 0 } ?: out.length
            return out.substring(start, end)
                .lineSequence()
                .mapNotNull { it.trim().removePrefix("package:").takeIf { p -> p.isNotEmpty() } }
                .toSet()
        }

        val enabled = section("__E__", "__D__")
        val disabled = section("__D__", "__U__")
        val everything = section("__U__", null)
        lastEverything = everything

        return BloatCatalog.entries().associate { entry ->
            val pkg = entry.packageName
            entry.packageName to when {
                pkg in disabled -> PackageState.DISABLED
                pkg in enabled -> PackageState.ACTIVE
                pkg in everything -> PackageState.UNINSTALLED
                else -> PackageState.ABSENT
            }
        }
    }

    /** Hide the package and stop it running. Reversible with [restore]; nothing is deleted. */
    fun disable(context: Context, packageName: String, currentState: PackageState, trigger: BrokerTrigger?): Result {
        return mutate(context, packageName, currentState, RemovalJournal.Action.DISABLED, PrivilegedCommand.DisablePackage(packageName), PackageState.DISABLED, trigger)
    }

    /**
     * Uninstall for this user. The APK stays on /system, so [restore] can bring it back —
     * but a factory reset or a firmware update is the only other way back, which is why the
     * UI warns before offering this.
     */
    fun uninstall(context: Context, packageName: String, currentState: PackageState, trigger: BrokerTrigger?): Result {
        return mutate(context, packageName, currentState, RemovalJournal.Action.UNINSTALLED, PrivilegedCommand.UninstallPackage(packageName), PackageState.UNINSTALLED, trigger)
    }

    /**
     * Put a package back the way the journal says it was. Both commands are issued because a
     * package can be both uninstalled-for-user and disabled, and reinstalling does not by
     * itself re-enable.
     */
    fun restore(context: Context, packageName: String, trigger: BrokerTrigger?): Result {
        if (!PrivilegedCommand.isValidPackageName(packageName)) {
            Log.w(TAG, "Refusing a package name that is not one")
            return Result.Refused("Not a package name")
        }
        if (!isReady(context)) return Result.NotPaired

        val reinstalled = PrivilegedShell.run(context, PrivilegedCommand.InstallExistingPackage(packageName), trigger)
        val enabled = PrivilegedShell.run(context, PrivilegedCommand.EnablePackage(packageName), trigger)

        val confirmed = when (localState(context, packageName)) {
            PackageState.ACTIVE -> true
            null -> reinstalled.ok || enabled.ok // not visible to the local check: all we have is the shell
            else -> false
        }
        return if (confirmed) {
            RemovalJournal.forget(context, packageName)
            Result.Success
        } else {
            Result.Failed(enabled.failure ?: reinstalled.failure ?: "Restore failed")
        }
    }

    /** Undo everything the journal knows about. Reports how many came back, and how many there were. */
    fun restoreAll(context: Context, trigger: BrokerTrigger?): Pair<Int, Int> {
        val records = RemovalJournal.all(context)
        var restored = 0
        records.forEach { record ->
            if (restore(context, record.packageName, trigger) is Result.Success) restored++
        }
        return restored to records.size
    }

    /**
     * What the phone itself says about [packageName], or null when it cannot tell (a package that
     * is not visible to this app). Never asks the broker.
     */
    internal fun localState(context: Context, packageName: String): PackageState? = runCatching {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getApplicationInfo(
            packageName,
            PackageManager.MATCH_UNINSTALLED_PACKAGES
        )
        when {
            (info.flags and ApplicationInfo.FLAG_INSTALLED) == 0 -> PackageState.UNINSTALLED
            !info.enabled -> PackageState.DISABLED
            else -> PackageState.ACTIVE
        }
    }.getOrNull()

    private fun mutate(
        context: Context,
        packageName: String,
        currentState: PackageState,
        action: RemovalJournal.Action,
        command: PrivilegedCommand,
        expected: PackageState,
        trigger: BrokerTrigger?,
    ): Result {
        if (!BloatCatalog.isSupportedDevice()) {
            return Result.Refused("This catalog is for the Titan 2 Elite only")
        }
        // Not a package name, or not allowed by the catalog: this is the last gate before the name
        // becomes part of a shell command (the command re-checks it when it builds the line).
        if (!PrivilegedCommand.isRemovableCatalogPackage(packageName)) {
            Log.w(TAG, "Refusing a package that is not removable")
            return Result.Refused("This package is protected")
        }
        if (!isReady(context)) return Result.NotPaired

        // Before the command: an unrecorded change is a change that cannot be undone.
        RemovalJournal.record(
            context,
            RemovalJournal.Record(
                packageName = packageName,
                previousState = currentState,
                action = action,
                timestamp = System.currentTimeMillis()
            )
        )

        val result = PrivilegedShell.run(context, command, trigger)
        val local = localState(context, packageName)
        if (!result.ok || (local != null && local != expected)) {
            // It never happened (the shell failed, or the phone says otherwise), so the journal
            // must not claim it did.
            RemovalJournal.forget(context, packageName)
            return Result.Failed(
                if (!result.ok) result.failure ?: "Command failed"
                else "The phone did not report the change"
            )
        }
        return Result.Success
    }
}
