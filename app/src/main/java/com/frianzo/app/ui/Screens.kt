package com.frianzo.app.ui

import android.content.Context
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.net.Uri
import android.media.MediaPlayer
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.widget.VideoView
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import com.frianzo.app.R
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.graphics.Brush
import coil.compose.rememberAsyncImagePainter
import coil.compose.AsyncImagePainter
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Share
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.text.withStyle
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import android.content.Intent
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Step 1 screens only: Login (Google + email OTP) and Feed (read-only).
 *  No posting, liking, commenting, reels or chat yet — those are later steps. */


/** Real Google "G" (canonical brand asset, res/drawable/ic_google_g.xml).
 * v4.8: the WEB's own G SVG is broken (its blue path is corrupted and
 * renders a distorted G with a blue blob on the live web login page), so
 * the app uses the canonical G paths, NOT the web's exact paths. The four
 * canonical paths were render-verified (own SVG rasterizer) and draw a
 * perfect G. Shown at 20dp, like the web's 20px G. */
@Composable
private fun GoogleGIcon() {
    Image(
        painter = painterResource(R.drawable.ic_google_g),
        contentDescription = "Google",
        modifier = Modifier.size(20.dp),
    )
}

@Composable
fun LoginScreen(api: FrianzoApi, session: SessionViewModel, cookieJar: PersistentCookieJar? = null, onGuest: () -> Unit = {}, onLoggedIn: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    // A-B21: codeSent must survive rotation like email/code do.
    var codeSent by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    // A-B26: Resend cooldown countdown (seconds left). Set to 30 on every
    // code send; the Resend button stays disabled until it reaches 0.
    var resendWait by remember { mutableStateOf(0) }
    LaunchedEffect(resendWait) {
        while (resendWait > 0) { delay(1000); resendWait -= 1 }
    }
    // A-B26: client-side validation — "abc" must never reach the server.
    val emailValid = EMAIL_REGEX.matches(email.trim())
    val codeValid = code.trim().length == 6 && code.trim().all { it.isDigit() }
    // A-B22: after a login response says success, verify the session
    // actually works (/api/auth/me reads the cookie back) before leaving
    // the login screen. Returns true only when the session is verified.
    // Gate fix (v5.1): carry the login response's isNewUser through to the
    // session — it is the ONLY true new-vs-existing signal (the backend
    // returns it on Google native login and email verify). Without it the
    // MainActivity gate cannot tell a new account from an existing one.
    suspend fun verifySessionAndEnter(user: MeData?, isNewUser: Boolean = false): Boolean {
        return try {
            val me = api.me()
            if (me.success) { session.markLoggedIn(me.data?.user ?: user, isNewUser); true }
            else { message = me.error?.message ?: "Login succeeded but the session check failed. Please try again."; false }
        } catch (e: Exception) {
            message = e.message ?: "Login succeeded but the session check failed. Please try again."
            false
        }
    }

    // LIVE SITE LOGIN (same-to-same): fixed pale blue-gray/off-white
    // background, white field/button, gray text — regardless of the
    // phone system theme. Other screens still follow the system theme.
    val loginBg = Color(0xFFF8FAFC)
    val loginFieldBg = Color.White
    val loginBorder = Color(0xFFE2E8F0)
    val loginGrey = Color(0xFF64748B)
    val loginDark = Color(0xFF0F172A)
    // Country word (v5.8 — v5.6 was WRONG, verified 2026-11-07): v5.6
    // called a backend endpoint that does NOT exist — the Web's
    // /api/location/country is a Next.js route on frianzo.online (it
    // reads the Cloudflare cf-ipcountry header), not a backend route,
    // so the call 404'd and the fallback (device locale en_US) showed
    // "the United States" in Pakistan. The country now comes from the
    // mobile network / SIM (the physical country) — not the phone
    // language and not a VPN. No network call, no backend change.
    val loginCountry = remember {
        val tm = context.getSystemService(android.content.Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
        val net = tm?.networkCountryIso?.trim()?.uppercase()
        val sim = tm?.simCountryIso?.trim()?.uppercase()
        val iso = listOf(net, sim).firstOrNull { it != null && it.matches(Regex("^[A-Z]{2}$")) }
        countryNameFor(iso) ?: "Pakistan"
    }

    fun fail(e: Exception, fallback: String) {
        // Never show Retrofit's bare "HTTP 403": map an HttpException to the
        // backend's own error code/message from the response body instead.
        message = if (e is HttpException) httpErrorMessage(e) else e.message ?: fallback
        busy = false
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color(0xFF334155),
        unfocusedTextColor = Color(0xFF334155),
        focusedContainerColor = loginFieldBg,
        unfocusedContainerColor = loginFieldBg,
        disabledContainerColor = loginFieldBg,
        focusedBorderColor = Color(0xFF94A3B8),
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
        FrianzoLogo(80.dp)
        Spacer(Modifier.height(20.dp))
        Text(
            "Frianzo",
            style = MaterialTheme.typography.displayMedium,
            color = loginDark,
            fontWeight = FontWeight.Bold,
            fontSize = 30.sp,
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
                        // v5.20 (M14): the Google flow lives in
                        // googleSignInWithCredentialManager() below
                        // (shared with Settings → Add Account); the
                        // steps, nonce, client id and errors here are
                        // unchanged.
                        val login = googleSignInWithCredentialManager(api, context)
                        login.data?.token?.let { cookieJar?.saveAuthToken(it) }
                        // A-B22: navigate only if /api/auth/me proves the
                        // session (cookie) actually works.
                        if (verifySessionAndEnter(login.data?.user, login.data?.isNewUser == true)) onLoggedIn()
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
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = Color.White,
                contentColor = loginDark,
            ),
            border = androidx.compose.foundation.BorderStroke(1.dp, loginBorder),
        ) {
            GoogleGIcon()
            Spacer(Modifier.width(12.dp))
            Text("Continue with Google", style = MaterialTheme.typography.titleMedium, color = loginDark, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }

        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider(color = loginBorder, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Text("OR", style = MaterialTheme.typography.bodySmall, color = Color(0xFF94A3B8), fontSize = 12.sp)
            Spacer(Modifier.width(12.dp))
            HorizontalDivider(color = loginBorder, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = email, onValueChange = { email = it },
            placeholder = { Text("Enter your email", fontSize = 14.sp) },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = fieldColors,
            enabled = !codeSent,
            textStyle = TextStyle(fontSize = 14.sp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        )
        if (codeSent) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = code, onValueChange = { code = it.filter { c -> c.isDigit() }.take(6) },
                placeholder = { Text("Enter 6-digit verification code", fontSize = 14.sp) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors,
                // OTP field: numeric keyboard, centred, 18sp, 0.35em letter
                // spacing (6.3sp) like the web's tracking-[0.35em]. No OTP
                // autofill hint: Compose 1.7 has no public API for it (the
                // semantics ContentType API is 1.8+; see REPORT.md).
                textStyle = TextStyle(fontSize = 18.sp, textAlign = TextAlign.Center, letterSpacing = 6.3.sp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            )
        }
        if (!codeSent && email.isNotBlank() && !emailValid) {
            Spacer(Modifier.height(6.dp))
            Text("Enter a valid email address.", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
        }
        if (codeSent && code.isNotBlank() && !codeValid) {
            Spacer(Modifier.height(6.dp))
            Text("Enter the 6-digit code.", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(12.dp))
        Button(
            // A-B26: enabled only when the current step's input is valid.
            enabled = !busy && (!codeSent && emailValid || codeSent && codeValid),
            onClick = {
                busy = true
                scope.launch {
                    try {
                        if (!codeSent) {
                            // A-B26: never send a malformed email address.
                            if (!emailValid) throw Exception("Enter a valid email address.")
                            val res = api.requestEmailCode(EmailCodeRequest(email.trim().lowercase()))
                            if (!res.success) throw Exception(res.error?.message ?: "Could not send code")
                            codeSent = true
                            resendWait = 30
                            message = "Code sent to your email."
                        } else {
                            // A-B26: never send a blank/non-6-digit code.
                            if (!codeValid) throw Exception("Enter the 6-digit code from your email.")
                            val res = api.verifyEmailCode(
                                EmailVerifyRequest(email.trim().lowercase(), code.trim(), deviceToken(context))
                            )
                            if (!res.success) throw Exception(res.error?.message ?: "Wrong code")
                            // A-B22: navigate only if /api/auth/me proves the
                            // session (cookie) actually works.
                            if (verifySessionAndEnter(res.data?.user, res.data?.isNewUser == true)) onLoggedIn()
                        }
                    } catch (e: Exception) { fail(e, "Email login failed") }
                    finally { busy = false }
                }
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF0F172A),
                contentColor = androidx.compose.ui.graphics.Color.White,
            ),
        ) { Text(if (codeSent) "Verify & Continue" else "Continue with Email", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
        if (codeSent) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                TextButton(enabled = !busy && resendWait == 0, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val res = api.requestEmailCode(EmailCodeRequest(email.trim().lowercase()))
                            if (!res.success) throw Exception(res.error?.message ?: "Could not resend code")
                            resendWait = 30
                            message = "Code sent again to your email."
                        } catch (e: Exception) { fail(e, "Could not resend code") }
                        finally { busy = false }
                    }
                }) { Text(if (resendWait > 0) "Resend code (${resendWait}s)" else "Resend code", color = loginGrey) }
                TextButton(enabled = !busy, onClick = { codeSent = false; code = ""; message = null; resendWait = 0 }) { Text("Change email", color = loginGrey) }
            }
        }

        message?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        if (busy) { Spacer(Modifier.height(12.dp)); DotsLoading() }

        // v5.21 (H1): guest/demo path — a Google reviewer (or anyone
        // without an account) can browse real public posts without
        // logging in. Login itself is unchanged.
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onGuest, modifier = Modifier.fillMaxWidth()) { Text("Browse as guest", color = FrianzoBlue, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, softWrap = false) }
        Text("See public posts without an account.", fontSize = 12.sp, color = loginGrey, textAlign = androidx.compose.ui.text.style.TextAlign.Center)

        Spacer(Modifier.height(28.dp))
        // v2.9: Terms / Privacy / About are real links now (they were
        // plain Text with no click). Pages verified live 2026-11-05:
        // frianzo.online/terms, /privacy, /about all load.
        val legalText = androidx.compose.ui.text.buildAnnotatedString {
            append("By continuing with an account located in ")
            withStyle(androidx.compose.ui.text.SpanStyle(color = FrianzoBlue)) { append(loginCountry) }
            append(", you agree to Frianzo's ")
            pushStringAnnotation("url", "https://frianzo.online/terms")
            withStyle(androidx.compose.ui.text.SpanStyle(color = FrianzoBlue)) { append("Terms of Service") }
            pop()
            append(" and acknowledge that you have read our ")
            pushStringAnnotation("url", "https://frianzo.online/privacy")
            withStyle(androidx.compose.ui.text.SpanStyle(color = FrianzoBlue)) { append("Privacy Policy") }
            pop()
            append(".")
        }
        ClickableText(
            text = legalText,
            style = MaterialTheme.typography.bodySmall.copy(color = loginGrey, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
            modifier = Modifier.fillMaxWidth(),
            onClick = { offset -> legalText.getStringAnnotations("url", offset, offset).firstOrNull()?.let { if (!openWebUrl(context, it.item)) message = "Couldn't open the link — no browser app found on this phone." } },
        )
        Spacer(Modifier.height(28.dp))
        val footerText = androidx.compose.ui.text.buildAnnotatedString {
            pushStringAnnotation("url", "https://frianzo.online/about")
            withStyle(androidx.compose.ui.text.SpanStyle(color = loginGrey)) { append("About") }
            pop()
            append(" · ")
            pushStringAnnotation("url", "https://frianzo.online/privacy")
            withStyle(androidx.compose.ui.text.SpanStyle(color = loginGrey)) { append("Privacy") }
            pop()
            append(" · ")
            pushStringAnnotation("url", "https://frianzo.online/terms")
            withStyle(androidx.compose.ui.text.SpanStyle(color = loginGrey)) { append("Terms") }
            pop()
        }
        ClickableText(
            text = footerText,
            style = MaterialTheme.typography.bodySmall.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
            modifier = Modifier.fillMaxWidth(),
            onClick = { offset -> footerText.getStringAnnotations("url", offset, offset).firstOrNull()?.let { if (!openWebUrl(context, it.item)) message = "Couldn't open the link — no browser app found on this phone." } },
        )
    }
}

// v5.21 (H1): guest/demo browsing — read-only public feed for a
// reviewer or anyone without an account. Uses the SAME public
// GET /api/posts feed the logged-in feed uses (optionalAuth, works
// logged-out — verified live). No login, no account, no private
// content: no DMs, no private posts, no settings, no follow/like/
// comment/post/chat actions, and no view recording (recordPostView
// is auth-only and is never called here). "Log in" returns to the
// login screen; the normal login flow is untouched.
@Composable
fun GuestScreen(feedVm: FeedViewModel, onLogin: () -> Unit) {
    val posts = feedVm.posts
    val loading = feedVm.loading
    val error = feedVm.error
    val hasMore = feedVm.hasMore
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            FrianzoLogo(36.dp)
            Spacer(Modifier.width(10.dp))
            Text("Frianzo", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            OutlinedButton(onClick = onLogin, shape = RoundedCornerShape(28.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) { Text("Log in", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false) }
        }
        Text(
            "You're browsing as a guest. Log in to like, comment, post, chat or follow.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 16.dp, vertical = 10.dp),
            textAlign = TextAlign.Center,
        )
        if (posts.isEmpty() && loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { DotsLoading() }
        } else if (posts.isEmpty() && error != null) {
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(error, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { feedVm.retry() }) { Text("Retry") }
            }
        } else if (posts.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No public posts yet — check back soon.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(posts, key = { it.id }) { post ->
                    GuestPostCard(post)
                    HorizontalDivider(color = FrianzoBorder)
                }
                item {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (loading) DotsLoading()
                        else if (hasMore) OutlinedButton(onClick = { feedVm.loadMore() }, shape = RoundedCornerShape(28.dp)) { Text("Load more", fontSize = 14.sp) }
                        else Text("You're all caught up.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// v5.21 (H1): one public post, read-only — author, time, text,
// photo, and static counts. Nothing here is tappable: no like,
// comment, share, follow, or profile navigation (those all need
// an account). Reposts render the original via OriginalPostCard.
@Composable
private fun GuestPostCard(post: Post) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FrianzoAvatar(post.author?.profilePictureUrl, 40.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(post.author?.displayName ?: "@${post.author?.username ?: ""}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (post.author?.isVerified == true) { Spacer(Modifier.width(4.dp)); Icon(Icons.Filled.Verified, contentDescription = "Verified", tint = FrianzoBlue, modifier = Modifier.size(16.dp)) }
                }
                Text(feedTime(post.createdAt).ifBlank { "Just now" }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
        }
        // Same order as the logged-in feed card: text (gradient tile
        // when a background style is set and there is no photo), then
        // photo, then the original post for reposts.
        post.content?.takeIf { it.isNotBlank() }?.let {
            if (!post.backgroundStyle.isNullOrBlank() && post.imageUrl.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 224.dp)
                        .clip(RoundedCornerShape(16.dp)).background(postBackgroundBrush(post.backgroundStyle!!)),
                    contentAlignment = Alignment.Center
                ) {
                    PostContentText(it, fontSizeSp = 24, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(24.dp))
                }
            } else {
                Spacer(Modifier.height(8.dp)); PostContentText(it, 15, MaterialTheme.colorScheme.onSurface)
            }
        }
        post.imageUrl?.let { Spacer(Modifier.height(8.dp)); PostPhoto(it) }
        post.originalPost?.let { Spacer(Modifier.height(8.dp)); OriginalPostCard(it) }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.ThumbUp, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(formatCount(post.likeCount), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(formatCount(post.commentCount), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Share, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(formatCount(post.shareCount), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/**
 * v5.20 (M14): the Credential Manager Google sign-in flow, extracted
 * verbatim from LoginScreen so Settings → Add Account can run the very
 * same sign-in WITHOUT signing the current user out first. Steps are
 * unchanged: (1) fetch the web client id from the backend via
 * nativeConfig (never hard-coded); (2) a random nonce, sent both to
 * Google and to /api/auth/google/native (the backend verifies it
 * inside the Google ID token); (3) Credential Manager picker;
 * (4) POST /api/auth/google/native with the same per-device
 * deviceToken LoginScreen passes, so the backend saves the new
 * account as an AccountSession on this same device session.
 * Returns the googleNative envelope on success; throws on failure
 * with the same exceptions/messages LoginScreen's catch blocks map
 * (cancelled, no account, post-selection failure, HTTP, backend
 * error) — callers keep their own catch mapping.
 * Session ownership stays with the CALLER: this only signs in and
 * POSTs. NOTE: googleNative switches the auth cookie/session to the
 * NEW account (the backend clears the old auth cookie), so a caller
 * that must stay on the original account has to POST
 * /api/auth/switch with the original accountId immediately after a
 * success — see SettingsScreen's Add Account flow.
 */
suspend fun googleSignInWithCredentialManager(api: FrianzoApi, context: Context): Envelope<GoogleNativeData> {
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
    return login
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
// v2.9: open a web page in the browser. Adds https:// when the stored
// value has no scheme (profile websites are often bare domains).
// A-D10: returns false when no app can open the link, so callers can
// tell the user instead of failing silently.
internal fun openWebUrl(context: Context, url: String): Boolean {
    val fixed = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
    return try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fixed))); true } catch (_: Exception) { false }
}

// v5.19 (H6 story stickers): parse a countdown ISO timestamp. The
// backend stores targetAt as JS toISOString() (always millis + Z).
// java.time is avoided: minSdk 24 < 26, so SimpleDateFormat + UTC.
private fun parseStickerIsoMillis(iso: String?): Long? {
    if (iso.isNullOrBlank()) return null
    val patterns = listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")
    for (p in patterns) {
        try {
            val f = SimpleDateFormat(p, Locale.US)
            f.timeZone = TimeZone.getTimeZone("UTC")
            return f.parse(iso)?.time
        } catch (_: Exception) {}
    }
    return null
}

private fun stickerIsoString(millis: Long): String {
    val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    f.timeZone = TimeZone.getTimeZone("UTC")
    return f.format(Date(millis))
}

// v5.19 (H6): web useCountdown format (StoryStickers.tsx:6-22).
private fun formatCountdown(targetMillis: Long, nowMillis: Long): String {
    val diff = targetMillis - nowMillis
    if (diff <= 0) return "Ended"
    val d = diff / 86400000
    val h = (diff % 86400000) / 3600000
    val m = (diff % 3600000) / 60000
    val s = (diff % 60000) / 1000
    return when {
        d > 0 -> "${d}d ${h}h ${m}m"
        h > 0 -> "${h}h ${m}m ${s}s"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}

// v5.19 (H6): combine a picked calendar date with a picked hour/minute
// into epoch millis (device timezone).
private fun combineDateTimeMillis(dateMillis: Long, hour: Int, minute: Int): Long {
    val c = Calendar.getInstance()
    c.timeInMillis = dateMillis
    c.set(Calendar.HOUR_OF_DAY, hour)
    c.set(Calendar.MINUTE, minute)
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

// v5.19 (H6): stagger new stickers down the preview so they never stack
// exactly on top of each other before the user drags them.
private fun nextStickerY(existingCount: Int): Float = (30 + existingCount * 12).coerceAtMost(78).toFloat()

// A-B26: simple email shape check (client-side only; the server still
// validates). Kept permissive on purpose — no MX/DNS check.
private val EMAIL_REGEX = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

// v5.6: ISO country code -> display name, matching the Web's
// Intl.DisplayNames mapping (US/GB have special names). Returns null
// for a missing/invalid code so callers fall back.
private fun countryNameFor(code: String?): String? {
    val c = code?.trim()?.uppercase() ?: return null
    if (!c.matches(Regex("^[A-Z]{2}$"))) return null
    return when (c) {
        "US" -> "the United States"
        "GB" -> "the United Kingdom"
        else -> try { Locale("en", c).displayCountry.takeIf { it.isNotBlank() } } catch (_: Exception) { null }
    }
}

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
class FeedViewModel(private val api: FrianzoApi, val api2: FrianzoApi2, private val appContext: android.content.Context) : ViewModel() {
    var posts by mutableStateOf<List<Post>>(emptyList()); private set
    var offset by mutableStateOf(0); private set
    var hasMore by mutableStateOf(true); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var likedIds by mutableStateOf<Set<String>>(emptySet()); private set
    // Live reaction type per post ("like", "love", "haha", "wow", "sad", "angry").
    // Seeded from Post.myReaction (the model DOES have it) in loadMore, so an
    // already-liked post shows as liked instead of snapping back on tap.
    var reactionByPost by mutableStateOf<Map<String, String>>(emptyMap()); private set
    var refreshing by mutableStateOf(false); private set
    // v5.12: cache-first. True while the list on screen came from the disk
    // cache and the background refresh has not replaced it yet.
    private var cacheSeeded = false
    // v5.12: one automatic retry after a first-load failure (never loops).
    private var autoRetried = false
    // v5.20 (M3): offline state for the feed — a default-network callback
    // (ACCESS_NETWORK_STATE is already declared in the manifest; it is a
    // normal permission, no prompt). Drives the "Showing saved posts"
    // banner + the disabled composer / gallery button in FeedScreen.
    // Recomputed from the CURRENT active network on every callback, so a
    // lost-then-regained network cannot leave a stale offline flag.
    var isOffline by mutableStateOf(false); private set
    private var connectivityCallback: ConnectivityManager.NetworkCallback? = null

    init {
        isOffline = currentOffline()
        registerConnectivityCallback()
    }

    private fun currentOffline(): Boolean {
        return try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val network = cm.activeNetwork ?: return true
            val caps = cm.getNetworkCapabilities(network) ?: return true
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) { false }
    }

    private fun registerConnectivityCallback() {
        try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { isOffline = currentOffline() }
                override fun onLost(network: Network) { isOffline = currentOffline() }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { isOffline = currentOffline() }
            }
            cm.registerDefaultNetworkCallback(cb)
            connectivityCallback = cb
        } catch (_: Exception) {}
    }

    override fun onCleared() {
        try {
            connectivityCallback?.let { cb ->
                (appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.unregisterNetworkCallback(cb)
            }
        } catch (_: Exception) {}
        super.onCleared()
    }

    /** v5.12: seeds the list from the disk cache (if any). Call once at
     *  startup before the first loadMore(); returns true when a cache
     *  was shown, so the feed appears instantly instead of blank dots. */
    fun restoreCache(): Boolean {
        if (posts.isNotEmpty()) return false
        val cached = FeedCache.load(appContext) ?: return false
        posts = cached.posts
        hasMore = cached.hasMore
        offset = cached.nextOffset
        cacheSeeded = true
        return true
    }

    /** v5.12: manual retry after a first-load failure (Retry button). */
    fun retry() {
        error = null
        autoRetried = false
        loadMore()
    }

    /** v5.12: one automatic retry a few seconds after a first-load
     *  failure with nothing on screen (no cache). Never loops. */
    private fun scheduleAutoRetry() {
        if (autoRetried) return
        autoRetried = true
        viewModelScope.launch {
            kotlinx.coroutines.delay(3000)
            if (posts.isEmpty() && error != null && !loading) loadMore()
        }
    }

    fun hidePost(postId: String) { posts = posts.filterNot { it.id == postId } }

    // v2.9: POST /api/posts/{id}/view { watchSec } — once per post per
    // feed session, fire-and-forget (a failed view must never break feed).
    private val viewedPostIds = mutableSetOf<String>()
    fun recordPostView(postId: String) {
        if (postId in viewedPostIds) return
        viewedPostIds += postId
        viewModelScope.launch { try { api2.recordPostView(postId, mapOf("watchSec" to 3)) } catch (_: Exception) {} }
    }

    fun loadMore() {
        if (loading) return
        // v5.12: a cache-seeded first load must still refresh even when
        // the cached page said hasMore=false (the refresh replaces it).
        if (!hasMore && !cacheSeeded) return
        loading = true
        // v5.12: a cache-seeded first load is a background refresh — it
        // fetches page 1 (offset 0) and REPLACES the cached posts instead
        // of appending. Later pages append as before.
        val refreshAfterCache = cacheSeeded
        val fetchOffset = if (refreshAfterCache) 0 else offset
        viewModelScope.launch {
            try {
                val res = api.feed(fetchOffset)
                if (res.success && res.data != null) {
                    // A-B2: a later successful load clears any earlier error.
                    error = null
                    autoRetried = false
                    val newPosts = res.data.posts ?: emptyList()
                    if (refreshAfterCache) {
                        // Replace the cached page with the fresh first page.
                        posts = newPosts
                        newPosts.forEach { post ->
                            post.myReaction?.let { mr ->
                                if (post.id !in reactionByPost) {
                                    reactionByPost = reactionByPost + (post.id to mr)
                                    likedIds = likedIds + post.id
                                }
                            }
                        }
                        offset = res.data.nextOffset
                        hasMore = res.data.hasMore
                        cacheSeeded = false
                        FeedCache.save(appContext, newPosts, res.data.hasMore, res.data.nextOffset)
                    } else {
                        // A-B23: defensive dedup by id. The backend always
                        // returns nextOffset = offset + posts.length
                        // (verified in vynzo-backend postController
                        // getFeedHandler), so a repeated page should not
                        // happen — but if it ever does, appending it again
                        // would duplicate keys and can crash the LazyColumn
                        // (itemsIndexed key = post.id). A page that adds no
                        // new ids also ends pagination instead of looping.
                        val fresh = newPosts.filter { np -> posts.none { it.id == np.id } }
                        posts = posts + fresh
                        if (newPosts.isNotEmpty() && fresh.isEmpty()) {
                            hasMore = false
                            return@launch
                        }
                        // Seed already-liked state from the server, but never
                        // overwrite a reaction the user just set in this session.
                        newPosts.forEach { post ->
                            post.myReaction?.let { mr ->
                                if (post.id !in reactionByPost) {
                                    reactionByPost = reactionByPost + (post.id to mr)
                                    likedIds = likedIds + post.id
                                }
                            }
                        }
                        offset = res.data.nextOffset
                        hasMore = res.data.hasMore
                        // v5.12: cache the first page for the next cold start.
                        if (fetchOffset == 0) {
                            FeedCache.save(appContext, newPosts, res.data.hasMore, res.data.nextOffset)
                        }
                    }
                } else {
                    // v5.12: only surface the error when there is nothing
                    // to show. A failed background refresh keeps the cached
                    // posts on screen instead of blanking the feed.
                    if (posts.isEmpty()) {
                        error = res.error?.message ?: "Feed failed"
                        scheduleAutoRetry()
                    } else if (refreshing || refreshAfterCache) {
                        // v5.22: a failed refresh with posts on screen
                        // was silent — tell the user the saved posts
                        // are what they are seeing.
                        toastRefreshFailed()
                    }
                    cacheSeeded = false
                }
            } catch (e: Exception) {
                if (posts.isEmpty()) {
                    error = e.message ?: "Feed failed"
                    scheduleAutoRetry()
                } else if (refreshing || refreshAfterCache) {
                    // v5.22: same silent-refresh Toast as above.
                    toastRefreshFailed()
                }
                cacheSeeded = false
            }
            finally { loading = false; refreshing = false }
        }
    }

    // v5.22: a failed refresh while posts are on screen was silent —
    // the user saw the same cached posts with no indication. Short
    // Toast only; the empty-list case is handled by the error UI.
    private fun toastRefreshFailed() {
        Toast.makeText(appContext, "Couldn't refresh — showing saved posts", Toast.LENGTH_SHORT).show()
    }

    fun refresh() {
        // v5.22: pulling while a load is in flight used to do nothing
        // at all. Keep the spinner up until that load finishes (its
        // finally clears refreshing) instead — no duplicate request.
        // Posts/offset are NOT cleared here: the in-flight load is
        // still using them.
        if (loading) { refreshing = true; return }
        posts = emptyList(); offset = 0; hasMore = true; error = null
        reactionByPost = emptyMap(); likedIds = emptySet()
        cacheSeeded = false; autoRetried = false
        refreshing = true
        loadMore()
    }

    fun updateCommentCount(postId: String, newCount: Int) {
        posts = posts.map { if (it.id == postId) it.copy(commentCount = newCount) else it }
    }

    fun updateShareCount(postId: String, newCount: Int) {
        posts = posts.map { if (it.id == postId) it.copy(shareCount = newCount) else it }
    }

    // A-B8: keep the feed copy's commentAudience in sync after a
    // successful "Who can comment" change.
    fun updateCommentAudience(postId: String, audience: String) {
        posts = posts.map { if (it.id == postId) it.copy(commentAudience = audience) else it }
    }

    // v5.14 (#5 Save post): keep the feed copy's isSaved in sync after
    // a successful save/unsave, same pattern as updateCommentAudience.
    fun updateSaved(postId: String, saved: Boolean) {
        posts = posts.map { if (it.id == postId) it.copy(isSaved = saved) else it }
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
                    // Backend returns data.reaction + data.likeCount (web uses these);
                    // the old myReaction/reactionCounts fields are kept as fallback.
                    val effReaction = r.data.myReaction ?: r.data.reaction
                    val effCount = r.data.likeCount ?: r.data.reactionCounts?.values?.sum()
                    if (effCount != null) {
                        posts = posts.map { if (it.id == post.id) it.copy(likeCount = effCount, reactionCounts = r.data.reactionCounts) else it }
                    }
                    if (effReaction != null) {
                        likedIds = likedIds + post.id
                        reactionByPost = reactionByPost + (post.id to effReaction)
                    } else {
                        likedIds = likedIds - post.id
                        reactionByPost = reactionByPost - post.id
                    }
                } else {
                    likedIds = prevLiked; reactionByPost = prevReactions; posts = prevPosts
                }
            } catch (e: Exception) {
                if (Outbox.isNetworkError(e)) {
                    // v5.12: offline — keep the optimistic like and queue
                    // it; the flush replays it when the connection returns.
                    Outbox.enqueueJson(appContext, "LIKE_POST", post.id, mapOf("reaction" to type))
                } else {
                    likedIds = prevLiked; reactionByPost = prevReactions; posts = prevPosts
                }
            } finally { reactionInFlight -= post.id }
        }
    }
}

fun feedViewModelFactory(api: FrianzoApi, api2: FrianzoApi2, appContext: android.content.Context) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = FeedViewModel(api, api2, appContext) as T
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
/**
 * v5.18: tappable post/reel text — #tags (blue, semibold) open the tag
 * page and @mentions open the profile, exactly like the web captions
 * (PostContent.tsx / FeedReelCard.tsx split on
 * /(#[a-zA-Z0-9_]+|@[a-zA-Z0-9_.]+)/g). A tap on plain text calls
 * onTextClick (feed: open the detail; reel card: open the reel).
 * NOT private — TagScreen.kt reuses it.
 */
@Composable
fun PostContentText(text: String, fontSizeSp: Int, color: Color, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE, fontWeight: FontWeight = FontWeight.Normal, onAuthor: (String) -> Unit = {}, onTag: (String) -> Unit = {}, onTextClick: () -> Unit = {}) {
    val annotated = androidx.compose.ui.text.buildAnnotatedString {
        val tagRegex = Regex("(#[A-Za-z0-9_]+|@[A-Za-z0-9_.]+)")
        var last = 0
        for (m in tagRegex.findAll(text)) {
            if (m.range.first > last) append(text.substring(last, m.range.first))
            val token = m.value
            val ann = if (token.startsWith("#")) "tag:${token.drop(1)}" else "user:${token.drop(1)}"
            pushStringAnnotation(ann, ann)
            withStyle(androidx.compose.ui.text.SpanStyle(color = FrianzoBlue, fontWeight = FontWeight.SemiBold)) { append(token) }
            pop()
            last = m.range.last + 1
        }
        if (last < text.length) append(text.substring(last))
    }
    ClickableText(
        text = annotated,
        style = androidx.compose.ui.text.TextStyle(color = color, fontSize = fontSizeSp.sp, fontWeight = fontWeight),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
        onClick = { offset ->
            val ann = annotated.getStringAnnotations(offset, offset).firstOrNull()
            when {
                ann?.tag?.startsWith("tag:") == true -> onTag(ann.tag.removePrefix("tag:"))
                ann?.tag?.startsWith("user:") == true -> onAuthor(ann.tag.removePrefix("user:"))
                else -> onTextClick()
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FeedScreen(vm: FeedViewModel, myId: String? = null, onCreatePost: () -> Unit = {}, onPickMedia: (android.net.Uri) -> Unit = { onCreatePost() }, onAuthor: (String) -> Unit = {}, onOpenChat: (String, String) -> Unit = { _, _ -> }, onOpenReel: (String) -> Unit = {}, onTag: (String) -> Unit = {}) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val posts = vm.posts
    val hasMore = vm.hasMore
    val loading = vm.loading
    val error = vm.error
    // v5.20 (M3): offline state from the VM's NetworkCallback.
    val isOffline = vm.isOffline
    val likedIds = vm.likedIds
    var commentPost by remember { mutableStateOf<Post?>(null) }
    var sharePost by remember { mutableStateOf<Post?>(null) }
    var detailPostId by remember { mutableStateOf<String?>(null) }
    var storyViewer by remember { mutableStateOf<StatusGroup?>(null) }
    var showStoryCreate by remember { mutableStateOf(false) }
    var statusGroups by remember { mutableStateOf<List<StatusGroup>>(emptyList()) }
    // v3.8 (F13): reels interleaved in the feed, one card every 2 posts.
    var feedReels by remember { mutableStateOf<List<Reel>>(emptyList()) }
    var statusFailed by remember { mutableStateOf(false) }
    // A-D9: statusFailed is now read — a failed story load shows a Retry
    // row instead of silently falling back to placeholders.
    var myAvatar by remember { mutableStateOf<String?>(null) }
    // A-B4: authors whose Follow tap is in flight, and the last follow
    // error to show at the bottom of the feed.
    var followBusyIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var followError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(followError) { if (followError != null) { delay(4000); followError = null } }
    // + BUTTON = SYSTEM GALLERY (live behaviour): photos AND videos.
    // v3.1: Gallery permission is asked here, at pick time, before the
    // system photo picker opens (owner-confirmed permission).
    val galleryPicker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) onPickMedia(uri)
    }
    val pickMedia = rememberPermissionGate(galleryPermissions(), onGranted = { galleryPicker.launch(PickVisualMediaRequest(PickVisualMedia.ImageAndVideo)) })
    LaunchedEffect(Unit) {
        try {
            val sf = vm.api2.statusFeed()
            if (sf.success && sf.data != null) statusGroups = sf.data.feed ?: emptyList() else statusFailed = true
        } catch (_: Exception) { statusFailed = true }
        try {
            val me = vm.api2.myProfile()
            if (me.success && me.data?.user != null) myAvatar = me.data.user.profilePictureUrl
        } catch (_: Exception) {}
        try {
            val rf = vm.api2.reelFeed(0, "foryou")
            if (rf.success && rf.data != null) feedReels = rf.data.reels
        } catch (_: Exception) {}
    }
    // Per-author follow status, fetched once per author (existing endpoint).
    var followStates by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    // A-B30: authors whose status fetch is already in flight. The check
    // and the add happen in the same LaunchedEffect pass (main thread),
    // so a second request for the same author cannot launch while the
    // first is still running and a stale reply cannot overwrite a
    // fresher state.
    var followStatusFetching by remember { mutableStateOf<Set<String>>(emptySet()) }
    val followScope = rememberCoroutineScope()
    LaunchedEffect(posts) {
        for (post in posts) {
            val aid = post.author?.id ?: continue
            if (aid in followStates || aid in followStatusFetching) continue
            followStatusFetching = followStatusFetching + aid
            followScope.launch {
                try {
                    val fs = vm.api2.followStatus(aid)
                    if (fs.success && fs.data != null) {
                        followStates = followStates + (aid to (fs.data.status ?: "none"))
                    }
                } catch (_: Exception) {}
                finally { followStatusFetching = followStatusFetching - aid }
            }
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { idx ->
                // A-B1: the LazyColumn has 2 header items (composer +
                // stories) before the posts, so the raw LazyColumn index
                // is 2 ahead of the post index. The old check triggered
                // page 2 as soon as the first post was visible. Convert
                // to a post index first (idx - 2).
                if (idx != null && vm.posts.isNotEmpty()) {
                    val lastPostIdx = idx - 2
                    if (lastPostIdx >= 0 && lastPostIdx >= vm.posts.size - 3) vm.loadMore()
                }
            }
    }
    commentPost?.let { post ->
        PostCommentsDialog(vm.api2, post, myId, myAvatar = myAvatar,
            onClose = { commentPost = null },
            onCountChange = { newCount -> vm.updateCommentCount(post.id, newCount) })
    }
    sharePost?.let { post -> PostShareSheet(vm.api2, post, onClose = { sharePost = null }, onOpenChat = onOpenChat) }
    detailPostId?.let { pid ->
        PostDetailScreen(vm, pid, myId, myAvatar = myAvatar, onClose = { detailPostId = null }, onAuthor = onAuthor, onOpenChat = onOpenChat, onOpenPost = { detailPostId = it })
    }
    storyViewer?.let { group ->
        StoryViewerDialog(vm.api2, group, myId, onClose = { storyViewer = null }, onDeleted = {
            storyViewer = null
            followScope.launch {
                try { val sf = vm.api2.statusFeed(); if (sf.success && sf.data != null) statusGroups = sf.data.feed ?: emptyList() } catch (_: Exception) {}
            }
        })
    }
    // A-B28: StoryCreateScreen used to be emitted as a bare top-level
    // composable, so whether the feed stayed visible/interactive behind
    // it was unverifiable from code. It now opens inside a full-screen
    // Dialog, which guarantees it covers the feed. PHONE-TEST-OWED.
    if (showStoryCreate) {
        Dialog(onDismissRequest = { showStoryCreate = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                StoryCreateScreen(vm.api2, onDone = {
            showStoryCreate = false
            followScope.launch {
                try { val sf = vm.api2.statusFeed(); if (sf.success && sf.data != null) statusGroups = sf.data.feed ?: emptyList() } catch (_: Exception) {}
            }
                }, onClose = { showStoryCreate = false })
            }
        }
    }
    // A-D2: the old placeholder-story dialog ("will open here in a
    // future update") is deleted — real StoryViewerDialog exists, so a
    // face that looks like a story must never promise a future build.
    // Placeholder authors below are no longer tappable as stories.

    PullToRefreshBox(
        isRefreshing = vm.refreshing,
        onRefresh = {
            vm.refresh()
            followScope.launch {
                try {
                    val sf = vm.api2.statusFeed()
                    if (sf.success && sf.data != null) statusGroups = sf.data.feed ?: emptyList()
                } catch (_: Exception) {}
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) {
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
                            .background(MaterialTheme.colorScheme.surfaceVariant).alpha(if (isOffline) 0.5f else 1f).clickable(enabled = !isOffline) { onCreatePost() },
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text("What's on your mind?", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).background(FrianzoBlueTint).alpha(if (isOffline) 0.5f else 1f).clickable(enabled = !isOffline) {
                            pickMedia()
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Add photo or video", modifier = Modifier.size(24.dp), tint = FrianzoBlue)
                    }
                }
                // v5.20 (M3): web parity (HomeClient) — offline banner
                // with Retry, inside the composer item so the LazyColumn
                // header count (and the A-B1 load-more index math) does
                // not shift. Web text when cached posts are on screen;
                // honest wording when there is nothing saved yet.
                if (isOffline) {
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (posts.isEmpty()) "You're offline. Check your connection and try again." else "Could not refresh posts. Showing saved posts.", Modifier.weight(1f), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { vm.retry() }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Retry", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = FrianzoBlue) }
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
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp).clickable { if (isOwn) showStoryCreate = true else storyViewer = group }) {
                                val ring = if (group.hasUnseen && !isOwn) Brush.linearGradient(listOf(Color(0xFFF59E0B), Color(0xFFEC4899))) else null
                                Box(Modifier.size(62.dp).clip(CircleShape).background(ring ?: Brush.linearGradient(listOf(Color(0xFFCBD5E1), Color(0xFFCBD5E1)))).padding(3.dp), contentAlignment = Alignment.Center) {
                                    Box(Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) { FrianzoAvatar(group.user?.profilePictureUrl, 56.dp) }
                                }
                                if (isOwn) { Box(Modifier.offset(y = (-16).dp).size(20.dp).clip(CircleShape).background(FrianzoBlue), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White) } }
                                Spacer(Modifier.height(4.dp))
                                Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (statusGroups.none { it.userId == myId || it.user?.id == myId }) {
                            item {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp).clickable { showStoryCreate = true }) {
                                    Box(contentAlignment = Alignment.BottomEnd) {
                                        Box(Modifier.size(60.dp).clip(CircleShape).background(FrianzoBlueTint), contentAlignment = Alignment.Center) { FrianzoAvatar(myAvatar, 56.dp) }
                                        Box(Modifier.size(20.dp).clip(CircleShape).background(FrianzoBlue), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White) }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text("Your story", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
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
                            // A-D1: this row is labelled "Your story", so it
                            // must open story creation like the real row does.
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp).clickable { showStoryCreate = true }) {
                                Box(contentAlignment = Alignment.BottomEnd) {
                                    Box(Modifier.size(60.dp).clip(CircleShape).background(FrianzoBlueTint), contentAlignment = Alignment.Center) { FrianzoAvatar(myAvatar, 56.dp) }
                                    Box(Modifier.size(20.dp).clip(CircleShape).background(FrianzoBlue), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White) }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text("Your story", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        // A-D9: the story load failed — offer a retry
                        // instead of silently showing placeholders.
                        if (statusFailed) {
                            item {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
                                    Box(Modifier.size(60.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                                        Text("!", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    TextButton(onClick = {
                                        statusFailed = false
                                        followScope.launch {
                                            try {
                                                val sf = vm.api2.statusFeed()
                                                if (sf.success && sf.data != null) statusGroups = sf.data.feed ?: emptyList() else statusFailed = true
                                            } catch (_: Exception) { statusFailed = true }
                                        }
                                    }, contentPadding = PaddingValues(0.dp)) { Text("Retry", fontSize = 12.sp, color = FrianzoBlue) }
                                }
                            }
                        }
                        items(storyAuthors) { author ->
                            // A-D2: placeholders are authors with no loaded
                            // story — not tappable as stories any more.
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
                                Box(Modifier.size(60.dp).clip(CircleShape).background(FrianzoBlue), contentAlignment = Alignment.Center) { FrianzoAvatar(author.profilePictureUrl, 56.dp) }
                                Spacer(Modifier.height(4.dp))
                                Text((author.displayName ?: author.username ?: "User").substringBefore(" "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        itemsIndexed(posts, key = { _, p -> p.id }) { index, post ->
            LaunchedEffect(post.id) { vm.recordPostView(post.id) }
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
                            Text(post.author?.displayName ?: post.author?.username ?: "User", modifier = Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            if (post.author?.isVerified == true) { Spacer(Modifier.width(4.dp)); Icon(Icons.Filled.CheckCircle, contentDescription = "Verified", modifier = Modifier.size(16.dp), tint = FrianzoBlue) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val time = feedTime(post.createdAt)
                            Text((if (time.isNotBlank()) time else "Just now") + (post.location?.let { " · $it" } ?: ""), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            if (post.isPinned) { Spacer(Modifier.width(6.dp)); Box(Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 6.dp, vertical = 2.dp)) { Text("Pinned", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                        }
                    }
                    val authorId = post.author?.id
                    val fstatus = authorId?.let { followStates[it] }
                    // A-B4: no button at all until the status has loaded —
                    // showing "Follow" first lets a tap accidentally
                    // unfollow someone already followed. Disabled + guarded
                    // while a toggle is in flight; failures show an error
                    // instead of silently doing nothing.
                    if (authorId != null && authorId != myId && fstatus != null && fstatus != "self") {
                        val ftext = when (fstatus) { "following" -> "Following"; "friends" -> "Friends"; "follow_back" -> "Follow Back"; else -> "Follow" }
                        val fBusy = authorId in followBusyIds
                        OutlinedButton(
                            enabled = !fBusy,
                            onClick = {
                                if (authorId in followBusyIds) return@OutlinedButton
                                followBusyIds = followBusyIds + authorId
                                followScope.launch {
                                    try {
                                        val r = vm.api2.toggleFollow(authorId)
                                        if (r.success && r.data != null) {
                                            val ns = r.data.friendStatus ?: if (r.data.following || r.data.isFollowing) "following" else "none"
                                            followStates = followStates + (authorId to ns)
                                        } else followError = r.error?.message ?: "Could not change follow status"
                                    } catch (e: Exception) { followError = e.message ?: "Could not change follow status" }
                                    finally { followBusyIds = followBusyIds - authorId }
                                }
                            },
                            modifier = Modifier.height(48.dp),
                            shape = RoundedCornerShape(28.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        ) { Text(ftext, fontSize = 13.sp, color = FrianzoBlue) }
                        Spacer(Modifier.width(8.dp))
                    }
                    Box {
                        IconButton(onClick = { menuPost = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    if (menuPost) {
                        PostMenuSheet(vm.api2, post, myId, followStates[post.author?.id], onClose = { menuPost = false }, onAuthor = onAuthor, onShare = { sharePost = post }, onComments = { commentPost = post }, onDetail = { detailPostId = post.id }, onHidden = { vm.hidePost(it) }, onAudienceChanged = { id, v -> vm.updateCommentAudience(id, v) }, onEdited = { vm.refresh() })
                    }
                    // Always-visible Hide X, live layout.
                    // A-B5: only hide locally when the server hide
                    // succeeded — hiding offline used to vanish the post
                    // until the next refresh, with no error.
                    IconButton(onClick = { followScope.launch { try { val r = vm.api2.hidePost(post.id); if (r.success) vm.hidePost(post.id) else followError = r.error?.message ?: "Could not hide post" } catch (e: Exception) { followError = e.message ?: "Could not hide post" } } }) { Icon(Icons.Filled.Close, contentDescription = "Hide", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
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
                        ) {
                            // v5.18: hashtags/mentions tappable on the tile
                            // (web); a plain tap opens the detail.
                            PostContentText(it, fontSizeSp = 24, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(24.dp), onAuthor = onAuthor, onTag = onTag, onTextClick = { detailPostId = post.id })
                        }
                    } else {
                        // v5.18: hashtags/mentions tappable (web).
                        PostContentText(it, fontSizeSp = 15, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 12.dp), onAuthor = onAuthor, onTag = onTag, onTextClick = { detailPostId = post.id })
                    }
                }
                post.imageUrl?.let {
                    Spacer(Modifier.height(10.dp))
                    PostPhoto(it, onClick = { detailPostId = post.id })
                }
                post.originalPost?.let { orig -> Spacer(Modifier.height(10.dp)); OriginalPostCard(orig, onAuthor = onAuthor, onDetail = { detailPostId = orig.id }) }
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
                            null -> Icon(Icons.Outlined.ThumbUp, contentDescription = "Like", modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            "like" -> Icon(Icons.Filled.ThumbUp, contentDescription = "Like", modifier = Modifier.size(24.dp), tint = FrianzoBlue)
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
            // v3.8 (F13): a reel card every 2 posts (web rule).
            if ((index + 1) % 2 == 0) {
                val ri = (index + 1) / 2 - 1
                feedReels.getOrNull(ri)?.let { reel ->
                    FeedReelCard(vm.api2, reel, onOpenReel = onOpenReel, onAuthor = onAuthor, onTag = onTag, onOpenChat = onOpenChat)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        item {
            // v5.18.5: footer states are centred (they were bare
            // left-aligned text at the bottom of the feed).
            if (followError != null) Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), contentAlignment = Alignment.Center) { Text(followError!!, color = Color.Red, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
            if (error != null) Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { Text(error!!, color = Color.Red, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
            if (loading) {
                if (posts.isEmpty()) {
                    // v5.12: skeleton post cards on a bare first load (no
                    // cache) instead of a blank screen with dots. When posts
                    // are already on screen this stays the dots footer
                    // (a load-more wait, not a first load).
                    Column {
                        repeat(3) { SkeletonPostCard() }
                    }
                } else {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { DotsLoading() }
                }
            } else if (posts.isEmpty() && error != null) {
                // v5.12: explicit Retry on a first-load failure (the old
                // "Load more" button below was not an obvious retry).
                Button(onClick = { vm.retry() }, modifier = Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(28.dp)) { Text("Retry", maxLines = 1, softWrap = false) }
            } else if (hasMore) {
                OutlinedButton(onClick = { vm.loadMore() }, modifier = Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(28.dp)) { Text("Load more") }
            } else if (posts.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { Text("No posts yet", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { Text("No more posts", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
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
// DECIDED (owner, 2026-11-04): the most professional reactions are the
// Facebook-style 3D gradient faces, exactly as on live web — blue gradient
// circle + white thumb (Like), pink/red gradient + white heart (Love),
// yellow gradient faces (Haha/Wow/Sad), orange gradient face (Angry).
// They are drawn by Canvas ReactionIcon in ReactionIcons.kt and used
// EVERYWHERE reactions appear. Never flat system emojis, never Material
// face icons. The old system-emoji helpers are deleted, not kept.
// v5.20 (M7): public — TagScreen's full post card uses the same list.
fun reactionTypes(): List<String> = listOf("like", "love", "haha", "wow", "sad", "angry")

@Composable
private fun ReactionEmojiText(type: String?, fontSize: androidx.compose.ui.unit.TextUnit, modifier: Modifier = Modifier) {
    ReactionIcon(type, fontSize.value.dp, modifier)
}


// v5.20 (M7): takes api2 instead of the FeedViewModel and is public, so
// TagScreen's full post card reuses this exact sheet (same DM picker,
// tiles, Repost button) instead of a copy. The VM was only ever used
// for vm.api2 — no VM list state lives here.
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PostShareSheet(api2: FrianzoApi2, post: Post, onClose: () -> Unit, onOpenChat: (String, String) -> Unit, onShared: () -> Unit = {}) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    // v5.18: "Send to" picker (web /messages/new) — the old DM tile
    // created a chat with the AUTHOR and opened it EMPTY; the link was
    // never sent (but the share count incremented). Now the user picks
    // recipients and the link is actually sent to each of them.
    var showDmPicker by remember { mutableStateOf(false) }
    var dmQ by remember { mutableStateOf("") }
    var dmFound by remember { mutableStateOf<List<PublicProfile>>(emptyList()) }
    var dmSelected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var dmSelectedUsers by remember { mutableStateOf<List<PublicProfile>>(emptyList()) }
    var dmSending by remember { mutableStateOf(false) }
    LaunchedEffect(dmQ) {
        if (dmQ.isBlank()) { dmFound = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(300)
        val term = dmQ
        try {
            val r = api2.searchUsers(term)
            if (term == dmQ && r.success && r.data != null) dmFound = r.data.users
        } catch (_: Exception) {}
    }
    // A-D8: `reposted` was dead state (never read or written) — deleted.
    // A-B11/A-D4/A-D5: errors from share taps (no author, app missing).
    var shareError by remember { mutableStateOf<String?>(null) }
    // A-B13: Repost in-flight guard.
    var repostBusy by remember { mutableStateOf(false) }
    var repostError by remember { mutableStateOf<String?>(null) }
    // Web: the feed's permanent share code is used first, no API call.
    var link by remember { mutableStateOf(if (!post.shareCode.isNullOrBlank()) "https://frianzo.online/s/${post.shareCode}" else "https://frianzo.online/post/${post.id}") }
    LaunchedEffect(post.id) {
        if (!post.shareCode.isNullOrBlank()) return@LaunchedEffect
        try {
            val r = api2.createShareLink(mapOf("type" to "post", "id" to post.id))
            val code = r.data?.code
            if (r.success && !code.isNullOrBlank()) link = "https://frianzo.online/s/$code"
        } catch (_: Exception) {}
    }
    fun record(channel: String) { scope.launch { try { api2.recordShareEvent(mapOf("type" to "post", "id" to post.id, "channel" to channel)) } catch (_: Exception) {} } }
    // v5.18: send the link to every picked recipient (declared after
    // `link` and `record` — a local fun may only use what is declared
    // above it).
    fun sendDm() {
        if (dmSelected.isEmpty() || dmSending) return
        dmSending = true; shareError = null
        val targets = dmSelectedUsers.filter { it.id in dmSelected }
        scope.launch {
            var sent = 0
            var firstConv: Pair<String, String>? = null
            for (u in targets) {
                val uid = u.id ?: continue
                try {
                    val res = api2.createConversation(com.frianzo.app.data.CreateConversationRequest(uid))
                    val convId = res.data?.id ?: continue
                    val obj = org.json.JSONObject()
                    obj.put("conversationId", convId)
                    obj.put("content", link)
                    val ack = ChatSocket.emitAck("message:send", obj)
                    if (ack?.optBoolean("success") == true) {
                        sent += 1
                        if (firstConv == null) firstConv = convId to (u.displayName ?: u.username ?: "User")
                    }
                } catch (_: Exception) {}
            }
            dmSending = false
            if (sent > 0) {
                record("dm"); onShared()
                showDmPicker = false; onClose()
                if (sent == 1 && firstConv != null) onOpenChat(firstConv!!.first, firstConv!!.second)
                else Toast.makeText(context, "Sent to $sent chats", Toast.LENGTH_SHORT).show()
            } else shareError = "Couldn't send — try again."
        }
    }
    // A-B11 + A-D5: an external share is counted ONLY after its app
    // actually opens — never before the tap, never when the target app
    // is missing, and never when the user only opened the sheet. The
    // local count (onShared) is NOT bumped for external intents at all:
    // Android cannot know whether the user completed the share, so
    // incrementing on tap inflated the count (a cancelled WhatsApp
    // share still counted). Copy and DM increment because those actions
    // complete inside this app. NOTE: the backend has no "confirmed
    // share" concept — recordShareEvent just inserts a ShareEvent row
    // per call (verified in vynzo-backend shareService), so the server
    // count stays tap-based by design; NEEDS-BACKEND if a confirmed
    // share count is ever wanted server-side.
    fun openUrl(url: String, channel: String) {
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
        if (intent.resolveActivity(context.packageManager) == null) {
            shareError = "That app isn't installed on this phone."
            return
        }
        try {
            context.startActivity(intent)
            record(channel)
            onClose()
        } catch (e: Exception) {
            shareError = "Couldn't open that app. Try again."
        }
    }
    // v5.15 (owner order, his screenshots): clean sheet like the profile
    // system-share look. NO visible URL anywhere — only a full-width
    // Copy link button; the link itself is only copied / passed to the
    // share intents, never displayed.
    ModalBottomSheet(
        onDismissRequest = onClose,
        modifier = Modifier.fillMaxHeight(0.85f),
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        dragHandle = { Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) { Box(Modifier.size(40.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(FrianzoBorder)) } },
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Share", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { clipboard.setText(AnnotatedString(link)); copied = true; record("copy"); onShared(); Toast.makeText(context, "Link copied!", Toast.LENGTH_SHORT).show(); scope.launch { kotlinx.coroutines.delay(2000); copied = false } },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) { Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (copied) "Copied!" else "Copy link") }
            Spacer(Modifier.height(16.dp))
            // Web grid order, 4 per row. The web tiles are LETTER circles
            // (owner's screenshot 1), not brand logos — clean letters in
            // brand colours, 56dp circles, 11sp labels (ShareTile).
            Row(Modifier.fillMaxWidth()) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    // v5.18: opens the "Send to" picker (web /messages/new).
                    // The old tile created an EMPTY chat with the author.
                    ShareTile("Frianzo DM", Color(0xFF0F172A), "F") { showDmPicker = true }
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { ShareTile("WhatsApp", Color(0xFF25D366), "W") { openUrl("https://wa.me/?text=" + android.net.Uri.encode(link), "whatsapp") } }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { ShareTile("WA Status", Color(0xFF25D366), "W") { openUrl("whatsapp://send?text=" + android.net.Uri.encode(link), "whatsapp_status") } }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { ShareTile("Messenger", Color(0xFF0084FF), "M") { openUrl("fb-messenger://share?link=" + android.net.Uri.encode(link), "messenger") } }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { ShareTile("Facebook", Color(0xFF1877F2), "f") { openUrl("https://www.facebook.com/sharer/sharer.php?u=" + android.net.Uri.encode(link), "facebook") } }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { ShareTile("FB Story", Color(0xFF1877F2), "f") { openUrl("fb://story/camera?attachment_url=" + android.net.Uri.encode(link), "facebook_story") } }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { ShareTile("Text message", Color(0xFF34C759), "S") { openUrl("sms:?body=" + android.net.Uri.encode(link), "sms") } }
                Spacer(Modifier.weight(1f))
            }
            if (shareError != null) { Spacer(Modifier.height(8.dp)); Text(shareError!!, fontSize = 13.sp, color = Color.Red) }
            if (repostError != null) { Spacer(Modifier.height(8.dp)); Text(repostError!!, fontSize = 13.sp, color = Color.Red) }
            Spacer(Modifier.height(12.dp))
            // Web: reposts are counted server-side and must NOT also be
            // recorded as a share event (that would double-count them).
            // On success the sheet closes (web: onClose + reload).
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            // v5.15 (owner order): Repost is a proper full-width button,
            // same slate-900 style as Copy link — not blue text.
            Button(enabled = !repostBusy, onClick = {
                // A-B13: double-tap guard — a second tap while the first
                // repost is in flight is ignored, so no duplicate repost.
                if (repostBusy) return@Button
                repostBusy = true
                scope.launch {
                    try {
                        val r = api2.repostPost(post.id, mapOf("content" to ""))
                        // A-B13: no vm.refresh() on success — a full
                        // refresh wipes the feed list and scroll position.
                        // The repost appears after the next pull-to-refresh.
                        if (r.success) { onClose() } else repostError = r.error?.message ?: "Could not repost"
                    } catch (e: Exception) { repostError = e.message }
                    finally { repostBusy = false }
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Repeat, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (repostBusy) "Reposting…" else "Repost to your feed")
            }
        }
    }
    // v5.18: "Send to" picker (web /messages/new?share=<url>): search
    // people, multi-select, Send — the link goes to each recipient.
    if (showDmPicker) {
        ModalBottomSheet(
            onDismissRequest = { showDmPicker = false },
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Send to", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false)
                    if (dmSelected.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Text("${dmSelected.size} selected", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { showDmPicker = false }, contentPadding = PaddingValues(0.dp)) { Text("Cancel", fontSize = 14.sp, color = FrianzoBlue, maxLines = 1, softWrap = false) }
                }
                HorizontalDivider(color = FrianzoBorder)
                OutlinedTextField(
                    value = dmQ, onValueChange = { dmQ = it },
                    label = { Text("Search people") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    shape = RoundedCornerShape(12.dp),
                )
                dmFound.forEach { u ->
                    val selected = u.id != null && dmSelected.contains(u.id)
                    Row(
                        Modifier.fillMaxWidth().background(if (selected) FrianzoBlueTint else Color.Transparent).clickable {
                            val id = u.id ?: return@clickable
                            if (selected) {
                                dmSelected = dmSelected - id
                                dmSelectedUsers = dmSelectedUsers.filter { it.id != id }
                            } else {
                                dmSelected = dmSelected + id
                                dmSelectedUsers = dmSelectedUsers + u
                            }
                        }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FrianzoAvatar(u.profilePictureUrl, 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(u.displayName ?: u.username ?: "User", Modifier.weight(1f), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Box(Modifier.size(20.dp).clip(CircleShape).background(if (selected) FrianzoBlue else Color.Transparent).border(if (selected) 0.dp else 1.dp, if (selected) Color.Transparent else FrianzoBorder, CircleShape), contentAlignment = Alignment.Center) {
                            if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                        }
                    }
                }
                if (dmQ.isNotBlank() && dmFound.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { Text("No people found", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { sendDm() },
                    enabled = dmSelected.isNotEmpty() && !dmSending,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(48.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface),
                ) {
                    Text(if (dmSending) "Sending…" else "Send (${dmSelected.size})", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

@Composable
private fun ShareTile(label: String, color: Color, letter: String, enabled: Boolean = true, onClick: () -> Unit) {
    // A-D8: a disabled tile (e.g. DM while one is being created) is dimmed
    // and does not fire.
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(enabled = enabled) { onClick() }.padding(4.dp).let { if (!enabled) it.alpha(0.4f) else it }) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
            Text(letter, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

// v3.8 (F13): reel card in the home feed, one every 2 posts (web rule) —
// muted autoplay video, caption expand, Like, comment opens the reel,
// share, mute toggle. Web sizes: avatar 40dp, username 15sp bold,
// caption 14sp, counts 13sp, Like 28x24, Comment/Share 26x22, NO time
// label, NO eye icon, NO star.
@Composable
fun FeedReelCard(api2: FrianzoApi2, reel: Reel, onOpenReel: (String) -> Unit, onAuthor: (String) -> Unit = {}, onTag: (String) -> Unit = {}, onOpenChat: (String, String) -> Unit = { _, _ -> }) {
    val scope = rememberCoroutineScope()
    var liked by remember(reel.id) { mutableStateOf(reel.liked) }
    var likes by remember(reel.id) { mutableStateOf(reel.likeCount) }
    var cardMuted by remember { mutableStateOf(true) }
    var paused by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    // v5.20 (M6): share opens the Frianzo ReelShareSheet (web ShareModal
    // parity), exactly as the full-screen ReelPage does — same call-site
    // pattern, same lazy /s/ short link. The old Android system chooser
    // (ACTION_SEND) is deleted, not kept.
    var shareOpen by remember { mutableStateOf(false) }
    val defaultReelLink = "https://frianzo.online/reel/${reel.id}"
    var shareLink by remember(reel.id) { mutableStateOf(defaultReelLink) }
    // Same lazy rule as ReelPage (B-B8): the POST /api/share that mints
    // the short link fires only when the sheet opens — scrolling past a
    // card must not mint a link. A failed mint keeps the default link.
    LaunchedEffect(shareOpen) {
        if (shareOpen && shareLink == defaultReelLink) {
            try {
                val r = api2.createShareLink(mapOf("type" to "reel", "id" to reel.id))
                val code = r.data?.code
                if (r.success && !code.isNullOrBlank()) shareLink = "https://frianzo.online/s/$code"
            } catch (_: Exception) {}
        }
    }
    // v5.20 (M6): local share count, bumped only by the sheet's
    // onShared — i.e. only after a share event recorded server-side.
    var shares by remember(reel.id) { mutableStateOf(reel.shareCount) }
    // Web rule: a view is recorded when the card appears.
    LaunchedEffect(reel.id) { try { api2.recordReelView(reel.id, mapOf("watchSec" to 0)) } catch (_: Exception) {} }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            FrianzoAvatar(reel.author?.profilePictureUrl, 40.dp)
            Spacer(Modifier.width(8.dp))
            Text(reel.author?.displayName ?: "@${reel.author?.username ?: ""}", fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (!reel.createdAt.isNullOrBlank()) { Spacer(Modifier.width(4.dp)); Text("· ${feedReelTimeAgo(reel.createdAt)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false) }
            if (reel.author?.isVerified == true) { Spacer(Modifier.width(4.dp)); Icon(Icons.Filled.CheckCircle, contentDescription = "Verified", modifier = Modifier.size(16.dp), tint = FrianzoBlue) }
            IconButton(onClick = { cardMuted = !cardMuted }) { Icon(if (cardMuted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp, contentDescription = if (cardMuted) "Unmute" else "Mute", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (reel.videoUrl != null) {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp).background(Color.Black).clickable { paused = !paused }, contentAlignment = Alignment.Center) {
                // A-B24: onRelease stops playback when the card leaves
                // the composition — without it, off-screen videos kept
                // playing (muted) after scrolling away.
                AndroidView(factory = { ctx -> VideoView(ctx).apply {
                    setVideoURI(Uri.parse(reel.videoUrl))
                    setOnPreparedListener { mp -> player = mp; mp.setVolume(0f, 0f); mp.isLooping = true; if (!paused) start() }
                } }, modifier = Modifier.fillMaxWidth().aspectRatio(9f / 16f), update = { view ->
                    // v4.2: setVolume() throws on a dead/errored player.
                    try { player?.setVolume(if (cardMuted) 0f else 1f, if (cardMuted) 0f else 1f) } catch (_: Exception) {}
                    if (paused) view.pause() else if (!view.isPlaying) view.start()
                }, onRelease = { player = null; try { it.stopPlayback() } catch (_: Exception) {} })
                if (paused) Box(Modifier.size(84.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(40.dp))
                }
            }
        }
        // v3.9 (live-web audit #2): no more/less expander — one line, ellipsis only.
        // v5.18: hashtags/mentions tappable in feed reel captions (web);
        // a plain tap opens the reel.
        reel.caption?.let {
            PostContentText(it, fontSizeSp = 14, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp), onAuthor = onAuthor, onTag = onTag, onTextClick = { onOpenReel(reel.id) })
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.clickable {
                val prevLiked = liked; val prevLikes = likes
                liked = !liked; likes = (likes + if (liked) 1 else -1).coerceAtLeast(0)
                scope.launch { try { val r = api2.toggleReelLike(reel.id); if (r.success && r.data != null) { liked = r.data.liked; likes = r.data.likeCount } else { liked = prevLiked; likes = prevLikes } } catch (_: Exception) { liked = prevLiked; likes = prevLikes } }
            }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, contentDescription = "Like", tint = if (liked) Color(0xFFEF4444) else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(width = 28.dp, height = 24.dp))
                Spacer(Modifier.width(4.dp))
                Text("$likes", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.clickable { onOpenReel(reel.id) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = "Comments", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(width = 26.dp, height = 22.dp))
                Spacer(Modifier.width(4.dp))
                Text("${reel.commentCount}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // v5.20 (M6): opens the in-app ReelShareSheet (Frianzo DM +
            // channels) — the sheet records the share event per channel
            // and bumps the count via onShared, so nothing is recorded
            // here. The old system chooser recorded channel "system".
            Row(Modifier.clickable { shareOpen = true }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Share, contentDescription = "Share", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(width = 26.dp, height = 22.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (shares > 0) "$shares" else "Share", fontSize = if (shares > 0) 13.sp else 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // Same call-site pattern as ReelPage (Screens2.kt): the sheet
        // owns Copy/DM/channels and calls onShared only after a share
        // event actually recorded server-side.
        if (shareOpen) {
            ReelShareSheet(api2, reel, shareLink, onClose = { shareOpen = false }, onShared = { shares += 1 }, onOpenChat = onOpenChat)
        }
    }
}

// Same relative-time rule as the reels screen ("2d", "3h") — local copy
// because reelTimeAgo in Screens2.kt is file-private.
private fun feedReelTimeAgo(createdAt: String?): String {
    if (createdAt.isNullOrBlank()) return ""
    val formats = listOf(
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US),
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US),
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PostCommentsDialog(api2: FrianzoApi2, post: Post, myId: String? = null, myAvatar: String? = null, onClose: () -> Unit, onCountChange: (Int) -> Unit) {
    // Shared header with the live count; kept in sync by CommentSection's
    // onCountChange (add/delete). No Top/Newest pill — owner order: native
    // post comments stay pill-less, same as the reel sheet.
    var headerCount by remember { mutableStateOf(post.commentCount) }
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            // Sorting is newest first, so a new comment lands at the top like FB.
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Text("$headerCount comments", fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close comments", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            HorizontalDivider(color = FrianzoBorder)
            CommentSection(api2, post.id, post.commentCount, myId, post.author?.id, myAvatar = myAvatar, fillHeight = true, onCountChange = { nc -> headerCount = nc; onCountChange(nc) })
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
// v2.9: dead sortBy param removed (it was never read — the web has a
// Top/Newest sort, but the owner ordered native to keep NO sort).
private fun ColumnScope.CommentSection(api2: FrianzoApi2, postId: String, initialCount: Int, myId: String? = null, postOwnerId: String? = null, myAvatar: String? = null, fillHeight: Boolean = false, onCountChange: (Int) -> Unit = {}) {
    val context = LocalContext.current
    var comments by remember { mutableStateOf<List<Comment>>(emptyList()) }
    var draft by rememberSaveable { mutableStateOf("") }
    var replyTo by remember { mutableStateOf<Comment?>(null) }
    var imageUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // A-B14: sending state — the Send button had none.
    var sending by remember { mutableStateOf(false) }
    var count by remember { mutableStateOf(initialCount) }
    var expandedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    // Follow status per comment author (same GET /api/follows/{id}/status
    // the feed uses) so the bubble can show a real "Follow Back" pill.
    var followStates by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var mentionQuery by remember { mutableStateOf<String?>(null) }
    var mentionResults by remember { mutableStateOf<List<PublicProfile>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri -> if (uri != null) imageUri = uri }
    val pickCommentPhoto = rememberPermissionGate(galleryPermissions(), onGranted = { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) })
    fun load() {
        scope.launch {
            try {
                val res = api2.postComments(postId)
                // A-B3: a later successful load clears an earlier error.
                if (res.success && res.data != null) { comments = res.data.comments; error = null } else error = res.error?.message
            } catch (e: Exception) { error = e.message }
        }
    }
    LaunchedEffect(postId) { load() }
    LaunchedEffect(comments) {
        val authorIds = (comments.map { it.author } + comments.flatMap { flattenReplies(it.replies, it.author).map { pair -> pair.first.author } })
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
            if (sorted.isEmpty() && error == null) item { Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) { Text("No comments yet. Be the first to comment.", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            items(sorted, key = { it.id }) { c ->
                CommentRow(api2, c, myId, postOwnerId, 0, null, followStatus = followStates[c.author?.id], onFollowChange = { aid, st -> followStates = followStates + (aid to st) }, onReply = { replyTo = it }, onChanged = { load() }, onDeleted = { val nc = (count - 1).coerceAtLeast(0); count = nc; onCountChange(nc) })
                val flatReplies = flattenReplies(c.replies, c.author)
                if (flatReplies.isNotEmpty()) {
                    val isExpanded = c.id in expandedIds
                    Column(Modifier.fillMaxWidth().padding(start = 40.dp, top = 2.dp)) {
                        if (!isExpanded) {
                            TextButton(onClick = { expandedIds = expandedIds + c.id }, contentPadding = PaddingValues(0.dp)) { Text("View ${flatReplies.size} ${if (flatReplies.size == 1) "reply" else "replies"}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        } else {
                            flatReplies.forEach { (r, parentAuthor) ->
                                // A-B16: deleting a REPLY does not change the
                                // count — the server's commentCount counts
                                // ONLY top-level comments (verified in
                                // vynzo-backend postService toPostDTO:
                                // _count.comments where parentCommentId ==
                                // null). Replies are cascade-deleted with
                                // their parent (Prisma Comment "Replies"
                                // relation onDelete: Cascade), and the
                                // parent delete below decrements by exactly
                                // 1, which is the correct server delta.
                                // deleteComment returns only {deleted:true},
                                // no count — NEEDS-BACKEND if a server
                                // count is ever wanted in that response.
                                CommentRow(api2, r, myId, postOwnerId, 1, parentAuthor, followStatus = followStates[r.author?.id], onFollowChange = { aid, st -> followStates = followStates + (aid to st) }, onReply = { replyTo = it }, onChanged = { load() }, onDeleted = {})
                            }
                            TextButton(onClick = { expandedIds = expandedIds - c.id }, contentPadding = PaddingValues(0.dp)) { Text("Hide replies", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        }
        if (replyTo != null) {
            // v5.18.5: the banner background stays the light blue tint in
            // both themes (like the amber banners), so the text is a
            // fixed dark slate — the old onSurfaceVariant is light grey
            // in dark mode and unreadable on the light tint.
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(FrianzoBlueTint).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = androidx.compose.ui.text.buildAnnotatedString {
                        append("Replying to ")
                        withStyle(androidx.compose.ui.text.SpanStyle(color = FrianzoBlue, fontWeight = FontWeight.SemiBold)) { append("@${replyTo!!.author?.username ?: "User"}") }
                    },
                    fontSize = 13.sp, color = Color(0xFF334155),
                    modifier = Modifier.weight(1f)
                )
                // v3.9 (live-web audit #2): web uses a "Cancel" TEXT button, not an x.
                TextButton(onClick = { replyTo = null }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Cancel", fontSize = 13.sp, color = Color(0xFF334155)) }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (mentionQuery != null && mentionResults.isNotEmpty()) {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp, border = BorderStroke(1.dp, FrianzoBorder)) { Column(Modifier.padding(vertical = 4.dp)) {
                mentionResults.forEach { u ->
                    Row(Modifier.fillMaxWidth().clickable {
                        val q = mentionQuery ?: ""
                        val idx = draft.lastIndexOf("@$q")
                        draft = if (idx >= 0) draft.substring(0, idx) + "@${u.username} " + draft.substring(idx + q.length + 1) else draft + "@${u.username} "
                        mentionQuery = null; mentionResults = emptyList()
                    }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FrianzoAvatar(u.profilePictureUrl, 28.dp)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(u.displayName ?: u.username ?: "", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            Text("@${u.username}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            } }
            Spacer(Modifier.height(8.dp))
        }
        if (imageUri != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(model = imageUri, contentDescription = "Comment attachment", modifier = Modifier.heightIn(max = 128.dp).clip(RoundedCornerShape(8.dp)))
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { imageUri = null }) { Text("Remove", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (error != null) { Text(error!!, fontSize = 13.sp, color = Color.Red); Spacer(Modifier.height(4.dp)) }
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            FrianzoAvatar(myAvatar, 32.dp)
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { pickCommentPhoto() }, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.AddPhotoAlternate, contentDescription = "Add photo", modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
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
                shape = RoundedCornerShape(FrianzoUi.ButtonRadius),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedBorderColor = FrianzoBorder,
                    unfocusedBorderColor = FrianzoBorder
                )
            )
            Spacer(Modifier.width(8.dp))
            Button(
                // A-B14: disabled while a send is in flight (no double send).
                enabled = !sending && (draft.isNotBlank() || imageUri != null),
                modifier = Modifier.height(FrianzoUi.ButtonHeight),
                contentPadding = PaddingValues(horizontal = 16.dp),
                shape = RoundedCornerShape(FrianzoUi.ButtonRadius),
                colors = ButtonDefaults.buttonColors(containerColor = FrianzoBlue, contentColor = Color.White),
                onClick = {
                    if (sending) return@Button
                    // A-B14: capture the values but do NOT clear the
                    // composer yet. The draft / photo / reply target are
                    // cleared only on success — on a server rejection
                    // (success == false, not an exception) the old code
                    // lost the typed comment forever; now it stays put
                    // with an error, and a failed reply stays a reply.
                    val text = draft; val uri = imageUri; val parent = replyTo?.id
                    sending = true; error = null
                    scope.launch {
                        try {
                            val ok = if (uri != null) {
                                // A-B29: check the size BEFORE reading the
                                // whole file into memory. openFileDescriptor
                                // gives statSize without allocating; when
                                // the size is unknown, stream with a cap so
                                // a huge photo can never OOM the app.
                                val fdSize = try { context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } } catch (_: Exception) { null }
                                if (fdSize != null && fdSize > 5 * 1024 * 1024) { error = "Image must be under 5MB."; false }
                                else {
                                    val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
                                        val out = java.io.ByteArrayOutputStream()
                                        val buf = ByteArray(8192)
                                        var total = 0L
                                        while (true) {
                                            val n = stream.read(buf)
                                            if (n < 0) break
                                            total += n
                                            if (total > 5 * 1024 * 1024) return@use null
                                            out.write(buf, 0, n)
                                        }
                                        out.toByteArray()
                                    }
                                    if (bytes == null) { error = "Image must be under 5MB."; false }
                                    else {
                                        val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                                        val body = okhttp3.RequestBody.create(mime.toMediaTypeOrNull(), bytes)
                                        val part = okhttp3.MultipartBody.Part.createFormData("image", "comment.jpg", body)
                                        val contentBody = okhttp3.RequestBody.create("text/plain".toMediaTypeOrNull(), text)
                                        val parentBody = parent?.let { okhttp3.RequestBody.create("text/plain".toMediaTypeOrNull(), it) }
                                        api2.addPostCommentImage(postId, contentBody, parentBody, part).success
                                    }
                                }
                            } else {
                                api2.addPostComment(postId, CommentCreateRequest(text, parent)).success
                            }
                            if (ok) { draft = ""; imageUri = null; replyTo = null; mentionQuery = null; load(); count += 1; onCountChange(count) }
                            else if (error == null) error = "Could not send comment"
                        } catch (e: Exception) {
                            if (Outbox.isNetworkError(e) && uri == null) {
                                // v5.12: offline text comment — queue it
                                // (image comments are not queued) and clear
                                // the composer so it cannot be sent twice.
                                Outbox.enqueueJson(context, "COMMENT_POST", postId, mapOf("text" to text, "parent" to (parent ?: "")))
                                draft = ""; replyTo = null; mentionQuery = null
                                error = "Waiting for connection…"
                            } else error = e.message ?: "Could not send comment"
                        }
                        finally { sending = false }
                    }
                },
            ) { Text(if (sending) "Sending…" else "Send") }
        }
    }
}

@Composable
private fun CommentRow(api2: FrianzoApi2, c: Comment, myId: String?, postOwnerId: String?, depth: Int, parentAuthor: Author?, followStatus: String? = null, onFollowChange: (String, String) -> Unit = { _, _ -> }, onReply: (Comment) -> Unit, onChanged: () -> Unit, onDeleted: () -> Unit = {}) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    // A-B15: key all per-comment state to the comment id / server values,
    // so a list reload after a web like (or an edit elsewhere) cannot
    // leave this row showing stale reaction/count/text.
    var editDraft by rememberSaveable(c.id) { mutableStateOf(c.content ?: "") }
    var localReaction by remember(c.id, c.myReaction) { mutableStateOf(c.myReaction) }
    var localCount by remember(c.id, c.reactionCount) { mutableStateOf(c.reactionCount) }
    var menuOpen by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var reported by remember { mutableStateOf(false) }
    var reportError by remember { mutableStateOf<String?>(null) }
    // v5.21 (M5): report reason picker (same 7 reasons + optional
    // details as the reel report) — reports used to file "other".
    var showReport by remember { mutableStateOf(false) }
    var reportReason by remember { mutableStateOf("other") }
    var reportDetails by remember { mutableStateOf("") }
    var reportBusy by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    var deleteBusy by remember { mutableStateOf(false) }
    // v5.20 (M1): the full 6-reaction picker (web CommentItem parity),
    // same UI/API pattern as reel comments (Screens2.kt
    // ReelCommentRow): tap Like opens the picker, long-press opens the
    // ⋮ menu. Sending the current reaction again removes it (server
    // toggles, same as POST /api/posts/{id}/reaction); sending a new
    // one switches it. The old tap-only "like" is deleted, not kept.
    var pickerOpen by remember { mutableStateOf(false) }
    var reactionBusy by remember { mutableStateOf(false) }
    fun applyReaction(type: String) {
        if (reactionBusy) return
        val prevReaction = localReaction; val prevCount = localCount
        val target: String? = if (type == prevReaction) null else type
        localReaction = target
        localCount = (prevCount + if (target == null) -1 else if (prevReaction == null) 1 else 0).coerceAtLeast(0)
        val sendType = target ?: prevReaction ?: "like"
        reactionBusy = true
        scope.launch {
            try { if (!api2.setCommentReaction(c.id, mapOf("type" to sendType)).success) { localReaction = prevReaction; localCount = prevCount } }
            catch (_: Exception) { localReaction = prevReaction; localCount = prevCount }
            finally { reactionBusy = false }
        }
    }
    val isReply = depth > 0
    val isOwner = myId != null && c.author?.id == myId
    val canDelete = isOwner || (myId != null && postOwnerId != null && myId == postOwnerId)
    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            FrianzoAvatar(c.author?.profilePictureUrl, if (isReply) 28.dp else 32.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                // v5.18.5: theme-aware bubble — the old hardcoded light
                // greys (#F0F2F5 / #F8FAFC) left white dark-mode text on a
                // light bubble, i.e. invisible. surfaceVariant matches
                // them in light mode and stays readable in dark mode;
                // the reply keeps its lighter look via 50% alpha.
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (isReply) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(c.author?.displayName ?: c.author?.username ?: "User", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            if (c.author?.isVerified == true) { Spacer(Modifier.width(4.dp)); Icon(Icons.Filled.CheckCircle, contentDescription = "Verified", modifier = Modifier.size(14.dp), tint = FrianzoBlue) }
                            // Web bubble "Follow Back" pill: shown only when the
                            // backend says this author follows me (follow_back)
                            // or I already follow them; tap toggles for real
                            // (POST /api/follows/{id}), never a dead button.
                            val authorId = c.author?.id
                            if (authorId != null && authorId != myId && followStatus != null && followStatus != "none" && followStatus != "self") {
                                Spacer(Modifier.width(6.dp))
                                val isFollowBack = followStatus == "follow_back"
                                Box(
                                    Modifier.clip(RoundedCornerShape(12.dp))
                                        .background(if (isFollowBack) FrianzoBlueTint else MaterialTheme.colorScheme.surfaceVariant)
                                        .clickable {
                                            scope.launch {
                                                try {
                                                    if (api2.toggleFollow(authorId).success) onFollowChange(authorId, if (isFollowBack) "following" else "none")
                                                } catch (_: Exception) {}
                                            }
                                        }
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(if (isFollowBack) "Follow Back" else "Following", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (isFollowBack) FrianzoBlue else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                                }
                            }
                        }
                        c.mediaUrl?.let { AsyncImage(model = it, contentDescription = "Comment image", modifier = Modifier.fillMaxWidth().heightIn(max = 192.dp).padding(top = 8.dp).clip(RoundedCornerShape(12.dp))) }
                        if (editing) {
                            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(value = editDraft, onValueChange = { editDraft = it }, modifier = Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(10.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = FrianzoBorder, unfocusedBorderColor = FrianzoBorder))
                                TextButton(onClick = {
                                    scope.launch {
                                        try { if (api2.editComment(c.id, mapOf("content" to editDraft)).success) { editing = false; onChanged() } } catch (_: Exception) {}
                                    }
                                }) { Text("Save", fontSize = 13.sp, color = FrianzoBlue) }
                                TextButton(onClick = { editing = false; editDraft = c.content ?: "" }) { Text("Cancel", fontSize = 13.sp) }
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
                // v5.20 (M1): picker floats above the Like button (same
                // Popup as reel comments) instead of pushing the list
                // down. It closes on pick, on an outside tap, or when
                // the ⋮ menu opens.
                if (pickerOpen) {
                    Popup(
                        alignment = Alignment.BottomStart,
                        offset = with(LocalDensity.current) { IntOffset(8.dp.roundToPx(), (-44).dp.roundToPx()) },
                        onDismissRequest = { pickerOpen = false },
                        properties = PopupProperties(focusable = false),
                    ) {
                        ReactionPickerPill(32.dp, listOf("like", "love", "haha", "wow", "sad", "angry")) { type -> pickerOpen = false; applyReaction(type) }
                    }
                }
                Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.pointerInput(c.id) {
                        detectTapGestures(
                            onLongPress = { pickerOpen = false; menuOpen = true },
                            onTap = { pickerOpen = !pickerOpen },
                        )
                    }) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                            if (localReaction != null) { ReactionIcon(localReaction, 16.dp); Spacer(Modifier.width(4.dp)) }
                            Text("Like", fontSize = 13.sp, fontWeight = if (localReaction != null) FontWeight.SemiBold else FontWeight.Normal, color = if (localReaction != null) FrianzoBlue else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    TextButton(onClick = { onReply(c) }, contentPadding = PaddingValues(0.dp)) { Text("Reply", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (localCount > 0) { Spacer(Modifier.width(12.dp)); Text(formatCount(localCount), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Spacer(Modifier.weight(1f))
                    Text(commentTimeAgo(c.createdAt), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Box {
                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.MoreVert, contentDescription = "More", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (isOwner) DropdownMenuItem(text = { Text("Edit") }, onClick = { menuOpen = false; editDraft = c.content ?: ""; editing = true })
                            if (canDelete) DropdownMenuItem(text = { Text("Delete", color = Color.Red) }, onClick = { menuOpen = false; showDelete = true })
                            DropdownMenuItem(text = { Text("Copy") }, onClick = { menuOpen = false; clipboard.setText(AnnotatedString(c.content ?: "")) })
                            DropdownMenuItem(text = { Text(if (reported) "Reported" else "Report") }, onClick = {
                                menuOpen = false
                                reportReason = "other"; reportDetails = ""; reportError = null; showReport = true
                            })
                        }
                    }
                }
                if (reported) Text("Comment reported. Thanks for letting us know.", fontSize = 12.sp, color = Color(0xFF16A34A), modifier = Modifier.padding(start = 8.dp))
                if (reportError != null) Text(reportError!!, fontSize = 12.sp, color = Color.Red, modifier = Modifier.padding(start = 8.dp))
                if (deleteError != null) Text(deleteError!!, fontSize = 12.sp, color = Color.Red, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
    if (showDelete) {
        // v5.15: red FILLED Delete button (was red text); the dialog stays
        // open on failure so the error is visible — it only closes when
        // the server actually deleted the comment.
        AlertDialog(
            onDismissRequest = { if (!deleteBusy) showDelete = false },
            shape = RoundedCornerShape(16.dp),
            title = { Text("Delete comment?") },
            text = { Column { Text("This can't be undone."); if (deleteError != null) { Spacer(Modifier.height(8.dp)); Text(deleteError!!, fontSize = 13.sp, color = Color.Red) } } },
            confirmButton = {
                Button(enabled = !deleteBusy, colors = ButtonDefaults.buttonColors(containerColor = FrianzoBadgeRed, contentColor = Color.White), onClick = {
                    if (deleteBusy) return@Button
                    deleteBusy = true; deleteError = null
                    scope.launch {
                        try {
                            val r = api2.deleteComment(c.id)
                            if (r.success) { showDelete = false; onChanged(); onDeleted() }
                            else deleteError = r.error?.message ?: "Could not delete comment"
                        } catch (e: Exception) { deleteError = e.message ?: "Could not delete comment" }
                        finally { deleteBusy = false }
                    }
                }) { Text(if (deleteBusy) "Deleting…" else "Delete") }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } },
        )
    }
    if (showReport) {
        // v5.21 (M5): same 7-reason picker + optional details as the
        // reel report dialog (Screens2.kt). The dialog stays open on
        // failure so the error is visible (A-D11: a failed report
        // used to close the menu with no confirmation and no error).
        AlertDialog(
            onDismissRequest = { if (!reportBusy) showReport = false },
            title = { Text("Report comment") },
            text = {
                Column {
                    listOf("spam" to "Spam", "harassment" to "Harassment", "hate_speech" to "Hate speech", "violence" to "Violence", "nudity" to "Nudity", "misinformation" to "False information", "other" to "Something else").forEach { (v, label) ->
                        Row(Modifier.fillMaxWidth().clickable { reportReason = v }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = reportReason == v, onClick = { reportReason = v })
                            Spacer(Modifier.width(8.dp))
                            Text(label, fontSize = 15.sp)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = reportDetails, onValueChange = { if (it.length <= 500) reportDetails = it }, label = { Text("Details (optional)") }, modifier = Modifier.fillMaxWidth(), maxLines = 4, shape = RoundedCornerShape(10.dp))
                    if (reportError != null) { Spacer(Modifier.height(8.dp)); Text(reportError!!, fontSize = 13.sp, color = Color.Red) }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (reportBusy) return@Button
                        reportBusy = true; reportError = null
                        scope.launch {
                            try {
                                val body = mapOf("reason" to reportReason) + if (reportDetails.isNotBlank()) mapOf("details" to reportDetails) else emptyMap()
                                val r = api2.reportComment(c.id, body)
                                if (r.success) { reported = true; reportError = null; showReport = false; Toast.makeText(context, "Reported", Toast.LENGTH_SHORT).show() }
                                else reportError = r.error?.message ?: "Could not report comment"
                            } catch (e: Exception) { reportError = e.message ?: "Could not report comment" }
                            finally { reportBusy = false }
                        }
                    },
                    enabled = !reportBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = FrianzoBadgeRed, contentColor = Color.White),
                    shape = RoundedCornerShape(28.dp),
                ) { Text(if (reportBusy) "Reporting…" else "Report", maxLines = 1, softWrap = false) }
            },
            dismissButton = { TextButton(onClick = { if (!reportBusy) showReport = false }) { Text("Cancel") } },
        )
    }
}

// v2.9: a repost renders the original post as a bordered inner card
// (web PostCard), with the original author, text and photo.
@Composable
private fun OriginalPostCard(original: Post, onAuthor: (String) -> Unit = {}, onDetail: () -> Unit = {}) {
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
                Text(feedTime(original.createdAt).ifBlank { "Just now" }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
        }
        original.content?.let { Spacer(Modifier.height(8.dp)); Text(it, fontSize = 14.sp) }
        original.imageUrl?.let { Spacer(Modifier.height(8.dp)); AsyncImage(model = it, contentDescription = null, modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop) }
    }
}

@Composable
private fun PostPhoto(url: String, onClick: () -> Unit = {}) {
    var reload by remember { mutableStateOf(0) }
    key(reload) {
        val context = LocalContext.current
        val screenWidthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
        // BLUR-UP (v5.23, Facebook-style): shimmer placeholder in a reserved
        // 4/3 box while loading (no layout jump), then the sharp image
        // crossfades in. The request is sized to the screen width so Coil does
        // not decode the full-resolution file.
        val painter = rememberAsyncImagePainter(model = ImageRequest.Builder(context).data(url).size(screenWidthPx, screenWidthPx).crossfade(true).build())
        when (val s = painter.state) {
            is AsyncImagePainter.State.Loading -> SkeletonBox(Modifier.fillMaxWidth().aspectRatio(4f / 3f).clickable { onClick() })
            is AsyncImagePainter.State.Error -> Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Image failed to load", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { reload++ }) { Text("Retry") }
            }
            else -> {
                val ratio = painter.intrinsicSize.let { size ->
                    if (size.width > 0f && size.height > 0f) size.width / size.height else 4f / 3f
                }
                Image(painter = painter, contentDescription = null, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth().aspectRatio(ratio).clickable { onClick() })
            }
        }
    }
}

// v5.15: web-style menu row — 18dp icon + 14sp label, 12dp gap, 14dp/16dp
// padding, min 48dp height, ripple click. No dividers, no title.
@Composable
private fun MenuRow(icon: ImageVector, label: String, destructive: Boolean = false, onClick: () -> Unit) {
    val textColor = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    val iconTint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onClick() }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = iconTint)
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 14.sp, color = textColor)
    }
}

// v5.20 (M7): takes api2 instead of the FeedViewModel and is public, so
// TagScreen's full post card reuses this exact menu instead of a copy.
// The three VM list-state touches become callbacks the caller owns:
// onHidden (was vm.hidePost), onAudienceChanged (was
// vm.updateCommentAudience), onEdited (was vm.refresh after Edit save).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostMenuSheet(api2: FrianzoApi2, post: Post, myId: String?, followStatus: String?, onClose: () -> Unit, onAuthor: (String) -> Unit, onShare: () -> Unit, onComments: () -> Unit, onDetail: () -> Unit, onHidden: (String) -> Unit = {}, onAudienceChanged: (String, String) -> Unit = { _, _ -> }, onEdited: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var showEdit by remember { mutableStateOf(false) }
    var editText by rememberSaveable { mutableStateOf(post.content ?: "") }
    var showDelete by remember { mutableStateOf(false) }
    var audienceExpanded by remember { mutableStateOf(false) }
    // A-B7: in-flight guards + visible errors for Edit/Delete.
    var editBusy by remember { mutableStateOf(false) }
    var editError by remember { mutableStateOf<String?>(null) }
    var deleteBusy by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    var audienceError by remember { mutableStateOf<String?>(null) }
    // v5.21 (M5): report reason picker (same 7 reasons + optional
    // details as the reel report) — reports used to file "other".
    var showReport by remember { mutableStateOf(false) }
    var reportReason by remember { mutableStateOf("other") }
    var reportDetails by remember { mutableStateOf("") }
    var reportBusy by remember { mutableStateOf(false) }
    var reportError by remember { mutableStateOf<String?>(null) }
    val isOwner = post.author?.id != null && post.author?.id == myId
    val clipboard = LocalClipboardManager.current
    // v5.15: feedback is a Toast, never the old inline blue notice text.
    fun toast(msg: String) { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
    // A-B6: run returns null on success or an error message on failure.
    // The Toast ("Reported", "Pinned", ...) is shown ONLY when the
    // server actually accepted the action — the old version showed
    // it even when the server rejected the call.
    fun act(label: String, run: suspend () -> String?) {
        scope.launch { try { val err = run(); if (err == null) toast(label) else toast(err) } catch (e: Exception) { toast(e.message ?: "Failed") } }
    }
    // v3.4: copy the SHORT /s/<code> link like the share sheet
    // (POST /api/share); direct /post/{id} only if that call fails.
    fun copyPostLink() {
        scope.launch {
            var url = "https://frianzo.online/post/${post.id}"
            try {
                val code = post.shareCode?.takeIf { it.isNotBlank() }
                    ?: api2.createShareLink(mapOf("type" to "post", "id" to post.id)).data?.code
                if (!code.isNullOrBlank()) url = "https://frianzo.online/s/$code"
            } catch (_: Exception) {}
            clipboard.setText(AnnotatedString(url)); toast("Link copied!")
        }
    }
    // v5.15: web's 5 owner rows / 4 non-owner rows ONLY — View profile,
    // View post, Share, Comments, Save, Hide post removed (owner order).
    // No "Post options" title, no dividers, no Cancel row, no X.
    ModalBottomSheet(
        onDismissRequest = onClose,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        dragHandle = { Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) { Box(Modifier.size(40.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(FrianzoBorder)) } },
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            if (isOwner) {
                MenuRow(Icons.Filled.Edit, "Edit post") { showEdit = true }
                MenuRow(Icons.Filled.Link, "Copy link") { copyPostLink() }
                MenuRow(Icons.Filled.PushPin, if (post.isPinned) "Unpin from profile" else "Pin to profile") {
                    act(if (post.isPinned) "Unpinned" else "Pinned") { val r = api2.togglePin(post.id); if (r.success) null else r.error?.message ?: "Could not change pin" }
                }
                MenuRow(Icons.Filled.ChatBubble, "Who can comment") { audienceExpanded = !audienceExpanded }
                if (audienceExpanded) {
                    // v5.15: inline sub-menu inside the sheet (web parity),
                    // NOT a separate dialog. Tapping an option saves
                    // immediately and closes the sheet; a server rejection
                    // shows the error inline and keeps the sheet open.
                    // The feed copy is updated locally on success so the
                    // detail/comment screens do not keep showing the old
                    // audience. Current option = semibold, 12sp.
                    val currentAudience = post.commentAudience ?: "everyone"
                    Column(Modifier.fillMaxWidth().padding(start = 46.dp, end = 16.dp, bottom = 4.dp)) {
                        listOf("everyone" to "Everyone", "followers" to "Followers", "only_me" to "Only me").forEach { (v, label) ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable {
                                scope.launch { try { val r = api2.updatePost(post.id, mapOf("commentAudience" to v)); if (r.success) { audienceError = null; onAudienceChanged(post.id, v); onClose() } else audienceError = r.error?.message ?: "Could not change who can comment" } catch (e: Exception) { audienceError = e.message ?: "Could not change who can comment" } }
                            }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(label, fontSize = 12.sp, fontWeight = if (currentAudience == v) FontWeight.SemiBold else FontWeight.Normal, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                        if (audienceError != null) Text(audienceError!!, fontSize = 12.sp, color = Color.Red)
                    }
                }
                MenuRow(Icons.Filled.Delete, "Delete post", destructive = true) { showDelete = true }
            } else {
                MenuRow(Icons.Filled.Link, "Copy link") { copyPostLink() }
                MenuRow(Icons.Filled.Flag, "Report post") { reportReason = "other"; reportDetails = ""; reportError = null; showReport = true }
                if (followStatus == "following" || followStatus == "friends") {
                    MenuRow(Icons.Filled.PersonRemove, "Unfollow") {
                        act("Unfollowed") { val aid = post.author?.id ?: return@act "Could not unfollow"; val r = api2.toggleFollow(aid); if (r.success) null else r.error?.message ?: "Could not unfollow" }
                    }
                }
                // v2.9: "Not interested" records creator feedback
                // (POST /api/users/me/not-interested { type: "creator",
                // value: authorId }) and hides the post locally.
                MenuRow(Icons.Filled.Block, "Not interested") {
                    act("Not interested") { val aid = post.author?.id ?: return@act "Could not mark not interested"; val r = api2.notInterested(mapOf("type" to "creator", "value" to aid)); if (r.success) { onHidden(post.id); onClose(); null } else r.error?.message ?: "Could not mark not interested" }
                }
            }
        }
    }
    if (showEdit) {
        // A-B7: Save is disabled while saving (no double submit); a
        // server rejection or network failure shows the error in the
        // dialog instead of silently leaving it open.
        AlertDialog(onDismissRequest = { if (!editBusy) showEdit = false }, shape = RoundedCornerShape(16.dp), title = { Text("Edit post") }, text = { Column { OutlinedTextField(value = editText, onValueChange = { editText = it }, modifier = Modifier.fillMaxWidth()); if (editError != null) { Spacer(Modifier.height(8.dp)); Text(editError!!, fontSize = 13.sp, color = Color.Red) } } },
            confirmButton = { Button(enabled = !editBusy, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface), onClick = { if (editBusy) return@Button; editBusy = true; editError = null; scope.launch { try { val r = api2.updatePost(post.id, mapOf("content" to editText)); if (r.success) { showEdit = false; onClose(); onEdited() } else editError = r.error?.message ?: "Could not save post" } catch (e: Exception) { editError = e.message ?: "Could not save post" } finally { editBusy = false } } }) { Text(if (editBusy) "Saving…" else "Save") } },
            dismissButton = { OutlinedButton(onClick = { showEdit = false }) { Text("Cancel") } })
    }
    if (showDelete) {
        // A-B7: same guard + error for Delete.
        AlertDialog(onDismissRequest = { if (!deleteBusy) showDelete = false }, shape = RoundedCornerShape(16.dp), title = { Text("Delete this post?") }, text = { Column { Text("This can't be undone."); if (deleteError != null) { Spacer(Modifier.height(8.dp)); Text(deleteError!!, fontSize = 13.sp, color = Color.Red) } } },
            confirmButton = { Button(enabled = !deleteBusy, colors = ButtonDefaults.buttonColors(containerColor = FrianzoBadgeRed, contentColor = Color.White), onClick = { if (deleteBusy) return@Button; deleteBusy = true; deleteError = null; scope.launch { try { val r = api2.deletePost(post.id); if (r.success) { onHidden(post.id); showDelete = false; onClose() } else deleteError = r.error?.message ?: "Could not delete post" } catch (e: Exception) { deleteError = e.message ?: "Could not delete post" } finally { deleteBusy = false } } }) { Text(if (deleteBusy) "Deleting…" else "Delete") } },
            dismissButton = { OutlinedButton(onClick = { showDelete = false }) { Text("Cancel") } })
    }
    if (showReport) {
        // v5.21 (M5): same 7-reason picker + optional details as the
        // reel report dialog (Screens2.kt). The dialog stays open on
        // failure so the error is visible; the sheet closes on success.
        AlertDialog(
            onDismissRequest = { if (!reportBusy) showReport = false },
            title = { Text("Report post") },
            text = {
                Column {
                    listOf("spam" to "Spam", "harassment" to "Harassment", "hate_speech" to "Hate speech", "violence" to "Violence", "nudity" to "Nudity", "misinformation" to "False information", "other" to "Something else").forEach { (v, label) ->
                        Row(Modifier.fillMaxWidth().clickable { reportReason = v }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = reportReason == v, onClick = { reportReason = v })
                            Spacer(Modifier.width(8.dp))
                            Text(label, fontSize = 15.sp)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = reportDetails, onValueChange = { if (it.length <= 500) reportDetails = it }, label = { Text("Details (optional)") }, modifier = Modifier.fillMaxWidth(), maxLines = 4, shape = RoundedCornerShape(10.dp))
                    if (reportError != null) { Spacer(Modifier.height(8.dp)); Text(reportError!!, fontSize = 13.sp, color = Color.Red) }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (reportBusy) return@Button
                        reportBusy = true; reportError = null
                        scope.launch {
                            try {
                                val body = mapOf("reason" to reportReason) + if (reportDetails.isNotBlank()) mapOf("details" to reportDetails) else emptyMap()
                                val r = api2.reportPost(post.id, body)
                                if (r.success) { showReport = false; toast("Reported"); onClose() }
                                else reportError = r.error?.message ?: "Could not report post"
                            } catch (e: Exception) { reportError = e.message ?: "Could not report post" }
                            finally { reportBusy = false }
                        }
                    },
                    enabled = !reportBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = FrianzoBadgeRed, contentColor = Color.White),
                    shape = RoundedCornerShape(28.dp),
                ) { Text(if (reportBusy) "Reporting…" else "Report", maxLines = 1, softWrap = false) }
            },
            dismissButton = { TextButton(onClick = { if (!reportBusy) showReport = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
// A-D3: onOpenPost opens another post's detail (the original post inside
// a repost). FeedScreen passes { detailPostId = it }; A-B9's reset in
// LaunchedEffect(postId) makes the new post load cleanly.
fun PostDetailScreen(vm: FeedViewModel, postId: String, myId: String?, myAvatar: String? = null, onClose: () -> Unit, onAuthor: (String) -> Unit, onOpenChat: (String, String) -> Unit, onOpenPost: (String) -> Unit = {}) {
    var detail by remember { mutableStateOf<Post?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var liked by remember { mutableStateOf(false) }
    var detailReaction by remember { mutableStateOf<String?>(null) }
    var likeCount by remember { mutableStateOf(0) }
    // Local mirrors of the fetched counts, updated by the comment section
    // and the share sheet below so this screen never shows a stale number
    // while the feed list already moved on.
    var detailCommentCount by remember { mutableStateOf<Int?>(null) }
    var detailShareCount by remember { mutableStateOf<Int?>(null) }
    var shareDetail by remember { mutableStateOf<Post?>(null) }
    var detailPicker by remember { mutableStateOf(false) }
    // A-B10: same in-flight guard as the feed's setReaction — rapid taps
    // must not fire concurrent reaction calls that land out of order.
    var detailReactionBusy by remember { mutableStateOf(false) }
    // A-D6: fullscreen photo viewer (null = closed).
    var photoViewer by remember { mutableStateOf<String?>(null) }
    // A-B9: reload tick for the Retry button.
    var reloadTick by remember { mutableStateOf(0) }
    val detailScroll = rememberScrollState()
    LaunchedEffect(postId, reloadTick) {
        // A-B9: reset ALL detail state first — opening post B after post
        // A used to flash A's text/like state until B loaded.
        detail = null; liked = false; detailReaction = null; likeCount = 0
        error = null; detailCommentCount = null; detailShareCount = null
        detailPicker = false; loading = true
        vm.recordPostView(postId)
        try {
            val r = vm.api2.postById(postId)
            if (r.success && r.data?.post != null) { detail = r.data.post; likeCount = r.data.post.likeCount; liked = r.data.post.myReaction != null; detailReaction = r.data.post.myReaction; detailCommentCount = r.data.post.commentCount; detailShareCount = r.data.post.shareCount }
            else error = r.error?.message ?: "Post not found"
        } catch (e: Exception) { error = e.message }
        loading = false
    }
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().verticalScroll(detailScroll).padding(bottom = 24.dp)) {
            if (loading) { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { DotsLoading() }; return@Column }
            // A-B9: a failed load gets a Retry button instead of a bare
            // error with no way back except closing and reopening.
            if (error != null || detail == null) { Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(error ?: "Post not found"); Spacer(Modifier.height(12.dp)); Button(onClick = { reloadTick++ }) { Text("Retry") } }; return@Column }
            val post = detail!!
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clickable { post.author?.username?.let { onAuthor(it) } }) { FrianzoAvatar(post.author?.profilePictureUrl, 40.dp) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(post.author?.displayName ?: post.author?.username ?: "User", modifier = Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        if (post.author?.isVerified == true) { Spacer(Modifier.width(4.dp)); Icon(Icons.Filled.CheckCircle, contentDescription = "Verified", modifier = Modifier.size(16.dp), tint = FrianzoBlue) }
                    }
                    Text((feedTime(post.createdAt).ifBlank { "Just now" }) + (post.location?.let { " · $it" } ?: ""), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }
            }
            post.content?.let { Text(it, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 16.dp)) }
            post.imageUrl?.let { Spacer(Modifier.height(10.dp)); PostPhoto(it, onClick = { photoViewer = it }) }
            // A-D3: the original-post card now opens that post's detail.
            post.originalPost?.let { orig -> Spacer(Modifier.height(10.dp)); OriginalPostCard(orig, onAuthor = onAuthor, onDetail = { onOpenPost(orig.id) }) }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
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
                val shownCommentCount = detailCommentCount ?: post.commentCount
                val shownShareCount = detailShareCount ?: post.shareCount
                if (shownCommentCount > 0) Text("$shownCommentCount comments", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (shownShareCount > 0) { Spacer(Modifier.width(8.dp)); Box(Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 8.dp, vertical = 2.dp)) { Text("$shownShareCount shares", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = FrianzoBorder)
            if (detailPicker) {
                // Web picker above the Like button, left-aligned, icons 40.
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.Start) {
                    ReactionPickerPill(40.dp, reactionTypes()) { type ->
                        // A-B10: ignore picks while a reaction is in flight.
                        if (detailReactionBusy) return@ReactionPickerPill
                        detailPicker = false
                        detailReactionBusy = true
                        val wasLiked = liked; val wasReaction = detailReaction; val wasCount = likeCount
                        val removing = detailReaction == type
                        val switching = detailReaction != null && !removing
                        liked = !removing; detailReaction = if (removing) null else type
                        likeCount = (likeCount + if (removing) -1 else if (switching) 0 else 1).coerceAtLeast(0)
                        scope.launch {
                            try {
                                val r = vm.api2.setReaction(post.id, mapOf("type" to type))
                                if (r.success && r.data != null) {
                                    val effR = r.data.myReaction ?: r.data.reaction
                                    val effC = r.data.likeCount ?: r.data.reactionCounts?.values?.sum()
                                    if (effC != null) likeCount = effC
                                    detailReaction = effR; liked = effR != null
                                } else { liked = wasLiked; detailReaction = wasReaction; likeCount = wasCount }
                            } catch (_: Exception) { liked = wasLiked; detailReaction = wasReaction; likeCount = wasCount }
                            finally { detailReactionBusy = false }
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
                            // A-B10: ignore taps while a reaction is in flight.
                            if (detailReactionBusy) return@detectTapGestures
                            detailReactionBusy = true
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
                                        val effR2 = r.data.myReaction ?: r.data.reaction
                                        val effC2 = r.data.likeCount ?: r.data.reactionCounts?.values?.sum()
                                        if (effC2 != null) likeCount = effC2
                                        detailReaction = effR2; liked = effR2 != null
                                    } else { liked = wasLiked; detailReaction = wasReaction; likeCount = wasCount }
                                } catch (_: Exception) { liked = wasLiked; detailReaction = wasReaction; likeCount = wasCount }
                                finally { detailReactionBusy = false }
                            }
                        }
                    })
                }, contentAlignment = Alignment.Center) {
                    when (detailReaction) {
                        null -> Icon(Icons.Outlined.ThumbUp, contentDescription = "Like", modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        "like" -> Icon(Icons.Filled.ThumbUp, contentDescription = "Like", modifier = Modifier.size(24.dp), tint = FrianzoBlue)
                        else -> ReactionIcon(detailReaction, 24.dp)
                    }
                }
                // A-B25: the old code read maxValue at tap time, when the
                // comments had not loaded yet and the scrollable height was
                // still small — the sheet barely moved. Now it waits (up
                // to ~3s) for the content to grow, then scrolls to the
                // composer. PHONE-TEST-OWED: verify on a real phone that
                // the tap lands on the comment box.
                Box(Modifier.weight(1f).height(48.dp).clickable { scope.launch { repeat(10) { delay(300); if (detailScroll.maxValue > 0) { detailScroll.animateScrollTo(detailScroll.maxValue); return@launch } } } }, contentAlignment = Alignment.Center) { Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = "Comments", modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                Box(Modifier.weight(1f).height(48.dp).clickable { shareDetail = post }, contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Share, contentDescription = "Share", modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            HorizontalDivider(color = FrianzoBorder)
            Spacer(Modifier.height(12.dp))
            CommentSection(vm.api2, post.id, detailCommentCount ?: post.commentCount, myId, post.author?.id, myAvatar = myAvatar) { detailCommentCount = it; vm.updateCommentCount(post.id, it) }
            shareDetail?.let { sp -> PostShareSheet(vm.api2, sp, onClose = { shareDetail = null }, onOpenChat = onOpenChat, onShared = { val nc = (detailShareCount ?: post.shareCount) + 1; detailShareCount = nc; vm.updateShareCount(post.id, nc) }) }
            // A-D6: fullscreen photo viewer for the detail photo tap.
            photoViewer?.let { url ->
                Dialog(onDismissRequest = { photoViewer = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                        AsyncImage(model = url, contentDescription = "Post photo", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                        IconButton(onClick = { photoViewer = null }, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StoryViewerDialog(api2: FrianzoApi2, group: StatusGroup, myId: String?, onClose: () -> Unit, onDeleted: () -> Unit = {}) {
    var idx by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    var paused by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var muted by remember { mutableStateOf(false) }
    var viewers by remember { mutableStateOf<List<StatusViewer>?>(null) }
    var showViewers by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var replyText by rememberSaveable { mutableStateOf("") }
    var replySent by remember { mutableStateOf(false) }
    var liked by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var isMuted by remember { mutableStateOf(false) }
    var storyPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    // v5.19 (H6 story stickers): the group is held in state so a poll
    // vote / question answer can re-fetch the feed and show fresh
    // results without closing the viewer. remember(group) re-seeds it
    // only if the parent hands in a different group.
    var liveGroup by remember(group) { mutableStateOf(group) }
    // v5.19 (H6): 1s clock for live countdown stickers.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); nowMs = System.currentTimeMillis() } }
    fun refreshStickers() {
        scope.launch {
            try {
                val sf = api2.statusFeed()
                val fresh = sf.data?.feed?.find { it.userId == liveGroup.userId }
                if (sf.success && fresh != null) liveGroup = fresh
            } catch (_: Exception) {}
        }
    }
    val isOwn = liveGroup.userId == myId || liveGroup.user?.id == myId
    val item = liveGroup.items.getOrNull(idx)
    val isVideo = item?.mediaType == "video" && !item.mediaUrl.isNullOrBlank()
    // v5.20 (M24): video stories play their FULL length (web parity —
    // the web drives progress from the <video> element itself). The
    // duration is probed from the file (MediaMetadataRetriever, same
    // pattern as Screens2.kt) and confirmed from the player's own
    // duration in onPrepared; 15000L is only the fallback while the
    // probe is still running or when it fails. Photos/text stay 5000L.
    // The ticker loop reads this state fresh on every tick, so a probe
    // that lands mid-play takes effect without restarting the story.
    var videoDurationMs by remember(item?.id) { mutableStateOf(15000L) }
    LaunchedEffect(item?.id) {
        if (!isVideo || item?.mediaUrl.isNullOrBlank()) return@LaunchedEffect
        val url = item.mediaUrl!!
        val probed = withContext(Dispatchers.IO) {
            try {
                val mmr = MediaMetadataRetriever()
                try {
                    mmr.setDataSource(url)
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                } finally { try { mmr.release() } catch (_: Exception) {} }
            } catch (_: Exception) { 0L }
        }
        if (probed > 0) videoDurationMs = probed
    }
    fun advance() { if (idx < liveGroup.items.size - 1) idx++ else onClose() }
    // A-D7: left-tap on the first story closes the viewer (web/FB go to
    // the previous story group) instead of doing nothing.
    fun goBack() { if (idx > 0) idx-- else onClose() }
    LaunchedEffect(item?.id) {
        if (item != null && !isOwn) { try { api2.viewStatus(item.id) } catch (_: Exception) {} }
        liked = item?.liked == true
    }
    // A-B18: load the real mute state once when the viewer opens, from
    // GET /api/statuses/muted — the menu used to always offer "Mute
    // stories" even for an already-muted author.
    LaunchedEffect(liveGroup.user?.id ?: liveGroup.userId) {
        if (isOwn) return@LaunchedEffect
        try {
            val r = api2.mutedStories()
            val uid = liveGroup.user?.id ?: liveGroup.userId
            if (r.success && r.data != null) isMuted = r.data.muted?.any { it.id == uid } == true
        } catch (_: Exception) {}
    }
    LaunchedEffect(idx) {
        // A-B19: "Reply sent" belongs to the story it was sent on — reset
        // it (and any notice) when the story changes.
        replySent = false; notice = null
        progress = 0f
        while (progress < 1f) {
            delay(100)
            // v5.20 (M24): read the (possibly just probed) duration fresh
            // each tick — a plain val captured at effect start would keep
            // the 15s fallback even after the probe lands.
            val durationMs = if (isVideo) videoDurationMs else 5000L
            if (!paused) progress = (progress + 100f / durationMs).coerceAtMost(1f)
        }
        advance()
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            // Media
            when {
                item == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No stories", color = Color.White) }
                !item.textContent.isNullOrBlank() && item.mediaUrl.isNullOrBlank() -> {
                    val bg = try { Color(android.graphics.Color.parseColor(item.bgColor ?: "#1E293B")) } catch (_: Exception) { Color(0xFF1E293B) }
                    Box(Modifier.fillMaxSize().background(bg), contentAlignment = Alignment.Center) { Text(item.textContent!!, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(24.dp), textAlign = TextAlign.Center) }
                }
                isVideo -> {
                    key(item.id) {
                        AndroidView(factory = { ctx -> VideoView(ctx).apply {
                            setVideoURI(Uri.parse(item.mediaUrl))
                            setOnPreparedListener { mp -> storyPlayer = mp; mp.isLooping = false; mp.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f); if (mp.duration > 0) videoDurationMs = mp.duration.toLong(); start() }
                            setOnCompletionListener { advance() }
                        } }, modifier = Modifier.fillMaxSize(), update = { view -> try { storyPlayer?.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f) } catch (_: Exception) {}; if (paused) view.pause() else view.start() }, onRelease = { storyPlayer = null; try { it.stopPlayback() } catch (_: Exception) {} })
                    }
                }
                !item.mediaUrl.isNullOrBlank() -> AsyncImage(model = item.mediaUrl, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(item.textContent ?: "", color = Color.White) }
            }
            // Tap zones: left 30% back, right 70% next; hold anywhere pauses.
            // This Box sits UNDER the header/bottom rows, so their buttons
            // still receive touches first.
            Box(Modifier.fillMaxSize().pointerInput(item?.id) {
                detectTapGestures(
                    onTap = { offset -> if (offset.x < size.width * 0.3f) goBack() else advance() },
                    onPress = { paused = true; tryAwaitRelease(); paused = false },
                )
            })
            // v5.19 (H6 story stickers): render web-created stickers at
            // their posX/posY (% of the viewer, centred like the web's
            // translate(-50%,-50%)). This layer sits ABOVE the tap zones
            // (sticker taps must not advance the story) and BELOW the
            // header/bottom rows. Sizes are measured so each card centres
            // exactly on its anchor; the guard stops a measure/set loop.
            val stickers = item?.stickers.orEmpty()
            if (stickers.isNotEmpty()) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val layerDensity = LocalDensity.current
                    var stickerSizes by remember(item?.id) { mutableStateOf<Map<String, IntSize>>(emptyMap()) }
                    stickers.forEach { sticker ->
                        val measured = stickerSizes[sticker.id]
                        val xDp = maxWidth * (sticker.posX / 100f) - with(layerDensity) { ((measured?.width ?: 0) / 2).toDp() }
                        val yDp = maxHeight * (sticker.posY / 100f) - with(layerDensity) { ((measured?.height ?: 0) / 2).toDp() }
                        Box(
                            Modifier.offset(x = xDp, y = yDp).onGloballyPositioned { coords ->
                                if (stickerSizes[sticker.id] != coords.size) stickerSizes = stickerSizes + (sticker.id to coords.size)
                            }
                        ) {
                            StoryStickerView(
                                api2 = api2,
                                sticker = sticker,
                                isOwner = isOwn,
                                nowMs = nowMs,
                                onVoted = { refreshStickers() },
                                onAnswered = { refreshStickers() },
                                onNotice = { notice = it },
                                onPauseChange = { paused = it },
                            )
                        }
                    }
                }
            }
            // Top: progress segments + header
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    liveGroup.items.forEachIndexed { i, _ ->
                        val fill = when { i < idx -> 1f; i == idx -> progress; else -> 0f }
                        Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.35f))) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(fill).background(Color.White))
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FrianzoAvatar(liveGroup.user?.profilePictureUrl, 36.dp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(liveGroup.user?.displayName ?: liveGroup.user?.username ?: "Story", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        Text(commentTimeAgo(item?.createdAt), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (isVideo) IconButton(onClick = { muted = !muted }) { Icon(if (muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp, contentDescription = if (muted) "Unmute" else "Mute", tint = Color.White) }
                    if (!isOwn) {
                        Box {
                            IconButton(onClick = { menuOpen = true; paused = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = Color.White) }
                            // v5.15: playback pauses while the menu is open
                            // (the progress ticker and the VideoView both
                            // honour `paused`); Cancel row added (web parity).
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false; paused = false }) {
                                DropdownMenuItem(text = { Text(if (isMuted) "Unmute stories" else "Mute stories") }, onClick = { menuOpen = false; paused = false; scope.launch { try { val uid = liveGroup.user?.id ?: liveGroup.userId; val r = if (isMuted) api2.unmuteStories(uid) else api2.muteStories(uid); if (r.success) { isMuted = !isMuted; notice = if (isMuted) "Stories muted" else "Stories unmuted" } else notice = r.error?.message ?: "Could not change mute setting" } catch (e: Exception) { notice = e.message } } })
                                DropdownMenuItem(text = { Text("Cancel") }, onClick = { menuOpen = false; paused = false })
                            }
                        }
                    }
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White) }
                }
            }
            // Bottom: own = Viewers + Delete; others = reply + Like
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)) {
                notice?.let { Text(it, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp)) }
                if (isOwn) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // A-B20: hidden when item == null — the old code
                        // fired a request with an empty story id.
                        if (item != null) OutlinedButton(onClick = { scope.launch { try { val r = api2.statusViewers(item!!.id); if (r.success && r.data != null) { viewers = r.data.viewers; showViewers = true } else notice = r.error?.message } catch (e: Exception) { notice = e.message } } }, colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White), border = BorderStroke(1.dp, Color.White), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)) { Text("Viewers") }
                        OutlinedButton(onClick = { showDelete = true }, colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White), border = BorderStroke(1.dp, Color.White), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)) { Text("Delete story") }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = replyText, onValueChange = { if (it.length <= 500) replyText = it }, placeholder = { Text("Reply...", color = Color.White.copy(alpha = 0.6f)) }, modifier = Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(28.dp), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedContainerColor = Color.Black.copy(alpha = 0.4f), unfocusedContainerColor = Color.Black.copy(alpha = 0.4f), focusedBorderColor = Color.White.copy(alpha = 0.5f), unfocusedBorderColor = Color.White.copy(alpha = 0.5f), cursorColor = Color.White))
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = {
                            val text = replyText.trim(); if (text.isBlank()) return@IconButton
                            // A-B19: clear the reply text ONLY on success —
                            // a failed reply used to lose the typed message.
                            // Web story reply goes to the author's DM; the
                            // backend has no story-reply endpoint, so the
                            // reply is sent as a real chat message.
                            scope.launch { try { val uid = liveGroup.user?.id ?: liveGroup.userId; val conv = api2.createConversation(CreateConversationRequest(uid)); val cid = conv.data?.id; if (cid != null && api2.sendMessage(cid, SendMessageRequest(text)).success) { replyText = ""; replySent = true; notice = "Reply sent" } else notice = "Could not send reply" } catch (e: Exception) { notice = e.message } }
                        }) { Icon(Icons.Filled.Send, contentDescription = "Send reply", tint = Color.White) }
                        // A-B17: revert the heart when the server rejects
                        // the like, not only on exception.
                        IconButton(onClick = { val was = liked; liked = !was; scope.launch { try { if (item != null) { val r = api2.toggleStatusLike(item.id); if (!r.success) liked = was } } catch (_: Exception) { liked = was } } }) { Icon(if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, contentDescription = "Like", tint = if (liked) Color(0xFFEF4444) else Color.White, modifier = Modifier.size(28.dp)) }
                    }
                    if (replySent) Text("Reply sent as a message.", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        if (showViewers) {
            AlertDialog(onDismissRequest = { showViewers = false }, title = { Text("Viewers") },
                text = { Column { (viewers ?: emptyList()).forEach { v -> Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) { FrianzoAvatar(v.profilePictureUrl, 32.dp); Spacer(Modifier.width(8.dp)); Text(v.displayName ?: v.username ?: "User", fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) } }; if (viewers.isNullOrEmpty()) Text("No views yet", fontSize = 14.sp) } },
                confirmButton = { TextButton(onClick = { showViewers = false }) { Text("Close") } })
        }
        if (showDelete) {
            AlertDialog(onDismissRequest = { showDelete = false }, shape = RoundedCornerShape(16.dp), title = { Text("Delete story?") }, text = { Text("This can't be undone.") },
                confirmButton = { Button(colors = ButtonDefaults.buttonColors(containerColor = FrianzoBadgeRed, contentColor = Color.White), onClick = { showDelete = false; scope.launch { try { if (item != null && api2.deleteStatus(item.id).success) onDeleted() else notice = "Could not delete story" } catch (e: Exception) { notice = e.message } } }) { Text("Delete") } },
                dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } })
        }
    }
}

// ---------- STORY STICKERS (v5.19, H6) ----------
// One web-created sticker, matching StoryStickers.tsx:25-190.
// Poll: vote once (owner sees bars, a voter sees bars after voting,
// anyone else sees plain options). Question: answer sheet, or the
// owner's answers sheet. Countdown: live ticking text. Link: opens
// the browser. Unknown types render nothing, like the web.
@Composable
private fun StoryStickerView(api2: FrianzoApi2, sticker: StatusSticker, isOwner: Boolean, nowMs: Long, onVoted: () -> Unit, onAnswered: () -> Unit, onNotice: (String) -> Unit, onPauseChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    if (sticker.type == "poll") {
        val data = sticker.data
        val options = data?.options.orEmpty()
        val results = sticker.results
        val voted = sticker.myVote != null
        var showResults by remember(sticker.id) { mutableStateOf(false) }
        var voting by remember(sticker.id) { mutableStateOf(false) }
        val showBars = isOwner || voted || showResults
        // Taps on the card itself (not on an option) must not advance
        // the story — the web stops propagation on every sticker.
        Column(
            Modifier.width(208.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.95f)).padding(12.dp)
                .pointerInput(Unit) { detectTapGestures { } }
        ) {
            Text(data?.question ?: "Poll", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            options.forEachIndexed { i, opt ->
                val pct = if (results != null && results.total > 0) (results.counts.getOrNull(i) ?: 0) * 100 / results.total else 0
                val mine = sticker.myVote == i
                Box(
                    Modifier.fillMaxWidth().height(32.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (mine) FrianzoBlue else Color(0xFFF1F5F9))
                        .clickable(enabled = !showBars && !voting && !isOwner) {
                            voting = true
                            scope.launch {
                                try {
                                    val r = api2.votePoll(sticker.id, mapOf("optionIndex" to i))
                                    // The vote response carries no results
                                    // (stickerService.votePoll returns only
                                    // { voted, optionIndex }), so the viewer
                                    // re-fetches the feed for fresh counts.
                                    if (r.success) onVoted() else onNotice(r.error?.message ?: "Could not vote")
                                } catch (e: Exception) { onNotice(e.message ?: "Could not vote") }
                                finally { voting = false }
                            }
                        }
                ) {
                    if (showBars && results != null) Box(Modifier.fillMaxHeight().fillMaxWidth(pct / 100f).background(if (mine) Color(0xFF1D4ED8) else Color(0xFFCBD5E1)))
                    Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(opt, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (mine) Color.White else Color(0xFF1E293B), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (showBars && results != null) Text("$pct%", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (mine) Color.White else Color(0xFF1E293B))
                    }
                }
                if (i < options.size - 1) Spacer(Modifier.height(6.dp))
            }
            if (results != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Text("${results.total} ${if (results.total == 1) "vote" else "votes"}", fontSize = 10.sp, color = Color(0xFF64748B))
                    // Same condition as the web (StoryStickers.tsx:75-79);
                    // on the web it can never fire either, since results
                    // is null for a non-owner who has not voted yet.
                    if (!isOwner && !voted) {
                        Spacer(Modifier.width(4.dp))
                        Text(if (showResults) "hide" else "results", fontSize = 10.sp, color = FrianzoBlue, modifier = Modifier.clickable { showResults = !showResults })
                    }
                }
            }
        }
    }
    if (sticker.type == "question") {
        var answerOpen by remember(sticker.id) { mutableStateOf(false) }
        var answersOpen by remember(sticker.id) { mutableStateOf(false) }
        var answerText by remember(sticker.id) { mutableStateOf("") }
        var sending by remember(sticker.id) { mutableStateOf(false) }
        val answered = sticker.myAnswer != null
        Column(
            Modifier.width(208.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.95f)).padding(12.dp)
                .clickable { if (isOwner) { answersOpen = true; onPauseChange(true) } else { answerOpen = true; onPauseChange(true) } },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Ask me", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF9333EA))
            Text(sticker.data?.prompt ?: "", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A), textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            if (isOwner) Text("${sticker.answerCount} answers — tap to view", fontSize = 10.sp, color = Color(0xFF64748B))
            else if (answered) Text("✓ Answered", fontSize = 10.sp, fontWeight = FontWeight.Medium, color = Color(0xFF16A34A))
            else Text("Tap to answer", fontSize = 10.sp, color = Color(0xFF64748B))
        }
        if (answerOpen) {
            Dialog(onDismissRequest = { answerOpen = false; onPauseChange(false) }) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).padding(16.dp)) {
                    Text(sticker.data?.prompt ?: "", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color(0xFF0F172A))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = answerText, onValueChange = { if (it.length <= 300) answerText = it }, placeholder = { Text("Type your answer...") }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 4)
                    Spacer(Modifier.height(12.dp))
                    Button(enabled = answerText.isNotBlank() && !sending, onClick = {
                        sending = true
                        scope.launch {
                            try {
                                val r = api2.answerQuestion(sticker.id, mapOf("text" to answerText.trim()))
                                if (r.success) { answerOpen = false; onPauseChange(false); answerText = ""; onAnswered() } else onNotice(r.error?.message ?: "Could not send answer")
                            } catch (e: Exception) { onNotice(e.message ?: "Could not send answer") }
                            finally { sending = false }
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text(if (sending) "Sending..." else "Send") }
                }
            }
        }
        if (answersOpen) {
            Dialog(onDismissRequest = { answersOpen = false; onPauseChange(false) }) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).padding(16.dp)) {
                    Text(sticker.data?.prompt ?: "", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color(0xFF0F172A), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Column(Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                        val answers = sticker.answers.orEmpty()
                        if (answers.isEmpty()) Text("No answers yet.", fontSize = 14.sp, color = Color(0xFF94A3B8), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                        answers.forEach { a ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                                FrianzoAvatar(a.user?.profilePictureUrl, 28.dp)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(a.user?.displayName ?: a.user?.username ?: "User", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF475569))
                                    Text(a.text ?: "", fontSize = 14.sp, color = Color(0xFF0F172A))
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { answersOpen = false; onPauseChange(false) }, modifier = Modifier.fillMaxWidth()) { Text("Close") }
                }
            }
        }
    }
    if (sticker.type == "countdown") {
        val targetMillis = parseStickerIsoMillis(sticker.data?.targetAt)
        Column(
            Modifier.clip(RoundedCornerShape(16.dp)).background(Brush.linearGradient(listOf(Color(0xFF9333EA), Color(0xFFDB2777)))).padding(horizontal = 16.dp, vertical = 10.dp)
                .pointerInput(Unit) { detectTapGestures { } },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(sticker.data?.title ?: "", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
            if (targetMillis != null) Text(formatCountdown(targetMillis, nowMs), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
    if (sticker.type == "link") {
        Button(
            onClick = {
                val url = sticker.data?.url ?: return@Button
                if (!openWebUrl(context, url)) onNotice("Could not open link")
            },
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.95f), contentColor = Color(0xFF1D4ED8)),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(sticker.data?.label ?: sticker.data?.url ?: "Link", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------- STORY CREATION: photo/video or text + background (POST /api/statuses) ----------
@Composable
private fun StoryCreateScreen(api2: FrianzoApi2, onDone: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var mediaUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var mediaIsVideo by remember { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    var bgColor by rememberSaveable { mutableStateOf("#1E293B") }
    var visibility by rememberSaveable { mutableStateOf("everyone") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    // v5.19 (H6 story stickers): drafts for this story (backend max 5,
    // enforced on the button) + which sticker sheet is open.
    var stickers by remember { mutableStateOf<List<StickerDraft>>(emptyList()) }
    var stickerSheet by remember { mutableStateOf<String?>(null) }
    // Sticker form state (plain remember: pollOpts is a List, which
    // rememberSaveable cannot save).
    var pollQ by remember { mutableStateOf("") }
    var pollOpts by remember { mutableStateOf(listOf("", "")) }
    var qPrompt by remember { mutableStateOf("") }
    var cdTitle by remember { mutableStateOf("") }
    var cdDateMillis by remember { mutableStateOf<Long?>(null) }
    var cdHour by remember { mutableStateOf(12) }
    var cdMinute by remember { mutableStateOf(0) }
    var linkUrl by remember { mutableStateOf("") }
    var linkLabel by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) {
            mediaUri = uri
            // A-B27: some providers return a null MIME type — a video
            // from one used to be misclassified as a photo and uploaded
            // as story_upload.jpg. Fall back to the file extension.
            val mime = try { context.contentResolver.getType(uri) } catch (_: Exception) { null }
            val ext = uri.lastPathSegment?.substringAfterLast(".", "")?.lowercase() ?: ""
            mediaIsVideo = mime?.startsWith("video") == true || ext in listOf("mp4", "mov", "webm", "mkv", "3gp", "avi")
        }
    }
    val pickStoryMedia = rememberPermissionGate(galleryPermissions(), onGranted = { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageAndVideo)) })
    // v5.19 (H6): the sticker section makes the screen taller than a
    // small phone, so the whole create screen scrolls.
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("Cancel") }
            Text("New story", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { pickStoryMedia() }, shape = RoundedCornerShape(28.dp)) { Text(if (mediaUri == null) "Add photo or video" else if (mediaIsVideo) "Video selected" else "Photo selected", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
        if (mediaUri != null && !mediaIsVideo) {
            Spacer(Modifier.height(8.dp))
            AsyncImage(model = mediaUri, contentDescription = null, modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = text, onValueChange = { if (it.length <= 500) text = it }, label = { Text("Text story (optional)") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text("Background colour", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("#1E293B", "#0EA5E9", "#8B5CF6", "#F43F5E", "#10B981", "#F97316")) { hex ->
                val color = try { Color(android.graphics.Color.parseColor(hex)) } catch (_: Exception) { Color(0xFF1E293B) }
                Box(Modifier.size(40.dp).clip(CircleShape).background(color).clickable { bgColor = hex }.border(if (bgColor == hex) 3.dp else 0.dp, if (bgColor == hex) FrianzoBlue else Color.Transparent, CircleShape))
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Who can see it", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("everyone" to "Everyone", "close_friends" to "Close friends").forEach { (v, label) ->
                val sel = visibility == v
                Box(Modifier.clip(RoundedCornerShape(28.dp)).background(if (sel) FrianzoBlue else MaterialTheme.colorScheme.surfaceVariant).clickable { visibility = v }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Text(label, color = if (sel) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        // v5.19 (H6 story stickers): the sticker step — same four types
        // as the web StickerSheets (poll / question / countdown / link).
        // The backend caps a story at 5 stickers (stickerService).
        Text("Stickers (${stickers.size}/5)", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        OutlinedButton(enabled = stickers.size < 5, onClick = { stickerSheet = "types" }, shape = RoundedCornerShape(28.dp)) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Add sticker", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
        if (stickers.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Drag a sticker to move it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            StickerPreviewBox(
                mediaUri = mediaUri,
                mediaIsVideo = mediaIsVideo,
                text = text,
                bgColor = bgColor,
                stickers = stickers,
                onMove = { i, x, y -> stickers = stickers.mapIndexed { idx, s -> if (idx == i) s.copy(posX = x, posY = y) else s } },
            )
            Spacer(Modifier.height(4.dp))
            stickers.forEachIndexed { i, s ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stickerDraftSummary(s), fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { stickers = stickers.filterIndexed { idx, _ -> idx != i } }) { Icon(Icons.Filled.Close, contentDescription = "Remove sticker", modifier = Modifier.size(18.dp)) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(enabled = !busy && (mediaUri != null || text.isNotBlank()), onClick = {
            busy = true
            scope.launch {
                try {
                    var part: okhttp3.MultipartBody.Part? = null
                    var tmpFile: java.io.File? = null
                    val uri = mediaUri
                    if (uri != null) {
                        val input = context.contentResolver.openInputStream(uri) ?: throw Exception("Could not read the media")
                        val mime = try { context.contentResolver.getType(uri) } catch (_: Exception) { null } ?: if (mediaIsVideo) "video/mp4" else "image/jpeg"
                        val ext = when (mime.substringAfter("/")) { "png" -> "png"; "webp" -> "webp"; "mp4" -> "mp4"; "mov" -> "mov"; else -> if (mediaIsVideo) "mp4" else "jpg" }
                        val tmp = java.io.File(context.cacheDir, "story_upload.$ext")
                        tmpFile = tmp
                        input.use { inp -> tmp.outputStream().use { out -> inp.copyTo(out) } }
                        part = okhttp3.MultipartBody.Part.createFormData("media", tmp.name, okhttp3.RequestBody.create(mime.toMediaTypeOrNull(), tmp))
                    }
                    val res = api2.createStatus(
                        part,
                        text.ifBlank { null }?.let { okhttp3.RequestBody.create("text/plain".toMediaTypeOrNull(), it) },
                        okhttp3.RequestBody.create("text/plain".toMediaTypeOrNull(), bgColor),
                        okhttp3.RequestBody.create("text/plain".toMediaTypeOrNull(), visibility),
                        // v5.19 (H6): stickers JSON part; null when none.
                        stickers = if (stickers.isEmpty()) null else okhttp3.RequestBody.create("text/plain".toMediaTypeOrNull(), stickerDraftsJson(stickers)),
                    )
                    try { tmpFile?.delete() } catch (_: Exception) {}
                    if (!res.success) throw Exception(res.error?.message ?: "Story failed")
                    onDone()
                } catch (e: Exception) { message = e.message ?: "Story failed" }
                finally { busy = false }
            }
        }, modifier = Modifier.fillMaxWidth().height(FrianzoUi.ButtonHeight), shape = RoundedCornerShape(FrianzoUi.ButtonRadius)) { Text(if (busy) "Posting..." else "Share story", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
        message?.let { Spacer(Modifier.height(12.dp)); Text(it, color = FrianzoBadgeRed) }
        if (busy) { Spacer(Modifier.height(12.dp)); DotsLoading() }
        // v5.19 (H6): sticker sheets — same fields + limits as the web
        // StickerSheets (poll Q<=100 / options 2-4 x <=40, question
        // prompt<=100, countdown title<=60 + future date/time, link
        // URL required / label<=40 optional).
        if (stickerSheet == "types") {
            AlertDialog(onDismissRequest = { stickerSheet = null }, title = { Text("Choose a sticker") },
                text = {
                    Column {
                        listOf("poll" to "Poll", "question" to "Question", "countdown" to "Countdown", "link" to "Link").forEach { (t, label) ->
                            Row(Modifier.fillMaxWidth().clickable { stickerSheet = t }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(when (t) { "poll" -> Icons.Filled.Poll; "question" -> Icons.Filled.QuestionAnswer; "countdown" -> Icons.Filled.Timer; else -> Icons.Filled.Link }, contentDescription = null, tint = FrianzoBlue)
                                Spacer(Modifier.width(12.dp))
                                Text(label, fontSize = 15.sp)
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { stickerSheet = null }) { Text("Cancel") } })
        }
        if (stickerSheet == "poll") {
            AlertDialog(onDismissRequest = { stickerSheet = null }, title = { Text("Poll") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        OutlinedTextField(value = pollQ, onValueChange = { if (it.length <= 100) pollQ = it }, label = { Text("Ask a question...") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Spacer(Modifier.height(8.dp))
                        pollOpts.forEachIndexed { i, opt ->
                            OutlinedTextField(value = opt, onValueChange = { v -> if (v.length <= 40) pollOpts = pollOpts.mapIndexed { j, o -> if (j == i) v else o } }, label = { Text("Option ${i + 1}") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            Spacer(Modifier.height(8.dp))
                        }
                        if (pollOpts.size < 4) TextButton(onClick = { pollOpts = pollOpts + "" }) { Text("+ Add option") }
                    }
                },
                confirmButton = {
                    val valid = pollQ.isNotBlank() && pollOpts.count { it.isNotBlank() } >= 2
                    Button(enabled = valid, onClick = {
                        stickers = stickers + StickerDraft(type = "poll", posX = 50f, posY = nextStickerY(stickers.size), question = pollQ.trim(), options = pollOpts.map { it.trim() }.filter { it.isNotEmpty() })
                        pollQ = ""; pollOpts = listOf("", "")
                        stickerSheet = null
                    }) { Text("Add") }
                },
                dismissButton = { TextButton(onClick = { stickerSheet = "types" }) { Text("Back") } })
        }
        if (stickerSheet == "question") {
            AlertDialog(onDismissRequest = { stickerSheet = null }, title = { Text("Question") },
                text = {
                    OutlinedTextField(value = qPrompt, onValueChange = { if (it.length <= 100) qPrompt = it }, label = { Text("Ask me anything...") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                },
                confirmButton = {
                    Button(enabled = qPrompt.isNotBlank(), onClick = {
                        stickers = stickers + StickerDraft(type = "question", posX = 50f, posY = nextStickerY(stickers.size), prompt = qPrompt.trim())
                        qPrompt = ""
                        stickerSheet = null
                    }) { Text("Add") }
                },
                dismissButton = { TextButton(onClick = { stickerSheet = "types" }) { Text("Back") } })
        }
        if (stickerSheet == "countdown") {
            AlertDialog(onDismissRequest = { stickerSheet = null }, title = { Text("Countdown") },
                text = {
                    Column {
                        OutlinedTextField(value = cdTitle, onValueChange = { if (it.length <= 60) cdTitle = it }, label = { Text("Countdown to...") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                val cal = Calendar.getInstance()
                                if (cdDateMillis != null) cal.timeInMillis = cdDateMillis!!
                                val dlg = DatePickerDialog(context, { _, y, m, d ->
                                    val c = Calendar.getInstance()
                                    c.set(y, m, d, cdHour, cdMinute, 0)
                                    c.set(Calendar.MILLISECOND, 0)
                                    cdDateMillis = c.timeInMillis
                                }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
                                dlg.datePicker.minDate = System.currentTimeMillis()
                                dlg.show()
                            }, modifier = Modifier.weight(1f)) { Text(if (cdDateMillis == null) "Pick a date" else SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(cdDateMillis!!)), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                            OutlinedButton(onClick = {
                                TimePickerDialog(context, { _, h, m ->
                                    cdHour = h; cdMinute = m
                                    cdDateMillis = cdDateMillis?.let { combineDateTimeMillis(it, h, m) }
                                }, cdHour, cdMinute, true).show()
                            }, modifier = Modifier.weight(1f)) { Text(String.format(Locale.US, "%02d:%02d", cdHour, cdMinute), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                        }
                        if (cdDateMillis != null && cdDateMillis!! <= System.currentTimeMillis()) {
                            Spacer(Modifier.height(4.dp))
                            Text("Pick a future date and time.", color = FrianzoBadgeRed, fontSize = 13.sp)
                        }
                    }
                },
                confirmButton = {
                    val valid = cdTitle.isNotBlank() && cdDateMillis != null && cdDateMillis!! > System.currentTimeMillis()
                    Button(enabled = valid, onClick = {
                        stickers = stickers + StickerDraft(type = "countdown", posX = 50f, posY = nextStickerY(stickers.size), title = cdTitle.trim(), targetAt = stickerIsoString(cdDateMillis!!))
                        cdTitle = ""; cdDateMillis = null; cdHour = 12; cdMinute = 0
                        stickerSheet = null
                    }) { Text("Add") }
                },
                dismissButton = { TextButton(onClick = { stickerSheet = "types" }) { Text("Back") } })
        }
        if (stickerSheet == "link") {
            AlertDialog(onDismissRequest = { stickerSheet = null }, title = { Text("Link") },
                text = {
                    Column {
                        OutlinedTextField(value = linkUrl, onValueChange = { linkUrl = it }, label = { Text("https://...") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = linkLabel, onValueChange = { if (it.length <= 40) linkLabel = it }, label = { Text("Label (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    }
                },
                confirmButton = {
                    Button(enabled = linkUrl.isNotBlank(), onClick = {
                        // The backend adds https:// when the URL has no
                        // scheme and defaults a blank label to the
                        // hostname (stickerService.validateStickerData).
                        stickers = stickers + StickerDraft(type = "link", posX = 50f, posY = nextStickerY(stickers.size), url = linkUrl.trim(), label = linkLabel.trim())
                        linkUrl = ""; linkLabel = ""
                        stickerSheet = null
                    }) { Text("Add") }
                },
                dismissButton = { TextButton(onClick = { stickerSheet = "types" }) { Text("Back") } })
        }
    }
}


private fun stickerDraftSummary(draft: StickerDraft): String {
    return when (draft.type) {
        "poll" -> "Poll: ${draft.question ?: ""}"
        "question" -> "Q: ${draft.prompt ?: ""}"
        "countdown" -> "Countdown: ${draft.title ?: ""}"
        "link" -> "Link: ${draft.label?.takeIf { it.isNotBlank() } ?: draft.url ?: ""}"
        else -> draft.type
    }
}

@Composable
private fun StickerDraftChip(draft: StickerDraft) {
    Box(Modifier.widthIn(max = 180.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.95f)).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(stickerDraftSummary(draft), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// v5.19 (H6): preview of where the stickers will sit on the story
// (the photo, or the text story's background colour), with drag to
// move. Positions are 0-100 percentages of the layer; the gesture's
// pixel deltas are converted with the layer size in px.
// rememberUpdatedState guards the stale-closure trap — the pointerInput
// block is keyed on the sticker index only, so without it the gesture
// would keep the first composition's draft/onMove forever.
@Composable
private fun StickerPreviewBox(mediaUri: android.net.Uri?, mediaIsVideo: Boolean, text: String, bgColor: String, stickers: List<StickerDraft>, onMove: (Int, Float, Float) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(12.dp))) {
        val density = LocalDensity.current
        val layerWpx = with(density) { maxWidth.toPx() }
        val layerHpx = with(density) { maxHeight.toPx() }
        val latestOnMove by rememberUpdatedState(onMove)
        var sizes by remember { mutableStateOf<Map<Int, IntSize>>(emptyMap()) }
        if (mediaUri != null && !mediaIsVideo) {
            AsyncImage(model = mediaUri, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            val bg = try { Color(android.graphics.Color.parseColor(bgColor)) } catch (_: Exception) { Color(0xFF1E293B) }
            Box(Modifier.fillMaxSize().background(bg), contentAlignment = Alignment.Center) {
                if (text.isNotBlank()) Text(text, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(16.dp), textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
                else if (mediaUri != null) Text("Video selected", color = Color.White, fontSize = 14.sp)
            }
        }
        stickers.forEachIndexed { i, draft ->
            val latestDraft by rememberUpdatedState(draft)
            val sz = sizes[i]
            val xDp = maxWidth * (draft.posX / 100f) - with(density) { ((sz?.width ?: 0) / 2).toDp() }
            val yDp = maxHeight * (draft.posY / 100f) - with(density) { ((sz?.height ?: 0) / 2).toDp() }
            Box(
                Modifier.offset(x = xDp, y = yDp)
                    .onGloballyPositioned { coords -> if (sizes[i] != coords.size) sizes = sizes + (i to coords.size) }
                    .pointerInput(i) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            if (layerWpx <= 0f || layerHpx <= 0f) return@detectDragGestures
                            val base = latestDraft
                            val nx = (((base.posX / 100f) * layerWpx + dragAmount.x) / layerWpx * 100f).coerceIn(0f, 100f)
                            val ny = (((base.posY / 100f) * layerHpx + dragAmount.y) / layerHpx * 100f).coerceIn(0f, 100f)
                            latestOnMove(i, nx, ny)
                        }
                    }
            ) {
                StickerDraftChip(draft)
            }
        }
    }
}


fun dummyContextUsage(@Suppress("UNUSED_PARAMETER") context: Context) = Unit


