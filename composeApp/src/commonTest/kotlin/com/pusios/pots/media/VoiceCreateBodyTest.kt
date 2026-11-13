package com.pusios.pots.media

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The exact JSON that goes to `POST /api/media/voices` (contract line 126):
 * `name`, mandatory `consent:true`, `engine`, and the sample as a full
 * `data:<mime>;base64,…` string in `audio_base64` — local samples have no
 * public https address.
 */
class VoiceCreateBodyTest {

    @Test
    fun bodyCarriesContractFields() {
        val body = buildVoiceCreateBody(
            name = "Мой голос",
            engine = "elevenlabs",
            audioDataUri = "data:audio/wav;base64,QUJD",
        )
        val obj = body.jsonObject
        assertEquals("Мой голос", obj["name"]!!.jsonPrimitive.content)
        assertEquals(true, obj["consent"]!!.jsonPrimitive.boolean)
        assertEquals("elevenlabs", obj["engine"]!!.jsonPrimitive.content)
        assertEquals("data:audio/wav;base64,QUJD", obj["audio_base64"]!!.jsonPrimitive.content)
        // Strict contract: no unexpected top-level fields.
        assertEquals(setOf("name", "consent", "engine", "audio_base64"), obj.keys)
    }

    @Test
    fun consentIsAlwaysExplicitTrue() {
        // The UI blocks the create button until the checkbox is ticked, so the
        // request builder never models consent=false — a false would be a 422.
        val body = buildVoiceCreateBody("v", "elevenlabs", "data:audio/mp3;base64,QQ==")
        assertEquals(true, body.jsonObject["consent"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun voicesListParsesAllDocumentedShapes() {
        // The exact GET /api/media/voices shape is not live-verified (needs a
        // key), so the parser tolerates the three plausible envelopes.
        val wrapped = parseVoicesPayload("""{"voices":[{"id":"v1","name":"Мой","status":"ready"}]}""")
        assertEquals(1, wrapped.size)
        assertEquals("v1", wrapped[0].id)
        assertEquals(VoiceStatus.Ready, wrapped[0].status)

        val arr = parseVoicesPayload("""[{"voice_id":"v2","name":"X","status":"processing"}]""")
        assertEquals(1, arr.size)
        assertEquals("v2", arr[0].id)
        assertEquals(VoiceStatus.Processing, arr[0].status)

        val data = parseVoicesPayload("""{"data":[{"voiceId":"v3","status":"failed"}]}""")
        assertEquals("v3", data[0].id)
        assertEquals(VoiceStatus.Error, data[0].status)

        // Unknown status strings never crash the list.
        val unknown = parseVoicesPayload("""{"voices":[{"id":"v4","status":"weird-new-state"}]}""")
        assertEquals(VoiceStatus.Unknown, unknown[0].status)
        assertTrue(parseVoicesPayload("not json").isEmpty())
    }
}
