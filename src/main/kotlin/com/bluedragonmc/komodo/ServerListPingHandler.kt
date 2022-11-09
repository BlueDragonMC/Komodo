package com.bluedragonmc.komodo

import com.bluedragonmc.api.grpc.playerCountRequest
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyPingEvent
import com.velocitypowered.api.util.Favicon
import kotlinx.coroutines.runBlocking
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import java.io.File
import java.nio.charset.Charset
import java.util.*

class ServerListPingHandler {

    private val header = (Component.empty() + MiniMessage.miniMessage().deserialize(
        "<bold><gradient:#4EB2F4:#3336f4>BlueDragon</gradient></bold> <dark_gray>[<green>1.18.2<dark_gray>]"
    )).center(92)

    private val serverNews = MiniMessage.miniMessage().deserialize(
        System.getenv("SERVER_NEWS").orEmpty()
    ).center(92)

    private val motd = header + Component.newline() + serverNews

    private val favicon = Favicon("data:image/png;base64," + runCatching {
        String(Base64.getEncoder().encode(File("favicon_64.png").readBytes()), Charset.forName("UTF-8"))
    }.getOrElse { "" })

    @Subscribe
    fun onPing(event: ProxyPingEvent) {
        event.ping = event.ping.asBuilder()
            .favicon(favicon)
            .description(motd)
            .onlinePlayers(getOnlinePlayers())
            .build()
    }

    private val lastCheck = 0L
    private var lastOnlinePlayerCount = 0

    private fun getOnlinePlayers(): Int {
        if (System.currentTimeMillis() - lastCheck > 5000) {
            runBlocking {
                lastOnlinePlayerCount = Stubs.instanceSvc.getTotalPlayerCount(playerCountRequest { }).totalPlayers
            }
        }
        return lastOnlinePlayerCount
    }
}