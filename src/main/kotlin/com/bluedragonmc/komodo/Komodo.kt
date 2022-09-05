package com.bluedragonmc.komodo

import com.bluedragonmc.messages.*
import com.bluedragonmc.messagingsystem.AMQPClient
import com.bluedragonmc.messagingsystem.message.Message
import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.player.KickedFromServerEvent
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyPingEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.network.ProtocolVersion
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.server.RegisteredServer
import com.velocitypowered.api.proxy.server.ServerInfo
import com.velocitypowered.api.proxy.server.ServerPing
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import java.net.InetSocketAddress
import java.net.Socket
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import kotlin.concurrent.timer
import kotlin.jvm.optionals.getOrNull

@Plugin(id = "komodo",
    name = "Komodo",
    version = "0.0.3",
    description = "BlueDragon's Velocity plugin that handles coordination with our service",
    url = "https://bluedragonmc.com",
    authors = ["FluxCapacitor2"])
class Komodo {

    @Inject
    lateinit var logger: Logger

    @Inject
    lateinit var proxyServer: ProxyServer

    lateinit var client: AMQPClient

    private val instanceMap = mutableMapOf<UUID, MutableList<UUID>>()
    private val lobbies = mutableSetOf<UUID>()

    private val ownMessages = mutableListOf<Message>()

    private val serviceDiscovery = ServiceDiscovery()

    private val lastFailover = mutableMapOf<Player, Long>()

    @OptIn(ExperimentalStdlibApi::class)
    @Subscribe
    fun onStart(event: ProxyInitializeEvent) {
        startAMQPConnection()
        timer("agones-update", daemon = true, period = 5_000) {
            runCatching {
                val gameServers = serviceDiscovery.listServices().filter { it.isReady() }
                gameServers.forEach {

                    val address = it.getHostAddress()
                    val port = it.getHostPort() ?: return@forEach
                    val uid = UUID.fromString(it.uid)

                    if (!instanceMap.containsKey(uid) || proxyServer.getServer(it.uid).isEmpty) {
                        logger.info("New game server created with UID: $uid")
                        instanceMap[uid] = mutableListOf()
                        proxyServer.registerServer(ServerInfo(it.uid, InetSocketAddress(address, port)))
                    }
                }
                instanceMap.forEach { (uid, _) ->
                    if (!gameServers.any { UUID.fromString(it.uid) == uid }) {
                        val server = proxyServer.getServer(uid.toString()).getOrNull()?.serverInfo
                        proxyServer.unregisterServer(server ?: return@forEach)
                        logger.info("Game server with uid $uid does not exist anymore and has been unregistered.")
                    }
                }
            }.onFailure {
                logger.severe("Error updating registered servers with Agones: ${it::class.java.name}")
                it.printStackTrace()
            }
        }
    }

