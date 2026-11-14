package com.pusios.pots.media

import io.ktor.client.plugins.HttpRequestTimeoutException

/*
 * The unified §8 error pattern (spec §8): every failure in the media studio
 * surfaces as an essence ([MediaErrorInfo.title]), one human sentence of
 * cause ([MediaErrorInfo.detail]) and a [MediaErrorCategory], in Russian.
 * The studio snackbar, the journal fail text and the editor cards all read
 * from this one model — there is no second error mechanism.
 */

/** The cause component of a §8 error. */
enum class MediaErrorCategory {
    Network,
    Provider,
    Limits,
    Key,
    Parameters,
    File,
    Tool,
    Storage,
    Other,
}

/** One user-facing failure: what happened, why, and which bucket it is. */
data class MediaErrorInfo(
    val title: String,
    val detail: String,
    val category: MediaErrorCategory,
    /** Offers the settings shortcut, the same as the old snackbar flag. */
    val keyProblem: Boolean = false,
)

/**
 * Maps any failure to the §8 shape: provider HTTP errors by their typed
 * exception, timeouts and connection failures to the network rows, editor
 * and project exceptions keep their own cards. Input is never cleared by an
 * error — that guarantee lives with the callers (spec §8).
 */
fun mediaErrorInfo(cause: Throwable): MediaErrorInfo = when (cause) {
    is MediaException -> providerErrorInfo(cause)

    is AudioEditException -> MediaErrorInfo(cause.title, cause.detail, audioEditCategory(cause))

    is ImageEditException -> MediaErrorInfo(cause.title, cause.detail, imageEditCategory(cause))

    is VideoEditException -> MediaErrorInfo(cause.title, cause.detail, videoEditCategory(cause))

    is MediaProjectException -> MediaErrorInfo(cause.title, cause.detail, MediaErrorCategory.File)

    is HttpRequestTimeoutException -> MediaErrorInfo(
        "Сервис не ответил",
        "Сервер не ответил вовремя. Обычно помогает повтор через минуту.",
        MediaErrorCategory.Network,
    )

    else -> if (isNetworkFailure(cause)) {
        MediaErrorInfo(
            "Нет связи",
            "Не удалось связаться с сервером генерации. Проверьте интернет и повторите.",
            MediaErrorCategory.Network,
        )
    } else {
        MediaErrorInfo(
            "Что-то пошло не так",
            cause.message?.takeIf { it.isNotBlank() } ?: "Неизвестная ошибка. Попробуйте ещё раз.",
            MediaErrorCategory.Other,
        )
    }
}

/** The missing-key case is detected before any request leaves the device. */
fun mediaMissingKeyError(): MediaErrorInfo = MediaErrorInfo(
    "Нет API-ключа",
    "Добавьте ключ сервиса в настройках сервисов — тогда генерации станут доступны.",
    MediaErrorCategory.Key,
    keyProblem = true,
)

/** Storage writes that fail on disk (autosave, edits, exports) — usually space. */
fun mediaStorageError(): MediaErrorInfo = MediaErrorInfo(
    "Не удалось сохранить файл",
    "Файл не сохранился. Проверьте свободное место и повторите.",
    MediaErrorCategory.Storage,
)

