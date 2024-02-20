package com.bluedragonmc.komodo.rpc

import com.bluedragonmc.api.grpc.GetPlayersResponseKt.connectedPlayer
import com.bluedragonmc.api.grpc.PlayerHolderGrpcKt
import com.bluedragonmc.api.grpc.PlayerHolderOuterClass
import com.bluedragonmc.api.grpc.getPlayersResponse
import com.bluedragonmc.api.grpc.sendPlayerResponse
import com.bluedragonmc.komodo.handler.InstanceRoutingHandler
import com.google.protobuf.Empty
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.server.ServerInfo
import java.net.InetSocketAddress
import java.util.*
import java.util.logging.Logger
import kotlin.jvm.optionals.getOrNull

class PlayerHolderService(
    private val proxyServer: ProxyServer,
    private val logger: Logger,
    private val instanceRoutingHandler: InstanceRoutingHandler,
) : PlayerHolderGrpcKt.PlayerHolderCoroutineImplBase() {

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