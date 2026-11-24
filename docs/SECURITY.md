# Security

**Last verified:** 2026-11-24

This page documents the security model of the Pots **password manager** (the
encrypted card vault) — what it protects, what it explicitly does not protect,
and how to back it up. For the general settings storage (API keys, email
passwords), see [features/encryption.md](features/encryption.md).

## Scope

The password manager keeps its data in a single file, `vault/vault.pots`, in
the app-private files directory. It is completely separate from
[settings export](features/settings-export-import.md): no vault data ever
enters the settings JSON.

## What is protected

### Data at rest

- The whole vault file is an encrypted JSON envelope: PBKDF2-HMAC-SHA256
  (600,000 iterations, salt stored in the file header) derives a KEK from the
  master password; the KEK wraps a random vault key; the vault key seals the
  document with **AES-256-GCM**. AAD strings bind the format version, so a
  downgraded or swapped header breaks the authentication tag.
- The file contains ciphertext only — no names, values, or field markers are
  readable without the master password (enforced by tests).
- Writes are atomic (temp file + move); an unreadable file is quarantined
  (renamed aside), never silently overwritten.
- While locked, neither the key nor the plaintext is in memory. After 5
  minutes without activity the session locks itself; locking wipes the key
  and the plaintext document. Copying a value to the clipboard schedules an
  automatic clipboard wipe (~45 s) and marks the clip sensitive on Android.
- The vault directory is excluded from Android Auto Backup and device
  transfer (`backup_rules.xml`, `data_extraction_rules.xml`).

### In chat and toward the AI provider

- The agent never receives secret values in the prompt. Vault tools return
  ids and `vault://<item>/<field>` references; a value is substituted only
  inside a secret-request form or a guarded HTTP tool, only into the
  destination the card names, and only after your confirmation in the sheet.
- Tool results and tool-call arguments that the chat history stores do not
  contain secret values; nothing vault-related is sent to the AI provider.

### Export/import

- Export writes a `*.pvault` file in the same envelope format, encrypted
  under the export password you type (it can differ from the vault master
  password). There is no CSV or plaintext export.
- Import verifies the header and both authentication tags. A tampered,
  corrupted, or wrong-password file is refused with a distinct message and
  the current vault is left untouched. Wrong-password and tampered failures
  are separate outcomes (wrong password is detected at key unwrap, tampering
  at payload verification).

## What is NOT protected

- **A compromised device.** The master password is typed on the device, and
  while the vault is unlocked both the key and the plaintext live in the
  process memory. Root, ADB, malware, or a person holding the unlocked phone
  can read your secrets — no app can defend against that. Auto-lock limits
  the window but does not remove it.
- **Forgetting the master password.** There is no recovery and no reset.
  The data is gone. The same applies to the export password of a backup.
- **Desktop key management.** On desktop the vault is protected by the master
  password only (PBKDF2); there is no OS keyring integration in v1.
- **Platforms without the vault.** The web build and iOS have no vault in v1.
- **Clipboard.** Between the copy and the automatic wipe, any app that can
  read the clipboard can read the copied secret.
- **The agent with your approval.** When you confirm a secret request in the
  chat sheet, the agent does use the secret at the destination it asked for.
  Approving is trusting.

## Backing up the vault

1. Open **Settings → Password manager**, unlock the vault.
2. Tap the **Export vault** button (save icon in the header).
3. Choose an export password — a long, unique passphrase; it is the only key
   to the backup. Confirm it twice.
4. Pick the destination file (`pots-vault-<timestamp>.pvault`). The file is
   fully encrypted, so any storage works: cloud drive, USB drive, another
   machine. Still, keep the password outside the same storage.
5. Verify the backup once: import it with **Import vault** on the same or
   another device and check the cards are there (use **Merge** so nothing is
   removed).

To restore on a new device: install Pots, create a vault (any master
password), unlock it, then **Import vault** with the `*.pvault` file and its
export password — **Merge** adds the cards to the fresh vault, **Replace**
discards the current cards and takes the file contents.

Repeat the export regularly, especially after important edits.