    private fun startAMQPConnection() {
        timer("amqp-connection-test", daemon = false, period = 5_000) {
            // Check if RabbitMQ is ready for requests
            try {
                // Check if the port is open first; this is faster and doesn't require the creation of a whole client
                Socket("rabbitmq", 5672).close()
                // Create a client to verify that RabbitMQ is fully started and running on this port
                AMQPClient(connectionName = "Komodo Connection Test{${System.currentTimeMillis()}}",
                    polymorphicModuleBuilder = {}).close()
                logger.info("RabbitMQ started successfully! Initializing messaging support.")
            } catch (ignored: Throwable) {
                logger.fine("Waiting 5 seconds to retry connection to RabbitMQ.")
                return@timer
            }
            subscribeAll()
            this.cancel()
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun subscribeAll() {
        client = AMQPClient(polymorphicModuleBuilder = polymorphicModuleBuilder)
        client.subscribe(NotifyInstanceCreatedMessage::class) { message ->
            instanceMap.getOrPut(message.containerId) { mutableListOf() }.add(message.instanceId)
            if (message.gameType.name == LOBBY_GAME_NAME) {
                logger.info("New lobby instance: instanceId=${message.instanceId}, containerId=${message.containerId}")
                lobbies.add(message.instanceId)
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
                return@subscribe // GameServers are ONLY added via requests to Agones on the Kubernetes API
            }
            instanceMap[message.containerId] = message.instances.map { it.instanceId }.toMutableList()
            val newLobbies = message.instances.filter { it.type?.name == LOBBY_GAME_NAME }.map { it.instanceId }
            lobbies.addAll(newLobbies)
        }
    }

    /**
     * Use some ugly reflection to use part of Velocity's implementation that isn't exposed in its API
     * The [RegisteredServer#ping] method does not allow us to specify a protocol version, so it uses
     * `ProtocolVersion.UNKNOWN` by default. We want to change this default and specify the client's
     * protocol version.
     */
    private val callServerListPing by lazy {
        val velocityRegisteredServerClass = Class.forName("com.velocitypowered.proxy.server.VelocityRegisteredServer")
        val ping = velocityRegisteredServerClass.getDeclaredMethod("ping",
            Class.forName("io.netty.channel.EventLoop"),
            ProtocolVersion::class.java)

        return@lazy { rs: RegisteredServer, version: ProtocolVersion ->
            ping.invoke(velocityRegisteredServerClass.cast(rs), null, version) as CompletableFuture<ServerPing>
        }
    }

    @Subscribe
    fun onServerListPing(event: ProxyPingEvent) {
        // When a client calls a server list ping, forward a ping from a backend server.
        proxyServer.allServers.forEach {
            try {
                val ping = callServerListPing(it, event.connection.protocolVersion).get(1, TimeUnit.SECONDS)
                event.ping = ping.asBuilder().onlinePlayers(proxyServer.allPlayers.size)
                    .maximumPlayers(proxyServer.configuration.showMaxPlayers)
                    .version(event.ping.version)
                    .build()
                return
            } catch (ignored: Throwable) {
            }
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
            client.publish(SendPlayerToInstanceMessage(event.player.uniqueId,
                getLobby(registeredServer.serverInfo.name)!!).also { ownMessages.add(it) })
            return
        }

        logger.warning("No lobby found for ${event.player} to join!")
        event.player.disconnect(Component.text("No lobby was found for you to join! Try rejoining in a few minutes.",
            NamedTextColor.RED))
    }

    @Subscribe
    fun onPlayerLeave(event: DisconnectEvent) {
        lastFailover.remove(event.player)
        client.publish(PlayerLogoutMessage(event.player.uniqueId))
    }

    @Subscribe
    fun onPlayerKick(event: KickedFromServerEvent) {
        if (event.kickedDuringServerConnect() || ((lastFailover[event.player] ?: 0L) + 10000 > System.currentTimeMillis()))
            return

        lastFailover[event.player] = System.currentTimeMillis()

        val registeredServer = proxyServer.allServers.filter { registeredServer ->
            // Find a lobby on a different server than the one that kicked the player
            registeredServer.serverInfo != event.server.serverInfo && getLobby(registeredServer.serverInfo.name) != null
        }.maxByOrNull { it.playersConnected.size }
        val msg = Component.text("You were kicked from ${event.server.serverInfo.name}: ", NamedTextColor.RED)
            .append(event.serverKickReason.orElse(Component.text("No reason specified", NamedTextColor.DARK_GRAY)))
        if (registeredServer != null) {
            event.result = KickedFromServerEvent.RedirectPlayer.create(registeredServer, msg)
        } else {
            event.result = KickedFromServerEvent.DisconnectPlayer.create(msg)
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
        for (player in proxyServer.allPlayers) {
            client.publish(PlayerLogoutMessage(player.uniqueId))
        }
        client.close()
    }

    companion object {
        private const val LOBBY_GAME_NAME = "Lobby"
    }
}