package org.polyfrost.polyplus.client.legal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LegalDocument(
    val version: Int = 1,
    @SerialName("privacy_version") val privacyVersion: Int? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val terms: String = "",
    val privacy: String? = null,
    @SerialName("terms_url") val termsUrl: String? = null,
    @SerialName("privacy_url") val privacyUrl: String? = null,
) {
    val resolvedPrivacyVersion: Int get() = privacyVersion ?: version
    val resolvedTermsUrl: String get() = termsUrl?.takeIf { it.isNotBlank() } ?: LegalDocuments.TERMS_URL
    val resolvedPrivacyUrl: String get() = privacyUrl?.takeIf { it.isNotBlank() } ?: LegalDocuments.PRIVACY_URL
    val privacyBody: String? get() = privacy?.takeIf { it.isNotBlank() }
}

/** Dusk: upstream's terms cover upstream's service, which Dusk doesn't use, so nothing is fetched. */
object LegalDocuments {
    const val TERMS_URL = "https://github.com/Ryz3nPlayZ/dusklauncher"
    const val PRIVACY_URL = "https://github.com/Ryz3nPlayZ/dusklauncher"

    suspend fun load(): Result<LegalDocument> =
        Result.failure(IllegalStateException("Dusk doesn't use upstream's online services"))
}
