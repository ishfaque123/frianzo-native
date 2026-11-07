package com.frianzo.app.data

import android.os.Handler
import android.os.Looper
import io.socket.client.Ack
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * v5.13 — minimal Socket.IO client for chat per-message actions.
 * The backend exposes reply / react / edit / delete / pin / forward
 * ONLY as Socket.IO events (socketServer.ts) — there are no REST
 * endpoints for them, and the web uses the same events. Auth is the
 * same vynzo_auth_token cookie the REST calls use, sent as a Cookie
 * header on the handshake (the backend reads it there, like the web's
 * withCredentials). Plain text sends stay on REST (the v5.12 outbox
 * path); the socket is used only for the actions REST cannot do.
 * Server is socket.io 4.7.5; the 2.x Java client speaks its protocol.
 */
object ChatSocket {
    private var socket: Socket? = null
    private var cookieJar: PersistentCookieJar? = null
    private var lastToken: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun init(jar: PersistentCookieJar) {
        cookieJar = jar
    }

    private fun authToken(): String? {
        val jar = cookieJar ?: return null
        return try {
            val url = "https://api.frianzo.online/".toHttpUrl()
            jar.loadForRequest(url).firstOrNull { it.name == "vynzo_auth_token" }?.value
        } catch (_: Exception) {
            null
        }
    }

    fun isConnected(): Boolean = socket?.connected() == true

    @Synchronized
    fun connect() {
        val token = authToken() ?: return
        val current = socket
        if (current != null) {
            if (token == lastToken) {
                if (!current.connected()) current.connect()
                return
            }
            // Token rotated (re-login): drop the stale socket so the
            // next connect uses the new cookie.
            try { current.disconnect() } catch (_: Exception) {}
            socket = null
        }
        lastToken = token
        try {
            val opts = IO.Options().apply {
                extraHeaders = mapOf(
                    "Cookie" to listOf("vynzo_auth_token=$token"),
                    "Origin" to listOf("https://frianzo.online"),
                )
                reconnection = true
                timeout = 15000L
            }
            socket = IO.socket("https://api.frianzo.online", opts)
            socket?.connect()
        } catch (_: Exception) {
            socket = null
        }
    }

    fun on(event: String, listener: Emitter.Listener) {
        connect()
        socket?.on(event, listener)
    }

    fun off(event: String, listener: Emitter.Listener) {
        socket?.off(event, listener)
    }

    fun emit(event: String, data: JSONObject) {
        connect()
        try { socket?.emit(event, data) } catch (_: Exception) {}
    }

    /**
     * Emit with ack, 15s timeout (web parity). The callback runs on the
     * main thread with the ack JSON, or null on timeout / no connection /
     * no auth token. The ack payload is { success, data?, error? }.
     */
    fun emitWithAck(event: String, data: JSONObject, onResult: (JSONObject?) -> Unit) {
        if (authToken() == null) {
            mainHandler.post { onResult(null) }
            return
        }
        connect()
        val sock = socket
        if (sock == null) {
            mainHandler.post { onResult(null) }
            return
        }
        val done = AtomicBoolean(false)
        fun finish(result: JSONObject?) {
            if (done.compareAndSet(false, true)) mainHandler.post { onResult(result) }
        }
        mainHandler.postDelayed({ finish(null) }, 15000L)
        try {
            sock.emit(event, data, Ack { args ->
                finish(args.firstOrNull() as? JSONObject)
            })
        } catch (_: Exception) {
            finish(null)
        }
    }

    /**
     * Suspend version of [emitWithAck] for the outbox flusher.
     * Null = no ack (treat as a network failure, keep the item queued).
     */
    suspend fun emitAck(event: String, data: JSONObject): JSONObject? =
        suspendCancellableCoroutine { cont ->
            emitWithAck(event, data) { result ->
                if (cont.isActive) cont.resume(result)
            }
        }
}
