package com.frianzo.app.ui

import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
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

// ---------- REELS (v2.9): full-screen BLACK vertical snap paging, like the web ----------
// One reel per screen, auto-play the current reel, progress bar, mute
// toggle, double-tap to like, right rail (Like / Comment / Share / Views /
// Save / More), Following / For You tabs, caption pill. Upload, favourite
// and more-menu behaviour are kept from v2.8.
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReelsScreen(api2: FrianzoApi2, myId: String? = null, onAuthor: (String) -> Unit = {}, focusReelId: String? = null, onFocusConsumed: () -> Unit = {}, uploadUri: android.net.Uri? = null, onUploadUriConsumed: () -> Unit = {}) {
    val context = LocalContext.current
    var reels by remember { mutableStateOf<List<Reel>>(emptyList()) }
    var offset by remember { mutableStateOf(0) }
    var hasMore by remember { mutableStateOf(true) }
    var viewedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var feed by remember { mutableStateOf("foryou") }
    var showUpload by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)
    // Current page = first fully-snapped visible item.
    val currentPage: Int get() = listState.firstVisibleItemIndex
    fun load() {
        if (loading || !hasMore) return
        loading = true
        scope.launch {
            try {
                // v2.9: exclude= stops repeats on load-more (backend
                // getReelFeedHandler reads req.query.exclude, max 400 ids).
                val exclude = reels.joinToString(",") { it.id }.takeIf { it.isNotBlank() }
                val res = api2.reelFeed(offset, feed, exclude)
                if (res.success && res.data != null) {
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
    reelComment?.let { reel -> ReelCommentsDialog(api2, reel, myId, onClose = { reelComment = null }, onCommentAdded = { reelCommentCounts = reelCommentCounts + (reel.id to ((reelCommentCounts[reel.id] ?: reel.commentCount) + 1)) }) }
    if (showUpload) { ReelUploadScreen(api2, initialVideoUri = uploadUri, onDone = { showUpload = false; onUploadUriConsumed(); reels = emptyList(); offset = 0; hasMore = true; load() }, onClose = { showUpload = false; onUploadUriConsumed() }) }
    LaunchedEffect(uploadUri) { if (uploadUri != null) showUpload = true }
    LaunchedEffect(feed) { reels = emptyList(); offset = 0; hasMore = true; load() }
    // Deep-link: jump to a reel (from a notification) once it is loaded.
    LaunchedEffect(reels, focusReelId) {
        val target = focusReelId ?: return@LaunchedEffect
        val idx = reels.indexOfFirst { it.id == target }
        if (idx >= 0) { scope.launch { listState.scrollToItem(idx) }; onFocusConsumed() }
    }
    // Load more when the user nears the end of the loaded reels.
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { page ->
            if (reels.isNotEmpty() && page >= reels.size - 3) load()
        }
    }
    // View counting: 3s of the current reel actually on screen, once.
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { page ->
            val reel = reels.getOrNull(page) ?: return@collect
            if (reel.id !in viewedIds) {
                delay(3000)
                if (listState.firstVisibleItemIndex == page && reel.id !in viewedIds) {
                    viewedIds = viewedIds + reel.id
                    try { api2.recordReelView(reel.id, mapOf("watchSec" to 3)) } catch (_: Exception) {}
                }
            }
        }
    }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("foryou" to "For You", "following" to "Following").forEach { (v, label) ->
                val sel = feed == v
                Box(Modifier.clip(RoundedCornerShape(28.dp)).background(if (sel) Color.White else Color.White.copy(alpha = 0.15f)).clickable { if (feed != v) feed = v }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Text(label, style = MaterialTheme.typography.bodyMedium, color = if (sel) Color.Black else Color.White)
                }
                Spacer(Modifier.width(8.dp))
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { muted = !muted }) { Icon(if (muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp, contentDescription = if (muted) "Unmute" else "Mute", tint = Color.White) }
            OutlinedButton(onClick = { showUpload = true }, shape = RoundedCornerShape(28.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White), border = androidx.compose.foundation.BorderStroke(1.dp, Color.White)) { Text("Upload reel") }
        }
        if (error != null && reels.isEmpty()) { Text(error!!, color = Color.White, modifier = Modifier.padding(16.dp)) }
        if (loading && reels.isEmpty()) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { DotsLoading() } }
        if (reels.isNotEmpty()) {
            LazyColumn(state = listState, flingBehavior = flingBehavior, modifier = Modifier.weight(1f).fillMaxWidth()) {
                itemsIndexed(reels, key = { _, r -> r.id }) { page, reel ->
                Box(Modifier.fillParentMaxSize()) {
                ReelPage(
                    api2 = api2, reel = reel, myId = myId,
                    isActive = page == listState.firstVisibleItemIndex,
                    muted = muted,
                    commentCount = reelCommentCounts[reel.id] ?: reel.commentCount,
                    onAuthor = onAuthor,
                    onComment = { reelComment = reel },
                    onDeleted = { reels = reels.filter { it.id != reel.id } },
                )
                }
                }
            }
        } else if (!loading && error == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No reels yet", color = Color.White) }
        }
    }
}

// v2.9: relative time for reel comments (commentTimeAgo is private to Screens.kt).
private fun reelTimeAgo(createdAt: String?): String {
    if (createdAt.isNullOrBlank()) return ""
    val formats = listOf(
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US),
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US),
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", java.util.Locale.US),
    )
    var millis: Long? = null
    for (f in formats) {
        f.timeZone = java.util.TimeZone.getTimeZone("UTC")
        try { millis = f.parse(createdAt)?.time; if (millis != null) break } catch (_: Exception) {}
    }
    if (millis == null) return ""
    val seconds = ((System.currentTimeMillis() - millis) / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "now"
        seconds < 3600 -> "${seconds / 60}m"
        seconds < 86400 -> "${seconds / 3600}h"
        seconds < 604800 -> "${seconds / 86400}d"
        seconds < 2592000 -> "${seconds / 604800}w"
        seconds < 31536000 -> "${seconds / 2592000}mo"
        else -> "${seconds / 31536000}y"
    }
}

// v2.9: compact counts for the reel rail (formatCount is private to Screens.kt).
private fun reelCount(n: Int): String {
    if (n < 1000) return "$n"
    if (n < 1000000) { val k = n / 1000.0; return if (k % 1.0 == 0.0) "${k.toInt()}k" else "%.1fk".format(k) }
    val m = n / 1000000.0; return if (m % 1.0 == 0.0) "${m.toInt()}M" else "%.1fM".format(m)
}

