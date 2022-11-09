package com.bluedragonmc.komodo

import com.bluedragonmc.api.grpc.*
import com.bluedragonmc.api.grpc.GetPlayersResponseKt.connectedPlayer
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.google.inject.Inject
import com.google.protobuf.Empty
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.player.KickedFromServerEvent
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent
import com.velocitypowered.api.event.player.ServerLoginPluginMessageEvent
import com.velocitypowered.api.event.player.ServerPostConnectEvent
import com.velocitypowered.api.event.player.ServerPreConnectEvent
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
import io.grpc.ManagedChannelBuilder
import io.grpc.ServerBuilder
import kotlinx.coroutines.runBlocking
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.time.Duration
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
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

    object Stubs {

        private val logger = LoggerFactory.getLogger(this::class.java)

        private val channel by lazy {
            val addr = InetAddress.getByName("puffin").hostAddress
            logger.info("Initializing gRPC channel - Connecting to puffin (resolves to ${addr})")
            ManagedChannelBuilder.forAddress("puffin", 50051)
                .defaultLoadBalancingPolicy("round_robin")
                .usePlaintext()
                .build()
        }

        val discovery by lazy {
            LobbyServiceGrpcKt.LobbyServiceCoroutineStub(channel)
        }

        val playerTracking by lazy {
            PlayerTrackerGrpcKt.PlayerTrackerCoroutineStub(channel)
        }

        fun preInitialize() {
            channel
        }
    }

    private val lastFailover = mutableMapOf<Player, Long>()

    private val instanceDestinations: Cache<Player, String> = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofSeconds(30))
        .expireAfterAccess(Duration.ofSeconds(5))
        .weakKeys()
        .build()

    @Subscribe
    fun onInit(event: ProxyInitializeEvent) {
        val server = ServerBuilder.forPort(50051).addService(PlayerHolderService()).build()
        server.start()

        // Pre-initialize gRPC channel
        Stubs.preInitialize()
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
                instanceDestinations.put(player, request.instanceId.toString())
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

    /**
     * Use some ugly reflection to use part of Velocity's implementation that isn't exposed in its API
     * The [RegisteredServer#ping] method does not allow us to specify a protocol version, so it uses
     * `ProtocolVersion.UNKNOWN` by default. We want to change this default and specify the client's
     * protocol version.
     */
    @Suppress("UNCHECKED_CAST")
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

    // If the proxy has no registered servers, it will use a MOTD from the last time it had one registered.
    private var lastPing: ServerPing? = null

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
                lastPing = ping
                return
            } catch (ignored: Throwable) {
            }
        }

        if (lastPing != null) {
            event.ping = lastPing!!.asBuilder().onlinePlayers(proxyServer.playerCount).build()
        }
    }

    @Subscribe
    fun onLoginPluginMessage(event: ServerLoginPluginMessageEvent) {
        if (event.identifier.id == "bluedragonmc:get_dest") {
            // Get the player's cached destination
            val instanceName = instanceDestinations.getIfPresent(event.connection.player) ?: return
            val uid = UUID.fromString(instanceName)
            val buf = ByteBuffer.allocate(16)
            // Write the instance UUID to the byte buffer
            buf.putLong(uid.mostSignificantBits)
            buf.putLong(uid.leastSignificantBits)
            buf.rewind()
            // Convert the buffer into a byte array to be sent to the backend server
            val bytes = ByteArray(buf.remaining())
            buf.get(bytes)
            // Reply to the request with these bytes
            event.result = ServerLoginPluginMessageEvent.ResponseResult.reply(bytes)
            logger.info("Sending player ${event.connection.player.username} to instance '$instanceName' on server '${event.connection.serverInfo.name}'")
        } else if (event.identifier.id.startsWith("bluedragonmc:")) {
            logger.warning("Login plugin message sent on unexpected channel: '${event.identifier.id}'")
            event.result = ServerLoginPluginMessageEvent.ResponseResult.unknown()
        }
    }

    @Subscribe
    fun onServerConnect(event: ServerPreConnectEvent) {
        instanceDestinations.get(event.player) {
            // If there is no destination instance specified,
            // a lobby on that server should be used.
            if (event.originalServer != null) {
                if (event.result.server.isPresent) {
                    val (registeredServer, lobbyInstance) = getLobby(event.result.server.get().serverInfo.name)
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
    fun onServerPostConnect(event: ServerPostConnectEvent) {
        if (event.previousServer?.playersConnected?.isEmpty() == true) {
            logger.info("Unregistering server ${event.previousServer?.serverInfo?.name} because its last player was sent to another server.")
            proxyServer.unregisterServer(event.previousServer!!.serverInfo)
        }
    }

    @Subscribe
    fun onPlayerJoin(event: PlayerChooseInitialServerEvent) {
        // When a player joins, send them to the lobby with the most players
        val (registeredServer, lobbyInstance) = getLobby()
        if (registeredServer != null && !lobbyInstance.isNullOrEmpty()) {
            event.setInitialServer(registeredServer)
            instanceDestinations.put(event.player, lobbyInstance)
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

        logger.warning("No lobby found for ${event.player} to join!")
        event.player.disconnect(
            Component.text(
                "No lobby was found for you to join! Try rejoining in a few minutes.",
                NamedTextColor.RED
            )
        )
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
            instanceDestinations.put(event.player, lobbyInstance)
        } else {
            event.result = KickedFromServerEvent.DisconnectPlayer.create(msg)
        }
    }

    private fun getLobby(serverName: String? = null, excluding: String? = null): Pair<RegisteredServer?, String?> =
        runBlocking {
            val response = Stubs.discovery.findLobby(findLobbyRequest {
                if (serverName != null)
                    this.includeServerNames += serverName
                if(excluding != null)
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