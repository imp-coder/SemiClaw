package com.ai.assistance.operit.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ai.assistance.operit.data.model.FeishuConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.feishuDataStore: DataStore<Preferences> by preferencesDataStore(name = "feishu_settings")

/**
 * 飞书配置存储
 *
 * 使用 DataStore 存储飞书 API 配置信息
 */
class FeishuPreferences private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: FeishuPreferences? = null

        fun getInstance(context: Context): FeishuPreferences {
            return INSTANCE ?: synchronized(this) {
                val instance = FeishuPreferences(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }

        private val FEISHU_CONFIG_JSON = stringPreferencesKey("feishu_config_json")
        private val FEISHU_ENABLED = booleanPreferencesKey("feishu_enabled")
        private val DEFAULT_CHAT_ID = stringPreferencesKey("default_chat_id")

        const val DEFAULT_FEISHU_CONFIG_JSON = "{}"
        const val DEFAULT_DEFAULT_CHAT_ID = ""
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    /**
     * 获取飞书配置 Flow
     */
    val feishuConfigFlow: Flow<FeishuConfig> =
        context.feishuDataStore.data.map { preferences ->
            val configJson = preferences[FEISHU_CONFIG_JSON] ?: DEFAULT_FEISHU_CONFIG_JSON
            runCatching {
                json.decodeFromString<FeishuConfig>(configJson)
            }.getOrElse { FeishuConfig() }
        }

    /**
     * 获取飞书启用状态 Flow
     */
    val feishuEnabledFlow: Flow<Boolean> =
        context.feishuDataStore.data.map { preferences ->
            preferences[FEISHU_ENABLED] ?: false
        }

    /**
     * 获取默认聊天 ID Flow
     */
    val defaultChatIdFlow: Flow<String> =
        context.feishuDataStore.data.map { preferences ->
            preferences[DEFAULT_CHAT_ID] ?: DEFAULT_DEFAULT_CHAT_ID
        }

    /**
     * 保存飞书配置
     */
    suspend fun saveFeishuConfig(config: FeishuConfig) {
        context.feishuDataStore.edit { preferences ->
            preferences[FEISHU_CONFIG_JSON] = json.encodeToString(config)
        }
    }

    /**
     * 获取飞书配置
     */
    suspend fun getFeishuConfig(): FeishuConfig {
        val preferences = context.feishuDataStore.data.first()
        val configJson = preferences[FEISHU_CONFIG_JSON] ?: DEFAULT_FEISHU_CONFIG_JSON
        return runCatching {
            json.decodeFromString<FeishuConfig>(configJson)
        }.getOrElse { FeishuConfig() }
    }

    /**
     * 保存飞书启用状态
     */
    suspend fun saveFeishuEnabled(enabled: Boolean) {
        context.feishuDataStore.edit { preferences ->
            preferences[FEISHU_ENABLED] = enabled
        }
    }

    /**
     * 保存默认聊天 ID
     */
    suspend fun saveDefaultChatId(chatId: String) {
        context.feishuDataStore.edit { preferences ->
            preferences[DEFAULT_CHAT_ID] = chatId
        }
    }

    /**
     * 获取默认聊天 ID
     */
    suspend fun getDefaultChatId(): String {
        val preferences = context.feishuDataStore.data.first()
        return preferences[DEFAULT_CHAT_ID] ?: DEFAULT_DEFAULT_CHAT_ID
    }

    /**
     * 清除所有飞书配置
     */
    suspend fun clearFeishuConfig() {
        context.feishuDataStore.edit { preferences ->
            preferences[FEISHU_CONFIG_JSON] = DEFAULT_FEISHU_CONFIG_JSON
            preferences[FEISHU_ENABLED] = false
            preferences[DEFAULT_CHAT_ID] = DEFAULT_DEFAULT_CHAT_ID
        }
    }

    /**
     * 更新飞书配置的部分字段
     */
    suspend fun updateFeishuConfig(
        appId: String? = null,
        appSecret: String? = null,
        enabled: Boolean? = null
    ) {
        val currentConfig = getFeishuConfig()
        val newConfig = currentConfig.copy(
            appId = appId ?: currentConfig.appId,
            appSecret = appSecret ?: currentConfig.appSecret,
            enabled = enabled ?: currentConfig.enabled
        )
        saveFeishuConfig(newConfig)
    }
}