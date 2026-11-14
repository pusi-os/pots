package com.pusios.pots.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Provider HTTP codes → typed media errors. 422 is server-side validation
 * (unknown parameter or combination, spec §19.4), so it maps to BadRequest,
 * while 404 stays the missing-model/unknown-job case.
 */
class MediaErrorMappingTest {

    private val provider = PlusVibeMediaProvider()

    private fun map(code: Int, body: String = "{}"): MediaException = provider.mapError(code, body)

    @Test
    fun httpCodesMapToTypedErrors() {
        assertIs<MediaException.BadRequest>(map(400))
        assertIs<MediaException.InvalidKey>(map(401))
        assertIs<MediaException.InvalidKey>(map(403))
        assertIs<MediaException.InsufficientBalance>(map(402))
        assertIs<MediaException.ModelNotFound>(map(404))
        assertIs<MediaException.BadRequest>(map(422))
        assertIs<MediaException.RateLimited>(map(429))
        assertIs<MediaException.Expired>(map(410))
        assertIs<MediaException.Unavailable>(map(503))
        assertIs<MediaException.UpstreamFailed>(map(502))
        assertIs<MediaException.Unknown>(map(500))
    }

    @Test
    fun detailExtractedFromErrorEnvelope() {
        val body = """{"error":{"message":"quality is not a valid parameter"}}"""
        assertEquals("quality is not a valid parameter", (map(422, body) as MediaException.BadRequest).detail)
    }

    @Test
    fun detailFallsBackToFlatMessageField() {
        val body = """{"message":"баланс исчерпан"}"""
        assertEquals("баланс исчерпан", (map(402, body) as MediaException.InsufficientBalance).detail)
    }

    @Test
    fun unknownCodeKeepsTheCodeInDetail() {
        val error = map(599)
        assertIs<MediaException.Unknown>(error)
        assertTrue(error.detail!!.contains("599"))
    }
}
