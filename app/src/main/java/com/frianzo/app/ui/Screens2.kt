package com.frianzo.app.ui

import android.net.Uri
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.frianzo.app.data.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.runtime.saveable.rememberSaveable
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

// ---------- REELS: vertical list, tap video to play/pause, like + view ----------
@Composable
fun ReelsScreen(api2: FrianzoApi2, onAuthor: (String) -> Unit = {}) {
    val context = LocalContext.current
    var reels by remember { mutableStateOf<List<Reel>>(emptyList()) }
    var offset by remember { mutableStateOf(0) }
    var hasMore by remember { mutableStateOf(true) }
    var viewedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun load() {
        if (loading || !hasMore) return
        loading = true
        scope.launch {
            try {
                val res = api2.reelFeed(offset)
                if (res.success && res.data != null) {
                    // PERMANENT FIX for H2: the backend has no exclude
                    // param and re-ranks on every page, so offset pages
                    // can repeat reels. Dedupe by id client-side so a
                    // reel already shown is never shown twice, and trust
                    // the backend hasMore instead of isNotEmpty (a page
                    // that exactly fills the page size used to show
                    // "Load more" once more, then an empty page).
                    val seen = reels.map { it.id }.toSet()
                    reels = reels + res.data.reels.filter { it.id !in seen }
                    offset += res.data.reels.size
                    hasMore = res.data.hasMore
                } else error = res.error?.message ?: "Reels failed"
            } catch (e: Exception) { error = e.message ?: "Reels failed" }
            finally { loading = false }
        }
    }
    var reelComment by remember { mutableStateOf<Reel?>(null) }
    var reelCommentCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    reelComment?.let { reel -> ReelCommentsDialog(api2, reel, onClose = { reelComment = null }, onCommentAdded = { reelCommentCounts = reelCommentCounts + (reel.id to ((reelCommentCounts[reel.id] ?: reel.commentCount) + 1)) }) }
    LaunchedEffect(Unit) { load() }
    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(reels, key = { it.id }) { reel ->
            var liked by remember { mutableStateOf(reel.liked) }
            var likes by remember { mutableStateOf(reel.likeCount) }
            var playing by remember { mutableStateOf(false) }
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { reel.author?.username?.let { onAuthor(it) } }) {
                        FrianzoAvatar(reel.author?.profilePictureUrl, 40.dp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("@${reel.author?.username ?: ""}", style = MaterialTheme.typography.titleMedium)
                            Text("${reel.viewCount} views", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    reel.caption?.let { Text(it) }
                    Spacer(Modifier.height(8.dp))
                    // PERF: previously EVERY reel card created a VideoView and
                    // called setVideoURI on composition, so opening Reels
                    // started downloading video headers for all visible
                    // reels at once. Now only a thumbnail/play button is
                    // shown until the user taps; the VideoView (and its
                    // download) is created only for the reel being played.
                    if (reel.videoUrl != null && playing) {
                        AndroidView(
                            factory = { ctx -> VideoView(ctx).apply {
                                setVideoURI(Uri.parse(reel.videoUrl))
                                setOnCompletionListener { v -> v.seekTo(0); v.start() }
                            } },
                            modifier = Modifier.fillMaxWidth().height(420.dp).clip(RoundedCornerShape(12.dp)).clickable { playing = false },
                            update = { view -> view.start() },
                            onRelease = { view -> view.stopPlayback() },
                        )
                    } else if (reel.videoUrl != null) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(420.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black).clickable {
                                playing = true
                                // Views were counted at 0 seconds before.
                                // Now gated: only after 3s of real playback,
                                // and only if still playing then.
                                if (reel.id !in viewedIds) {
                                    scope.launch {
                                        delay(3000)
                                        if (playing && reel.id !in viewedIds) {
                                            viewedIds = viewedIds + reel.id
                                            try { api2.recordReelView(reel.id, mapOf("watchSec" to 3)) } catch (_: Exception) {}
                                        }
                                    }
                                }
                            },
                            contentAlignment = Alignment.Center,
                        ) {
                            reel.thumbnailUrl?.let { AsyncImage(model = it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(64.dp))
                        }
                    } else {
                        reel.thumbnailUrl?.let { AsyncImage(model = it, contentDescription = null, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(shape = RoundedCornerShape(28.dp), onClick = {
                            scope.launch {
                                try {
                                    val r = api2.toggleReelLike(reel.id)
                                    if (r.success && r.data != null) { liked = r.data.liked; likes = r.data.likeCount }
                                } catch (_: Exception) {}
                            }
                        }) { Row(verticalAlignment = Alignment.CenterVertically) { Icon(if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, contentDescription = "Like", modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("$likes") } }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { reelComment = reel }) { Icon(Icons.Filled.Chat, contentDescription = "Comments", modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("${reelCommentCounts[reel.id] ?: reel.commentCount}") }
                        TextButton(onClick = {
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_TEXT, "https://frianzo.online/reel/${reel.id}")
                            }
                            context.startActivity(android.content.Intent.createChooser(intent, "Share reel"))
                        }) { Icon(Icons.Filled.Share, contentDescription = "Share", modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Share") }
                    }
                }
            }
        }
        item {
            if (error != null) Text(error!!, Modifier.padding(16.dp))
            if (loading) Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { DotsLoading() }
            else if (hasMore) OutlinedButton(onClick = { load() }, modifier = Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(28.dp)) { Text("Load more reels") }
        }
    }
}


// ---------- REEL COMMENTS: read + add via existing api2 reelComments endpoints ----------
@Composable
private fun ReelCommentsDialog(api2: FrianzoApi2, reel: Reel, onClose: () -> Unit, onCommentAdded: () -> Unit = {}) {
    var comments by remember { mutableStateOf<List<ReelComment>>(emptyList()) }
    var draft by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun load() {
        scope.launch {
            try {
                val res = api2.reelComments(reel.id)
                if (res.success && res.data != null) comments = res.data.comments else error = res.error?.message
            } catch (e: Exception) { error = e.message }
        }
    }
    LaunchedEffect(reel.id) { load() }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Comments") },
        text = {
            Column {
                LazyColumn(Modifier.fillMaxWidth().height(320.dp)) {
                    items(comments, key = { it.id }) { c ->
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Text(c.author?.displayName ?: c.author?.username ?: "User", style = MaterialTheme.typography.titleMedium)
                            Text(c.content ?: "", style = MaterialTheme.typography.bodyMedium)
                        }
                        HorizontalDivider()
                    }
                    if (comments.isEmpty() && error == null) item { Text("No comments yet") }
                    if (error != null) item { Text(error!!) }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = draft, onValueChange = { draft = it }, label = { Text("Write a comment") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
        },
        confirmButton = {
            Button(onClick = {
                if (draft.isBlank()) { error = "Type a comment first"; return@Button }
                val text = draft; draft = ""
                scope.launch {
                    try {
                        val res = api2.addReelComment(reel.id, CommentCreateRequest(text))
                        if (res.success) { load(); onCommentAdded() } else error = res.error?.message
                    } catch (e: Exception) { error = e.message; draft = text }
                }
            }) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

// ---------- CREATE POST: text + optional photo ----------
@Composable
fun CreatePostScreen(api2: FrianzoApi2, initialUri: android.net.Uri? = null, onPosted: () -> Unit) {
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    var imageUri by remember { mutableStateOf<Uri?>(initialUri) }
    var mediaIsVideo by remember { mutableStateOf(false) }
    var mediaMime by remember { mutableStateOf<String?>(null) }
    // Pre-attached media from the feed + button (system Gallery picker).
    LaunchedEffect(initialUri) {
        if (initialUri != null) {
            imageUri = initialUri
            val mime = try { context.contentResolver.getType(initialUri) } catch (_: Exception) { null }
            mediaMime = mime
            mediaIsVideo = mime?.startsWith("video") == true
        }
    }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) {
            imageUri = uri
            val mime = try { context.contentResolver.getType(uri) } catch (_: Exception) { null }
            mediaMime = mime
            mediaIsVideo = mime?.startsWith("video") == true
        }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        Text("New post", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("What's on your mind?") }, modifier = Modifier.fillMaxWidth().height(140.dp), shape = RoundedCornerShape(12.dp))
        Spacer(Modifier.height(8.dp))
        OutlinedButton(shape = RoundedCornerShape(28.dp), onClick = { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageAndVideo)) }) { Row(verticalAlignment = Alignment.CenterVertically) { if (imageUri != null) { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)) }; Text(if (imageUri == null) "Add photo or video" else if (mediaIsVideo) "Video selected" else "Photo selected") } }
        if (imageUri != null && !mediaIsVideo) {
            Spacer(Modifier.height(8.dp))
            AsyncImage(model = imageUri, contentDescription = null, modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(12.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        }
        if (mediaIsVideo) {
            Spacer(Modifier.height(8.dp))
            Text("Video selected from Gallery. Feed posts here accept only a photo — a video-post endpoint does not exist yet — so Post stays off for a video. Pick a photo instead, or post the video as a reel later.", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(12.dp))
        Button(enabled = !busy && !mediaIsVideo && (text.isNotBlank() || imageUri != null), onClick = {
            busy = true
            scope.launch {
                try {
                    var part: MultipartBody.Part? = null
                    var tmpFile: File? = null
                    val uri = imageUri
                    if (uri != null) {
                        val input = context.contentResolver.openInputStream(uri)
                            ?: throw Exception("Could not read the selected photo")
                        val mime = mediaMime ?: (try { context.contentResolver.getType(uri) } catch (_: Exception) { null }) ?: "image/jpeg"
                        val ext = when (mime.substringAfter("/")) { "png" -> "png"; "webp" -> "webp"; "gif" -> "gif"; else -> "jpg" }
                        val tmp = File(context.cacheDir, "post_upload.$ext")
                        tmpFile = tmp
                        input.use { inp -> tmp.outputStream().use { out -> inp.copyTo(out) } }
                        part = MultipartBody.Part.createFormData("image", tmp.name, tmp.asRequestBody(mime.toMediaTypeOrNull()))
                    }
                    val res = api2.createPost(text.toRequestBody("text/plain".toMediaTypeOrNull()), part)
                    // Temp upload file is cache-only; delete it after use
                    // instead of leaving post_upload.jpg behind forever.
                    try { tmpFile?.delete() } catch (_: Exception) {}
                    if (!res.success) throw Exception(res.error?.message ?: "Post failed")
                    message = "Posted"
                    onPosted()
                } catch (e: Exception) { message = e.message ?: "Post failed" }
                finally { busy = false }
            }
        }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(28.dp)) { Text("Post") }
        message?.let { Spacer(Modifier.height(12.dp)); Text(it) }
        if (busy) { Spacer(Modifier.height(12.dp)); DotsLoading() }
    }
}

// ---------- CHAT: list + thread (REST polling; live socket = later stage) ----------
@Composable
fun ChatListScreen(api2: FrianzoApi2, onOpen: (String, String) -> Unit) {
    var list by remember { mutableStateOf<List<Conversation>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        scope.launch {
            try {
                val res = api2.conversations()
                if (res.success && res.data != null) list = res.data else error = res.error?.message
            } catch (e: Exception) { error = e.message }
        }
    }
    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentPadding = PaddingValues(vertical = 8.dp)) {
        item { Text("Chats", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp)) }
        items(list, key = { it.id }) { c ->
            val other = c.otherUser
            val last = c.lastMessage?.content ?: c.messages?.firstOrNull()?.content ?: ""
            ListItem(
                headlineContent = { Text(other?.displayName ?: other?.username ?: "Chat", style = MaterialTheme.typography.titleMedium) },
                supportingContent = { Text(last, style = MaterialTheme.typography.bodySmall) },
                leadingContent = { FrianzoAvatar(other?.profilePictureUrl, 48.dp) },
                trailingContent = { if (c.unreadCount > 0) Badge(containerColor = FrianzoBadgeRed) { Text("${c.unreadCount}") } },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.clickable { onOpen(c.id, other?.displayName ?: other?.username ?: "") },
            )
            HorizontalDivider()
        }
        if (error != null) item { Text(error!!, Modifier.padding(16.dp)) }
        if (list.isEmpty() && error == null) item { Text("No chats yet", Modifier.padding(16.dp)) }
    }
}

