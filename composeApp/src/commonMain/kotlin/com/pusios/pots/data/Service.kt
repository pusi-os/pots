package com.pusios.pots.data

import com.pusios.pots.BuildSecrets
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
import pots.composeapp.generated.resources.ic_service_free_expert
import pots.composeapp.generated.resources.ic_service_free_fast
import pots.composeapp.generated.resources.ic_service_gemini
import pots.composeapp.generated.resources.ic_service_gptunnel
import pots.composeapp.generated.resources.ic_service_groqcloud
import pots.composeapp.generated.resources.ic_service_huggingface
import pots.composeapp.generated.resources.ic_service_litert
import pots.composeapp.generated.resources.ic_service_longcat
import pots.composeapp.generated.resources.ic_service_minimax
import pots.composeapp.generated.resources.ic_service_mistral
import pots.composeapp.generated.resources.ic_service_moonshot
import pots.composeapp.generated.resources.ic_service_nvidia
import pots.composeapp.generated.resources.ic_service_ollamacloud
import pots.composeapp.generated.resources.ic_service_openai
import pots.composeapp.generated.resources.ic_service_openai_compatible
import pots.composeapp.generated.resources.ic_service_opencode
import pots.composeapp.generated.resources.ic_service_openrouter
import pots.composeapp.generated.resources.ic_service_perplexity
import pots.composeapp.generated.resources.ic_service_polza
import pots.composeapp.generated.resources.ic_service_proxyapi
import pots.composeapp.generated.resources.ic_service_publicai
import pots.composeapp.generated.resources.ic_service_requesty
import pots.composeapp.generated.resources.ic_service_together
import pots.composeapp.generated.resources.ic_service_venice
import pots.composeapp.generated.resources.ic_service_vsegpt
import pots.composeapp.generated.resources.ic_service_xai
import pots.composeapp.generated.resources.ic_service_zai
import pots.composeapp.generated.resources.service_free_expert
import pots.composeapp.generated.resources.service_free_fast

/**
 * Chat endpoint of the hosted free tier.
 *
 * The endpoint speaks the OpenAI chat-completions protocol, so the regular OpenAI-compatible
 * request path is used unchanged.
 */
const val FREE_TIER_CHAT_URL = "https://omni.prosvetim.ru/v1/chat/completions"

/** Model served by the hosted free tier. */
const val FREE_TIER_MODEL_ID = "pots4free"

/** OpenCode Zen's free model used by the Trial Flash preset. */
const val OPENCODE_FREE_MODEL_ID = "big-pickle"

const val OPENCODE_CHAT_URL = "https://opencode.ai/zen/v1/chat/completions"
const val OPENCODE_MODELS_URL = "https://opencode.ai/zen/v1/models"

