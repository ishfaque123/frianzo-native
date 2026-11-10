package com.frianzo.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import coil.request.ImageRequest
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.frianzo.app.R

/**
 * Frianzo web-app branding, matched to frianzo.online (see the web audit):
 * brand blue = Tailwind blue-600 (#2563EB), page background = slate-50
 * (#F8FAFC), cards = white with a soft shadow, headings = slate-900
 * (#0F172A), secondary text = slate-500 (#64748B), borders = slate-200.
 * The web font stack is the system sans stack; on Android that is Roboto
 * via FontFamily.Default, so no custom font is bundled.
 */
val FrianzoBlue = Color(0xFF2563EB)
val FrianzoBlueDark = Color(0xFF1D4ED8)
val FrianzoBlueTint = Color(0xFFEFF6FF)
val FrianzoPageBackground = Color(0xFFF8FAFC)
val FrianzoTextDark = Color(0xFF0F172A)
val FrianzoTextSecondary = Color(0xFF64748B)
val FrianzoBorder = Color(0xFFE2E8F0)
val FrianzoBadgeRed = Color(0xFFDC2626)
val FrianzoSuccess = Color(0xFF16A34A)

/**
 * v3.1 DESIGN SYSTEM — one consistent look for every screen (Facebook /
 * live-web style). Decide once, use everywhere:
 * - Buttons: ONE height 44dp, ONE radius 12dp, 15sp SemiBold. Primary =
 *   blue fill + white text; secondary = outlined (blue text, slate border);
 *   danger = red ONLY for delete; text buttons = blue, no background.
 *   Pills/chips keep the 28dp radius (feed tabs, filters, interests).
 * - Colours: FrianzoBlue primary; slate-900 text, slate-500 secondary,
 *   slate-200 borders, slate-50 page bg; red only destructive, green only
 *   success/online. No random per-screen colours.
 * - Pages: 16dp horizontal padding, 20sp bold screen titles, 16sp bold
 *   section titles, 15sp body, 13sp caption, 12sp small.
 * - Avatars: feed 40dp, comment 32dp, story/highlight 60-64dp, profile 96dp.
 * - Cards: 16dp radius, 1dp elevation; list rows 12dp vertical spacing.
 * - Long names/titles: maxLines=1 + ellipsis, never wrap mid-word.
 */
object FrianzoUi {
    val ButtonHeight = 44.dp
    val ButtonRadius = 12.dp
    val ChipRadius = 28.dp
    val CardRadius = 16.dp
    val ScreenPadding = 16.dp
    val RowSpacing = 12.dp
    val SectionSpacing = 16.dp
    val AvatarFeed = 40.dp
    val AvatarComment = 32.dp
    val AvatarStory = 64.dp
    val AvatarProfile = 96.dp
    val TitleSize = 20.sp
    val SectionTitleSize = 16.sp
    val BodySize = 15.sp
    val CaptionSize = 13.sp
    val SmallSize = 12.sp
}

private val FrianzoDarkColorScheme = darkColorScheme(
    primary = FrianzoBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1E3A8A),
    onPrimaryContainer = Color.White,
    secondary = FrianzoBlue,
    secondaryContainer = Color(0xFF1E3A8A),
    background = Color(0xFF0F172A),
    onBackground = Color.White,
    surface = Color(0xFF1E293B),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF334155),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF475569),
    error = Color(0xFFF87171),
)

private val FrianzoColorScheme = lightColorScheme(
    primary = FrianzoBlue,
    onPrimary = Color.White,
    primaryContainer = FrianzoBlueTint,
    onPrimaryContainer = FrianzoBlueDark,
    secondary = FrianzoBlue,
    secondaryContainer = FrianzoBlueTint,
    background = FrianzoPageBackground,
    onBackground = FrianzoTextDark,
    surface = Color.White,
    onSurface = FrianzoTextDark,
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = FrianzoTextSecondary,
    outline = FrianzoBorder,
    error = FrianzoBadgeRed,
)