@Composable
fun ChatThreadScreen(api2: FrianzoApi2, conversationId: String, title: String, myId: String?, onBack: () -> Unit = {}) {
    var messages by remember { mutableStateOf<List<ConversationMessage>>(emptyList()) }
    var draft by rememberSaveable { mutableStateOf("") }
    var loadError by remember { mutableStateOf<String?>(null) }
    var sendError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // PERMANENT FIX for H3: poll every 5s while the thread is open, so
    // incoming messages appear without leaving and re-opening the thread.
    // The loop is tied to LaunchedEffect, so it stops when the thread closes.
    LaunchedEffect(conversationId) {
        while (true) {
            try {
                val res = api2.messages(conversationId)
                // Backend already returns messages chronological (old -> new);
                // reverse once so newest-first pairs with reverseLayout below.
                if (res.success && res.data != null) { messages = res.data.messages.reversed(); loadError = null }
                else loadError = res.error?.message
            } catch (e: Exception) { loadError = e.message }
            delay(5000)
        }
    }
    fun load() {
        scope.launch {
            try {
                val res = api2.messages(conversationId)
                if (res.success && res.data != null) messages = res.data.messages.reversed()
            } catch (e: Exception) { loadError = e.message }
        }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 16.dp))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = true) {
            items(messages, key = { it.id ?: "" }) { m ->
                val mine = m.senderId == myId
                Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
                    Surface(color = if (mine) FrianzoBlue else MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp)) {
                        Text(m.content ?: "", Modifier.padding(12.dp), color = if (mine) Color.White else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
        loadError?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = FrianzoBadgeRed) }
        sendError?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = FrianzoBadgeRed) }
        Row(Modifier.background(MaterialTheme.colorScheme.surface).padding(8.dp)) {
            OutlinedTextField(value = draft, onValueChange = { draft = it }, modifier = Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(28.dp))
            Spacer(Modifier.width(8.dp))
            Button(shape = RoundedCornerShape(28.dp), onClick = {
                // M3 fix: an empty draft used to be sent, rejected 400
                // by the backend, and silently restored — now it is
                // blocked with a visible message and nothing is sent.
                if (draft.isBlank()) { sendError = "Type a message first"; return@Button }
                sendError = null
                val text = draft; draft = ""
                scope.launch {
                    try { api2.sendMessage(conversationId, SendMessageRequest(text)); load() } catch (e: Exception) { draft = text; sendError = e.message }
                }
            }) { Text("Send") }
        }
    }
}

