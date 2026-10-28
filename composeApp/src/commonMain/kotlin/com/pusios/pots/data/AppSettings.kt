package com.pusios.pots.data

import com.pusios.pots.defaultUiScale
import com.pusios.pots.deleteAppStorageFolder
import com.pusios.pots.linux.LinuxDistro
import com.pusios.pots.mcp.McpServerConfig
import com.pusios.pots.ui.potsik.PotsikLook
import com.pusios.pots.ui.potsik.PotsikPlacementMode
import com.pusios.pots.writeAppStorageFile
import com.russhwolf.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class ImportSection {
    SERVICES,
    SOUL,
    MEMORY,
    SCHEDULING,
    HEARTBEAT,
    EMAIL,
    SMS,
    SPLINTERLANDS,
    TOOLS,
    MCP,
    CONVERSATIONS,
}

enum class ThemeMode {
    System,
    Light,
    Dark,
    OledBlack,
}

enum class AppLanguage {
    System,
    Russian,
    English,
}

/**
 * Which speech engine reads answers aloud.
 *
 * NATIVE is the platform engine (Android system TTS — the default, as before);
 * PIPER is the bundled offline engine (downloaded models, works on desktop and
 * mobile); CLOUD is a future server-side voice via an OpenAI-compatible
 * inference endpoint (placeholder for now).
 */
enum class TtsEngine {
    Native,
    Piper,
    Cloud,
}

/**
 * Stricter than [detectImportSections]: only includes sections that contain actual user data,
 * skipping ones that exist purely because of default feature-toggle flags (e.g. `sms_enabled = false`,
 * `splinterlands_enabled = false`, `mcp_servers = []`). Used to drive the Export preview dialog.
 */
fun detectExportableSections(json: JsonObject): Map<ImportSection, String?> {
    val sections = mutableMapOf<ImportSection, String?>()

    val configured = json["configured_services"]?.jsonArray
    if (configured != null && configured.isNotEmpty()) {
        sections[ImportSection.SERVICES] = "${configured.size}"
    }

    if (json["soul_text"] != null) {
        sections[ImportSection.SOUL] = null
    }

    val memories = json["agent_memories"]?.jsonArray
    if (memories != null && memories.isNotEmpty()) {
        sections[ImportSection.MEMORY] = "${memories.size}"
    }

    val tasks = json["scheduled_tasks"]?.jsonArray
    if (tasks != null && tasks.isNotEmpty()) {
        sections[ImportSection.SCHEDULING] = "${tasks.size}"
    }

    val heartbeatHasPrompt = json["heartbeat_prompt"] != null
    val heartbeatHasConfig = json["heartbeat_config"] != null
    val heartbeatHasLog = json["heartbeat_log"]?.jsonArray?.isNotEmpty() == true
    if (heartbeatHasPrompt || heartbeatHasConfig || heartbeatHasLog) {
        sections[ImportSection.HEARTBEAT] = null
    }

    val emails = json["email_accounts"]?.jsonArray
    if (emails != null && emails.isNotEmpty()) {
        sections[ImportSection.EMAIL] = "${emails.size}"
    }

    val smsEnabled = json["sms_enabled"]?.jsonPrimitive?.content?.toBoolean() == true
    val smsSendEnabled = json["sms_send_enabled"]?.jsonPrimitive?.content?.toBoolean() == true
    if (smsEnabled || smsSendEnabled) {
        sections[ImportSection.SMS] = null
    }

    if (json["splinterlands_account"] != null) {
        sections[ImportSection.SPLINTERLANDS] = null
    }

    val toolOverrides = json["tool_overrides"]?.jsonObject
    if (toolOverrides != null && toolOverrides.isNotEmpty()) {
        val enabled = toolOverrides.count { (_, v) ->
            try {
                v.jsonPrimitive.content.toBoolean()
            } catch (_: Exception) {
                false
            }
        }
        sections[ImportSection.TOOLS] = "$enabled"
    }

    val mcp = json["mcp_servers"]?.jsonArray
    if (mcp != null && mcp.isNotEmpty()) {
        sections[ImportSection.MCP] = "${mcp.size}"
    }

    val conversations = json["conversations"]?.jsonArray
    if (conversations != null && conversations.isNotEmpty()) {
        sections[ImportSection.CONVERSATIONS] = "${conversations.size}"
    }

    return sections
}

fun detectImportSections(json: JsonObject): Map<ImportSection, String?> {
    val sections = mutableMapOf<ImportSection, String?>()
    if (json["configured_services"] != null || json["current_service_id"] != null || json["free_fallback_enabled"] != null || json["instance_settings"] != null) {
        val count = json["configured_services"]?.jsonArray?.size
        sections[ImportSection.SERVICES] = count?.let { "$it" }
    }
    if (json["soul_text"] != null) {
        sections[ImportSection.SOUL] = null
    }
    if (json["memory_enabled"] != null || json["agent_memories"] != null) {
        val count = json["agent_memories"]?.jsonArray?.size
        sections[ImportSection.MEMORY] = count?.let { "$it" }
    }
    if (json["scheduling_enabled"] != null || json["scheduled_tasks"] != null) {
        val count = json["scheduled_tasks"]?.jsonArray?.size
        sections[ImportSection.SCHEDULING] = count?.let { "$it" }
    }
    if (json["heartbeat_config"] != null || json["heartbeat_prompt"] != null || json["heartbeat_log"] != null) {
        sections[ImportSection.HEARTBEAT] = null
    }
    if (json["email_enabled"] != null || json["email_accounts"] != null) {
        val count = json["email_accounts"]?.jsonArray?.size
        sections[ImportSection.EMAIL] = count?.let { "$it" }
    }
    if (json["sms_enabled"] != null || json["sms_poll_interval"] != null || json["sms_send_enabled"] != null) {
        sections[ImportSection.SMS] = null
    }
    if (json["splinterlands_enabled"] != null || json["splinterlands_account"] != null) {
        sections[ImportSection.SPLINTERLANDS] = null
    }
    if (json["tool_overrides"] != null) {
        val enabled = json["tool_overrides"]?.jsonObject?.count { (_, v) ->
            try {
                v.jsonPrimitive.content.toBoolean()
            } catch (_: Exception) {
                false
            }
        }
        sections[ImportSection.TOOLS] = enabled?.let { "$it" }
    }
    if (json["mcp_servers"] != null) {
        val count = json["mcp_servers"]?.jsonArray?.size
        sections[ImportSection.MCP] = count?.let { "$it" }
    }
    if (json["conversations"] != null) {
        val count = try {
            json["conversations"]?.jsonArray?.size
        } catch (_: Exception) {
            null
        }
        sections[ImportSection.CONVERSATIONS] = count?.let { "$it" }
    }
    return sections
}

data class ServiceInstance(
    val instanceId: String,
    val serviceId: String,
)

class AppSettings(internal val settings: Settings) {

