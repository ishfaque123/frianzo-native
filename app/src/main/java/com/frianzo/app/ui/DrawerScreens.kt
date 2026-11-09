package com.frianzo.app.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.net.URL
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import coil.compose.AsyncImage
import com.frianzo.app.data.*
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ---------- Drawer destinations, exactly the live web drawer order ----------
// Settings / Your Activity / Professional Dashboard / Close Friends /
// AI Video Generator / ISAI Assistant. Every screen below calls a real,
// verified backend route (see Api2). Nothing here shows faked data: a
// failed load shows the backend error text, and a gated feature shows its
// real gate (for example AI Video referrals) instead of a fake result.

// C-B16: process-level scope for account operations (Delete Account,
// account switch) that must finish even if the user navigates away
// mid-request. rememberCoroutineScope() is cancelled with the screen,
// which left the outcome unknowable. Only these two operations use it;
// everything else stays on the composition scope.
private val accountOpsScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

// v5.18.5: theme-aware warning / success / trend colours. Amber-700
// (#B45309), emerald-700 (#047857), blue-600 (#2563EB) and red-500
// (#EF4444) are readable on a light surface but too dark on a dark one,
// so dark mode uses the 400-scale shades. (DashboardPill is NOT changed:
// its text sits on a light pill background in both themes, so the dark
// shades stay readable there.)
@Composable
private fun warningAmber(): Color = if (isSystemInDarkTheme()) Color(0xFFFBBF24) else Color(0xFFB45309)

@Composable
private fun earningGreen(): Color = if (isSystemInDarkTheme()) Color(0xFF34D399) else Color(0xFF047857)

@Composable
private fun trendColor(positive: Boolean): Color = if (isSystemInDarkTheme()) {
    if (positive) Color(0xFF60A5FA) else Color(0xFFF87171)
} else {
    if (positive) Color(0xFF2563EB) else Color(0xFFEF4444)
}

@Composable
fun DrawerScreenRouter(api2: FrianzoApi2, label: String, onBack: () -> Unit, onLogout: () -> Unit, onSwitched: () -> Unit = {}, onPostAsReel: ((android.net.Uri) -> Unit)? = null, onOpenDrawerScreen: ((String) -> Unit)? = null, initialSubScreen: String? = null, onInitialSubScreenConsumed: (() -> Unit)? = null) {
    when (label) {
        "Settings" -> SettingsScreen(api2, onBack, onLogout, onSwitched, onOpenDrawerScreen, initialSubScreen, onInitialSubScreenConsumed)
        // v5.19 (H5): a verification notification tap lands straight on
        // the Verification page (web /settings-menu/verification), not
        // the Settings list — same screen, opened at its sub-screen.
        "Verification" -> SettingsScreen(api2, onBack, onLogout, onSwitched, onOpenDrawerScreen, initialSubScreen = "verification")
        "Your Activity" -> ActivityScreen(api2, onBack)
        "Professional Dashboard" -> DashboardScreen(api2, onBack)
        "Monetization" -> MonetizationScreen(api2, onBack)
        "Close Friends" -> CloseFriendsScreen(api2, onBack)
        "AI Video Generator" -> AiVideoScreen(api2, onBack, onPostAsReel)
        "ISAI Assistant" -> IsaiScreen(api2, onBack)
        else -> DrawerScaffold(label, onBack) { Text("This screen is not available.") }
    }
}

@Composable
fun DrawerScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = FrianzoBlue, modifier = Modifier.weight(1f), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
        HorizontalDivider()
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), content = content)
    }
}

@Composable
private fun ErrorText(error: String?) {
    if (error != null) {
        Text(error, fontSize = 13.sp, color = Color.Red)
        Spacer(Modifier.height(8.dp))
    }
}

// ---------- v5.15: grouped-cards Settings rows (web settings-menu) ----------
// Card = rounded 12dp, 1dp outline, surface background; rows = 20dp icon
// + 16sp label, 12dp gap, 12dp/16dp padding, min 48dp tall, ripple on tap.
// Icon tint is onSurfaceVariant, which IS FrianzoTextSecondary (#64748B)
// in the light scheme (Theme.kt) and the dark equivalent in dark mode;
// error IS FrianzoBadgeRed (#DC2626) in the light scheme.

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) { Column(content = content) }
}

@Composable
private fun SettingsCardDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
}

@Composable
private fun SettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    labelColor: Color = MaterialTheme.colorScheme.onSurface,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 16.sp, color = labelColor, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SettingsSubRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    labelColor: Color = MaterialTheme.colorScheme.onSurface,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 32.dp, end = 16.dp, top = 12.dp, bottom = 12.dp).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 14.sp, color = labelColor, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

private fun formatMinutes(mins: Int): String {
    val h = mins / 60
    val m = mins % 60
    return when {
        h == 0 -> "${m}m"
        m == 0 -> "${h}h"
        else -> "${h}h ${m}m"
    }
}

// ---------- Settings (web: /settings-menu) ----------
// Real web sections in this screen: profile edit (display name, bio),
// Account Privacy (private account switch), Logout, Delete Account, and
// Privacy Policy / Terms of Service (opened in the browser, as on the web
// they are web pages). Saved-account switching exists on the web
// (POST /api/auth/switch) but is NOT in this native build yet: switching
// needs a native re-login flow, and it is not faked here.

// ---------- Settings (web: /settings-menu) ----------
// v3.4: Login & Security is an expandable section grouping Account
// Privacy, Edit Profile, Blocked Accounts, Devices, Theme, Data Saver,
// My Interests, Notifications and Delete Account. Each row opens a real
// screen (or a real dialog/action) — nothing here is a placeholder.

