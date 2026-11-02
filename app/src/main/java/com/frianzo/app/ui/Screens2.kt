package com.frianzo.app.ui

import android.net.Uri
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.frianzo.app.data.*
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

// ---------- REELS: vertical list, tap video to play/pause, like + view ----------
@Composable
fun ReelsScreen(api2: FrianzoApi2) {
    var reels by remember { mutableStateOf<List<Reel>>(emptyList()) }
    var offset by remember { mutableStateOf(0) }
    var hasMore by remember { mutableStateOf(true) }
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
                    reels = reels + res.data.reels
                    offset += res.data.reels.size
                    hasMore = res.data.reels.isNotEmpty()
                } else error = res.error?.message ?: "Reels failed"
            } catch (e: Exception) { error = e.message ?: "Reels failed" }
            finally { loading = false }
        }
    }
    LaunchedEffect(Unit) { load() }
    LazyColumn(Modifier.fillMaxSize().background(FrianzoPageBackground), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(reels, key = { it.id }) { reel ->
            var liked by remember { mutableStateOf(reel.liked) }
            var likes by remember { mutableStateOf(reel.likeCount) }
            var playing by remember { mutableStateOf(false) }
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FrianzoAvatar(reel.author?.profilePictureUrl, 40.dp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("@${reel.author?.username ?: ""}", style = MaterialTheme.typography.titleMedium)
                            Text("${reel.viewCount} views", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    reel.caption?.let { Text(it) }
                    Spacer(Modifier.height(8.dp))
                    if (reel.videoUrl != null) {
                        AndroidView(
                            factory = { ctx -> VideoView(ctx).apply { setVideoURI(Uri.parse(reel.videoUrl)) } },
                            modifier = Modifier.fillMaxWidth().height(420.dp).clip(RoundedCornerShape(12.dp)).clickable {
                                playing = !playing
                            },
                            update = { view -> if (playing) view.start() else view.pause() },
                        )
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
                        }) { Text(if (liked) "♥ $likes" else "♡ $likes") }
                        Spacer(Modifier.width(8.dp))
                        Text("${reel.commentCount} comments")
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(shape = RoundedCornerShape(28.dp), onClick = {
                            scope.launch { try { api2.recordReelView(reel.id, mapOf("watchSec" to 1)) } catch (_: Exception) {} }
                        }) { Text("Viewed") }
                    }
                }
            }
        }
        item {
            if (error != null) Text(error!!, Modifier.padding(16.dp))
            if (loading) Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else if (hasMore) OutlinedButton(onClick = { load() }, modifier = Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(28.dp)) { Text("Load more reels") }
        }
    }
}

