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
    var unread by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    // PERMANENT FIX for H1: Android Back from a chat thread or an opened
    // profile returns to the previous screen instead of exiting the app.
    BackHandler(enabled = openChat != null) { openChat = null }
    BackHandler(enabled = openProfile != null) { openProfile = null }

    // Unread badge for the Alerts tab (M1): refresh whenever the tab changes
    // and after returning to it; a failed count simply shows no badge.
    LaunchedEffect(tab) {
        scope.launch {
            try {
                val res = api2.unreadCount()
                if (res.success && res.data != null) unread = res.data.unreadCount.takeIf { it > 0 } ?: res.data.count
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
        // Web header: white bar, blue F logo + bold blue "Frianzo" wordmark.
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        FrianzoLogo(32.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Frianzo",
                            style = MaterialTheme.typography.titleLarge,
                            color = FrianzoBlue,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.White),
            )
        },
        containerColor = FrianzoPageBackground,
        bottomBar = {
            // FB-style custom bottom bar (NOT NavigationBar): NavigationBarItem
            // gives each of 7 items too little width, so "Reels"/"Alerts"/
            // "Search"/"Profile" wrapped across two lines in the video.
            // This Row gives every item equal weight, a 24dp icon and an
            // 11sp label forced to one line with no soft wrap, so no label
            // can ever wrap. All 7 destinations are kept: removing one
            // would break navigation (Post, Chat, Alerts are core).
            Surface(color = androidx.compose.ui.graphics.Color.White, tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    val defs = listOf(
                        NavDef(Tab.FEED, "Feed", Icons.Filled.Home),
                        NavDef(Tab.REELS, "Reels", Icons.Filled.PlayArrow),
                        NavDef(Tab.CREATE, "Post", Icons.Filled.Add),
                        NavDef(Tab.CHAT, "Chat", Icons.Filled.Chat),
                        NavDef(Tab.NOTIFS, "Alerts", Icons.Filled.Notifications, unread),
                        NavDef(Tab.SEARCH, "Search", Icons.Filled.Search),
                        NavDef(Tab.PROFILE, "Profile", Icons.Filled.Person),
                    )
                    defs.forEach { def ->
                        val selected = tab == def.tabValue
                        val color = if (selected) FrianzoBlue else FrianzoTextSecondary
                        Column(
                            Modifier.weight(1f).clickable { tab = def.tabValue; openChat = null; openProfile = null },
                            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                        ) {
                            Box(contentAlignment = androidx.compose.ui.Alignment.TopEnd) {
                                Icon(def.icon, contentDescription = def.label, tint = color, modifier = Modifier.size(24.dp))
                                if (def.badge > 0) {
                                    Badge(containerColor = FrianzoBadgeRed, modifier = Modifier.offset(x = 8.dp, y = (-4).dp)) {
                                        Text("${def.badge}", fontSize = 9.sp)
                                    }
                                }
                            }
                            Text(
                                def.label, fontSize = 11.sp, maxLines = 1, softWrap = false,
                                color = color,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when {
                openChat != null -> ChatThreadScreen(api2, openChat!!.first, openChat!!.second, myId)
                openProfile != null -> ProfileScreen(api2, openProfile, myId, null)
                tab == Tab.FEED -> FeedScreen(feedVm, myId) { tab = Tab.CREATE }
                tab == Tab.REELS -> ReelsScreen(api2)
                tab == Tab.CREATE -> CreatePostScreen(api2) { feedVm.refresh(); tab = Tab.FEED }
                tab == Tab.CHAT -> ChatListScreen(api2) { id, name -> openChat = id to name }
                tab == Tab.NOTIFS -> NotificationsScreen(api2)
                tab == Tab.SEARCH -> SearchScreen(api2) { username -> openProfile = username }
                tab == Tab.PROFILE -> ProfileScreen(api2, null, myId, ::logout)
            }
        }
    }
}

