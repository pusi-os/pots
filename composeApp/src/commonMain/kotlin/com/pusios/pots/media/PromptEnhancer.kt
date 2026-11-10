package com.pusios.pots.media

/**
 * Rewrites a short user prompt into a detailed image/video generation prompt
 * via the active chat model. Media Studio exposes this as the “✨” enhance
 * action; the agent `enhance_prompt` tool uses the same template.
 */
object PromptEnhancer {

    const val SYSTEM_PROMPT: String =
        "Перепиши короткий запрос пользователя в детальный промпт для нейросетевой генерации изображения. " +
            "Добавь стиль, освещение, композицию, детали, но не выдумывай отсутствующие объекты. " +
            "Отвечай только переписанным промптом, без пояснений, на языке исходного запроса."

    fun buildPrompt(raw: String): String = "$SYSTEM_PROMPT\n\nЗапрос: $raw\n\nДетальный промпт:"

    fun clean(raw: String): String = raw.trim().trim('"', '«', '»').trim().take(4000)
}
