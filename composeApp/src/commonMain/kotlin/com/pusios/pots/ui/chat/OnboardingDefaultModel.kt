package com.pusios.pots.ui.chat

import com.pusios.pots.data.RecommendedModels
import com.pusios.pots.data.Service
import com.pusios.pots.ui.settings.SettingsModel

/**
 * Picks the model the onboarding lands on right after a key validates.
 *
 * The owner's default is the DeepSeek V4.1 Flash snapshot — `deepseek-v4.1-flash-1017` — but the
 * providers spell it differently and two of them do not carry the snapshot at all (researcher
 * report t_b1dce70b §6.0, verified against the live catalogs on 2026-11-24):
 * PlusVibe/AITUNNEL use the bare snapshot id, Polza prefixes it with `deepseek/`, GPTunnel only
 * has the base `deepseek-v4.1-flash`, and LLM Router only has the older `deepseek-v4-flash`.
 *
 * When the preferred id is missing from the provider's actual catalog, the fallback is the
 * nearest curated everyday pick ([ModelClass.EVERYDAY] from [RecommendedModels]) that the
 * catalog really returned — a stale pick id simply drops out and the next everyday pick wins,
 * so the wizard never selects a model the provider would reject.
 */
internal object OnboardingDefaultModel {

    fun pick(catalog: List<SettingsModel>, service: Service): String? {
        val preferred = when (service) {
            Service.PlusVibe, Service.PlusVibeReserve, Service.AiTunnel -> "deepseek-v4.1-flash-1017"
            Service.Polza -> "deepseek/deepseek-v4.1-flash-1017"
            Service.Gptunnel -> "deepseek-v4.1-flash"
            Service.LlmRouter -> "deepseek-v4-flash"
            else -> null
        }
        if (preferred != null && catalog.any { it.id == preferred }) return preferred
        return RecommendedModels.picksFor(service)
            .firstOrNull { pick -> pick.modelClass == RecommendedModels.ModelClass.EVERYDAY && catalog.any { it.id == pick.id } }
            ?.id
    }
}
