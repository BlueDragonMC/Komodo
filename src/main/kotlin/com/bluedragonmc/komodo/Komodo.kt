package com.bluedragonmc.komodo

import com.bluedragonmc.api.grpc.findLobbyRequest
import com.bluedragonmc.api.grpc.playerLogoutRequest
import com.bluedragonmc.komodo.command.AddServerCommand
import com.bluedragonmc.komodo.command.RemoveServerCommand
import com.bluedragonmc.komodo.handler.FailoverHandler
import com.bluedragonmc.komodo.handler.InstanceRoutingHandler
import com.bluedragonmc.komodo.handler.ServerListPingHandler
import com.bluedragonmc.komodo.jukebox.JukeboxState
import com.bluedragonmc.komodo.rpc.JukeboxService
import com.bluedragonmc.komodo.rpc.PlayerHolderService
import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Dependency
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.server.RegisteredServer
import com.velocitypowered.api.proxy.server.ServerInfo
import io.grpc.Server
import io.grpc.ServerBuilder
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import kotlin.jvm.optionals.getOrElse
import kotlin.system.exitProcess

@Plugin(
    id = "komodo",
    name = "Komodo",
    version = "0.1.0",
    description = "BlueDragon's Velocity plugin that handles coordination with our service",
    url = "https://bluedragonmc.com",
    authors = ["FluxCapacitor2"],
    dependencies = [Dependency(id = "bluedragon-jukebox", optional = false)]
)
class Komodo {

    @Inject
    lateinit var logger: Logger

    @Inject
    lateinit var proxyServer: ProxyServer

    internal val instanceRoutingHandler = InstanceRoutingHandler()

    private lateinit var server: Server

    companion object {
        lateinit var INSTANCE: Komodo
    }

    @Subscribe
    fun onInit(event: ProxyInitializeEvent) {
        try {
            INSTANCE = this
            server = ServerBuilder.forPort(50051)
                .addService(PlayerHolderService(proxyServer, logger, instanceRoutingHandler))
                .addService(JukeboxService(proxyServer, logger))
                .build()
            server.start()

            // Initialize gRPC channel to Puffin
            runBlocking {
                Stubs.initialize()
            }

            // Subscribe to events
            proxyServer.eventManager.register(this, ServerListPingHandler())
            proxyServer.eventManager.register(this, instanceRoutingHandler)
            proxyServer.eventManager.register(this, FailoverHandler())
            proxyServer.eventManager.register(this, JukeboxState)

            // Register commands
            proxyServer.commandManager.register(AddServerCommand.create(proxyServer))
            proxyServer.commandManager.register(RemoveServerCommand.create(proxyServer))

            // Unregister empty servers every hour
            proxyServer.scheduler.buildTask(this) {
                proxyServer.allServers.filter { it.playersConnected.isEmpty() }.forEach { server ->
                    proxyServer.unregisterServer(server.serverInfo)
                }
            }.repeat(Duration.ofHours(1)).schedule()

        } catch (e: Throwable) {
            logger.severe("There was an error initializing Komodo.")
            e.printStackTrace()
            exitProcess(1)
        }
    }

    @Subscribe
    fun onPlayerLeave(event: DisconnectEvent) {
        runBlocking {
            Stubs.playerTracking.playerLogout(playerLogoutRequest {
                username = event.player.username
                uuid = event.player.uniqueId.toString()
            })
        }
    }

    fun getLobby(serverName: String? = null, excluding: String? = null): Pair<RegisteredServer?, String?> =
        runBlocking {
            val response = Stubs.discovery.findLobby(findLobbyRequest {
                if (serverName != null)
                    this.includeServerNames += serverName
                if (excluding != null)
                    this.excludeServerNames += excluding
            })

            if (!response.found) return@runBlocking null to null // :( no lobby was found

            return@runBlocking proxyServer.getServer(response.serverName).getOrElse {
                logger.info("Registering server ${response.serverName} at address ${response.ip}:${response.port}")
                proxyServer.registerServer(
                    ServerInfo(response.serverName, InetSocketAddress(response.ip, response.port))
                )
            } to response.instanceUuid
        }

    @Subscribe
    fun onStop(event: ProxyShutdownEvent) {
        for (player in proxyServer.allPlayers) {
            runBlocking {
                Stubs.playerTracking.playerLogout(playerLogoutRequest {
                    username = player.username
                    uuid = player.uniqueId.toString()
                })
            }
        }
        if (::server.isInitialized && !server.isShutdown) {
            server.shutdown()
            server.awaitTermination(10, TimeUnit.SECONDS)
        }
        Stubs.shutdown()
    }
}