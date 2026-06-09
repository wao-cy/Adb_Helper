package com.adbhelper.app.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scripts")
data class ScriptEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val description: String = "",
    val category: String = "general",
    val commandsJson: String = "[]",
    val variablesJson: String = "{}",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "execution_history")
data class ExecutionHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val scriptId: String,
    val scriptName: String,
    val output: String,
    val success: Boolean,
    val startTime: Long,
    val endTime: Long,
    val executedAt: Long = System.currentTimeMillis()
)
