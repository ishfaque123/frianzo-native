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
    data class LoggedIn(val user: MeData?, val isNewUser: Boolean = false) : AuthState()
    data class Error(val message: String) : AuthState()
}

/** Holds login state. On start it asks /api/auth/me: the persisted cookie
 *  decides whether the session is still alive (server also revokes sessions
 *  that were remotely logged out, so this check is authoritative). */
class SessionViewModel(private val api: FrianzoApi) : ViewModel() {
    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state

    // Gate fix (v5.1): remembers whether the CURRENT session began with a
    // fresh login that created the account (isNewUser=true from the login
    // response). Set ONLY by markLoggedIn (a fresh login); a cold start
    // begins at Loading, so a restored session never carries it and goes
    // straight to the feed. It lives outside the state object so that a
    // refresh() — including the login-success refresh (MainActivity
    // onLoggedIn) and Error -> Retry — never wipes it; otherwise the
    // setup screen would flash and vanish before a new user could
    // complete it. It becomes inert once /me says profileCompleted=true
    // (the gate needs both isNewUser AND profileCompleted != true).
    // Cleared on logout (markLoggedOut); the next login sets it fresh.
    private var freshLoginIsNewUser: Boolean = false

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
                _state.value = if (res.success) AuthState.LoggedIn(res.data?.user, freshLoginIsNewUser)
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

    fun markLoggedIn(user: MeData?, isNewUser: Boolean = false) {
        freshLoginIsNewUser = isNewUser
        _state.value = AuthState.LoggedIn(user, isNewUser)
    }
    fun markLoggedOut() { freshLoginIsNewUser = false; _state.value = AuthState.LoggedOut }
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

