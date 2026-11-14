package rocks.pkb.plektra.shizuku

import java.util.concurrent.Future

/**
 * the bookkeeping behind [ShizukuUserServiceShell], kept apart from Shizuku
 * so it can be tested on the JVM. Everything is guarded by ONE lock: the busy count, the bound
 * service, the current binding (what teardown needs) and the call in flight. The idle/hide unbind
 * decides under that same lock, so it can never tear down a service a command has already claimed.
 *
 * Every bind gets a new generation number. A callback from an older connection (a late
 * `onServiceDisconnected` of a service that was already unbound) carries its generation and is
 * ignored: it cannot null a newer service or cancel a newer command's call. The binding is only
 * cleared by an unbind that takes it, under the lock, so a newer bind can never be left without a
 * binding to tear down.
 *
 * [S] is the service interface, [B] what the caller needs to undo one bind (its args and the
 * connection object it passed). The [post] callbacks run under the lock, so binds and unbinds
 * reach the main thread in the same order they were decided.
 */
internal class ServiceBindingState<S : Any, B : Any> {

    /** A service and the generation of the bind it came from. */
    class Claimed<S>(val service: S, val generation: Long)

    private val lock = Any()
    private var generation = 0L
    private var service: S? = null
    private var binding: B? = null
    private var busy = 0
    private var inFlight: Future<*>? = null

    /** A command starts: from now on the idle unbind leaves the service alone. */
    fun claim() = synchronized(lock) { busy++ }

    /** A command ended. True when it was the last one (the idle timer should start). */
    fun release(): Boolean = synchronized(lock) { --busy == 0 }

    /** The bound service, with the generation it belongs to; null when none is bound. */
    fun current(): Claimed<S>? = synchronized(lock) { service?.let { Claimed(it, generation) } }

    /**
     * Starts a bind: a new generation, no service until it connects, and the new binding recorded
     * before [post] is called. [create] builds the binding for the new generation.
     */
    fun beginBind(create: (generation: Long) -> B, post: (B) -> Unit): Long = synchronized(lock) {
        val gen = ++generation
        val next = create(gen)
        service = null
        binding = next
        post(next)
        gen
    }

    /** A connection of [gen] delivered [s]. False (and nothing changes) when [gen] is not current. */
    fun connected(gen: Long, s: S): Boolean = synchronized(lock) {
        if (gen != generation) return false
        service = s
        true
    }

    /**
     * A connection of [gen] was lost. Only the current generation forgets its service and ends the
     * call in flight; a late callback of an older connection affects nothing.
     */
    fun disconnected(gen: Long) = synchronized(lock) {
        if (gen != generation) return@synchronized
        service = null
        inFlight?.cancel(true)
    }

    /** The service of generation [gen] answered with a dead binder: forget it, if still current. */
    fun dropService(gen: Long) = synchronized(lock) {
        if (gen == generation) service = null
    }

    fun setInFlight(call: Future<*>) = synchronized(lock) { inFlight = call }

    fun clearInFlight(call: Future<*>) = synchronized(lock) { if (inFlight === call) inFlight = null }

    /**
     * Idle/hide unbind: decided under the lock together with the busy count. Does nothing while a
     * command is running or nothing is bound.
     */
    fun unbindIfIdle(post: (B) -> Unit): Boolean = synchronized(lock) {
        if (busy != 0) return false
        takeBinding(post)
    }

    /**
     * Kills the service of [gen] (a call that never answered). Does nothing when a newer bind has
     * replaced it, so a stuck command cannot tear down its successor's service.
     */
    fun unbindGeneration(gen: Long, post: (B) -> Unit): Boolean = synchronized(lock) {
        if (gen != generation) return false
        takeBinding(post)
    }

    private fun takeBinding(post: (B) -> Unit): Boolean {
        val b = binding ?: return false
        binding = null
        service = null
        generation++ // any callback of the connection being unbound is now stale
        post(b)
        return true
    }
}