enum class FreeMode(
    val modelId: String,
    val nameRes: StringResource,
    val icon: DrawableResource,
    val service: Service,
    val displayName: String,
) {
    FAST(OPENCODE_FREE_MODEL_ID, Res.string.service_free_fast, Res.drawable.ic_service_free_fast, Service.OpenCodeFree, "Trial Flash"),
    EXPERT(FREE_TIER_MODEL_ID, Res.string.service_free_expert, Res.drawable.ic_service_free_expert, Service.Free, "Trial Smart"),
    ;

    val instanceId: String get() = "free-$modelId"
}

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
     * `tool_calls`. Truly required by Z.AI Coding Plan, OpenCode Zen (DeepSeek
     * route), and Moonshot kimi-k2.6 with `thinking.keep="all"`. Accepted as a
     * documented field by Fireworks, Z.AI standard, and OpenRouter (as an alias
     * for `reasoning`). Tolerated as an unknown field by LongCat, Venice, MiniMax.
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
    val isOnDevice: Boolean = false,
    val supportsPdf: Boolean = false,
    val supportsImages: Boolean = true,
    val reasoningRequestMode: ReasoningRequestMode = ReasoningRequestMode.NONE,
    val apiKeyAuthStyle: ApiKeyAuthStyle = ApiKeyAuthStyle.BEARER,
    /** A built-in free plan that must never be added as an ordinary configured service. */
    val isFreeTier: Boolean = false,
    /**
     * API key shipped with the build and used when the user has not entered one.
     *
     * Only meaningful for services that accept a key without requiring one; see
     * [supportsOptionalApiKey]. The value comes from the generated `BuildSecrets` object and
     * is therefore never part of the repository.
     */
    val defaultApiKey: String = "",
) {
    data object Free : Service(
        id = "free",
        displayName = "Pots Free",
        icon = Res.drawable.ic_service_free_fast,
        requiresApiKey = false,
        // The hosted free tier accepts a key but does not demand one: the build ships a
        // default, and the user can replace it with their own in the settings.
        supportsOptionalApiKey = true,
        defaultModel = FREE_TIER_MODEL_ID,
        settingsKeyPrefix = "",
        chatUrl = FREE_TIER_CHAT_URL,
        modelsUrl = null,
        defaultApiKey = BuildSecrets.FREE_TIER_API_KEY,
        // The free tier serves text-only models, so images can't be promised on this path.
        supportsImages = false,
        isFreeTier = true,
    )

    /**
     * OpenCode Zen's free tier. The official client authenticates its free models with the
     * literal key `public` and identifies itself with the `x-opencode-*` harness headers.
     * A user-supplied key still wins, so a personal OpenCode key can be used here too.
     */
    data object OpenCodeFree : Service(
        id = "opencode-free",
        displayName = "OpenCode Free",
        icon = Res.drawable.ic_service_opencode,
        requiresApiKey = false,
        supportsOptionalApiKey = true,
        defaultModel = OPENCODE_FREE_MODEL_ID,
        settingsKeyPrefix = "opencode-free",
        defaultModels = listOf(ModelDefinition(id = OPENCODE_FREE_MODEL_ID, subtitle = "OpenCode Zen")),
        chatUrl = OPENCODE_CHAT_URL,
        modelsUrl = OPENCODE_MODELS_URL,
        apiKeyUrl = "https://opencode.ai/docs/zen/",
        apiKeyUrlDisplay = "opencode.ai/docs/zen",
        reasoningRequestMode = ReasoningRequestMode.REASONING_CONTENT,
        defaultApiKey = "public",
        isFreeTier = true,
    )

    data object Polza : Service(
        id = "polza",
        displayName = "Polza.ai",
        icon = Res.drawable.ic_service_polza,
        requiresApiKey = true,
        defaultModel = null,
        settingsKeyPrefix = "polza",
        chatUrl = "https://polza.ai/api/v1/chat/completions",
        modelsUrl = "https://polza.ai/api/v1/models",
        apiKeyUrl = "https://polza.ai/dashboard",
        apiKeyUrlDisplay = "polza.ai/dashboard",
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
        apiKeyUrl = "https://gptunnel.ru/profile",
        apiKeyUrlDisplay = "gptunnel.ru/profile",
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
        apiKeyUrl = "https://aitunnel.ru/keys",
        apiKeyUrlDisplay = "aitunnel.ru/keys",
        reasoningRequestMode = ReasoningRequestMode.REASONING_CONTENT,
    )

    data object ProxyApi : Service(
        id = "proxyapi",
        displayName = "ProxyAPI",
        icon = Res.drawable.ic_service_proxyapi,
        requiresApiKey = true,
        defaultModel = null,
        settingsKeyPrefix = "proxyapi",
        chatUrl = "https://api.proxyapi.ru/openai/v1/chat/completions",
        modelsUrl = "https://api.proxyapi.ru/openai/v1/models",
        apiKeyUrl = "https://console.proxyapi.ru/keys",
        apiKeyUrlDisplay = "console.proxyapi.ru/keys",
        supportsPdf = true,
        reasoningRequestMode = ReasoningRequestMode.REASONING_CONTENT,
    )

    data object VseGpt : Service(
        id = "vsegpt",
        displayName = "VseGPT",
        icon = Res.drawable.ic_service_vsegpt,
        requiresApiKey = true,
        defaultModel = null,
        settingsKeyPrefix = "vsegpt",
        chatUrl = "https://api.vsegpt.ru/v1/chat/completions",
        modelsUrl = "https://api.vsegpt.ru/v1/models",
        apiKeyUrl = "https://vsegpt.ru/User/Keys",
        apiKeyUrlDisplay = "vsegpt.ru/User/Keys",
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
            OpenCodeFree,
            Free,
            Polza,
            Gptunnel,
            AiTunnel,
            ProxyApi,
            VseGpt,
            OpenAICompatible,
            LiteRT,
        )

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

        fun fromId(id: String): Service = all.find { it.id == id } ?: OpenCodeFree
    }

    val apiKeyKey: String get() = "service_${settingsKeyPrefix}_api_key"
    val modelIdKey: String get() = "service_${settingsKeyPrefix}_model_id"
    val baseUrlKey: String get() = "service_${settingsKeyPrefix}_base_url"
}
