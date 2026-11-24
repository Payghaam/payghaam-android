package com.payghaam.sdk

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException

private val Context.payghaamDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "payghaam_datastore",
    produceMigrations = { context ->
        listOf(
            SharedPreferencesMigration(context, "payghaam_sdk"),
            SharedPreferencesMigration(context, "payghaam_prefs"),
            SharedPreferencesMigration(context, "payghaam_queue")
        )
    }
)

/**
 * Thread-safe, non-blocking persistence backed by Jetpack DataStore Preferences.
 * Eliminates Main-Thread disk I/O ANRs while providing seamless migration from
 * legacy SharedPreferences (matching Braze's zero-ANR DataStore architecture).
 */
internal class PayghaamStore(private val context: Context) {

    private val dataStore = context.payghaamDataStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // In-memory cache for fast, non-blocking synchronous reads
    @Volatile
    private var cachedExternalId: String? = null

    @Volatile
    private var cachedAnonymousId: String? = null

    @Volatile
    private var cachedLanguage: String? = null

    @Volatile
    private var cachedApiKey: String? = null

    @Volatile
    private var cachedBaseUrl: String? = null

    @Volatile
    private var cachedQueue: String? = null

    companion object {
        val KEY_EXTERNAL_ID = stringPreferencesKey("external_id")
        val KEY_ANONYMOUS_ID = stringPreferencesKey("anonymous_id")
        val KEY_LANGUAGE = stringPreferencesKey("language")
        val KEY_CFG_API_KEY = stringPreferencesKey("cfg_api_key")
        val KEY_CFG_BASE_URL = stringPreferencesKey("cfg_base_url")
        val KEY_QUEUE = stringPreferencesKey("queue_v1")
    }

    init {
        // Hydrate in-memory cache asynchronously and observe continuous updates
        scope.launch {
            dataStore.data
                .catch { exception ->
                    if (exception is IOException) {
                        emit(emptyPreferences())
                    } else {
                        throw exception
                    }
                }
                .collect { prefs ->
                    cachedExternalId = prefs[KEY_EXTERNAL_ID]
                    cachedAnonymousId = prefs[KEY_ANONYMOUS_ID]
                    cachedLanguage = prefs[KEY_LANGUAGE]
                    cachedApiKey = prefs[KEY_CFG_API_KEY]
                    cachedBaseUrl = prefs[KEY_CFG_BASE_URL]
                    cachedQueue = prefs[KEY_QUEUE]
                }
        }
    }

    // ── External ID ──────────────────────────────────────────────────────────

    fun getExternalIdSync(): String? {
        return cachedExternalId ?: runBlocking(Dispatchers.IO) {
            runCatching {
                dataStore.data.first()[KEY_EXTERNAL_ID]
            }.getOrNull().also { cachedExternalId = it }
        }
    }

    suspend fun setExternalId(id: String) {
        cachedExternalId = id
        dataStore.edit { prefs ->
            prefs[KEY_EXTERNAL_ID] = id
        }
    }

    suspend fun clearExternalId() {
        cachedExternalId = null
        dataStore.edit { prefs ->
            prefs.remove(KEY_EXTERNAL_ID)
        }
    }

    // ── Anonymous ID ─────────────────────────────────────────────────────────

    fun getAnonymousIdSync(): String {
        cachedAnonymousId?.let { if (it.isNotEmpty()) return it }
        val stored = runBlocking(Dispatchers.IO) {
            runCatching { dataStore.data.first()[KEY_ANONYMOUS_ID] }.getOrNull()
        }
        if (!stored.isNullOrEmpty()) {
            cachedAnonymousId = stored
            return stored
        }
        val newId = java.util.UUID.randomUUID().toString().lowercase()
        cachedAnonymousId = newId
        scope.launch {
            dataStore.edit { prefs -> prefs[KEY_ANONYMOUS_ID] = newId }
        }
        return newId
    }

    suspend fun resetAnonymousId(): String {
        val newId = java.util.UUID.randomUUID().toString().lowercase()
        cachedAnonymousId = newId
        dataStore.edit { prefs -> prefs[KEY_ANONYMOUS_ID] = newId }
        return newId
    }

    // ── Language ─────────────────────────────────────────────────────────────

    fun getLanguageSync(): String? {
        return cachedLanguage ?: runBlocking(Dispatchers.IO) {
            runCatching { dataStore.data.first()[KEY_LANGUAGE] }.getOrNull().also { cachedLanguage = it }
        }
    }

    suspend fun setLanguage(lang: String?) {
        cachedLanguage = lang
        dataStore.edit { prefs ->
            if (lang != null) {
                prefs[KEY_LANGUAGE] = lang
            } else {
                prefs.remove(KEY_LANGUAGE)
            }
        }
    }

    // ── Bridge Config ────────────────────────────────────────────────────────

    fun getBridgeConfigSync(): Pair<String?, String?> {
        val key = cachedApiKey ?: runBlocking(Dispatchers.IO) {
            runCatching { dataStore.data.first()[KEY_CFG_API_KEY] }.getOrNull()
        }.also { cachedApiKey = it }

        val base = cachedBaseUrl ?: runBlocking(Dispatchers.IO) {
            runCatching { dataStore.data.first()[KEY_CFG_BASE_URL] }.getOrNull()
        }.also { cachedBaseUrl = it }

        return Pair(key, base)
    }

    suspend fun setBridgeConfig(
        apiKey: String,
        baseUrl: String,
        externalId: String? = null,
        anonymousId: String? = null,
        language: String? = null,
    ) {
        cachedApiKey = apiKey
        cachedBaseUrl = baseUrl
        if (externalId != null) cachedExternalId = externalId
        if (anonymousId != null) cachedAnonymousId = anonymousId
        if (language != null) cachedLanguage = language

        dataStore.edit { prefs ->
            prefs[KEY_CFG_API_KEY] = apiKey
            prefs[KEY_CFG_BASE_URL] = baseUrl
            if (externalId != null) {
                prefs[KEY_EXTERNAL_ID] = externalId
            }
            if (anonymousId != null) {
                prefs[KEY_ANONYMOUS_ID] = anonymousId
            }
            if (language != null) {
                prefs[KEY_LANGUAGE] = language
            }
        }
    }

    // ── Offline Queue ────────────────────────────────────────────────────────

    fun getQueueRawSync(): String? {
        return cachedQueue ?: runBlocking(Dispatchers.IO) {
            runCatching {
                dataStore.data.first()[KEY_QUEUE]
            }.getOrNull().also { cachedQueue = it }
        }
    }

    suspend fun setQueueRaw(raw: String) {
        cachedQueue = raw
        dataStore.edit { prefs ->
            prefs[KEY_QUEUE] = raw
        }
    }
}
