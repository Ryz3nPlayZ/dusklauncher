package org.polyfrost.polyplus.client.status

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ServiceStatus(
    val state: String,
    val announcement: String?,
    val affected: List<String>,
) {
    val operational: Boolean get() = state == StatusPage.OPERATIONAL
    val maintenance: Boolean get() = state == StatusPage.MAINTENANCE

    fun affects(service: String): Boolean = !operational && affected.any { it.equals(service, ignoreCase = true) }

    fun summary(): String {
        val names = affected.joinToString(", ")
        return when {
            maintenance && names.isNotEmpty() -> "Dusk maintenance on $names"
            maintenance -> "Dusk services are under maintenance"
            names.isNotEmpty() -> "Dusk services with problems: $names"
            else -> "Some Dusk services have problems"
        }
    }
}

/** Dusk publishes no status page; the status stays unknown and nothing is shown for it. */
object StatusPage {
    const val PAGE_URL = "https://github.com/Ryz3nPlayZ/dusklauncher"
    const val OPERATIONAL = "operational"
    const val MAINTENANCE = "maintenance"

    private val _status = MutableStateFlow<ServiceStatus?>(null)
    val status: StateFlow<ServiceStatus?> = _status.asStateFlow()

    fun disconnectHint(): String = ""
}
