package com.frianzo.app.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Brush
import coil.compose.rememberAsyncImagePainter
import coil.compose.AsyncImagePainter
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Share
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.text.withStyle
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import android.content.Intent
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.ContentScale
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import coil.compose.AsyncImage
import retrofit2.HttpException
import com.frianzo.app.data.*
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.flow.collect
import coil.request.ImageRequest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.security.SecureRandom
import java.util.Base64

/** Step 1 screens only: Login (Google + email OTP) and Feed (read-only).
 *  No posting, liking, commenting, reels or chat yet — those are later steps. */


/** Multicolour Google "G" (drawn, no asset in repo): four coloured arcs
 * plus a blue bar, in Google's blue/red/yellow/green. */
@Composable
private fun GoogleGIcon() {
    Canvas(modifier = Modifier.size(24.dp)) {
        val stroke = 3.dp.toPx()
        val style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
        drawArc(Color(0xFF4285F4), 300f, 90f, false, style = style)
        drawArc(Color(0xFF34A853), 30f, 90f, false, style = style)
        drawArc(Color(0xFFFBBC05), 120f, 90f, false, style = style)
        drawArc(Color(0xFFEA4335), 210f, 90f, false, style = style)
        drawLine(
            Color(0xFF4285F4),
            start = androidx.compose.ui.geometry.Offset(center.x, center.y),
            end = androidx.compose.ui.geometry.Offset(size.width, center.y),
            strokeWidth = stroke,
        )
    }
}

