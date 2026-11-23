package com.pusios.pots.data

import com.pusios.pots.network.dtos.aitunnel.AiTunnelCatalogEntryDto
import com.pusios.pots.network.dtos.anthropic.AnthropicModelsResponseDto
import com.pusios.pots.network.dtos.gemini.GeminiModelsResponseDto
import com.pusios.pots.network.dtos.llmrouter.LlmRouterPricingResponseDto
import com.pusios.pots.network.dtos.openaicompatible.OpenAICompatibleModelResponseDto
import com.pusios.pots.network.dtos.plusvibe.PlusVibeCatalogEntryDto
import com.pusios.pots.toIsoDate
import com.pusios.pots.ui.settings.SettingsModel

/**
 * Model id substrings that mark a model as non-chat. Any model whose
 * lowercased id contains one of these is filtered out of the picker.
 * Covers: voice/TTS, embeddings, moderation, OCR, safety, image gen,
 * video gen, retrieval, reward, translation, and other non-chat APIs.
 */
private val nonChatPatterns = listOf(
    "embed",
    "tts",
    "transcribe",
    "realtime",
    "moderation",
    "ocr",
    "guard",
    "safety",
    "reward",
    "voxtral",
    "whisper",
    "orpheus",
    "leanstral",
    "vibe-cli",
    "streampetr",
    "nvclip",
    "deplot",
    "paligemma",
    "gliner",
    "nemoretriever",
    "nemotron-parse",
    "riva-translate",
    "kosmos",
    "nano-banana",
    "lyria",
    "imagen",
    "image",
    "aqa",
    "veo",
    "native-audio",
    "live-",
    "bge-",
    "shieldgemma",
)

internal fun isChatModel(modelId: String): Boolean {
    val lower = modelId.lowercase()
    return nonChatPatterns.none { lower.contains(it) }
}

/**
 * Unified "newest first" sort applied to every provider's model list.
 *
 *  1. Release date descending (nulls last)
 *  2. Context window descending (nulls last)
 *  3. Model id ascending (stable tiebreaker)
 */
internal val newestFirstComparator: Comparator<SettingsModel> = Comparator { a, b ->
    val dateA = a.releaseDate
    val dateB = b.releaseDate
    when {
        dateA != null && dateB == null -> return@Comparator -1

        dateA == null && dateB != null -> return@Comparator 1

        dateA != null && dateB != null -> {
            val cmp = dateB.compareTo(dateA)
            if (cmp != 0) return@Comparator cmp
        }
    }
    val ctxA = a.contextWindow
    val ctxB = b.contextWindow
    when {
        ctxA != null && ctxB == null -> return@Comparator -1

        ctxA == null && ctxB != null -> return@Comparator 1

        ctxA != null && ctxB != null -> {
            val cmp = ctxB.compareTo(ctxA)
            if (cmp != 0) return@Comparator cmp
        }
    }
    a.id.compareTo(b.id)
}

/**
 * Builds the [SettingsModel] every provider's mapper produces: the curated [ModelCatalog] entry
 * wins on each field, falling back to whatever the provider's API supplied.
 *
 * The `api*` parameters default to null because the providers genuinely differ in what they
 * expose — Anthropic has no context window or release date on its models endpoint, and Gemini
 * has no release date — so each mapper passes only the fields its API actually carries.
 */
private fun buildSettingsModel(
    service: Service,
    id: String,
    selectedModelId: String,
    apiDisplayName: String? = null,
    apiContextWindow: Long? = null,
    apiReleaseDate: String? = null,
    apiPricePerMillion: Double? = null,
    apiOutputPricePerMillion: Double? = null,
    apiCachePricePerMillion: Double? = null,
    apiSupportsTools: Boolean = false,
    apiSupportsVision: Boolean = false,
): SettingsModel {
    val curated = ModelCatalog.lookup(id)
    return SettingsModel(
        id = id,
        displayName = curated?.displayName ?: apiDisplayName,
        subtitle = "",
        isSelected = id == selectedModelId,
        contextWindow = curated?.contextWindow ?: apiContextWindow,
        releaseDate = curated?.releaseDate ?: apiReleaseDate,
        parameterCount = curated?.parameterCount,
        arenaScore = curated?.arenaScore,
        pricePerMillion = apiPricePerMillion,
        outputPricePerMillion = apiOutputPricePerMillion,
        cachePricePerMillion = apiCachePricePerMillion,
        supportsTools = apiSupportsTools,
        supportsVision = apiSupportsVision,
    )
}

