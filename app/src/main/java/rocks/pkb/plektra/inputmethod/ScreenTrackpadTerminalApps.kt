package rocks.pkb.plektra.inputmethod

/**
 * Apps where the screen trackpad also works with no editable text field in focus.
 *
 * Terminal emulators and SSH clients focus a view whose input type is TYPE_NULL, so the
 * trackpad (which opens only over a real editable field) never appeared there. The user picks
 * the apps; apps not on the list keep the normal behaviour.
 */
internal object ScreenTrackpadTerminalApps {
    /** What the list holds until the user edits it (a stored list, even an empty one, replaces it). */
    val DEFAULT_PACKAGES: Set<String> = linkedSetOf(
        "com.termux",
        "app.getmoshi.android", // Moshi
        "com.stably.orca.mobile", // Orca (not com.facebook.orca)
        "com.android.virtualization.terminal", // Android Terminal
        "in.sunilpaulmathew.ashell",
        "in.hridayan.ashell",
        "org.connectbot",
        "com.sonelli.juicessh",
        "com.server.auditor.ssh.client", // Termius
        "com.termoneplus", // Terminal Emulator (Term-onePlus)
        "tech.ula", // UserLAnd
    )

    /** Most entries kept from a stored list; a restored backup could hold any number. */
    const val MAX_PACKAGES = 256
    private const val MAX_PACKAGE_LENGTH = 255
    private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

    /** Keeps only syntactically valid package names, at most [MAX_PACKAGES] of them. */
    fun sanitize(stored: Set<String>): Set<String> =
        stored.asSequence()
            .filter { it.length <= MAX_PACKAGE_LENGTH && PACKAGE_NAME.matches(it) }
            .take(MAX_PACKAGES)
            .toSet()

    fun isListed(packages: Set<String>, packageName: String?): Boolean =
        !packageName.isNullOrEmpty() && packageName in packages

    /**
     * Whether the overlay may exist at all. An editable field always qualifies (today's
     * behaviour). Without one, only a listed app with a started input connection does: the
     * overlay's only job is to send DPAD key events through that connection, and without one it
     * would be a pointless full-screen touch catcher. See [hasStartedInputConnection] for what
     * counts as started.
     */
    fun mayOpenOverlay(editableFieldFocused: Boolean, hasInputConnection: Boolean, appListed: Boolean): Boolean =
        editableFieldFocused || (appListed && hasInputConnection)

    /**
     * Whether the focused view really handed the IME an input connection. An input session must
     * be started (not finished or unbound since), and the connection must not be the binding's
     * own: InputMethodService.getCurrentInputConnection() falls back to the binding connection
     * whenever a client is bound, even when the focused view returned none.
     */
    fun hasStartedInputConnection(inputStarted: Boolean, current: Any?, bindingConnection: Any?): Boolean =
        inputStarted && current != null && current !== bindingConnection
}
