package com.pusios.pots.media

/**
 * Minimal media-layer logger. Release builds previously had zero visibility
 * into generation failures; every catalog load, quote, submit, poll and
 * error now goes through here. Priorities match `android.util.Log`
 * (3 debug, 4 info, 5 warn, 6 error).
 */
expect fun platformMediaLog(priority: Int, tag: String, message: String)

object MediaLog {
    const val TAG = "PotsMedia"

    fun d(message: String, tag: String = TAG) {
        platformMediaLog(3, tag, message)
    }

    fun i(message: String, tag: String = TAG) {
        platformMediaLog(4, tag, message)
    }

    fun w(message: String, tag: String = TAG) {
        platformMediaLog(5, tag, message)
    }

    fun e(message: String, throwable: Throwable? = null, tag: String = TAG) {
        val full = if (throwable != null) "$message\n${throwable.stackTraceToString()}" else message
        platformMediaLog(6, tag, full)
    }
}

/** Russian user-facing text for typed media errors (shown in the studio snackbar). */
fun MediaException.userMessageRu(): String = when (this) {
    is MediaException.BadRequest -> detail?.let { "Неверный запрос: $it" } ?: "Неверный запрос к провайдеру"
    is MediaException.InvalidKey -> "API-ключ сервиса не найден или не подходит"
    is MediaException.InsufficientBalance -> "Недостаточно баланса для генерации"
    is MediaException.ModelNotFound -> detail?.let { "Модель не найдена: $it" } ?: "Модель не найдена"
    is MediaException.RateLimited -> "Слишком много запросов — попробуй чуть позже"
    is MediaException.UpstreamFailed -> "Провайдер не справился с задачей — попробуй другую модель"
    is MediaException.Unavailable -> "Модель временно недоступна"
    is MediaException.Expired -> "Результат генерации истёк (хранится 5 дней)"
    is MediaException.Unknown -> detail?.let { "Ошибка генерации: $it" } ?: "Неизвестная ошибка генерации"
}
