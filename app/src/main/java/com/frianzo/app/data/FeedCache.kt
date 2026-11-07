package com.frianzo.app.data

import android.content.Context

/**
 * v5.12 — home-feed cache (cache-first, like the web's getOfflineFeed).
 * Persists the last successful first page of the home feed (posts JSON)
 * in SharedPreferences, following the same pattern as the reels offline
 * cache (SharedPreferences "frianzo_reels" + Gson). The feed is shown
 * instantly from this cache on cold start, then refreshed from the
 * network in the background. Only the first page is cached (30 posts
 * max) so the stored JSON stays small; likes/comments/counts are the
 * snapshot from save time, refreshed on the next successful load.
 */
object FeedCache {
    private const val PREFS = "frianzo_feed"
    private const val KEY_JSON = "cached_feed_json"
    private const val MAX_POSTS = 30

    data class CachedFeed(
        val posts: List<Post> = emptyList(),
        val hasMore: Boolean = false,
        val nextOffset: Int = 0,
        val savedAt: Long = 0L,
    )

    fun load(context: Context): CachedFeed? {
        return try {
            val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_JSON, null) ?: return null
            val type = object : com.google.gson.reflect.TypeToken<CachedFeed>() {}.type
            val cached = com.google.gson.Gson().fromJson<CachedFeed>(json, type)
            if (cached == null || cached.posts.isEmpty()) null else cached
        } catch (_: Exception) {
            null
        }
    }

    fun save(context: Context, posts: List<Post>, hasMore: Boolean, nextOffset: Int) {
        try {
            val cached = CachedFeed(
                posts = posts.take(MAX_POSTS),
                hasMore = hasMore,
                nextOffset = nextOffset,
                savedAt = System.currentTimeMillis(),
            )
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_JSON, com.google.gson.Gson().toJson(cached))
                .apply()
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
}
