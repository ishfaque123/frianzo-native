package com.frianzo.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cookieJar = PersistentCookieJar(applicationContext)
        val api = createApi(applicationContext, cookieJar)
        val api2 = createApi2(applicationContext, cookieJar)
        setContent {
            FrianzoTheme {
                val session: SessionViewModel = viewModel(factory = sessionViewModelFactory(api))
                // PERF: the feed ViewModel is created here (Activity scope),
                // not inside the Feed tab, so (a) its first page starts
                // loading IN PARALLEL with Session /me instead of after it,
                // and (b) it survives tab switches (see FeedViewModel).
                val feedVm: FeedViewModel = viewModel(factory = feedViewModelFactory(api, api2))
                LaunchedEffect(Unit) { feedVm.loadMore() }
                val state by session.state.collectAsState()
                when (state) {
                    is AuthState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        DotsLoading()
                    }
                    is AuthState.LoggedIn -> {
                        val user = (state as AuthState.LoggedIn).user
                        AppShell(api, api2, user?.id, cookieJar, session, feedVm)
                    }
                    is AuthState.Error -> {
                        // Network failure on /me is not "logged out": show the
                        // error with Retry, and the login screen below it so
                        // the user can still sign in another way.
                        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                            Text((state as AuthState.Error).message, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { session.refresh() }) { Text("Retry") }
                            Spacer(Modifier.height(12.dp))
                            TextButton(onClick = { session.markLoggedOut() }) { Text("Continue to login") }
                        }
                    }
                    else -> LoginScreen(api, session, cookieJar) { session.refresh() }
                }
            }
        }
    }
}

private enum class Tab { FEED, REELS, CREATE, CHAT, NOTIFS, SEARCH, PROFILE }

