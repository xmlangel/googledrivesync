package uk.xmlangel.googledrivesync.util

import org.junit.Assert.assertEquals
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
    fun `sanitizeFileName handles real cases from log`() {
        val case1 = "책-연관-기능 개선과 구조 개선에 대한 PR이 구분될 수 있는가?.md"
        val expected1 = "책-연관-기능 개선과 구조 개선에 대한 PR이 구분될 수 있는가_.md"
        assertEquals(expected1, FileUtils.sanitizeFileName(case1))
        
        val case2 = "책-연관-<Tidy First?> 번역이 옵션 개념을 가르치다.md"
        val expected2 = "책-연관-_Tidy First__ 번역이 옵션 개념을 가르치다.md"
        assertEquals(expected2, FileUtils.sanitizeFileName(case2))
    }
}
