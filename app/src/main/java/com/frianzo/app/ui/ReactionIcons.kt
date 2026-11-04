package com.frianzo.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Web-exact reaction icons, drawn in code to match vynzo-frontend
 * ReactionIcon.tsx (Facebook-style 3D SVG faces, NOT system emojis).
 * Gradient circles: like #5BA1FF->#1668DC, love #FF7A9E->#E0245E,
 * haha/wow/sad #FFE066->#F5A623, angry #FF9A5A->#E8590C.
 * Face shapes use the same 36x36 coordinates as the web SVG.
 */
@Composable
fun ReactionIcon(type: String?, size: Dp, modifier: Modifier = Modifier) {
    val t = type ?: "like"
    when (t) {
        "like", "love" -> {
            val f = if (t == "like") Color(0xFF5BA1FF) else Color(0xFFFF7A9E)
            val toC = if (t == "like") Color(0xFF1668DC) else Color(0xFFE0245E)
            Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.size(size)) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(f, toC),
                            center = Offset(this.size.width * 0.35f, this.size.height * 0.28f),
                            radius = this.size.width * 0.8f
                        )
                    )
                }
                Icon(
                    imageVector = if (t == "like") Icons.Filled.ThumbUp else Icons.Filled.Favorite,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(size * 0.55f)
                )
            }
        }
        else -> {
            Canvas(modifier = modifier.size(size)) {
                val w = this.size.width
                val s = w / 36f
                val (from, to) = if (t == "angry") Color(0xFFFF9A5A) to Color(0xFFE8590C)
                    else Color(0xFFFFE066) to Color(0xFFF5A623)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(from, to),
                        center = Offset(w * 0.35f, w * 0.28f),
                        radius = w * 0.8f
                    )
                )
                when (t) {
                    "haha" -> {
                        drawArc(Color(0xFF5B3B00), 115f, 310f, false, Offset(9f * s, 13f * s), Size(6f * s, 5f * s), style = Stroke(2f * s, cap = StrokeCap.Round))
                        drawArc(Color(0xFF5B3B00), 115f, 310f, false, Offset(21f * s, 13f * s), Size(6f * s, 5f * s), style = Stroke(2f * s, cap = StrokeCap.Round))
                        val mouth = Path().apply {
                            moveTo(10f * s, 20f * s); quadraticBezierTo(18f * s, 27f * s, 26f * s, 20f * s); close()
                        }
                        drawPath(mouth, Color(0xFF5B3B00))
                        val teeth = Path().apply {
                            moveTo(12.5f * s, 21.5f * s); quadraticBezierTo(18f * s, 24f * s, 23.5f * s, 21.5f * s)
                            quadraticBezierTo(18f * s, 25f * s, 12.5f * s, 21.5f * s); close()
                        }
                        drawPath(teeth, Color.White)
                    }
                    "wow" -> {
                        drawCircle(Color(0xFF5B3B00), 2.6f * s, Offset(12.5f * s, 14f * s))
                        drawCircle(Color(0xFF5B3B00), 2.6f * s, Offset(23.5f * s, 14f * s))
                        drawOval(Color(0xFF5B3B00), Offset(14f * s, 19f * s), Size(8f * s, 10f * s))
                    }
                    "sad" -> {
                        drawCircle(Color(0xFF5B3B00), 2.2f * s, Offset(12.5f * s, 14f * s))
                        drawCircle(Color(0xFF5B3B00), 2.2f * s, Offset(23.5f * s, 14f * s))
                        val tear = Path().apply {
                            moveTo(27f * s, 12.5f * s); quadraticBezierTo(29.5f * s, 16f * s, 26.5f * s, 19.5f * s)
                            quadraticBezierTo(25.5f * s, 16f * s, 27f * s, 12.5f * s); close()
                        }
                        drawPath(tear, Color(0xFF4AA8FF))
                        drawArc(Color(0xFF5B3B00), 200f, 140f, false, Offset(12f * s, 20f * s), Size(12f * s, 6f * s), style = Stroke(2f * s, cap = StrokeCap.Round))
                    }
                    "angry" -> {
                        drawLine(Color(0xFF5B2500), Offset(9f * s, 12f * s), Offset(15f * s, 14.5f * s), 2.4f * s, StrokeCap.Round)
                        drawLine(Color(0xFF5B2500), Offset(27f * s, 12f * s), Offset(21f * s, 14.5f * s), 2.4f * s, StrokeCap.Round)
                        drawCircle(Color(0xFF5B2500), 2.2f * s, Offset(13f * s, 17.5f * s))
                        drawCircle(Color(0xFF5B2500), 2.2f * s, Offset(23f * s, 17.5f * s))
                        drawArc(Color(0xFF5B2500), 200f, 140f, false, Offset(12f * s, 21f * s), Size(12f * s, 6f * s), style = Stroke(2f * s, cap = StrokeCap.Round))
                    }
                }
            }
        }
    }
}


private fun pickerLabel(type: String): String = when (type) {
    "love" -> "Love"; "haha" -> "Haha"; "wow" -> "Wow"; "sad" -> "Sad"; "angry" -> "Angry"; else -> "Like"
}

/** One web picker item: staggered pop (45ms each, scale 0 -> 1.25 -> 1, 0.35s),
 *  press-and-hold scales 1.45, lifts -6px and shows the black/80 label above
 *  (web hover tooltip, adapted to touch). Release picks the reaction. */
@Composable
private fun ReactionPickerItem(type: String, idx: Int, size: Dp, onPick: (String) -> Unit) {
    val scale = remember { Animatable(0f) }
    var pressed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(idx * 45L)
        scale.animateTo(1.25f, tween(210))
        scale.animateTo(1f, tween(140))
    }
    val displayScale = if (pressed) 1.45f else scale.value
    val lift = if (pressed) -6f else 0f
    // Web: label tooltip sits ABOVE the icon (absolute -top-8), so the label
    // row comes first with a fixed height, keeping icons aligned.
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (pressed) {
            Box(
                modifier = Modifier.clip(RoundedCornerShape(28.dp)).background(Color(0xCC000000)).padding(horizontal = 8.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) { Text(pickerLabel(type), fontSize = 10.sp, fontWeight = FontWeight.Medium, color = Color.White) }
        } else {
            Spacer(Modifier.height(18.dp))
        }
        Spacer(Modifier.height(2.dp))
        Box(
            modifier = Modifier.size(size).graphicsLayer { scaleX = displayScale; scaleY = displayScale; translationY = lift },
            contentAlignment = Alignment.Center
        ) {
            ReactionIcon(type, size, modifier = Modifier.pointerInput(type) {
                detectTapGestures(onPress = {
                    pressed = true
                    val released = tryAwaitRelease()
                    pressed = false
                    if (released) onPick(type)
                })
            })
        }
    }
}

/** Web picker pill: white, border black/5, shadow-xl, NO labels below icons.
 *  iconSize 40dp for posts, 32dp for comments (web sizes). */
@Composable
fun ReactionPickerPill(iconSize: Dp, types: List<String>, onPick: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Color(0x0D000000)),
        shadowElevation = 16.dp
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            types.forEachIndexed { idx, type -> ReactionPickerItem(type, idx, iconSize, onPick) }
        }
    }
}
