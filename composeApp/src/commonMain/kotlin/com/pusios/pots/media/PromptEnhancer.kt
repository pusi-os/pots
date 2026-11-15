package com.pusios.pots.media

/** Content type the prompt enhancement is tailored for (studio toolbar modes + the audio flow). */
enum class PromptTarget { Image, Video, Music, Tts }

/**
 * Rewrites a short user prompt into a detailed generation prompt via the
 * active chat model. Media Studio exposes this as the “✨” enhance action;
 * the template depends on the selected content type ([PromptTarget]) so a
 * music or voice-over prompt is never enhanced as an image.
 */
object PromptEnhancer {

    private const val IMAGE_TEMPLATE =
        "Перепиши короткий запрос пользователя в детальный промпт для нейросетевой генерации изображения. " +
            "Добавь стиль, освещение, композицию, детали, но не выдумывай отсутствующие объекты. " +
            "Отвечай только переписанным промптом, без пояснений, на языке исходного запроса."

    private const val VIDEO_TEMPLATE =
        "Перепиши короткий запрос пользователя в детальный промпт для нейросетевой генерации видео. " +
            "Опиши движение и динамику сцены, работу камеры, освещение и стиль, но не выдумывай отсутствующие объекты. " +
            "Отвечай только переписанным промптом, без пояснений, на языке исходного запроса."

    private const val MUSIC_TEMPLATE =
        "Перепиши короткий запрос пользователя в детальный промпт для нейросетевой генерации музыки или звукового эффекта. " +
            "Опиши жанр, настроение, темп, инструменты и структуру трека, но не выдумывай лишнего. " +
            "Отвечай только переписанным промптом, без пояснений, на языке исходного запроса."

    private const val TTS_TEMPLATE =
        "Оптимизируй текст пользователя для озвучивания синтезом речи: сделай формулировки естественными для живого чтения, " +
            "расставь пунктуацию для интонации и пауз, разбей слишком длинные предложения. " +
            "Удали сценические ремарки и инструкции чтецу (например, слово «запятая», «пауза», пометы эмоций в скобках) — " +
            "оставь только текст, который должен прозвучать. " +
            "Отвечай только готовым текстом, без пояснений, на языке исходного текста."

    private fun template(target: PromptTarget): String = when (target) {
        PromptTarget.Image -> IMAGE_TEMPLATE
        PromptTarget.Video -> VIDEO_TEMPLATE
        PromptTarget.Music -> MUSIC_TEMPLATE
        PromptTarget.Tts -> TTS_TEMPLATE
    }

    fun buildPrompt(raw: String, target: PromptTarget = PromptTarget.Image): String {
        val tail = if (target == PromptTarget.Tts) "Готовый текст:" else "Детальный промпт:"
        return "${template(target)}\n\nЗапрос: $raw\n\n$tail"
    }

    fun clean(raw: String): String = raw.trim().trim('"', '«', '»').trim().take(4000)
}
