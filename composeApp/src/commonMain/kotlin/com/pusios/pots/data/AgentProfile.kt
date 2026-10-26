package com.pusios.pots.data

import androidx.compose.runtime.Immutable
import com.pusios.pots.ui.potsik.PotsikLook
import kotlinx.serialization.Serializable

/** Profile-owned agent identity and choices. Credentials, provider/model choices and user data stay global. */
@Serializable
@Immutable
data class AgentProfile(
    val id: String,
    val name: String,
    val personaId: String = AgentPersonaPresets.ASSISTANT,
    val soulText: String = "",
    val toolOverrides: Map<String, Boolean> = emptyMap(),
    val localToolSelection: LocalToolSelection = LocalToolSelection(),
    /** Null preserves the legacy enabled state until the user configures MCPs for this profile. */
    val enabledMcpServerIds: List<String>? = null,
    val enabledSkillIds: List<String> = emptyList(),
    val skillSelectionConfigured: Boolean = false,
    val potsikLook: PotsikLook = PotsikLook.Default,
)
