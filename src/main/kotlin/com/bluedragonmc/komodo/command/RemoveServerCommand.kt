package com.bluedragonmc.komodo.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.velocitypowered.api.command.BrigadierCommand
import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.proxy.ProxyServer
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

object RemoveServerCommand {
    fun create(proxyServer: ProxyServer): BrigadierCommand {
        val node = LiteralArgumentBuilder.literal<CommandSource>("removeserver")
            .requires { source -> source.hasPermission("command.removeserver") }
            .then(RequiredArgumentBuilder.argument<CommandSource?, String?>("name", StringArgumentType.string())
                .suggests { context, builder ->
                    val filter = if (context.arguments.containsKey("name"))
                        context.getArgument("name", String::class.java) else ""

                    proxyServer.allServers.forEach { server ->
                        val name = server.serverInfo.name
                        if (name.startsWith(filter.lowercase())) {
                            builder.suggest(name)
                        }
                    }
                    builder.buildFuture()
                }.executes { context ->
                    val name = context.getArgument("name", String::class.java)

                    val server = proxyServer.getServer(name)
                    if (server.isPresent) {
                        proxyServer.unregisterServer(server.get().serverInfo)
                        context.source.sendMessage(
                            Component.text(
                                "Successfully removed server \"$name\".", NamedTextColor.GREEN
                            )
                        )
                    } else {
                        context.source.sendMessage(
                            Component.text(
                                "Server \"$name\" does not exist!", NamedTextColor.RED
                            )
                        )
                    }
                    return@executes Command.SINGLE_SUCCESS
                })
        return BrigadierCommand(node)
    }
}