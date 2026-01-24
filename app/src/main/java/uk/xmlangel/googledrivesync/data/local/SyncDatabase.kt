package uk.xmlangel.googledrivesync.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import uk.xmlangel.googledrivesync.data.model.SyncDirection
import uk.xmlangel.googledrivesync.data.model.SyncStatus

@Database(
    entities = [
        SyncFolderEntity::class,
        SyncItemEntity::class,
        SyncHistoryEntity::class
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class SyncDatabase : RoomDatabase() {
    
    abstract fun syncFolderDao(): SyncFolderDao
    abstract fun syncItemDao(): SyncItemDao
    abstract fun syncHistoryDao(): SyncHistoryDao
    
    companion object {
        @Volatile
        private var INSTANCE: SyncDatabase? = null
        
        fun getInstance(context: Context): SyncDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SyncDatabase::class.java,
                    "sync_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class Converters {
    @TypeConverter
    fun fromSyncStatus(status: SyncStatus): String = status.name
    
    @TypeConverter
    fun toSyncStatus(value: String): SyncStatus = SyncStatus.valueOf(value)
    
    @TypeConverter
    fun fromSyncDirection(direction: SyncDirection): String = direction.name
    
    @TypeConverter
    fun toSyncDirection(value: String): SyncDirection = SyncDirection.valueOf(value)
}
