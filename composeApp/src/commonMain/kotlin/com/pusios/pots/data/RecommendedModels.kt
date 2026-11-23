package com.pusios.pots.data

import com.pusios.pots.ui.settings.SettingsModel

/**
 * Curated "Recommended" picks per chat service: one everyday model (fast and cheap), one middle,
 * one hard (powerful and expensive), plus a spare everyday where the provider's catalog offers a
 * real choice. Ids and the price notes in [Pick.description] were taken from the providers' public
 * catalogs as probed on 2026-11-23 and re-verified live the same day while wording these
 * descriptions (researcher table B, board task t_cd33ff78; see
 * `docs/knowledge/providers/onboarding-*.md`). Polza's per-model prices drift intraday, so its
 * numbers are written as approximate. The sheet filters every pick through the model list
 * the selected service actually returned, so a stale or foreign id simply drops out instead of
 * showing someone else's model.
 */
object RecommendedModels {

    enum class ModelClass { EVERYDAY, MIDDLE, HARD }

    data class Pick(
        val modelClass: ModelClass,
        val id: String,
        /** One-line Russian description shown under the model name in the Recommended list. */
        val description: String,
    )

    // Display order: everyday first, then middle, hard, spare everyday. Kept grouped by class.
    private val table: Map<Service, List<Pick>> = mapOf(
        Service.PlusVibe to listOf(
            Pick(ModelClass.EVERYDAY, "z-ai/glm-5.3-flash", "Дефолтный вариант каталога: 2 / 6,67 ₽ за 1M, контекст 1M, tools+vision"),
            Pick(ModelClass.EVERYDAY, "deepseek-v4.1-flash-1017", "Быстрый вариант «на каждый день»: 2 / 8 ₽ за 1M, контекст 1M"),
            Pick(ModelClass.MIDDLE, "google/gemini-3.8-flash", "Рабочая лошадка: 4,72 / 23,58 ₽ за 1M, контекст 1M, tools+vision"),
            Pick(ModelClass.HARD, "gpt-6.2-astra", "Для сложных задач: 47 / 237 ₽ за 1M, контекст 1M, tools+vision"),
        ),
        Service.PlusVibeReserve to listOf(
            Pick(ModelClass.EVERYDAY, "z-ai/glm-5.3-flash", "Дефолтный вариант каталога: 2 / 6,67 ₽ за 1M, контекст 1M, tools+vision"),
            Pick(ModelClass.EVERYDAY, "deepseek-v4.1-flash-1017", "Быстрый вариант «на каждый день»: 2 / 8 ₽ за 1M, контекст 1M"),
            Pick(ModelClass.MIDDLE, "google/gemini-3.8-flash", "Рабочая лошадка: 4,72 / 23,58 ₽ за 1M, контекст 1M, tools+vision"),
            Pick(ModelClass.HARD, "gpt-6.2-astra", "Для сложных задач: 47 / 237 ₽ за 1M, контекст 1M, tools+vision"),
        ),
        Service.Polza to listOf(
            Pick(ModelClass.EVERYDAY, "z-ai/glm-5.3-flash", "≈4,7 / 8,7 ₽ за 1M, контекст 1M, tools+vision"),
            Pick(ModelClass.EVERYDAY, "deepseek/deepseek-v4-flash", "Одна из самых дешёвых чат-моделей каталога: 1,71 / 3,41 ₽ за 1M"),
            Pick(ModelClass.MIDDLE, "google/gemini-3.8-flash", "44,87 / 224,33 ₽ за 1M, контекст 1M, tools+vision"),
            Pick(ModelClass.HARD, "openai/gpt-6.2-astra", "Для сложных задач: 937 / 4 688 ₽ за 1M — дорогая ставка Polza"),
        ),
        Service.AiTunnel to listOf(
            Pick(ModelClass.EVERYDAY, "glm-5.3-flash", "7,98 / 15,68 ₽ за 1M, контекст 1M, vision"),
            Pick(ModelClass.MIDDLE, "gemini-3.8-flash", "165 / 825 ₽ за 1M, контекст 1M, vision"),
            Pick(ModelClass.HARD, "gpt-6.2-astra", "Усиленный reasoning для сложных задач: 1 600 / 8 000 ₽ за 1M"),
        ),
        Service.Gptunnel to listOf(
            Pick(ModelClass.EVERYDAY, "gpt-6-luna", "Дешёвый уровень GPT-6: ≈40 / 200 ₽ за 1M, контекст 1M"),
            Pick(ModelClass.MIDDLE, "gemini-3.6-flash", "Средний класс в каталоге GPTunnel"),
            Pick(ModelClass.HARD, "gpt-6.2-astra", "Верхняя ступень GPT-6: 2 / 10 ₽ за 1K токенов"),
        ),
        Service.LlmRouter to listOf(
            Pick(ModelClass.EVERYDAY, "glm-5.3-flash", "Дешевле всех в прайсе: $0,02 / $0,039 за 1M"),
            Pick(ModelClass.MIDDLE, "gemini-3.8-flash", "Средний класс по цене и скорости: $0,375 / $1,875 за 1M"),
            Pick(ModelClass.HARD, "gpt-6.2-astra", "Для сложных задач: $4 / $20 за 1M"),
        ),
    )

    /** Curated picks for [service], in display order; empty when the service has no table entry. */
    fun picksFor(service: Service): List<Pick> = table[service] ?: emptyList()

    /**
     * Id of the everyday pick — the automatic chat default a service lands on the first time it
     * gains a key and has no saved model choice. Media model selection is untouched.
     */
    fun everydayModelId(service: Service): String? = picksFor(service).firstOrNull { it.modelClass == ModelClass.EVERYDAY }?.id

    /**
     * The Recommended list for the sheet: only models the service's own catalog returned, ordered
     * everyday → middle → hard, the current choice kept reachable, capped at [limit] entries.
     * A pick whose id is absent from [models] (stale catalog entry) is skipped — no holes.
     */
    fun selectRecommended(
        models: List<SettingsModel>,
        service: Service?,
        selectedId: String?,
        limit: Int = 5,
    ): List<SettingsModel> {
        val picks = service?.let { picksFor(it) } ?: emptyList()
        if (picks.isEmpty()) return models
        val order = picks.withIndex().associate { it.value.id to it.index }
        val selected = models.filter { it.id == selectedId && it.id !in order }
        val picked = models.filter { it.id in order }.sortedBy { order.getValue(it.id) }
        return (selected + picked).take(limit)
    }

    /** The [Pick] backing a recommended row, or null for a model outside the table. */
    fun pickFor(service: Service?, modelId: String): Pick? = service?.let { picksFor(it).firstOrNull { pick -> pick.id == modelId } }
}
