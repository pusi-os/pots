// Encrypted vault: unlock session. The unwrapped vault key and the plaintext
// document live in memory only — never on disk, never in settings, never in
// logs. Locking wipes both. A wrong master password and a tampered file come
// back as distinct [VaultUnlockResult]s instead of a crash.

package com.pusios.pots.data.vault

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Result of [VaultSession.unlock], one per situation the unlock screen must name. */
sealed interface VaultUnlockResult {
    data object Success : VaultUnlockResult
    data object WrongPassword : VaultUnlockResult
    data object Tampered : VaultUnlockResult
    data object Corrupted : VaultUnlockResult
    data object NotCreated : VaultUnlockResult
    data class UnsupportedVersion(val version: Int) : VaultUnlockResult
    data object NotSupported : VaultUnlockResult
}

/** How [VaultSession.importVault] treats the cards from a *.pvault backup. */
enum class VaultImportMode {
    /** Keep the current cards; imported cards replace ones with the same id. */
    MERGE,

    /** Drop the current cards and take the whole file contents. */
    REPLACE,
}

/** Result of [VaultSession.importVault], one per situation the import dialog must name. */
sealed interface VaultImportResult {
    data class Success(val imported: Int) : VaultImportResult
    data object WrongPassword : VaultImportResult
    data object Tampered : VaultImportResult
    data object Corrupted : VaultImportResult
    data class UnsupportedVersion(val version: Int) : VaultImportResult
    data object Locked : VaultImportResult
    data object NotSupported : VaultImportResult
}

/** How long the session stays unlocked without activity before locking itself. */
val DEFAULT_AUTO_LOCK_TIMEOUT: Duration = 5.minutes

@OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)
class VaultSession(
    private val store: VaultStore,
    private val autoLockTimeout: Duration = DEFAULT_AUTO_LOCK_TIMEOUT,
    private val clock: TimeSource = TimeSource.Monotonic,
) {
    val isSupported: Boolean
        get() = store.isSupported

    private val _locked = MutableStateFlow(true)

    /** True while the vault key is not in memory. Collect for lock screens. */
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private var envelope: VaultEnvelope? = null
    private var vaultKey: ByteArray? = null
    private var document: VaultDocument? = null
    private var lastActivity: TimeMark? = null

    /** True once the setup flow has produced a vault file. */
    fun hasVaultFile(): Boolean = store.exists()

    /** Create the vault with an empty document and start an unlocked session. */
    suspend fun createVault(masterPassword: String) {
        assertSupported()
        val sealed = store.create(masterPassword)
        adopt(sealed.envelope, sealed.vaultKey, VaultDocument())
    }

    /**
     * Derive the KEK, unwrap the vault key, open the payload. Any failure locks
     * the session and returns a named result — never throws, never crashes.
     */
    suspend fun unlock(masterPassword: String): VaultUnlockResult {
        if (!_locked.value) return VaultUnlockResult.Success
        if (!isSupported) return VaultUnlockResult.NotSupported
        val engine = requireNotNull(store.engine)
        return withContext(Dispatchers.Default) {
            try {
                val env = store.readEnvelope() ?: return@withContext VaultUnlockResult.NotCreated
                val kek = deriveKek(engine, masterPassword, env.kdf)
                val key = unwrapVaultKey(engine, kek, env.wrap)
                val doc = openPayload(engine, key, env.payload)
                adopt(env, key, doc)
                VaultUnlockResult.Success
            } catch (_: VaultWrongPasswordException) {
                lock()
                VaultUnlockResult.WrongPassword
            } catch (_: VaultTamperedException) {
                lock()
                VaultUnlockResult.Tampered
            } catch (_: VaultCorruptedException) {
                lock()
                VaultUnlockResult.Corrupted
            } catch (e: VaultUnsupportedVersionException) {
                lock()
                VaultUnlockResult.UnsupportedVersion(e.version)
            }
        }
    }

    /** Wipe the key and the plaintext document. Safe to call when already locked. */
    fun lock() {
        vaultKey?.fill(0)
        vaultKey = null
        document = null
        envelope = null
        lastActivity = null
        _locked.value = true
    }

    /**
     * Eager check of the auto-lock timeout against the current clock, returning
     * the fresh locked state. The [locked] flow only publishes lock()/adopt()
     * transitions — it goes stale while nobody touches the session, because
     * the expiry itself is noticed lazily inside [checkUnlocked]. Callers that
     * must act on the *actual* state (the chat request sheet gating its
     * submit) use this instead of [locked.value]. Checking is not activity:
     * the timer is left alone, an expired session only locks.
     */
    fun refreshLockState(): Boolean {
        if (!_locked.value) {
            val mark = lastActivity
            if (mark != null && mark.elapsedNow() > autoLockTimeout) {
                lock()
            }
        }
        return _locked.value
    }

    /**
     * Run [block] with the raw vault key. Throws [VaultLockedException] when the
     * session is locked, including when it timed out right before the call.
     */
    suspend fun <T> withKey(block: suspend (ByteArray) -> T): T {
        checkUnlocked()
        touch()
        return block(requireNotNull(vaultKey))
    }

    /** Plaintext document, or null while locked. Refreshes the auto-lock timer. */
    fun document(): VaultDocument? = try {
        checkUnlocked()
        touch()
        document
    } catch (_: VaultLockedException) {
        null
    }

    /**
     * Insert or replace a card. [values] is the internal-API path (the agent's
     * form bridge): field values keyed by field key, merged into the card's
     * fields without ever serializing values outside the app. A value whose key
     * is not in the item yet is appended as a TEXT field. Blank id → new UUID.
     */
    suspend fun upsert(item: VaultItem, values: Map<String, String> = emptyMap()): VaultItem = withKey { key ->
        val now = epochMillis()
        val id = item.id.ifBlank { Uuid.random().toString() }
        val createdAt = currentDocument().items.firstOrNull { it.id == id }
            ?.createdAt
            ?.takeIf { it > 0 }
            ?: now
        val saved = mergeValues(item, values).copy(
            id = id,
            createdAt = createdAt,
            updatedAt = now,
        )
        updateDocument { items -> items.filterNot { it.id == id } + saved }
        persist(key)
        saved
    }

    /** Remove a card by id; false when there is no such card. */
    suspend fun delete(itemId: String): Boolean = withKey { key ->
        val doc = currentDocument()
        if (doc.items.none { it.id == itemId }) return@withKey false
        updateDocument { items -> items.filterNot { it.id == itemId } }
        persist(key)
        true
    }

    /**
     * Filter cards by text (name/url/username/notes/tags) and optionally by
     * known kind or tag. Empty list while locked.
     */
    fun search(
        query: String = "",
        kind: VaultItemKind? = null,
        tag: String? = null,
    ): List<VaultItem> {
        val doc = try {
            checkUnlocked()
            touch()
            currentDocument()
        } catch (_: VaultLockedException) {
            return emptyList()
        }
        val q = query.trim()
        return doc.items.filter { item ->
            if (kind != null && item.type != kind.id) return@filter false
            if (tag != null && item.tags.none { it.equals(tag, ignoreCase = true) }) return@filter false
            if (q.isEmpty()) return@filter true
            (listOf(item.name, item.url, item.username, item.notes) + item.tags)
                .any { it.contains(q, ignoreCase = true) }
        }
    }

    /**
     * Re-wrap the vault key under a new master password (fresh salt, fresh wrap
     * block, payload untouched). False when the old password is wrong.
     */
    suspend fun changeMasterPassword(oldPassword: String, newPassword: String): Boolean {
        assertSupported()
        val engine = requireNotNull(store.engine)
        checkUnlocked()
        val current = requireNotNull(envelope)
        val key = requireNotNull(vaultKey)
        return withContext(Dispatchers.Default) {
            try {
                val kek = deriveKek(engine, oldPassword, current.kdf)
                unwrapVaultKey(engine, kek, current.wrap)
            } catch (_: VaultWrongPasswordException) {
                return@withContext false
            }
            val rewrapped = reWrapEnvelope(engine, current, key, newPassword)
            store.save(rewrapped)
            envelope = rewrapped
            touch()
            true
        }
    }

    /**
     * Serialized *.pvault backup bytes of the whole current document, sealed in
     * a fresh envelope under [exportPassword]: fresh salt, fresh vault key, own
     * KDF header. The live vault's master password is not involved — the file
     * opens only with [exportPassword]. Throws [VaultLockedException] when the
     * session is locked.
     */
    suspend fun exportVault(exportPassword: String): ByteArray {
        assertSupported()
        checkUnlocked()
        val engine = requireNotNull(store.engine)
        val document = currentDocument()
        return withContext(Dispatchers.Default) {
            envelopeBytes(buildEnvelope(engine, document, exportPassword).envelope)
        }
    }

    /**
     * Import a *.pvault backup: parse, verify the header and both tags (a wrong
     * password and a tampered file are distinct results, the vault is never
     * touched on failure), then apply the cards in [mode]. Blank ids get fresh
     * ones; blank timestamps are stamped now, otherwise ids and createdAt are
     * preserved so repeated imports stay idempotent.
     */
    suspend fun importVault(bytes: ByteArray, password: String, mode: VaultImportMode): VaultImportResult {
        if (!isSupported) return VaultImportResult.NotSupported
        if (refreshLockState()) return VaultImportResult.Locked
        val engine = requireNotNull(store.engine)
        return withContext(Dispatchers.Default) {
            try {
                val envelope = parseEnvelope(bytes)
                val kek = deriveKek(engine, password, envelope.kdf)
                val key = unwrapVaultKey(engine, kek, envelope.wrap)
                val importedDocument = openPayload(engine, key, envelope.payload)
                val imported = withKey { vaultKey ->
                    val now = epochMillis()
                    val items = importedDocument.items.map { item ->
                        val id = item.id.ifBlank { Uuid.random().toString() }
                        val createdAt = currentDocument().items.firstOrNull { it.id == id }?.createdAt
                            ?.takeIf { it > 0 }
                            ?: item.createdAt.takeIf { it > 0 }
                            ?: now
                        item.copy(id = id, createdAt = createdAt, updatedAt = item.updatedAt.takeIf { it > 0 } ?: now)
                    }
                    updateDocument { current ->
                        when (mode) {
                            VaultImportMode.MERGE ->
                                current.filterNot { existing -> items.any { it.id == existing.id } } + items
                            VaultImportMode.REPLACE -> items
                        }
                    }
                    persist(vaultKey)
                    items.size
                }
                VaultImportResult.Success(imported)
            } catch (_: VaultWrongPasswordException) {
                VaultImportResult.WrongPassword
            } catch (_: VaultTamperedException) {
                VaultImportResult.Tampered
            } catch (_: VaultCorruptedException) {
                VaultImportResult.Corrupted
            } catch (e: VaultUnsupportedVersionException) {
                VaultImportResult.UnsupportedVersion(e.version)
            } catch (_: VaultLockedException) {
                VaultImportResult.Locked
            }
        }
    }

    private fun adopt(envelope: VaultEnvelope, key: ByteArray, document: VaultDocument) {
        this.envelope = envelope
        this.vaultKey = key
        this.document = document
        _locked.value = false
        touch()
    }

    private fun touch() {
        lastActivity = clock.markNow()
    }

    private fun checkUnlocked() {
        if (_locked.value) throw VaultLockedException()
        val mark = lastActivity
        if (mark != null && mark.elapsedNow() > autoLockTimeout) {
            lock()
            throw VaultLockedException()
        }
    }

    private fun currentDocument(): VaultDocument = document ?: throw VaultLockedException()

    private fun updateDocument(edit: (List<VaultItem>) -> List<VaultItem>) {
        document = currentDocument().copy(items = edit(currentDocument().items))
    }

    private suspend fun persist(key: ByteArray) {
        val engine = requireNotNull(store.engine)
        val current = envelope ?: throw VaultLockedException()
        val updated = current.copy(payload = sealPayload(engine, key, currentDocument()))
        store.save(updated)
        envelope = updated
    }

    private fun assertSupported() {
        if (!isSupported) throw VaultUnsupportedPlatformException()
    }

    private fun mergeValues(item: VaultItem, values: Map<String, String>): VaultItem {
        if (values.isEmpty()) return item
        val fields = item.fields.toMutableList()
        val extras = LinkedHashMap<String, String>()
        for ((key, value) in values) {
            val index = fields.indexOfFirst { it.key == key }
            if (index >= 0) {
                fields[index] = fields[index].copy(value = value)
            } else {
                extras[key] = value
            }
        }
        val appended = extras.map { (key, value) ->
            VaultField(key = key, label = key, type = VaultFieldKind.TEXT.id, value = value)
        }
        var merged = item.copy(fields = fields + appended)
        // Keep the top-level card trio in sync with the canonical field values.
        values["url"]?.let { merged = merged.copy(url = it) }
        values["username"]?.let { merged = merged.copy(username = it) }
        values["notes"]?.let { merged = merged.copy(notes = it) }
        return merged
    }

    private fun epochMillis(): Long = Clock.System.now().toEpochMilliseconds()
}