// ---------- NOTIFICATIONS ----------
/** M1 fix: raw type codes ("reel_like", blank actor) rendered as
 * " reel_like". Map known types to plain words and fall back to the
 * type with underscores replaced, never a blank headline. */
private fun notificationTitle(n: NotificationItem): String {
    val actor = n.actor?.displayName ?: n.actor?.username ?: "Someone"
    val action = when (n.type) {
        "post_like", "reel_like" -> "liked your post"
        "post_comment", "reel_comment", "comment" -> "commented on your post"
        "follow" -> "started following you"
        "new_device_login" -> "signed in from a new device"
        "message" -> "sent you a message"
        else -> (n.type ?: "notification").replace("_", " ")
    }
    return "$actor $action"
}

private fun notifTimeAgo(createdAt: String?): String {
    if (createdAt.isNullOrBlank()) return ""
    return try {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val millis = f.parse(createdAt)?.time ?: return createdAt.take(10)
        val s = ((System.currentTimeMillis() - millis) / 1000).coerceAtLeast(0)
        when { s < 60 -> "now"; s < 3600 -> "${s / 60}m"; s < 86400 -> "${s / 3600}h"; s < 604800 -> "${s / 86400}d"; s < 2592000 -> "${s / 604800}w"; else -> "${s / 2592000}mo" }
    } catch (_: Exception) { createdAt.take(10) }
}

