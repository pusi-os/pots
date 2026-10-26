package com.pusios.pots.data

/**
 * Providers with meaningful free-tier usage that we recommend when the
 * built-in Free FAST/EXPERT path is rate-limited and the user has no
 * configured services yet.
 */
data class FreeProviderSuggestion(
    val service: Service,
) {
    val signupUrl: String
        get() = requireNotNull(service.apiKeyUrl) {
            "FreeProviderSuggestion requires apiKeyUrl on ${service.id}"
        }
}

val freeProviderSuggestions: List<FreeProviderSuggestion> = listOf(
    FreeProviderSuggestion(Service.Polza),
    FreeProviderSuggestion(Service.Gptunnel),
    FreeProviderSuggestion(Service.AiTunnel),
)
