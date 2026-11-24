package com.pusios.pots.data

import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.ic_service_1minai
import pots.composeapp.generated.resources.ic_service_aihorde
import pots.composeapp.generated.resources.ic_service_aihubmix
import pots.composeapp.generated.resources.ic_service_aitunnel
import pots.composeapp.generated.resources.ic_service_anthropic
import pots.composeapp.generated.resources.ic_service_atlascloud
import pots.composeapp.generated.resources.ic_service_cerebras
import pots.composeapp.generated.resources.ic_service_deepinfra
import pots.composeapp.generated.resources.ic_service_deepseek
import pots.composeapp.generated.resources.ic_service_fireworksai
import pots.composeapp.generated.resources.ic_service_gemini
import pots.composeapp.generated.resources.ic_service_gptunnel
import pots.composeapp.generated.resources.ic_service_groqcloud
import pots.composeapp.generated.resources.ic_service_huggingface
import pots.composeapp.generated.resources.ic_service_litert
import pots.composeapp.generated.resources.ic_service_llm_router
import pots.composeapp.generated.resources.ic_service_longcat
import pots.composeapp.generated.resources.ic_service_minimax
import pots.composeapp.generated.resources.ic_service_mistral
import pots.composeapp.generated.resources.ic_service_moonshot
import pots.composeapp.generated.resources.ic_service_nvidia
import pots.composeapp.generated.resources.ic_service_ollamacloud
import pots.composeapp.generated.resources.ic_service_openai
import pots.composeapp.generated.resources.ic_service_openai_compatible
import pots.composeapp.generated.resources.ic_service_openrouter
import pots.composeapp.generated.resources.ic_service_perplexity
import pots.composeapp.generated.resources.ic_service_plusvibe
import pots.composeapp.generated.resources.ic_service_polza
import pots.composeapp.generated.resources.ic_service_publicai
import pots.composeapp.generated.resources.ic_service_requesty
import pots.composeapp.generated.resources.ic_service_together
import pots.composeapp.generated.resources.ic_service_venice
import pots.composeapp.generated.resources.ic_service_xai
import pots.composeapp.generated.resources.ic_service_zai

data class ModelDefinition(
    val id: String,
    val subtitle: String,
    val descriptionRes: StringResource? = null,
)

/** How a provider expects its API key in the `Authorization` header. */
enum class ApiKeyAuthStyle {
    /** `Authorization: Bearer <key>` (OpenAI and almost every compatible gateway). */
    BEARER,

    /** `Authorization: <key>` (GPTunnel's documented scheme). */
    RAW,
}

enum class ReasoningRequestMode {
    /** Strip the field before sending. Safe default. */
    NONE,

    /**
     * Echo `reasoning_content` back on assistant turns that previously produced
     * `tool_calls`. Truly required by Z.AI Coding Plan and Moonshot kimi-k2.6 with
     * `thinking.keep="all"`. Accepted as a documented field by Fireworks, Z.AI standard,
     * and OpenRouter (as an alias for `reasoning`). Tolerated as an unknown field by
     * LongCat, Venice, MiniMax.
     *
     * See `docs/features/reasoning.md` for the authoritative per-provider matrix
     * and known fidelity gaps (`reasoning_details`, `<think>`-in-content, paired
     * flags like `clear_thinking` and `reasoning_history`).
     */
    REASONING_CONTENT,
}

