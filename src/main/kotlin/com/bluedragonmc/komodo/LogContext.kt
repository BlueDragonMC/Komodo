package com.bluedragonmc.komodo

import org.slf4j.MDC
import java.util.*

/** Structured logging helpers using SLF4J MDC */
object LogContext {

    fun fields(vararg pairs: Pair<String, Any?>): Map<String, String> = buildMap {
        for ((key, value) in pairs) {
            val text = value?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) put(key, text)
        }
    }

    fun player(uuid: UUID, username: String? = null): Map<String, String> = fields(
        "player" to username,
        "player_uuid" to uuid,
    )

    fun player(uuid: String, username: String? = null): Map<String, String> = fields(
        "player" to username,
        "player_uuid" to uuid,
    )

    fun game(
        gameId: String?,
        server: String? = null,
        proxy: String? = null,
        game: String? = null,
    ): Map<String, String> = fields(
        "game_id" to gameId,
        "server" to server,
        "proxy" to proxy,
        "game" to game,
    )

    fun <T> with(fields: Map<String, String>, block: () -> T): T {
        if (fields.isEmpty()) return block()
        val previous = fields.keys.associateWith { MDC.get(it) }
        try {
            fields.forEach { (key, value) -> MDC.put(key, value) }
            return block()
        } finally {
            previous.forEach { (key, value) ->
                if (value == null) MDC.remove(key) else MDC.put(key, value)
            }
        }
    }

    fun <T> with(vararg pairs: Pair<String, Any?>, block: () -> T): T =
        with(fields(*pairs), block)
}
