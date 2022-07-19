package com.bluedragonmc.komodo

import com.bluedragonmc.messages.*
import com.bluedragonmc.messagingsystem.AMQPClient
import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.server.ServerInfo
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.UUID
import java.util.logging.Logger
import kotlin.jvm.optionals.getOrNull

@Plugin(
    id = "komodo",
    name = "Komodo",
    version = "0.0.1",
    description = "BlueDragon's Velocity plugin that handles coordination with our service",
    url = "https://bluedragonmc.com",
    authors = ["FluxCapacitor2"]
)
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

    @OptIn(ExperimentalStdlibApi::class)
    @Subscribe
    fun onStart(event: ProxyInitializeEvent) {
        client = AMQPClient(polymorphicModuleBuilder = polymorphicModuleBuilder)
        client.subscribe(PingMessage::class) { message ->
            logger.info("Adding server at ${message.containerId}")
            val str = message.containerId.toString()
            server.registerServer(ServerInfo(str, InetSocketAddress(str, 25565)))
        }
        client.subscribe(NotifyInstanceCreatedMessage::class) { message ->
            instanceMap.getOrPut(message.containerId) { mutableListOf() }.add(message.instanceId)
        }
        client.subscribe(NotifyInstanceRemovedMessage::class) { message ->
            instanceMap[message.containerId]?.remove(message.instanceId)
            if(instanceMap[message.containerId]?.size == 0) {
                instanceMap.remove(message.containerId)
                server.unregisterServer(server.getServer(message.containerId.toString()).getOrNull()?.serverInfo ?: run {
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
            player.createConnectionRequest(server).fireAndForget()
        }
    }

    private fun getContainerId(instance: UUID): String? {
        for((containerId, instances) in instanceMap) {
            if(instances.contains(instance)) return containerId.toString()
        }
        return null
    }

    @Subscribe
    fun onStop(event: ProxyShutdownEvent) {

    }
}