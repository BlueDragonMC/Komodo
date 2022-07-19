package com.bluedragonmc.komodo

import com.bluedragonmc.messages.*
import com.bluedragonmc.messagingsystem.AMQPClient
import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.server.ServerInfo
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.UUID
import java.util.logging.Logger
import kotlin.jvm.optionals.getOrNull

@Plugin(id = "komodo",
    name = "Komodo",
    version = "0.0.1",
    description = "BlueDragon's Velocity plugin that handles coordination with our service",
    url = "https://bluedragonmc.com",
    authors = ["FluxCapacitor2"])
class Komodo {

    @Inject
    lateinit var logger: Logger

    @Inject
    lateinit var server: ProxyServer

    @Inject
    @DataDirectory
    lateinit var dataDirectory: Path

    lateinit var client: AMQPClient

    private val instanceMap = mutableMapOf<UUID, MutableList<UUID>>()
    private val lobbies = mutableListOf<UUID>()

    @OptIn(ExperimentalStdlibApi::class)
    @Subscribe
    fun onStart(event: ProxyInitializeEvent) {
        client = AMQPClient(polymorphicModuleBuilder = polymorphicModuleBuilder)
        client.subscribe(PingMessage::class) { message ->
            logger.info("Adding server at ${message.containerId}:25565")
            val str = message.containerId.toString()
            server.registerServer(ServerInfo(str, InetSocketAddress(str, 25565)))
        }
        client.subscribe(NotifyInstanceCreatedMessage::class) { message ->
            instanceMap.getOrPut(message.containerId) { mutableListOf() }.add(message.instanceId)
            if (message.gameType.name == lobbyGameName) {
                logger.info("New lobby instance: instanceId=${message.instanceId}, containerId=${message.containerId}")
                lobbies.add(message.instanceId)
            }
        }
        client.subscribe(NotifyInstanceRemovedMessage::class) { message ->
            instanceMap[message.containerId]?.remove(message.instanceId)
            if (instanceMap[message.containerId]?.size == 0) {
                instanceMap.remove(message.containerId)
                server.unregisterServer(server.getServer(message.containerId.toString()).getOrNull()?.serverInfo
                    ?: run {
                        logger.warning("Tried to unregister server that didn't exist! containerId=${message.containerId}")
                        return@subscribe
                    })
            }
        }
        client.subscribe(SendPlayerToInstanceMessage::class) { message ->
            val player = server.getPlayer(message.player).getOrNull() ?: return@subscribe
            val server = server.getServer(getContainerId(message.instance)).getOrNull() ?: run {
                logger.warning("Received SendPlayerToInstanceMessage for unknown server: instanceId=${message.instance}, containerId=???")
                return@subscribe
            }
            // Don't try to send a player to their own server
            if (player.currentServer.isPresent && player.currentServer == server) return@subscribe
            player.createConnectionRequest(server).fireAndForget()
            logger.info("Sending player $player to server $server")
        }
    }

    private var lastCreateInstanceMessage: Long = 0L
    private val minServerCreationDelay = 30000 // 30 seconds between [RequestCreateInstanceMessage]s
    private val lobbyGameName = "Lobby"

    @Subscribe
    fun onPlayerJoin(event: PlayerChooseInitialServerEvent) {
        // When a player joins, send them to the lobby with the most players
        val backendServer = server.allServers.filter { registeredServer ->
            instanceMap[UUID.fromString(registeredServer.serverInfo.name)]?.any { lobbies.contains(it) } == true
        }.maxByOrNull { it.playersConnected.size }
        if(backendServer != null) {
            event.setInitialServer(backendServer)
            client.publish(SendPlayerToInstanceMessage(event.player.uniqueId,
                UUID.fromString(backendServer.serverInfo.name)))
        } else {
            logger.warning("No lobby found for ${event.player} to join!")
            event.player.disconnect(Component.text("No lobby was found for you to join! Try rejoining in a few minutes.", NamedTextColor.RED))
            if(System.currentTimeMillis() - lastCreateInstanceMessage > minServerCreationDelay) {
                val containerId = instanceMap.minByOrNull { it.value.size }?.key
                if(containerId != null) {
                    client.publish(RequestCreateInstanceMessage(containerId, GameType(lobbyGameName)))
                    lastCreateInstanceMessage = System.currentTimeMillis()
                }
            }
        }
    }

    private fun getContainerId(instance: UUID): String? {
        for ((containerId, instances) in instanceMap) {
            if (instances.contains(instance)) return containerId.toString()
        }
        return null
    }

    @Subscribe
    fun onStop(event: ProxyShutdownEvent) {

    }
}