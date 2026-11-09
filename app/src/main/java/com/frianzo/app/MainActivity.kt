package com.frianzo.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.google.firebase.messaging.FirebaseMessaging
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.frianzo.app.data.*
import com.frianzo.app.ui.*
import kotlinx.coroutines.launch

/**
 * Full native app (Jetpack Compose, NO WebView), built step by step:
 * Stage 1: login + feed. Stage 2: posts, reels, chat, profile,
 * notifications, search — all on the same AWS backend (api.frianzo.online).
 * NOT published to Play. Current live app is untouched.
 * Chat uses REST polling (5s while a thread is open); live socket delivery
 * is a later stage. Push notifications need a Firebase key (owner step).
 */
class MainActivity : ComponentActivity() {
    // C-B1: deep link waiting to be routed once the user is logged in
    // (AppShell only exists then). Set in onCreate (cold start) and in
    // onNewIntent (app already running, singleTop).
    private var pendingDeepLink by mutableStateOf<DeepLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingDeepLink = parsePushIntent(intent)
        val cookieJar = PersistentCookieJar(applicationContext)
        val api = createApi(applicationContext, cookieJar)
        val api2 = createApi2(applicationContext, cookieJar)
        // v5.12: global connectivity listener (registered once) + flush
        // the offline outbox whenever the connection returns.
        NetworkMonitor.start(applicationContext)
        NetworkMonitor.onBackOnline = {
            lifecycleScope.launch {
                try { OutboxFlusher.flush(applicationContext, api2) } catch (_: Exception) {}
            }
        }
        // v3.4: Theme + Data Saver are local prefs — load before the first
        // frame so Settings' choice applies from startup.
        FrianzoThemeState.mode = readThemeMode(applicationContext)
        FrianzoThemeState.dataSaver = readDataSaver(applicationContext)
        setContent {
            FrianzoTheme {
                val session: SessionViewModel = viewModel(factory = sessionViewModelFactory(api))
                // PERF: the feed ViewModel is created here (Activity scope),
                // not inside the Feed tab, so (a) its first page starts
                // loading IN PARALLEL with Session /me instead of after it,
                // and (b) it survives tab switches (see FeedViewModel).
                val feedVm: FeedViewModel = viewModel(factory = feedViewModelFactory(api, api2, applicationContext))
                // v5.12: cache-first — show the last saved feed instantly,
                // then refresh it from the network in the background.
                LaunchedEffect(Unit) { feedVm.restoreCache(); feedVm.loadMore() }
                val state by session.state.collectAsState()
                when (state) {
                    is AuthState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        DotsLoading()
                    }
                    is AuthState.LoggedIn -> {
                        val loggedIn = state as AuthState.LoggedIn
                        val user = loggedIn.user
                        // Gate fix (v5.1, owner complaint 2026-11-07 ~09:50
                        // PKT): setup shows ONLY for a fresh login that
                        // created the account (isNewUser=true from the
                        // login response) AND profileCompleted != true.
                        // profileCompleted ALONE is the wrong signal: it
                        // defaults to false in the Prisma schema and is set
                        // true only by completeProfile, so EVERY
                        // pre-existing account (including the owner's) has
                        // false — v5.0 trapped them all on setup. The
                        // backend auto-assigns a username at signup, so a
                        // blank-username gate would never show setup
                        // either. isNewUser is the one true signal; a
                        // restored session (/me only, no fresh login) never
                        // carries it and goes straight to the feed.
                        // WITHIN the new-user flow the hard gate stands:
                        // setup consumes Back and the feed opens only after
                        // a successful save (session refresh -> /me says
                        // complete). Deep links still wait safely: they only
                        // route once AppShell mounts, i.e. after setup.
                        if (loggedIn.isNewUser && user?.profileCompleted != true) {
                            ProfileSetupScreen(api2) { session.refresh() }
                        } else {
                            AppShell(api, api2, user?.id, cookieJar, session, feedVm, deepLink = pendingDeepLink, onDeepLinkConsumed = { pendingDeepLink = null })
                        }
                    }
                    is AuthState.Error -> {
                        // Network failure on /me is not "logged out": show the
                        // error with Retry, and the login screen below it so
                        // the user can still sign in another way.
                        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                            Text((state as AuthState.Error).message, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { session.refresh() }) { Text("Retry", maxLines = 1, softWrap = false) }
                            Spacer(Modifier.height(12.dp))
                            TextButton(onClick = { session.markLoggedOut() }) { Text("Continue to login", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                    else -> LoginScreen(api, session, cookieJar) { session.refresh() }
                }
            }
        }
    }

    // C-B1: a link tapped while the app is already running arrives here
    // (singleTop, see the manifest) instead of a fresh onCreate.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        parsePushIntent(intent)?.let { pendingDeepLink = it }
    }
}

