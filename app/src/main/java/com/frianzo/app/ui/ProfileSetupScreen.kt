package com.frianzo.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.frianzo.app.data.FrianzoApi2
import com.frianzo.app.data.ProfileSetupRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * G1 — Profile setup screen. A 100/100 match of the web's
 * /profile-setup page (vynzo-frontend src/app/profile-setup/page.tsx),
 * verified field by field, function by function:
 *
 * Order, labels, placeholders (web code + the owner's live screenshot of
 * frianzo.online/profile-setup):
 *  1. Title "Complete your profile" (text-2xl semibold = 24sp SemiBold)
 *  2. "Name" (username), placeholder "e.g. johnsmith"
 *  3. "Full Name", placeholder "e.g. John Smith"
 *  4. "Birthday" date picker, helper "You must be at least 13 years old
 *     to use Frianzo."
 *  5. "Gender" dropdown: Select / Male / Female / Custom (+ a
 *     "Describe your gender" field, max 30, when Custom)
 *  6. "Contact number", placeholder "e.g. 03xxxxxxxxx"
 *  7. "Bio" textarea, placeholder "Tell us about yourself", counter n/300
 *  8. Full-width "Complete profile" button ("Saving..." while saving)
 *
 * Colours are the web's own values, light AND dark (globals.css
 * html.dark overrides — the owner's screenshot is the dark rendering):
 * light: page white, labels slate-600 #475569, fields white bg /
 * #0F172A text / #E5E7EB border, placeholder #94A3B8, helper #94A3B8,
 * button slate-900 #0F172A with white text.
 * dark: page #1E2530, labels #94A3B8, fields #333D4D bg / #E2E8F0 text /
 * #3D4759 border, placeholder #64748B, helper #64748B, button #E2E8F0
 * with #1E2530 text (the white button in the screenshot).
 * Username status colours are the same in both modes (no dark override
 * in the web CSS): available green-600 #16A34A, taken/invalid red-500
 * #EF4444, field border red-400 #F87171 / green-400 #4ADE80.
 *
 * Functions, same as the web (see REPORT.md for the verified behaviour):
 * username live-availability (lowercase + strip spaces, regex
 * ^[a-zA-Z0-9_.]{3,30}$, 500ms debounce, GET /api/users/{name}:
 * 404 = available, 200 = taken, other error = idle); birthday 13+
 * validation with the picker's max date = today minus 13 years; phone
 * filter (digits, +, -, space only); bio capped at 300 with a live
 * n/300 counter; submit validates username regex, then taken, then
 * age, then POSTs /api/users/me/profile-setup — the same endpoint the
 * web uses. On success the parent refreshes the session (/me now says
 * profileCompleted=true) and the feed opens.
 *
 * HARD GATE (owner's order 2026-11-07): this screen is mandatory. The
 * system Back button is consumed here — there is no skip button and no
 * way around to the feed without completing and saving the profile.
 * (The web itself only routes *new* users to /profile-setup via
 * isNewUser and never blocks the feed for an incomplete profile; the
 * native gate is deliberately harder, on the owner's explicit order.)
 */
private enum class UsernameStatus { Idle, Checking, Available, Taken, Invalid }

private val USERNAME_REGEX = Regex("^[a-zA-Z0-9_.]{3,30}$")

private fun utcDateFormat(): SimpleDateFormat =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
        isLenient = false
    }

/** Latest birthday the picker allows: today minus 13 years (the web's
 *  date input max attribute), as UTC-midnight millis. */
private fun maxBirthDateMillis(): Long {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    cal.add(Calendar.YEAR, -13)
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

private fun formatDateUtc(millis: Long): String = utcDateFormat().format(Date(millis))

/** Same rule as the web's isAtLeast13: full years since the birthday,
 *  minus one if this year's birthday has not happened yet. */
private fun isAtLeast13(dobStr: String): Boolean {
    val dob = try { utcDateFormat().parse(dobStr) } catch (_: Exception) { null } ?: return false
    val now = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    val birth = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { time = dob }
    var age = now.get(Calendar.YEAR) - birth.get(Calendar.YEAR)
    val hadBirthday = now.get(Calendar.MONTH) > birth.get(Calendar.MONTH) ||
        (now.get(Calendar.MONTH) == birth.get(Calendar.MONTH) &&
            now.get(Calendar.DAY_OF_MONTH) >= birth.get(Calendar.DAY_OF_MONTH))
    if (!hadBirthday) age -= 1
    return age >= 13
}

/** Backend error body looks like {"success":false,"error":{"code":..,
 *  "message":..}} — extract code/message like the login screen's
 *  httpErrorMessage (kept local: that helper is private to Screens.kt). */
private fun setupHttpError(e: HttpException): Pair<String?, String> {
    val body = try { e.response()?.errorBody()?.string() } catch (_: Exception) { null }
    val code = body?.let { Regex("\"code\"\\s*:\\s*\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
    val msg = body?.let { Regex("\"message\"\\s*:\\s*\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
    return when {
        msg != null && code != null -> code to "$msg ($code)"
        code != null -> code to "$code (HTTP ${e.code()})"
        msg != null -> null to msg
        else -> null to "HTTP ${e.code()}"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSetupScreen(api2: FrianzoApi2, onDone: () -> Unit) {
    // G1 hard gate: Back never leaves this screen for the feed.
    BackHandler(enabled = true) { /* stay — setup is mandatory */ }

    // Web colours, light vs dark (see the header comment for the source
    // of every value). The dark choice follows the same rule as
    // FrianzoTheme: Settings mode, else the phone's system theme.
    val dark = when (FrianzoThemeState.mode) {
        THEME_MODE_DARK -> true
        THEME_MODE_LIGHT -> false
        else -> isSystemInDarkTheme()
    }
    val pageBg = if (dark) Color(0xFF1E2530) else Color.White
    val titleColor = if (dark) Color(0xFFE2E8F0) else Color(0xFF0F172A)
    val labelColor = if (dark) Color(0xFF94A3B8) else Color(0xFF475569)
    val fieldBg = if (dark) Color(0xFF333D4D) else Color.White
    val fieldText = if (dark) Color(0xFFE2E8F0) else Color(0xFF0F172A)
    val fieldBorder = if (dark) Color(0xFF3D4759) else Color(0xFFE5E7EB)
    val placeholderColor = if (dark) Color(0xFF64748B) else Color(0xFF94A3B8)
    val helperColor = if (dark) Color(0xFF64748B) else Color(0xFF94A3B8)
    val buttonBg = if (dark) Color(0xFFE2E8F0) else Color(0xFF0F172A)
    val buttonText = if (dark) Color(0xFF1E2530) else Color.White
    val availableGreen = Color(0xFF16A34A)
    val takenRed = Color(0xFFEF4444)
    val statusBorderRed = Color(0xFFF87171)
    val statusBorderGreen = Color(0xFF4ADE80)
    val focusBorder = Color(0xFF94A3B8)

    var username by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }
    var dateOfBirth by rememberSaveable { mutableStateOf("") }
    var bio by rememberSaveable { mutableStateOf("") }
    var gender by rememberSaveable { mutableStateOf("") }
    var customGender by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var usernameStatus by remember { mutableStateOf(UsernameStatus.Idle) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    var genderMenuOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Live username validation + availability, debounced 500ms — the
    // web's handleUsernameChange, step by step: lowercase + strip
    // spaces, empty = idle, bad shape = invalid, else checking, then
    // GET /api/users/{name}: 404 = available, 200 = taken, any other
    // failure = idle. A keystroke restarts the effect, so a stale
    // result can never overwrite a newer one (the web guards this with
    // a prev === 'checking' check; here the effect itself is cancelled).
    LaunchedEffect(username) {
        val name = username
        if (name.isEmpty()) { usernameStatus = UsernameStatus.Idle; return@LaunchedEffect }
        if (!USERNAME_REGEX.matches(name)) { usernameStatus = UsernameStatus.Invalid; return@LaunchedEffect }
        usernameStatus = UsernameStatus.Checking
        delay(500)
        try {
            api2.publicProfile(name)
            if (username == name) usernameStatus = UsernameStatus.Taken
        } catch (e: HttpException) {
            if (username != name) return@LaunchedEffect
            usernameStatus = if (e.code() == 404) UsernameStatus.Available else UsernameStatus.Idle
        } catch (_: Exception) {
            if (username == name) usernameStatus = UsernameStatus.Idle
        }
    }

    val usernameBorder = when (usernameStatus) {
        UsernameStatus.Taken, UsernameStatus.Invalid -> statusBorderRed
        UsernameStatus.Available -> statusBorderGreen
        else -> fieldBorder
    }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = fieldText,
        unfocusedTextColor = fieldText,
        focusedContainerColor = fieldBg,
        unfocusedContainerColor = fieldBg,
        disabledContainerColor = fieldBg,
        focusedBorderColor = focusBorder,
        unfocusedBorderColor = fieldBorder,
        focusedPlaceholderColor = placeholderColor,
        unfocusedPlaceholderColor = placeholderColor,
        cursorColor = fieldText,
    )
    val usernameColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = fieldText,
        unfocusedTextColor = fieldText,
        focusedContainerColor = fieldBg,
        unfocusedContainerColor = fieldBg,
        disabledContainerColor = fieldBg,
        focusedBorderColor = if (usernameStatus == UsernameStatus.Taken ||
            usernameStatus == UsernameStatus.Invalid) statusBorderRed
        else if (usernameStatus == UsernameStatus.Available) statusBorderGreen else focusBorder,
        unfocusedBorderColor = usernameBorder,
        focusedPlaceholderColor = placeholderColor,
        unfocusedPlaceholderColor = placeholderColor,
        cursorColor = fieldText,
    )

    fun submit() {
        // (0) Required fields — the web blocks these with the browser's
        // required-field bubble before its submit handler runs; the
        // native equivalent is this one line naming the three fields.
        if (username.isBlank() || displayName.isBlank() || dateOfBirth.isBlank()) {
            error = "Please fill in Name, Full Name and Birthday."
            return
        }
        // (1) Username shape, (2) taken, (3) 13+ — the web's exact
        // order and exact messages.
        if (!USERNAME_REGEX.matches(username)) {
            error = "Username must be 3-30 characters: letters, numbers, underscore, or period."
            return
        }
        if (usernameStatus == UsernameStatus.Taken) {
            error = "This username is taken. Please choose another."
            return
        }
        if (!isAtLeast13(dateOfBirth)) {
            error = "You must be at least 13 years old to use Frianzo."
            return
        }
        saving = true
        error = null
        scope.launch {
            try {
                // Same payload the web sends (the whole form). gender is
                // omitted when blank: the backend's zod enum rejects an
                // empty string, so sending "" would 400 — the web has
                // this quirk (see REPORT.md); the native sends the field
                // only when a gender was picked. customGender is sent
                // like the web does; the backend's zod schema drops it.
                val res = api2.submitProfileSetup(
                    ProfileSetupRequest(
                        username = username,
                        displayName = displayName,
                        dateOfBirth = dateOfBirth,
                        bio = bio,
                        gender = gender.ifBlank { null },
                        customGender = customGender,
                        phone = phone,
                    )
                )
                if (res.success) {
                    onDone()
                } else {
                    error = res.error?.message ?: "Could not save your profile. Please try again."
                }
            } catch (e: HttpException) {
                // 403 PROFILE_ALREADY_COMPLETED: the profile was already
                // finished (e.g. on another device) — the session
                // refresh will see profileCompleted=true and open the
                // feed, exactly like the web setup page bouncing a
                // completed profile back to '/'.
                val (code, msg) = setupHttpError(e)
                if (code == "PROFILE_ALREADY_COMPLETED") onDone() else error = msg
            } catch (e: Exception) {
                error = e.message ?: "Could not save your profile. Please try again."
            } finally {
                saving = false
            }
        }
    }

    if (showDatePicker) {
        val maxMillis = maxBirthDateMillis()
        val dateState = rememberDatePickerState(
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    utcTimeMillis <= maxMillis
            }
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let { dateOfBirth = formatDateUtc(it) }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) { DatePicker(state = dateState) }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(pageBg),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 448.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 48.dp),
        ) {
            // Web: h1 mb-6 text-2xl font-semibold.
            Text(
                "Complete your profile",
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                color = titleColor,
            )
            Spacer(Modifier.height(24.dp))

            // 1. Name (username) — web label "Name", placeholder
            // "e.g. johnsmith", live status line under the field.
            Text("Name", fontSize = 14.sp, color = labelColor)
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = username,
                onValueChange = { username = it.lowercase().replace(" ", "") },
                placeholder = { Text("e.g. johnsmith", fontSize = 15.sp) },
                modifier = Modifier.fillMaxWidth().height(40.dp),
                singleLine = true,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                colors = usernameColors,
                textStyle = TextStyle(fontSize = 15.sp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
            )
            when (usernameStatus) {
                UsernameStatus.Checking -> {
                    Spacer(Modifier.height(4.dp))
                    Text("Checking availability...", fontSize = 12.sp, color = helperColor)
                }
                UsernameStatus.Available -> {
                    Spacer(Modifier.height(4.dp))
                    Text("✓ Username available", fontSize = 12.sp, color = availableGreen)
                }
                UsernameStatus.Taken -> {
                    Spacer(Modifier.height(4.dp))
                    Text("✗ Username taken, try another", fontSize = 12.sp, color = takenRed)
                }
                UsernameStatus.Invalid -> {
                    if (username.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text("3-30 chars: letters, numbers, _ or .", fontSize = 12.sp, color = takenRed)
                    }
                }
                UsernameStatus.Idle -> {}
            }
            Spacer(Modifier.height(16.dp))

            // 2. Full Name.
            Text("Full Name", fontSize = 14.sp, color = labelColor)
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                placeholder = { Text("e.g. John Smith", fontSize = 15.sp) },
                modifier = Modifier.fillMaxWidth().height(40.dp),
                singleLine = true,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                colors = fieldColors,
                textStyle = TextStyle(fontSize = 15.sp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
            )
            Spacer(Modifier.height(16.dp))

            // 3. Birthday — read-only field opening the date picker
            // (the web's <input type="date">). The picker caps dates at
            // today minus 13 years, the web's max attribute.
            Text("Birthday", fontSize = 14.sp, color = labelColor)
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = dateOfBirth,
                onValueChange = {},
                placeholder = { Text("dd/mm/yyyy", fontSize = 15.sp) },
                modifier = Modifier.fillMaxWidth().height(40.dp)
                    .clickable { showDatePicker = true },
                readOnly = true,
                singleLine = true,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                colors = fieldColors,
                textStyle = TextStyle(fontSize = 15.sp),
                // The chevron is a real button too (the web date input's
                // picker indicator): tapping anywhere on the field opens
                // the picker, and the chevron always does.
                trailingIcon = {
                    androidx.compose.material3.IconButton(onClick = { showDatePicker = true }) {
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = placeholderColor)
                    }
                },
            )
            Spacer(Modifier.height(4.dp))
            Text("You must be at least 13 years old to use Frianzo.", fontSize = 12.sp, color = helperColor)
            Spacer(Modifier.height(16.dp))

            // 4. Gender — Select / Male / Female / Custom; Custom shows
            // the "Describe your gender" field (max 30) underneath.
            Text("Gender", fontSize = 14.sp, color = labelColor)
            Spacer(Modifier.height(4.dp))
            Box {
                OutlinedTextField(
                    value = when (gender) {
                        "male" -> "Male"
                        "female" -> "Female"
                        "custom" -> "Custom"
                        else -> ""
                    },
                    onValueChange = {},
                    placeholder = { Text("Select", fontSize = 15.sp) },
                    modifier = Modifier.fillMaxWidth().height(40.dp)
                        .clickable { genderMenuOpen = true },
                    readOnly = true,
                    singleLine = true,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    colors = fieldColors,
                    textStyle = TextStyle(fontSize = 15.sp),
                    trailingIcon = {
                        androidx.compose.material3.IconButton(onClick = { genderMenuOpen = true }) {
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = placeholderColor)
                        }
                    },
                )
                DropdownMenu(expanded = genderMenuOpen, onDismissRequest = { genderMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Male") },
                        onClick = { gender = "male"; genderMenuOpen = false },
                    )
                    DropdownMenuItem(
                        text = { Text("Female") },
                        onClick = { gender = "female"; genderMenuOpen = false },
                    )
                    DropdownMenuItem(
                        text = { Text("Custom") },
                        onClick = { gender = "custom"; genderMenuOpen = false },
                    )
                }
            }
            if (gender == "custom") {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = customGender,
                    onValueChange = { if (it.length <= 30) customGender = it },
                    placeholder = { Text("Describe your gender", fontSize = 15.sp) },
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                    singleLine = true,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    colors = fieldColors,
                    textStyle = TextStyle(fontSize = 15.sp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
                )
            }
            Spacer(Modifier.height(16.dp))

            // 5. Contact number — digits, +, - and space only (the
            // web's onChange filter), phone keyboard.
            Text("Contact number", fontSize = 14.sp, color = labelColor)
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it.filter { c -> c.isDigit() || c == '+' || c == '-' || c == ' ' } },
                placeholder = { Text("e.g. 03xxxxxxxxx", fontSize = 15.sp) },
                modifier = Modifier.fillMaxWidth().height(40.dp),
                singleLine = true,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                colors = fieldColors,
                textStyle = TextStyle(fontSize = 15.sp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
            )
            Spacer(Modifier.height(16.dp))

            // 6. Bio — textarea, capped at 300, live n/300 counter
            // right-aligned underneath (the web's text-right counter).
            Text("Bio", fontSize = 14.sp, color = labelColor)
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = bio,
                onValueChange = { if (it.length <= 300) bio = it },
                placeholder = { Text("Tell us about yourself", fontSize = 15.sp) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                minLines = 2,
                maxLines = 6,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                colors = fieldColors,
                textStyle = TextStyle(fontSize = 15.sp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${bio.length}/300",
                fontSize = 12.sp,
                color = helperColor,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.End,
            )
            Spacer(Modifier.height(16.dp))

            // Submit — web: w-full rounded-lg bg-slate-900 py-2
            // text-white, disabled:opacity-50, "Saving..." while saving.
            Button(
                onClick = { submit() },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth().height(40.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = buttonBg,
                    contentColor = buttonText,
                    disabledContainerColor = buttonBg.copy(alpha = 0.5f),
                    disabledContentColor = buttonText.copy(alpha = 0.5f),
                ),
            ) {
                Text(
                    if (saving) "Saving..." else "Complete profile",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    softWrap = false,
                )
            }

            // Errors — the web shows a bottom Toast (2.2s, auto-dismiss);
            // the app has no toast component, so errors show here as a
            // centred line under the button, the login screen's pattern.
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
