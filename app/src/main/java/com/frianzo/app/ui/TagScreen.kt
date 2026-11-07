package com.frianzo.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.frianzo.app.data.*
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
                    is TagItem.PostItem -> TagPostCard(api2, item.post, onOpenProfile = onOpenProfile, onOpenPost = onOpenPost, onTag = onTag)
                    is TagItem.ReelItem -> FeedReelCard(api2, item.reel, onOpenReel = onOpenReel, onAuthor = onOpenProfile, onTag = onTag)
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
 * v5.18: compact post card for the tag timeline (web reuses the full
 * feed PostCard; the feed's post block is inline in FeedScreen, not a
 * reusable composable, so the tag card has like / comment / tap-to-detail
 * and the full actions live on the detail screen — noted in the review).
 */
@Composable
private fun TagPostCard(api2: FrianzoApi2, post: Post, onOpenProfile: (String) -> Unit, onOpenPost: (String) -> Unit, onTag: (String) -> Unit) {
    var myReaction by remember(post.id) { mutableStateOf(post.myReaction) }
    var likeCount by remember(post.id) { mutableStateOf(post.likeCount) }
    var likeBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { post.author?.username?.let { onOpenProfile(it) } }) {
            FrianzoAvatar(post.author?.profilePictureUrl, 40.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(post.author?.displayName ?: post.author?.username ?: "User", fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (post.author?.isVerified == true) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Filled.CheckCircle, contentDescription = "Verified", tint = FrianzoBlue, modifier = Modifier.size(16.dp))
                    }
                }
                Text(notifTimeAgo(post.createdAt), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
            }
        }
        Spacer(Modifier.height(8.dp))
        post.content?.let {
            PostContentText(
                text = it, fontSizeSp = 15, color = MaterialTheme.colorScheme.onSurface,
                onAuthor = { u -> onOpenProfile(u) }, onTag = onTag,
                onTextClick = { onOpenPost(post.id) },
            )
        }
        post.imageUrl?.let {
            Spacer(Modifier.height(10.dp))
            AsyncImage(model = it, contentDescription = null, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onOpenPost(post.id) }, contentScale = ContentScale.Crop)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.clickable(enabled = !likeBusy) {
                if (likeBusy) return@clickable
                likeBusy = true
                val prevReaction = myReaction
                val prevCount = likeCount
                val removing = prevReaction != null
                myReaction = if (removing) null else "like"
                likeCount = (likeCount + if (removing) -1 else 1).coerceAtLeast(0)
                scope.launch {
                    try {
                        val r = api2.setReaction(post.id, mapOf("type" to (prevReaction ?: "like")))
                        if (r.success && r.data != null) {
                            myReaction = r.data.myReaction ?: r.data.reaction
                            r.data.likeCount?.let { likeCount = it }
                        } else { myReaction = prevReaction; likeCount = prevCount }
                    } catch (_: Exception) { myReaction = prevReaction; likeCount = prevCount }
                    finally { likeBusy = false }
                }
            }.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (myReaction != null) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, contentDescription = "Like", tint = if (myReaction != null) Color(0xFFEF4444) else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(4.dp))
                Text("$likeCount", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(16.dp))
            Row(Modifier.clickable { onOpenPost(post.id) }.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = "Comments", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(4.dp))
                Text("${post.commentCount}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
