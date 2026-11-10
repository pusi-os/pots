package com.pusios.pots.media

/**
 * Cloud media provider contract. Every method takes the provider `apiKey`
 * explicitly so any chat-service instance key can be reused.
 *
 * Implementations must:
 *  - tolerate unknown JSON fields (never fail a whole catalog on one model),
 *  - treat paid submission as idempotent for 24 h via [MediaJobRequest.idempotencyKey],
 *  - poll politely (catalog docs recommend 3–5 s → 30 s backoff).
 */
interface MediaProvider {
    /** Catalog of image/video models with prices; public for most providers (no key needed). */
    suspend fun listModels(apiKey: String): List<MediaModel>

    /** Optional pre-submit quote; providers without it return a local estimate. */
    suspend fun quotePrice(apiKey: String, request: MediaJobRequest): MediaPriceQuote

    /** Submit a job; returns immediately with a non-terminal state carrying `jobId`. */
    suspend fun submitJob(apiKey: String, request: MediaJobRequest): MediaJobState

    /** Poll one job; `submitting` is non-terminal and must be re-polled, never re-submitted. */
    suspend fun pollJob(apiKey: String, jobId: String): MediaJobState

    /** Download result bytes for a signed/file URL (no key needed for signed URLs). */
    suspend fun downloadResult(apiKey: String, url: String): ByteArray
}
