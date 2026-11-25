package com.frianzo.app.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.frianzo.app.data.Boost
import com.frianzo.app.data.BoostBudgetData
import com.frianzo.app.data.BoostConfigData
import com.frianzo.app.data.BoostCreateRequest
import com.frianzo.app.data.BoostEstimateFactorsData
import com.frianzo.app.data.BoostPaymentMethodData
import com.frianzo.app.data.BoostPurposeData
import com.frianzo.app.data.FrianzoApi2
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.math.roundToInt

// ---------- Boost (v5.26, v2 page v5.31) ----------
// The whole Boost UI lives in this one file so the feature's diff stays
// contained: the launch payload, the country gate, the "Boosted" badge,
// the Boost page and the My Boosts list. The page is the owner-approved
// Facebook-style design (2026-11-18): ONE scrolling page — real preview
// (with the goal's button on it), goal choices, a daily-budget slider
// with the duration picked separately, an Analysis card (post insights
// + Estimated daily results with range bars), then the payment summary
// and manual payment. Prices and estimate factors are NEVER hardcoded
// here — they come from GET /api/boosts/config; the local fallbacks
// below only keep the flow usable if a payload list ever arrives
// empty, and mirror the contract exactly. Amounts are never sent by
// the app: the server re-derives the charged amount from
// dailyBudget × days.

/** What one chooser row / launch carries about the content: the small
 *  preview fields plus everything the full Boost-page preview needs
 *  (author, counts, background style). Plain values only. */
data class BoostChoiceItem(
    val contentType: String, // "post" | "reel"
    val contentId: String,
    val previewText: String? = null,
    val previewImageUrl: String? = null, // post imageUrl / reel thumbnailUrl
    val createdAt: String? = null,
    val authorName: String? = null,
    val authorAvatar: String? = null,
    val authorVerified: Boolean = false,
    val likeCount: Int = 0,
    val commentCount: Int = 0,
    val shareCount: Int = 0,
    val backgroundStyle: String? = null,
)

/** What the Boost screen is opened with. Built by the feed / profile /
 *  reels call sites (from the tapped Post/Reel) and by My Boosts'
 *  "Boost Again" (with purpose + budget + duration pre-selected).
 *  Plain values only — no model objects — so AppShell can hold it in
 *  simple state. */
data class BoostLaunch(
    // Null contentType/contentId = choose-item mode (the profile Boost
    // button): the screen opens on a "Choose a post or reel" list.
    // The per-post / per-reel Boost buttons pass a preselected item
    // instead and skip that list.
    val contentType: String? = null, // "post" | "reel"
    val contentId: String? = null,
    val previewText: String? = null,
    val previewImageUrl: String? = null, // post imageUrl / reel thumbnailUrl
    val presetPurpose: String? = null,
    val presetPlanDays: Int? = null,
    val presetBudget: Int? = null,
    val startAtPayment: Boolean = false,
    val choices: List<BoostChoiceItem> = emptyList(),
    val previewAuthorName: String? = null,
    val previewAuthorAvatar: String? = null,
    val previewAuthorVerified: Boolean = false,
    val previewLikeCount: Int = 0,
    val previewCommentCount: Int = 0,
    val previewShareCount: Int = 0,
    val previewBackgroundStyle: String? = null,
)

/** PK gate for every Boost UI element (owner rule, DESIGN.md §9.1):
 *  the signed-in user's own country from GET /api/users/me, normalized
 *  the same way the backend does (normalizeCountry) — stored values are
 *  a 2-letter code ("PK") or a legacy full name ("Pakistan"). */
fun isBoostCountryPk(raw: String?): Boolean {
    val t = raw?.trim().orEmpty()
    return t.equals("PK", ignoreCase = true) || t.equals("Pakistan", ignoreCase = true)
}

/** The "Ad" label with a globe icon (owner, 2026-11-25: boosted content reads "Ad" with a world icon next to the timestamp — replaces the blue
 *  "Sponsored" chip). Grey, like the timestamp it sits next to. */
@Composable
fun BoostedBadge() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Public, contentDescription = "Ad", modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(2.dp))
        Text("Ad", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
    }
}

private fun boostFmt(n: Int): String = java.text.NumberFormat.getNumberInstance(java.util.Locale.US).format(n)

