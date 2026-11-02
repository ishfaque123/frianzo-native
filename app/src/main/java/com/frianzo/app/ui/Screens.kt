package com.frianzo.app.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import coil.compose.AsyncImage
import com.frianzo.app.data.*
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.Base64

/** Step 1 screens only: Login (Google + email OTP) and Feed (read-only).
 *  No posting, liking, commenting, reels or chat yet — those are later steps. */

@Composable
fun LoginScreen(api: FrianzoApi, session: SessionViewModel, onLoggedIn: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var codeSent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun fail(e: Exception, fallback: String) {
        message = e.message ?: fallback
        busy = false
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Frianzo", style = MaterialTheme.typography.displayMedium)
        Text("Native app — prototype (login + feed only)", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))

        Button(
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
                        session.markLoggedIn(login.data?.user)
                        onLoggedIn()
                    } catch (e: GetCredentialException) {
                        fail(Exception("Google sign-in cancelled or unavailable"), "Google sign-in failed")
                    } catch (e: Exception) {
                        fail(e, "Google sign-in failed")
                    } finally { busy = false }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Continue with Google") }

        Spacer(Modifier.height(16.dp))
        Text("Or login with email code", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = email, onValueChange = { email = it },
            label = { Text("Email") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        if (codeSent) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = code, onValueChange = { code = it },
                label = { Text("6-digit code from email") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
        }
        Spacer(Modifier.height(8.dp))
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
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (codeSent) "Verify code" else "Send code") }

        message?.let { Spacer(Modifier.height(12.dp)); Text(it, color = MaterialTheme.colorScheme.error) }
        if (busy) { Spacer(Modifier.height(12.dp)); CircularProgressIndicator() }
    }
}

private fun randomNonce(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

@Composable
fun FeedScreen(api: FrianzoApi) {
    var posts by remember { mutableStateOf<List<Post>>(emptyList()) }
    var offset by remember { mutableStateOf(0) }
    var hasMore by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun loadMore() {
        if (loading || !hasMore) return
        loading = true
        scope.launch {
            try {
                val res = api.feed(offset)
                if (res.success && res.data != null) {
                    posts = posts + res.data.posts
                    offset = res.data.nextOffset
                    hasMore = res.data.hasMore
                } else error = res.error?.message ?: "Feed failed"
            } catch (e: Exception) { error = e.message ?: "Feed failed" }
            finally { loading = false }
        }
    }

    LaunchedEffect(Unit) { loadMore() }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Text(
                "Frianzo Feed", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(16.dp),
            )
        }
        items(posts, key = { it.id }) { post ->
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(
                            model = post.author?.profilePictureUrl, contentDescription = null,
                            modifier = Modifier.size(40.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(post.author?.displayName ?: post.author?.username ?: "User")
                            Text("@${post.author?.username ?: ""}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    post.content?.let { Spacer(Modifier.height(8.dp)); Text(it) }
                    post.imageUrl?.let {
                        Spacer(Modifier.height(8.dp))
                        AsyncImage(model = it, contentDescription = null, modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${post.likeCount} likes  •  ${post.commentCount} comments  •  ${post.shareCount} shares",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            // Load the next 5-post page when the list end is reached is handled
            // by the button below; infinite auto-scroll is a later step.
        }
        item {
            if (error != null) Text(error!!, modifier = Modifier.padding(16.dp))
            if (loading) {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (hasMore) {
                Button(onClick = { loadMore() }, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Load more")
                }
            } else if (posts.isNotEmpty()) {
                Text("No more posts", modifier = Modifier.padding(16.dp))
            }
        }
    }
}

fun dummyContextUsage(@Suppress("UNUSED_PARAMETER") context: Context) = Unit