internal fun mapOpenAICompatibleModels(
    models: List<OpenAICompatibleModelResponseDto.Model>,
    service: Service,
    selectedModelId: String,
    pricing: List<LlmRouterPricingResponseDto.Entry>? = null,
    catalog: Map<String, ApiCatalogEnrichment>? = null,
): List<SettingsModel> {
    val pricesById = pricing?.associateBy { it.id }.orEmpty()
    val activeFiltered = if (service.filterActiveStrictly) {
        models.filter { it.isActive == true }
    } else {
        models.filter { it.isActive != false }
    }
    val typeFiltered = if (service.filterByModelType && activeFiltered.any { it.type != null }) {
        activeFiltered.filter { it.type == "chat" }
    } else {
        activeFiltered
    }
    val chatOnly = typeFiltered.filter { isChatModel(it.id) }
    val unique = chatOnly.distinctBy { it.id }
    val mapped = unique.map {
        val enrichment = catalog?.get(it.id)
        buildSettingsModel(
            service = service,
            id = it.id,
            selectedModelId = selectedModelId,
            apiDisplayName = it.name,
            apiContextWindow = it.context_window ?: it.context_length ?: enrichment?.contextWindow,
            apiReleaseDate = it.created?.toIsoDate(),
            apiPricePerMillion = it.pricePerMillion() ?: enrichment?.inputPricePerMillion
                ?: pricesById[it.id]?.perMillion("input"),
            apiOutputPricePerMillion = it.outputPricePerMillion() ?: enrichment?.outputPricePerMillion
                ?: pricesById[it.id]?.perMillion("output"),
            apiCachePricePerMillion = pricesById[it.id]?.perMillion("cache_read")
                ?: enrichment?.cachePricePerMillion,
            apiSupportsTools = it.supportsTools() || (enrichment?.supportsTools == true),
            apiSupportsVision = it.supportsVision() || (enrichment?.supportsVision == true),
        )
    }
    return if (service.sortModelsById) {
        mapped.sortedBy { it.id }
    } else {
        mapped.sortedWith(newestFirstComparator)
    }
}

/**
 * Ensures [selectedModelId] appears in [models] and is marked selected.
 *
 * Previous [SettingsModel.isManualEntry] rows are dropped first so retyping a custom id
 * replaces the prior draft instead of accumulating every intermediate string in the picker.
 * When the id is blank, only provider-sourced models remain (all deselected) so the caller
 * may auto-select a default. When the id is missing from the list, a single synthetic
 * manual entry is prepended so the choice is not overwritten on refresh.
 */
internal fun ensureSelectedModelPresent(
    models: List<SettingsModel>,
    selectedModelId: String,
): List<SettingsModel> {
    val providerModels = models.filter { !it.isManualEntry }
    if (selectedModelId.isEmpty()) {
        return providerModels.map { it.copy(isSelected = false) }
    }
    val matchInProvider = providerModels.any { it.id == selectedModelId }
    return if (matchInProvider) {
        providerModels.map { it.copy(isSelected = it.id == selectedModelId) }
    } else {
        // Keep a manual row only when the id is not already a real list entry.
        // If [models] already had this id as a manual entry, rebuild it cleanly.
        listOf(
            SettingsModel(
                id = selectedModelId,
                subtitle = "",
                isSelected = true,
                isManualEntry = true,
            ),
        ) + providerModels.map { it.copy(isSelected = false) }
    }
}

private fun LlmRouterPricingResponseDto.Entry.perMillion(op: String): Double? = prices.firstOrNull { it.op == op && it.unit == "1m_tokens" }?.usd

internal fun OpenAICompatibleModelResponseDto.Model.pricePerMillion(): Double? {
    val pricing = top_provider?.pricing ?: pricing ?: return null
    val raw = pricing.prompt_per_million ?: pricing.prompt ?: return null
    val value = raw.toDoubleOrNull() ?: return null
    return if (pricing.prompt_per_million != null) value else value * 1_000_000.0
}

internal fun OpenAICompatibleModelResponseDto.Model.outputPricePerMillion(): Double? {
    val pricing = top_provider?.pricing ?: pricing ?: return null
    val raw = pricing.completion_per_million ?: pricing.completion ?: return null
    val value = raw.toDoubleOrNull() ?: return null
    return if (pricing.completion_per_million != null) value else value * 1_000_000.0
}

/**
 * Fields merged from a provider's public catalog when its `/v1/models` endpoint does not carry
 * them (PlusVibe `/api/catalog`, AITUNNEL `/public/aitunnel/models/chat`). A null field means
 * "unknown" and never overwrites a value parsed from `/v1/models`; boolean caps are OR-ed with
 * what the endpoint itself declared.
 */
internal data class ApiCatalogEnrichment(
    val inputPricePerMillion: Double? = null,
    val outputPricePerMillion: Double? = null,
    val cachePricePerMillion: Double? = null,
    val contextWindow: Long? = null,
    val supportsTools: Boolean? = null,
    val supportsVision: Boolean? = null,
)

internal fun PlusVibeCatalogEntryDto.toEnrichment(): ApiCatalogEnrichment {
    val variant = variants.firstOrNull { it.isDefault == true } ?: variants.firstOrNull()
    return ApiCatalogEnrichment(
        inputPricePerMillion = variant?.inputRub,
        outputPricePerMillion = variant?.outputRub,
        cachePricePerMillion = variant?.cachedInputRub,
        contextWindow = caps?.contextWindow ?: variant?.maxContext,
        supportsTools = caps?.toolCalling,
        supportsVision = caps?.vision ?: variant?.vision,
    )
}

internal fun AiTunnelCatalogEntryDto.toEnrichment(): ApiCatalogEnrichment = ApiCatalogEnrichment(
    inputPricePerMillion = prompt_cost,
    outputPricePerMillion = completion_cost,
    contextWindow = context_size,
    supportsVision = modalities?.input.orEmpty().any { it == "image" },
)

/** True when the model advertises function calling in its supported parameter list. */
internal fun OpenAICompatibleModelResponseDto.Model.supportsTools(): Boolean = (supported_parameters ?: top_provider?.supported_parameters).orEmpty()
    .any { it == "tools" || it == "tool_choice" }

/** True when the model accepts images as input. */
internal fun OpenAICompatibleModelResponseDto.Model.supportsVision(): Boolean = architecture?.input_modalities.orEmpty().any { it == "image" }
