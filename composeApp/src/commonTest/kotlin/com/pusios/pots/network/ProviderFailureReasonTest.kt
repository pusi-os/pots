package com.pusios.pots.network

import com.pusios.pots.data.Service
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.error_openai_compatible_connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Guards the diagnostic half of the heartbeat fix: a failure must arrive at the journal with a
 * reason and the provider's own words, never as a bare class name.
 */
class ProviderFailureReasonTest {

    /** Stands in for the transport exceptions Ktor throws; only its name matters. */
    private class UnknownHostException : Exception("unable to resolve host api.example.com")

    private class SocketTimeoutException : Exception("connect timed out")

    private class JsonConvertException : Exception("Unexpected JSON token at offset 42")

    @Test
    fun `every reason is reachable from a real failure`() {
        val reachable = mapOf(
            ProviderFailureReason.Unauthorized to OpenAICompatibleInvalidApiKeyException(),
            ProviderFailureReason.ModelNotFound to OpenAICompatibleModelNotFoundException(),
            ProviderFailureReason.RateLimited to OpenAICompatibleRateLimitExceededException(),
            ProviderFailureReason.Timeout to OpenAICompatibleTimeoutException(),
            ProviderFailureReason.Unreachable to UnknownHostException(),
            ProviderFailureReason.BadResponse to JsonConvertException(),
            ProviderFailureReason.Unknown to RuntimeException("something else"),
        )

        for ((expected, failure) in reachable) {
            assertEquals(expected, failure.providerFailureReason(), "wrong reason for ${failure::class.simpleName}")
        }
        // A reason nobody can produce would be dead weight in the journal's vocabulary.
        assertEquals(ProviderFailureReason.entries.toSet(), reachable.keys)
    }

    @Test
    fun `transport failures are told apart by their cause`() {
        assertEquals(ProviderFailureReason.Timeout, SocketTimeoutException().providerFailureReason())
        assertEquals(ProviderFailureReason.BadResponse, JsonConvertException().providerFailureReason())
        assertEquals(ProviderFailureReason.Unreachable, UnknownHostException().providerFailureReason())
    }

    @Test
    fun `connection exception keeps its reason`() {
        val failure = OpenAICompatibleConnectionException(ProviderFailureReason.Timeout, "no answer in 90s")
        assertEquals(ProviderFailureReason.Timeout, failure.providerFailureReason())
    }

    @Test
    fun `describe gives every reason its own text and keeps the provider detail`() {
        val lines = ProviderFailureReason.entries.map { describeConnectionFailure(it, "provider said no") }

        assertEquals(ProviderFailureReason.entries.size, lines.toSet().size, "two reasons render identically: $lines")
        for ((reason, line) in ProviderFailureReason.entries.zip(lines)) {
            assertTrue(line.startsWith(reason.name), "line for $reason does not start with its reason: $line")
            assertTrue(line.contains("provider said no"), "line for $reason lost the provider detail: $line")
        }
    }

    @Test
    fun `message survives wrapping and stays null-free`() {
        val failure = connectionFailure(UnknownHostException())

        assertIs<OpenAICompatibleConnectionException>(failure)
        assertEquals("Unreachable: unable to resolve host api.example.com", failure.message)
        assertEquals("unable to resolve host api.example.com", failure.detail)
        assertEquals(ProviderFailureReason.Unreachable, failure.reason)
    }

    @Test
    fun `a nameless exception still produces a readable line`() {
        val failure = connectionFailure(object : Exception() {})

        // No message, no class name to quote — the journal still gets a reason, not "null".
        assertEquals(ProviderFailureReason.Unknown.name, failure.message)
    }

    @Test
    fun `reason without detail renders as the bare reason`() {
        assertEquals("Timeout", describeConnectionFailure(ProviderFailureReason.Timeout, null))
        assertEquals("Timeout", describeConnectionFailure(ProviderFailureReason.Timeout, "   "))
    }

    @Test
    fun `retry policy follows the failure class`() {
        val retried = listOf(
            UnknownHostException(),
            SocketTimeoutException(),
            OpenAICompatibleConnectionException(ProviderFailureReason.Timeout),
            OpenAICompatibleConnectionException(ProviderFailureReason.Unreachable),
            OpenAICompatibleRateLimitExceededException(),
            // Unclassified failures keep the pre-diagnostics behaviour: they were retried before
            // this fix and a spare attempt costs less than a silently skipped retry.
            RuntimeException("something else"),
            OpenAICompatibleGenericException("bad gateway"),
            OpenAICompatibleConnectionException(ProviderFailureReason.Unknown, "odd"),
            // An empty answer is classified BadResponse by shape, but a retry is what helps.
            OpenAICompatibleEmptyResponseException(),
        )
        val notRetried = listOf(
            OpenAICompatibleInvalidApiKeyException(),
            OpenAICompatibleModelNotFoundException(),
            OpenAICompatibleBadRequestException("invalid model parameter"),
            OpenAICompatibleContentModerationException(),
            // Looks like a rate limit, but a retry cannot restore the quota.
            OpenAICompatibleQuotaExhaustedException(),
            ConfiguredInstanceNotFoundException("pulse-model"),
            NoConfiguredServiceException("no configured service instances"),
        )

        for (failure in retried) assertTrue(failure.isRetryableProviderFailure(), "${failure::class.simpleName} should be retried")
        for (failure in notRetried) assertFalse(failure.isRetryableProviderFailure(), "${failure::class.simpleName} must not be retried")
    }

    @Test
    fun `chat UI keeps its connection-failure message`() {
        val failure = OpenAICompatibleConnectionException(ProviderFailureReason.Unauthorized, "401 Unauthorized")
        val ui = failure.toUiError()

        // The reason is for the journal; the chat bubble keeps saying "cannot connect".
        assertIs<UiError.Resource>(ui)
        assertEquals(UiError.Resource(Res.string.error_openai_compatible_connection), ui)
    }

    @Test
    fun `a connection failure never triggers the free-provider upsell`() {
        // The provider's raw text may mention a rate limit; that is not a capacity verdict.
        val failure = OpenAICompatibleConnectionException(
            ProviderFailureReason.RateLimited,
            "429 too many requests, quota exhausted",
        )

        assertFalse(failure.isFreeCapacityError())
    }

    @Test
    fun `a keyless provider gets no key and therefore no auth header`() {
        val requests = Requests()

        // OpenAI-compatible with an empty key is the local-Ollama shape: no key, no header.
        assertNull(requests.getApiKeyOrThrow(Service.OpenAICompatible, ServiceCredentials(apiKey = "")))
        // A key-required provider with no key is a configuration error, not a silent empty header.
        val keyRequired = Service.all.firstOrNull { it.requiresApiKey } ?: fail("no key-required service in the catalogue")
        try {
            requests.getApiKeyOrThrow(keyRequired, ServiceCredentials(apiKey = ""))
            fail("${keyRequired.id} must reject an empty key")
        } catch (_: OpenAICompatibleInvalidApiKeyException) {
            // expected
        }
        assertEquals("sk-test", requests.getApiKeyOrThrow(keyRequired, ServiceCredentials(apiKey = "sk-test")))
    }
}