@Composable
fun SettingsScreen(api2: FrianzoApi2, onBack: () -> Unit, onLogout: () -> Unit, onSwitched: () -> Unit = {}, onOpenDrawerScreen: ((String) -> Unit)? = null, initialSubScreen: String? = null, onInitialSubScreenConsumed: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // v2.9: in-settings sub-screens (My Interests, Verification).
    // C-B18: subScreen survives rotation.
    // v5.19 (H5): initialSubScreen lets a caller (notification tap)
    // open straight at a sub-screen, e.g. "verification".
    var subScreen by rememberSaveable { mutableStateOf(initialSubScreen) }
    // v5.19 (FCM): consume the initial sub-screen once, so Back to the
    // Settings list never re-opens Verification.
    LaunchedEffect(initialSubScreen) {
        if (initialSubScreen != null) {
            subScreen = initialSubScreen
            onInitialSubScreenConsumed?.invoke()
        }
    }
    // C-B16: true while a Delete Account request is in flight; Back is
    // swallowed until it finishes, so the outcome stays knowable.
    var deleteBusy by remember { mutableStateOf(false) }
    // C-B2: system Back from a sub-screen returns to the Settings list,
    // not to Feed. Registered before the early returns below so it is
    // active while a sub-screen is shown. The delete handler is
    // registered after it, so it wins while a delete is in flight.
    BackHandler(enabled = subScreen != null) { subScreen = null }
    BackHandler(enabled = deleteBusy) { /* delete in flight — wait for the result */ }
    if (subScreen == "interests") { MyInterestsScreen(api2, onBack = { subScreen = null }); return }
    if (subScreen == "verification") { VerificationScreen(api2, onBack = { subScreen = null }); return }
    if (subScreen == "privacy") { AccountPrivacyScreen(api2, onBack = { subScreen = null }); return }
    if (subScreen == "muted") { MutedStoriesScreen(api2, onBack = { subScreen = null }); return }
    if (subScreen == "blocked") { BlockedAccountsScreen(api2, onBack = { subScreen = null }); return }
    if (subScreen == "devices") { DevicesScreen(api2, onBack = { subScreen = null }); return }
    if (subScreen == "notifprefs") { NotificationPrefsScreen(api2, onBack = { subScreen = null }); return }
    var profile by remember { mutableStateOf<PublicProfile?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var displayName by rememberSaveable { mutableStateOf("") }
    var bio by rememberSaveable { mutableStateOf("") }
    var isPrivate by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showLogout by remember { mutableStateOf(false) }
    var showAddAccount by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }
    var editError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    // C-B12: true while the Private switch auto-save is in flight.
    var privateSaving by remember { mutableStateOf(false) }
    // v2.9: saved accounts on this device (GET /api/auth/accounts).
    var savedAccounts by remember { mutableStateOf<List<SavedAccount>>(emptyList()) }
    var switchingId by remember { mutableStateOf<String?>(null) }
    // v3.4: Login & Security section (expanded by default) + local prefs.
    // C-B18: expanded state survives rotation.
    var loginSecurityExpanded by rememberSaveable { mutableStateOf(true) }
    var themeMode by remember { mutableStateOf(readThemeMode(context)) }
    var dataSaver by remember { mutableStateOf(readDataSaver(context)) }
    fun load() {
        // C-B23: reset so Retry re-runs a clean load.
        loading = true; error = null
        scope.launch {
            try {
                val res = api2.myProfile()
                if (res.success && res.data?.user != null) {
                    profile = res.data.user
                    displayName = res.data.user.displayName ?: ""
                    bio = res.data.user.bio ?: ""
                    isPrivate = res.data.user.isPrivate
                } else error = res.error?.message ?: "Could not load profile"
            } catch (e: Exception) { error = e.message }
            loading = false
        }
        scope.launch {
            try { val r = api2.savedAccounts(); if (r.success && r.data?.accounts != null) savedAccounts = r.data.accounts } catch (_: Exception) {}
        }
    }
    LaunchedEffect(Unit) { load() }
    // v5.18.4 (A-H3): Settings → Edit Profile opens the FULL
    // EditProfileScreen (Screens2.kt — username + 30-day locks, gender,
    // cascading Country→Province→City, website, phone, avatar/cover),
    // as a full screen like the other sub-screens — NOT the cut-down
    // dialog below (kept untouched; nothing sets showEdit any more).
    // The profile is the one Settings already loaded via
    // api2.myProfile() in load(); if it never loaded, fall through to
    // the Settings list, which shows the error + Retry.
    if (subScreen == "editprofile" && profile != null) {
        EditProfileScreen(api2, profile!!, onClose = { subScreen = null }, onSaved = { updated -> profile = updated; subScreen = null }, onProfileChanged = { updated -> profile = updated })
        return
    }
    DrawerScaffold("Settings", onBack) {
      Box(Modifier.weight(1f)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (loading) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { DotsLoading() }
        } else {
            ErrorText(error)
            // C-B23: retry the profile load (only while it never
            // succeeded — a later error may be a failed Save, and
            // reloading then would discard the user's edits).
            if (error != null && profile == null) { TextButton(onClick = { load() }) { Text("Retry") } }
            if (notice != null) { Text(notice!!, fontSize = 13.sp, color = FrianzoBlue); Spacer(Modifier.height(8.dp)) }
            Text("Profile", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
            Text("@${profile?.username ?: ""}", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = displayName, onValueChange = { displayName = it }, label = { Text("Display name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = bio, onValueChange = { if (it.length <= 300) bio = it }, label = { Text("Bio") }, modifier = Modifier.fillMaxWidth())
            Text("${bio.length}/300", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            Spacer(Modifier.height(12.dp))
            Text("Account Privacy", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Private account", fontSize = 14.sp)
                    Text("Only people who follow you can see your posts.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // C-B12: the toggle saves immediately (like the comments
                // switch) and rolls back on failure — it is no longer
                // silently lost when leaving without pressing Save.
                Switch(modifier = Modifier.padding(vertical = 8.dp), checked = isPrivate, enabled = !privateSaving, onCheckedChange = { checked ->
                    val prev = isPrivate
                    isPrivate = checked
                    privateSaving = true
                    scope.launch {
                        try {
                            val r = api2.updateMe(mapOf("isPrivate" to checked))
                            if (r.success) profile = profile?.copy(isPrivate = checked)
                            else { isPrivate = prev; error = r.error?.message ?: "Could not update private account setting" }
                        } catch (e: Exception) { isPrivate = prev; error = e.message }
                        privateSaving = false
                    }
                })
            }
            Spacer(Modifier.height(8.dp))
            // C-B7: Save stays off until the profile actually loaded, so
            // a failed load can no longer wipe the name/bio with "".
            Button(enabled = !saving && profile != null, onClick = {
                saving = true
                scope.launch {
                    try {
                        val res = api2.updateMe(mapOf("displayName" to displayName, "bio" to bio, "isPrivate" to isPrivate))
                        // C-B7: keep the local profile in step, so the
                        // Edit Profile dialog (which reads `profile`)
                        // shows the new name instead of the old one.
                        if (res.success) { profile = profile?.copy(displayName = displayName, bio = bio, isPrivate = isPrivate); notice = "Profile saved." } else error = res.error?.message ?: "Could not save profile"
                    } catch (e: Exception) { error = e.message }
                    saving = false
                }
            }, modifier = Modifier.fillMaxWidth()) { Text(if (saving) "Saving..." else "Save", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
            Spacer(Modifier.height(16.dp))
            // v5.15: grouped cards (web settings-menu/page.tsx). Card 1 =
            // the account rows + the Login & Security accordion + the
            // saved-account switcher. Card 2 = Logout. Card 3 = the legal
            // pages. Only the layout changed: every row below keeps its
            // original destination / action.
            // NOTE (verified 2026-11-07): there is NO Admin row in this
            // build — PublicProfile has no role/admin field — so no Admin
            // row is shown. Fingerprint Login is also NOT shown: no
            // biometric bridge exists in the app to report on, so a
            // toggle would be fake. v5.18.4 (A-H1): Monetization now has
            // its own screen (MonetizationScreen, via the router below).
            SettingsCard {
                SettingsRow(Icons.Filled.BarChart, "Professional Dashboard") { onOpenDrawerScreen?.invoke("Professional Dashboard") }
                SettingsCardDivider()
                SettingsRow(Icons.Filled.MonetizationOn, "Monetization") { onOpenDrawerScreen?.invoke("Monetization") }
                SettingsCardDivider()
                SettingsRow(Icons.Filled.Verified, "Verification") { subScreen = "verification" }
                SettingsCardDivider()
                SettingsRow(Icons.Filled.History, "Your Activity") { onOpenDrawerScreen?.invoke("Your Activity") }
                SettingsCardDivider()
                SettingsRow(Icons.Filled.Star, "Close Friends") { onOpenDrawerScreen?.invoke("Close Friends") }
                SettingsCardDivider()
                SettingsRow(Icons.Filled.VisibilityOff, "Muted Stories") { subScreen = "muted" }
                SettingsCardDivider()
                // v3.4: Login & Security — expandable, groups the account rows.
                Row(Modifier.fillMaxWidth().clickable { loginSecurityExpanded = !loginSecurityExpanded }.padding(horizontal = 16.dp, vertical = 12.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Security, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Login & Security", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    Icon(if (loginSecurityExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = if (loginSecurityExpanded) "Collapse" else "Expand", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
                if (loginSecurityExpanded) {
                    // Indented sub-list on a surfaceVariant background.
                    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)) {
                        SettingsSubRow(Icons.Filled.Lock, "Account Privacy") { subScreen = "privacy" }
                        SettingsCardDivider()
                        SettingsSubRow(Icons.Filled.Edit, "Edit Profile") { subScreen = "editprofile" }
                        SettingsCardDivider()
                        SettingsSubRow(Icons.Filled.Block, "Blocked Accounts") { subScreen = "blocked" }
                        SettingsCardDivider()
                        SettingsSubRow(Icons.Filled.Devices, "Devices") { subScreen = "devices" }
                        SettingsCardDivider()
                        Text("Theme", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().padding(start = 32.dp, end = 16.dp, top = 12.dp))
                        listOf(THEME_MODE_SYSTEM to "System", THEME_MODE_LIGHT to "Light", THEME_MODE_DARK to "Dark").forEach { (v, label) ->
                            Row(Modifier.fillMaxWidth().clickable { themeMode = v; saveThemeMode(context, v) }.padding(start = 32.dp, end = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = themeMode == v, onClick = { themeMode = v; saveThemeMode(context, v) })
                                Text(label, fontSize = 14.sp)
                            }
                        }
                        SettingsCardDivider()
                        Row(Modifier.fillMaxWidth().padding(start = 32.dp, end = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Data Saver", fontSize = 14.sp)
                                Text("Use less mobile data.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(modifier = Modifier.padding(vertical = 8.dp), checked = dataSaver, onCheckedChange = { dataSaver = it; saveDataSaver(context, it) })
                        }
                        SettingsCardDivider()
                        SettingsSubRow(Icons.Filled.Interests, "My Interests") { subScreen = "interests" }
                        SettingsCardDivider()
                        SettingsSubRow(Icons.Filled.Notifications, "Notifications") { subScreen = "notifprefs" }
                        SettingsCardDivider()
                        SettingsSubRow(Icons.Filled.Delete, "Delete Account", labelColor = MaterialTheme.colorScheme.error, iconTint = MaterialTheme.colorScheme.error) { if (!deleteBusy) showDelete = true }
                    }
                }
                SettingsCardDivider()
                // v2.9: switch between accounts saved on this device
                // (POST /api/auth/switch). The new auth cookie is set by the
                // server; onSwitched refreshes the session to the new user.
                if (savedAccounts.isEmpty()) {
                    Text("No saved accounts on this device yet.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp))
                }
                savedAccounts.forEachIndexed { index, acc ->
                    if (index > 0) SettingsCardDivider()
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        FrianzoAvatar(acc.profilePictureUrl, 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(acc.displayName ?: acc.username ?: "User", fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            Text("@${acc.username ?: ""}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        }
                        val isCurrent = acc.id != null && acc.id == profile?.id
                        if (isCurrent) Text("Current", fontSize = 12.sp, color = FrianzoBlue, maxLines = 1, softWrap = false)
                        else if (acc.id != null) TextButton(enabled = switchingId != acc.id, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp), onClick = {
                            switchingId = acc.id
                            // C-B16: the switch runs on the app scope, so
                            // leaving Settings mid-switch cannot cancel it
                            // with an ambiguous outcome.
                            accountOpsScope.launch {
                                try {
                                    val r = api2.switchAccount(mapOf("accountId" to acc.id))
                                    if (r.success) onSwitched() else error = r.error?.message ?: "Could not switch account"
                                } catch (e: Exception) { error = e.message }
                                switchingId = null
                            }
                        }) { Text("Switch", maxLines = 1, softWrap = false) }
                        // C-D1: a saved account with no id cannot be switched
                        // to — say so instead of showing a dead row.
                        else Text("Can't switch", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                    }
                }
                if (savedAccounts.isNotEmpty()) SettingsCardDivider()
                SettingsRow(Icons.Filled.PersonAdd, "Add Account") { showAddAccount = true }
            }
            Spacer(Modifier.height(16.dp))
            // Card 2: Logout (opens the confirm modal below).
            SettingsCard {
                SettingsRow(Icons.Filled.PowerSettingsNew, "Logout", labelColor = MaterialTheme.colorScheme.error, iconTint = MaterialTheme.colorScheme.error) { showLogout = true }
            }
            Spacer(Modifier.height(16.dp))
            // Card 3: legal pages (opened in the browser, as on the web).
            SettingsCard {
                SettingsRow(Icons.Filled.PrivacyTip, "Privacy Policy") {
                    // C-B20: no browser on the device must not crash the app.
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://frianzo.online/privacy"))
                        context.startActivity(intent)
                    } catch (e: Exception) { error = "Could not open Privacy Policy — no browser found on this device." }
                }
                SettingsCardDivider()
                SettingsRow(Icons.Filled.Description, "Terms of Service") {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://frianzo.online/terms"))
                        context.startActivity(intent)
                    } catch (e: Exception) { error = "Could not open Terms of Service — no browser found on this device." }
                }
            }
        }
        }
      }
    }
    // v3.4: Edit Profile from Settings (same dialog as the profile screen).
    if (showEdit && profile != null) {
        val cur = profile!!
        var edName by remember { mutableStateOf(cur.displayName ?: "") }
        var edBio by remember { mutableStateOf(cur.bio ?: "") }
        var edCity by remember { mutableStateOf(cur.city ?: "") }
        var edProvince by remember { mutableStateOf(cur.province ?: "") }
        var edCountry by remember { mutableStateOf(cur.country ?: "") }
        var edWeb by remember { mutableStateOf(cur.website ?: "") }
        // C-B15: double-submit guard for Save Changes.
        var edSaving by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showEdit = false },
            title = { Text("Edit profile") },
            text = {
                Column {
                    OutlinedTextField(value = edName, onValueChange = { edName = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = edBio, onValueChange = { if (it.length <= 300) edBio = it }, label = { Text("Bio") }, modifier = Modifier.fillMaxWidth())
                    Text("${edBio.length}/300", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = edCity, onValueChange = { edCity = it }, label = { Text("City") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = edProvince, onValueChange = { edProvince = it }, label = { Text("Province") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = edCountry, onValueChange = { edCountry = it }, label = { Text("Country") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = edWeb, onValueChange = { edWeb = it }, label = { Text("Website") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    editError?.let { Spacer(Modifier.height(8.dp)); Text(it, fontSize = 13.sp, color = Color.Red) }
                }
            },
            confirmButton = {
                Button(enabled = !edSaving, onClick = {
                    if (edSaving) return@Button
                    edSaving = true
                    scope.launch {
                        try {
                            val body = mapOf<String, Any>("displayName" to edName, "bio" to edBio, "city" to edCity, "province" to edProvince, "country" to edCountry, "website" to edWeb)
                            val r = api2.updateMe(body)
                            if (r.success) { profile = cur.copy(displayName = edName, bio = edBio, city = edCity, province = edProvince, country = edCountry, website = edWeb); displayName = edName; bio = edBio; showEdit = false; editError = null; notice = "Profile saved." }
                            else editError = r.error?.message ?: "Could not save profile"
                        } catch (e: Exception) { editError = e.message }
                        edSaving = false
                    }
                }, modifier = Modifier.fillMaxWidth().height(FrianzoUi.ButtonHeight), shape = RoundedCornerShape(FrianzoUi.ButtonRadius), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A))) { Text(if (edSaving) "Saving..." else "Save Changes", maxLines = 1, softWrap = false) }
            },
            dismissButton = { TextButton(onClick = { showEdit = false }) { Text("Cancel") } },
        )
    }
    // v3.4: Add Account — there is no second-account login inside the app,
    // so this takes the user to the real login screen (current session is
    // signed out first; saved accounts stay on this device).
    if (showAddAccount) {
        AlertDialog(
            onDismissRequest = { showAddAccount = false },
            title = { Text("Add account") },
            text = { Text("You'll be signed out of this account and taken to the login screen, where you can sign in with another account. Your saved accounts stay on this device.") },
            confirmButton = { TextButton(onClick = { showAddAccount = false; onLogout() }) { Text("Continue to login") } },
            dismissButton = { TextButton(onClick = { showAddAccount = false }) { Text("Cancel") } },
        )
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Delete Account", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
            text = { Text("This permanently removes your profile, posts, and messages. This cannot be undone.") },
            // v2.9 FIX: only log out when the delete actually succeeded.
            // On failure show the error and STAY logged in.
            confirmButton = {
                TextButton(enabled = !deleteBusy, onClick = {
                    showDelete = false
                    deleteBusy = true
                    // C-B16: the delete runs on the app scope, so
                    // navigating away cannot cancel it mid-request.
                    // Logout still happens ONLY on success.
                    accountOpsScope.launch {
                        try {
                            val r = api2.deleteMe()
                            if (r.success) onLogout()
                            else error = r.error?.message ?: "Could not delete account. Please try again."
                        } catch (e: Exception) { error = e.message ?: "Could not delete account. Please try again." }
                        deleteBusy = false
                    }
                }) { Text("Delete", color = Color.Red, maxLines = 1, softWrap = false) }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Text("Cancel") } },
        )
    }
    // v5.15: Logout confirm — centred modal, RED FILLED "Log out" button
    // + bordered Cancel. (No such confirm existed before in Settings;
    // the drawer keeps logging out directly, with no confirm, as now.)
    if (showLogout) {
        AlertDialog(
            onDismissRequest = { showLogout = false },
            title = { Text("Log out", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
            text = { Text("Are you sure you want to log out?") },
            confirmButton = {
                Button(onClick = { showLogout = false; onLogout() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = Color.White), shape = RoundedCornerShape(12.dp)) { Text("Log out", maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                OutlinedButton(onClick = { showLogout = false }, shape = RoundedCornerShape(12.dp)) { Text("Cancel", maxLines = 1, softWrap = false) }
            },
        )
    }
}

// ---------- Account Privacy (v3.4) ----------
// Web: /settings-menu/privacy. Every field is saved on the one endpoint
// that accepts it: PATCH /api/users/me (isPrivate, showOnlineStatus,
// messagePermission, tagPermission, phone) + PATCH
// /api/comments/settings/toggle { disabled } for the comments switch.

@Composable
private fun PermissionChoice(title: String, value: String, onValue: (String) -> Unit) {
    Column {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        listOf("everyone" to "Everyone", "followers" to "Followers", "none" to "No one").forEach { (v, label) ->
            Row(Modifier.fillMaxWidth().clickable { onValue(v) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = value == v, onClick = { onValue(v) })
                Text(label, fontSize = 14.sp)
            }
        }
    }
}

@Composable
fun AccountPrivacyScreen(api2: FrianzoApi2, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var isPrivate by remember { mutableStateOf(false) }
    var showOnlineStatus by remember { mutableStateOf(true) }
    var messagePermission by remember { mutableStateOf("everyone") }
    var tagPermission by remember { mutableStateOf("everyone") }
    var phone by rememberSaveable { mutableStateOf("") }
    var commentsDisabled by remember { mutableStateOf(false) }
    var commentsSaving by remember { mutableStateOf(false) }
    // C-B23: true once the settings loaded — Retry only shows while the
    // first load never succeeded, so it cannot discard the user's edits.
    var privacyLoaded by remember { mutableStateOf(false) }
    // C-B12: true while a toggle/radio auto-save is in flight.
    var privacySaving by remember { mutableStateOf(false) }
    fun load() {
        loading = true; error = null
        scope.launch {
            try {
                val r = api2.myProfile()
                val u = r.data?.user
                if (r.success && u != null) {
                    isPrivate = u.isPrivate
                    showOnlineStatus = u.showOnlineStatus ?: true
                    messagePermission = u.messagePermission ?: "everyone"
                    tagPermission = u.tagPermission ?: "everyone"
                    phone = u.phone ?: ""
                    commentsDisabled = u.commentsDisabled ?: false
                    privacyLoaded = true
                } else error = r.error?.message ?: "Could not load privacy settings"
            } catch (e: Exception) { error = e.message }
            loading = false
        }
    }
    // C-B12: discrete choices (toggles, radios) save immediately, like
    // the comments switch, and roll back on failure — leaving without
    // pressing Save no longer loses them. The phone field still saves
    // with the Save button (it is free text, not a discrete choice).
    fun persistPrivacy(changed: Map<String, Any>, rollback: () -> Unit) {
        if (privacySaving) return
        privacySaving = true
        scope.launch {
            try {
                val r = api2.updateMe(changed)
                if (!r.success) { rollback(); error = r.error?.message ?: "Could not save privacy settings" }
            } catch (e: Exception) { rollback(); error = e.message }
            privacySaving = false
        }
    }
    LaunchedEffect(Unit) { load() }
    DrawerScaffold("Account Privacy", onBack) {
      Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (loading) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { DotsLoading() }
        } else {
            ErrorText(error)
            if (error != null && !privacyLoaded) { TextButton(onClick = { load() }) { Text("Retry") } }
            if (notice != null) { Text(notice!!, fontSize = 13.sp, color = FrianzoBlue); Spacer(Modifier.height(8.dp)) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Private account", fontSize = 14.sp)
                    Text("Only people who follow you can see your posts.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = isPrivate, enabled = !privacySaving, onCheckedChange = { checked ->
                    val prev = isPrivate
                    isPrivate = checked
                    persistPrivacy(mapOf("isPrivate" to checked)) { isPrivate = prev }
                })
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Show online status", fontSize = 14.sp)
                    Text("Let others see when you're active.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = showOnlineStatus, enabled = !privacySaving, onCheckedChange = { checked ->
                    val prev = showOnlineStatus
                    showOnlineStatus = checked
                    persistPrivacy(mapOf("showOnlineStatus" to checked)) { showOnlineStatus = prev }
                })
            }
            Spacer(Modifier.height(16.dp))
            PermissionChoice("Who can message you", messagePermission) { v ->
                if (privacySaving) return@PermissionChoice
                val prev = messagePermission
                messagePermission = v
                persistPrivacy(mapOf("messagePermission" to v)) { messagePermission = prev }
            }
            Spacer(Modifier.height(16.dp))
            PermissionChoice("Who can tag you", tagPermission) { v ->
                if (privacySaving) return@PermissionChoice
                val prev = tagPermission
                tagPermission = v
                persistPrivacy(mapOf("tagPermission" to v)) { tagPermission = prev }
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(value = phone, onValueChange = { if (it.length <= 30) phone = it }, label = { Text("WhatsApp number") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Allow comments", fontSize = 14.sp)
                    Text("Turn off to stop comments on your posts.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = !commentsDisabled, enabled = !commentsSaving, onCheckedChange = { allow ->
                    val prev = commentsDisabled
                    commentsDisabled = !allow
                    commentsSaving = true
                    scope.launch {
                        try {
                            val r = api2.toggleComments(mapOf("disabled" to !allow))
                            if (!r.success) { commentsDisabled = prev; error = r.error?.message ?: "Could not update comments setting" }
                        } catch (e: Exception) { commentsDisabled = prev; error = e.message }
                        commentsSaving = false
                    }
                })
            }
            Spacer(Modifier.height(16.dp))
            Button(enabled = !saving, onClick = {
                saving = true
                scope.launch {
                    try {
                        val body = mapOf<String, Any>(
                            "isPrivate" to isPrivate,
                            "showOnlineStatus" to showOnlineStatus,
                            "messagePermission" to messagePermission,
                            "tagPermission" to tagPermission,
                            "phone" to phone,
                        )
                        val r = api2.updateMe(body)
                        if (r.success) notice = "Privacy settings saved." else error = r.error?.message ?: "Could not save privacy settings"
                    } catch (e: Exception) { error = e.message }
                    saving = false
                }
            }, modifier = Modifier.fillMaxWidth().height(FrianzoUi.ButtonHeight), shape = RoundedCornerShape(FrianzoUi.ButtonRadius)) { Text(if (saving) "Saving..." else "Save", maxLines = 1, softWrap = false) }
        }
      }
    }
}

// ---------- Muted Stories (v3.4) ----------
// GET /api/statuses/muted -> { muted: [user...] }; unmute is the existing
// DELETE /api/statuses/mute/{userId}.

@Composable
fun MutedStoriesScreen(api2: FrianzoApi2, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var users by remember { mutableStateOf<List<MutedUser>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    fun load() {
        loading = true; error = null
        scope.launch {
            try {
                val r = api2.mutedStories()
                if (r.success && r.data?.muted != null) users = r.data.muted else error = r.error?.message ?: "Could not load muted stories"
            } catch (e: Exception) { error = e.message }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }
    DrawerScaffold("Muted Stories", onBack) {
        Text("Stories from these people are hidden from your feed.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        if (loading) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { DotsLoading() }
        } else {
            ErrorText(error)
            if (error != null) { TextButton(onClick = { load() }) { Text("Retry") } }
            if (users.isEmpty() && error == null) Text("No muted stories.")
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            // C-B22: stable unique keys even when id/username are null
            // (the old `it.id ?: it.username ?: ""` key collided on "").
            itemsIndexed(users, key = { index, u -> u.id ?: "muted-${u.username ?: "?"}-$index" }) { _, u ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FrianzoAvatar(u.profilePictureUrl, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(u.displayName ?: u.username ?: "User", fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        Text("@${u.username ?: ""}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    }
                    // C-B13: a failed Unmute now shows the error instead
                    // of silently doing nothing. C-B22: a row with no id
                    // has no action — say so instead of showing a dead row.
                    if (u.id != null) TextButton(onClick = { scope.launch { try { val r = api2.unmuteStories(u.id); if (r.success) users = users.filter { it.id != u.id } else error = r.error?.message ?: "Could not unmute" } catch (e: Exception) { error = e.message } } }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Text("Unmute", maxLines = 1, softWrap = false) }
                    else Text("Unavailable", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

// ---------- Blocked Accounts (v3.4, moved out of the flat Settings page) ----------

@Composable
fun BlockedAccountsScreen(api2: FrianzoApi2, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var blocked by remember { mutableStateOf<List<BlockedEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    fun load() {
        loading = true; error = null
        scope.launch {
            try {
                val r = api2.blockedUsers()
                if (r.success && r.data?.blocked != null) blocked = r.data.blocked else error = r.error?.message ?: "Could not load blocked accounts"
            } catch (e: Exception) { error = e.message }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }
    DrawerScaffold("Blocked Accounts", onBack) {
        if (loading) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { DotsLoading() }
        } else {
            ErrorText(error)
            if (error != null) { TextButton(onClick = { load() }) { Text("Retry") } }
            if (blocked.isEmpty() && error == null) Text("Nobody blocked.")
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            // C-B22: stable unique keys even when the user id is null
            // (the old `it.user?.id ?: ""` key collided on "").
            itemsIndexed(blocked, key = { index, entry -> entry.user?.id ?: "blocked-${entry.user?.username ?: "?"}-$index" }) { _, entry ->
                val u = entry.user
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FrianzoAvatar(u?.profilePictureUrl, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(u?.displayName ?: u?.username ?: "User", fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    // C-B13: a failed Unblock now shows the error instead
                    // of silently doing nothing. C-B22: a row with no id
                    // has no action — say so instead of showing a dead row.
                    if (u?.id != null) TextButton(onClick = { scope.launch { try { val r = api2.unblockUser(u.id); if (r.success) blocked = blocked.filter { it.user?.id != u.id } else error = r.error?.message ?: "Could not unblock" } catch (e: Exception) { error = e.message } } }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Text("Unblock", maxLines = 1, softWrap = false) }
                    else Text("Unavailable", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

// ---------- Devices (v3.4, moved out of the flat Settings page) ----------

@Composable
fun DevicesScreen(api2: FrianzoApi2, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<DeviceItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    fun load() {
        loading = true; error = null
        scope.launch {
            try {
                val r = api2.devices()
                if (r.success && r.data != null) devices = r.data.devices ?: emptyList() else error = r.error?.message ?: "Could not load devices"
            } catch (e: Exception) { error = e.message }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }
    DrawerScaffold("Devices", onBack) {
        if (loading) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { DotsLoading() }
        } else {
            // C-B11: the list scrolls — with many devices the rows below
            // the fold (and "Revoke all other devices") were unreachable.
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ErrorText(error)
            if (error != null) { TextButton(onClick = { load() }) { Text("Retry") } }
            if (devices.isEmpty() && error == null) Text("No other devices.")
            devices.forEach { d ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text((d.label ?: "Device") + if (d.isCurrent) " (this device)" else "", fontSize = 14.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(d.location, d.lastActiveAt?.take(10)).joinToString(" · "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    }
                    // C-B13: a failed Revoke now shows the error instead
                    // of silently doing nothing.
                    if (!d.isCurrent && d.id != null) TextButton(onClick = { scope.launch { try { val r = api2.revokeDevice(d.id); if (r.success) devices = devices.filter { it.id != d.id } else error = r.error?.message ?: "Could not revoke device" } catch (e: Exception) { error = e.message } } }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Text("Revoke", maxLines = 1, softWrap = false) }
                }
            }
            if (devices.size > 1) {
                TextButton(onClick = { scope.launch { try { val r = api2.revokeOtherDevices(); if (r.success) devices = devices.filter { it.isCurrent } else error = r.error?.message ?: "Could not revoke devices" } catch (e: Exception) { error = e.message } } }, modifier = Modifier.fillMaxWidth()) { Text("Revoke all other devices", modifier = Modifier.fillMaxWidth(), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
            }
            }
        }
    }
}

// ---------- Notification preferences (v3.4, moved out of the flat Settings page) ----------

@Composable
fun NotificationPrefsScreen(api2: FrianzoApi2, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var prefs by remember { mutableStateOf<List<NotificationPref>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    fun load() {
        loading = true; error = null
        scope.launch {
            try {
                val r = api2.notificationPrefs()
                if (r.success && r.data?.preferences != null) prefs = r.data.preferences else error = r.error?.message ?: "Could not load notification settings"
            } catch (e: Exception) { error = e.message }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }
    DrawerScaffold("Notifications", onBack) {
        if (loading) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { DotsLoading() }
        } else {
            // C-B11: the list scrolls — with many categories the rows
            // below the fold were unreachable.
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ErrorText(error)
            if (error != null) { TextButton(onClick = { load() }) { Text("Retry") } }
            if (prefs.isEmpty() && error == null) Text("Notification settings could not be loaded.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            prefs.forEach { pref ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(pref.category.replaceFirstChar { it.uppercase() }, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    Switch(checked = pref.enabled, onCheckedChange = { checked ->
                        // C-B5: roll back on failure, like the comments
                        // switch — the toggle no longer lies about saving.
                        val prev = prefs
                        prefs = prefs.map { if (it.category == pref.category) it.copy(enabled = checked) else it }
                        scope.launch {
                            try {
                                val r = api2.updateNotificationPref(mapOf("category" to pref.category, "enabled" to checked))
                                if (!r.success) { prefs = prev; error = r.error?.message ?: "Could not update notification setting" }
                            } catch (e: Exception) { prefs = prev; error = e.message }
                        }
                    })
                }
            }
            }
        }
    }
}

// ---------- My Interests (v2.9) ----------
// GET /api/users/interests returns the 12 fixed interests; PUT
// /api/users/me/interests { interests: [...] } saves them and returns the
// saved list.
// C-B4 (backend check 2026-11-06, vynzo-backend main): GET returns ONLY
// the available list — userController.getInterests sends
// { interests: AVAILABLE_INTERESTS } and nothing else, PUT replaces the
// whole saved list (prisma.user.update data.interests = valid), and
// toPrivateProfile (GET /api/users/me) does NOT include interests. So
// there is NO endpoint that returns the user's current selections and no
// Api2 caller to add. NEEDS-BACKEND: the backend must return the current
// selections (e.g. a `selected` field on GET /api/users/interests, or
// `interests` on GET /api/users/me) before this screen can pre-select
// them. Until then the honest app-side part is the warning below: saving
// replaces everything previously saved.
@Composable
fun MyInterestsScreen(api2: FrianzoApi2, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var available by remember { mutableStateOf<List<String>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    // v5.18.4 (A-H2): true ONLY once the server has actually returned the
    // user's current selections. Until then Save stays DISABLED — saving
    // blind would REPLACE all previously saved interests (PUT replaces
    // the whole list). The load below reads them from GET /api/users/me
    // (PublicProfile.interests); VERIFIED 2026-11-08 in vynzo-backend
    // userService.toPrivateProfile: the field is NOT returned, so this
    // stays false and Save stays off. Backend gap: toPrivateProfile
    // must return `interests` (the DB column exists, PUT already saves
    // it). If the backend adds the field, this screen starts working
    // with no further app change — the selections pre-load and Save
    // enables, exactly like the web interests page.
    var loadedSelected by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    fun load() {
        loading = true; error = null; loadedSelected = false; selected = emptySet()
        scope.launch {
            try {
                val r = api2.interests()
                if (r.success && r.data?.interests != null) available = r.data.interests else error = r.error?.message ?: "Could not load interests"
            } catch (e: Exception) { error = e.message }
            loading = false
        }
        scope.launch {
            try {
                val p = api2.myProfile()
                val saved = p.data?.user?.interests
                if (p.success && saved != null) { selected = saved.toSet(); loadedSelected = true }
            } catch (_: Exception) { /* selections stay unknown — Save stays disabled */ }
        }
    }
    LaunchedEffect(Unit) { load() }
    DrawerScaffold("My Interests", onBack) {
      Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("Pick what you like. Frianzo uses this to show you more of it.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        // v5.18.4 (A-H2): the old amber text warned that saving replaces
        // everything — and then let the user do exactly that. Now the
        // warning is honest AND enforced: until the server returns the
        // current selections, Save is disabled, so nothing saved earlier
        // can be wiped by accident.
        if (loadedSelected) {
            Text("Your current interests are selected below — change them and save.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("Your saved interests could not be loaded, so Save is turned off. Saving now would erase the interests you saved before. This needs a server fix before it can work.", fontSize = 13.sp, color = warningAmber())
        }
        Spacer(Modifier.height(12.dp))
        if (loading) { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { DotsLoading() } }
        else {
            ErrorText(error)
            if (error != null) { TextButton(onClick = { load() }) { Text("Retry") } }
            if (notice != null) { Text(notice!!, fontSize = 13.sp, color = FrianzoBlue); Spacer(Modifier.height(8.dp)) }
            // Chips in rows of 3 (a simple wrap; no flow-layout dep).
            Column(Modifier.fillMaxWidth()) {
                available.chunked(3).forEach { chunk ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        chunk.forEach { interest ->
                            val sel = interest in selected
                            Box(Modifier.weight(1f, fill = false).clip(RoundedCornerShape(28.dp)).background(if (sel) FrianzoBlue else MaterialTheme.colorScheme.surfaceVariant).clickable { selected = if (sel) selected - interest else selected + interest }.padding(horizontal = 14.dp, vertical = 8.dp).heightIn(min = 48.dp)) {
                                Text(interest.replaceFirstChar { it.uppercase() }, color = if (sel) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            // v5.18.4 (A-H2): Save is enabled only once the current
            // selections were loaded (loadedSelected) — a blind save
            // would wipe previously saved interests. See the note above.
            Button(enabled = !saving && loadedSelected, onClick = {
                saving = true
                scope.launch {
                    try {
                        val r = api2.updateMyInterests(mapOf("interests" to selected.toList()))
                        if (r.success && r.data?.interests != null) { selected = r.data.interests.toSet(); notice = "Interests saved." } else error = r.error?.message ?: "Could not save interests"
                    } catch (e: Exception) { error = e.message }
                    saving = false
                }
            }, modifier = Modifier.fillMaxWidth()) { Text(if (saving) "Saving..." else "Save interests", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
        }
      }
    }
}

// Renders a verification requirement `current`/`required` value, which the
// backend sends as a number (25) or a string ("Complete").
private fun verificationValue(v: com.google.gson.JsonElement?): String {
    if (v == null || v.isJsonNull) return "-"
    if (!v.isJsonPrimitive) return v.toString()
    val prim = v.asJsonPrimitive
    return when {
        prim.isNumber -> { val d = prim.asDouble; if (d % 1.0 == 0.0) "${d.toInt()}" else "$d" }
        prim.isString -> prim.asString
        prim.isBoolean -> if (prim.asBoolean) "Yes" else "No"
        else -> prim.toString()
    }
}

// ---------- Verification (v2.9) ----------
// GET /api/users/me/verification-request returns the latest request (if
// any) plus eligibility: 6 requirements — referrals 25, followers 100,
// account age 30 days, posts 5, profile Photo+Bio, clean record (no
// violations in 90 days). POST with { reason } only works when eligible;
// the backend rejects it otherwise (403), so Submit stays off until then.
@Composable
fun VerificationScreen(api2: FrianzoApi2, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<VerificationData?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var reason by rememberSaveable { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    fun load() {
        loading = true; error = null
        scope.launch {
            try {
                val r = api2.verificationRequest()
                if (r.success && r.data != null) data = r.data else error = r.error?.message ?: "Could not load verification"
            } catch (e: Exception) { error = e.message }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }
    DrawerScaffold("Verification", onBack) {
      Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("The blue tick is for genuine, established accounts. Check the requirements below, then tell us why you should be verified.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        if (loading) { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { DotsLoading() } }
        else {
            ErrorText(error)
            if (error != null) { TextButton(onClick = { load() }) { Text("Retry") } }
            if (notice != null) { Text(notice!!, fontSize = 13.sp, color = FrianzoBlue); Spacer(Modifier.height(8.dp)) }
            val eligibility = data?.eligibility
            val request = data?.request
            if (eligibility?.isVerified == true) {
                Text("You are verified.", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = FrianzoBlue)
                Spacer(Modifier.height(12.dp))
            }
            if (request != null) {
                Text("Your request: ${request.status ?: ""}", fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                request.reason?.let { Text(it, fontSize = 13.sp) }
                request.adminNote?.let { Text("Note from Frianzo: $it", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Spacer(Modifier.height(12.dp))
            }
            Text("Requirements", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
            val labels = mapOf(
                "referrals" to "Referrals", "followers" to "Followers",
                "accountAge" to "Account age (days)", "posts" to "Posts",
                "profile" to "Profile", "cleanRecord" to "Clean record",
            )
            (eligibility?.requirements ?: emptyMap()).forEach { (key, req) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (req.met) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked, contentDescription = if (req.met) "Met" else "Not met", tint = if (req.met) Color(0xFF16A34A) else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(labels[key] ?: key, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    Text("${verificationValue(req.current)} / ${verificationValue(req.required)}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }
            }
            if (eligibility?.requirements.isNullOrEmpty()) {
                Text("Requirements could not be loaded.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // C-D5: a failed requirements load leaves a disabled
                // Submit with no way forward — Retry reloads them.
                TextButton(onClick = { load() }) { Text("Retry") }
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(value = reason, onValueChange = { if (it.length <= 1000) reason = it }, label = { Text("Why should you be verified?") }, modifier = Modifier.fillMaxWidth().height(120.dp))
            Spacer(Modifier.height(12.dp))
            val pending = request?.status == "pending"
            Button(enabled = !submitting && (eligibility?.eligible == true) && reason.isNotBlank() && !pending && eligibility?.isVerified != true, onClick = {
                submitting = true
                scope.launch {
                    try {
                        val r = api2.createVerificationRequest(mapOf("reason" to reason.trim()))
                        if (r.success) { notice = "Verification request submitted."; reason = ""; load() } else error = r.error?.message ?: "Could not submit request"
                    } catch (e: Exception) { error = e.message }
                    submitting = false
                }
            }, modifier = Modifier.fillMaxWidth()) {
                Text(when {
                    pending -> "Request pending"
                    eligibility?.eligible != true -> "Meet all requirements first"
                    else -> if (submitting) "Submitting..." else "Submit request"
                }, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
        }
      }
    }
}

// ---------- Your Activity (web: /settings-menu/activity, GET /api/usage) ----------
// Web treats each usage ping as one minute, grouped by local hour.

@Composable
fun ActivityScreen(api2: FrianzoApi2, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var hourly by remember { mutableStateOf(List(24) { 0 }) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    fun load() {
        loading = true; error = null
        scope.launch {
            try {
                val cal = Calendar.getInstance()
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                fmt.timeZone = TimeZone.getTimeZone("UTC")
                val from = fmt.format(cal.time)
                val to = fmt.format(Calendar.getInstance().time)
                val res = api2.usage(from, to)
                if (res.success && res.data?.pings != null) {
                    val counts = MutableList(24) { 0 }
                    for (iso in res.data.pings) {
                        try {
                            val parsed = fmt.parse(iso) ?: continue
                            val local = Calendar.getInstance()
                            local.time = parsed
                            counts[local.get(Calendar.HOUR_OF_DAY)] += 1
                        } catch (_: Exception) {}
                    }
                    hourly = counts
                } else error = res.error?.message ?: "Could not load activity"
            } catch (e: Exception) { error = e.message }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }
    val total = hourly.sum()
    val max = (hourly.maxOrNull() ?: 0).coerceAtLeast(1)
    DrawerScaffold("Your Activity", onBack) {
        Text("How much time you've spent on Frianzo today.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        if (loading) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { DotsLoading() }
        } else {
            ErrorText(error)
            if (error != null) { TextButton(onClick = { load() }) { Text("Retry") } }
            Text("Today", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatMinutes(total), fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Text("Time of day", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().height(128.dp), verticalAlignment = Alignment.Bottom) {
                hourly.forEach { v ->
                    Box(Modifier.weight(1f).padding(horizontal = 1.dp).height(((v * 120) / max).dp).background(FrianzoBlue))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("12AM", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("6AM", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("12PM", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("6PM", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("11PM", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---------- Professional Dashboard (web: /settings-menu/dashboard) ----------
// v5.16: professional redesign matching the web dashboard A to Z —
// white bordered cards (8dp radius, 16dp padding, 12dp gap), slate-100
// stat cards, segmented range pills, line chart with tappable dots,
// shimmer skeleton loading, centred error + dark "Try again" button.
// Main stats, earnings, monetization and daily views come from
// GET /api/users/me/dashboard; Achievements and Strikes come from
// GET /api/users/me/achievements and /api/users/me/strikes.
// The models and the /api/users/me/dashboard call are untouched.

@Composable
fun DashboardScreen(api2: FrianzoApi2, onBack: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var range by remember { mutableStateOf(30) }
    var data by remember { mutableStateOf<DashboardData?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var achievements by remember { mutableStateOf<List<Achievement>>(emptyList()) }
    var strikes by remember { mutableStateOf<StrikesData?>(null) }
    // C-B6: separate failure flags — without them a failed load left
    // achievements empty and strikes null, which the UI read as
    // "No achievements yet." and a green "Account health: Good" badge.
    var achievementsFailed by remember { mutableStateOf(false) }
    var strikesFailed by remember { mutableStateOf(false) }
    var extrasLoading by remember { mutableStateOf(true) }
    var selectedDay by remember { mutableStateOf<Int?>(null) }
    fun load(days: Int) {
        loading = true; error = null; selectedDay = null
        // C-B21: drop the old range's stats at once, so a failed reload
        // never shows stale numbers under the new range's error.
        data = null
        scope.launch {
            try {
                val res = api2.dashboard(days)
                if (res.success && res.data != null) data = res.data else error = res.error?.message ?: "Could not load dashboard"
            } catch (e: Exception) { error = e.message }
            loading = false
        }
    }
    fun loadExtras() {
        achievementsFailed = false; strikesFailed = false; extrasLoading = true
        scope.launch {
            try {
                val r = api2.myAchievements()
                if (r.success && r.data?.achievements != null) achievements = r.data.achievements else achievementsFailed = true
            } catch (_: Exception) { achievementsFailed = true }
            try {
                val r = api2.myStrikes()
                if (r.success && r.data != null) strikes = r.data else strikesFailed = true
            } catch (_: Exception) { strikesFailed = true }
            extrasLoading = false
        }
    }
    LaunchedEffect(range) { load(range) }
    LaunchedEffect(Unit) { loadExtras() }
    DrawerScaffold("Dashboard", onBack) {
        // v5.16: ONE scrollable column for the whole screen.
        // DrawerScaffold's content Column does not scroll, and the old
        // screen only scrolled the chart's LazyColumn, so the top half
        // could be cut off on short phones. DrawerScaffold itself is
        // left alone: other drawer screens host LazyColumns inside it,
        // which would break under a scrolling parent.
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Segmented range pills (web: slate-100 bar, white active pill).
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Row(Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf(7, 30, 90).forEach { d ->
                        val selected = d == range
                        Box(Modifier.clip(RoundedCornerShape(6.dp)).background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent).clickable { range = d }.padding(horizontal = 12.dp, vertical = 12.dp).heightIn(min = 48.dp)) {
                            Text("${d}d", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
            if (loading) {
                DashboardSkeleton()
            } else if (error != null || data == null) {
                Column(Modifier.fillMaxWidth().padding(vertical = 64.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Could not load dashboard. Please try again.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { load(range); loadExtras() }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A), contentColor = Color.White), shape = RoundedCornerShape(8.dp)) { Text("Try again", fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                }
            } else {
                val stats = data!!.stats
                // 1. Account health (strikes) — web StrikesSection.
                // Total counts strikes + removals, like the web.
                DashboardCard(borderColor = if (!extrasLoading && !strikesFailed && (strikes?.strikes?.size ?: 0) + (strikes?.removals?.size ?: 0) > 0) Color(0xFFFDE68A) else MaterialTheme.colorScheme.outline) {
                    if (extrasLoading) {
                        SkeletonBox(Modifier.fillMaxWidth().height(20.dp))
                    } else if (strikesFailed) {
                        Text("Account health could not be checked.", fontSize = 13.sp, color = Color.Red)
                    } else {
                        val totalStrikes = (strikes?.strikes?.size ?: 0) + (strikes?.removals?.size ?: 0)
                        if (totalStrikes == 0) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Account health", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                                DashboardPill("Good", emerald = true)
                            }
                        } else {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Account health", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                                DashboardPill("Warning", emerald = false)
                            }
                            if (totalStrikes >= 3) {
                                Text("Your profile is at risk of being suspended because it repeatedly didn't follow the rules.", fontSize = 12.sp, color = warningAmber())
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                strikes?.strikes?.forEach { s ->
                                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(s.reason ?: s.action ?: "Strike", fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                                            Text(dashboardTimeAgo(s.createdAt), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        s.severity?.let { Text(it.replaceFirstChar { c -> c.uppercase() }, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false) }
                                    }
                                }
                                strikes?.removals?.forEach { r ->
                                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(removalLabel(r.type), fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                                            Text(dashboardTimeAgo(r.createdAt), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                // 2. Weekly goals — web WeeklyGoals.
                data!!.goals?.let { g ->
                    DashboardCard {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Weekly goals", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            if (data!!.allGoalsMet) DashboardPill("Complete", emerald = true)
                        }
                        data!!.newAchievement?.let { a ->
                            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFFFFFBEB)).padding(12.dp), contentAlignment = Alignment.Center) {
                                Text("New achievement: ${a.name ?: a.key ?: "Achievement"}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF92400E), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                            GoalRing("Posts", g.posts?.current ?: 0, g.posts?.target ?: 7, Icons.Filled.Image)
                            GoalRing("Reels", g.reels?.current ?: 0, g.reels?.target ?: 7, Icons.Filled.PlayArrow)
                            GoalRing("Stories", g.stories?.current ?: 0, g.stories?.target ?: 14, Icons.Filled.History)
                        }
                        if (data!!.allGoalsMet) {
                            Text("Fresh goals start Monday", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                }
                // 3. Achievements — web AchievementsSection: an empty list
                // renders NOTHING (no "No achievements yet." line).
                if (extrasLoading) {
                    DashboardCard { SkeletonBox(Modifier.fillMaxWidth().height(96.dp)) }
                } else if (achievementsFailed) {
                    DashboardCard { Text("Could not load achievements.", fontSize = 13.sp, color = Color.Red) }
                } else if (achievements.isNotEmpty()) {
                    DashboardCard {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Achievements", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("${achievements.size} earned", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            achievements.forEach { a ->
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF0F172A)), contentAlignment = Alignment.Center) {
                                        Icon(achievementIcon(a.icon), contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(a.name ?: a.key ?: "Achievement", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                                        a.description?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                                    }
                                }
                            }
                        }
                    }
                }
                // 4. Earnings.
                DashboardCard {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Earnings", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("$${"%.2f".format(data!!.earnings)}", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                // 5. Monetization.
                data!!.monetization?.let { m ->
                    DashboardCard {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Monetization", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            DashboardPill(if (m.status == "eligible") "Eligible" else "Locked", emerald = m.status == "eligible")
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("${m.referrals} / ${m.requiredReferrals} referrals", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${m.progress}%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(999.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                                Box(Modifier.fillMaxWidth((m.progress / 100f).coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(999.dp)).background(Color(0xFF0F172A)))
                            }
                        }
                        if (m.referralLink.isNotBlank()) {
                            OutlinedButton(onClick = {
                                clipboard.setText(AnnotatedString(m.referralLink))
                                Toast.makeText(context, "Referral link copied", Toast.LENGTH_SHORT).show()
                            }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface), contentPadding = PaddingValues(vertical = 8.dp)) {
                                Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Copy referral link", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
                if (stats != null) {
                    // 6. Stats grid 2x2 — web StatCards (slate-100 cards).
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            StatCard("Views", stats.views, stats.viewsTrendPct, Modifier.weight(1f).fillMaxHeight())
                            StatCard("Engagement", stats.engagement, stats.engagementTrendPct, Modifier.weight(1f).fillMaxHeight(), breakdown = stats.engagementBreakdown)
                        }
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            StatCard("New followers", stats.newFollowers, stats.followersTrendPct, Modifier.weight(1f).fillMaxHeight())
                            StatCard("Content", stats.content, stats.contentTrendPct, Modifier.weight(1f).fillMaxHeight())
                        }
                    }
                    // 7. Top performing content.
                    DashboardCard {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("Top performing content", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("This month, by views", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            stats.topPerforming?.let { top ->
                                Text("${formatThousands(top.views)} views", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                            }
                        }
                        val top = stats.topPerforming
                        if (top != null && !top.id.isNullOrBlank()) {
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { openTopContent(context, top) }.padding(4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (!top.mediaUrl.isNullOrBlank()) {
                                    AsyncImage(model = top.mediaUrl, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)))
                                } else {
                                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                                        Text(if (top.type == "reel") "Reel" else "Post", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                                    }
                                }
                                Column(Modifier.weight(1f)) {
                                    Text((top.type ?: "").uppercase(Locale.US), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                                    Text(top.title ?: "", fontSize = 14.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                                }
                                Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            Text("No content views this month yet.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    // 8. Content views LINE chart (replaces the bar chart).
                    DashboardCard {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Content views", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text("Last $range days", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(formatThousands(stats.dailyViews.sumOf { it.views }), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                        }
                        val daily = stats.dailyViews
                        if (daily.size > 1) {
                            ViewsLineChart(daily, selectedDay) { selectedDay = it }
                            Box(Modifier.fillMaxWidth().height(32.dp), contentAlignment = Alignment.Center) {
                                val sel = selectedDay?.let { daily.getOrNull(it) }
                                if (sel != null) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(formatThousands(sel.views), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                        Text(" views on ", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(sel.date, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                    }
                                } else {
                                    Text("Tap a point for daily breakdown", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        } else {
                            Box(Modifier.fillMaxWidth().height(90.dp), contentAlignment = Alignment.Center) {
                                Text("Not enough data yet", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${range}d ago".uppercase(Locale.US), fontSize = 10.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Today".uppercase(Locale.US), fontSize = 10.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------- Monetization (v5.18.4, A-H1) ----------
// Web: /settings-menu/monetization. Referral progress + referral-link copy
// come from GET /api/users/me/dashboard (the same monetization block the
// Professional Dashboard card shows); payout methods from
// GET /api/payout/methods, earnings from GET /api/payout/earnings, and the
// leaderboard from GET /api/users/leaderboard?category=&limit=. All five
// endpoints were verified to exist in vynzo-backend main before this
// screen was written (payoutRoutes.ts mounted at /api/payout in app.ts;
// userRoutes.ts GET /api/users/leaderboard). The Referrers leaderboard
// tab stays hidden, like on the web (owner order 2026-10-16).

@Composable
fun MonetizationScreen(api2: FrianzoApi2, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var dash by remember { mutableStateOf<DashboardData?>(null) }
    var earn by remember { mutableStateOf<EarningsData?>(null) }
    var methods by remember { mutableStateOf<List<PayoutMethod>>(emptyList()) }
    var allowed by remember { mutableStateOf<Map<String, List<PayoutMethodOption>>>(emptyMap()) }
    var country by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    // Add-method form.
    var showAdd by remember { mutableStateOf(false) }
    var addMethodType by remember { mutableStateOf("") }
    var addName by remember { mutableStateOf("") }
    var addNumber by remember { mutableStateOf("") }
    var addExtra by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf<String?>(null) }
    var addSaving by remember { mutableStateOf(false) }
    // Leaderboard, one cached list per tab (web fetches each tab once).
    var activeTab by remember { mutableStateOf("followers") }
    var boards by remember { mutableStateOf<Map<String, List<LeaderboardEntry>>>(emptyMap()) }
    var boardLoading by remember { mutableStateOf(false) }
    var boardError by remember { mutableStateOf<String?>(null) }
    // Id of the method whose delete / set-default is in flight.
    var methodBusy by remember { mutableStateOf<String?>(null) }
    fun loadBoard(tab: String) {
        if (boards.containsKey(tab)) return
        boardLoading = true; boardError = null
        scope.launch {
            try {
                val r = api2.leaderboard(tab, 10)
                if (r.success && r.data?.leaderboard != null) boards = boards + (tab to r.data.leaderboard)
                else boardError = r.error?.message ?: "Could not load leaderboard"
            } catch (e: Exception) { boardError = e.message }
            boardLoading = false
        }
    }
    fun load() {
        loading = true; error = null
        scope.launch {
            try {
                val res = api2.dashboard()
                if (res.success && res.data != null) dash = res.data else error = res.error?.message ?: "Could not load monetization info"
            } catch (e: Exception) { error = e.message }
            loading = false
        }
        scope.launch {
            try { val r = api2.earnings(20); if (r.success && r.data != null) earn = r.data } catch (_: Exception) { /* earnings list is extra — the total below falls back to the dashboard */ }
        }
        scope.launch {
            try {
                val r = api2.payoutMethods()
                if (r.success && r.data != null) {
                    methods = r.data.methods ?: emptyList()
                    allowed = r.data.allowed ?: emptyMap()
                    country = r.data.country
                }
            } catch (_: Exception) { /* payout methods stay empty on failure */ }
        }
    }
    LaunchedEffect(Unit) { load() }
    LaunchedEffect(activeTab) { loadBoard(activeTab) }
    DrawerScaffold("Monetization", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (loading) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { DotsLoading() }
            } else {
                ErrorText(error)
                if (error != null && dash == null) { TextButton(onClick = { load() }) { Text("Retry") } }
                if (notice != null) { Text(notice!!, fontSize = 13.sp, color = FrianzoBlue) }
                // 1. Earnings + referral progress (web: earnings card).
                val m = dash?.monetization
                DashboardCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Earnings", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        DashboardPill(if (m?.status == "eligible") "Eligible" else "Locked", emerald = m?.status == "eligible")
                    }
                    Text("$${"%.2f".format(earn?.total ?: dash?.earnings ?: 0.0)}", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("${m?.referrals ?: 0} / ${m?.requiredReferrals ?: 25} referrals", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${m?.progress ?: 0}%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(999.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                            Box(Modifier.fillMaxWidth(((m?.progress ?: 0) / 100f).coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(999.dp)).background(Color(0xFF0F172A)))
                        }
                    }
                    val recent = earn?.earnings.orEmpty()
                    if (recent.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Recent earnings", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            recent.take(5).forEach { e ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("${if (e.type == "views") "Views" else "Referral"} · ${e.createdAt?.take(10) ?: ""}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                                    Text("+$${"%.2f".format(e.amount)}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = earningGreen(), maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }
                }
                // 2. Referral link (web: link card + Copy button).
                DashboardCard {
                    Text("Your referral link", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text("Share this link with friends to invite them to Frianzo.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = m?.referralLink ?: "", onValueChange = {}, readOnly = true, placeholder = { Text("Complete your profile to get a referral link", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.weight(1f))
                        Button(enabled = !m?.referralLink.isNullOrBlank(), onClick = {
                            clipboard.setText(AnnotatedString(m?.referralLink ?: ""))
                            Toast.makeText(context, "Referral link copied", Toast.LENGTH_SHORT).show()
                        }, shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A), contentColor = Color.White)) { Text("Copy", fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                    }
                }
                // 3. Leaderboard (web: Followers / Likers / Commenters tabs).
                DashboardCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.EmojiEvents, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(18.dp))
                        Text("Leaderboard", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("followers" to "Followers", "likers" to "Likers", "commenters" to "Commenters").forEach { (key, label) ->
                            val tabSelected = key == activeTab
                            Box(Modifier.clip(RoundedCornerShape(999.dp)).background(if (tabSelected) Color(0xFF0F172A) else MaterialTheme.colorScheme.surfaceVariant).clickable { activeTab = key }.padding(horizontal = 12.dp, vertical = 12.dp).heightIn(min = 48.dp)) {
                                Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (tabSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                    if (boardLoading && boards[activeTab] == null) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { DotsLoading() }
                    } else if (boardError != null && boards[activeTab] == null) {
                        Text(boardError!!, fontSize = 13.sp, color = Color.Red)
                    } else {
                        val entries = boards[activeTab].orEmpty()
                        val unit = when (activeTab) { "likers" -> "likes"; "commenters" -> "comments"; else -> "followers" }
                        if (entries.isEmpty()) {
                            Text("No rankings yet — be the first!", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                entries.forEach { e ->
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text("${e.rank}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = when (e.rank) { 1 -> Color(0xFFF59E0B); 2 -> Color(0xFF94A3B8); 3 -> warningAmber(); else -> MaterialTheme.colorScheme.onSurfaceVariant }, modifier = Modifier.width(24.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                        FrianzoAvatar(e.avatar, 36.dp)
                                        Column(Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("@${e.username ?: ""}", fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                                                if (e.isVerified) { Spacer(Modifier.width(4.dp)); Icon(Icons.Filled.Verified, contentDescription = "Verified", tint = FrianzoBlue, modifier = Modifier.size(14.dp)) }
                                            }
                                            if (!e.displayName.isNullOrBlank()) Text(e.displayName, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                                        }
                                        Text("${formatThousands(e.count)} $unit", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                                    }
                                }
                            }
                        }
                    }
                }
                // 4. Payout methods (web: methods card + Add sheet).
                DashboardCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Payout methods", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        TextButton(onClick = { addMethodType = ""; addName = ""; addNumber = ""; addExtra = ""; addError = null; showAdd = true }) { Text("+ Add", fontSize = 13.sp) }
                    }
                    if (methods.isEmpty()) {
                        Text("No payout method added yet. Add one to withdraw your balance.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            methods.forEach { pm ->
                                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(pm.methodLabel ?: pm.methodType ?: "Method", fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                                        if (pm.isDefault) DashboardPill("Default", emerald = true)
                                    }
                                    Text(listOfNotNull(pm.accountName, pm.accountNumber, pm.extra).joinToString(" · "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        if (!pm.isDefault) {
                                            TextButton(enabled = methodBusy == null, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp), onClick = {
                                                val id = pm.id ?: return@TextButton
                                                methodBusy = id
                                                scope.launch {
                                                    try {
                                                        val r = api2.setDefaultPayoutMethod(id)
                                                        if (r.success) methods = methods.map { if (it.id == id) it.copy(isDefault = true) else it.copy(isDefault = false) }
                                                        else error = r.error?.message ?: "Could not set default payout method"
                                                    } catch (e: Exception) { error = e.message }
                                                    methodBusy = null
                                                }
                                            }) { Text("Set default", fontSize = 12.sp) }
                                        }
                                        TextButton(enabled = methodBusy == null, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp), onClick = {
                                            val id = pm.id ?: return@TextButton
                                            methodBusy = id
                                            scope.launch {
                                                try {
                                                    val r = api2.deletePayoutMethod(id)
                                                    if (r.success) { methods = methods.filter { it.id != id }; notice = "Payout method removed." }
                                                    else error = r.error?.message ?: "Could not remove payout method"
                                                } catch (e: Exception) { error = e.message }
                                                methodBusy = null
                                            }
                                        }) { Text("Remove", fontSize = 12.sp, color = Color.Red) }
                                    }
                                }
                            }
                        }
                    }
                    // Add-method form (web: bottom sheet — inline here).
                    if (showAdd) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        Text("Add payout method", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        val countryMethods = country?.let { allowed[it] } ?: emptyList()
                        if (country == null) {
                            Text("Please set your country in your profile first.", fontSize = 13.sp, color = warningAmber())
                        } else if (countryMethods.isEmpty()) {
                            Text("No payout methods are available for your country yet.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text("Method", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                countryMethods.forEach { opt ->
                                    val code = opt.code ?: ""
                                    val optSelected = code == addMethodType
                                    Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (optSelected) FrianzoBlue else MaterialTheme.colorScheme.surfaceVariant).clickable { addMethodType = code }.padding(vertical = 8.dp).heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                                        Text(opt.label ?: code, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (optSelected) Color.White else MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            val selectedMethod = countryMethods.firstOrNull { it.code == addMethodType }
                            val extraLabel = selectedMethod?.fields?.extra
                            OutlinedTextField(value = addName, onValueChange = { addName = it }, label = { Text("Account holder name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            OutlinedTextField(value = addNumber, onValueChange = { addNumber = it }, label = { Text(selectedMethod?.fields?.accountNumber ?: "Account number") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            if (extraLabel != null) {
                                OutlinedTextField(value = addExtra, onValueChange = { addExtra = it }, label = { Text(extraLabel) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            }
                            ErrorText(addError)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(enabled = !addSaving && country != null && addMethodType.isNotBlank() && addName.isNotBlank() && addNumber.isNotBlank(), onClick = {
                                    addSaving = true
                                    scope.launch {
                                        try {
                                            val body = mutableMapOf("methodType" to addMethodType, "accountName" to addName.trim(), "accountNumber" to addNumber.trim())
                                            if (addExtra.isNotBlank()) body["extra"] = addExtra.trim()
                                            val r = api2.addPayoutMethod(body)
                                            val added = r.data?.method
                                            if (r.success && added != null) {
                                                methods = listOf(added) + methods.map { if (added.isDefault) it.copy(isDefault = false) else it }
                                                showAdd = false; addMethodType = ""; addName = ""; addNumber = ""; addExtra = ""; addError = null
                                                notice = "Payout method added."
                                            } else addError = r.error?.message ?: "Could not add payout method"
                                        } catch (e: Exception) { addError = e.message }
                                        addSaving = false
                                    }
                                }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A), contentColor = Color.White)) { Text(if (addSaving) "Adding..." else "Add payout method", fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                                OutlinedButton(onClick = { showAdd = false; addError = null }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) { Text("Cancel", fontSize = 14.sp) }
                            }
                        }
                    }
                }
            }
        }
    }
}

// v5.16: white bordered card — 8dp radius, 16dp padding, 12dp gap between
// children. The border turns amber for the account-health warning state.
@Composable
private fun DashboardCard(borderColor: Color = MaterialTheme.colorScheme.outline, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, borderColor), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

// v5.16: status pill — emerald (Good / Eligible / Complete) or amber (Warning / Locked).
@Composable
private fun DashboardPill(text: String, emerald: Boolean) {
    Box(Modifier.clip(RoundedCornerShape(999.dp)).background(if (emerald) Color(0xFFD1FAE5) else Color(0xFFFEF3C7)).padding(horizontal = 10.dp, vertical = 2.dp)) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (emerald) Color(0xFF047857) else Color(0xFFB45309), maxLines = 1, softWrap = false)
    }
}

// v5.16: shimmer skeleton matching the dashboard layout (web DashboardSkeleton).
@Composable
private fun DashboardSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SkeletonBox(Modifier.width(128.dp).height(28.dp))
        SkeletonBox(Modifier.fillMaxWidth().height(96.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SkeletonBox(Modifier.weight(1f).height(80.dp))
            SkeletonBox(Modifier.weight(1f).height(80.dp))
        }
        SkeletonBox(Modifier.fillMaxWidth().height(128.dp))
        SkeletonBox(Modifier.fillMaxWidth().height(176.dp))
    }
}

// v5.16: one weekly-goal ring (Posts x/7, Reels x/7, Stories x/14).
// Web WeeklyGoals Ring: 80px, 18px icon + x/y inside, green #10B981 when
// done, blue #3B82F6 while in progress, slate-100 track.
@Composable
private fun GoalRing(label: String, current: Int, target: Int, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    val progress = if (target > 0) (current.toFloat() / target).coerceIn(0f, 1f) else 0f
    val done = target > 0 && current >= target
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(progress = progress, modifier = Modifier.size(80.dp), color = if (done) Color(0xFF10B981) else Color(0xFF3B82F6), trackColor = MaterialTheme.colorScheme.surfaceVariant, strokeWidth = 7.dp)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface)
                Text("$current/$target", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
    }
}

// v5.16: one slate-100 stat card. Trends are coloured: + blue #2563EB,
// − red #EF4444 (web trendClass). The engagement breakdown is 3 separate
// rows with dividers, like the web.
@Composable
private fun StatCard(label: String, value: Int, trendPct: Double?, modifier: Modifier = Modifier, breakdown: EngagementBreakdown? = null) {
    Column(modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(formatThousands(value), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        trendPct?.let { Text(formatTrend(it), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = trendColor(it >= 0), maxLines = 1, softWrap = false) }
        breakdown?.let { b ->
            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                EngagementBreakdownRow("Likes", b.likes)
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                EngagementBreakdownRow("Comments", b.comments)
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                EngagementBreakdownRow("Shares", b.shares)
            }
        }
    }
}

@Composable
private fun EngagementBreakdownRow(label: String, value: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(formatThousands(value), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

// v5.16: line chart — same geometry as the web SVG (viewBox 0 0 320 90,
// y = 85 − (views/max)×75, 2.5px blue #2563EB line, tappable dots;
// selected dot is bigger and filled blue). Same dailyViews data source;
// only the rendering changed.
@Composable
private fun ViewsLineChart(daily: List<DailyView>, selectedDay: Int?, onSelect: (Int?) -> Unit) {
    val density = LocalDensity.current
    val lineWidthPx = with(density) { 2.5.dp.toPx() }
    val dotRadiusPx = with(density) { 4.dp.toPx() }
    val selectedRadiusPx = with(density) { 6.dp.toPx() }
    val ringWidthPx = with(density) { 2.dp.toPx() }
    val maxViews = (daily.maxOfOrNull { it.views } ?: 0).coerceAtLeast(1)
    var canvasWidth by remember { mutableStateOf(0f) }
    Canvas(
        Modifier.fillMaxWidth().height(90.dp)
            .onSizeChanged { canvasWidth = it.width.toFloat() }
            .pointerInput(daily) {
                detectTapGestures { offset ->
                    if (daily.size < 2 || canvasWidth <= 0f) return@detectTapGestures
                    val step = canvasWidth / (daily.size - 1)
                    val idx = (offset.x / step).roundToInt().coerceIn(0, daily.size - 1)
                    onSelect(if (selectedDay == idx) null else idx)
                }
            }
    ) {
        val w = size.width
        val h = size.height
        fun x(i: Int): Float = if (daily.size > 1) (i.toFloat() / (daily.size - 1)) * w else 0f
        fun y(v: Int): Float = ((85f - (v.toFloat() / maxViews) * 75f) / 90f) * h
        val path = Path()
        daily.forEachIndexed { i, d ->
            if (i == 0) path.moveTo(x(i), y(d.views)) else path.lineTo(x(i), y(d.views))
        }
        drawPath(path, color = FrianzoBlue, style = Stroke(width = lineWidthPx, cap = StrokeCap.Round))
        daily.forEachIndexed { i, d ->
            val selected = selectedDay == i
            val radius = if (selected) selectedRadiusPx else dotRadiusPx
            val center = Offset(x(i), y(d.views))
            drawCircle(color = if (selected) FrianzoBlue else Color.White, radius = radius, center = center)
            drawCircle(color = FrianzoBlue, radius = radius, center = center, style = Stroke(width = ringWidthPx))
        }
    }
}

// v5.16: web timeAgo — Today / Yesterday / Nd ago (StrikesSection).
private fun dashboardTimeAgo(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    val parsed = try {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(iso)
    } catch (_: Exception) {
        try { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso.take(10)) } catch (_: Exception) { null }
    } ?: return iso.take(10)
    val days = ((System.currentTimeMillis() - parsed.time) / 86400000L).toInt()
    return when {
        days <= 0 -> "Today"
        days == 1 -> "Yesterday"
        else -> "${days}d ago"
    }
}

// v5.16: web removal label — comment_removed / post_removed / else Reel removed.
private fun removalLabel(type: String?): String = when (type) {
    "comment_removed" -> "Comment removed"
    "post_removed" -> "Post removed"
    else -> "Reel removed"
}

// v5.16: web AchievementIcon keys (fire/star/heart/verified/followers/
// reel/post, default story) mapped to the nearest Material icon already
// used in this repo. "fire" has no Material twin here, so it falls back
// to Star like the web's default.
private fun achievementIcon(icon: String?): androidx.compose.ui.graphics.vector.ImageVector {
    return when (icon?.lowercase(Locale.US)) {
        "heart" -> Icons.Filled.Favorite
        "verified" -> Icons.Filled.Verified
        "followers" -> Icons.Filled.PersonAdd
        "reel" -> Icons.Filled.PlayArrow
        "post" -> Icons.Filled.Image
        else -> Icons.Filled.Star
    }
}

// v5.16: thousands separators (web toLocaleString).
private fun formatThousands(value: Int): String = String.format(Locale.US, "%,d", value)

// v5.16: web trend text — "+12%" / "-3%" (integer when whole, else 1 decimal).
private fun formatTrend(pct: Double): String {
    val sign = if (pct > 0) "+" else ""
    val num = if (pct % 1.0 == 0.0) pct.toInt().toString() else String.format(Locale.US, "%.1f", pct)
    return "$sign$num%"
}

// v5.16: top-performing row opens the post/reel through the app's own
// deep links (AndroidManifest C-B1: /post/<id> opens the post,
// /reels?id=<id> focuses the reel in-app; a browser is only the fallback
// if the system does not route the frianzo.online link back to the app).
private fun openTopContent(context: android.content.Context, top: TopPerforming) {
    val id = top.id ?: return
    val url = if (top.type == "reel") "https://frianzo.online/reels?id=$id" else "https://frianzo.online/post/$id"
    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
}

// ---------- Close Friends (web: /settings-menu/close-friends) ----------

@Composable
fun CloseFriendsScreen(api2: FrianzoApi2, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var candidates by remember { mutableStateOf<List<CloseFriendCandidate>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busyId by remember { mutableStateOf<String?>(null) }
    fun load() {
        loading = true; error = null
        scope.launch {
            try {
                val res = api2.closeFriends()
                if (res.success && res.data?.users != null) candidates = res.data.users else error = res.error?.message ?: "Could not load close friends"
            } catch (e: Exception) { error = e.message }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }
    DrawerScaffold("Close Friends", onBack) {
        Text("Add people you follow to your Close Friends list.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        if (loading) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { DotsLoading() }
        } else {
            ErrorText(error)
            if (error != null) { TextButton(onClick = { load() }) { Text("Retry") } }
            if (candidates.isEmpty() && error == null) {
                Text("You need to follow people before you can add them as close friends.")
            }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(candidates, key = { it.id }) { user ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FrianzoAvatar(user.profilePictureUrl, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(user.displayName ?: user.username, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        Text("@${user.username}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(enabled = busyId != user.id, onClick = {
                        busyId = user.id
                        scope.launch {
                            try {
                                val ok = if (user.isCloseFriend) api2.removeCloseFriend(user.id).success else api2.addCloseFriend(user.id).success
                                if (ok) candidates = candidates.map { if (it.id == user.id) it.copy(isCloseFriend = !user.isCloseFriend) else it } else error = "Could not update close friends"
                            } catch (e: Exception) { error = e.message }
                            busyId = null
                        }
                    }) {
                        Icon(if (user.isCloseFriend) Icons.Filled.Star else Icons.Outlined.StarBorder, contentDescription = if (user.isCloseFriend) "Remove from close friends" else "Add to close friends", tint = if (user.isCloseFriend) Color(0xFF16A34A) else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ---------- AI Video Generator (web: /ai-video) ----------
// Status, generate and operation polling all use the real backend routes.
// If the backend returns an error (for example no billing on the video
// service), that exact error is shown. Posting a generated video as a
// reel is NOT in this native build yet.

@Composable
// v2.9: after generation, "Post as Reel" downloads the generated video
// to the phone cache and opens the reel uploader with it. The backend reel
// upload takes a video FILE (multipart), not a URL, so a URL alone cannot
// be posted — the download is the real step, not a fake upload.
fun AiVideoScreen(api2: FrianzoApi2, onBack: () -> Unit, onPostAsReel: ((android.net.Uri) -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var postingReel by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<VideoGenStatus?>(null) }
    var statusError by remember { mutableStateOf<String?>(null) }
    var prompt by rememberSaveable { mutableStateOf("") }
    var aspect by rememberSaveable { mutableStateOf("9:16") }
    var duration by rememberSaveable { mutableStateOf(8) }
    var phase by remember { mutableStateOf("idle") }
    var videoUrl by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var elapsed by remember { mutableStateOf(0) }
    // C-B8: the generation poll job, so Cancel can stop it.
    var genJob by remember { mutableStateOf<Job?>(null) }
    // v3.4: referral-link button on the locked screen.
    val clipboard = LocalClipboardManager.current
    var referralCopied by remember { mutableStateOf(false) }
    var referralError by remember { mutableStateOf<String?>(null) }
    fun loadStatus() {
        scope.launch {
            try {
                val res = api2.videoGenStatus()
                if (res.success && res.data != null) status = res.data else statusError = res.error?.message ?: "Could not load video status"
            } catch (e: Exception) { statusError = e.message }
        }
    }
    LaunchedEffect(Unit) { loadStatus() }
    DrawerScaffold("AI Video Generator", onBack) {
        val scroll = rememberScrollState()
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            if (statusError != null) {
                ErrorText(statusError)
                // C-B23: retry the status load without leaving the screen.
                TextButton(onClick = { statusError = null; loadStatus() }) { Text("Retry") }
            }
            status?.let { s ->
                if (!s.enabled) Text("AI Video Generator is not enabled.", fontSize = 14.sp)
                if (!s.hasAccess) {
                    Text("Unlock AI Video Generation", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("Invite friends! Get ${s.referralsNeeded} referrals to unlock FREE AI video generation. ${s.referralCount}/${s.referralsNeeded} referrals, ${(s.referralsNeeded - s.referralCount).coerceAtLeast(0)} more needed", fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = (s.referralCount.toFloat() / s.referralsNeeded.coerceAtLeast(1)).coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth(), color = FrianzoBlue)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        scope.launch {
                            try {
                                val r = api2.referral()
                                val link = r.data?.referralLink
                                if (r.success && !link.isNullOrBlank()) { clipboard.setText(AnnotatedString(link)); referralCopied = true } else referralError = r.error?.message ?: "Could not load referral link"
                            } catch (e: Exception) { referralError = e.message }
                        }
                    }, modifier = Modifier.fillMaxWidth().height(FrianzoUi.ButtonHeight), shape = RoundedCornerShape(FrianzoUi.ButtonRadius)) { Text(if (referralCopied) "Referral link copied" else "Get my referral link", maxLines = 1, softWrap = false) }
                    referralError?.let { Text(it, fontSize = 13.sp, color = Color.Red) }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = prompt, onValueChange = { if (it.length <= 500) prompt = it }, label = { Text("Describe your video") }, modifier = Modifier.fillMaxWidth())
            Text("${prompt.length}/500", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("9:16" to "Portrait 9:16", "16:9" to "Landscape 16:9").forEach { (v, label) ->
                    Button(onClick = { aspect = v }, colors = ButtonDefaults.buttonColors(containerColor = if (aspect == v) FrianzoBlue else MaterialTheme.colorScheme.surfaceVariant, contentColor = if (aspect == v) Color.White else MaterialTheme.colorScheme.onSurface), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Text(label, maxLines = 1, softWrap = false) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(4, 8).forEach { d ->
                    Button(onClick = { duration = d }, colors = ButtonDefaults.buttonColors(containerColor = if (duration == d) FrianzoBlue else MaterialTheme.colorScheme.surfaceVariant, contentColor = if (duration == d) Color.White else MaterialTheme.colorScheme.onSurface), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Text("${d}s", maxLines = 1, softWrap = false) }
                }
            }
            Spacer(Modifier.height(8.dp))
            val gated = status != null && (!status!!.enabled || !status!!.hasAccess)
            Button(enabled = prompt.trim().length >= 3 && phase != "generating" && !gated, onClick = {
                phase = "generating"; error = null; videoUrl = null; elapsed = 0
                genJob = scope.launch {
                    try {
                        val start = api2.generateVideo(VideoGenGenerateRequest(prompt.trim(), aspect, duration))
                        val op = start.data?.operationName
                        if (!start.success || op.isNullOrBlank()) {
                            phase = "error"; error = start.error?.message ?: "Failed to start generation."
                        } else {
                            // C-B8: the old loop was `while (true)` with
                            // `if (!st.success) continue` — it polled
                            // forever, silently, with no way to stop.
                            // Now: ~5 minute timeout, and 3 consecutive
                            // failed status checks surface an error.
                            var statusFailures = 0
                            while (true) {
                                delay(3000)
                                elapsed += 3
                                if (elapsed >= 300) { phase = "error"; error = "Generation timed out after 5 minutes. Please try again."; break }
                                val st = api2.videoGenOperation(op)
                                if (!st.success) {
                                    statusFailures++
                                    if (statusFailures >= 3) { phase = "error"; error = st.error?.message ?: "Could not check generation status. Please try again."; break }
                                    continue
                                }
                                statusFailures = 0
                                val d = st.data
                                if (d?.done == true) {
                                    if (!d.videoUrl.isNullOrBlank()) { videoUrl = d.videoUrl; phase = "done" } else { phase = "error"; error = d.error ?: "Generation finished but no video was returned." }
                                    break
                                }
                                if (!d?.error.isNullOrBlank()) { phase = "error"; error = d!!.error; break }
                            }
                        }
                    } catch (e: Exception) {
                        // Cancel is not a failure: back to idle, no error.
                        if (e is CancellationException) { phase = "idle"; elapsed = 0 } else { phase = "error"; error = e.message }
                    }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text(if (phase == "generating") "Creating your video... (${elapsed}s)" else "Generate Video", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
            // C-B8: Cancel stops the poll (the job above is cancelled).
            if (phase == "generating") {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { genJob?.cancel() }, modifier = Modifier.fillMaxWidth()) { Text("Cancel", maxLines = 1, softWrap = false) }
            }
            Spacer(Modifier.height(8.dp))
            Text("Generation usually takes 1–3 minutes.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            ErrorText(error)
            if (phase == "generating") Text("Creating your video...", fontSize = 14.sp)
            if (phase == "done" && videoUrl != null) {
                Text("Your video is ready.", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(videoUrl!!, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    // C-B20: no video player on the device must not crash
                    // the app — show the error instead.
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(videoUrl))
                        context.startActivity(intent)
                    } catch (e: Exception) { error = "Could not open the video — no video player found on this device." }
                }) { Text("Open video", maxLines = 1, softWrap = false) }
                Spacer(Modifier.height(8.dp))
                if (onPostAsReel != null) {
                    Button(enabled = !postingReel, onClick = {
                        postingReel = true
                        val url = videoUrl!!
                        scope.launch {
                            try {
                                val file = withContext(Dispatchers.IO) {
                                    val tmp = java.io.File(context.cacheDir, "ai_video.mp4")
                                    URL(url).openStream().use { input -> tmp.outputStream().use { out -> input.copyTo(out) } }
                                    tmp
                                }
                                onPostAsReel(android.net.Uri.fromFile(file))
                            } catch (e: Exception) { error = e.message ?: "Could not download the video" }
                            postingReel = false
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text(if (postingReel) "Preparing..." else "Post as Reel", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                    Spacer(Modifier.height(8.dp))
                }
            }
            if (phase == "error" && error == null) Text("Generation failed. Please try again.", fontSize = 14.sp)
        }
    }
}

// ---------- ISAI Assistant (web: /ai-assistant, POST /api/ai-assistant/chat) ----------
// Text chat only in this build. The web voice input uses browser speech
// recognition, which has no native equivalent here, so it is not faked.

@Composable
fun IsaiScreen(api2: FrianzoApi2, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    // v3.4: greeting is the first assistant message, like the web.
    // C-B14: the chat survives rotation. IsaiMessage is a plain data
    // class (not Parcelable), so the default saver cannot store it —
    // save as "role\u0001content" strings instead.
    val isaiMessagesSaver = listSaver<List<IsaiMessage>, String>(
        save = { list -> list.map { "${it.role}\u0001${it.content}" } },
        restore = { saved -> saved.map { val parts = it.split("\u0001", limit = 2); IsaiMessage(parts.getOrElse(0) { "assistant" }, parts.getOrElse(1) { "" }) } },
    )
    var messages by rememberSaveable(stateSaver = isaiMessagesSaver) { mutableStateOf(listOf(IsaiMessage("assistant", "Hey! I'm ISAI — Frianzo's AI assistant."))) }
    var input by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var remaining by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    DrawerScaffold("ISAI Assistant", onBack) {
        Text("Frianzo AI Assistant", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        if (remaining != null) Text("$remaining left today", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        fun sendIsai(text: String) {
            if (text.isBlank() || sending) return
            val next = messages + IsaiMessage("user", text)
            messages = next; sending = true; error = null
            scope.launch {
                try {
                    val res = api2.isaiChat(IsaiChatRequest(next.takeLast(10)))
                    sending = false
                    if (res.success && res.data?.reply != null) {
                        messages = next + IsaiMessage("assistant", res.data.reply)
                        if (res.data.remaining != null) remaining = res.data.remaining
                    } else {
                        messages = next + IsaiMessage("assistant", res.error?.message ?: "Something went wrong. Please try again.")
                    }
                } catch (e: Exception) {
                    sending = false
                    messages = next + IsaiMessage("assistant", e.message ?: "Something went wrong. Please try again.")
                }
            }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(messages) { m ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = if (m.role == "user") Arrangement.End else Arrangement.Start) {
                    Surface(color = if (m.role == "user") FrianzoBlue else MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                        Text(m.content, fontSize = 14.sp, color = if (m.role == "user") Color.White else MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }
            }
        }
        ErrorText(error)
        // v3.4: the web's 5 suggestion chips — tapping one sends it.
        // C-D4: chips are disabled (and dimmed) while a reply is in
        // flight, instead of silently ignoring the tap.
        LazyRow(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("Who created Frianzo?", "Why isn't my video on ForYou?", "How do I go viral?", "How do I get a blue tick?", "Give me content ideas for today")) { s ->
                Box(Modifier.clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable(enabled = !sending) { sendIsai(s) }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Text(s, fontSize = 13.sp, color = if (sending) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = input, onValueChange = { input = it }, label = { Text("Ask ISAI anything...") }, modifier = Modifier.weight(1f), singleLine = true)
            Spacer(Modifier.width(8.dp))
            Button(enabled = input.isNotBlank() && !sending, onClick = { val text = input.trim(); input = ""; sendIsai(text) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Text(if (sending) "..." else "Send", maxLines = 1, softWrap = false) }
        }
    }
}
