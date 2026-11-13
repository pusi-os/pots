package com.pusios.pots.media

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Audio editing core is pure common code (trim / speed / concat / WAV decode),
 * so every rule the editor promises is pinned here instead of trusting the UI.
 */
class AudioEditTest {

    /** Mono ramp: frame i carries sample value i (8 kHz-safe small values). */
    private fun ramp(frames: Int, from: Int = 0, sampleRate: Int = 1000): AudioPcm {
        val samples = ShortArray(frames) { (from + it).toShort() }
        return AudioPcm(samples, sampleRate, channels = 1)
    }

    // -- trim ---------------------------------------------------------------

    @Test
    fun trimExtractsSelectedRange() {
        val pcm = ramp(1000)
        val cut = pcm.trimRange(250, 750)

        assertEquals(500, cut.frameCount)
        assertEquals(250.toShort(), cut.samples[0])
        assertEquals(749.toShort(), cut.samples[499])
        assertEquals(1000, cut.sampleRate)
    }

    @Test
    fun trimClampsEndBeyondDuration() {
        val pcm = ramp(1000)
        val cut = pcm.trimRange(900, 5000)

        assertEquals(100, cut.frameCount)
        assertEquals(900.toShort(), cut.samples[0])
    }

    @Test
    fun trimRejectsEmptyOrInvertedRange() {
        val pcm = ramp(1000)
        assertFailsWith<AudioEditException.EmptyRange> { pcm.trimRange(500, 500) }
        assertFailsWith<AudioEditException.EmptyRange> { pcm.trimRange(700, 300) }
    }

    // -- speed --------------------------------------------------------------

    @Test
    fun changeSpeedTwoHalvesDurationAndKeepsContent() {
        val pcm = ramp(1000)
        val fast = pcm.changeSpeed(2f)

        assertEquals(500, fast.frameCount)
        assertEquals(500, fast.durationMs)
        // Ramp is linear, so interpolation lands exactly on source samples.
        for (i in 0 until 500) assertEquals((i * 2).toShort(), fast.samples[i])
    }

    @Test
    fun changeSpeedHalfDoublesDuration() {
        val pcm = ramp(1000)
        val slow = pcm.changeSpeed(0.5f)

        assertEquals(2000, slow.frameCount)
        assertEquals(2000, slow.durationMs)
        assertEquals(0.toShort(), slow.samples[0])
        assertEquals(500.toShort(), slow.samples[1000])
    }

    @Test
    fun changeSpeedRejectsBadFactor() {
        val pcm = ramp(10)
        assertFailsWith<AudioEditException.BadSpeed> { pcm.changeSpeed(0f) }
        assertFailsWith<AudioEditException.BadSpeed> { pcm.changeSpeed(-1f) }
        assertFailsWith<AudioEditException.BadSpeed> { pcm.changeSpeed(Float.NaN) }
    }

    @Test
    fun changeSpeedReportsMonotonicProgressUpToOne() {
        val fractions = mutableListOf<Float>()
        ramp(1000).changeSpeed(0.5f) { fraction ->
            fractions.add(fraction)
            true
        }

        assertTrue(fractions.isNotEmpty())
        assertTrue(fractions.zipWithNext().all { (a, b) -> b > a })
        assertEquals(1f, fractions.last())
    }

    @Test
    fun changeSpeedStopsWhenProgressCancels() {
        assertFailsWith<AudioEditException.Cancelled> {
            ramp(10_000).changeSpeed(0.5f) { fraction -> fraction < 0.5f }
        }
    }

    // -- concat -------------------------------------------------------------

    @Test
    fun concatKeepsOrderAndTotalDuration() {
        val a = ramp(100, from = 0)
        val b = ramp(100, from = 1000)
        val joined = concatAudio(listOf(a, b))

        assertEquals(200, joined.frameCount)
        assertEquals(200, joined.durationMs)
        assertEquals(0.toShort(), joined.samples[0])
        assertEquals(99.toShort(), joined.samples[99])
        assertEquals(1000.toShort(), joined.samples[100])
        assertEquals(1099.toShort(), joined.samples[199])
    }

    @Test
    fun concatConvertsChannelsToFirstTrack() {
        // First track is stereo → the mono second track duplicates both channels.
        val stereo = AudioPcm(ShortArray(100) { if (it % 2 == 0) 10 else 20 }, 1000, channels = 2)
        val mono = ramp(50, from = 100)
        val joined = concatAudio(listOf(stereo, mono))

        assertEquals(2, joined.channels)
        assertEquals(100, joined.frameCount)
        assertEquals(10.toShort(), joined.samples[0])
        assertEquals(20.toShort(), joined.samples[1])
        assertEquals(100.toShort(), joined.samples[50 * 2])
        assertEquals(100.toShort(), joined.samples[50 * 2 + 1])
    }