@Composable
private fun ReelPage(api2: FrianzoApi2, reel: Reel, myId: String?, isActive: Boolean, muted: Boolean, commentCount: Int, onAuthor: (String) -> Unit, onComment: () -> Unit, onDeleted: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var liked by remember(reel.id) { mutableStateOf(reel.liked) }
    var likes by remember(reel.id) { mutableStateOf(reel.likeCount) }
    var fav by remember(reel.id) { mutableStateOf(reel.favorited) }
    var paused by remember { mutableStateOf(false) }
    var burst by remember { mutableStateOf(false) }
    var prepared by remember { mutableStateOf(false) }
    var durationMs by remember { mutableStateOf(0) }
    var positionMs by remember { mutableStateOf(0) }
    // NOTE: player/prepared are set ONLY in VideoView callbacks (never in
    // the AndroidView factory — writing state during composition is not
    // allowed). The progress ticker reads player.currentPosition.
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var moreOpen by remember { mutableStateOf(false) }
    val burstScale by animateFloatAsState(if (burst) 1.4f else 1f, label = "heartBurst")
    fun doLike() {
        scope.launch {
            try {
                val r = api2.toggleReelLike(reel.id)
                if (r.success && r.data != null) { liked = r.data.liked; likes = r.data.likeCount }
            } catch (_: Exception) {}
        }
    }
    LaunchedEffect(burst) { if (burst) { delay(700); burst = false } }
    // Progress ticker: reads the real VideoView position while active.
    LaunchedEffect(isActive, prepared) {
        while (isActive && prepared) {
            delay(200)
            positionMs = player?.currentPosition ?: 0
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (reel.videoUrl != null) {
            key(reel.id) {
                AndroidView(
                    factory = { ctx -> VideoView(ctx).apply {
                        setVideoURI(Uri.parse(reel.videoUrl))
                        setOnPreparedListener { mp -> player = mp; durationMs = mp.duration; prepared = true; mp.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f); if (isActive && !paused) start() }
                        setOnCompletionListener { v -> v.seekTo(0); v.start() }
                    } },
                    modifier = Modifier.fillMaxSize().pointerInput(reel.id) {
                        detectTapGestures(
                            onTap = { paused = !paused },
                            onDoubleTap = { if (!liked) doLike(); burst = true },
                        )
                    },
                    update = { view ->
                        player?.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f)
                        if (isActive && !paused) { if (!view.isPlaying) view.start() } else view.pause()
                    },
                    onRelease = { player = null; it.stopPlayback() },
                )
            }
            if (!prepared) {
                reel.thumbnailUrl?.let { AsyncImage(model = it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { DotsLoading() }
            }
        } else {
            reel.thumbnailUrl?.let { AsyncImage(model = it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
        }
        if (paused && prepared) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Paused", tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(72.dp))
            }
        }
        if (burst) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Favorite, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(96.dp).graphicsLayer(scaleX = burstScale, scaleY = burstScale))
            }
        }
        // Progress bar (top of the reel).
        if (prepared && durationMs > 0) {
            LinearProgressIndicator(progress = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth().height(3.dp), color = Color.White, trackColor = Color.White.copy(alpha = 0.3f))
        }
        // Right rail: Like / Comment / Share / Views / Save / More.
        Column(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp, bottom = 96.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = { doLike() }) { Icon(if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, contentDescription = "Like", tint = if (liked) Color(0xFFEF4444) else Color.White, modifier = Modifier.size(32.dp)) }
                Text(reelCount(likes), color = Color.White, fontSize = 12.sp)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = onComment) { Icon(Icons.Filled.Chat, contentDescription = "Comments", tint = Color.White, modifier = Modifier.size(30.dp)) }
                Text(reelCount(commentCount), color = Color.White, fontSize = 12.sp)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = {
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, "https://frianzo.online/reel/${reel.id}") }
                    context.startActivity(android.content.Intent.createChooser(intent, "Share reel"))
                }) { Icon(Icons.Filled.Share, contentDescription = "Share", tint = Color.White, modifier = Modifier.size(30.dp)) }
                Text(reelCount(reel.shareCount), color = Color.White, fontSize = 12.sp)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Visibility, contentDescription = "Views", tint = Color.White, modifier = Modifier.size(28.dp))
                Text(reelCount(reel.viewCount), color = Color.White, fontSize = 12.sp)
            }
            IconButton(onClick = { scope.launch { try { val r = api2.toggleReelFavorite(reel.id); if (r.success && r.data != null) fav = r.data.favorited } catch (_: Exception) {} } }) { Icon(if (fav) Icons.Filled.Star else Icons.Outlined.StarBorder, contentDescription = "Save reel", tint = if (fav) Color(0xFFF59E0B) else Color.White, modifier = Modifier.size(28.dp)) }
            Box {
                IconButton(onClick = { moreOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = Color.White) }
                DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                    DropdownMenuItem(text = { Text("Copy link") }, onClick = { moreOpen = false; val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager; cm.setPrimaryClip(android.content.ClipData.newPlainText("reel", "https://frianzo.online/reel/${reel.id}")) })
                    DropdownMenuItem(text = { Text("Report") }, onClick = { moreOpen = false; scope.launch { try { api2.reportReel(reel.id, mapOf("reason" to "other")) } catch (_: Exception) {} } })
                    if (reel.author?.id == myId) DropdownMenuItem(text = { Text("Delete reel", color = Color.Red) }, onClick = { moreOpen = false; scope.launch { try { if (api2.deleteReel(reel.id).success) onDeleted() } catch (_: Exception) {} } })
                }
            }
        }
        // Bottom-left: author + caption pill.
        Column(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 72.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { reel.author?.username?.let { onAuthor(it) } }) {
                FrianzoAvatar(reel.author?.profilePictureUrl, 40.dp)
                Spacer(Modifier.width(8.dp))
                Text("@${reel.author?.username ?: ""}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            reel.caption?.let { Spacer(Modifier.height(8.dp)); Text(it, color = Color.White, fontSize = 14.sp, maxLines = 3) }
            reel.category?.let { Spacer(Modifier.height(6.dp)); Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.2f)).padding(horizontal = 8.dp, vertical = 2.dp)) { Text(it, color = Color.White, fontSize = 12.sp) } }
        }
    }
}

