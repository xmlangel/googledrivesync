package uk.xmlangel.googledrivesync.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileUtilsTest {

    @Test
    fun `sanitizeFileName replaces restricted characters with underscore`() {
        val input = "file?with*invalid:chars.txt"
        val expected = "file_with_invalid_chars.txt"
        assertEquals(expected, FileUtils.sanitizeFileName(input))
    }

    @Test
    fun `sanitizeFileName handles more restricted characters`() {
        val input = "brackets<greater>and\"quotes|pipe.pdf"
        val expected = "brackets_greater_and_quotes_pipe.pdf"
        assertEquals(expected, FileUtils.sanitizeFileName(input))
    }

    @Test
    fun `sanitizeFileName handles slashes`() {
        val input = "some/path\\file.txt"
        val expected = "some_path_file.txt"
        assertEquals(expected, FileUtils.sanitizeFileName(input))
    }

    @Test
    fun `sanitizeFileName handles blank names`() {
        assertEquals("_", FileUtils.sanitizeFileName(""))
        assertEquals("_", FileUtils.sanitizeFileName("   "))
    }
    
    @Test
    fun `calculateMd5 returns correct MD5 for a known file`() {
        val tempFile = java.io.File.createTempFile("test", ".txt")
        tempFile.writeText("hello world")
        // MD5 of "hello world" is 5eb63bbbe01eeed093cb22bb8f5acdc3
        val expected = "5eb63bbbe01eeed093cb22bb8f5acdc3"
        assertEquals(expected, FileUtils.calculateMd5(tempFile))
        tempFile.delete()
    }

    @Test
    fun `calculateMd5 returns null for non-existent file`() {
        val nonExistentFile = java.io.File("non_existent_file.txt")
        assertNull(FileUtils.calculateMd5(nonExistentFile))
    }

    @Test
    fun `calculateMd5 returns null for directory`() {
        val dir = java.io.File(System.getProperty("java.io.tmpdir"))
        assertNull(FileUtils.calculateMd5(dir))
    }

    @Test
    fun `calculateMd5 returns correct MD5 for empty file`() {
        val tempFile = java.io.File.createTempFile("empty", ".txt")
        // MD5 of empty string is d41d8cd98f00b204e9800998ecf8427e
        val expected = "d41d8cd98f00b204e9800998ecf8427e"
        assertEquals(expected, FileUtils.calculateMd5(tempFile))
        tempFile.delete()
    }

    @Test
    fun `calculateMd5 returns null on exception`() {
        val tempFile = java.io.File.createTempFile("error", ".txt")
        
        io.mockk.mockkStatic(java.security.MessageDigest::class)
        io.mockk.every { java.security.MessageDigest.getInstance("MD5") } throws RuntimeException("Simulated digest error")
        
        assertNull(FileUtils.calculateMd5(tempFile))
        
        io.mockk.unmockkStatic(java.security.MessageDigest::class)
        tempFile.delete()
    }
}
