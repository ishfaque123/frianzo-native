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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.frianzo.app.data.BoostConfigData
import com.frianzo.app.data.BoostCreateRequest
import com.frianzo.app.data.BoostPaymentMethodData
import com.frianzo.app.data.BoostPlanData
import com.frianzo.app.data.BoostPurposeData
import com.frianzo.app.data.FrianzoApi2
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

// ---------- Boost (v5.26) ----------
// The whole Boost UI lives in this one file so the feature's diff stays
// contained: the launch payload, the country gate, the "Boosted" badge,
// the stepped Boost checkout (Purpose -> Budget -> Payment, sticky
// bottom action button) and the My Boosts list. Prices, estimates,
// purposes and payment details are NEVER hardcoded here — they come
// from GET /api/boosts/config (DESIGN.md §9); the local fallbacks below
// only keep the flow usable if a payload list ever arrives empty, and
// mirror the contract exactly. Amounts are never sent by the app: the
// server re-derives the charged amount from the chosen plan.

/** What the Boost screen is opened with. Built by the feed / profile /
 *  reels call sites (from the tapped Post/Reel) and by My Boosts'
 *  "Boost Again" (with purpose + plan pre-selected, landing straight
 *  on the payment step). Plain values only — no model objects — so
 *  AppShell can hold it in simple state. */
data class BoostChoiceItem(
    val contentType: String, // "post" | "reel"
    val contentId: String,
    val previewText: String? = null,
    val previewImageUrl: String? = null, // post imageUrl / reel thumbnailUrl
    val createdAt: String? = null,
)

data class BoostLaunch(
    // Null contentType/contentId = choose-item mode (the profile Boost
    // button): the flow opens on a "Choose a post or reel" step listing
    // `choices`. The per-post / per-reel Boost buttons pass a preselected
    // item instead and skip that step.
    val contentType: String? = null, // "post" | "reel"
    val contentId: String? = null,
    val previewText: String? = null,
    val previewImageUrl: String? = null, // post imageUrl / reel thumbnailUrl
    val presetPurpose: String? = null,
    val presetPlanDays: Int? = null,
    val startAtPayment: Boolean = false,
    val choices: List<BoostChoiceItem> = emptyList(),
)

/** PK gate for every Boost UI element (owner rule, DESIGN.md §9.1):
 *  the signed-in user's own country from GET /api/users/me, normalized
 *  the same way the backend does (normalizeCountry) — stored values are
 *  a 2-letter code ("PK") or a legacy full name ("Pakistan"). */
fun isBoostCountryPk(raw: String?): Boolean {
    val t = raw?.trim().orEmpty()
    return t.equals("PK", ignoreCase = true) || t.equals("Pakistan", ignoreCase = true)
}

/** The "🚀 Boosted" chip, shaped like the existing Pinned chip (feed
 *  card) but in FrianzoBlue so a paid placement reads as sponsored. */
@Composable
fun BoostedBadge() {
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(FrianzoBlue).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text("🚀 Boosted", fontSize = 11.sp, color = Color.White, maxLines = 1, softWrap = false)
    }
}

private fun boostFmt(n: Int): String = java.text.NumberFormat.getNumberInstance(java.util.Locale.US).format(n)

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

// Fallbacks mirroring DESIGN.md §9.2 exactly — used only if the config
// payload ever returns an empty list, so the screen never dead-ends.
private val FALLBACK_PURPOSES = listOf(
    BoostPurposeData("followers_gain", "Followers Gain", "Grow your profile followers."),
    BoostPurposeData("views_gain", "Views Gain", "Get more views on this post or reel."),
    BoostPurposeData("business_promotion", "Business Promotion", "Promote a product, service or business."),
)
private val FALLBACK_METHODS = listOf(
    BoostPaymentMethodData("easypaisa", "EasyPaisa", "SHAHI COMMUNICATION", "990001988", "Send the amount to this EasyPaisa Till ID, then upload the payment screenshot below."),
    BoostPaymentMethodData("jazzcash", "JazzCash", "ISHFAQ Shop", "984580283", "Send the amount to this JazzCash business number, then upload the payment screenshot below."),
)

@Composable
private fun BoostHeader(title: String, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
    }
    HorizontalDivider(color = FrianzoBorder)
}

/** Purpose -> Budget -> Payment indicator. Completed steps show a blue
 *  check, the current step a blue number, upcoming steps stay grey.
 *  Tapping a completed step jumps back to it. */
