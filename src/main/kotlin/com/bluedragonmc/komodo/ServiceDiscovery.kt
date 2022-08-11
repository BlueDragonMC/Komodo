package com.bluedragonmc.komodo

import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.Configuration
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
    }

    fun listServices(): List<DynamicKubernetesObject> {
        val response = gameServersAPI.list()
        if (response.httpStatusCode >= 400) {
            error("Kubernetes returned HTTP error code: ${response.httpStatusCode}: ${response.status?.status}, ${response.status?.message}")
        }
        return response.`object`.items
    }
}
