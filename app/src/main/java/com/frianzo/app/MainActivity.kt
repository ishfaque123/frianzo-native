package com.frianzo.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.frianzo.app.data.*
import com.frianzo.app.ui.*

/**
 * Full native app (Jetpack Compose, NO WebView), built step by step:
 * Stage 1: login + feed. Stage 2: posts, reels, chat, profile,
 * notifications, search — all on the same AWS backend (api.frianzo.online).
 * NOT published to Play. Current live app is untouched.
 * Chat here uses REST polling; live socket delivery is a later stage.
 * Push notifications need a Firebase key (owner step, not included here).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cookieJar = PersistentCookieJar(applicationContext)
        val api = createApi(applicationContext, cookieJar)
        val api2 = createApi2(applicationContext, cookieJar)
        setContent {
            MaterialTheme {
                val session: SessionViewModel = viewModel(factory = sessionViewModelFactory(api))
                val state by session.state.collectAsState()
                when (state) {
                    is AuthState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    is AuthState.LoggedIn -> {
                        val user = (state as AuthState.LoggedIn).user
                        AppShell(api, api2, user?.id)
                    }
                    else -> LoginScreen(api, session) { session.refresh() }
                }
            }
        }
    }
}

private enum class Tab { FEED, REELS, CREATE, CHAT, NOTIFS, SEARCH, PROFILE }

@Composable
private fun AppShell(api: FrianzoApi, api2: FrianzoApi2, myId: String?) {
    var tab by remember { mutableStateOf(Tab.FEED) }
    var openChat by remember { mutableStateOf<Pair<String, String>?>(null) }
    var openProfile by remember { mutableStateOf<String?>(null) }
    var feedRefresh by remember { mutableStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == Tab.FEED, onClick = { tab = Tab.FEED; openChat = null; openProfile = null }, label = { Text("Feed") }, icon = { Text("🏠") })
                NavigationBarItem(selected = tab == Tab.REELS, onClick = { tab = Tab.REELS; openChat = null; openProfile = null }, label = { Text("Reels") }, icon = { Text("▶") })
                NavigationBarItem(selected = tab == Tab.CREATE, onClick = { tab = Tab.CREATE; openChat = null; openProfile = null }, label = { Text("Post") }, icon = { Text("+") })
                NavigationBarItem(selected = tab == Tab.CHAT, onClick = { tab = Tab.CHAT; openChat = null; openProfile = null }, label = { Text("Chat") }, icon = { Text("💬") })
                NavigationBarItem(selected = tab == Tab.NOTIFS, onClick = { tab = Tab.NOTIFS; openChat = null; openProfile = null }, label = { Text("Alerts") }, icon = { Text("🔔") })
                NavigationBarItem(selected = tab == Tab.SEARCH, onClick = { tab = Tab.SEARCH; openChat = null; openProfile = null }, label = { Text("Search") }, icon = { Text("🔍") })
                NavigationBarItem(selected = tab == Tab.PROFILE, onClick = { tab = Tab.PROFILE; openChat = null; openProfile = null }, label = { Text("Profile") }, icon = { Text("👤") })
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when {
                openChat != null -> ChatThreadScreen(api2, openChat!!.first, openChat!!.second, myId)
                openProfile != null -> ProfileScreen(api2, openProfile, myId)
                tab == Tab.FEED -> key(feedRefresh) { FeedScreen(api) }
                tab == Tab.REELS -> ReelsScreen(api2)
                tab == Tab.CREATE -> CreatePostScreen(api2) { feedRefresh++; tab = Tab.FEED }
                tab == Tab.CHAT -> ChatListScreen(api2) { id, name -> openChat = id to name }
                tab == Tab.NOTIFS -> NotificationsScreen(api2)
                tab == Tab.SEARCH -> SearchScreen(api2) { username -> openProfile = username }
                tab == Tab.PROFILE -> ProfileScreen(api2, null, myId)
            }
        }
    }
}
