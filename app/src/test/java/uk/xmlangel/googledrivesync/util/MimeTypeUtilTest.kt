package uk.xmlangel.googledrivesync.util

import org.junit.Assert.assertEquals
import org.junit.Test

class MimeTypeUtilTest {

    @Test
    fun `getMimeType returns correct image types`() {
        assertEquals("image/jpeg", MimeTypeUtil.getMimeType("photo.jpg"))
        assertEquals("image/jpeg", MimeTypeUtil.getMimeType("photo.JPEG"))
        assertEquals("image/png", MimeTypeUtil.getMimeType("image.png"))
        assertEquals("image/gif", MimeTypeUtil.getMimeType("animation.gif"))
    }

    @Test
    fun `getMimeType returns correct document types`() {
        assertEquals("application/pdf", MimeTypeUtil.getMimeType("document.pdf"))
        assertEquals("application/msword", MimeTypeUtil.getMimeType("notes.doc"))
        assertEquals("application/msword", MimeTypeUtil.getMimeType("notes.docx"))
        assertEquals("application/vnd.ms-excel", MimeTypeUtil.getMimeType("data.xls"))
        assertEquals("application/vnd.ms-excel", MimeTypeUtil.getMimeType("data.xlsx"))
        assertEquals("text/plain", MimeTypeUtil.getMimeType("readme.txt"))
    }

    @Test
    fun `getMimeType returns correct media types`() {
        assertEquals("audio/mpeg", MimeTypeUtil.getMimeType("song.mp3"))
        assertEquals("video/mp4", MimeTypeUtil.getMimeType("movie.mp4"))
    }

    @Test
    fun `getMimeType returns default type for unknown extensions`() {
        assertEquals("application/octet-stream", MimeTypeUtil.getMimeType("file.unknown"))
        assertEquals("application/octet-stream", MimeTypeUtil.getMimeType("no_extension"))
    }
}
