package com.frianzo.app.ui

import android.app.Activity
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import android.media.MediaPlayer
import android.net.Uri
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.layout.onSizeChanged
import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.frianzo.app.data.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.saveable.rememberSaveable
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

// v3.6 (U2): never show raw exception / DNS text to users in reels.
// Map any failure to a short friendly sentence; the raw message stays
// in logs only. Short server messages are already friendly, so they
// pass through unchanged. Every error state keeps its Retry button.
private fun friendlyReelsError(raw: String?, fallback: String): String {
    val msg = raw.orEmpty()
    val lower = msg.lowercase()
    return when {
        "unable to resolve host" in lower || "unknownhost" in lower ||
        "no address associated" in lower || "failed to connect" in lower ||
        "unable to connect" in lower || "network" in lower ||
        "timeout" in lower || "connection reset" in lower ->
            "No internet connection — check your connection and retry"
        msg.isNotBlank() && msg.length <= 100 && "exception" !in lower -> msg
        else -> fallback
    }
}
private fun friendlyReelsError(e: Throwable?, fallback: String): String =
    friendlyReelsError(e?.message, fallback)

// ---------- REELS (v2.9): full-screen BLACK vertical snap paging, like the web ----------
// One reel per screen, auto-play the current reel, progress bar, mute
// toggle, double-tap to like, right rail (Like / Comment / Share / Views /
// Save / More), Following / For You tabs, caption pill. Upload, favourite
// and more-menu behaviour are kept from v2.8.
// v3.5 (R2/R20): the reels session lives OUTSIDE ReelsScreen (held in
// AppShell), so leaving Reels for a profile/chat/tab no longer wipes the
// loaded reels, scroll position, viewed ids or mute — coming back resumes
// where the user left off instead of restarting from reel #1.
class ReelsSession(context: android.content.Context) {
    var reels by mutableStateOf<List<Reel>>(emptyList())
    var offset by mutableStateOf(0)
    var hasMore by mutableStateOf(true)
    var viewedIds by mutableStateOf<Set<String>>(emptySet())
    var feed by mutableStateOf("foryou")
    var scrollIndex by mutableStateOf(0)
    // R19: last watched position per reel, so scrolling back resumes.
    var positions by mutableStateOf<Map<String, Int>>(emptyMap())
    // v3.9 (live-web audit #2): mute is PER-REEL, not global. The first
    // reel starts muted, later reels start unmuted, and each reel keeps
    // its own toggle for the session. Nothing is persisted — the old
    // SharedPreferences "reels_muted" is no longer read or written.
    // (v3.7 fix: a 'fun setMuted' would clash with the generated setter
    // of a 'muted' property — the helper keeps the name updateMuted.)
    var mutedReels by mutableStateOf<Map<String, Boolean>>(emptyMap())
    fun isMuted(reelId: String, isFirst: Boolean): Boolean = mutedReels[reelId] ?: isFirst
    fun updateMuted(reelId: String, m: Boolean) { mutedReels = mutedReels + (reelId to m) }
    // v3.8 (F10): per-tab feed cache — Following / For You each keep their
    // own reels + offset + hasMore + scroll position, so switching tabs
    // restores instantly instead of reloading from the top (web feedCacheRef).
    var tabReels by mutableStateOf<Map<String, List<Reel>>>(emptyMap())
    var tabOffset by mutableStateOf<Map<String, Int>>(emptyMap())
    var tabHasMore by mutableStateOf<Map<String, Boolean>>(emptyMap())
    var tabScroll by mutableStateOf<Map<String, Int>>(emptyMap())
    fun saveTab(feedKey: String) {
        tabReels = tabReels + (feedKey to reels)
        tabOffset = tabOffset + (feedKey to offset)
        tabHasMore = tabHasMore + (feedKey to hasMore)
        tabScroll = tabScroll + (feedKey to scrollIndex)
    }
}
// v4.7 (owner): ripple-free tap for the reels screen. Compose's default
// clickable draws a grey ripple on press; on the dark video that reads as
// a dirty shadow behind whatever was tapped. Every tap in the reels flow
// uses this instead — same action, no visual press feedback. The
// double-tap heart burst is a deliberate animation and is untouched.
private fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
}

// v4.7 (owner): the reels loading indicator is a ROUND spinner like the
// web (ReelItem: h-12 w-12 = 48px circle, 4px white/30 ring with a solid
// white top arc, spinning). The old blue dots are gone from the reels
// screen; same placement and same show/hide conditions, only the shape.
@Composable
private fun ReelLoadingSpinner() {
    CircularProgressIndicator(
        modifier = Modifier.size(48.dp),
        color = Color.White,
        strokeWidth = 4.dp,
        trackColor = Color.White.copy(alpha = 0.3f),
    )
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ReelsScreen(api2: FrianzoApi2, myId: String? = null, session: ReelsSession, onAuthor: (String) -> Unit = {}, onOpenChat: (String, String) -> Unit = { _, _ -> }, focusReelId: String? = null, onFocusConsumed: () -> Unit = {}, uploadUri: android.net.Uri? = null, onUploadUriConsumed: () -> Unit = {}) {
    val context = LocalContext.current
    // Hoisted session state (R2): these survive leaving Reels.
    var reels by session::reels
    var offset by session::offset
    var hasMore by session::hasMore
    var viewedIds by session::viewedIds
    var feed by session::feed
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loadMoreError by remember { mutableStateOf<String?>(null) }
    var showUpload by remember { mutableStateOf(false) }
    var showSaved by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var focusError by remember { mutableStateOf<String?>(null) }
    var focusTried by remember { mutableStateOf<String?>(null) }
    var lastPage by remember { mutableStateOf(0) }
    // v3.8 (F9/F11): pull-to-refresh state + offline fallback flag.
    var refreshing by remember { mutableStateOf(false) }
    var offline by remember { mutableStateOf(false) }
    // v3.8 (F1): in-app camera recorder state.
    var showCamera by remember { mutableStateOf(false) }
    var camVideoUri by remember { mutableStateOf<Uri?>(null) }
    var pendingCoverUri by remember { mutableStateOf<Uri?>(null) }
    var pendingCamFilter by remember { mutableStateOf<String?>(null) }
    var pendingCamSpeed by remember { mutableStateOf<String?>(null) }
    // v3.8 (F12): Data Saver is the Settings toggle (FrianzoThemeState).
    val dataSaver = FrianzoThemeState.dataSaver
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)
    // Current page = first visible item (listState.firstVisibleItemIndex).
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
                    val firstPage = offset == 0
                    val seen = reels.map { it.id }.toSet()
                    reels = reels + res.data.reels.filter { it.id !in seen }
                    offset += res.data.reels.size
                    hasMore = res.data.hasMore
                    offline = false
                    // v4.7 (owner): save the first 1-2 reels for offline
                    // play, like the web (IndexedDB cache). The
                    // scroll-away cache only saves a reel AFTER the user
                    // leaves it, so a user who only ever watches reel 1
                    // had nothing saved and got the error screen offline.
                    // Background, WiFi-only (the v4.3 rule: never a
                    // full-file download on a slow metered link while
                    // the player is streaming) and never on Data Saver
                    // (the web never caches on Data Saver).
                    // saveOfflineReel skips files that already exist,
                    // so re-runs on refresh are cheap.
                    if (firstPage && !dataSaver) {
                        val firstTwo = reels.take(2)
                        scope.launch(Dispatchers.IO) {
                            for (r in firstTwo) saveOfflineReelIfUnmetered(context, r)
                        }
                    }
                } else {
                    val msg = friendlyReelsError(res.error?.message, "Couldn't load reels — try again")
                    if (reels.isEmpty()) {
                        // v3.8 (F11): no network + nothing loaded — fall back
                        // to the offline cache instead of a bare error.
                        val cached = loadOfflineReels(context)
                        if (cached.isNotEmpty()) { reels = cached; offline = true; error = null }
                        else error = msg
                    } else loadMoreError = msg
                }
                if (res.success && res.data != null) loadMoreError = null
            } catch (e: Exception) {
                val msg = friendlyReelsError(e, "Couldn't load reels — try again")
                if (reels.isEmpty()) {
                    val cached = loadOfflineReels(context)
                    if (cached.isNotEmpty()) { reels = cached; offline = true; error = null }
                    else error = msg
                } else loadMoreError = msg
            }
            finally { loading = false; refreshing = false }
        }
    }
    // v3.8 (F9): pull-to-refresh — reload the current tab from the top.
    fun refreshFeed() {
        if (loading) return
        session.tabReels = session.tabReels - feed
        session.tabOffset = session.tabOffset - feed
        session.tabHasMore = session.tabHasMore - feed
        reels = emptyList(); offset = 0; hasMore = true; error = null; offline = false
        refreshing = true
        load()
    }
    // v3.8 (F10): switching tabs saves the old tab's place and restores
    // the new tab's cached list instantly — no reload from the top.
    fun switchFeed(v: String) {
        if (v == feed) return
        session.saveTab(feed)
        feed = v
        val cached = session.tabReels[v]
        if (cached != null) {
            reels = cached
            offset = session.tabOffset[v] ?: 0
            hasMore = session.tabHasMore[v] ?: true
            val idx = session.tabScroll[v] ?: 0
            lastPage = idx; session.scrollIndex = idx
            scope.launch { if (idx > 0) listState.scrollToItem(idx) }
        } else {
            reels = emptyList(); offset = 0; hasMore = true; lastPage = 0; session.scrollIndex = 0; load()
        }
    }
    var reelComment by remember { mutableStateOf<Reel?>(null) }
    var reelCommentCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    // v3.7 (C10): follow status per author, hoisted here so following on
    // one reel fans out to every reel by the same author in the feed.
    var authorFollowStates by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var myAvatar by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            val me = api2.myProfile()
            if (me.success && me.data?.user != null) myAvatar = me.data.user.profilePictureUrl
        } catch (_: Exception) {}
    }
    reelComment?.let { reel -> ReelCommentsDialog(api2, reel, myId, myAvatar = myAvatar, initialTotal = reelCommentCounts[reel.id] ?: reel.commentCount, onClose = { reelComment = null }, onCommentAdded = { reelCommentCounts = reelCommentCounts + (reel.id to ((reelCommentCounts[reel.id] ?: reel.commentCount) + 1)) }, onCommentDeleted = { reelCommentCounts = reelCommentCounts + (reel.id to ((reelCommentCounts[reel.id] ?: reel.commentCount) - 1).coerceAtLeast(0)) }) }
    if (showCamera) {
        ReelCameraScreen(
            onVideoRecorded = { uri, filter, speed -> showCamera = false; camVideoUri = uri; pendingCamFilter = filter; pendingCamSpeed = speed; showUpload = true },
            onPhotoCaptured = { uri -> showCamera = false; pendingCoverUri = uri; showUpload = true },
            onClose = { showCamera = false },
        )
    }
    if (showUpload) { ReelUploadScreen(api2, initialVideoUri = camVideoUri ?: uploadUri, initialCoverUri = pendingCoverUri, initialFilter = pendingCamFilter, initialSpeed = pendingCamSpeed, onOpenCamera = { showUpload = false; showCamera = true }, onDone = { newReelId -> showUpload = false; onUploadUriConsumed(); camVideoUri = null; pendingCoverUri = null; pendingCamFilter = null; pendingCamSpeed = null; if (newReelId != null) { scope.launch { try { val r = api2.reelById(newReelId); val nr = r.data?.reel; if (r.success && nr != null) { reels = listOf(nr) + reels.filter { it.id != nr.id }; listState.scrollToItem(0) } else { reels = emptyList(); offset = 0; hasMore = true; load() } } catch (_: Exception) { reels = emptyList(); offset = 0; hasMore = true; load() } } } else { reels = emptyList(); offset = 0; hasMore = true; load() } }, onClose = { showUpload = false; onUploadUriConsumed(); camVideoUri = null; pendingCoverUri = null; pendingCamFilter = null; pendingCamSpeed = null }) }
    if (showSaved) { SavedReelsScreen(api2, onClose = { showSaved = false }, onOpenReel = { id -> showSaved = false; scope.launch { try { val r = api2.reelById(id); val nr = r.data?.reel; if (r.success && nr != null) { reels = listOf(nr) + reels.filter { it.id != nr.id }; listState.scrollToItem(0) } } catch (_: Exception) {} } }) }
    LaunchedEffect(uploadUri) { if (uploadUri != null) showUpload = true }
    // v3.8 (F10): tab switches go through switchFeed() (cached restore);
    // this only loads the first tab on first composition.
    LaunchedEffect(Unit) { if (reels.isEmpty()) load() }
    // Deep-link: jump to a reel (from a notification / profile grid).
    // R3: if it is not in the loaded list, fetch it directly with
    // GET /api/reels/:id and prepend it instead of silently landing at
    // the top of For You. A failed fetch shows "Reel not found" once.
    LaunchedEffect(reels, focusReelId) {
        val target = focusReelId ?: return@LaunchedEffect
        val idx = reels.indexOfFirst { it.id == target }
        if (idx >= 0) { scope.launch { listState.scrollToItem(idx) }; onFocusConsumed() }
        else if (focusTried != target) {
            focusTried = target
            scope.launch {
                try {
                    val r = api2.reelById(target)
                    if (r.success && r.data?.reel != null) {
                        val nr = r.data.reel
                        if (reels.none { it.id == nr.id }) reels = listOf(nr) + reels
                        listState.scrollToItem(0)
                        onFocusConsumed()
                    } else { focusError = "Reel not found"; onFocusConsumed(); scope.launch { delay(3000); focusError = null } }
                } catch (e: Exception) { focusError = friendlyReelsError(e, "Reel not found"); onFocusConsumed(); scope.launch { delay(3000); focusError = null } }
            }
        }
    }
    // R2: restore the scroll position when coming back to Reels (and
    // tell the one-step cap where we are, or it would snap back to 1).
    LaunchedEffect(Unit) { if (reels.isNotEmpty() && session.scrollIndex > 0) { lastPage = session.scrollIndex; listState.scrollToItem(session.scrollIndex) } }
    // Load more when the user nears the end of the loaded reels.
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { page ->
            // R17: one reel per swipe — a fast fling that would skip past
            // reels is pulled back to the next reel only.
            if (page - lastPage > 1) { scope.launch { listState.scrollToItem(lastPage + 1) }; return@collect }
            lastPage = page
            session.scrollIndex = page
            if (reels.isNotEmpty() && page >= reels.size - 3) load()
        }
    }
    // v3.7 (C1+C2+C13): web rule — a view is recorded the moment a reel
    // becomes active (watchSec 0), and the real watch seconds are sent
    // when the user leaves it (>= 1s). No 3s gate, no delay() inside the
    // collector, so fast swipes are counted and checks never queue behind
    // each other. The view call returns the fresh viewCount (R23).
    var watchReelId by remember { mutableStateOf<String?>(null) }
    var watchStartMs by remember { mutableStateOf(0L) }
    LaunchedEffect(listState) {
        // The flow also watches reels.size: when the first page loads the
        // index stays 0, so without this the first reel would never get
        // its view recorded until the user swipes.
        // v4.3: prevPage remembers the last seen page, so the collector
        // can tell a real scroll (page changed) from a list refresh
        // (load-more appends, same page) — only a real scroll means the
        // previous reel was left and is safe to cache offline.
        var prevPage = -1
        snapshotFlow { listState.firstVisibleItemIndex to reels.size }.collect { (page, _) ->
            val now = System.currentTimeMillis()
            // Finalise the reel just left, if it was watched >= 1s.
            val prevId = watchReelId
            // v4.3 (black screen): the user scrolled to a different reel,
            // so the one just left is no longer playing and may be cached
            // for offline playback. Never while it is the active one: a
            // full-file download on a slow link starves the player's
            // progressive stream (it prepares on metadata, then no frame
            // ever arrives and the surface stays black, with no error).
            val scrolledAway = prevId != null && prevPage >= 0 && page != prevPage
            if (prevId != null) {
                val sec = ((now - watchStartMs) / 1000).toInt()
                if (sec >= 1) {
                    scope.launch {
                        try {
                            val vr = api2.recordReelView(prevId, mapOf("watchSec" to sec))
                            val vc = vr.data?.viewCount
                            if (vr.success && vc != null) reels = reels.map { if (it.id == prevId) it.copy(viewCount = vc) else it }
                        } catch (_: Exception) {}
                    }
                }
                if (scrolledAway) {
                    val prevReel = reels.firstOrNull { it.id == prevId }
                    if (prevReel != null) {
                        scope.launch(Dispatchers.IO) { saveOfflineReelIfUnmetered(context, prevReel) }
                    }
                }
            }
            prevPage = page
            val reel = reels.getOrNull(page) ?: run { watchReelId = null; return@collect }
            watchReelId = reel.id
            watchStartMs = now
            if (reel.id !in viewedIds) {
                viewedIds = viewedIds + reel.id
                scope.launch {
                    try {
                        val vr = api2.recordReelView(reel.id, mapOf("watchSec" to 0))
                        val vc = vr.data?.viewCount
                        if (vr.success && vc != null) reels = reels.map { if (it.id == reel.id) it.copy(viewCount = vc) else it }
                    } catch (_: Exception) {}
                }
            }
        }
    }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        // v4.6 (owner): Following / For You header — NO black background
        // and NO underline under the active tab. The old 42dp header
        // strip sat in this black Column, so it read as a black band
        // under the white top bar. Now the header OVERLAYS the video
        // (transparent, like the web's absolute header) — see the overlay
        // Box at the bottom of this function. The web has a small 24x2px
        // underline under the active tab, but the owner ordered it
        // removed, so it is gone here. NO star icon (web has none).
        // v3.9: the white 4-icon top bar now comes from the Scaffold.
        // Tabs are CENTRED: Following grey 16sp, For You white bold
        // 16sp, 15dp gap, 17dp below the white bar, sitting on the video.
        // The + button is fixed to the viewport top-right (17dp from
        // the edge), NOT in the tabs row. The mute glyph is a bare
        // overlay on the video area below, not a circle here.
        // v3.9: mute is PER-REEL (audit #2) — this toggles only the
        // reel on screen. First reel starts muted, later reels start
        // unmuted; each reel remembers its own toggle.
        val currentReelId = reels.getOrNull(session.scrollIndex)?.id
        val currentMuted = currentReelId?.let { session.isMuted(it, session.scrollIndex == 0) } ?: true
        if (focusError != null) { Text(focusError!!, color = Color(0xFFFCA5A5), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        // v3.8 (F11): "Offline" badge when the feed is served from cache.
        if (offline) { Text("Offline — showing saved reels", color = Color(0xFFFCA5A5), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        if (notice != null) { Text(notice!!, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        // R4: a failed first load shows the error with a Retry button.
        if (error != null && reels.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(error!!, color = Color.White)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { error = null; load() }, modifier = Modifier.height(FrianzoUi.ButtonHeight), colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black), shape = RoundedCornerShape(28.dp)) { Text("Retry", maxLines = 1, softWrap = false) }
            }
        }
        if (loading && reels.isEmpty()) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("Loading Frianzo reels", color = Color.White, fontSize = 15.sp); Spacer(Modifier.height(12.dp)); ReelLoadingSpinner() } } }
        // v3.8 (F9): pull down past the top to refresh the feed.
        // v4.6: the video area is a Box so the header can overlay it.
        Box(Modifier.weight(1f).fillMaxWidth()) {
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refreshFeed() }, modifier = Modifier.fillMaxSize()) {
        if (reels.isNotEmpty()) {
            LazyColumn(state = listState, flingBehavior = flingBehavior, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(reels, key = { _, r -> r.id }) { page, reel ->
                Box(Modifier.fillParentMaxSize()) {
                ReelPage(
                    api2 = api2, reel = reel, myId = myId,
                    isActive = page == listState.firstVisibleItemIndex,
                    muted = session.isMuted(reel.id, page == 0),
                    onToggleMute = { currentReelId?.let { session.updateMuted(it, !currentMuted) } },
                    dataSaver = dataSaver,
                    onTag = { tag -> notice = "#$tag — tag pages are not in the app yet"; scope.launch { delay(3000); notice = null } },
                    commentCount = reelCommentCounts[reel.id] ?: reel.commentCount,
                    followOverride = reel.author?.id?.let { authorFollowStates[it] },
                    onFollowChange = { aid, st -> authorFollowStates = authorFollowStates + (aid to st) },
                    onAuthor = onAuthor,
                    onComment = { reelComment = reel },
                    // v4.6: like state is hoisted into the reels list
                    // (web's updateLike) so it survives item recreation.
                    onLikeChange = { id, l, c -> reels = reels.map { if (it.id == id) it.copy(liked = l, likeCount = c) else it } },
                    onOpenSaved = { showSaved = true },
                    onDeleted = { reels = reels.filter { it.id != reel.id } },
                    // R19: resume from where this reel was left.
                    resumeMs = session.positions[reel.id] ?: 0,
                    onPosition = { ms -> session.positions = session.positions + (reel.id to ms) },
                    isLast = page == reels.size - 1,
                    // R18: a finished reel advances to the next one.
                    onFinished = { scope.launch { if (page + 1 < reels.size) listState.animateScrollToItem(page + 1) } },
                    onHidden = { reels = reels.filter { it.id != reel.id } },
                    onNotice = { msg -> notice = msg; scope.launch { delay(3000); notice = null } },
                    onOpenChat = onOpenChat,
                )
                }
                }
                // R5: loading indicator while more reels load; R4: retry
                // when a load-more fails instead of failing silently.
                if (loading) item { Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) { ReelLoadingSpinner() } }
                if (loadMoreError != null) item {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(loadMoreError!!, color = Color.White, fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { loadMoreError = null; load() }, modifier = Modifier.height(FrianzoUi.ButtonHeight), shape = RoundedCornerShape(28.dp)) { Text("Retry", color = Color.White, maxLines = 1, softWrap = false) }
                    }
                }
            }
        } else if (!loading && error == null) {
            // R34: Following with nobody followed gets guidance, not a bare "No reels yet".
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { if (feed == "following") { Text("No reels from people you follow yet", color = Color.White, fontSize = 15.sp); Spacer(Modifier.height(4.dp)); Text("Follow creators to see their reels here.", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp) } else Text("No reels yet", color = Color.White, fontSize = 15.sp) } }
        }
        }
        // v4.6: header overlay — transparent, on the video (no black bar,
        // no underline; see the note at the top of this function).
        Box(Modifier.fillMaxWidth().height(42.dp).align(Alignment.TopCenter)) {
            Row(Modifier.align(Alignment.TopCenter).padding(top = 17.dp), horizontalArrangement = Arrangement.spacedBy(15.dp)) {
                listOf("following" to "Following", "foryou" to "For You").forEach { (v, label) ->
                    val sel = feed == v
                    Text(label, fontSize = 16.sp, lineHeight = 18.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium, color = if (sel) Color.White else Color.White.copy(alpha = 0.6f), maxLines = 1, softWrap = false, modifier = Modifier.noRippleClickable { switchFeed(v) })
                }
            }
            Box(Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 17.dp).size(40.dp).clip(CircleShape).background(Color(0xFF333333)).noRippleClickable { showUpload = true }, contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Add, contentDescription = "Create reel", tint = Color.White, modifier = Modifier.size(12.dp))
            }
        }
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

