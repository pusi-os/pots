package com.pusios.pots.network

import com.pusios.pots.inference.InferenceTimeoutException
import com.pusios.pots.inference.InsufficientMemoryException
import com.pusios.pots.inference.ModelIntegrityException
import com.pusios.pots.inference.NoModelDownloadedException
import org.jetbrains.compose.resources.StringResource
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.error_all_services_failed
import pots.composeapp.generated.resources.error_bad_request
import pots.composeapp.generated.resources.error_content_moderation
import pots.composeapp.generated.resources.error_context_window_exceeded
import pots.composeapp.generated.resources.error_empty_response
import pots.composeapp.generated.resources.error_file_too_large
import pots.composeapp.generated.resources.error_image_too_large
import pots.composeapp.generated.resources.error_insufficient_credits
import pots.composeapp.generated.resources.error_invalid_api_key
import pots.composeapp.generated.resources.error_openai_compatible_connection
import pots.composeapp.generated.resources.error_openai_compatible_model_not_found
import pots.composeapp.generated.resources.error_provider_error
import pots.composeapp.generated.resources.error_quota_exhausted
import pots.composeapp.generated.resources.error_rate_limit_exceeded
import pots.composeapp.generated.resources.error_service_unavailable
import pots.composeapp.generated.resources.error_unknown
import pots.composeapp.generated.resources.error_unsupported_file_type
import pots.composeapp.generated.resources.litert_error_inference_timeout
import pots.composeapp.generated.resources.litert_error_insufficient_memory
import pots.composeapp.generated.resources.litert_error_model_integrity
import pots.composeapp.generated.resources.litert_error_no_model
import pots.composeapp.generated.resources.onboarding_skipped

sealed class ApiException(message: String?, cause: Throwable? = null) : Exception(message, cause)

class GenericNetworkException(message: String, cause: Throwable? = null) : ApiException(message, cause)

sealed class GeminiApiException(message: String? = null, cause: Throwable? = null) : ApiException(message, cause)
class GeminiGenericException(message: String, cause: Throwable? = null) : GeminiApiException(message, cause)
class GeminiRateLimitExceededException : GeminiApiException()
class GeminiInvalidApiKeyException : GeminiApiException()

sealed class AnthropicApiException(message: String? = null, cause: Throwable? = null) : ApiException(message, cause)
class AnthropicGenericException(message: String, cause: Throwable? = null) : AnthropicApiException(message, cause)
class AnthropicInvalidApiKeyException : AnthropicApiException()
class AnthropicRateLimitExceededException : AnthropicApiException()
class AnthropicOverloadedException : AnthropicApiException()
class AnthropicInsufficientCreditsException : AnthropicApiException()

sealed class OpenAICompatibleApiException(message: String? = null, cause: Throwable? = null) : ApiException(message, cause)
class OpenAICompatibleGenericException(message: String, cause: Throwable? = null) : OpenAICompatibleApiException(message, cause)
class OpenAICompatibleInvalidApiKeyException : OpenAICompatibleApiException()
class OpenAICompatibleRateLimitExceededException : OpenAICompatibleApiException()
class OpenAICompatibleQuotaExhaustedException : OpenAICompatibleApiException()

/**
 * The call never produced a usable answer: the endpoint was unreachable, the request timed
 * out, or the response could not be understood. [reason] and [detail] exist because a bare
 * class name in the heartbeat journal tells the owner nothing — "provider down", "bad key"
 * and "model does not exist" all used to arrive as this one nameless type.
 */
class OpenAICompatibleConnectionException(
    val reason: ProviderFailureReason = ProviderFailureReason.Unknown,
    val detail: String? = null,
) : OpenAICompatibleApiException(describeConnectionFailure(reason, detail))

class OpenAICompatibleModelNotFoundException : OpenAICompatibleApiException()
class OpenAICompatibleEmptyResponseException : OpenAICompatibleApiException()
class OpenAICompatibleRequestTooLargeException : OpenAICompatibleApiException()
class OpenAICompatibleContentModerationException(detail: String? = null) : OpenAICompatibleApiException(detail)
class OpenAICompatibleProviderErrorException(detail: String? = null) : OpenAICompatibleApiException(detail)
class OpenAICompatibleServiceUnavailableException : OpenAICompatibleApiException()
class OpenAICompatibleTimeoutException : OpenAICompatibleApiException()
class OpenAICompatibleBadRequestException(detail: String? = null) : OpenAICompatibleApiException(detail)

