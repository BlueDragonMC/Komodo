package com.bluedragonmc.komodo

import com.bluedragonmc.api.grpc.InstanceServiceGrpcKt
import com.bluedragonmc.api.grpc.LobbyServiceGrpcKt
import com.bluedragonmc.api.grpc.PlayerTrackerGrpcKt
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

object Stubs {

    private val logger = LoggerFactory.getLogger(this::class.java)

    private lateinit var channel: ManagedChannel

    private fun createChannel(): ManagedChannel {
        val uri = System.getenv("KOMODO_PUFFIN_URI") ?: "puffin:50051"
        logger.info("Initializing gRPC channel - Connecting to puffin at $uri")
        return ManagedChannelBuilder.forTarget(uri)
            .defaultLoadBalancingPolicy("round_robin")
            .usePlaintext()
            .enableRetry()
            .build()
    }

    val discovery by lazy {
        LobbyServiceGrpcKt.LobbyServiceCoroutineStub(channel)
    }

    val playerTracking by lazy {
        PlayerTrackerGrpcKt.PlayerTrackerCoroutineStub(channel)
    }

    val instanceSvc by lazy {
        InstanceServiceGrpcKt.InstanceServiceCoroutineStub(channel)
    }

    private var connectAttempts = 0

    suspend fun initialize() {
        while (true) {
            try {
                channel = createChannel()
                break
            } catch (e: Throwable) {
                connectAttempts++
                if (connectAttempts > 10) {
                    logger.error("Failed to connect to Puffin after 10 attempts.")
                    exitProcess(1)
                }
                logger.error("Failed to connect to Puffin ($connectAttempts attempts). Retrying...")
                delay(2_000)
            }
        }
    }

    fun shutdown() {
        channel.shutdown()
        channel.awaitTermination(10, TimeUnit.SECONDS)
    }
}