    private val profileJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
    private val agentProfilesSerializer = ListSerializer(AgentProfile.serializer())
    private val profileFileWriteScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pendingProfileFileWrite: Job? = null
    private val _agentProfilesFlow = MutableStateFlow(loadInitialAgentProfiles())
    val agentProfilesFlow: StateFlow<List<AgentProfile>> = _agentProfilesFlow
    private val initialActiveProfileId = settings.getString(KEY_ACTIVE_AGENT_PROFILE_ID, DEFAULT_AGENT_PROFILE_ID)
        .takeIf { id -> _agentProfilesFlow.value.any { it.id == id } }
        ?: _agentProfilesFlow.value.first().id
    private val _activeAgentProfileIdFlow = MutableStateFlow(initialActiveProfileId)
    val activeAgentProfileIdFlow: StateFlow<String> = _activeAgentProfileIdFlow

    init {
        settings.putString(KEY_ACTIVE_AGENT_PROFILE_ID, initialActiveProfileId)
        scheduleAgentProfileFilesWrite(_agentProfilesFlow.value)
    }

    fun getAgentProfiles(): List<AgentProfile> = _agentProfilesFlow.value

    fun getActiveAgentProfileId(): String = _activeAgentProfileIdFlow.value

    fun getActiveAgentProfile(): AgentProfile = _agentProfilesFlow.value.firstOrNull { it.id == _activeAgentProfileIdFlow.value }
        ?: _agentProfilesFlow.value.first()

    fun createAgentProfile(name: String): AgentProfile? {
        val cleanName = name.trim().replace(Regex("[\\r\\n\\t]+"), " ").take(60)
        if (cleanName.isBlank()) return null
        val base = cleanName.lowercase().replace(Regex("[^a-z0-9а-яё]+"), "_").trim('_').ifBlank { "pots" }
        var counter = settings.getLong(KEY_PROFILE_ID_COUNTER, 0L)
        var id: String
        do {
            counter++
            id = "${base.take(36)}_$counter"
        } while (_agentProfilesFlow.value.any { it.id == id })
        settings.putLong(KEY_PROFILE_ID_COUNTER, counter)
        val source = getActiveAgentProfile()
        val profile = source.copy(
            id = id,
            name = cleanName,
            enabledMcpServerIds = source.enabledMcpServerIds ?: readLegacyEnabledMcpIds(),
        )
        saveAgentProfiles(_agentProfilesFlow.value + profile)
        return profile
    }

    fun renameAgentProfile(id: String, name: String): Boolean {
        val cleanName = name.trim().replace(Regex("[\\r\\n\\t]+"), " ").take(60)
        if (cleanName.isBlank()) return false
        val current = _agentProfilesFlow.value
        if (current.none { it.id == id }) return false
        saveAgentProfiles(current.map { if (it.id == id) it.copy(name = cleanName) else it })
        return true
    }

    fun activateAgentProfile(id: String): Boolean {
        if (_agentProfilesFlow.value.none { it.id == id }) return false
        if (_activeAgentProfileIdFlow.value == id) return true
        settings.putString(KEY_ACTIVE_AGENT_PROFILE_ID, id)
        _activeAgentProfileIdFlow.value = id
        _potsikLookFlow.value = getActiveAgentProfile().potsikLook
        return true
    }

    fun deleteAgentProfile(id: String): Boolean {
        val current = _agentProfilesFlow.value
        if (id == DEFAULT_AGENT_PROFILE_ID) return false
        if (current.size <= 1 || current.none { it.id == id }) return false
        val remaining = current.filterNot { it.id == id }
        if (_activeAgentProfileIdFlow.value == id) {
            val fallback = remaining.first()
            settings.putString(KEY_ACTIVE_AGENT_PROFILE_ID, fallback.id)
            _activeAgentProfileIdFlow.value = fallback.id
            _potsikLookFlow.value = fallback.potsikLook
        }
        saveAgentProfiles(remaining)
        deleteAgentProfileFiles(id)
        return true
    }

    fun setProfileEnabledMcpServerIds(ids: Set<String>) {
        updateActiveAgentProfile { copy(enabledMcpServerIds = ids.toList().sorted()) }
    }

    fun getProfileEnabledMcpServerIds(): Set<String>? = getActiveAgentProfile().enabledMcpServerIds?.toSet()

    fun removeMcpServerFromAgentProfiles(serverId: String) {
        saveAgentProfiles(
            _agentProfilesFlow.value.map { profile ->
                val enabled = profile.enabledMcpServerIds ?: return@map profile
                profile.copy(enabledMcpServerIds = enabled.filterNot { it == serverId })
            },
        )
    }

    fun setActiveProfileSkillIds(ids: Set<String>) {
        updateActiveAgentProfile { copy(enabledSkillIds = ids.toList().sorted(), skillSelectionConfigured = true) }
    }

    fun setAgentProfileSkillIds(id: String, ids: Set<String>) {
        saveAgentProfiles(
            _agentProfilesFlow.value.map { profile ->
                if (profile.id == id) {
                    profile.copy(enabledSkillIds = ids.toList().sorted(), skillSelectionConfigured = true)
                } else {
                    profile
                }
            },
        )
    }

    fun isActiveProfileSkillEnabled(skillId: String): Boolean {
        val profile = getActiveAgentProfile()
        return !profile.skillSelectionConfigured || skillId in profile.enabledSkillIds
    }

    private fun updateActiveAgentProfile(transform: AgentProfile.() -> AgentProfile) {
        val id = _activeAgentProfileIdFlow.value
        saveAgentProfiles(_agentProfilesFlow.value.map { profile -> if (profile.id == id) profile.transform() else profile })
    }

    private fun saveAgentProfiles(profiles: List<AgentProfile>) {
        val normalized = profiles.ifEmpty { listOf(defaultAgentProfile()) }
        settings.putString(KEY_AGENT_PROFILES, profileJson.encodeToString(agentProfilesSerializer, normalized))
        _agentProfilesFlow.value = normalized
        scheduleAgentProfileFilesWrite(normalized)
    }

    private fun loadInitialAgentProfiles(): List<AgentProfile> {
        val stored = settings.getStringOrNull(KEY_AGENT_PROFILES)
        if (!stored.isNullOrBlank()) {
            val decoded = try {
                profileJson.decodeFromString(agentProfilesSerializer, stored)
            } catch (e: Exception) {
                backupCorruptJson("agentProfiles", stored)
                emptyList()
            }
            if (decoded.isNotEmpty()) return decoded
        }
        val legacyToolOverrides = settings.keys
            .filter { it.startsWith(KEY_TOOL_PREFIX) }
            .associate { key -> key.removePrefix(KEY_TOOL_PREFIX) to settings.getBoolean(key, true) }
        val legacySelection = try {
            val raw = settings.getString(KEY_LOCAL_TOOL_SELECTION, "")
            if (raw.isBlank()) LocalToolSelection() else profileJson.decodeFromString(LocalToolSelection.serializer(), raw)
        } catch (_: Exception) {
            LocalToolSelection()
        }
        return listOf(
            AgentProfile(
                id = DEFAULT_AGENT_PROFILE_ID,
                name = "Потсик",
                personaId = settings.getString(KEY_SOUL_PRESET, AgentPersonaPresets.ASSISTANT),
                soulText = settings.getString(KEY_SOUL, ""),
                toolOverrides = legacyToolOverrides,
                localToolSelection = legacySelection,
                potsikLook = loadInitialPotsikLook(),
            ),
        )
    }

