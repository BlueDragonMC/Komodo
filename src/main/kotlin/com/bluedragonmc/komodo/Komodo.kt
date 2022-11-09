package com.bluedragonmc.komodo

import com.bluedragonmc.api.grpc.*
import com.bluedragonmc.api.grpc.GetPlayersResponseKt.connectedPlayer
import com.google.inject.Inject
import com.google.protobuf.Empty
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.player.KickedFromServerEvent
import com.velocitypowered.api.event.player.ServerPostConnectEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.server.RegisteredServer
import com.velocitypowered.api.proxy.server.ServerInfo
import io.grpc.ServerBuilder
import kotlinx.coroutines.runBlocking
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import java.net.InetSocketAddress
import java.util.*
import java.util.logging.Logger
import kotlin.jvm.optionals.getOrElse
import kotlin.jvm.optionals.getOrNull

@OptIn(ExperimentalStdlibApi::class)
@Plugin(
    id = "komodo",
    name = "Komodo",
    version = "0.0.3",
    description = "BlueDragon's Velocity plugin that handles coordination with our service",
    url = "https://bluedragonmc.com",
    authors = ["FluxCapacitor2"]
)
class Komodo {

    @Inject
    lateinit var logger: Logger

    @Inject
    lateinit var proxyServer: ProxyServer

    private val lastFailover = mutableMapOf<Player, Long>()

    private val instanceRoutingHandler = InstanceRoutingHandler()

    companion object {
        lateinit var INSTANCE: Komodo
    }

    @Subscribe
    fun onInit(event: ProxyInitializeEvent) {
        INSTANCE = this
        val server = ServerBuilder.forPort(50051).addService(PlayerHolderService()).build()
        server.start()

        // Pre-initialize gRPC channel
        Stubs.preInitialize()

        // Subscribe to events
        proxyServer.eventManager.register(this, ServerListPingHandler())
        proxyServer.eventManager.register(this, instanceRoutingHandler)
    }

    inner class PlayerHolderService : PlayerHolderGrpcKt.PlayerHolderCoroutineImplBase() {
        @OptIn(ExperimentalStdlibApi::class)
        override suspend fun sendPlayer(request: PlayerHolderOuterClass.SendPlayerRequest): PlayerHolderOuterClass.SendPlayerResponse {

            val uuid = UUID.fromString(request.playerUuid)
            val player = proxyServer.getPlayer(uuid).getOrNull()
            val registeredServer = proxyServer.getServer(request.serverName).getOrNull() ?: run {
                logger.info("Registering server ${request.serverName} at ${request.gameServerIp}:${request.gameServerPort} to send player $player to it.")
                proxyServer.registerServer(
                    ServerInfo(request.serverName, InetSocketAddress(request.gameServerIp, request.gameServerPort))
                )
            }
            // Don't try to send a player to their current server
            if (player == null || registeredServer == null) {
                return sendPlayerResponse {
                    playerFound = player != null
                }
            }
            if (player.currentServer.getOrNull()?.serverInfo?.name == registeredServer.serverInfo.name) {
                return sendPlayerResponse {
                    playerFound = true
                    successes += PlayerHolderOuterClass.SendPlayerResponse.SuccessFlags.SET_SERVER
                }
            }
            try {
                instanceRoutingHandler.route(player, request.instanceId.toString())
                player.createConnectionRequest(registeredServer).fireAndForget()
            } catch (e: Throwable) {
                logger.warning("Error sending player ${player.username} to server $registeredServer!")
                e.printStackTrace()
            }
            logger.info("Sending player $player to server $registeredServer and instance ${request.instanceId}")
            return sendPlayerResponse {
                playerFound = true
                successes += PlayerHolderOuterClass.SendPlayerResponse.SuccessFlags.SET_SERVER
                successes += PlayerHolderOuterClass.SendPlayerResponse.SuccessFlags.SET_INSTANCE
            }
        }

        override suspend fun getPlayers(request: Empty): PlayerHolderOuterClass.GetPlayersResponse {
            return getPlayersResponse {
                proxyServer.allPlayers.forEach { player ->
                    this.players += connectedPlayer {
                        this.uuid = player.uniqueId.toString()
                        this.username = player.username
                        if (player.currentServer.isPresent) {
                            this.serverName = player.currentServer.get().serverInfo.name
                        }
                    }
                }
            }
        }
    }

    @Subscribe
    fun onServerPostConnect(event: ServerPostConnectEvent) {
        if (event.previousServer?.playersConnected?.isEmpty() == true) {
            logger.info("Unregistering server ${event.previousServer?.serverInfo?.name} because its last player was sent to another server.")
            proxyServer.unregisterServer(event.previousServer!!.serverInfo)
        }
    }

    @Subscribe
    fun onPlayerLeave(event: DisconnectEvent) {
        lastFailover.remove(event.player)
        runBlocking {
            Stubs.playerTracking.playerLogout(playerLogoutRequest {
                username = event.player.username
                uuid = event.player.uniqueId.toString()
            })
        }

        val server = event.player.currentServer.getOrNull()?.server
        if (server?.playersConnected?.isEmpty() == true) {
            logger.info("Unregistering server $server because its last player logged out.")
            proxyServer.unregisterServer(server.serverInfo)
        }
    }

    @Subscribe
    fun onPlayerKick(event: KickedFromServerEvent) {
        if (event.kickedDuringServerConnect() || ((lastFailover[event.player]
                ?: 0L) + 10000 > System.currentTimeMillis())
        )
            return

        lastFailover[event.player] = System.currentTimeMillis()

        val (registeredServer, lobbyInstance) = getLobby(excluding = event.server.serverInfo.name)
        val msg = Component.text("You were kicked from ${event.server.serverInfo.name}: ", NamedTextColor.RED)
            .append(event.serverKickReason.orElse(Component.text("No reason specified", NamedTextColor.DARK_GRAY)))
        if (registeredServer != null) {
            event.result = KickedFromServerEvent.RedirectPlayer.create(registeredServer, msg)
            instanceRoutingHandler.route(event.player, lobbyInstance)
        } else {
            event.result = KickedFromServerEvent.DisconnectPlayer.create(msg)
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
    }
}