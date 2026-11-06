package com.frianzo.app.data

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException

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
                // A-B31: a 2xx {success:false} is NOT "logged out" — the
                // backend only returns success:false with an HTTP error
                // status (verified: sendSuccess always sets success:true;
                // failures go through sendError + ApiError status), so a
                // 2xx success:false here is transient. Show Error + Retry
                // instead of bouncing a valid session to the login screen.
                _state.value = if (res.success) AuthState.LoggedIn(res.data?.user)
                    else AuthState.Error(res.error?.message ?: "Session check failed. Try again.")
            } catch (e: HttpException) {
                // 401-on-start fix: a clean unauthenticated /me (HTTP 401)
                // is NOT an error — it simply means "not logged in", so go
                // straight to the login screen (MainActivity else-branch).
                // Any other HTTP status still shows the Error screen.
                _state.value = if (e.code() == 401) AuthState.LoggedOut
                    else AuthState.Error(e.message ?: "HTTP ${e.code()}")
            } catch (e: Exception) {
                // PERMANENT FIX for H5: a network failure is NOT "logged
                // out". Previously every IOException dropped a logged-in
                // user to the login screen with no retry. Now it is an
                // Error state with a Retry button (MainActivity), while a
                // clean unauthenticated /me response still means LoggedOut.
                _state.value = AuthState.Error(e.message ?: "Network error. Check your connection and retry.")
            }
        }
    }

    fun markLoggedIn(user: MeData?) { _state.value = AuthState.LoggedIn(user) }
    fun markLoggedOut() { _state.value = AuthState.LoggedOut }
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

