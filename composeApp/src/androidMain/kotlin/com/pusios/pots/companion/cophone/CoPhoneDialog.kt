package com.pusios.pots.companion.cophone

import android.content.Context
import android.util.Log
import com.pusios.pots.data.Conversation
import com.pusios.pots.data.DataRepository
import com.pusios.pots.ui.chat.History
import com.pusios.pots.ui.chat.lastRenderedAssistant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue

/**
 * The voice dialog between a bonded PotsCharm cube and the Pots agent.
 *
 * Adapted from the PixelTag `CoPhoneBridge` audio core (Apache-2.0): the
 * transport, framing and the sentence-streamed, paced Opus downlink follow
 * the reference, while PixelTag's companion engine is replaced by Pots' own
 * agent pipeline — a dialog continues the currently open chat,
 * the transcribed utterance is submitted with [DataRepository.ask] and the
 * reply is read back from [DataRepository.chatHistory]. Both the user line
 * and the reply therefore land in the phone's chat screen AND on the cube's
 * transcript as bubbles.
 *
 * Loop (the cube runs its own VAD and streams the mic while the dialog is
 * open):
 *   cube "dialog" / LISTEN_START -> [startDialog] (open capture window)
 *   cube AUDIO_UP frames          -> [onAudioUp] (collect raw Opus)
 *   cube LISTEN_STOP              -> [stopDialog] -> decode -> Vosk -> turn
 *                                   (cloud transcription as a fallback)
 *   turn reply                    -> TTS -> Opus -> AUDIO_DOWN, paced + bubbles
 *                                   (cloud speech as a fallback)
 */