    private fun defaultAgentProfile(): AgentProfile = AgentProfile(
        id = DEFAULT_AGENT_PROFILE_ID,
        name = "Потсик",
        potsikLook = PotsikLook.Default,
    )

    private fun writeAgentProfileFiles(profiles: List<AgentProfile>) {
        for (profile in profiles) {
            val safeId = profile.id.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val relative = "agent_profiles/$safeId"
            writeAppStorageFile("$relative/profile.json", profileJson.encodeToString(AgentProfile.serializer(), profile))
            writeAppStorageFile("$relative/profile.md", profileMarkdown(profile))
        }
    }

    private fun scheduleAgentProfileFilesWrite(profiles: List<AgentProfile>) {
        pendingProfileFileWrite?.cancel()
        pendingProfileFileWrite = profileFileWriteScope.launch {
            delay(200L)
            writeAgentProfileFiles(profiles)
        }
    }

    private fun readLegacyEnabledMcpIds(): List<String> = try {
        val serializer = ListSerializer(McpServerConfig.serializer())
        profileJson.decodeFromString(serializer, getMcpServersJson())
            .filter { it.isEnabled }
            .map { it.id }
    } catch (_: Exception) {
        emptyList()
    }

    private fun deleteAgentProfileFiles(id: String) {
        val safeId = id.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        pendingProfileFileWrite?.cancel()
        pendingProfileFileWrite = profileFileWriteScope.launch {
            delay(200L)
            deleteAppStorageFolder("agent_profiles/$safeId")
            writeAgentProfileFiles(_agentProfilesFlow.value)
        }
    }

    private fun profileMarkdown(profile: AgentProfile): String = buildString {
        append("# ").append(profile.name).append("\n\n")
        append("- ID: `").append(profile.id).append("`\n")
        append("- Характер: `").append(profile.personaId).append("`\n")
        append("- Активные навыки: ").append(if (profile.skillSelectionConfigured) profile.enabledSkillIds.joinToString().ifBlank { "нет" } else "все установленные").append("\n")
        append("- MCP-серверы: ").append(profile.enabledMcpServerIds?.joinToString().orEmpty().ifBlank { "наследуемые настройки" }).append("\n\n")
        append("## Инструменты\n\n")
        if (profile.toolOverrides.isEmpty()) {
            append("Используются значения по умолчанию.\n\n")
        } else {
            profile.toolOverrides.entries.sortedBy { it.key }.forEach { entry ->
                append("- `").append(entry.key).append("`: ").append(if (entry.value) "включён" else "выключен").append('\n')
            }
            append('\n')
        }
        append("## Инструменты локальной модели\n\n")
        append("- Настроено явно: ").append(profile.localToolSelection.configured).append('\n')
        append("- Встроенные: ").append(profile.localToolSelection.toolIds.joinToString().ifBlank { "по умолчанию" }).append('\n')
        append("- MCP: ").append(profile.localToolSelection.mcpServerIds.joinToString().ifBlank { "по умолчанию" }).append("\n\n")
        append("## Системный промпт\n\n").append(profile.soulText.ifBlank { "Используется базовый характер приложения." }).append("\n\n")
        append("## Образ Потсика\n\n```json\n")
        append(profileJson.encodeToString(PotsikLook.serializer(), profile.potsikLook))
        append("\n```\n")
    }

    // App open tracking
    fun trackAppOpen(): Int {
        val currentCount = settings.getInt(KEY_APP_OPENS, 0)
        val newCount = currentCount + 1
        settings.putInt(KEY_APP_OPENS, newCount)
        return newCount
    }

    // Tool enable/disable settings
    fun isToolEnabled(toolId: String, defaultEnabled: Boolean = true): Boolean = getActiveAgentProfile().toolOverrides[toolId] ?: defaultEnabled

    fun setToolEnabled(toolId: String, enabled: Boolean) {
        updateActiveAgentProfile { copy(toolOverrides = toolOverrides + (toolId to enabled)) }
    }

    fun clearActiveProfileToolOverrides(toolIds: List<String>) {
        val ids = toolIds.toSet()
        updateActiveAgentProfile { copy(toolOverrides = toolOverrides - ids) }
    }

    fun getConversationsJson(): String? = settings.getStringOrNull(KEY_CONVERSATIONS)

    fun setConversationsJson(json: String) {
        settings.putString(KEY_CONVERSATIONS, json)
    }

    fun removeConversationsJson() {
        settings.remove(KEY_CONVERSATIONS)
    }

    fun getCurrentConversationId(): String? = settings.getStringOrNull(KEY_CURRENT_CONVERSATION_ID)

    fun setCurrentConversationId(id: String?) {
        if (id == null) {
            settings.remove(KEY_CURRENT_CONVERSATION_ID)
        } else {
            settings.putString(KEY_CURRENT_CONVERSATION_ID, id)
        }
    }

    fun getMiniAppsJson(): String? = settings.getStringOrNull(KEY_MINI_APPS)

    fun setMiniAppsJson(json: String) {
        settings.putString(KEY_MINI_APPS, json)
    }

    /** Ids the user chose to pin to the chat toolbar. Null means "all visible apps". */
    fun getMiniAppToolbarIds(): String? = settings.getStringOrNull(KEY_MINI_APP_TOOLBAR_IDS)

    fun setMiniAppToolbarIds(json: String) {
        settings.putString(KEY_MINI_APP_TOOLBAR_IDS, json)
    }

    /**
     * Schemas the agent declared for interactive drafts that have not been saved
     * yet. Kept beside the app store instead of in it so an abandoned draft never
     * shows up as an app.
     */
    fun getMiniAppDraftsJson(): String? = settings.getStringOrNull(KEY_MINI_APP_DRAFTS)

    fun setMiniAppDraftsJson(json: String) {
        settings.putString(KEY_MINI_APP_DRAFTS, json)
    }

    fun getCurrentInteractiveMode(): Boolean = settings.getBoolean(KEY_CURRENT_INTERACTIVE_MODE, false)

    fun setCurrentInteractiveMode(enabled: Boolean) {
        settings.putBoolean(KEY_CURRENT_INTERACTIVE_MODE, enabled)
    }

    fun isCurrentConversationMigrated(): Boolean = settings.getBoolean(KEY_CURRENT_CONVERSATION_MIGRATED, false)

    fun markCurrentConversationMigrated() {
        settings.putBoolean(KEY_CURRENT_CONVERSATION_MIGRATED, true)
    }

    fun getEncryptionKey(): ByteArray? {
        val encoded = settings.getStringOrNull(KEY_ENCRYPTION_KEY) ?: return null
        return try {
            @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
            kotlin.io.encoding.Base64.decode(encoded)
        } catch (_: Exception) {
            null
        }
    }

