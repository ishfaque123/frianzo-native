package com.frianzo.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
                val state by session.state.collectAsState()
                when (state) {
                    is AuthState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        DotsLoading()
                    }
                    is AuthState.LoggedIn -> {
                        val user = (state as AuthState.LoggedIn).user
                        AppShell(api, api2, user?.id, cookieJar, session)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppShell(api: FrianzoApi, api2: FrianzoApi2, myId: String?, cookieJar: PersistentCookieJar, session: SessionViewModel) {
    var tab by remember { mutableStateOf(Tab.FEED) }
    var openChat by remember { mutableStateOf<Pair<String, String>?>(null) }
    var openProfile by remember { mutableStateOf<String?>(null) }
    var feedRefresh by remember { mutableStateOf(0) }
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
            // Web bottom nav: white bar, active tab blue, inactive tabs grey.
            // Icons come from material-icons-extended (declared in
            // app/build.gradle.kts): Chat/ChatBubble live ONLY there, not
            // in the core set — that missing dependency broke builds
            // #31/#32/#33. Do NOT remove the extended dependency.
            NavigationBar(
                containerColor = androidx.compose.ui.graphics.Color.White,
            ) {
                val navColors = NavigationBarItemDefaults.colors(
                    selectedIconColor = FrianzoBlue,
                    selectedTextColor = FrianzoBlue,
                    unselectedIconColor = FrianzoTextSecondary,
                    unselectedTextColor = FrianzoTextSecondary,
                    indicatorColor = FrianzoBlueTint,
                )
                NavigationBarItem(selected = tab == Tab.FEED, onClick = { tab = Tab.FEED; openChat = null; openProfile = null }, label = { Text("Feed") }, icon = { Icon(Icons.Filled.Home, contentDescription = "Feed") }, colors = navColors)
                NavigationBarItem(selected = tab == Tab.REELS, onClick = { tab = Tab.REELS; openChat = null; openProfile = null }, label = { Text("Reels") }, icon = { Icon(Icons.Filled.PlayArrow, contentDescription = "Reels") }, colors = navColors)
                NavigationBarItem(selected = tab == Tab.CREATE, onClick = { tab = Tab.CREATE; openChat = null; openProfile = null }, label = { Text("Post") }, icon = { Icon(Icons.Filled.Add, contentDescription = "Post") }, colors = navColors)
                NavigationBarItem(selected = tab == Tab.CHAT, onClick = { tab = Tab.CHAT; openChat = null; openProfile = null }, label = { Text("Chat") }, icon = { Icon(Icons.Filled.Chat, contentDescription = "Chat") }, colors = navColors)
                NavigationBarItem(selected = tab == Tab.NOTIFS, onClick = { tab = Tab.NOTIFS; openChat = null; openProfile = null }, label = { Text("Alerts") }, icon = {
                    BadgedBox(badge = { if (unread > 0) Badge(containerColor = FrianzoBadgeRed) { Text("$unread") } }) {
                        Icon(Icons.Filled.Notifications, contentDescription = "Alerts")
                    }
                }, colors = navColors)
                NavigationBarItem(selected = tab == Tab.SEARCH, onClick = { tab = Tab.SEARCH; openChat = null; openProfile = null }, label = { Text("Search") }, icon = { Icon(Icons.Filled.Search, contentDescription = "Search") }, colors = navColors)
                NavigationBarItem(selected = tab == Tab.PROFILE, onClick = { tab = Tab.PROFILE; openChat = null; openProfile = null }, label = { Text("Profile") }, icon = { Icon(Icons.Filled.Person, contentDescription = "Profile") }, colors = navColors)
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when {
                openChat != null -> ChatThreadScreen(api2, openChat!!.first, openChat!!.second, myId)
                openProfile != null -> ProfileScreen(api2, openProfile, myId, null)
                tab == Tab.FEED -> key(feedRefresh) { FeedScreen(api, api2) }
                tab == Tab.REELS -> ReelsScreen(api2)
                tab == Tab.CREATE -> CreatePostScreen(api2) { feedRefresh++; tab = Tab.FEED }
                tab == Tab.CHAT -> ChatListScreen(api2) { id, name -> openChat = id to name }
                tab == Tab.NOTIFS -> NotificationsScreen(api2)
                tab == Tab.SEARCH -> SearchScreen(api2) { username -> openProfile = username }
                tab == Tab.PROFILE -> ProfileScreen(api2, null, myId, ::logout)
            }
        }
    }
}