private val FrianzoShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

// v5.18.5 dark-mode fix: the text colours are theme-aware. Light branch
// returns exactly the old constants (FrianzoTextDark #0F172A /
// FrianzoTextSecondary #64748B — the light scheme's onSurface /
// onSurfaceVariant), so light mode is pixel-identical. Dark branch uses
// the dark scheme's onSurface (White) / onSurfaceVariant (#94A3B8), so
// title/body text is visible on the dark (#0F172A) background. A plain
// colour-strip was NOT safe: bodySmall without a colour would have become
// onSurface (#0F172A) in light mode instead of secondary grey.
// displayMedium (FrianzoBlue) and labelLarge (no colour) are untouched.
private fun frianzoTypography(darkTheme: Boolean) = Typography(
    displayMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.ExtraBold,
        fontSize = 36.sp, lineHeight = 40.sp, color = FrianzoBlue,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,
        fontSize = 20.sp, lineHeight = 26.sp,
        color = if (darkTheme) FrianzoDarkColorScheme.onSurface else FrianzoTextDark,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,
        fontSize = 16.sp, lineHeight = 22.sp,
        color = if (darkTheme) FrianzoDarkColorScheme.onSurface else FrianzoTextDark,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = 15.sp, lineHeight = 21.sp,
        color = if (darkTheme) FrianzoDarkColorScheme.onSurface else FrianzoTextDark,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = 13.sp, lineHeight = 18.sp,
        color = if (darkTheme) FrianzoDarkColorScheme.onSurfaceVariant else FrianzoTextSecondary,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp, lineHeight = 21.sp,
    ),
)

// ---------- v3.4 Theme + Data Saver (local-only, SharedPreferences) ----------
// The web Theme page is local-only (System/Light/Dark); there is no
// backend theme field, so the choice lives on the phone. FrianzoThemeState
// is a Compose State read inside FrianzoTheme, so changing it in Settings
// re-themes the whole app immediately without a restart. MainActivity
// initialises it from prefs once at startup.
object FrianzoThemeState {
    var mode by androidx.compose.runtime.mutableStateOf("system")
    var dataSaver by androidx.compose.runtime.mutableStateOf(false)
}

private const val THEME_PREFS = "frianzo_prefs"
const val THEME_MODE_SYSTEM = "system"
const val THEME_MODE_LIGHT = "light"
const val THEME_MODE_DARK = "dark"

fun readThemeMode(context: android.content.Context): String =
    context.getSharedPreferences(THEME_PREFS, android.content.Context.MODE_PRIVATE)
        .getString("theme_mode", THEME_MODE_SYSTEM) ?: THEME_MODE_SYSTEM

fun saveThemeMode(context: android.content.Context, mode: String) {
    context.getSharedPreferences(THEME_PREFS, android.content.Context.MODE_PRIVATE)
        .edit().putString("theme_mode", mode).apply()
    FrianzoThemeState.mode = mode
}

fun readDataSaver(context: android.content.Context): Boolean =
    context.getSharedPreferences(THEME_PREFS, android.content.Context.MODE_PRIVATE)
        .getBoolean("data_saver", false)

fun saveDataSaver(context: android.content.Context, enabled: Boolean) {
    context.getSharedPreferences(THEME_PREFS, android.content.Context.MODE_PRIVATE)
        .edit().putBoolean("data_saver", enabled).apply()
    FrianzoThemeState.dataSaver = enabled
}

@Composable
fun FrianzoTheme(content: @Composable () -> Unit) {
    // v3.4: the theme now follows the Settings picker (System/Light/Dark).
    // System = phone setting; Light/Dark force that scheme. Default is
    // System, which on most phones is light — the web look is preserved
    // unless the user picks Dark in Settings.
    val darkTheme = when (FrianzoThemeState.mode) {
        THEME_MODE_DARK -> true
        THEME_MODE_LIGHT -> false
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (darkTheme) FrianzoDarkColorScheme else FrianzoColorScheme,
        shapes = FrianzoShapes,
        typography = frianzoTypography(darkTheme),
        content = content,
    )
}

