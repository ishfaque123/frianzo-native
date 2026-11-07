package com.frianzo.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * v5.12 — skeleton placeholders (shimmer grey shapes), the professional
 * replacement for a bare DotsLoading row on FIRST loads. The web uses
 * the same pattern (SkeletonPostCard on the home feed, skeleton rows on
 * notifications). DotsLoading is KEPT for small inline waits (button
 * busy, load-more footer) — skeletons are only for first loads, where a
 * blank screen with dots reads as broken.
 *
 * The grey is MaterialTheme surfaceVariant, so it adapts to light and
 * dark mode. The pulse is a cheap alpha animation (one infinite
 * transition per skeleton, same as DotsLoading's approach).
 */
@Composable
private fun shimmerAlpha(): Float {
    val transition = rememberInfiniteTransition(label = "skeletonShimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeletonAlpha",
    )
    return alpha
}

/** One pulsing grey shape — the primitive every skeleton below is built from. */
@Composable
fun SkeletonBox(modifier: Modifier) {
    Box(
        modifier = modifier
            .alpha(shimmerAlpha())
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
    )
}

/** One pulsing grey circle (avatar placeholder). */
@Composable
fun SkeletonCircle(sizeDp: Int) {
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .alpha(shimmerAlpha())
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

/**
 * A post-card skeleton: avatar circle + author/time lines, two text
 * lines, and an image-height block. Matches the home feed's post card
 * shape; the web shows 3 of these on a cold start with no cache.
 */
@Composable
fun SkeletonPostCard(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SkeletonCircle(40)
            Spacer(Modifier.width(12.dp))
            Column {
                SkeletonBox(Modifier.width(120.dp).height(14.dp))
                Spacer(Modifier.height(6.dp))
                SkeletonBox(Modifier.width(80.dp).height(12.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        SkeletonBox(Modifier.fillMaxWidth().height(14.dp))
        Spacer(Modifier.height(8.dp))
        SkeletonBox(Modifier.fillMaxWidth(0.7f).height(14.dp))
        Spacer(Modifier.height(12.dp))
        SkeletonBox(Modifier.fillMaxWidth().height(200.dp))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            SkeletonBox(Modifier.width(48.dp).height(14.dp))
            SkeletonBox(Modifier.width(48.dp).height(14.dp))
            SkeletonBox(Modifier.width(48.dp).height(14.dp))
        }
    }
}

/**
 * A single list-row skeleton: avatar circle + two lines. Used for
 * notifications (5 rows, like the web's SkeletonNotificationRow), the
 * chat list, and search results.
 */
@Composable
fun SkeletonRow(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkeletonCircle(44)
        Spacer(Modifier.width(12.dp))
        Column {
            SkeletonBox(Modifier.width(180.dp).height(14.dp))
            Spacer(Modifier.height(6.dp))
            SkeletonBox(Modifier.width(120.dp).height(12.dp))
        }
    }
}

/**
 * A profile-header skeleton: cover bar, overlapping avatar circle,
 * name/username bars and four counter bars — the same shape as the real
 * profile header (was: a centered circle + three bars, no cover).
 * Shown while the profile user is loading.
 */
@Composable
fun SkeletonProfileHeader(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(168.dp)) {
            SkeletonBox(Modifier.fillMaxWidth().height(128.dp))
            Box(Modifier.align(Alignment.BottomStart).padding(start = 16.dp)) {
                SkeletonCircle(80)
            }
        }
        Column(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SkeletonCircle(36)
                Spacer(Modifier.width(8.dp))
                SkeletonCircle(36)
            }
            Spacer(Modifier.height(12.dp))
            SkeletonBox(Modifier.width(140.dp).height(18.dp))
            Spacer(Modifier.height(8.dp))
            SkeletonBox(Modifier.width(100.dp).height(12.dp))
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                SkeletonBox(Modifier.width(48.dp).height(16.dp))
                SkeletonBox(Modifier.width(48.dp).height(16.dp))
                SkeletonBox(Modifier.width(48.dp).height(16.dp))
                SkeletonBox(Modifier.width(48.dp).height(16.dp))
            }
        }
    }
}
