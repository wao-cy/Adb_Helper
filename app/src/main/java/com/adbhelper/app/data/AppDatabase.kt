package com.adbhelper.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.adbhelper.app.data.models.ExecutionHistoryEntity
import com.adbhelper.app.data.models.ScriptEntity

@Database(
    entities = [ScriptEntity::class, ExecutionHistoryEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scriptDao(): ScriptDao

    companion object {
        const val DATABASE_NAME = "adb_helper_db"

        fun create(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context,
                AppDatabase::class.java,
                DATABASE_NAME
            ).build()
        }
    }
}