private enum class Tab { FEED, REELS, CREATE, CHAT, NOTIFS, SEARCH, PROFILE }

// C-B1: deep links, URL shapes verified read-only against the web app
// (vynzo-frontend src/app): /post/[id], /reels?id=<id>,
// /u/[username], /s/[code]. BROWSER = short link: the backend
// GET /api/share/{code} resolve exists, but the native Api2 has no call
// for it (area B owns Api2.kt), so short links open in the browser
// instead of being faked in-app — PARTIAL, see the area report.
private enum class DeepLinkKind { POST, REEL, PROFILE, BROWSER, TAG, CHAT, MY_PROFILE, VERIFICATION, NOTIFICATIONS }
// extra: chat sender name for CHAT (a /messages/{id} URL carries no name).
private data class DeepLink(val kind: DeepLinkKind, val value: String, val extra: String? = null)

// v5.19 (FCM): a chat push carries the sender's display name as an
// intent extra (FrianzoMessagingService.EXTRA_CHAT_NAME); plain web
// links have no extra, so they parse exactly as before.
private fun parsePushIntent(intent: Intent?): DeepLink? {
    val link = parseDeepLink(intent?.data) ?: return null
    return if (link.kind == DeepLinkKind.CHAT) link.copy(extra = intent?.getStringExtra(FrianzoMessagingService.EXTRA_CHAT_NAME)) else link
}

private fun parseDeepLink(uri: Uri?): DeepLink? {
    if (uri == null) return null
    val host = uri.host ?: return null
    if (host != "frianzo.online" && host != "www.frianzo.online") return null
    val seg = uri.pathSegments
    return when {
        seg.size == 2 && seg[0] == "post" && seg[1].isNotBlank() -> DeepLink(DeepLinkKind.POST, seg[1])
        seg.size == 1 && seg[0] == "reels" && !uri.getQueryParameter("id").isNullOrBlank() -> DeepLink(DeepLinkKind.REEL, uri.getQueryParameter("id")!!)
        seg.size == 2 && seg[0] == "reels" && seg[1].isNotBlank() -> DeepLink(DeepLinkKind.REEL, seg[1])
        seg.size == 2 && seg[0] == "u" && seg[1].isNotBlank() -> DeepLink(DeepLinkKind.PROFILE, seg[1])
        seg.size == 2 && seg[0] == "tag" && seg[1].isNotBlank() -> DeepLink(DeepLinkKind.TAG, seg[1])
        seg.size == 2 && seg[0] == "s" && seg[1].isNotBlank() -> DeepLink(DeepLinkKind.BROWSER, "https://frianzo.online/s/${seg[1]}")
        // v5.19 (FCM tap destinations): /messages/{id} is the chat push
        // url (web: /messages/{conversationId}); /me and
        // /settings-menu/verification are internal-only push targets
        // (no such web routes) for verification_approved (own profile)
        // and verification_rejected (Verification settings);
        // /notifications is the backend's default push url.
        seg.size == 2 && seg[0] == "messages" && seg[1].isNotBlank() -> DeepLink(DeepLinkKind.CHAT, seg[1])
        seg.size == 1 && seg[0] == "me" -> DeepLink(DeepLinkKind.MY_PROFILE, "")
        seg.size == 2 && seg[0] == "settings-menu" && seg[1] == "verification" -> DeepLink(DeepLinkKind.VERIFICATION, "")
        seg.size == 1 && seg[0] == "notifications" -> DeepLink(DeepLinkKind.NOTIFICATIONS, "")
        else -> null
    }
}

