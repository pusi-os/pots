# Agent Profiles (Потсы)

**Last verified:** 2026-10-27

A Потс is one named agent setup: its character/system prompt, enabled tools, installed-skill selection, MCP server selection, and complete Потсик look/behavior. Profiles are selectable from **Settings → Agent** or the person icon in the chat toolbar.

## Profile-owned settings

| Setting | Profile behavior |
|---|---|
| Name | User-provided, editable; one profile is always retained |
| Ready/custom character | Preset id and the profile's own soul text |
| Built-in tool overrides | Enabled/disabled choices are independent per profile |
| Local-model tool selection | Built-in tool and MCP allowlist is independent per profile |
| MCP selection | Server configurations/credentials remain shared; enabled server ids are per profile |
| Skills | Installed globally, selected for each profile; an untouched selection means all installed skills |
| Потсик | Visibility, costume, colors, shades, size, tempo, banter, dragging, placement mode and remembered position |

## Shared app settings

These remain common when changing profiles: providers, API credentials, models and model context windows; memory and memory instructions; context compression and agent-loop tuning (including reasoning effort); heartbeat and scheduled tasks; file/email/notification/integration access; Obsidian vault; animated background; Dynamic UI; sandbox and platform permissions. MCP server connection details and installed skills are shared resources, while each profile's selection is separate.

## Persistence and switching

The protected settings store contains the profile registry and active profile id. Each profile is mirrored under `agent_profiles/<profile-id>/profile.json` and `profile.md` in the app's files area (the Web target maps these paths to persistent localStorage keys). JSON contains the complete profile configuration; Markdown is a human-readable snapshot. The profile registry remains the recovery source if a mirrored file cannot be written.

Switching profiles stops an in-flight turn, starts a fresh chat session, applies the new soul and tool selection, reconnects the selected MCP servers, and restores that profile's Потсик appearance. The old Потсик waves and exits; the incoming one enters with the selected look. The toolbar shows small, independently animated profile characters and their names.

## Key files

| File | Purpose |
|---|---|
| `composeApp/src/commonMain/kotlin/com/pusios/pots/data/AgentProfile.kt` | Serializable profile model |
| `composeApp/src/commonMain/kotlin/com/pusios/pots/data/AppSettings.kt` | Registry, active profile, migrations, profile-scoped data and JSON/Markdown snapshots |
| `composeApp/src/commonMain/kotlin/com/pusios/pots/data/RemoteDataRepository.kt` | Applies profile activation, starts a fresh session and updates MCP connections |
| `composeApp/src/commonMain/kotlin/com/pusios/pots/ui/settings/AgentSettings.kt` | Create/select/rename profiles, choose skills and edit the profile character |
| `composeApp/src/commonMain/kotlin/com/pusios/pots/ui/chat/composables/AgentProfilesToolbar.kt` | Animated chooser and Потсик handoff |
| `composeApp/src/commonMain/kotlin/com/pusios/pots/ui/potsik/PotsikLook.kt` | Serializable Потсик look and behavior bundle |