@Composable
fun LoginScreen(api: FrianzoApi, session: SessionViewModel, cookieJar: PersistentCookieJar? = null, onLoggedIn: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var codeSent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    // LIVE SITE LOGIN (same-to-same): fixed pale blue-gray/off-white
    // background, white field/button, gray text — regardless of the
    // phone system theme. Other screens still follow the system theme.
    val loginBg = Color(0xFFF8FAFC)
    val loginFieldBg = Color.White
    val loginBorder = Color(0xFFCBD5E1)
    val loginGrey = Color(0xFF64748B)
    val loginDark = Color(0xFF0F172A)
    // Logged-out: no user country is known yet, so the live site's
    // country word defaults to Pakistan via the existing mapping.
    val loginCountry = countryDisplayName(null) ?: "Pakistan"

    fun fail(e: Exception, fallback: String) {
        // Never show Retrofit's bare "HTTP 403": map an HttpException to the
        // backend's own error code/message from the response body instead.
        message = if (e is HttpException) httpErrorMessage(e) else e.message ?: fallback
        busy = false
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = loginDark,
        unfocusedTextColor = loginDark,
        focusedContainerColor = loginFieldBg,
        unfocusedContainerColor = loginFieldBg,
        disabledContainerColor = loginFieldBg,
        focusedBorderColor = loginBorder,
        unfocusedBorderColor = loginBorder,
        focusedPlaceholderColor = loginGrey,
        unfocusedPlaceholderColor = loginGrey,
        cursorColor = loginDark,
    )

    Column(
        modifier = Modifier.fillMaxSize()
            .background(loginBg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Official blue-F logo, bold white wordmark, grey tagline (web login).
        FrianzoLogo(96.dp)
        Spacer(Modifier.height(20.dp))
        Text(
            "Frianzo",
            style = MaterialTheme.typography.displayMedium,
            color = Color.Black,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Share your moments with friends",
            style = MaterialTheme.typography.bodyMedium,
            color = loginGrey,
        )
        Spacer(Modifier.height(36.dp))

        OutlinedButton(
            enabled = !busy,
            onClick = {
                busy = true
                scope.launch {
                    try {
                        // 1. Fetch the web client id from the backend (never hard-coded).
                        val config = api.nativeConfig()
                        val clientId = config.data?.clientId
                            ?: throw Exception("Google config not available")
                        // 2. Random nonce: the backend verifies this nonce inside
                        //    the Google ID token, so the same value must be sent
                        //    to Google AND to /api/auth/google/native.
                        val nonce = randomNonce()
                        val option = GetSignInWithGoogleOption.Builder(clientId)
                            .setNonce(nonce)
                            .build()
                        val credentialManager = CredentialManager.create(context)
                        val result = credentialManager.getCredential(
                            context,
                            GetCredentialRequest.Builder().addCredentialOption(option).build(),
                        )
                        val googleIdToken = (result.credential.data
                            .getString("com.google.android.libraries.identity.googleid.BUNDLE_KEY_ID_TOKEN")
                            ?: throw Exception("No Google ID token returned"))
                        val login = api.googleNative(
                            GoogleNativeRequest(googleIdToken, nonce, deviceToken(context))
                        )
                        if (!login.success) throw Exception(login.error?.message ?: "Google login failed")
                        login.data?.token?.let { cookieJar?.saveAuthToken(it) }
                        session.markLoggedIn(login.data?.user)
                        onLoggedIn()
                    } catch (e: GetCredentialCancellationException) {
                        // User backed out of the picker: distinct from a failure.
                        fail(Exception("Google sign-in cancelled. Tap again to try."), "Google sign-in failed")
                    } catch (e: NoCredentialException) {
                        // Kept as its own case, but it is NOT the observed bug:
                        // the picker DOES show Gmail accounts, so a post-selection
                        // failure is not a missing-account case.
                        fail(Exception("No Google account is available for sign-in on this phone."), "Google sign-in failed")
                    } catch (e: GetCredentialException) {
                        // Post-selection failure: the picker showed accounts and
                        // the user chose one, yet getCredential still threw.
                        // That points at Google Cloud setup, not at the account:
                        // the server client id comes from native-config at
                        // runtime (never hard-coded) and the nonce sent to
                        // Google is the same one sent to the backend, so a
                        // mismatch between the app's package/signing key and
                        // the registered Android OAuth client is the remaining
                        // suspect — that check is parent-side (Google Cloud),
                        // not fixable from this app code. Email code still works.
                        fail(Exception("Google sign-in failed after you chose your account. Please try again, or use email code for now."), "Google sign-in failed")
                    } catch (e: HttpException) {
                        fail(e, "Google sign-in failed")
                    } catch (e: Exception) {
                        fail(e, "Google sign-in failed")
                    } finally { busy = false }
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = Color.White,
                contentColor = loginDark,
            ),
            border = androidx.compose.foundation.BorderStroke(1.dp, loginBorder),
        ) {
            GoogleGIcon()
            Spacer(Modifier.width(10.dp))
            Text("Continue with Google", style = MaterialTheme.typography.titleMedium, color = loginDark, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider(color = loginBorder, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Text("OR", style = MaterialTheme.typography.bodySmall, color = loginGrey)
            Spacer(Modifier.width(12.dp))
            HorizontalDivider(color = loginBorder, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = email, onValueChange = { email = it },
            placeholder = { Text("Enter your email") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = fieldColors,
        )
        if (codeSent) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = code, onValueChange = { code = it },
                placeholder = { Text("Enter 6-digit verification code") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = fieldColors,
            )
        }
        Spacer(Modifier.height(12.dp))
        Button(
            enabled = !busy && email.isNotBlank(),
            onClick = {
                busy = true
                scope.launch {
                    try {
                        if (!codeSent) {
                            val res = api.requestEmailCode(EmailCodeRequest(email.trim()))
                            if (!res.success) throw Exception(res.error?.message ?: "Could not send code")
                            codeSent = true
                            message = "Code sent to your email."
                        } else {
                            val res = api.verifyEmailCode(
                                EmailVerifyRequest(email.trim(), code.trim(), deviceToken(context))
                            )
                            if (!res.success) throw Exception(res.error?.message ?: "Wrong code")
                            session.markLoggedIn(res.data?.user)
                            onLoggedIn()
                        }
                    } catch (e: Exception) { fail(e, "Email login failed") }
                    finally { busy = false }
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF0F172A),
                contentColor = androidx.compose.ui.graphics.Color.White,
            ),
        ) { Text(if (codeSent) "Verify & Continue" else "Continue with Email", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        if (codeSent) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val res = api.requestEmailCode(EmailCodeRequest(email.trim()))
                            if (!res.success) throw Exception(res.error?.message ?: "Could not resend code")
                            message = "Code sent again to your email."
                        } catch (e: Exception) { fail(e, "Could not resend code") }
                        finally { busy = false }
                    }
                }) { Text("Resend code", color = loginGrey) }
                TextButton(enabled = !busy, onClick = { codeSent = false; code = ""; message = null }) { Text("Change email", color = loginGrey) }
            }
        }

        message?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        if (busy) { Spacer(Modifier.height(12.dp)); DotsLoading() }

        Spacer(Modifier.height(28.dp))
        Text(
            androidx.compose.ui.text.buildAnnotatedString {
                append("By continuing with an account located in ")
                withStyle(androidx.compose.ui.text.SpanStyle(color = FrianzoBlue)) { append(loginCountry) }
                append(", you agree to Frianzo's ")
                withStyle(androidx.compose.ui.text.SpanStyle(color = FrianzoBlue)) { append("Terms of Service") }
                append(" and acknowledge that you have read our ")
                withStyle(androidx.compose.ui.text.SpanStyle(color = FrianzoBlue)) { append("Privacy Policy") }
                append(".")
            },
            style = MaterialTheme.typography.bodySmall,
            color = loginGrey,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Text(
            "About · Privacy · Terms",
            style = MaterialTheme.typography.bodySmall,
            color = loginGrey,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Map a Retrofit HttpException to the backend's own error instead of the
 * bare "HTTP 403" Retrofit puts in Exception.message. Backend errors look
 * like {"success":false,"error":{"code":"MISSING_ORIGIN","message":"..."}},
 * so extract code/message from the error body when present.
 */
private fun httpErrorMessage(e: HttpException): String {
    val body = try { e.response()?.errorBody()?.string() } catch (_: Exception) { null }
    val code = body?.let { Regex("\"code\"\\s*:\\s*\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
    val msg = body?.let { Regex("\"message\"\\s*:\\s*\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
    return when {
        msg != null && code != null -> "$msg ($code)"
        code != null -> "$code (HTTP ${e.code()})"
        msg != null -> msg
        else -> "HTTP ${e.code()}"
    }
}

private fun randomNonce(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}


// FB-style relative time: "5m", "3h", "2d", else yyyy-MM-dd. Raw ISO
// timestamps must never be shown to the user.
private fun feedTime(createdAt: String?): String {
    if (createdAt.isNullOrBlank()) return ""
    return try {
        val instant = java.time.Instant.parse(createdAt)
        val mins = java.time.Duration.between(instant, java.time.Instant.now()).toMinutes()
        when {
            mins < 1 -> "Just now"
            mins < 60 -> "${mins}m"
            mins < 24 * 60 -> "${mins / 60}h"
            mins < 7 * 24 * 60 -> "${mins / (24 * 60)}d"
            else -> createdAt.take(10)
        }
    } catch (_: Exception) { createdAt.take(10) }
}


/**
 * PERF: feed state lives in a ViewModel, not in FeedScreen's remember{}.
 * Previously every tab switch destroyed FeedScreen, so returning to Feed
 * refetched page 1 from scratch. Now posts survive tab switches, and the
 * same instance is created at app start so the first feed page loads in
 * PARALLEL with Session /me instead of after it (see MainActivity).
 */
class FeedViewModel(private val api: FrianzoApi, val api2: FrianzoApi2) : ViewModel() {
    var posts by mutableStateOf<List<Post>>(emptyList()); private set
    var offset by mutableStateOf(0); private set
    var hasMore by mutableStateOf(true); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var likedIds by mutableStateOf<Set<String>>(emptySet()); private set
    // Live reaction type per post ("like", "love", "haha", "wow", "sad", "angry").
    // Post model carries no myReaction field, so this map is the local source.
    var reactionByPost by mutableStateOf<Map<String, String>>(emptyMap()); private set

    fun hidePost(postId: String) { posts = posts.filterNot { it.id == postId } }

    fun loadMore() {
        if (loading || !hasMore) return
        loading = true
        viewModelScope.launch {
            try {
                val res = api.feed(offset)
                if (res.success && res.data != null) {
                    posts = posts + (res.data.posts ?: emptyList())
                    offset = res.data.nextOffset
                    hasMore = res.data.hasMore
                } else error = res.error?.message ?: "Feed failed"
            } catch (e: Exception) { error = e.message ?: "Feed failed" }
            finally { loading = false }
        }
    }

    fun refresh() {
        posts = emptyList(); offset = 0; hasMore = true; error = null
        loadMore()
    }

    fun updateCommentCount(postId: String, newCount: Int) {
        posts = posts.map { if (it.id == postId) it.copy(commentCount = newCount) else it }
    }

    fun toggleLike(post: Post) { setReaction(post, "like") }

    /**
     * Live reaction behaviour: optimistic instant update, server sync wins,
     * revert on failure, in-flight taps ignored. Tap on the SAME reaction
     * again sends that type again (existing backend behaviour: it toggles
     * the reaction off, myReaction becomes null). Switching reaction keeps
     * the total count flat; adding +1; removing -1. Reaction type strings
     * are the web client's ("like","love","haha","wow","sad","angry");
     * no new endpoint is used — POST /api/posts/{id}/reaction only.
     */
    private val reactionInFlight = mutableSetOf<String>()
    fun setReaction(post: Post, type: String) {
        if (post.id in reactionInFlight) return
        reactionInFlight += post.id
        val prevLiked = likedIds
        val prevReactions = reactionByPost
        val prevPosts = posts
        val current = reactionByPost[post.id]
        val removing = current == type
        val switching = current != null && !removing
        val delta = when { removing -> -1; switching -> 0; else -> 1 }
        likedIds = if (removing) likedIds - post.id else likedIds + post.id
        reactionByPost = if (removing) reactionByPost - post.id else reactionByPost + (post.id to type)
        posts = posts.map { if (it.id == post.id) it.copy(likeCount = (it.likeCount + delta).coerceAtLeast(0)) else it }
        viewModelScope.launch {
            try {
                val r = api2.setReaction(post.id, mapOf("type" to type))
                if (r.success && r.data != null) {
                    val counts = r.data.reactionCounts
                    if (counts != null) {
                        val total = counts.values.sum()
                        posts = posts.map { if (it.id == post.id) it.copy(likeCount = total, reactionCounts = counts) else it }
                    }
                    if (r.data.myReaction != null) {
                        likedIds = likedIds + post.id
                        reactionByPost = reactionByPost + (post.id to r.data.myReaction)
                    } else {
                        likedIds = likedIds - post.id
                        reactionByPost = reactionByPost - post.id
                    }
                } else {
                    likedIds = prevLiked; reactionByPost = prevReactions; posts = prevPosts
                }
            } catch (_: Exception) {
                likedIds = prevLiked; reactionByPost = prevReactions; posts = prevPosts
            } finally { reactionInFlight -= post.id }
        }
    }
}

fun feedViewModelFactory(api: FrianzoApi, api2: FrianzoApi2) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = FeedViewModel(api, api2) as T
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FeedScreen(vm: FeedViewModel, myId: String? = null, onCreatePost: () -> Unit = {}, onPickMedia: (android.net.Uri) -> Unit = { onCreatePost() }, onAuthor: (String) -> Unit = {}, onOpenChat: (String, String) -> Unit = { _, _ -> }) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val posts = vm.posts
    val hasMore = vm.hasMore
    val loading = vm.loading
    val error = vm.error
    val likedIds = vm.likedIds
    var commentPost by remember { mutableStateOf<Post?>(null) }
    var sharePost by remember { mutableStateOf<Post?>(null) }
    var storyInfo by remember { mutableStateOf<String?>(null) }
    var detailPostId by remember { mutableStateOf<String?>(null) }
    var storyViewer by remember { mutableStateOf<StatusGroup?>(null) }
    var statusGroups by remember { mutableStateOf<List<StatusGroup>>(emptyList()) }
    var statusFailed by remember { mutableStateOf(false) }
    var myAvatar by remember { mutableStateOf<String?>(null) }
    // + BUTTON = SYSTEM GALLERY (live behaviour): photos AND videos.
    // PickVisualMedia (system photo picker) needs no Gallery permission
    // and returns a Uri; the parent opens Create with it pre-attached.
    val galleryPicker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) onPickMedia(uri)
    }
    LaunchedEffect(Unit) {
        try {
            val sf = vm.api2.statusFeed()
            if (sf.success && sf.data != null) statusGroups = sf.data.feed ?: emptyList() else statusFailed = true
        } catch (_: Exception) { statusFailed = true }
        try {
            val me = vm.api2.myProfile()
            if (me.success && me.data?.user != null) myAvatar = me.data.user.profilePictureUrl
        } catch (_: Exception) {}
    }
    // Per-author follow status, fetched once per author (existing endpoint).
    var followStates by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val followScope = rememberCoroutineScope()
    LaunchedEffect(posts) {
        for (post in posts) {
            val aid = post.author?.id ?: continue
            if (aid in followStates) continue
            followScope.launch {
                try {
                    val fs = vm.api2.followStatus(aid)
                    if (fs.success && fs.data != null) {
                        followStates = followStates + (aid to (fs.data.status ?: "none"))
                    }
                } catch (_: Exception) {}
            }
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { idx ->
                if (idx != null && vm.posts.isNotEmpty() && idx >= vm.posts.size - 3) vm.loadMore()
            }
    }
    commentPost?.let { post ->
        PostCommentsDialog(vm.api2, post,
            onClose = { commentPost = null },
            onCountChange = { newCount -> vm.updateCommentCount(post.id, newCount) })
    }
    sharePost?.let { post -> PostShareSheet(vm, post, onClose = { sharePost = null }, onOpenChat = onOpenChat) }
    detailPostId?.let { pid ->
        PostDetailScreen(vm, pid, myId, onClose = { detailPostId = null }, onAuthor = onAuthor, onOpenChat = onOpenChat)
    }
    storyViewer?.let { group ->
        StoryViewerDialog(vm.api2, group, myId, onClose = { storyViewer = null })
    }
    storyInfo?.let { name ->
        AlertDialog(
            onDismissRequest = { storyInfo = null },
            title = { Text("Stories") },
            text = { Text("$name's story will open here in a future update. Stories viewing is not in this build yet.") },
            confirmButton = { TextButton(onClick = { storyInfo = null }) { Text("OK") } },
        )
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
    ) {
        // Composer row (flat, live layout): avatar + pill -> Post screen.
        // The + button opens the Post screen too, where photo pick lives;
        // live opens a system picker first, but that picker cannot hand a
        // file to the separate Post tab, so a picker here would be a dead
        // target. Post-screen photo pick is the working equivalent.
        item {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FrianzoAvatar(myAvatar, 40.dp)
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(28.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant).clickable { onCreatePost() },
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text("What's on your mind?", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(FrianzoBlueTint).clickable {
                            galleryPicker.launch(PickVisualMediaRequest(PickVisualMedia.ImageAndVideo))
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Add photo or video", modifier = Modifier.size(24.dp), tint = FrianzoBlue)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        // Stories row: REAL statuses from GET /api/statuses when available,
        // otherwise the old feed-author placeholders (fetch failed / empty).
        item {
            if (statusGroups.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(vertical = 12.dp)) {
                    LazyRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(statusGroups) { group ->
                            val isOwn = group.userId == myId || group.user?.id == myId
                            val label = if (isOwn) "Your story" else (group.user?.displayName ?: group.user?.username ?: "User").substringBefore(" ")
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp).clickable { storyViewer = group }) {
                                val ring = if (group.hasUnseen && !isOwn) Brush.linearGradient(listOf(Color(0xFFF59E0B), Color(0xFFEC4899))) else null
                                Box(Modifier.size(62.dp).clip(CircleShape).background(ring ?: Brush.linearGradient(listOf(Color(0xFFCBD5E1), Color(0xFFCBD5E1)))).padding(3.dp), contentAlignment = Alignment.Center) {
                                    Box(Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) { FrianzoAvatar(group.user?.profilePictureUrl, 56.dp) }
                                }
                                if (isOwn) { Box(Modifier.offset(y = (-16).dp).size(20.dp).clip(CircleShape).background(FrianzoBlue), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White) } }
                                Spacer(Modifier.height(4.dp))
                                Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                            }
                        }
                        if (statusGroups.none { it.userId == myId || it.user?.id == myId }) {
                            item {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp).clickable { onCreatePost() }) {
                                    Box(contentAlignment = Alignment.BottomEnd) {
                                        Box(Modifier.size(60.dp).clip(CircleShape).background(FrianzoBlueTint), contentAlignment = Alignment.Center) { FrianzoAvatar(myAvatar, 56.dp) }
                                        Box(Modifier.size(20.dp).clip(CircleShape).background(FrianzoBlue), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White) }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text("Your story", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            } else {
                val storyAuthors = posts.mapNotNull { it.author }.distinctBy { it.id ?: it.username }.take(8)
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(vertical = 12.dp)) {
                    LazyRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        item {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp).clickable { onCreatePost() }) {
                                Box(contentAlignment = Alignment.BottomEnd) {
                                    Box(Modifier.size(60.dp).clip(CircleShape).background(FrianzoBlueTint), contentAlignment = Alignment.Center) { FrianzoAvatar(myAvatar, 56.dp) }
                                    Box(Modifier.size(20.dp).clip(CircleShape).background(FrianzoBlue), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White) }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text("Your story", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                            }
                        }
                        items(storyAuthors) { author ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp).clickable { storyInfo = author.displayName ?: author.username ?: "User" }) {
                                Box(Modifier.size(60.dp).clip(CircleShape).background(FrianzoBlue), contentAlignment = Alignment.Center) { FrianzoAvatar(author.profilePictureUrl, 56.dp) }
                                Spacer(Modifier.height(4.dp))
                                Text((author.displayName ?: author.username ?: "User").substringBefore(" "), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        items(posts, key = { it.id }) { post ->
            val liked = post.id in likedIds
            val myReaction = vm.reactionByPost[post.id]
            var menuPost by remember { mutableStateOf(false) }
            var pickerPost by remember { mutableStateOf(false) }
            // Flat full-width card, live layout: white section + 8dp grey gap.
            // No rounded shadow card. Text and photo taps are NOT nested
            // inside any parent clickable, so action buttons below can
            // never be swallowed by a card tap handler.
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.clickable { post.author?.username?.let { onAuthor(it) } }) { FrianzoAvatar(post.author?.profilePictureUrl, 40.dp) }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f).clickable { post.author?.username?.let { onAuthor(it) } }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(post.author?.displayName ?: post.author?.username ?: "User", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                            if (post.author?.isVerified == true) { Spacer(Modifier.width(4.dp)); Icon(Icons.Filled.CheckCircle, contentDescription = "Verified", modifier = Modifier.size(16.dp), tint = FrianzoBlue) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val time = feedTime(post.createdAt)
                            Text((if (time.isNotBlank()) time else "Just now") + (post.location?.let { " · $it" } ?: ""), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                            if (post.isPinned) { Spacer(Modifier.width(6.dp)); Box(Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 6.dp, vertical = 2.dp)) { Text("Pinned", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                        }
                    }
                    val authorId = post.author?.id
                    val fstatus = authorId?.let { followStates[it] }
                    if (authorId != null && authorId != myId && fstatus != "self") {
                        val ftext = when (fstatus) { "following" -> "Following"; "friends" -> "Friends"; "follow_back" -> "Follow Back"; else -> "Follow" }
                        OutlinedButton(
                            onClick = {
                                followScope.launch {
                                    try {
                                        val r = vm.api2.toggleFollow(authorId)
                                        if (r.success && r.data != null) {
                                            val ns = r.data.friendStatus ?: if (r.data.following || r.data.isFollowing) "following" else "none"
                                            followStates = followStates + (authorId to ns)
                                        }
                                    } catch (_: Exception) {}
                                }
                            },
                            modifier = Modifier.height(36.dp),
                            shape = RoundedCornerShape(28.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                        ) { Text(ftext, fontSize = 13.sp, color = FrianzoBlue) }
                        Spacer(Modifier.width(8.dp))
                    }
                    Box {
                        IconButton(onClick = { menuPost = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    if (menuPost) {
                        PostMenuSheet(vm, post, myId, followStates[post.author?.id], onClose = { menuPost = false }, onAuthor = onAuthor, onShare = { sharePost = post }, onComments = { commentPost = post }, onDetail = { detailPostId = post.id })
                    }
                    // Always-visible Hide X, live layout.
                    IconButton(onClick = { followScope.launch { try { vm.api2.hidePost(post.id) } catch (_: Exception) {} ; vm.hidePost(post.id) } }) { Icon(Icons.Filled.Close, contentDescription = "Hide", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                // Text tap -> comments dialog (no post-detail screen exists
                // in the app yet, so this is the working tap target).
                post.content?.let { Text(it, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 12.dp).clickable { detailPostId = post.id }) }
                post.imageUrl?.let {
                    Spacer(Modifier.height(10.dp))
                    PostPhoto(it, onClick = { detailPostId = post.id })
                }
                Spacer(Modifier.height(10.dp))
                // Counts row, live style: up to 3 overlapping reaction icons + total, shares pill.
                if (post.likeCount > 0 || post.commentCount > 0 || post.shareCount > 0) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (post.likeCount > 0) {
                            val topReactions = (post.reactionCounts?.entries?.sortedByDescending { it.value }?.map { it.key } ?: listOfNotNull(vm.reactionByPost[post.id])).take(3)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                topReactions.forEachIndexed { idx, rtype ->
                                    Box(Modifier.offset(x = (-7 * idx).dp).size(24.dp).clip(CircleShape).background(Color.White).padding(1.dp), contentAlignment = Alignment.Center) {
                                        Icon(reactionIcon(rtype), contentDescription = null, modifier = Modifier.size(22.dp), tint = reactionColor(rtype))
                                    }
                                }
                            }
                            Spacer(Modifier.width(4.dp))
                            Text("${post.likeCount}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.weight(1f))
                        if (post.commentCount > 0) Text("${post.commentCount} comments", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (post.shareCount > 0) { Spacer(Modifier.width(8.dp)); Box(Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 8.dp, vertical = 2.dp)) { Text("${post.shareCount} shares", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                HorizontalDivider(color = FrianzoBorder)
                if (pickerPost) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.Center) {
                        Surface(shape = RoundedCornerShape(28.dp), tonalElevation = 8.dp, shadowElevation = 8.dp) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                reactionOptions().forEach { opt ->
                                    Icon(opt.icon, contentDescription = opt.label, modifier = Modifier.size(40.dp).clickable { pickerPost = false; vm.setReaction(post, opt.type) }, tint = opt.color)
                                }
                            }
                        }
                    }
                }
                // Icon-only action row, live style: three flex-1 buttons.
                // Like is a thumbs-up (NOT a heart — heart is reels-only on
                // live). Tap toggles; 400ms long-press opens the picker.
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Box(
                        Modifier.weight(1f).height(48.dp).combinedClickable(
                            onClick = { vm.toggleLike(post) },
                            onLongClick = { pickerPost = true },
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.ThumbUp, contentDescription = "Like",
                            modifier = Modifier.size(28.dp),
                            tint = if (liked) FrianzoBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box(Modifier.weight(1f).height(48.dp).clickable { commentPost = post }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = "Comments", modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box(Modifier.weight(1f).height(48.dp).clickable { sharePost = post }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Share, contentDescription = "Share", modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        item {
            if (error != null) Text(error!!, modifier = Modifier.padding(16.dp))
            if (loading) {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { DotsLoading() }
            } else if (hasMore) {
                OutlinedButton(onClick = { vm.loadMore() }, modifier = Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(28.dp)) { Text("Load more") }
            } else if (posts.isEmpty()) {
                Text("No posts yet", modifier = Modifier.padding(16.dp))
            } else {
                Text("No more posts", modifier = Modifier.padding(16.dp))
            }
        }
    }
}

private data class ReactionOption(val type: String, val label: String, val icon: ImageVector, val color: Color)

private fun reactionOptions(): List<ReactionOption> = listOf(
    ReactionOption("like", "Like", Icons.Outlined.ThumbUp, Color(0xFF2563EB)),
    ReactionOption("love", "Love", Icons.Filled.Favorite, Color(0xFFDC2626)),
    ReactionOption("haha", "Haha", Icons.Filled.SentimentSatisfied, Color(0xFFCA8A04)),
    ReactionOption("wow", "Wow", Icons.Filled.SentimentVerySatisfied, Color(0xFFCA8A04)),
    ReactionOption("sad", "Sad", Icons.Filled.SentimentDissatisfied, Color(0xFFCA8A04)),
    ReactionOption("angry", "Angry", Icons.Filled.SentimentVeryDissatisfied, Color(0xFFEA580C)),
)

private fun reactionIcon(type: String?): ImageVector =
    reactionOptions().firstOrNull { it.type == type }?.icon ?: Icons.Outlined.ThumbUp

@Composable
private fun reactionColor(type: String?): Color =
    reactionOptions().firstOrNull { it.type == type }?.color ?: MaterialTheme.colorScheme.onSurfaceVariant

@Composable
private fun ReactionPickerRow(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        reactionOptions().forEach { opt ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onPick(opt.type) }) {
                Icon(opt.icon, contentDescription = opt.label, modifier = Modifier.size(40.dp), tint = opt.color)
                Text(opt.label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TextButton(onClick = onDismiss) { Text("Close") }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun PostShareSheet(vm: FeedViewModel, post: Post, onClose: () -> Unit, onOpenChat: (String, String) -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    var dmBusy by remember { mutableStateOf(false) }
    var reposted by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf("https://frianzo.online/post/${post.id}") }
    LaunchedEffect(post.id) {
        try {
            val r = vm.api2.createShareLink(mapOf("type" to "post", "id" to post.id))
            val code = r.data?.code
            if (r.success && !code.isNullOrBlank()) link = "https://frianzo.online/s/$code"
        } catch (_: Exception) {}
    }
    fun record(channel: String) { scope.launch { try { vm.api2.recordShareEvent(mapOf("type" to "post", "id" to post.id, "channel" to channel)) } catch (_: Exception) {} } }
    fun openUrl(url: String, channel: String) { record(channel); try { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) } catch (_: Exception) {} }
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("Share", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(link, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Button(onClick = { clipboard.setText(AnnotatedString(link)); copied = true; record("copy") }, modifier = Modifier.fillMaxWidth()) { Text(if (copied) "Copied!" else "Copy link") }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                ShareTile("WhatsApp", Color(0xFF25D366), "W") { openUrl("https://wa.me/?text=" + android.net.Uri.encode(link), "whatsapp") }
                ShareTile("WA Status", Color(0xFF25D366), "W") { openUrl("whatsapp://send?text=" + android.net.Uri.encode(link), "whatsapp_status") }
                ShareTile("Messenger", Color(0xFF0084FF), "M") { openUrl("fb-messenger://share?link=" + android.net.Uri.encode(link), "messenger") }
                ShareTile("Facebook", Color(0xFF1877F2), "f") { openUrl("https://www.facebook.com/sharer/sharer.php?u=" + android.net.Uri.encode(link), "facebook") }
                ShareTile("FB Story", Color(0xFF1877F2), "f") { openUrl("fb://story/camera?attachment_url=" + android.net.Uri.encode(link), "facebook_story") }
                ShareTile("SMS", Color(0xFF34C759), "S") { openUrl("sms:?body=" + android.net.Uri.encode(link), "sms") }
                ShareTile("More", FrianzoBlue, "+") { val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, link) }; context.startActivity(Intent.createChooser(intent, "Share post")) }
            }
            Spacer(Modifier.height(8.dp))
            val authorId = post.author?.id
            if (authorId != null) {
                TextButton(enabled = !dmBusy, onClick = {
                    dmBusy = true; record("dm")
                    scope.launch {
                        try {
                            val res = vm.api2.createConversation(com.frianzo.app.data.CreateConversationRequest(authorId))
                            val convId = res.data?.id
                            if (convId != null) { onClose(); onOpenChat(convId, post.author?.displayName ?: post.author?.username ?: "User") }
                        } catch (_: Exception) {}
                        dmBusy = false
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Send in Frianzo DM") }
            }
            TextButton(onClick = {
                scope.launch {
                    try { val r = vm.api2.repostPost(post.id, mapOf("content" to "")); if (r.success) { reposted = true; record("repost"); vm.refresh() } } catch (_: Exception) {}
                }
            }, modifier = Modifier.fillMaxWidth()) { Text(if (reposted) "Reposted!" else "Repost to your feed") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ShareTile(label: String, color: Color, letter: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onClick() }.padding(4.dp)) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
            Text(letter, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PostCommentsDialog(api2: FrianzoApi2, post: Post, myId: String? = null, onClose: () -> Unit, onCountChange: (Int) -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose) {
        CommentSection(api2, post.id, post.commentCount, myId, onCountChange)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CommentSection(api2: FrianzoApi2, postId: String, initialCount: Int, myId: String? = null, onCountChange: (Int) -> Unit = {}) {
    val context = LocalContext.current
    var comments by remember { mutableStateOf<List<Comment>>(emptyList()) }
    var draft by rememberSaveable { mutableStateOf("") }
    var replyTo by remember { mutableStateOf<Comment?>(null) }
    var imageUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var count by remember { mutableStateOf(initialCount) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri -> if (uri != null) imageUri = uri }
    fun load() {
        scope.launch {
            try {
                val res = api2.postComments(postId)
                if (res.success && res.data != null) comments = res.data.comments else error = res.error?.message
            } catch (e: Exception) { error = e.message }
        }
    }
    LaunchedEffect(postId) { load() }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text("Comments", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxWidth().height(380.dp)) {
            items(comments, key = { it.id }) { c -> CommentRow(api2, c, myId, 0, onReply = { replyTo = it }, onChanged = { load() }) }
            if (comments.isEmpty() && error == null) item { Text("No comments yet") }
            if (error != null) item { Text(error!!) }
        }
        if (replyTo != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Replying to @${replyTo!!.author?.username ?: "User"}", fontSize = 12.sp, color = FrianzoBlue)
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { replyTo = null }) { Text("Cancel") }
            }
        }
        if (imageUri != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Photo attached", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { imageUri = null }) { Text("Remove") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) }) { Icon(Icons.Filled.Add, contentDescription = "Attach photo", tint = FrianzoBlue) }
            OutlinedTextField(value = draft, onValueChange = { draft = it }, label = { Text("Write a comment") }, modifier = Modifier.weight(1f), singleLine = true)
            Spacer(Modifier.width(8.dp))
            Button(enabled = draft.isNotBlank() || imageUri != null, onClick = {
                val text = draft; val uri = imageUri; val parent = replyTo?.id; draft = ""; imageUri = null; replyTo = null
                scope.launch {
                    try {
                        val ok = if (uri != null) {
                            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            if (bytes == null) false else {
                                val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                                val body = okhttp3.RequestBody.create(mime.toMediaTypeOrNull(), bytes)
                                val part = okhttp3.MultipartBody.Part.createFormData("image", "comment.jpg", body)
                                val contentBody = okhttp3.RequestBody.create("text/plain".toMediaTypeOrNull(), text)
                                val parentBody = parent?.let { okhttp3.RequestBody.create("text/plain".toMediaTypeOrNull(), it) }
                                api2.addPostCommentImage(postId, contentBody, parentBody, part).success
                            }
                        } else {
                            api2.addPostComment(postId, CommentCreateRequest(text, parent)).success
                        }
                        if (ok) { load(); count += 1; onCountChange(count) } else error = "Could not send comment"
                    } catch (e: Exception) { error = e.message; draft = text }
                }
            }) { Text("Send") }
        }
    }
}

@Composable
private fun CommentRow(api2: FrianzoApi2, c: Comment, myId: String?, depth: Int, onReply: (Comment) -> Unit, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var liked by remember { mutableStateOf(c.myReaction != null) }
    var likeCount by remember { mutableStateOf(c.reactionCount) }
    var reported by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = (depth * 24).dp, top = 6.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FrianzoAvatar(c.author?.profilePictureUrl, 32.dp)
            Spacer(Modifier.width(8.dp))
            Text(c.author?.displayName ?: c.author?.username ?: "User", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        Text(c.content ?: "", fontSize = 14.sp, modifier = Modifier.padding(start = 40.dp))
        c.mediaUrl?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth().padding(start = 40.dp, top = 4.dp)) }
        Row(Modifier.padding(start = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                liked = !liked; likeCount += if (liked) 1 else -1
                scope.launch { try { api2.setCommentReaction(c.id, mapOf("type" to "like")) } catch (_: Exception) {} }
            }) { Text(if (liked) "Liked · $likeCount" else "Like · $likeCount", fontSize = 12.sp) }
            TextButton(onClick = { onReply(c) }) { Text("Reply", fontSize = 12.sp) }
            TextButton(onClick = { scope.launch { try { if (api2.reportComment(c.id, mapOf("reason" to "other")).success) reported = true } catch (_: Exception) {} } }) { Text(if (reported) "Reported" else "Report", fontSize = 12.sp) }
            if (c.author?.id != null && c.author?.id == myId) {
                TextButton(onClick = { scope.launch { try { if (api2.deleteComment(c.id).success) onChanged() } catch (_: Exception) {} } }) { Text("Delete", fontSize = 12.sp, color = Color.Red) }
            }
        }
        c.replies.forEach { r -> CommentRow(api2, r, myId, (depth + 1).coerceAtMost(2), onReply, onChanged) }
        HorizontalDivider()
    }
}


@Composable
private fun PostPhoto(url: String, onClick: () -> Unit = {}) {
    var reload by remember { mutableStateOf(0) }
    key(reload) {
        val painter = rememberAsyncImagePainter(model = ImageRequest.Builder(LocalContext.current).data(url).crossfade(true).build())
        when (val s = painter.state) {
            is AsyncImagePainter.State.Loading -> Box(Modifier.fillMaxWidth().height(200.dp).clickable { onClick() }, contentAlignment = Alignment.Center) { DotsLoading() }
            is AsyncImagePainter.State.Error -> Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Image failed to load", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { reload++ }) { Text("Retry") }
            }
            else -> Image(painter = painter, contentDescription = null, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth().clickable { onClick() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PostMenuSheet(vm: FeedViewModel, post: Post, myId: String?, followStatus: String?, onClose: () -> Unit, onAuthor: (String) -> Unit, onShare: () -> Unit, onComments: () -> Unit, onDetail: () -> Unit) {
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf<String?>(null) }
    var showEdit by remember { mutableStateOf(false) }
    var editText by rememberSaveable { mutableStateOf(post.content ?: "") }
    var showDelete by remember { mutableStateOf(false) }
    var showAudience by remember { mutableStateOf(false) }
    val isOwner = post.author?.id != null && post.author?.id == myId
    val clipboard = LocalClipboardManager.current
    fun act(label: String, run: suspend () -> Unit) {
        scope.launch { try { run(); notice = label } catch (e: Exception) { notice = e.message ?: "Failed" } }
    }
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("Post options", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            if (notice != null) { Text(notice!!, fontSize = 13.sp, color = FrianzoBlue); Spacer(Modifier.height(8.dp)) }
            if (post.author?.username != null) TextButton(onClick = { onClose(); onAuthor(post.author!!.username!!) }, modifier = Modifier.fillMaxWidth()) { Text("View profile", modifier = Modifier.fillMaxWidth()) }
            TextButton(onClick = { onClose(); onDetail() }, modifier = Modifier.fillMaxWidth()) { Text("View post", modifier = Modifier.fillMaxWidth()) }
            TextButton(onClick = { clipboard.setText(AnnotatedString("https://frianzo.online/post/${post.id}")); notice = "Link copied"; }, modifier = Modifier.fillMaxWidth()) { Text("Copy link", modifier = Modifier.fillMaxWidth()) }
            TextButton(onClick = { onClose(); onShare() }, modifier = Modifier.fillMaxWidth()) { Text("Share", modifier = Modifier.fillMaxWidth()) }
            TextButton(onClick = { onClose(); onComments() }, modifier = Modifier.fillMaxWidth()) { Text("Comments", modifier = Modifier.fillMaxWidth()) }
            TextButton(onClick = { act("Hidden") { vm.api2.hidePost(post.id); vm.hidePost(post.id); onClose() } }, modifier = Modifier.fillMaxWidth()) { Text("Hide post", modifier = Modifier.fillMaxWidth()) }
            TextButton(onClick = { act("Not interested") { vm.api2.hidePost(post.id); vm.hidePost(post.id); onClose() } }, modifier = Modifier.fillMaxWidth()) { Text("Not interested", modifier = Modifier.fillMaxWidth()) }
            TextButton(onClick = { act("Reported") { vm.api2.reportPost(post.id, mapOf("reason" to "other")) } }, modifier = Modifier.fillMaxWidth()) { Text("Report post", modifier = Modifier.fillMaxWidth()) }
            if (followStatus == "following" || followStatus == "friends") {
                TextButton(onClick = { act("Unfollowed") { post.author?.id?.let { vm.api2.toggleFollow(it) } } }, modifier = Modifier.fillMaxWidth()) { Text("Unfollow", modifier = Modifier.fillMaxWidth()) }
            }
            if (isOwner) {
                TextButton(onClick = { showEdit = true }, modifier = Modifier.fillMaxWidth()) { Text("Edit post", modifier = Modifier.fillMaxWidth()) }
                TextButton(onClick = { act(if (post.isPinned) "Unpinned" else "Pinned") { vm.api2.togglePin(post.id) } }, modifier = Modifier.fillMaxWidth()) { Text(if (post.isPinned) "Unpin from profile" else "Pin to profile", modifier = Modifier.fillMaxWidth()) }
                TextButton(onClick = { showAudience = true }, modifier = Modifier.fillMaxWidth()) { Text("Who can comment", modifier = Modifier.fillMaxWidth()) }
                TextButton(onClick = { showDelete = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete post", color = Color.Red, modifier = Modifier.fillMaxWidth()) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (showEdit) {
        AlertDialog(onDismissRequest = { showEdit = false }, title = { Text("Edit post") }, text = { OutlinedTextField(value = editText, onValueChange = { editText = it }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { Button(onClick = { scope.launch { try { if (vm.api2.updatePost(post.id, mapOf("content" to editText)).success) { showEdit = false; onClose(); vm.refresh() } } catch (_: Exception) {} } }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { showEdit = false }) { Text("Cancel") } })
    }
    if (showDelete) {
        AlertDialog(onDismissRequest = { showDelete = false }, title = { Text("Delete this post?") }, text = { Text("This can't be undone.") },
            confirmButton = { Button(onClick = { scope.launch { try { if (vm.api2.deletePost(post.id).success) { vm.hidePost(post.id); showDelete = false; onClose() } } catch (_: Exception) {} } }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } })
    }
    if (showAudience) {
        AlertDialog(onDismissRequest = { showAudience = false }, title = { Text("Who can comment") }, text = { Column {
            listOf("everyone" to "Everyone", "followers" to "Followers", "only_me" to "Only me").forEach { (v, label) ->
                TextButton(onClick = { scope.launch { try { vm.api2.updatePost(post.id, mapOf("commentAudience" to v)); showAudience = false } catch (_: Exception) {} } }, modifier = Modifier.fillMaxWidth()) { Text(label, modifier = Modifier.fillMaxWidth()) }
            }
        } }, confirmButton = {}, dismissButton = { TextButton(onClick = { showAudience = false }) { Text("Cancel") } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PostDetailScreen(vm: FeedViewModel, postId: String, myId: String?, onClose: () -> Unit, onAuthor: (String) -> Unit, onOpenChat: (String, String) -> Unit) {
    var detail by remember { mutableStateOf<Post?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var liked by remember { mutableStateOf(false) }
    var likeCount by remember { mutableStateOf(0) }
    LaunchedEffect(postId) {
        try {
            val r = vm.api2.postById(postId)
            if (r.success && r.data?.post != null) { detail = r.data.post; likeCount = r.data.post.likeCount; liked = r.data.post.myReaction != null }
            else error = r.error?.message ?: "Post not found"
        } catch (e: Exception) { error = e.message }
        loading = false
    }
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            if (loading) { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { DotsLoading() }; return@Column }
            if (error != null || detail == null) { Text(error ?: "Post not found", modifier = Modifier.padding(16.dp)); return@Column }
            val post = detail!!
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clickable { post.author?.username?.let { onAuthor(it) } }) { FrianzoAvatar(post.author?.profilePictureUrl, 40.dp) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(post.author?.displayName ?: post.author?.username ?: "User", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        if (post.author?.isVerified == true) { Spacer(Modifier.width(4.dp)); Icon(Icons.Filled.CheckCircle, contentDescription = "Verified", modifier = Modifier.size(16.dp), tint = FrianzoBlue) }
                    }
                    Text((feedTime(post.createdAt).ifBlank { "Just now" }) + (post.location?.let { " · $it" } ?: ""), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            post.content?.let { Text(it, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 12.dp)) }
            post.imageUrl?.let { Spacer(Modifier.height(10.dp)); PostPhoto(it) }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (post.likeCount > 0 || likeCount > 0) Text("$likeCount likes", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text("${post.commentCount} comments", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = FrianzoBorder)
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                Box(Modifier.weight(1f).height(48.dp).clickable {
                    val was = liked; liked = !liked; likeCount += if (liked) 1 else -1
                    scope.launch { try { val r = vm.api2.setReaction(post.id, mapOf("type" to "like")); if (r.success && r.data?.reactionCounts != null) likeCount = r.data.reactionCounts.values.sum() else { liked = was; likeCount += if (was) 1 else -1 } } catch (_: Exception) { liked = was } }
                }, contentAlignment = Alignment.Center) { Icon(Icons.Outlined.ThumbUp, contentDescription = "Like", modifier = Modifier.size(28.dp), tint = if (liked) FrianzoBlue else MaterialTheme.colorScheme.onSurfaceVariant) }
                Box(Modifier.weight(1f).height(48.dp), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = "Comments", modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                Box(Modifier.weight(1f).height(48.dp), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Share, contentDescription = "Share", modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            HorizontalDivider(color = FrianzoBorder)
            Spacer(Modifier.height(12.dp))
            CommentSection(vm.api2, post.id, post.commentCount, myId) { vm.updateCommentCount(post.id, it) }
        }
    }
}

@Composable
private fun StoryViewerDialog(api2: FrianzoApi2, group: StatusGroup, myId: String?, onClose: () -> Unit) {
    var idx by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val item = group.items.getOrNull(idx)
    LaunchedEffect(item?.id) { if (item != null && group.userId != myId) { scope.launch { try { api2.viewStatus(item.id) } catch (_: Exception) {} } } }
    AlertDialog(onDismissRequest = onClose, title = { Text(group.user?.displayName ?: group.user?.username ?: "Story") }, text = {
        Column(Modifier.fillMaxWidth()) {
            if (item == null) { Text("No stories") }
            else if (!item.textContent.isNullOrBlank() && item.mediaUrl.isNullOrBlank()) {
                val bg = try { Color(android.graphics.Color.parseColor(item.bgColor ?: "#1E293B")) } catch (_: Exception) { Color(0xFF1E293B) }
                Box(Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(12.dp)).background(bg), contentAlignment = Alignment.Center) { Text(item.textContent!!, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(16.dp)) }
            } else if (!item.mediaUrl.isNullOrBlank()) {
                AsyncImage(model = item.thumbnailUrl ?: item.mediaUrl, contentDescription = null, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
                if (item.mediaType == "video") Text("Video story", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else { Text(item.textContent ?: "") }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = idx > 0, onClick = { idx-- }) { Text("Back") }
                Button(enabled = idx < group.items.size - 1, onClick = { idx++ }) { Text("Next") }
            }
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text("Close") } }, dismissButton = {
        if (group.userId != myId) TextButton(onClick = { item?.let { scope.launch { try { api2.toggleStatusLike(it.id) } catch (_: Exception) {} } } }) { Text("Like") }
    })
}


fun dummyContextUsage(@Suppress("UNUSED_PARAMETER") context: Context) = Unit


