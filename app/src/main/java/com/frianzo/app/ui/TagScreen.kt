package com.frianzo.app.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.frianzo.app.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * v5.18: tag page (web: /tag/[tag]). Header = blue # circle + #tag +
 * "N post(s)"; the timeline merges posts + reels newest-first; empty
 * state uses the web's exact words. Pagination is Load more via
 * limit/offset (the backend supports it; the web has no pagination UI).
 * No backend change — GET /api/tags/:tag already exists.
 */
private sealed class TagItem(val createdAt: String?) {
    class PostItem(val post: Post) : TagItem(post.createdAt)
    class ReelItem(val reel: Reel) : TagItem(reel.createdAt)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagScreen(
    api2: FrianzoApi2,
    tag: String,
    myId: String?,
    onBack: () -> Unit = {},
    onOpenProfile: (String) -> Unit = {},
    onOpenReel: (String) -> Unit = {},
    onOpenChat: (String, String) -> Unit = { _, _ -> },
    onOpenPost: (String) -> Unit = {},
    onTag: (String) -> Unit = {},
) {
    var posts by remember(tag) { mutableStateOf<List<Post>>(emptyList()) }
    var reels by remember(tag) { mutableStateOf<List<Reel>>(emptyList()) }
    var loading by remember(tag) { mutableStateOf(true) }
    var error by remember(tag) { mutableStateOf<String?>(null) }
    var hasMore by remember(tag) { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var offset by remember(tag) { mutableStateOf(0) }
    var totalCount by remember(tag) { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    fun loadMore() {
        if (loadingMore) return
        loadingMore = true
        scope.launch {
            try {
                val res = api2.getTag(tag, limit = 20, offset = offset)
                if (res.success && res.data != null) {
                    val d = res.data
                    posts = posts + d.posts.filter { p -> posts.none { it.id == p.id } }
                    reels = reels + d.reels.filter { r -> reels.none { it.id == r.id } }
                    offset += d.posts.size + d.reels.size
                    totalCount = posts.size + reels.size
                    hasMore = d.hasMore
                    error = null
                } else error = res.error?.message ?: "Couldn't load tag"
            } catch (e: Exception) { error = e.message ?: "Couldn't load tag" }
            finally { loadingMore = false; loading = false }
        }
    }
    LaunchedEffect(tag) { loadMore() }

    // v5.20 (M7): full re-load after an Edit save in the post menu
    // (the menu's onEdited) — resets the merged timeline and fetches
    // page 1 again, like a fresh visit to the tag.
    fun reload() {
        if (loadingMore) return
        posts = emptyList(); reels = emptyList(); offset = 0; hasMore = false; totalCount = 0; error = null; loading = true
        loadMore()
    }

    // v5.20 (M7): per-author follow status for the full post menu
    // (Unfollow row) — same pattern as FeedScreen: fetch once per
    // author, in-flight guard, stale replies cannot overwrite.
    var followStates by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var followStatusFetching by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(posts) {
        for (post in posts) {
            val aid = post.author?.id ?: continue
            if (aid in followStates || aid in followStatusFetching) continue
            followStatusFetching = followStatusFetching + aid
            scope.launch {
                try {
                    val fs = api2.followStatus(aid)
                    if (fs.success && fs.data != null) {
                        followStates = followStates + (aid to (fs.data.status ?: "none"))
                    }
                } catch (_: Exception) {}
                finally { followStatusFetching = followStatusFetching - aid }
            }
        }
    }

    val items = remember(posts, reels) {
        (posts.map { TagItem.PostItem(it) } + reels.map { TagItem.ReelItem(it) })
            .sortedByDescending { it.createdAt ?: "" }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Back", maxLines = 1, softWrap = false) }
        }
        // Web header, centred: 64px blue-tinted circle with bold #,
        // "#tag" 24px bold, "N post(s)".
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(FrianzoBlueTint), contentAlignment = Alignment.Center) {
                Text("#", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = FrianzoBlue)
            }
            Spacer(Modifier.height(8.dp))
            Text("#$tag", fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${totalCount} post${if (totalCount == 1) "" else "s"}", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f)) {
            if (loading && items.isEmpty()) {
                item { Column { repeat(5) { SkeletonRow() } } }
            }
            items(items, key = { when (it) { is TagItem.PostItem -> "p-${it.post.id}"; is TagItem.ReelItem -> "r-${it.reel.id}" } }) { item ->
                when (item) {
                    is TagItem.PostItem -> TagPostCard(api2, item.post, myId = myId, followStatus = followStates[item.post.author?.id], onOpenProfile = onOpenProfile, onOpenPost = onOpenPost, onOpenChat = onOpenChat, onTag = onTag, onHidden = { id -> posts = posts.filterNot { it.id == id }; totalCount = posts.size + reels.size }, onAudienceChanged = { id, v -> posts = posts.map { if (it.id == id) it.copy(commentAudience = v) else it } }, onEdited = { reload() })
                    is TagItem.ReelItem -> FeedReelCard(api2, item.reel, onOpenReel = onOpenReel, onAuthor = onOpenProfile, onTag = onTag, onOpenChat = onOpenChat)
                }
                HorizontalDivider()
            }
            if (error != null) {
                item {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(error!!, Modifier.weight(1f), color = FrianzoBadgeRed)
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { error = null; loadMore() }) { Text("Retry", maxLines = 1, softWrap = false) }
                    }
                }
            }
            if (hasMore && error == null) {
                item {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        OutlinedButton(onClick = { loadMore() }, enabled = !loadingMore, shape = RoundedCornerShape(28.dp)) {
                            Text(if (loadingMore) "Loading…" else "Load more", maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
            if (!loading && items.isEmpty() && error == null) {
                item {
                    // Web empty state, exact words.
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No posts yet", fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                        Spacer(Modifier.height(4.dp))
                        Text("Be the first to post with #$tag", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onBack, shape = RoundedCornerShape(28.dp)) { Text("Back to feed", maxLines = 1, softWrap = false) }
                    }
                }
            }
        }
    }
}

/**
 * v5.20 (M7): full feed post card (web PostCard parity). The old compact
 * card had like-only + comment count and no Share / ⋮ menu / reaction
 * picker / ReactionSummary / repost header / Hide. The feed's post block
 * is inline in FeedScreen, not a reusable composable, so this card
 * mirrors it piece by piece — and reuses the SAME shared sheets
 * (PostShareSheet + PostMenuSheet, public in Screens.kt) instead of
 * copies. Deliberately NOT copied from the feed: the Follow button
 * (needs feed-style follow state the tag list does not carry) and the
 * background-style gradient tile (L-2/"with" line likewise stay
 * feed-only for now) — the M7 list is Share, menu, picker, summary,
 * repost header, Hide.
 */
@Composable
private fun TagPostCard(api2: FrianzoApi2, post: Post, myId: String?, followStatus: String? = null, onOpenProfile: (String) -> Unit, onOpenPost: (String) -> Unit, onOpenChat: (String, String) -> Unit = { _, _ -> }, onTag: (String) -> Unit, onHidden: (String) -> Unit = {}, onAudienceChanged: (String, String) -> Unit = { _, _ -> }, onEdited: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var myReaction by remember(post.id) { mutableStateOf(post.myReaction) }
    var likeCount by remember(post.id) { mutableStateOf(post.likeCount) }
    var shareCount by remember(post.id) { mutableStateOf(post.shareCount) }
    var reactionCounts by remember(post.id) { mutableStateOf(post.reactionCounts) }
    var reactionBusy by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var shareOpen by remember { mutableStateOf(false) }
    // Local summary counts move with the reaction (the server copy is
    // authoritative on the next tag load; the detail screen does the
    // same optimistic update).
    fun bumpCounts(counts: Map<String, Int>?, old: String?, new: String?): Map<String, Int>? {
        if (old == new) return counts
        val m = (counts ?: emptyMap()).toMutableMap()
        if (old != null) { val v = (m[old] ?: 1) - 1; if (v <= 0) m.remove(old) else m[old] = v }
        if (new != null) m[new] = (m[new] ?: 0) + 1
        return m
    }
    fun applyReaction(type: String) {
        if (reactionBusy) return
        reactionBusy = true
        val wasReaction = myReaction; val wasCount = likeCount; val wasCounts = reactionCounts
        val removing = myReaction == type
        val switching = myReaction != null && !removing
        myReaction = if (removing) null else type
        likeCount = (likeCount + if (removing) -1 else if (switching) 0 else 1).coerceAtLeast(0)
        reactionCounts = bumpCounts(reactionCounts, wasReaction, myReaction)
        scope.launch {
            try {
                val r = api2.setReaction(post.id, mapOf("type" to type))
                if (r.success && r.data != null) {
                    val effR = r.data.myReaction ?: r.data.reaction
                    val effC = r.data.likeCount ?: r.data.reactionCounts?.values?.sum()
                    if (effC != null) likeCount = effC
                    r.data.reactionCounts?.let { reactionCounts = it }
                    myReaction = effR
                } else { myReaction = wasReaction; likeCount = wasCount; reactionCounts = wasCounts }
            } catch (_: Exception) { myReaction = wasReaction; likeCount = wasCount; reactionCounts = wasCounts }
            finally { reactionBusy = false }
        }
    }
    // Same as the feed/detail tap: sends the CURRENT reaction (toggles
    // it off server-side) or "like" when there is none.
    fun toggleLike() {
        if (reactionBusy) return
        applyReaction(myReaction ?: "like")
    }
    fun toast(msg: String) { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        // Repost header (web PostCard "Reposted · time").
        if (post.originalPost != null) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Repeat, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text("Reposted · ${notifTimeAgo(post.createdAt)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clickable { post.author?.username?.let { onOpenProfile(it) } }) { FrianzoAvatar(post.author?.profilePictureUrl, 40.dp) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).clickable { post.author?.username?.let { onOpenProfile(it) } }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(post.author?.displayName ?: post.author?.username ?: "User", modifier = Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    if (post.author?.isVerified == true) { Spacer(Modifier.width(4.dp)); Icon(Icons.Filled.CheckCircle, contentDescription = "Verified", modifier = Modifier.size(16.dp), tint = FrianzoBlue) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val time = notifTimeAgo(post.createdAt)
                    Text((if (time.isNotBlank()) time else "Just now") + (post.location?.let { " · $it" } ?: ""), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    if (post.isPinned) { Spacer(Modifier.width(6.dp)); Box(Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 6.dp, vertical = 2.dp)) { Text("Pinned", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (menuOpen) {
                PostMenuSheet(api2, post, myId, followStatus, onClose = { menuOpen = false }, onAuthor = onOpenProfile, onShare = { shareOpen = true }, onComments = { onOpenPost(post.id) }, onDetail = { onOpenPost(post.id) }, onHidden = onHidden, onAudienceChanged = onAudienceChanged, onEdited = onEdited)
            }
            // Always-visible Hide X, same as the feed card: only hide
            // locally when the server hide succeeded.
            IconButton(onClick = { scope.launch { try { val r = api2.hidePost(post.id); if (r.success) onHidden(post.id) else toast(r.error?.message ?: "Could not hide post") } catch (e: Exception) { toast(e.message ?: "Could not hide post") } } }) { Icon(Icons.Filled.Close, contentDescription = "Hide", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        post.content?.let {
            PostContentText(it, fontSizeSp = 15, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 12.dp), onAuthor = onOpenProfile, onTag = onTag, onTextClick = { onOpenPost(post.id) })
        }
        post.imageUrl?.let {
            Spacer(Modifier.height(10.dp))
            AsyncImage(model = it, contentDescription = null, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(12.dp)).clickable { onOpenPost(post.id) }, contentScale = ContentScale.Crop)
        }
        post.originalPost?.let { orig -> Spacer(Modifier.height(10.dp)); TagOriginalPostCard(orig, onAuthor = onOpenProfile, onDetail = { onOpenPost(orig.id) }) }
        Spacer(Modifier.height(10.dp))
        // ReactionSummary, same as the feed counts row: up to 3
        // overlapping reaction icons + total, comments, shares pill.
        if (likeCount > 0 || post.commentCount > 0 || shareCount > 0) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (likeCount > 0) {
                    val topReactions = (reactionCounts?.entries?.sortedByDescending { it.value }?.map { it.key } ?: listOfNotNull(myReaction)).take(3)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        topReactions.forEachIndexed { idx, rtype ->
                            Box(Modifier.zIndex((topReactions.size - idx).toFloat()).offset(x = (-7 * idx).dp).size(24.dp).clip(CircleShape).background(Color.White).padding(1.dp), contentAlignment = Alignment.Center) {
                                ReactionIcon(rtype, 22.dp)
                            }
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    Text("$likeCount", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                if (post.commentCount > 0) Text("${post.commentCount} comments", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (shareCount > 0) { Spacer(Modifier.width(8.dp)); Box(Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 8.dp, vertical = 2.dp)) { Text("$shareCount shares", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            }
            Spacer(Modifier.height(8.dp))
        }
        HorizontalDivider(color = FrianzoBorder)
        if (pickerOpen) {
            // Web picker above the Like button, left-aligned, icons 40
            // — same in-flow row as the feed card.
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.Start) {
                ReactionPickerPill(40.dp, reactionTypes()) { type -> pickerOpen = false; applyReaction(type) }
            }
        }
        // Icon-only action row, same as the feed: Like is a thumbs-up
        // (400ms hold opens the picker, tap toggles), Comment opens the
        // detail, Share opens the post share sheet.
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Box(
                Modifier.weight(1f).height(48.dp).pointerInput(post.id) {
                    detectTapGestures(onPress = {
                        var opened = false
                        val timer = scope.launch { delay(400); opened = true; pickerOpen = true }
                        val released = tryAwaitRelease()
                        timer.cancel()
                        if (released && !opened) toggleLike()
                    })
                },
                contentAlignment = Alignment.Center,
            ) {
                when (myReaction) {
                    null -> Icon(Icons.Outlined.ThumbUp, contentDescription = "Like", modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    "like" -> Icon(Icons.Filled.ThumbUp, contentDescription = "Like", modifier = Modifier.size(24.dp), tint = FrianzoBlue)
                    else -> ReactionIcon(myReaction, 24.dp)
                }
            }
            Box(Modifier.weight(1f).height(48.dp).clickable { onOpenPost(post.id) }, contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = "Comments", modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box(Modifier.weight(1f).height(48.dp).clickable { shareOpen = true }, contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Share, contentDescription = "Share", modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (shareOpen) {
        PostShareSheet(api2, post, onClose = { shareOpen = false }, onOpenChat = onOpenChat, onShared = { shareCount += 1 })
    }
}

// v5.20 (M7): the repost's original-post box, same as the feed's
// OriginalPostCard (private in Screens.kt) — bordered inner card with
// the original author, text and photo.
@Composable
private fun TagOriginalPostCard(original: Post, onAuthor: (String) -> Unit = {}, onDetail: () -> Unit = {}) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, FrianzoBorder, RoundedCornerShape(12.dp))
            .clickable { onDetail() }
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clickable { original.author?.username?.let { onAuthor(it) } }) { FrianzoAvatar(original.author?.profilePictureUrl, 28.dp) }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(original.author?.displayName ?: original.author?.username ?: "User", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                Text(notifTimeAgo(original.createdAt).ifBlank { "Just now" }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
        }
        original.content?.let { Spacer(Modifier.height(8.dp)); Text(it, fontSize = 14.sp) }
        original.imageUrl?.let { Spacer(Modifier.height(8.dp)); AsyncImage(model = it, contentDescription = null, modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop) }
    }
}
