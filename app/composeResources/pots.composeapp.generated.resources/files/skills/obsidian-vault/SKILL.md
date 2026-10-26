---
name: obsidian-vault
description: Work with the user's Obsidian vault: list, read, create, update and semantically search notes using local vector search. Use when the user mentions Obsidian, their notes, vault, knowledge base, or asks to save/find/organize information in notes.
---

# Obsidian Vault

You have tools to work with the user's Obsidian vault:
- `obsidian_list_notes` — list notes, optionally in a subfolder.
- `obsidian_read_note` — read a note by relative path.
- `obsidian_write_note` — create or update a note (Markdown). Use `append=true` to add to an existing note.
- `obsidian_search_notes` — local TF-IDF vector search over note chunks. No network, no cloud.

## Guidelines

1. Before writing, check whether a relevant note already exists with `obsidian_search_notes` or `obsidian_list_notes`.
2. Prefer updating an existing note over creating duplicates. Use clear headings and keep Markdown valid.
3. For research or meeting notes, include a title, date, and concise bullets.
4. When the user asks to "remember" or "save" something long-term, store it in the vault and mention the path.
5. Search results are ranked by local vector similarity; read the top results before answering.
6. Never overwrite a note without confirming with the user unless they explicitly asked to replace it.
7. Paths are relative to the vault root and use `/`. Markdown files usually end in `.md`.
8. If tools report the vault is not configured, tell the user to pick the vault folder in Settings → Tools → Obsidian.
