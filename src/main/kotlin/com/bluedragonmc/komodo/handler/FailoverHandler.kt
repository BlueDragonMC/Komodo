package com.bluedragonmc.komodo.handler

import com.bluedragonmc.komodo.Komodo
import com.bluedragonmc.komodo.plus
import com.bluedragonmc.komodo.toPlainText
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.player.KickedFromServerEvent
import com.velocitypowered.api.proxy.Player
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import kotlin.jvm.optionals.getOrNull

class FailoverHandler {

    private val lastFailover = mutableMapOf<Player, Long>()

    @Subscribe
    fun onPlayerKick(event: KickedFromServerEvent) {
        if (event.kickedDuringServerConnect() || ((lastFailover[event.player]
                ?: 0L) + 10000 > System.currentTimeMillis())
        ) {
            return
        }

        // Kick messages with a non-breaking space (U+00A0) should not trigger failover
        // This is a way of differentiating intentional vs. accidental kicks that remains invisible to the end user
        val kickWasIntentional = event.serverKickReason.getOrNull()?.toPlainText()?.contains("\u00A0")
        if (kickWasIntentional == true) {
            val extraInfo = if (event.kickedDuringServerConnect()) {
                Component.text(
                    "You were kicked while trying to join " + event.server.serverInfo.name + ".",
                    NamedTextColor.DARK_GRAY
                )
            } else {
                Component.text(
                    "You were kicked from " + event.server.serverInfo.name + ".",
                    NamedTextColor.DARK_GRAY
                )
            }
            event.result =
                KickedFromServerEvent.DisconnectPlayer.create(extraInfo + Component.newline() + event.serverKickReason.get())
            return
        }

        lastFailover[event.player] = System.currentTimeMillis()

        val (registeredServer, lobbyInstance) = Komodo.INSTANCE.getLobby(excluding = event.server.serverInfo.name)
        val msg = Component.text("You were kicked from ${event.server.serverInfo.name}: ", NamedTextColor.RED)
            .append(event.serverKickReason.orElse(Component.text("No reason specified", NamedTextColor.DARK_GRAY)))
        if (registeredServer != null) {
            event.result = KickedFromServerEvent.RedirectPlayer.create(registeredServer, msg)
            Komodo.INSTANCE.instanceRoutingHandler.route(event.player, lobbyInstance)
        } else {
            event.result = KickedFromServerEvent.DisconnectPlayer.create(msg)
        }
    }

    @Subscribe
    fun onPlayerLeave(event: DisconnectEvent) {
        lastFailover.remove(event.player)
    }
}