private data class NavDef(val tabValue: Tab, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val badge: Int = 0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppShell(api: FrianzoApi, api2: FrianzoApi2, myId: String?, cookieJar: PersistentCookieJar, session: SessionViewModel, feedVm: FeedViewModel) {
    var tab by remember { mutableStateOf(Tab.FEED) }
    var openChat by remember { mutableStateOf<Pair<String, String>?>(null) }
    var openProfile by remember { mutableStateOf<String?>(null) }
    var pendingMedia by remember { mutableStateOf<android.net.Uri?>(null) }
    var unread by remember { mutableStateOf(0) }
    var unreadMessages by remember { mutableStateOf(0) }
    var drawerOpen by remember { mutableStateOf(false) }
    var drawerScreen by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // PERMANENT FIX for H1: Android Back from a chat thread or an opened
    // profile returns to the previous screen instead of exiting the app.
    BackHandler(enabled = openChat != null) { openChat = null }
    BackHandler(enabled = openProfile != null) { openProfile = null }
    BackHandler(enabled = drawerScreen != null) { drawerScreen = null }

    // Unread badge for the Alerts tab (M1): refresh whenever the tab changes
    // and after returning to it; a failed count simply shows no badge.
    LaunchedEffect(tab) {
        scope.launch {
            try {
                val res = api2.unreadCount()
                if (res.success && res.data != null) unread = res.data.unreadCount.takeIf { it > 0 } ?: res.data.count
            } catch (_: Exception) {}
        }
        scope.launch {
            try {
                val res = api2.conversations()
                if (res.success && res.data != null) unreadMessages = res.data.sumOf { it.unreadCount }
            } catch (_: Exception) {}
        }
    }

    fun logout() {
        // No server logout endpoint is verified, so app logout clears the
        // persisted auth cookies (the same mechanism web logout relies on
        // client-side) and drops the session state. The server token then
        // simply expires (7 days) and cannot be reused from this phone.
        cookieJar.clear()
        session.markLoggedOut()
    }

    Scaffold(
        // LIVE CHROME (Shell.tsx): header row + second nav row, NO bottom bar.
        // Header: left = 3-lines menu + blue "Frianzo" TEXT wordmark (live has
        // no image logo in the header); right = Search + Messages (badge 9+).
        // Second row: Home / Reels / Notifications (badge 99+) / Profile,
        // 24dp outline icons, NO labels, active slate-900 / inactive slate-400.
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { drawerOpen = true }) { Icon(Icons.Outlined.Menu, contentDescription = "Menu", tint = Color(0xFF0F172A), modifier = Modifier.size(24.dp)) }
                            Text("Frianzo", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = FrianzoBlue)
                        }
                    },
                    actions = {
                        IconButton(onClick = { tab = Tab.SEARCH; openChat = null; openProfile = null }) { Icon(Icons.Outlined.Search, contentDescription = "Search", tint = Color(0xFF0F172A), modifier = Modifier.size(24.dp)) }
                        Box(contentAlignment = Alignment.TopEnd) {
                            IconButton(onClick = { tab = Tab.CHAT; openChat = null; openProfile = null }) { Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = "Messages", tint = Color(0xFF0F172A), modifier = Modifier.size(24.dp)) }
                            if (unreadMessages > 0) {
                                Badge(containerColor = FrianzoBadgeRed, modifier = Modifier.offset(x = (-2).dp, y = 2.dp)) {
                                    Text(if (unreadMessages > 9) "9+" else "$unreadMessages", fontSize = 10.sp)
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
                                        Text(if (unread > 99) "99+" else "$unread", fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                }
                HorizontalDivider()
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when {
                drawerScreen != null -> DrawerScreenRouter(api2, drawerScreen!!, onBack = { drawerScreen = null }, onLogout = { drawerScreen = null; logout() })
                openChat != null -> ChatThreadScreen(api2, openChat!!.first, openChat!!.second, myId) { openChat = null }
                openProfile != null -> ProfileScreen(api2, openProfile, myId, null, onBack = { openProfile = null }, onOpenChat = { id, name -> openProfile = null; openChat = id to name }, feedVm = feedVm, onUser = { openProfile = it })
                tab == Tab.FEED -> FeedScreen(feedVm, myId, onCreatePost = { tab = Tab.CREATE }, onPickMedia = { uri -> pendingMedia = uri; tab = Tab.CREATE }, onAuthor = { username -> if (username.isNotBlank()) openProfile = username }, onOpenChat = { id, name -> openChat = id to name })
                tab == Tab.REELS -> ReelsScreen(api2, myId) { username -> openProfile = username }
                tab == Tab.CREATE -> CreatePostScreen(api2, initialUri = pendingMedia) { pendingMedia = null; feedVm.refresh(); tab = Tab.FEED }
                tab == Tab.CHAT -> ChatListScreen(api2) { id, name -> openChat = id to name }
                tab == Tab.NOTIFS -> NotificationsScreen(api2) { username -> openProfile = username }
                tab == Tab.SEARCH -> SearchScreen(api2) { username -> openProfile = username }
                tab == Tab.PROFILE -> ProfileScreen(api2, null, myId, ::logout, feedVm = feedVm, onUser = { openProfile = it })
            }
        if (drawerOpen) {
            // LIVE DRAWER (Shell.tsx drawerLinks, in order): profile row, then
            // Settings / Your Activity / Professional Dashboard / Close Friends
            // / AI Video Generator / ISAI Assistant, then Share Frianzo, then
            // Logout in the footer. Items with no native screen yet open a
            // Each item opens a real native screen (DrawerScreens.kt), never a placeholder.
            AlertDialog(
                onDismissRequest = { drawerOpen = false },
                title = { Text("Frianzo") },
                text = {
                    Column {
                        TextButton(onClick = { drawerOpen = false; tab = Tab.PROFILE; openChat = null; openProfile = null }, modifier = Modifier.fillMaxWidth()) { Text("View Profile", modifier = Modifier.fillMaxWidth()) }
                        HorizontalDivider()
                        val items = listOf("Settings", "Your Activity", "Professional Dashboard", "Close Friends", "AI Video Generator", "ISAI Assistant")
                        items.forEach { label ->
                            TextButton(onClick = { drawerOpen = false; drawerScreen = label }, modifier = Modifier.fillMaxWidth()) { Text(label, modifier = Modifier.fillMaxWidth()) }
                        }
                        HorizontalDivider()
                        TextButton(onClick = {
                            drawerOpen = false
                            val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "Frianzo - share posts, videos and connect with people. Download: https://play.google.com/store/apps/details?id=com.frianzo.app") }
                            context.startActivity(Intent.createChooser(intent, "Share Frianzo"))
                        }, modifier = Modifier.fillMaxWidth()) { Text("Share Frianzo", modifier = Modifier.fillMaxWidth()) }
                        HorizontalDivider()
                        TextButton(onClick = { drawerOpen = false; logout() }, modifier = Modifier.fillMaxWidth()) { Text("Logout", color = Color.Red, modifier = Modifier.fillMaxWidth()) }
                    }
                },
                confirmButton = { TextButton(onClick = { drawerOpen = false }) { Text("Close") } },
            )
        }

        }
    }
}

