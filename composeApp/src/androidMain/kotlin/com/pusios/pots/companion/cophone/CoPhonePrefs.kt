package com.pusios.pots.companion.cophone

import android.content.Context

/**
 * CoPhone link settings. Persists the link switch, the SAS pair material
 * (pairKey + phoneId) and the paired cube's display identity. The observed
 * BLE address is NEVER stored or matched — Android rotates it (RPA); the
 * pairKey is what reconnects.
 */
class CoPhonePrefs(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Master switch: advertise the CoPhone service / run the bridge. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_ENABLED, v).apply()

    /** 32-byte pair key as hex (64 chars); "" = not paired. */
    var pairKeyHex: String
        get() = prefs.getString(KEY_PAIRKEY, "") ?: ""
        set(v) = prefs.edit().putString(KEY_PAIRKEY, normalizeHex(v, 64)).apply()

    /** 6-byte phoneId as hex (12 chars); "" = not paired. */
    var phoneIdHex: String
        get() = prefs.getString(KEY_PHONEID, "") ?: ""
        set(v) = prefs.edit().putString(KEY_PHONEID, normalizeHex(v, 12)).apply()

    val paired: Boolean
        get() = pairKeyHex.length == 64 && phoneIdHex.length == 12

    /** MAC of the paired cube, display only (never used to match). */
    var deviceMac: String
        get() = prefs.getString(KEY_MAC, "") ?: ""
        set(v) = prefs.edit().putString(KEY_MAC, v.trim().uppercase()).apply()

    /** Friendly name of the paired cube, display only. */
    var deviceName: String
        get() = prefs.getString(KEY_NAME, "") ?: ""
        set(v) = prefs.edit().putString(KEY_NAME, v.trim().take(24)).apply()

    /**
     * The paired cube's stable 8-byte nodeId (16 hex chars), "" when unknown.
     * Not used in any key: it identifies the cube in plaintext control frames
     * (a DIAL request), where the rotating BLE address cannot.
     */
    var deviceNodeHex: String
        get() = prefs.getString(KEY_NODE, "") ?: ""
        set(v) = prefs.edit().putString(KEY_NODE, normalizeHex(v, 16)).apply()

    /** Speak replies on the cube (the text bubbles are shown either way). */
    var ttsReplies: Boolean
        get() = prefs.getBoolean(KEY_TTS, true)
        set(v) = prefs.edit().putBoolean(KEY_TTS, v).apply()

    var lastState: String
        get() = prefs.getString(KEY_STATE, "") ?: ""
        set(v) = prefs.edit().putString(KEY_STATE, v.take(120)).apply()

    /** True while a real cube is connected over GATT (drives the bar badge). */
    var connected: Boolean
        get() = prefs.getBoolean(KEY_CONNECTED, false)
        set(v) = prefs.edit().putBoolean(KEY_CONNECTED, v).apply()

    /** Epoch millis when the current connection came up; 0 when not connected. */
    var connectedAtMs: Long
        get() = prefs.getLong(KEY_CONNECTED_AT, 0L)
        set(v) = prefs.edit().putLong(KEY_CONNECTED_AT, v).apply()

    /** True while the bridge is waiting to pair with a cube. */
    var pairing: Boolean
        get() = prefs.getBoolean(KEY_PAIRING, false)
        set(v) = prefs.edit().putBoolean(KEY_PAIRING, v).apply()

    /** Key-schedule version: 3 authenticates with stable IDs only (v2 and
     *  below mixed rotating BLE addresses into the handshake, so a bond
     *  could never verify after either side rotated). */
    var pairV: Int
        get() = prefs.getInt(KEY_PAIRV, 0)
        set(v) = prefs.edit().putInt(KEY_PAIRV, v).apply()

    /** The six-digit SAS to show while pairing; -1 when none. */
    var sas: Int
        get() = prefs.getInt(KEY_SAS, -1)
        set(v) = prefs.edit().putInt(KEY_SAS, v).apply()

    /**
     * True when the cube's key half is in and the app is waiting for the code
     * printed on the cube. The expected code is NEVER shown here: the whole
     * point is that a person at the cube types what they see there.
     */
    var awaitCode: Boolean
        get() = prefs.getBoolean(KEY_AWAIT_CODE, false)
        set(v) = prefs.edit().putBoolean(KEY_AWAIT_CODE, v).apply()

    /**
     * Speech-to-text engine for the cube dialog: "vosk" (on-device, offline,
     * default) or "cloud" (the companion endpoint's `/audio/transcriptions`).
     */
    var sttEngine: String
        get() = prefs.getString(KEY_STT, STT_VOSK) ?: STT_VOSK
        set(v) = prefs.edit().putString(KEY_STT, if (v == STT_CLOUD) STT_CLOUD else STT_VOSK).apply()

    /** Which offline model the Vosk engine uses (see CoPhoneAsr.MODELS). */
    var sttModel: String
        get() = prefs.getString(KEY_STT_MODEL, "ru") ?: "ru"
        set(v) = prefs.edit().putString(KEY_STT_MODEL, v.trim().ifEmpty { "ru" }).apply()

    /**
     * Text-to-speech engine for the cube dialog: "system" (the phone's TTS,
     * default) or "cloud" (the endpoint's `/audio/speech`).
     */
    var ttsEngine: String
        get() = prefs.getString(KEY_TTS, TTS_SYSTEM) ?: TTS_SYSTEM
        set(v) = prefs.edit().putString(KEY_TTS, if (v == TTS_CLOUD) TTS_CLOUD else TTS_SYSTEM).apply()

    /** System TTS voice name ("" = engine default for ru-RU). */
    var ttsVoice: String
        get() = prefs.getString(KEY_TTS_VOICE, "") ?: ""
        set(v) = prefs.edit().putString(KEY_TTS_VOICE, v.trim()).apply()

    /** System TTS rate, 0.5–1.5 (1.0 = normal). */
    var ttsRate: Float
        get() = prefs.getFloat(KEY_TTS_RATE, 1.0f).coerceIn(0.5f, 1.5f)
        set(v) = prefs.edit().putFloat(KEY_TTS_RATE, v.coerceIn(0.5f, 1.5f)).apply()

    fun forget() {
        prefs.edit()
            .remove(KEY_PAIRKEY).remove(KEY_PHONEID).remove(KEY_MAC).remove(KEY_NAME)
            .remove(KEY_NODE)
            .putBoolean(KEY_PAIRING, false).putInt(KEY_SAS, -1).putInt(KEY_PAIRV, 0)
            .putBoolean(KEY_AWAIT_CODE, false)
            // The status line and the "connected" badge describe the bond that
            // was just dropped: leaving them behind shows a link that no
            // longer exists, and the settings screen keeps reporting it.
            .putString(KEY_STATE, "")
            .putBoolean(KEY_CONNECTED, false)
            .putLong(KEY_CONNECTED_AT, 0L)
            .apply()
    }

    /** Wipe everything the UI derives from a bond (status + badge). */
    fun clearState() {
        prefs.edit().putString(KEY_STATE, "").putBoolean(KEY_CONNECTED, false).apply()
    }

    companion object {
        private const val PREFS = "cophone"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PAIRKEY = "pair_key"
        private const val KEY_PHONEID = "phone_id"
        private const val KEY_MAC = "device_mac"
        private const val KEY_NAME = "device_name"
        private const val KEY_NODE = "device_node"
        private const val KEY_STATE = "last_state"
        private const val KEY_CONNECTED = "connected"
        private const val KEY_CONNECTED_AT = "connected_at"
        private const val KEY_PAIRING = "pairing"
        private const val KEY_SAS = "sas"
        private const val KEY_AWAIT_CODE = "await_code"
        private const val KEY_PAIRV = "pair_v"
        private const val KEY_STT = "stt_engine"
        private const val KEY_STT_MODEL = "stt_model"
        private const val KEY_TTS = "tts_engine"
        private const val KEY_TTS_VOICE = "tts_voice"
        private const val KEY_TTS_RATE = "tts_rate"

        const val STT_VOSK = "vosk"
        const val STT_CLOUD = "cloud"
        const val TTS_SYSTEM = "system"
        const val TTS_CLOUD = "cloud"

        private fun normalizeHex(v: String, len: Int): String {
            val t = v.trim().lowercase().filter { it in "0123456789abcdef" }
            return if (t.length == len) t else ""
        }
    }
}