class ContextWindowExceededException : ApiException(null)
class UnsupportedFileTypeException : ApiException(null)
class FileTooLargeException : ApiException(null)
class AllServicesFailedException : ApiException(null)

/**
 * Machine-readable reason behind a failed provider call. The heartbeat journal prints it as
 * the first word of the failure line, so "bad key" and "provider down" are told apart at a
 * glance instead of both arriving as one exception class name.
 */
enum class ProviderFailureReason {
    Unreachable,
    Timeout,
    Unauthorized,
    ModelNotFound,
    RateLimited,
    BadResponse,
    Unknown,
    ;

    /**
     * Whether repeating the same call against the same instance can plausibly help.
     * [Unknown] stays retryable on purpose: an unclassified failure used to be retried, and a
     * spare attempt costs less than silently dropping a retry that would have worked.
     */
    val isRetryable: Boolean
        get() = this == Unreachable || this == Timeout || this == RateLimited || this == Unknown
}

/** The journal/UI line for a connection failure: `Reason: provider detail`. */
internal fun describeConnectionFailure(reason: ProviderFailureReason, detail: String?): String = detail?.trim()?.takeIf { it.isNotEmpty() }?.let { "${reason.name}: $it" } ?: reason.name

/**
 * Classifies any provider failure. Typed API exceptions are classified by their type — a 401
 * is [ProviderFailureReason.Unauthorized] whether it arrived as an invalid-key exception or as
 * a raw transport error — and everything else by its cause.
 */
fun Throwable.providerFailureReason(): ProviderFailureReason = when (this) {
    is OpenAICompatibleConnectionException -> reason

    is GeminiInvalidApiKeyException,
    is AnthropicInvalidApiKeyException,
    is OpenAICompatibleInvalidApiKeyException,
    -> ProviderFailureReason.Unauthorized

    is OpenAICompatibleModelNotFoundException -> ProviderFailureReason.ModelNotFound

    is GeminiRateLimitExceededException,
    is AnthropicRateLimitExceededException,
    is AnthropicOverloadedException,
    is OpenAICompatibleRateLimitExceededException,
    is OpenAICompatibleQuotaExhaustedException,
    is AnthropicInsufficientCreditsException,
    -> ProviderFailureReason.RateLimited

    is InferenceTimeoutException, is OpenAICompatibleTimeoutException -> ProviderFailureReason.Timeout

    // The provider answered, but with "I am down" — the endpoint is unusable, not misconfigured.
    is OpenAICompatibleServiceUnavailableException, is OpenAICompatibleProviderErrorException -> ProviderFailureReason.Unreachable

    is OpenAICompatibleEmptyResponseException,
    is OpenAICompatibleBadRequestException,
    is OpenAICompatibleRequestTooLargeException,
    is OpenAICompatibleContentModerationException,
    is ContextWindowExceededException,
    is UnsupportedFileTypeException,
    is FileTooLargeException,
    -> ProviderFailureReason.BadResponse

    else -> transportFailureReason()
}

/**
 * Classification for exceptions no provider type describes — transport and serialization
 * failures, which reach the callers as whatever Ktor or kotlinx.serialization threw.
 */
internal fun Throwable.transportFailureReason(): ProviderFailureReason {
    val name = this::class.simpleName.orEmpty()
    val text = message.orEmpty()
    return when {
        name.contains("Timeout", ignoreCase = true) || text.contains("timed out", ignoreCase = true) -> ProviderFailureReason.Timeout

        name.contains("Serializ", ignoreCase = true) ||
            name.contains("JsonConvert", ignoreCase = true) ||
            name.contains("JsonDecod", ignoreCase = true) -> ProviderFailureReason.BadResponse

        name.endsWith("IOException") ||
            name.contains("ConnectException", ignoreCase = true) ||
            name.contains("UnknownHost", ignoreCase = true) ||
            name.contains("NoRoute", ignoreCase = true) ||
            name.contains("SocketException", ignoreCase = true) ||
            name.contains("Unresolved", ignoreCase = true) ||
            name.contains("Handshake", ignoreCase = true) -> ProviderFailureReason.Unreachable

        else -> ProviderFailureReason.Unknown
    }
}

