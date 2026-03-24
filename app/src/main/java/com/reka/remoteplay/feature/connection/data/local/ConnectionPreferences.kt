package com.reka.remoteplay.feature.connection.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "connection_prefs")

@JsonClass(generateAdapter = true)
data class SavedServer(
    val host: String,
    val port: Int = 8288,
    val name: String = "",
    val lastConnected: Long = 0
)

@Singleton
class ConnectionPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val serverListType = Types.newParameterizedType(List::class.java, SavedServer::class.java)
    private val serverListAdapter = moshi.adapter<List<SavedServer>>(serverListType)

    private object Keys {
        val SAVED_SERVERS = stringPreferencesKey("saved_servers")
        val LAST_HOST = stringPreferencesKey("last_host")
        val LAST_PORT = intPreferencesKey("last_port")
        val USB_MODE = booleanPreferencesKey("usb_mode")
    }

    val savedServers: Flow<List<SavedServer>> = context.dataStore.data.map { prefs ->
        val raw = prefs[Keys.SAVED_SERVERS] ?: "[]"
        try { serverListAdapter.fromJson(raw) ?: emptyList() } catch (_: Exception) { emptyList() }
    }

    val lastHost: Flow<String> = context.dataStore.data.map { it[Keys.LAST_HOST] ?: "" }
    val lastPort: Flow<Int> = context.dataStore.data.map { it[Keys.LAST_PORT] ?: 8288 }
    val usbMode: Flow<Boolean> = context.dataStore.data.map { it[Keys.USB_MODE] ?: false }

    suspend fun saveServer(server: SavedServer) {
        context.dataStore.edit { prefs ->
            val existing = prefs[Keys.SAVED_SERVERS]?.let {
                try { serverListAdapter.fromJson(it) ?: emptyList() } catch (_: Exception) { emptyList() }
            } ?: emptyList()

            val updated = (listOf(server.copy(lastConnected = System.currentTimeMillis())) +
                existing.filter { it.host != server.host || it.port != server.port })
                .take(10) // Keep max 10

            prefs[Keys.SAVED_SERVERS] = serverListAdapter.toJson(updated)
            prefs[Keys.LAST_HOST] = server.host
            prefs[Keys.LAST_PORT] = server.port
        }
    }

    suspend fun removeServer(host: String, port: Int) {
        context.dataStore.edit { prefs ->
            val existing = prefs[Keys.SAVED_SERVERS]?.let {
                try { serverListAdapter.fromJson(it) ?: emptyList() } catch (_: Exception) { emptyList() }
            } ?: emptyList()
            prefs[Keys.SAVED_SERVERS] = serverListAdapter.toJson(existing.filter { it.host != host || it.port != port })
        }
    }

    suspend fun setUsbMode(enabled: Boolean) {
        context.dataStore.edit { it[Keys.USB_MODE] = enabled }
    }
}