@Composable
fun NotificationsScreen(api2: FrianzoApi2, onAuthor: (String) -> Unit = {}) {
    var items by remember { mutableStateOf<List<NotificationItem>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf("All") }
    var showMarkAllConfirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun load() {
        scope.launch {
            try {
                val res = api2.notifications()
                if (res.success && res.data != null) items = res.data.notifications else error = res.error?.message
            } catch (e: Exception) { error = e.message }
        }
    }
    LaunchedEffect(Unit) { load() }
    val filtered = when (filter) {
        "Unread" -> items.filter { !it.read }
        "Likes" -> items.filter { (it.type ?: "").contains("like") }
        "Comments" -> items.filter { (it.type ?: "").contains("comment") }
        "Follows" -> items.filter { it.type == "follow" }
        "Messages" -> items.filter { it.type == "message" }
        else -> items
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Notifications", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { showMarkAllConfirm = true }) { Text("Mark all read") }
        }
        if (showMarkAllConfirm) {
            AlertDialog(
                onDismissRequest = { showMarkAllConfirm = false },
                title = { Text("Mark all as read?") },
                text = { Text("All notifications will be marked as read.") },
                confirmButton = { Button(onClick = { showMarkAllConfirm = false; scope.launch { try { api2.markAllNotificationsRead(); load() } catch (_: Exception) {} } }) { Text("Mark all read") } },
                dismissButton = { TextButton(onClick = { showMarkAllConfirm = false }) { Text("Cancel") } },
            )
        }
        // Filter chips below the header (placement: header first, filters
        // second, list last — the live-web order).
        LazyRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("All", "Unread", "Likes", "Comments", "Follows", "Messages")) { f ->
                val sel = filter == f
                Box(Modifier.clip(RoundedCornerShape(28.dp)).background(if (sel) FrianzoBlue else MaterialTheme.colorScheme.surfaceVariant).clickable { filter = f }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Text(f, style = MaterialTheme.typography.bodyMedium, color = if (sel) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(filtered, key = { it.id }) { n ->
                ListItem(
                    headlineContent = { Text(notificationTitle(n), style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text(listOfNotNull(n.detail?.takeIf { it.isNotBlank() }, notifTimeAgo(n.createdAt).takeIf { it.isNotBlank() }).joinToString(" · "), style = MaterialTheme.typography.bodySmall) },
                    leadingContent = { FrianzoAvatar(n.actor?.profilePictureUrl, 48.dp) },
                    trailingContent = { if (!n.read) Badge(containerColor = FrianzoBadgeRed) { Text("new") } },
                    colors = ListItemDefaults.colors(containerColor = if (!n.read) FrianzoBlueTint else MaterialTheme.colorScheme.surface),
                    modifier = Modifier.clickable {
                        scope.launch { try { api2.markNotificationRead(n.id); load() } catch (_: Exception) {} }
                        n.actor?.username?.let { onAuthor(it) }
                    },
                )
                HorizontalDivider()
            }
            if (error != null) item { Text(error!!, Modifier.padding(16.dp)) }
            if (filtered.isEmpty() && error == null) item { Text(if (items.isEmpty()) "No notifications yet" else "Nothing in $filter", Modifier.padding(16.dp)) }
        }
    }
}

// ---------- SEARCH (users) + PROFILE ----------
@Composable
fun SearchScreen(api2: FrianzoApi2, onUser: (String) -> Unit) {
    val context = LocalContext.current
    var q by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PublicProfile>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // Recent searches, kept on the phone only (SharedPreferences, no backend).
    // Stored as one newline-joined string so order is newest-first, and
    // only explicit Search taps are saved (debounced typing is not).
    val prefs = remember { context.getSharedPreferences("frianzo_search", android.content.Context.MODE_PRIVATE) }
    var recents by remember { mutableStateOf(prefs.getString("recents_str", "")?.split("\n")?.filter { it.isNotBlank() }?.take(5) ?: emptyList()) }
    fun saveRecents(list: List<String>) { val clean = list.map { it.replace("\n", " ").trim() }.filter { it.isNotBlank() }; prefs.edit().putString("recents_str", clean.joinToString("\n")).apply() }
    fun runSearch(term: String, saveRecent: Boolean = false) {
        if (term.isBlank()) { searchError = "Type a name to search"; results = emptyList(); return }
        searchError = null; searching = true
        if (saveRecent) {
            val updated = (listOf(term) + recents.filter { it != term }).take(5)
            recents = updated; saveRecents(updated)
        }
        scope.launch {
            try {
                val res = api2.searchUsers(term)
                if (res.success && res.data != null) results = res.data.users
                else searchError = res.error?.message
            } catch (e: Exception) { searchError = e.message }
            finally { searching = false }
        }
    }
    // Live search as you type (300ms debounce), plus the Search button.
    LaunchedEffect(q) {
        if (q.isBlank()) { results = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(300)
        runSearch(q, saveRecent = false)
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        OutlinedTextField(
            value = q, onValueChange = { q = it },
            label = { Text("Search people") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(12.dp),
            trailingIcon = { IconButton(onClick = { runSearch(q, saveRecent = true) }) { Icon(Icons.Filled.Search, contentDescription = "Search") } },
        )
        if (searching) Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) { DotsLoading() }
        if (q.isBlank() && recents.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Recent", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { recents = emptyList(); saveRecents(emptyList()) }) { Text("Clear") }
            }
            recents.forEach { r ->
                Row(Modifier.fillMaxWidth().clickable { q = r }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.History, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Text(r, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { val u = recents - r; recents = u; saveRecents(u) }, modifier = Modifier.size(24.dp)) { Icon(Icons.Filled.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp)) }
                }
                HorizontalDivider()
            }
        }
        searchError?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = FrianzoBadgeRed) }
        if (results.isEmpty() && searchError == null && q.isNotBlank() && !searching) Text("No people found", Modifier.padding(16.dp))
        if (results.isEmpty() && searchError == null && q.isBlank() && recents.isEmpty()) Text("Search for people by name or @username. Post search is not available yet — the backend has no post-search endpoint.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn {
            items(results, key = { it.id ?: it.username ?: "" }) { u ->
                ListItem(
                    headlineContent = { Text(u.displayName ?: "", style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text("@${u.username ?: ""}", style = MaterialTheme.typography.bodySmall) },
                    leadingContent = { FrianzoAvatar(u.profilePictureUrl, 48.dp) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.clickable { if (u.username != null) onUser(u.username) },
                )
                HorizontalDivider()
            }
        }
    }
}