// ---------- REEL COMMENTS (v2.9): bottom sheet like the WEB reel comments ----------
// Web reel comments are DIFFERENT from post comments: 78vh sheet, true-total
// header count, plain-text rows (no bubble), no Follow pill, no sort, 500-char
// limit, infinite scroll (cursor pagination). Rows: 32px avatar, name +
// timestamp, Like / Reply / count, "..." menu (Edit / Delete / Copy /
// Report). Replies use parentCommentId. Backend DTO (fresh reelService):
// { comments: [{ id, content, createdAt, author, reactionCount, myReaction,
// replies: [...] }], pagination: { hasMore, nextCursor } }.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReelCommentsDialog(api2: FrianzoApi2, reel: Reel, myId: String? = null, onClose: () -> Unit, onCommentAdded: () -> Unit = {}) {
    var comments by remember { mutableStateOf<List<ReelComment>>(emptyList()) }
    var draft by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var replyTo by remember { mutableStateOf<ReelComment?>(null) }
    var total by remember { mutableStateOf(reel.commentCount) }
    var nextCursor by remember { mutableStateOf<String?>(null) }
    var hasMore by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var expandedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val scope = rememberCoroutineScope()
    fun load(cursor: String? = null) {
        if (cursor != null) loadingMore = true
        scope.launch {
            try {
                val res = api2.reelComments(reel.id, cursor)
                if (res.success && res.data != null) {
                    comments = if (cursor == null) res.data.comments else comments + res.data.comments
                    nextCursor = res.data.pagination?.nextCursor
                    hasMore = res.data.pagination?.hasMore == true
                } else error = res.error?.message
            } catch (e: Exception) { error = e.message }
            loadingMore = false
        }
    }
    LaunchedEffect(reel.id) { load() }
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.78f).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Text("$total comments", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close comments", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            HorizontalDivider(color = FrianzoBorder)
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                if (comments.isEmpty() && error == null) item { Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) { Text("No comments yet. Be the first to comment.", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                items(comments, key = { it.id }) { c ->
                    ReelCommentRow(api2, c, reel, myId, onReply = { replyTo = it }, onChanged = { load() }, onDeleted = { total = (total - 1).coerceAtLeast(0) })
                    val replies = c.replies
                    if (replies.isNotEmpty()) {
                        val isExpanded = c.id in expandedIds
                        Column(Modifier.fillMaxWidth().padding(start = 40.dp, top = 2.dp)) {
                            if (!isExpanded) {
                                TextButton(onClick = { expandedIds = expandedIds + c.id }, contentPadding = PaddingValues(0.dp)) { Text("View ${replies.size} ${if (replies.size == 1) "reply" else "replies"}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            } else {
                                replies.forEach { r ->
                                    ReelCommentRow(api2, r, reel, myId, isReply = true, parentAuthor = c.author, onReply = { replyTo = it }, onChanged = { load() }, onDeleted = { total = (total - 1).coerceAtLeast(0) })
                                }
                                TextButton(onClick = { expandedIds = expandedIds - c.id }, contentPadding = PaddingValues(0.dp)) { Text("Hide replies", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                }
                if (hasMore) item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        if (loadingMore) DotsLoading() else OutlinedButton(onClick = { load(nextCursor) }, shape = RoundedCornerShape(28.dp)) { Text("Load more comments") }
                    }
                }
                if (error != null) item { Text(error!!, fontSize = 13.sp, color = Color.Red, modifier = Modifier.padding(vertical = 8.dp)) }
            }
            if (replyTo != null) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(FrianzoBlueTint).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = androidx.compose.ui.text.buildAnnotatedString {
                            append("Replying to ")
                            withStyle(androidx.compose.ui.text.SpanStyle(color = FrianzoBlue, fontWeight = FontWeight.SemiBold)) { append("@${replyTo!!.author?.username ?: "User"}") }
                        },
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { replyTo = null }, modifier = Modifier.size(24.dp)) { Icon(Icons.Filled.Close, contentDescription = "Cancel reply", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Spacer(Modifier.height(8.dp))
            }
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { if (it.length <= 500) draft = it },
                    placeholder = { Text("Add a comment...") },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    maxLines = 4,
                    singleLine = false,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color.White, unfocusedContainerColor = Color.White, focusedBorderColor = FrianzoBorder, unfocusedBorderColor = FrianzoBorder),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = draft.isNotBlank(),
                    modifier = Modifier.height(48.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A), contentColor = Color.White),
                    onClick = {
                        val text = draft; val parent = replyTo?.id; draft = ""; replyTo = null
                        scope.launch {
                            try {
                                val res = api2.addReelComment(reel.id, CommentCreateRequest(text, parent))
                                if (res.success) { total += 1; onCommentAdded(); load() } else error = res.error?.message ?: "Could not send comment"
                            } catch (e: Exception) { error = e.message; draft = text }
                        }
                    },
                ) { Text("Send") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ReelCommentRow(api2: FrianzoApi2, c: ReelComment, reel: Reel, myId: String?, isReply: Boolean = false, parentAuthor: Author? = null, onReply: (ReelComment) -> Unit, onChanged: () -> Unit, onDeleted: () -> Unit = {}) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var editDraft by rememberSaveable { mutableStateOf(c.content ?: "") }
    var localReaction by remember { mutableStateOf(c.myReaction) }
    var localCount by remember { mutableStateOf(c.reactionCount) }
    var menuOpen by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var reported by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    val isOwner = myId != null && c.author?.id == myId
    val canDelete = isOwner || (myId != null && reel.author?.id == myId)
    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            FrianzoAvatar(c.author?.profilePictureUrl, 32.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                // Plain-text row (web reel comments): no bubble, no Follow pill.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.author?.displayName ?: c.author?.username ?: "User", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    if (c.author?.isVerified == true) { Spacer(Modifier.width(4.dp)); Icon(Icons.Filled.CheckCircle, contentDescription = "Verified", modifier = Modifier.size(14.dp), tint = FrianzoBlue) }
                    Spacer(Modifier.width(8.dp))
                    Text(reelTimeAgo(c.createdAt), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(2.dp))
                if (editing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = editDraft, onValueChange = { if (it.length <= 500) editDraft = it }, modifier = Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(10.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = FrianzoBorder, unfocusedBorderColor = FrianzoBorder))
                        TextButton(onClick = { scope.launch { try { if (api2.editReelComment(c.id, mapOf("content" to editDraft)).success) { editing = false; onChanged() } } catch (_: Exception) {} } }) { Text("Save", fontSize = 13.sp, color = FrianzoBlue) }
                        TextButton(onClick = { editing = false; editDraft = c.content ?: "" }) { Text("Cancel", fontSize = 13.sp) }
                    }
                } else {
                    val prefix = if (isReply && parentAuthor != null && !(c.content ?: "").trim().lowercase().startsWith("@${parentAuthor.username?.lowercase()}")) "@${parentAuthor.username} " else ""
                    Text(
                        text = androidx.compose.ui.text.buildAnnotatedString {
                            if (prefix.isNotEmpty()) { withStyle(androidx.compose.ui.text.SpanStyle(color = FrianzoBlue, fontWeight = FontWeight.SemiBold)) { append(prefix) } }
                            append(c.content ?: "")
                        },
                        fontSize = 14.sp,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        // Backend returns { reaction } only; toggle "like"
                        // locally and revert on failure.
                        val prevReaction = localReaction; val prevCount = localCount
                        val removing = prevReaction == "like"
                        val switching = prevReaction != null && !removing
                        localReaction = if (removing) null else "like"
                        localCount = (prevCount + if (removing) -1 else if (switching) 0 else 1).coerceAtLeast(0)
                        scope.launch { try { if (!api2.reelCommentReaction(c.id, mapOf("type" to "like")).success) { localReaction = prevReaction; localCount = prevCount } } catch (_: Exception) { localReaction = prevReaction; localCount = prevCount } }
                    }, contentPadding = PaddingValues(0.dp)) {
                        if (localReaction != null) { ReactionIcon(localReaction, 16.dp); Spacer(Modifier.width(4.dp)) }
                        Text("Like", fontSize = 13.sp, fontWeight = if (localReaction != null) FontWeight.SemiBold else FontWeight.Normal, color = if (localReaction != null) FrianzoBlue else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(12.dp))
                    TextButton(onClick = { onReply(c) }, contentPadding = PaddingValues(0.dp)) { Text("Reply", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (localCount > 0) { Spacer(Modifier.width(12.dp)); Text(reelCount(localCount), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Spacer(Modifier.weight(1f))
                    Box {
                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(24.dp)) { Icon(Icons.Filled.MoreVert, contentDescription = "More", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (isOwner) DropdownMenuItem(text = { Text("Edit") }, onClick = { menuOpen = false; editDraft = c.content ?: ""; editing = true })
                            if (canDelete) DropdownMenuItem(text = { Text("Delete", color = Color.Red) }, onClick = { menuOpen = false; showDelete = true })
                            DropdownMenuItem(text = { Text("Copy") }, onClick = { menuOpen = false; clipboard.setText(AnnotatedString(c.content ?: "")) })
                            DropdownMenuItem(text = { Text(if (reported) "Reported" else "Report") }, onClick = {
                                menuOpen = false
                                scope.launch { try { if (api2.reportReelComment(c.id, mapOf("reason" to "other")).success) reported = true } catch (_: Exception) {} }
                            })
                        }
                    }
                }
                if (reported) Text("Comment reported. Thanks for letting us know.", fontSize = 12.sp, color = Color(0xFF16A34A))
                if (deleteError != null) Text(deleteError!!, fontSize = 12.sp, color = Color.Red)
            }
        }
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Delete comment?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    scope.launch {
                        try {
                            val r = api2.deleteReelComment(c.id)
                            if (r.success) { onChanged(); onDeleted() }
                            else deleteError = r.error?.message ?: "Could not delete comment"
                        } catch (e: Exception) { deleteError = e.message ?: "Could not delete comment" }
                    }
                }) { Text("Delete", color = Color.Red) }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } },
        )
    }
}

