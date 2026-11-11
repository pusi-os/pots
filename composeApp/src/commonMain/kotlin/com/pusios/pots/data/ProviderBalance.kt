package com.pusios.pots.data

/**
 * Prepaid balance reported by a provider's account endpoint, in the currency the provider
 * bills in. The currency is shown next to the amount so ₽ and $ accounts are never confused.
 */
data class ProviderBalance(
    val amount: Double,
    val currency: String,
)
