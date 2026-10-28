package com.bluedragonmc.komodo

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.scheduler.ScheduledTask
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger

/**
 * Kubernetes lifecycle endpoints and graceful shutdown/draining.
 */
class ProxyLifecycle(
    private val plugin: Any,
    private val proxyServer: ProxyServer,
    private val logger: Logger,
) {

    private val lifecyclePort =
        (System.getenv("KOMODO_HEALTH_PORT")?.toIntOrNull() ?: 8081).coerceIn(1, 65535)
    private val heartbeatTimeoutNanos =
        envLong("KOMODO_HEALTH_TIMEOUT_MS", 1_000L).coerceAtLeast(1) * NANOS_PER_MILLI
    private val countdownSeconds =
        envLong("KOMODO_SHUTDOWN_COUNTDOWN_SECONDS", 2 * 60 * 60L).coerceAtLeast(1)
    private val drainQuiescenceNanos =
        envLong("KOMODO_DRAIN_QUIESCENCE_MS", 15_000L) * NANOS_PER_MILLI

    private val lastHeartbeatNanos = AtomicLong(0L)
    private val initialized = AtomicBoolean(false)
    private val draining = AtomicBoolean(false)
    private val shutdownComplete = AtomicBoolean(false)

    @Volatile
    private var drainFuture: CompletableFuture<Unit>? = null

    @Volatile
    private var countdownTask: ScheduledFuture<*>? = null

    @Volatile
    private var countdown: ShutdownCountdown? = null

    private var emptySinceNanos: Long? = null
    private var heartbeatTask: ScheduledTask? = null

    @Volatile
    private var httpServer: HttpServer? = null

    @Volatile
    private var httpExecutor: ExecutorService? = null

    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "proxy-shutdown-countdown").apply { isDaemon = true }
        }

    fun start() {
        if (System.getenv("KOMODO_LIFECYCLE_DISABLED") != null) {
            logger.warning("Proxy lifecycle endpoints are disabled by KOMODO_LIFECYCLE_DISABLED.")
            initialized.set(true)
            return
        }

        try {
            lastHeartbeatNanos.set(System.nanoTime())
            heartbeatTask = proxyServer.scheduler.buildTask(plugin, Runnable {
                lastHeartbeatNanos.set(System.nanoTime())
            }).repeat(100, TimeUnit.MILLISECONDS).schedule()

            val executor = Executors.newVirtualThreadPerTaskExecutor()
            val server = HttpServer.create(InetSocketAddress(lifecyclePort), 0)
            server.executor = executor
            server.createContext("/health") { exchange ->
                val healthy = isHealthy()
                respond(exchange, if (healthy) 200 else 503, if (healthy) "ok" else "unhealthy")
            }
            server.createContext("/ready") { exchange ->
                val ready = isReady()
                respond(exchange, if (ready) 200 else 503, if (ready) "ready" else "not ready")
            }
            server.createContext("/drain") { exchange -> handleDrain(exchange) }
            server.start()

            httpServer = server
            httpExecutor = executor
            initialized.set(true)
            logger.info("Proxy lifecycle endpoints listening on port $lifecyclePort")
            Runtime.getRuntime().addShutdownHook(Thread(::stop, "komodo-lifecycle-shutdown"))
        } catch (e: Exception) {
            logger.severe("Failed to start proxy lifecycle endpoints on port $lifecyclePort")
            e.printStackTrace()
            throw e
        }
    }

    fun stop() {
        runCatching { httpServer?.stop(0) }
        httpServer = null
        runCatching { httpExecutor?.shutdownNow() }
        httpExecutor = null
        runCatching { heartbeatTask?.cancel() }
        heartbeatTask = null
        scheduler.shutdownNow()
    }

    private fun isHealthy(): Boolean {
        if (!initialized.get() || proxyServer.isShuttingDown) return false
        val lastHeartbeat = lastHeartbeatNanos.get()
        return lastHeartbeat != 0L && System.nanoTime() - lastHeartbeat <= heartbeatTimeoutNanos
    }

    private fun isReady(): Boolean = isHealthy() && !draining.get()

    @Synchronized
    fun beginDrain(): CompletableFuture<Unit> {
        drainFuture?.let { return it }

        draining.set(true)
        logger.info("Proxy drain requested; starting a ${countdownSeconds}s countdown.")

        val future = CompletableFuture<Unit>()
        drainFuture = future

        try {
            countdownTask = scheduler.scheduleAtFixedRate(::drainTick, 0, 1, TimeUnit.SECONDS)
        } catch (e: RejectedExecutionException) {
            logger.severe("Could not schedule the proxy shutdown countdown: $e")
            future.complete(Unit)
        }

        return future
    }

    private fun drainTick() {
        if (shutdownComplete.get()) return

        try {
            val players = proxyServer.allPlayers

            val activeCountdown = countdown
            if (activeCountdown == null) {
                if (players.isEmpty()) {
                    val emptySince = emptySinceNanos
                    if (emptySince == null) {
                        emptySinceNanos = System.nanoTime()
                    } else if (System.nanoTime() - emptySince >= drainQuiescenceNanos) {
                        completeDrain(disconnectPlayers = false)
                    }
                    return
                }

                emptySinceNanos = null
                val newCountdown = ShutdownCountdown(
                    totalSeconds = countdownSeconds,
                    onMilestone = ::announceMilestone,
                    onComplete = { completeDrain(disconnectPlayers = true) },
                )
                newCountdown.start()
                countdown = newCountdown
                return
            }

            activeCountdown.tick()

            if (players.isEmpty()) {
                val emptySince = emptySinceNanos
                if (emptySince == null) {
                    emptySinceNanos = System.nanoTime()
                } else if (System.nanoTime() - emptySince >= drainQuiescenceNanos) {
                    completeDrain(disconnectPlayers = false)
                }
            } else {
                emptySinceNanos = null
            }
        } catch (e: Throwable) {
            logger.severe("Error while running the proxy shutdown countdown: $e")
            e.printStackTrace()
        }
    }

    /**
     * Re-sends the current countdown to a player every time they switch game servers.
     */
    fun onServerConnected(player: Player) {
        if (!draining.get()) return
        val activeCountdown = countdown ?: return
        val remaining = activeCountdown.remainingSeconds()
        if (remaining <= 0) return
        runCatching { player.sendMessage(countdownMessage(remaining)) }
    }

    private fun announceMilestone(remainingSeconds: Long) {
        val message = countdownMessage(remainingSeconds)
        proxyServer.allPlayers.forEach { player ->
            runCatching { player.sendMessage(message) }
        }
    }

    private fun countdownMessage(remainingSeconds: Long) = Component.text(
        "This proxy is restarting in ${ShutdownCountdown.formatChat(remainingSeconds)}. When this happens, you will be disconnected from the server. Please leave and rejoin in between games to avoid interruptions.",
        NamedTextColor.RED,
    )

    private fun completeDrain(disconnectPlayers: Boolean) {
        if (!shutdownComplete.compareAndSet(false, true)) return

        countdownTask?.cancel(false)
        countdownTask = null
        countdown = null

        if (disconnectPlayers) {
            val players = proxyServer.allPlayers.toList()
            if (players.isNotEmpty()) {
                val chatMessage = Component.text("The proxy is restarting now.", NamedTextColor.RED)
                val kickMessage = Component.text("The proxy is restarting. Please reconnect.", NamedTextColor.RED)
                players.forEach { player ->
                    runCatching {
                        player.sendMessage(chatMessage)
                        player.disconnect(kickMessage)
                    }
                }

                // Give the disconnect packets a moment to reach the clients.
                runCatching { Thread.sleep(2_000) }
            }
        }

        drainFuture?.complete(Unit)
        logger.info("Proxy drain finished (disconnectPlayers=$disconnectPlayers).")
    }

    private fun handleDrain(exchange: HttpExchange) {
        val future = beginDrain()
        try {
            future.join()
        } catch (e: Throwable) {
            logger.severe("The proxy shutdown drain did not complete normally: $e")
            e.printStackTrace()
        }
        respond(exchange, 200, "drained")
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun envLong(name: String, default: Long): Long =
        System.getenv(name)?.toLongOrNull()?.coerceAtLeast(0) ?: default

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