// Country display, matching the web app (vynzo-frontend src/lib/countries.ts):
// the backend stores User.country in mixed form — a 2-letter code from
// IP auto-detect at profile setup (e.g. "PK") or a full name from Settings
// (e.g. "Pakistan") — so the profile must map codes to real names before
// showing them, never show a bare code like "PK". Unknown values are shown
// as-is, never dropped.
private val COUNTRY_NAMES = mapOf(
    "PK" to "Pakistan", "IN" to "India", "BD" to "Bangladesh",
    "AF" to "Afghanistan", "SA" to "Saudi Arabia",
    "AE" to "United Arab Emirates", "QA" to "Qatar", "KW" to "Kuwait",
    "OM" to "Oman", "BH" to "Bahrain", "TR" to "Turkey",
    "GB" to "United Kingdom", "US" to "United States", "CA" to "Canada",
    "AU" to "Australia", "DE" to "Germany", "FR" to "France",
    "IT" to "Italy", "ES" to "Spain", "NL" to "Netherlands",
    "CN" to "China", "JP" to "Japan", "KR" to "South Korea",
    "MY" to "Malaysia", "ID" to "Indonesia", "TH" to "Thailand",
    "PH" to "Philippines", "SG" to "Singapore", "LK" to "Sri Lanka",
    "NP" to "Nepal", "IR" to "Iran", "IQ" to "Iraq", "EG" to "Egypt",
    "ZA" to "South Africa", "NG" to "Nigeria", "KE" to "Kenya",
    "BR" to "Brazil", "MX" to "Mexico", "RU" to "Russia",
)