private fun boostKfmt(n: Int): String {
    if (n < 1000) return "$n"
    val v = Math.round(n / 100.0) / 10.0
    return if (v == Math.floor(v)) "${v.toInt()}K" else "${v}K"
}

private fun boostDateLabel(iso: String?): String? {
    if (iso.isNullOrBlank()) return null
    return try {
        val inF = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val d = inF.parse(iso) ?: return if (iso.length >= 10) iso.substring(0, 10) else iso
        java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.US).format(d)
    } catch (_: Exception) { if (iso.length >= 10) iso.substring(0, 10) else iso }
}

private fun boostPurposeLabel(id: String?): String = when (id) {
    "followers_gain" -> "Followers Gain"
    "views_gain" -> "Views Gain"
    "business_promotion" -> "Business Promotion"
    else -> "Boost"
}

// Fallbacks mirroring the boost config contract exactly — used only if
// the config payload ever returns an empty list, so the screen never
// dead-ends.
private val FALLBACK_PURPOSES = listOf(
    BoostPurposeData("followers_gain", "Followers Gain", "Grow your profile followers."),
    BoostPurposeData("views_gain", "Views Gain", "Get more views on this post or reel."),
    BoostPurposeData("business_promotion", "Business Promotion", "Promote a product, service or business."),
)
private val FALLBACK_METHODS = listOf(
    BoostPaymentMethodData("easypaisa", "EasyPaisa", "SHAHI COMMUNICATION", "990001988", "Send the amount to this EasyPaisa Till ID, then upload the payment screenshot below."),
    BoostPaymentMethodData("jazzcash", "JazzCash", "ISHFAQ Shop", "984580283", "Send the amount to this JazzCash business number, then upload the payment screenshot below."),
)
private val FALLBACK_DURATIONS = listOf(1, 3, 7, 14)
private val FALLBACK_BUTTON_TEXTS = listOf("Learn More", "Shop Now", "Order Now")

// Same gradient set as the feed's postBackgroundBrush (Screens.kt).
private fun boostBackgroundBrush(style: String): Brush = when (style) {
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

@Composable
private fun BoostHeader(title: String, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
    }
    HorizontalDivider(color = FrianzoBorder)
}

/** The full preview: the content as people will see it in the feed.
 *  Only a Business Promotion boost puts a button on the content (the
 *  link button, when a link is added) — Views Gain and Followers Gain
 *  boosts look completely normal (owner rule, 2026-11-18). */