// v3.7 (C12): web shows RAW integers on the reel rail (12400, not 12.4k).
private fun reelCount(n: Int): String {
    return "$n"
}

// v3.8 (F2+F3): extract evenly-spaced video frames as small JPEG bitmaps
// (web /reels/new does the same with 3 frames for detect-category and a
// row of frame thumbnails for the cover). Each bitmap is ~320px wide so a
// base64 frame stays well under the server's 500KB limit.
private fun extractVideoFrames(context: android.content.Context, uri: Uri, count: Int): List<Bitmap> {
    val out = mutableListOf<Bitmap>()
    try {
        val mmr = MediaMetadataRetriever()
        mmr.setDataSource(context, uri)
        val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        if (durMs <= 0) { mmr.release(); return out }
        for (i in 0 until count) {
            val atMs = durMs * (i + 1) / (count + 1)
            val frame = mmr.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: continue
            val w = frame.width; val h = frame.height
            val scaled = if (w > 320) Bitmap.createScaledBitmap(frame, 320, (h * 320 / w).coerceAtLeast(1), true) else frame
            out.add(scaled)
            if (frame !== scaled) frame.recycle()
        }
        mmr.release()
    } catch (_: Exception) {}
    return out
}

private fun bitmapToBase64Jpeg(b: Bitmap): String {
    val baos = ByteArrayOutputStream()
    b.compress(Bitmap.CompressFormat.JPEG, 70, baos)
    return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
}

// v3.8 (F11): offline reel cache — watched reels are saved (video file +
// a small JSON list of their metadata) so the feed can fall back to them
// when the network is gone, like web's IndexedDB cache. Best-effort only.
private fun offlineReelsDir(context: android.content.Context): File {
    val d = File(context.cacheDir, "offline_reels")
    if (!d.exists()) d.mkdirs()
    return d
}

// v4.3 (black screen): the offline cache may only download on an
// unmetered network (WiFi). A full-file download on a slow metered link
// starves the player's progressive stream, so the player prepares on
// metadata and then shows only black, with no error. The caller only
// ever passes a reel the user has already scrolled away from.
private fun isUnmeteredNetwork(context: android.content.Context): Boolean {
    return try {
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    } catch (_: Exception) { false }
}

private fun saveOfflineReelIfUnmetered(context: android.content.Context, reel: Reel) {
    if (!isUnmeteredNetwork(context)) return
    saveOfflineReel(context, reel)
}

private fun saveOfflineReel(context: android.content.Context, reel: Reel) {
    try {
        val url = reel.videoUrl ?: return
        val file = File(offlineReelsDir(context), "${reel.id}.mp4")
        if (!file.exists() || file.length() == 0L) {
            val conn = java.net.URL(url).openConnection()
            conn.connectTimeout = 8000; conn.readTimeout = 15000
            try {
                conn.getInputStream().use { inp -> file.outputStream().use { out -> inp.copyTo(out) } }
            } catch (_: Exception) {
                // v4.3: a partial download is NOT a cached reel — delete it
                // so it is never registered (or played back) as complete.
                try { file.delete() } catch (_: Exception) {}
                return
            }
        }
        val prefs = context.getSharedPreferences("frianzo_reels", android.content.Context.MODE_PRIVATE)
        val gson = com.google.gson.Gson()
        val saved = try { gson.fromJson(prefs.getString("offline_reels_json", "[]"), Array<Reel>::class.java)?.toMutableList() ?: mutableListOf() } catch (_: Exception) { mutableListOf() }
        if (saved.none { it.id == reel.id }) {
            saved.add(0, reel.copy(videoUrl = file.absolutePath))
            if (saved.size > 20) saved.subList(20, saved.size).clear()
            prefs.edit().putString("offline_reels_json", gson.toJson(saved)).apply()
        }
    } catch (_: Exception) {}
}

private fun loadOfflineReels(context: android.content.Context): List<Reel> {
    return try {
        val prefs = context.getSharedPreferences("frianzo_reels", android.content.Context.MODE_PRIVATE)
        val gson = com.google.gson.Gson()
        val list = gson.fromJson(prefs.getString("offline_reels_json", "[]"), Array<Reel>::class.java)?.toList() ?: emptyList()
        list.filter { File(offlineReelsDir(context), "${it.id}.mp4").exists() }
    } catch (_: Exception) { emptyList() }
}

