package com.pusios.pots.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Block 4: the video element's height comes from the frame aspect (@joni's
 * contract). The probe rides ffprobe on the desktop; the args builder and the
 * csv parser are pure common code, so the contract is tested without binaries.
 */
class VideoAspectProbeTest {

    @Test
    fun argsAskFirstVideoStreamAsCsv() {
        val args = ffprobeVideoSizeArgs("/tmp/clip.mp4")
        assertEquals("ffprobe", args.first())
        assertEquals("/tmp/clip.mp4", args.last())
        val i = args.indexOf("-select_streams")
        assertEquals("v:0", args[i + 1])
        val e = args.indexOf("-show_entries")
        assertEquals("stream=width,height", args[e + 1])
        assertTrue(args.containsAll(listOf("-v", "error", "-of", "csv=p=0")))
    }

    @Test
    fun parsesLandscapeCsv() {
        assertEquals(1280f / 720f, parseVideoSizeCsv("1280,720")!!, 0.0001f)
    }

    @Test
    fun parsesPortraitCsvWithPaddingAndExtras() {
        assertEquals(720f / 1280f, parseVideoSizeCsv(" 720,1280\n")!!, 0.0001f)
        // A trailing field (e.g. an extra column) degrades gracefully.
        assertEquals(1920f / 1080f, parseVideoSizeCsv("1920,1080,extra")!!, 0.0001f)
    }

    @Test
    fun junkCsvDegradesToNull() {
        assertNull(parseVideoSizeCsv(null))
        assertNull(parseVideoSizeCsv(""))
        assertNull(parseVideoSizeCsv("abc"))
        assertNull(parseVideoSizeCsv("1280"))
        assertNull(parseVideoSizeCsv("0,720"))
        assertNull(parseVideoSizeCsv("1280,0"))
        assertNull(parseVideoSizeCsv("-4,3"))
    }
}
