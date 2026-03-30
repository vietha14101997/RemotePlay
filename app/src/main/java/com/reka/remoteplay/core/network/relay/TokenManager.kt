package com.reka.remoteplay.core.network.relay

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TokenManager @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("relay_tokens", Context.MODE_PRIVATE)

    private val _isLoggedIn = MutableStateFlow(prefs.getString(KEY_ACCESS_TOKEN, null) != null)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS_TOKEN, null)
        private set(value) {
            prefs.edit { putString(KEY_ACCESS_TOKEN, value) }
            _isLoggedIn.value = value != null
        }

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH_TOKEN, null)
        private set(value) { prefs.edit { putString(KEY_REFRESH_TOKEN, value) } }

    var relayUrl: String?
        get() = prefs.getString(KEY_RELAY_URL, null)
        set(value) { prefs.edit { putString(KEY_RELAY_URL, value) } }

    fun saveTokens(access: String, refresh: String) {
        accessToken = access
        refreshToken = refresh
    }

    fun clearTokens() {
        prefs.edit { clear() }
        _isLoggedIn.value = false
    }

    fun authHeader(): String = "Bearer $accessToken"

    companion object {
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_RELAY_URL = "relay_url"
    }
}
