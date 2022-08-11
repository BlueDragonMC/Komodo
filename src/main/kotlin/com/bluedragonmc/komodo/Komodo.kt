package com.bluedragonmc.komodo

import com.bluedragonmc.messages.*
import com.bluedragonmc.messagingsystem.AMQPClient
import com.bluedragonmc.messagingsystem.message.Message
import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyPingEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.network.ProtocolVersion
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.server.RegisteredServer
import com.velocitypowered.api.proxy.server.ServerInfo
import com.velocitypowered.api.proxy.server.ServerPing
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import java.net.InetSocketAddress
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import kotlin.concurrent.timer
import kotlin.jvm.optionals.getOrNull

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

    lateinit var client: AMQPClient

    private val instanceMap = mutableMapOf<UUID, MutableList<UUID>>()
    private val lobbies = mutableSetOf<UUID>()

    private val pingTimes = mutableMapOf<UUID, Long>()
    private val ownMessages = mutableListOf<Message>()

    @OptIn(ExperimentalStdlibApi::class)
    @Subscribe
    fun onStart(event: ProxyInitializeEvent) {
        client = AMQPClient(polymorphicModuleBuilder = polymorphicModuleBuilder)
        client.subscribe(PingMessage::class) { message ->
            logger.info("Adding server at ${message.containerId}:25565")
            val str = message.containerId.toString()
            proxyServer.registerServer(ServerInfo(str, InetSocketAddress(str, 25565)))
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
                pingTimes.remove(message.containerId)
                proxyServer.unregisterServer(proxyServer.getServer(message.containerId.toString())
                    .getOrNull()?.serverInfo
                    ?: run {
                        logger.warning("Tried to unregister server that didn't exist! containerId=${message.containerId}")
                        return@subscribe
                    })
                logger.info("Removed server with containerId=${message.containerId} because its last instance was removed.")
            }
        }
        client.subscribe(SendPlayerToInstanceMessage::class) { message ->
            if (ownMessages.contains(message)) { // If this message was sent by Komodo there is no reason to handle it.
                ownMessages.remove(message)
                return@subscribe
            }
            val player = proxyServer.getPlayer(message.player).getOrNull() ?: return@subscribe
            val registeredServer =
                getContainerId(message.instance)?.let { proxyServer.getServer(it).getOrNull() } ?: run {
                    logger.warning("Received SendPlayerToInstanceMessage for unknown server: instanceId=${message.instance}, containerId=???")
                    return@subscribe
                }
            // Don't try to send a player to their current server
            if (player.currentServer.getOrNull()?.serverInfo?.name == registeredServer.serverInfo.name) return@subscribe
            try {
                player.createConnectionRequest(registeredServer).fireAndForget()
            } catch (e: Throwable) {
                logger.warning("Error sending player ${player.username} to server $registeredServer!")
                e.printStackTrace()
            }
            logger.info("Sending player $player to server $registeredServer")
        }
        client.subscribe(ServerSyncMessage::class) { message ->
            // Every 30 seconds, this message is sent from each Minestom server.
            // It prevents desync between the proxy and the backend servers by updating the instance list on an interval.
            if (!instanceMap.containsKey(message.containerId)) {
                logger.info("New container added during sync: ${message.containerId}")
                val str = message.containerId.toString()
                proxyServer.registerServer(ServerInfo(str, InetSocketAddress(str, 25565)))
                logger.info("(Sync) Registered server at $str:25565")
            }
            instanceMap[message.containerId] = message.instances.map { it.instanceId }.toMutableList()
            val newLobbies = message.instances.filter { it.type?.name == lobbyGameName }.map { it.instanceId }
            lobbies.addAll(newLobbies)
            pingTimes[message.containerId] = System.currentTimeMillis()
        }
        timer("ping-timeout", daemon = true, period = 10_000) {
            pingTimes.entries.removeAll { (containerId, time) ->
                if (System.currentTimeMillis() - time > 300_000) { // 5 minutes
                    logger.warning("Removed server $containerId because it has not sent a ping in the last 5 minutes.")
                    instanceMap.remove(containerId)
                    runCatching {
                        proxyServer.unregisterServer(
                            proxyServer.getServer(containerId.toString()).getOrNull()?.serverInfo
                        )
                    }
                    return@removeAll true
                } else return@removeAll false
            }
        }
    }

    private var lastCreateInstanceMessage: Long = 0L
    private val minServerCreationDelay = 30000 // 30 seconds between [RequestCreateInstanceMessage]s
    private val lobbyGameName = "Lobby"
    private val enableCreateNewInstances = false

    /**
     * Use some ugly reflection to use part of Velocity's implementation that isn't exposed in its API
     * The [RegisteredServer#ping] method does not allow us to specify a protocol version, so it uses
     * `ProtocolVersion.UNKNOWN` by default. We want to change this default and specify the client's
     * protocol version.
     */
    private val callServerListPing by lazy {
        val velocityRegisteredServerClass = Class.forName("com.velocitypowered.proxy.server.VelocityRegisteredServer")
        val ping = velocityRegisteredServerClass.getDeclaredMethod(
            "ping",
            Class.forName("io.netty.channel.EventLoop"),
            ProtocolVersion::class.java
        )

        return@lazy { rs: RegisteredServer, version: ProtocolVersion ->
            ping.invoke(velocityRegisteredServerClass.cast(rs), null, version) as CompletableFuture<ServerPing>
        }
    }

    @Subscribe
    fun onServerListPing(event: ProxyPingEvent) {
        // When a client calls a server list ping, forward a ping from a backend server.
        proxyServer.allServers.forEach {
            val ping = callServerListPing(it, event.connection.protocolVersion).get(1, TimeUnit.SECONDS)
            event.ping = ping.asBuilder().onlinePlayers(proxyServer.allPlayers.size)
                .maximumPlayers(proxyServer.configuration.showMaxPlayers).build()
            return
        }
    }

    @Subscribe
    fun onPlayerJoin(event: PlayerChooseInitialServerEvent) {
        // When a player joins, send them to the lobby with the most players
        val registeredServer = proxyServer.allServers.filter { registeredServer ->
            getLobby(registeredServer.serverInfo.name) != null
        }.maxByOrNull { it.playersConnected.size }
        if (registeredServer != null) {
            event.setInitialServer(registeredServer)
            client.publish(
                SendPlayerToInstanceMessage(
                    event.player.uniqueId,
                    getLobby(registeredServer.serverInfo.name)!!
                ).also { ownMessages.add(it) }
            )
            return
        }

        logger.warning("No lobby found for ${event.player} to join!")
        event.player.disconnect(
            Component.text(
                "No lobby was found for you to join! Try rejoining in a few minutes.",
                NamedTextColor.RED
            )
        )

        if (enableCreateNewInstances) {
            if (System.currentTimeMillis() - lastCreateInstanceMessage > minServerCreationDelay) {
                val containerId = instanceMap.minByOrNull { it.value.size }?.key
                if (containerId != null) {
                    client.publish(RequestCreateInstanceMessage(containerId, GameType(lobbyGameName)))
                    lastCreateInstanceMessage = System.currentTimeMillis()
                }
            }
        }
    }

    private fun getLobby(serverName: String): UUID? =
        instanceMap[UUID.fromString(serverName)]?.firstOrNull { lobbies.contains(it) }

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