private data class NavDef(val tabValue: Tab, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val badge: Int = 0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppShell(api: FrianzoApi, api2: FrianzoApi2, myId: String?, cookieJar: PersistentCookieJar, session: SessionViewModel, feedVm: FeedViewModel, deepLink: DeepLink? = null, onDeepLinkConsumed: () -> Unit = {}) {
    // C-B18: navigation state survives rotation / process death. Uri
    // state (pendingMedia / pendingReelUri) stays plain remember — a
    // content Uri is not saveable, so a picked photo/video is lost on
    // rotation (known limitation, see the area report).
    var tab by rememberSaveable { mutableStateOf(Tab.FEED) }
    var openChat by rememberSaveable { mutableStateOf<Pair<String, String>?>(null) }
    var openProfile by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingMedia by remember { mutableStateOf<android.net.Uri?>(null) }
    var unread by remember { mutableStateOf(0) }
    var unreadMessages by remember { mutableStateOf(0) }
    // v5.15: real drawer state (replaces the old boolean + dialog).
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    var drawerScreen by rememberSaveable { mutableStateOf<String?>(null) }
    // v5.19 (FCM): a verification-rejected push opens Settings straight
    // at the Verification sub-screen. Cleared whenever the drawer closes
    // so a stale value can never hijack a later manual Settings open.
    var drawerSubScreen by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(drawerScreen) { if (drawerScreen == null) drawerSubScreen = null }
    // v2.9: notification deep-links + AI Video "Post as Reel" handoff.
    var openPostId by rememberSaveable { mutableStateOf<String?>(null) }
    var focusReelId by rememberSaveable { mutableStateOf<String?>(null) }
    // v5.18: open tag page (from a tapped hashtag or a /tag/{tag} link).
    var openTag by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingReelUri by remember { mutableStateOf<android.net.Uri?>(null) }
    // v5.1 (web-like reel editor): the editor + camera are full-screen
    // (web: no nav bar on /reels/new) — ReelsScreen reports when either
    // is open and the top bar hides until it closes.
    var reelsEditorOpen by remember { mutableStateOf(false) }
    // v3.6: context must be declared BEFORE reelsSession uses it
    // (v3.5 build failed: MainActivity.kt:112 used it first).
    val context = LocalContext.current
    // v3.5 (R2): the reels session survives tab/profile/chat switches.
    val reelsSession = remember { ReelsSession(context) }
    var myProfile by remember { mutableStateOf<PublicProfile?>(null) }
    val scope = rememberCoroutineScope()
    // v3.1: POST_NOTIFICATIONS is asked ONCE, right after login — never at
    // launch, never again. (Push delivery itself still needs the Firebase
    // key, an owner step; this only asks the system permission so the app
    // is ready the day push is switched on. Gallery/Camera/Microphone are
    // asked at feature use; Location only when the composer adds a
    // location (v3.4).)
    var notifAsked by remember { mutableStateOf(false) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (!notifAsked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifAsked = true
            if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    // v5.19 (FCM): register this device's push token now that the user
    // is logged in — AppShell only exists in the LoggedIn branch, so
    // this runs once per login (and again on a restored session after
    // process death). Token refreshes are handled by
    // FrianzoMessagingService.onNewToken. Best-effort and silent: a
    // failure (no google-services.json, no network) must never break
    // login, and the backend upserts the token, so registering the same
    // token twice is harmless.
    LaunchedEffect(Unit) {
        try {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                scope.launch {
                    try { api2.registerPushToken(PushTokenRequest(token)) } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }
    // v2.9: drawer profile row data (loaded once; a failure just leaves
    // the row showing the fallback name).
    LaunchedEffect(Unit) {
        try { val r = api2.myProfile(); if (r.success) myProfile = r.data?.user } catch (_: Exception) {}
    }

    // PERMANENT FIX for H1: Android Back from a chat thread or an opened
    // profile returns to the previous screen instead of exiting the app.
    BackHandler(enabled = openChat != null) { openChat = null }
    BackHandler(enabled = openProfile != null) { openProfile = null }
    BackHandler(enabled = drawerScreen != null) { drawerScreen = null }
    BackHandler(enabled = openPostId != null) { openPostId = null }
    // v5.18: Back from a tag page closes it.
    BackHandler(enabled = openTag != null) { openTag = null }
    // C-B3: Back from a focused reel returns to the reels list, and Back
    // from Create / Search / Chat list / Notifications / Profile returns
    // to Feed — instead of closing the app. Registered after the overlay
    // handlers, so an open overlay still wins. The tab handler stays off
    // while any overlay is open.
    BackHandler(enabled = focusReelId != null) { focusReelId = null }
    BackHandler(enabled = tab != Tab.FEED && openChat == null && openProfile == null && drawerScreen == null && openPostId == null && focusReelId == null && openTag == null) {
        // Leaving Create by Back drops the pending pick, like Close does.
        if (tab == Tab.CREATE) pendingMedia = null
        tab = Tab.FEED
    }

    // C-B1: route a deep link once the user is logged in (AppShell only
    // exists then, so a cold-start link waits for login instead of
    // being dropped).
    LaunchedEffect(deepLink) {
        val dl = deepLink ?: return@LaunchedEffect
        when (dl.kind) {
            DeepLinkKind.POST -> { openChat = null; openProfile = null; openPostId = dl.value }
            DeepLinkKind.REEL -> { focusReelId = dl.value; tab = Tab.REELS }
            DeepLinkKind.PROFILE -> { openProfile = dl.value }
            // v5.18: /tag/{tag} opens the in-app tag page (no backend change).
            DeepLinkKind.TAG -> { openTag = dl.value }
            // v5.19 (FCM tap destinations): chat opens the thread (the
            // sender name rides in dl.extra); MY_PROFILE opens the own
            // profile tab; VERIFICATION opens Settings at the
            // Verification sub-screen; NOTIFICATIONS opens the Alerts tab.
            DeepLinkKind.CHAT -> { openProfile = null; openPostId = null; openChat = dl.value to (dl.extra?.takeIf { it.isNotBlank() } ?: "Chat") }
            DeepLinkKind.MY_PROFILE -> { openChat = null; openProfile = null; openPostId = null; drawerScreen = null; tab = Tab.PROFILE }
            DeepLinkKind.VERIFICATION -> { openChat = null; openProfile = null; openPostId = null; drawerScreen = "Settings"; drawerSubScreen = "verification" }
            DeepLinkKind.NOTIFICATIONS -> { openChat = null; openProfile = null; openPostId = null; drawerScreen = null; tab = Tab.NOTIFS }
            DeepLinkKind.BROWSER -> {
                // Short link: no in-app resolver (see parseDeepLink note).
                try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(dl.value))) } catch (_: Exception) {
                    Toast.makeText(context, "Could not open this link — no browser found.", Toast.LENGTH_LONG).show()
                }
            }
        }
        onDeepLinkConsumed()
    }

    // Unread badge for the Alerts tab (M1): refresh whenever the tab changes
    // and after returning to it; a failed count simply shows no badge.
    // v5.20 (M21): the badge is now LIVE — the web refetches the unread
    // count every 30s (Shell.tsx), the app only refreshed on tab change.
    // The 30s poll below runs only while logged in (AppShell only exists
    // in the LoggedIn branch) and is cancelled on logout with the
    // composition. Same silent-failure rule as the tab refresh.
    suspend fun refreshUnread() {
        try {
            val res = api2.unreadCount()
            // C-B17: the backend GET /api/notifications/unread-count
            // returns ONLY { count } — and count IS the unread total
            // (notificationService.getUnreadCount counts read=false).
            // There is no unreadCount field in the response, so the
            // old `unreadCount.takeIf { >0 } ?: count` fallback was
            // needless; use count directly, no badge when it is 0.
            if (res.success && res.data != null) unread = res.data.count
        } catch (_: Exception) {}
        try {
            val res = api2.conversations()
            if (res.success && res.data != null) unreadMessages = res.data.sumOf { it.unreadCount }
        } catch (_: Exception) {}
    }
    LaunchedEffect(tab) { refreshUnread() }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            refreshUnread()
        }
    }

    fun logout() {
        // v5.19 (FCM): remove this device's push token BEFORE clearing
        // the auth cookie — DELETE /api/notifications/push-token needs
        // the cookie. The token fetch + DELETE run first; logout always
        // completes (3 s watchdog) even if FCM hangs or the call fails,
        // so a push failure can never trap the user on this phone.
        // No server logout endpoint is verified, so app logout clears the
        // persisted auth cookies (the same mechanism web logout relies on
        // client-side) and drops the session state. The server token then
        // simply expires (7 days) and cannot be reused from this phone.
        var loggedOut = false
        fun finishLogout() {
            if (!loggedOut) {
                loggedOut = true
                cookieJar.clear()
                session.markLoggedOut()
            }
        }
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                scope.launch {
                    try {
                        val token = if (task.isSuccessful) task.result else null
                        if (!token.isNullOrBlank()) api2.removePushToken(PushTokenRequest(token))
                    } catch (_: Exception) {}
                    finishLogout()
                }
            }
            scope.launch { kotlinx.coroutines.delay(3000); finishLogout() }
        } catch (_: Exception) {
            finishLogout()
        }
    }

    // v5.15: real left slide-in drawer (web Shell.tsx) — replaces the
    // centred "Frianzo" AlertDialog. Scrim black/40, panel 300dp wide
    // (max 85% of the screen width), surface background; closes on
    // scrim tap, X, Back, item tap or route change (ModalNavigationDrawer
    // handles scrim + Back; every row below closes it explicitly).
    // Icon tint uses onSurfaceVariant, which IS FrianzoTextSecondary
    // (#64748B) in the light scheme and the dark equivalent in dark mode;
    // error IS FrianzoBadgeRed (#DC2626) in the light scheme (Theme.kt).
    ModalNavigationDrawer(
        drawerState = drawerState,
        scrimColor = Color.Black.copy(alpha = 0.4f),
        drawerContent = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxHeight().fillMaxWidth(0.85f).widthIn(max = 300.dp),
            ) {
                Column(Modifier.fillMaxSize()) {
                    // Header: blue wordmark + X, with a bottom divider.
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Frianzo", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = FrianzoBlue, modifier = Modifier.weight(1f), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { scope.launch { drawerState.close() } }) { Icon(Icons.Filled.Close, contentDescription = "Close menu", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp)) }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    // Profile row: avatar + name + @username + View Profile.
                    Row(Modifier.fillMaxWidth().clickable { scope.launch { drawerState.close() }; tab = Tab.PROFILE; openChat = null; openProfile = null }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        FrianzoAvatar(myProfile?.profilePictureUrl, 48.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(myProfile?.displayName ?: myProfile?.username ?: "Frianzo", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            Text("@${myProfile?.username ?: ""}", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            Text("View Profile", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = FrianzoBlue, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    // Nav list (web Shell.tsx drawerLinks order), one
                    // hairline divider between rows; Share last, then the
                    // footer Logout row below the scrollable list.
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        val navItems = listOf(
                            "Settings" to Icons.Filled.Settings,
                            "Your Activity" to Icons.Filled.History,
                            "Professional Dashboard" to Icons.Filled.BarChart,
                            "Close Friends" to Icons.Filled.Star,
                            "AI Video Generator" to Icons.Filled.AutoAwesome,
                            "ISAI Assistant" to Icons.Filled.ChatBubble,
                        )
                        navItems.forEachIndexed { index, (label, icon) ->
                            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                            Row(Modifier.fillMaxWidth().clickable { scope.launch { drawerState.close() }; drawerScreen = label }.padding(horizontal = 16.dp, vertical = 12.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(label, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        Row(Modifier.fillMaxWidth().clickable {
                            scope.launch { drawerState.close() }
                            val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "Frianzo - share posts, videos and connect with people. Download: https://play.google.com/store/apps/details?id=com.frianzo.nativeapp") }
                            // C-B20: no share handler on the device must
                            // not crash the app — show a message instead.
                            try { context.startActivity(Intent.createChooser(intent, "Share Frianzo")) } catch (_: Exception) {
                                Toast.makeText(context, "No app available to share with.", Toast.LENGTH_LONG).show()
                            }
                        }.padding(horizontal = 16.dp, vertical = 12.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Share, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("Share Frianzo", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    // Footer: Logout (web has NO confirm dialog here).
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Row(Modifier.fillMaxWidth().clickable { scope.launch { drawerState.close() }; logout() }.padding(horizontal = 16.dp, vertical = 12.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.PowerSettingsNew, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Logout", fontSize = 16.sp, color = MaterialTheme.colorScheme.error, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
    ) {
    Scaffold(
        // LIVE CHROME (Shell.tsx): header row + second nav row, NO bottom bar.
        // Header: left = 3-lines menu + blue "Frianzo" TEXT wordmark (live has
        // no image logo in the header); right = Search + Messages (badge 9+).
        // Second row: Home / Reels / Notifications (badge 99+) / Profile,
        // 24dp outline icons, NO labels, active slate-900 / inactive slate-400.
        // v3.9 (live-web audit #2): the reels screen has a WHITE top bar
        // with 4 icons only (Home / Reels / Notifications / Profile) —
        // NO hamburger, NO "Frianzo" wordmark, NO Search, NO Messages.
        // The full chrome returns when an overlay opens (profile, chat,
        // drawer screen, post detail), because those need it.
        topBar = {
            if (!reelsEditorOpen) {
            val overlayOpen = drawerScreen != null || openChat != null || openProfile != null || openPostId != null || openTag != null
            if (overlayOpen || tab != Tab.REELS) {
            Column {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Outlined.Menu, contentDescription = "Menu", tint = Color(0xFF0F172A), modifier = Modifier.size(24.dp)) }
                            Text("Frianzo", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = FrianzoBlue, maxLines = 1, softWrap = false)
                        }
                    },
                    actions = {
                        IconButton(onClick = { tab = Tab.SEARCH; openChat = null; openProfile = null }) { Icon(Icons.Outlined.Search, contentDescription = "Search", tint = Color(0xFF0F172A), modifier = Modifier.size(24.dp)) }
                        Box(contentAlignment = Alignment.TopEnd) {
                            IconButton(onClick = { tab = Tab.CHAT; openChat = null; openProfile = null }) { Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = "Messages", tint = Color(0xFF0F172A), modifier = Modifier.size(24.dp)) }
                            if (unreadMessages > 0) {
                                Badge(containerColor = FrianzoBadgeRed, modifier = Modifier.offset(x = (-2).dp, y = 2.dp)) {
                                    Text(if (unreadMessages > 9) "9+" else "$unreadMessages", fontSize = 10.sp, maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                )
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceAround) {
                        val nav2 = listOf(
                            Triple(Tab.FEED, Icons.Outlined.Home, "Home"),
                            Triple(Tab.REELS, Icons.Filled.PlayArrow, "Reels"),
                            Triple(Tab.NOTIFS, Icons.Outlined.Notifications, "Notifications"),
                            Triple(Tab.PROFILE, Icons.Outlined.Person, "Profile"),
                        )
                        nav2.forEach { (t, icon, label) ->
                            val selected = tab == t
                            val color = if (selected) Color(0xFF0F172A) else Color(0xFF94A3B8)
                            Box(contentAlignment = Alignment.TopEnd, modifier = Modifier.clickable { tab = t; openChat = null; openProfile = null }.padding(8.dp)) {
                                Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(24.dp))
                                if (t == Tab.NOTIFS && unread > 0) {
                                    Badge(containerColor = FrianzoBadgeRed, modifier = Modifier.offset(x = 8.dp, y = (-4).dp)) {
                                        Text(if (unread > 99) "99+" else "$unread", fontSize = 10.sp, maxLines = 1, softWrap = false)
                                    }
                                }
                            }
                        }
                    }
                }
                HorizontalDivider()
            }
            } else {
                // v4.0 (audit #3): white bar, 58dp tall + 1dp #E5E7EB
                // bottom border; icons at web sizes (NOT 24dp).
                Surface(color = Color.White) {
                    Column {
                    Row(Modifier.fillMaxWidth().statusBarsPadding().height(58.dp), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
                        val reelsNav = listOf(
                            Triple(Tab.FEED, Icons.Outlined.Home, "Home"),
                            Triple(Tab.REELS, Icons.Filled.OndemandVideo, "Reels"),
                            Triple(Tab.NOTIFS, Icons.Outlined.Notifications, "Notifications"),
                            Triple(Tab.PROFILE, Icons.Outlined.Person, "Profile"),
                        )
                        reelsNav.forEach { (t, icon, label) ->
                            val selected = tab == t
                            val color = if (selected) Color(0xFF0F172A) else Color(0xFF94A3B8)
                            Box(contentAlignment = Alignment.TopEnd, modifier = Modifier.clickable { tab = t; openChat = null; openProfile = null }.padding(8.dp)) {
                                // v4.0 (audit #3): web icon sizes.
                                val (iw, ih) = when (t) {
                                    Tab.FEED -> 20.dp to 21.dp
                                    Tab.REELS -> 20.dp to 20.dp
                                    Tab.NOTIFS -> 18.dp to 21.dp
                                    else -> 16.dp to 18.dp
                                }
                                Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(width = iw, height = ih))
                                if (t == Tab.NOTIFS && unread > 0) {
                                    Badge(containerColor = FrianzoBadgeRed, modifier = Modifier.offset(x = 8.dp, y = (-4).dp)) {
                                        Text(if (unread > 99) "99+" else "$unread", fontSize = 10.sp, maxLines = 1, softWrap = false)
                                    }
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = Color(0xFFE5E7EB), thickness = 1.dp)
                    }
                }
            }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.padding(padding)) {
            // v5.12: "You're offline" banner (global connectivity state).
            if (!NetworkMonitor.isOnline) {
                Box(
                    Modifier.fillMaxWidth().background(Color(0xFFB91C1C)).padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "You're offline — actions will send when you're back online",
                        color = Color.White,
                        fontSize = 12.sp,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                drawerScreen != null -> DrawerScreenRouter(api2, drawerScreen!!, onBack = { drawerScreen = null }, onLogout = { drawerScreen = null; logout() }, onSwitched = { drawerScreen = null; session.refresh() }, onPostAsReel = { uri -> drawerScreen = null; pendingReelUri = uri; tab = Tab.REELS }, onOpenDrawerScreen = { drawerScreen = it }, initialSubScreen = drawerSubScreen, onInitialSubScreenConsumed = { drawerSubScreen = null }, api = api)
                openChat != null -> ChatThreadScreen(api2, openChat!!.first, openChat!!.second, myId, onBack = { openChat = null }, onOpenProfile = { username -> openChat = null; openProfile = username })
                openProfile != null -> ProfileScreen(api2, openProfile, myId, null, onBack = { openProfile = null }, onOpenChat = { id, name -> openProfile = null; openChat = id to name }, feedVm = feedVm, onUser = { openProfile = it }, onOpenReel = { id -> openProfile = null; focusReelId = id; tab = Tab.REELS })
                openPostId != null -> PostDetailScreen(feedVm, openPostId!!, myId, onClose = { openPostId = null }, onAuthor = { username -> openPostId = null; openProfile = username }, onOpenChat = { id, name -> openPostId = null; openChat = id to name })
                // v5.18: tag page overlay (hashtag taps + /tag/{tag} links).
                openTag != null -> TagScreen(api2, openTag!!, myId, onBack = { openTag = null }, onOpenProfile = { username -> openTag = null; openProfile = username }, onOpenReel = { id -> openTag = null; focusReelId = id; tab = Tab.REELS }, onOpenChat = { id, name -> openTag = null; openChat = id to name }, onOpenPost = { id -> openTag = null; openPostId = id }, onTag = { t -> openTag = t })
                tab == Tab.FEED -> FeedScreen(feedVm, myId, onCreatePost = { tab = Tab.CREATE }, onPickMedia = { uri -> pendingMedia = uri; tab = Tab.CREATE }, onAuthor = { username -> if (username.isNotBlank()) openProfile = username }, onOpenChat = { id, name -> openChat = id to name }, onOpenReel = { id -> focusReelId = id; tab = Tab.REELS }, onTag = { tag -> openTag = tag })
                tab == Tab.REELS -> ReelsScreen(api2, myId, session = reelsSession, onAuthor = { username -> openProfile = username }, onOpenChat = { id, name -> openChat = id to name }, focusReelId = focusReelId, onFocusConsumed = { focusReelId = null }, uploadUri = pendingReelUri, onUploadUriConsumed = { pendingReelUri = null }, onEditorOpenChange = { reelsEditorOpen = it }, onTag = { tag -> openTag = tag })
                tab == Tab.CREATE -> CreatePostScreen(api2, initialUri = pendingMedia, onPosted = { pendingMedia = null; feedVm.refresh(); tab = Tab.FEED }, onClose = { pendingMedia = null; tab = Tab.FEED }, onPostAsReel = { uri -> pendingMedia = null; pendingReelUri = uri; tab = Tab.REELS })
                tab == Tab.CHAT -> ChatListScreen(api2, myId) { id, name -> openChat = id to name }
                // v5.19 (H5): verification taps need destinations the screen
                // itself cannot reach — own profile is the Profile tab
                // (ProfileScreen with a null username), Verification is
                // the Settings drawer opened straight at its Verification
                // sub-screen ("Verification" router label, DrawerScreens).
                tab == Tab.NOTIFS -> NotificationsScreen(api2, onAuthor = { username -> openProfile = username }, onOpenPost = { id -> openPostId = id }, onOpenReel = { id -> focusReelId = id; tab = Tab.REELS }, onOpenChat = { id, name -> openChat = id to name }, onOpenOwnProfile = { openChat = null; openProfile = null; openPostId = null; openTag = null; tab = Tab.PROFILE }, onOpenVerification = { openChat = null; openProfile = null; openPostId = null; openTag = null; drawerScreen = "Verification" })
                tab == Tab.SEARCH -> SearchScreen(api2, onUser = { username -> openProfile = username }, onTag = { tag -> openTag = tag }, onOpenPost = { id -> openPostId = id }, onOpenReel = { id -> focusReelId = id; tab = Tab.REELS })
                tab == Tab.PROFILE -> ProfileScreen(api2, null, myId, ::logout, feedVm = feedVm, onUser = { openProfile = it }, onDashboard = { drawerScreen = "Professional Dashboard" }, onOpenReel = { id -> focusReelId = id; tab = Tab.REELS })
            }
        }
        }
    }
    }
}

