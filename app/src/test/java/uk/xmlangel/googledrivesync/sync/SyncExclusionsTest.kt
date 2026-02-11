package uk.xmlangel.googledrivesync.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncExclusionsTest {

    @Test
    fun `default exclusion contains workspace json`() {
        assertTrue(SyncExclusions.isExcludedRelativePath(".obsidian/workspace.json"))
        assertFalse(SyncExclusions.isExcludedRelativePath(".obsidian/workspace-mobile.json"))
    }

    @Test
    fun `user file exclusion matches exact file only`() {
        val userRules = setOf("file:Attachments/sample.png")
        assertTrue(SyncExclusions.isExcludedRelativePath("Attachments/sample.png", userRules))
        assertFalse(SyncExclusions.isExcludedRelativePath("Attachments/sub/sample.png", userRules))
    }

    @Test
    fun `user directory exclusion matches descendants`() {
        val userRules = setOf("directory:Attachments/People")
        assertTrue(SyncExclusions.isExcludedRelativePath("Attachments/People", userRules))
        assertTrue(SyncExclusions.isExcludedRelativePath("Attachments/People/a.jpg", userRules))
        assertTrue(SyncExclusions.isExcludedRelativePath("Attachments/People/inner/b.jpg", userRules))
        assertFalse(SyncExclusions.isExcludedRelativePath("Attachments/Other/c.jpg", userRules))
    }

    @Test
    fun `user pattern exclusion supports glob`() {
        val userRules = setOf("pattern:*.tmp", "pattern:.obsidian/*.bak")
        assertTrue(SyncExclusions.isExcludedRelativePath("cache.tmp", userRules))
        assertTrue(SyncExclusions.isExcludedRelativePath(".obsidian/state.bak", userRules))
        assertFalse(SyncExclusions.isExcludedRelativePath(".obsidian/state.json", userRules))
    }

    @Test
    fun `absolute path exclusion works with user rules`() {
        val userRules = setOf("directory:Template")
        assertTrue(
            SyncExclusions.isExcludedAbsolutePath(
                "/storage/emulated/0/Obsidian/Template/Default.md",
                userRules
            )
        )
        assertFalse(
            SyncExclusions.isExcludedAbsolutePath(
                "/storage/emulated/0/Obsidian/Resources/Default.md",
                userRules
            )
        )
    }

    @Test
    fun `invalid user rules are ignored`() {
        val userRules = setOf("invalid_token", "file:")
        assertFalse(SyncExclusions.isExcludedRelativePath("a.txt", userRules))
    }
}

