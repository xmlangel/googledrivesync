package uk.xmlangel.googledrivesync.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleAccountTest {

    @Test
    fun `test GoogleAccount data class properties`() {
        val account = GoogleAccount(
            id = "test-id",
            email = "test@example.com",
            displayName = "Test User",
            photoUrl = "https://example.com/photo.jpg",
            lastSyncedAt = 123456789L,
            isActive = true
        )

        assertEquals("test-id", account.id)
        assertEquals("test@example.com", account.email)
        assertEquals("Test User", account.displayName)
        assertEquals("https://example.com/photo.jpg", account.photoUrl)
        assertEquals(123456789L, account.lastSyncedAt)
        assertTrue(account.isActive)
    }

    @Test
    fun `test GoogleAccount equality`() {
        val account1 = GoogleAccount("id1", "email1", "name1", "url1")
        val account2 = GoogleAccount("id1", "email1", "name1", "url1")
        val account3 = GoogleAccount("id2", "email1", "name1", "url1")

        assertEquals(account1, account2)
        assertNotEquals(account1, account3)
        assertEquals(account1.hashCode(), account2.hashCode())
    }

    @Test
    fun `test GoogleAccount toString`() {
        val account = GoogleAccount("id1", "email1", "name1", "url1")
        val expectedString = "GoogleAccount(id=id1, email=email1, displayName=name1, photoUrl=url1, lastSyncedAt=null, isActive=false)"
        assertEquals(expectedString, account.toString())
    }

    @Test
    fun `test SyncStatus enum`() {
        assertEquals(6, SyncStatus.values().size)
        assertEquals(SyncStatus.SYNCED, SyncStatus.valueOf("SYNCED"))
        assertEquals(SyncStatus.PENDING_UPLOAD, SyncStatus.valueOf("PENDING_UPLOAD"))
        assertEquals(SyncStatus.PENDING_DOWNLOAD, SyncStatus.valueOf("PENDING_DOWNLOAD"))
        assertEquals(SyncStatus.SYNCING, SyncStatus.valueOf("SYNCING"))
        assertEquals(SyncStatus.CONFLICT, SyncStatus.valueOf("CONFLICT"))
        assertEquals(SyncStatus.ERROR, SyncStatus.valueOf("ERROR"))
    }

    @Test
    fun `test SyncDirection enum`() {
        assertEquals(3, SyncDirection.values().size)
        assertEquals(SyncDirection.BIDIRECTIONAL, SyncDirection.valueOf("BIDIRECTIONAL"))
        assertEquals(SyncDirection.UPLOAD_ONLY, SyncDirection.valueOf("UPLOAD_ONLY"))
        assertEquals(SyncDirection.DOWNLOAD_ONLY, SyncDirection.valueOf("DOWNLOAD_ONLY"))
    }

    @Test
    fun `test AppVersion data class`() {
        val version = AppVersion("1.0.x", 123L)
        assertEquals("1.0.x", version.versionName)
        assertEquals(123L, version.versionCode)
        
        val version2 = AppVersion("1.0.x", 123L)
        assertEquals(version, version2)
        assertTrue(version.toString().contains("1.0.x"))
    }
}
