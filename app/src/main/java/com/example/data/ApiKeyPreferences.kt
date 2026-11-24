package com.example.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "nabra_voice_settings")

class ApiKeyPreferences(private val context: Context) {
    private val voiceCloneKeyPref = stringPreferencesKey("voice_clone_api_key")

    val voiceCloneApiKeyFlow: Flow<String> = context.settingsDataStore.data.map { prefs ->
        val stored = prefs[voiceCloneKeyPref]?.trim().orEmpty()
        if (stored.isNotEmpty()) {
            stored
        } else {
            val buildKey = BuildConfig.ELEVENLABS_API_KEY.trim()
            if (buildKey.isNotEmpty() && buildKey != PLACEHOLDER_KEY) buildKey else ""
        }
    }

    suspend fun saveVoiceCloneApiKey(apiKey: String) {
        val cleaned = apiKey.trim()
        context.settingsDataStore.edit { prefs ->
            if (cleaned.isEmpty()) {
                prefs.remove(voiceCloneKeyPref)
            } else {
                prefs[voiceCloneKeyPref] = cleaned
            }
        }
    }

    private companion object {
        const val PLACEHOLDER_KEY = "MY_ELEVENLABS_API_KEY"
    }
}
