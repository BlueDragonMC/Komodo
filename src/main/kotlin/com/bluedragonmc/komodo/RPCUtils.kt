package com.bluedragonmc.komodo

import org.slf4j.LoggerFactory

object RPCUtils {
    inline fun <R : Any> handleRPC(handler: () -> R): R {
        try {
            return handler()
        } catch (e: Throwable) {
            LoggerFactory.getLogger(this::class.java).error("An error occurred in an RPC handler:")
            e.printStackTrace()
            throw e
        }
    }
}