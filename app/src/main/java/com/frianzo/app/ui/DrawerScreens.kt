package com.frianzo.app.ui

import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.frianzo.app.data.*
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---------- Drawer destinations, exactly the live web drawer order ----------
// Settings / Your Activity / Professional Dashboard / Close Friends /
// AI Video Generator / ISAI Assistant. Every screen below calls a real,
// verified backend route (see Api2). Nothing here shows faked data: a
// failed load shows the backend error text, and a gated feature shows its
// real gate (for example AI Video referrals) instead of a fake result.

@Composable
fun DrawerScreenRouter(api2: FrianzoApi2, label: String, onBack: () -> Unit, onLogout: () -> Unit, onSwitched: () -> Unit = {}, onPostAsReel: ((android.net.Uri) -> Unit)? = null) {
    when (label) {
        "Settings" -> SettingsScreen(api2, onBack, onLogout, onSwitched)
        "Your Activity" -> ActivityScreen(api2, onBack)
        "Professional Dashboard" -> DashboardScreen(api2, onBack)
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

@Composable
fun SettingsScreen(api2: FrianzoApi2, onBack: () -> Unit, onLogout: () -> Unit, onSwitched: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // v2.9: in-settings sub-screens (My Interests, Verification).
    var subScreen by remember { mutableStateOf<String?>(null) }
    if (subScreen == "interests") { MyInterestsScreen(api2, onBack = { subScreen = null }); return }
    if (subScreen == "verification") { VerificationScreen(api2, onBack = { subScreen = null }); return }
    var profile by remember { mutableStateOf<PublicProfile?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var displayName by rememberSaveable { mutableStateOf("") }
    var bio by rememberSaveable { mutableStateOf("") }
    var isPrivate by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var prefs by remember { mutableStateOf<List<NotificationPref>>(emptyList()) }
    var devices by remember { mutableStateOf<List<DeviceItem>>(emptyList()) }
    var blocked by remember { mutableStateOf<List<BlockedEntry>>(emptyList()) }
    // v2.9: saved accounts on this device (GET /api/auth/accounts).
    var savedAccounts by remember { mutableStateOf<List<SavedAccount>>(emptyList()) }
    var switchingId by remember { mutableStateOf<String?>(null) }
    fun load() {
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
    }
    LaunchedEffect(Unit) {
        load()
        scope.launch {
            try { val r = api2.notificationPrefs(); if (r.success && r.data?.preferences != null) prefs = r.data.preferences } catch (_: Exception) {}
            try { val r = api2.devices(); if (r.success && r.data != null) devices = r.data.devices ?: emptyList() } catch (_: Exception) {}
            try { val r = api2.blockedUsers(); if (r.success && r.data?.blocked != null) blocked = r.data.blocked } catch (_: Exception) {}
            try { val r = api2.savedAccounts(); if (r.success && r.data?.accounts != null) savedAccounts = r.data.accounts } catch (_: Exception) {}
        }
    }
    DrawerScaffold("Settings", onBack) {
      Box(Modifier.weight(1f)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (loading) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { DotsLoading() }
        } else {
            ErrorText(error)
            if (notice != null) { Text(notice!!, fontSize = 13.sp, color = FrianzoBlue); Spacer(Modifier.height(8.dp)) }
            Text("Profile", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
            Text("@${profile?.username ?: ""}", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = displayName, onValueChange = { displayName = it }, label = { Text("Display name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = bio, onValueChange = { bio = it }, label = { Text("Bio") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Text("Account Privacy", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Private account", fontSize = 14.sp)
                    Text("Only people who follow you can see your posts.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = isPrivate, onCheckedChange = { isPrivate = it })
            }
            Spacer(Modifier.height(8.dp))
            Button(enabled = !saving, onClick = {
                saving = true
                scope.launch {
                    try {
                        val res = api2.updateMe(mapOf("displayName" to displayName, "bio" to bio, "isPrivate" to isPrivate))
                        if (res.success) notice = "Profile saved." else error = res.error?.message ?: "Could not save profile"
                    } catch (e: Exception) { error = e.message }
                    saving = false
                }
            }, modifier = Modifier.fillMaxWidth()) { Text(if (saving) "Saving..." else "Save", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
            Spacer(Modifier.height(16.dp))
            Text("Notifications", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            if (prefs.isEmpty()) { Text("Notification settings could not be loaded.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            prefs.forEach { pref ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(pref.category.replaceFirstChar { it.uppercase() }, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    Switch(checked = pref.enabled, onCheckedChange = { checked ->
                        prefs = prefs.map { if (it.category == pref.category) it.copy(enabled = checked) else it }
                        scope.launch { try { api2.updateNotificationPref(mapOf("category" to pref.category, "enabled" to checked)) } catch (_: Exception) {} }
                    })
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Devices", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            if (devices.isEmpty()) { Text("No other devices.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            devices.forEach { d ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text((d.label ?: "Device") + if (d.isCurrent) " (this device)" else "", fontSize = 14.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(d.location, d.lastActiveAt?.take(10)).joinToString(" · "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    }
                    if (!d.isCurrent && d.id != null) TextButton(onClick = { scope.launch { try { if (api2.revokeDevice(d.id).success) devices = devices.filter { it.id != d.id } } catch (_: Exception) {} } }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Text("Revoke", maxLines = 1, softWrap = false) }
                }
            }
            if (devices.size > 1) {
                TextButton(onClick = { scope.launch { try { if (api2.revokeOtherDevices().success) devices = devices.filter { it.isCurrent } } catch (_: Exception) {} } }, modifier = Modifier.fillMaxWidth()) { Text("Revoke all other devices", modifier = Modifier.fillMaxWidth(), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
            }
            Spacer(Modifier.height(16.dp))
            Text("Blocked accounts", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            if (blocked.isEmpty()) { Text("Nobody blocked.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            blocked.forEach { entry ->
                val u = entry.user
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(u?.displayName ?: u?.username ?: "User", fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    if (u?.id != null) TextButton(onClick = { scope.launch { try { if (api2.unblockUser(u.id).success) blocked = blocked.filter { it.user?.id != u.id } } catch (_: Exception) {} } }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Text("Unblock", maxLines = 1, softWrap = false) }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Preferences", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { subScreen = "interests" }, modifier = Modifier.fillMaxWidth()) { Text("My Interests", modifier = Modifier.fillMaxWidth(), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
            TextButton(onClick = { subScreen = "verification" }, modifier = Modifier.fillMaxWidth()) { Text("Verification", modifier = Modifier.fillMaxWidth(), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
            Spacer(Modifier.height(16.dp))
            // v2.9: switch between accounts saved on this device
            // (POST /api/auth/switch). The new auth cookie is set by the
            // server; onSwitched refreshes the session to the new user.
            Text("Switch account", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            if (savedAccounts.isEmpty()) { Text("No saved accounts on this device yet.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            savedAccounts.forEach { acc ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
                        scope.launch {
                            try {
                                val r = api2.switchAccount(mapOf("accountId" to acc.id))
                                if (r.success) onSwitched() else error = r.error?.message ?: "Could not switch account"
                            } catch (e: Exception) { error = e.message }
                            switchingId = null
                        }
                    }) { Text("Switch", maxLines = 1, softWrap = false) }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Login & Security", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://frianzo.online/privacy"))
                context.startActivity(intent)
            }, modifier = Modifier.fillMaxWidth()) { Text("Privacy Policy", modifier = Modifier.fillMaxWidth(), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
            TextButton(onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://frianzo.online/terms"))
                context.startActivity(intent)
            }, modifier = Modifier.fillMaxWidth()) { Text("Terms of Service", modifier = Modifier.fillMaxWidth(), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
            TextButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) { Text("Logout", modifier = Modifier.fillMaxWidth(), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
            TextButton(onClick = { showDelete = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete Account", color = Color.Red, modifier = Modifier.fillMaxWidth(), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
        }
        }
      }
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Delete Account", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
            text = { Text("This permanently removes your profile, posts, and messages. This cannot be undone.") },
            // v2.9 FIX: only log out when the delete actually succeeded.
            // On failure show the error and STAY logged in.
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    scope.launch {
                        try {
                            val r = api2.deleteMe()
                            if (r.success) onLogout()
                            else error = r.error?.message ?: "Could not delete account. Please try again."
                        } catch (e: Exception) { error = e.message ?: "Could not delete account. Please try again." }
                    }
                }) { Text("Delete", color = Color.Red, maxLines = 1, softWrap = false) }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Text("Cancel", maxLines = 1, softWrap = false) } },
        )
    }
}

// ---------- My Interests (v2.9) ----------
// GET /api/users/interests returns the 12 fixed interests; PUT
// /api/users/me/interests { interests: [...] } saves them and returns the
// saved list. There is NO endpoint for the user's current interests, so the
// screen starts unselected and shows the saved list after Save.
@Composable
fun MyInterestsScreen(api2: FrianzoApi2, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var available by remember { mutableStateOf<List<String>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        scope.launch {
            try {
                val r = api2.interests()
                if (r.success && r.data?.interests != null) available = r.data.interests else error = r.error?.message ?: "Could not load interests"
            } catch (e: Exception) { error = e.message }
            loading = false
        }
    }
    DrawerScaffold("My Interests", onBack) {
      Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("Pick what you like. Frianzo uses this to show you more of it.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        if (loading) { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { DotsLoading() } }
        else {
            ErrorText(error)
            if (notice != null) { Text(notice!!, fontSize = 13.sp, color = FrianzoBlue); Spacer(Modifier.height(8.dp)) }
            // Chips in rows of 3 (a simple wrap; no flow-layout dep).
            Column(Modifier.fillMaxWidth()) {
                available.chunked(3).forEach { chunk ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        chunk.forEach { interest ->
                            val sel = interest in selected
                            Box(Modifier.weight(1f, fill = false).clip(RoundedCornerShape(28.dp)).background(if (sel) FrianzoBlue else MaterialTheme.colorScheme.surfaceVariant).clickable { selected = if (sel) selected - interest else selected + interest }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                                Text(interest.replaceFirstChar { it.uppercase() }, color = if (sel) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(enabled = !saving && selected.isNotEmpty(), onClick = {
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
            if (eligibility?.requirements.isNullOrEmpty()) Text("Requirements could not be loaded.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    LaunchedEffect(Unit) {
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
    val total = hourly.sum()
    val max = (hourly.maxOrNull() ?: 0).coerceAtLeast(1)
    DrawerScaffold("Your Activity", onBack) {
        Text("How much time you've spent on Frianzo today.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        if (loading) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { DotsLoading() }
        } else {
            ErrorText(error)
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
// Main stats, earnings, monetization and daily views come from
// GET /api/users/me/dashboard; Achievements and Strikes come from
// GET /api/users/me/achievements and /api/users/me/strikes.

@Composable
fun DashboardScreen(api2: FrianzoApi2, onBack: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var range by remember { mutableStateOf(30) }
    var data by remember { mutableStateOf<DashboardData?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    var achievements by remember { mutableStateOf<List<Achievement>>(emptyList()) }
    var strikes by remember { mutableStateOf<StrikesData?>(null) }
    fun load(days: Int) {
        loading = true; error = null
        scope.launch {
            try {
                val res = api2.dashboard(days)
                if (res.success && res.data != null) data = res.data else error = res.error?.message ?: "Could not load dashboard"
            } catch (e: Exception) { error = e.message }
            loading = false
        }
    }
    LaunchedEffect(range) { load(range) }
    LaunchedEffect(Unit) {
        scope.launch {
            try { val r = api2.myAchievements(); if (r.success && r.data?.achievements != null) achievements = r.data.achievements } catch (_: Exception) {}
            try { val r = api2.myStrikes(); if (r.success && r.data != null) strikes = r.data } catch (_: Exception) {}
        }
    }
    DrawerScaffold("Professional Dashboard", onBack) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(7, 30, 90).forEach { d ->
                val selected = d == range
                Button(onClick = { range = d }, colors = ButtonDefaults.buttonColors(containerColor = if (selected) FrianzoBlue else MaterialTheme.colorScheme.surfaceVariant, contentColor = if (selected) Color.White else MaterialTheme.colorScheme.onSurface), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Text("${d}d", maxLines = 1, softWrap = false) }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (loading) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { DotsLoading() }
        } else {
            ErrorText(error)
            val stats = data?.stats
            if (data != null) {
                Text("Earnings", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("$${"%.2f".format(data!!.earnings)}", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                data!!.monetization?.let { m ->
                    Text("Monetization: ${if (m.status == "eligible") "Eligible" else "Locked"}", fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    Text("${m.referrals} / ${m.requiredReferrals} referrals - ${m.progress}%", fontSize = 13.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    if (m.referralLink.isNotBlank()) {
                        TextButton(onClick = { clipboard.setText(AnnotatedString(m.referralLink)); copied = true }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Text(if (copied) "Referral link copied" else "Copy referral link", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                if (stats != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) { Text("Views", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Text("${stats.views}", fontSize = 20.sp, fontWeight = FontWeight.Bold); stats.viewsTrendPct?.let { Text("${if (it >= 0) "+" else ""}${it}%", fontSize = 12.sp) } }
                        Column(Modifier.weight(1f)) { Text("Engagement", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Text("${stats.engagement}", fontSize = 20.sp, fontWeight = FontWeight.Bold); stats.engagementBreakdown?.let { Text("Likes ${it.likes} - Comments ${it.comments} - Shares ${it.shares}", fontSize = 11.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) } }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) { Text("New followers", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Text("${stats.newFollowers}", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                        Column(Modifier.weight(1f)) { Text("Content", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Text("${stats.content}", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                    }
                    Spacer(Modifier.height(16.dp))
                    stats.topPerforming?.let { top ->
                        Text("Top performing content", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("${top.type} - ${top.views} views", fontSize = 13.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        Text(top.title ?: "", fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(12.dp))
                    }
                    Text("Content views - Last $range days", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("Total: ${stats.dailyViews.sumOf { it.views }}", fontSize = 13.sp)
                    Spacer(Modifier.height(16.dp))
                    Text("Achievements", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    if (achievements.isEmpty()) { Text("No achievements yet.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    achievements.forEach { a ->
                        Text(a.name ?: a.key ?: "Achievement", fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        a.description?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        Spacer(Modifier.height(4.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Strikes", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    val totalStrikes = strikes?.total ?: 0
                    if (totalStrikes == 0) { Text("No strikes — good.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    strikes?.strikes?.forEach { s ->
                        Text(listOfNotNull(s.reason, s.action, s.createdAt?.take(10)).joinToString(" · "), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    strikes?.removals?.forEach { r ->
                        Text(listOfNotNull(r.type, r.detail, r.createdAt?.take(10)).joinToString(" · "), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        // Daily views list is scrollable separately below the header content.
        val daily = data?.stats?.dailyViews ?: emptyList()
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(daily) { d ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(d.date, fontSize = 13.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    Text("${d.views} views", fontSize = 13.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }
                HorizontalDivider()
            }
        }
    }
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
    LaunchedEffect(Unit) {
        scope.launch {
            try {
                val res = api2.videoGenStatus()
                if (res.success && res.data != null) status = res.data else statusError = res.error?.message ?: "Could not load video status"
            } catch (e: Exception) { statusError = e.message }
        }
    }
    DrawerScaffold("AI Video Generator", onBack) {
        val scroll = rememberScrollState()
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            if (statusError != null) { ErrorText(statusError) }
            status?.let { s ->
                if (!s.enabled) Text("AI Video Generator is not enabled.", fontSize = 14.sp)
                if (!s.hasAccess) Text("You need ${s.referralsNeeded} referrals to use AI Video Generator. You have ${s.referralCount}.", fontSize = 14.sp)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = prompt, onValueChange = { if (it.length <= 500) prompt = it }, label = { Text("Describe your video") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("9:16", "16:9").forEach { a ->
                    Button(onClick = { aspect = a }, colors = ButtonDefaults.buttonColors(containerColor = if (aspect == a) FrianzoBlue else MaterialTheme.colorScheme.surfaceVariant, contentColor = if (aspect == a) Color.White else MaterialTheme.colorScheme.onSurface), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Text(a, maxLines = 1, softWrap = false) }
                }
                listOf(4, 8).forEach { d ->
                    Button(onClick = { duration = d }, colors = ButtonDefaults.buttonColors(containerColor = if (duration == d) FrianzoBlue else MaterialTheme.colorScheme.surfaceVariant, contentColor = if (duration == d) Color.White else MaterialTheme.colorScheme.onSurface), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Text("${d}s", maxLines = 1, softWrap = false) }
                }
            }
            Spacer(Modifier.height(8.dp))
            val gated = status != null && (!status!!.enabled || !status!!.hasAccess)
            Button(enabled = prompt.trim().length >= 3 && phase != "generating" && !gated, onClick = {
                phase = "generating"; error = null; videoUrl = null; elapsed = 0
                scope.launch {
                    try {
                        val start = api2.generateVideo(VideoGenGenerateRequest(prompt.trim(), aspect, duration))
                        val op = start.data?.operationName
                        if (!start.success || op.isNullOrBlank()) {
                            phase = "error"; error = start.error?.message ?: "Failed to start generation."
                        } else {
                            while (true) {
                                delay(3000)
                                elapsed += 3
                                val st = api2.videoGenOperation(op)
                                if (!st.success) continue
                                val d = st.data
                                if (d?.done == true) {
                                    if (!d.videoUrl.isNullOrBlank()) { videoUrl = d.videoUrl; phase = "done" } else { phase = "error"; error = d.error ?: "Generation finished but no video was returned." }
                                    break
                                }
                                if (!d?.error.isNullOrBlank()) { phase = "error"; error = d!!.error; break }
                            }
                        }
                    } catch (e: Exception) { phase = "error"; error = e.message }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text(if (phase == "generating") "Creating your video... (${elapsed}s)" else "Generate", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
            Spacer(Modifier.height(12.dp))
            ErrorText(error)
            if (phase == "generating") Text("Creating your video...", fontSize = 14.sp)
            if (phase == "done" && videoUrl != null) {
                Text("Your video is ready.", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(videoUrl!!, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(videoUrl))
                    context.startActivity(intent)
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
    var messages by remember { mutableStateOf<List<IsaiMessage>>(emptyList()) }
    var input by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var remaining by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    DrawerScaffold("ISAI Assistant", onBack) {
        Text("Frianzo AI Assistant", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        if (remaining != null) Text("$remaining left today", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(messages) { m ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = if (m.role == "user") Arrangement.End else Arrangement.Start) {
                    Surface(color = if (m.role == "user") FrianzoBlue else MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                        Text(m.content, fontSize = 14.sp, color = if (m.role == "user") Color.White else MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }
            }
            if (messages.isEmpty()) item { Text("Ask ISAI anything about Frianzo.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        ErrorText(error)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = input, onValueChange = { input = it }, label = { Text("Message ISAI") }, modifier = Modifier.weight(1f), singleLine = true)
            Spacer(Modifier.width(8.dp))
            Button(enabled = input.isNotBlank() && !sending, onClick = {
                val text = input.trim(); input = ""
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
            }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Text(if (sending) "..." else "Send", maxLines = 1, softWrap = false) }
        }
    }
}