private fun providerErrorInfo(error: MediaException): MediaErrorInfo = when (error) {
    is MediaException.InvalidKey -> MediaErrorInfo(
        "Ключ не подходит",
        "API-ключ сервиса не найден или не подходит. Добавьте его в настройках сервисов.",
        MediaErrorCategory.Key,
        keyProblem = true,
    )

    is MediaException.InsufficientBalance -> MediaErrorInfo(
        "Недостаточно средств",
        "Для этой генерации не хватает средств на балансе сервиса. Пополните баланс или выберите модель дешевле.",
        MediaErrorCategory.Limits,
    )

    is MediaException.BadRequest -> MediaErrorInfo(
        "Параметры не подходят",
        error.detail?.takeIf { it.isNotBlank() } ?: "Выбранное сочетание параметров недоступно для этой модели.",
        MediaErrorCategory.Parameters,
    )

    is MediaException.ModelNotFound -> MediaErrorInfo(
        "Модель не найдена",
        error.detail?.takeIf { it.isNotBlank() } ?: "Сервер не знает такую модель. Обновите каталог или выберите другую.",
        MediaErrorCategory.Provider,
    )

    is MediaException.RateLimited -> MediaErrorInfo(
        "Слишком много запросов",
        "Сервер получил слишком много запросов. Подождите минуту и повторите.",
        MediaErrorCategory.Limits,
    )

    is MediaException.UpstreamFailed -> MediaErrorInfo(
        "Провайдер не справился",
        "Вышестоящий сервис провайдера ответил ошибкой. Попробуйте другую модель.",
        MediaErrorCategory.Provider,
    )

    is MediaException.Unavailable -> MediaErrorInfo(
        "Модель временно недоступна",
        "Сервис вернул 503: модель недоступна. Попробуйте позже или выберите другую модель.",
        MediaErrorCategory.Provider,
    )

    is MediaException.Expired -> MediaErrorInfo(
        "Результат истёк",
        "Результат генерации хранится 5 дней и больше недоступен. Запустите генерацию заново.",
        MediaErrorCategory.File,
    )

    is MediaException.Unknown -> MediaErrorInfo(
        "Ошибка генерации",
        error.detail?.takeIf { it.isNotBlank() } ?: "Неизвестная ошибка. Попробуйте ещё раз.",
        MediaErrorCategory.Other,
    )
}

private fun audioEditCategory(error: AudioEditException): MediaErrorCategory = when (error) {
    is AudioEditException.DecodeFailed, is AudioEditException.UnsupportedFormat -> MediaErrorCategory.File
    is AudioEditException.ExportFailed -> MediaErrorCategory.Storage
    is AudioEditException.EmptyRange, is AudioEditException.BadSpeed -> MediaErrorCategory.Parameters
    is AudioEditException.Cancelled -> MediaErrorCategory.Other
}

private fun imageEditCategory(error: ImageEditException): MediaErrorCategory = when (error) {
    is ImageEditException.DecodeFailed, is ImageEditException.UnsupportedFormat -> MediaErrorCategory.File

    is ImageEditException.ExportFailed -> MediaErrorCategory.Storage

    is ImageEditException.EmptyCrop,
    is ImageEditException.EmptySketch,
    is ImageEditException.BadSize,
    is ImageEditException.BadAdjust,
    is ImageEditException.BadOverlay,
    -> MediaErrorCategory.Parameters

    is ImageEditException.Cancelled -> MediaErrorCategory.Other
}

private fun videoEditCategory(error: VideoEditException): MediaErrorCategory = when (error) {
    is VideoEditException.ToolMissing -> MediaErrorCategory.Tool
    is VideoEditException.ProbeFailed, is VideoEditException.SourceMissing, is VideoEditException.TrackUnreadable -> MediaErrorCategory.File
    is VideoEditException.ExportFailed -> MediaErrorCategory.Storage
    is VideoEditException.EmptyRange, is VideoEditException.EmptySequence, is VideoEditException.BadSpeed -> MediaErrorCategory.Parameters
    is VideoEditException.Cancelled, is VideoEditException.UnsupportedPlatform -> MediaErrorCategory.Other
}

/** Connection failure class names across the ktor engines, matched over the whole cause chain. */
private val NETWORK_EXCEPTION_NAMES = setOf(
    "ConnectException",
    "ConnectTimeoutException",
    "UnknownHostException",
    "UnresolvedAddressException",
    "NoRouteToHostException",
    "SocketTimeoutException",
    "SocketException",
)

private val NETWORK_MESSAGE_MARKERS = listOf(
    "failed to connect",
    "connection refused",
    "network is unreachable",
    "no address associated",
    "unable to resolve",
    "unresolved address",
)

/** True when any cause in the chain looks like a connectivity failure. */
fun isNetworkFailure(cause: Throwable): Boolean {
    var current: Throwable? = cause
    var depth = 0
    while (current != null && depth < 8) {
        val name = current::class.simpleName.orEmpty()
        if (name in NETWORK_EXCEPTION_NAMES) return true
        val message = current.message?.lowercase().orEmpty()
        if (NETWORK_MESSAGE_MARKERS.any { it in message }) return true
        current = current.cause
        depth++
    }
    return false
}