    @Test
    fun concatMixesDownWhenFirstTrackIsMono() {
        val mono = ramp(50)
        val stereo = AudioPcm(ShortArray(100) { if (it % 2 == 0) 100 else 200 }, 1000, channels = 2)
        val joined = concatAudio(listOf(mono, stereo))

        assertEquals(1, joined.channels)
        assertEquals(100, joined.frameCount)
        // Stereo averaged down to mono: (100 + 200) / 2.
        assertEquals(150.toShort(), joined.samples[50])
    }

    @Test
    fun concatConvertsSampleRateToFirstTrack() {
        val slow = ramp(100, sampleRate = 1000) // 100 ms
        val fast = ramp(400, from = 500, sampleRate = 2000) // 200 ms
        val joined = concatAudio(listOf(slow, fast))

        assertEquals(1000, joined.sampleRate)
        assertEquals(300, joined.frameCount)
        assertEquals(300, joined.durationMs)
        // Junction: last frame of track A, then resampled track B starting at 500.
        assertEquals(99.toShort(), joined.samples[99])
        assertEquals(500.toShort(), joined.samples[100])
    }

    @Test
    fun concatEmptyListThrows() {
        assertFailsWith<AudioEditException.EmptyRange> { concatAudio(emptyList()) }
    }

    @Test
    fun concatStopsWhenProgressCancels() {
        val big = listOf(ramp(5_000), ramp(5_000), ramp(5_000))
        assertFailsWith<AudioEditException.Cancelled> {
            concatAudio(big) { fraction -> fraction < 0.4f }
        }
    }

    // -- WAV decode ---------------------------------------------------------

    @Test
    fun wavRoundTripMonoAndStereo() {
        val mono = ShortArray(123) { (it * 7).toShort() }
        val decodedMono = wavToPcm(pcm16ToWav(mono, 8000, 1))!!
        assertContentEquals(mono, decodedMono.samples)
        assertEquals(8000, decodedMono.sampleRate)
        assertEquals(1, decodedMono.channels)

        val stereo = ShortArray(64) { if (it % 2 == 0) 500 else -500 }
        val decodedStereo = wavToPcm(pcm16ToWav(stereo, 44100, 2))!!
        assertContentEquals(stereo, decodedStereo.samples)
        assertEquals(2, decodedStereo.channels)
    }

    @Test
    fun wavToPcmRejectsNonWav() {
        assertNull(wavToPcm(ByteArray(64) { 0x42 }))
        assertNull(wavToPcm(ByteArray(4)))
        // MP3 frame sync is not a WAV.
        assertNull(wavToPcm(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00) + ByteArray(60)))
    }

    @Test
    fun wavToPcmRejectsUnsupportedEncoding() {
        // Canonical header but 8-bit samples: the editor only handles 16-bit PCM.
        val wav = pcm16ToWav(ShortArray(8), 8000, 1)
        wav[34] = 8 // bits per sample
        assertNull(wavToPcm(wav))
    }

    @Test
    fun wavToPcmClampsTruncatedDataChunk() {
        val wav = pcm16ToWav(ShortArray(16) { it.toShort() }, 8000, 1)
        val truncated = wav.copyOfRange(0, 44 + 10) // only 5 frames of 16 survive
        val decoded = wavToPcm(truncated)!!
        assertEquals(5, decoded.frameCount)
        assertEquals(0.toShort(), decoded.samples[0])
        assertEquals(4.toShort(), decoded.samples[4])
    }

    // -- waveform peaks ------------------------------------------------------

    @Test
    fun peaksAreBucketedAndNormalized() {
        val pcm = AudioPcm(ShortArray(400) { if (it < 200) 16384 else 0 }, 1000, channels = 1)
        val peaks = pcm.peaks(4)

        assertEquals(4, peaks.size)
        assertEquals(0.5f, peaks[0], 0.01f)
        assertEquals(0.5f, peaks[1], 0.01f)
        assertEquals(0f, peaks[2])
        assertEquals(0f, peaks[3])
    }

    @Test
    fun peaksOfSilenceAreZero() {
        assertTrue(ramp(100).let { AudioPcm(ShortArray(100), 1000, 1) }.peaks(8).all { it == 0f })
    }

    // -- errors (§8 pattern: title, human detail, a rescue) ------------------

    @Test
    fun errorsCarryRussianTitleDetailAndFix() {
        val format = AudioEditException.UnsupportedFormat("MP3 и WAV")
        assertEquals("Формат не поддерживается", format.title)
        assertTrue("MP3 и WAV" in format.detail)
        assertTrue(format.fix.isNotBlank())

        val cancelled = AudioEditException.Cancelled()
        assertTrue(cancelled.title.isNotBlank())

        val empty = AudioEditException.EmptyRange()
        assertTrue(empty.detail.isNotBlank())

        val speed = AudioEditException.BadSpeed(10f)
        assertTrue(speed.detail.isNotBlank())

        val export = AudioEditException.ExportFailed()
        assertEquals("Не удалось собрать аудио", export.title)

        for (e in listOf(format, cancelled, empty, speed, export)) {
            val message = e.userMessageRu()
            assertTrue(e.title in message)
        }
    }
}
