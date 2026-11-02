package com.frianzo.app.data

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed class AuthState {
    data object Loading : AuthState()
    data object LoggedOut : AuthState()
    data class LoggedIn(val user: MeData?) : AuthState()
    data class Error(val message: String) : AuthState()
}

/** Holds login state. On start it asks /api/auth/me: the persisted cookie
 *  decides whether the session is still alive (server also revokes sessions
 *  that were remotely logged out, so this check is authoritative). */
class SessionViewModel(private val api: FrianzoApi) : ViewModel() {
    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            try {
                val res = api.me()
                _state.value = if (res.success) AuthState.LoggedIn(res.data)
                    else AuthState.LoggedOut
            } catch (_: Exception) {
                // Feed is public, so a failed /me must not block the app:
                // treat as logged out and let the feed load anyway.
                _state.value = AuthState.LoggedOut
            }
        }
    }

    fun markLoggedIn(user: MeData?) { _state.value = AuthState.LoggedIn(user) }
    fun markError(message: String) { _state.value = AuthState.Error(message) }
}

fun sessionViewModelFactory(api: FrianzoApi) = object : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = SessionViewModel(api) as T
}

fun deviceToken(context: Context): String {
    val prefs = context.getSharedPreferences("frianzo_device", Context.MODE_PRIVATE)
    var token = prefs.getString("deviceToken", null)
    if (token == null) {
        token = java.util.UUID.randomUUID().toString()
        prefs.edit().putString("deviceToken", token).apply()
    }
    return token
}
