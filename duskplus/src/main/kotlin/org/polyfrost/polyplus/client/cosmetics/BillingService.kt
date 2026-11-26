package org.polyfrost.polyplus.client.cosmetics

import org.polyfrost.polyplus.client.network.http.responses.TransactionInfo

/** Dusk: nothing is sold here. */
object BillingService {
    class AlreadyOwnedException(message: String) : Exception(message)

    enum class CheckoutOutcome { Opened, Completed }

    suspend fun checkoutAndOpen(productIds: List<String>): Result<CheckoutOutcome> =
        Result.failure(IllegalStateException("The store isn't available"))

    suspend fun fetchTransactions(): Result<List<TransactionInfo>> = Result.success(emptyList())
}