// ---------- CREATE POST: text + optional photo ----------
@Composable
fun CreatePostScreen(api2: FrianzoApi2, onPosted: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> imageUri = uri }
    Column(Modifier.fillMaxSize().background(FrianzoPageBackground).padding(16.dp)) {
        Text("New post", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("What's on your mind?") }, modifier = Modifier.fillMaxWidth().height(140.dp), shape = RoundedCornerShape(12.dp))
        Spacer(Modifier.height(8.dp))
        OutlinedButton(shape = RoundedCornerShape(28.dp), onClick = { picker.launch("image/*") }) { Text(if (imageUri == null) "Add photo (optional)" else "Photo selected ✓") }
        Spacer(Modifier.height(12.dp))
        Button(enabled = !busy && (text.isNotBlank() || imageUri != null), onClick = {
            busy = true
            scope.launch {
                try {
                    var part: MultipartBody.Part? = null
                    if (imageUri != null) {
                        val input = context.contentResolver.openInputStream(imageUri!!)!!
                        val tmp = File(context.cacheDir, "post_upload.jpg")
                        tmp.outputStream().use { out -> input.copyTo(out) }
                        part = MultipartBody.Part.createFormData("image", tmp.name, tmp.asRequestBody("image/jpeg".toMediaTypeOrNull()))
                    }
                    val res = api2.createPost(text.toRequestBody("text/plain".toMediaTypeOrNull()), part)
                    if (!res.success) throw Exception(res.error?.message ?: "Post failed")
                    message = "Posted ✓"
                    onPosted()
                } catch (e: Exception) { message = e.message ?: "Post failed" }
                finally { busy = false }
            }
        }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(28.dp)) { Text("Post") }
        message?.let { Spacer(Modifier.height(12.dp)); Text(it) }
        if (busy) { Spacer(Modifier.height(12.dp)); CircularProgressIndicator() }
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
    LazyColumn(Modifier.fillMaxSize().background(FrianzoPageBackground), contentPadding = PaddingValues(vertical = 8.dp)) {
        item { Text("Chats", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp)) }
        items(list, key = { it.id }) { c ->
            val other = c.otherUser
            val last = c.lastMessage?.content ?: c.messages?.firstOrNull()?.content ?: ""
            ListItem(
                headlineContent = { Text(other?.displayName ?: other?.username ?: "Chat", style = MaterialTheme.typography.titleMedium) },
                supportingContent = { Text(last, style = MaterialTheme.typography.bodySmall) },
                leadingContent = { FrianzoAvatar(other?.profilePictureUrl, 48.dp) },
                trailingContent = { if (c.unreadCount > 0) Badge(containerColor = FrianzoBadgeRed) { Text("${c.unreadCount}") } },
                colors = ListItemDefaults.colors(containerColor = Color.White),
                modifier = Modifier.clickable { onOpen(c.id, other?.displayName ?: other?.username ?: "") },
            )
            HorizontalDivider()
        }
        if (error != null) item { Text(error!!, Modifier.padding(16.dp)) }
    }
}

@Composable
fun ChatThreadScreen(api2: FrianzoApi2, conversationId: String, title: String, myId: String?) {
    var messages by remember { mutableStateOf<List<ConversationMessage>>(emptyList()) }
    var draft by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun load() {
        scope.launch {
            try {
                val res = api2.messages(conversationId)
                // Backend already returns messages chronological (old -> new);
                // reverse once so newest-first pairs with reverseLayout below.
                if (res.success && res.data != null) messages = res.data.messages.reversed()
            } catch (_: Exception) {}
        }
    }
    LaunchedEffect(conversationId) { load() }
    Column(Modifier.fillMaxSize().background(FrianzoPageBackground)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = true) {
            items(messages, key = { it.id ?: "" }) { m ->
                val mine = m.senderId == myId
                Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
                    Surface(color = if (mine) FrianzoBlue else Color.White, shape = RoundedCornerShape(16.dp)) {
                        Text(m.content ?: "", Modifier.padding(12.dp), color = if (mine) Color.White else FrianzoTextDark)
                    }
                }
            }
        }
        Row(Modifier.background(Color.White).padding(8.dp)) {
            OutlinedTextField(value = draft, onValueChange = { draft = it }, modifier = Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(28.dp))
            Spacer(Modifier.width(8.dp))
            Button(shape = RoundedCornerShape(28.dp), onClick = {
                val text = draft; draft = ""
                scope.launch {
                    try { api2.sendMessage(conversationId, SendMessageRequest(text)); load() } catch (_: Exception) { draft = text }
                }
            }) { Text("Send") }
        }
    }
}

// ---------- NOTIFICATIONS ----------
@Composable
fun NotificationsScreen(api2: FrianzoApi2) {
    var items by remember { mutableStateOf<List<NotificationItem>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
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
    Column(Modifier.fillMaxSize().background(FrianzoPageBackground)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Notifications", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { scope.launch { try { api2.markAllNotificationsRead(); load() } catch (_: Exception) {} } }) { Text("Mark all read") }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(items, key = { it.id }) { n ->
                ListItem(
                    headlineContent = { Text("${n.actor?.displayName ?: n.actor?.username ?: ""} ${n.type ?: ""}", style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text(n.detail ?: "", style = MaterialTheme.typography.bodySmall) },
                    leadingContent = { FrianzoAvatar(n.actor?.profilePictureUrl, 48.dp) },
                    trailingContent = { if (!n.read) Badge(containerColor = FrianzoBadgeRed) { Text("new") } },
                    colors = ListItemDefaults.colors(containerColor = Color.White),
                    modifier = Modifier.clickable {
                        scope.launch { try { api2.markNotificationRead(n.id); load() } catch (_: Exception) {} }
                    },
                )
                HorizontalDivider()
            }
            if (error != null) item { Text(error!!, Modifier.padding(16.dp)) }
        }
    }
}