internal class CoPhoneDialog(
    context: Context,
    private val gatt: CoPhoneGatt,
    private val prefs: CoPhonePrefs,
    private val repository: DataRepository,
) {
    private val appCtx = context.applicationContext
    private val tts = CoPhoneTts(appCtx)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var peerMac: String? = null

    @Volatile
    private var dialogOpen = false

    private val opus = ArrayList<ByteArray>()

    /** Set when the turn is interrupted (ABORT) so the paced sender stops. */
    @Volatile
    private var abortAudio = false

    @Volatile
    private var turnRunning = false

    /** The agent turn in flight; ABORT cancels it. */
    @Volatile
    private var turnJob: Job? = null

    /** Generation of the speaker pipeline; stale loops exit on mismatch. */
    @Volatile
    private var audioGen = 0

    @Volatile
    private var speechStarted = false

    @Volatile
    private var realStarted = false

    // Sentence-streamed speech: completed sentences go here, a synth thread
    // turns each into Opus and a pace thread emits it at real time. SPEECH_END
    // is a sentinel, never null (LinkedBlockingQueue rejects null).
    private val speechQ = LinkedBlockingQueue<String>()
    private val playQ = LinkedBlockingQueue<Any>()

    private class SpeechChunk(val text: String, val packets: List<ByteArray>)

    private val playEnd = Any()
    private var synthThread: Thread? = null
    private var paceThread: Thread? = null

    // The cube's session list holds 9-char ids at most, while Pots
    // conversations are UUIDs: the wire gets short indices, mapped here.
    private val sessionIds = ArrayList<String>()

    // ---- wiring from the GATT listener -------------------------------------

    fun onConnected(mac: String) {
        peerMac = mac
        Log.i(TAG, "dialog: connected $mac")
        // Warm the offline recognizer in the background so the first utterance
        // does not sit out the ~40 MB model download.
        if (prefs.sttEngine != CoPhonePrefs.STT_CLOUD) {
            Thread {
                runCatching { CoPhoneAsr.ensure(appCtx, prefs.sttModel) }
            }.apply { isDaemon = true }.start()
        }
    }

    fun sendLook() {
        val look = repository.potsikLookFlow.value
        val outfits = listOf("NONE", "TANOOKI", "UNICORN", "TINFOIL", "SHADOW", "PLUMBER", "TALLBRO", "SPACE", "BLUEBLUR", "CAPTAIN", "WOLFPELT", "CHROMEWING", "VOIDEYE", "PARKA", "SHARK", "YZZERD", "OVER9000", "SHAMBLER", "TH3_0N3", "STRIPES")
        sendText(
            JSONObject().put("type", "look")
                .put("outfit", outfits.indexOf(look.outfit.name).coerceAtLeast(0))
                .put("shirt", look.shirtColor.index).put("shorts", look.shortsColor.index)
                .put("shoes", look.shoesColor.index).put("shade", look.shadeTint.ordinal)
                .put("glasses", if (look.glassesOn) 1 else 0).put("lashes", if (look.lashesOn) 1 else 0).toString(),
        )
    }

    fun onDisconnected(mac: String) {
        if (peerMac == mac) {
            peerMac = null
            resetAudioState()
            Log.i(TAG, "dialog: disconnected $mac")
        }
    }

    fun onAudioUp(bytes: ByteArray) {
        if (!dialogOpen) return
        synchronized(opus) {
            if (opus.size < MAX_FRAMES) opus.add(bytes)
        }
    }

    fun onTextUp(text: String) {
        try {
            val o = JSONObject(text)
            when (o.optString("type")) {
                "dialog", "wake" -> {
                    Log.i(TAG, "dialog: start from the cube")
                    sendLook()
                    // The device and phone share the currently open conversation.
                    sendHistory()
                    startDialog()
                }

                "session_new" -> {
                    if (!turnRunning) newSession()
                    startDialog()
                }

                "sessions" -> sendSessions()

                "session" -> switchSession(o.optString("id"))

                "set" -> {
                    prefs.ttsReplies = o.optInt("tts", 1) != 0
                    if (!prefs.ttsReplies) abortSpeech()
                }

                "text" -> {
                    val t = o.optString("text").trim()
                    if (t.isNotEmpty() && !turnRunning) {
                        sendStt(t)
                        runTurn(t)
                    }
                }

                else -> Unit
            }
        } catch (t: Throwable) {
            Log.w(TAG, "dialog: bad text frame", t)
        }
    }

    fun onCtrl(op: Int, rest: ByteArray) {
        when (op) {
            CoPhoneUuids.LISTEN_START -> startDialog()
            CoPhoneUuids.LISTEN_STOP -> stopDialog()
            CoPhoneUuids.ABORT -> abortSpeech()
            else -> Unit
        }
    }

    // ---- dialog -------------------------------------------------------------

    private fun newSession() {
        if (turnRunning) return
        runCatching { repository.startNewChat() }
        Log.i(TAG, "dialog: fresh chat for the voice session")
    }

    private fun startDialog() {
        if (peerMac == null) {
            Log.w(TAG, "dialog: listen with no peer")
            return
        }
        if (turnRunning && !abortAudio) {
            // The cube streams on its own VAD; while a turn is being answered
            // just drop the capture window instead of buffering a reply-to-be.
            return
        }
        abortAudio = false
        dialogOpen = true
        speechQ.clear()
        playQ.clear()
        synchronized(opus) { opus.clear() }
        // Proof of life for the cube side: the header flips to "слушаю" the
        // moment the phone is really in the loop.
        sendText(JSONObject().put("type", "state").put("state", "listen").toString())
        Log.i(TAG, "dialog: capture window open")
    }

    private fun stopDialog() {
        if (!dialogOpen) return
        dialogOpen = false
        val frames: List<ByteArray> = synchronized(opus) {
            val c = opus.toList()
            opus.clear()
            c
        }
        Log.i(TAG, "dialog: utterance end, ${frames.size} frames")
        if (frames.isEmpty()) {
            reArm()
            return
        }
        Thread {
            val heard = recognize(frames)
            if (heard.isNullOrBlank()) {
                Log.i(TAG, "asr: empty (${frames.size} frames, ${CoPhoneAsr.status})")
                reArm()
                return@Thread
            }
            Log.i(TAG, "asr: ${heard.take(60)}")
            sendStt(heard)
            runTurn(heard)
        }.apply { isDaemon = true }.start()
    }

    /** Interrupted from the cube: stop the turn and the speaker at once. */
    private fun abortSpeech() {
        abortAudio = true
        turnRunning = false
        dialogOpen = false
        audioGen++
        runCatching { turnJob?.cancel() }
        turnJob = null
        try {
            speechQ.put(SPEECH_END)
        } catch (_: InterruptedException) {
        }
        runCatching { speechQ.clear() }
        runCatching { playQ.clear() }
        reapAudioThreads()
        Log.i(TAG, "dialog: aborted")
    }

    /** Re-open the capture window after an utterance produced no speech. */
    private fun reArm() {
        if (abortAudio) return
        synchronized(opus) { opus.clear() }
        speechQ.clear()
        playQ.clear()
        dialogOpen = true
        // The cube streams the mic on its own VAD, so "listen again" is only a
        // status line, not a mic re-arm.
        sendText(JSONObject().put("type", "state").put("state", "listen").toString())
    }

    /**
     * Cube Opus -> PCM -> on-device Vosk; when the model is unavailable or
     * the local path comes back empty, fall back to the configured service's
     * `/audio/transcriptions` (WAV when the PCM decoded, Ogg when it did not).
     */
    private fun recognize(frames: List<ByteArray>): String? {
        val mid = prefs.sttModel
        val language = if (mid == "ru") "ru" else null
        if (prefs.sttEngine != CoPhonePrefs.STT_CLOUD) {
            if (!CoPhoneAsr.ready(mid)) {
                sendFiller("Гружу модель речи...")
            }
            val pcm = CoPhoneOpus.decodeAll(frames)
            if (pcm != null && pcm.isNotEmpty()) {
                Log.i(TAG, "asr: pcm=${pcm.size} rms=${rmsOf(pcm)}")
                CoPhoneAsr.ensure(appCtx, mid)
                val t = CoPhoneAsr.transcribe(pcm, mid)
                if (t != null) return t
                Log.w(TAG, "asr: vosk empty (${CoPhoneAsr.status})")
                val c = CoPhoneCloud.creds(repository)
                if (c != null) {
                    val w = CoPhoneCloud.transcribeWav(pcm, c, language)
                    if (w != null) return w
                }
            } else {
                Log.w(TAG, "asr: decode fail ${CoPhoneOpus.lastError ?: "empty"}")
                val c = CoPhoneCloud.creds(repository)
                if (c != null) {
                    val o = CoPhoneCloud.transcribeOgg(frames, c, language)
                    if (o != null) return o
                }
            }
        } else {
            val c = CoPhoneCloud.creds(repository)
            if (c != null) {
                val pcm = CoPhoneOpus.decodeAll(frames)
                val t = if (pcm != null && pcm.isNotEmpty()) {
                    CoPhoneCloud.transcribeWav(pcm, c, language)
                } else {
                    null
                }
                return t ?: CoPhoneCloud.transcribeOgg(frames, c, language)
            }
            Log.w(TAG, "asr: cloud engine selected but no service credentials")
        }
        return null
    }

    private fun rmsOf(pcm: ShortArray): Int {
        var acc = 0.0
        for (s in pcm) acc += s.toDouble() * s
        return kotlin.math.sqrt(acc / pcm.size).toInt()
    }

    // ---- the agent turn -----------------------------------------------------

    private fun runTurn(heard: String) {
        if (turnRunning) {
            sendTextReply("Занят другим разговором, повтори через пару секунд.")
            return
        }
        turnRunning = true
        sendFiller("Думаю…")
        turnJob = scope.launch {
            try {
                repository.ask(heard, emptyList())
                if (abortAudio) return@launch
                val reply = repository.chatHistory.value.lastRenderedAssistant()?.content
                if (reply.isNullOrBlank()) {
                    reArm()
                    return@launch
                }
                speak(cleanReply(reply))
                reArm()
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.w(TAG, "turn failed", t)
                sendTextReply("Ошибка: " + (t.message ?: "неизвестная").take(160))
                reArm()
            } finally {
                turnRunning = false
            }
        }
    }

    /** Strip UI-only markup the cube cannot render. */
    private fun cleanReply(text: String): String {
        var t = text
        // Drop fenced code / pots-ui blocks entirely — they are screen-only.
        t = Regex("```[\\s\\S]*?```").replace(t, " ")
        return t.trim()
    }

    // ---- sentence-streamed, paced speech -----------------------------------

    /** Synthesize + pace the whole reply (batch of sentences). */
    private fun speak(reply: String) {
        val sentences = sentences(reply)
        if (sentences.isEmpty()) return
        if (!prefs.ttsReplies) {
            // Voice replies switched off on the cube: the transcript still
            // streams sentence by sentence.
            sendTtsStart()
            for (s in sentences) sendSentenceBubble(s)
            sendTtsStop()
            return
        }
        startSpeech()
        try {
            for (s in sentences) speechQ.put(s)
        } catch (_: InterruptedException) {
        }
        finishSpeech()
    }

    private fun startSpeech() {
        abortAudio = false
        speechStarted = false
        realStarted = false
        speechQ.clear()
        playQ.clear()
        val g = ++audioGen
        val s = Thread { synthLoop(g) }
        s.isDaemon = true
        synthThread = s
        s.start()
        val p = Thread { paceLoop(g) }
        p.isDaemon = true
        paceThread = p
        p.start()
    }

    /** End the turn: drain both stages, then tell the cube to stop speaking. */
    private fun finishSpeech() {
        try {
            speechQ.put(SPEECH_END)
        } catch (_: InterruptedException) {
        }
        val st = synthThread
        val pt = paceThread
        synthThread = null
        paceThread = null
        try {
            st?.join(8000)
        } catch (_: InterruptedException) {
        }
        try {
            pt?.join(60000)
        } catch (_: InterruptedException) {
        }
        if (speechStarted) sendTtsStop()
    }

    /** Stage 1 — synthesize each sentence to Opus, staying ahead of playback. */
    private fun synthLoop(g: Int) {
        try {
            while (true) {
                if (g != audioGen || abortAudio) break
                val s = try {
                    speechQ.take()
                } catch (_: InterruptedException) {
                    break
                }
                if (g != audioGen || abortAudio) break
                if (s == SPEECH_END) break
                val packets = synthSentence(s)
                if (g != audioGen || abortAudio) break
                playQ.put(SpeechChunk(s, packets))
            }
        } catch (_: InterruptedException) {
        }
        try {
            playQ.put(playEnd)
        } catch (_: InterruptedException) {
        }
    }

    /**
     * Stage 2 — pace: emit each sentence's bubble when its audio starts, then
     * push the Opus at the packet's own TOC duration with a small lead for the
     * cube's jitter buffer. Re-anchors after a pause so a late sentence is not
     * dumped into the queue at full speed.
     */
    private fun paceLoop(g: Int) {
        val prime = 180L
        var due = 0L
        try {
            while (true) {
                if (g != audioGen || abortAudio) break
                val item = try {
                    playQ.poll(2500, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    break
                }
                if (g != audioGen || abortAudio) break
                if (item == null) continue
                if (item === playEnd) break
                val chunk = item as SpeechChunk
                if (chunk.packets.isNotEmpty() && !speechStarted) {
                    speechStarted = true
                    sendTtsStart()
                }
                sendSentenceBubble(chunk.text)
                if (chunk.packets.isNotEmpty()) realStarted = true
                due = pacePackets(chunk.packets, due, prime, g)
            }
        } catch (_: InterruptedException) {
        }
    }

    private fun pacePackets(packets: List<ByteArray>, dueIn: Long, prime: Long, g: Int): Long {
        var due = dueIn
        for (p in packets) {
            if (g != audioGen || abortAudio) break
            val now = android.os.SystemClock.elapsedRealtime()
            if (due == 0L || now > due + 120L) {
                due = now + prime
            }
            sendAudioDown(p)
            due += CoPhoneOpus.packetDurationMs(p)
            val wait = due - android.os.SystemClock.elapsedRealtime()
            if (wait > 0) {
                try {
                    Thread.sleep(wait)
                } catch (_: InterruptedException) {
                }
            }
        }
        return due
    }

    /**
     * Synthesize one sentence to Opus for the cube's 16 kHz downlink: the
     * phone's system TTS first, the configured service's `/audio/speech`
     * second (its Ogg/Opus stream demuxes to raw packets the cube decodes at
     * its own rate), system TTS last when cloud is the preferred engine.
     */
    private fun synthSentence(sentence: String): List<ByteArray> {
        val say = deviceText(sentence).take(400)
        val cloudFirst = prefs.ttsEngine == CoPhonePrefs.TTS_CLOUD
        var packets: List<ByteArray>? = null
        if (!cloudFirst) {
            packets = tts.synthesize(say, repository.getTtsSystemVoice(), repository.getTtsRate(), repository.getTtsPitch())
        }
        if (packets.isNullOrEmpty()) {
            val c = CoPhoneCloud.creds(repository)
            if (c != null) {
                val ogg = CoPhoneCloud.ttsOpus(say, c)
                if (ogg != null) {
                    // The cube's downlink slot is 160 bytes per packet.
                    packets = CoPhoneOgg.demux(ogg).filter { it.size <= 160 }
                }
            }
        }
        if (packets.isNullOrEmpty() && cloudFirst) {
            packets = tts.synthesize(say, repository.getTtsSystemVoice(), repository.getTtsRate(), repository.getTtsPitch())
        }
        Log.i(TAG, "tts: ${say.length} chars -> ${packets?.size ?: 0} pkts")
        return packets ?: emptyList()
    }

    // ---- cube-side sessions and history --------------------------------------

    private fun sendSessions() {
        try {
            repository.loadConversations()
            val active = repository.currentConversationId.value ?: ""
            val chats = repository.savedConversations.value
                .filter { it.type == Conversation.TYPE_CHAT }
                .sortedByDescending { it.updatedAt }
                .take(8)
            sessionIds.clear()
            val items = JSONArray()
            chats.forEachIndexed { i, c ->
                sessionIds.add(c.id)
                val title = c.title.ifBlank {
                    c.messages.firstOrNull { it.role == "user" }?.content?.take(32) ?: "Чат"
                }
                items.put(
                    JSONObject()
                        .put("id", i.toString())
                        .put("title", title.take(36))
                        .put("n", c.messages.size),
                )
            }
            // The active id on the wire is the short index too.
            val activeShort = sessionIds.indexOf(active).let { if (it >= 0) it.toString() else "" }
            sendText(
                JSONObject().put("type", "sessions").put("active", activeShort)
                    .put("items", items).toString(),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "sessions failed", t)
        }
    }

    private fun switchSession(shortId: String) {
        val idx = shortId.toIntOrNull() ?: return
        val real = sessionIds.getOrNull(idx) ?: return
        try {
            repository.loadConversation(real)
            sendHistory()
        } catch (t: Throwable) {
            Log.w(TAG, "switch session failed", t)
        }
    }

    /** Mirror the current chat into the cube's transcript window. */
    private fun sendHistory() {
        try {
            val items = JSONArray()
            for (h in repository.chatHistory.value.takeLast(12)) {
                if (h.role != History.Role.USER && h.role != History.Role.ASSISTANT) continue
                val t = deviceText(h.content).take(60)
                if (t.isBlank()) continue
                items.put(
                    JSONObject()
                        .put("r", if (h.role == History.Role.USER) 0 else 1)
                        .put("t", t)
                        .put("s", ""),
                )
            }
            sendText(JSONObject().put("type", "history").put("items", items).toString())
        } catch (t: Throwable) {
            Log.w(TAG, "history failed", t)
        }
    }

    // ---- frames to the cube -------------------------------------------------

    /**
     * The cube screen draws ASCII + Cyrillic only; strip markdown/emoji so the
     * transcript does not turn into boxes.
     */
    private fun deviceText(s: String): String {
        var t = s.replace(Regex("""[*_`#>~|\[\]]+"""), " ")
        val sb = StringBuilder(t.length)
        var i = 0
        while (i < t.length) {
            val cp = t.codePointAt(i)
            val n = Character.charCount(cp)
            val drop = cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF ||
                cp == 0xFE0F || cp == 0x200D || cp in 0x1F1E6..0x1F1FF ||
                cp in 0x2190..0x21FF || cp in 0x2B00..0x2BFF
            if (!drop) sb.appendCodePoint(cp)
            i += n
        }
        t = sb.toString().replace(Regex("""\s+"""), " ").trim()
        return t
    }

    private fun sendStt(text: String) {
        for (part in bubbleParts(deviceText(text))) {
            sendText(JSONObject().put("type", "stt").put("text", part).toString())
        }
    }

    private fun sendFiller(text: String) {
        sendText(JSONObject().put("type", "filler").put("text", text).toString())
    }

    private fun sendTtsStart() {
        sendText(JSONObject().put("type", "tts").put("state", "start").toString())
    }

    private fun sendTtsStop() {
        sendText(JSONObject().put("type", "tts").put("state", "stop").toString())
    }

    /** One reply chunk; long ones are split at word boundaries (bubble 128 B). */
    private fun sendSentenceBubble(s: String) {
        for (part in bubbleParts(deviceText(s))) {
            sendText(
                JSONObject().put("type", "tts").put("state", "sentence_start")
                    .put("text", part).toString(),
            )
        }
    }

    private fun bubbleParts(text: String): List<String> {
        val parts = mutableListOf<String>()
        var rest = text
        while (rest.isNotEmpty()) {
            // The device's JSON reader has a byte budget, not a character budget.
            var cut = 0
            var bytes = 0
            while (cut < rest.length) {
                val cp = rest.codePointAt(cut)
                val count = Character.charCount(cp)
                val size = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
                if (bytes + size > 60) break
                bytes += size
                cut += count
            }
            if (cut < rest.length) {
                val sp = rest.lastIndexOf(' ', cut - 1)
                if (sp > 0) cut = sp
            }
            val part = rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
            if (part.isNotEmpty()) parts.add(part)
        }
        return parts
    }

    /** A whole text-only reply: bubbles then stop. */
    private fun sendTextReply(text: String) {
        sendTtsStart()
        for (s in sentences(text)) sendSentenceBubble(s)
        sendTtsStop()
    }

    private fun sendText(json: String) {
        val mac = peerMac ?: return
        val ok = gatt.notify(mac, CoPhoneUuids.TEXT, json.toByteArray(Charsets.UTF_8))
        if (!ok) Log.w(TAG, "text notify failed: ${json.take(40)}")
    }

    private fun sendAudioDown(opusPacket: ByteArray) {
        val mac = peerMac ?: return
        gatt.notify(mac, CoPhoneUuids.AUDIO_DOWN, opusPacket)
    }

    private fun sentences(text: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (ch in text) {
            cur.append(ch)
            if ((ch == '.' || ch == '!' || ch == '?' || ch == '\n') && cur.length > 24) {
                val s = cur.toString().trim()
                if (s.isNotEmpty()) out.addAll(splitSentence(s))
                cur.clear()
            }
        }
        val tail = cur.toString().trim()
        if (tail.isNotEmpty()) out.addAll(splitSentence(tail))
        return out
    }

    private fun splitSentence(text: String): List<String> = text.chunked(400)

    // ---- teardown -----------------------------------------------------------

    private fun resetAudioState() {
        audioGen++
        dialogOpen = false
        abortAudio = false
        turnRunning = false
        runCatching { turnJob?.cancel() }
        turnJob = null
        runCatching { speechQ.clear() }
        runCatching { playQ.clear() }
        runCatching { synchronized(opus) { opus.clear() } }
        reapAudioThreads()
    }

    private fun reapAudioThreads() {
        val st = synthThread
        val pt = paceThread
        synthThread = null
        paceThread = null
        if (st == null && pt == null) return
        Thread {
            try {
                st?.join(2000)
            } catch (_: InterruptedException) {
            }
            try {
                pt?.join(2000)
            } catch (_: InterruptedException) {
            }
        }.apply { isDaemon = true }.start()
    }

    fun shutdown() {
        resetAudioState()
        peerMac = null
        tts.shutdown()
    }

    companion object {
        private const val TAG = "CoPhoneDialog"
        private const val MAX_FRAMES = 6000
        private const val SPEECH_END = "\u0000"
    }
}
