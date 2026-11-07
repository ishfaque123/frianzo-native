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
        // v5.13: the flusher may run with no chat thread open, so the
        // socket needs its cookie jar here (a thread open inits it too).
        ChatSocket.init(PersistentCookieJar(context))
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
            "MESSAGE" -> replayMessage(api2, item.targetId, payload)
            else -> true
        }
    }

    /**
     * v5.13: a queued reply / forward MUST keep its reply link — the
     * REST send has no replyToId / isForwarded fields, so such items go
     * over the socket. A null ack (socket down / no auth) throws so the
     * flusher stops and the item stays queued instead of being sent as
     * a degraded plain message. A server rejection (success == false)
     * returns false so it is dropped, never retried forever.
     */
    private suspend fun replayMessage(api2: FrianzoApi2, conversationId: String, payload: Map<String, String>): Boolean {
        val replyToId = payload["replyToId"]?.takeIf { it.isNotBlank() }
        val isForwarded = payload["isForwarded"] == "true"
        if (replyToId == null && !isForwarded) {
            return api2.sendMessage(conversationId, SendMessageRequest(payload["text"] ?: "")).success
        }
        val obj = org.json.JSONObject()
        obj.put("conversationId", conversationId)
        obj.put("content", payload["text"] ?: "")
        if (replyToId != null) obj.put("replyToId", replyToId)
        if (isForwarded) obj.put("isForwarded", true)
        val ack = ChatSocket.emitAck("message:send", obj)
            ?: throw java.io.IOException("chat socket ack timeout")
        return ack.optBoolean("success")
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
