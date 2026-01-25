package uk.xmlangel.googledrivesync.util

import org.junit.Assert.assertEquals
import org.junit.Test

class MimeTypeUtilTest {

    @Test
    fun `getMimeType returns correct image types`() {
        println("Testing common image formats...")
        assertEquals("image/jpeg", MimeTypeUtil.getMimeType("photo.jpg"))
        println("  Verified photo.jpg -> image/jpeg")
        assertEquals("image/jpeg", MimeTypeUtil.getMimeType("photo.JPEG"))
        println("  Verified photo.JPEG -> image/jpeg")
        assertEquals("image/png", MimeTypeUtil.getMimeType("image.png"))
        println("  Verified image.png -> image/png")
        assertEquals("image/gif", MimeTypeUtil.getMimeType("animation.gif"))
        println("  Verified animation.gif -> image/gif")
    }

    @Test
    fun `getMimeType returns correct document types`() {
        println("Testing common document formats...")
        assertEquals("application/pdf", MimeTypeUtil.getMimeType("document.pdf"))
        println("  Verified document.pdf -> application/pdf")
        assertEquals("application/msword", MimeTypeUtil.getMimeType("notes.doc"))
        println("  Verified notes.doc -> application/msword")
        assertEquals("application/msword", MimeTypeUtil.getMimeType("notes.docx"))
        println("  Verified notes.docx -> application/msword")
        assertEquals("application/vnd.ms-excel", MimeTypeUtil.getMimeType("data.xls"))
        println("  Verified data.xls -> application/vnd.ms-excel")
        assertEquals("application/vnd.ms-excel", MimeTypeUtil.getMimeType("data.xlsx"))
        println("  Verified data.xlsx -> application/vnd.ms-excel")
        assertEquals("text/plain", MimeTypeUtil.getMimeType("readme.txt"))
        println("  Verified readme.txt -> text/plain")
    }

    @Test
    fun `getMimeType returns correct media types`() {
        println("Testing basic media formats...")
        assertEquals("audio/mpeg", MimeTypeUtil.getMimeType("song.mp3"))
        println("  Verified song.mp3 -> audio/mpeg")
        assertEquals("video/mp4", MimeTypeUtil.getMimeType("movie.mp4"))
        println("  Verified movie.mp4 -> video/mp4")
    }

    @Test
    fun `getMimeType returns correct obsidian and modern types`() {
        println("Testing Obsidian and modern web formats...")
        
        // Obsidian specific
        assertEquals("text/markdown", MimeTypeUtil.getMimeType("note.md"))
        println("  Verified note.md -> text/markdown (Obsidian)")
        assertEquals("application/json", MimeTypeUtil.getMimeType("board.canvas"))
        println("  Verified board.canvas -> application/json (Obsidian)")

        // Modern web types
        assertEquals("image/svg+xml", MimeTypeUtil.getMimeType("icon.svg"))
        println("  Verified icon.svg -> image/svg+xml")
        assertEquals("image/webp", MimeTypeUtil.getMimeType("image.webp"))
        println("  Verified image.webp -> image/webp")

        // Additional Audio
        assertEquals("audio/wav", MimeTypeUtil.getMimeType("sound.wav"))
        println("  Verified sound.wav -> audio/wav")
        assertEquals("audio/x-m4a", MimeTypeUtil.getMimeType("audio.m4a"))
        println("  Verified audio.m4a -> audio/x-m4a")
        assertEquals("audio/ogg", MimeTypeUtil.getMimeType("music.ogg"))
        println("  Verified music.ogg -> audio/ogg")
        assertEquals("audio/flac", MimeTypeUtil.getMimeType("highres.flac"))
        println("  Verified highres.flac -> audio/flac")

        // Additional Video
        assertEquals("video/webm", MimeTypeUtil.getMimeType("video.webm"))
        println("  Verified video.webm -> video/webm")
        assertEquals("video/quicktime", MimeTypeUtil.getMimeType("movie.mov"))
        println("  Verified movie.mov -> video/quicktime")
        assertEquals("video/x-matroska", MimeTypeUtil.getMimeType("hd.mkv"))
        println("  Verified hd.mkv -> video/x-matroska")
    }


    @Test
    fun `getMimeType returns default type for unknown extensions`() {
        println("Testing unknown extensions and fallback...")
        assertEquals("application/octet-stream", MimeTypeUtil.getMimeType("file.unknown"))
        println("  Verified file.unknown -> application/octet-stream")
        assertEquals("application/octet-stream", MimeTypeUtil.getMimeType("no_extension"))
        println("  Verified no_extension -> application/octet-stream")
    }
}
