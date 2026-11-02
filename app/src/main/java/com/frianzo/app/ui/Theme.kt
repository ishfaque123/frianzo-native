package com.frianzo.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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

private val FrianzoTypography = Typography(
    displayMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.ExtraBold,
        fontSize = 36.sp, lineHeight = 40.sp, color = FrianzoBlue,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,
        fontSize = 20.sp, lineHeight = 26.sp, color = FrianzoTextDark,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,
        fontSize = 16.sp, lineHeight = 22.sp, color = FrianzoTextDark,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = 15.sp, lineHeight = 21.sp, color = FrianzoTextDark,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = 13.sp, lineHeight = 18.sp, color = FrianzoTextSecondary,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp, lineHeight = 21.sp,
    ),
)

@Composable
fun FrianzoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FrianzoColorScheme,
        shapes = FrianzoShapes,
        typography = FrianzoTypography,
        content = content,
    )
}

/** Blue-F logo tile drawn in code (NOT the launcher icon resource):
 * R.mipmap.ic_launcher is an adaptive-icon XML on API 26+, and Compose
 * painterResource cannot draw an AdaptiveIconDrawable — it throws at
 * composition time, which crashed startup on both the login screen and
 * the app header. Drawing the tile here keeps the launcher icon untouched
 * (home-screen icon and app name are unchanged) while startup can never
 * crash on the logo resource again. */
@Composable
fun FrianzoLogo(size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(size).clip(RoundedCornerShape((size.value * 0.22f).dp))
            .background(FrianzoBlue),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Text(
            "F",
            color = androidx.compose.ui.graphics.Color.White,
            fontWeight = FontWeight.ExtraBold,
            fontSize = (size.value * 0.55f).sp,
        )
    }
}

/** Circular avatar like the web feed: photo if present, light-blue circle otherwise. */
@Composable
fun FrianzoAvatar(model: String?, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    // PERF: decode the avatar at its display size (e.g. 40dp -> ~100px)
    // instead of decoding the full upload, and crossfade in. This cuts
    // decode memory/time per avatar. It does NOT shrink the download
    // itself: the backend serves no avatar-thumbnail URL to the native
    // app (Author has only profilePictureUrl), so a smaller download
    // needs a backend thumbnail field first.
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    Box(
        modifier = modifier.size(size).clip(CircleShape)
            .background(FrianzoBlueTint),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(model).size(px, px).crossfade(true).build(),
            contentDescription = null,
            modifier = Modifier.size(size).clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    }
}