fun countryDisplayName(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    val upper = trimmed.uppercase()
    if (upper == "USA" || upper == "AMERICA") return "United States"
    if (upper == "UAE") return "United Arab Emirates"
    if (upper == "UK") return "United Kingdom"
    COUNTRY_NAMES[upper]?.let { return it }
    // Already a full name (any case): return the canonical web spelling.
    COUNTRY_NAMES.values.firstOrNull { it.equals(trimmed, ignoreCase = true) }?.let { return it }
    return trimmed
}

@Composable
fun ProfileScreen(api2: FrianzoApi2, username: String?, myId: String?, onLogout: (() -> Unit)? = null, onBack: (() -> Unit)? = null, onOpenChat: ((String, String) -> Unit)? = null) {
    var profile by remember { mutableStateOf<PublicProfile?>(null) }
    var posts by remember { mutableStateOf<List<Post>>(emptyList()) }
    var postsLoading by remember { mutableStateOf(false) }
    var postsError by remember { mutableStateOf<String?>(null) }
    var following by remember { mutableStateOf(false) }
    var followStatus by remember { mutableStateOf<String?>(null) }
    var showEdit by remember { mutableStateOf(false) }
    var editError by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(username) {
        scope.launch {
            try {
                val res = if (username == null) api2.myProfile() else api2.publicProfile(username)
                if (res.success && res.data?.user != null) {
                    profile = res.data.user
                    val uname = res.data.user.username
                    if (uname != null) {
                        postsLoading = true
                        val pr = api2.userPosts(uname)
                        if (pr.success && pr.data != null) posts = pr.data.posts ?: emptyList()
                        else postsError = pr.error?.message
                        postsLoading = false
                    }
                    if (res.data.user.id != null && res.data.user.id != myId) {
                        val fs = api2.followStatus(res.data.user.id)
                        if (fs.success && fs.data != null) {
                            followStatus = fs.data.status
                            following = fs.data.status == "following" || fs.data.status == "friends"
                        }
                    }
                } else error = res.error?.message
            } catch (e: Exception) { error = e.message }
        }
    }
    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (username != null && onBack != null) {
            item { TextButton(onClick = onBack, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Back") } }
        }
        item {
            profile?.let { p ->
                p.coverPhotoUrl?.let { AsyncImage(model = it, contentDescription = null, modifier = Modifier.fillMaxWidth().height(180.dp)) }
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    FrianzoAvatar(p.profilePictureUrl, 80.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.displayName ?: "", style = MaterialTheme.typography.titleLarge)
                            if (p.isVerified) { Spacer(Modifier.width(4.dp)); Icon(Icons.Filled.CheckCircle, contentDescription = "Verified", modifier = Modifier.size(20.dp), tint = FrianzoBlue) }
                        }
                        Text("@${p.username ?: ""}", style = MaterialTheme.typography.bodySmall)
                        p.bio?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        p.website?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = FrianzoBlue) }
                        val loc = listOfNotNull(p.city?.takeIf { it.isNotBlank() }, countryDisplayName(p.country)).joinToString(", ")
                        if (loc.isNotBlank()) Text(loc, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${posts.size} Posts", style = MaterialTheme.typography.titleMedium)
                            val fc = p.followerCount ?: p.followersCount
                            if (fc != null) { Spacer(Modifier.width(16.dp)); Text("$fc Followers", style = MaterialTheme.typography.titleMedium) }
                            val fg = p.followingCount ?: p.followingsCount
                            if (fg != null) { Spacer(Modifier.width(16.dp)); Text("$fg Following", style = MaterialTheme.typography.titleMedium) }
                        }
                    }
                }
                if (username == null && onLogout != null) {
                    Row(Modifier.padding(horizontal = 16.dp)) {
                        OutlinedButton(onClick = { showEdit = true }, modifier = Modifier.height(48.dp), shape = RoundedCornerShape(28.dp)) { Text("Edit profile") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = onLogout, modifier = Modifier.height(48.dp), shape = RoundedCornerShape(28.dp)) { Text("Log out") }
                    }
                    if (editError != null) Text(editError!!, style = MaterialTheme.typography.bodySmall, color = FrianzoBadgeRed, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
                if (showEdit && profile != null) {
                    val cur = profile!!
                    var edName by remember { mutableStateOf(cur.displayName ?: "") }
                    var edBio by remember { mutableStateOf(cur.bio ?: "") }
                    var edCity by remember { mutableStateOf(cur.city ?: "") }
                    var edCountry by remember { mutableStateOf(cur.country ?: "") }
                    var edWeb by remember { mutableStateOf(cur.website ?: "") }
                    AlertDialog(
                        onDismissRequest = { showEdit = false },
                        title = { Text("Edit profile") },
                        text = {
                            Column {
                                OutlinedTextField(value = edName, onValueChange = { edName = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(value = edBio, onValueChange = { edBio = it }, label = { Text("Bio") }, modifier = Modifier.fillMaxWidth())
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(value = edCity, onValueChange = { edCity = it }, label = { Text("City") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(value = edCountry, onValueChange = { edCountry = it }, label = { Text("Country") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(value = edWeb, onValueChange = { edWeb = it }, label = { Text("Website") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            }
                        },
                        confirmButton = {
                            Button(onClick = {
                                scope.launch {
                                    try {
                                        val body = mapOf<String, Any>("displayName" to edName, "bio" to edBio, "city" to edCity, "country" to edCountry, "website" to edWeb)
                                        val r = api2.updateMe(body)
                                        if (r.success) { profile = cur.copy(displayName = edName, bio = edBio, city = edCity, country = edCountry, website = edWeb); showEdit = false; editError = null }
                                        else editError = r.error?.message ?: "Could not save profile"
                                    } catch (e: Exception) { editError = e.message }
                                }
                            }) { Text("Save") }
                        },
                        dismissButton = { TextButton(onClick = { showEdit = false }) { Text("Cancel") } },
                    )
                }
                if (username != null && p.id != null && p.id != myId) {
                    Row(Modifier.padding(horizontal = 16.dp)) {
                        Button(onClick = {
                            scope.launch {
                                try {
                                    val r = api2.toggleFollow(p.id)
                                    if (r.success && r.data != null) { following = r.data.following || r.data.isFollowing; followStatus = r.data.friendStatus ?: if (following) "following" else "none" }
                                } catch (_: Exception) {}
                            }
                        }, modifier = Modifier.height(48.dp), shape = RoundedCornerShape(28.dp)) { Text(if (following) "Following" else if (followStatus == "follow_back") "Follow Back" else "Follow") }
                        if (onOpenChat != null) {
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = {
                                scope.launch {
                                    try {
                                        val r = api2.createConversation(CreateConversationRequest(p.id))
                                        if (r.success && r.data != null) onOpenChat(r.data.id, p.displayName ?: p.username ?: "")
                                    } catch (_: Exception) {}
                                }
                            }, modifier = Modifier.height(48.dp), shape = RoundedCornerShape(28.dp)) { Text("Message") }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (p.isPrivate && username != null && !following) {
                    Text("This account is private", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp))
                }
                Text("Posts", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
                if (postsLoading) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { DotsLoading() }
                if (postsError != null) Text(postsError!!, style = MaterialTheme.typography.bodySmall, color = FrianzoBadgeRed, modifier = Modifier.padding(horizontal = 16.dp))
                if (!postsLoading && postsError == null && posts.isEmpty()) Text("No posts yet", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
            } ?: run {
                if (error != null) Text(error!!, Modifier.padding(16.dp)) else Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { DotsLoading() }
            }
        }
        items(if (profile?.isPrivate == true && username != null && !following) emptyList() else posts, key = { it.id }) { post ->
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                Column(Modifier.padding(16.dp)) {
                    post.content?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    post.imageUrl?.let { Spacer(Modifier.height(8.dp)); AsyncImage(model = it, contentDescription = null, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))) }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Favorite, contentDescription = "Likes", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("${post.likeCount}", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(12.dp))
                    Icon(Icons.Filled.Chat, contentDescription = "Comments", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("${post.commentCount}", style = MaterialTheme.typography.bodySmall)
                }
                }
            }
        }
    }
}