@Composable
private fun BoostFullPreview(
    contentType: String,
    text: String?,
    imageUrl: String?,
    backgroundStyle: String?,
    authorName: String?,
    authorAvatar: String?,
    authorVerified: Boolean,
    likeCount: Int,
    commentCount: Int,
    shareCount: Int,
    purpose: String,
    buttonText: String,
    hasLink: Boolean,
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, FrianzoBorder, RoundedCornerShape(12.dp)).padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FrianzoAvatar(authorAvatar, 38.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(authorName ?: "You", fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (authorVerified) { Spacer(Modifier.width(4.dp)); Icon(Icons.Filled.CheckCircle, contentDescription = "Verified", modifier = Modifier.size(15.dp), tint = FrianzoBlue) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Public, contentDescription = "Ad", modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(2.dp))
                    Text("Ad · Public", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        when {
            contentType == "reel" -> {
                Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
                    if (imageUrl != null) AsyncImage(model = imageUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Text("Your reel", color = Color.White, fontSize = 14.sp)
                    Box(Modifier.align(Alignment.TopEnd).padding(8.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                        Text("Reel", fontSize = 11.sp, color = Color.White, maxLines = 1, softWrap = false)
                    }
                }
                if (!text.isNullOrBlank()) { Spacer(Modifier.height(8.dp)); Text(text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            imageUrl != null -> AsyncImage(model = imageUrl, contentDescription = null, modifier = Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
            !backgroundStyle.isNullOrBlank() && !text.isNullOrBlank() -> Box(
                Modifier.fillMaxWidth().heightIn(min = 130.dp).clip(RoundedCornerShape(8.dp)).background(boostBackgroundBrush(backgroundStyle)),
                contentAlignment = Alignment.Center,
            ) { Text(text, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(20.dp)) }
            !text.isNullOrBlank() -> Text(text, fontSize = 15.sp)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            Text("${boostFmt(likeCount)} Likes", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
            Text("${boostFmt(commentCount)} Comments", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
            Text("${boostFmt(shareCount)} Shares", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
        }
        // Owner rule (2026-11-18): only a Business Promotion boost puts
        // a button on the content. Views Gain and Followers Gain boosts
        // show no button — the content looks completely normal.
        if (purpose == "business_promotion" && hasLink) {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = FrianzoBorder)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Your link", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Box(Modifier.clip(RoundedCornerShape(8.dp)).background(FrianzoBlue).padding(horizontal = 14.dp, vertical = 6.dp)) {
                    Text(buttonText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

/** One Estimated-daily-results row: label, range, and the blue range
 *  bar positioned inside the full scale (the approved design). */
@Composable
private fun BoostEstimateBar(label: String, min: Int, max: Int, scaleMax: Int) {
    val left = (min.toFloat() / scaleMax).coerceIn(0f, 1f)
    val width = ((max - min).toFloat() / scaleMax).coerceIn(0.02f, 1f - left)
    val rest = (1f - left - width).coerceAtLeast(0f)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1, softWrap = false)
        Text("${boostKfmt(min)} – ${boostKfmt(max)}", fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
    }
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(FrianzoBorder)) {
        if (left > 0f) Spacer(Modifier.weight(left))
        Box(Modifier.weight(width).fillMaxHeight().background(FrianzoBlue))
        if (rest > 0f) Spacer(Modifier.weight(rest))
    }
}

@Composable
fun BoostScreen(api2: FrianzoApi2, launch: BoostLaunch, onClose: () -> Unit, onOpenMyBoosts: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf<BoostConfigData?>(null) }
    var configError by remember { mutableStateOf<String?>(null) }
    // The item being boosted: preselected by the caller, or picked from
    // the choose list in choose-item mode (chosenId == null there).
    var chosenType by remember { mutableStateOf(launch.contentType) }
    var chosenId by remember { mutableStateOf(launch.contentId) }
    var chosenText by remember { mutableStateOf(launch.previewText) }
    var chosenImage by remember { mutableStateOf(launch.previewImageUrl) }
    var chosenBg by remember { mutableStateOf(launch.previewBackgroundStyle) }
    var chosenAuthorName by remember { mutableStateOf(launch.previewAuthorName) }
    var chosenAuthorAvatar by remember { mutableStateOf(launch.previewAuthorAvatar) }
    var chosenAuthorVerified by remember { mutableStateOf(launch.previewAuthorVerified) }
    var chosenLikes by remember { mutableStateOf(launch.previewLikeCount) }
    var chosenComments by remember { mutableStateOf(launch.previewCommentCount) }
    var chosenShares by remember { mutableStateOf(launch.previewShareCount) }
    var purpose by remember { mutableStateOf(launch.presetPurpose ?: "views_gain") }
    var budget by remember { mutableStateOf(launch.presetBudget ?: 200) }
    var days by remember { mutableStateOf(launch.presetPlanDays ?: 3) }
    var linkUrl by remember { mutableStateOf("") }
    var buttonText by remember { mutableStateOf("Learn More") }
    var methodId by remember { mutableStateOf<String?>(null) }
    var txnId by remember { mutableStateOf("") }
    var shotUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var shotUrl by remember { mutableStateOf<String?>(null) }
    var uploading by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }
    var submitted by remember { mutableStateOf(false) }

    fun loadConfig() {
        scope.launch {
            try {
                val r = api2.boostConfig()
                if (r.success && r.data != null) {
                    config = r.data
                    configError = null
                    if (methodId == null) methodId = (r.data.paymentMethods?.firstOrNull() ?: FALLBACK_METHODS.first()).id
                } else configError = r.error?.message ?: "Could not load boost options"
            } catch (e: Exception) { configError = e.message ?: "Could not load boost options" }
        }
    }
    LaunchedEffect(Unit) { loadConfig() }

    // Payment screenshot — the chat photo pattern (Screens2.kt): gallery
    // pick behind the gallery permission gate, 10 MB cap, bytes uploaded
    // through POST /api/upload/chat (field "file"), R2 URL kept for the
    // create call.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            uploading = true
            formError = null
            try {
                val size = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1
                if (size > 10 * 1024 * 1024) { formError = "Screenshot must be under 10 MB"; return@launch }
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes == null) { formError = "Couldn't read screenshot"; return@launch }
                val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                val part = MultipartBody.Part.createFormData("file", "boost-screenshot.jpg", bytes.toRequestBody(mime.toMediaTypeOrNull()))
                val r = api2.uploadChatMedia(part)
                val url = r.data?.url
                if (r.success && url != null) { shotUri = uri; shotUrl = url }
                else formError = r.error?.message ?: "Couldn't upload screenshot"
            } catch (e: Exception) { formError = e.message ?: "Couldn't upload screenshot" }
            finally { uploading = false }
        }
    }
    val pickGate = rememberPermissionGate(galleryPermissions(), onGranted = { picker.launch("image/*") })

    val title = when {
        chosenId == null -> "Boost"
        chosenType == "reel" -> "Boost Reel"
        else -> "Boost Post"
    }

    if (submitted) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            BoostHeader(title, onClose)
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFF16A34A), modifier = Modifier.size(88.dp))
                Spacer(Modifier.height(16.dp))
                Text("Request sent — your boost will start once it is approved", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                Button(onClick = onOpenMyBoosts, colors = ButtonDefaults.buttonColors(containerColor = FrianzoBlue), shape = RoundedCornerShape(28.dp), modifier = Modifier.height(48.dp)) { Text("My Boosts", color = Color.White, maxLines = 1, softWrap = false) }
                TextButton(onClick = onClose) { Text("Done", maxLines = 1, softWrap = false) }
            }
        }
        return
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        BoostHeader(title, onClose = {
            if (launch.contentId == null && chosenId != null) {
                // Back from a picked item returns to the choose list.
                chosenId = null; chosenType = null
            } else onClose()
        })
        val cfg = config
        when {
            cfg == null && configError == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { DotsLoading() }
            configError != null -> Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(configError!!, color = Color.Red, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { configError = null; loadConfig() }, colors = ButtonDefaults.buttonColors(containerColor = FrianzoBlue), shape = RoundedCornerShape(28.dp), modifier = Modifier.height(48.dp)) { Text("Retry", color = Color.White, maxLines = 1, softWrap = false) }
            }
            cfg != null && !cfg.eligible -> Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("Boost is available for users in Pakistan only.", fontSize = 15.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onClose) { Text("Done", maxLines = 1, softWrap = false) }
            }
            cfg != null -> {
                val purposes = cfg.purposes?.takeIf { it.isNotEmpty() } ?: FALLBACK_PURPOSES
                val methods = cfg.paymentMethods?.takeIf { it.isNotEmpty() } ?: FALLBACK_METHODS
                val budgetCfg = cfg.budget ?: BoostBudgetData()
                val durations = cfg.durations?.takeIf { it.isNotEmpty() } ?: FALLBACK_DURATIONS
                val factors = cfg.estimateFactors ?: BoostEstimateFactorsData()
                val buttonTexts = cfg.buttonTexts?.takeIf { it.isNotEmpty() } ?: FALLBACK_BUTTON_TEXTS
                val trial = cfg.firstBoostTrial
                val selectedMethod = methods.firstOrNull { it.id == methodId } ?: methods.first()
                val isBusiness = purpose == "business_promotion"
                val trimmedLink = linkUrl.trim()
                val linkValid = trimmedLink.isEmpty() || Regex("^https?://.+", RegexOption.IGNORE_CASE).containsMatchIn(trimmedLink)
                val trialApplies = trial?.available == true && budget == (trial?.dailyBudget ?: 100) && days == (trial?.days ?: 1)
                val total = if (trialApplies) trial?.amountPkr ?: budget * days else budget * days
                if (chosenId == null) {
                    // Choose-item mode (profile Boost button): pick one of
                    // my own posts/reels, then the Boost page follows.
                    Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                        Spacer(Modifier.height(16.dp))
                        Text("Choose a post or reel", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(10.dp))
                        if (launch.choices.isEmpty()) {
                            Text("You have no posts or reels to boost yet.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            LazyColumn(Modifier.fillMaxSize()) {
                                items(launch.choices.sortedByDescending { it.createdAt ?: "" }, key = { it.contentType + ":" + it.contentId }) { item ->
                                    Row(
                                        Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface)
                                            .border(1.dp, FrianzoBorder, RoundedCornerShape(12.dp))
                                            .clickable {
                                                chosenType = item.contentType; chosenId = item.contentId
                                                chosenText = item.previewText; chosenImage = item.previewImageUrl
                                                chosenBg = item.backgroundStyle; chosenAuthorName = item.authorName
                                                chosenAuthorAvatar = item.authorAvatar; chosenAuthorVerified = item.authorVerified
                                                chosenLikes = item.likeCount; chosenComments = item.commentCount; chosenShares = item.shareCount
                                            }
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (item.previewImageUrl != null) {
                                            AsyncImage(model = item.previewImageUrl, contentDescription = null, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                                            Spacer(Modifier.width(12.dp))
                                        }
                                        Column(Modifier.weight(1f)) {
                                            Text(if (item.contentType == "reel") "REEL" else "POST", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = FrianzoBlue, maxLines = 1, softWrap = false)
                                            Spacer(Modifier.height(2.dp))
                                            Text(item.previewText?.takeIf { it.isNotBlank() } ?: if (item.contentType == "reel") "Your reel" else "Your post", fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                        Spacer(Modifier.height(12.dp))
                        Text("PREVIEW — HOW PEOPLE WILL SEE IT", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                        Spacer(Modifier.height(8.dp))
                        BoostFullPreview(
                            contentType = chosenType ?: "post",
                            text = chosenText,
                            imageUrl = chosenImage,
                            backgroundStyle = chosenBg,
                            authorName = chosenAuthorName,
                            authorAvatar = chosenAuthorAvatar,
                            authorVerified = chosenAuthorVerified,
                            likeCount = chosenLikes,
                            commentCount = chosenComments,
                            shareCount = chosenShares,
                            purpose = purpose,
                            buttonText = buttonText,
                            hasLink = trimmedLink.isNotEmpty(),
                        )

                        // Goal
                        Spacer(Modifier.height(20.dp))
                        Text("Choose your goal", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(10.dp))
                        purposes.forEach { p ->
                            val sel = purpose == p.id
                            Box(
                                Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(12.dp))
                                    .background(if (sel) FrianzoBlueTint else MaterialTheme.colorScheme.surface)
                                    .border(1.dp, if (sel) FrianzoBlue else FrianzoBorder, RoundedCornerShape(12.dp))
                                    .clickable { purpose = p.id }.padding(14.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier.size(18.dp).clip(CircleShape).border(2.dp, if (sel) FrianzoBlue else MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
                                        contentAlignment = Alignment.Center,
                                    ) { if (sel) Box(Modifier.size(9.dp).clip(CircleShape).background(FrianzoBlue)) }
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(p.label ?: boostPurposeLabel(p.id), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                                        p.description?.let { Spacer(Modifier.height(2.dp)); Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    }
                                }
                            }
                        }
                        if (isBusiness) {
                            OutlinedTextField(
                                value = linkUrl,
                                onValueChange = { linkUrl = it },
                                placeholder = { Text("Your website or WhatsApp link (optional)") },
                                singleLine = true,
                                isError = !linkValid,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (!linkValid) { Spacer(Modifier.height(4.dp)); Text("Enter a valid link starting with http:// or https://.", fontSize = 12.sp, color = Color.Red) }
                            Spacer(Modifier.height(12.dp))
                            Text("Button text", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                buttonTexts.forEach { t ->
                                    val sel = buttonText == t
                                    Box(
                                        Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                                            .background(if (sel) FrianzoBlue else MaterialTheme.colorScheme.surface)
                                            .border(1.dp, if (sel) FrianzoBlue else FrianzoBorder, RoundedCornerShape(8.dp))
                                            .clickable { buttonText = t }.padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center,
                                    ) { Text(t, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (sel) Color.White else MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false) }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text("The button will appear on your boosted ${if (chosenType == "reel") "reel" else "post"} with your link. Without a link, no button will show.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        // Budget + duration
                        Spacer(Modifier.height(16.dp))
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface)
                                .border(1.dp, FrianzoBorder, RoundedCornerShape(12.dp)).padding(14.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Daily budget", fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, softWrap = false)
                                Text("Rs ${boostFmt(budget)}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = FrianzoBlue, maxLines = 1, softWrap = false)
                            }
                            Slider(
                                value = budget.coerceIn(budgetCfg.min, budgetCfg.max).toFloat(),
                                onValueChange = { budget = (it / budgetCfg.step).roundToInt() * budgetCfg.step },
                                valueRange = budgetCfg.min.toFloat()..budgetCfg.max.toFloat(),
                                steps = ((budgetCfg.max - budgetCfg.min) / budgetCfg.step - 1).coerceAtLeast(0),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(Modifier.fillMaxWidth()) {
                                Text("Rs ${boostFmt(budgetCfg.min)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1, softWrap = false)
                                Text("Rs ${boostFmt(budgetCfg.max)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                            }
                            Spacer(Modifier.height(14.dp))
                            Text("Duration", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                durations.forEach { d ->
                                    val sel = days == d
                                    Box(
                                        Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                                            .background(if (sel) FrianzoBlue else MaterialTheme.colorScheme.surface)
                                            .border(1.dp, if (sel) FrianzoBlue else FrianzoBorder, RoundedCornerShape(8.dp))
                                            .clickable { days = d }.padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center,
                                    ) { Text("$d ${if (d == 1) "day" else "days"}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (sel) Color.White else MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false) }
                                }
                            }
                        }

                        // Analysis + Estimated daily results
                        Spacer(Modifier.height(12.dp))
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface)
                                .border(1.dp, FrianzoBorder, RoundedCornerShape(12.dp)).padding(14.dp)
                        ) {
                            Text("Analysis", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Text("${if (chosenType == "reel") "Reel" else "Post"} insights so far", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text("${boostFmt(chosenLikes)} Likes", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                                Text("${boostFmt(chosenComments)} Comments", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                                Text("${boostFmt(chosenShares)} Shares", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                            }
                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider(color = FrianzoBorder)
                            Spacer(Modifier.height(12.dp))
                            Text("Estimated daily results", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            val reachMin = (budget * factors.reachPerRupeeMin).roundToInt()
                            val reachMax = (budget * factors.reachPerRupeeMax).roundToInt()
                            val impMin = (budget * factors.impressionsPerRupeeMin).roundToInt()
                            val impMax = (budget * factors.impressionsPerRupeeMax).roundToInt()
                            val clickMin = (reachMin * factors.clicksPerReach).roundToInt()
                            val clickMax = (reachMax * factors.clicksPerReach).roundToInt()
                            BoostEstimateBar("People reached", reachMin, reachMax, (budgetCfg.max * factors.reachPerRupeeMax).roundToInt())
                            Spacer(Modifier.height(10.dp))
                            BoostEstimateBar("Impressions", impMin, impMax, (budgetCfg.max * factors.impressionsPerRupeeMax).roundToInt())
                            Spacer(Modifier.height(10.dp))
                            BoostEstimateBar("Link clicks", clickMin, clickMax, (budgetCfg.max * factors.reachPerRupeeMax * factors.clicksPerReach).roundToInt())
                            Spacer(Modifier.height(8.dp))
                            Text("Estimated per day — actual results depend on how many people are online.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        Spacer(Modifier.height(12.dp))
                        Text("Your ${if (chosenType == "reel") "reel" else "post"} will appear in: Home feed · Reels feed · Search", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                        // Payment
                        Spacer(Modifier.height(12.dp))
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface)
                                .border(1.dp, FrianzoBorder, RoundedCornerShape(12.dp)).padding(14.dp)
                        ) {
                            Text("Payment summary", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth()) {
                                Text("Daily budget", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1, softWrap = false)
                                Text("Rs ${boostFmt(budget)}", fontSize = 13.sp, maxLines = 1, softWrap = false)
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(Modifier.fillMaxWidth()) {
                                Text("Duration", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1, softWrap = false)
                                Text("$days ${if (days == 1) "day" else "days"}", fontSize = 13.sp, maxLines = 1, softWrap = false)
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Total", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, softWrap = false)
                                if (trialApplies) {
                                    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(FrianzoBlue).padding(horizontal = 6.dp, vertical = 2.dp)) { Text("First boost", fontSize = 11.sp, color = Color.White, maxLines = 1, softWrap = false) }
                                    Spacer(Modifier.width(6.dp))
                                    Text("Rs ${boostFmt(budget * days)}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text("Rs ${boostFmt(total)}", fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                            }
                            Spacer(Modifier.height(16.dp))
                            Text("Pay with", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(10.dp))
                            methods.forEach { m ->
                                val sel = selectedMethod.id == m.id
                                Box(
                                    Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(12.dp))
                                        .background(if (sel) FrianzoBlueTint else MaterialTheme.colorScheme.surface)
                                        .border(1.dp, if (sel) FrianzoBlue else FrianzoBorder, RoundedCornerShape(12.dp))
                                        .clickable { methodId = m.id }.padding(16.dp)
                                ) {
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(m.label ?: m.id, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, softWrap = false)
                                            if (sel) Icon(Icons.Filled.CheckCircle, contentDescription = "Selected", tint = FrianzoBlue, modifier = Modifier.size(22.dp))
                                        }
                                        Spacer(Modifier.height(8.dp))
                                        Text(if (m.id == "easypaisa") "Till ID" else "Account number", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(m.accountNumber ?: "", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = FrianzoBlue, modifier = Modifier.weight(1f, fill = false), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                                            Spacer(Modifier.width(10.dp))
                                            OutlinedButton(
                                                onClick = {
                                                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("account", m.accountNumber ?: ""))
                                                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                                                },
                                                shape = RoundedCornerShape(28.dp),
                                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                            ) { Text("Copy", fontSize = 13.sp, maxLines = 1, softWrap = false) }
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text(m.accountTitle ?: "", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                                        m.instructions?.let { Spacer(Modifier.height(4.dp)); Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    }
                                }
                            }
                            if (shotUri != null) {
                                AsyncImage(model = shotUri, contentDescription = "Payment screenshot", modifier = Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                                TextButton(onClick = { pickGate() }, enabled = !uploading, contentPadding = PaddingValues(horizontal = 4.dp)) { Text(if (uploading) "Uploading…" else "Change screenshot", maxLines = 1, softWrap = false) }
                            } else {
                                OutlinedButton(onClick = { pickGate() }, enabled = !uploading, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(28.dp)) { Text(if (uploading) "Uploading…" else "Upload payment screenshot", maxLines = 1, softWrap = false) }
                            }
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(value = txnId, onValueChange = { txnId = it }, label = { Text("Transaction ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            formError?.let { Spacer(Modifier.height(10.dp)); Text(it, color = Color.Red, fontSize = 13.sp) }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                    // Sticky bottom action (Boost v2 design): one button —
                    // the whole page above is a single scroll.
                    HorizontalDivider(color = FrianzoBorder)
                    Box(Modifier.fillMaxWidth().padding(16.dp)) {
                        Button(
                            enabled = shotUrl != null && !uploading && !submitting && linkValid,
                            onClick = {
                                val shot = shotUrl ?: return@Button
                                submitting = true
                                formError = null
                                scope.launch {
                                    try {
                                        val r = api2.createBoost(
                                            BoostCreateRequest(
                                                contentType = chosenType ?: "post",
                                                postId = if (chosenType == "post") chosenId else null,
                                                reelId = if (chosenType == "reel") chosenId else null,
                                                purpose = purpose,
                                                dailyBudget = budget,
                                                days = days,
                                                linkUrl = if (isBusiness && trimmedLink.isNotEmpty()) trimmedLink else null,
                                                buttonText = if (isBusiness && trimmedLink.isNotEmpty()) buttonText else null,
                                                paymentMethod = selectedMethod.id,
                                                transactionId = txnId.trim().ifBlank { null },
                                                screenshotUrl = shot,
                                            )
                                        )
                                        if (r.success) submitted = true
                                        else formError = r.error?.message ?: "Could not send the boost request"
                                    } catch (e: Exception) { formError = e.message ?: "Could not send the boost request" }
                                    finally { submitting = false }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = FrianzoBlue),
                            shape = RoundedCornerShape(28.dp),
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) { Text(if (submitting) "Sending…" else "Boost Post Now", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false) }
                    }
                }
            }
        }
    }
}

@Composable
fun MyBoostsScreen(api2: FrianzoApi2, onClose: () -> Unit, onBoostAgain: (BoostLaunch) -> Unit) {
    val scope = rememberCoroutineScope()
    var boosts by remember { mutableStateOf<List<Boost>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    fun load() {
        scope.launch {
            try {
                val r = api2.myBoosts()
                if (r.success) { boosts = r.data?.boosts ?: emptyList(); error = null }
                else error = r.error?.message ?: "Could not load your boosts"
            } catch (e: Exception) { error = e.message ?: "Could not load your boosts" }
        }
    }
    LaunchedEffect(Unit) { load() }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        BoostHeader("My Boosts", onClose)
        val list = boosts
        when {
            list == null && error == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { DotsLoading() }
            error != null -> Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(error!!, color = Color.Red, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { error = null; load() }, colors = ButtonDefaults.buttonColors(containerColor = FrianzoBlue), shape = RoundedCornerShape(28.dp), modifier = Modifier.height(48.dp)) { Text("Retry", color = Color.White, maxLines = 1, softWrap = false) }
            }
            list.isNullOrEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("No boosts yet. Open one of your posts or reels and tap Boost.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.id }) { b ->
                    BoostRow(b, onBoostAgain = onBoostAgain)
                    HorizontalDivider(color = FrianzoBorder)
                }
            }
        }
    }
}

@Composable
private fun BoostRow(b: Boost, onBoostAgain: (BoostLaunch) -> Unit) {
    val isReel = b.contentType == "reel"
    val thumb = if (isReel) b.reel?.thumbnailUrl else b.post?.imageUrl
    val text = (if (isReel) b.reel?.caption else b.post?.content)?.takeIf { it.isNotBlank() } ?: if (isReel) "Reel" else "Post"
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (thumb != null) {
                AsyncImage(model = thumb, contentDescription = null, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    if (b.dailyBudget != null) "Rs ${boostFmt(b.dailyBudget)} /day · ${if (b.planDays == 1) "1 day" else "${b.planDays} days"} · Rs ${boostFmt(b.amountPkr)} · ${boostPurposeLabel(b.purpose)}"
                    else "${if (b.planDays == 1) "1 day" else "${b.planDays} days"} · Rs ${boostFmt(b.amountPkr)} · ${boostPurposeLabel(b.purpose)}",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                )
                if (!b.linkUrl.isNullOrBlank()) {
                    Text("Button: ${b.buttonText ?: "Learn More"} · ${b.linkUrl}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(8.dp))
            when (b.status) {
                "pending" -> Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFFFEF3C7)).padding(horizontal = 8.dp, vertical = 3.dp)) { Text("Pending", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF92400E), maxLines = 1, softWrap = false) }
                "approved" -> Box(Modifier.clip(RoundedCornerShape(8.dp)).background(FrianzoBlue).padding(horizontal = 8.dp, vertical = 3.dp)) { Text("Active" + (boostDateLabel(b.endsAt)?.let { " · ends $it" } ?: ""), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, softWrap = false) }
                "rejected" -> Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFFFEE2E2)).padding(horizontal = 8.dp, vertical = 3.dp)) { Text("Rejected", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF991B1B), maxLines = 1, softWrap = false) }
                "expired" -> Box(Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 8.dp, vertical = 3.dp)) { Text("Ended", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false) }
                else -> Box(Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 8.dp, vertical = 3.dp)) { Text(b.status ?: "", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false) }
            }
        }
        if (b.status == "rejected" && !b.adminNote.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text("Note: ${b.adminNote}", fontSize = 12.sp, color = Color(0xFF991B1B))
        }
        b.results?.let { res ->
            Spacer(Modifier.height(6.dp))
            val parts = mutableListOf<String>()
            res.views?.let { parts.add("Views: ${boostFmt(it)}") }
            parts.add("New followers: ${boostFmt(res.followers)}")
            Text(parts.joinToString(" · "), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = FrianzoBlue, maxLines = 1, softWrap = false)
        }
        if (b.status == "expired" || b.status == "rejected") {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    onBoostAgain(
                        BoostLaunch(
                            contentType = b.contentType ?: "post",
                            contentId = b.postId ?: b.reelId ?: "",
                            previewText = if (isReel) b.reel?.caption else b.post?.content,
                            previewImageUrl = thumb,
                            presetPurpose = b.purpose,
                            presetPlanDays = b.planDays,
                            presetBudget = b.dailyBudget,
                        )
                    )
                },
                shape = RoundedCornerShape(28.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                modifier = Modifier.height(44.dp),
            ) { Text("Boost Again", color = FrianzoBlue, fontSize = 13.sp, maxLines = 1, softWrap = false) }
        }
    }
}
