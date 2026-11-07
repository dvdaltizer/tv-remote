package com.slowpixels.tvremote.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thelightphone.sdk.ui.LightThemeTokens

/** The remote's symbols (back chevron, TV, play/pause, skip 10, power, speaker, captions, keyboard) as plain line art. */
enum class Glyph { Back, Tv, PlayPause, SkipBack, SkipForward, Power, Speaker, SpeakerMuted, Captions, Keyboard }

@Composable
fun GlyphIcon(glyph: Glyph, size: Dp = 30.dp, ink: Color = LightThemeTokens.colors.content) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val w = this.size.width
            val line = Stroke(width = w * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            when (glyph) {
                Glyph.Back -> drawPath(Path().apply {
                    moveTo(w * 0.62f, w * 0.18f); lineTo(w * 0.30f, w * 0.5f); lineTo(w * 0.62f, w * 0.82f)
                }, ink, style = line)
                Glyph.Tv -> {
                    drawRoundRect(ink, topLeft = Offset(w * 0.08f, w * 0.16f), size = Size(w * 0.84f, w * 0.56f),
                        cornerRadius = CornerRadius(w * 0.08f), style = line)
                    drawLine(ink, Offset(w * 0.32f, w * 0.86f), Offset(w * 0.68f, w * 0.86f), strokeWidth = line.width, cap = StrokeCap.Round)
                }
                Glyph.PlayPause -> {
                    drawPath(Path().apply {
                        moveTo(w * 0.06f, w * 0.22f); lineTo(w * 0.48f, w * 0.5f); lineTo(w * 0.06f, w * 0.78f); close()
                    }, ink)
                    drawRect(ink, topLeft = Offset(w * 0.60f, w * 0.24f), size = Size(w * 0.11f, w * 0.52f))
                    drawRect(ink, topLeft = Offset(w * 0.82f, w * 0.24f), size = Size(w * 0.11f, w * 0.52f))
                }
                Glyph.Power -> {
                    // The standard power symbol: a circle open at the top, and a line through the gap.
                    val r = w * 0.36f
                    val c = Offset(w / 2, w * 0.54f)
                    drawArc(ink, startAngle = -60f, sweepAngle = 300f, useCenter = false,
                        topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2, r * 2), style = line)
                    drawLine(ink, Offset(w / 2, w * 0.08f), Offset(w / 2, w * 0.50f), strokeWidth = line.width, cap = StrokeCap.Round)
                }
                Glyph.Keyboard -> {
                    // A keyboard: a rounded outline, two rows of keys and a space bar.
                    drawRoundRect(ink, topLeft = Offset(w * 0.04f, w * 0.20f), size = Size(w * 0.92f, w * 0.60f),
                        cornerRadius = CornerRadius(w * 0.08f), style = line)
                    for (row in 0..1) for (col in 0..4) {
                        drawCircle(ink, radius = w * 0.045f, center = Offset(w * (0.20f + col * 0.15f), w * (0.36f + row * 0.14f)))
                    }
                    drawLine(ink, Offset(w * 0.30f, w * 0.66f), Offset(w * 0.70f, w * 0.66f), strokeWidth = line.width, cap = StrokeCap.Round)
                }
                Glyph.Captions -> {
                    // A speech bubble with caption lines, like Apple's.
                    drawPath(Path().apply {
                        moveTo(w * 0.16f, w * 0.18f); lineTo(w * 0.84f, w * 0.18f)
                        quadraticBezierTo(w * 0.94f, w * 0.18f, w * 0.94f, w * 0.28f); lineTo(w * 0.94f, w * 0.62f)
                        quadraticBezierTo(w * 0.94f, w * 0.72f, w * 0.84f, w * 0.72f); lineTo(w * 0.40f, w * 0.72f)
                        lineTo(w * 0.24f, w * 0.86f); lineTo(w * 0.26f, w * 0.72f); lineTo(w * 0.16f, w * 0.72f)
                        quadraticBezierTo(w * 0.06f, w * 0.72f, w * 0.06f, w * 0.62f); lineTo(w * 0.06f, w * 0.28f)
                        quadraticBezierTo(w * 0.06f, w * 0.18f, w * 0.16f, w * 0.18f); close()
                    }, ink, style = line)
                    val thin = line.width * 0.8f
                    drawLine(ink, Offset(w * 0.22f, w * 0.38f), Offset(w * 0.44f, w * 0.38f), strokeWidth = thin, cap = StrokeCap.Round)
                    drawLine(ink, Offset(w * 0.54f, w * 0.38f), Offset(w * 0.78f, w * 0.38f), strokeWidth = thin, cap = StrokeCap.Round)
                    drawLine(ink, Offset(w * 0.22f, w * 0.54f), Offset(w * 0.62f, w * 0.54f), strokeWidth = thin, cap = StrokeCap.Round)
                    drawLine(ink, Offset(w * 0.70f, w * 0.54f), Offset(w * 0.78f, w * 0.54f), strokeWidth = thin, cap = StrokeCap.Round)
                }
                Glyph.Speaker, Glyph.SpeakerMuted -> {
                    // A speaker: sound waves when on, a cross when muted.
                    drawPath(Path().apply {
                        moveTo(w * 0.08f, w * 0.38f); lineTo(w * 0.26f, w * 0.38f); lineTo(w * 0.50f, w * 0.16f)
                        lineTo(w * 0.50f, w * 0.84f); lineTo(w * 0.26f, w * 0.62f); lineTo(w * 0.08f, w * 0.62f); close()
                    }, ink, style = line)
                    if (glyph == Glyph.SpeakerMuted) {
                        drawLine(ink, Offset(w * 0.64f, w * 0.36f), Offset(w * 0.92f, w * 0.64f), strokeWidth = line.width, cap = StrokeCap.Round)
                        drawLine(ink, Offset(w * 0.92f, w * 0.36f), Offset(w * 0.64f, w * 0.64f), strokeWidth = line.width, cap = StrokeCap.Round)
                    } else {
                        for (r in listOf(0.16f, 0.30f)) drawArc(ink, startAngle = -45f, sweepAngle = 90f, useCenter = false,
                            topLeft = Offset(w * 0.52f - w * r, w * 0.5f - w * r), size = Size(w * r * 2, w * r * 2), style = line)
                    }
                }
                Glyph.SkipBack, Glyph.SkipForward -> {
                    // Like Apple's gobackward/goforward: the arrow's head sits at the top, pointing the way it turns.
                    val back = glyph == Glyph.SkipBack
                    val r = w * 0.40f
                    val c = Offset(w / 2, w * 0.54f)
                    drawArc(ink, startAngle = -90f, sweepAngle = if (back) 300f else -300f, useCenter = false,
                        topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2, r * 2), style = line)
                    val tip = Offset(c.x, c.y - r)
                    val ahead = if (back) -1f else 1f
                    drawPath(Path().apply {
                        moveTo(tip.x - ahead * w * 0.02f, tip.y - w * 0.13f)
                        lineTo(tip.x + ahead * w * 0.12f, tip.y)
                        lineTo(tip.x - ahead * w * 0.02f, tip.y + w * 0.13f)
                    }, ink, style = line)
                }
            }
        }
        if (glyph == Glyph.SkipBack || glyph == Glyph.SkipForward) {
            Text("10", color = ink, style = LightThemeTokens.typography.micro.copy(fontSize = (size.value * 0.30f).sp),
                modifier = Modifier.align(Alignment.Center))
        }
    }
}
