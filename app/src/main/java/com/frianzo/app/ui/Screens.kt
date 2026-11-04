package com.frianzo.app.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.zIndex
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
import kotlinx.coroutines.delay
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.flow.collect
import coil.request.ImageRequest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale
import java.util.TimeZone

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

    // Web handleTap: tap sends the CURRENT reaction (which toggles it
    // off server-side) or "like" when there is no reaction yet.
    fun toggleLike(post: Post) { setReaction(post, reactionByPost[post.id] ?: "like") }

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
                // Web PostCard: a post with backgroundStyle and no image
                // renders as a centred white bold text on a gradient tile
                // (min-h-56, rounded-2xl), not plain body text.
                post.content?.let {
                    if (!post.backgroundStyle.isNullOrBlank() && post.imageUrl.isNullOrBlank()) {
                        Box(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(min = 224.dp)
                                .clip(RoundedCornerShape(16.dp)).background(postBackgroundBrush(post.backgroundStyle!!)),
                            contentAlignment = Alignment.Center
                        ) { Text(it, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(24.dp).clickable { detailPostId = post.id }) }
                    } else {
                        Text(it, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 12.dp).clickable { detailPostId = post.id })
                    }
                }
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
                                    Box(Modifier.zIndex((topReactions.size - idx).toFloat()).offset(x = (-7 * idx).dp).size(24.dp).clip(CircleShape).background(Color.White).padding(1.dp), contentAlignment = Alignment.Center) {
                                        ReactionIcon(rtype, 22.dp)
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
                    // Web picker: absolute bottom-full left-0 above the Like
                    // button, white pill, icons 40, no labels below, pop +
                    // press tooltip inside ReactionPickerPill.
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.Start) {
                        ReactionPickerPill(40.dp, reactionTypes()) { type -> pickerPost = false; vm.setReaction(post, type) }
                    }
                }
                // Icon-only action row, live style: three flex-1 buttons.
                // Like is a thumbs-up (NOT a heart — heart is reels-only on
                // live). Tap toggles; 400ms long-press opens the picker.
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Box(
                        Modifier.weight(1f).height(48.dp).pointerInput(post.id) {
                            // Web ReactionButton: HOLD_TO_OPEN_MS = 400 exactly.
                            // Down starts a 400ms timer; release before it = tap
                            // (toggle current reaction / like); release after it
                            // opened the picker, so NO tap fires; a scroll/drag
                            // cancels the press, so no accidental reaction.
                            detectTapGestures(onPress = {
                                var opened = false
                                val timer = followScope.launch { delay(400); opened = true; pickerPost = true }
                                val released = tryAwaitRelease()
                                timer.cancel()
                                if (released && !opened) vm.toggleLike(post)
                            })
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        when (myReaction) {
                            null -> Icon(Icons.Outlined.ThumbUp, contentDescription = "Like", modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            "like" -> Icon(Icons.Filled.ThumbUp, contentDescription = "Like", modifier = Modifier.size(28.dp), tint = FrianzoBlue)
                            else -> ReactionIcon(myReaction, 24.dp)
                        }
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

// Web PostCard POST_BACKGROUND_CLASSES, as Compose gradients.
private fun postBackgroundBrush(style: String): Brush = when (style) {
    "sunset" -> Brush.linearGradient(listOf(Color(0xFFFB923C), Color(0xFFEC4899), Color(0xFF9333EA)))
    "ocean" -> Brush.linearGradient(listOf(Color(0xFF22D3EE), Color(0xFF3B82F6), Color(0xFF6366F1)))
    "violet" -> Brush.linearGradient(listOf(Color(0xFF8B5CF6), Color(0xFFD946EF), Color(0xFFEC4899)))
    "mint" -> Brush.linearGradient(listOf(Color(0xFF6EE7B7), Color(0xFF2DD4BF), Color(0xFF22D3EE)))
    "peach" -> Brush.linearGradient(listOf(Color(0xFFFDE68A), Color(0xFFFB923C), Color(0xFFF43F5E)))
    "night" -> Brush.linearGradient(listOf(Color(0xFF475569), Color(0xFF0F172A), Color(0xFF000000)))
    "rose" -> Brush.linearGradient(listOf(Color(0xFFFB7185), Color(0xFFEF4444), Color(0xFFEC4899)))
    "sky" -> Brush.linearGradient(listOf(Color(0xFF7DD3FC), Color(0xFF60A5FA), Color(0xFF6366F1)))
    else -> Brush.linearGradient(listOf(Color(0xFF2563EB), Color(0xFF1D4ED8)))
}

// Web ReactionButton REACTIONS, in web order, with web labels and colours:
// like (blue), love (red), haha / wow / sad (yellow), angry (orange).
// Web renders Facebook-style 3D SVG icons (not system emojis, which show
// as boxes on some Android phones); native has no SVG asset for those,
// so the picker/counts use the web emoji characters themselves, in the
// same order and colours, instead of Material face icons.
private fun reactionEmoji(type: String?): String = when (type) {
    "love" -> "\u2764\uFE0F"
    "haha" -> "\uD83D\uDE06"
    "wow" -> "\uD83D\uDE2E"
    "sad" -> "\uD83D\uDE22"
    "angry" -> "\uD83D\uDE20"
    else -> "\uD83D\uDC4D"
}
private fun reactionLabel(type: String?): String = when (type) {
    "love" -> "Love"; "haha" -> "Haha"; "wow" -> "Wow"; "sad" -> "Sad"; "angry" -> "Angry"; else -> "Like"
}
private fun reactionColor(type: String?): Color = when (type) {
    "love" -> Color(0xFFDC2626)
    "haha", "wow", "sad" -> Color(0xFFCA8A04)
    "angry" -> Color(0xFFEA580C)
    else -> Color(0xFF2563EB)
}
private fun reactionTypes(): List<String> = listOf("like", "love", "haha", "wow", "sad", "angry")

@Composable
private fun ReactionEmojiText(type: String?, fontSize: androidx.compose.ui.unit.TextUnit, modifier: Modifier = Modifier) {
    ReactionIcon(type, fontSize.value.dp, modifier)
}

@Composable
private fun ReactionPickerRow(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        reactionTypes().forEach { type ->
            Box(modifier = Modifier.clickable { onPick(type) }) {
                ReactionIcon(type, 36.dp)
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
    // Web: the feed's permanent share code is used first, no API call.
    var link by remember { mutableStateOf(if (!post.shareCode.isNullOrBlank()) "https://frianzo.online/s/${post.shareCode}" else "https://frianzo.online/post/${post.id}") }
    LaunchedEffect(post.id) {
        if (!post.shareCode.isNullOrBlank()) return@LaunchedEffect
        try {
            val r = vm.api2.createShareLink(mapOf("type" to "post", "id" to post.id))
            val code = r.data?.code
            if (r.success && !code.isNullOrBlank()) link = "https://frianzo.online/s/$code"
        } catch (_: Exception) {}
    }
    fun record(channel: String) { scope.launch { try { vm.api2.recordShareEvent(mapOf("type" to "post", "id" to post.id, "channel" to channel)) } catch (_: Exception) {} } }
    // Web openAndClose: external taps close the sheet after recording.
    fun openUrl(url: String, channel: String) { record(channel); try { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) } catch (_: Exception) {}; onClose() }
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("Share", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            // Web link box: bordered box with the URL, then a dark Copy link button.
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color.White).border(1.dp, FrianzoBorder, RoundedCornerShape(8.dp)).padding(10.dp)) {
                Text(link, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { clipboard.setText(AnnotatedString(link)); copied = true; record("copy"); scope.launch { kotlinx.coroutines.delay(2000); copied = false } },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A), contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (copied) "Copied!" else "Copy link") }
            }
            Spacer(Modifier.height(12.dp))
            // Web grid order, 4 per row, no "More" tile on web. Brand logos
            // are web SVGs; native keeps its letter circles (no logo assets).
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                ShareTile("Frianzo DM", Color(0xFF0F172A), "F") {
                    val authorId2 = post.author?.id
                    if (authorId2 != null) {
                        dmBusy = true; record("dm")
                        scope.launch {
                            try {
                                val res = vm.api2.createConversation(com.frianzo.app.data.CreateConversationRequest(authorId2))
                                val convId = res.data?.id
                                if (convId != null) { onClose(); onOpenChat(convId, post.author?.displayName ?: post.author?.username ?: "User") }
                            } catch (_: Exception) {}
                            dmBusy = false
                        }
                    }
                }
                ShareTile("WhatsApp", Color(0xFF25D366), "W") { openUrl("https://wa.me/?text=" + android.net.Uri.encode(link), "whatsapp") }
                ShareTile("WA Status", Color(0xFF25D366), "W") { openUrl("whatsapp://send?text=" + android.net.Uri.encode(link), "whatsapp_status") }
                ShareTile("Messenger", Color(0xFF0084FF), "M") { openUrl("fb-messenger://share?link=" + android.net.Uri.encode(link), "messenger") }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                ShareTile("Facebook", Color(0xFF1877F2), "f") { openUrl("https://www.facebook.com/sharer/sharer.php?u=" + android.net.Uri.encode(link), "facebook") }
                ShareTile("FB Story", Color(0xFF1877F2), "f") { openUrl("fb://story/camera?attachment_url=" + android.net.Uri.encode(link), "facebook_story") }
                ShareTile("Text message", Color(0xFF34C759), "S") { openUrl("sms:?body=" + android.net.Uri.encode(link), "sms") }
            }
            Spacer(Modifier.height(8.dp))
            // Web: reposts are counted server-side and must NOT also be
            // recorded as a share event (that would double-count them).
            // On success the sheet closes (web: onClose + reload).
            var repostError by remember { mutableStateOf<String?>(null) }
            if (repostError != null) { Spacer(Modifier.height(8.dp)); Text(repostError!!, fontSize = 13.sp, color = Color.Red) }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            TextButton(onClick = {
                scope.launch {
                    try {
                        val r = vm.api2.repostPost(post.id, mapOf("content" to ""))
                        if (r.success) { vm.refresh(); onClose() } else repostError = r.error?.message ?: "Could not repost"
                    } catch (e: Exception) { repostError = e.message }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Repost to your feed") }
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
    var sortBy by remember { mutableStateOf("top") }
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            // FB-style header: just "Comments" centred + X. No Top/Newest pill.
            // Sorting is newest first, so a new comment lands at the top like FB.
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Text("Comments", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close comments", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            HorizontalDivider()
            CommentSection(api2, post.id, post.commentCount, myId, post.author?.id, sortBy, true, onCountChange)
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun commentTimeAgo(createdAt: String?): String {
    if (createdAt.isNullOrBlank()) return ""
    val formats = listOf(
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US),
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US),
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US),
    )
    var millis: Long? = null
    for (f in formats) {
        f.timeZone = TimeZone.getTimeZone("UTC")
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

private fun formatCount(n: Int): String {
    if (n < 1000) return "$n"
    if (n < 1000000) { val k = n / 1000.0; return if (k % 1.0 == 0.0) "${k.toInt()}k" else "%.1fk".format(k) }
    val m = n / 1000000.0; return if (m % 1.0 == 0.0) "${m.toInt()}M" else "%.1fM".format(m)
}

// Web CommentsModal.flattenReplies: replies flattened recursively, sorted
// by createdAt ascending, each carrying its immediate parent author.
private fun flattenReplies(replies: List<Comment>, parentAuthor: Author?): List<Pair<Comment, Author?>> {
    val flat = mutableListOf<Pair<Comment, Author?>>()
    for (r in replies) {
        flat.add(r to parentAuthor)
        if (r.replies.isNotEmpty()) flat.addAll(flattenReplies(r.replies, r.author))
    }
    return flat.sortedBy { it.first.createdAt ?: "" }
}

@Composable
private fun ColumnScope.CommentSection(api2: FrianzoApi2, postId: String, initialCount: Int, myId: String? = null, postOwnerId: String? = null, sortBy: String = "top", fillHeight: Boolean = false, onCountChange: (Int) -> Unit = {}) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var comments by remember { mutableStateOf<List<Comment>>(emptyList()) }
    var draft by rememberSaveable { mutableStateOf("") }
    var replyTo by remember { mutableStateOf<Comment?>(null) }
    var imageUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var count by remember { mutableStateOf(initialCount) }
    var expandedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var mentionQuery by remember { mutableStateOf<String?>(null) }
    var mentionResults by remember { mutableStateOf<List<PublicProfile>>(emptyList()) }
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
    // @mention search: web debounces 300ms, searches /api/users/search limit 5.
    LaunchedEffect(mentionQuery) {
        val q = mentionQuery
        if (q.isNullOrBlank()) { mentionResults = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(300)
        try {
            val res = api2.searchUsers(q)
            if (res.success && res.data != null) mentionResults = res.data.users.take(5) else mentionResults = emptyList()
        } catch (_: Exception) { mentionResults = emptyList() }
    }
    // FB-style: newest first, so a new comment appears at the top.
    val sorted = comments.sortedByDescending { it.createdAt ?: "" }
    Column(if (fillHeight) Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp) else Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        LazyColumn(if (fillHeight) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth().height(380.dp)) {
            if (sorted.isEmpty() && error == null) item { Text("No comments yet. Be the first to comment.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp)) }
            if (error != null) item { Text(error!!, fontSize = 13.sp, color = Color.Red) }
            items(sorted, key = { it.id }) { c ->
                CommentRow(api2, c, myId, postOwnerId, 0, null, onReply = { replyTo = it }, onChanged = { load() })
                val flatReplies = flattenReplies(c.replies, c.author)
                if (flatReplies.isNotEmpty()) {
                    val isExpanded = c.id in expandedIds
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 4.dp)) {
                        Box(Modifier.width(2.dp).fillMaxHeight().background(Color(0xFFE2E8F0)))
                        Column(Modifier.padding(start = 12.dp)) {
                            if (!isExpanded) {
                                TextButton(onClick = { expandedIds = expandedIds + c.id }) { Text("View ${flatReplies.size} ${if (flatReplies.size == 1) "reply" else "replies"}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            } else {
                                flatReplies.forEach { (r, parentAuthor) ->
                                    CommentRow(api2, r, myId, postOwnerId, 1, parentAuthor, onReply = { replyTo = it }, onChanged = { load() })
                                }
                                TextButton(onClick = { expandedIds = expandedIds - c.id }) { Text("Hide replies", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                }
            }
        }
        if (replyTo != null) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(FrianzoBlueTint).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Replying to @${replyTo!!.author?.username ?: "User"}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { replyTo = null }, modifier = Modifier.size(24.dp)) { Icon(Icons.Filled.Close, contentDescription = "Cancel reply", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (mentionQuery != null && mentionResults.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White).padding(vertical = 4.dp)) {
                mentionResults.forEach { u ->
                    Row(Modifier.fillMaxWidth().clickable {
                        val q = mentionQuery ?: ""
                        val idx = draft.lastIndexOf("@$q")
                        draft = if (idx >= 0) draft.substring(0, idx) + "@${u.username} " + draft.substring(idx + q.length + 1) else draft + "@${u.username} "
                        mentionQuery = null; mentionResults = emptyList()
                    }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FrianzoAvatar(u.profilePictureUrl, 28.dp)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(u.displayName ?: u.username ?: "", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("@${u.username}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (imageUri != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(model = imageUri, contentDescription = "Comment attachment", modifier = Modifier.heightIn(max = 128.dp).clip(RoundedCornerShape(8.dp)))
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { imageUri = null }) { Text("Remove") }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (error != null) { Text(error!!, fontSize = 13.sp, color = Color.Red); Spacer(Modifier.height(4.dp)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) }) { Icon(Icons.Filled.AddPhotoAlternate, contentDescription = "Add photo", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            OutlinedTextField(
                value = draft,
                onValueChange = { v ->
                    draft = v
                    val m = Regex("@(\\w*)$").find(v)
                    mentionQuery = m?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
                },
                placeholder = { Text("Write a comment...") },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                maxLines = 4,
                singleLine = false,
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White,
                    focusedBorderColor = FrianzoBorder,
                    unfocusedBorderColor = FrianzoBorder
                )
            )
            Spacer(Modifier.width(8.dp))
            Button(
                enabled = draft.isNotBlank() || imageUri != null,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF64748B), contentColor = Color.White),
                onClick = {
                    val text = draft; val uri = imageUri; val parent = replyTo?.id; draft = ""; imageUri = null; replyTo = null; mentionQuery = null
                    scope.launch {
                        try {
                            val ok = if (uri != null) {
                                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                if (bytes == null) false
                                else if (bytes.size > 5 * 1024 * 1024) { error = "Image must be under 5MB."; imageUri = uri; draft = text; false }
                                else {
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
                            if (ok) { load(); count += 1; onCountChange(count) } else if (error == null) error = "Could not send comment"
                        } catch (e: Exception) { error = e.message; draft = text }
                    }
                },
            ) { Text("Send") }
        }
    }
}

@Composable
private fun CommentRow(api2: FrianzoApi2, c: Comment, myId: String?, postOwnerId: String?, depth: Int, parentAuthor: Author?, onReply: (Comment) -> Unit, onChanged: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var editDraft by rememberSaveable { mutableStateOf(c.content ?: "") }
    var localReaction by remember { mutableStateOf(c.myReaction) }
    var localCount by remember { mutableStateOf(c.reactionCount) }
    var menuOpen by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var reported by remember { mutableStateOf(false) }
    val isReply = depth > 0
    val isOwner = myId != null && c.author?.id == myId
    val canDelete = isOwner || (myId != null && postOwnerId != null && myId == postOwnerId)
    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            FrianzoAvatar(c.author?.profilePictureUrl, if (isReply) 28.dp else 32.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                // Web bubble: top-level bg-slate-100, reply bg-slate-50 + border.
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (isReply) Color(0xFFF8FAFC) else Color(0xFFF0F2F5)).padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(c.author?.displayName ?: c.author?.username ?: "User", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            if (c.author?.isVerified == true) { Spacer(Modifier.width(4.dp)); Icon(Icons.Filled.CheckCircle, contentDescription = "Verified", modifier = Modifier.size(14.dp), tint = FrianzoBlue) }
                        }
                        c.mediaUrl?.let { AsyncImage(model = it, contentDescription = "Comment image", modifier = Modifier.fillMaxWidth().heightIn(max = 192.dp).padding(top = 8.dp).clip(RoundedCornerShape(12.dp))) }
                        if (editing) {
                            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(value = editDraft, onValueChange = { editDraft = it }, modifier = Modifier.weight(1f), singleLine = true)
                                TextButton(onClick = {
                                    scope.launch {
                                        try { if (api2.editComment(c.id, mapOf("content" to editDraft)).success) { editing = false; onChanged() } } catch (_: Exception) {}
                                    }
                                }) { Text("Save", fontSize = 12.sp, color = FrianzoBlue) }
                                TextButton(onClick = { editing = false; editDraft = c.content ?: "" }) { Text("Cancel", fontSize = 12.sp) }
                            }
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
                Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        // Live web: comment Like is TAP-ONLY, no picker.
                        // Tap toggles "like" (switching from another reaction keeps the count).
                        val prevReaction = localReaction; val prevCount = localCount
                        val removing = prevReaction == "like"
                        val switching = prevReaction != null && !removing
                        localReaction = if (removing) null else "like"
                        localCount = (prevCount + if (removing) -1 else if (switching) 0 else 1).coerceAtLeast(0)
                        scope.launch {
                            try { if (!api2.setCommentReaction(c.id, mapOf("type" to "like")).success) { localReaction = prevReaction; localCount = prevCount } } catch (_: Exception) { localReaction = prevReaction; localCount = prevCount }
                        }
                    }, contentPadding = PaddingValues(0.dp)) {
                        if (localReaction != null) ReactionIcon(localReaction, 16.dp)
                        else Text("Like", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(12.dp))
                    TextButton(onClick = { onReply(c) }, contentPadding = PaddingValues(0.dp)) { Text("Reply", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (localCount > 0) { Spacer(Modifier.width(12.dp)); Text(formatCount(localCount), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Spacer(Modifier.weight(1f))
                    Text(commentTimeAgo(c.createdAt), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Box {
                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(24.dp)) { Icon(Icons.Filled.MoreVert, contentDescription = "More", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (isOwner) DropdownMenuItem(text = { Text("Edit") }, onClick = { menuOpen = false; editDraft = c.content ?: ""; editing = true })
                            if (canDelete) DropdownMenuItem(text = { Text("Delete", color = Color.Red) }, onClick = { menuOpen = false; showDelete = true })
                            DropdownMenuItem(text = { Text("Copy") }, onClick = { menuOpen = false; clipboard.setText(AnnotatedString(c.content ?: "")) })
                            DropdownMenuItem(text = { Text(if (reported) "Reported" else "Report") }, onClick = {
                                menuOpen = false
                                scope.launch { try { if (api2.reportComment(c.id, mapOf("reason" to "other")).success) reported = true } catch (_: Exception) {} }
                            })
                        }
                    }
                }
                if (reported) Text("Comment reported. Thanks for letting us know.", fontSize = 12.sp, color = Color(0xFF16A34A), modifier = Modifier.padding(start = 8.dp))
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
                    scope.launch { try { if (api2.deleteComment(c.id).success) onChanged() } catch (_: Exception) {} }
                }) { Text("Delete", color = Color.Red) }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } },
        )
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
    var detailReaction by remember { mutableStateOf<String?>(null) }
    var likeCount by remember { mutableStateOf(0) }
    var shareDetail by remember { mutableStateOf<Post?>(null) }
    var detailPicker by remember { mutableStateOf(false) }
    val detailScroll = rememberScrollState()
    LaunchedEffect(postId) {
        try {
            val r = vm.api2.postById(postId)
            if (r.success && r.data?.post != null) { detail = r.data.post; likeCount = r.data.post.likeCount; liked = r.data.post.myReaction != null; detailReaction = r.data.post.myReaction }
            else error = r.error?.message ?: "Post not found"
        } catch (e: Exception) { error = e.message }
        loading = false
    }
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().verticalScroll(detailScroll).padding(bottom = 24.dp)) {
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
                val detailTop = (post.reactionCounts?.entries?.sortedByDescending { it.value }?.map { it.key } ?: listOfNotNull(detailReaction)).take(3)
                if (likeCount > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        detailTop.forEachIndexed { idx, rtype ->
                            Box(Modifier.zIndex((detailTop.size - idx).toFloat()).offset(x = (-7 * idx).dp).size(24.dp).clip(CircleShape).background(Color.White).padding(1.dp), contentAlignment = Alignment.Center) {
                                ReactionIcon(rtype, 22.dp)
                            }
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    Text("$likeCount", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                if (post.commentCount > 0) Text("${post.commentCount} comments", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (post.shareCount > 0) { Spacer(Modifier.width(8.dp)); Box(Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 8.dp, vertical = 2.dp)) { Text("${post.shareCount} shares", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = FrianzoBorder)
            if (detailPicker) {
                // Web picker above the Like button, left-aligned, icons 40.
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.Start) {
                    ReactionPickerPill(40.dp, reactionTypes()) { type ->
                        detailPicker = false
                        val wasLiked = liked; val wasReaction = detailReaction; val wasCount = likeCount
                        val removing = detailReaction == type
                        val switching = detailReaction != null && !removing
                        liked = !removing; detailReaction = if (removing) null else type
                        likeCount = (likeCount + if (removing) -1 else if (switching) 0 else 1).coerceAtLeast(0)
                        scope.launch {
                            try {
                                val r = vm.api2.setReaction(post.id, mapOf("type" to type))
                                if (r.success && r.data != null) {
                                    if (r.data.reactionCounts != null) likeCount = r.data.reactionCounts.values.sum()
                                    detailReaction = r.data.myReaction; liked = r.data.myReaction != null
                                } else { liked = wasLiked; detailReaction = wasReaction; likeCount = wasCount }
                            } catch (_: Exception) { liked = wasLiked; detailReaction = wasReaction; likeCount = wasCount }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                Box(Modifier.weight(1f).height(48.dp).pointerInput(post.id) {
                    // Web: 400ms hold opens the picker; release after hold
                    // fires NO tap; scroll cancels. Tap sends the CURRENT
                    // reaction (toggles it off) or "like" when none.
                    detectTapGestures(onPress = {
                        var opened = false
                        val timer = scope.launch { delay(400); opened = true; detailPicker = true }
                        val released = tryAwaitRelease()
                        timer.cancel()
                        if (released && !opened) {
                            val sendType = detailReaction ?: "like"
                            val wasLiked = liked; val wasReaction = detailReaction; val wasCount = likeCount
                            // BUG FIX: removing a non-like reaction previously
                            // optimistically showed "like"; it must clear.
                            liked = false; detailReaction = null
                            likeCount = (likeCount - 1).coerceAtLeast(0)
                            if (wasReaction == null) { liked = true; detailReaction = "like"; likeCount = wasCount + 1 }
                            scope.launch {
                                try {
                                    val r = vm.api2.setReaction(post.id, mapOf("type" to sendType))
                                    if (r.success && r.data != null) {
                                        if (r.data.reactionCounts != null) likeCount = r.data.reactionCounts.values.sum()
                                        detailReaction = r.data.myReaction; liked = r.data.myReaction != null
                                    } else { liked = wasLiked; detailReaction = wasReaction; likeCount = wasCount }
                                } catch (_: Exception) { liked = wasLiked; detailReaction = wasReaction; likeCount = wasCount }
                            }
                        }
                    })
                }, contentAlignment = Alignment.Center) {
                    when (detailReaction) {
                        null -> Icon(Icons.Outlined.ThumbUp, contentDescription = "Like", modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        "like" -> Icon(Icons.Filled.ThumbUp, contentDescription = "Like", modifier = Modifier.size(28.dp), tint = FrianzoBlue)
                        else -> ReactionIcon(detailReaction, 24.dp)
                    }
                }
                Box(Modifier.weight(1f).height(48.dp).clickable { scope.launch { detailScroll.animateScrollTo(detailScroll.maxValue) } }, contentAlignment = Alignment.Center) { Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = "Comments", modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                Box(Modifier.weight(1f).height(48.dp).clickable { shareDetail = post }, contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Share, contentDescription = "Share", modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            HorizontalDivider(color = FrianzoBorder)
            Spacer(Modifier.height(12.dp))
            CommentSection(vm.api2, post.id, post.commentCount, myId, post.author?.id) { vm.updateCommentCount(post.id, it) }
            shareDetail?.let { sp -> PostShareSheet(vm, sp, onClose = { shareDetail = null }, onOpenChat = onOpenChat) }
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