/**
 * Whether a failed call is worth repeating against the same instance. Rejected credentials, a
 * missing model, a malformed request and exhausted credit all fail identically a second later,
 * so they go straight to the fallback instead of burning the retry budget.
 *
 * Everything not listed here is retried: this list is the explicit "a repeat cannot help" set,
 * so an unclassified failure keeps the old behaviour of getting another attempt.
 */
fun Exception.isRetryableProviderFailure(): Boolean = when (this) {
    // Quota and credit exhaustion look like a rate limit but will not clear on a retry.
    is OpenAICompatibleQuotaExhaustedException,
    is AnthropicInsufficientCreditsException,
    is NoConfiguredServiceException,
    is ConfiguredInstanceNotFoundException,
    -> false

    // The provider answered with nothing. Classified as BadResponse by shape, but a retry is
    // exactly what helps here, so it must not join the straight-to-fallback group above.
    is OpenAICompatibleEmptyResponseException -> true

    else -> providerFailureReason().isRetryable
}

/**
 * The instance a background run was pinned to no longer exists — its provider was removed,
 * re-created under a new id, or settings were imported from another device. Deliberately not
 * "use whatever remote is configured instead": the run reports the missing pin rather than
 * silently reporting on a model the owner did not choose.
 */
class ConfiguredInstanceNotFoundException(val instanceId: String) : ApiException("configured instance not found: $instanceId")

/**
 * The request could not be routed at all: the fallback chain was empty — no
 * services are configured, or none of the configured instances has a valid API
 * key. Deliberately distinct from [OpenAICompatibleEmptyResponseException]
 * ("a provider answered with nothing") so the UI can offer provider setup
 * instead of claiming a response was lost.
 */
class NoConfiguredServiceException(message: String? = null) : ApiException(message)

sealed interface UiError {
    data class Resource(val resource: StringResource) : UiError
    data class Text(val message: String) : UiError
    data class ResourceWithDetail(val resource: StringResource, val detail: String) : UiError
}

/**
 * Errors that mean the built-in Free path (or another free-capacity quota)
 * is exhausted — used to decide whether to show free-provider signup suggestions.
 *
 * The pots Free proxy often returns HTTP 500 with a body like
 * "All free providers failed" rather than a pure 429, so message matching
 * is required in addition to typed rate-limit exceptions.
 */
fun Exception.isFreeCapacityError(): Boolean = when (this) {
    is OpenAICompatibleRateLimitExceededException,
    is OpenAICompatibleQuotaExhaustedException,
    is GeminiRateLimitExceededException,
    is AnthropicRateLimitExceededException,
    is AnthropicOverloadedException,
    -> true

    is OpenAICompatibleProviderErrorException,
    is OpenAICompatibleServiceUnavailableException,
    is OpenAICompatibleGenericException,
    is OpenAICompatibleBadRequestException,
    -> messageLooksLikeFreeCapacity(message)

    // A connection failure carries a diagnostic reason and the provider's raw words, not a
    // capacity verdict: never offer the free-provider upsell for one.
    is OpenAICompatibleConnectionException -> false

    else -> messageLooksLikeFreeCapacity(message)
}

/** Message patterns used by Free / capacity exhaustion across providers. */
internal fun messageLooksLikeFreeCapacity(message: String?): Boolean {
    val m = message?.lowercase() ?: return false
    return m.contains("all free providers failed") ||
        m.contains("free providers failed") ||
        m.contains("all free providers") ||
        m.contains("rate limit") ||
        m.contains("rate_limit") ||
        m.contains("too many requests") ||
        m.contains("quota") ||
        m.contains("capacity") ||
        m.contains("overloaded")
}

