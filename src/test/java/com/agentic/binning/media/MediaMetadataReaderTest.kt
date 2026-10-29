package com.agentic.binning.media

import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class MediaMetadataReaderTest {
    private lateinit var saved: TimeZone

    @Before fun setUp() {
        saved = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After fun tearDown() = TimeZone.setDefault(saved)

    @Test fun exifDate_isParsedInLocalTime() {
        assertEquals(1_700_000_000_000L, MediaMetadataReader.parseExifDate("2023:11:14 22:13:20"))
    }

    @Test fun exifDate_garbageIsNull() {
        assertNull(MediaMetadataReader.parseExifDate("0000:00:00 00:00:00x"))
        assertNull(MediaMetadataReader.parseExifDate(null))
    }

    @Test fun videoDate_isParsedAsUtc() {
        assertEquals(1_700_000_000_000L, MediaMetadataReader.parseVideoDate("20231114T221320.000Z"))
        assertEquals(1_700_000_000_000L, MediaMetadataReader.parseVideoDate("20231114T221320Z"))
    }

    @Test fun videoDate_beforeUnixEpochOrGarbageIsNull() {
        assertNull(MediaMetadataReader.parseVideoDate("19040101T000000.000Z"))
        assertNull(MediaMetadataReader.parseVideoDate("not a date"))
        assertNull(MediaMetadataReader.parseVideoDate(null))
    }
}