// v3.9 (live-web audit #2): live web shows NO more/less expander —
// a long caption is cut with an ellipsis only (one line). #tag and
// @user stay tappable blue spans inside the visible line.
@Composable
private fun ReelCaptionText(caption: String, onAuthor: (String) -> Unit = {}, onTag: (String) -> Unit = {}) {
    val annotated = androidx.compose.ui.text.buildAnnotatedString {
        val tagRegex = Regex("(#[A-Za-z0-9_]+|@[A-Za-z0-9_.]+)")
        var last = 0
        for (m in tagRegex.findAll(caption)) {
            if (m.range.first > last) append(caption.substring(last, m.range.first))
            val token = m.value
            val ann = if (token.startsWith("#")) "tag:${token.drop(1)}" else "user:${token.drop(1)}"
            pushStringAnnotation(ann, ann)
            withStyle(androidx.compose.ui.text.SpanStyle(color = Color(0xFF93C5FD), fontWeight = FontWeight.SemiBold)) { append(token) }
            pop()
            last = m.range.last + 1
        }
        if (last < caption.length) append(caption.substring(last))
    }
    ClickableText(
        text = annotated,
        style = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        onClick = { offset ->
            annotated.getStringAnnotations(offset, offset).firstOrNull()?.let { ann ->
                when {
                    ann.tag.startsWith("tag:") -> onTag(ann.tag.removePrefix("tag:"))
                    ann.tag.startsWith("user:") -> onAuthor(ann.tag.removePrefix("user:"))
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReelPage(api2: FrianzoApi2, reel: Reel, myId: String?, isActive: Boolean, muted: Boolean, onToggleMute: () -> Unit = {}, commentCount: Int, onAuthor: (String) -> Unit, onComment: () -> Unit, onLikeChange: (String, Boolean, Int) -> Unit = { _, _, _ -> }, onDeleted: () -> Unit, resumeMs: Int = 0, onPosition: (Int) -> Unit = {}, isLast: Boolean = false, onFinished: () -> Unit = {}, onHidden: () -> Unit = {}, onNotice: (String) -> Unit = {}, onOpenChat: (String, String) -> Unit = { _, _ -> }, followOverride: String? = null, onFollowChange: (String, String) -> Unit = { _, _ -> }, dataSaver: Boolean = false, onTag: (String) -> Unit = {}, onOpenSaved: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var liked by remember(reel.id) { mutableStateOf(reel.liked) }
    var likes by remember(reel.id) { mutableStateOf(reel.likeCount) }
    var shares by remember(reel.id) { mutableStateOf(reel.shareCount) }
    var fav by remember(reel.id) { mutableStateOf(reel.favorited) }
    // Short /s/ link for this reel (POST /api/share { type: "reel" }),
    // like the post share sheet; falls back to the plain /reel/ URL.
    // B-B8: the short /s/ link is created LAZILY when the share sheet
    // opens (see LaunchedEffect(shareOpen) below) — viewing a reel must
    // not fire a POST /api/share for every reel scrolled past.
    val defaultReelLink = "https://frianzo.online/reel/${reel.id}"
    var reelLink by remember(reel.id) { mutableStateOf(defaultReelLink) }
    var paused by remember { mutableStateOf(false) }
    var burst by remember { mutableStateOf(false) }
    var prepared by remember { mutableStateOf(false) }
    // v4.3 (black screen): true while the prepared player is stalled
    // waiting for data (MEDIA_INFO_BUFFERING_START). A prepared player
    // shows only black while it starves — with no error — so the stall
    // must be visible instead of a silent black surface.
    var buffering by remember(reel.id) { mutableStateOf(false) }
    // v3.5 (R1): a video that fails to load shows an error + Retry, never
    // an endless spinner. actionMsg is the small feedback pill (R7/R32/R33).
    var videoError by remember(reel.id) { mutableStateOf<String?>(null) }
    // Retry recreates the player (a failed VideoView never re-prepares).
    var videoRetryKey by remember(reel.id) { mutableStateOf(0) }
    var actionMsg by remember { mutableStateOf<String?>(null) }
    var shareOpen by remember { mutableStateOf(false) }
    // B-B8: lazy share-link creation — only when the share sheet opens.
    LaunchedEffect(shareOpen) {
        if (shareOpen && reelLink == defaultReelLink) {
            try {
                val r = api2.createShareLink(mapOf("type" to "reel", "id" to reel.id))
                val code = r.data?.code
                if (r.success && !code.isNullOrBlank()) reelLink = "https://frianzo.online/s/$code"
            } catch (_: Exception) {}
        }
    }
    var reportOpen by remember { mutableStateOf(false) }
    var reportReason by remember { mutableStateOf("other") }
    var showDeleteReel by remember { mutableStateOf(false) }
    fun showMsg(m: String) { actionMsg = m; scope.launch { delay(2500); if (actionMsg == m) actionMsg = null } }
    var durationMs by remember { mutableStateOf(0) }
    var positionMs by remember { mutableStateOf(0) }
    // NOTE: player/prepared are set ONLY in VideoView callbacks (never in
    // the AndroidView factory — writing state during composition is not
    // allowed). The progress ticker reads player.currentPosition.
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var moreOpen by remember { mutableStateOf(false) }
    // v4.0 (audit #3): the reel's real video rectangle, so the rail,
    // author column and progress bar anchor to the VIDEO edges (the
    // video is centred in the taller item box; the item is taller
    // than a 9:16 video on a phone). Before the video is prepared
    // the aspect defaults to 9:16.
    var pageSize by remember { mutableStateOf(IntSize.Zero) }
    val videoPw = player?.videoWidth ?: 0
    val videoPh = player?.videoHeight ?: 0
    val videoAspect = if (videoPw > 0 && videoPh > 0) videoPw.toFloat() / videoPh else 9f / 16f
    val videoWidthPx = if (pageSize.width > 0) pageSize.width.toFloat().coerceAtMost(pageSize.height * videoAspect) else 0f
    val videoHeightPx = if (pageSize.width > 0) pageSize.height.toFloat().coerceAtMost(pageSize.width / videoAspect) else 0f
    val videoTopOffset = with(LocalDensity.current) { ((pageSize.height - videoHeightPx) / 2f).toDp() }
    // The video is centred, so the bottom offset equals the top one.
    val videoBottomOffset = videoTopOffset
    val videoWidthDp = with(LocalDensity.current) { videoWidthPx.toDp() }
    // v3.8 (F12): Data Saver tap-to-play — the reel waits for a tap.
    var tapToPlay by remember(reel.id) { mutableStateOf(false) }
    // v3.8 (F14): single tap reveals the play/pause controls (web rule);
    // they hide again after 3s.
    var controlsVisible by remember { mutableStateOf(false) }
    LaunchedEffect(controlsVisible) { if (controlsVisible) { delay(3000); controlsVisible = false } }
    // v3.8 (F14): a reel starts muted, then tries to unmute (web rule).
    var autoMuted by remember(reel.id) { mutableStateOf(true) }
    LaunchedEffect(isActive, reel.id) { if (isActive) { delay(600); autoMuted = false } }
    // v3.8 (F14): a video error is auto-retried once after 1.5s (web rule).
    var errorRetries by remember(reel.id) { mutableStateOf(0) }
    LaunchedEffect(videoError) {
        if (videoError != null && errorRetries < 1) {
            delay(1500); errorRetries += 1
            videoError = null; prepared = false; player = null; videoRetryKey += 1
        }
    }
    // v3.8 (F7): "Interested in #category?" prompt state.
    var showInterest by remember(reel.id) { mutableStateOf(false) }
    var interestAnswered by remember(reel.id) { mutableStateOf(false) }
    LaunchedEffect(isActive, reel.id) { if (isActive) { delay(3000); if (!interestAnswered && !reel.category.isNullOrBlank()) showInterest = true } }
    LaunchedEffect(liked) { if (liked && !interestAnswered && !reel.category.isNullOrBlank()) showInterest = true }
    // v3.8 (F15): live download progress (-1 = not downloading).
    var downloadProgress by remember { mutableStateOf(-1) }
    // B-B23: true when the server gives no length (chunked response) —
    // the pill then reads "Downloading…" instead of a stuck "0%".
    var downloadUnknownLength by remember { mutableStateOf(false) }
    // v3.8 (F17): optional report details (500 chars, backend accepts it).
    var reportDetails by remember { mutableStateOf("") }
    var seekWidthPx by remember { mutableStateOf(0) }
    // v3.4: Follow / Follow Back pill on the author (GET /api/follows/{id}/status).
    // v3.8 (F6): seed from the reel's own friendStatus when the backend sent it.
    var authorFollow by remember(reel.author?.id) { mutableStateOf<String?>(reel.friendStatus) }
    // B-B22: one retry, then fall back to showing "Follow" instead of
    // hiding the pill forever when the single status fetch fails.
    LaunchedEffect(reel.author?.id) {
        val aid = reel.author?.id
        if (aid != null && aid != myId && authorFollow == null) {
            var done = false
            var attempt = 0
            while (attempt < 2 && !done) {
                try { val r = api2.followStatus(aid); if (r.success) { authorFollow = r.data?.status; done = true } } catch (_: Exception) {}
                if (!done) { attempt += 1; if (attempt < 2) delay(1500) }
            }
            if (!done) authorFollow = "none"
        }
    }
    // v3.8 (F21): save toggle shared by the rail star and the More sheet row.
    fun toggleFav() {
        val prevFav = fav; fav = !prevFav; showMsg(if (!prevFav) "Saved" else "Removed from saved")
        scope.launch { try { val r = api2.toggleReelFavorite(reel.id); if (r.success && r.data != null) fav = r.data.favorited else { fav = prevFav; showMsg("Couldn't save — try again") } } catch (_: Exception) { fav = prevFav; showMsg("Couldn't save — try again") } }
    }
    val burstScale by animateFloatAsState(if (burst) 1.4f else 1f, label = "heartBurst")
    // v3.7 (C3+C4): the heart toggles from the CURRENT state (web rule) and
    // like calls are serialised per reel with an in-flight guard, so rapid
    // taps can never race and leave the wrong state. Optimistic, reverts
    // only if the server call fails (R7).
    var likeBusy by remember(reel.id) { mutableStateOf(false) }
    // v4.6: the like is hoisted to the parent list (web's updateLike) —
    // onLikeChange writes the new state into ReelsScreen's reels, so a
    // like survives the LazyColumn item being disposed and recreated
    // (scroll away and back, tab switch). Before this, liked/likes lived
    // only in this ReelPage's remember and reset to the stale list value
    // on recreation — the like "went back" to unlike.
    fun doLike() {
        if (liked || likeBusy) return
        likeBusy = true
        val prevLiked = liked; val prevLikes = likes
        liked = true; likes = prevLikes + 1
        onLikeChange(reel.id, true, likes)
        scope.launch {
            try {
                val r = api2.toggleReelLike(reel.id)
                if (r.success && r.data != null) { liked = r.data.liked; likes = r.data.likeCount; onLikeChange(reel.id, liked, likes) }
                else { liked = prevLiked; likes = prevLikes; onLikeChange(reel.id, prevLiked, prevLikes); showMsg("Couldn't like — try again") }
            } catch (_: Exception) { liked = prevLiked; likes = prevLikes; onLikeChange(reel.id, prevLiked, prevLikes); showMsg("Couldn't like — try again") }
            finally { likeBusy = false }
        }
    }
    fun doUnlike() {
        if (!liked || likeBusy) return
        likeBusy = true
        val prevLiked = liked; val prevLikes = likes
        liked = false; likes = (prevLikes - 1).coerceAtLeast(0)
        onLikeChange(reel.id, false, likes)
        scope.launch {
            try {
                val r = api2.toggleReelLike(reel.id)
                if (r.success && r.data != null) { liked = r.data.liked; likes = r.data.likeCount; onLikeChange(reel.id, liked, likes) }
                else { liked = prevLiked; likes = prevLikes; onLikeChange(reel.id, prevLiked, prevLikes); showMsg("Couldn't unlike — try again") }
            } catch (_: Exception) { liked = prevLiked; likes = prevLikes; onLikeChange(reel.id, prevLiked, prevLikes); showMsg("Couldn't unlike — try again") }
            finally { likeBusy = false }
        }
    }
    // Rail heart: toggle from current state (C3) — never a blind doLike().
    fun toggleLike() { if (liked) doUnlike() else doLike() }
    LaunchedEffect(burst) { if (burst) { delay(700); burst = false } }
    // Progress ticker: reads the real VideoView position while active.
    // v4.2: getCurrentPosition() throws IllegalStateException on a
    // dead / not-yet-ready player — never let the ticker kill the app.
    // v4.3 (black screen): stall watchdog — a prepared, playing player
    // whose position has not moved for ~5s is starved of data (a stall
    // fires no onError, so the screen would stay black forever). Show
    // the existing error + Retry UI so the user can retry instead of
    // staring at black. Fires once per player (stallReported resets on
    // Retry via videoRetryKey, and on a new reel via reel.id). The
    // existing 1.5s auto-retry then recreates the player once, with no
    // competing download running during the retry.
    var stallReported by remember(reel.id, videoRetryKey) { mutableStateOf(false) }
    // v4.5 (no-frames): true once the player renders its first video
    // frame (MEDIA_INFO_VIDEO_RENDERING_START). A yuv444p file prepares
    // and plays its audio but renders zero frames and reports no error,
    // so without this the screen stays black forever with no error.
    // Both flags reset per player (videoRetryKey) and per reel.
    var frameRendered by remember(reel.id, videoRetryKey) { mutableStateOf(false) }
    var noFramesReported by remember(reel.id, videoRetryKey) { mutableStateOf(false) }
    LaunchedEffect(isActive, prepared) {
        var lastPos = -1
        var lastMoveMs = System.currentTimeMillis()
        // v4.5 (no-frames): the clock for the no-frames detector — the
        // moment this effect (re)starts with a prepared player. It
        // restarts when prepared flips or the reel is re-activated, so
        // the player always gets a fresh 5s window.
        val preparedAtMs = System.currentTimeMillis()
        while (isActive && prepared) {
            delay(200)
            val pos = try { player?.currentPosition ?: positionMs } catch (_: Exception) { positionMs }
            positionMs = pos
            val playing = try { player?.isPlaying == true } catch (_: Exception) { false }
            if (pos != lastPos) { lastPos = pos; lastMoveMs = System.currentTimeMillis() }
            else if (playing && !stallReported && !paused && videoError == null && System.currentTimeMillis() - lastMoveMs >= 5000) {
                stallReported = true
                videoError = "Couldn't play this video"
            }
            // v4.5 (no-frames): a prepared, playing player that has
            // rendered zero frames in 5s is a dead video track (a
            // yuv444p file: audio plays, position moves, no onError,
            // no buffering event — the stall watchdog above never
            // fires because the position DOES move). This is a
            // separate if, not chained to the movement check, because
            // the position moves in exactly this failure. Gated on
            // !buffering so a first frame still arriving on a slow
            // connection is the stall watchdog's job, not this one's.
            // Fires once per player; the existing 1.5s auto-retry then
            // recreates the player once, exactly like the stall path.
            if (playing && !frameRendered && !buffering && !noFramesReported && !paused && videoError == null && System.currentTimeMillis() - preparedAtMs >= 5000) {
                noFramesReported = true
                videoError = "Couldn't play this video"
            }
        }
    }
    // v3.8 (F14): single tap reveals the play/pause controls (web rule) —
    // the centre button does the pausing. Double-tap likes (v4.6 — never
    // unlikes, web rule).
    Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged { pageSize = it }.pointerInput(reel.id) {
        detectTapGestures(
            onTap = { controlsVisible = true },
            // R35 (v4.6): double-tap ONLY likes, never unlikes (web rule —
            // the web double-tap is `if (!reel.liked) handleLike()`).
            // The old toggle unliked an already-liked reel, so a like
            // "went back" to unlike. The burst shows either way.
            onDoubleTap = { if (!liked) doLike(); burst = true },
        )
    }) {
        if (reel.videoUrl != null) {
            key(reel.id, videoRetryKey) {
                AndroidView(
                    factory = { ctx -> VideoView(ctx).apply {
                        setVideoURI(Uri.parse(reel.videoUrl))
                        setOnPreparedListener { mp -> player = mp; durationMs = mp.duration; prepared = true; buffering = false; val em = muted || autoMuted; mp.setVolume(if (em) 0f else 1f, if (em) 0f else 1f); if (resumeMs > 0) mp.seekTo(resumeMs); if (isActive && !paused && (!dataSaver || tapToPlay)) start() }
                        // R1: a broken video reports the error instead of
                        // spinning forever.
                        setOnErrorListener { _, _, _ -> player = null; prepared = false; buffering = false; videoError = "Couldn't play this video"; true }
                        // v4.3 (black screen): a prepared player can stall
                        // waiting for data without ever firing onError — it
                        // then shows only black. Surface the stall so the
                        // user sees "Buffering…" instead of a black screen.
                        setOnInfoListener { _, what, _ ->
                            when (what) {
                                MediaPlayer.MEDIA_INFO_BUFFERING_START -> { buffering = true; true }
                                MediaPlayer.MEDIA_INFO_BUFFERING_END -> { buffering = false; true }
                                // v4.5 (no-frames): the first rendered video
                                // frame. A yuv444p file never sends this.
                                MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> { frameRendered = true; true }
                                else -> false
                            }
                        }
                        // R18: a finished reel moves to the next one.
                        // v3.7 (C11): on the LAST reel playback STOPS, like
                        // web — it never loops back to the start.
                        setOnCompletionListener { v -> onPosition(0); if (!isLast) onFinished() }
                    } },
                    modifier = Modifier.fillMaxSize(),
                    update = { view ->
                        val em = muted || autoMuted
                        // v4.2: setVolume() throws on a dead/errored
                        // player — update runs on every recomposition.
                        try { player?.setVolume(if (em) 0f else 1f, if (em) 0f else 1f) } catch (_: Exception) {}
                        // v3.8 (F12): Data Saver waits for an explicit tap.
                        if (isActive && !paused && (!dataSaver || tapToPlay)) { if (!view.isPlaying) view.start() } else view.pause()
                    },
                    // R19: remember where this reel was left.
                    // v4.2: a dead/preparing player throws on
                    // getCurrentPosition() and on stop() — read the
                    // position only when prepared, and never let the
                    // release path throw (it fires on every scroll).
                    onRelease = { val pos = try { if (prepared && videoError == null) player?.currentPosition ?: positionMs else positionMs } catch (_: Exception) { positionMs }; onPosition(pos); player = null; try { it.stopPlayback() } catch (_: Exception) {} },
                )
            }
            if (videoError != null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(videoError!!, color = Color.White, fontSize = 15.sp)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { videoError = null; prepared = false; player = null; videoRetryKey += 1 }, modifier = Modifier.height(FrianzoUi.ButtonHeight), colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black), shape = RoundedCornerShape(28.dp)) { Text("Retry", maxLines = 1, softWrap = false) }
                    }
                }
            } else if (!prepared) {
                reel.thumbnailUrl?.let { AsyncImage(model = it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ReelLoadingSpinner() }
            } else if (buffering) {
                // v4.3 (black screen): the prepared player is waiting for
                // data — show a spinner + "Buffering…" over the black
                // surface instead of silent black.
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        ReelLoadingSpinner()
                        Spacer(Modifier.height(12.dp))
                        Text("Buffering…", color = Color.White, fontSize = 15.sp)
                    }
                }
            }
        } else {
            reel.thumbnailUrl?.let { AsyncImage(model = it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Video unavailable", color = Color.White, fontSize = 15.sp) }
        }
        // v3.8 (F12): Data Saver shows the poster + a tap-to-play pill.
        if (dataSaver && !tapToPlay && prepared) {
            // B-B35: the tap that starts playback also reveals the
            // play/pause controls — the overlay used to swallow it.
            Box(Modifier.fillMaxSize().noRippleClickable { tapToPlay = true; controlsVisible = true }, contentAlignment = Alignment.Center) {
                Box(Modifier.clip(RoundedCornerShape(28.dp)).background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Tap to play", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        // v3.8 (F14): centre play/pause button, shown while controls are up.
        if ((controlsVisible || paused) && prepared && (!dataSaver || tapToPlay)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                // v4.7: no ripple on the video — the IconButton's grey
                // press circle read as a dirty shadow, like clickable.
                // A plain Box + noRippleClickable keeps the same 84dp
                // circle and action with no press feedback at all.
                // (NoIndication does not exist in this Compose version —
                // LocalIndication provides null / NoIndication both fail
                // to compile, so the IconButton is gone instead.)
                Box(
                    Modifier.size(84.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f))
                        .noRippleClickable { paused = !paused; controlsVisible = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, contentDescription = if (paused) "Play" else "Pause", tint = Color.White, modifier = Modifier.size(40.dp))
                }
            }
        }
        if (burst) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Favorite, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(96.dp).graphicsLayer(scaleX = burstScale, scaleY = burstScale))
            }
        }
        // v3.8 (F8): seekable progress bar — tap anywhere on it to seek.
        // v4.0 (audit #3): 4dp thick, 10dp inset from each VIDEO edge,
        // white fill on a #404040 track, sitting ON the video's bottom
        // edge. No scrubber dot, no time label (web has neither).
        if (prepared && durationMs > 0) {
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = videoBottomOffset).width((videoWidthDp - 20.dp).coerceAtLeast(100.dp)).height(16.dp).onSizeChanged { seekWidthPx = it.width }.pointerInput(durationMs) {
                detectTapGestures { offset ->
                    if (seekWidthPx > 0) {
                        val ratio = (offset.x / seekWidthPx).coerceIn(0f, 1f)
                        val ms = (ratio * durationMs).toInt()
                        try { player?.seekTo(ms) } catch (_: Exception) {}; positionMs = ms
                    }
                }
            }, contentAlignment = Alignment.BottomStart) {
                Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0xFF404040)))
                Box(Modifier.fillMaxWidth((positionMs.toFloat() / durationMs).coerceIn(0f, 1f)).height(4.dp).background(Color.White))
            }
        }
        // v4.0 (audit #3): the mute is a BARE white speaker glyph —
        // NO circle — fixed 90dp below the white bar (48dp below the
        // tabs band), 15dp from the right edge. It floats in the black
        // area above shorter videos; it is NOT anchored to the video.
        // The 40dp transparent Box is only the tap target; the glyph
        // sits at its TOP (TopCenter) so it lands exactly at y148.
        if (isActive) {
            Box(Modifier.align(Alignment.TopEnd).padding(top = 48.dp, end = 15.dp).size(40.dp).noRippleClickable { onToggleMute() }, contentAlignment = Alignment.TopCenter) {
                Icon(if (muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp, contentDescription = if (muted) "Unmute" else "Mute", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
        if (actionMsg != null) {
            Box(Modifier.align(Alignment.TopCenter).padding(top = 12.dp).clip(RoundedCornerShape(28.dp)).background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text(actionMsg!!, color = Color.White, fontSize = 13.sp, maxLines = 1, softWrap = false)
            }
        }
        // v3.8 (F15): live download progress.
        if (downloadProgress >= 0) {
            Box(Modifier.align(Alignment.TopCenter).padding(top = 56.dp).clip(RoundedCornerShape(28.dp)).background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text(if (downloadUnknownLength) "Downloading…" else "Downloading ${downloadProgress}%", color = Color.White, fontSize = 13.sp, maxLines = 1, softWrap = false)
            }
        }
        // v4.6 (owner): right rail matches the web exactly — bottom-anchored
        // (24dp above the video bottom, 12dp from the right edge), NOT
        // vertically centred; web icon sizes (heart 30, comment 28,
        // share 26, more 26 wide), 12sp labels (Views label 10sp),
        // 4dp icon->label, 16dp between buttons. The old rail was centred
        // (too high), 48dp wide with 21dp gaps and 13sp labels — it read
        // as a big, dirty block.
        Column(Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 24.dp + videoBottomOffset), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.noRippleClickable { toggleLike() }, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, contentDescription = "Like", tint = if (liked) Color(0xFFEF4444) else Color.White, modifier = Modifier.size(30.dp))
                Spacer(Modifier.height(4.dp))
                Text(reelCount(likes), color = Color.White, fontSize = 12.sp, lineHeight = 12.sp, maxLines = 1, softWrap = false)
            }
            Spacer(Modifier.height(16.dp))
            Column(Modifier.noRippleClickable { onComment() }, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = "Comments", tint = Color.White, modifier = Modifier.size(28.dp))
                Spacer(Modifier.height(4.dp))
                Text(reelCount(commentCount), color = Color.White, fontSize = 12.sp, lineHeight = 12.sp, maxLines = 1, softWrap = false)
            }
            Spacer(Modifier.height(16.dp))
            Column(Modifier.noRippleClickable { shareOpen = true }, horizontalAlignment = Alignment.CenterHorizontally) {
                // R11/R6: the share sheet (DM + channels + copy). The count
                // moves only when a channel is actually tapped, never when
                // the sheet is opened or the chooser is cancelled.
                Icon(Icons.Filled.Share, contentDescription = "Share", tint = Color.White, modifier = Modifier.size(26.dp))
                Spacer(Modifier.height(4.dp))
                Text(if (shares > 0) reelCount(shares) else "Share", color = Color.White, fontSize = 12.sp, lineHeight = 12.sp, maxLines = 1, softWrap = false)
            }
            Spacer(Modifier.height(16.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Live web has NO eye icon on Views — number + "Views" only.
                Text(reelCount(reel.viewCount), color = Color.White, fontSize = 12.sp, lineHeight = 12.sp, maxLines = 1, softWrap = false)
                Spacer(Modifier.height(4.dp))
                Text("Views", color = Color.White, fontSize = 10.sp, lineHeight = 10.sp, maxLines = 1, softWrap = false)
            }
            Spacer(Modifier.height(16.dp))
            Column(Modifier.noRippleClickable { moreOpen = true }, horizontalAlignment = Alignment.CenterHorizontally) {
                // v4.0 (audit #3): web More is three horizontal 4dp
                // dots (20x4 total), NOT a vertical MoreVert icon.
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    repeat(3) { Box(Modifier.size(4.dp).clip(CircleShape).background(Color.White)) }
                }
                Spacer(Modifier.height(4.dp))
                Text("More", color = Color.White, fontSize = 12.sp, lineHeight = 12.sp, maxLines = 1, softWrap = false)
            }
        }
        // v4.0 (audit #2/#3): the More sheet is a white bottom sheet,
        // "More options", with the web rows — NOT a small dropdown menu.
        // "Saved reels" stays (the only entry to the saved list, owner
        // rule: no star icon) and "Delete reel" stays for own reels.
        if (moreOpen) {
            ModalBottomSheet(onDismissRequest = { moreOpen = false }) {
                Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Text("More options", fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                    }
                    HorizontalDivider(color = FrianzoBorder)
                    if (!reel.category.isNullOrBlank()) MoreSheetRow(Icons.Filled.ThumbUp, "Interested") {
                        moreOpen = false
                        scope.launch {
                            try {
                                val r = api2.notInterested(mapOf("type" to "interested_category", "value" to (reel.category ?: "")))
                                if (r.success) showMsg("We'll show you more like this") else showMsg("Couldn't save — try again")
                            } catch (_: Exception) { showMsg("Couldn't save — try again") }
                        }
                    }
                    MoreSheetRow(Icons.Filled.ThumbDown, "Not interested") {
                        moreOpen = false
                        val aid = reel.author?.id
                        scope.launch {
                            try {
                                val r = if (aid != null) api2.notInterested(mapOf("type" to "creator", "value" to aid)) else null
                                if (r?.success == true) { onHidden(); onNotice("You won't see this again") } else showMsg("Couldn't hide — try again")
                            } catch (_: Exception) { showMsg("Couldn't hide — try again") }
                        }
                    }
                    MoreSheetRow(Icons.Filled.Bookmark, if (fav) "Remove from saved" else "Save reel") { moreOpen = false; toggleFav() }
                    MoreSheetRow(Icons.Filled.Bookmark, "Saved reels") { moreOpen = false; onOpenSaved() }
                    MoreSheetRow(Icons.Filled.Download, "Save video") {
                        moreOpen = false
                        scope.launch {
                            // B-B23: the MediaStore entry is deleted again
                            // if the download fails, so no 0-byte/corrupt
                            // file is left behind in Downloads.
                            var savedDownloadUri: android.net.Uri? = null
                            try {
                                val body = api2.downloadReel(reel.id)
                                val totalBytes = body.contentLength()
                                downloadUnknownLength = totalBytes <= 0
                                val fileName = "frianzo-reel-${reel.id}.mp4"
                                val values = ContentValues().apply {
                                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                                }
                                val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                                savedDownloadUri = uri
                                if (uri == null) { showMsg("Download failed"); return@launch }
                                val stream = body.byteStream()
                                try {
                                    // v3.8 (F15): count bytes while copying so
                                    // the pill above shows a live 0-100%.
                                    downloadProgress = 0
                                    context.contentResolver.openOutputStream(uri)?.use { out ->
                                        val buf = ByteArray(8192)
                                        var read: Int
                                        var done = 0L
                                        while (stream.read(buf).also { read = it } != -1) {
                                            out.write(buf, 0, read); done += read
                                            if (totalBytes > 0) downloadProgress = ((done * 100) / totalBytes).toInt().coerceIn(0, 100)
                                        }
                                    }
                                    downloadProgress = -1; showMsg("Downloaded")
                                } finally { try { stream.close() } catch (_: Exception) {}; try { body.close() } catch (_: Exception) {}; downloadProgress = -1; downloadUnknownLength = false }
                            } catch (e: Exception) {
                                downloadProgress = -1; downloadUnknownLength = false
                                try { savedDownloadUri?.let { context.contentResolver.delete(it, null, null) } } catch (_: Exception) {}
                                showMsg(friendlyReelsError(e, "Download failed — try again"))
                            }
                        }
                    }
                    MoreSheetRow(Icons.Filled.Link, "Copy link") { moreOpen = false; val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager; cm.setPrimaryClip(android.content.ClipData.newPlainText("reel", reelLink)); showMsg("Link copied") }
                    if (!reel.category.isNullOrBlank()) MoreSheetRow(Icons.Filled.Block, "Don't recommend this category") {
                        moreOpen = false
                        scope.launch {
                            try {
                                val r = api2.notInterested(mapOf("type" to "category", "value" to (reel.category ?: "")))
                                if (r.success) { onHidden(); onNotice("You won't see this again") } else showMsg("Couldn't hide — try again")
                            } catch (_: Exception) { showMsg("Couldn't hide — try again") }
                        }
                    }
                    MoreSheetRow(Icons.Filled.Flag, "Report", red = true) { moreOpen = false; reportOpen = true }
                    if (reel.author?.id == myId) MoreSheetRow(Icons.Filled.Delete, "Delete reel", red = true) { moreOpen = false; showDeleteReel = true }
                }
            }
        }
        // Bottom-left: author + caption pill.
        // v4.0 (web code: overlay is left-0 right-16 p-4): content
        // starts 16dp from the left and ends 80dp from the right;
        // the comment pill sits 10dp above the VIDEO bottom.
        Column(Modifier.align(Alignment.BottomStart).padding(start = 16.dp, end = 80.dp, bottom = 10.dp + videoBottomOffset)) {
            Row(Modifier.fillMaxWidth().noRippleClickable { reel.author?.username?.let { onAuthor(it) } }, verticalAlignment = Alignment.CenterVertically) {
                FrianzoAvatar(reel.author?.profilePictureUrl, 40.dp)
                Spacer(Modifier.width(8.dp))
                // v3.8 (F6): web shows the display name + "· 2d" time ago.
                // The name takes weight(1f) and truncates, so the Follow pill
                // never clips on a 360dp phone (owner rule).
                Text(reel.author?.displayName ?: "@${reel.author?.username ?: ""}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (reel.author?.isVerified == true) { Spacer(Modifier.width(4.dp)); Icon(Icons.Filled.CheckCircle, contentDescription = "Verified", modifier = Modifier.size(16.dp), tint = FrianzoBlue) }
                if (!reel.createdAt.isNullOrBlank()) { Spacer(Modifier.width(4.dp)); Text("· ${reelTimeAgo(reel.createdAt)}", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1, softWrap = false) }
                val authorId = reel.author?.id
                // v3.7 (C10): web rule — the pill shows ONLY while not
                // following (Follow / Follow Back); it never offers
                // unfollow. The status fans out to every reel by this
                // author via onFollowChange (hoisted in ReelsScreen).
                val effectiveFollow = followOverride ?: authorFollow
                if (authorId != null && authorId != myId && effectiveFollow != null && effectiveFollow != "self" && effectiveFollow != "following" && effectiveFollow != "friends") {
                    Spacer(Modifier.width(8.dp))
                    val label = if (effectiveFollow == "follow_back") "Follow Back" else "Follow"
                    // v4.0 (audit #3 + web code): black fill, 1dp white
                    // border, white text — BESIDE the name on the SAME
                    // row (owner rule, final word: never below the
                    // name, never on a second line; the name truncates
                    // so the pill never wraps).
                    Box(Modifier.height(34.dp).clip(RoundedCornerShape(28.dp)).background(Color.Black).border(1.dp, Color.White, RoundedCornerShape(28.dp)).noRippleClickable {
                        val prev = effectiveFollow
                        authorFollow = "following"
                        onFollowChange(authorId, "following")
                        scope.launch {
                            try {
                                val r = api2.toggleFollow(authorId)
                                if (r.success && r.data != null) {
                                    val st = r.data.friendStatus ?: if (r.data.following || r.data.isFollowing) "following" else "none"
                                    authorFollow = st
                                    onFollowChange(authorId, st)
                                } else { authorFollow = prev; onFollowChange(authorId, prev) }
                            } catch (_: Exception) { authorFollow = prev; onFollowChange(authorId, prev) }
                        }
                    }.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                        Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                    }
                }
            }
            // v3.9 (audit #2): one line + ellipsis, no expander; #tag / @user links.
            reel.caption?.let { Spacer(Modifier.height(8.dp)); ReelCaptionText(it, onAuthor = { u -> onAuthor(u) }, onTag = onTag) }
            // v3.9 (live-web audit #2): NO category chip on the reel
            // screen — no chip was seen on any live reel. The category
            // is still stored and sent on upload; only its display
            // chip is gone. (The "Interested in #category?" prompt
            // below is a different row and stays.)
            // v3.8 (F7): "Interested in #category? Yes / No" (web rule).
            if (showInterest && !interestAnswered && !reel.category.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.2f)).padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Interested in #${reel.category}?", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    // B-B24: "We'll show you more like this" only on
                    // success — it used to fire even when offline.
                    TextButton(onClick = { interestAnswered = true; showInterest = false; scope.launch { try { val r = api2.notInterested(mapOf("type" to "interested_category", "value" to (reel.category ?: ""))); if (r.success) showMsg("We'll show you more like this") else showMsg("Couldn't save — try again") } catch (_: Exception) { showMsg("Couldn't save — try again") } } }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Yes", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                    TextButton(onClick = { interestAnswered = true; showInterest = false }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("No", color = Color.White, fontSize = 13.sp) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(28.dp)).background(Color.Black.copy(alpha = 0.35f)).border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(28.dp)).noRippleClickable { onComment() }.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                // v3.8 (F18): the pill has a 20px speech-bubble icon (web rule).
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Write a comment...", color = Color.White, fontSize = 14.sp, maxLines = 1, softWrap = false)
                }
            }
        }
        if (shareOpen) {
            ReelShareSheet(api2, reel, reelLink, onClose = { shareOpen = false }, onShared = { shares += 1 }, onOpenChat = onOpenChat)
        }
        if (reportOpen) {
            AlertDialog(
                onDismissRequest = { reportOpen = false },
                title = { Text("Report reel", maxLines = 1, softWrap = false) },
                text = {
                    Column {
                        listOf("spam" to "Spam", "harassment" to "Harassment", "hate_speech" to "Hate speech", "violence" to "Violence", "nudity" to "Nudity", "misinformation" to "False information", "other" to "Something else").forEach { (v, label) ->
                            Row(Modifier.fillMaxWidth().noRippleClickable { reportReason = v }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = reportReason == v, onClick = { reportReason = v })
                                Spacer(Modifier.width(8.dp))
                                Text(label, fontSize = 15.sp)
                            }
                        }
                        // v3.8 (F17): optional details (500 chars, web rule).
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = reportDetails, onValueChange = { if (it.length <= 500) reportDetails = it }, label = { Text("Details (optional)") }, modifier = Modifier.fillMaxWidth(), maxLines = 4, shape = RoundedCornerShape(10.dp))
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        reportOpen = false
                        scope.launch {
                            try {
                                val body = mapOf("reason" to reportReason) + if (reportDetails.isNotBlank()) mapOf("details" to reportDetails) else emptyMap()
                                if (api2.reportReel(reel.id, body).success) { reportDetails = ""; showMsg("Reported. Thanks for letting us know.") } else showMsg("Couldn't report — try again")
                            } catch (_: Exception) { showMsg("Couldn't report — try again") }
                        }
                    }) { Text("Report", color = Color.Red) }
                },
                dismissButton = { TextButton(onClick = { reportOpen = false }) { Text("Cancel") } },
            )
        }
        if (showDeleteReel) {
            AlertDialog(
                onDismissRequest = { showDeleteReel = false },
                title = { Text("Delete reel?") },
                text = { Text("This can't be undone.") },
                confirmButton = {
                    TextButton(onClick = {
                        showDeleteReel = false
                        scope.launch { try { if (api2.deleteReel(reel.id).success) onDeleted() else showMsg("Couldn't delete — try again") } catch (_: Exception) { showMsg("Couldn't delete — try again") } }
                    }) { Text("Delete", color = Color.Red) }
                },
                dismissButton = { TextButton(onClick = { showDeleteReel = false }) { Text("Cancel") } },
            )
        }
    }
}

// v4.0: one row of the More bottom sheet — 18dp icon, 15sp label,
// ~48dp pitch, like the web sheet (audit #1).
@Composable
private fun MoreSheetRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, red: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().noRippleClickable { onClick() }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = if (red) Color.Red else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 15.sp, color = if (red) Color.Red else MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

