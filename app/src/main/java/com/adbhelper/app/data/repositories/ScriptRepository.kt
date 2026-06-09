package com.adbhelper.app.data.repositories

import com.adbhelper.app.core.script.AdbScript
import com.adbhelper.app.data.ScriptDao
import com.adbhelper.app.data.models.ScriptEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScriptRepository @Inject constructor(
    private val scriptDao: ScriptDao,
    private val json: Json
) {
    fun getAllScripts(): Flow<List<AdbScript>> {
        return scriptDao.getAllScripts().map { entities ->
            entities.map { it.toAdbScript() }
        }
    }

    fun getScriptsByCategory(category: String): Flow<List<AdbScript>> {
        return scriptDao.getScriptsByCategory(category).map { entities ->
            entities.map { it.toAdbScript() }
        }
    }

    suspend fun getScriptById(id: String): AdbScript? {
        return scriptDao.getScriptById(id)?.toAdbScript()
    }

    suspend fun saveScript(script: AdbScript) {
        val entity = ScriptEntity(
            id = script.id,
            name = script.name,
            description = script.description,
            category = script.category,
            commandsJson = json.encodeToString(script.commands),
            variablesJson = json.encodeToString(script.variables),
            createdAt = script.createdAt,
            updatedAt = System.currentTimeMillis()
        )
        scriptDao.insertScript(entity)
    }

    suspend fun deleteScript(id: String) {
        scriptDao.deleteScriptById(id)
    }

    private fun ScriptEntity.toAdbScript(): AdbScript {
        return AdbScript(
            id = id,
            name = name,
            description = description,
            category = category,
            commands = try {
                json.decodeFromString(commandsJson)
            } catch (_: Exception) {
                emptyList()
            },
            variables = try {
                json.decodeFromString(variablesJson)
            } catch (_: Exception) {
                emptyMap()
            },
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }
}
