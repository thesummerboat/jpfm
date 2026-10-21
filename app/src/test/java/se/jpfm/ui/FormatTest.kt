package se.jpfm.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormatTest {
    @Test
    fun durations() {
        assertEquals("0:00", Format.duration(0))
        assertEquals("4:05", Format.duration(245_000))
        assertEquals("1:02:05", Format.duration(3_725_000))
        assertEquals("47 min", Format.longDuration(47 * 60_000L))
        assertEquals("2 h 13 min", Format.longDuration((2 * 60 + 13) * 60_000L))
        assertEquals("1 h", Format.longDuration(60 * 60_000L))
    }

    @Test
    fun counts() {
        assertEquals("1 låt", Format.tracks(1))
        assertEquals("12 låtar", Format.tracks(12))
    }

    @Test
    fun greetings() {
        assertEquals("God morgon", Format.greeting(7))
        assertEquals("God eftermiddag", Format.greeting(14))
        assertEquals("God kväll", Format.greeting(20))
        assertEquals("God natt", Format.greeting(2))
    }

    @Test
    fun quality() {
        assertEquals("FLAC · 980 kbps", Format.quality("flac", 980))
        assertEquals("MP3", Format.quality("mp3", null))
        assertNull(Format.quality(null, 320))
    }
}
