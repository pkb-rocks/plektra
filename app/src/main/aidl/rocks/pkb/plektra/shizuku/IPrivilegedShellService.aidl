package rocks.pkb.plektra.shizuku;

import android.os.Bundle;

/**
 * the Shizuku UserService that runs the broker's commands as Shizuku's
 * user (shell over ADB, root on a rooted phone). Only Plektra holds this binder: Shizuku hands it
 * to the app that bound the service, after the user allowed Plektra in Shizuku.
 *
 * Change this file and you MUST bump ShizukuUserServiceShell.AIDL_VERSION (a unit test pins this
 * file's hash to it), so a stale service instance is never reused across an interface change.
 */
interface IPrivilegedShellService {
    /** Shizuku's reserved transaction code for destroying a user service. */
    void destroy() = 16777114;

    /**
     * Runs one program, argv[0] with its arguments, with no shell: `ProcessBuilder(argv)`. Stdout
     * and stderr together. The service caps the output at maxOutputBytes and kills the process
     * after deadlineMs (both clamped to the broker's own limits). Returns a ShellRunResult bundle,
     * which carries the uid the service runs as (Os.getuid()).
     */
    Bundle run(in String[] argv, int maxOutputBytes, long deadlineMs) = 1;
}
