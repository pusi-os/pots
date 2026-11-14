package com.pusios.pots.media

import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.HttpRequestBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unified §8 error pattern (spec §8): every failure maps to a Russian
 * title (the essence), one human sentence (the cause) and a category, for
 * every source — provider HTTP codes, network failures, editor exceptions,
 * project files and storage writes.
 */
class MediaErrorsTest {

    private fun cyrillic(text: String): Boolean = text.any { it in 'а'..'я' || it in 'А'..'Я' }

    @Test
    fun invalidKeyIsKeyProblem() {
        val info = mediaErrorInfo(MediaException.InvalidKey())
        assertEquals(MediaErrorCategory.Key, info.category)
        assertTrue(info.keyProblem)
        assertTrue(cyrillic(info.title))
    }

    @Test
    fun missingKeyHasSettingsHint() {
        val info = mediaMissingKeyError()
        assertEquals(MediaErrorCategory.Key, info.category)
        assertTrue(info.keyProblem)
        assertTrue(info.detail.contains("настройк", ignoreCase = true))
    }

    @Test
    fun insufficientBalanceIsLimits() {
        val info = mediaErrorInfo(MediaException.InsufficientBalance())
        assertEquals(MediaErrorCategory.Limits, info.category)
        assertEquals("Недостаточно средств", info.title)
        assertFalse(info.keyProblem)
    }

    @Test
    fun validationIsParameters() {
        val info = mediaErrorInfo(MediaException.BadRequest("quality unknown"))
        assertEquals(MediaErrorCategory.Parameters, info.category)
        assertEquals("Параметры не подходят", info.title)
        assertTrue(info.detail.contains("quality unknown"))
    }

    @Test
    fun validationWithoutDetailGetsHumanSentence() {
        val info = mediaErrorInfo(MediaException.BadRequest())
        assertEquals(MediaErrorCategory.Parameters, info.category)
        assertTrue(cyrillic(info.detail))
        assertTrue(info.detail.isNotBlank())
    }

    @Test
    fun modelUnavailableIsProvider() {
        val info = mediaErrorInfo(MediaException.Unavailable())
        assertEquals(MediaErrorCategory.Provider, info.category)
        assertEquals("Модель временно недоступна", info.title)
    }

    @Test
    fun rateLimitedIsLimits() {
        val info = mediaErrorInfo(MediaException.RateLimited(retryAfterSeconds = 30))
        assertEquals(MediaErrorCategory.Limits, info.category)
    }

    @Test
    fun upstreamFailureIsProvider() {
        val info = mediaErrorInfo(MediaException.UpstreamFailed())
        assertEquals(MediaErrorCategory.Provider, info.category)
    }

    @Test
    fun modelNotFoundIsProvider() {
        val info = mediaErrorInfo(MediaException.ModelNotFound())
        assertEquals(MediaErrorCategory.Provider, info.category)
    }

    @Test
    fun expiredResultIsFile() {
        val info = mediaErrorInfo(MediaException.Expired())
        assertEquals(MediaErrorCategory.File, info.category)
    }

    @Test
    fun timeoutIsNetwork() {
        val info = mediaErrorInfo(HttpRequestTimeoutException(HttpRequestBuilder()))
        assertEquals(MediaErrorCategory.Network, info.category)
        assertEquals("Сервис не ответил", info.title)
    }

    /** A wrapped connection failure anywhere in the cause chain reads as «нет связи». */
    private class ConnectException(message: String) : Exception(message)
    private class UnknownHostException(message: String) : Exception(message)

    @Test
    fun connectFailureIsNoConnection() {
        val wrapped = RuntimeException("request failed", ConnectException("Connection refused"))
        val info = mediaErrorInfo(wrapped)
        assertEquals(MediaErrorCategory.Network, info.category)
        assertEquals("Нет связи", info.title)
    }

    @Test
    fun dnsFailureIsNoConnection() {
        val info = mediaErrorInfo(UnknownHostException("plusvibeapi.ru"))
        assertEquals(MediaErrorCategory.Network, info.category)
    }

    @Test
    fun genericFailureGetsRussianFallback() {
        val info = mediaErrorInfo(IllegalStateException("boom"))
        assertEquals(MediaErrorCategory.Other, info.category)
        assertTrue(cyrillic(info.title))
        assertTrue(info.detail.contains("boom"))
    }

    @Test
    fun audioEditorExceptionsKeepTheirCards() {
        val decode = mediaErrorInfo(AudioEditException.DecodeFailed())
        assertEquals("Не удалось прочитать аудио", decode.title)
        assertEquals(MediaErrorCategory.File, decode.category)
        val export = mediaErrorInfo(AudioEditException.ExportFailed())
        assertEquals(MediaErrorCategory.Storage, export.category)
        val speed = mediaErrorInfo(AudioEditException.BadSpeed(9f))
        assertEquals(MediaErrorCategory.Parameters, speed.category)
    }

    @Test
    fun imageEditorExceptionsKeepTheirCards() {
        val format = mediaErrorInfo(ImageEditException.UnsupportedFormat("png, jpg"))
        assertEquals("Формат не поддерживается", format.title)
        assertEquals(MediaErrorCategory.File, format.category)
        val export = mediaErrorInfo(ImageEditException.ExportFailed())
        assertEquals(MediaErrorCategory.Storage, export.category)
    }

    @Test
    fun videoToolMissingIsToolCategory() {
        val info = mediaErrorInfo(VideoEditException.ToolMissing())
        assertEquals(MediaErrorCategory.Tool, info.category)
        assertTrue(info.title.contains("ffmpeg"))
        assertTrue(info.detail.contains("pacman"))
    }

    @Test
    fun videoProbeFailureIsFile() {
        val info = mediaErrorInfo(VideoEditException.ProbeFailed())
        assertEquals(MediaErrorCategory.File, info.category)
    }

    @Test
    fun projectImportErrorsAreFileCategory() {
        val corrupt = mediaErrorInfo(MediaProjectException.CorruptFile())
        assertEquals(MediaErrorCategory.File, corrupt.category)
        assertEquals("Файл повреждён", corrupt.title)
        val version = mediaErrorInfo(MediaProjectException.UnsupportedVersion(999))
        assertEquals(MediaErrorCategory.File, version.category)
        assertEquals("Формат не поддерживается", version.title)
    }

    @Test
    fun storageErrorMentionsFreeSpace() {
        val info = mediaStorageError()
        assertEquals(MediaErrorCategory.Storage, info.category)
        assertTrue(info.detail.contains("место"))
    }

    @Test
    fun everyProviderMappingHasRussianTitleAndDetail() {
        val causes = listOf<Throwable>(
            MediaException.BadRequest(),
            MediaException.InvalidKey(),
            MediaException.InsufficientBalance(),
            MediaException.ModelNotFound(),
            MediaException.RateLimited(),
            MediaException.UpstreamFailed(),
            MediaException.Unavailable(),
            MediaException.Expired(),
            MediaException.Unknown(),
        )
        causes.forEach { cause ->
            val info = mediaErrorInfo(cause)
            assertTrue(info.title.isNotBlank(), "blank title for $cause")
            assertTrue(info.detail.isNotBlank(), "blank detail for $cause")
            assertTrue(cyrillic(info.title), "non-Russian title for $cause: ${info.title}")
        }
    }
}