// ---------- REEL UPLOAD: video + caption + category (POST /api/reels) ----------
@Composable
private fun ReelUploadScreen(api2: FrianzoApi2, initialVideoUri: Uri? = null, onDone: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var videoUri by remember { mutableStateOf<Uri?>(initialVideoUri) }
    var caption by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("") }
    var maxSec by remember { mutableStateOf(180) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { scope.launch { try { val r = api2.reelsConfig(); if (r.success && r.data != null) maxSec = r.data.maxDurationSec } catch (_: Exception) {} } }
    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri -> if (uri != null) videoUri = uri }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("Cancel") }
            Text("Upload reel", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(PickVisualMedia.VideoOnly)) }, shape = RoundedCornerShape(28.dp)) { Text(if (videoUri == null) "Pick a video" else "Video selected") }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = caption, onValueChange = { if (it.length <= 500) caption = it }, label = { Text("Caption (optional)") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = category, onValueChange = { category = it }, label = { Text("Category (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Text("Max length: ${maxSec}s", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Button(enabled = !busy && videoUri != null, onClick = {
            busy = true
            scope.launch {
                try {
                    val uri = videoUri!!
                    val durMs = try { val mmr = MediaMetadataRetriever(); mmr.setDataSource(context, uri); (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 15000L).also { mmr.release() } } catch (_: Exception) { 15000L }
                    val durationSec = ((durMs / 1000).toInt()).coerceIn(1, maxSec)
                    val input = context.contentResolver.openInputStream(uri) ?: throw Exception("Could not read the video")
                    val mime = try { context.contentResolver.getType(uri) } catch (_: Exception) { null } ?: "video/mp4"
                    val tmp = File(context.cacheDir, "reel_upload.mp4")
                    input.use { inp -> tmp.outputStream().use { out -> inp.copyTo(out) } }
                    val videoPart = MultipartBody.Part.createFormData("video", tmp.name, tmp.asRequestBody(mime.toMediaTypeOrNull()))
                    val res = api2.uploadReel(videoPart, caption.ifBlank { null }?.toRequestBody("text/plain".toMediaTypeOrNull()), category.ifBlank { null }?.toRequestBody("text/plain".toMediaTypeOrNull()), durationSec.toString().toRequestBody("text/plain".toMediaTypeOrNull()))
                    try { tmp.delete() } catch (_: Exception) {}
                    if (!res.success) throw Exception(res.error?.message ?: "Upload failed")
                    onDone()
                } catch (e: Exception) { message = e.message ?: "Upload failed" }
                finally { busy = false }
            }
        }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(28.dp)) { Text(if (busy) "Uploading..." else "Upload reel") }
        message?.let { Spacer(Modifier.height(12.dp)); Text(it, color = FrianzoBadgeRed) }
        if (busy) { Spacer(Modifier.height(12.dp)); DotsLoading() }
    }
}

