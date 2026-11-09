package com.frianzo.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.frianzo.app.data.PersistentCookieJar
import com.frianzo.app.data.PushTokenRequest
import com.frianzo.app.data.createApi2
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * v5.19 (FCM push, HIGH gap H1): receives Firebase Cloud Messaging.
 *
 * The backend sends DATA-ONLY messages (pushService.ts — no `notification`
 * payload, so onMessageReceived always runs, even in the background) with
 * data = { title, body, url, badge, tag?, avatarUrl?, type?,
 * conversationId?, postId?, commentId?, reelId? }. This service builds
 * the system notification itself from that data. Message text is NEVER
 * in the push body for chat (body is "New message" / "{N} new messages" /
 * "Sent you a photo" / "Sent you a voice message") — nothing here reads
 * or displays message content.
 *
 * Tap destinations match the web `hrefFor` map (NOTIFICATIONS-MAP.md
 * §(c)): post -> post detail, reel -> reels, follow -> the actor's
 * profile, verification_approved -> own profile, verification_rejected
 * -> Verification settings, removed/restricted/banned -> Community
 * Guidelines (opened the same way the app's legal pages open: a browser
 * ACTION_VIEW intent, see DrawerScreens.kt Card 3), chat type=message
 * -> the conversation by conversationId, new_device_login -> no
 * navigation (opens the app). Anything unrecognised falls back to the
 * Notifications tab (the backend's default url, /notifications).
 *
 * Dormant until the owner sets FIREBASE_SERVICE_ACCOUNT_JSON on the
 * backend: without it pushService.ts skips every send, and no token
 * registration here can make a push arrive. No service-account JSON is
 * in this app, and google-services.json stays a CI secret (never
 * committed) — the SDK reads it at build time.
 */
class FrianzoMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "FrianzoMessaging"
        private const val CHANNEL_ID = "frianzo_push"
        private const val CHANNEL_NAME = "Frianzo"
        // Chat pushes carry the sender's display name as an intent extra
        // (the /messages/{id} URL has no name in it); MainActivity reads
        // it in parsePushIntent().
        const val EXTRA_CHAT_NAME = "frianzo_push_chat_name"
        // Destinations the backend can send for removed/restricted
        // accounts and content (web hrefFor -> /community-guidelines).
        private val GUIDELINES_TYPES = setOf(
            "post_removed", "reel_removed", "messaging_restricted",
            "account_restricted", "account_banned",
        )
    }

    // onNewToken / onMessageReceived run off the main thread; network and
    // disk work here must never touch the UI thread. Cancelled in
    // onDestroy so a dead service cannot leak a coroutine.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Token refresh (first launch, reinstall, Firebase rotation).
        // Reuses the app's authenticated Retrofit client (cookie auth,
        // same as MainActivity): no new HTTP client, no stored token.
        // If the user is logged out the call 401s silently; the token is
        // re-registered on the next login by MainActivity. The backend
        // upserts, so a duplicate registration is harmless.
        serviceScope.launch {
            try {
                val api2 = createApi2(applicationContext, PersistentCookieJar(applicationContext))
                api2.registerPushToken(PushTokenRequest(token))
            } catch (_: Exception) {
                Log.w(TAG, "onNewToken: could not register push token")
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        // Data-only by contract; the notification-payload fallback only
        // covers a future backend change — today it is never populated.
        val title = data["title"]?.takeIf { it.isNotBlank() }
            ?: message.notification?.title?.takeIf { it.isNotBlank() }
            ?: "Frianzo"
        val body = data["body"]?.takeIf { it.isNotBlank() }
            ?: message.notification?.body?.takeIf { it.isNotBlank() }
            ?: return
        showNotification(title, body, data)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun showNotification(title: String, body: String, data: Map<String, String>) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(manager)
        val badge = data["badge"]?.toIntOrNull() ?: 0
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setColor(Color.parseColor("#2563EB")) // FrianzoBlue
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
        // Avatar as the large icon when the backend sent one (best
        // effort: a failed download just leaves the small icon).
        // onMessageReceived runs on a worker thread, so this network
        // fetch is allowed here; it is capped at 3 s either way.
        data["avatarUrl"]?.takeIf { it.isNotBlank() }?.let { url ->
            downloadBitmap(url)?.let { bitmap -> builder.setLargeIcon(bitmap) }
        }
        // Badge count (unread notifications, sent by the backend as a
        // string). Applied via setNumber — the standard mechanism; the
        // launcher icon badge itself is OEM-dependent.
        if (badge > 0) builder.setNumber(badge)
        pushTapIntent(data)?.let { pending -> builder.setContentIntent(pending) }
        // Backend `tag` groups/replaces (same type + target replaces the
        // previous push, e.g. "A and 2 others liked your post"); without
        // a tag each push gets a unique tag so nothing is replaced.
        val notifyTag = data["tag"]?.takeIf { it.isNotBlank() }
            ?: "frianzo-push-${System.currentTimeMillis()}"
        manager.notify(notifyTag, 0, builder.build())
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    /**
     * Tap destination for a push, in the same order as the web hrefFor:
     * removed/restricted first (a removed post still carries its postId,
     * but the post is gone — it must NOT open post detail), then chat,
     * reel, post, follow, verification, and finally the backend url
     * (default /notifications) or plain app open.
     */
    private fun pushTapIntent(data: Map<String, String>): PendingIntent? {
        val type = data["type"]
        val conversationId = data["conversationId"]?.takeIf { it.isNotBlank() }
        val postId = data["postId"]?.takeIf { it.isNotBlank() }
        val reelId = data["reelId"]?.takeIf { it.isNotBlank() }
        if (type != null && type in GUIDELINES_TYPES) {
            // Same mechanism as the app's legal pages (Settings Card 3):
            // a browser ACTION_VIEW intent. NOTE: this device may offer
            // the app itself as a handler (MainActivity declares a VIEW
            // filter for frianzo.online); parseDeepLink returns null for
            // /community-guidelines, so the app then just opens on the
            // feed instead of the page.
            return browserPendingIntent("https://frianzo.online/community-guidelines")
        }
        val intent: Intent = when {
            type == "message" && conversationId != null -> {
                Intent(this, MainActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    this.data = Uri.parse("https://frianzo.online/messages/$conversationId")
                    putExtra(EXTRA_CHAT_NAME, data["title"]?.takeIf { it.isNotBlank() } ?: "Chat")
                }
            }
            reelId != null -> mainActivityIntent("https://frianzo.online/reels?id=$reelId")
            postId != null -> mainActivityIntent("https://frianzo.online/post/$postId")
            type == "follow" -> {
                // Backend sends url = /u/{actorUsername} for follows.
                val url = data["url"]?.takeIf { it.startsWith("/") }
                if (url != null) mainActivityIntent("https://frianzo.online$url")
                else mainActivityIntent("https://frianzo.online/notifications")
            }
            type == "verification_approved" -> {
                // Web hrefFor: /u/{ownUsername}. Internal-only deep link
                // (no such web route): MainActivity opens the own-profile
                // tab. Explicit intent, so no browser is involved.
                mainActivityIntent("https://frianzo.online/me")
            }
            type == "verification_rejected" -> {
                mainActivityIntent("https://frianzo.online/settings-menu/verification")
            }
            type == "new_device_login" -> {
                // Web hrefFor is null: no navigation, just open the app.
                Intent(this, MainActivity::class.java)
            }
            else -> {
                val url = data["url"]?.takeIf { it.startsWith("/") }
                if (url != null) mainActivityIntent("https://frianzo.online$url")
                else Intent(this, MainActivity::class.java)
            }
        }
        val requestCode = (intent.data?.toString() ?: type ?: "frianzo-push").hashCode()
        return PendingIntent.getActivity(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun mainActivityIntent(url: String): Intent =
        Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(url)
            // singleTop (manifest) + CLEAR_TOP: an already-running app
            // is brought forward and receives this in onNewIntent.
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

    private fun browserPendingIntent(url: String): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        return PendingIntent.getActivity(
            this, url.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun downloadBitmap(urlString: String): Bitmap? {
        return try {
            val connection = URL(urlString).openConnection() as HttpURLConnection
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.connect()
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.inputStream.use { stream -> BitmapFactory.decodeStream(stream) }
        } catch (_: Exception) {
            null
        }
    }
}
