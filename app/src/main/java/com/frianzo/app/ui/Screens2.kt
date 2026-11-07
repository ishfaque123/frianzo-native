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
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.frianzo.app.data.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.saveable.rememberSaveable
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okio.BufferedSink
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import kotlin.math.roundToInt

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
fun ReelsScreen(api2: FrianzoApi2, myId: String? = null, session: ReelsSession, onAuthor: (String) -> Unit = {}, onOpenChat: (String, String) -> Unit = { _, _ -> }, focusReelId: String? = null, onFocusConsumed: () -> Unit = {}, uploadUri: android.net.Uri? = null, onUploadUriConsumed: () -> Unit = {}, onEditorOpenChange: (Boolean) -> Unit = {}, onTag: (String) -> Unit = {}) {
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
    reelComment?.let { reel -> ReelCommentsDialog(api2, reel, myId, myAvatar = myAvatar, initialTotal = reelCommentCounts[reel.id] ?: reel.commentCount, onClose = { reelComment = null }, onCommentAdded = { reelCommentCounts = reelCommentCounts + (reel.id to ((reelCommentCounts[reel.id] ?: reel.commentCount) + 1)) }, onCommentDeleted = { d -> reelCommentCounts = reelCommentCounts + (reel.id to ((reelCommentCounts[reel.id] ?: reel.commentCount) - d).coerceAtLeast(0)) }) }
    // v4.8: the camera / upload / saved overlays moved to the end of this
    // function — emitted before the main Column they were drawn UNDER its
    // opaque black background and invisible (the + button "did nothing").
    LaunchedEffect(uploadUri) { if (uploadUri != null) showUpload = true }
    // v5.11: pre-warm the camera (owner: no spinner, TikTok-instant).
    // ProcessCameraProvider.getInstance() is a singleton — calling it
    // here starts CameraX initialisation in the background while the
    // user is on the feed, so when + is tapped the provider is already
    // ready and the preview binds at once. This only initialises the
    // provider; the camera hardware starts when the camera screen binds
    // (no battery cost while just watching reels).
    LaunchedEffect(Unit) {
        try { androidx.camera.lifecycle.ProcessCameraProvider.getInstance(context) } catch (_: Exception) {}
    }
    // v5.1 (web-like reel editor): the editor + camera are full-screen
    // (web: no nav bar on /reels/new) — tell MainActivity when either
    // is open so the top bar hides; reset on unmount.
    LaunchedEffect(showUpload, showCamera) { onEditorOpenChange(showUpload || showCamera) }
    DisposableEffect(Unit) { onDispose { onEditorOpenChange(false) } }
    // v5.1 (web handleUpload): the daily limit is checked at the "+"
    // tap, before the camera opens — not inside the editor. A failed
    // status fetch opens the camera anyway (fail open; the server still
    // enforces the limit at Post) — the web blocks on fetch failure,
    // which would lock out reel creation on any status blip.
    fun onCreateReelTap() {
        scope.launch {
            val remaining = try { api2.myReelStatus().data?.remaining } catch (_: Exception) { null }
            if (remaining != null && remaining <= 0) {
                notice = "You've reached your reel limit for the last 24 hours. Please try again later."
                scope.launch { delay(3000); notice = null }
            } else {
                showCamera = true
            }
        }
    }
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
                    // BUG (owner, 2026-11-07): the reel kept PLAYING (video +
                    // audio) underneath the camera/editor, so a doll video
                    // appeared in the camera by itself and kept playing while
                    // recording. isActive only meant "visible page" — it never
                    // knew the camera/editor was open on top. Now the reel
                    // pauses whenever the camera or the editor is open, and
                    // resumes when they close.
                    isActive = page == listState.firstVisibleItemIndex && !showCamera && !showUpload,
                    muted = session.isMuted(reel.id, page == 0),
                    onToggleMute = { currentReelId?.let { session.updateMuted(it, !currentMuted) } },
                    dataSaver = dataSaver,
                    onTag = onTag,
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
            Box(Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 17.dp).size(40.dp).clip(CircleShape).background(Color(0xFF333333)).noRippleClickable { onCreateReelTap() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Add, contentDescription = "Create reel", tint = Color.White, modifier = Modifier.size(12.dp))
            }
        }
        }
    }

    // v4.8 (owner): the + button did nothing — these three overlays were
    // emitted BEFORE the main Column, so the opaque black full-size Column
    // drew on top of them and hid them completely. Emitted after the Column
    // they draw on top, so the upload screen (and camera / saved screens)
    // open. The tap itself always worked (showUpload = true fired); the
    // screen was just invisible underneath.
    if (showCamera) {
        ReelCameraScreen(
            onVideoRecorded = { uri, filter, speed -> showCamera = false; camVideoUri = uri; pendingCamFilter = filter; pendingCamSpeed = speed; showUpload = true },
            onPhotoCaptured = { uri -> showCamera = false; pendingCoverUri = uri; showUpload = true },
            onClose = { showCamera = false },
        )
    }
    if (showUpload) { ReelUploadScreen(api2, initialVideoUri = camVideoUri ?: uploadUri, initialCoverUri = pendingCoverUri, initialFilter = pendingCamFilter, initialSpeed = pendingCamSpeed, onOpenCamera = { showUpload = false; showCamera = true }, onDone = { newReelId -> showUpload = false; onUploadUriConsumed(); camVideoUri = null; pendingCoverUri = null; pendingCamFilter = null; pendingCamSpeed = null; if (newReelId != null) { scope.launch { try { val r = api2.reelById(newReelId); val nr = r.data?.reel; if (r.success && nr != null) { reels = listOf(nr) + reels.filter { it.id != nr.id }; listState.scrollToItem(0) } else { reels = emptyList(); offset = 0; hasMore = true; load() } } catch (_: Exception) { reels = emptyList(); offset = 0; hasMore = true; load() } } } else { reels = emptyList(); offset = 0; hasMore = true; load() } }, onClose = { showUpload = false; onUploadUriConsumed(); camVideoUri = null; pendingCoverUri = null; pendingCamFilter = null; pendingCamSpeed = null }) }
    if (showSaved) { SavedReelsScreen(api2, onClose = { showSaved = false }, onOpenReel = { id -> showSaved = false; scope.launch { try { val r = api2.reelById(id); val nr = r.data?.reel; if (r.success && nr != null) { reels = listOf(nr) + reels.filter { it.id != nr.id }; listState.scrollToItem(0) } } catch (_: Exception) {} } }) }
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
    var reportBusy by remember { mutableStateOf(false) }
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
    // v5.7: getVideoWidth/Height throw IllegalStateException on a
    // dead/errored player (Crashlytics ReelPage crash, 4x on 2026-11-07
    // across 4.9/5.1/5.4/5.6 — the app closed on camera tap). Guard them
    // like currentPosition already is; a dead player falls back to 9:16.
    val videoPw = try { player?.videoWidth ?: 0 } catch (_: Exception) { 0 }
    val videoPh = try { player?.videoHeight ?: 0 } catch (_: Exception) { 0 }
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
            } catch (e: Exception) {
                if (Outbox.isNetworkError(e)) {
                    // v5.12: offline — keep the optimistic like and queue
                    // the toggle; the flush replays it when back online.
                    Outbox.enqueueJson(context, "LIKE_REEL", reel.id, mapOf("reaction" to "like"))
                    showMsg("Waiting for connection…")
                } else { liked = prevLiked; likes = prevLikes; onLikeChange(reel.id, prevLiked, prevLikes); showMsg("Couldn't like — try again") }
            }
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
            } catch (e: Exception) {
                if (Outbox.isNetworkError(e)) {
                    // v5.12: offline — keep the optimistic unlike and queue
                    // the toggle; the flush replays it when back online.
                    Outbox.enqueueJson(context, "LIKE_REEL", reel.id, mapOf("reaction" to "like"))
                    showMsg("Waiting for connection…")
                } else { liked = prevLiked; likes = prevLikes; onLikeChange(reel.id, prevLiked, prevLikes); showMsg("Couldn't unlike — try again") }
            }
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
                        // v5.5 (owner, 2026-11-07): hide this reel's surface
                        // when it is not the active page. A paused VideoView
                        // keeps showing its last frame, and its SurfaceView
                        // sits above the camera's preview — so the frozen
                        // frame flashed in the camera for ~1s after tapping
                        // + until the live feed started. INVISIBLE hides the
                        // frame; the player stays prepared and resumes when
                        // this reel is active again.
                        view.visibility = if (isActive) android.view.View.VISIBLE else android.view.View.INVISIBLE
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
        // v5.15 (owner: web menus A-to-Z): the More sheet header is
        // 14sp semibold + an 11sp subtitle with a divider below it.
        // Rows are true 48dp with 20dp icons; Interested uses a
        // checkmark (web); exactly ONE destructive row — Delete for
        // the owner, Report otherwise. "Saved reels" and "Don't
        // recommend this category" are gone (the web has neither).
        if (moreOpen) {
            ModalBottomSheet(
                onDismissRequest = { moreOpen = false },
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("More options", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false)
                        Text("Reel actions", fontSize = 11.sp, color = FrianzoTextSecondary, maxLines = 1, softWrap = false)
                    }
                    HorizontalDivider(color = FrianzoBorder)
                    Spacer(Modifier.height(4.dp))
                    if (!reel.category.isNullOrBlank()) MoreSheetRow(Icons.Filled.Check, "Interested") {
                        moreOpen = false
                        scope.launch {
                            try {
                                val r = api2.notInterested(mapOf("type" to "interested_category", "value" to (reel.category ?: "")))
                                if (r.success) showMsg("We'll show you more like this") else showMsg("Couldn't save — try again")
                            } catch (_: Exception) { showMsg("Couldn't save — try again") }
                        }
                    }
                    MoreSheetRow(Icons.Filled.Block, "Not interested") {
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
                    if (reel.author?.id == myId) MoreSheetRow(Icons.Filled.Delete, "Delete reel", red = true) { moreOpen = false; showDeleteReel = true }
                    else MoreSheetRow(Icons.Filled.Flag, "Report reel", red = true) { moreOpen = false; reportOpen = true }
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
                // v4.8 (owner, Facebook rule): the pill STAYS after a
                // follow and shows the new state in place — Follow ->
                // Following, Follow Back -> Friend. It hides only for
                // your own reel, while the status is still loading
                // (null), or for "self". (The web hides the pill once
                // following; the owner explicitly ordered the Facebook
                // behaviour instead.) Tapping toggles: Following ->
                // Follow, Friend -> Follow Back. The status fans out to
                // every reel by this author via onFollowChange (hoisted
                // in ReelsScreen).
                val effectiveFollow = followOverride ?: authorFollow
                if (authorId != null && authorId != myId && effectiveFollow != null && effectiveFollow != "self") {
                    Spacer(Modifier.width(8.dp))
                    val label = when (effectiveFollow) {
                        "follow_back", "follower" -> "Follow Back"
                        "following" -> "Following"
                        "friends" -> "Friend"
                        else -> "Follow"
                    }
                    // v4.0 (audit #3 + web code): black fill, 1dp white
                    // border, white text — BESIDE the name on the SAME
                    // row (owner rule, final word: never below the
                    // name, never on a second line; the name truncates
                    // so the pill never wraps).
                    Box(Modifier.height(34.dp).clip(RoundedCornerShape(28.dp)).background(Color.Black).border(1.dp, Color.White, RoundedCornerShape(28.dp)).noRippleClickable {
                        val prev = effectiveFollow
                        // Optimistic new state, from prev + the toggle
                        // direction: following from "none" -> "following",
                        // from "follow_back"/"follower" -> "friends"
                        // (mutual); unfollowing from "following" -> "none",
                        // from "friends" -> "follow_back" (they still
                        // follow me). The backend toggle returns only
                        // { following: Boolean } — no friendStatus — so
                        // the same table reconciles the server response.
                        val optimistic = if (prev == "following" || prev == "friends") {
                            if (prev == "friends") "follow_back" else "none"
                        } else {
                            if (prev == "follow_back" || prev == "follower") "friends" else "following"
                        }
                        authorFollow = optimistic
                        onFollowChange(authorId, optimistic)
                        scope.launch {
                            try {
                                val r = api2.toggleFollow(authorId)
                                if (r.success && r.data != null) {
                                    val st = r.data.friendStatus ?: if (r.data.following || r.data.isFollowing) {
                                        if (prev == "follow_back" || prev == "follower") "friends" else "following"
                                    } else {
                                        if (prev == "friends") "follow_back" else "none"
                                    }
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
                    Button(
                        onClick = {
                            if (reportBusy) return@Button
                            reportBusy = true
                            scope.launch {
                                try {
                                    val body = mapOf("reason" to reportReason) + if (reportDetails.isNotBlank()) mapOf("details" to reportDetails) else emptyMap()
                                    if (api2.reportReel(reel.id, body).success) { reportDetails = ""; reportOpen = false; showMsg("Reported. Thanks for letting us know.") } else showMsg("Couldn't report — try again")
                                } catch (_: Exception) { showMsg("Couldn't report — try again") }
                                finally { reportBusy = false }
                            }
                        },
                        enabled = !reportBusy,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface),
                        shape = RoundedCornerShape(28.dp),
                    ) { Text(if (reportBusy) "Reporting…" else "Report", maxLines = 1, softWrap = false) }
                },
                dismissButton = { TextButton(onClick = { reportOpen = false }) { Text("Cancel") } },
            )
        }
        if (showDeleteReel) {
            FrianzoConfirmDialog(
                title = "Confirm action",
                message = "Delete this reel? This can't be undone.",
                confirmLabel = "Delete",
                showLogo = true,
                onConfirm = {
                    showDeleteReel = false
                    scope.launch { try { if (api2.deleteReel(reel.id).success) onDeleted() else showMsg("Couldn't delete — try again") } catch (_: Exception) { showMsg("Couldn't delete — try again") } }
                },
                onDismiss = { showDeleteReel = false },
            )
        }
    }
}

// v4.0: one row of the More bottom sheet — 18dp icon, 15sp label,
// ~48dp pitch, like the web sheet (audit #1).
// v5.15: one row of the More bottom sheet — TRUE 48dp, 20dp icon,
// 15sp label, rounded corners, clickable WITH ripple (web sheet).
@Composable
private fun MoreSheetRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, red: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(8.dp)).clickable { onClick() }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = if (red) FrianzoBadgeRed else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 15.sp, color = if (red) FrianzoBadgeRed else MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

// v5.15: chat action-sheet row — 40dp grey circle icon + 15sp
// font-medium label. Delete gets a light-red circle + red icon.
@Composable
private fun ChatActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, red: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(8.dp)).clickable { onClick() }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(if (red) FrianzoBadgeRed.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = if (red) FrianzoBadgeRed else Color(0xFF334155), modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (red) FrianzoBadgeRed else MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

// v5.15: delete-sheet row — title 15sp + sublabel 12sp secondary.
@Composable
private fun ChatDeleteRow(title: String, sublabel: String, red: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(8.dp)).clickable { onClick() }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(if (red) FrianzoBadgeRed.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Delete, contentDescription = null, tint = if (red) FrianzoBadgeRed else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (red) FrianzoBadgeRed else MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false)
            Text(sublabel, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
    }
}

// v5.15: full-width grey Cancel pill (rounded-full, surfaceVariant).
@Composable
private fun GreyCancelPill(label: String = "Cancel", modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
    ) { Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false) }
}

// v5.15: centred confirm modal (web ReelNotice style) — Cancel is a
// plain outlined button, the confirm is slate-900 filled, or RED filled
// when confirmRed. The logo is the blue "F" mark (same letter mark as
// the share tiles — no logo asset is bundled in the app).
@Composable
private fun FrianzoConfirmDialog(title: String, message: String, confirmLabel: String, confirmRed: Boolean = false, showLogo: Boolean = false, confirmEnabled: Boolean = true, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (showLogo) {
                    Box(Modifier.size(48.dp).clip(CircleShape).background(FrianzoBlue), contentAlignment = Alignment.Center) {
                        Text("F", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Spacer(Modifier.height(16.dp))
                }
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(message, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(28.dp)) { Text("Cancel", maxLines = 1, softWrap = false) }
                    Button(
                        onClick = onConfirm,
                        enabled = confirmEnabled,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (confirmRed) FrianzoBadgeRed else MaterialTheme.colorScheme.onSurface,
                            contentColor = if (confirmRed) Color.White else MaterialTheme.colorScheme.inverseOnSurface,
                        ),
                    ) { Text(confirmLabel, maxLines = 1, softWrap = false) }
                }
            }
        }
    }
}

// v5.15: Block confirm bottom sheet, shared by the chat header menu
// and the profile menu. onBlock runs the existing block call.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BlockConfirmSheet(name: String, onBlock: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Block $name?", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text("They won't be able to message you anymore. You can unblock them anytime.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { onBlock(); onDismiss() },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(containerColor = FrianzoBadgeRed, contentColor = Color.White),
            ) { Text("Block", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false) }
            Spacer(Modifier.height(12.dp))
            GreyCancelPill(onClick = onDismiss)
        }
    }
}

// v5.15: chat wallpaper swatches (local-only; persisted per
// conversation in SharedPreferences "frianzo_chat").
private val ChatWallpapers = listOf(
    "Default" to Color(0xFFF8FAFC),
    "Beige" to Color(0xFFE9DFD0),
    "Mint" to Color(0xFFD8E8D2),
    "Sky" to Color(0xFFD5E2F2),
    "Rose" to Color(0xFFF2DDE4),
    "Dark" to Color(0xFF232A33),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatWallpaperSheet(selected: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Chat wallpaper", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center, maxLines = 1, softWrap = false)
            Spacer(Modifier.height(16.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxWidth().height(176.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                userScrollEnabled = false,
            ) {
                items(ChatWallpapers) { (name, color) ->
                    val isSel = name == selected
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onPick(name) }) {
                        Box(
                            Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).background(color)
                                .border(if (isSel) 3.dp else 1.dp, if (isSel) Color(0xFF22C55E) else FrianzoBorder, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isSel) Icon(Icons.Filled.Check, contentDescription = null, tint = Color(0xFF22C55E), modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(name, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            GreyCancelPill(onClick = onDismiss)
        }
    }
}

// ---------- REEL SHARE (v3.5, R11; v5.15 owner screenshots): clean
// sheet, NO URL text — only a full-width Copy link button — then the
// 7 LETTER tiles in brand colours (the web tiles are letters, not
// brand logos). Frianzo DM, WhatsApp, WA Status, Messenger, Facebook,
// FB Story, Text.
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
    ModalBottomSheet(
        onDismissRequest = onClose,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("Share", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(12.dp))
            // v5.15 (owner): NO URL text in the sheet — only a full-width
            // Copy link button. The link is still copied (existing
            // logic) + a "Link copied!" toast.
            Button(
                onClick = { clipboard.setText(AnnotatedString(link)); copied = true; record("copy"); Toast.makeText(context, "Link copied!", Toast.LENGTH_SHORT).show(); scope.launch { delay(2000); copied = false } },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface),
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text(if (copied) "Copied!" else "Copy link", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false) }
            Spacer(Modifier.height(12.dp))
            // v5.15 (owner): letter tiles in brand colours (the web
            // tiles are letters, not brand logos — screenshot 1).
            // 4-column grid, 56dp circles, 11sp labels.
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxWidth().height(168.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                userScrollEnabled = false,
            ) {
                // B-D5: the DM tile is hidden when the reel has no author
                // id — it used to sit there and do nothing when tapped.
                if (reel.author?.id != null) {
                    item {
                        ReelShareTile("Frianzo DM", Color(0xFF0F172A), "F") {
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
                    }
                }
                item { ReelShareTile("WhatsApp", Color(0xFF25D366), "W") { openUrl("https://wa.me/?text=" + android.net.Uri.encode(link), "whatsapp") } }
                item { ReelShareTile("WA Status", Color(0xFF25D366), "W") { openUrl("whatsapp://send?text=" + android.net.Uri.encode(link), "whatsapp_status") } }
                item { ReelShareTile("Messenger", Color(0xFF0084FF), "M") { openUrl("fb-messenger://share?link=" + android.net.Uri.encode(link), "messenger") } }
                item { ReelShareTile("Facebook", Color(0xFF1877F2), "f") { openUrl("https://www.facebook.com/sharer/sharer.php?u=" + android.net.Uri.encode(link), "facebook") } }
                item { ReelShareTile("FB Story", Color(0xFF1877F2), "f") { openUrl("fb://story/camera?attachment_url=" + android.net.Uri.encode(link), "facebook_story") } }
                item { ReelShareTile("Text message", Color(0xFF34C759), "S") { openUrl("sms:?body=" + android.net.Uri.encode(link), "sms") } }
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
            // v5.12: skeleton rows on first load; error already has Retry.
            if (loading) { Column { repeat(4) { SkeletonRow() } } }
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
private fun ReelCommentsDialog(api2: FrianzoApi2, reel: Reel, myId: String? = null, myAvatar: String? = null, initialTotal: Int = -1, onClose: () -> Unit, onCommentAdded: () -> Unit = {}, onCommentDeleted: (Int) -> Unit = {}) {
    val context = LocalContext.current
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
    // v4.8 comments fixes: sending state (U-B6), send error shown near the
    // input (U-B9), delete confirmation feedback (U-B17), just-sent scroll
    // target (C-A5), one-popup-at-a-time id (U-B15).
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    var deleteFeedback by remember { mutableStateOf<String?>(null) }
    var justSent by remember { mutableStateOf(false) }
    var openPopupId by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val inputFocus = remember { FocusRequester() }
    var expandedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val scope = rememberCoroutineScope()
    fun load(cursor: String? = null) {
        error = null
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
    // C-A2 (web rule): focus the input 300ms after the sheet opens.
    LaunchedEffect(Unit) { delay(300); inputFocus.requestFocus() }
    // C-A5 (web rule): after a send, scroll to the bottom of the loaded
    // list so the new comment is visible. (If the reel has more than one
    // page of comments, the new comment may sit on a later page — the web
    // appends it locally; here the list reloads from page 1.)
    LaunchedEffect(comments) {
        if (justSent) {
            justSent = false
            val last = listState.layoutInfo.totalItemsCount - 1
            if (last >= 0) listState.animateScrollToItem(last)
        }
    }
    // S-4 (web rule): load the next page automatically when the user
    // scrolls within 3 items of the end. The "Load more" button stays as
    // a fallback below.
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastIdx ->
                val totalItems = listState.layoutInfo.totalItemsCount
                if (hasMore && !loadingMore && !loadingFirst && lastIdx != null && totalItems > 0 && lastIdx >= totalItems - 3) load(nextCursor)
            }
    }
    ModalBottomSheet(onDismissRequest = onClose, shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)) {
        // Same 0.85f sheet height as the post comments sheet: one shared UI.
        // v4.8 (owner): the comment input went behind the keyboard —
        // nothing in this sheet handled IME insets (no imePadding
        // anywhere in the app, no windowSoftInputMode in the
        // manifest, activity is not edge-to-edge). imePadding lifts
        // the input above the keyboard while typing.
        // U-B18: cap the sheet content at 576dp wide on wide screens
        // (web max-w-xl), centred; phones are unchanged (full width).
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth().widthIn(max = 576.dp).fillMaxHeight(0.85f).imePadding().padding(horizontal = 16.dp)) {
            // v4.0 (audit #2): web header is "Comments" with the
            // count ("N comments" / "Join the conversation") below
            // it as a subtitle — not the count alone.
            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Comments", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                    Text(if (total > 0) "$total comments" else "Join the conversation", fontSize = 11.sp, color = Color(0xFF94A3B8), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd)) { Icon(Icons.Filled.Close, contentDescription = "Close comments", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            HorizontalDivider(color = FrianzoBorder)
            if (deleteFeedback != null) Text(deleteFeedback!!, fontSize = 12.sp, color = Color(0xFF16A34A), modifier = Modifier.fillMaxWidth().padding(top = 8.dp), textAlign = TextAlign.Center)
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp)) {
                if (loadingFirst) { items(4) { ReelCommentSkeletonRow() } }
                else if (comments.isEmpty() && error == null) item {
                    // C-A6 (web rule): icon + bold title + grey subtitle.
                    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(48.dp).clip(CircleShape).background(Color(0xFFF1F5F9)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null, modifier = Modifier.size(22.dp), tint = Color(0xFF64748B))
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("No comments yet", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1E293B))
                        Spacer(Modifier.height(4.dp))
                        Text("Be the first to start the conversation.", fontSize = 12.sp, color = Color(0xFF94A3B8))
                    }
                }
                items(comments, key = { it.id }) { c ->
                    ReelCommentRow(api2, c, reel, myId, popupOpenId = openPopupId, onPopupOpened = { openPopupId = it }, onReply = { replyTo = it }, onChanged = { load() }, onDeleted = { val d = 1 + c.replies.size; total = (total - d).coerceAtLeast(0); onCommentDeleted(d); deleteFeedback = "Comment deleted."; scope.launch { delay(2000); deleteFeedback = null } })
                    val replies = c.replies
                    if (replies.isNotEmpty()) {
                        val isExpanded = c.id in expandedIds
                        // U-B4 (web rule): thread line — 20dp indent + a 2dp
                        // light left border, so a reply is visibly attached
                        // to its parent (was: 40dp indent, no line).
                        Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 4.dp).height(IntrinsicSize.Min)) {
                            Box(Modifier.width(2.dp).fillMaxHeight().background(Color(0xFFE2E8F0).copy(alpha = 0.6f)))
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            if (!isExpanded) {
                                TextButton(onClick = { expandedIds = expandedIds + c.id }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) { Text("View ${replies.size} ${if (replies.size == 1) "reply" else "replies"}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false) }
                            } else {
                                replies.forEach { r ->
                                    ReelCommentRow(api2, r, reel, myId, isReply = true, parentAuthor = c.author, popupOpenId = openPopupId, onPopupOpened = { openPopupId = it }, onReply = { replyTo = it }, onChanged = { load() }, onDeleted = { total = (total - 1).coerceAtLeast(0); onCommentDeleted(1); deleteFeedback = "Comment deleted."; scope.launch { delay(2000); deleteFeedback = null } })
                                }
                                TextButton(onClick = { expandedIds = expandedIds - c.id }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) { Text("Hide replies", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false) }
                            }
                            }
                        }
                    }
                }
                if (hasMore) {
                    if (loadingMore) { items(2) { ReelCommentSkeletonRow() } }
                    else item {
                        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            OutlinedButton(onClick = { load(nextCursor) }, modifier = Modifier.height(FrianzoUi.ButtonHeight), shape = RoundedCornerShape(28.dp)) { Text("Load more comments", maxLines = 1, softWrap = false) }
                        }
                    }
                }
                if (error != null) item {
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(error!!, fontSize = 13.sp, color = Color.Red, modifier = Modifier.weight(1f))
                        TextButton(onClick = { load() }) { Text("Retry", fontSize = 13.sp, color = FrianzoBlue) }
                    }
                }
            }
            if (replyTo != null) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFFF8FAFC)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = androidx.compose.ui.text.buildAnnotatedString {
                            append("Replying to ")
                            withStyle(androidx.compose.ui.text.SpanStyle(color = FrianzoBlue, fontWeight = FontWeight.SemiBold)) { append("@${replyTo!!.author?.username ?: "User"}") }
                        },
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f)
                    )
                    // v3.9 (live-web audit #2): web uses a "Cancel" TEXT button, not an x.
                    TextButton(onClick = { replyTo = null; draft = "" }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Cancel", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Spacer(Modifier.height(8.dp))
            }
            // U-B8: divider above the input so it does not blend into the list.
            HorizontalDivider(color = FrianzoBorder)
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                FrianzoAvatar(myAvatar, 32.dp)
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = draft,
                    onValueChange = { if (it.length <= 500) draft = it },
                    placeholder = { Text(if (replyTo != null) "Write a reply..." else "Write a comment...", fontSize = 14.sp) },
                    modifier = Modifier.weight(1f).heightIn(min = 40.dp).focusRequester(inputFocus),
                    maxLines = 4,
                    singleLine = false,
                    textStyle = TextStyle(fontSize = 14.sp),
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color(0xFFF8FAFC), unfocusedContainerColor = Color(0xFFF8FAFC), focusedBorderColor = FrianzoBorder, unfocusedBorderColor = FrianzoBorder),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = draft.isNotBlank() && !sending,
                    modifier = Modifier.height(FrianzoUi.ButtonHeight),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    shape = RoundedCornerShape(FrianzoUi.ButtonRadius),
                    colors = ButtonDefaults.buttonColors(containerColor = FrianzoBlue, contentColor = Color.White),
                    onClick = {
                        // B-B10: keep the reply target so a failed send
                        // restores the draft AS a reply, not as a new
                        // top-level comment.
                        val text = draft; val parent = replyTo?.id; val savedReply = replyTo; draft = ""; replyTo = null
                        sending = true; sendError = null
                        scope.launch {
                            try {
                                val res = api2.addReelComment(reel.id, CommentCreateRequest(text, parent))
                                if (res.success) {
                                    // v3.8 (F16): short send sound (web rule).
                                    try { val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60); tg.startTone(ToneGenerator.TONE_PROP_BEEP, 120); scope.launch { delay(300); try { tg.release() } catch (_: Exception) {} } } catch (_: Exception) {}
                                    total += 1; onCommentAdded(); justSent = true; if (parent != null) expandedIds = expandedIds + parent; load()
                                } else { sendError = friendlyReelsError(res.error?.message, "Could not send comment"); draft = text; replyTo = savedReply }
                            } catch (e: Exception) {
                                if (Outbox.isNetworkError(e)) {
                                    // v5.12: offline — queue the comment; the
                                    // flush replays it when back online. The
                                    // draft stays cleared so it cannot be
                                    // sent twice.
                                    Outbox.enqueueJson(context, "COMMENT_REEL", reel.id, mapOf("text" to text, "parent" to (parent ?: "")))
                                    sendError = "Waiting for connection…"
                                } else { sendError = friendlyReelsError(e, "Could not send comment"); draft = text; replyTo = savedReply }
                            }
                            sending = false
                        }
                    },
                ) { Text(if (sending) "..." else "Send", maxLines = 1, softWrap = false) }
            }
            // U-B9: a failed send shows here, next to the input — not at
            // the bottom of the list. C-A4: x/500 counter near the limit.
            if (sendError != null) Text(sendError!!, fontSize = 12.sp, color = Color.Red, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            if (draft.length > 440) Text("${draft.length}/500", fontSize = 11.sp, color = Color(0xFF94A3B8), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End)
            Spacer(Modifier.height(8.dp).navigationBarsPadding())
        }
        }
    }
}

