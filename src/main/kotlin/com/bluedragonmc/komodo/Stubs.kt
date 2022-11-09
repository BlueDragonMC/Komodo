package com.bluedragonmc.komodo

import com.bluedragonmc.api.grpc.InstanceServiceGrpcKt
import com.bluedragonmc.api.grpc.LobbyServiceGrpcKt
import com.bluedragonmc.api.grpc.PlayerTrackerGrpcKt
import io.grpc.ManagedChannelBuilder
import org.slf4j.LoggerFactory
import java.net.InetAddress

object Stubs {

    private val logger = LoggerFactory.getLogger(this::class.java)

    private val channel by lazy {
        val addr = InetAddress.getByName("puffin").hostAddress
        logger.info("Initializing gRPC channel - Connecting to puffin (resolves to ${addr})")
        ManagedChannelBuilder.forAddress("puffin", 50051)
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

    fun preInitialize() {
        channel
    }
}