package com.frianzo.app.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
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
                        posts = posts.map { if (it.id == post.id) it.copy(likeCount = total) else it }
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
    var myAvatar by remember { mutableStateOf<String?>(null) }
    // + BUTTON = SYSTEM GALLERY (live behaviour): photos AND videos.
    // PickVisualMedia (system photo picker) needs no Gallery permission
    // and returns a Uri; the parent opens Create with it pre-attached.
    val galleryPicker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) onPickMedia(uri)
    }
    LaunchedEffect(Unit) {
        try {
            val me = vm.api2.myProfile()
            if (me.success && me.data?.user != null) myAvatar = me.data.user.profilePictureUrl
        } catch (_: Exception) {}
    }
    // Per-author follow status, fetched once per author (existing endpoint).
    var followStates by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    val followScope = rememberCoroutineScope()
    LaunchedEffect(posts) {
        for (post in posts) {
            val aid = post.author?.id ?: continue
            if (aid in followStates) continue
            followScope.launch {
                try {
                    val fs = vm.api2.followStatus(aid)
                    if (fs.success && fs.data != null) {
                        val st = fs.data.status
                        followStates = followStates + (aid to (st == "following" || st == "friends" || st == "self"))
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
        // Stories row (flat): Your story -> Post screen (works); other
        // authors -> dialog (no stories endpoint exists in Api/Api2, none
        // invented). Every circle is tappable; none is a dead target.
        item {
            val storyAuthors = posts.mapNotNull { it.author }.distinctBy { it.id ?: it.username }.take(8)
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(vertical = 12.dp)) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
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
        items(posts, key = { it.id }) { post ->
            val liked = post.id in likedIds
            val myReaction = vm.reactionByPost[post.id]
            var menuExpanded by remember { mutableStateOf(false) }
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
                        Text(post.author?.displayName ?: post.author?.username ?: "User", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                        val time = feedTime(post.createdAt)
                        Text(if (time.isNotBlank()) time else "@${post.author?.username ?: ""}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                    }
                    val authorId = post.author?.id
                    if (authorId != null && authorId != myId && followStates[authorId] == false) {
                        OutlinedButton(
                            onClick = {
                                followScope.launch {
                                    try {
                                        val r = vm.api2.toggleFollow(authorId)
                                        if (r.success && r.data != null) followStates = followStates + (authorId to (r.data.following || r.data.isFollowing))
                                    } catch (_: Exception) {}
                                }
                            },
                            modifier = Modifier.height(36.dp),
                            shape = RoundedCornerShape(28.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                        ) { Text("Follow", fontSize = 13.sp, color = FrianzoBlue) }
                        Spacer(Modifier.width(8.dp))
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More", modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            if (post.author?.username != null) {
                                DropdownMenuItem(text = { Text("View profile") }, onClick = { menuExpanded = false; post.author?.username?.let { onAuthor(it) } })
                            }
                            DropdownMenuItem(text = { Text("Copy link") }, onClick = {
                                menuExpanded = false
                                clipboard.setText(AnnotatedString("https://frianzo.online/post/${post.id}"))
                            })
                            DropdownMenuItem(text = { Text("Share") }, onClick = { menuExpanded = false; sharePost = post })
                            DropdownMenuItem(text = { Text("Comments") }, onClick = { menuExpanded = false; commentPost = post })
                            // Hide removes the post from this feed only.
                            // Report / Edit / Delete / Pin are NOT listed:
                            // no verified endpoint exists for them in Api2.
                            DropdownMenuItem(text = { Text("Hide post") }, onClick = { menuExpanded = false; vm.hidePost(post.id) })
                        }
                    }
                    // Always-visible Hide X, live layout.
                    IconButton(onClick = { vm.hidePost(post.id) }) { Icon(Icons.Filled.Close, contentDescription = "Hide", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                // Text tap -> comments dialog (no post-detail screen exists
                // in the app yet, so this is the working tap target).
                post.content?.let { Text(it, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 12.dp).clickable { commentPost = post }) }
                post.imageUrl?.let {
                    Spacer(Modifier.height(10.dp))
                    AsyncImage(model = ImageRequest.Builder(context).data(it).crossfade(true).build(), contentDescription = null, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth().clickable { commentPost = post })
                }
                Spacer(Modifier.height(10.dp))
                // Counts row, live style: 12sp, reaction icon + total left,
                // comments/shares right. Only shown when any count > 0.
                if (post.likeCount > 0 || post.commentCount > 0 || post.shareCount > 0) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (post.likeCount > 0) {
                            Icon(reactionIcon(myReaction), contentDescription = null, modifier = Modifier.size(16.dp), tint = reactionColor(myReaction))
                            Spacer(Modifier.width(4.dp))
                            Text("${post.likeCount}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.weight(1f))
                        val rightParts = buildList {
                            if (post.commentCount > 0) add("${post.commentCount} comments")
                            if (post.shareCount > 0) add("${post.shareCount} shares")
                        }
                        if (rightParts.isNotEmpty()) Text(rightParts.joinToString(" · "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(8.dp))
                }
                HorizontalDivider(color = FrianzoBorder)
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
                        Icon(Icons.Filled.Chat, contentDescription = "Comments", modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box(Modifier.weight(1f).height(48.dp).clickable { sharePost = post }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Share, contentDescription = "Share", modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (pickerPost) {
                    ReactionPickerRow(
                        onPick = { type -> pickerPost = false; vm.setReaction(post, type) },
                        onDismiss = { pickerPost = false },
                    )
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
    val link = "https://frianzo.online/post/${post.id}"
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("Share", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(link, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                clipboard.setText(AnnotatedString(link)); copied = true
            }, modifier = Modifier.fillMaxWidth()) { Text(if (copied) "Copied!" else "Copy link") }
            Spacer(Modifier.height(12.dp))
            // Tiles that all work via real intents / API. Repost is NOT
            // offered: no share/repost endpoint exists in Api2, and the
            // old share endpoint creates a backend repost, not a link.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                ShareTile("WhatsApp") {
                    context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://wa.me/?text=" + android.net.Uri.encode(link))))
                }
                ShareTile("Facebook") {
                    context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.facebook.com/sharer/sharer.php?u=" + android.net.Uri.encode(link))))
                }
                ShareTile("SMS") {
                    context.startActivity(Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("sms:?body=" + android.net.Uri.encode(link))))
                }
                ShareTile("More") {
                    val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, link) }
                    context.startActivity(Intent.createChooser(intent, "Share post"))
                }
            }
            Spacer(Modifier.height(8.dp))
            // Frianzo DM: creates a conversation with the author (existing
            // endpoint) and opens the thread. Hidden when author id unknown.
            val authorId = post.author?.id
            if (authorId != null) {
                TextButton(
                    enabled = !dmBusy,
                    onClick = {
                        dmBusy = true
                        scope.launch {
                            try {
                                val res = vm.api2.createConversation(com.frianzo.app.data.CreateConversationRequest(authorId))
                                val convId = res.data?.id
                                if (convId != null) {
                                    onClose()
                                    onOpenChat(convId, post.author?.displayName ?: post.author?.username ?: "User")
                                }
                            } catch (_: Exception) {}
                            dmBusy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Send in Frianzo DM") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ShareTile(label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onClick() }.padding(8.dp)) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(FrianzoBlueTint), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(24.dp), tint = FrianzoBlue)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}


@Composable
private fun PostCommentsDialog(api2: FrianzoApi2, post: Post, onClose: () -> Unit, onCountChange: (Int) -> Unit) {
    var comments by remember { mutableStateOf<List<Comment>>(emptyList()) }
    var draft by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun load() {
        scope.launch {
            try {
                val res = api2.postComments(post.id)
                if (res.success && res.data != null) comments = res.data.comments
                else error = res.error?.message
            } catch (e: Exception) { error = e.message }
        }
    }
    LaunchedEffect(post.id) { load() }
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
                        val res = api2.addPostComment(post.id, CommentCreateRequest(text))
                        if (res.success) { load(); onCountChange(post.commentCount + 1) }
                        else error = res.error?.message
                    } catch (e: Exception) { error = e.message; draft = text }
                }
            }) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

fun dummyContextUsage(@Suppress("UNUSED_PARAMETER") context: Context) = Unit


