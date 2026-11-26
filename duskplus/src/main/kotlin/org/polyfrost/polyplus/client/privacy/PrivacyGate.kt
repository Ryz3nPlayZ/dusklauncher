package org.polyfrost.polyplus.client.privacy

import org.polyfrost.polyplus.libs.ktor.client.plugins.api.createClientPlugin

class OnlineServicesDisabledException(url: String) : IllegalStateException(
    "This feature needs a service Dusk doesn't have: $url",
) {
    override fun fillInStackTrace(): Throwable = this
}

private const val POLYFROST_DOMAIN = "polyfrost.org"

fun isPolyfrostHost(host: String, backendHost: String?): Boolean {
    val normalized = host.lowercase().trimEnd('.')
    if (normalized == backendHost?.lowercase()?.trimEnd('.')) return true
    return normalized == POLYFROST_DOMAIN || normalized.endsWith(".$POLYFROST_DOMAIN")
}

// Dusk never talks to upstream's servers, whatever was consented to
val PrivacyGate = createClientPlugin("PolyPlusPrivacyGate") {
    onRequest { request, _ ->
        val host = request.url.host
        if (isPolyfrostHost(host, null) || host.endsWith(".invalid")) {
            throw OnlineServicesDisabledException(request.url.buildString())
        }
    }
}
