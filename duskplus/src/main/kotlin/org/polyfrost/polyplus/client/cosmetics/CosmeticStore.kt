package org.polyfrost.polyplus.client.cosmetics

import org.polyfrost.polyplus.client.network.http.responses.CosmeticSearchResponse
import org.polyfrost.polyplus.client.network.http.responses.CosmeticStoreView
import org.polyfrost.polyplus.client.network.http.responses.CosmeticType

/** Dusk: there is no store. */
object CosmeticStore {
    const val MAX_PAGE_SIZE = 100

    enum class Sort(val serializedName: String) {
        Newest("newest"),
        Oldest("oldest"),
        Ascending("ascending"),
        Descending("descending"),
        Popularity("popularity"),
    }

    suspend fun search(
        page: Int = 1,
        perPage: Int = 12,
        text: String? = null,
        sort: Sort = Sort.Newest,
        types: List<String> = emptyList(),
        tags: List<String> = emptyList(),
        collection: Int? = null,
    ): Result<CosmeticSearchResponse> = Result.success(CosmeticSearchResponse())

    suspend fun stockedTypes(): List<CosmeticType> = emptyList()

    suspend fun view(id: Int): Result<CosmeticStoreView> =
        Result.failure(IllegalStateException("The store isn't available"))
}
