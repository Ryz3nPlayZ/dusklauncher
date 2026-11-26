package org.polyfrost.polyplus.client.cosmetics

import org.polyfrost.polyplus.client.network.http.responses.BundleInfo
import org.polyfrost.polyplus.client.network.http.responses.BundleSearchResponse

class FeaturedBundle(val info: BundleInfo, val cosmetics: List<Int>, val emotes: List<Int>)

/** Dusk: there is no store, so there are no bundles. */
object BundleCatalog {
    const val MAX_PAGE_SIZE = 100

    suspend fun search(
        page: Int = 1,
        perPage: Int = 50,
        text: String? = null,
    ): Result<BundleSearchResponse> = Result.success(BundleSearchResponse())

    suspend fun featured(): FeaturedBundle? = null
}