// ---------- CREATE POST: text + optional photo ----------
@Composable
fun CreatePostScreen(api2: FrianzoApi2, initialUri: android.net.Uri? = null, onPosted: () -> Unit, onClose: () -> Unit = {}) {
    val context = LocalContext.current
    var showDiscard by remember { mutableStateOf(false) }
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
    var bgStyle by rememberSaveable { mutableStateOf<String?>(null) }
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            // v2.9: Cancel / Discard — confirms first when the post is dirty.
            TextButton(onClick = { if (text.isNotBlank() || imageUri != null || bgStyle != null) showDiscard = true else onClose() }) { Text("Cancel") }
            Text("New post", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("What's on your mind?") }, modifier = Modifier.fillMaxWidth().height(140.dp), shape = RoundedCornerShape(12.dp))
        if (imageUri == null) {
            Spacer(Modifier.height(8.dp))
            Text("Text background", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf("sunset" to Color(0xFFF97316), "ocean" to Color(0xFF0EA5E9), "violet" to Color(0xFF8B5CF6), "mint" to Color(0xFF10B981), "peach" to Color(0xFFFBBF24), "night" to Color(0xFF1E293B), "rose" to Color(0xFFF43F5E), "sky" to Color(0xFF38BDF8))) { (style, color) ->
                    Box(Modifier.size(40.dp).clip(CircleShape).background(color).clickable { bgStyle = if (bgStyle == style) null else style }.border(if (bgStyle == style) 3.dp else 0.dp, if (bgStyle == style) FrianzoBlue else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
                        Text(style.take(1).uppercase(), color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    }
                }
            }
        }
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
                    val res = if (bgStyle != null && part == null) api2.createPostWithBg(text.toRequestBody("text/plain".toMediaTypeOrNull()), bgStyle!!.toRequestBody("text/plain".toMediaTypeOrNull()))
                    else api2.createPost(text.toRequestBody("text/plain".toMediaTypeOrNull()), part)
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
    if (showDiscard) {
        AlertDialog(
            onDismissRequest = { showDiscard = false },
            title = { Text("Discard post?") },
            text = { Text("Your post will be lost.") },
            confirmButton = { TextButton(onClick = { showDiscard = false; onClose() }) { Text("Discard", color = Color.Red) } },
            dismissButton = { TextButton(onClick = { showDiscard = false }) { Text("Keep editing") } },
        )
    }
}

