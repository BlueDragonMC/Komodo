package com.bluedragonmc.komodo

import com.bluedragonmc.api.grpc.InstanceServiceGrpcKt
import com.bluedragonmc.api.grpc.LobbyServiceGrpcKt
import com.bluedragonmc.api.grpc.PlayerTrackerGrpcKt
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.net.InetAddress
import kotlin.system.exitProcess

object Stubs {

    private val logger = LoggerFactory.getLogger(this::class.java)

    private lateinit var channel: ManagedChannel

    private fun createChannel(): ManagedChannel {
        val addr = InetAddress.getByName("puffin").hostAddress
        logger.info("Initializing gRPC channel - Connecting to puffin (resolves to ${addr})")
        return ManagedChannelBuilder.forAddress("puffin", 50051)
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
                connectAttempts ++
                if (connectAttempts > 10) {
                    logger.error("Failed to connect to Puffin after 10 attempts.")
                    exitProcess(1)
                }
                logger.error("Failed to connect to Puffin ($connectAttempts attempts). Retrying...")
                delay(2_000)
            }
        }
    }
}