// ---------- REEL SHARE (v3.5, R11): same sheet as posts — link box + Copy,
// Frianzo DM, WhatsApp, WA Status, Messenger, Facebook, FB Story, Text.
// The rail count moves only when a channel is really tapped (R6).
// No Repost row: the backend has no reel-repost endpoint, so none is faked.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReelShareSheet(api2: FrianzoApi2, reel: Reel, link: String, onClose: () -> Unit, onShared: () -> Unit, onOpenChat: (String, String) -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    var dmBusy by remember { mutableStateOf(false) }
    // v3.7 (C8): the rail count moves ONLY after POST /api/share/event
    // succeeds. A failed event used to bump the rail anyway (fire-and-
    // forget with the error swallowed) and show a share the server never
    // recorded.
    fun record(channel: String) {
        scope.launch {
            try {
                if (api2.recordShareEvent(mapOf("type" to "reel", "id" to reel.id, "channel" to channel)).success) onShared()
            } catch (_: Exception) { /* no bump: the server never recorded it */ }
        }
    }
    fun openUrl(url: String, channel: String) { record(channel); try { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) } catch (_: Exception) {}; onClose() }
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("Share", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color.White).border(1.dp, FrianzoBorder, RoundedCornerShape(8.dp)).padding(10.dp)) {
                Text(link, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { clipboard.setText(AnnotatedString(link)); copied = true; record("copy"); scope.launch { delay(2000); copied = false } },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A), contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth().height(FrianzoUi.ButtonHeight),
                ) { Text(if (copied) "Copied!" else "Copy link") }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                // B-D5: the DM tile is hidden when the reel has no author
                // id — it used to sit there and do nothing when tapped.
                if (reel.author?.id != null) ReelShareTile("Frianzo DM", Color(0xFF0F172A), "F") {
                    val authorId = reel.author?.id
                    if (authorId != null && !dmBusy) {
                        dmBusy = true
                        scope.launch {
                            try {
                                val res = api2.createConversation(com.frianzo.app.data.CreateConversationRequest(authorId))
                                val convId = res.data?.id
                                if (convId != null) {
                                    // v3.7 (C8): record the DM share ONLY
                                    // after the conversation really exists.
                                    record("dm")
                                    onClose(); onOpenChat(convId, reel.author?.displayName ?: reel.author?.username ?: "User")
                                }
                            } catch (_: Exception) {}
                            dmBusy = false
                        }
                    }
                }
                ReelShareTile("WhatsApp", Color(0xFF25D366), "W") { openUrl("https://wa.me/?text=" + android.net.Uri.encode(link), "whatsapp") }
                ReelShareTile("WA Status", Color(0xFF25D366), "W") { openUrl("whatsapp://send?text=" + android.net.Uri.encode(link), "whatsapp_status") }
                ReelShareTile("Messenger", Color(0xFF0084FF), "M") { openUrl("fb-messenger://share?link=" + android.net.Uri.encode(link), "messenger") }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                ReelShareTile("Facebook", Color(0xFF1877F2), "f") { openUrl("https://www.facebook.com/sharer/sharer.php?u=" + android.net.Uri.encode(link), "facebook") }
                ReelShareTile("FB Story", Color(0xFF1877F2), "f") { openUrl("fb://story/camera?attachment_url=" + android.net.Uri.encode(link), "facebook_story") }
                ReelShareTile("Text message", Color(0xFF34C759), "S") { openUrl("sms:?body=" + android.net.Uri.encode(link), "sms") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ReelShareTile(label: String, color: Color, letter: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.noRippleClickable { onClick() }.padding(4.dp)) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
            Text(letter, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

// ---------- SAVED REELS (v3.5, R14): the list the star toggle never had.
// GET /api/reels/me/favorites. Tapping a reel opens it in the feed.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavedReelsScreen(api2: FrianzoApi2, onOpenReel: (String) -> Unit, onClose: () -> Unit) {
    var reels by remember { mutableStateOf<List<Reel>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun load() {
        loading = true
        scope.launch {
            try {
                val r = api2.myFavoriteReels()
                if (r.success && r.data != null) reels = r.data.reels ?: emptyList() else error = friendlyReelsError(r.error?.message, "Couldn't load saved reels")
            } catch (e: Exception) { error = friendlyReelsError(e, "Couldn't load saved reels") }
            finally { loading = false }
        }
    }
    LaunchedEffect(Unit) { load() }
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Text("Saved reels", fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            HorizontalDivider(color = FrianzoBorder)
            if (loading) { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { DotsLoading() } }
            else if (error != null) {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error!!, fontSize = 14.sp, color = Color.Red)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { error = null; load() }, modifier = Modifier.height(FrianzoUi.ButtonHeight), colors = ButtonDefaults.buttonColors(containerColor = FrianzoBlue, contentColor = Color.White), shape = RoundedCornerShape(28.dp)) { Text("Retry", maxLines = 1, softWrap = false) }
                }
            }
            else if (reels.isEmpty()) { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text("No saved reels yet. Save a reel from its More menu to see it here.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            else {
                LazyVerticalGrid(columns = GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth().weight(1f)) {
                    items(reels, key = { it.id }) { reel ->
                        Box(Modifier.aspectRatio(9f / 16f).clip(RoundedCornerShape(4.dp)).background(Color(0xFF1E293B)).noRippleClickable { onOpenReel(reel.id) }) {
                            if (reel.thumbnailUrl != null) AsyncImage(model = reel.thumbnailUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Reel", tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp))
                            Row(Modifier.align(Alignment.BottomStart).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Visibility, contentDescription = "Views", tint = Color.White, modifier = Modifier.size(12.dp))
                                Spacer(Modifier.width(2.dp))
                                Text(reelCount(reel.viewCount), color = Color.White, fontSize = 11.sp, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
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
private fun ReelCommentsDialog(api2: FrianzoApi2, reel: Reel, myId: String? = null, myAvatar: String? = null, initialTotal: Int = -1, onClose: () -> Unit, onCommentAdded: () -> Unit = {}, onCommentDeleted: () -> Unit = {}) {
    var comments by remember { mutableStateOf<List<ReelComment>>(emptyList()) }
    var draft by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var replyTo by remember { mutableStateOf<ReelComment?>(null) }
    // v3.7 (C5+C6): the header is the feed TRUE total (roots + replies)
    // passed in by the parent, and moves only by +/-1 deltas on
    // send/delete. It is NEVER recomputed from the loaded list — the
    // endpoint returns roots only, so that used to drop every reply.
    var total by remember { mutableStateOf(if (initialTotal >= 0) initialTotal else reel.commentCount) }
    var nextCursor by remember { mutableStateOf<String?>(null) }
    var hasMore by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    // R9: the sheet shows a spinner until the first load finishes, so
    // "0 comments" never flashes while comments are still loading.
    var loadingFirst by remember { mutableStateOf(true) }
    var expandedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    // Follow status per comment author (GET /api/follows/{id}/status) so
    // the bubble can show a real "Follow Back" pill, like post comments.
    var followStates by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val scope = rememberCoroutineScope()
    fun load(cursor: String? = null) {
        if (cursor != null) loadingMore = true else loadingFirst = true
        scope.launch {
            try {
                val res = api2.reelComments(reel.id, cursor)
                if (res.success && res.data != null) {
                    comments = if (cursor == null) res.data.comments else comments + res.data.comments
                    nextCursor = res.data.pagination?.nextCursor
                    hasMore = res.data.pagination?.hasMore == true
                    // v3.7 (C5): R8 recompute DELETED — loading more pages
                    // never recomputes the total (web rule W3).
                } else error = friendlyReelsError(res.error?.message, "Couldn't load comments — try again")
            } catch (e: Exception) { error = friendlyReelsError(e, "Couldn't load comments — try again") }
            loadingMore = false
            loadingFirst = false
        }
    }
    LaunchedEffect(reel.id) { load() }
    LaunchedEffect(comments) {
        val authorIds = (comments.map { it.author } + comments.flatMap { c -> c.replies.map { it.author } })
            .mapNotNull { it?.id }.toSet()
        for (aid in authorIds) {
            if (aid in followStates || aid == myId) continue
            scope.launch {
                try {
                    val fs = api2.followStatus(aid)
                    if (fs.success && fs.data != null) followStates = followStates + (aid to (fs.data.status ?: "none"))
                } catch (_: Exception) {}
            }
        }
    }
    ModalBottomSheet(onDismissRequest = onClose) {
        // Same 0.85f sheet height as the post comments sheet: one shared UI.
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(horizontal = 16.dp)) {
            // v4.0 (audit #2): web header is "Comments" with the
            // count ("N comments" / "Join the conversation") below
            // it as a subtitle — not the count alone.
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Comments", fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                    Text(if (total > 0) "$total comments" else "Join the conversation", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd)) { Icon(Icons.Filled.Close, contentDescription = "Close comments", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            HorizontalDivider(color = FrianzoBorder)
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                if (loadingFirst) item { Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) { DotsLoading() } }
                else if (comments.isEmpty() && error == null) item { Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) { Text("No comments yet. Be the first to comment.", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                items(comments, key = { it.id }) { c ->
                    ReelCommentRow(api2, c, reel, myId, followStatus = followStates[c.author?.id], onFollowChange = { aid, st -> followStates = followStates + (aid to st) }, onReply = { replyTo = it }, onChanged = { load() }, onDeleted = { total = (total - 1).coerceAtLeast(0); onCommentDeleted() })
                    val replies = c.replies
                    if (replies.isNotEmpty()) {
                        val isExpanded = c.id in expandedIds
                        Column(Modifier.fillMaxWidth().padding(start = 40.dp, top = 2.dp)) {
                            if (!isExpanded) {
                                TextButton(onClick = { expandedIds = expandedIds + c.id }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) { Text("View ${replies.size} ${if (replies.size == 1) "reply" else "replies"}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false) }
                            } else {
                                replies.forEach { r ->
                                    ReelCommentRow(api2, r, reel, myId, isReply = true, parentAuthor = c.author, followStatus = followStates[r.author?.id], onFollowChange = { aid, st -> followStates = followStates + (aid to st) }, onReply = { replyTo = it }, onChanged = { load() }, onDeleted = { total = (total - 1).coerceAtLeast(0); onCommentDeleted() })
                                }
                                TextButton(onClick = { expandedIds = expandedIds - c.id }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) { Text("Hide replies", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false) }
                            }
                        }
                    }
                }
                if (hasMore) item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        if (loadingMore) DotsLoading() else OutlinedButton(onClick = { load(nextCursor) }, modifier = Modifier.height(FrianzoUi.ButtonHeight), shape = RoundedCornerShape(28.dp)) { Text("Load more comments", maxLines = 1, softWrap = false) }
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
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f)
                    )
                    // v3.9 (live-web audit #2): web uses a "Cancel" TEXT button, not an x.
                    TextButton(onClick = { replyTo = null }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Cancel", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Spacer(Modifier.height(8.dp))
            }
            // B-B30: the 550ms long-press-to-like is undiscoverable — say so.
            Text("Tip: long-press Like to like a comment instantly", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 2.dp))
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                FrianzoAvatar(myAvatar, 32.dp)
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = draft,
                    onValueChange = { if (it.length <= 500) draft = it },
                    placeholder = { Text("Write a comment...") },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    maxLines = 4,
                    singleLine = false,
                    shape = RoundedCornerShape(FrianzoUi.ButtonRadius),
                    colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color.White, unfocusedContainerColor = Color.White, focusedBorderColor = FrianzoBorder, unfocusedBorderColor = FrianzoBorder),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = draft.isNotBlank(),
                    modifier = Modifier.height(FrianzoUi.ButtonHeight),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    shape = RoundedCornerShape(FrianzoUi.ButtonRadius),
                    colors = ButtonDefaults.buttonColors(containerColor = FrianzoBlue, contentColor = Color.White),
                    onClick = {
                        // B-B10: keep the reply target so a failed send
                        // restores the draft AS a reply, not as a new
                        // top-level comment.
                        val text = draft; val parent = replyTo?.id; val savedReply = replyTo; draft = ""; replyTo = null
                        scope.launch {
                            try {
                                val res = api2.addReelComment(reel.id, CommentCreateRequest(text, parent))
                                if (res.success) {
                                    // v3.8 (F16): short send sound (web rule).
                                    try { val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60); tg.startTone(ToneGenerator.TONE_PROP_BEEP, 120); scope.launch { delay(300); try { tg.release() } catch (_: Exception) {} } } catch (_: Exception) {}
                                    total += 1; onCommentAdded(); load()
                                } else { error = friendlyReelsError(res.error?.message, "Could not send comment"); draft = text; replyTo = savedReply }
                            } catch (e: Exception) { error = friendlyReelsError(e, "Could not send comment"); draft = text; replyTo = savedReply }
                        }
                    },
                ) { Text("Send", maxLines = 1, softWrap = false) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ReelCommentRow(api2: FrianzoApi2, c: ReelComment, reel: Reel, myId: String?, isReply: Boolean = false, parentAuthor: Author? = null, followStatus: String? = null, onFollowChange: (String, String) -> Unit = { _, _ -> }, onReply: (ReelComment) -> Unit, onChanged: () -> Unit, onDeleted: () -> Unit = {}) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var editDraft by rememberSaveable { mutableStateOf(c.content ?: "") }
    // R10: keyed by comment id + server values, so a reload replaces
    // stale local state instead of the row keeping old values.
    var localReaction by remember(c.id, c.myReaction) { mutableStateOf(c.myReaction) }
    var localCount by remember(c.id, c.reactionCount) { mutableStateOf(c.reactionCount) }
    var menuOpen by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    // B-B12: report reason picker (same list as the reel report dialog) —
    // reports used to always file reason "other".
    var showReport by remember { mutableStateOf(false) }
    var reportReason by remember { mutableStateOf("other") }
    var reportError by remember { mutableStateOf<String?>(null) }
    var reported by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    // B-B11: edit-save failures are shown under the row (was: silent).
    var editError by remember { mutableStateOf<String?>(null) }
    // v3.7 (C7): 6-reaction picker on reel comments, same as web
    // (like / love / haha / wow / sad / angry). Tap Like opens the
    // picker; long-press (550ms, web rule) toggles "like" directly.
    // The server returns { reaction } only; the count is adjusted locally
    // and a busy guard blocks double taps. Sending the current reaction
    // again removes it (server toggles), sending a new one switches it.
    var pickerOpen by remember { mutableStateOf(false) }
    var reactionBusy by remember { mutableStateOf(false) }
    // B-B30: "Copied" feedback for the Copy menu item (was: silent).
    var copied by remember { mutableStateOf(false) }
    fun applyReaction(type: String) {
        if (reactionBusy) return
        val prevReaction = localReaction; val prevCount = localCount
        val target: String? = if (type == prevReaction) null else type
        localReaction = target
        localCount = (prevCount + if (target == null) -1 else if (prevReaction == null) 1 else 0).coerceAtLeast(0)
        val sendType = target ?: prevReaction ?: "like"
        reactionBusy = true
        scope.launch {
            try { if (!api2.reelCommentReaction(c.id, mapOf("type" to sendType)).success) { localReaction = prevReaction; localCount = prevCount } }
            catch (_: Exception) { localReaction = prevReaction; localCount = prevCount }
            finally { reactionBusy = false }
        }
    }
    val isOwner = myId != null && c.author?.id == myId
    val canDelete = isOwner || (myId != null && reel.author?.id == myId)
    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            FrianzoAvatar(c.author?.profilePictureUrl, if (isReply) 28.dp else 32.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                // Shared bubble with post comments: grey bubble, bold name +
                // Follow Back pill, text inside. One timestamp only, in the
                // action row below — never twice, same as post comments.
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (isReply) Color(0xFFF8FAFC) else Color(0xFFF0F2F5)).padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(c.author?.displayName ?: c.author?.username ?: "User", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            if (c.author?.isVerified == true) { Spacer(Modifier.width(4.dp)); Icon(Icons.Filled.CheckCircle, contentDescription = "Verified", modifier = Modifier.size(14.dp), tint = FrianzoBlue) }
                            val authorId = c.author?.id
                            if (authorId != null && authorId != myId && followStatus != null && followStatus != "none" && followStatus != "self") {
                                Spacer(Modifier.width(6.dp))
                                val isFollowBack = followStatus == "follow_back"
                                // B-B9: status display ONLY. The web
                                // reel-comments modal has no follow pill at
                                // all and never toggles follow from a
                                // comment — tapping this used to silently
                                // unfollow the author.
                                Box(
                                    Modifier.clip(RoundedCornerShape(12.dp))
                                        .background(if (isFollowBack) FrianzoBlueTint else MaterialTheme.colorScheme.surfaceVariant)
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(if (isFollowBack) "Follow Back" else "Following", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (isFollowBack) FrianzoBlue else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                                }
                            }
                        }
                        if (editing) {
                            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(value = editDraft, onValueChange = { if (it.length <= 500) editDraft = it }, modifier = Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(10.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = FrianzoBorder, unfocusedBorderColor = FrianzoBorder))
                                TextButton(onClick = { scope.launch { try { val r = api2.editReelComment(c.id, mapOf("content" to editDraft)); if (r.success) { editing = false; editError = null; onChanged() } else editError = friendlyReelsError(r.error?.message, "Could not save edit") } catch (e: Exception) { editError = friendlyReelsError(e, "Could not save edit") } } }) { Text("Save", fontSize = 13.sp, color = FrianzoBlue, maxLines = 1, softWrap = false) }
                                TextButton(onClick = { editing = false; editDraft = c.content ?: "" }) { Text("Cancel", fontSize = 13.sp, maxLines = 1, softWrap = false) }
                            }
                            if (editError != null) Text(editError!!, fontSize = 12.sp, color = Color.Red, modifier = Modifier.padding(top = 4.dp))
                        } else {
                            val prefix = if (isReply && parentAuthor != null && !(c.content ?: "").trim().lowercase().startsWith("@${parentAuthor.username?.lowercase()}")) "@${parentAuthor.username} " else ""
                            Text(
                                text = androidx.compose.ui.text.buildAnnotatedString {
                                    if (prefix.isNotEmpty()) { withStyle(androidx.compose.ui.text.SpanStyle(color = FrianzoBlue, fontWeight = FontWeight.SemiBold)) { append(prefix) } }
                                    append(c.content ?: "")
                                },
                                fontSize = 15.sp,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                if (pickerOpen) {
                    Row(Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 4.dp)) {
                        ReactionPickerPill(32.dp, listOf("like", "love", "haha", "wow", "sad", "angry")) { type -> pickerOpen = false; applyReaction(type) }
                    }
                }
                Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.pointerInput(c.id) {
                        detectTapGestures(
                            onLongPress = { applyReaction("like") },
                            onTap = { pickerOpen = !pickerOpen },
                        )
                    }) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                            if (localReaction != null) { ReactionIcon(localReaction, 16.dp); Spacer(Modifier.width(4.dp)) }
                            Text("Like", fontSize = 13.sp, fontWeight = if (localReaction != null) FontWeight.SemiBold else FontWeight.Normal, color = if (localReaction != null) FrianzoBlue else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    TextButton(onClick = { onReply(c) }, contentPadding = PaddingValues(0.dp)) { Text("Reply", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false) }
                    if (localCount > 0) { Spacer(Modifier.width(12.dp)); Text(reelCount(localCount), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Spacer(Modifier.weight(1f))
                    Text(reelTimeAgo(c.createdAt), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Box {
                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(40.dp)) { Icon(Icons.Filled.MoreVert, contentDescription = "More", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (isOwner) DropdownMenuItem(text = { Text("Edit") }, onClick = { menuOpen = false; editDraft = c.content ?: ""; editing = true })
                            if (canDelete) DropdownMenuItem(text = { Text("Delete", color = Color.Red) }, onClick = { menuOpen = false; showDelete = true })
                            DropdownMenuItem(text = { Text("Reply") }, onClick = { menuOpen = false; onReply(c) })
                            DropdownMenuItem(text = { Text("Copy") }, onClick = { menuOpen = false; clipboard.setText(AnnotatedString(c.content ?: "")); copied = true; scope.launch { delay(2000); copied = false } })
                            DropdownMenuItem(text = { Text(if (reported) "Reported" else "Report") }, onClick = { menuOpen = false; showReport = true })
                        }
                    }
                }
                if (reported) Text("Comment reported. Thanks for letting us know.", fontSize = 12.sp, color = Color(0xFF16A34A), modifier = Modifier.padding(start = 8.dp))
                if (reportError != null) Text(reportError!!, fontSize = 12.sp, color = Color.Red, modifier = Modifier.padding(start = 8.dp))
                if (copied) Text("Copied", fontSize = 12.sp, color = Color(0xFF16A34A), modifier = Modifier.padding(start = 8.dp))
                if (deleteError != null) Text(deleteError!!, fontSize = 12.sp, color = Color.Red, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
    if (showReport) {
        AlertDialog(
            onDismissRequest = { showReport = false },
            title = { Text("Report comment") },
            text = {
                Column {
                    listOf("spam" to "Spam", "harassment" to "Harassment", "hate_speech" to "Hate speech", "violence" to "Violence", "nudity" to "Nudity", "misinformation" to "False information", "other" to "Something else").forEach { (v, label) ->
                        Row(Modifier.fillMaxWidth().noRippleClickable { reportReason = v }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = reportReason == v, onClick = { reportReason = v })
                            Spacer(Modifier.width(8.dp))
                            Text(label, fontSize = 15.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showReport = false
                    scope.launch {
                        try {
                            val r = api2.reportReelComment(c.id, mapOf("reason" to reportReason))
                            if (r.success) { reported = true; reportError = null } else reportError = friendlyReelsError(r.error?.message, "Could not report comment")
                        } catch (e: Exception) { reportError = friendlyReelsError(e, "Could not report comment") }
                    }
                }) { Text("Report", color = Color.Red) }
            },
            dismissButton = { TextButton(onClick = { showReport = false }) { Text("Cancel") } },
        )
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
                            else deleteError = friendlyReelsError(r.error?.message, "Could not delete comment")
                        } catch (e: Exception) { deleteError = friendlyReelsError(e, "Could not delete comment") }
                    }
                }) { Text("Delete", color = Color.Red) }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } },
        )
    }
}

// v3.7: CountingRequestBody DELETED — every buffer() approach failed
// CI (run #101 okio.Okio.buffer deprecated, #102 raw ForwardingSink type
// mismatch, #103 counting.buffer() unresolved). The upload now sends the
// plain delegate RequestBody and shows an indeterminate spinner instead
// of a real % (R25 real-% progress is dropped so the build compiles).
// No code in this file may reference okio.buffer, Okio.buffer, .buffer(),
// ForwardingSink or BufferedSink.

// v3.8 (F22): one filter chip, reused by the grouped filter picker.
@Composable
private fun FilterChip(value: String, label: String, selected: String, onSelect: (String) -> Unit) {
    val sel = selected == value
    Box(Modifier.clip(RoundedCornerShape(28.dp)).background(if (sel) FrianzoBlue else MaterialTheme.colorScheme.surfaceVariant).clickable { onSelect(value) }.padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(label, color = if (sel) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 1, softWrap = false)
    }
}

// ---------- REEL UPLOAD: video + caption + category (POST /api/reels) ----------
@Composable
private fun ReelUploadScreen(api2: FrianzoApi2, initialVideoUri: Uri? = null, onDone: (String?) -> Unit, onClose: () -> Unit, initialCoverUri: Uri? = null, initialFilter: String? = null, initialSpeed: String? = null, onOpenCamera: () -> Unit = {}) {
    val context = LocalContext.current
    var videoUri by remember { mutableStateOf<Uri?>(initialVideoUri) }
    var caption by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("") }
    var customCategory by rememberSaveable { mutableStateOf("") }
    // v3.4: real upload options the backend accepts (POST /api/reels):
    // cover image, speed 0.5|1|1.5|2, text overlay (<=100), trim seconds.
    var coverUri by remember { mutableStateOf<Uri?>(initialCoverUri) }
    var speed by rememberSaveable { mutableStateOf(initialSpeed ?: "1") }
    // v3.5 (R15): server filter enum normal/warm/cool/bw/vintage/vivid/beauty.
    var filter by rememberSaveable { mutableStateOf(initialFilter ?: "normal") }
    var textOverlay by rememberSaveable { mutableStateOf("") }
    // v3.8 (F4): editor extras — the backend schema already accepts them.
    var template by rememberSaveable { mutableStateOf<String?>(null) }
    var letterbox by rememberSaveable { mutableStateOf(false) }
    var flip by rememberSaveable { mutableStateOf(false) }
    var rotateDeg by rememberSaveable { mutableStateOf(0) }
    var crop by rememberSaveable { mutableStateOf<String?>(null) }
    var textPosition by rememberSaveable { mutableStateOf("top") }
    // v3.8 (F2+F3): frames extracted from the picked video — cover
    // thumbnails + AI category auto-detect.
    var coverFrames by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var selectedFrameIdx by remember { mutableStateOf(-1) }
    var detecting by remember { mutableStateOf(false) }
    var manualCategory by remember { mutableStateOf(false) }
    var trimStartF by remember { mutableStateOf(0f) }
    var trimEndF by remember { mutableStateOf(0f) }
    var maxSec by remember { mutableStateOf(180) }
    // v3.5 (R28): how many reels are left today.
    var dailyStatus by remember { mutableStateOf<ReelStatusData?>(null) }
    // v3.5 (R25/R26/R27): picked-video facts, checked at pick time.
    var videoDurationSec by remember { mutableStateOf<Int?>(null) }
    var videoSizeMb by remember { mutableStateOf<Double?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        scope.launch { try { val r = api2.reelsConfig(); if (r.success && r.data != null) maxSec = r.data.maxDurationSec } catch (_: Exception) {} }
        scope.launch { try { val r = api2.myReelStatus(); if (r.success && r.data != null) dailyStatus = r.data } catch (_: Exception) {} }
    }
    // R26/R27: check length + size as soon as a video is picked, and
    // refuse an over-long video BEFORE uploading instead of silently
    // sending the whole file with a coerced duration number.
    var lastCheckedUri by remember { mutableStateOf<Uri?>(null) }
    fun checkVideo(uri: Uri) {
        if (uri == lastCheckedUri) return
        lastCheckedUri = uri
        try {
            // B-B31: release in finally, not only on the happy path.
            val mmr = MediaMetadataRetriever()
            val durMs = try {
                mmr.setDataSource(context, uri)
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally { try { mmr.release() } catch (_: Exception) {} }
            val durSec = (durMs / 1000).toInt()
            val size = try { context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L } catch (_: Exception) { -1L }
            videoDurationSec = durSec
            videoSizeMb = if (size > 0) size / 1024.0 / 1024.0 else null
            trimStartF = 0f; trimEndF = durSec.toFloat()
            if (durSec > maxSec) { message = "This video is ${durSec}s — reels can be ${maxSec}s at most. Please pick a shorter video."; videoUri = null; lastCheckedUri = null; videoDurationSec = null }
        } catch (e: Exception) { message = friendlyReelsError(e, "Couldn't read this video") }
    }
    LaunchedEffect(videoUri) { val u = videoUri; if (u != null) checkVideo(u) }
    // v3.8 (F2+F3): extract frames once per video — cover thumbnails, and
    // AI category auto-detect (POST /api/reels/detect-category, 3 frames).
    LaunchedEffect(videoUri) {
        val u = videoUri ?: return@LaunchedEffect
        scope.launch(Dispatchers.IO) {
            val frames = extractVideoFrames(context, u, 6)
            coverFrames = frames
            selectedFrameIdx = -1
            if (!manualCategory && frames.isNotEmpty()) {
                detecting = true
                try {
                    val b64 = frames.take(3).map { bitmapToBase64Jpeg(it) }
                    val r = api2.detectCategory(mapOf<String, Any>("frames" to b64, "caption" to caption))
                    val cat = r.data?.category
                    if (r.success && !cat.isNullOrBlank()) {
                        val known = listOf("Funny", "Dance", "Music", "Comedy", "Food", "Travel", "Sports", "Fashion", "Education", "Gaming", "Lifestyle")
                        val match = known.firstOrNull { it.equals(cat, ignoreCase = true) }
                        if (match != null) { category = match; customCategory = "" }
                        else { category = "Other"; customCategory = cat }
                    }
                } catch (_: Exception) {}
                detecting = false
            }
        }
    }
    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri -> if (uri != null) videoUri = uri }
    // R27: the server rejects covers over 10MB, so check at pick time.
    val coverPicker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) {
            val size = try { context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L } catch (_: Exception) { -1L }
            if (size > 10 * 1024 * 1024) message = "Cover photo must be smaller than 10MB" else { coverUri = uri; selectedFrameIdx = -1 } // B-B14: a gallery cover beats a picked frame (upload prefers frames)
        }
    }
    val pickCover = rememberPermissionGate(galleryPermissions(), onGranted = { coverPicker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) })
    // v3.1: Gallery asked at pick time; Camera + Microphone asked when the
    // user taps "Record a video" (owner-confirmed permissions).
    val pickReelVideo = rememberPermissionGate(galleryPermissions(), onGranted = { picker.launch(PickVisualMediaRequest(PickVisualMedia.VideoOnly)) })
    // v3.8 (F1): "Record a video" now opens the in-app CameraX recorder
    // (ReelCameraScreen) instead of the system camera intent.
    // v3.6 (U1): the upload form is ~850-900dp tall with a video
    // picked — scroll it so Trim and the Upload button stay reachable
    // on small phones.
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("Cancel", maxLines = 1, softWrap = false) }
            Text("Upload reel", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickReelVideo() }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(28.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Text(if (videoUri == null) "Pick a video" else "Video selected", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
            OutlinedButton(onClick = { onOpenCamera() }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(28.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Text("Record a video", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
        }
        // R25: a muted looping preview of the picked video + its length/size.
        if (videoUri != null) {
            Spacer(Modifier.height(12.dp))
            // v3.6 (U3): a real 9:16 preview (180x320dp, centred)
            // instead of the 200dp letterbox where a 9:16 video
            // rendered only ~112dp wide with black bars.
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                // v3.8 (F4): preview shows flip / rotate / crop / letterbox
                // and the text overlay at its chosen position.
                Box(Modifier.height(320.dp).aspectRatio(if (crop == "square") 1f else 9f / 16f).clip(RoundedCornerShape(12.dp)).background(Color.Black)) {
                    key(videoUri) {
                        AndroidView(factory = { ctx -> VideoView(ctx).apply { setVideoURI(videoUri); setOnPreparedListener { mp -> mp.setVolume(0f, 0f); mp.isLooping = true; start() } } }, modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = if (flip) -1f else 1f; rotationZ = rotateDeg.toFloat() })
                    }
                    if (letterbox) {
                        Box(Modifier.fillMaxWidth().height(28.dp).background(Color.Black).align(Alignment.TopCenter))
                        Box(Modifier.fillMaxWidth().height(28.dp).background(Color.Black).align(Alignment.BottomCenter))
                    }
                    if (textOverlay.isNotBlank()) {
                        Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = when (textPosition) { "bottom" -> Alignment.BottomCenter; "middle" -> Alignment.Center; else -> Alignment.TopCenter }) {
                            Text(textOverlay, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            videoDurationSec?.let { d -> Spacer(Modifier.height(4.dp)); Text("${d}s" + (videoSizeMb?.let { s -> " · %.1f MB".format(s) } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = caption, onValueChange = { if (it.length <= 500) caption = it }, label = { Text("Caption (optional)") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        // R36: category chips instead of a free-text box.
        // v3.8 (F2): AI auto-detect runs when a video is picked; tapping a
        // chip overrides it (manualCategory) and stops further auto-detect.
        Text("Category" + if (detecting) " — detecting…" else "", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("Funny", "Dance", "Music", "Comedy", "Food", "Travel", "Sports", "Fashion", "Education", "Gaming", "Lifestyle", "Other")) { cat ->
                val sel = category == cat
                Box(Modifier.clip(RoundedCornerShape(28.dp)).background(if (sel) FrianzoBlue else MaterialTheme.colorScheme.surfaceVariant).clickable { category = cat; manualCategory = true; if (cat != "Other") customCategory = "" }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Text(cat, color = if (sel) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 1, softWrap = false)
                }
            }
        }
        if (category == "Other") { Spacer(Modifier.height(8.dp)); OutlinedTextField(value = customCategory, onValueChange = { customCategory = it }, label = { Text("Your category") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
        Text("Max length: ${maxSec}s" + (dailyStatus?.let { " · Reels left today: ${it.remaining} (posted ${it.postedToday})" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { pickCover() }, shape = RoundedCornerShape(28.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Text(if (coverUri == null && selectedFrameIdx < 0) "Add cover photo (optional)" else "Cover selected", maxLines = 1, softWrap = false) }
        // v3.8 (F3): pick the cover from the video's own frames (web rule).
        if (coverFrames.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Cover from video", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(coverFrames.size) { idx ->
                    val sel = selectedFrameIdx == idx
                    Box(Modifier.size(width = 64.dp, height = 112.dp).clip(RoundedCornerShape(8.dp)).border(if (sel) 3.dp else 1.dp, if (sel) FrianzoBlue else FrianzoBorder, RoundedCornerShape(8.dp)).clickable { selectedFrameIdx = idx; coverUri = null }) {
                        androidx.compose.foundation.Image(bitmap = coverFrames[idx].asImageBitmap(), contentDescription = "Cover frame ${idx + 1}", modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        // R15 + v3.8 (F22): filter picker grouped like web
        // (Trending / Portrait / B&W) — the server applies the filter.
        Text("Filter", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        Text("Trending", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip("normal", "Normal", filter) { filter = it }
            FilterChip("warm", "Warm", filter) { filter = it }
            FilterChip("vivid", "Vivid", filter) { filter = it }
        }
        Spacer(Modifier.height(8.dp))
        Text("Portrait", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip("beauty", "Beauty", filter) { filter = it }
            FilterChip("cool", "Cool", filter) { filter = it }
        }
        Spacer(Modifier.height(8.dp))
        Text("B&W", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip("bw", "B&W", filter) { filter = it }
            FilterChip("vintage", "Vintage", filter) { filter = it }
        }
        Spacer(Modifier.height(8.dp))
        Text("Speed", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("0.5", "1", "1.5", "2").forEach { s ->
                val sel = speed == s
                Box(Modifier.clip(RoundedCornerShape(28.dp)).background(if (sel) FrianzoBlue else MaterialTheme.colorScheme.surfaceVariant).clickable { speed = s }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Text("${s}x", color = if (sel) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 1, softWrap = false)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        // v3.8 (F4): one-tap templates (web rule).
        Text("Template", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf<Pair<String?, String>>(null to "None", "cinematic" to "Cinematic", "glow" to "Glow", "slowmo" to "Slow Mo").forEach { (v, label) ->
                val sel = template == v
                Box(Modifier.clip(RoundedCornerShape(28.dp)).background(if (sel) FrianzoBlue else MaterialTheme.colorScheme.surfaceVariant).clickable {
                    template = v
                    when (v) {
                        // B-B15: "None" resets filter/letterbox/speed to
                        // defaults; each template also resets the speed it
                        // does not use, so a Slow-Mo 0.5x never survives
                        // switching to Cinematic/Glow/None.
                        null -> { filter = "normal"; letterbox = false; speed = "1" }
                        "cinematic" -> { filter = "vintage"; letterbox = true; speed = "1" }
                        "glow" -> { filter = "beauty"; letterbox = false; speed = "1" }
                        "slowmo" -> { filter = "cool"; speed = "0.5" }
                    }
                }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Text(label, color = if (sel) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 1, softWrap = false)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { letterbox = !letterbox }, shape = RoundedCornerShape(28.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Text("Letterbox: " + if (letterbox) "On" else "Off", fontSize = 13.sp, maxLines = 1, softWrap = false) }
            OutlinedButton(onClick = { flip = !flip }, shape = RoundedCornerShape(28.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Text("Flip: " + if (flip) "On" else "Off", fontSize = 13.sp, maxLines = 1, softWrap = false) }
            OutlinedButton(onClick = { rotateDeg = when (rotateDeg) { 0 -> 90; 90 -> 180; 180 -> 270; else -> 0 } }, shape = RoundedCornerShape(28.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Text("Rotate: ${rotateDeg}°", fontSize = 13.sp, maxLines = 1, softWrap = false) }
        }
        Spacer(Modifier.height(8.dp))
        Text("Crop", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf<Pair<String?, String>>(null to "Original", "square" to "Square", "portrait" to "Portrait").forEach { (v, label) ->
                val sel = crop == v
                Box(Modifier.clip(RoundedCornerShape(28.dp)).background(if (sel) FrianzoBlue else MaterialTheme.colorScheme.surfaceVariant).clickable { crop = v }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Text(label, color = if (sel) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 1, softWrap = false)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = textOverlay, onValueChange = { if (it.length <= 100) textOverlay = it }, label = { Text("Text overlay (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        if (textOverlay.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text("Text position", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("top" to "Top", "middle" to "Middle", "bottom" to "Bottom").forEach { (v, label) ->
                    val sel = textPosition == v
                    Box(Modifier.clip(RoundedCornerShape(28.dp)).background(if (sel) FrianzoBlue else MaterialTheme.colorScheme.surfaceVariant).clickable { textPosition = v }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                        Text(label, color = if (sel) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        // R25: visual trim — drag the start/end of the video instead of
        // typing seconds into two boxes.
        if (videoDurationSec != null && videoDurationSec!! > 1) {
            Text("Trim: ${trimStartF.toInt()}s – ${trimEndF.toInt()}s", style = MaterialTheme.typography.bodyMedium)
            Slider(value = trimStartF, onValueChange = { trimStartF = it.coerceIn(0f, (trimEndF - 1f).coerceAtLeast(0f)) }, valueRange = 0f..videoDurationSec!!.toFloat())
            Slider(value = trimEndF, onValueChange = { trimEndF = it.coerceIn(trimStartF + 1f, videoDurationSec!!.toFloat()) }, valueRange = 0f..videoDurationSec!!.toFloat())
        }
        Spacer(Modifier.height(12.dp))
        // B-B37: the daily limit is enforced BEFORE the upload, not only
        // by the server after the whole file has uploaded.
        if (dailyStatus?.remaining == 0) {
            Text("You've reached today's reel limit — come back tomorrow.", color = FrianzoBadgeRed, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
        }
        Button(enabled = !busy && videoUri != null && (dailyStatus?.remaining ?: 1) > 0, onClick = {
            busy = true
            scope.launch {
                // B-B31: temp files are deleted in finally (below), so a
                // failed upload no longer leaks cache files.
                var tmp: File? = null
                var coverTmp: File? = null
                try {
                    val uri = videoUri!!
                    val durMs = try { val mmr = MediaMetadataRetriever(); try { mmr.setDataSource(context, uri); mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 15000L } finally { try { mmr.release() } catch (_: Exception) {} } } catch (_: Exception) { 15000L }
                    // R26: never coerce an over-long video into the limit —
                    // refuse it with a clear message instead.
                    val durationSec = (durMs / 1000).toInt()
                    if (durationSec < 1) throw Exception("Couldn't read this video's length.")
                    if (durationSec > maxSec) throw Exception("This video is ${durationSec}s — reels can be ${maxSec}s at most.")
                    val input = context.contentResolver.openInputStream(uri) ?: throw Exception("Could not read the video")
                    val mime = try { context.contentResolver.getType(uri) } catch (_: Exception) { null } ?: "video/mp4"
                    tmp = File(context.cacheDir, "reel_upload.mp4")
                    input.use { inp -> tmp!!.outputStream().use { out -> inp.copyTo(out) } }
                    val videoPart = MultipartBody.Part.createFormData("video", tmp!!.name, tmp!!.asRequestBody(mime.toMediaTypeOrNull()))
                    var coverPart: MultipartBody.Part? = null
                    // v3.8 (F3): a picked video frame beats a gallery cover.
                    val frameCover = selectedFrameIdx.takeIf { it >= 0 }?.let { coverFrames.getOrNull(it) }
                    if (frameCover != null) {
                        val cTmp = File(context.cacheDir, "reel_cover.jpg")
                        coverTmp = cTmp
                        cTmp.outputStream().use { out -> frameCover.compress(Bitmap.CompressFormat.JPEG, 85, out) }
                        coverPart = MultipartBody.Part.createFormData("cover", cTmp.name, cTmp.asRequestBody("image/jpeg".toMediaTypeOrNull()))
                    } else if (coverUri != null) {
                        val cInput = context.contentResolver.openInputStream(coverUri!!) ?: throw Exception("Could not read the cover photo")
                        val cMime = try { context.contentResolver.getType(coverUri!!) } catch (_: Exception) { null } ?: "image/jpeg"
                        val cExt = when (cMime.substringAfter("/")) { "png" -> "png"; "webp" -> "webp"; else -> "jpg" }
                        val cTmp = File(context.cacheDir, "reel_cover.$cExt")
                        coverTmp = cTmp
                        cInput.use { inp -> cTmp.outputStream().use { out -> inp.copyTo(out) } }
                        coverPart = MultipartBody.Part.createFormData("cover", cTmp.name, cTmp.asRequestBody(cMime.toMediaTypeOrNull()))
                    }
                    val trimStartSec = trimStartF.toInt().takeIf { it > 0 }
                    val trimEndSec = trimEndF.toInt().takeIf { it > 0 && it < durationSec && (trimStartSec == null || it > trimStartSec) }
                    val effectiveCategory = if (category == "Other") customCategory.trim() else category
                    val res = api2.uploadReel(
                        videoPart,
                        caption.ifBlank { null }?.toRequestBody("text/plain".toMediaTypeOrNull()),
                        effectiveCategory.ifBlank { null }?.toRequestBody("text/plain".toMediaTypeOrNull()),
                        durationSec.toString().toRequestBody("text/plain".toMediaTypeOrNull()),
                        cover = coverPart,
                        speed = if (speed == "1") null else speed.toRequestBody("text/plain".toMediaTypeOrNull()),
                        textOverlay = textOverlay.ifBlank { null }?.toRequestBody("text/plain".toMediaTypeOrNull()),
                        trimStartSec = trimStartSec?.toString()?.toRequestBody("text/plain".toMediaTypeOrNull()),
                        trimEndSec = trimEndSec?.toString()?.toRequestBody("text/plain".toMediaTypeOrNull()),
                        filter = if (filter == "normal") null else filter.toRequestBody("text/plain".toMediaTypeOrNull()),
                        // v3.8 (F4): editor extras — backend schema accepts them.
                        letterbox = if (letterbox) "true".toRequestBody("text/plain".toMediaTypeOrNull()) else null,
                        flip = if (flip) "true".toRequestBody("text/plain".toMediaTypeOrNull()) else null,
                        rotate = if (rotateDeg != 0) rotateDeg.toString().toRequestBody("text/plain".toMediaTypeOrNull()) else null,
                        crop = crop?.toRequestBody("text/plain".toMediaTypeOrNull()),
                        textPosition = if (textOverlay.isNotBlank()) textPosition.toRequestBody("text/plain".toMediaTypeOrNull()) else null,
                    )
                    if (!res.success) throw Exception(res.error?.message ?: "Upload failed")
                    // R29: hand the new reel's id back so the feed opens it.
                    onDone(res.data?.reel?.id)
                } catch (e: Exception) { message = friendlyReelsError(e, "Upload failed — try again") }
                finally { busy = false; try { tmp?.delete() } catch (_: Exception) {}; try { coverTmp?.delete() } catch (_: Exception) {} }
            }
        }, modifier = Modifier.fillMaxWidth().height(FrianzoUi.ButtonHeight), shape = RoundedCornerShape(FrianzoUi.ButtonRadius)) { Text(if (busy) "Uploading..." else "Upload reel", maxLines = 1, softWrap = false) }
        message?.let { Spacer(Modifier.height(12.dp)); Text(it, color = FrianzoBadgeRed) }
        if (busy) {
            // v3.7: no real % any more (CountingRequestBody deleted) —
            // indeterminate spinner while the upload runs.
            Spacer(Modifier.height(12.dp))
            DotsLoading()
            Spacer(Modifier.height(4.dp))
            Text("Uploading…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------- CREATE POST: text + optional photo ----------
// ---------- CREATE POST: text + optional photo ----------
@Composable
fun CreatePostScreen(api2: FrianzoApi2, initialUri: android.net.Uri? = null, onPosted: () -> Unit, onClose: () -> Unit = {}, onPostAsReel: ((android.net.Uri) -> Unit)? = null) {
    val context = LocalContext.current
    var showDiscard by remember { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    var imageUri by remember { mutableStateOf<Uri?>(initialUri) }
    var mediaIsVideo by remember { mutableStateOf(false) }
    var mediaMime by remember { mutableStateOf<String?>(null) }
    var bgStyle by rememberSaveable { mutableStateOf<String?>(null) }
    // Pre-attached media from the feed + button (system Gallery picker).
    LaunchedEffect(initialUri) {
        if (initialUri != null) {
            imageUri = initialUri
            bgStyle = null // B-B13: pre-attached media also drops the background
            val mime = try { context.contentResolver.getType(initialUri) } catch (_: Exception) { null }
            mediaMime = mime
            mediaIsVideo = mime?.startsWith("video") == true
        }
    }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    // v3.4 composer options (all sent on the one POST /api/posts call).
    var visibility by rememberSaveable { mutableStateOf("public") }
    var commentAudience by rememberSaveable { mutableStateOf("everyone") }
    var audienceMenu by remember { mutableStateOf(false) }
    var commentsMenu by remember { mutableStateOf(false) }
    var tagged by remember { mutableStateOf<List<PublicProfile>>(emptyList()) }
    var showTagPicker by remember { mutableStateOf(false) }
    var location by rememberSaveable { mutableStateOf<String?>(null) }
    var locationInput by remember { mutableStateOf(false) }
    var locationText by rememberSaveable { mutableStateOf("") }
    var locationBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // v3.4 location: permission -> device GPS -> GET /api/geo/reverse.
    // If the permission is denied, or no fix / no lookup result, show a
    // plain text field instead — the location is never faked.
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) {
            locationBusy = true
            scope.launch {
                try {
                    val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
                    val loc = try { lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER) } catch (_: Exception) { null }
                        ?: try { lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER) } catch (_: Exception) { null }
                    if (loc == null) { locationInput = true; message = "Couldn't get your location — type it instead." }
                    else {
                        val r = api2.reverseGeo(loc.latitude, loc.longitude)
                        val name = r.data?.location
                        if (r.success && !name.isNullOrBlank()) { location = name; locationInput = false }
                        else { locationInput = true; message = "Couldn't look up that place — type it instead." }
                    }
                } catch (e: Exception) { locationInput = true; message = e.message ?: "Couldn't get your location — type it instead." }
                locationBusy = false
            }
        } else {
            locationInput = true
        }
    }
    // v3.4 record video: there is NO video-post endpoint, so a recorded
    // clip goes to the reel uploader (onPostAsReel), never to a post.
    var recordUri by remember { mutableStateOf<Uri?>(null) }
    val takeVideo = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val u = recordUri
            if (u != null) onPostAsReel?.invoke(u) else message = "Could not start the reel uploader"
        }
    }
    val recordVideo = rememberPermissionGate(cameraMicPermissions(), onGranted = {
        val u = createVideoUri(context)
        if (u != null) { recordUri = u; takeVideo.launch(Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply { putExtra(MediaStore.EXTRA_OUTPUT, u); addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }) } else message = "Could not start camera"
    })
    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) {
            imageUri = uri
            // B-B13: a picked photo/video drops any text background — the
            // backend rejects background + image together, so the style
            // must never be sent with a media post.
            bgStyle = null
            val mime = try { context.contentResolver.getType(uri) } catch (_: Exception) { null }
            mediaMime = mime
            mediaIsVideo = mime?.startsWith("video") == true
        }
    }
    val pickPostMedia = rememberPermissionGate(galleryPermissions(), onGranted = { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageAndVideo)) })
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // v2.9: Cancel / Discard — confirms first when the post is dirty.
            TextButton(onClick = { if (text.isNotBlank() || imageUri != null || bgStyle != null || location != null || locationText.isNotBlank() || tagged.isNotEmpty() || visibility != "public" || commentAudience != "everyone") showDiscard = true else onClose() }) { Text("Cancel", maxLines = 1, softWrap = false) }
            Text("New post", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(12.dp))
        // v3.4: audience + comments pills.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                OutlinedButton(onClick = { audienceMenu = true }, shape = RoundedCornerShape(28.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Text(if (visibility == "public") "Public" else "Only me", fontSize = 13.sp, maxLines = 1, softWrap = false) }
                DropdownMenu(expanded = audienceMenu, onDismissRequest = { audienceMenu = false }) {
                    DropdownMenuItem(text = { Text("Public") }, onClick = { visibility = "public"; audienceMenu = false })
                    DropdownMenuItem(text = { Text("Only me") }, onClick = { visibility = "private"; audienceMenu = false })
                }
            }
            Box {
                OutlinedButton(onClick = { commentsMenu = true }, shape = RoundedCornerShape(28.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Text("Comments: " + when (commentAudience) { "followers" -> "Followers"; "only_me" -> "Only me"; else -> "Everyone" }, fontSize = 13.sp, maxLines = 1, softWrap = false) }
                DropdownMenu(expanded = commentsMenu, onDismissRequest = { commentsMenu = false }) {
                    listOf("everyone" to "Everyone", "followers" to "Followers", "only_me" to "Only me").forEach { (v, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { commentAudience = v; commentsMenu = false })
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = text, onValueChange = { if (it.length <= 2000) text = it }, label = { Text("What's on your mind?") }, modifier = Modifier.fillMaxWidth().height(140.dp), shape = RoundedCornerShape(12.dp))
        Text("${text.length}/2000", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.End)
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
        OutlinedButton(shape = RoundedCornerShape(28.dp), onClick = { pickPostMedia() }) { Row(verticalAlignment = Alignment.CenterVertically) { if (imageUri != null) { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)) }; Text(if (imageUri == null) "Add photo or video" else if (mediaIsVideo) "Video selected" else "Photo selected", maxLines = 1, softWrap = false) } }
        if (imageUri != null && !mediaIsVideo) {
            Spacer(Modifier.height(8.dp))
            AsyncImage(model = imageUri, contentDescription = null, modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(12.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        }
        // B-B13: attached media can be removed without cancelling the post.
        if (imageUri != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { imageUri = null; mediaIsVideo = false; mediaMime = null }, shape = RoundedCornerShape(28.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Text(if (mediaIsVideo) "Remove video" else "Remove photo", maxLines = 1, softWrap = false) }
        }
        if (mediaIsVideo) {
            Spacer(Modifier.height(8.dp))
            Text("Video selected from Gallery. Feed posts here accept only a photo — a video-post endpoint does not exist yet — so Post stays off for a video. Pick a photo instead, or post the video as a reel.", style = MaterialTheme.typography.bodySmall)
            if (onPostAsReel != null && imageUri != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { onPostAsReel(imageUri!!) }, shape = RoundedCornerShape(28.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Text("Post as reel instead", maxLines = 1, softWrap = false) }
            }
        }
        Spacer(Modifier.height(8.dp))
        // v3.4: tag people (GET /api/users/search) + location.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showTagPicker = true }, shape = RoundedCornerShape(28.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Text(if (tagged.isEmpty()) "Tag people" else "Tagged (${tagged.size})", maxLines = 1, softWrap = false) }
            if (location != null) {
                Box(Modifier.clip(RoundedCornerShape(28.dp)).background(FrianzoBlueTint).padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(location!!, fontSize = 13.sp, color = FrianzoBlueDark, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Filled.Close, contentDescription = "Remove location", tint = FrianzoBlueDark, modifier = Modifier.size(14.dp).clickable { location = null })
                    }
                }
            } else {
                OutlinedButton(enabled = !locationBusy, onClick = { locationLauncher.launch(locationPermissions()) }, shape = RoundedCornerShape(28.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Text(if (locationBusy) "Finding place..." else "Add location", maxLines = 1, softWrap = false) }
            }
            if (onPostAsReel != null) {
                OutlinedButton(onClick = { recordVideo() }, shape = RoundedCornerShape(28.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Text("Record video", maxLines = 1, softWrap = false) }
            }
        }
        if (tagged.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(tagged, key = { it.id ?: it.username ?: "" }) { u ->
                    Box(Modifier.clip(RoundedCornerShape(28.dp)).background(FrianzoBlueTint).padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("@${u.username ?: ""}", fontSize = 13.sp, color = FrianzoBlueDark, maxLines = 1, softWrap = false)
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Filled.Close, contentDescription = "Remove tag", tint = FrianzoBlueDark, modifier = Modifier.size(14.dp).clickable { tagged = tagged.filter { it.id != u.id } })
                        }
                    }
                }
            }
        }
        if (locationInput && location == null) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = locationText, onValueChange = { if (it.length <= 200) locationText = it }, label = { Text("Location") }, placeholder = { Text("Type a place name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
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
                        // B-B32: refuse an over-10MB photo BEFORE copying
                        // and uploading it (same 10MB cap as reel covers).
                        val photoSize = try { context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L } catch (_: Exception) { -1L }
                        if (photoSize > 10 * 1024 * 1024) throw Exception("Photo must be smaller than 10MB")
                        val input = context.contentResolver.openInputStream(uri)
                            ?: throw Exception("Could not read the selected photo")
                        val mime = mediaMime ?: (try { context.contentResolver.getType(uri) } catch (_: Exception) { null }) ?: "image/jpeg"
                        val ext = when (mime.substringAfter("/")) { "png" -> "png"; "webp" -> "webp"; "gif" -> "gif"; else -> "jpg" }
                        val tmp = File(context.cacheDir, "post_upload.$ext")
                        tmpFile = tmp
                        input.use { inp -> tmp.outputStream().use { out -> inp.copyTo(out) } }
                        part = MultipartBody.Part.createFormData("image", tmp.name, tmp.asRequestBody(mime.toMediaTypeOrNull()))
                    }
                    val locValue = location ?: locationText.trim().takeIf { it.isNotBlank() }
                    val res = api2.createPost(
                        // B-B40: omit the content part for image-only posts
                        // (backend-verified: empty content is accepted only
                        // with an image; omitting it is the clean form).
                        if (text.isBlank()) null else text.toRequestBody("text/plain".toMediaTypeOrNull()),
                        part,
                        backgroundStyle = if (imageUri != null) null else bgStyle?.toRequestBody("text/plain".toMediaTypeOrNull()),
                        visibility = visibility.toRequestBody("text/plain".toMediaTypeOrNull()),
                        commentAudience = commentAudience.toRequestBody("text/plain".toMediaTypeOrNull()),
                        taggedUserIds = if (tagged.isEmpty()) null else com.google.gson.Gson().toJson(tagged.mapNotNull { it.id }).toRequestBody("text/plain".toMediaTypeOrNull()),
                        location = locValue?.toRequestBody("text/plain".toMediaTypeOrNull()),
                    )
                    // Temp upload file is cache-only; delete it after use
                    // instead of leaving post_upload.jpg behind forever.
                    try { tmpFile?.delete() } catch (_: Exception) {}
                    if (!res.success) throw Exception(res.error?.message ?: "Post failed")
                    message = "Posted"
                    onPosted()
                } catch (e: Exception) { message = e.message ?: "Post failed" }
                finally { busy = false }
            }
        }, modifier = Modifier.fillMaxWidth().height(FrianzoUi.ButtonHeight), shape = RoundedCornerShape(FrianzoUi.ButtonRadius)) { Text("Post", maxLines = 1, softWrap = false) }
        message?.let { Spacer(Modifier.height(12.dp)); Text(it) }
        if (busy) { Spacer(Modifier.height(12.dp)); DotsLoading() }
    }
    if (showTagPicker) {
        TagPeopleDialog(api2, tagged, onDone = { tagged = it; showTagPicker = false }, onClose = { showTagPicker = false })
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

// v3.4: tag-people picker — searches real users (GET /api/users/search),
// returns the chosen users; the composer sends their ids as taggedUserIds.
@Composable
private fun TagPeopleDialog(api2: FrianzoApi2, initial: List<PublicProfile>, onDone: (List<PublicProfile>) -> Unit, onClose: () -> Unit) {
    var q by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PublicProfile>>(emptyList()) }
    var selected by remember { mutableStateOf(initial) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(q) {
        if (q.isBlank()) { results = emptyList(); return@LaunchedEffect }
        delay(300)
        // B-B7: stale responses must not overwrite newer results.
        val term = q
        scope.launch {
            try { val r = api2.searchUsers(term); if (term != q) return@launch; if (r.success && r.data != null) results = r.data.users } catch (_: Exception) {}
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Tag people") },
        text = {
            Column {
                OutlinedTextField(value = q, onValueChange = { q = it }, label = { Text("Search people") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                    items(results, key = { it.id ?: it.username ?: "" }) { u ->
                        val sel = selected.any { it.id == u.id }
                        Row(Modifier.fillMaxWidth().clickable { selected = if (sel) selected.filter { it.id != u.id } else selected + u }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = sel, onCheckedChange = { checked -> selected = if (checked) selected + u else selected.filter { it.id != u.id } })
                            FrianzoAvatar(u.profilePictureUrl, 40.dp)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(u.displayName ?: u.username ?: "", fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("@${u.username ?: ""}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    if (results.isEmpty() && q.isNotBlank()) item { Text("No people found", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp)) }
                }
                if (selected.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text("${selected.size} tagged", fontSize = 13.sp, color = FrianzoBlue) }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(selected) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

// ---------- CHAT: list + thread (REST polling; live socket = later stage) ----------
@Composable
fun ChatListScreen(api2: FrianzoApi2, myId: String? = null, onOpen: (String, String) -> Unit) {
    var list by remember { mutableStateOf<List<Conversation>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    // B-B18: loading flag so "No chats yet" never flashes before the
    // first load finishes.
    var loading by remember { mutableStateOf(true) }
    // B-B19: starting a new chat shows its error instead of doing nothing.
    var newChatError by remember { mutableStateOf<String?>(null) }
    // v3.4: this box filters the loaded conversations (client-side — the
    // backend has no conversation-search endpoint).
    var q by rememberSaveable { mutableStateOf("") }
    // v3.4: starting a NEW chat is a separate row/button below the filter.
    var showNewChat by remember { mutableStateOf(false) }
    var newQ by rememberSaveable { mutableStateOf("") }
    var found by remember { mutableStateOf<List<PublicProfile>>(emptyList()) }
    val scope = rememberCoroutineScope()
    fun loadList() {
        loading = true
        scope.launch {
            try {
                val res = api2.conversations()
                if (res.success && res.data != null) list = res.data else error = res.error?.message
            } catch (e: Exception) { error = e.message }
            finally { loading = false }
        }
    }
    LaunchedEffect(Unit) { loadList() }
    // B-B7: only the latest query may write results — a slow earlier
    // response must not overwrite a newer one.
    LaunchedEffect(newQ) {
        if (newQ.isBlank()) { found = emptyList(); return@LaunchedEffect }
        delay(300)
        val term = newQ
        scope.launch {
            try { val r = api2.searchUsers(term); if (term != newQ) return@launch; if (r.success && r.data != null) found = r.data.users } catch (_: Exception) {}
        }
    }
    val shown = if (q.isBlank()) list else list.filter { c ->
        val name = "${c.otherUser?.displayName ?: ""} ${c.otherUser?.username ?: ""}"
        name.contains(q, ignoreCase = true) || (c.lastMessage?.content ?: "").contains(q, ignoreCase = true)
    }
    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentPadding = PaddingValues(vertical = 8.dp)) {
        item { Text("Chats", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        item {
            OutlinedTextField(value = q, onValueChange = { q = it }, label = { Text("Search conversations...") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true, shape = RoundedCornerShape(28.dp))
        }
        item {
            TextButton(onClick = { showNewChat = !showNewChat }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { Text(if (showNewChat) "Close new chat" else "Message someone new", modifier = Modifier.fillMaxWidth(), maxLines = 1, softWrap = false) }
        }
        if (showNewChat) {
            item {
                OutlinedTextField(value = newQ, onValueChange = { newQ = it }, label = { Text("Search people to message") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true, shape = RoundedCornerShape(28.dp))
            }
            if (newChatError != null) {
                item { Text(newChatError!!, color = FrianzoBadgeRed, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
            }
            items(found, key = { it.id ?: it.username ?: "" }) { u ->
                ListItem(
                    headlineContent = { Text(u.displayName ?: u.username ?: "", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("@${u.username ?: ""}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { FrianzoAvatar(u.profilePictureUrl, 48.dp) },
                    modifier = Modifier.clickable {
                        if (u.id != null) scope.launch {
                            try {
                                val r = api2.createConversation(CreateConversationRequest(u.id))
                                if (r.success && r.data != null) { newChatError = null; newQ = ""; showNewChat = false; onOpen(r.data.id, u.displayName ?: u.username ?: "") }
                                else newChatError = r.error?.message ?: "Couldn't start chat — try again"
                            } catch (e: Exception) { newChatError = e.message ?: "Couldn't start chat — try again" }
                        }
                    },
                )
                HorizontalDivider()
            }
        }
        items(shown, key = { it.id }) { c ->
            val other = c.otherUser
            val last = c.lastMessage
            // v3.4: preview from the real payload — "You:" for my own
            // message, "Photo"/"Voice message" when mediaType is set.
            val preview = when {
                last == null -> ""
                last.isDeleted -> "Message deleted"
                last.mediaType == "image" -> "Photo"
                last.mediaType == "voice" -> "Voice message"
                else -> last.content ?: ""
            }
            val youPrefix = last != null && last.senderId == myId && !last.isDeleted && preview.isNotBlank()
            ListItem(
                headlineContent = { Text(other?.displayName ?: other?.username ?: "Chat", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text((if (youPrefix) "You: " else "") + preview, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingContent = { FrianzoAvatar(other?.profilePictureUrl, 48.dp) },
                trailingContent = {
                    Column(horizontalAlignment = Alignment.End) {
                        val whenText = notifTimeAgo(last?.createdAt ?: c.updatedAt)
                        if (whenText.isNotBlank()) Text(whenText, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // v3.4: read ticks from lastMessageStatus (only
                            // for my own last message): sent ✓, delivered ✓✓,
                            // read ✓✓ in blue.
                            if (last != null && last.senderId == myId && c.lastMessageStatus != null) {
                                Text(
                                    when (c.lastMessageStatus) { "read", "delivered" -> "✓✓"; else -> "✓" },
                                    fontSize = 12.sp,
                                    color = if (c.lastMessageStatus == "read") FrianzoBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, softWrap = false,
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            if (c.unreadCount > 0) Badge(containerColor = FrianzoBadgeRed) { Text("${c.unreadCount}", maxLines = 1, softWrap = false) }
                            IconButton(onClick = { scope.launch { try { if (api2.deleteConversation(c.id).success) list = list.filter { it.id != c.id } } catch (_: Exception) {} } }) { Icon(Icons.Filled.Delete, contentDescription = "Delete chat", modifier = Modifier.size(20.dp)) }
                        }
                    }
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.clickable { onOpen(c.id, other?.displayName ?: other?.username ?: "") },
            )
            HorizontalDivider()
        }
        if (error != null) item { Text(error!!, Modifier.padding(16.dp)) }
        if (loading) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { DotsLoading() } }
        if (!loading && shown.isEmpty() && error == null) item { Text(if (list.isEmpty()) "No chats yet" else "No conversations match", Modifier.padding(16.dp)) }
    }
}

@Composable
fun ChatThreadScreen(api2: FrianzoApi2, conversationId: String, title: String, myId: String?, onBack: () -> Unit = {}, onOpenProfile: ((String) -> Unit)? = null) {
    var messages by remember { mutableStateOf<List<ConversationMessage>>(emptyList()) }
    var draft by rememberSaveable { mutableStateOf("") }
    var loadError by remember { mutableStateOf<String?>(null) }
    var sendError by remember { mutableStateOf<String?>(null) }
    var otherUserId by remember { mutableStateOf<String?>(null) }
    var otherUsername by remember { mutableStateOf<String?>(null) }
    var otherAvatar by remember { mutableStateOf<String?>(null) }
    var otherLastActiveAt by remember { mutableStateOf<String?>(null) }
    var otherIsOnline by remember { mutableStateOf(false) }
    // v3.4: disappearing timer + presence come from the conversations list
    // data on open (GET /api/messages/conversations) — never defaulted.
    var disappearingDuration by remember { mutableStateOf<Int?>(null) }
    var isBlocked by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var showTimer by remember { mutableStateOf(false) }
    var timerLabel by remember { mutableStateOf("Off") }
    var showReport by remember { mutableStateOf(false) }
    var reportReason by remember { mutableStateOf("other") }
    var reportDetails by rememberSaveable { mutableStateOf("") }
    var reportDone by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun disappearingLabel(duration: Int?): String = when (duration) {
        3600 -> "1 hour"
        86400 -> "1 day"
        604800 -> "1 week"
        7776000 -> "90 days"
        null -> "Off"
        else -> "${duration / 3600} hours"
    }
    fun lastSeenText(iso: String?): String {
        val ago = notifTimeAgo(iso)
        return when {
            ago.isBlank() -> ""
            ago == "now" -> "just now"
            else -> "$ago ago"
        }
    }
    LaunchedEffect(conversationId) {
        scope.launch {
            try {
                val res = api2.conversations()
                val conv = res.data?.firstOrNull { it.id == conversationId }
                val other = conv?.otherUser
                otherUserId = other?.id
                otherUsername = other?.username
                otherAvatar = other?.profilePictureUrl
                otherLastActiveAt = other?.lastActiveAt
                otherIsOnline = other?.isOnline == true
                disappearingDuration = conv?.disappearingDuration
                timerLabel = disappearingLabel(conv?.disappearingDuration)
                if (other?.id != null) { try { val bs = api2.blockStatus(other.id); isBlocked = bs.data?.blockedByMe == true } catch (_: Exception) {} }
            } catch (_: Exception) {}
        }
    }
    // B-B39: the other side's read/delivered watermarks (see tickFor).
    var otherLastReadAt by remember { mutableStateOf<String?>(null) }
    var otherLastDeliveredAt by remember { mutableStateOf<String?>(null) }
    // B-B36: only replace the list when it actually changed — a wholesale
    // replace every 5s recomposed (and could jump) the list even when
    // nothing new arrived.
    fun mergeMessages(fresh: List<ConversationMessage>) {
        val current = messages
        val changed = fresh.size != current.size || fresh.zip(current).any { (a, b) -> a.id != b.id || a.content != b.content || a.isDeleted != b.isDeleted }
        if (changed) messages = fresh
    }
    // PERMANENT FIX for H3: poll every 5s while the thread is open, so
    // incoming messages appear without leaving and re-opening the thread.
    // The loop is tied to LaunchedEffect, so it stops when the thread closes.
    LaunchedEffect(conversationId) {
        var tick = 0
        while (true) {
            try {
                val res = api2.messages(conversationId)
                // Backend already returns messages chronological (old -> new);
                // reverse once so newest-first pairs with reverseLayout below.
                if (res.success && res.data != null) { mergeMessages(res.data.messages.reversed()); otherLastReadAt = res.data.otherLastReadAt; otherLastDeliveredAt = res.data.otherLastDeliveredAt; loadError = null }
                else loadError = res.error?.message
            } catch (e: Exception) { loadError = e.message }
            // B-B25: presence ("Active now" / last seen) and the timer are
            // refreshed every 3rd tick (15s) instead of freezing at open.
            tick += 1
            if (tick % 3 == 0) {
                try {
                    val conv = api2.conversations().data?.firstOrNull { it.id == conversationId }
                    val other = conv?.otherUser
                    if (other != null) { otherIsOnline = other.isOnline == true; otherLastActiveAt = other.lastActiveAt }
                    if (conv != null && conv.disappearingDuration != disappearingDuration) { disappearingDuration = conv.disappearingDuration; timerLabel = disappearingLabel(conv.disappearingDuration) }
                } catch (_: Exception) {}
            }
            delay(5000)
        }
    }
    fun load() {
        scope.launch {
            try {
                val res = api2.messages(conversationId)
                if (res.success && res.data != null) { mergeMessages(res.data.messages.reversed()); otherLastReadAt = res.data.otherLastReadAt; otherLastDeliveredAt = res.data.otherLastDeliveredAt }
            } catch (e: Exception) { loadError = e.message }
        }
    }
    val presenceText = when {
        otherIsOnline -> "Active now"
        otherLastActiveAt != null -> "Last seen ${lastSeenText(otherLastActiveAt)}"
        else -> ""
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Back", maxLines = 1, softWrap = false) }
            FrianzoAvatar(otherAvatar, 40.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (presenceText.isNotBlank()) Text(presenceText, fontSize = 12.sp, color = if (otherIsOnline) FrianzoSuccess else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
            }
            if (timerLabel != "Off") Text(timerLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Chat options") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (otherUsername != null) DropdownMenuItem(text = { Text("View profile") }, onClick = { menuOpen = false; onOpenProfile?.invoke(otherUsername!!) })
                    DropdownMenuItem(text = { Text("Disappearing messages: $timerLabel") }, onClick = { menuOpen = false; showTimer = true })
                    if (otherUserId != null) DropdownMenuItem(text = { Text("Report user") }, onClick = { menuOpen = false; reportDone = false; showReport = true })
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
        // v3.4: big avatar + name + "View profile" pill header.
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            FrianzoAvatar(otherAvatar, 72.dp)
            Spacer(Modifier.height(8.dp))
            Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (otherUsername != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { onOpenProfile?.invoke(otherUsername!!) }, shape = RoundedCornerShape(28.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) { Text("View profile", maxLines = 1, softWrap = false) }
            }
        }
        // v3.4: amber banner only when the conversation really has a timer.
        if (disappearingDuration != null) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFFEF3C7)).padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text("Messages disappear after ${disappearingLabel(disappearingDuration)}", fontSize = 13.sp, color = Color(0xFF92400E))
            }
            Spacer(Modifier.height(8.dp))
        }
        if (showTimer) {
            AlertDialog(onDismissRequest = { showTimer = false }, title = { Text("Disappearing messages") },
                text = { Column { listOf(null to "Off", 3600 to "1 hour", 86400 to "1 day", 604800 to "1 week").forEach { (secs, label) ->
                    // B-B2: Off sends an explicit {"duration":null} — the
                    // backend accepts null = Off (0 would be rejected 400),
                    // and default Gson drops nulls from a Map, so the body
                    // is built as raw JSON. The optimistic UI reverts on
                    // failure instead of lying about the timer.
                    TextButton(onClick = { showTimer = false; val prevLabel = timerLabel; val prevDuration = disappearingDuration; timerLabel = label; disappearingDuration = secs; scope.launch { try { val body = (if (secs == null) "{\"duration\":null}" else "{\"duration\":$secs}").toRequestBody("application/json".toMediaTypeOrNull()); val r = api2.setDisappearing(conversationId, body); if (!r.success) { timerLabel = prevLabel; disappearingDuration = prevDuration; sendError = r.error?.message ?: "Couldn't change disappearing timer" } } catch (e: Exception) { timerLabel = prevLabel; disappearingDuration = prevDuration; sendError = e.message } } }, modifier = Modifier.fillMaxWidth()) { Text(label, modifier = Modifier.fillMaxWidth(), maxLines = 1, softWrap = false) }
                } } },
                confirmButton = {}, dismissButton = { TextButton(onClick = { showTimer = false }) { Text("Cancel") } })
        }
        if (showReport) {
            AlertDialog(onDismissRequest = { showReport = false }, title = { Text("Report user") },
                text = {
                    Column {
                        Text("Why are you reporting this user?", fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        listOf("spam" to "Spam", "harassment" to "Harassment", "hate_speech" to "Hate speech", "violence" to "Violence", "nudity" to "Nudity", "misinformation" to "Misinformation", "other" to "Other").forEach { (v, label) ->
                            Row(Modifier.fillMaxWidth().clickable { reportReason = v }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = reportReason == v, onClick = { reportReason = v })
                                Text(label, fontSize = 14.sp)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = reportDetails, onValueChange = { if (it.length <= 500) reportDetails = it }, label = { Text("Details (optional)") }, modifier = Modifier.fillMaxWidth())
                        if (reportDone) { Spacer(Modifier.height(8.dp)); Text("Report sent. Thanks for letting us know.", fontSize = 13.sp, color = FrianzoSuccess) }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val uid = otherUserId
                        if (uid != null) scope.launch {
                            try {
                                val body = mutableMapOf("reason" to reportReason)
                                if (reportDetails.isNotBlank()) body["details"] = reportDetails
                                if (api2.reportUser(uid, body).success) reportDone = true else sendError = "Could not send report"
                            } catch (e: Exception) { sendError = e.message }
                        }
                    }) { Text("Send report") }
                },
                dismissButton = { TextButton(onClick = { showReport = false }) { Text("Cancel") } })
        }
        // B-B39: per-message read ticks. The backend does not (yet)
        // populate per-message isRead/readAt, so ticks derive from the
        // other side's watermarks (GET .../messages returns
        // otherLastReadAt / otherLastDeliveredAt); when the backend fills
        // isRead, that wins. NEEDS-BACKEND for the per-message fields.
        fun tickFor(m: ConversationMessage): Pair<String, Boolean> {
            if (m.isRead == true) return "✓✓" to true
            val ts = m.createdAt ?: return "✓" to false
            if (otherLastReadAt != null && ts <= otherLastReadAt!!) return "✓✓" to true
            if (otherLastDeliveredAt != null && ts <= otherLastDeliveredAt!!) return "✓✓" to false
            return "✓" to false
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = true) {
            items(messages, key = { it.id ?: "" }) { m ->
                val mine = m.senderId == myId
                Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
                    Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                    Surface(color = if (mine) FrianzoBlue else MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp)) {
                        // v3.4: a received photo/voice has no text content —
                        // show "Photo"/"Voice message" instead of an empty bubble.
                        val mediaLabel = when {
                            m.isDeleted -> "Message deleted"
                            m.mediaType == "image" -> "Photo"
                            m.mediaType == "voice" -> "Voice message" + if ((m.voiceDuration ?: 0) > 0) " (${m.voiceDuration}s)" else ""
                            else -> null
                        }
                        val shown = if ((m.content ?: "").isBlank()) mediaLabel ?: "" else m.content ?: ""
                        Text(shown, Modifier.padding(12.dp), color = if (mine) Color.White else MaterialTheme.colorScheme.onSurface)
                    }
                    if (mine) {
                        val (tick, tickRead) = tickFor(m)
                        Text(tick, fontSize = 11.sp, color = if (tickRead) FrianzoBlue else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp, end = 4.dp), maxLines = 1, softWrap = false)
                    }
                    } // Column
                }
            }
        }
        loadError?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = FrianzoBadgeRed) }
        sendError?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = FrianzoBadgeRed) }
        Row(Modifier.background(MaterialTheme.colorScheme.surface).padding(8.dp)) {
            // v3.4: no photo/mic send buttons — photo/voice sending is
            // socket-only (no REST endpoint), so they are not faked here.
            OutlinedTextField(value = draft, onValueChange = { draft = it }, placeholder = { Text("Message...") }, modifier = Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(28.dp))
            // v3.4: Send appears only once text is typed.
            if (draft.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                // B-B1: a server rejection restores the draft (was: the
                // message vanished with no error on success == false).
                Button(shape = RoundedCornerShape(28.dp), onClick = {
                    sendError = null
                    val text = draft; draft = ""
                    scope.launch {
                        try {
                            val r = api2.sendMessage(conversationId, SendMessageRequest(text))
                            if (r.success) load() else { draft = text; sendError = r.error?.message ?: "Couldn't send message" }
                        } catch (e: Exception) { draft = text; sendError = e.message }
                    }
                }) { Text("Send", maxLines = 1, softWrap = false) }
            }
        }
    }
}

// ---------- NOTIFICATIONS ----------
/** M1 fix: raw type codes ("reel_like", blank actor) rendered as
 * " reel_like". Map known types to plain words and fall back to the
 * type with underscores replaced, never a blank headline. */
private fun notificationTitle(n: NotificationItem): String {
    val actor = n.actor?.displayName ?: n.actor?.username ?: "Someone"
    // B-B34: every type the backend can send (notificationService
    // NotificationType union), plus "message" for safety. Unknown future
    // types fall back to plain English, never the raw snake_case code.
    val action = when (n.type) {
        "post_like" -> "liked your post"
        "reel_like" -> "liked your reel"
        "post_comment" -> "commented on your post"
        "reel_comment" -> "commented on your reel"
        "comment" -> "commented on your post"
        "comment_like" -> "liked your comment"
        "comment_reply" -> "replied to your comment"
        "reel_reply" -> "replied to your reel comment"
        "comment_mention" -> "mentioned you in a comment"
        "post_share" -> "shared your post"
        "follow" -> "started following you"
        "new_device_login" -> "signed in from a new device"
        "message" -> "sent you a message"
        "account_restricted" -> "your account was restricted"
        "account_banned" -> "your account was banned"
        "verification_approved" -> "your verification was approved"
        "verification_rejected" -> "your verification was not approved"
        "comment_removed" -> "a comment was removed"
        "post_removed" -> "a post was removed"
        "reel_removed" -> "a reel was removed"
        "messaging_restricted" -> "your messaging was restricted"
        else -> "sent you a notification"
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
    // B-B18: loading flag so "You're all caught up" never flashes before
    // the first load finishes. B-B20: action failures are shown (was: the
    // dialog closed and nothing changed, silently).
    var loading by remember { mutableStateOf(true) }
    var actionError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun load() {
        loading = true
        scope.launch {
            try {
                val res = api2.notifications()
                if (res.success && res.data != null) items = res.data.notifications else error = res.error?.message
            } catch (e: Exception) { error = e.message }
            finally { loading = false }
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
        // v3.0: title on its own row — the three action buttons used to share
        // one Row with it, leaving the title ~60dp wide so it wrapped letter
        // by letter. Actions now sit on a second row, single-line, no wrap.
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("Notifications", style = MaterialTheme.typography.titleLarge, maxLines = 1, softWrap = false)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { selecting = !selecting; if (!selecting) selected = emptySet() }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text(if (selecting) "Cancel" else "Select", maxLines = 1, softWrap = false) }
                TextButton(onClick = { showMarkAllConfirm = true }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("Mark all read", maxLines = 1, softWrap = false) }
                TextButton(onClick = { showDeleteAllConfirm = true }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("Delete all", color = Color.Red, maxLines = 1, softWrap = false) }
            }
        }
        if (selecting && selected.isNotEmpty()) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${selected.size} selected", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Button(onClick = { scope.launch { try { val r = api2.deleteNotifications(DeleteIdsRequest(selected.toList())); if (r.success) { items = items.filter { it.id !in selected }; selected = emptySet(); selecting = false; actionError = null } else actionError = r.error?.message ?: "Couldn't delete notifications" } catch (e: Exception) { actionError = e.message ?: "Couldn't delete notifications" } } }, colors = ButtonDefaults.buttonColors(containerColor = FrianzoBadgeRed), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Text("Delete selected", maxLines = 1, softWrap = false) }
            }
        }
        if (showDeleteAllConfirm) {
            AlertDialog(onDismissRequest = { showDeleteAllConfirm = false }, title = { Text("Delete all notifications?") }, text = { Text("This can't be undone.") },
                confirmButton = { Button(onClick = { scope.launch { try { val r = api2.deleteAllNotifications(); if (r.success) { items = emptyList(); showDeleteAllConfirm = false; actionError = null } else actionError = r.error?.message ?: "Couldn't delete notifications" } catch (e: Exception) { actionError = e.message ?: "Couldn't delete notifications" } } }, colors = ButtonDefaults.buttonColors(containerColor = FrianzoBadgeRed)) { Text("Delete all", maxLines = 1, softWrap = false) } },
                dismissButton = { TextButton(onClick = { showDeleteAllConfirm = false }) { Text("Cancel") } })
        }
        if (showMarkAllConfirm) {
            AlertDialog(
                onDismissRequest = { showMarkAllConfirm = false },
                title = { Text("Mark all as read?") },
                text = { Text("All notifications will be marked as read.") },
                confirmButton = { Button(onClick = { scope.launch { try { val r = api2.markAllNotificationsRead(); if (r.success) { showMarkAllConfirm = false; actionError = null; load() } else actionError = r.error?.message ?: "Couldn't mark notifications read" } catch (e: Exception) { actionError = e.message ?: "Couldn't mark notifications read" } } }) { Text("Mark all read", maxLines = 1, softWrap = false) } },
                dismissButton = { TextButton(onClick = { showMarkAllConfirm = false }) { Text("Cancel") } },
            )
        }
        // Filter chips below the header (placement: header first, filters
        // second, list last — the live-web order).
        LazyRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("All", "Unread", "Likes", "Comments", "Follows", "Messages")) { f ->
                val sel = filter == f
                Box(Modifier.clip(RoundedCornerShape(28.dp)).background(if (sel) FrianzoBlue else MaterialTheme.colorScheme.surfaceVariant).clickable { filter = f }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Text(f, style = MaterialTheme.typography.bodyMedium, color = if (sel) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        actionError?.let { Text(it, color = FrianzoBadgeRed, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        LazyColumn(Modifier.weight(1f)) {
            if (loading) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { DotsLoading() } }
            items(filtered, key = { it.id }) { n ->
                ListItem(
                    headlineContent = { Text(notificationTitle(n), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(listOfNotNull(n.detail?.takeIf { it.isNotBlank() }, notifTimeAgo(n.createdAt).takeIf { it.isNotBlank() }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = { if (!n.read) Badge(containerColor = FrianzoBadgeRed) { Text("new", maxLines = 1, softWrap = false) } },
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
            if (!loading && filtered.isEmpty() && error == null) item { Text(if (items.isEmpty()) "You're all caught up. New activity will show up here." else "Nothing in $filter", Modifier.padding(16.dp)) }
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
                // B-B7: a stale response (a newer query already started)
                // must not overwrite the newer results or error.
                if (term != q) return@launch
                if (res.success && res.data != null) results = res.data.users
                else searchError = res.error?.message ?: "Couldn't search — try again" // B-B33: never a null error masquerading as "No people found"
            } catch (e: Exception) { if (term == q) searchError = e.message ?: "Couldn't search — try again" }
            finally { if (term == q) searching = false }
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
                Text("Recent", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { recents = emptyList(); saveRecents(emptyList()) }) { Text("Clear", maxLines = 1, softWrap = false) }
            }
            recents.forEach { r ->
                Row(Modifier.fillMaxWidth().clickable { q = r }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.History, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Text(r, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                    headlineContent = { Text(u.displayName ?: "", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("@${u.username ?: ""}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
fun ProfileScreen(api2: FrianzoApi2, username: String?, myId: String?, onLogout: (() -> Unit)? = null, onBack: (() -> Unit)? = null, onOpenChat: ((String, String) -> Unit)? = null, feedVm: FeedViewModel? = null, onUser: ((String) -> Unit)? = null, onDashboard: (() -> Unit)? = null, onOpenReel: ((String) -> Unit)? = null) {
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
    // v3.2: web-profile parity — tabs, user reels, share/copy, block menu.
    var activeTab by remember { mutableStateOf("all") }
    var userReels by remember { mutableStateOf<List<Reel>>(emptyList()) }
    var reelsLoading by remember { mutableStateOf(false) }
    var linkMsg by remember { mutableStateOf<String?>(null) }
    var blockedByMe by remember { mutableStateOf(false) }
    var blockedByOther by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    var showUnfollowConfirm by remember { mutableStateOf(false) }
    // v3.3: strikes banner on own profile (GET /api/users/me/strikes,
    // same endpoint the Dashboard screen already uses).
    var strikes by remember { mutableStateOf<StrikesData?>(null) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // B-B4: private profile viewed by a non-follower — posts/photos/all
    // are already gated; the Reels grid was not. (Highlights are NOT
    // gated: the web profile shows them for private accounts too —
    // ProfileClient.tsx renders the Highlights row without any
    // isPrivateView check, while its reels grid is gated.)
    val isPrivateView = profile?.isPrivate == true && username != null && !following
    fun profileUrl(u: String?) = "https://frianzo.online/u/${u ?: ""}"
    fun copyProfileLink(u: String?) {
        clipboard.setText(AnnotatedString(profileUrl(u)))
        linkMsg = "Link copied"
        scope.launch { delay(2000); linkMsg = null }
    }
    // B-B27: after a successful upload the profile is reloaded so the
    // new photo/cover shows at once (was: "reopen profile to see it").
    val avatarPicker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            try {
                val part = uriToImagePart(context, uri, "avatar_upload")
                if (api2.uploadAvatar(part).success) { try { val rp = api2.myProfile(); if (rp.success && rp.data?.user != null) profile = rp.data.user } catch (_: Exception) {}; photoMsg = "Photo updated" } else photoMsg = "Photo upload failed"
            } catch (e: Exception) { photoMsg = e.message }
        }
    }
    val coverPicker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            try {
                val part = uriToImagePart(context, uri, "cover_upload")
                if (api2.uploadCover(part).success) { try { val rp = api2.myProfile(); if (rp.success && rp.data?.user != null) profile = rp.data.user } catch (_: Exception) {}; photoMsg = "Cover updated" } else photoMsg = "Cover upload failed"
            } catch (e: Exception) { photoMsg = e.message }
        }
    }
    val pickAvatar = rememberPermissionGate(galleryPermissions(), onGranted = { avatarPicker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) })
    val pickCover = rememberPermissionGate(galleryPermissions(), onGranted = { coverPicker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) })
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
                        // B-B5: try/finally — a throwing userPosts() used
                        // to leave postsLoading true and the tab spinning
                        // forever with no error or retry.
                        postsLoading = true
                        try {
                            val pr = api2.userPosts(uname)
                            if (pr.success && pr.data != null) posts = pr.data.posts ?: emptyList()
                            else postsError = pr.error?.message
                        } finally { postsLoading = false }
                    }
                    if (res.data.user.id != null) {
                        try { val fc = api2.followCounts(res.data.user.id); if (fc.success && fc.data != null) followCounts = fc.data } catch (_: Exception) {}
                    }
                    if (uname != null) {
                        try { val hr = api2.highlights(uname); if (hr.success && hr.data != null) highlights = hr.data.highlights ?: emptyList() } catch (e: Exception) { highlightError = e.message }
                    }
                    if (uname != null) {
                        reelsLoading = true
                        try { val rr = api2.userReels(uname); if (rr.success && rr.data != null) userReels = rr.data.reels } catch (_: Exception) {}
                        reelsLoading = false
                    }
                    if (username == null) {
                        try { val sr = api2.myStrikes(); if (sr.success && sr.data != null) strikes = sr.data } catch (_: Exception) {}
                        try {
                            val sf = api2.statusFeed()
                            val own = sf.data?.feed?.firstOrNull { it.userId == myId || it.user?.id == myId }
                            ownStatusItems = own?.items ?: emptyList()
                        } catch (_: Exception) {}
                    }
                    if (res.data.user.id != null && res.data.user.id != myId) {
                        try { val bs = api2.blockStatus(res.data.user.id); if (bs.success && bs.data != null) { blockedByMe = bs.data.blockedByMe; blockedByOther = bs.data.blockedByOther } } catch (_: Exception) {}
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
                // v3.1: Facebook-style header — 200dp cover, 96dp avatar
                // overlapping the cover's bottom edge with a white ring,
                // name/bio/counts full-width below (was: 80dp avatar in a
                // side-by-side Row, counts squeezed into the narrow column).
                Box(Modifier.fillMaxWidth().height(248.dp)) {
                    if (p.coverPhotoUrl != null) AsyncImage(model = p.coverPhotoUrl, contentDescription = null, modifier = Modifier.fillMaxWidth().height(200.dp), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                    else Box(Modifier.fillMaxWidth().height(200.dp).background(FrianzoBlueTint))
                    // v3.2 web parity: "Edit cover" pill on the cover (own profile).
                    if (username == null) {
                        Button(onClick = { pickCover() }, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 56.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.55f), contentColor = Color.White), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), shape = RoundedCornerShape(28.dp)) { Text("Edit cover", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                    }
                    Box(Modifier.align(Alignment.BottomStart).padding(start = 16.dp).size(96.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                        FrianzoAvatar(p.profilePictureUrl, 88.dp)
                        // v3.2 web parity: own avatar gets a + badge (change photo);
                        // other users get a green online dot, like the web.
                        if (username == null) {
                            Box(Modifier.align(Alignment.BottomEnd).size(28.dp).clip(CircleShape).background(Color(0xFF0F172A)).border(2.dp, Color.White, CircleShape).clickable { pickAvatar() }, contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Add, contentDescription = "Change profile photo", tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                        } else if (p.isOnline) {
                            Box(Modifier.align(Alignment.BottomEnd).size(18.dp).clip(CircleShape).background(FrianzoSuccess).border(2.dp, Color.White, CircleShape))
                        }
                    }
                }
                Column(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp)) {
                        // v3.2 web parity: share + copy-link icons (own), and for
                        // others also Message + a More menu (Copy link / Block).
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                            if (username != null) {
                                Box {
                                    IconButton(onClick = { moreOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More options", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                                        DropdownMenuItem(text = { Text("Copy link") }, onClick = { moreOpen = false; copyProfileLink(p.username) })
                                        DropdownMenuItem(text = { Text(if (blockedByMe) "Unblock" else "Block", color = if (blockedByMe) MaterialTheme.colorScheme.onSurface else FrianzoBadgeRed) }, onClick = {
                                            moreOpen = false
                                            scope.launch {
                                                try {
                                                    if (blockedByMe) { if (api2.unblockUser(p.id ?: "").success) blockedByMe = false }
                                                    else if (api2.blockUser(p.id ?: "").success) { blockedByMe = true; following = false; followStatus = "none" }
                                                } catch (_: Exception) {}
                                            }
                                        })
                                    }
                                }
                                if (onOpenChat != null && !blockedByOther) {
                                    IconButton(onClick = {
                                        scope.launch {
                                            try {
                                                val r = api2.createConversation(CreateConversationRequest(p.id ?: ""))
                                                if (r.success && r.data != null) onOpenChat(r.data.id, p.displayName ?: p.username ?: "")
                                            } catch (_: Exception) {}
                                        }
                                    }) { Icon(Icons.Filled.Chat, contentDescription = "Message", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                            }
                            IconButton(onClick = {
                                // v3.3: share the short /s/ link like web
                                // (POST /api/share { type: "profile", id }),
                                // direct /u/ URL only if that call fails.
                                scope.launch {
                                    var url = profileUrl(p.username)
                                    try {
                                        val r = api2.createShareLink(mapOf("type" to "profile", "id" to (p.id ?: p.username ?: "")))
                                        val code = r.data?.code
                                        if (r.success && !code.isNullOrBlank()) url = "https://frianzo.online/s/$code"
                                    } catch (_: Exception) {}
                                    val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, url) }
                                    context.startActivity(Intent.createChooser(send, "Share profile"))
                                }
                            }) { Icon(Icons.Filled.Share, contentDescription = "Share profile", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                            IconButton(onClick = { copyProfileLink(p.username) }) { Icon(Icons.Filled.Link, contentDescription = "Copy profile link", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        if (linkMsg != null) Text(linkMsg!!, style = MaterialTheme.typography.bodySmall, color = FrianzoBlue, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.displayName ?: "", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (p.isVerified) { Spacer(Modifier.width(4.dp)); Icon(Icons.Filled.CheckCircle, contentDescription = "Verified", modifier = Modifier.size(20.dp), tint = FrianzoBlue) }
                        }
                        // v3.3: @username is tappable — copies it, like web.
                        Text("@${p.username ?: ""}", style = MaterialTheme.typography.bodySmall, color = FrianzoBlue, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable {
                            clipboard.setText(AnnotatedString("@${p.username ?: ""}"))
                            linkMsg = "Username copied"
                            scope.launch { delay(2000); linkMsg = null }
                        })
                        // v3.3: "Active now" for online other users, like web.
                        if (username != null && p.isOnline) Text("Active now", style = MaterialTheme.typography.bodySmall, color = FrianzoSuccess, maxLines = 1, softWrap = false)
                        // v3.3: "Followed by X, Y and N others" — backend already
                        // returns mutualFollowers/mutualCount; names are tappable.
                        if (username != null && (p.mutualCount ?: 0) > 0) {
                            val shown = (p.mutualFollowers ?: emptyList()).take(2)
                            val rest = (p.mutualCount ?: 0) - shown.size
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Followed by ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                                shown.forEachIndexed { idx, mf ->
                                    if (idx > 0) Text(", ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(mf.displayName ?: mf.username ?: "", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { if (mf.username != null) onUser?.invoke(mf.username!!) })
                                }
                                if (rest > 0) Text(" and $rest others", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                            }
                        }
                        p.bio?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis) }
                        // v2.9: website is a real link (was plain blue Text).
                        p.website?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = FrianzoBlue, modifier = Modifier.clickable { openWebUrl(context, it) }, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        // v3.3: web shows city + province; country is only the fallback.
                        val loc = listOfNotNull(p.city?.takeIf { it.isNotBlank() }, p.province?.takeIf { it.isNotBlank() } ?: countryDisplayName(p.country)).joinToString(", ")
                        if (loc.isNotBlank()) Text(loc, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(8.dp))
                        // v3.1: counts as a full-width row — three equal
                        // columns, bold number over grey label (was: three
                        // texts squeezed into the narrow side column).
                        // v2.9: real counts from GET /api/follows/{id}/counts
                        // (PublicProfile itself returns NO counts).
                        // v3.2 web parity: FOUR columns with divider lines —
                        // Posts / Reels / Followers / Following. Reels count is
                        // real: GET /api/reels/user/{username} (web uses it too).
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            val fc = followCounts?.followers ?: (p.followerCount ?: p.followersCount)
                            val fg = followCounts?.following ?: (p.followingCount ?: p.followingsCount)
                            @Composable fun countCol(num: String, label: String, onClick: (() -> Unit)? = null) {
                                Column(Modifier.weight(1f).then(if (onClick != null) Modifier.clickable { onClick() } else Modifier), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(num, style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                                    Text(label, style = MaterialTheme.typography.bodySmall, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            // B-B21 (backend-verified): GET /api/posts/user/{u}
                            // and GET /api/reels/user/{u} return the FULL
                            // lists (no paging, no total field), so the
                            // loaded sizes ARE the true totals.
                            countCol("${posts.size}", "Posts")
                            Box(Modifier.width(1.dp).height(32.dp).background(MaterialTheme.colorScheme.outline))
                            countCol("${userReels.size}", "Reels") { activeTab = "reels" }
                            Box(Modifier.width(1.dp).height(32.dp).background(MaterialTheme.colorScheme.outline))
                            if (fc != null) countCol("$fc", "Followers") { if (p.id != null) followList = "followers" }
                            Box(Modifier.width(1.dp).height(32.dp).background(MaterialTheme.colorScheme.outline))
                            if (fg != null) countCol("$fg", "Following") { if (p.id != null) followList = "following" }
                        }
                    }
                    // v3.3: amber strikes warning banner on own profile, like web.
                    val strikeList = strikes?.strikes ?: emptyList()
                    if (username == null && strikeList.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFFFEF3C7)).padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text("You have ${strikeList.size} strike${if (strikeList.size == 1) "" else "s"} — ${strikeList.firstOrNull()?.reason ?: "check your Dashboard."}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF92400E), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                // v3.2 web parity: own profile = Edit Profile + Dashboard
                // (web: dark Edit Profile, outlined Dashboard). Log out is
                // back in the drawer only — it no longer sits on the profile.
                if (username == null) {
                    Row(Modifier.padding(horizontal = 16.dp)) {
                        Button(onClick = { showEdit = true }, modifier = Modifier.weight(1f).height(FrianzoUi.ButtonHeight), shape = RoundedCornerShape(FrianzoUi.ButtonRadius), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A))) { Text("Edit Profile", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { onDashboard?.invoke() }, modifier = Modifier.weight(1f).height(FrianzoUi.ButtonHeight), shape = RoundedCornerShape(FrianzoUi.ButtonRadius)) { Text("Dashboard", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                    }
                    if (editError != null) Text(editError!!, style = MaterialTheme.typography.bodySmall, color = FrianzoBadgeRed, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
                if (showEdit && profile != null) {
                    val cur = profile!!
                    var edName by remember { mutableStateOf(cur.displayName ?: "") }
                    var edBio by remember { mutableStateOf(cur.bio ?: "") }
                    var edCity by remember { mutableStateOf(cur.city ?: "") }
                    var edProvince by remember { mutableStateOf(cur.province ?: "") }
                    var edCountry by remember { mutableStateOf(cur.country ?: "") }
                    var edWeb by remember { mutableStateOf(cur.website ?: "") }
                    var edSaving by remember { mutableStateOf(false) }
                    AlertDialog(
                        onDismissRequest = { showEdit = false },
                        title = { Text("Edit profile") },
                        text = {
                            Column {
                                OutlinedTextField(value = edName, onValueChange = { edName = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(value = edBio, onValueChange = { if (it.length <= 300) edBio = it }, label = { Text("Bio") }, modifier = Modifier.fillMaxWidth())
                                Text("${edBio.length}/300", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(value = edCity, onValueChange = { edCity = it }, label = { Text("City") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(value = edProvince, onValueChange = { edProvince = it }, label = { Text("Province") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(value = edCountry, onValueChange = { edCountry = it }, label = { Text("Country") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(value = edWeb, onValueChange = { edWeb = it }, label = { Text("Website") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { pickAvatar() }, shape = RoundedCornerShape(28.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Text("Change photo", maxLines = 1, softWrap = false) }
                                    OutlinedButton(onClick = { pickCover() }, shape = RoundedCornerShape(28.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Text("Change cover", maxLines = 1, softWrap = false) }
                                }
                                photoMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = FrianzoBlue) }
                            }
                        },
                        confirmButton = {
                            Button(enabled = !edSaving, onClick = {
                                // B-B26: a blank name must not blank the
                                // display name, and Save cannot double-fire.
                                if (edName.isBlank()) { editError = "Name can't be empty"; return@Button }
                                edSaving = true
                                scope.launch {
                                    try {
                                        val body = mapOf<String, Any>("displayName" to edName, "bio" to edBio, "city" to edCity, "province" to edProvince, "country" to edCountry, "website" to edWeb)
                                        val r = api2.updateMe(body)
                                        if (r.success) { profile = cur.copy(displayName = edName, bio = edBio, city = edCity, province = edProvince, country = edCountry, website = edWeb); showEdit = false; editError = null }
                                        else editError = r.error?.message ?: "Could not save profile"
                                    } catch (e: Exception) { editError = e.message }
                                    finally { edSaving = false }
                                }
                            }, modifier = Modifier.fillMaxWidth().height(FrianzoUi.ButtonHeight), shape = RoundedCornerShape(FrianzoUi.ButtonRadius), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A))) { Text(if (edSaving) "Saving..." else "Save Changes", maxLines = 1, softWrap = false) }
                        },
                        dismissButton = { TextButton(onClick = { showEdit = false }) { Text("Cancel") } },
                    )
                }
                // v3.2 web parity: others get ONE full-width Follow button
                // (Message moved to the icon row, like the web). Unfollow
                // asks first, like the web confirm dialog.
                if (showUnfollowConfirm) {
                    AlertDialog(onDismissRequest = { showUnfollowConfirm = false }, title = { Text("Unfollow @${p.username ?: ""}?") }, text = { Text("You will stop seeing their posts.") },
                        confirmButton = { Button(onClick = {
                            showUnfollowConfirm = false
                            scope.launch {
                                try {
                                    val r = api2.toggleFollow(p.id ?: "")
                                    if (r.success && r.data != null) { following = r.data.following || r.data.isFollowing; followStatus = r.data.friendStatus ?: if (following) "following" else "none" }
                                } catch (_: Exception) {}
                            }
                        }) { Text("Unfollow") } },
                        dismissButton = { TextButton(onClick = { showUnfollowConfirm = false }) { Text("Cancel") } })
                }
                if (username != null && p.id != null && p.id != myId) {
                    if (blockedByOther) {
                        Text("This user is unavailable.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    } else if (blockedByMe) {
                        OutlinedButton(onClick = {
                            scope.launch { try { if (api2.unblockUser(p.id).success) blockedByMe = false } catch (_: Exception) {} }
                        }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(FrianzoUi.ButtonHeight), shape = RoundedCornerShape(FrianzoUi.ButtonRadius)) { Text("Unblock", maxLines = 1, softWrap = false) }
                    } else {
                        Button(onClick = {
                            if (following) { showUnfollowConfirm = true; return@Button }
                            scope.launch {
                                try {
                                    val r = api2.toggleFollow(p.id)
                                    if (r.success && r.data != null) { following = r.data.following || r.data.isFollowing; followStatus = r.data.friendStatus ?: if (following) "following" else "none" }
                                } catch (_: Exception) {}
                            }
                        }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(FrianzoUi.ButtonHeight), shape = RoundedCornerShape(FrianzoUi.ButtonRadius), colors = ButtonDefaults.buttonColors(containerColor = if (following) MaterialTheme.colorScheme.surfaceVariant else Color(0xFF0F172A), contentColor = if (following) MaterialTheme.colorScheme.onSurface else Color.White)) { Text(if (following) "Following" else if (followStatus == "follow_back") "Follow Back" else "Follow", maxLines = 1, softWrap = false) }
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
                                Text(h.title ?: "", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (username == null) {
                            item {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp).clickable { showHighlightCreate = true }) {
                                    // v3.2 web parity: dashed-circle "New" button.
                                    Box(Modifier.size(60.dp).drawBehind {
                                        drawCircle(color = androidx.compose.ui.graphics.Color(0xFF94A3B8), style = Stroke(width = 4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f)))
                                    }, contentAlignment = Alignment.Center) { Icon(Icons.Filled.Add, contentDescription = "New highlight", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    Spacer(Modifier.height(4.dp))
                                    Text("New", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    highlightError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = FrianzoBadgeRed, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                    Spacer(Modifier.height(12.dp))
                }
                // v3.2 web parity: All / Reels / Photos tabs, selected tab
                // underlined in slate-900, like the web.
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    listOf("all" to "All", "reels" to "Reels", "photos" to "Photos").forEach { (v, label) ->
                        val sel = activeTab == v
                        Box(Modifier.weight(1f).clickable { activeTab = v }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                            Text(label, style = MaterialTheme.typography.bodyMedium, color = if (sel) Color(0xFF0F172A) else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal, maxLines = 1, softWrap = false)
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline))
                if (activeTab == "reels") {
                    if (isPrivateView) { /* private banner above already explains it */ }
                    else if (reelsLoading) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { DotsLoading() }
                    else if (userReels.isEmpty()) Text("No reels yet.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
                } else if (activeTab == "photos") {
                    if (posts.filter { !it.imageUrl.isNullOrBlank() }.isEmpty()) Text("No photos yet.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
                } else {
                    if (postsLoading) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { DotsLoading() }
                    if (postsError != null) Text(postsError!!, style = MaterialTheme.typography.bodySmall, color = FrianzoBadgeRed, modifier = Modifier.padding(horizontal = 16.dp))
                    if (!postsLoading && postsError == null && posts.isEmpty()) Text("No posts yet", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
                }
            } ?: run {
                if (error != null) Text(error!!, Modifier.padding(16.dp)) else Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { DotsLoading() }
            }
        }
        // v3.2: Reels tab = 3-column grid of the user's real reels;
        // Photos tab = 3-column grid of posts with photos; All = post cards.
        if (activeTab == "reels") {
            // B-B4: same private gate as the Photos/All tabs.
            items(if (isPrivateView) emptyList() else userReels.chunked(3), key = { row -> row.firstOrNull()?.id ?: "empty" }) { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    row.forEach { reel ->
                        Box(Modifier.weight(1f).aspectRatio(9f / 16f).clip(RoundedCornerShape(4.dp)).background(Color(0xFF1E293B)).clickable { onOpenReel?.invoke(reel.id) }) {
                            if (reel.thumbnailUrl != null) AsyncImage(model = reel.thumbnailUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Reel", tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp))
                            reel.caption?.takeIf { it.isNotBlank() }?.let { Text(it, color = Color.White, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)) }
                            // R24: view count on the tile, like the feed rail.
                            Row(Modifier.align(Alignment.BottomEnd).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Visibility, contentDescription = "Views", tint = Color.White, modifier = Modifier.size(12.dp))
                                Spacer(Modifier.width(2.dp))
                                Text(reelCount(reel.viewCount), color = Color.White, fontSize = 11.sp, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        } else if (activeTab == "photos") {
            val photoPosts = if (profile?.isPrivate == true && username != null && !following) emptyList() else posts.filter { !it.imageUrl.isNullOrBlank() }
            items(photoPosts.chunked(3), key = { row -> row.firstOrNull()?.id ?: "empty" }) { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    row.forEach { post ->
                        Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(4.dp)).clickable { if (feedVm != null) detailPostId = post.id }) {
                            AsyncImage(model = post.imageUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        // v3.3: All tab is pinned-first, then newest — like web, client-side only.
        val allPosts = posts.sortedWith(compareByDescending<Post> { it.isPinned }.thenByDescending { it.createdAt ?: "" })
        items(if (activeTab != "all") emptyList() else if (profile?.isPrivate == true && username != null && !following) emptyList() else allPosts, key = { it.id }) { post ->
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clickable { if (feedVm != null) detailPostId = post.id }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
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
    // B-B3: per-item video error (reset when the item changes).
    var videoError by remember(item?.id) { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when {
                item == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Empty highlight", color = Color.White) }
                item.mediaType == "video" -> {
                    // B-B3: a video item with no media URL used to call
                    // Uri.parse(null) and crash; a video that fails to
                    // load now shows an error instead of a black screen.
                    if (item.mediaUrl.isNullOrBlank()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("This video can't be played.", color = Color.White, fontSize = 15.sp)
                                Spacer(Modifier.height(8.dp))
                                Text("Close this highlight and try again.", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                            }
                        }
                    } else {
                        key(item.id) {
                            AndroidView(factory = { ctx -> VideoView(ctx).apply {
                                setVideoURI(Uri.parse(item.mediaUrl))
                                setOnPreparedListener { mp -> mp.isLooping = true; mp.start() }
                                setOnErrorListener { _, _, _ -> videoError = "This video can't be played."; true }
                            } }, modifier = Modifier.fillMaxSize(), onRelease = { try { it.stopPlayback() } catch (_: Exception) {} })
                        }
                        if (videoError != null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(videoError!!, color = Color.White, fontSize = 15.sp) }
                    }
                }
                else -> AsyncImage(model = item.mediaUrl, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(highlight.title ?: "Highlight", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${idx + 1}/${highlight.items.size}", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 1, softWrap = false)
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White) }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)) {
                notice?.let { Text(it, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = idx > 0, onClick = { idx-- }, colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.2f), contentColor = Color.White), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Text("Back", maxLines = 1, softWrap = false) }
                    Button(enabled = idx < highlight.items.size - 1, onClick = { idx++ }, colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.2f), contentColor = Color.White), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Text("Next", maxLines = 1, softWrap = false) }
                    if (isOwn) {
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = { showDelete = true }, colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White), border = androidx.compose.foundation.BorderStroke(1.dp, Color.White), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Text("Delete highlight", maxLines = 1, softWrap = false) }
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
                Text("Select stories", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                if (statusItems.isEmpty()) Text("No active stories. Post a story first.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                    items(statusItems, key = { it.id }) { st ->
                        Row(Modifier.fillMaxWidth().clickable { selected = if (st.id in selected) selected - st.id else selected + st.id }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = st.id in selected, onCheckedChange = { checked -> selected = if (checked) selected + st.id else selected - st.id })
                            if (!st.mediaUrl.isNullOrBlank()) AsyncImage(model = st.thumbnailUrl ?: st.mediaUrl, contentDescription = null, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                            Spacer(Modifier.width(8.dp))
                            Text(st.textContent?.takeIf { it.isNotBlank() } ?: if (st.mediaType == "video") "Video story" else "Photo story", fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            }) { Text(if (busy) "Creating..." else "Create (${selected.size} selected)", maxLines = 1, softWrap = false) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

// ---------- FOLLOWERS / FOLLOWING LIST (GET /api/follows/{id}/{direction}) ----------
@Composable
private fun FollowListScreen(api2: FrianzoApi2, userId: String, direction: String, onUser: (String) -> Unit, onClose: () -> Unit) {
    var users by remember { mutableStateOf<List<FollowListUser>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    // v3.4: server pagination (?page=&limit=20) + client-side search.
    var page by remember { mutableStateOf(1) }
    var pages by remember { mutableStateOf(1) }
    var total by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var q by rememberSaveable { mutableStateOf("") }
    // B-B28: all pages combined, for searching (the backend has no
    // search param on this endpoint, so extra pages are fetched on demand).
    var allUsers by remember { mutableStateOf<List<FollowListUser>?>(null) }
    val scope = rememberCoroutineScope()
    fun load(p: Int) {
        loading = true
        scope.launch {
            try {
                val res = api2.followUsers(userId, direction, p, 20)
                if (res.success && res.data != null) {
                    users = res.data.users ?: emptyList()
                    page = res.data.pagination?.page ?: p
                    pages = (res.data.pagination?.pages ?: 1).coerceAtLeast(1)
                    total = res.data.pagination?.total ?: users.size
                } else error = res.error?.message
            } catch (e: Exception) { error = e.message }
            loading = false
        }
    }
    LaunchedEffect(userId, direction) { load(1) }
    LaunchedEffect(q, pages) {
        if (q.isBlank() || pages <= 1) { allUsers = null; return@LaunchedEffect }
        // Fetch every page (1..pages), not just the current one plus the
        // rest — the loaded page alone would miss page 1 when paging.
        val combined = mutableListOf<FollowListUser>()
        for (p in 1..pages) {
            try {
                val res = api2.followUsers(userId, direction, p, 20)
                if (res.success && res.data?.users != null) combined += res.data.users!!
            } catch (_: Exception) { break }
        }
        allUsers = combined
    }
    val shown = if (q.isBlank()) users else (allUsers ?: users).filter {
        (it.displayName ?: "").contains(q, ignoreCase = true) || (it.username ?: "").contains(q, ignoreCase = true)
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("Back", maxLines = 1, softWrap = false) }
            Text(if (direction == "followers") "Followers" else "Following", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        OutlinedTextField(value = q, onValueChange = { q = it }, label = { Text("Search") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true, shape = RoundedCornerShape(28.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(shown, key = { it.id ?: it.username ?: "" }) { u ->
                ListItem(
                    headlineContent = { Text(u.displayName ?: u.username ?: "", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("@${u.username ?: ""}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { FrianzoAvatar(u.profilePictureUrl, 48.dp) },
                    modifier = Modifier.clickable { if (u.username != null) onUser(u.username!!) },
                )
                HorizontalDivider()
            }
            if (error != null) item { Text(error!!, Modifier.padding(16.dp)) }
            if (loading) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { DotsLoading() } }
            if (shown.isEmpty() && error == null && !loading) item { Text(if (users.isEmpty()) "Nobody here yet" else "No matches", Modifier.padding(16.dp)) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(enabled = page > 1 && !loading, onClick = { load(page - 1) }) { Text("Prev", maxLines = 1, softWrap = false) }
            Text("Page $page of $pages" + if (total > 0) " ($total)" else "", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
            TextButton(enabled = page < pages && !loading, onClick = { load(page + 1) }) { Text("Next", maxLines = 1, softWrap = false) }
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