// ---------- SEARCH (users) + PROFILE ----------
@Composable
fun SearchScreen(api2: FrianzoApi2, onUser: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PublicProfile>>(emptyList()) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().background(FrianzoPageBackground)) {
        OutlinedTextField(
            value = q, onValueChange = { q = it },
            label = { Text("Search people") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(12.dp),
        )
        Button(onClick = {
            scope.launch {
                try {
                    val res = api2.searchUsers(q)
                    if (res.success && res.data != null) results = res.data.users
                } catch (_: Exception) {}
            }
        }, modifier = Modifier.padding(horizontal = 16.dp), shape = RoundedCornerShape(28.dp)) { Text("Search") }
        LazyColumn {
            items(results, key = { it.id ?: it.username ?: "" }) { u ->
                ListItem(
                    headlineContent = { Text(u.displayName ?: "", style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text("@${u.username ?: ""}", style = MaterialTheme.typography.bodySmall) },
                    leadingContent = { FrianzoAvatar(u.profilePictureUrl, 48.dp) },
                    colors = ListItemDefaults.colors(containerColor = Color.White),
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
fun ProfileScreen(api2: FrianzoApi2, username: String?, myId: String?) {
    var profile by remember { mutableStateOf<PublicProfile?>(null) }
    var posts by remember { mutableStateOf<List<Post>>(emptyList()) }
    var following by remember { mutableStateOf(false) }
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
                        val pr = api2.userPosts(uname)
                        if (pr.success && pr.data != null) posts = pr.data.posts
                    }
                    if (res.data.user.id != null && res.data.user.id != myId) {
                        val fs = api2.followStatus(res.data.user.id)
                        if (fs.success && fs.data != null) following = fs.data.status == "following" || fs.data.status == "friends"
                    }
                } else error = res.error?.message
            } catch (e: Exception) { error = e.message }
        }
    }
    LazyColumn(Modifier.fillMaxSize().background(FrianzoPageBackground)) {
        item {
            profile?.let { p ->
                p.coverPhotoUrl?.let { AsyncImage(model = it, contentDescription = null, modifier = Modifier.fillMaxWidth().height(180.dp)) }
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    FrianzoAvatar(p.profilePictureUrl, 80.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.displayName ?: "", style = MaterialTheme.typography.titleLarge)
                        Text("@${p.username ?: ""}", style = MaterialTheme.typography.bodySmall)
                        p.bio?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        Text(listOfNotNull(p.city, countryDisplayName(p.country)).joinToString(", "), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (username != null && p.id != null && p.id != myId) {
                    Button(onClick = {
                        scope.launch {
                            try {
                                val r = api2.toggleFollow(p.id)
                                if (r.success && r.data != null) following = r.data.following || r.data.isFollowing
                            } catch (_: Exception) {}
                        }
                    }, modifier = Modifier.padding(horizontal = 16.dp).height(48.dp), shape = RoundedCornerShape(28.dp)) { Text(if (following) "Following" else "Follow") }
                }
                Spacer(Modifier.height(12.dp))
                Text("Posts", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
            } ?: run {
                if (error != null) Text(error!!, Modifier.padding(16.dp)) else Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
        items(posts, key = { it.id }) { post ->
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                Column(Modifier.padding(16.dp)) {
                    post.content?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    post.imageUrl?.let { Spacer(Modifier.height(8.dp)); AsyncImage(model = it, contentDescription = null, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))) }
                    Spacer(Modifier.height(8.dp))
                    Text("♥ ${post.likeCount}  •  💬 ${post.commentCount}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
