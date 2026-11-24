# Password Manager

**Last verified:** 2026-11-24

Pots ships a local, encrypted password manager: cards for logins, bank cards,
identity data, notes, SSH keys, hosts, and tokens — with custom fields, tags,
and full agent integration. Everything lives in one encrypted file on the
device; there is no cloud and no sync.

See [SECURITY.md](../SECURITY.md) for the threat model and the backup guide.

## Vault and session

- First run: choose a master password (no recovery — forgetting it means the
  vault cannot be opened). The vault file is `vault/vault.pots` in
  app-private storage: AES-256-GCM with a two-level key (PBKDF2-HMAC-SHA256
  600k → KEK wraps a random vault key). Changing the master password only
  re-wraps the key; the payload is not re-encrypted.
- The session locks itself after 5 minutes without activity; the lock and
  unlock screens take a master password. Wrong password, tampering, and a
  corrupted file are reported as distinct errors.
- The vault directory is excluded from Android Auto Backup; use the export
  feature for backups instead.

## Cards and fields

Cards are flat entries with typed fields (text, secret, multiline, URL), a
name, URL, username, notes, tags, and a favorite flag. Seven templates
pre-fill common field sets (login, card, identity, note, SSH key, host,
token); unknown field or card types from a newer app version degrade
gracefully instead of breaking the file.

## Agent tools

When the agent has the vault enabled, it can list cards, search, create
cards, and fill forms — without ever seeing secret values in the prompt:

- `vault_list` / `vault_search` return names, urls, and usernames — no
  secret values.
- Secrets are delivered through a request form you confirm in the chat sheet;
  the value goes straight into the destination, and the tool result contains
  only a `vault://` reference.
- Copying a secret to the clipboard sets the sensitive flag on Android and
  schedules an automatic wipe after ~45 seconds.

## Export and import

- **Export** writes the whole vault to an encrypted `*.pvault` file (same
  envelope format) under the export password you choose. There is no CSV or
  plaintext export.
- **Import** reads a `*.pvault` file after verifying its integrity:
  **Merge** adds imported cards (ones with the same id replace existing
  cards), **Replace** drops the current cards and takes the file contents.
  Ids and creation timestamps survive the roundtrip, so a repeated import of
  the same file does not duplicate cards. A tampered, corrupted, or
  wrong-password file is refused and the current vault is untouched.
