package com.frianzo.app.data

/**
 * v5.12 — replays the offline outbox when the connection returns.
 * Items are replayed in queue order. A replay that succeeds is removed.
 * A replay that fails for a NON-network reason (server error, deleted
 * post/reel) is also removed — it would never succeed, so it must not
 * be retried forever. A replay that fails for a network reason stops
 * the flush; the remaining items stay queued for the next reconnect.
 */
object OutboxFlusher {
    data class FlushResult(val sent: Int, val dropped: Int, val stoppedOffline: Boolean)

    suspend fun flush(context: android.content.Context, api2: FrianzoApi2): FlushResult {
        val items = Outbox.list(context)
        var sent = 0
        var dropped = 0
        var stoppedOffline = false
        for (item in items) {
            try {
                val ok = replay(api2, item)
                if (ok) {
                    Outbox.remove(context, item.id)
                    sent++
                } else {
                    Outbox.remove(context, item.id)
                    dropped++
                }
            } catch (e: Exception) {
                if (Outbox.isNetworkError(e)) {
                    stoppedOffline = true
                    break
                }
                Outbox.remove(context, item.id)
                dropped++
            }
        }
        return FlushResult(sent, dropped, stoppedOffline)
    }

    private suspend fun replay(api2: FrianzoApi2, item: Outbox.OutboxItem): Boolean {
        val payload = parsePayload(item.payload)
        return when (item.type) {
            "LIKE_POST" -> api2.setReaction(item.targetId, mapOf("type" to (payload["reaction"] ?: "like"))).success
            "LIKE_REEL" -> api2.toggleReelLike(item.targetId).success
            "COMMENT_POST" -> api2.addPostComment(item.targetId, CommentCreateRequest(payload["text"] ?: "", payload["parent"]?.takeIf { it.isNotBlank() })).success
            "COMMENT_REEL" -> api2.addReelComment(item.targetId, CommentCreateRequest(payload["text"] ?: "", payload["parent"]?.takeIf { it.isNotBlank() })).success
            "MESSAGE" -> api2.sendMessage(item.targetId, SendMessageRequest(payload["text"] ?: "")).success
            else -> true
        }
    }

    private fun parsePayload(json: String): Map<String, String> {
        return try {
            val type = object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type
            com.google.gson.Gson().fromJson<Map<String, String>>(json, type) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
