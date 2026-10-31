package com.bluedragonmc.komodo

import com.bluedragonmc.api.grpc.consumeTransferRequest
import com.bluedragonmc.api.grpc.playerLoginRequest
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent
import com.velocitypowered.api.event.player.ServerLoginPluginMessageEvent
import com.velocitypowered.api.event.player.ServerPreConnectEvent
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.server.ServerInfo
import kotlinx.coroutines.runBlocking
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.jvm.optionals.getOrNull

class InstanceRoutingHandler {

    private val logger = LoggerFactory.getLogger(this::class.java)

    private val gameDestinations: Cache<Player, String?> = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofSeconds(30))
        .expireAfterAccess(Duration.ofSeconds(5))
        .weakKeys()
        .build()

    @Subscribe
    fun onLoginPluginMessage(event: ServerLoginPluginMessageEvent) {
        if (event.identifier.id == "bluedragonmc:get_dest") {
            // Get the player's cached destination
            val gameId = gameDestinations.getIfPresent(event.connection.player) ?: return
            // Convert the game ID to a byte array
            val bytes = gameId.toByteArray()
            // Reply to the request with these bytes
            event.result = ServerLoginPluginMessageEvent.ResponseResult.reply(bytes)
            logger.info("Sending player ${event.connection.player.username} to instance '$gameId' on server '${event.connection.serverInfo.name}'")
        } else if (event.identifier.id.startsWith("bluedragonmc:")) {
            logger.warn("Login plugin message sent on unexpected channel: '${event.identifier.id}'")
            event.result = ServerLoginPluginMessageEvent.ResponseResult.unknown()
        }
    }

    @Subscribe
    fun onServerConnect(event: ServerPreConnectEvent) {
        gameDestinations.get(event.player) {
            // If there is no destination instance specified,
            // a lobby on that server should be used.
            if (event.originalServer != null) {
                if (event.result.server.isPresent) {
                    val (registeredServer, lobbyInstance) = Komodo.INSTANCE.getLobby(event.result.server.get().serverInfo.name)
                    if (registeredServer == null || lobbyInstance == null) {
                        event.result = ServerPreConnectEvent.ServerResult.denied()
                    }
                    return@get lobbyInstance
                }
            }
            return@get null
        }
    }

    @Subscribe
    fun onPlayerJoin(event: PlayerChooseInitialServerEvent) {
        if (routeTransferredPlayer(event)) {
            registerLogin(event.player)
            return
        }

        // When a player joins, send them to the lobby with the most players
        val (registeredServer, lobbyInstance) = Komodo.INSTANCE.getLobby()
        if (registeredServer != null && !lobbyInstance.isNullOrEmpty()) {
            event.setInitialServer(registeredServer)
            route(event.player, lobbyInstance)
            logger.info("Routing player ${event.player.username} to instance '$lobbyInstance' on server '${registeredServer.serverInfo.name}'.")

            registerLogin(event.player)
            return
        }

        logger.warn("No lobby found for ${event.player} to join!")
        event.player.disconnect(
            Component.text(
                "No lobby was found for you to join! Try rejoining in a few minutes.",
                NamedTextColor.RED
            )
        )
    }

    private fun routeTransferredPlayer(event: PlayerChooseInitialServerEvent): Boolean {
        val player = event.player
        val transfer = try {
            runBlocking {
                Stubs.playerTransfer.withDeadlineAfter(3, TimeUnit.SECONDS)
                    .consumeTransfer(consumeTransferRequest {
                        playerUuid = player.uniqueId.toString()
                    })
            }
        } catch (e: Throwable) {
            logger.warn("Could not consume a pending proxy transfer for ${player.username}", e)
            return false
        }

        if (!transfer.found) return false

        val proxyServer = Komodo.INSTANCE.proxyServer
        val targetAddress = InetSocketAddress(transfer.gameServerIp, transfer.gameServerPort)
        val existingServer = proxyServer.getServer(transfer.serverName).getOrNull()
        val registeredServer = if (existingServer != null && existingServer.serverInfo.address == targetAddress) {
            existingServer
        } else {
            if (existingServer != null) {
                proxyServer.unregisterServer(existingServer.serverInfo)
            }
            logger.info(
                "Registering transferred server ${transfer.serverName} at " +
                        "${transfer.gameServerIp}:${transfer.gameServerPort}"
            )
            proxyServer.registerServer(ServerInfo(transfer.serverName, targetAddress))
        }

        // Set the destination before the initial server is connected so
        // ServerPreConnectEvent does not redirect the player to a lobby.
        route(player, transfer.gameId)
        event.setInitialServer(registeredServer)

        logger.info(
            "Routing transferred player ${player.username} to instance '${transfer.gameId}' " +
                    "on server '${transfer.serverName}'."
        )
        return true
    }

    private fun registerLogin(player: Player) {
        runBlocking {
            Stubs.playerTracking.playerLogin(playerLoginRequest {
                username = player.username
                uuid = player.uniqueId.toString()
                proxyPodName = System.getenv("HOSTNAME")
            })
        }
    }

    fun route(player: Player, gameId: String) {
        gameDestinations.put(player, gameId)
    }
}