/**
 * Show the free-provider upsell when routing was impossible in the first place
 * (empty fallback chain → [NoConfiguredServiceException]; this is the second
 * chance after the onboarding wizard was skipped, spec §5.4), or when Free is
 * the sole path (no user services configured) and the failure is a free-capacity
 * rate/quota limit.
 */
fun shouldShowFreeProviderSuggestions(
    noConfiguredServices: Boolean,
    exception: Exception,
): Boolean = exception is NoConfiguredServiceException ||
    (noConfiguredServices && exception.isFreeCapacityError())

fun Exception.toUiError(): UiError = when (this) {
    is UnsupportedFileTypeException -> UiError.Resource(Res.string.error_unsupported_file_type)

    is FileTooLargeException -> UiError.Resource(Res.string.error_file_too_large)

    is ContextWindowExceededException -> UiError.Resource(Res.string.error_context_window_exceeded)

    is AllServicesFailedException -> UiError.Resource(Res.string.error_all_services_failed)

    is OpenAICompatibleRequestTooLargeException -> UiError.Resource(Res.string.error_image_too_large)

    is GeminiInvalidApiKeyException, is OpenAICompatibleInvalidApiKeyException, is AnthropicInvalidApiKeyException -> UiError.Resource(Res.string.error_invalid_api_key)

    is GeminiRateLimitExceededException, is OpenAICompatibleRateLimitExceededException, is AnthropicRateLimitExceededException -> UiError.Resource(Res.string.error_rate_limit_exceeded)

    is AnthropicOverloadedException -> UiError.Resource(Res.string.error_rate_limit_exceeded)

    is AnthropicInsufficientCreditsException -> UiError.Resource(Res.string.error_insufficient_credits)

    is OpenAICompatibleQuotaExhaustedException -> UiError.Resource(Res.string.error_quota_exhausted)

    is OpenAICompatibleConnectionException -> UiError.Resource(Res.string.error_openai_compatible_connection)

    is OpenAICompatibleModelNotFoundException -> UiError.Resource(Res.string.error_openai_compatible_model_not_found)

    is OpenAICompatibleEmptyResponseException -> UiError.Resource(Res.string.error_empty_response)

    // Nothing to route to: the honest message is @stiv's "until a model is
    // connected, I stay silent" copy — never "no response received".
    is NoConfiguredServiceException -> UiError.Resource(Res.string.onboarding_skipped)

    is OpenAICompatibleTimeoutException -> UiError.Resource(Res.string.error_openai_compatible_connection)

    is OpenAICompatibleServiceUnavailableException -> UiError.Resource(Res.string.error_service_unavailable)

    is OpenAICompatibleContentModerationException -> message?.takeIf { it.isNotBlank() }
        ?.let { UiError.ResourceWithDetail(Res.string.error_content_moderation, it) }
        ?: UiError.Resource(Res.string.error_content_moderation)

    is OpenAICompatibleProviderErrorException -> message?.takeIf { it.isNotBlank() }
        ?.let { UiError.ResourceWithDetail(Res.string.error_provider_error, it) }
        ?: UiError.Resource(Res.string.error_provider_error)

    is OpenAICompatibleBadRequestException -> message?.takeIf { it.isNotBlank() }
        ?.let { UiError.ResourceWithDetail(Res.string.error_bad_request, it) }
        ?: UiError.Resource(Res.string.error_bad_request)

    is InsufficientMemoryException -> UiError.Resource(Res.string.litert_error_insufficient_memory)

    is InferenceTimeoutException -> UiError.Resource(Res.string.litert_error_inference_timeout)

    is NoModelDownloadedException -> UiError.Resource(Res.string.litert_error_no_model)

    is ModelIntegrityException -> UiError.Resource(Res.string.litert_error_model_integrity)

    is GeminiGenericException, is OpenAICompatibleGenericException, is AnthropicGenericException, is GenericNetworkException ->
        if (!message.isNullOrBlank()) UiError.Text(message!!) else UiError.Resource(Res.string.error_unknown)

    else -> if (!message.isNullOrBlank()) UiError.Text(message!!) else UiError.Resource(Res.string.error_unknown)
}
