package com.bluedragonmc.komodo

import com.bluedragonmc.api.grpc.ServerTracking
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyPingEvent
import com.velocitypowered.api.proxy.server.ServerPing.SamplePlayer
import com.velocitypowered.api.proxy.server.ServerPing.Version
import com.velocitypowered.api.util.Favicon
import kotlinx.coroutines.*
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import java.io.File
import java.nio.charset.Charset
import java.nio.file.FileSystems
import java.nio.file.Paths
import java.nio.file.StandardWatchEventKinds.*
import java.time.Duration
import java.util.*
import kotlin.coroutines.CoroutineContext
import kotlin.io.path.inputStream

class ServerListPingHandler {

    private val configDir = Paths.get("/proxy/config/")

    private fun watchConfig() {
        val watcher = FileSystems.getDefault().newWatchService()
        configDir.register(watcher, ENTRY_MODIFY, ENTRY_CREATE, ENTRY_DELETE)
        while (true) {
            val key = watcher.take()
            for (event in key.pollEvents()) {
                reloadConfig()
                Komodo.INSTANCE.logger.info("Updated MOTD configuration (File changed on disk)")
            }
            if (!key.reset()) break
        }
        watcher.close()
    }

    private fun reloadConfig() {
        val props = Properties().apply {
            load(configDir.resolve("proxy-config.properties").inputStream())
        }
        val lines = (1..2).map { i ->
            val text = props.getProperty("motd.line_$i.text")
            val center = props.getProperty("motd.line_$i.center").lowercase() == "true"
            if (center) {
                (Component.empty() + MiniMessage.miniMessage().deserialize(text)).center(92)
            } else {
                MiniMessage.miniMessage().deserialize(text)
            }
        }
        motd = lines[0] + Component.newline() + lines[1]
    }

    private var motd: Component = Component.empty()

    private var favicon = Favicon("data:image/png;base64," + runCatching {
        String(Base64.getEncoder().encode(File("favicon_64.png").readBytes()), Charset.forName("UTF-8"))
    }.getOrElse { "" })

    private var lastOnlinePlayerCount = 0

    @Subscribe
    fun onPing(event: ProxyPingEvent) {

        val samplePlayers = Komodo.INSTANCE.proxyServer.allPlayers
            .shuffled()
            .take(5)
            .map { SamplePlayer(it.username, it.uniqueId) }

        event.ping = event.ping.asBuilder()
            .favicon(favicon)
            .description(motd)
            .onlinePlayers(lastOnlinePlayerCount)
            .version(Version(760, "1.19.2"))
            .samplePlayers(*samplePlayers.toTypedArray())
            .build()
    }

    private val pool = object : CoroutineScope {
        override val coroutineContext: CoroutineContext =
            Dispatchers.IO + SupervisorJob() + CoroutineName("File I/O")
    }

    init {
        pool.launch {
            reloadConfig() // Initially load the config
            while (true) {
                watchConfig() // Watch the config for changes, updating the MOTD as necessary
            }
        }
        Komodo.INSTANCE.proxyServer.scheduler.buildTask(Komodo.INSTANCE) {
            lastOnlinePlayerCount = runBlocking {
                Stubs.instanceSvc.getTotalPlayerCount(ServerTracking.PlayerCountRequest.getDefaultInstance()).totalPlayers
            }
        }.repeat(Duration.ofSeconds(30)).schedule()
    }
}
