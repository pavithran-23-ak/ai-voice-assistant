package com.example.myaiassistant

import android.content.Context
import androidx.room.*
import kotlinx.serialization.Serializable

@Entity(tableName = "notifications")
data class NotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val sourceApp: String,
    val sender: String,
    val body: String,
    val timestamp: Long,
    val isProcessed: Boolean = false
)

@Dao
interface NotificationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotification(notification: NotificationEntity)

    @Query("SELECT * FROM notifications WHERE isProcessed = 0 ORDER BY timestamp DESC")
    suspend fun getUnprocessedNotifications(): List<NotificationEntity>

    @Query("UPDATE notifications SET isProcessed = 1 WHERE id IN (:ids)")
    suspend fun markAsProcessed(ids: List<Long>)
}

@Database(entities = [NotificationEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun notificationDao(): NotificationDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ai_assistant_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// AI Serialization Schemas
@Serializable
data class NotificationPromptItem(
    val id: Long, 
    val source: String, 
    val sender: String, 
    val message: String
)

@Serializable
data class TriagedItem(
    val id: Long, 
    val category: String, 
    val summary: String
)

@Serializable
data class TriageResponse(
    val urgentCount: Int, 
    val summaryOverview: String, 
    val items: List<TriagedItem>
)

@Serializable
data class VoiceAction(
    val action: String, // "CHECK_NOTIFICATIONS", "SEND_WHATSAPP", "UNKNOWN"
    val recipient: String? = null,
    val message: String? = null
)