    // Free fallback
    fun isFreeFallbackEnabled(): Boolean = settings.getBoolean(KEY_FREE_FALLBACK_ENABLED, true)

    fun setFreeFallbackEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_FREE_FALLBACK_ENABLED, enabled)
    }

    fun getFreeMode(): FreeMode {
        val stored = settings.getStringOrNull(KEY_FREE_MODE) ?: return FreeMode.FAST
        return FreeMode.entries.find { it.name == stored } ?: FreeMode.FAST
    }

    fun setFreeMode(mode: FreeMode) {
        settings.putString(KEY_FREE_MODE, mode.name)
    }

    fun isFreeServicePrimary(): Boolean = settings.getBoolean(KEY_FREE_SERVICE_PRIMARY, false)

    fun setFreeServicePrimary(primary: Boolean) {
        settings.putBoolean(KEY_FREE_SERVICE_PRIMARY, primary)
    }

    // Soul (system prompt)
    fun getSoulText(): String = getActiveAgentProfile().soulText

    fun setSoulText(text: String) {
        updateActiveAgentProfile { copy(soulText = text) }
    }

    // Obsidian vault (Android SAF tree URI)
    fun getObsidianVaultUri(): String = settings.getString(KEY_OBSIDIAN_VAULT_URI, "")

    fun setObsidianVaultUri(uri: String) {
        settings.putString(KEY_OBSIDIAN_VAULT_URI, uri.trim())
    }

    fun getSoulPresetId(): String = getActiveAgentProfile().personaId

    fun setSoulPresetId(id: String) {
        val normalized = id.trim().ifBlank { AgentPersonaPresets.ASSISTANT }
        updateActiveAgentProfile { copy(personaId = normalized) }
    }

    fun isAutoVectorizePhotos(): Boolean = settings.getBoolean(KEY_AUTO_VECTORIZE_PHOTOS, false)

    fun setAutoVectorizePhotos(enabled: Boolean) {
        settings.putBoolean(KEY_AUTO_VECTORIZE_PHOTOS, enabled)
    }

    fun isAutoVectorizeDocuments(): Boolean = settings.getBoolean(KEY_AUTO_VECTORIZE_DOCUMENTS, false)

    fun setAutoVectorizeDocuments(enabled: Boolean) {
        settings.putBoolean(KEY_AUTO_VECTORIZE_DOCUMENTS, enabled)
    }

    // Memory
    fun isMemoryEnabled(): Boolean = settings.getBoolean(KEY_MEMORY_ENABLED, true)

    fun setMemoryEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_MEMORY_ENABLED, enabled)
    }

    fun getMemoryInstructions(): String = settings.getString(KEY_MEMORY_INSTRUCTIONS, DEFAULT_MEMORY_INSTRUCTIONS)

    // Agent loop tuning
    fun getAgentTuning(): AgentTuning {
        val threshold = settings.getInt(KEY_AGENT_COMPACT_THRESHOLD, 70).coerceIn(30, 95)
        val target = settings.getInt(KEY_AGENT_COMPACT_TARGET, 35).coerceIn(10, threshold)
        return AgentTuning(
            maxToolIterations = settings.getInt(KEY_AGENT_MAX_TOOL_ITERATIONS, 30).coerceIn(1, 100),
            maxRepeatedToolCalls = settings.getInt(KEY_AGENT_MAX_REPEATED_TOOL_CALLS, 3).coerceIn(2, 10),
            maxParallelToolCalls = settings.getInt(KEY_AGENT_MAX_PARALLEL_TOOL_CALLS, 4).coerceIn(1, 16),
            toolCallsPerMinute = settings.getInt(KEY_AGENT_TOOL_CALLS_PER_MINUTE, 0).coerceIn(0, 600),
            maxApiRetries = settings.getInt(KEY_AGENT_MAX_API_RETRIES, 2).coerceIn(0, 5),
            autoCompact = settings.getBoolean(KEY_AGENT_AUTO_COMPACT, true),
            compactThresholdPercent = threshold,
            compactTargetPercent = target,
            compactKeepRecentTurns = settings.getInt(KEY_AGENT_COMPACT_KEEP_RECENT, 4).coerceIn(1, 20),
            reasoningEffort = settings.getString(KEY_AGENT_REASONING_EFFORT, ReasoningEffort.AUTO.name)
                .let { stored -> ReasoningEffort.entries.firstOrNull { it.name == stored } }
                ?: ReasoningEffort.AUTO,
        )
    }

    fun setAgentTuning(tuning: AgentTuning) {
        val threshold = tuning.compactThresholdPercent.coerceIn(30, 95)
        settings.putInt(KEY_AGENT_MAX_TOOL_ITERATIONS, tuning.maxToolIterations.coerceIn(1, 100))
        settings.putInt(KEY_AGENT_MAX_REPEATED_TOOL_CALLS, tuning.maxRepeatedToolCalls.coerceIn(2, 10))
        settings.putInt(KEY_AGENT_MAX_PARALLEL_TOOL_CALLS, tuning.maxParallelToolCalls.coerceIn(1, 16))
        settings.putInt(KEY_AGENT_TOOL_CALLS_PER_MINUTE, tuning.toolCallsPerMinute.coerceIn(0, 600))
        settings.putInt(KEY_AGENT_MAX_API_RETRIES, tuning.maxApiRetries.coerceIn(0, 5))
        settings.putBoolean(KEY_AGENT_AUTO_COMPACT, tuning.autoCompact)
        settings.putInt(KEY_AGENT_COMPACT_THRESHOLD, threshold)
        settings.putInt(KEY_AGENT_COMPACT_TARGET, tuning.compactTargetPercent.coerceIn(10, threshold))
        settings.putInt(KEY_AGENT_COMPACT_KEEP_RECENT, tuning.compactKeepRecentTurns.coerceIn(1, 20))
        settings.putString(KEY_AGENT_REASONING_EFFORT, tuning.reasoningEffort.name)
    }

    // Agent memories
    fun getMemoriesJson(): String = settings.getString(KEY_AGENT_MEMORIES, "[]")

    /**
     * Keeps a persisted JSON blob that no longer decodes (e.g. after a schema change without a
     * migration) under its own key, so the store's next write can't destroy the only copy.
     * Not part of settings export; recoverable by hand or by a later migration.
     */
    fun backupCorruptJson(label: String, raw: String) {
        settings.putString("$KEY_CORRUPT_JSON_BACKUP_PREFIX$label", raw)
    }

    fun setMemoriesJson(json: String) {
        settings.putString(KEY_AGENT_MEMORIES, json)
    }

    // Scheduling
    fun isSchedulingEnabled(): Boolean = settings.getBoolean(KEY_SCHEDULING_ENABLED, true)

    fun setSchedulingEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_SCHEDULING_ENABLED, enabled)
    }

    // Dynamic UI
    fun isDynamicUiEnabled(): Boolean = settings.getBoolean(KEY_DYNAMIC_UI_ENABLED, true)

    fun setDynamicUiEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_DYNAMIC_UI_ENABLED, enabled)
    }

    private val _animatedBackgroundFlow = MutableStateFlow(
        com.pusios.pots.ui.background.AnimatedBackground.fromName(settings.getString(KEY_ANIMATED_BACKGROUND, "")),
    )
    val animatedBackgroundFlow: StateFlow<com.pusios.pots.ui.background.AnimatedBackground> = _animatedBackgroundFlow

    fun getAnimatedBackground(): com.pusios.pots.ui.background.AnimatedBackground = _animatedBackgroundFlow.value

    fun setAnimatedBackground(kind: com.pusios.pots.ui.background.AnimatedBackground) {
        settings.putString(KEY_ANIMATED_BACKGROUND, kind.name)
        _animatedBackgroundFlow.value = kind
    }

    private val _themeModeFlow = MutableStateFlow(loadInitialThemeMode())
    val themeModeFlow: StateFlow<ThemeMode> = _themeModeFlow

    fun getThemeMode(): ThemeMode = _themeModeFlow.value

    fun setThemeMode(mode: ThemeMode) {
        settings.putString(KEY_THEME_MODE, mode.name)
        _themeModeFlow.value = mode
    }

    private fun loadInitialThemeMode(): ThemeMode {
        val raw = settings.getString(KEY_THEME_MODE, "")
        if (raw.isNotEmpty()) {
            return try {
                ThemeMode.valueOf(raw)
            } catch (_: IllegalArgumentException) {
                ThemeMode.System
            }
        }
        // Migrate the legacy boolean OLED toggle: true → OledBlack, false → System.
        return if (settings.getBoolean(KEY_OLED_MODE_ENABLED, false)) ThemeMode.OledBlack else ThemeMode.System
    }

    private val _appLanguageFlow = MutableStateFlow(loadInitialAppLanguage())
    val appLanguageFlow: StateFlow<AppLanguage> = _appLanguageFlow

    fun getAppLanguage(): AppLanguage = _appLanguageFlow.value

    fun setAppLanguage(language: AppLanguage) {
        settings.putString(KEY_APP_LANGUAGE, language.name)
        _appLanguageFlow.value = language
    }

    private fun loadInitialAppLanguage(): AppLanguage {
        val raw = settings.getString(KEY_APP_LANGUAGE, "")
        if (raw.isNotEmpty()) {
            return try {
                AppLanguage.valueOf(raw)
            } catch (_: IllegalArgumentException) {
                AppLanguage.System
            }
        }
        return AppLanguage.System
    }

    // Speech (TTS): engine, voices, rate and pitch.
    private val _ttsEngineFlow = MutableStateFlow(loadInitialTtsEngine())
    val ttsEngineFlow: StateFlow<TtsEngine> = _ttsEngineFlow

    fun getTtsEngine(): TtsEngine = _ttsEngineFlow.value

    fun setTtsEngine(engine: TtsEngine) {
        settings.putString(KEY_TTS_ENGINE, engine.name)
        _ttsEngineFlow.value = engine
    }

    private fun loadInitialTtsEngine(): TtsEngine {
        val raw = settings.getString(KEY_TTS_ENGINE, "")
        if (raw.isNotEmpty()) {
            return try {
                TtsEngine.valueOf(raw)
            } catch (_: IllegalArgumentException) {
                TtsEngine.Native
            }
        }
        return TtsEngine.Native
    }

    private val _ttsSystemVoiceFlow = MutableStateFlow(settings.getString(KEY_TTS_SYSTEM_VOICE, ""))
    val ttsSystemVoiceFlow: StateFlow<String> = _ttsSystemVoiceFlow

    fun getTtsSystemVoice(): String = _ttsSystemVoiceFlow.value

    fun setTtsSystemVoice(voiceName: String) {
        settings.putString(KEY_TTS_SYSTEM_VOICE, voiceName)
        _ttsSystemVoiceFlow.value = voiceName
    }

    private val _ttsPiperVoiceFlow = MutableStateFlow(settings.getString(KEY_TTS_PIPER_VOICE, ""))
    val ttsPiperVoiceFlow: StateFlow<String> = _ttsPiperVoiceFlow

    fun getTtsPiperVoice(): String = _ttsPiperVoiceFlow.value

    fun setTtsPiperVoice(voiceId: String) {
        settings.putString(KEY_TTS_PIPER_VOICE, voiceId)
        _ttsPiperVoiceFlow.value = voiceId
    }

    private val _ttsRateFlow = MutableStateFlow(settings.getFloat(KEY_TTS_RATE, 1.0f))
    val ttsRateFlow: StateFlow<Float> = _ttsRateFlow

    fun getTtsRate(): Float = _ttsRateFlow.value

    fun setTtsRate(rate: Float) {
        settings.putFloat(KEY_TTS_RATE, rate.coerceIn(0.5f, 2.0f))
        _ttsRateFlow.value = rate.coerceIn(0.5f, 2.0f)
    }

    private val _ttsPitchFlow = MutableStateFlow(settings.getFloat(KEY_TTS_PITCH, 1.0f))
    val ttsPitchFlow: StateFlow<Float> = _ttsPitchFlow

    fun getTtsPitch(): Float = _ttsPitchFlow.value

    fun setTtsPitch(pitch: Float) {
        settings.putFloat(KEY_TTS_PITCH, pitch.coerceIn(0.5f, 2.0f))
        _ttsPitchFlow.value = pitch.coerceIn(0.5f, 2.0f)
    }

    // Потсик — the animated character.
    //
    // Legacy individual appearance fields are read once while creating the
    // first profile; all subsequent reads/writes use that profile's look object.
    private val _potsikLookFlow = MutableStateFlow(getActiveAgentProfile().potsikLook)
    val potsikLookFlow: StateFlow<PotsikLook> = _potsikLookFlow

    fun getPotsikLook(): PotsikLook = _potsikLookFlow.value

    fun setPotsikLook(look: PotsikLook) {
        updateActiveAgentProfile { copy(potsikLook = look) }
        _potsikLookFlow.value = look
    }

    private fun loadInitialPotsikLook(): PotsikLook {
        val default = PotsikLook.Default
        return PotsikLook(
            isVisible = settings.getBoolean(KEY_POTSIK_VISIBLE, default.isVisible),
            outfit = settings.getString(KEY_POTSIK_OUTFIT, default.outfit.name).toEnum(default.outfit),
            shirtColor = settings.getString(KEY_POTSIK_SHIRT, default.shirtColor.name).toEnum(default.shirtColor),
            shortsColor = settings.getString(KEY_POTSIK_SHORTS, default.shortsColor.name).toEnum(default.shortsColor),
            shoesColor = settings.getString(KEY_POTSIK_SHOES, default.shoesColor.name).toEnum(default.shoesColor),
            shadeTint = settings.getString(KEY_POTSIK_SHADES, default.shadeTint.name).toEnum(default.shadeTint),
            glassesOn = settings.getBoolean(KEY_POTSIK_GLASSES, default.glassesOn),
            lashesOn = settings.getBoolean(KEY_POTSIK_LASHES, default.lashesOn),
            sizeScale = settings.getFloat(KEY_POTSIK_SIZE, default.sizeScale),
            sizePercent = settings.getInt(KEY_POTSIK_SIZE_PERCENT, default.sizePercent),
            tempoPercent = settings.getInt(KEY_POTSIK_TEMPO_PERCENT, default.tempoPercent),
            banterEnabled = settings.getBoolean(KEY_POTSIK_BANTER, default.banterEnabled),
            draggable = settings.getBoolean(KEY_POTSIK_DRAGGABLE, default.draggable),
            placementMode = settings.getString(KEY_POTSIK_PLACEMENT_MODE, default.placementMode.name)
                .toEnum(default.placementMode),
            pinnedX = settings.getFloat(KEY_POTSIK_PINNED_X, Float.NaN).takeUnless { it.isNaN() },
            pinnedY = settings.getFloat(KEY_POTSIK_PINNED_Y, Float.NaN).takeUnless { it.isNaN() },
        )
    }

    private inline fun <reified T : Enum<T>> String.toEnum(fallback: T): T = try {
        enumValueOf<T>(this)
    } catch (_: IllegalArgumentException) {
        fallback
    }

    // Daemon mode
    fun isDaemonEnabled(): Boolean = settings.getBoolean(KEY_DAEMON_ENABLED, false)

    fun setDaemonEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_DAEMON_ENABLED, enabled)
    }

    // Linux Sandbox
    fun isSandboxEnabled(): Boolean = settings.getBoolean(KEY_SANDBOX_ENABLED, true)

    fun setSandboxEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_SANDBOX_ENABLED, enabled)
    }

    /**
     * Which distribution the shell integration runs in. Each keeps its own
     * install, so changing this points the sandbox at the other one rather than
     * replacing anything — and an install always records its own distro, which
     * is what everything downstream reads.
     */
    fun getSandboxDistro(): LinuxDistro = LinuxDistro.fromId(settings.getStringOrNull(KEY_SANDBOX_DISTRO))

    /**
     * The distribution the user actually picked, or null if they never have.
     * Sandboxes predating the picker never recorded one, and the default must
     * not be mistaken for a choice — it would point them away from the Linux
     * they have been using all along.
     */
    fun getSandboxDistroOrNull(): LinuxDistro? = settings.getStringOrNull(KEY_SANDBOX_DISTRO)
        ?.let { id -> LinuxDistro.entries.firstOrNull { it.id == id } }

    fun setSandboxDistro(distro: LinuxDistro) {
        settings.putString(KEY_SANDBOX_DISTRO, distro.id)
    }

    /**
     * Pots Build's "Open with" choice — the agent a freshly opened project starts,
     * or null for a plain shell. Stored as an empty string so "never picked" and
     * "picked the shell" both come back as null.
     */
    fun getPotsBuildLaunchAgent(): String? = settings.getStringOrNull(KEY_POTS_BUILD_LAUNCH_AGENT)?.takeIf { it.isNotEmpty() }

    fun setPotsBuildLaunchAgent(agentId: String?) {
        settings.putString(KEY_POTS_BUILD_LAUNCH_AGENT, agentId.orEmpty())
    }

    fun getScheduledTasksJson(): String = settings.getString(KEY_SCHEDULED_TASKS, "[]")

    fun setScheduledTasksJson(json: String) {
        settings.putString(KEY_SCHEDULED_TASKS, json)
    }

    // Heartbeat config
    fun getHeartbeatConfigJson(): String = settings.getString(KEY_HEARTBEAT_CONFIG, "")

    fun setHeartbeatConfigJson(json: String) {
        settings.putString(KEY_HEARTBEAT_CONFIG, json)
    }

    // Heartbeat log
    fun getHeartbeatLogJson(): String = settings.getString(KEY_HEARTBEAT_LOG, "")

    fun setHeartbeatLogJson(json: String) {
        settings.putString(KEY_HEARTBEAT_LOG, json)
    }

    // Heartbeat prompt
    fun getHeartbeatPrompt(): String = settings.getString(KEY_HEARTBEAT_PROMPT, "")

    fun setHeartbeatPrompt(text: String) {
        settings.putString(KEY_HEARTBEAT_PROMPT, text)
    }

    // MCP Servers
    fun getMcpServersJson(): String = settings.getString(KEY_MCP_SERVERS, "")

    fun setMcpServersJson(json: String) {
        settings.putString(KEY_MCP_SERVERS, json)
    }

    // Tools available to the on-device (local) model

    /**
     * The user's choice of tools and MCP servers for the on-device model.
     *
     * An unconfigured selection ([LocalToolSelection.configured] is false) means the built-in
     * defaults apply; a configured one is honoured verbatim.
     */
    fun getLocalToolSelection(): LocalToolSelection = getActiveAgentProfile().localToolSelection

    fun setLocalToolSelection(selection: LocalToolSelection) {
        updateActiveAgentProfile { copy(localToolSelection = selection) }
    }

    // UI Scale
    private val _uiScaleFlow = MutableStateFlow(settings.getFloat(KEY_UI_SCALE, defaultUiScale))
    val uiScaleFlow: StateFlow<Float> = _uiScaleFlow

    fun getUiScale(): Float = _uiScaleFlow.value

    fun setUiScale(scale: Float) {
        settings.putFloat(KEY_UI_SCALE, scale)
        _uiScaleFlow.value = scale
    }

    // Email
    fun isEmailEnabled(): Boolean = settings.getBoolean(KEY_EMAIL_ENABLED, true)

    fun setEmailEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_EMAIL_ENABLED, enabled)
    }

    fun getEmailAccountsJson(): String = settings.getString(KEY_EMAIL_ACCOUNTS, "")

    fun setEmailAccountsJson(json: String) {
        settings.putString(KEY_EMAIL_ACCOUNTS, json)
    }

    fun getEmailPassword(accountId: String): String = settings.getString("${KEY_EMAIL_PASSWORD_PREFIX}$accountId", "")

    fun setEmailPassword(accountId: String, password: String) {
        settings.putString("${KEY_EMAIL_PASSWORD_PREFIX}$accountId", password)
    }

    fun removeEmailPassword(accountId: String) {
        settings.remove("${KEY_EMAIL_PASSWORD_PREFIX}$accountId")
    }

    fun getEmailSyncStateJson(accountId: String): String = settings.getString("${KEY_EMAIL_SYNC_PREFIX}$accountId", "")

    fun setEmailSyncStateJson(accountId: String, json: String) {
        settings.putString("${KEY_EMAIL_SYNC_PREFIX}$accountId", json)
    }

    fun getEmailPollIntervalMinutes(): Int = settings.getInt(KEY_EMAIL_POLL_INTERVAL, 15)

    fun setEmailPollIntervalMinutes(minutes: Int) {
        settings.putInt(KEY_EMAIL_POLL_INTERVAL, minutes)
    }

    fun getEmailPendingJson(): String = settings.getString(KEY_EMAIL_PENDING, "")

    fun setEmailPendingJson(json: String) {
        settings.putString(KEY_EMAIL_PENDING, json)
    }

    // SMS (FOSS-only, Android-only — settings layer is platform-agnostic, feature gate
    // is enforced by the READ_SMS permission being declared only in foss/AndroidManifest.xml)
    fun isSmsEnabled(): Boolean = settings.getBoolean(KEY_SMS_ENABLED, false)

    fun setSmsEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_SMS_ENABLED, enabled)
    }

    fun getSmsPollIntervalMinutes(): Int = settings.getInt(KEY_SMS_POLL_INTERVAL, 15)

    fun setSmsPollIntervalMinutes(minutes: Int) {
        settings.putInt(KEY_SMS_POLL_INTERVAL, minutes)
    }

    fun getSmsPendingJson(): String = settings.getString(KEY_SMS_PENDING, "")

    fun setSmsPendingJson(json: String) {
        settings.putString(KEY_SMS_PENDING, json)
    }

    fun getSmsSyncStateJson(): String = settings.getString(KEY_SMS_SYNC_STATE, "")

    fun setSmsSyncStateJson(json: String) {
        settings.putString(KEY_SMS_SYNC_STATE, json)
    }

    fun isSmsSendEnabled(): Boolean = settings.getBoolean(KEY_SMS_SEND_ENABLED, false)

    fun setSmsSendEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_SMS_SEND_ENABLED, enabled)
    }

    fun getSmsDraftsJson(): String = settings.getString(KEY_SMS_DRAFTS, "")

    fun setSmsDraftsJson(json: String) {
        settings.putString(KEY_SMS_DRAFTS, json)
    }

    // Notifications (FOSS-only, Android-only — settings layer is platform-agnostic, feature
    // gate is enforced by the listener service being declared only in foss/AndroidManifest.xml)
    fun isNotificationsEnabled(): Boolean = settings.getBoolean(KEY_NOTIFICATIONS_ENABLED, false)

    fun setNotificationsEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_NOTIFICATIONS_ENABLED, enabled)
    }

    fun getNotificationsPendingJson(): String = settings.getString(KEY_NOTIFICATIONS_PENDING, "")

    fun setNotificationsPendingJson(json: String) {
        settings.putString(KEY_NOTIFICATIONS_PENDING, json)
    }

    fun getNotificationsStoreJson(): String = settings.getString(KEY_NOTIFICATIONS_STORE, "")

    fun setNotificationsStoreJson(json: String) {
        settings.putString(KEY_NOTIFICATIONS_STORE, json)
    }

    fun getNotificationsSyncStateJson(): String = settings.getString(KEY_NOTIFICATIONS_SYNC_STATE, "")

    fun setNotificationsSyncStateJson(json: String) {
        settings.putString(KEY_NOTIFICATIONS_SYNC_STATE, json)
    }

    // Local model context size
    fun getModelContextTokens(modelId: String): Int = settings.getInt("$KEY_MODEL_CONTEXT_PREFIX$modelId", 0)

    fun setModelContextTokens(modelId: String, contextTokens: Int) {
        settings.putInt("$KEY_MODEL_CONTEXT_PREFIX$modelId", contextTokens)
    }

    // Splinterlands
    fun isSplinterlandsEnabled(): Boolean = settings.getBoolean(KEY_SPLINTERLANDS_ENABLED, false)

    fun setSplinterlandsEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_SPLINTERLANDS_ENABLED, enabled)
    }

    fun getSplinterlandsAccountJson(): String = settings.getString(KEY_SPLINTERLANDS_ACCOUNT, "")

    fun setSplinterlandsAccountJson(json: String) {
        settings.putString(KEY_SPLINTERLANDS_ACCOUNT, json)
    }

    fun getSplinterlandsPostingKey(): String = settings.getString(KEY_SPLINTERLANDS_POSTING_KEY, "")

    fun getSplinterlandsPostingKey(accountId: String): String = settings.getString("${KEY_SPLINTERLANDS_POSTING_KEY}_$accountId", "")
        .ifEmpty { getSplinterlandsPostingKey() } // fallback to legacy key

    fun setSplinterlandsPostingKey(accountId: String, key: String) {
        settings.putString("${KEY_SPLINTERLANDS_POSTING_KEY}_$accountId", key)
    }

    fun getSplinterlandsInstanceId(): String = settings.getString(KEY_SPLINTERLANDS_INSTANCE_ID, "")

    fun setSplinterlandsInstanceId(instanceId: String) {
        settings.putString(KEY_SPLINTERLANDS_INSTANCE_ID, instanceId)
    }

    fun getSplinterlandsInstanceIdsJson(): String = settings.getString(KEY_SPLINTERLANDS_INSTANCE_IDS, "")

    fun setSplinterlandsInstanceIdsJson(json: String) {
        settings.putString(KEY_SPLINTERLANDS_INSTANCE_IDS, json)
    }

    fun getSplinterlandsBattleLogJson(): String = settings.getString(KEY_SPLINTERLANDS_BATTLE_LOG, "")

    fun setSplinterlandsBattleLogJson(json: String) {
        settings.putString(KEY_SPLINTERLANDS_BATTLE_LOG, json)
    }

    companion object {
        private const val DEFAULT_AGENT_PROFILE_ID = "default"
        private const val KEY_AGENT_PROFILES = "agent_profiles_json"
        private const val KEY_ACTIVE_AGENT_PROFILE_ID = "active_agent_profile_id"
        private const val KEY_PROFILE_ID_COUNTER = "agent_profile_id_counter"
        const val KEY_CURRENT_SERVICE_ID = "current_service_id"
        const val KEY_APP_OPENS = "app_opens"

        const val KEY_CONVERSATIONS = "conversations_json"
        const val KEY_CURRENT_CONVERSATION_ID = "current_conversation_id"
        const val KEY_CURRENT_INTERACTIVE_MODE = "current_interactive_mode"
        const val KEY_MINI_APPS = "mini_apps_json"
        const val KEY_MINI_APP_TOOLBAR_IDS = "mini_app_toolbar_ids"
        const val KEY_MINI_APP_DRAFTS = "mini_app_drafts_json"
        const val KEY_CURRENT_CONVERSATION_MIGRATED = "current_conversation_migrated"
        const val KEY_ENCRYPTION_KEY = "encryption_key"
        const val KEY_MIGRATION_COMPLETE = "migration_complete_v1"
        const val KEY_TOOL_PREFIX = "tool_enabled_"
        const val KEY_SOUL = "soul_text"
        const val KEY_OBSIDIAN_VAULT_URI = "obsidian_vault_uri"
        const val KEY_SOUL_PRESET = "soul_preset_id"
        const val KEY_AUTO_VECTORIZE_PHOTOS = "auto_vectorize_photos"
        const val KEY_AUTO_VECTORIZE_DOCUMENTS = "auto_vectorize_documents"
        const val KEY_MEMORY_ENABLED = "memory_enabled"
        const val KEY_MEMORY_INSTRUCTIONS = "memory_instructions"
        const val KEY_AGENT_MEMORIES = "agent_memories"
        const val KEY_AGENT_MAX_TOOL_ITERATIONS = "agent_max_tool_iterations"
        const val KEY_AGENT_MAX_REPEATED_TOOL_CALLS = "agent_max_repeated_tool_calls"
        const val KEY_AGENT_MAX_PARALLEL_TOOL_CALLS = "agent_max_parallel_tool_calls"
        const val KEY_AGENT_TOOL_CALLS_PER_MINUTE = "agent_tool_calls_per_minute"
        const val KEY_AGENT_MAX_API_RETRIES = "agent_max_api_retries"
        const val KEY_AGENT_AUTO_COMPACT = "agent_auto_compact"
        const val KEY_AGENT_COMPACT_THRESHOLD = "agent_compact_threshold_percent"
        const val KEY_AGENT_COMPACT_TARGET = "agent_compact_target_percent"
        const val KEY_AGENT_COMPACT_KEEP_RECENT = "agent_compact_keep_recent_turns"
        const val KEY_AGENT_REASONING_EFFORT = "agent_reasoning_effort"
        const val KEY_CORRUPT_JSON_BACKUP_PREFIX = "corrupt_json_backup."
        const val KEY_SCHEDULED_TASKS = "scheduled_tasks"
        const val KEY_SCHEDULING_ENABLED = "scheduling_enabled"
        const val KEY_DYNAMIC_UI_ENABLED = "dynamic_ui_enabled"
        const val KEY_OLED_MODE_ENABLED = "oled_mode_enabled"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_APP_LANGUAGE = "app_language"
        const val KEY_TTS_ENGINE = "tts_engine"
        const val KEY_TTS_SYSTEM_VOICE = "tts_system_voice"
        const val KEY_TTS_PIPER_VOICE = "tts_piper_voice"
        const val KEY_TTS_RATE = "tts_rate"
        const val KEY_TTS_PITCH = "tts_pitch"
        const val KEY_ANIMATED_BACKGROUND = "animated_background"
        const val KEY_DAEMON_ENABLED = "daemon_enabled"
        const val KEY_HEARTBEAT_CONFIG = "heartbeat_config"
        const val KEY_HEARTBEAT_PROMPT = "heartbeat_prompt"
        const val KEY_HEARTBEAT_LOG = "heartbeat_log"

        const val KEY_EMAIL_ENABLED = "email_enabled"
        const val KEY_EMAIL_ACCOUNTS = "email_accounts"
        const val KEY_EMAIL_PASSWORD_PREFIX = "email_password_"
        const val KEY_EMAIL_SYNC_PREFIX = "email_sync_"
        const val KEY_EMAIL_POLL_INTERVAL = "email_poll_interval"
        const val KEY_EMAIL_PENDING = "email_pending"

        const val KEY_SMS_ENABLED = "sms_enabled"
        const val KEY_SMS_POLL_INTERVAL = "sms_poll_interval"
        const val KEY_SMS_PENDING = "sms_pending"
        const val KEY_SMS_SYNC_STATE = "sms_sync_state"
        const val KEY_SMS_SEND_ENABLED = "sms_send_enabled"
        const val KEY_SMS_DRAFTS = "sms_drafts"

        const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
        const val KEY_NOTIFICATIONS_PENDING = "notifications_pending"
        const val KEY_NOTIFICATIONS_STORE = "notifications_store"
        const val KEY_NOTIFICATIONS_SYNC_STATE = "notifications_sync_state"
        const val KEY_CONFIGURED_SERVICES = "configured_services"
        const val KEY_FREE_FALLBACK_ENABLED = "free_fallback_enabled"
        const val KEY_FREE_MODE = "free_mode"
        const val KEY_FREE_SERVICE_PRIMARY = "free_service_primary"
        const val KEY_SERVICES_MIGRATION_COMPLETE = "services_migration_complete_v1"
        const val KEY_UI_SCALE = "ui_scale"
        const val KEY_POTSIK_VISIBLE = "potsik_visible"
        const val KEY_POTSIK_OUTFIT = "potsik_outfit"
        const val KEY_POTSIK_SHIRT = "potsik_shirt_color"
        const val KEY_POTSIK_SHORTS = "potsik_shorts_color"
        const val KEY_POTSIK_SHOES = "potsik_shoes_color"
        const val KEY_POTSIK_SHADES = "potsik_shade_tint"
        const val KEY_POTSIK_GLASSES = "potsik_glasses_on"
        const val KEY_POTSIK_LASHES = "potsik_lashes_on"
        const val KEY_POTSIK_SIZE = "potsik_size_scale"
        const val KEY_POTSIK_SIZE_PERCENT = "potsik_size_percent"
        const val KEY_POTSIK_TEMPO_PERCENT = "potsik_tempo_percent"
        const val KEY_POTSIK_BANTER = "potsik_banter_enabled"
        const val KEY_POTSIK_DRAGGABLE = "potsik_draggable"
        const val KEY_POTSIK_PLACEMENT_MODE = "potsik_placement_mode"
        const val KEY_POTSIK_PINNED_X = "potsik_pinned_x"
        const val KEY_POTSIK_PINNED_Y = "potsik_pinned_y"
        const val KEY_MCP_SERVERS = "mcp_servers"
        const val KEY_LOCAL_TOOL_SELECTION = "local_tool_selection"
        const val KEY_INSTANCE_MIGRATION_COMPLETE = "instance_migration_complete_v1"
        const val KEY_BASE_URL_V1_MIGRATION_COMPLETE = "base_url_v1_migration_complete"
        const val KEY_CUSTOM_MODEL_MIGRATION_COMPLETE = "custom_model_migration_complete_v1"

        const val KEY_SPLINTERLANDS_ENABLED = "splinterlands_enabled"
        const val KEY_SPLINTERLANDS_ACCOUNT = "splinterlands_account"
        const val KEY_SPLINTERLANDS_POSTING_KEY = "splinterlands_posting_key"
        const val KEY_SPLINTERLANDS_BATTLE_LOG = "splinterlands_battle_log"
        const val KEY_SPLINTERLANDS_INSTANCE_ID = "splinterlands_instance_id"
        const val KEY_SPLINTERLANDS_INSTANCE_IDS = "splinterlands_instance_ids"

        const val KEY_MODEL_CONTEXT_PREFIX = "model_context_"

        const val KEY_SANDBOX_ENABLED = "sandbox_enabled"
        const val KEY_SANDBOX_DISTRO = "sandbox_distro"
        const val KEY_POTS_BUILD_LAUNCH_AGENT = "pots_build_launch_agent"

        // Basic memory guidance shared by every chat variant. The advanced `## Structured
        // Learning` block lives in `ChatSystemPromptBuilder.DEFAULT_STRUCTURED_LEARNING_SECTION`
        // and is composed in only for the remote variant.
        const val DEFAULT_MEMORY_INSTRUCTIONS =
            "You have persistent memory across conversations. " +
                "All your stored memories are listed in the system prompt grouped by category.\n\n" +
                "When you learn important information about the user (name, preferences, projects, goals, etc.), " +
                "proactively use the memory_store tool to save it.\n" +
                "Use the memory_forget tool to remove outdated or incorrect memories.\n" +
                "Do not store trivial or transient information."
    }
}