// ---------- CHAT: list + thread (REST polling; live socket = later stage) ----------
@Composable
fun ChatListScreen(api2: FrianzoApi2, onOpen: (String, String) -> Unit) {
    var list by remember { mutableStateOf<List<Conversation>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var q by rememberSaveable { mutableStateOf("") }
    var found by remember { mutableStateOf<List<PublicProfile>>(emptyList()) }
    val scope = rememberCoroutineScope()
    fun loadList() {
        scope.launch {
            try {
                val res = api2.conversations()
                if (res.success && res.data != null) list = res.data else error = res.error?.message
            } catch (e: Exception) { error = e.message }
        }
    }
    LaunchedEffect(Unit) { loadList() }
    LaunchedEffect(q) {
        if (q.isBlank()) { found = emptyList(); return@LaunchedEffect }
        delay(300)
        scope.launch {
            try { val r = api2.searchUsers(q); if (r.success && r.data != null) found = r.data.users } catch (_: Exception) {}
        }
    }
    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentPadding = PaddingValues(vertical = 8.dp)) {
        item { Text("Chats", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        item {
            OutlinedTextField(value = q, onValueChange = { q = it }, label = { Text("Search people to message") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true, shape = RoundedCornerShape(28.dp))
        }
        if (q.isNotBlank()) {
            items(found, key = { it.id ?: it.username ?: "" }) { u ->
                ListItem(
                    headlineContent = { Text(u.displayName ?: u.username ?: "", style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text("@${u.username ?: ""}", style = MaterialTheme.typography.bodySmall) },
                    leadingContent = { FrianzoAvatar(u.profilePictureUrl, 48.dp) },
                    modifier = Modifier.clickable {
                        if (u.id != null) scope.launch {
                            try { val r = api2.createConversation(CreateConversationRequest(u.id)); if (r.success && r.data != null) { q = ""; onOpen(r.data.id, u.displayName ?: u.username ?: "") } } catch (_: Exception) {}
                        }
                    },
                )
                HorizontalDivider()
            }
        } else {
            items(list, key = { it.id }) { c ->
                val other = c.otherUser
                val last = c.lastMessage?.content ?: c.messages?.firstOrNull()?.content ?: ""
                ListItem(
                    headlineContent = { Text(other?.displayName ?: other?.username ?: "Chat", style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text(last, style = MaterialTheme.typography.bodySmall) },
                    leadingContent = { FrianzoAvatar(other?.profilePictureUrl, 48.dp) },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (c.unreadCount > 0) Badge(containerColor = FrianzoBadgeRed) { Text("${c.unreadCount}") }
                            IconButton(onClick = { scope.launch { try { if (api2.deleteConversation(c.id).success) list = list.filter { it.id != c.id } } catch (_: Exception) {} } }) { Icon(Icons.Filled.Delete, contentDescription = "Delete chat", modifier = Modifier.size(20.dp)) }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.clickable { onOpen(c.id, other?.displayName ?: other?.username ?: "") },
                )
                HorizontalDivider()
            }
            if (error != null) item { Text(error!!, Modifier.padding(16.dp)) }
            if (list.isEmpty() && error == null) item { Text("No chats yet", Modifier.padding(16.dp)) }
        }
    }
}

@Composable
fun ChatThreadScreen(api2: FrianzoApi2, conversationId: String, title: String, myId: String?, onBack: () -> Unit = {}) {
    var messages by remember { mutableStateOf<List<ConversationMessage>>(emptyList()) }
    var draft by rememberSaveable { mutableStateOf("") }
    var loadError by remember { mutableStateOf<String?>(null) }
    var sendError by remember { mutableStateOf<String?>(null) }
    var otherUserId by remember { mutableStateOf<String?>(null) }
    var isBlocked by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var showTimer by remember { mutableStateOf(false) }
    var timerLabel by remember { mutableStateOf("Off") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(conversationId) {
        scope.launch {
            try {
                val res = api2.conversations()
                val other = res.data?.firstOrNull { it.id == conversationId }?.otherUser
                otherUserId = other?.id
                if (other?.id != null) { try { val bs = api2.blockStatus(other.id); isBlocked = bs.data?.blockedByMe == true } catch (_: Exception) {} }
            } catch (_: Exception) {}
        }
    }
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
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(vertical = 16.dp))
            if (timerLabel != "Off") Text(timerLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Chat options") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Disappearing messages: $timerLabel") }, onClick = { menuOpen = false; showTimer = true })
                    if (otherUserId != null) DropdownMenuItem(text = { Text(if (isBlocked) "Unblock" else "Block") }, onClick = {
                        menuOpen = false
                        scope.launch {
                            try {
                                if (isBlocked) { if (api2.unblockUser(otherUserId!!).success) isBlocked = false }
                                else if (api2.blockUser(otherUserId!!).success) isBlocked = true
                            } catch (_: Exception) {}
                        }
                    })
                    DropdownMenuItem(text = { Text("Delete chat", color = Color.Red) }, onClick = {
                        menuOpen = false
                        scope.launch { try { if (api2.deleteConversation(conversationId).success) onBack() } catch (_: Exception) {} }
                    })
                }
            }
        }
        if (showTimer) {
            AlertDialog(onDismissRequest = { showTimer = false }, title = { Text("Disappearing messages") },
                text = { Column { listOf(null to "Off", 3600 to "1 hour", 86400 to "1 day", 604800 to "1 week").forEach { (secs, label) ->
                    TextButton(onClick = { showTimer = false; timerLabel = label; scope.launch { try { api2.setDisappearing(conversationId, mapOf<String, Any?>("duration" to secs)) } catch (_: Exception) {} } }, modifier = Modifier.fillMaxWidth()) { Text(label, modifier = Modifier.fillMaxWidth()) }
                } } },
                confirmButton = {}, dismissButton = { TextButton(onClick = { showTimer = false }) { Text("Cancel") } })
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
// v2.9: tapping a notification deep-links — postId opens the post detail,
// reelId opens that reel, a message opens the chat with the actor, a follow
// opens the actor's profile. It no longer always opens the actor profile.
fun NotificationsScreen(api2: FrianzoApi2, onAuthor: (String) -> Unit = {}, onOpenPost: (String) -> Unit = {}, onOpenReel: (String) -> Unit = {}, onOpenChat: (String, String) -> Unit = { _, _ -> }) {
    var items by remember { mutableStateOf<List<NotificationItem>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf("All") }
    var showMarkAllConfirm by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showDeleteAllConfirm by remember { mutableStateOf(false) }
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
            TextButton(onClick = { selecting = !selecting; if (!selecting) selected = emptySet() }) { Text(if (selecting) "Cancel" else "Select") }
            TextButton(onClick = { showMarkAllConfirm = true }) { Text("Mark all read") }
            TextButton(onClick = { showDeleteAllConfirm = true }) { Text("Delete all", color = Color.Red) }
        }
        if (selecting && selected.isNotEmpty()) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${selected.size} selected", modifier = Modifier.weight(1f))
                Button(onClick = { scope.launch { try { if (api2.deleteNotifications(DeleteIdsRequest(selected.toList())).success) { items = items.filter { it.id !in selected }; selected = emptySet(); selecting = false } } catch (_: Exception) {} } }, colors = ButtonDefaults.buttonColors(containerColor = FrianzoBadgeRed)) { Text("Delete selected") }
            }
        }
        if (showDeleteAllConfirm) {
            AlertDialog(onDismissRequest = { showDeleteAllConfirm = false }, title = { Text("Delete all notifications?") }, text = { Text("This can't be undone.") },
                confirmButton = { Button(onClick = { showDeleteAllConfirm = false; scope.launch { try { if (api2.deleteAllNotifications().success) items = emptyList() } catch (_: Exception) {} } }, colors = ButtonDefaults.buttonColors(containerColor = FrianzoBadgeRed)) { Text("Delete all") } },
                dismissButton = { TextButton(onClick = { showDeleteAllConfirm = false }) { Text("Cancel") } })
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
                    trailingContent = { if (!n.read) Badge(containerColor = FrianzoBadgeRed) { Text("new") } },
                    leadingContent = { if (selecting) Checkbox(checked = n.id in selected, onCheckedChange = { checked -> selected = if (checked) selected + n.id else selected - n.id }) else FrianzoAvatar(n.actor?.profilePictureUrl, 48.dp) },
                    colors = ListItemDefaults.colors(containerColor = if (!n.read) FrianzoBlueTint else MaterialTheme.colorScheme.surface),
                    modifier = Modifier.clickable {
                        if (selecting) { selected = if (n.id in selected) selected - n.id else selected + n.id }
                        else {
                            scope.launch { try { api2.markNotificationRead(n.id); load() } catch (_: Exception) {} }
                            when {
                                n.postId != null -> onOpenPost(n.postId)
                                n.reelId != null -> onOpenReel(n.reelId)
                                n.type == "message" && n.actor?.id != null -> {
                                    // No conversation id on the notification:
                                    // open (or create) the DM with the actor.
                                    scope.launch {
                                        try {
                                            val r = api2.createConversation(CreateConversationRequest(n.actor!!.id!!))
                                            val cid = r.data?.id
                                            if (cid != null) onOpenChat(cid, n.actor?.displayName ?: n.actor?.username ?: "Chat")
                                            else n.actor?.username?.let { onAuthor(it) }
                                        } catch (_: Exception) { n.actor?.username?.let { onAuthor(it) } }
                                    }
                                }
                                else -> n.actor?.username?.let { onAuthor(it) }
                            }
                        }
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
fun ProfileScreen(api2: FrianzoApi2, username: String?, myId: String?, onLogout: (() -> Unit)? = null, onBack: (() -> Unit)? = null, onOpenChat: ((String, String) -> Unit)? = null, feedVm: FeedViewModel? = null, onUser: ((String) -> Unit)? = null) {
    var profile by remember { mutableStateOf<PublicProfile?>(null) }
    var posts by remember { mutableStateOf<List<Post>>(emptyList()) }
    var postsLoading by remember { mutableStateOf(false) }
    var postsError by remember { mutableStateOf<String?>(null) }
    var following by remember { mutableStateOf(false) }
    var followStatus by remember { mutableStateOf<String?>(null) }
    var showEdit by remember { mutableStateOf(false) }
    var editError by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var followList by remember { mutableStateOf<String?>(null) }
    var detailPostId by remember { mutableStateOf<String?>(null) }
    var photoMsg by remember { mutableStateOf<String?>(null) }
    // v2.9: real follow counts, highlights, per-post like state.
    var followCounts by remember { mutableStateOf<FollowCountsData?>(null) }
    var highlights by remember { mutableStateOf<List<Highlight>>(emptyList()) }
    var highlightViewer by remember { mutableStateOf<Highlight?>(null) }
    var showHighlightCreate by remember { mutableStateOf(false) }
    var ownStatusItems by remember { mutableStateOf<List<StatusItem>>(emptyList()) }
    var highlightError by remember { mutableStateOf<String?>(null) }
    var postReactions by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var postLikeCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val avatarPicker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            try {
                val part = uriToImagePart(context, uri, "avatar_upload")
                if (api2.uploadAvatar(part).success) photoMsg = "Photo updated — reopen profile to see it." else photoMsg = "Photo upload failed"
            } catch (e: Exception) { photoMsg = e.message }
        }
    }
    val coverPicker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            try {
                val part = uriToImagePart(context, uri, "cover_upload")
                if (api2.uploadCover(part).success) photoMsg = "Cover updated." else photoMsg = "Cover upload failed"
            } catch (e: Exception) { photoMsg = e.message }
        }
    }
    followList?.let { dir -> FollowListScreen(api2, profile?.id ?: "", dir, onUser = { u -> followList = null; onUser?.invoke(u) }, onClose = { followList = null }) }
    highlightViewer?.let { h ->
        HighlightViewerDialog(api2, h, username == null, onClose = { highlightViewer = null }, onDeleted = {
            highlightViewer = null
            scope.launch { try { val r = api2.highlights(profile?.username ?: ""); if (r.success && r.data != null) highlights = r.data.highlights ?: emptyList() } catch (_: Exception) {} }
        })
    }
    if (showHighlightCreate) {
        HighlightCreateDialog(api2, ownStatusItems, onClose = { showHighlightCreate = false }, onCreated = {
            showHighlightCreate = false
            scope.launch { try { val r = api2.highlights(profile?.username ?: ""); if (r.success && r.data != null) highlights = r.data.highlights ?: emptyList() } catch (_: Exception) {} }
        })
    }
    if (detailPostId != null && feedVm != null) {
        PostDetailScreen(feedVm, detailPostId!!, myId, onClose = { detailPostId = null }, onAuthor = { u -> detailPostId = null; onUser?.invoke(u) }, onOpenChat = { id, name -> detailPostId = null; onOpenChat?.invoke(id, name) })
    }
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
                    if (res.data.user.id != null) {
                        try { val fc = api2.followCounts(res.data.user.id); if (fc.success && fc.data != null) followCounts = fc.data } catch (_: Exception) {}
                    }
                    if (uname != null) {
                        try { val hr = api2.highlights(uname); if (hr.success && hr.data != null) highlights = hr.data.highlights ?: emptyList() } catch (e: Exception) { highlightError = e.message }
                    }
                    if (username == null) {
                        try {
                            val sf = api2.statusFeed()
                            val own = sf.data?.feed?.firstOrNull { it.userId == myId || it.user?.id == myId }
                            ownStatusItems = own?.items ?: emptyList()
                        } catch (_: Exception) {}
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
                        // v2.9: website is a real link (was plain blue Text).
                        p.website?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = FrianzoBlue, modifier = Modifier.clickable { openWebUrl(context, it) }) }
                        val loc = listOfNotNull(p.city?.takeIf { it.isNotBlank() }, countryDisplayName(p.country)).joinToString(", ")
                        if (loc.isNotBlank()) Text(loc, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${posts.size} Posts", style = MaterialTheme.typography.titleMedium)
                            // v2.9: real counts from GET /api/follows/{id}/counts
                            // (PublicProfile itself returns NO counts).
                            val fc = followCounts?.followers ?: (p.followerCount ?: p.followersCount)
                            if (fc != null) { Spacer(Modifier.width(16.dp)); Text("$fc Followers", style = MaterialTheme.typography.titleMedium, modifier = Modifier.clickable { if (p.id != null) followList = "followers" }) }
                            val fg = followCounts?.following ?: (p.followingCount ?: p.followingsCount)
                            if (fg != null) { Spacer(Modifier.width(16.dp)); Text("$fg Following", style = MaterialTheme.typography.titleMedium, modifier = Modifier.clickable { if (p.id != null) followList = "following" }) }
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
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { avatarPicker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) }, shape = RoundedCornerShape(28.dp)) { Text("Change photo") }
                                    OutlinedButton(onClick = { coverPicker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) }, shape = RoundedCornerShape(28.dp)) { Text("Change cover") }
                                }
                                photoMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = FrianzoBlue) }
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
                // v2.9: Highlights row (GET /api/highlights/user/{username}).
                if (highlights.isNotEmpty() || username == null) {
                    Text("Highlights", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
                    Spacer(Modifier.height(8.dp))
                    LazyRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(highlights, key = { it.id }) { h ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp).clickable { highlightViewer = h }) {
                                Box(Modifier.size(60.dp).clip(CircleShape).background(FrianzoBlueTint), contentAlignment = Alignment.Center) {
                                    val cover = h.coverUrl ?: h.items.firstOrNull { !it.mediaUrl.isNullOrBlank() }?.mediaUrl
                                    if (cover != null) AsyncImage(model = cover, contentDescription = null, modifier = Modifier.size(60.dp).clip(CircleShape), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                                    else Text((h.title ?: "H").take(1).uppercase(), color = FrianzoBlue, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(h.title ?: "", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                            }
                        }
                        if (username == null) {
                            item {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp).clickable { showHighlightCreate = true }) {
                                    Box(Modifier.size(60.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Add, contentDescription = "New highlight", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    Spacer(Modifier.height(4.dp))
                                    Text("New", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }
                    highlightError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = FrianzoBadgeRed, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                    Spacer(Modifier.height(12.dp))
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
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clickable { if (feedVm != null) detailPostId = post.id }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                Column(Modifier.padding(16.dp)) {
                    post.content?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    post.imageUrl?.let { Spacer(Modifier.height(8.dp)); AsyncImage(model = it, contentDescription = null, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))) }
                    Spacer(Modifier.height(8.dp))
                    // v2.9: Like / Comment are tappable now (they were static
                    // icons). Like toggles via POST /api/posts/{id}/reaction;
                    // Comment opens the post detail (which has comments).
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val myReaction = postReactions[post.id] ?: post.myReaction
                        val likeTotal = postLikeCounts[post.id] ?: post.likeCount
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable {
                            val current = postReactions[post.id] ?: post.myReaction
                            val sendType = current ?: "like"
                            val prevReactions = postReactions; val prevCounts = postLikeCounts
                            val wasCount = postLikeCounts[post.id] ?: post.likeCount
                            if (current == null) { postReactions = postReactions + (post.id to "like"); postLikeCounts = postLikeCounts + (post.id to wasCount + 1) }
                            else { postReactions = postReactions - post.id; postLikeCounts = postLikeCounts + (post.id to (wasCount - 1).coerceAtLeast(0)) }
                            scope.launch {
                                try {
                                    val r = api2.setReaction(post.id, mapOf("type" to sendType))
                                    if (r.success && r.data != null) {
                                        val eff = r.data.myReaction ?: r.data.reaction
                                        val effC = r.data.likeCount
                                        if (eff != null) postReactions = postReactions + (post.id to eff) else postReactions = postReactions - post.id
                                        if (effC != null) postLikeCounts = postLikeCounts + (post.id to effC)
                                    } else { postReactions = prevReactions; postLikeCounts = prevCounts }
                                } catch (_: Exception) { postReactions = prevReactions; postLikeCounts = prevCounts }
                            }
                        }) {
                            Icon(if (myReaction != null) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, contentDescription = "Like", tint = if (myReaction != null) Color(0xFFEF4444) else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("$likeTotal", style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.width(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { if (feedVm != null) detailPostId = post.id }) {
                            Icon(Icons.Filled.Chat, contentDescription = "Comments", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(4.dp))
                            Text("${post.commentCount}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}


// ---------- HIGHLIGHTS (v2.9): viewer + create ----------
// Viewer: full-screen black, Prev/Next through the highlight's items,
// photo or video playback, Delete for the owner (DELETE /api/highlights/{id}).
@Composable
private fun HighlightViewerDialog(api2: FrianzoApi2, highlight: Highlight, isOwn: Boolean, onClose: () -> Unit, onDeleted: () -> Unit) {
    var idx by remember { mutableStateOf(0) }
    var showDelete by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val item = highlight.items.getOrNull(idx)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when {
                item == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Empty highlight", color = Color.White) }
                item.mediaType == "video" -> {
                    key(item.id) {
                        AndroidView(factory = { ctx -> VideoView(ctx).apply {
                            setVideoURI(Uri.parse(item.mediaUrl))
                            setOnPreparedListener { mp -> mp.isLooping = true; mp.start() }
                        } }, modifier = Modifier.fillMaxSize(), onRelease = { it.stopPlayback() })
                    }
                }
                else -> AsyncImage(model = item.mediaUrl, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(highlight.title ?: "Highlight", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Text("${idx + 1}/${highlight.items.size}", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White) }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)) {
                notice?.let { Text(it, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = idx > 0, onClick = { idx-- }, colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.2f), contentColor = Color.White)) { Text("Back") }
                    Button(enabled = idx < highlight.items.size - 1, onClick = { idx++ }, colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.2f), contentColor = Color.White)) { Text("Next") }
                    if (isOwn) {
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = { showDelete = true }, colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White), border = androidx.compose.foundation.BorderStroke(1.dp, Color.White)) { Text("Delete highlight") }
                    }
                }
            }
        }
        if (showDelete) {
            AlertDialog(onDismissRequest = { showDelete = false }, title = { Text("Delete highlight?") }, text = { Text("This can't be undone.") },
                confirmButton = { TextButton(onClick = { showDelete = false; scope.launch { try { if (api2.deleteHighlight(highlight.id).success) onDeleted() else notice = "Could not delete highlight" } catch (e: Exception) { notice = e.message } } }) { Text("Delete", color = Color.Red) } },
                dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } })
        }
    }
}

