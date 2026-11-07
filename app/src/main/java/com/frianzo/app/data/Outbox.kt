package com.frianzo.app.data

import android.content.Context
import java.util.UUID

/**
 * v5.12 — offline outbox. When a like, comment, or chat message fails
 * because there is no network, it is queued here (persisted in
 * SharedPreferences as JSON) instead of being dropped with just an
 * error. When the connection returns, [NetworkMonitor] triggers a flush
 * that replays the queued calls in order. An item that fails again for
 * a NON-network reason (server error) is dropped, not retried forever.
 *
 * Payload is a small JSON object per type:
 * - LIKE_POST / LIKE_REEL: {"reaction":"like"} (targetId = post/reel id)
 * - COMMENT_POST / COMMENT_REEL: {"text":"..."} (targetId = post/reel id)
 * - MESSAGE: {"text":"..."} (targetId = conversation id); a queued
 *   reply / forward also carries "replyToId" and/or "isForwarded":"true"
 *   (v5.13) and is replayed over the socket, since REST has no such fields.
 */
object Outbox {
    private const val PREFS = "frianzo_outbox"
    private const val KEY_JSON = "queue_json"
    private const val MAX_ITEMS = 50

    data class OutboxItem(
        val id: String = UUID.randomUUID().toString(),
        val type: String,
        val targetId: String,
        val payload: String = "{}",
        val createdAt: Long = System.currentTimeMillis(),
    )

    private data class Queue(val items: List<OutboxItem> = emptyList())

    fun list(context: Context): List<OutboxItem> {
        return try {
            val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_JSON, null) ?: return emptyList()
            val type = object : com.google.gson.reflect.TypeToken<Queue>() {}.type
            com.google.gson.Gson().fromJson<Queue>(json, type)?.items ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun enqueue(context: Context, item: OutboxItem): Boolean {
        return try {
            val items = (listOf(item) + list(context)).take(MAX_ITEMS)
            save(context, items)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Enqueue with a JSON payload built from a field map (Gson-escaped). */
    fun enqueueJson(context: Context, type: String, targetId: String, fields: Map<String, String>): Boolean {
        val payload = try { com.google.gson.Gson().toJson(fields) } catch (_: Exception) { "{}" }
        return enqueue(context, OutboxItem(type = type, targetId = targetId, payload = payload))
    }

    fun remove(context: Context, id: String) {
        try {
            save(context, list(context).filter { it.id != id })
        } catch (_: Exception) {
        }
    }

    fun clear(context: Context) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_JSON).apply()
        } catch (_: Exception) {
        }
    }

    private fun save(context: Context, items: List<OutboxItem>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_JSON, com.google.gson.Gson().toJson(Queue(items)))
            .apply()
    }

    /** True when the failure is a network failure (no connection, timeout,
     *  DNS) rather than a server error. Only network failures are queued. */
    fun isNetworkError(e: Throwable): Boolean {
        return when (e) {
            is java.net.UnknownHostException -> true
            is java.net.SocketTimeoutException -> true
            is java.io.IOException -> true
            else -> false
        }
    }
}
