package com.pusios.pots.media

import com.pusios.pots.data.AppSettings
import com.pusios.pots.data.Service

/**
 * One provider per service, created on first use and kept. [PlusVibeMediaProvider]
 * remembers the catalog it parsed (`modelsById`) and that map decides which
 * `opts` key a reference image goes into on submit — a fresh instance per call
 * would lose it and silently drop reference mapping.
 */
private val providers = mutableMapOf<Service, MediaProvider>()

/**
 * The provider that serves [service], or null when the service has no media
 * endpoints (catalog + submit + poll).
 *
 * Only PlusVibe documents a full media contract today
 * (`docs/knowledge/media-providers/plusvibe.md`; the reserve service is the same
 * API on `gw`). Polza, AITUNNEL, GPTunnel and LLM Router expose media endpoints
 * in their docs, but their contracts are not implemented yet — they stay null
 * until their providers land, so no screen offers a service the app cannot drive.
 */
fun mediaProviderFor(service: Service): MediaProvider? = when (service) {
    Service.PlusVibe, Service.PlusVibeReserve -> providers.getOrPut(service) {
        PlusVibeMediaProvider(service.mediaBaseUrl())
    }

    else -> null
}

/** `https://plusvibeapi.ru/v1/chat/completions` → `https://plusvibeapi.ru`. */
private fun Service.mediaBaseUrl(): String = chatUrl.removeSuffix("/v1/chat/completions")

/**
 * The `MediaProvider` Koin hands out: every call goes to the provider of the
 * service chosen in Settings → Services → «Сервис для медиатеки». Keys still
 * come from the caller, so the wrapper never holds credentials.
 */
class ActiveMediaProvider(private val appSettings: AppSettings) : MediaProvider {

    /**
     * A job is polled and downloaded from the service that accepted it, even if
     * the user switches the media service while it runs — otherwise the poll
     * would 404 on the new service and never end.
     */
    private val jobServices = mutableMapOf<String, Service>()

    private fun noService(): MediaException = MediaException.NoMediaService(
        "No media service is configured. Add a service with media models in Settings → Services.",
    )

    private fun providerFor(service: Service): MediaProvider = mediaProviderFor(service) ?: throw noService()

    private fun active(): MediaProvider = activeMediaService(appSettings)?.let { providerFor(it.service) } ?: throw noService()

    private fun forJob(jobId: String): MediaProvider = jobServices[jobId]?.let { providerFor(it) } ?: active()

    override suspend fun listModels(apiKey: String): List<MediaModel> = active().listModels(apiKey)

    override suspend fun priceTable(apiKey: String, modelId: String): MediaPriceTable? = active().priceTable(apiKey, modelId)

    override suspend fun quotePrice(apiKey: String, request: MediaJobRequest): MediaPriceQuote = active().quotePrice(apiKey, request)

    override suspend fun submitJob(apiKey: String, request: MediaJobRequest): MediaJobState {
        val choice = activeMediaService(appSettings) ?: throw noService()
        val state = providerFor(choice.service).submitJob(apiKey, request)
        jobServices[state.jobId] = choice.service
        return state
    }

    override suspend fun pollJob(apiKey: String, jobId: String): MediaJobState = forJob(jobId).pollJob(apiKey, jobId)

    override suspend fun downloadResult(apiKey: String, url: String): ByteArray = active().downloadResult(apiKey, url)
}
