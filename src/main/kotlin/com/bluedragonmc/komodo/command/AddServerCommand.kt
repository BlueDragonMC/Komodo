package com.bluedragonmc.komodo.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.velocitypowered.api.command.BrigadierCommand
import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.server.ServerInfo
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

object AddServerCommand {
    fun create(proxyServer: ProxyServer): BrigadierCommand {
        val node = LiteralArgumentBuilder.literal<CommandSource>("addserver")
            .requires { source -> source.hasPermission("command.addserver") }.then(
                RequiredArgumentBuilder.argument<CommandSource, String>("name", StringArgumentType.string()).then(
                    RequiredArgumentBuilder.argument<CommandSource, String>("address", StringArgumentType.string())
                        .then(RequiredArgumentBuilder.argument<CommandSource, Int>(
                            "port", IntegerArgumentType.integer(0, 65535)
                        ).executes { context ->
                            val name = context.getArgument("name", String::class.java)
                            val address = context.getArgument("address", String::class.java)
                            val port = context.getArgument("port", Integer::class.java)

                            val server = proxyServer.registerServer(
                                ServerInfo(
                                    name, InetSocketAddress(address, port.toInt())
                                )
                            )
                            context.source.sendMessage(
                                Component.text(
                                    "Server \"$name\" registered at $address:$port!", NamedTextColor.GREEN
                                )
                            )

                            try {
                                val ping = server.ping().get(10, TimeUnit.SECONDS)
                                if (ping.players.isPresent) {
                                    context.source.sendMessage(
                                        Component.text(
                                            "Ping successful!  \"$name\" has ${ping.players.get().online} players online.",
                                            NamedTextColor.GREEN
                                        )
                                    )
                                }
                                context.source.sendMessage(
                                    Component.text(
                                        "Ping successful!", NamedTextColor.GREEN
                                    )
                                )
                            } catch (e: Throwable) {
                                context.source.sendMessage(
                                    Component.text(
                                        "Failed to ping server \"$name\" at $address:$port!", NamedTextColor.RED
                                    )
                                )
                            }

                            return@executes Command.SINGLE_SUCCESS
                        })
                )
            )
        return BrigadierCommand(node)
    }
}