sealed class Service(
    val id: String,
    val displayName: String,
    val icon: DrawableResource,
    val requiresApiKey: Boolean,
    val supportsOptionalApiKey: Boolean = false,
    val defaultModel: String?,
    val settingsKeyPrefix: String,
    val defaultModels: List<ModelDefinition> = emptyList(),
    val chatUrl: String = "",
    /**
     * OpenAI Responses API endpoint (`/v1/responses`), for the services that expose one. Only
     * reached for models that [requiresResponsesApi] selects; every other model stays on
     * [chatUrl].
     */
    val responsesUrl: String? = null,
    val modelsUrl: String? = null,
    val modelsResponseIsArray: Boolean = false,
    val filterActiveStrictly: Boolean = false,
    val filterByModelType: Boolean = false,
    val sortModelsById: Boolean = false,
    val apiKeyUrl: String? = null,
    val apiKeyUrlDisplay: String? = null,
    /**
     * Direct URL to the provider's API-keys page in its cabinet (opens the keys section for a
     * logged-in user; no referral parameter — referrals only fire on sign-up). Shown on the
     * onboarding "you already have an account" button next to the [apiKeyUrl] sign-up link.
     */
    val cabinetApiKeysUrl: String? = null,
    val isOnDevice: Boolean = false,
    val supportsPdf: Boolean = false,
    val supportsImages: Boolean = true,
    val reasoningRequestMode: ReasoningRequestMode = ReasoningRequestMode.NONE,
    val apiKeyAuthStyle: ApiKeyAuthStyle = ApiKeyAuthStyle.BEARER,
) {
    data object Polza : Service(
        id = "polza",
        displayName = "Polza.ai",
        icon = Res.drawable.ic_service_polza,
        requiresApiKey = true,
        defaultModel = null,
        settingsKeyPrefix = "polza",
        chatUrl = "https://polza.ai/api/v1/chat/completions",
        modelsUrl = "https://polza.ai/api/v1/models",
        apiKeyUrl = "https://polza.ai/?referral=GAiyEDme7O",
        cabinetApiKeysUrl = "https://polza.ai/dashboard/api-keys",
        apiKeyUrlDisplay = "polza.ai",
        reasoningRequestMode = ReasoningRequestMode.REASONING_CONTENT,
    )

    data object Gptunnel : Service(
        id = "gptunnel",
        displayName = "GPTunnel",
        icon = Res.drawable.ic_service_gptunnel,
        requiresApiKey = true,
        defaultModel = null,
        settingsKeyPrefix = "gptunnel",
        chatUrl = "https://gptunnel.ru/v1/chat/completions",
        modelsUrl = "https://gptunnel.ru/v1/models",
        apiKeyUrl = "https://gptunnel.ru/?ref=pots",
        cabinetApiKeysUrl = "https://gptunnel.ru/profile/api",
        apiKeyUrlDisplay = "gptunnel.ru",
        apiKeyAuthStyle = ApiKeyAuthStyle.RAW,
        reasoningRequestMode = ReasoningRequestMode.REASONING_CONTENT,
    )

    data object AiTunnel : Service(
        id = "aitunnel",
        displayName = "AITUNNEL",
        icon = Res.drawable.ic_service_aitunnel,
        requiresApiKey = true,
        defaultModel = null,
        settingsKeyPrefix = "aitunnel",
        chatUrl = "https://api.aitunnel.ru/v1/chat/completions",
        modelsUrl = "https://api.aitunnel.ru/v1/models",
        apiKeyUrl = "https://aitunnel.ru/?r=67643",
        cabinetApiKeysUrl = "https://aitunnel.ru/panel/keys",
        apiKeyUrlDisplay = "aitunnel.ru",
        reasoningRequestMode = ReasoningRequestMode.REASONING_CONTENT,
    )

    data object PlusVibe : Service(
        id = "plusvibe",
        displayName = "PlusVibeAPI",
        icon = Res.drawable.ic_service_plusvibe,
        requiresApiKey = true,
        defaultModel = null,
        settingsKeyPrefix = "plusvibe",
        chatUrl = "https://plusvibeapi.ru/v1/chat/completions",
        modelsUrl = "https://plusvibeapi.ru/v1/models",
        apiKeyUrl = "https://plusvibeapi.ru/?ref=9APNMZKK",
        cabinetApiKeysUrl = "https://plusvibeapi.ru/dashboard/keys",
        apiKeyUrlDisplay = "plusvibeapi.ru",
        reasoningRequestMode = ReasoningRequestMode.REASONING_CONTENT,
    )

    data object PlusVibeReserve : Service(
        id = "plusvibe-reserve",
        displayName = "PlusVibeAPI (резерв)",
        icon = Res.drawable.ic_service_plusvibe,
        requiresApiKey = true,
        defaultModel = null,
        settingsKeyPrefix = "plusvibe-reserve",
        chatUrl = "https://gw.plusvibeapi.ru/v1/chat/completions",
        modelsUrl = "https://gw.plusvibeapi.ru/v1/models",
        apiKeyUrl = "https://plusvibeapi.ru/?ref=9APNMZKK",
        cabinetApiKeysUrl = "https://plusvibeapi.ru/dashboard/keys",
        apiKeyUrlDisplay = "plusvibeapi.ru",
        reasoningRequestMode = ReasoningRequestMode.REASONING_CONTENT,
    )

    data object LlmRouter : Service(
        id = "llm-router",
        displayName = "LLM Router",
        icon = Res.drawable.ic_service_llm_router,
        requiresApiKey = true,
        defaultModel = null,
        settingsKeyPrefix = "llm-router",
        chatUrl = "https://llm-router.org/v1/chat/completions",
        modelsUrl = "https://llm-router.org/v1/models",
        apiKeyUrl = "https://llm-router.org?ref=kxknsvt9",
        cabinetApiKeysUrl = "https://llm-router.org/cabinet",
        apiKeyUrlDisplay = "llm-router.org",
        reasoningRequestMode = ReasoningRequestMode.REASONING_CONTENT,
    )

    data object OpenAICompatible : Service(
        id = "openai-compatible",
        displayName = "OpenAI-Compatible API",
        icon = Res.drawable.ic_service_openai_compatible,
        requiresApiKey = false,
        supportsOptionalApiKey = true,
        defaultModel = null,
        settingsKeyPrefix = "openai-compatible",
        chatUrl = "/chat/completions",
        responsesUrl = "/responses",
        modelsUrl = "/models",
        sortModelsById = true,
    )

    data object LiteRT : Service(
        id = "litert",
        displayName = "Local Model",
        icon = Res.drawable.ic_service_litert,
        requiresApiKey = false,
        defaultModel = null,
        settingsKeyPrefix = "litert",
        isOnDevice = true,
    )

    companion object {
        /** Providers offered in the RF build. */
        val all: List<Service> get() = listOf(
            Polza,
            Gptunnel,
            AiTunnel,
            PlusVibe,
            PlusVibeReserve,
            LlmRouter,
            OpenAICompatible,
            LiteRT,
        )

        /**
         * Providers offered on the onboarding picker (card t_6ecd4626), in display order:
         * full-functionality services with a balance, PlusVibe first and preselected.
         * A constant, not computed — a provider joins this list explicitly once its
         * balance and full media support are real.
         */
        val onboardingServices: List<Service> get() = listOf(PlusVibe, Polza, AiTunnel, Gptunnel, LlmRouter)

        const val DEFAULT_OPENAI_COMPATIBLE_BASE_URL = "http://localhost:11434/v1"

        /**
         * Base URL for an OpenAI-Compatible instance, ready for an endpoint path to be appended.
         * Users often paste the full chat endpoint (`…/v1/chat/completions`); that suffix is dropped
         * so it isn't doubled, and so the models and responses paths resolve against the real base.
         */
        fun normalizeOpenAICompatibleBaseUrl(baseUrl: String): String = baseUrl.ifBlank { DEFAULT_OPENAI_COMPATIBLE_BASE_URL }
            .trim()
            .trimEnd('/')
            .let { url -> if (url.endsWith(OpenAICompatible.chatUrl, ignoreCase = true)) url.dropLast(OpenAICompatible.chatUrl.length) else url }
            .trimEnd('/')

        /** Resolves a stored provider id; ids of removed or unknown providers yield null. */
        fun fromId(id: String): Service? = all.find { it.id == id }

        // Compatibility aliases for possible naming variants.
        val PlusVibePrimary = PlusVibe
        val PlusVibeApi = PlusVibe
        val PlusVibeAPI = PlusVibe
        val PlusVibeGw = PlusVibeReserve
        val PlusVibeFallback = PlusVibeReserve
        val PlusVibeBackup = PlusVibeReserve
        val PlusVibeSecondary = PlusVibeReserve
    }

    val apiKeyKey: String get() = "service_${settingsKeyPrefix}_api_key"
    val modelIdKey: String get() = "service_${settingsKeyPrefix}_model_id"
    val baseUrlKey: String get() = "service_${settingsKeyPrefix}_base_url"
}

/**
 * Caption shown next to the per-token price in the model sheet tiles: the provider's price
 * currency and the token volume the price is quoted for (researcher report for 3.20.0, board
 * task t_b1dce70b, section B). Null when the service's price currency is unknown — the tile
 * falls back to the plain "M" caption.
 */
val Service.priceTileUnit: String?
    get() = when (this) {
        Service.PlusVibe, Service.PlusVibeReserve, Service.Polza, Service.AiTunnel -> "₽/1M"
        Service.Gptunnel -> "₽/1K"
        Service.LlmRouter -> "$/1M"
        else -> null
    }