// Create: pick a title + your current (non-expired) statuses, then
// POST /api/highlights { title, statusIds }.
@Composable
private fun HighlightCreateDialog(api2: FrianzoApi2, statusItems: List<StatusItem>, onClose: () -> Unit, onCreated: () -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("New highlight") },
        text = {
            Column {
                OutlinedTextField(value = title, onValueChange = { if (it.length <= 30) title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(8.dp))
                if (statusItems.isEmpty()) Text("No current stories to add. Post a story first.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                    items(statusItems, key = { it.id }) { st ->
                        Row(Modifier.fillMaxWidth().clickable { selected = if (st.id in selected) selected - st.id else selected + st.id }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = st.id in selected, onCheckedChange = { checked -> selected = if (checked) selected + st.id else selected - st.id })
                            if (!st.mediaUrl.isNullOrBlank()) AsyncImage(model = st.thumbnailUrl ?: st.mediaUrl, contentDescription = null, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                            Spacer(Modifier.width(8.dp))
                            Text(st.textContent?.takeIf { it.isNotBlank() } ?: if (st.mediaType == "video") "Video story" else "Photo story", fontSize = 14.sp, maxLines = 1)
                        }
                    }
                }
                if (error != null) { Spacer(Modifier.height(8.dp)); Text(error!!, fontSize = 13.sp, color = Color.Red) }
            }
        },
        confirmButton = {
            Button(enabled = !busy && title.isNotBlank() && selected.isNotEmpty(), onClick = {
                busy = true
                scope.launch {
                    try {
                        val r = api2.createHighlight(mapOf("title" to title.trim(), "statusIds" to selected.toList()))
                        if (r.success) onCreated() else error = r.error?.message ?: "Could not create highlight"
                    } catch (e: Exception) { error = e.message }
                    busy = false
                }
            }) { Text(if (busy) "Creating..." else "Create") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

// ---------- FOLLOWERS / FOLLOWING LIST (GET /api/follows/{id}/{direction}) ----------
@Composable
private fun FollowListScreen(api2: FrianzoApi2, userId: String, direction: String, onUser: (String) -> Unit, onClose: () -> Unit) {
    var users by remember { mutableStateOf<List<FollowListUser>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(userId, direction) {
        scope.launch {
            try {
                val res = api2.followUsers(userId, direction)
                if (res.success && res.data != null) users = res.data.users ?: emptyList() else error = res.error?.message
            } catch (e: Exception) { error = e.message }
        }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("Back") }
            Text(if (direction == "followers") "Followers" else "Following", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        LazyColumn(Modifier.weight(1f)) {
            items(users, key = { it.id ?: it.username ?: "" }) { u ->
                ListItem(
                    headlineContent = { Text(u.displayName ?: u.username ?: "", style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text("@${u.username ?: ""}", style = MaterialTheme.typography.bodySmall) },
                    leadingContent = { FrianzoAvatar(u.profilePictureUrl, 48.dp) },
                    modifier = Modifier.clickable { if (u.username != null) onUser(u.username!!) },
                )
                HorizontalDivider()
            }
            if (error != null) item { Text(error!!, Modifier.padding(16.dp)) }
            if (users.isEmpty() && error == null) item { Text("Nobody here yet", Modifier.padding(16.dp)) }
        }
    }
}

private fun uriToImagePart(context: android.content.Context, uri: Uri, baseName: String): MultipartBody.Part {
    val input = context.contentResolver.openInputStream(uri) ?: throw Exception("Could not read the image")
    val mime = try { context.contentResolver.getType(uri) } catch (_: Exception) { null } ?: "image/jpeg"
    val ext = when (mime.substringAfter("/")) { "png" -> "png"; "webp" -> "webp"; "gif" -> "gif"; else -> "jpg" }
    val tmp = File(context.cacheDir, "$baseName.$ext")
    input.use { inp -> tmp.outputStream().use { out -> inp.copyTo(out) } }
    return MultipartBody.Part.createFormData("image", tmp.name, tmp.asRequestBody(mime.toMediaTypeOrNull()))
}
