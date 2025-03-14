package com.bluedragonmc.komodo

import com.bluedragonmc.api.grpc.playerLoginRequest
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent
import com.velocitypowered.api.event.player.ServerLoginPluginMessageEvent
import com.velocitypowered.api.event.player.ServerPreConnectEvent
import com.velocitypowered.api.proxy.Player
import kotlinx.coroutines.runBlocking
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.slf4j.LoggerFactory
import java.time.Duration

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
        // When a player joins, send them to the lobby with the most players
        val (registeredServer, lobbyInstance) = Komodo.INSTANCE.getLobby()
        if (registeredServer != null && !lobbyInstance.isNullOrEmpty()) {
            event.setInitialServer(registeredServer)
            route(event.player, lobbyInstance)
            logger.info("Routing player ${event.player.username} to instance '$lobbyInstance' on server '${registeredServer.serverInfo.name}'.")

            runBlocking {
                Stubs.playerTracking.playerLogin(playerLoginRequest {
                    username = event.player.username
                    uuid = event.player.uniqueId.toString()
                    proxyPodName = System.getenv("HOSTNAME")
                })
            }

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

    fun route(player: Player, gameId: String) {
        gameDestinations.put(player, gameId)
    }
}