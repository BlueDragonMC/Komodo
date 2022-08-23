package com.bluedragonmc.komodo

import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.Configuration
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.util.Config
import io.kubernetes.client.util.generic.dynamic.DynamicKubernetesApi
import io.kubernetes.client.util.generic.dynamic.DynamicKubernetesObject

class ServiceDiscovery {

    companion object {

        private val client: ApiClient = Config.defaultClient()

        init {
            Configuration.setDefaultApiClient(client)
        }

        private val gameServersAPI = DynamicKubernetesApi("agones.dev", "v1", "gameservers", client)
        private val coreAPI = CoreV1Api(client)
    }

    fun listServices(): List<GameServer> {
        val gs = gameServersAPI.list()
        val pods =
            coreAPI.listPodForAllNamespacesWithHttpInfo(null, null, null, null, null, null, null, null, null, null)
        if (gs.httpStatusCode >= 400) {
            error("Kubernetes (Agones request) returned HTTP error code: ${gs.httpStatusCode}: ${gs.status?.status}, ${gs.status?.message}")
        }
        if (pods.statusCode >= 400) {
            error("Kubernetes (Pods request) returned HTTP error code: ${pods.statusCode}")
        }
        return gs.`object`.items.mapNotNull { obj ->
            val pod = pods.data.items.find { pod -> pod.metadata?.name == obj.metadata?.name } ?: return@mapNotNull null
            GameServer(pod, obj)
        }
    }

    data class GameServer(
        private val pod: V1Pod,
        private val agonesServer: DynamicKubernetesObject,
    ) {

        val uid = agonesServer.metadata.uid
        private val name = agonesServer.metadata.name

        fun isReady(): Boolean =
            getAddress() != null && getContainerPort() != null && pod.status?.containerStatuses?.all { it.ready } == true

        internal fun getAddress(): String? = pod.status?.podIP

        /**
         * Get the host port of this game server (exposed by Agones)
         */
        private fun getHostPort(): Int? {
            val status = agonesServer.raw.getAsJsonObject("status")
            if (status?.get("ports")?.isJsonArray != true) return null
            return status.get("ports")!!.asJsonArray.firstOrNull { p ->
                p.asJsonObject.get("name").asString == "minecraft"
            }?.asJsonObject?.get("port")?.asInt
        }

        /**
         * Get the container port of this game server
         * (used for internal container networking, not forwarded to the host)
         */
        internal fun getContainerPort(): Int? {
            val hostPort = getHostPort()
            pod.spec?.containers?.forEach { container ->
                // Find a container with a host port that matches the exposed port of the game server
                container.ports?.forEach { port ->
                    // Return the container port (most likely 25565, the default Minecraft port)
                    if (port.hostPort == hostPort) return port.containerPort
                }
            }
            return null
        }

        private fun getState() = agonesServer.raw.get("status").asJsonObject.get("state").asString

        override fun toString(): String {
            return "GameServer(uid=$uid, name=$name, state=${getState()}, clusterIP=${getAddress()}, hostPort=${getHostPort()}, containerPort=${getContainerPort()})"
        }
    }
}