/** Real Frianzo logo (the glossy 3D "F" from frianzo.online/logo.png),
 * shown on the login screen at the web's 80dp size. The asset is a plain
 * PNG in res/drawable/, so painterResource draws a BitmapDrawable — safe
 * at composition time. Do NOT point this at R.mipmap.ic_launcher: the
 * launcher icon is an adaptive-icon XML on API 26+, and Compose
 * painterResource cannot draw an AdaptiveIconDrawable — it throws at
 * composition time, which crashed startup on the login screen. The
 * launcher icon itself is untouched (home-screen icon and app name are
 * unchanged). */
@Composable
fun FrianzoLogo(size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.logo),
        contentDescription = "Frianzo",
        modifier = modifier.size(size),
        contentScale = ContentScale.Fit,
    )
}

/**
 * Avatar thumbnail (2026-11-02): the backend stores a ~96px JPEG next to
 * every newly uploaded avatar, under the full avatar's R2 key with "-thumb"
 * inserted before the extension (avatars/<uuid>.jpg -> avatars/<uuid>-thumb.jpg).
 * Only R2 avatar URLs get a thumbnail candidate; external URLs (e.g. Google
 * photos) are returned unchanged, and URLs without an extension are too.
 * (Port of web Avatar.tsx avatarThumbUrl.)
 */
fun avatarThumbUrl(url: String?): String? {
    if (url == null) return null
    val marker = "/avatars/"
    val idx = url.indexOf(marker)
    if (idx < 0) return null
    val after = url.substring(idx + marker.length)
    val dot = after.lastIndexOf('.')
    if (dot <= 0) return null
    val ext = after.substring(dot)
    if (ext.contains('/') || ext.contains('?') || ext.contains('#')) return null
    return url.substring(0, idx + marker.length + dot) + "-thumb" + ext
}

/** Circular avatar like the web feed: photo if present, light-blue circle otherwise.
 * v5.18: optional green online dot (web chat list shows one on the avatar).
 * v5.23 BLUR-UP (Facebook-style): the ~96px thumbnail loads first — at 40dp it
 * IS the display size, so the circle appears near-instantly instead of waiting
 * for the full file. Larger avatars (profile header, chat header) then load the
 * full image over the thumb with a crossfade, so the circle sharpens instead of
 * popping in. If the thumb does not exist (avatars uploaded before thumbnails),
 * the full image loads once as a fallback — never a blank circle. */
@Composable
fun FrianzoAvatar(model: String?, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier, online: Boolean = false) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val thumb = avatarThumbUrl(model)
    // The thumb is ~96px: up to ~1.5x upscale it is indistinguishable from the
    // full file, so small circles load the thumb only — loading the full file as
    // well would re-introduce the full-size download this change exists to avoid.
    val needsFull = px > 144
    var thumbFailed by remember(model) { mutableStateOf(false) }
    val showFull = model != null && thumb != null && (needsFull || thumbFailed)
    val baseModel = thumb ?: model
    Box(
        modifier = modifier.size(size).clip(CircleShape)
            .background(FrianzoBlueTint),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(baseModel).size(px, px).crossfade(true).build(),
            contentDescription = null,
            modifier = Modifier.size(size).clip(CircleShape),
            contentScale = ContentScale.Crop,
            onError = { if (thumb != null && baseModel == thumb) thumbFailed = true },
        )
        if (showFull) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(model).size(px, px).crossfade(true).build(),
                contentDescription = null,
                modifier = Modifier.size(size).clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        }
        if (online) {
            Box(
                Modifier.align(androidx.compose.ui.Alignment.BottomEnd)
                    .size((size.value * 0.32f).dp)
                    .clip(CircleShape)
                    .background(Color(0xFF22C55E))
                    .border(2.dp, Color.White, CircleShape)
            )
        }
    }
}

