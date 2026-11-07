package com.frianzo.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * v5.12 — one global connectivity listener, registered once at app
 * start (MainActivity.onCreate). [isOnline] is Compose state, so any
 * screen can show an "You're offline" banner and re-render when the
 * connection returns. [onBackOnline] fires (once per reconnect) so the
 * outbox can be flushed and failed loads can retry. The web does the
 * same with window online/offline listeners; the native app had no
 * connectivity awareness at all before this.
 */
object NetworkMonitor {
    var isOnline by mutableStateOf(true)
        private set

    /** Set by MainActivity to flush the outbox when we come back online. */
    var onBackOnline: (() -> Unit)? = null

    private var callback: ConnectivityManager.NetworkCallback? = null
    private var wasOffline = false

    fun isOnlineNow(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Exception) {
            true
        }
    }

    fun start(context: Context) {
        if (callback != null) return
        isOnline = isOnlineNow(context)
        wasOffline = !isOnline
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                isOnline = true
                if (wasOffline) {
                    wasOffline = false
                    onBackOnline?.invoke()
                }
            }

            override fun onLost(network: Network) {
                isOnline = isOnlineNow(context)
                if (!isOnline) wasOffline = true
            }

            override fun onUnavailable() {
                isOnline = false
                wasOffline = true
            }
        }
        callback = cb
        try {
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                cb,
            )
        } catch (_: Exception) {
            callback = null
        }
    }

    fun stop(context: Context) {
        val cb = callback ?: return
        callback = null
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(cb)
        } catch (_: Exception) {
        }
    }
}