@Composable
private fun BoostStepIndicator(step: Int, onStep: (Int) -> Unit) {
    val labels = listOf("Purpose", "Budget", "Payment")
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        labels.forEachIndexed { i, label ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = if (i < step) Modifier.clickable { onStep(i) } else Modifier,
            ) {
                Box(
                    Modifier.size(26.dp).clip(CircleShape).background(if (i <= step) FrianzoBlue else MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (i < step) Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    else Text("${i + 1}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (i == step) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                }
                Spacer(Modifier.width(6.dp))
                Text(label, fontSize = 13.sp, fontWeight = if (i == step) FontWeight.SemiBold else FontWeight.Normal, color = if (i <= step) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
            }
            if (i < labels.size - 1) {
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f).height(2.dp).background(if (i < step) FrianzoBlue else FrianzoBorder))
                Spacer(Modifier.width(8.dp))
            }
        }
    }
}

/** The content being boosted, as a proper card (owner UI direction). */
@Composable
private fun BoostPreviewCard(contentType: String, previewText: String?, previewImageUrl: String?) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, FrianzoBorder, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (previewImageUrl != null) {
            AsyncImage(model = previewImageUrl, contentDescription = null, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(if (contentType == "reel") "REEL" else "POST", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = FrianzoBlue, maxLines = 1, softWrap = false)
            Spacer(Modifier.height(2.dp))
            Text(previewText?.takeIf { it.isNotBlank() } ?: if (contentType == "reel") "Your reel" else "Your post", fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun BoostScreen(api2: FrianzoApi2, launch: BoostLaunch, onClose: () -> Unit, onOpenMyBoosts: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf<BoostConfigData?>(null) }
    var configError by remember { mutableStateOf<String?>(null) }
    // Boost Again opens straight on the payment step (purpose + plan
    // pre-selected); a fresh boost starts at Purpose.
    var step by remember { mutableStateOf(if (launch.startAtPayment) 2 else if (launch.contentId == null) -1 else 0) }
    // The item being boosted: preselected by the caller, or picked on
    // the choose step (step -1) in choose-item mode.
    var chosenType by remember { mutableStateOf(launch.contentType) }
    var chosenId by remember { mutableStateOf(launch.contentId) }
    var chosenText by remember { mutableStateOf(launch.previewText) }
    var chosenImage by remember { mutableStateOf(launch.previewImageUrl) }
    var purpose by remember { mutableStateOf(launch.presetPurpose) }
    var planDays by remember { mutableStateOf(launch.presetPlanDays) }
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
        step == -1 -> "Boost"
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
        BoostHeader(title, onClose = { if (step > 0) step -= 1 else if (step == 0 && launch.contentId == null) step = -1 else onClose() })
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
                val plans = cfg.plans ?: emptyList()
                val methods = cfg.paymentMethods?.takeIf { it.isNotEmpty() } ?: FALLBACK_METHODS
                val trial = cfg.firstBoostTrial
                val selectedMethod = methods.firstOrNull { it.id == methodId } ?: methods.first()
                val selectedPlan = plans.firstOrNull { it.days == planDays }
                fun planPrice(plan: BoostPlanData): Int = trial?.takeIf { it.available && it.planDays == plan.days }?.amountPkr ?: plan.amountPkr
                if (step == -1) {
                    // Choose-item mode (profile Boost button): pick one of
                    // my own posts/reels, then the normal steps follow.
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
                                            .clickable { chosenType = item.contentType; chosenId = item.contentId; chosenText = item.previewText; chosenImage = item.previewImageUrl; step = 0 }
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
                BoostStepIndicator(step, onStep = { step = it })
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                    BoostPreviewCard(chosenType ?: "post", chosenText, chosenImage)
                    Spacer(Modifier.height(20.dp))
                    when (step) {
                        0 -> {
                            Text("Why are you boosting?", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(10.dp))
                            purposes.forEach { p ->
                                val sel = purpose == p.id
                                Box(
                                    Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(12.dp))
                                        .background(if (sel) FrianzoBlueTint else MaterialTheme.colorScheme.surface)
                                        .border(1.dp, if (sel) FrianzoBlue else FrianzoBorder, RoundedCornerShape(12.dp))
                                        .clickable { purpose = p.id }.padding(16.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(p.label ?: boostPurposeLabel(p.id), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                                            p.description?.let { Spacer(Modifier.height(2.dp)); Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                        }
                                        if (sel) Icon(Icons.Filled.CheckCircle, contentDescription = "Selected", tint = FrianzoBlue, modifier = Modifier.size(22.dp))
                                    }
                                }
                            }
                        }
                        1 -> {
                            Text("Choose your budget", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(10.dp))
                            plans.forEach { plan ->
                                val sel = planDays == plan.days
                                val trialPrice = trial?.takeIf { it.available && it.planDays == plan.days }?.amountPkr
                                Box(
                                    Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(12.dp))
                                        .background(if (sel) FrianzoBlueTint else MaterialTheme.colorScheme.surface)
                                        .border(1.dp, if (sel) FrianzoBlue else FrianzoBorder, RoundedCornerShape(12.dp))
                                        .clickable { planDays = plan.days }.padding(16.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("Rs ${boostFmt(trialPrice ?: plan.amountPkr)}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = FrianzoBlue, maxLines = 1, softWrap = false)
                                                if (trialPrice != null) {
                                                    Spacer(Modifier.width(8.dp))
                                                    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(FrianzoBlue).padding(horizontal = 6.dp, vertical = 2.dp)) { Text("First boost", fontSize = 11.sp, color = Color.White, maxLines = 1, softWrap = false) }
                                                    Spacer(Modifier.width(6.dp))
                                                    Text("Rs ${boostFmt(plan.amountPkr)}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                                                }
                                            }
                                            Text(if (plan.days == 1) "1 day" else "${plan.days} days", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                                            Spacer(Modifier.height(6.dp))
                                            Text("Andazan views: ${boostFmt(plan.estViewsMin)} - ${boostFmt(plan.estViewsMax)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                                            Text("Andazan clicks: ${boostFmt(plan.estClicksMin)} - ${boostFmt(plan.estClicksMax)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                                        }
                                        if (sel) Icon(Icons.Filled.CheckCircle, contentDescription = "Selected", tint = FrianzoBlue, modifier = Modifier.size(22.dp))
                                    }
                                }
                            }
                        }
                        else -> {
                            // Payment step: chosen purpose + plan summary.
                            Text(
                                "${boostPurposeLabel(purpose)} · ${selectedPlan?.let { if (it.days == 1) "1 day" else "${it.days} days" } ?: ""} · Rs ${selectedPlan?.let { boostFmt(planPrice(it)) } ?: ""}",
                                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = FrianzoBlue, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(12.dp))
                            Text("Pay with", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
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
                            Spacer(Modifier.height(4.dp))
                            if (shotUri != null) {
                                AsyncImage(model = shotUri, contentDescription = "Payment screenshot", modifier = Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                                TextButton(onClick = { pickGate() }, enabled = !uploading, contentPadding = PaddingValues(horizontal = 4.dp)) { Text(if (uploading) "Uploading…" else "Change screenshot", maxLines = 1, softWrap = false) }
                            } else {
                                OutlinedButton(onClick = { pickGate() }, enabled = !uploading, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(28.dp)) { Text(if (uploading) "Uploading…" else "Upload payment screenshot", maxLines = 1, softWrap = false) }
                            }
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(value = txnId, onValueChange = { txnId = it }, label = { Text("Transaction ID (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            formError?.let { Spacer(Modifier.height(10.dp)); Text(it, color = Color.Red, fontSize = 13.sp) }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                // Sticky bottom action: Continue between steps, Submit on
                // the payment step; disabled until the step is valid.
                HorizontalDivider(color = FrianzoBorder)
                Box(Modifier.fillMaxWidth().padding(16.dp)) {
                    if (step < 2) {
                        Button(
                            enabled = if (step == 0) purpose != null else planDays != null,
                            onClick = { step += 1 },
                            colors = ButtonDefaults.buttonColors(containerColor = FrianzoBlue),
                            shape = RoundedCornerShape(28.dp),
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) { Text("Continue", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false) }
                    } else {
                        Button(
                            enabled = shotUrl != null && !uploading && !submitting,
                            onClick = {
                                val p = purpose ?: return@Button
                                val d = planDays ?: return@Button
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
                                                purpose = p,
                                                planDays = d,
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
                        ) { Text(if (submitting) "Sending…" else "Submit Boost Request", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false) }
                    }
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
                Text("${if (b.planDays == 1) "1 day" else "${b.planDays} days"} · Rs ${boostFmt(b.amountPkr)} · ${boostPurposeLabel(b.purpose)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
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
                            startAtPayment = true,
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
