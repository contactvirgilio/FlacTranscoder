package com.example.flac2mp3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CueParserTest {

    private val sample = """
        REM GENRE "Jazz"
        REM DATE 1959
        PERFORMER "Miles Davis"
        TITLE "Kind of Blue"
        FILE "Kind of Blue.flac" WAVE
          TRACK 01 AUDIO
            TITLE "So What"
            PERFORMER "Miles Davis"
            INDEX 01 00:00:00
          TRACK 02 AUDIO
            TITLE "Freddie Freeloader"
            INDEX 00 09:01:50
            INDEX 01 09:03:30
          TRACK 03 AUDIO
            TITLE "Blue in Green"
            INDEX 01 18:12:00
    """.trimIndent()

    @Test
    fun parsesAlbumAndTracks() {
        val sheet = CueParser.parse(sample)
        assertEquals("Kind of Blue", sheet.album)
        assertEquals("Miles Davis", sheet.performer)
        assertEquals("1959", sheet.date)
        assertEquals("Jazz", sheet.genre)
        assertEquals(3, sheet.tracks.size)
        assertEquals("So What", sheet.tracks[0].title)
        assertEquals("Freddie Freeloader", sheet.tracks[1].title)
    }

    @Test
    fun usesIndex01AndChainsEnds() {
        val t = CueParser.parse(sample).tracks
        assertEquals(0.0, t[0].startSec, 1e-9)
        assertEquals(9 * 60 + 3 + 30 / 75.0, t[1].startSec, 1e-9)
        assertEquals(t[1].startSec, t[0].endSec!!, 1e-9)
        assertEquals(t[2].startSec, t[1].endSec!!, 1e-9)
        assertNull(t[2].endSec)
    }

    @Test
    fun rejectsMultiFileCue() {
        val text = "FILE \"a.flac\" WAVE\n TRACK 01 AUDIO\n  INDEX 01 00:00:00\nFILE \"b.flac\" WAVE\n TRACK 02 AUDIO\n  INDEX 01 00:00:00\n"
        try {
            CueParser.parse(text)
            fail("expected CueException")
        } catch (e: CueException) {
            assertTrue(e.message!!.contains("several files"))
        }
    }

    @Test
    fun rejectsEmptyCue() {
        try {
            CueParser.parse("TITLE \"x\"\n")
            fail("expected CueException")
        } catch (_: CueException) {
        }
    }

    @Test
    fun decodesLegacyEncoding() {
        // 0xE9 is "é" in Windows-1252 and is invalid UTF-8 on its own.
        assertEquals("café", CueParser.decode(byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte())))
        assertEquals("café", CueParser.decode("café".toByteArray(Charsets.UTF_8)))
    }
}
