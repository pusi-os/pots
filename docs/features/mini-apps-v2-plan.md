# Mini-apps v2 — improvement plan

## Goal
Make saved mini-apps fully standalone, data-driven web apps: last working version + Screen that runs as a standalone app with its own backend (from Integrations background server), dynamic persistence to DB without LLM inference, all functionality on scripts/snippets, agent tools/skills, save conversion dialog with progress+log, sandbox usage.

## Current state (what works)
- Interactive UI studio, Save gathers pots-ui screens + collections + records, version tree, toolbar icons, runner, native actions store_put/store_delete/navigate/host_call, web apps served by MiniAppServer, MiniAppDbTool, automations.
- Limitations list from mini-apps.md (document store, automations via agent, capabilities declarative, web no SQLite).

## Gaps
1. Save is synchronous-ish, no conversion step: prototype-only elements (callbacks needing LLM) remain; no removal of prototype scaffolding; no headers/metadata normalization; no validation of standalone readiness.
2. Web apps rely on server running; no offline-first caching; no service worker; no IndexedDB fallback.
3. Agent lacks snippets/scripts for authoring robust web apps (fetch API, IndexedDB, service worker, host API usage).
4. No progress dialog with log during save.
5. Sandbox (Linux) underutilized by agent for backend scripts/testing.
6. Data dynamic updates require LLM for callbacks; need migration to host_call/store_* only.
7. Version "last working full-functional version" not explicitly marked/validated.

## Target architecture
- Save flow becomes a pipeline: collect screens -> validate -> normalize (remove prototype-only dead ends, add headers/meta) -> persist collections/records -> write web assets -> register automations -> mark version as "standalone-ready" -> show progress dialog.
- Web app template with service worker + IndexedDB cache + host API client snippet.
- Agent skills: mini-app-authoring, mini-app-web-backend, mini-app-data, sandbox-testing.
- Snippets library in docs/knowledge or composeResources.

## Best practices (from research)
- Offline-first: app shell cache, IndexedDB for structured data, background sync, service worker precache, versioned caches.
- Progressive enhancement, CSP, same-origin API.
- Standalone display mode manifest.
- Idempotent save, atomic version nodes, rollback via new version.
- Progress UI: modal with progress bar + scrollable log + cancel (optional).

## Phased implementation
Wave 0: Docs + skills + snippets (this plan).
Wave 1: Save pipeline + progress dialog UI + conversion normalizer.
Wave 2: Web app template/snippets injection + service worker + IndexedDB helper.
Wave 3: Agent tools additions: sandbox shell integration, DB query helpers, server control, snippet insertion.
Wave 4: Standalone validation + "last working version" marker + toolbar launch.
Wave 5: Tests (desktopTest) + screenshot goldens + docs update + Last verified bump.

## Files to touch (indicative)
- ChatViewModel.saveMiniApp -> delegate to MiniAppSavePipeline
- New: data/miniapp/MiniAppSavePipeline.kt
- New UI: ui/miniapp/MiniAppSaveDialog.kt
- MiniAppRepository.saveDraft enhancements
- MiniAppServer template files
- tools/MiniAppDbTool additions: validate_standalone, add_snippet
- skills resources
- ChatSystemPromptBuilder snippets section
- docs updates

## Agent snippets (provide)
- Web app fetch host API snippet
- IndexedDB wrapper
- Service worker
- Form submit -> store_put
- host_call usage
- Offline fallback

## Sandbox usage
- Agent can run node/python in sandbox to lint/test web assets, generate icons, validate JSON, run sqlite queries.
- Add tools: execute_shell_command already exists; document usage in skill.

## Risks
- Don't break existing saved apps; migration additive.
- Keep SQLDelight migrations numbered.
- ProGuard keep rules.

## Testing
- desktopTest unit tests for pipeline normalization.
- Paparazzi screenshots for dialog.
