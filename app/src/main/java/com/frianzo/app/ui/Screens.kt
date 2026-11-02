package com.frianzo.app.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import java.security.SecureRandom
import java.util.Base64

/** Step 1 screens only: Login (Google + email OTP) and Feed (read-only).
 *  No posting, liking, commenting, reels or chat yet — those are later steps. */

@Composable
fun LoginScreen(api: FrianzoApi, session: SessionViewModel, cookieJar: PersistentCookieJar? = null, onLoggedIn: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var codeSent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    // Dark login look from the real web login screenshot: dark slate page,
    // white wordmark, dark outlined Google button, white email button.
    val loginBg = androidx.compose.ui.graphics.Color(0xFF1E293B)
    val loginFieldBg = androidx.compose.ui.graphics.Color(0xFF2B3648)
    val loginBorder = androidx.compose.ui.graphics.Color(0xFF475569)
    val loginGrey = androidx.compose.ui.graphics.Color(0xFF94A3B8)
    val loginBlue = androidx.compose.ui.graphics.Color(0xFF3B82F6)

    fun fail(e: Exception, fallback: String) {
        // Never show Retrofit's bare "HTTP 403": map an HttpException to the
        // backend's own error code/message from the response body instead.
        message = if (e is HttpException) httpErrorMessage(e) else e.message ?: fallback
        busy = false
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = androidx.compose.ui.graphics.Color.White,
        unfocusedTextColor = androidx.compose.ui.graphics.Color.White,
        focusedContainerColor = loginFieldBg,
        unfocusedContainerColor = loginFieldBg,
        disabledContainerColor = loginFieldBg,
        focusedBorderColor = loginBorder,
        unfocusedBorderColor = loginBorder,
        focusedPlaceholderColor = loginGrey,
        unfocusedPlaceholderColor = loginGrey,
        cursorColor = androidx.compose.ui.graphics.Color.White,
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
            color = androidx.compose.ui.graphics.Color.White,
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
                containerColor = loginFieldBg,
                contentColor = androidx.compose.ui.graphics.Color.White,
            ),
            border = androidx.compose.foundation.BorderStroke(1.dp, loginBorder),
        ) {
            // Simplified Google "G" mark: no official multicolour G asset exists
            // in the native repo, so a white disc with a blue G is used.
            Box(
                modifier = Modifier.size(24.dp)
                    .background(androidx.compose.ui.graphics.Color.White, androidx.compose.foundation.shape.CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "G",
                    color = FrianzoBlue,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(Modifier.width(10.dp))
            Text("Continue with Google", style = MaterialTheme.typography.titleMedium, color = androidx.compose.ui.graphics.Color.White)
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
                containerColor = androidx.compose.ui.graphics.Color.White,
                contentColor = androidx.compose.ui.graphics.Color(0xFF0F172A),
            ),
        ) { Text(if (codeSent) "Verify & Continue" else "Continue with Email", style = MaterialTheme.typography.titleMedium) }
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
        if (busy) { Spacer(Modifier.height(12.dp)); DotsLoading(color = androidx.compose.ui.graphics.Color.White) }

        Spacer(Modifier.height(28.dp))
        Text(
            text = androidx.compose.ui.text.buildAnnotatedString {
                append("By continuing with an account located in ")
                withStyle(androidx.compose.ui.text.SpanStyle(color = loginBlue, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)) { append("Pakistan") }
                append(", you agree to Frianzo's ")
                withStyle(androidx.compose.ui.text.SpanStyle(color = loginBlue, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)) { append("Terms of Service") }
                append(" and acknowledge that you have read our ")
                withStyle(androidx.compose.ui.text.SpanStyle(color = loginBlue, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)) { append("Privacy Policy") }
                append(".")
            },
            style = MaterialTheme.typography.bodySmall,
            color = loginGrey,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
            Text("About", style = MaterialTheme.typography.bodySmall, color = loginGrey)
            Text("   ·   ", style = MaterialTheme.typography.bodySmall, color = loginGrey)
            Text("Privacy", style = MaterialTheme.typography.bodySmall, color = loginGrey)
            Text("   ·   ", style = MaterialTheme.typography.bodySmall, color = loginGrey)
            Text("Terms", style = MaterialTheme.typography.bodySmall, color = loginGrey)
        }
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

@Composable
fun FeedScreen(api: FrianzoApi, api2: FrianzoApi2) {
    val context = LocalContext.current
    var posts by remember { mutableStateOf<List<Post>>(emptyList()) }
    var offset by remember { mutableStateOf(0) }
    var hasMore by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var likedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var commentPost by remember { mutableStateOf<Post?>(null) }
    val scope = rememberCoroutineScope()

    fun loadMore() {
        if (loading || !hasMore) return
        loading = true
        scope.launch {
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

    LaunchedEffect(Unit) { loadMore() }

    // Comments dialog for one post (FB-like: read + add, counts update).
    commentPost?.let { post ->
        PostCommentsDialog(api2, post,
            onClose = { commentPost = null },
            onCountChange = { newCount ->
                posts = posts.map { if (it.id == post.id) it.copy(commentCount = newCount) else it }
            })
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(FrianzoPageBackground),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(posts, key = { it.id }) { post ->
            val liked = post.id in likedIds
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Column {
                    // FB header: avatar left, name (16sp bold) + time
                    // (12sp grey) below it, 3-dot menu at the right edge.
                    // The menu icon is placement only: no menu actions are
                    // wired yet, so it is a plain Icon, not a dead button.
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FrianzoAvatar(post.author?.profilePictureUrl, 40.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                post.author?.displayName ?: post.author?.username ?: "User",
                                fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
                            )
                            val time = feedTime(post.createdAt)
                            Text(
                                if (time.isNotBlank()) time else "@${post.author?.username ?: ""}",
                                fontSize = 12.sp, color = FrianzoTextSecondary, maxLines = 1, softWrap = false,
                            )
                        }
                        Icon(Icons.Filled.MoreVert, contentDescription = "More", modifier = Modifier.size(24.dp), tint = FrianzoTextSecondary)
                    }
                    // FB body: text first, then a FULL-WIDTH photo (no side
                    // padding, no rounded inset — the old card shrank photos
                    // inside 12dp + 16dp of padding).
                    post.content?.let { Text(it, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 12.dp)) }
                    post.imageUrl?.let {
                        Spacer(Modifier.height(10.dp))
                        AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
                    }
                    // FB counts row: likes left, comments/shares right, 14sp.
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = null, modifier = Modifier.size(16.dp),
                            tint = if (liked) FrianzoBlue else FrianzoTextSecondary,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("${post.likeCount}", fontSize = 14.sp, color = FrianzoTextSecondary)
                        Spacer(Modifier.weight(1f))
                        val rightParts = buildList {
                            if (post.commentCount > 0) add("${post.commentCount} comments")
                            if (post.shareCount > 0) add("${post.shareCount} shares")
                        }
                        if (rightParts.isNotEmpty()) {
                            Text(rightParts.joinToString(" · "), fontSize = 14.sp, color = FrianzoTextSecondary)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = FrianzoBorder)
                    // FB action row: 3 EQUAL buttons, each 48dp tall,
                    // 14sp text + 20dp icon, centred. This replaces the old
                    // left-aligned cramped icon+count cluster.
                    Row(Modifier.fillMaxWidth()) {
                        // Like toggles via POST /api/posts/{id}/reaction
                        // {type:"like"} (same endpoint as web); a second tap
                        // removes the reaction, and the server count wins.
                        TextButton(
                            onClick = {
                                scope.launch {
                                    try {
                                        val r = api2.setReaction(post.id, mapOf("type" to "like"))
                                        if (r.success && r.data != null) {
                                            val counts = r.data.reactionCounts
                                            val newLike = counts?.get("like") ?: post.likeCount
                                            posts = posts.map { if (it.id == post.id) it.copy(likeCount = newLike) else it }
                                            likedIds = if (r.data.myReaction != null) likedIds + post.id else likedIds - post.id
                                        }
                                    } catch (_: Exception) {}
                                }
                            },
                            modifier = Modifier.weight(1f).height(48.dp),
                        ) {
                            Icon(
                                if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                contentDescription = "Like", modifier = Modifier.size(20.dp),
                                tint = if (liked) FrianzoBlue else FrianzoTextSecondary,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Like", fontSize = 14.sp, color = if (liked) FrianzoBlue else FrianzoTextSecondary)
                        }
                        TextButton(
                            onClick = { commentPost = post },
                            modifier = Modifier.weight(1f).height(48.dp),
                        ) {
                            Icon(Icons.Filled.Chat, contentDescription = "Comments", modifier = Modifier.size(20.dp), tint = FrianzoTextSecondary)
                            Spacer(Modifier.width(6.dp))
                            Text("Comment", fontSize = 14.sp, color = FrianzoTextSecondary)
                        }
                        // Share opens the Android share sheet with the real
                        // post link. It deliberately does NOT call
                        // POST /api/posts/{id}/share, which on the backend
                        // creates a repost, not a share link.
                        TextButton(
                            onClick = {
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, "https://frianzo.online/post/${post.id}")
                                }
                                context.startActivity(Intent.createChooser(intent, "Share post"))
                            },
                            modifier = Modifier.weight(1f).height(48.dp),
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = "Share", modifier = Modifier.size(20.dp), tint = FrianzoTextSecondary)
                            Spacer(Modifier.width(6.dp))
                            Text("Share", fontSize = 14.sp, color = FrianzoTextSecondary)
                        }
                    }
                }
            }
        }
        item {
            if (error != null) Text(error!!, modifier = Modifier.padding(16.dp))
            if (loading) {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    DotsLoading()
                }
            } else if (hasMore) {
                OutlinedButton(onClick = { loadMore() }, modifier = Modifier.fillMaxWidth().padding(16.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)) {
                    Text("Load more")
                }
            } else if (posts.isEmpty()) {
                Text("No posts yet", modifier = Modifier.padding(16.dp))
            } else {
                Text("No more posts", modifier = Modifier.padding(16.dp))
            }
        }
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