// C-A7 (web rule): skeleton rows while comments load (web: CommentSkeleton
// — 36px avatar + 3 grey bars, animate-pulse). Static here: this file
// imports no infinite-transition API, so the bars do not pulse.
@Composable
private fun ReelCommentSkeletonRow() {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(Color(0xFFE2E8F0)))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Box(Modifier.width(120.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFFE2E8F0)))
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFFF1F5F9)))
            Spacer(Modifier.height(6.dp))
            Box(Modifier.width(180.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFFF1F5F9)))
        }
    }
}

@Composable
private fun ReelCommentRow(api2: FrianzoApi2, c: ReelComment, reel: Reel, myId: String?, isReply: Boolean = false, parentAuthor: Author? = null, popupOpenId: String? = null, onPopupOpened: (String?) -> Unit = {}, onReply: (ReelComment) -> Unit, onChanged: () -> Unit, onDeleted: () -> Unit = {}) {
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
    var deleting by remember { mutableStateOf(false) }
    val editFocus = remember { FocusRequester() }
    // U-B15 (web rule: one popup at a time — the web dispatches
    // 'reel-comment-popup-open' and every other row closes): the dialog
    // owns the id of the row whose menu/picker is open.
    val popupBlocked = popupOpenId != null && popupOpenId != c.id
    fun claimPopup() { onPopupOpened(c.id) }
    fun releasePopup() { onPopupOpened(null) }
    fun openMenu() { claimPopup(); pickerOpen = false; menuOpen = true }
    fun togglePicker() { if (pickerOpen) { pickerOpen = false; releasePopup() } else { claimPopup(); pickerOpen = true } }
    LaunchedEffect(popupOpenId) { if (popupOpenId != null && popupOpenId != c.id) { menuOpen = false; pickerOpen = false } }
    // U-B19 (web rule): the edit field takes focus when editing starts.
    LaunchedEffect(editing) { if (editing) { delay(100); editFocus.requestFocus() } }
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
    // S-5 + C-A8 (web rule): a 550ms long-press anywhere on the comment
    // opens its menu (the web's startLongPress timer; moving cancels it).
    // Over the Like/Reply/menu buttons the press is consumed by the
    // button itself — the Like button's own long-press also opens the
    // menu (see below), so the menu is reachable from the comment body.
    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 6.dp).pointerInput(Unit) { detectTapGestures(onLongPress = { if (!editing && !popupBlocked) openMenu() }) }) {
        Row(verticalAlignment = Alignment.Top) {
            FrianzoAvatar(c.author?.profilePictureUrl, 32.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                // U-B1 (web rule): NO bubble — the comment is plain text
                // on the sheet background (was: a grey rounded box).
                Column(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(c.author?.displayName ?: c.author?.username ?: "User", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            if (c.author?.isVerified == true) { Spacer(Modifier.width(4.dp)); Icon(Icons.Filled.CheckCircle, contentDescription = "Verified", modifier = Modifier.size(16.dp), tint = FrianzoBlue) }
                            // U-B3 (web rule): the timestamp sits right
                            // after the name, 11sp grey (was: detached at
                            // the far right of the action row).
                            Text(reelTimeAgo(c.createdAt), fontSize = 11.sp, color = Color(0xFF94A3B8), maxLines = 1, softWrap = false, modifier = Modifier.padding(start = 6.dp))
                        }
                        if (editing) {
                            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(value = editDraft, onValueChange = { if (it.length <= 500) editDraft = it }, modifier = Modifier.weight(1f).focusRequester(editFocus), singleLine = true, shape = RoundedCornerShape(10.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = FrianzoBorder, unfocusedBorderColor = FrianzoBorder))
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
                                fontSize = 14.sp,
                            )
                        }
                }
                Spacer(Modifier.height(4.dp))
                if (pickerOpen) {
                    // U-B5 (web rule): the picker FLOATS above the Like
                    // button (web: absolute bottom-full left-0 mb-2)
                    // instead of pushing the list down (was: an in-flow Row).
                    // It is anchored to this row: if the list scrolls while
                    // it is open it stays where it was — it closes on pick,
                    // on an outside tap, or when another row opens a popup.
                    Popup(
                        alignment = Alignment.BottomStart,
                        offset = with(LocalDensity.current) { IntOffset(8.dp.roundToPx(), (-44).dp.roundToPx()) },
                        onDismissRequest = { pickerOpen = false; releasePopup() },
                        properties = PopupProperties(focusable = false),
                    ) {
                        ReactionPickerPill(32.dp, listOf("like", "love", "haha", "wow", "sad", "angry")) { type -> pickerOpen = false; releasePopup(); applyReaction(type) }
                    }
                }
                Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.pointerInput(c.id) {
                        detectTapGestures(
                            // C-A8 (web rule): a long-press on Like opens
                            // the comment MENU (was: liked directly — the
                            // old Tip line taught that wrong behaviour).
                            // Tap still toggles the picker (web rule).
                            onLongPress = { if (!popupBlocked) openMenu() },
                            onTap = { togglePicker() },
                        )
                    }) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                            // U-B11 (web rule): a reacted comment shows the
                            // emoji only — no "Like" word beside it.
                            if (localReaction != null) { ReactionIcon(localReaction, 16.dp) }
                            else Text("Like", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                        }
                    }
                    // U-B10 (web rule): the count sits right after Like
                    // (was: after Reply, before the timestamp).
                    if (localCount > 0) { Spacer(Modifier.width(6.dp)); Text(reelCount(localCount), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Spacer(Modifier.width(12.dp))
                    TextButton(onClick = { onReply(c) }, contentPadding = PaddingValues(0.dp)) { Text("Reply", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false) }
                    Spacer(Modifier.weight(1f))
                    Box {
                        if (!isReply) IconButton(onClick = { if (!popupBlocked) openMenu() }, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.MoreVert, contentDescription = "More", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        DropdownMenu(expanded = menuOpen && !popupBlocked, onDismissRequest = { menuOpen = false; releasePopup() }) {
                            if (isOwner) DropdownMenuItem(text = { Text("Edit") }, onClick = { menuOpen = false; releasePopup(); editDraft = c.content ?: ""; editing = true })
                            DropdownMenuItem(text = { Text("Reply") }, onClick = { menuOpen = false; releasePopup(); onReply(c) })
                            DropdownMenuItem(text = { Text("Copy") }, onClick = { menuOpen = false; releasePopup(); clipboard.setText(AnnotatedString(c.content ?: "")); copied = true; scope.launch { delay(2000); copied = false } })
                            DropdownMenuItem(text = { Text(if (reported) "Reported" else "Report") }, onClick = { menuOpen = false; releasePopup(); showReport = true })
                            if (canDelete) DropdownMenuItem(text = { Text("Delete", color = FrianzoBadgeRed) }, onClick = { menuOpen = false; releasePopup(); showDelete = true })
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
                Button(
                    onClick = {
                        showReport = false
                        scope.launch {
                            try {
                                val r = api2.reportReelComment(c.id, mapOf("reason" to reportReason))
                                if (r.success) { reported = true; reportError = null } else reportError = friendlyReelsError(r.error?.message, "Could not report comment")
                            } catch (e: Exception) { reportError = friendlyReelsError(e, "Could not report comment") }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = FrianzoBadgeRed, contentColor = Color.White),
                    shape = RoundedCornerShape(28.dp),
                ) { Text("Report", maxLines = 1, softWrap = false) }
            },
            dismissButton = { TextButton(onClick = { showReport = false }) { Text("Cancel") } },
        )
    }
    if (showDelete) {
        FrianzoConfirmDialog(
            title = "Delete comment?",
            message = "This can't be undone.",
            confirmLabel = if (deleting) "Deleting..." else "Delete",
            confirmRed = true,
            confirmEnabled = !deleting,
            onConfirm = {
                deleting = true
                scope.launch {
                    try {
                        val r = api2.deleteReelComment(c.id)
                        if (r.success) { showDelete = false; deleting = false; onChanged(); onDeleted() }
                        else { showDelete = false; deleting = false; deleteError = friendlyReelsError(r.error?.message, "Could not delete comment") }
                    } catch (e: Exception) { showDelete = false; deleting = false; deleteError = friendlyReelsError(e, "Could not delete comment") }
                }
            },
            onDismiss = { showDelete = false },
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

// ---------- REEL UPLOAD: web-like black editor (POST /api/reels) ----------
// v5.1 (owner: "Web jaisa editor banao"): the old white scrolling form is
// replaced by the web's black full-screen editor (/reels/new): fixed
// header (X + "New Reel" + "Post" pill with real upload %), video preview
// in the middle (live speed, flip/rotate, 12% letterbox, text overlay),
// bottom panel (caption + 5-icon toolbar) and bottom sheets for Edit /
// Filters / Templates / Text / Speed / Cover / Trim / Crop. Flow (web):
// the video is picked/recorded FIRST (camera page / gallery) and the
// editor only opens with a video; the + tap checks the daily limit
// first. No manual category picker — AI only (owner order 2026-10-16).
// Filter groups fixed to the web's (Cool is Trending, not Portrait).
// NOT done (honest): live filter on the MAIN preview — the preview is a
// VideoView, which has no filter API; the only real path is adding
// media3 + media3-effect and rewriting the player, which cannot be
// verified in the sandbox and risks the build. The filter IS applied
// server-side on Post (as before), and the Filters sheet shows the real
// filter on a video frame (ColorMatrix), so the posted reel is correct.

private data class ReelEditorFilter(val id: String, val name: String, val cat: String)

// Web REEL_FILTERS (src/app/reels/new/page.tsx): Trending = Normal,
// Vivid, Warm, Cool; Portrait = Beauty only; B&W = B&W, Vintage.
private val REEL_EDITOR_FILTERS = listOf(
    ReelEditorFilter("normal", "Normal", "trending"),
    ReelEditorFilter("vivid", "Vivid", "trending"),
    ReelEditorFilter("warm", "Warm", "trending"),
    ReelEditorFilter("cool", "Cool", "trending"),
    ReelEditorFilter("beauty", "Beauty", "portrait"),
    ReelEditorFilter("bw", "B&W", "mono"),
    ReelEditorFilter("vintage", "Vintage", "mono"),
)
private val REEL_EDITOR_FILTER_CATS = listOf("trending" to "Trending", "portrait" to "Portrait", "mono" to "B&W")

private data class ReelEditorTemplate(val id: String, val name: String, val color1: Color, val color2: Color, val filter: String, val speed: String, val letterbox: Boolean, val desc: String)

// Web REEL_TEMPLATES: Cinematic (vintage, 1, letterbox), Glow (beauty,
// 1), Slow Mo (cool, 0.5). Gradients are the web's 135deg values.
private val REEL_EDITOR_TEMPLATES = listOf(
    ReelEditorTemplate("cinematic", "Cinematic", Color(0xFF1A1A2E), Color(0xFF0F0F0F), "vintage", "1", true, "Movie-style bars"),
    ReelEditorTemplate("glow", "Glow", Color(0xFFFFD9A0), Color(0xFFFF9A9E), "beauty", "1", false, "Smooth skin glow"),
    ReelEditorTemplate("slowmo", "Slow Mo", Color(0xFFA8EDEA), Color(0xFF7FB8E8), "cool", "0.5", false, "Smooth slow motion"),
)

private enum class ReelEditorSheet { EDIT, FILTERS, TEMPLATES, TEXT, SPEED, COVER, TRIM, CROP }

// 4x5 colour matrices (Compose ColorMatrix = 20 floats, translation in
// 0..1). These are the web's CSS filter chains, composed into one matrix
// so the Filters sheet shows the REAL filter on a video frame.
private fun matIdentity(): FloatArray = floatArrayOf(
    1f, 0f, 0f, 0f, 0f,
    0f, 1f, 0f, 0f, 0f,
    0f, 0f, 1f, 0f, 0f,
    0f, 0f, 0f, 1f, 0f,
)

// Returns a*b (b is applied first, then a) for 4x5 matrices.
private fun matMul(a: FloatArray, b: FloatArray): FloatArray {
    val out = FloatArray(20)
    for (r in 0 until 4) {
        for (c in 0 until 5) {
            var v = 0f
            for (k in 0 until 4) v += a[r * 5 + k] * b[k * 5 + c]
            if (c == 4) v += a[r * 5 + 4]
            out[r * 5 + c] = v
        }
    }
    return out
}

private fun matSaturation(s: Float): FloatArray {
    val rw = 0.213f; val gw = 0.715f; val bw = 0.072f
    return floatArrayOf(
        rw + (1f - rw) * s, gw - gw * s, bw - bw * s, 0f, 0f,
        rw - rw * s, gw + (1f - gw) * s, bw - bw * s, 0f, 0f,
        rw - rw * s, gw - gw * s, bw + (1f - bw) * s, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )
}

private fun matContrast(c: Float): FloatArray {
    val t = 0.5f * (1f - c)
    return floatArrayOf(
        c, 0f, 0f, 0f, t,
        0f, c, 0f, 0f, t,
        0f, 0f, c, 0f, t,
        0f, 0f, 0f, 1f, 0f,
    )
}

private fun matBrightness(b: Float): FloatArray = floatArrayOf(
    b, 0f, 0f, 0f, 0f,
    0f, b, 0f, 0f, 0f,
    0f, 0f, b, 0f, 0f,
    0f, 0f, 0f, 1f, 0f,
)

private fun matSepia(a: Float): FloatArray {
    val sep = floatArrayOf(
        0.393f, 0.769f, 0.189f, 0f, 0f,
        0.349f, 0.686f, 0.168f, 0f, 0f,
        0.272f, 0.534f, 0.131f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )
    val id = matIdentity()
    return FloatArray(20) { i -> id[i] * (1f - a) + sep[i] * a }
}

private fun matHueRotate(deg: Float): FloatArray {
    val rad = Math.toRadians(deg.toDouble())
    val cos = Math.cos(rad).toFloat(); val sin = Math.sin(rad).toFloat()
    val rw = 0.213f; val gw = 0.715f; val bw = 0.072f
    return floatArrayOf(
        rw + cos * (1f - rw) + sin * (-rw), gw + cos * (-gw) + sin * (-gw), bw + cos * (-bw) + sin * (1f - bw), 0f, 0f,
        rw + cos * (-rw) + sin * 0.143f, gw + cos * (1f - gw) + sin * 0.140f, bw + cos * (-bw) + sin * (-0.283f), 0f, 0f,
        rw + cos * (-rw) + sin * (-(1f - rw)), gw + cos * (-gw) + sin * gw, bw + cos * (1f - bw) + sin * bw, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )
}

// The web's CSS filter chain per filter id, as one ColorMatrix.
// Functions are applied left-to-right like CSS (the first in the chain
// runs first).
private fun filterColorMatrix(id: String): ColorMatrix {
    var m = matIdentity()
    fun apply(next: FloatArray) { m = matMul(next, m) }
    when (id) {
        "vivid" -> { apply(matSaturation(1.5f)); apply(matContrast(1.12f)); apply(matBrightness(1.01f)) }
        "warm" -> { apply(matSepia(0.25f)); apply(matSaturation(1.3f)); apply(matContrast(1.05f)); apply(matBrightness(1.03f)) }
        "cool" -> { apply(matSaturation(1.15f)); apply(matContrast(1.05f)); apply(matBrightness(1.02f)); apply(matHueRotate(-10f)) }
        "beauty" -> { apply(matBrightness(1.04f)); apply(matSaturation(1.18f)); apply(matContrast(1.02f)) }
        "bw" -> { apply(matSaturation(0f)); apply(matContrast(1.1f)) }
        "vintage" -> { apply(matSepia(0.4f)); apply(matSaturation(0.8f)); apply(matContrast(0.95f)); apply(matBrightness(1.02f)) }
    }
    return ColorMatrix(m)
}

private fun formatTrimTime(sec: Float): String {
    val s = sec.toInt().coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

// v5.1: real upload % WITHOUT Okio buffer() — the v3.7 counting fix
// removed CountingRequestBody because buffer() broke the build, and the
// task forbids re-adding it. This body writes the file in 8KB chunks
// straight to the BufferedSink (sink.write(ByteArray, Int, Int) is
// stable Okio API in every version) and reports progress. No Buffer,
// no ForwardingSink, no buffer() anywhere. Progress is throttled to
// whole-percent changes (max 100 callbacks per upload).
private class ProgressFileBody(
    private val file: File,
    private val contentType: MediaType?,
    private val onProgress: (written: Long, total: Long) -> Unit,
) : RequestBody() {
    override fun contentType() = contentType
    override fun contentLength() = file.length()
    override fun writeTo(sink: BufferedSink) {
        val total = file.length()
        var written = 0L
        var lastPct = -1
        file.inputStream().use { input ->
            val buf = ByteArray(8 * 1024)
            var read: Int
            while (input.read(buf).also { read = it } != -1) {
                sink.write(buf, 0, read)
                written += read
                val pct = if (total > 0) ((written * 100) / total).toInt().coerceIn(0, 100) else 100
                if (pct != lastPct) { lastPct = pct; onProgress(written, total) }
            }
        }
        sink.flush()
    }
}

@Composable
private fun ReelEditorHeader(onCancel: () -> Unit, postLabel: String, postEnabled: Boolean, onPost: () -> Unit) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp).noRippleClickable { onCancel() }, contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Close, contentDescription = "Cancel", tint = Color.White, modifier = Modifier.size(24.dp))
        }
        Text("New Reel", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f), maxLines = 1, softWrap = false)
        Box(Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = if (postEnabled) 1f else 0.5f)).noRippleClickable { if (postEnabled) onPost() }.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text(postLabel, color = Color(0xFF0F172A), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun ReelEditorToolbarButton(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(64.dp).noRippleClickable { onClick() }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            if (active) {
                Box(Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp).size(10.dp).clip(CircleShape).background(Color(0xFF34D399)).border(2.dp, Color.Black, CircleShape))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun ReelEditorEditButton(icon: ImageVector, label: String, active: Boolean, badge: String? = null, onClick: () -> Unit) {
    Column(Modifier.noRippleClickable { onClick() }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(if (active) Color(0xFF34D399).copy(alpha = 0.20f) else Color.White.copy(alpha = 0.10f)).border(if (active) 2.dp else 0.dp, if (active) Color(0xFF34D399) else Color.Transparent, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(24.dp))
            if (badge != null) {
                Box(Modifier.align(Alignment.BottomCenter).offset(y = 4.dp).clip(RoundedCornerShape(50)).background(Color(0xFF34D399)).padding(horizontal = 6.dp, vertical = 1.dp)) {
                    Text(badge, color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun ReelEditorSheetHeader(title: String, onDone: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, softWrap = false)
        Box(Modifier.clip(RoundedCornerShape(50)).background(Color.White).noRippleClickable { onDone() }.padding(horizontal = 20.dp, vertical = 6.dp)) {
            Text("Done", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
        }
    }
}

// Dark text-field colours for the editor (web: border-white/20,
// bg-white/10, white text, white/50 placeholder).
@Composable
private fun darkEditorFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Color.White.copy(alpha = 0.10f),
    unfocusedContainerColor = Color.White.copy(alpha = 0.10f),
    focusedBorderColor = Color.White.copy(alpha = 0.20f),
    unfocusedBorderColor = Color.White.copy(alpha = 0.20f),
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    cursorColor = Color.White,
    focusedPlaceholderColor = Color.White.copy(alpha = 0.5f),
    unfocusedPlaceholderColor = Color.White.copy(alpha = 0.5f),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReelUploadScreen(api2: FrianzoApi2, initialVideoUri: Uri? = null, onDone: (String?) -> Unit, onClose: () -> Unit, initialCoverUri: Uri? = null, initialFilter: String? = null, initialSpeed: String? = null, onOpenCamera: () -> Unit = {}) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    var videoUri by remember { mutableStateOf<Uri?>(initialVideoUri) }
    var caption by rememberSaveable { mutableStateOf("") }
    // AI only (web rule) — the detected category is sent as-is; there is
    // no manual picker, no chip row, no "Other" field any more.
    var category by rememberSaveable { mutableStateOf("") }
    var coverUri by remember { mutableStateOf<Uri?>(initialCoverUri) }
    var speed by rememberSaveable { mutableStateOf(initialSpeed ?: "1") }
    var filter by rememberSaveable { mutableStateOf(initialFilter ?: "normal") }
    var textOverlay by rememberSaveable { mutableStateOf("") }
    var activeTemplate by rememberSaveable { mutableStateOf<String?>(null) }
    var letterbox by rememberSaveable { mutableStateOf(false) }
    var flip by rememberSaveable { mutableStateOf(false) }
    var rotateDeg by rememberSaveable { mutableStateOf(0) }
    var crop by rememberSaveable { mutableStateOf<String?>(null) }
    var textPosition by rememberSaveable { mutableStateOf("top") }
    var coverFrames by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var selectedFrameIdx by remember { mutableStateOf(-1) }
    var detecting by remember { mutableStateOf(false) }
    var trimStartF by remember { mutableStateOf(0f) }
    var trimEndF by remember { mutableStateOf(0f) }
    var trimApplied by remember { mutableStateOf(false) }
    var maxSec by remember { mutableStateOf(180) }
    var dailyStatus by remember { mutableStateOf<ReelStatusData?>(null) }
    var videoDurationSec by remember { mutableStateOf<Int?>(null) }
    var previewError by remember { mutableStateOf(false) }
    var posting by remember { mutableStateOf(false) }
    var uploadProgress by remember { mutableStateOf<Int?>(null) }
    var postTrimProcessing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var uploadJob by remember { mutableStateOf<Job?>(null) }
    var previewPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    // One sheet at a time (web: only one bottom sheet is ever open).
    var sheet by remember { mutableStateOf<ReelEditorSheet?>(null) }
    var filterCat by rememberSaveable { mutableStateOf("trending") }
    // Trim sheet working state (snapshot = values when the sheet opened).
    var trimSnapshot by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var trimPlaying by remember { mutableStateOf(false) }
    var currentTime by remember { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()

    val durationF = (videoDurationSec ?: 0).toFloat()
    val trimChanged = trimStartF > 0.05f || (durationF > 0f && trimEndF < durationF - 0.05f)
    val processing = posting && postTrimProcessing && uploadProgress == 100
    val postLabel = if (posting) { if (processing) "Processing…" else "${uploadProgress ?: 0}%" } else "Post"
    val postEnabled = !posting && videoUri != null && trimEndF > trimStartF && (dailyStatus?.remaining ?: 1) > 0

    // Web Toast: one message at a time, auto-clears after 3 seconds.
    LaunchedEffect(message) { if (message != null) { delay(3000); message = null } }

    LaunchedEffect(Unit) {
        scope.launch { try { val r = api2.reelsConfig(); if (r.success && r.data != null) maxSec = r.data.maxDurationSec } catch (_: Exception) {} }
        scope.launch { try { val r = api2.myReelStatus(); if (r.success && r.data != null) dailyStatus = r.data } catch (_: Exception) {} }
    }

    fun checkVideo(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            val durMs = try { val mmr = MediaMetadataRetriever(); try { mmr.setDataSource(context, uri); mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 15000L } finally { try { mmr.release() } catch (_: Exception) {} } } catch (_: Exception) { 15000L }
            val durSec = (durMs / 1000).toInt()
            videoDurationSec = durSec
            if (durSec > maxSec) { message = "Reels must be ${maxSec}s seconds or shorter."; videoUri = null; return@launch }
            trimStartF = 0f
            trimEndF = durSec.toFloat()
        }
    }
    LaunchedEffect(videoUri) { val u = videoUri; if (u != null) checkVideo(u) }

    LaunchedEffect(videoUri) {
        val u = videoUri ?: return@LaunchedEffect
        scope.launch(Dispatchers.IO) {
            val frames = extractVideoFrames(context, u, 7)
            coverFrames = frames
            selectedFrameIdx = -1
            if (frames.isNotEmpty()) {
                detecting = true
                try {
                    val b64 = frames.take(3).map { bitmapToBase64Jpeg(it) }
                    val r = api2.detectCategory(mapOf<String, Any>("frames" to b64, "caption" to caption))
                    val cat = r.data?.category
                    if (r.success && !cat.isNullOrBlank()) category = cat
                } catch (_: Exception) {}
                detecting = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri -> if (uri != null) videoUri = uri }
    val coverPicker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) {
            val size = try { context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L } catch (_: Exception) { -1L }
            if (size > 10 * 1024 * 1024) message = "Cover photo must be smaller than 10MB" else { coverUri = uri; selectedFrameIdx = -1 }
        }
    }
    val pickCover = rememberPermissionGate(galleryPermissions(), onGranted = { coverPicker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) })
    val pickReelVideo = rememberPermissionGate(galleryPermissions(), onGranted = { picker.launch(PickVisualMediaRequest(PickVisualMedia.VideoOnly)) })

    // Web: the preview really plays at the chosen speed (playbackRate).
    LaunchedEffect(speed) {
        val mp = previewPlayer ?: return@LaunchedEffect
        try { mp.playbackParams = mp.playbackParams.setSpeed(speed.toFloatOrNull() ?: 1f) } catch (_: Exception) {}
    }

    // Web rule: while a trim is applied (or the trim sheet is open) the
    // preview loops inside [trimStart, trimEnd] instead of the whole video.
    LaunchedEffect(trimApplied, sheet, videoUri) {
        if (!trimApplied && sheet != ReelEditorSheet.TRIM) return@LaunchedEffect
        while (true) {
            try {
                val mp = previewPlayer
                if (mp != null) {
                    val pos = mp.currentPosition / 1000f
                    currentTime = pos
                    trimPlaying = mp.isPlaying
                    if (trimEndF > trimStartF && pos >= trimEndF) {
                        try { mp.seekTo((trimStartF * 1000).toInt()) } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
            delay(100)
        }
    }

    // Web handleCancel: if posting, abort the upload and stay on the
    // editor; if a file is loaded, ask "Discard this reel?"; else back.
    fun handleCancel() {
        if (posting) {
            uploadJob?.cancel()
            uploadJob = null
            posting = false
            uploadProgress = null
            postTrimProcessing = false
            message = "Upload cancelled."
            return
        }
        if (videoUri != null) confirmDiscard = true else onClose()
    }

    fun handlePost() {
        val uri = videoUri ?: return
        if (posting || trimEndF <= trimStartF) return
        posting = true
        uploadProgress = 0
        val changed = trimStartF > 0.05f || (durationF > 0f && trimEndF < durationF - 0.05f)
        postTrimProcessing = changed
        uploadJob = scope.launch {
            var tmp: File? = null
            var coverTmp: File? = null
            try {
                val durMs = try { val mmr = MediaMetadataRetriever(); try { mmr.setDataSource(context, uri); mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 15000L } finally { try { mmr.release() } catch (_: Exception) {} } } catch (_: Exception) { 15000L }
                val durationSec = (durMs / 1000).toInt()
                if (durationSec < 1) throw Exception("Couldn't read this video's length.")
                if (durationSec > maxSec) throw Exception("This video is ${durationSec}s — reels can be ${maxSec}s at most.")
                val input = context.contentResolver.openInputStream(uri) ?: throw Exception("Could not read the video")
                val mime = try { context.contentResolver.getType(uri) } catch (_: Exception) { null } ?: "video/mp4"
                tmp = File(context.cacheDir, "reel_upload.mp4")
                input.use { inp -> tmp!!.outputStream().use { out -> inp.copyTo(out) } }
                // Real upload % without Okio buffer() (see ProgressFileBody).
                // The body calls us only when the whole % changes.
                val videoBody = ProgressFileBody(tmp!!, mime.toMediaTypeOrNull()) { written, total ->
                    val pct = if (total > 0) ((written * 100) / total).toInt().coerceIn(0, 100) else 100
                    scope.launch(Dispatchers.Main) { uploadProgress = pct }
                }
                var coverPart: MultipartBody.Part? = null
                val frameCover = selectedFrameIdx.takeIf { it >= 0 }?.let { coverFrames.getOrNull(it) }
                if (frameCover != null) {
                    val cTmp = File(context.cacheDir, "reel_cover.jpg")
                    coverTmp = cTmp
                    cTmp.outputStream().use { out -> frameCover.compress(Bitmap.CompressFormat.JPEG, 85, out) }
                    coverPart = MultipartBody.Part.createFormData("cover", cTmp.name, cTmp.asRequestBody("image/jpeg".toMediaTypeOrNull()))
                } else if (coverUri != null) {
                    val c = coverUri!!
                    val cMime = try { context.contentResolver.getType(c) } catch (_: Exception) { null } ?: "image/jpeg"
                    val cSize = try { context.contentResolver.openFileDescriptor(c, "r")?.use { it.statSize } ?: -1L } catch (_: Exception) { -1L }
                    if (cSize > 10 * 1024 * 1024) throw Exception("Cover photo must be smaller than 10MB")
                    coverTmp = File(context.cacheDir, "reel_cover.jpg")
                    context.contentResolver.openInputStream(c)?.use { inp -> coverTmp!!.outputStream().use { out -> inp.copyTo(out) } }
                    coverPart = MultipartBody.Part.createFormData("cover", coverTmp!!.name, coverTmp!!.asRequestBody(cMime.toMediaTypeOrNull()))
                }
                val trimStartSec = trimStartF.toInt().takeIf { it > 0 }
                val trimEndSec = trimEndF.toInt().takeIf { it > 0 && it < durationSec && (trimStartSec == null || it > trimStartSec) }
                val res = api2.uploadReel(
                    MultipartBody.Part.createFormData("video", tmp!!.name, videoBody),
                    caption.ifBlank { null }?.toRequestBody("text/plain".toMediaTypeOrNull()),
                    category.ifBlank { null }?.toRequestBody("text/plain".toMediaTypeOrNull()),
                    durationSec.toString().toRequestBody("text/plain".toMediaTypeOrNull()),
                    cover = coverPart,
                    speed = if (speed == "1") null else speed.toRequestBody("text/plain".toMediaTypeOrNull()),
                    textOverlay = textOverlay.ifBlank { null }?.toRequestBody("text/plain".toMediaTypeOrNull()),
                    trimStartSec = trimStartSec?.toString()?.toRequestBody("text/plain".toMediaTypeOrNull()),
                    trimEndSec = trimEndSec?.toString()?.toRequestBody("text/plain".toMediaTypeOrNull()),
                    filter = if (filter == "normal") null else filter.toRequestBody("text/plain".toMediaTypeOrNull()),
                    letterbox = if (letterbox) "true".toRequestBody("text/plain".toMediaTypeOrNull()) else null,
                    flip = if (flip) "true".toRequestBody("text/plain".toMediaTypeOrNull()) else null,
                    rotate = if (rotateDeg != 0) rotateDeg.toString().toRequestBody("text/plain".toMediaTypeOrNull()) else null,
                    crop = crop?.toRequestBody("text/plain".toMediaTypeOrNull()),
                    textPosition = if (textOverlay.isNotBlank()) textPosition.toRequestBody("text/plain".toMediaTypeOrNull()) else null,
                )
                if (!res.success) throw Exception(res.error?.message ?: "Upload failed")
                onDone(res.data?.reel?.id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                message = friendlyReelsError(e, "Upload failed — try again")
            } finally {
                posting = false; uploadProgress = null; postTrimProcessing = false; uploadJob = null
                try { tmp?.delete() } catch (_: Exception) {}
                try { coverTmp?.delete() } catch (_: Exception) {}
            }
        }
    }

    // No video yet (photo path: a cover was taken, no video) — the web
    // editor never opens without a video; the app only reaches here when
    // the camera's photo mode supplied a cover. Offer pick / record only.
    if (videoUri == null) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Column(Modifier.fillMaxSize()) {
                ReelEditorHeader(onCancel = { onClose() }, postLabel = "Post", postEnabled = false, onPost = {})
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Pick a video to start", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.10f)).noRippleClickable { pickReelVideo() }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                                Text("Pick a video", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Box(Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.10f)).noRippleClickable { onOpenCamera() }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                                Text("Record a video", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
            message?.let {
                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp).clip(RoundedCornerShape(28.dp)).background(Color(0xFF1F2937)).padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(it, color = Color.White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        return
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            ReelEditorHeader(onCancel = { handleCancel() }, postLabel = postLabel, postEnabled = postEnabled, onPost = { handlePost() })
            // Preview (flex-1, centred). The preview box size follows the
            // crop (web): original 9:16 fills the space, square 1:1 and
            // portrait 4:5 cap at 70% of the height. A 90/270-degree
            // rotation scales 0.5625 so the rotated video still fits.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                val maxW = maxWidth
                val maxH = maxHeight
                val boxW: Dp
                val boxH: Dp
                when (crop) {
                    "square" -> { val side = minOf(maxH * 0.7f, maxW); boxW = side; boxH = side }
                    "portrait" -> { val h = minOf(maxH * 0.7f, maxW * 5f / 4f); boxW = h * 4f / 5f; boxH = h }
                    else -> { val h = minOf(maxH, maxW * 16f / 9f); boxW = h * 9f / 16f; boxH = h }
                }
                Box(Modifier.width(boxW).height(boxH).clip(RoundedCornerShape(12.dp)).background(Color.Black)) {
                    key(videoUri) {
                        AndroidView(factory = { ctx -> VideoView(ctx).apply {
                            setVideoURI(videoUri)
                            setOnPreparedListener { mp ->
                                // v5.7: the editor preview was muted
                                // (volume 0), so a recorded video sounded
                                // silent and the owner thought the recording
                                // had no voice. Play it with sound so the
                                // recorded voice is audible.
                                mp.setVolume(1f, 1f); mp.isLooping = true
                                previewPlayer = mp
                                try { mp.playbackParams = mp.playbackParams.setSpeed(speed.toFloatOrNull() ?: 1f) } catch (_: Exception) {}
                                start()
                            }
                            setOnErrorListener { _, _, _ -> previewError = true; true }
                        } }, modifier = Modifier.fillMaxSize().graphicsLayer {
                            val rotScale = if (rotateDeg == 90 || rotateDeg == 270) 0.5625f else 1f
                            scaleX = (if (flip) -1f else 1f) * rotScale
                            scaleY = rotScale
                            rotationZ = rotateDeg.toFloat()
                        })
                    }
                    if (letterbox) {
                        Box(Modifier.fillMaxWidth().height(boxH * 0.12f).background(Color.Black).align(Alignment.TopCenter))
                        Box(Modifier.fillMaxWidth().height(boxH * 0.12f).background(Color.Black).align(Alignment.BottomCenter))
                    }
                    if (textOverlay.isNotBlank()) {
                        Box(Modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = when (textPosition) { "bottom" -> Alignment.BottomCenter; "middle" -> Alignment.Center; else -> Alignment.TopCenter }) {
                            Text(textOverlay, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis, style = TextStyle(shadow = Shadow(color = Color.Black.copy(alpha = 0.7f), offset = Offset(0f, 2f), blurRadius = 10f)))
                        }
                    }
                }
            }
            // Bottom panel (web: max 45dvh, scrollable): caption + toolbar.
            Column(Modifier.fillMaxWidth().heightIn(max = screenHeightDp.dp * 0.45f).verticalScroll(rememberScrollState()).imePadding().padding(16.dp)) {
                if (previewError) {
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFFFFF7ED)).padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text("Video preview is not supported on this device. MP4 (H.264) is recommended.", color = Color(0xFF92400E), fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                }
                if (processing) {
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF1E3A8A)).padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text("Processing… Please wait while the server prepares your reel.", color = Color.White, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                }
                OutlinedTextField(value = caption, onValueChange = { if (it.length <= 500) caption = it }, placeholder = { Text("Write a caption", color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp) }, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth(), colors = darkEditorFieldColors())
                if (detecting) {
                    Spacer(Modifier.height(8.dp))
                    Text("AI video dekh raha hai...", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                }
                if (uploadProgress != null) {
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.20f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth((uploadProgress ?: 0) / 100f).clip(RoundedCornerShape(50)).background(Color.White))
                    }
                }
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.10f)))
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceAround) {
                    ReelEditorToolbarButton(icon = Icons.Filled.Edit, label = "Edit", active = trimChanged || coverUri != null || selectedFrameIdx >= 0 || flip || rotateDeg != 0 || crop != null) { sheet = ReelEditorSheet.EDIT }
                    ReelEditorToolbarButton(icon = Icons.Filled.Star, label = "Filters", active = filter != "normal") { sheet = ReelEditorSheet.FILTERS }
                    ReelEditorToolbarButton(icon = Icons.Filled.GridView, label = "Effects", active = activeTemplate != null) { sheet = ReelEditorSheet.TEMPLATES }
                    ReelEditorToolbarButton(icon = Icons.Filled.TextFields, label = "Text", active = textOverlay.isNotBlank()) { sheet = ReelEditorSheet.TEXT }
                    ReelEditorToolbarButton(icon = Icons.Filled.Schedule, label = "Speed", active = speed != "1") { sheet = ReelEditorSheet.SPEED }
                }
            }
        }
        // Web Toast: bottom-centre pill, auto-clears after 3s.
        message?.let {
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp).clip(RoundedCornerShape(28.dp)).background(Color(0xFF1F2937)).padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(it, color = Color.White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        // Discard dialog (web: "Discard this reel?").
        if (confirmDiscard) {
            AlertDialog(onDismissRequest = { confirmDiscard = false }, title = { Text("Discard this reel?") }, text = { Text("Your video and edits will be lost.") }, confirmButton = { TextButton(onClick = { confirmDiscard = false; onClose() }) { Text("Discard", color = Color.Red) } }, dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } })
        }

        // Bottom sheets (web: one at a time, rounded-t-3xl, neutral-950).
        when (sheet) {
            ReelEditorSheet.EDIT -> {
                ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = Color(0xFF0A0A0A), shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
                        ReelEditorSheetHeader(title = "Edit", onDone = { sheet = null })
                        Spacer(Modifier.height(20.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            ReelEditorEditButton(icon = Icons.Filled.ContentCut, label = "Trim", active = trimChanged) {
                                trimSnapshot = trimStartF to trimEndF
                                sheet = ReelEditorSheet.TRIM
                            }
                            ReelEditorEditButton(icon = Icons.Filled.Image, label = "Cover", active = coverUri != null || selectedFrameIdx >= 0) { sheet = ReelEditorSheet.COVER }
                            ReelEditorEditButton(icon = Icons.Filled.SwapHoriz, label = "Flip", active = flip) {
                                flip = !flip
                                sheet = null
                                message = if (flip) "Flipped" else "Flip off"
                            }
                            ReelEditorEditButton(icon = Icons.Filled.RotateRight, label = "Rotate", active = rotateDeg != 0, badge = if (rotateDeg != 0) "${rotateDeg}°" else null) {
                                rotateDeg = when (rotateDeg) { 0 -> 90; 90 -> 180; 180 -> 270; else -> 0 }
                                message = if (rotateDeg == 0) "Rotation reset" else "Rotated ${rotateDeg}°"
                            }
                            ReelEditorEditButton(icon = Icons.Filled.Crop, label = "Crop", active = crop != null) { sheet = ReelEditorSheet.CROP }
                        }
                    }
                }
            }
            ReelEditorSheet.FILTERS -> {
                ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = Color(0xFF0A0A0A), shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
                        ReelEditorSheetHeader(title = "Filters", onDone = { sheet = null })
                        Spacer(Modifier.height(16.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            REEL_EDITOR_FILTER_CATS.forEach { (id, name) ->
                                val sel = filterCat == id
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.noRippleClickable { filterCat = id }) {
                                    Text(name, color = if (sel) Color.White else Color.White.copy(alpha = 0.4f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                                    Spacer(Modifier.height(10.dp))
                                    Box(Modifier.width(24.dp).height(2.dp).clip(RoundedCornerShape(50)).background(if (sel) Color(0xFF34D399) else Color.Transparent))
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(REEL_EDITOR_FILTERS.filter { it.cat == filterCat }) { f ->
                                val sel = filter == f.id
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(80.dp)) {
                                    Box(Modifier.size(width = 80.dp, height = 112.dp).clip(RoundedCornerShape(16.dp)).border(if (sel) 2.dp else 1.dp, if (sel) Color(0xFF34D399) else Color.White.copy(alpha = 0.10f), RoundedCornerShape(16.dp)).noRippleClickable { filter = f.id }) {
                                        // The thumbnail is a real frame of
                                        // THIS video with the REAL filter
                                        // matrix applied (web: live
                                        // video-frame thumbnails).
                                        val thumb = coverFrames.getOrNull(coverFrames.size / 2) ?: coverFrames.firstOrNull()
                                        if (thumb != null) {
                                            androidx.compose.foundation.Image(bitmap = thumb.asImageBitmap(), contentDescription = f.name, modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop, colorFilter = if (f.id == "normal") null else ColorFilter.colorMatrix(filterColorMatrix(f.id)))
                                        } else {
                                            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFFF59E0B), Color(0xFFEF4444), Color(0xFF8B5CF6), Color(0xFF3B82F6)))))
                                        }
                                        if (sel) {
                                            Box(Modifier.align(Alignment.BottomEnd).padding(6.dp).size(20.dp).clip(CircleShape).background(Color(0xFF34D399)), contentAlignment = Alignment.Center) {
                                                Icon(Icons.Filled.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(10.dp))
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Text(f.name, color = if (sel) Color(0xFF34D399) else Color.White.copy(alpha = 0.7f), fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }
                }
            }
            ReelEditorSheet.TEMPLATES -> {
                ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = Color(0xFF0A0A0A), shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
                        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Templates", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, softWrap = false)
                            if (activeTemplate != null) {
                                Box(Modifier.clip(RoundedCornerShape(50)).border(1.dp, Color.White.copy(alpha = 0.20f), RoundedCornerShape(50)).noRippleClickable { filter = "normal"; speed = "1"; letterbox = false; activeTemplate = null }.padding(horizontal = 16.dp, vertical = 6.dp)) {
                                    Text("Clear", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                                }
                                Spacer(Modifier.width(8.dp))
                            }
                            Box(Modifier.clip(RoundedCornerShape(50)).background(Color.White).noRippleClickable { sheet = null }.padding(horizontal = 20.dp, vertical = 6.dp)) {
                                Text("Done", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            REEL_EDITOR_TEMPLATES.forEach { t ->
                                val sel = activeTemplate == t.id
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                                    Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(16.dp)).background(Brush.linearGradient(listOf(t.color1, t.color2))).border(if (sel) 2.dp else 1.dp, if (sel) Color(0xFF34D399) else Color.White.copy(alpha = 0.10f), RoundedCornerShape(16.dp)).noRippleClickable {
                                        filter = t.filter; speed = t.speed; letterbox = t.letterbox; activeTemplate = t.id
                                        sheet = null
                                        message = t.name + " applied"
                                    }) {
                                        if (t.letterbox) {
                                            Box(Modifier.fillMaxWidth().fillMaxHeight(0.16f).background(Color.Black).align(Alignment.TopCenter))
                                            Box(Modifier.fillMaxWidth().fillMaxHeight(0.16f).background(Color.Black).align(Alignment.BottomCenter))
                                        }
                                        if (sel) {
                                            Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(24.dp).clip(CircleShape).background(Color(0xFF34D399)), contentAlignment = Alignment.Center) {
                                                Icon(Icons.Filled.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(12.dp))
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    Text(t.name, color = if (sel) Color(0xFF34D399) else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                                    Text(t.desc, color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
            ReelEditorSheet.TEXT -> {
                ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = Color(0xFF0A0A0A), shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
                    Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Add text", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, softWrap = false)
                            Box(Modifier.clip(RoundedCornerShape(50)).background(Color.White).noRippleClickable { sheet = null }.padding(horizontal = 16.dp, vertical = 6.dp)) {
                                Text("Done", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(value = textOverlay, onValueChange = { if (it.length <= 100) textOverlay = it }, placeholder = { Text("Write something...", color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = darkEditorFieldColors())
                        Spacer(Modifier.height(12.dp))
                        Text("Position", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("top" to "Top", "middle" to "Middle", "bottom" to "Bottom").forEach { (v, label) ->
                                val sel = textPosition == v
                                Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (sel) Color(0xFF34D399) else Color.White.copy(alpha = 0.10f)).noRippleClickable { textPosition = v }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                    Text(label, color = if (sel) Color.Black else Color.White.copy(alpha = 0.7f), fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
                                }
                            }
                        }
                        if (textOverlay.isNotBlank()) {
                            Spacer(Modifier.height(12.dp))
                            Text("Remove text", color = Color(0xFFF87171), fontSize = 12.sp, modifier = Modifier.noRippleClickable { textOverlay = "" })
                        }
                    }
                }
            }
            ReelEditorSheet.SPEED -> {
                ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = Color(0xFF0A0A0A), shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
                        ReelEditorSheetHeader(title = "Playback speed", onDone = { sheet = null })
                        Spacer(Modifier.height(20.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("0.5", "1", "1.5", "2").forEach { s ->
                                val sel = speed == s
                                Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (sel) Color(0xFF34D399) else Color.White.copy(alpha = 0.10f)).noRippleClickable { speed = s }.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                                    Text("${s}x", color = if (sel) Color.Black else Color.White.copy(alpha = 0.7f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Preview plays at this speed. The posted reel keeps it too.", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
                    }
                }
            }

            ReelEditorSheet.COVER -> {
                ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = Color(0xFF0A0A0A), shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
                        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Select cover", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, softWrap = false)
                            Text("Cancel", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, modifier = Modifier.noRippleClickable { sheet = null })
                        }
                        Spacer(Modifier.height(16.dp))
                        Box(Modifier.align(Alignment.CenterHorizontally).width(128.dp).aspectRatio(9f / 16f).clip(RoundedCornerShape(12.dp)).background(Color.Black).border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                            when {
                                coverUri != null -> AsyncImage(model = coverUri, contentDescription = "Custom cover", modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                                selectedFrameIdx >= 0 -> coverFrames.getOrNull(selectedFrameIdx)?.let { androidx.compose.foundation.Image(bitmap = it.asImageBitmap(), contentDescription = "Cover preview", modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
                                coverFrames.isNotEmpty() -> coverFrames.getOrNull(0)?.let { androidx.compose.foundation.Image(bitmap = it.asImageBitmap(), contentDescription = "Cover preview", modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
                                else -> Text("Loading...", color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp)
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            (0 until 7).forEach { i ->
                                val thumb = coverFrames.getOrNull(i)
                                val sel = selectedFrameIdx == i
                                Box(Modifier.weight(1f).aspectRatio(3f / 4f).clip(RoundedCornerShape(8.dp)).border(if (sel) 2.dp else 1.dp, if (sel) Color.White else Color.Transparent, RoundedCornerShape(8.dp)).noRippleClickable { selectedFrameIdx = i; coverUri = null }) {
                                    if (thumb != null) {
                                        androidx.compose.foundation.Image(bitmap = thumb.asImageBitmap(), contentDescription = "Frame ${i + 1}", modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                                    } else {
                                        Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.10f)))
                                    }
                                    if (sel) {
                                        Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(16.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                                            Icon(Icons.Filled.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(10.dp))
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(8.dp)).noRippleClickable { pickCover() }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                Text("Upload custom", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
                            }
                            if (coverUri != null || selectedFrameIdx >= 0) {
                                Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(8.dp)).noRippleClickable { coverUri = null; selectedFrameIdx = -1 }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                    Text("Remove", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
                                }
                            }
                            Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(Color(0xFF3B82F6)).noRippleClickable { sheet = null }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                Text("Done", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }
            ReelEditorSheet.TRIM -> {
                // Dismissing (swipe down / back / outside tap) cancels the
                // trim, like the web's Cancel: the snapshot is restored.
                ModalBottomSheet(onDismissRequest = {
                    trimSnapshot?.let { trimStartF = it.first; trimEndF = it.second }
                    trimSnapshot = null; trimPlaying = false; sheet = null
                }, containerColor = Color(0xFF0A0A0A), shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                Text("Trim video", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text("${formatTrimTime(trimStartF)} – ${formatTrimTime(trimEndF)}", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                            }
                            Text("Cancel", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, modifier = Modifier.noRippleClickable {
                                trimSnapshot?.let { trimStartF = it.first; trimEndF = it.second }
                                trimSnapshot = null; trimPlaying = false; sheet = null
                            })
                        }
                        Spacer(Modifier.height(12.dp))
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(36.dp).clip(CircleShape).background(Color.White).noRippleClickable {
                                val mp = previewPlayer
                                if (mp != null) {
                                    if (mp.isPlaying) {
                                        try { mp.pause() } catch (_: Exception) {}
                                        trimPlaying = false
                                    } else {
                                        try {
                                            val pos = mp.currentPosition / 1000f
                                            if (pos < trimStartF || pos >= trimEndF) mp.seekTo((trimStartF * 1000).toInt())
                                            mp.start()
                                        } catch (_: Exception) {}
                                        trimPlaying = true
                                    }
                                }
                            }, contentAlignment = Alignment.Center) {
                                Icon(if (trimPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (trimPlaying) "Pause" else "Play", tint = Color.Black, modifier = Modifier.size(16.dp))
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        // Timeline (web): 7 frame thumbnails, draggable
                        // emerald handles, playhead, selection overlay.
                        BoxWithConstraints(Modifier.fillMaxWidth().height(64.dp)) {
                            val wDp = maxWidth
                            val wPx = with(density) { wDp.toPx() }
                            val startX = if (durationF > 0f) wDp * (trimStartF / durationF) else 0.dp
                            val endX = if (durationF > 0f) wDp * (trimEndF / durationF) else wDp
                            val playX = if (durationF > 0f) wDp * (currentTime.coerceIn(trimStartF, trimEndF) / durationF) else 0.dp
                            Box(Modifier.fillMaxWidth().padding(top = 24.dp).height(32.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.05f)).pointerInput(Unit) {
                                detectTapGestures(onTap = { offset ->
                                    if (durationF <= 0f || wPx <= 0f) return@detectTapGestures
                                    val t = (offset.x / wPx * durationF).coerceIn(trimStartF, trimEndF)
                                    try { previewPlayer?.seekTo((t * 1000).toInt()); previewPlayer?.start() } catch (_: Exception) {}
                                    currentTime = t; trimPlaying = true
                                })
                            }) {
                                Row(Modifier.fillMaxSize()) {
                                    if (coverFrames.isNotEmpty()) {
                                        coverFrames.forEach { bmp ->
                                            androidx.compose.foundation.Image(bitmap = bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.weight(1f).fillMaxHeight(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                                        }
                                    } else {
                                        repeat(7) { Box(Modifier.weight(1f).fillMaxHeight().background(Color.White.copy(alpha = 0.10f))) }
                                    }
                                }
                            }
                            Box(Modifier.offset { IntOffset(startX.toPx().roundToInt(), 20.dp.toPx().roundToInt()) }.width(endX - startX).height(40.dp).clip(RoundedCornerShape(8.dp)).border(2.dp, Color(0xFF34D399), RoundedCornerShape(8.dp)).background(Color(0xFF34D399).copy(alpha = 0.10f)))
                            Box(Modifier.offset { IntOffset(playX.toPx().roundToInt(), 4.dp.toPx().roundToInt()) }.width(2.dp).height(56.dp).background(Color.White)) {
                                Box(Modifier.align(Alignment.TopCenter).offset(y = (-6).dp).size(12.dp).clip(CircleShape).background(Color.White))
                            }
                            // Start handle: drags trimStart (min 1s trim).
                            Box(Modifier.offset { IntOffset((startX - 12.dp).toPx().roundToInt(), 8.dp.toPx().roundToInt()) }.width(24.dp).height(64.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF34D399)).pointerInput(Unit) {
                                detectDragGestures(onDragStart = {}, onDrag = { _, dragAmount ->
                                    if (durationF <= 0f || wPx <= 0f) return@detectDragGestures
                                    val dxSec = dragAmount.x / wPx * durationF
                                    val newStart = (trimStartF + dxSec).coerceIn(0f, (trimEndF - 1f).coerceAtLeast(0f))
                                    trimStartF = newStart
                                    try { previewPlayer?.seekTo((newStart * 1000).toInt()) } catch (_: Exception) {}
                                    currentTime = newStart
                                })
                            }, contentAlignment = Alignment.Center) {
                                Box(Modifier.width(4.dp).height(32.dp).clip(RoundedCornerShape(50)).background(Color.White))
                            }
                            // End handle: drags trimEnd (min 1s trim).
                            Box(Modifier.offset { IntOffset((endX - 12.dp).toPx().roundToInt(), 8.dp.toPx().roundToInt()) }.width(24.dp).height(64.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF34D399)).pointerInput(Unit) {
                                detectDragGestures(onDragStart = {}, onDrag = { _, dragAmount ->
                                    if (durationF <= 0f || wPx <= 0f) return@detectDragGestures
                                    val dxSec = dragAmount.x / wPx * durationF
                                    val newEnd = (trimEndF + dxSec).coerceIn((trimStartF + 1f).coerceAtMost(durationF), durationF)
                                    trimEndF = newEnd
                                    try { previewPlayer?.seekTo((newEnd * 1000).toInt()) } catch (_: Exception) {}
                                    currentTime = newEnd
                                })
                            }, contentAlignment = Alignment.Center) {
                                Box(Modifier.width(4.dp).height(32.dp).clip(RoundedCornerShape(50)).background(Color.White))
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(8.dp)).noRippleClickable {
                                trimStartF = 0f; trimEndF = durationF
                                try { previewPlayer?.seekTo(0) } catch (_: Exception) {}
                                currentTime = 0f
                            }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                Text("Reset", color = Color.White, fontSize = 14.sp, maxLines = 1, softWrap = false)
                            }
                            Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(Color(0xFF10B981)).noRippleClickable {
                                if (trimEndF - trimStartF < 1f || durationF < 1f) { message = "Trimmed reel must be at least 1 second long."; return@noRippleClickable }
                                trimApplied = trimStartF > 0.05f || trimEndF < durationF - 0.05f
                                trimSnapshot = null; trimPlaying = false; sheet = null
                            }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                Text("Done", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }
            ReelEditorSheet.CROP -> {
                ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = Color(0xFF0A0A0A), shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
                        ReelEditorSheetHeader(title = "Crop", onDone = { sheet = null })
                        Spacer(Modifier.height(20.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            listOf<Triple<String?, String, String>>(Triple(null, "Original", "9:16"), Triple("square", "Square", "1:1"), Triple("portrait", "Portrait", "4:5")).forEach { (v, label, ratio) ->
                                val sel = crop == v
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                                    Box(Modifier.width(64.dp).aspectRatio(when (v) { "square" -> 1f; "portrait" -> 4f / 5f; else -> 9f / 16f }).clip(RoundedCornerShape(12.dp)).border(if (sel) 2.dp else 1.dp, if (sel) Color(0xFF34D399) else Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp)).background(if (sel) Color(0xFF34D399).copy(alpha = 0.10f) else Color.White.copy(alpha = 0.05f)).noRippleClickable {
                                        crop = v; sheet = null
                                        if (v == "square") message = "Square crop applied"
                                        if (v == "portrait") message = "4:5 crop applied"
                                    }, contentAlignment = Alignment.Center) {
                                        Text(ratio, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }
                }
            }
            null -> {}
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
                    DropdownMenuItem(
                        text = {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Public", modifier = Modifier.weight(1f))
                                if (visibility == "public") Icon(Icons.Filled.Check, contentDescription = null, tint = FrianzoBlue, modifier = Modifier.size(18.dp))
                            }
                        },
                        onClick = { visibility = "public"; audienceMenu = false },
                    )
                    DropdownMenuItem(
                        text = {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Only me", modifier = Modifier.weight(1f))
                                if (visibility == "private") Icon(Icons.Filled.Check, contentDescription = null, tint = FrianzoBlue, modifier = Modifier.size(18.dp))
                            }
                        },
                        onClick = { visibility = "private"; audienceMenu = false },
                    )
                }
            }
            Box {
                OutlinedButton(onClick = { commentsMenu = true }, shape = RoundedCornerShape(28.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Text("Comments: " + when (commentAudience) { "followers" -> "Followers"; "only_me" -> "Only me"; else -> "Everyone" }, fontSize = 13.sp, maxLines = 1, softWrap = false) }
                DropdownMenu(expanded = commentsMenu, onDismissRequest = { commentsMenu = false }) {
                    listOf("everyone" to "Everyone", "followers" to "Followers", "only_me" to "Only me").forEach { (v, label) ->
                        DropdownMenuItem(
                            text = {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(label, modifier = Modifier.weight(1f))
                                    if (commentAudience == v) Icon(Icons.Filled.Check, contentDescription = null, tint = FrianzoBlue, modifier = Modifier.size(18.dp))
                                }
                            },
                            onClick = { commentAudience = v; commentsMenu = false },
                        )
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
    // v5.18: selection mode (web: long-press 550ms selects) + delete
    // confirmation (web: swipe → confirm sheet; the app deletes via the
    // selection mode / the row Delete button, both confirmed).
    var selecting by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingDelete by remember { mutableStateOf<Conversation?>(null) }
    var showBulkDelete by remember { mutableStateOf(false) }
    var deleteProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun loadList(quiet: Boolean = false) {
        if (!quiet) loading = true
        scope.launch {
            try {
                val res = api2.conversations()
                if (res.success && res.data != null) list = res.data else error = res.error?.message
            } catch (e: Exception) { error = e.message }
            finally { if (!quiet) loading = false }
        }
    }
    LaunchedEffect(Unit) { loadList() }
    // v5.18: live refresh — a new message or a presence change reloads
    // the list quietly (web: socket message:new / presence:*). The
    // 5s poll is NOT added here; the list only reloads on open + live
    // events, like the web.
    DisposableEffect(Unit) {
        ChatSocket.init(PersistentCookieJar(context))
        ChatSocket.connect()
        val onNew = io.socket.emitter.Emitter.Listener { loadList(quiet = true) }
        val onPresence = io.socket.emitter.Emitter.Listener { loadList(quiet = true) }
        ChatSocket.on("message:new", onNew)
        ChatSocket.on("presence:online", onPresence)
        ChatSocket.on("presence:offline", onPresence)
        onDispose {
            ChatSocket.off("message:new", onNew)
            ChatSocket.off("presence:online", onPresence)
            ChatSocket.off("presence:offline", onPresence)
        }
    }
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
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp)) {
        // v5.18: selection header (web: Cancel / "N selected" / Delete).
        if (selecting) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { selecting = false; selectedIds = emptySet() }, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Cancel", maxLines = 1, softWrap = false) }
                    Text("${selectedIds.size} selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { if (selectedIds.isNotEmpty()) showBulkDelete = true }, enabled = selectedIds.isNotEmpty(), contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Delete", color = FrianzoBadgeRed, maxLines = 1, softWrap = false) }
                }
            }
        } else {
            item { Text("Chats", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        }
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
            // v5.18 (web parity): empty thread shows "Say hi 👋", not "".
            val preview = when {
                last == null -> "Say hi 👋"
                last.isDeleted -> "Message deleted"
                last.mediaType == "image" -> "Photo"
                last.mediaType == "voice" -> "Voice message"
                else -> last.content ?: ""
            }
            val youPrefix = last != null && last.senderId == myId && !last.isDeleted && preview.isNotBlank() && preview != "Say hi 👋"
            val hasUnread = c.unreadCount > 0
            val selected = selectedIds.contains(c.id)
            ListItem(
                headlineContent = {
                    // v5.18: verified badge + bold name when unread (web).
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(other?.displayName ?: other?.username ?: "Chat", style = MaterialTheme.typography.titleMedium, fontWeight = if (hasUnread) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (other?.isVerified == true) {
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Filled.CheckCircle, contentDescription = "Verified", tint = FrianzoBlue, modifier = Modifier.size(16.dp))
                        }
                    }
                },
                supportingContent = { Text((if (youPrefix) "You: " else "") + preview, style = MaterialTheme.typography.bodySmall, color = if (hasUnread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingContent = {
                    // v5.18: green online dot on the avatar (web) + a
                    // check circle while selecting.
                    if (selecting) {
                        Box(Modifier.size(48.dp).clip(CircleShape).background(if (selected) FrianzoBlue else Color.Transparent).border(if (selected) 0.dp else 1.dp, if (selected) Color.Transparent else FrianzoBorder, CircleShape), contentAlignment = Alignment.Center) {
                            if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    } else {
                        FrianzoAvatar(other?.profilePictureUrl, 48.dp, online = other?.isOnline == true && other?.showOnlineStatus != false)
                    }
                },
                trailingContent = {
                    Column(horizontalAlignment = Alignment.End) {
                        val whenText = notifTimeAgo(last?.createdAt ?: c.updatedAt)
                        // v5.18: unread time is blue (web).
                        if (whenText.isNotBlank()) Text(whenText, fontSize = 12.sp, color = if (hasUnread) FrianzoBlue else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
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
                            // v5.18: unread badge — 99+ cap; exactly 1 is a
                            // blue dot, not a number (web).
                            if (c.unreadCount > 1) Badge(containerColor = FrianzoBadgeRed) { Text(if (c.unreadCount > 99) "99+" else "${c.unreadCount}", maxLines = 1, softWrap = false) }
                            else if (c.unreadCount == 1) Box(Modifier.size(8.dp).clip(CircleShape).background(FrianzoBlue))
                            if (!selecting) IconButton(onClick = { pendingDelete = c }, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Delete, contentDescription = "Delete chat", modifier = Modifier.size(20.dp)) }
                        }
                    }
                },
                colors = ListItemDefaults.colors(containerColor = if (selected) FrianzoBlueTint else MaterialTheme.colorScheme.surface),
                // v5.18: long-press selects (web: 550ms); tap toggles while
                // selecting, opens the chat otherwise.
                modifier = Modifier.combinedClickable(
                    onClick = {
                        if (selecting) selectedIds = if (selected) selectedIds - c.id else selectedIds + c.id
                        else onOpen(c.id, other?.displayName ?: other?.username ?: "")
                    },
                    onLongClick = { if (!selecting) { selecting = true; selectedIds = setOf(c.id) } },
                ),
            )
            HorizontalDivider()
        }
        if (error != null) item {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(error!!, Modifier.weight(1f), color = FrianzoBadgeRed)
                Spacer(Modifier.width(8.dp))
                Button(onClick = { error = null; loadList() }) { Text("Retry", maxLines = 1, softWrap = false) }
            }
        }
        if (loading) item {
            // v5.12: skeleton rows on a bare first load; dots footer stays
            // for a load-more wait.
            if (shown.isEmpty()) {
                Column { repeat(5) { SkeletonRow() } }
            } else {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { DotsLoading() }
            }
        }
        if (!loading && shown.isEmpty() && error == null) item { Text(if (list.isEmpty()) "No chats yet" else "No conversations match", Modifier.padding(16.dp)) }
    }
    // v5.18: delete confirmation (web: "Delete conversation?" sheet).
    // The old code deleted immediately on tap — no confirmation.
    if (pendingDelete != null) {
        val target = pendingDelete!!
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete conversation?") },
            text = { Text("This will remove the chat from your list. This can't be undone.") },
            confirmButton = {
                Button(onClick = {
                    pendingDelete = null
                    scope.launch {
                        try {
                            if (api2.deleteConversation(target.id).success) {
                                list = list.filter { it.id != target.id }
                                Toast.makeText(context, "Conversation deleted", Toast.LENGTH_SHORT).show()
                            }
                        } catch (_: Exception) { Toast.makeText(context, "Couldn't delete conversation", Toast.LENGTH_SHORT).show() }
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = FrianzoBadgeRed)) { Text("Delete", maxLines = 1, softWrap = false) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
    // v5.18: bulk delete confirmation + progress (web: "Deleting n of N…").
    if (showBulkDelete) {
        AlertDialog(
            onDismissRequest = { showBulkDelete = false },
            title = { Text("Delete ${selectedIds.size} conversation(s)?") },
            text = { Text("This will remove them from your list. This can't be undone.") },
            confirmButton = {
                Button(onClick = {
                    showBulkDelete = false
                    val targets = selectedIds.toList()
                    scope.launch {
                        var done = 0
                        for (id in targets) {
                            deleteProgress = done to targets.size
                            try { if (api2.deleteConversation(id).success) list = list.filter { it.id != id } } catch (_: Exception) {}
                            done += 1
                            deleteProgress = done to targets.size
                        }
                        deleteProgress = null
                        selecting = false
                        selectedIds = emptySet()
                        Toast.makeText(context, "${targets.size} conversation(s) deleted", Toast.LENGTH_SHORT).show()
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = FrianzoBadgeRed)) { Text("Delete", maxLines = 1, softWrap = false) }
            },
            dismissButton = { TextButton(onClick = { showBulkDelete = false }) { Text("Cancel") } },
        )
    }
    deleteProgress?.let { (done, total) ->
        Text("Deleting $done of $total…", Modifier.fillMaxWidth().padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatThreadScreen(api2: FrianzoApi2, conversationId: String, title: String, myId: String?, onBack: () -> Unit = {}, onOpenProfile: ((String) -> Unit)? = null) {
    val context = LocalContext.current
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
    var showWallpaper by remember { mutableStateOf(false) }
    var showBlockConfirm by remember { mutableStateOf(false) }
    val chatPrefs = remember { context.getSharedPreferences("frianzo_chat", android.content.Context.MODE_PRIVATE) }
    var wallpaperName by remember(conversationId) { mutableStateOf(chatPrefs.getString("wallpaper_$conversationId", "Default") ?: "Default") }
    var timerLabel by remember { mutableStateOf("Off") }
    var showReport by remember { mutableStateOf(false) }
    var reportReason by remember { mutableStateOf("") }
    var reportDetails by rememberSaveable { mutableStateOf("") }
    var reportDone by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // v5.13: per-message actions (reply / react / forward / copy / edit /
    // delete / pin). All of them go over the socket — the backend has no
    // REST endpoints for them. Plain text sends stay on REST (v5.12).
    val clipboard = LocalClipboardManager.current
    var actionMessage by remember { mutableStateOf<ConversationMessage?>(null) }
    var replyTo by remember { mutableStateOf<ConversationMessage?>(null) }
    var editingMessage by remember { mutableStateOf<ConversationMessage?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ConversationMessage?>(null) }
    var deletingId by remember { mutableStateOf<String?>(null) }
    var actionMsg by remember { mutableStateOf<String?>(null) }
    fun showMsg(m: String) { actionMsg = m; scope.launch { delay(2500); if (actionMsg == m) actionMsg = null } }
    var forwardOpen by remember { mutableStateOf(false) }
    var forwardConversations by remember { mutableStateOf<List<Conversation>>(emptyList()) }
    var forwardLoaded by remember { mutableStateOf(false) }
    var forwardSelected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var forwardBusy by remember { mutableStateOf(false) }
    var pinnedCycle by remember { mutableStateOf(0) }
    // v5.18: optimistic sends — local bubbles ("local-…") show a clock
    // while sending, "Not sent · Tap to retry" when failed.
    var sendingIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var failedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    // v5.18: typing indicator + live presence (web: socket typing:* /
    // presence:*). The 15s REST presence poll stays as a fallback.
    var typingUser by remember { mutableStateOf(false) }
    var lastTypingEmit by remember { mutableStateOf(0L) }
    // v5.18: cursor pagination (web: "Load older messages", 30/page).
    var hasMore by remember { mutableStateOf(true) }
    var loadingOlder by remember { mutableStateOf(false) }
    // v5.18: Forward enters selection mode (web); the picker then sends
    // ALL selected messages to each selected chat.
    var selecting by remember { mutableStateOf(false) }
    var selectedMessages by remember { mutableStateOf<Set<String>>(emptySet()) }
    var forwardMessages by remember { mutableStateOf<List<ConversationMessage>>(emptyList()) }
    // v5.18: photo/voice send + fullscreen image viewer.
    var viewerUrl by remember { mutableStateOf<String?>(null) }
    var photoBusy by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var recordSeconds by remember { mutableStateOf(0) }
    var recorder by remember { mutableStateOf<android.media.MediaRecorder?>(null) }
    var voiceFile by remember { mutableStateOf<java.io.File?>(null) }
    // v5.18: verified badge in the thread header (web shows one).
    var otherIsVerified by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
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
                otherIsVerified = other?.isVerified == true
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
        // v5.13: full data-class equality — the poll must also pick up
        // reactions / replyTo / pinnedAt / editedAt / isForwarded changes,
        // not just id/content/isDeleted.
        // v5.18: optimistic local bubbles ("local-…") survive the 5s poll
        // until the real message arrives (matched by content + media);
        // otherwise the bubble would vanish mid-send.
        val locals = messages.filter { it.id?.startsWith("local-") == true }
        val keptLocals = locals.filter { local -> fresh.none { it.senderId == myId && it.content == local.content && it.mediaUrl == local.mediaUrl } }
        val droppedLocals = locals - keptLocals.toSet()
        if (droppedLocals.isNotEmpty()) {
            val droppedIds = droppedLocals.mapNotNull { it.id }.toSet()
            sendingIds = sendingIds - droppedIds
            failedIds = failedIds - droppedIds
        }
        val merged = keptLocals + fresh
        if (merged != messages) messages = merged
    }
    // v5.18: one new message (socket message:new / send ack) — prepends,
    // because the list is newest-first, and swaps out a matching local
    // bubble instead of duplicating it.
    fun addMessage(msg: ConversationMessage) {
        if (messages.any { it.id == msg.id }) return
        val matchedLocal = messages.firstOrNull { it.id?.startsWith("local-") == true && it.senderId == myId && it.content == msg.content && it.mediaUrl == msg.mediaUrl }
        if (matchedLocal != null) {
            sendingIds = sendingIds - matchedLocal.id!!
            failedIds = failedIds - matchedLocal.id!!
            messages = listOf(msg) + messages.filter { it.id != matchedLocal.id }
        } else {
            messages = listOf(msg) + messages
        }
    }
    // v5.18: "Load older messages" — cursor = oldest loaded message id
    // (web: 30/page, hasMore false when fewer than 30 come back).
    fun loadOlder() {
        if (loadingOlder || !hasMore) return
        val cursor = messages.lastOrNull()?.id ?: return
        loadingOlder = true
        scope.launch {
            try {
                val res = api2.messages(conversationId, cursor = cursor)
                if (res.success && res.data != null) {
                    val older = res.data.messages.reversed()
                    val known = messages.mapNotNull { it.id }.toSet()
                    messages = messages + older.filter { it.id !in known }
                    hasMore = res.data.messages.size >= 30
                }
            } catch (_: Exception) {}
            finally { loadingOlder = false }
        }
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
                if (res.success && res.data != null) { mergeMessages(res.data.messages.reversed()); otherLastReadAt = res.data.otherLastReadAt; otherLastDeliveredAt = res.data.otherLastDeliveredAt; loadError = null; hasMore = res.data.messages.size >= 30 }
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
    // v5.13: short label for a message (reply quote, pinned banner).
    // Declared here (before the Column) so every use below is after it.
    fun messageLabel(m: ConversationMessage): String = when {
        m.isDeleted -> "Message deleted"
        m.mediaType == "image" -> "Photo"
        m.mediaType == "voice" -> "Voice message"
        (m.content ?: "").isBlank() -> "Message"
        else -> m.content ?: ""
    }
    // v5.13: live socket for per-message actions + live updates. The 5s
    // REST poll above stays as the fallback. Socket callbacks run on a
    // background thread, so every state write goes through scope (main).
    fun parseSocketMessage(json: org.json.JSONObject): ConversationMessage? {
        return try { com.google.gson.Gson().fromJson(json.toString(), ConversationMessage::class.java) } catch (_: Exception) { null }
    }
    DisposableEffect(conversationId) {
        ChatSocket.init(PersistentCookieJar(context))
        ChatSocket.connect()
        ChatSocket.emit("conversation:join", org.json.JSONObject().put("conversationId", conversationId))
        ChatSocket.emit("conversation:read", org.json.JSONObject().put("conversationId", conversationId))
        val onNew = io.socket.emitter.Emitter.Listener { args ->
            val json = args.firstOrNull() as? org.json.JSONObject ?: return@Listener
            if (json.optString("conversationId") != conversationId) return@Listener
            val msg = parseSocketMessage(json) ?: return@Listener
            // v5.18: prepend — the list is newest-first (reversed), so
            // appending put a new message at the visual top until the
            // next poll fixed it.
            scope.launch { addMessage(msg) }
        }
        val onEdited = io.socket.emitter.Emitter.Listener { args ->
            val json = args.firstOrNull() as? org.json.JSONObject ?: return@Listener
            val msg = parseSocketMessage(json) ?: return@Listener
            scope.launch { messages = messages.map { if (it.id == msg.id) msg else it } }
        }
        val onReaction = io.socket.emitter.Emitter.Listener { args ->
            val json = args.firstOrNull() as? org.json.JSONObject ?: return@Listener
            val messageId = json.optString("messageId")
            val reactionsJson = json.optJSONArray("reactions") ?: return@Listener
            val reactions = try {
                val type = object : com.google.gson.reflect.TypeToken<List<MessageReaction>>() {}.type
                com.google.gson.Gson().fromJson<List<MessageReaction>>(reactionsJson.toString(), type) ?: emptyList()
            } catch (_: Exception) { emptyList() }
            scope.launch { messages = messages.map { if (it.id == messageId) it.copy(reactions = reactions) else it } }
        }
        val onPinned = io.socket.emitter.Emitter.Listener { args ->
            val json = args.firstOrNull() as? org.json.JSONObject ?: return@Listener
            val messageId = json.optString("messageId")
            val pinnedAt = if (json.isNull("pinnedAt")) null else json.optString("pinnedAt")
            val pinnedById = if (json.isNull("pinnedById")) null else json.optString("pinnedById")
            scope.launch { messages = messages.map { if (it.id == messageId) it.copy(pinnedAt = pinnedAt, pinnedById = pinnedById) else it } }
        }
        val onDeleted = io.socket.emitter.Emitter.Listener { args ->
            val json = args.firstOrNull() as? org.json.JSONObject ?: return@Listener
            val messageId = json.optString("messageId")
            scope.launch { messages = messages.map { if (it.id == messageId) it.copy(isDeleted = true, content = "", mediaUrl = null, mediaType = null, voiceDuration = null) else it } }
        }
        ChatSocket.on("message:new", onNew)
        ChatSocket.on("message:edited", onEdited)
        ChatSocket.on("message:reaction", onReaction)
        ChatSocket.on("message:pinned", onPinned)
        ChatSocket.on("message:deleted", onDeleted)
        // v5.18: typing indicator + live presence (web: socket typing:*
        // / presence:*). Presence updates only when the event is about
        // the other user in this thread.
        val onTypingStart = io.socket.emitter.Emitter.Listener { args ->
            val json = args.firstOrNull() as? org.json.JSONObject ?: return@Listener
            if (json.optString("conversationId") != conversationId) return@Listener
            if (json.optString("userId") == myId) return@Listener
            scope.launch { typingUser = true }
        }
        val onTypingStop = io.socket.emitter.Emitter.Listener { args ->
            val json = args.firstOrNull() as? org.json.JSONObject ?: return@Listener
            if (json.optString("conversationId") != conversationId) return@Listener
            if (json.optString("userId") == myId) return@Listener
            scope.launch { typingUser = false }
        }
        val onPresenceOnline = io.socket.emitter.Emitter.Listener { args ->
            val json = args.firstOrNull() as? org.json.JSONObject ?: return@Listener
            if (json.optString("userId") == otherUserId) scope.launch { otherIsOnline = true }
        }
        val onPresenceOffline = io.socket.emitter.Emitter.Listener { args ->
            val json = args.firstOrNull() as? org.json.JSONObject ?: return@Listener
            if (json.optString("userId") == otherUserId) scope.launch { otherIsOnline = false; typingUser = false }
        }
        ChatSocket.on("typing:start", onTypingStart)
        ChatSocket.on("typing:stop", onTypingStop)
        ChatSocket.on("presence:online", onPresenceOnline)
        ChatSocket.on("presence:offline", onPresenceOffline)
        onDispose {
            ChatSocket.off("message:new", onNew)
            ChatSocket.off("message:edited", onEdited)
            ChatSocket.off("message:reaction", onReaction)
            ChatSocket.off("message:pinned", onPinned)
            ChatSocket.off("message:deleted", onDeleted)
            ChatSocket.off("typing:start", onTypingStart)
            ChatSocket.off("typing:stop", onTypingStop)
            ChatSocket.off("presence:online", onPresenceOnline)
            ChatSocket.off("presence:offline", onPresenceOffline)
        }
    }
    // v5.13: forward picker loads the conversation list when opened.
    LaunchedEffect(forwardOpen) {
        if (forwardOpen) {
            forwardLoaded = false
            try {
                val res = api2.conversations()
                if (res.success && res.data != null) forwardConversations = res.data.filter { it.id != conversationId && it.otherUser != null }
            } catch (_: Exception) {}
            forwardLoaded = true
        }
    }
    val presenceText = when {
        // v5.18: the other side typing shows in the status line (web).
        typingUser -> "Typing…"
        otherIsOnline -> "Active now"
        otherLastActiveAt != null -> "Last seen ${lastSeenText(otherLastActiveAt)}"
        else -> ""
    }
    // v5.18: typing emit — typing:start on change (throttled to one per
    // 2s; the server rate-limits at 10/10s), typing:stop 1500ms after the
    // last keystroke (web timing).
    fun emitTypingStart() {
        val now = System.currentTimeMillis()
        if (now - lastTypingEmit < 2000) return
        lastTypingEmit = now
        ChatSocket.emit("typing:start", org.json.JSONObject().put("conversationId", conversationId))
    }
    fun emitTypingStop() {
        ChatSocket.emit("typing:stop", org.json.JSONObject().put("conversationId", conversationId))
    }
    LaunchedEffect(draft) {
        if (draft.isNotBlank()) {
            emitTypingStart()
            kotlinx.coroutines.delay(1500)
            emitTypingStop()
        }
    }
    // v5.18: voice recording timer (1s ticks while recording).
    LaunchedEffect(recording) {
        while (recording) {
            kotlinx.coroutines.delay(1000)
            recordSeconds += 1
        }
    }
    // v5.15: chat wallpaper (local-only) is the thread background.
    val wallpaperColor = ChatWallpapers.firstOrNull { it.first == wallpaperName }?.second ?: Color(0xFFF8FAFC)
    Column(Modifier.fillMaxSize().background(wallpaperColor)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Back", maxLines = 1, softWrap = false) }
            FrianzoAvatar(otherAvatar, 40.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                // v5.18: verified badge next to the name (web).
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (otherIsVerified) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Filled.CheckCircle, contentDescription = "Verified", tint = FrianzoBlue, modifier = Modifier.size(16.dp))
                    }
                }
                if (presenceText.isNotBlank()) Text(presenceText, fontSize = 12.sp, color = if (typingUser) FrianzoBlue else if (otherIsOnline) FrianzoSuccess else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
            }
            if (timerLabel != "Off") Text(timerLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Chat options") }
                // v5.15 (web order): View profile, Block/Unblock,
                // Report, Wallpaper, Disappearing messages. NO Delete
                // chat row (it deleted immediately, no confirmation;
                // the web has no such item).
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (otherUsername != null) DropdownMenuItem(
                        text = { Text("View profile") },
                        leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) },
                        onClick = { menuOpen = false; onOpenProfile?.invoke(otherUsername!!) },
                    )
                    if (otherUserId != null) DropdownMenuItem(
                        text = { Text(if (isBlocked) "Unblock" else "Block", color = if (isBlocked) MaterialTheme.colorScheme.onSurface else FrianzoBadgeRed) },
                        leadingIcon = { Icon(Icons.Filled.Block, contentDescription = null, tint = if (isBlocked) MaterialTheme.colorScheme.onSurfaceVariant else FrianzoBadgeRed, modifier = Modifier.size(18.dp)) },
                        onClick = {
                            menuOpen = false
                            if (isBlocked) {
                                scope.launch {
                                    try { if (api2.unblockUser(otherUserId!!).success) isBlocked = false } catch (_: Exception) {}
                                }
                            } else showBlockConfirm = true
                        },
                    )
                    if (otherUserId != null) DropdownMenuItem(
                        text = { Text("Report user") },
                        leadingIcon = { Icon(Icons.Filled.Flag, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) },
                        onClick = { menuOpen = false; reportDone = false; reportReason = ""; showReport = true },
                    )
                    DropdownMenuItem(
                        text = { Text("Wallpaper") },
                        leadingIcon = { Icon(Icons.Filled.Wallpaper, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) },
                        onClick = { menuOpen = false; showWallpaper = true },
                    )
                    DropdownMenuItem(
                        text = {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Disappearing messages", modifier = Modifier.weight(1f))
                                if (timerLabel != "Off") {
                                    Spacer(Modifier.width(8.dp))
                                    Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFFFFFBEB)).padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.AccessTime, contentDescription = null, tint = Color(0xFFD97706), modifier = Modifier.size(12.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("On", fontSize = 12.sp, color = Color(0xFFD97706), fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                                    }
                                }
                            }
                        },
                        leadingIcon = { Icon(Icons.Filled.Timer, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) },
                        onClick = { menuOpen = false; showTimer = true },
                    )
                }
            }
        }
        // v5.18: offline banner (web: "You're offline — messages will
        // send when you reconnect."). The v5.12 Outbox still queues sends.
        if (!NetworkMonitor.isOnline) {
            Box(Modifier.fillMaxWidth().background(Color(0xFFFEF3C7)).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("You're offline — messages will send when you reconnect.", fontSize = 13.sp, color = Color(0xFF92400E))
            }
        }
        // v5.18: selection header (web: Cancel / "N selected" / Forward).
        if (selecting) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { selecting = false; selectedMessages = emptySet() }, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Cancel", maxLines = 1, softWrap = false) }
                Text("${selectedMessages.size} selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = {
                    val picked = messages.filter { it.id in selectedMessages && !it.isDeleted }
                    if (picked.isNotEmpty()) { forwardMessages = picked; forwardSelected = emptySet(); forwardConversations = emptyList(); forwardOpen = true }
                }, enabled = selectedMessages.isNotEmpty()) { Icon(Icons.Filled.Forward, contentDescription = "Forward selected") }
            }
            HorizontalDivider(color = FrianzoBorder)
        }
        // v5.13: pinned banner (web: sticky at the top of the thread).
        // Tap scrolls to the pinned message and cycles when several.
        val pinnedMessages = messages.filter { it.pinnedAt != null && !it.isDeleted }.sortedByDescending { it.pinnedAt }
        if (pinnedMessages.isNotEmpty()) {
            val shownPinned = pinnedMessages[pinnedCycle % pinnedMessages.size]
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFFEF3C7)).noRippleClickable {
                pinnedCycle = (pinnedCycle + 1) % pinnedMessages.size
                val idx = messages.indexOfFirst { it.id == shownPinned.id }
                if (idx >= 0) scope.launch { try { listState.scrollToItem(idx) } catch (_: Exception) {} }
            }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("📌", fontSize = 16.sp, maxLines = 1, softWrap = false)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Pinned message" + if (pinnedMessages.size > 1) " ${pinnedCycle % pinnedMessages.size + 1}/${pinnedMessages.size}" else "", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF92400E), maxLines = 1, softWrap = false)
                    Text(messageLabel(shownPinned), fontSize = 12.sp, color = Color(0xFF92400E), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        // v3.4: big avatar + name + "View profile" pill header.
        // v5.18: avatar is 80dp (web), was 72dp.
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            FrianzoAvatar(otherAvatar, 80.dp)
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
            // v5.15 (web): disappearing time is a bottom sheet — the
            // current option is amber-50 + amber-700 with a 16dp check;
            // tapping applies immediately; no Cancel, scrim closes.
            ModalBottomSheet(
                onDismissRequest = { showTimer = false },
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Disappearing messages", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center, maxLines = 1, softWrap = false)
                    Spacer(Modifier.height(4.dp))
                    Text("New messages disappear after the selected time.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    listOf(null to "Off", 3600 to "1 hour", 86400 to "24 hours", 604800 to "7 days", 7776000 to "90 days").forEach { (secs, label) ->
                        // B-B2: Off sends an explicit {"duration":null} —
                        // the backend accepts null = Off (0 would be
                        // rejected 400), and default Gson drops nulls
                        // from a Map, so the body is built as raw JSON.
                        // The optimistic UI reverts on failure instead of
                        // lying about the timer.
                        val selected = disappearingDuration == secs
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (selected) Color(0xFFFFFBEB) else Color.Transparent).clickable { showTimer = false; val prevLabel = timerLabel; val prevDuration = disappearingDuration; timerLabel = label; disappearingDuration = secs; scope.launch { try { val body = (if (secs == null) "{\"duration\":null}" else "{\"duration\":$secs}").toRequestBody("application/json".toMediaTypeOrNull()); val r = api2.setDisappearing(conversationId, body); if (!r.success) { timerLabel = prevLabel; disappearingDuration = prevDuration; sendError = r.error?.message ?: "Couldn't change disappearing timer" } } catch (e: Exception) { timerLabel = prevLabel; disappearingDuration = prevDuration; sendError = e.message } } }.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(label, fontSize = 15.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = if (selected) Color(0xFFB45309) else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f), maxLines = 1, softWrap = false)
                            if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = Color(0xFFB45309), modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
        if (showReport) {
            AlertDialog(onDismissRequest = { showReport = false }, title = { Text("Report user") },
                text = {
                    Column {
                        Text("Why are you reporting this user?", fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        listOf("spam" to "Spam", "harassment" to "Harassment", "hate_speech" to "Hate speech", "violence" to "Violence", "nudity" to "Nudity", "misinformation" to "Misinformation", "other" to "Other").forEach { (v, label) ->
                            Row(Modifier.fillMaxWidth().clickable { reportReason = v }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(label, fontSize = 15.sp, fontWeight = if (reportReason == v) FontWeight.SemiBold else FontWeight.Normal, color = if (reportReason == v) FrianzoBlue else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f), maxLines = 1, softWrap = false)
                                if (reportReason == v) Icon(Icons.Filled.Check, contentDescription = null, tint = FrianzoBlue, modifier = Modifier.size(18.dp))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = reportDetails, onValueChange = { if (it.length <= 500) reportDetails = it }, label = { Text("Details (optional)") }, modifier = Modifier.fillMaxWidth())
                        if (reportDone) { Spacer(Modifier.height(8.dp)); Text("Report sent. Thanks for letting us know.", fontSize = 13.sp, color = FrianzoSuccess) }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val uid = otherUserId
                            if (uid != null) scope.launch {
                                try {
                                    val body = mutableMapOf("reason" to reportReason)
                                    if (reportDetails.isNotBlank()) body["details"] = reportDetails
                                    if (api2.reportUser(uid, body).success) reportDone = true else sendError = "Could not send report"
                                } catch (e: Exception) { sendError = e.message }
                            }
                        },
                        enabled = reportReason.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = FrianzoBadgeRed, contentColor = Color.White),
                        shape = RoundedCornerShape(28.dp),
                    ) { Text("Submit", maxLines = 1, softWrap = false) }
                },
                dismissButton = { TextButton(onClick = { showReport = false }) { Text("Cancel") } })
        }
        if (showBlockConfirm) {
            BlockConfirmSheet(
                name = title,
                onBlock = {
                    scope.launch {
                        try { if (otherUserId != null && api2.blockUser(otherUserId!!).success) isBlocked = true } catch (_: Exception) {}
                    }
                },
                onDismiss = { showBlockConfirm = false },
            )
        }
        if (showWallpaper) {
            ChatWallpaperSheet(
                selected = wallpaperName,
                onPick = { name ->
                    wallpaperName = name
                    chatPrefs.edit().putString("wallpaper_$conversationId", name).apply()
                },
                onDismiss = { showWallpaper = false },
            )
        }
        // v5.13: delete one message (web: no undo anywhere). mode "me"
        // removes it locally only; "everyone" applies the tombstone.
        fun deleteMessage(target: ConversationMessage, mode: String) {
            val id = target.id ?: return
            deletingId = id
            val obj = org.json.JSONObject().put("messageId", id).put("mode", mode)
            ChatSocket.emitWithAck("message:delete", obj) { ack ->
                deletingId = null
                if (ack?.optBoolean("success") == true) {
                    if (mode == "me") {
                        messages = messages.filter { it.id != id }
                    } else {
                        messages = messages.map { if (it.id == id) it.copy(isDeleted = true, content = "", mediaUrl = null, mediaType = null, voiceDuration = null) else it }
                    }
                } else sendError = "Could not delete message"
            }
        }
        // v5.13: forward the picked message(s) to the selected chats,
        // sequentially (web: sendForwardedMessages). The web quirk is
        // kept: isForwarded is true only when forwarding someone else's
        // message; no replyToId is carried. v5.18: forwards ALL selected
        // messages (web selection mode), not just one.
        fun sendForward() {
            val targets = forwardMessages
            if (targets.isEmpty() || forwardSelected.isEmpty() || forwardBusy) return
            forwardBusy = true
            val convIds = forwardSelected.toList()
            scope.launch {
                var failed = false
                for (target in targets) {
                    for (targetConvId in convIds) {
                        val obj = org.json.JSONObject()
                        obj.put("conversationId", targetConvId)
                        obj.put("content", target.content ?: "")
                        if (target.mediaUrl != null) obj.put("mediaUrl", target.mediaUrl)
                        if (target.mediaType != null) obj.put("mediaType", target.mediaType)
                        if (target.voiceDuration != null) obj.put("voiceDuration", target.voiceDuration)
                        obj.put("isForwarded", target.senderId != myId)
                        val ack = ChatSocket.emitAck("message:send", obj)
                        if (ack?.optBoolean("success") != true) { failed = true; break }
                    }
                    if (failed) break
                }
                forwardBusy = false
                forwardOpen = false
                forwardMessages = emptyList()
                selecting = false
                selectedMessages = emptySet()
                if (failed) showMsg("Could not forward message") else showMsg("Message forwarded")
            }
        }
        // v5.18: optimistic send (web). A local bubble appears at once
        // with a clock; the socket ack swaps in the real message (the
        // server also broadcasts message:new, which addMessage dedups).
        // Ack timeout (15s) or a server rejection marks the bubble failed
        // — tap to retry. Offline queues it in the v5.12 Outbox instead
        // (plain text replays over REST, media/reply over the socket).
        fun sendMessageNow(text: String, reply: ConversationMessage?, mediaUrl: String?, mediaType: String?, voiceDuration: Int?) {
            val localId = "local-${java.util.UUID.randomUUID()}"
            val nowIso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())
            val local = ConversationMessage(
                id = localId, content = text, senderId = myId, createdAt = nowIso,
                mediaUrl = mediaUrl, mediaType = mediaType, voiceDuration = voiceDuration,
                replyToId = reply?.id,
                replyTo = reply?.let { ReplyRef(id = it.id, content = it.content, mediaType = it.mediaType, sender = ReplyRefSender(displayName = if (it.senderId == myId) "You" else title, username = null)) },
            )
            scope.launch {
                messages = listOf(local) + messages
                sendingIds = sendingIds + localId
                val obj = org.json.JSONObject()
                obj.put("conversationId", conversationId)
                obj.put("content", text)
                if (reply != null) obj.put("replyToId", reply.id)
                if (mediaUrl != null) obj.put("mediaUrl", mediaUrl)
                if (mediaType != null) obj.put("mediaType", mediaType)
                if (voiceDuration != null) obj.put("voiceDuration", voiceDuration)
                val ack = ChatSocket.emitAck("message:send", obj)
                if (ack?.optBoolean("success") == true) {
                    val data = ack.optJSONObject("data")
                    val sent = data?.let { parseSocketMessage(it) }
                    if (sent != null) addMessage(sent)
                    else { messages = messages.filter { it.id != localId }; sendingIds = sendingIds - localId; load() }
                } else if (!NetworkMonitor.isOnlineNow(context)) {
                    val fields = mutableMapOf("text" to text)
                    if (reply?.id != null) fields["replyToId"] = reply.id!!
                    if (mediaUrl != null) fields["mediaUrl"] = mediaUrl
                    if (mediaType != null) fields["mediaType"] = mediaType
                    if (voiceDuration != null) fields["voiceDuration"] = voiceDuration.toString()
                    Outbox.enqueueJson(context, "MESSAGE", conversationId, fields)
                    messages = messages.filter { it.id != localId }
                    sendingIds = sendingIds - localId
                    sendError = "Waiting for connection…"
                } else {
                    sendingIds = sendingIds - localId
                    failedIds = failedIds + localId
                    sendError = when (ack?.optString("error")) {
                        "MESSAGING_RESTRICTED", "LINK_SPAM_RESTRICTED" -> "Messaging is restricted right now — try again later."
                        "BLOCKED" -> "You can't message this user."
                        "MESSAGES_DISABLED" -> "This user doesn't accept messages."
                        "MESSAGES_RESTRICTED" -> "This user only accepts messages from followers."
                        "RATE_LIMITED" -> "You're sending too fast — slow down."
                        else -> "Could not send message"
                    }
                }
            }
        }
        // v5.18: retry a failed bubble (web: "Not sent · Tap to retry").
        fun retrySend(local: ConversationMessage) {
            val id = local.id ?: return
            failedIds = failedIds - id
            messages = messages.filter { it.id != id }
            val reply = local.replyToId?.let { rid -> messages.firstOrNull { it.id == rid } }
            sendMessageNow(local.content ?: "", reply, local.mediaUrl, local.mediaType, local.voiceDuration)
        }
        // v5.18: upload a chat file — POST /api/upload/chat (field
        // "file"), returns the R2 URL + mediaType. Suspend; callers run
        // it inside scope.launch.
        suspend fun uploadChatFile(bytes: ByteArray, fileName: String, mime: String): Pair<String, String>? {
            val part = okhttp3.MultipartBody.Part.createFormData("file", fileName, bytes.toRequestBody(mime.toMediaTypeOrNull()))
            val r = api2.uploadChatMedia(part)
            val url = r.data?.url
            return if (r.success && url != null) url to (r.data?.mediaType ?: "image") else null
        }
        // v5.18: photo send — gallery pick (10 MB limit, web) → upload →
        // socket send with mediaUrl/mediaType.
        val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                photoBusy = true
                try {
                    val size = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1
                    if (size > 10 * 1024 * 1024) { sendError = "Photo must be under 10 MB"; return@launch }
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes == null) { sendError = "Couldn't read photo"; return@launch }
                    val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                    val uploaded = uploadChatFile(bytes, "photo.jpg", mime)
                    if (uploaded != null) sendMessageNow("", null, uploaded.first, uploaded.second, null)
                    else sendError = "Couldn't upload photo"
                } catch (e: Exception) { sendError = e.message ?: "Couldn't upload photo" }
                finally { photoBusy = false }
            }
        }
        val photoGate = rememberPermissionGate(galleryPermissions(), onGranted = { photoPicker.launch("image/*") })
        // v5.18: voice record — mic permission, MediaRecorder AAC/MP4,
        // upload → socket send with mediaType "voice" + duration.
        fun startRecording() {
            try {
                val file = java.io.File(context.cacheDir, "voice-${System.currentTimeMillis()}.m4a")
                val rec = android.media.MediaRecorder()
                rec.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                rec.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                rec.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                rec.setOutputFile(file.absolutePath)
                rec.prepare()
                rec.start()
                recorder = rec
                voiceFile = file
                recordSeconds = 0
                recording = true
            } catch (e: Exception) { sendError = e.message ?: "Couldn't start recording" }
        }
        fun stopRecording(send: Boolean) {
            val rec = recorder
            val file = voiceFile
            recording = false
            recorder = null
            voiceFile = null
            try { rec?.stop() } catch (_: Exception) {}
            try { rec?.release() } catch (_: Exception) {}
            if (!send || file == null || !file.exists()) { file?.delete(); return }
            val seconds = recordSeconds
            scope.launch {
                try {
                    val uploaded = uploadChatFile(file.readBytes(), "voice.m4a", "audio/mp4")
                    if (uploaded != null) sendMessageNow("", null, uploaded.first, "voice", seconds) else sendError = "Couldn't upload voice message"
                } catch (e: Exception) { sendError = e.message ?: "Couldn't upload voice message" }
                finally { file.delete() }
            }
        }
        val micGate = rememberPermissionGate(arrayOf(android.Manifest.permission.RECORD_AUDIO), onGranted = { startRecording() })
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
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), reverseLayout = true) {
            itemsIndexed(messages, key = { _, m -> m.id ?: "" }) { index, m ->
                val mine = m.senderId == myId
                val isDeleting = deletingId == m.id
                // v5.13: deleted and id-less rows cannot open the sheet.
                val canAct = !m.isDeleted && m.id != null
                // v5.18: optimistic state (local bubbles only).
                val isLocal = m.id?.startsWith("local-") == true
                val isSending = isLocal && sendingIds.contains(m.id)
                val isFailed = isLocal && failedIds.contains(m.id)
                val isSelected = m.id != null && selectedMessages.contains(m.id)
                // v5.18: date separator above the newest message of a day
                // (list is newest-first, so the neighbour is index-1).
                val showDate = index == 0 || chatDayKey(messages.getOrNull(index - 1)?.createdAt) != chatDayKey(m.createdAt)
                // v5.18: same-sender consecutive messages sit tighter (web).
                val grouped = index > 0 && messages[index - 1].senderId == m.senderId && chatDayKey(messages[index - 1].createdAt) == chatDayKey(m.createdAt)
                Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = if (grouped) 1.dp else 4.dp)) {
                    if (showDate) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
                                Text(chatDateLabel(m.createdAt), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), maxLines = 1, softWrap = false)
                            }
                        }
                    }
                Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
                    // v5.18: bubble max-width 75% (web) — the Column wraps
                    // content up to 75%, so short messages stay small.
                    Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start, modifier = Modifier.fillMaxWidth(0.75f)) {
                    Surface(
                        color = if (isSelected) FrianzoBlueTint else if (mine) FrianzoBlue else MaterialTheme.colorScheme.surface,
                        // v5.18: tails (web) — one corner square on the
                        // sender's side.
                        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = if (mine) 16.dp else 4.dp, bottomEnd = if (mine) 4.dp else 16.dp),
                        border = if (m.pinnedAt != null && !m.isDeleted) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF59E0B)) else null,
                        modifier = Modifier.graphicsLayer { alpha = if (isDeleting) 0.6f else 1f }.pointerInput(m.id, selecting) {
                            detectTapGestures(
                                onLongPress = { if (canAct && !selecting) actionMessage = m },
                                // v5.18: tap toggles selection while selecting.
                                onTap = { if (selecting && canAct && m.id != null) selectedMessages = if (isSelected) selectedMessages - m.id!! else selectedMessages + m.id!! },
                            )
                        }.pointerInput(m.id) {
                            // v5.15: swipe right on a bubble replies (web) —
                            // a horizontal drag past 55dp triggers the same
                            // reply flow as the Reply row. Long-press is
                            // untouched (separate detector above).
                            var totalDx = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { totalDx = 0f },
                                onHorizontalDrag = { _, dragAmount -> totalDx += dragAmount },
                                onDragEnd = { if (totalDx > 55.dp.toPx() && canAct && !selecting) { replyTo = m; editingMessage = null } },
                            )
                        }
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            // v5.13: "Forwarded" label (web: italic 11px).
                            if (m.isForwarded) Text("Forwarded", fontSize = 11.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = if (mine) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                            // v5.13: reply quote (web: left border + sender + text).
                            val quote = m.replyTo
                            if (quote != null) {
                                Row(Modifier.widthIn(max = 260.dp).clip(RoundedCornerShape(6.dp)).background(if (mine) Color.White.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.width(3.dp).height(28.dp).background(if (mine) Color.White else FrianzoBlue))
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(quote.sender?.displayName ?: quote.sender?.username ?: "User", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (mine) Color.White else FrianzoBlue, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(if (quote.deletedAt != null) "Message deleted" else (quote.content?.takeIf { it.isNotBlank() } ?: when (quote.mediaType) { "image" -> "Photo"; "voice" -> "Voice message"; else -> "Message" }), fontSize = 12.sp, color = if (mine) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                            }
                            // v5.18: real photo / voice rendering (web). A
                            // photo shows the image (tap → fullscreen +
                            // Download); a voice message shows a player.
                            // Text-only fallback keeps the old labels.
                            if (!m.isDeleted && m.mediaType == "image" && m.mediaUrl != null) {
                                AsyncImage(
                                    model = m.mediaUrl,
                                    contentDescription = "Photo",
                                    modifier = Modifier.fillMaxWidth().heightIn(max = 288.dp).clip(RoundedCornerShape(12.dp)).clickable { viewerUrl = m.mediaUrl },
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                )
                                if ((m.content ?: "").isNotBlank()) {
                                    Spacer(Modifier.height(8.dp))
                                    Text(m.content!!, color = if (mine) Color.White else MaterialTheme.colorScheme.onSurface)
                                }
                            } else if (!m.isDeleted && m.mediaType == "voice" && m.mediaUrl != null) {
                                VoiceMessagePlayer(url = m.mediaUrl, durationSec = m.voiceDuration, mine = mine)
                                if ((m.content ?: "").isNotBlank()) {
                                    Spacer(Modifier.height(8.dp))
                                    Text(m.content!!, color = if (mine) Color.White else MaterialTheme.colorScheme.onSurface)
                                }
                            } else {
                                val mediaLabel = when {
                                    m.isDeleted -> "Message deleted"
                                    m.mediaType == "image" -> "Photo"
                                    m.mediaType == "voice" -> "Voice message" + if ((m.voiceDuration ?: 0) > 0) " (${m.voiceDuration}s)" else ""
                                    else -> null
                                }
                                val shown = if ((m.content ?: "").isBlank()) mediaLabel ?: "" else m.content ?: ""
                                Text(shown, color = if (mine) Color.White else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                    // v5.13: reactions — deduplicated emoji pills (web: no
                    // counts, no who-reacted). Display only; change or
                    // remove a reaction from the long-press sheet.
                    val reactionEmojis = m.reactions.orEmpty().mapNotNull { it.emoji?.takeIf { e -> e.isNotBlank() } }.distinct()
                    if (reactionEmojis.isNotEmpty()) {
                        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            reactionEmojis.forEach { emoji ->
                                Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, FrianzoBorder)) {
                                    Text(emoji, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                }
                            }
                        }
                    }
                    if (m.pinnedAt != null && !m.isDeleted) {
                        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("📌", fontSize = 11.sp, maxLines = 1, softWrap = false)
                            Spacer(Modifier.width(2.dp))
                            Text("Pinned", fontSize = 11.sp, color = Color(0xFFF59E0B), maxLines = 1, softWrap = false)
                        }
                    }
                    if (isDeleting) Text("Deleting…", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp, end = 4.dp), maxLines = 1, softWrap = false)
                    // v5.18: time + ticks in one row (web: "09:41 PM ✓✓").
                    // "edited ·" prefixes the time; a clock icon marks a
                    // disappearing message; a sending bubble shows 🕐.
                    Row(Modifier.padding(top = 2.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (m.editedAt != null) {
                            Text("edited · ", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                        }
                        val timeText = chatTime(m.createdAt)
                        if (timeText.isNotBlank()) Text(timeText, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                        if (m.expiresAt != null && !m.isDeleted) {
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Filled.AccessTime, contentDescription = "Disappears", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(12.dp))
                        }
                        if (mine) {
                            Spacer(Modifier.width(4.dp))
                            if (isSending) {
                                Text("🕐", fontSize = 11.sp, maxLines = 1, softWrap = false)
                            } else {
                                val (tick, tickRead) = tickFor(m)
                                Text(tick, fontSize = 11.sp, color = if (tickRead) FrianzoBlue else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                    // v5.18: failed bubble — "Not sent · Tap to retry" (web).
                    if (isFailed) {
                        Text("Not sent · Tap to retry", fontSize = 11.sp, color = FrianzoBadgeRed, modifier = Modifier.padding(top = 2.dp, end = 4.dp).clickable { retrySend(m) }, maxLines = 1, softWrap = false)
                    }
                    } // Column
                }
                } // date Column
            }
            // v5.18: "Load older messages" sits at the visual top (the
            // last item in a reverseLayout list) when more pages exist.
            if (hasMore && messages.isNotEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                        OutlinedButton(onClick = { loadOlder() }, enabled = !loadingOlder, shape = RoundedCornerShape(28.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                            Text(if (loadingOlder) "Loading…" else "Load older messages", maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
            // v5.18: empty state (web: "No messages yet").
            if (messages.isEmpty() && loadError == null) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("No messages yet", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        // v5.15 (web): no drag handle, no "Message options" title.
        // Reaction strip is 7 emoji in 40dp grey circles (others'
        // messages only); rows are Reply → Copy → Edit → Pin/Unpin
        // → Forward → Delete, with a grey Cancel pill at the bottom.
        if (actionMessage != null) {
            val target = actionMessage!!
            val targetMine = target.senderId == myId
            val hasText = (target.content ?: "").isNotBlank()
            ModalBottomSheet(
                onDismissRequest = { actionMessage = null },
                dragHandle = null,
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                    if (!targetMine) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                            listOf("❤️", "😂", "😮", "😢", "😡", "👍", "👎").forEach { emoji ->
                                Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).clickable {
                                    actionMessage = null
                                    val obj = org.json.JSONObject().put("messageId", target.id).put("emoji", emoji)
                                    ChatSocket.emitWithAck("message:reaction", obj) { ack ->
                                        if (ack?.optBoolean("success") == true) load() else showMsg("Couldn't react — try again")
                                    }
                                }, contentAlignment = Alignment.Center) {
                                    Text(emoji, fontSize = 22.sp, maxLines = 1, softWrap = false)
                                }
                            }
                        }
                        HorizontalDivider(color = FrianzoBorder)
                    }
                    ChatActionRow(Icons.Filled.Reply, "Reply") { actionMessage = null; replyTo = target; editingMessage = null }
                    if (hasText) ChatActionRow(Icons.Filled.ContentCopy, "Copy") {
                        actionMessage = null
                        clipboard.setText(AnnotatedString(target.content ?: ""))
                        showMsg("Copied")
                    }
                    if (targetMine && hasText) ChatActionRow(Icons.Filled.Edit, "Edit") { actionMessage = null; editingMessage = target; draft = target.content ?: ""; replyTo = null }
                    ChatActionRow(Icons.Filled.PushPin, if (target.pinnedAt != null) "Unpin" else "Pin") {
                        actionMessage = null
                        val obj = org.json.JSONObject().put("messageId", target.id)
                        ChatSocket.emitWithAck("message:pin", obj) { ack ->
                            if (ack?.optBoolean("success") == true) load() else showMsg("Couldn't pin — try again")
                        }
                    }
                    // v5.18: Forward enters selection mode (web) with
                    // this message pre-selected; the picker opens from
                    // the selection header.
                    ChatActionRow(Icons.Filled.Forward, "Forward") { actionMessage = null; selecting = true; selectedMessages = setOf(target.id!!) }
                    ChatActionRow(Icons.Filled.Delete, "Delete", red = true) { actionMessage = null; deleteTarget = target; showDeleteDialog = true }
                    Spacer(Modifier.height(12.dp))
                    GreyCancelPill(modifier = Modifier.padding(horizontal = 16.dp), onClick = { actionMessage = null })
                }
            }
        }
        // v5.15 (web selection sheet): "Forward message" + count +
        // Cancel top-right; 40dp avatars, 14sp names, 20dp check
        // circles; selected rows tinted; full-width slate-900 pill.
        if (forwardOpen) {
            ModalBottomSheet(
                onDismissRequest = { forwardOpen = false },
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (forwardMessages.size > 1) "Forward ${forwardMessages.size} messages" else "Forward message", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false)
                        if (forwardSelected.isNotEmpty()) {
                            Spacer(Modifier.width(8.dp))
                            Text("${forwardSelected.size} selected", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { forwardOpen = false }, contentPadding = PaddingValues(0.dp)) { Text("Cancel", fontSize = 14.sp, color = FrianzoBlue, maxLines = 1, softWrap = false) }
                    }
                    HorizontalDivider(color = FrianzoBorder)
                    if (!forwardLoaded) {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { DotsLoading() }
                    } else if (forwardConversations.isEmpty()) {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { Text("No other chats yet", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    } else {
                        forwardConversations.forEach { conv ->
                            val other = conv.otherUser
                            val selected = forwardSelected.contains(conv.id)
                            Row(
                                Modifier.fillMaxWidth().background(if (selected) FrianzoBlueTint else Color.Transparent).clickable { forwardSelected = if (selected) forwardSelected - conv.id else forwardSelected + conv.id }.padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                FrianzoAvatar(other?.profilePictureUrl, 40.dp)
                                Spacer(Modifier.width(12.dp))
                                Text(other?.displayName ?: other?.username ?: "User", Modifier.weight(1f), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Box(Modifier.size(20.dp).clip(CircleShape).background(if (selected) FrianzoBlue else Color.Transparent).border(if (selected) 0.dp else 1.dp, if (selected) Color.Transparent else FrianzoBorder, CircleShape), contentAlignment = Alignment.Center) {
                                    if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { sendForward() },
                        enabled = forwardSelected.isNotEmpty() && !forwardBusy,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(48.dp),
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface),
                    ) {
                        Text(if (forwardBusy) "Sending…" else "Send to ${forwardSelected.size} chat(s)", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
        // v5.13: delete dialog (web: no undo anywhere). "Delete for
        // everyone" only on your own messages.
        if (showDeleteDialog && deleteTarget != null) {
            val target = deleteTarget!!
            val targetMine = target.senderId == myId
            // v5.15 (web): delete is a SHEET, not a dialog — same
            // deleteMessage logic (including the socket ack path).
            ModalBottomSheet(
                onDismissRequest = { showDeleteDialog = false },
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                    if (targetMine) {
                        ChatDeleteRow(
                            title = "Delete for everyone",
                            sublabel = "No one in this chat will see it anymore",
                            red = true,
                            onClick = { showDeleteDialog = false; deleteMessage(target, "everyone") },
                        )
                    }
                    ChatDeleteRow(
                        title = "Delete for me",
                        sublabel = "Only removed from your chat",
                        red = false,
                        onClick = { showDeleteDialog = false; deleteMessage(target, "me") },
                    )
                    Spacer(Modifier.height(12.dp))
                    GreyCancelPill(modifier = Modifier.padding(horizontal = 16.dp), onClick = { showDeleteDialog = false })
                }
            }
        }
        // v5.18: load error with Retry (web: "Couldn't load messages").
        loadError?.let {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(it, Modifier.weight(1f), color = FrianzoBadgeRed)
                Spacer(Modifier.width(8.dp))
                Button(onClick = { loadError = null; load() }) { Text("Retry", maxLines = 1, softWrap = false) }
            }
        }
        sendError?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = FrianzoBadgeRed) }
        // v5.18: fullscreen image viewer (web: tap a photo → viewer with
        // Download + Close).
        if (viewerUrl != null) {
            Dialog(onDismissRequest = { viewerUrl = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                    AsyncImage(model = viewerUrl, contentDescription = "Photo", modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
                    Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { viewerUrl = null }) { Text("Close", color = Color.White, maxLines = 1, softWrap = false) }
                        TextButton(onClick = {
                            val url = viewerUrl!!
                            try {
                                val dm = context.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
                                val req = android.app.DownloadManager.Request(android.net.Uri.parse(url))
                                    .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                    .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, "frianzo-${System.currentTimeMillis()}.jpg")
                                dm.enqueue(req)
                                Toast.makeText(context, "Downloading…", Toast.LENGTH_SHORT).show()
                            } catch (_: Exception) { Toast.makeText(context, "Couldn't download photo", Toast.LENGTH_SHORT).show() }
                        }) { Text("Download", color = Color.White, maxLines = 1, softWrap = false) }
                    }
                }
            }
        }
        // v5.13: feedback pill (same pattern as the reel actionMsg).
        if (actionMsg != null) {
            Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                Surface(color = Color(0xFF0F172A), shape = RoundedCornerShape(20.dp)) {
                    Text(actionMsg!!, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), color = Color.White, fontSize = 13.sp, maxLines = 1, softWrap = false)
                }
            }
        }
        // v5.13: reply banner (web: green bar, "Replying to …").
        if (replyTo != null) {
            val rt = replyTo!!
            Row(Modifier.fillMaxWidth().background(Color(0xFFF0FDF4)).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(3.dp).height(28.dp).background(FrianzoSuccess))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Replying to " + if (rt.senderId == myId) "yourself" else title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = FrianzoSuccess, maxLines = 1, softWrap = false)
                    Text(messageLabel(rt), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { replyTo = null }, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Close, contentDescription = "Cancel reply", modifier = Modifier.size(18.dp)) }
            }
        }
        // v5.13: edit banner (web: amber "Editing message").
        if (editingMessage != null) {
            Row(Modifier.fillMaxWidth().background(Color(0xFFFEF3C7)).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Editing message", Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF92400E), maxLines = 1, softWrap = false)
                TextButton(onClick = { editingMessage = null; draft = "" }) { Text("Cancel", fontSize = 13.sp, color = Color(0xFF92400E), maxLines = 1, softWrap = false) }
            }
        }
        // v5.18: blocked → the composer is replaced by an Unblock button
        // (web); recording replaces the row with Cancel / timer / Send.
        if (isBlocked) {
            Button(
                onClick = { scope.launch { try { if (otherUserId != null && api2.unblockUser(otherUserId!!).success) isBlocked = false } catch (_: Exception) {} } },
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                shape = RoundedCornerShape(28.dp),
            ) { Text("Unblock to send messages", maxLines = 1, softWrap = false) }
        } else if (recording) {
            Row(Modifier.background(MaterialTheme.colorScheme.surface).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { stopRecording(false) }) { Icon(Icons.Filled.Close, contentDescription = "Cancel recording") }
                Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFEF4444)))
                Spacer(Modifier.width(8.dp))
                Text("Recording… ${recordSeconds / 60}:${"%02d".format(recordSeconds % 60)}", Modifier.weight(1f), fontSize = 15.sp, maxLines = 1, softWrap = false)
                Button(shape = RoundedCornerShape(28.dp), onClick = { stopRecording(true) }) { Text("Stop and send", maxLines = 1, softWrap = false) }
            }
        } else Row(Modifier.background(MaterialTheme.colorScheme.surface).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            // v5.18: photo + mic send buttons (web). The old comment
            // claimed media sending was "socket-only (no REST endpoint)"
            // — the upload endpoint + socket media fields both exist
            // (verified in the backend code), so the buttons are real.
            IconButton(onClick = { photoGate() }, enabled = !photoBusy, modifier = Modifier.size(40.dp)) { Icon(Icons.Filled.Image, contentDescription = "Send photo", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp)) }
            // v5.18: max message length 2000 (web).
            OutlinedTextField(value = draft, onValueChange = { if (it.length <= 2000) draft = it }, placeholder = { Text("Message...") }, modifier = Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(28.dp))
            IconButton(onClick = { micGate() }, modifier = Modifier.size(40.dp)) { Icon(Icons.Filled.Mic, contentDescription = "Record voice message", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp)) }
            // v3.4: Send appears only once text is typed.
            if (draft.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                Button(shape = RoundedCornerShape(28.dp), onClick = {
                    sendError = null
                    val text = draft
                    val editing = editingMessage
                    val reply = replyTo
                    // v5.13: edit own message (socket; web parity, no
                    // optimistic update — waits for the ack/broadcast).
                    if (editing != null) {
                        val targetId = editing.id
                        if (targetId == null) { editingMessage = null; return@Button }
                        draft = ""
                        val obj = org.json.JSONObject().put("messageId", targetId).put("content", text)
                        ChatSocket.emitWithAck("message:edit", obj) { ack ->
                            if (ack?.optBoolean("success") == true) { editingMessage = null; load() }
                            else { draft = text; editingMessage = editing; sendError = "Could not edit message" }
                        }
                        return@Button
                    }
                    draft = ""
                    emitTypingStop()
                    replyTo = null
                    // v5.18: optimistic socket send (replaces the REST
                    // send; replies already went over the socket).
                    sendMessageNow(text, reply, null, null, null)
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

fun notifTimeAgo(createdAt: String?): String {
    if (createdAt.isNullOrBlank()) return ""
    return try {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val millis = f.parse(createdAt)?.time ?: return createdAt.take(10)
        val s = ((System.currentTimeMillis() - millis) / 1000).coerceAtLeast(0)
        when { s < 60 -> "now"; s < 3600 -> "${s / 60}m"; s < 86400 -> "${s / 3600}h"; s < 604800 -> "${s / 86400}d"; s < 2592000 -> "${s / 604800}w"; else -> "${s / 2592000}mo" }
    } catch (_: Exception) { createdAt.take(10) }
}

// ---------- v5.18: chat time helpers (web: "09:41 PM", "Today" /
// "Yesterday" / "5 Nov" / "5 Nov 2025") ----------
private fun parseChatMillis(iso: String?): Long? {
    if (iso.isNullOrBlank()) return null
    return try {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        f.parse(iso)?.time
    } catch (_: Exception) { null }
}

private fun chatTime(iso: String?): String {
    val ms = parseChatMillis(iso) ?: return ""
    return java.text.SimpleDateFormat("hh:mm a", java.util.Locale.US).format(java.util.Date(ms))
}

private fun chatDayKey(iso: String?): String {
    val ms = parseChatMillis(iso) ?: return ""
    return java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(ms))
}

private fun chatDateLabel(iso: String?): String {
    val ms = parseChatMillis(iso) ?: return ""
    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
    val key = fmt.format(java.util.Date(ms))
    val cal = java.util.Calendar.getInstance()
    val today = fmt.format(cal.time)
    cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
    val yesterday = fmt.format(cal.time)
    return when (key) {
        today -> "Today"
        yesterday -> "Yesterday"
        else -> {
            val yearNow = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            val yearMsg = java.util.Calendar.getInstance().apply { timeInMillis = ms }.get(java.util.Calendar.YEAR)
            if (yearMsg == yearNow) java.text.SimpleDateFormat("d MMM", java.util.Locale.US).format(java.util.Date(ms))
            else java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.US).format(java.util.Date(ms))
        }
    }
}

private fun formatVoiceTime(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)

/** v5.18: voice message player (web: play/pause + waveform + seek +
 * duration). The waveform is a progress bar here — a real 32-bar
 * waveform needs audio decoding; the playback and seek are real. */
@Composable
private fun VoiceMessagePlayer(url: String, durationSec: Int?, mine: Boolean) {
    var player by remember(url) { mutableStateOf<android.media.MediaPlayer?>(null) }
    var playing by remember(url) { mutableStateOf(false) }
    var positionMs by remember(url) { mutableStateOf(0) }
    var totalMs by remember(url) { mutableStateOf((durationSec ?: 0) * 1000) }
    var prepared by remember(url) { mutableStateOf(false) }
    fun ensurePlayer(): android.media.MediaPlayer? {
        player?.let { return it }
        return try {
            val mp = android.media.MediaPlayer()
            mp.setDataSource(url)
            mp.setOnPreparedListener {
                totalMs = if (mp.duration > 0) mp.duration else totalMs
                prepared = true
                if (playing) { try { mp.start() } catch (_: Exception) {} }
            }
            mp.setOnCompletionListener { playing = false; positionMs = 0 }
            mp.setOnErrorListener { _, _, _ -> playing = false; true }
            mp.prepareAsync()
            player = mp
            mp
        } catch (_: Exception) { null }
    }
    DisposableEffect(url) {
        onDispose { try { player?.release() } catch (_: Exception) {}; player = null }
    }
    LaunchedEffect(playing) {
        while (playing) {
            val mp = player
            if (mp != null) { try { positionMs = mp.currentPosition } catch (_: Exception) {} }
            kotlinx.coroutines.delay(200)
        }
    }
    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = {
            val mp = ensurePlayer() ?: return@IconButton
            if (playing) {
                try { mp.pause() } catch (_: Exception) {}
                playing = false
            } else {
                playing = true
                if (prepared) { try { mp.start() } catch (_: Exception) {} }
            }
        }, modifier = Modifier.size(36.dp)) {
            Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (playing) "Pause" else "Play", tint = if (mine) Color.White else FrianzoBlue, modifier = Modifier.size(24.dp))
        }
        Slider(
            value = positionMs.toFloat(),
            onValueChange = { v -> positionMs = v.toInt(); try { player?.seekTo(v.toInt()) } catch (_: Exception) {} },
            valueRange = 0f..(totalMs.coerceAtLeast(1)).toFloat(),
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = if (mine) Color.White else FrianzoBlue, activeTrackColor = if (mine) Color.White else FrianzoBlue),
        )
        Text(formatVoiceTime(positionMs / 1000) + " / " + formatVoiceTime(totalMs / 1000), fontSize = 11.sp, color = if (mine) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
    }
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
            if (loading) item {
                // v5.12: skeleton rows on a bare first load (web parity);
                // the dots footer stays for a load-more wait.
                if (filtered.isEmpty()) {
                    Column { repeat(5) { SkeletonRow() } }
                } else {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { DotsLoading() }
                }
            }
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
// v5.18: tabs People | Posts | Reels | Tags (web search is people-only;
// tabs are the owner's approved #8 design). Posts/Reels call the new
// GET /api/search endpoint and are hidden until SEARCH_API_READY is true
// (the backend is staged, not deployed yet). Tags opens a tag page.
fun SearchScreen(api2: FrianzoApi2, onUser: (String) -> Unit, onTag: (String) -> Unit = {}, onOpenPost: (String) -> Unit = {}, onOpenReel: (String) -> Unit = {}) {
    val context = LocalContext.current
    var q by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableStateOf("People") }
    var results by remember { mutableStateOf<List<PublicProfile>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    // v5.18: Posts/Reels search results (GET /api/search, gated).
    var allResults by remember { mutableStateOf<SearchAllData?>(null) }
    var allSearching by remember { mutableStateOf(false) }
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
    // v5.18: post/reel search (GET /api/search — gated by
    // SEARCH_API_READY; never called while the endpoint is not live).
    fun runSearchAll(term: String, type: String) {
        if (term.isBlank()) { allResults = null; return }
        searchError = null; allSearching = true
        scope.launch {
            try {
                val res = api2.searchAll(term, type = type)
                if (term != q) return@launch
                if (res.success && res.data != null) allResults = res.data
                else searchError = res.error?.message ?: "Couldn't search — try again"
            } catch (e: Exception) { if (term == q) searchError = e.message ?: "Couldn't search — try again" }
            finally { if (term == q) allSearching = false }
        }
    }
    // Live search as you type (300ms debounce), plus the Search button.
    // v5.18: the active tab decides which endpoint runs.
    LaunchedEffect(q, tab) {
        if (q.isBlank()) { results = emptyList(); allResults = null; return@LaunchedEffect }
        kotlinx.coroutines.delay(300)
        when (tab) {
            "Posts" -> runSearchAll(q, "posts")
            "Reels" -> runSearchAll(q, "reels")
            "Tags" -> { /* tags need no search — #query opens the page */ }
            else -> runSearch(q, saveRecent = false)
        }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        OutlinedTextField(
            value = q, onValueChange = { q = it },
            label = { Text("Search") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(12.dp),
            // v5.18: clear (X) button when there is text (web parity
            // for the field); the Search button saves a recent.
            trailingIcon = {
                if (q.isNotBlank()) {
                    IconButton(onClick = { q = ""; results = emptyList(); allResults = null; searchError = null }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") }
                } else {
                    IconButton(onClick = { runSearch(q, saveRecent = true) }) { Icon(Icons.Filled.Search, contentDescription = "Search") }
                }
            },
        )
        // v5.18: tabs (Posts/Reels hidden until GET /api/search lands).
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val tabNames = buildList { add("People"); if (SEARCH_API_READY) { add("Posts"); add("Reels") }; add("Tags") }
            tabNames.forEach { name ->
                val selected = tab == name
                Box(Modifier.clip(RoundedCornerShape(28.dp)).background(if (selected) FrianzoBlue else MaterialTheme.colorScheme.surfaceVariant).clickable { tab = name }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Text(name, style = MaterialTheme.typography.bodyMedium, color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        // v5.12: skeleton rows while searching with no results yet.
        if (searching || allSearching) {
            if (results.isEmpty() && allResults == null) {
                Column { repeat(5) { SkeletonRow() } }
            } else {
                Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) { DotsLoading() }
            }
        }
        if (tab == "People" && q.isBlank() && recents.isNotEmpty()) {
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
        // v5.18: Tags tab — #query opens the tag page; anything else
        // shows a hint (the tag page itself lists the posts + reels).
        if (tab == "Tags") {
            if (q.startsWith("#") && q.length > 1) {
                Row(Modifier.fillMaxWidth().clickable { onTag(q.drop(1)) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).clip(CircleShape).background(FrianzoBlueTint), contentAlignment = Alignment.Center) {
                        Text("#", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = FrianzoBlue)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(q, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Open tag page", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                    }
                    Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            } else if (q.isNotBlank()) {
                Text("Type #tag to open a tag page.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("Search tags — try #sunset, #travel or any hashtag you saw in a post.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        searchError?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = FrianzoBadgeRed) }
        if (tab == "People" && results.isEmpty() && searchError == null && q.isNotBlank() && !searching) Text("No people found", Modifier.padding(16.dp))
        if (tab == "People" && results.isEmpty() && searchError == null && q.isBlank() && recents.isEmpty()) Text("Search for people by name or @username.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if ((tab == "Posts" || tab == "Reels") && allResults != null && searchError == null && !allSearching) {
            val empty = if (tab == "Posts") allResults!!.posts.isEmpty() else allResults!!.reels.isEmpty()
            if (empty && q.isNotBlank()) Text(if (tab == "Posts") "No posts found" else "No reels found", Modifier.padding(16.dp))
        }
        LazyColumn {
            if (tab == "People") {
                items(results, key = { it.id ?: it.username ?: "" }) { u ->
                    ListItem(
                        // v5.18: verified badge in search rows (web).
                        headlineContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(u.displayName ?: "", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                if (u.isVerified) {
                                    Spacer(Modifier.width(4.dp))
                                    Icon(Icons.Filled.CheckCircle, contentDescription = "Verified", tint = FrianzoBlue, modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                        supportingContent = { Text("@${u.username ?: ""}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { FrianzoAvatar(u.profilePictureUrl, 48.dp) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.clickable { if (u.username != null) onUser(u.username) },
                    )
                    HorizontalDivider()
                }
            }
            if (tab == "Posts") {
                items(allResults?.posts ?: emptyList(), key = { it.id }) { p ->
                    ListItem(
                        headlineContent = { Text(p.author?.displayName ?: p.author?.username ?: "User", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(p.content ?: "", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { FrianzoAvatar(p.author?.profilePictureUrl, 48.dp) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.clickable { onOpenPost(p.id) },
                    )
                    HorizontalDivider()
                }
            }
            if (tab == "Reels") {
                items(allResults?.reels ?: emptyList(), key = { it.id }) { r ->
                    ListItem(
                        headlineContent = { Text(r.caption ?: "Reel", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(r.author?.displayName ?: r.author?.username ?: "User", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = {
                            Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(FrianzoBlueTint), contentAlignment = Alignment.Center) {
                                if (r.thumbnailUrl != null) AsyncImage(model = r.thumbnailUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                                else Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = FrianzoBlue)
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.clickable { onOpenReel(r.id) },
                    )
                    HorizontalDivider()
                }
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

// v5.16 (Edit Profile): country + province/city lists copied from the web
// Edit Profile page (vynzo-frontend src/app/settings/page.tsx —
// COUNTRIES from src/lib/countries.ts, PROVINCES_BY_COUNTRY local to the
// page). No backend needed: these are frontend constants.
private val EDIT_PROFILE_COUNTRIES = listOf(
    "Pakistan", "India", "Bangladesh", "Afghanistan", "Saudi Arabia",
    "United Arab Emirates", "Qatar", "Kuwait", "Oman", "Bahrain", "Turkey",
    "United Kingdom", "United States", "Canada", "Australia", "Germany",
    "France", "Italy", "Spain", "Netherlands", "China", "Japan",
    "South Korea", "Malaysia", "Indonesia", "Thailand", "Philippines",
    "Singapore", "Sri Lanka", "Nepal", "Iran", "Iraq", "Egypt",
    "South Africa", "Nigeria", "Kenya", "Brazil", "Mexico", "Russia",
)

private val PROVINCES_BY_COUNTRY: Map<String, Map<String, List<String>>> = mapOf(
    "Pakistan" to mapOf(
        "Sindh" to listOf("Karachi", "Hyderabad", "Sukkur", "Larkana", "Nawabshah", "Khairpur", "Mirpurkhas"),
        "Punjab" to listOf("Lahore", "Faisalabad", "Rawalpindi", "Multan", "Gujranwala", "Sialkot", "Bahawalpur"),
        "Khyber Pakhtunkhwa" to listOf("Peshawar", "Abbottabad", "Mardan", "Swat", "Kohat"),
        "Balochistan" to listOf("Quetta", "Gwadar", "Turbat", "Khuzdar"),
        "Islamabad" to listOf("Islamabad"),
        "Gilgit-Baltistan" to listOf("Gilgit", "Skardu"),
        "Azad Kashmir" to listOf("Muzaffarabad", "Mirpur"),
    ),
    "India" to mapOf(
        "Maharashtra" to listOf("Mumbai", "Pune", "Nagpur", "Nashik"),
        "Delhi" to listOf("New Delhi"),
        "Karnataka" to listOf("Bangalore", "Mysore", "Hubli"),
        "Tamil Nadu" to listOf("Chennai", "Coimbatore", "Madurai"),
        "Uttar Pradesh" to listOf("Lucknow", "Kanpur", "Agra", "Varanasi"),
        "Punjab" to listOf("Amritsar", "Ludhiana", "Jalandhar"),
        "Gujarat" to listOf("Ahmedabad", "Surat", "Vadodara"),
        "West Bengal" to listOf("Kolkata", "Howrah"),
        "Telangana" to listOf("Hyderabad"),
        "Rajasthan" to listOf("Jaipur", "Jodhpur", "Udaipur"),
    ),
    "United States" to mapOf(
        "California" to listOf("Los Angeles", "San Francisco", "San Diego", "Sacramento"),
        "New York" to listOf("New York City", "Buffalo", "Albany"),
        "Texas" to listOf("Houston", "Dallas", "Austin", "San Antonio"),
        "Florida" to listOf("Miami", "Orlando", "Tampa"),
        "Illinois" to listOf("Chicago", "Springfield"),
        "Washington" to listOf("Seattle", "Spokane"),
        "Georgia" to listOf("Atlanta", "Savannah"),
        "Arizona" to listOf("Phoenix", "Tucson"),
        "New Jersey" to listOf("Newark", "Jersey City"),
        "Virginia" to listOf("Richmond", "Virginia Beach"),
    ),
    "Iran" to mapOf(
        "Tehran" to listOf("Tehran", "Rey"),
        "Isfahan" to listOf("Isfahan", "Kashan"),
        "Fars" to listOf("Shiraz"),
        "Khorasan" to listOf("Mashhad"),
        "Khuzestan" to listOf("Ahvaz"),
        "Tabriz" to listOf("Tabriz"),
    ),
    "Saudi Arabia" to mapOf(
        "Riyadh" to listOf("Riyadh", "Al Kharj"),
        "Makkah" to listOf("Mecca", "Jeddah", "Taif"),
        "Madinah" to listOf("Medina"),
        "Eastern Province" to listOf("Dammam", "Khobar", "Dhahran"),
        "Asir" to listOf("Abha"),
    ),
    "United Arab Emirates" to mapOf(
        "Dubai" to listOf("Dubai"),
        "Abu Dhabi" to listOf("Abu Dhabi", "Al Ain"),
        "Sharjah" to listOf("Sharjah"),
        "Ajman" to listOf("Ajman"),
        "Ras Al Khaimah" to listOf("Ras Al Khaimah"),
        "Fujairah" to listOf("Fujairah"),
        "Umm Al Quwain" to listOf("Umm Al Quwain"),
    ),
    "Afghanistan" to mapOf(
        "Kabul" to listOf("Kabul"),
        "Herat" to listOf("Herat"),
        "Kandahar" to listOf("Kandahar"),
        "Balkh" to listOf("Mazar-i-Sharif"),
        "Nangarhar" to listOf("Jalalabad"),
    ),
    "United Kingdom" to mapOf(
        "England" to listOf("London", "Manchester", "Birmingham", "Liverpool"),
        "Scotland" to listOf("Edinburgh", "Glasgow", "Aberdeen"),
        "Wales" to listOf("Cardiff", "Swansea"),
        "Northern Ireland" to listOf("Belfast"),
    ),
    "Canada" to mapOf(
        "Ontario" to listOf("Toronto", "Ottawa", "Mississauga"),
        "Quebec" to listOf("Montreal", "Quebec City"),
        "British Columbia" to listOf("Vancouver", "Victoria"),
        "Alberta" to listOf("Calgary", "Edmonton"),
        "Manitoba" to listOf("Winnipeg"),
    ),
)

// v5.16 (Edit Profile): 30-day name lock — the same rule as the web
// settings page (cooldownInfo, COOLDOWN_DAYS = 30) and the backend
// checkCooldown (vynzo-backend userService.ts). Returns the days left
// until the name can be changed again, or 0 when it is editable (never
// changed, 30+ days ago, or an unparseable timestamp — never lock the
// user out on bad data).
private fun cooldownDaysLeft(changedAt: String?): Int {
    if (changedAt.isNullOrBlank()) return 0
    val formats = listOf(
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US),
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US),
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", java.util.Locale.US),
    )
    var millis: Long? = null
    for (f in formats) {
        f.timeZone = java.util.TimeZone.getTimeZone("UTC")
        try { millis = f.parse(changedAt)?.time; if (millis != null) break } catch (_: Exception) {}
    }
    if (millis == null) return 0
    val daysSince = (System.currentTimeMillis() - millis) / 86400000.0
    if (daysSince >= 30) return 0
    return kotlin.math.ceil(30.0 - daysSince).toInt().coerceIn(1, 30)
}

// v5.16 (Edit Profile): "now" in the ISO form the backend returns, so a
// just-saved name change locks immediately without waiting for a reload.
private fun isoNow(): String =
    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        .format(java.util.Date())

// v5.17: one All-tab entry — a post or a reel — so the All tab can merge
// both lists, pinned-first then newest, like the web profile.
private data class ProfileAllItem(val key: String, val pinned: Boolean, val createdAt: String?, val post: Post?, val reel: Reel?)

@Composable
fun ProfileScreen(api2: FrianzoApi2, username: String?, myId: String?, onLogout: (() -> Unit)? = null, onBack: (() -> Unit)? = null, onOpenChat: ((String, String) -> Unit)? = null, feedVm: FeedViewModel? = null, onUser: ((String) -> Unit)? = null, onDashboard: (() -> Unit)? = null, onOpenReel: ((String) -> Unit)? = null) {
    var profile by remember { mutableStateOf<PublicProfile?>(null) }
    var posts by remember { mutableStateOf<List<Post>>(emptyList()) }
    var postsLoading by remember { mutableStateOf(false) }
    var postsError by remember { mutableStateOf<String?>(null) }
    var following by remember { mutableStateOf(false) }
    var followStatus by remember { mutableStateOf<String?>(null) }
    var showEdit by remember { mutableStateOf(false) }
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
    // v5.14 (#5 Save post): own-profile Saved tab state. Loaded via
    // GET /api/posts/me/favorites when the tab is selected; there is no
    // per-user saved-posts endpoint, so the tab is own-profile-only.
    var savedPosts by remember { mutableStateOf<List<Post>>(emptyList()) }
    var savedPostsLoading by remember { mutableStateOf(false) }
    var savedPostsError by remember { mutableStateOf<String?>(null) }
    var userReels by remember { mutableStateOf<List<Reel>>(emptyList()) }
    var reelsLoading by remember { mutableStateOf(false) }
    var linkMsg by remember { mutableStateOf<String?>(null) }
    var blockedByMe by remember { mutableStateOf(false) }
    var blockedByOther by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    var showBlockConfirm by remember { mutableStateOf(false) }
    var showUnfollowConfirm by remember { mutableStateOf(false) }
    // v5.17: profile-load retry (web has a "Try again" button).
    var retryKey by remember { mutableStateOf(0) }
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
    // v5.17: blocked view (web hides tabs + content behind a panel).
    val isBlockedView = blockedByMe || blockedByOther
    fun profileUrl(u: String?) = "https://frianzo.online/u/${u ?: ""}"
    fun copyProfileLink(u: String?) {
        clipboard.setText(AnnotatedString(profileUrl(u)))
        linkMsg = "Link copied"
        scope.launch { delay(2000); linkMsg = null }
    }
    // v5.17: keep the follower count live after follow/unfollow (web bumps
    // it immediately); the counts endpoint is only re-read on reload.
    fun bumpFollowers(delta: Int) {
        val fc = followCounts
        if (fc != null) followCounts = fc.copy(followers = (fc.followers + delta).coerceAtLeast(0))
        else if (profile != null) {
            val cur = profile!!.followerCount ?: profile!!.followersCount
            if (cur != null) profile = profile!!.copy(followerCount = (cur + delta).coerceAtLeast(0))
        }
    }
    // v5.14 (#5 Save post): load the own-profile Saved tab. In-flight
    // guard so rapid tab taps cannot stack duplicate loads; error is
    // kept for the Retry button (mirror of SavedReelsScreen.load).
    fun loadSavedPosts() {
        if (savedPostsLoading) return
        savedPostsLoading = true
        savedPostsError = null
        scope.launch {
            try {
                val r = api2.myFavoritePosts()
                if (r.success && r.data != null) savedPosts = r.data.posts ?: emptyList()
                else savedPostsError = r.error?.message ?: "Couldn't load saved posts"
            } catch (e: Exception) { savedPostsError = e.message ?: "Couldn't load saved posts" }
            finally { savedPostsLoading = false }
        }
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
    // v5.16 (Edit Profile): full page, not a dialog — same entry point
    // (the Edit Profile button) now opens the web-matching page.
    if (showEdit && profile != null) {
        EditProfileScreen(api2, profile!!, onClose = { showEdit = false }, onSaved = { updated -> profile = updated; showEdit = false }, onProfileChanged = { updated -> profile = updated })
    }
    LaunchedEffect(username, retryKey) {
        scope.launch {
            error = null
            postsError = null
            // v5.17: network first, cached profile as the offline fallback
            // (web: getOfflineProfile). A cached profile still loads its
            // posts/reels below; those fail silently on their own, as before.
            var netError: String? = null
            val netUser: PublicProfile? = try {
                val res = if (username == null) api2.myProfile() else api2.publicProfile(username)
                if (res.success && res.data?.user != null) res.data.user
                else { netError = res.error?.message; null }
            } catch (e: Exception) { netError = e.message; null }
            val user = netUser ?: ProfileCache.load(context, username ?: "me")
            if (user == null) { error = netError ?: "Couldn't load this profile"; return@launch }
            if (netUser != null) ProfileCache.save(context, username ?: "me", netUser)
            profile = user
            val uname = user.username
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
            if (user.id != null) {
                try { val fc = api2.followCounts(user.id); if (fc.success && fc.data != null) followCounts = fc.data } catch (_: Exception) {}
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
            if (user.id != null && user.id != myId) {
                try { val bs = api2.blockStatus(user.id); if (bs.success && bs.data != null) { blockedByMe = bs.data.blockedByMe; blockedByOther = bs.data.blockedByOther } } catch (_: Exception) {}
                val fs = api2.followStatus(user.id)
                if (fs.success && fs.data != null) {
                    followStatus = fs.data.status
                    following = fs.data.status == "following" || fs.data.status == "friends"
                }
            }
        }
    }
    // v5.14 (#5 Save post): reload saved posts every time the Saved tab
    // is selected, so a post saved from the feed since the last visit
    // shows up; never fires for other profiles (no such endpoint).
    LaunchedEffect(activeTab) {
        if (activeTab == "saved" && username == null) loadSavedPosts()
    }
    // v5.17: clear the avatar/cover upload message after a while.
    LaunchedEffect(photoMsg) { if (photoMsg != null) { delay(2500); photoMsg = null } }
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
                // v5.17: web sizes — 128dp cover, 80dp avatar, 40dp
                // overlap (web: h-32 cover, h-20 w-20 avatar, -mt-10).
                Box(Modifier.fillMaxWidth().height(168.dp)) {
                    if (p.coverPhotoUrl != null) AsyncImage(model = p.coverPhotoUrl, contentDescription = null, modifier = Modifier.fillMaxWidth().height(128.dp), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                    else Box(Modifier.fillMaxWidth().height(128.dp).background(Color(0xFFE2E8F0)))
                    // v3.2 web parity: "Edit cover" pill on the cover (own profile).
                    if (username == null) {
                        Button(onClick = { pickCover() }, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 48.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.55f), contentColor = Color.White), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), shape = RoundedCornerShape(28.dp)) { Text("Edit cover", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                    }
                    Box(Modifier.align(Alignment.BottomStart).padding(start = 16.dp).size(80.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                        FrianzoAvatar(p.profilePictureUrl, 72.dp)
                        // v5.17: no photo = initial letter, like the web
                        // (web shows the initial on slate-200).
                        if (p.profilePictureUrl.isNullOrBlank()) {
                            Text((p.displayName?.firstOrNull()?.toString() ?: "?").uppercase(), color = Color(0xFF475569), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                        }
                        // v3.2 web parity: own avatar gets a + badge (change photo);
                        // other users get a green online dot, like the web.
                        if (username == null) {
                            Box(Modifier.align(Alignment.BottomEnd).size(32.dp).clip(CircleShape).background(Color(0xFF0F172A)).border(2.dp, Color.White, CircleShape).clickable { pickAvatar() }, contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Add, contentDescription = "Change profile photo", tint = Color.White, modifier = Modifier.size(12.dp))
                            }
                        } else if (p.isOnline) {
                            Box(Modifier.align(Alignment.BottomEnd).size(16.dp).clip(CircleShape).background(FrianzoSuccess).border(2.dp, Color.White, CircleShape))
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
                                        DropdownMenuItem(
                                            text = { Text("Copy link") },
                                            leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) },
                                            onClick = { moreOpen = false; copyProfileLink(p.username) },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(if (blockedByMe) "Unblock" else "Block", color = if (blockedByMe) MaterialTheme.colorScheme.onSurface else FrianzoBadgeRed) },
                                            leadingIcon = { Icon(Icons.Filled.Block, contentDescription = null, tint = if (blockedByMe) MaterialTheme.colorScheme.onSurfaceVariant else FrianzoBadgeRed, modifier = Modifier.size(18.dp)) },
                                            onClick = {
                                                moreOpen = false
                                                if (blockedByMe) {
                                                    scope.launch {
                                                        try { if (api2.unblockUser(p.id ?: "").success) blockedByMe = false } catch (_: Exception) {}
                                                    }
                                                } else showBlockConfirm = true
                                            },
                                        )
                                    }
                                }
                                if (showBlockConfirm) {
                                    BlockConfirmSheet(
                                        name = p.displayName ?: p.username ?: "User",
                                        onBlock = {
                                            scope.launch {
                                                try { if (api2.blockUser(p.id ?: "").success) { blockedByMe = true; following = false; followStatus = "none" } } catch (_: Exception) {}
                                            }
                                        },
                                        onDismiss = { showBlockConfirm = false },
                                    )
                                }
                                if (onOpenChat != null && !blockedByOther) {
                                    // v5.17: disabled when you blocked them, like the web
                                    // (was: tappable, and the tap silently did nothing).
                                    IconButton(enabled = !blockedByMe, onClick = {
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
                        // v5.17: avatar/cover upload feedback (was set but never shown).
                        if (photoMsg != null) Text(photoMsg!!, style = MaterialTheme.typography.bodySmall, color = if (photoMsg!!.contains("fail", ignoreCase = true)) FrianzoBadgeRed else FrianzoBlue, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.displayName ?: "", style = MaterialTheme.typography.titleLarge, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (p.isVerified) { Spacer(Modifier.width(4.dp)); Icon(Icons.Filled.CheckCircle, contentDescription = "Verified", modifier = Modifier.size(20.dp), tint = FrianzoBlue) }
                        }
                        // v3.3: @username is tappable — copies it, like web.
                        // v5.17: grey like the web (was FrianzoBlue).
                        Text("@${p.username ?: ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable {
                            clipboard.setText(AnnotatedString("@${p.username ?: ""}"))
                            linkMsg = "Username copied"
                            scope.launch { delay(2000); linkMsg = null }
                        })
                        // v5.17: green dot + text, like the web (was text only).
                        if (username != null && p.isOnline) Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(FrianzoSuccess))
                            Spacer(Modifier.width(6.dp))
                            Text("Active now", style = MaterialTheme.typography.bodySmall, color = FrianzoSuccess, maxLines = 1, softWrap = false)
                        }
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
                        // v5.17: full bio, like the web (was clamped to 4 lines).
                        p.bio?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        // v2.9: website is a real link (was plain blue Text).
                        p.website?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = FrianzoBlue, modifier = Modifier.clickable { openWebUrl(context, it) }, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        // v5.17: web shows city + province only, never the country.
                        val loc = listOfNotNull(p.city?.takeIf { it.isNotBlank() }, p.province?.takeIf { it.isNotBlank() }).joinToString(", ")
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
                        // v5.17: web counts row — border-y, no dividers.
                        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline))
                        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
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
                            countCol("${userReels.size}", "Reels") { activeTab = "reels" }
                            if (fc != null) countCol("$fc", "Followers") { if (p.id != null) followList = "followers" }
                            if (fg != null) countCol("$fg", "Following") { if (p.id != null) followList = "following" }
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline))
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
                                    if (r.success && r.data != null) { following = r.data.following || r.data.isFollowing; followStatus = r.data.friendStatus ?: if (following) "following" else "none"; bumpFollowers(if (following) 1 else -1) }
                                } catch (_: Exception) {}
                            }
                        }) { Text("Unfollow") } },
                        dismissButton = { TextButton(onClick = { showUnfollowConfirm = false }) { Text("Cancel") } })
                }
                if (username != null && p.id != null && p.id != myId) {
                    if (blockedByOther) {
                        // v5.17: grey pill, like the web (was plain text).
                        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                            Text("This user is unavailable.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                        }
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
                                    if (r.success && r.data != null) { following = r.data.following || r.data.isFollowing; followStatus = r.data.friendStatus ?: if (following) "following" else "none"; bumpFollowers(if (following) 1 else -1) }
                                } catch (_: Exception) {}
                            }
                        }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(FrianzoUi.ButtonHeight), shape = RoundedCornerShape(FrianzoUi.ButtonRadius), colors = ButtonDefaults.buttonColors(containerColor = if (following) MaterialTheme.colorScheme.surfaceVariant else Color(0xFF0F172A), contentColor = if (following) MaterialTheme.colorScheme.onSurface else Color.White)) { Text(if (followStatus == "friends") "Friends" else if (following) "Following" else if (followStatus == "follow_back") "Follow Back" else "Follow", maxLines = 1, softWrap = false) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                // v2.9: Highlights row (GET /api/highlights/user/{username}).
                // v5.17: hidden on blocked views, like the web; "New" first.
                if (!isBlockedView && (highlights.isNotEmpty() || username == null)) {
                    Text("Highlights", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
                    Spacer(Modifier.height(8.dp))
                    LazyRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (username == null) {
                            item {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp).clickable { showHighlightCreate = true }) {
                                    // v3.2 web parity: dashed-circle "New" button.
                                    Box(Modifier.size(64.dp).drawBehind {
                                        drawCircle(color = androidx.compose.ui.graphics.Color(0xFF94A3B8), style = Stroke(width = 4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f)))
                                    }, contentAlignment = Alignment.Center) { Icon(Icons.Filled.Add, contentDescription = "New highlight", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    Spacer(Modifier.height(4.dp))
                                    Text("New", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        items(highlights, key = { it.id }) { h ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp).clickable { highlightViewer = h }) {
                                Box(Modifier.size(64.dp).clip(CircleShape).background(FrianzoBlueTint), contentAlignment = Alignment.Center) {
                                    val cover = h.coverUrl ?: h.items.firstOrNull { !it.mediaUrl.isNullOrBlank() }?.mediaUrl
                                    if (cover != null) AsyncImage(model = cover, contentDescription = null, modifier = Modifier.size(64.dp).clip(CircleShape), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                                    else Text((h.title ?: "H").take(1).uppercase(), color = FrianzoBlue, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(h.title ?: "", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    highlightError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = FrianzoBadgeRed, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                    Spacer(Modifier.height(12.dp))
                }
                // v5.17: blocked / private views show the web's icon panel
                // instead of tabs + content.
                if (isBlockedView) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(56.dp).clip(CircleShape).border(2.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Block, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(26.dp))
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(if (blockedByMe) "You blocked this user" else "This user is unavailable", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                        if (blockedByMe) Text("Unblock them to see their posts and videos.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                } else if (isPrivateView) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(56.dp).clip(CircleShape).border(2.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(26.dp))
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("This account is private", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                        Text("Follow to see their photos and videos.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                } else {
                    // v3.2 web parity: All / Reels / Photos tabs, selected tab
                    // underlined in slate-900, like the web.
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        // v5.14 (#5 Save post): "Saved" is the 4th tab, own
                        // profile only — /me/favorites has no per-user form.
                        (listOf("all" to "All", "reels" to "Reels", "photos" to "Photos") + if (username == null) listOf("saved" to "Saved") else emptyList()).forEach { (v, label) ->
                            val sel = activeTab == v
                            Box(Modifier.weight(1f).clickable { activeTab = v }.padding(top = 10.dp), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(label, style = MaterialTheme.typography.bodyMedium, color = if (sel) Color(0xFF0F172A) else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal, maxLines = 1, softWrap = false)
                                    Spacer(Modifier.height(8.dp))
                                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (sel) Color(0xFF0F172A) else Color.Transparent))
                                }
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline))
                    if (activeTab == "reels") {
                        if (reelsLoading) {
                            // v5.12: skeleton rows while the reels tab loads.
                            Column { repeat(3) { SkeletonRow() } }
                        }
                        else if (userReels.isEmpty()) Text("No reels yet.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
                    } else if (activeTab == "photos") {
                        if (posts.filter { !it.imageUrl.isNullOrBlank() }.isEmpty()) Text("No photos yet.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
                    } else if (activeTab == "saved") {
                        // v5.14 (#5 Save post): mirror of SavedReelsScreen —
                        // skeleton cards while loading, error + Retry, empty
                        // state; the cards themselves render below via the
                        // All-tab items block.
                        if (savedPostsLoading) Column { repeat(3) { SkeletonPostCard() } }
                        else if (savedPostsError != null) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(savedPostsError!!, style = MaterialTheme.typography.bodySmall, color = FrianzoBadgeRed)
                                Spacer(Modifier.height(12.dp))
                                Button(onClick = { savedPostsError = null; loadSavedPosts() }, modifier = Modifier.height(FrianzoUi.ButtonHeight), colors = ButtonDefaults.buttonColors(containerColor = FrianzoBlue, contentColor = Color.White), shape = RoundedCornerShape(28.dp)) { Text("Retry", maxLines = 1, softWrap = false) }
                            }
                        }
                        else if (savedPosts.isEmpty()) Text("No saved posts yet. Save a post from its menu to see it here.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
                    } else {
                        // v5.12: skeleton post cards while the posts tab loads.
                        if (postsLoading) Column { repeat(3) { SkeletonPostCard() } }
                        if (postsError != null) Text(postsError!!, style = MaterialTheme.typography.bodySmall, color = FrianzoBadgeRed, modifier = Modifier.padding(horizontal = 16.dp))
                        if (!postsLoading && !reelsLoading && postsError == null && posts.isEmpty() && userReels.isEmpty()) Text("No posts yet", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            } ?: run {
                // v5.12: skeleton profile header while the user loads.
                // v5.17: error + Retry, like the web (was: error text only).
                if (error != null) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(error!!, style = MaterialTheme.typography.bodySmall, color = FrianzoBadgeRed, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { error = null; retryKey++ }, modifier = Modifier.height(FrianzoUi.ButtonHeight), colors = ButtonDefaults.buttonColors(containerColor = FrianzoBlue, contentColor = Color.White), shape = RoundedCornerShape(28.dp)) { Text("Try again", maxLines = 1, softWrap = false) }
                    }
                } else SkeletonProfileHeader()
            }
        }
        // v3.2: Reels tab = 3-column grid of the user's real reels;
        // Photos tab = 3-column grid of posts with photos; All = post cards.
        // v5.17: grids hidden on blocked/private views (panels show instead).
        if (!isBlockedView && !isPrivateView && activeTab == "reels") {
            // B-B4: same private gate as the Photos/All tabs.
            items(if (isPrivateView) emptyList() else userReels.chunked(3), key = { row -> row.firstOrNull()?.id ?: "empty" }) { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { reel ->
                        Box(Modifier.weight(1f).aspectRatio(9f / 16f).background(Color(0xFF1E293B)).clickable { onOpenReel?.invoke(reel.id) }) {
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
        } else if (!isBlockedView && !isPrivateView && activeTab == "photos") {
            val photoPosts = if (profile?.isPrivate == true && username != null && !following) emptyList() else posts.filter { !it.imageUrl.isNullOrBlank() }
            items(photoPosts.chunked(3), key = { row -> row.firstOrNull()?.id ?: "empty" }) { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { post ->
                        Box(Modifier.weight(1f).aspectRatio(1f).clickable { if (feedVm != null) detailPostId = post.id }) {
                            AsyncImage(model = post.imageUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        // v5.17: All tab merges posts + reels, pinned-first then newest —
        // like the web profile (ProfileClient merges both lists). The Saved
        // tab reuses the same card rendering against savedPosts.
        val allEntries = if (activeTab == "saved") savedPosts.map { ProfileAllItem("post-" + it.id, it.isPinned, it.createdAt, it, null) }
            else if (activeTab != "all" || isBlockedView || isPrivateView) emptyList()
            else (posts.map { ProfileAllItem("post-" + it.id, it.isPinned, it.createdAt, it, null) } + userReels.map { ProfileAllItem("reel-" + it.id, false, it.createdAt, null, it) })
                .sortedWith(compareByDescending<ProfileAllItem> { it.pinned }.thenByDescending { it.createdAt ?: "" })
        items(allEntries, key = { it.key }) { entry ->
            if (entry.reel != null) {
                FeedReelCard(api2, entry.reel) { id -> onOpenReel?.invoke(id) }
                return@items
            }
            val post = entry.post ?: return@items
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

// ---------- EDIT PROFILE (v5.16, web /settings match) ----------
// Full page, not a dialog: tappable cover + avatar on top, then the web
// form in web order — Username, Full Name, Bio (300 counter), Gender,
// Country -> Province -> City cascading selects, Website, Contact Number.
// Username / Full Name lock for 30 days after a change (same rule as the
// web + the backend checkCooldown); the lock timestamps come from
// GET /api/users/me (toPrivateProfile) via PublicProfile, no new endpoint.
// Save uses the existing PATCH /api/users/me (updateMe); avatar/cover use
// the existing upload calls. Save errors surface as Toasts — the old
// dialog wrote the error under the profile buttons, behind the dialog,
// so a failed save looked like nothing happened.
@Composable
private fun EditProfileScreen(
    api2: FrianzoApi2,
    profile: PublicProfile,
    onClose: () -> Unit,
    onSaved: (PublicProfile) -> Unit,
    onProfileChanged: (PublicProfile) -> Unit = {},
) {
    BackHandler { onClose() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Form state (rememberSaveable so a rotation does not wipe the form).
    // Country is normalised to its web display name: the backend stores a
    // 2-letter code (IP auto-detect) or a full name (Settings) — the app
    // already maps codes to names for display (countryDisplayName).
    var edUsername by rememberSaveable { mutableStateOf(profile.username ?: "") }
    var edName by rememberSaveable { mutableStateOf(profile.displayName ?: "") }
    var edBio by rememberSaveable { mutableStateOf(profile.bio ?: "") }
    var edGender by rememberSaveable {
        mutableStateOf(when ((profile.gender ?: "").lowercase()) { "male", "female", "custom" -> (profile.gender ?: "").lowercase(); else -> "" })
    }
    var edCountry by rememberSaveable { mutableStateOf(countryDisplayName(profile.country) ?: "") }
    var edProvince by rememberSaveable { mutableStateOf(profile.province ?: "") }
    var edCity by rememberSaveable { mutableStateOf(profile.city ?: "") }
    var edWeb by rememberSaveable { mutableStateOf(profile.website ?: "") }
    var edPhone by rememberSaveable { mutableStateOf(profile.phone ?: "") }
    var avatarUrl by remember { mutableStateOf(profile.profilePictureUrl) }
    var coverUrl by remember { mutableStateOf(profile.coverPhotoUrl) }
    var edSaving by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }
    // 30-day lock (web cooldownInfo): 0 = editable.
    val usernameDaysLeft = cooldownDaysLeft(profile.usernameChangedAt)
    val nameDaysLeft = cooldownDaysLeft(profile.displayNameChangedAt)
    // Cascading options: the stored value is always offered too, so a
    // value the web lists do not contain is never silently dropped.
    val countryOptions = EDIT_PROFILE_COUNTRIES
        .let { if (edCountry.isNotBlank() && edCountry !in it) it + edCountry else it }
    val provinceOptions = (PROVINCES_BY_COUNTRY[edCountry]?.keys?.toList() ?: emptyList())
        .let { if (edProvince.isNotBlank() && edProvince !in it) it + edProvince else it }
    val cityOptions = (PROVINCES_BY_COUNTRY[edCountry]?.get(edProvince) ?: emptyList())
        .let { if (edCity.isNotBlank() && edCity !in it) it + edCity else it }
    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    // Avatar / cover upload (same calls + reload as the profile header
    // pickers); the parent profile is updated too, so the photo behind
    // this page is fresh when the user goes back.
    val avatarPicker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            uploading = true
            try {
                val part = uriToImagePart(context, uri, "avatar_upload")
                if (api2.uploadAvatar(part).success) {
                    try { val rp = api2.myProfile(); if (rp.success && rp.data?.user != null) { avatarUrl = rp.data.user.profilePictureUrl ?: avatarUrl; onProfileChanged(rp.data.user) } } catch (_: Exception) {}
                    Toast.makeText(context, "Photo updated", Toast.LENGTH_SHORT).show()
                } else Toast.makeText(context, "Photo upload failed", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) { Toast.makeText(context, e.message ?: "Photo upload failed", Toast.LENGTH_SHORT).show() }
            finally { uploading = false }
        }
    }
    val coverPicker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            uploading = true
            try {
                val part = uriToImagePart(context, uri, "cover_upload")
                if (api2.uploadCover(part).success) {
                    try { val rp = api2.myProfile(); if (rp.success && rp.data?.user != null) { coverUrl = rp.data.user.coverPhotoUrl ?: coverUrl; onProfileChanged(rp.data.user) } } catch (_: Exception) {}
                    Toast.makeText(context, "Cover updated", Toast.LENGTH_SHORT).show()
                } else Toast.makeText(context, "Cover upload failed", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) { Toast.makeText(context, e.message ?: "Cover upload failed", Toast.LENGTH_SHORT).show() }
            finally { uploading = false }
        }
    }
    val pickAvatar = rememberPermissionGate(galleryPermissions(), onGranted = { avatarPicker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) })
    val pickCover = rememberPermissionGate(galleryPermissions(), onGranted = { coverPicker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) })
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("Back", maxLines = 1, softWrap = false) }
        }
        // Cover banner (web: h-32) + avatar overlapping it by 40dp.
        Box(Modifier.fillMaxWidth().height(168.dp)) {
            Box(
                Modifier.fillMaxWidth().height(128.dp)
                    .then(if (coverUrl == null) Modifier.background(Brush.linearGradient(listOf(Color(0xFF60A5FA), Color(0xFF2563EB), Color(0xFF1E40AF)))) else Modifier)
                    .clickable(enabled = !uploading) { pickCover() },
                contentAlignment = Alignment.Center,
            ) {
                if (coverUrl != null) AsyncImage(model = coverUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                    Text(if (uploading) "Uploading..." else "Tap to change cover photo", fontSize = 14.sp, color = Color.White, maxLines = 1, softWrap = false)
                }
            }
            // Avatar: photo if present, else the initial letter (web Avatar).
            Box(Modifier.align(Alignment.BottomCenter).size(80.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).border(4.dp, MaterialTheme.colorScheme.surface, CircleShape).clickable(enabled = !uploading) { pickAvatar() }, contentAlignment = Alignment.Center) {
                if (avatarUrl != null) AsyncImage(model = avatarUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                else Text(edName.trim().take(1).uppercase().ifEmpty { "?" }, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 16.dp, bottom = 24.dp)) {
            Text("Edit Profile", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
            Spacer(Modifier.height(16.dp))
            // Username (30-day lock).
            Text("Username", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(value = edUsername, onValueChange = { edUsername = it }, modifier = Modifier.fillMaxWidth().height(40.dp), enabled = usernameDaysLeft == 0, singleLine = true, shape = RoundedCornerShape(8.dp), textStyle = TextStyle(fontSize = 15.sp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next))
            if (usernameDaysLeft > 0) { Spacer(Modifier.height(4.dp)); Text("You can change this again in $usernameDaysLeft day(s).", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.height(16.dp))
            // Full Name (30-day lock).
            Text("Full Name", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(value = edName, onValueChange = { edName = it }, modifier = Modifier.fillMaxWidth().height(40.dp), enabled = nameDaysLeft == 0, singleLine = true, shape = RoundedCornerShape(8.dp), textStyle = TextStyle(fontSize = 15.sp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next))
            if (nameDaysLeft > 0) { Spacer(Modifier.height(4.dp)); Text("You can change this again in $nameDaysLeft day(s).", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.height(16.dp))
            // Bio + counter.
            Text("Bio", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(value = edBio, onValueChange = { if (it.length <= 300) edBio = it }, modifier = Modifier.fillMaxWidth(), minLines = 3, shape = RoundedCornerShape(8.dp), textStyle = TextStyle(fontSize = 15.sp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next))
            Text("${edBio.length}/300", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End)
            Spacer(Modifier.height(16.dp))
            // Gender select.
            EditProfileSelectField(label = "Gender", value = when (edGender) { "male" -> "Male"; "female" -> "Female"; "custom" -> "Custom"; else -> "" }, placeholder = "Select", options = listOf("Male", "Female", "Custom"), onPick = { edGender = it.lowercase() })
            Spacer(Modifier.height(16.dp))
            // Country -> Province -> City cascading selects.
            EditProfileSelectField(label = "Country", value = edCountry, placeholder = "Select your country", options = countryOptions, onPick = { edCountry = it; edProvince = ""; edCity = "" })
            Spacer(Modifier.height(16.dp))
            EditProfileSelectField(label = "Province", value = edProvince, placeholder = "Select", options = provinceOptions, onPick = { edProvince = it; edCity = "" })
            if (edProvince.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                EditProfileSelectField(label = "City", value = edCity, placeholder = "Select", options = cityOptions, onPick = { edCity = it })
            }
            Spacer(Modifier.height(16.dp))
            // Website.
            Text("Website", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(value = edWeb, onValueChange = { edWeb = it }, placeholder = { Text("https://", fontSize = 15.sp) }, modifier = Modifier.fillMaxWidth().height(40.dp), singleLine = true, shape = RoundedCornerShape(8.dp), textStyle = TextStyle(fontSize = 15.sp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next))
            Spacer(Modifier.height(16.dp))
            // Contact Number.
            Text("Contact Number", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(value = edPhone, onValueChange = { if (it.length <= 30) edPhone = it }, modifier = Modifier.fillMaxWidth().height(40.dp), singleLine = true, shape = RoundedCornerShape(8.dp), textStyle = TextStyle(fontSize = 15.sp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done))
            Spacer(Modifier.height(16.dp))
            Button(enabled = !edSaving, onClick = {
                // Client guards mirror the backend profileUpdateSchema
                // (zod): username 3-30 [a-zA-Z0-9_.], displayName non-blank.
                // Anything else the backend rejects comes back as a Toast.
                val uname = edUsername.trim()
                val dname = edName.trim()
                if (!Regex("^[a-zA-Z0-9_.]{3,30}$").matches(uname)) { toast("Username must be 3-30 characters: letters, numbers, underscore, or period."); return@Button }
                if (dname.isBlank()) { toast("Name can't be empty"); return@Button }
                edSaving = true
                scope.launch {
                    try {
                        val body = mutableMapOf<String, Any>(
                            "username" to uname, "displayName" to dname, "bio" to edBio,
                            "website" to edWeb, "phone" to edPhone, "province" to edProvince,
                            "city" to edCity, "country" to edCountry,
                        )
                        // The backend zod enum rejects an empty gender
                        // string — omit it when nothing is picked (same
                        // rule as profile setup).
                        if (edGender.isNotBlank()) body["gender"] = edGender
                        val r = api2.updateMe(body)
                        if (r.success) {
                            val nowIso = isoNow()
                            val updated = profile.copy(
                                username = uname, displayName = dname, bio = edBio,
                                gender = edGender.ifBlank { profile.gender },
                                website = edWeb, phone = edPhone, province = edProvince,
                                city = edCity, country = edCountry,
                                usernameChangedAt = if (uname != profile.username) nowIso else profile.usernameChangedAt,
                                displayNameChangedAt = if (dname != profile.displayName) nowIso else profile.displayNameChangedAt,
                            )
                            Toast.makeText(context, "Profile updated successfully.", Toast.LENGTH_SHORT).show()
                            onSaved(updated)
                        } else Toast.makeText(context, r.error?.message ?: "Could not save profile", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, e.message ?: "Could not save profile", Toast.LENGTH_LONG).show()
                    } finally { edSaving = false }
                }
            }, modifier = Modifier.fillMaxWidth().height(FrianzoUi.ButtonHeight), shape = RoundedCornerShape(FrianzoUi.ButtonRadius), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A))) { Text(if (edSaving) "Saving..." else "Save Changes", maxLines = 1, softWrap = false) }
        }
    }
}

// Select field (web <select> equivalent): read-only field + DropdownMenu,
// the same pattern ProfileSetupScreen uses for Gender.
@Composable
private fun EditProfileSelectField(label: String, value: String, placeholder: String, options: List<String>, onPick: (String) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(4.dp))
    Box {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            placeholder = { Text(placeholder, fontSize = 15.sp) },
            modifier = Modifier.fillMaxWidth().height(40.dp).clickable { menuOpen = true },
            readOnly = true,
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
            textStyle = TextStyle(fontSize = 15.sp),
            trailingIcon = {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            options.forEach { opt ->
                DropdownMenuItem(text = { Text(opt) }, onClick = { onPick(opt); menuOpen = false })
            }
        }
    }
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
            if (loading) item {
                // v5.12: skeleton rows on a bare first load; dots footer
                // stays for a load-more wait.
                if (shown.isEmpty()) {
                    Column { repeat(5) { SkeletonRow() } }
                } else {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { DotsLoading() }
                }
            }
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
    // v5.17: web enforces a 5MB photo limit — same rule here, so a big
    // phone photo fails fast with a clear message instead of a raw error.
    if (tmp.length() > 5 * 1024 * 1024) { try { tmp.delete() } catch (_: Exception) {}; throw Exception("Photo is too large. Maximum size is 5MB.") }
    return MultipartBody.Part.createFormData("image", tmp.name, tmp.asRequestBody(mime.toMediaTypeOrNull()))
}
