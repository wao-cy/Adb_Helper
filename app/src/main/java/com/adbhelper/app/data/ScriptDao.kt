package com.adbhelper.app.data

import androidx.room.*
import com.adbhelper.app.data.models.ExecutionHistoryEntity
import com.adbhelper.app.data.models.ScriptEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScriptDao {
    @Query("SELECT * FROM scripts ORDER BY updatedAt DESC")
    fun getAllScripts(): Flow<List<ScriptEntity>>

    @Query("SELECT * FROM scripts WHERE category = :category ORDER BY updatedAt DESC")
    fun getScriptsByCategory(category: String): Flow<List<ScriptEntity>>

    @Query("SELECT * FROM scripts WHERE id = :id")
    suspend fun getScriptById(id: String): ScriptEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScript(script: ScriptEntity)

    @Update
    suspend fun updateScript(script: ScriptEntity)

    @Delete
    suspend fun deleteScript(script: ScriptEntity)

    @Query("DELETE FROM scripts WHERE id = :id")
    suspend fun deleteScriptById(id: String)

    @Query("SELECT * FROM execution_history ORDER BY executedAt DESC LIMIT :limit")
    fun getRecentExecutions(limit: Int = 50): Flow<List<ExecutionHistoryEntity>>

    @Query("SELECT * FROM execution_history WHERE scriptId = :scriptId ORDER BY executedAt DESC LIMIT :limit")
    fun getExecutionHistoryForScript(scriptId: String, limit: Int = 20): Flow<List<ExecutionHistoryEntity>>

    @Insert
    suspend fun insertExecutionHistory(history: ExecutionHistoryEntity)

    @Query("DELETE FROM execution_history WHERE executedAt < :timestamp")
    suspend fun deleteOldHistory(timestamp: Long)
}
