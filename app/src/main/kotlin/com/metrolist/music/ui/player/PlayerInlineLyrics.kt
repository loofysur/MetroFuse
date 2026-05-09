/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.player

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.db.entities.LyricsEntity
import com.metrolist.music.lyrics.LyricsEntry
import com.metrolist.music.lyrics.LyricsUtils
import kotlinx.coroutines.isActive

@Composable
internal fun PlayerInlineLyrics(
    lyricsEntity: LyricsEntity?,
    positionMs: Long,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    val playerConnection = LocalPlayerConnection.current
    val isPlaying by playerConnection
        ?.isEffectivelyPlaying
        ?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf(false) }
    var smoothPositionMs by remember { mutableLongStateOf(positionMs) }
    var positionAnchorMs by remember { mutableLongStateOf(positionMs) }
    var timeAnchorMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    LaunchedEffect(positionMs) {
        positionAnchorMs = positionMs
        timeAnchorMs = SystemClock.elapsedRealtime()
        smoothPositionMs = positionMs
    }

    val lyricsEntries =
        remember(lyricsEntity?.id, lyricsEntity?.lyrics) {
            lyricsEntity
                ?.lyrics
                ?.takeUnless { it == LyricsEntity.LYRICS_NOT_FOUND }
                ?.let(LyricsUtils::parseLyrics)
                ?.filter { it.text.isNotBlank() }
                .orEmpty()
        }

    LaunchedEffect(isPlaying, lyricsEntries.isNotEmpty()) {
        if (!isPlaying || lyricsEntries.isEmpty()) {
            smoothPositionMs = positionAnchorMs
            return@LaunchedEffect
        }

        while (isActive) {
            withFrameMillis {
                smoothPositionMs = positionAnchorMs + (SystemClock.elapsedRealtime() - timeAnchorMs)
            }
        }
    }

    val lyricLines = remember(lyricsEntries, smoothPositionMs) {
        lyricsEntries.currentInlineLines(smoothPositionMs)
    }

    Box(
        modifier =
            modifier
                .height(InlineLyricsSlotHeight)
                .clipToBounds(),
        contentAlignment = Alignment.CenterStart,
    ) {
        AnimatedContent(
            targetState = lyricLines,
            transitionSpec = {
                (
                    slideInVertically(
                        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
                        initialOffsetY = { it / 2 },
                    ) + fadeIn(animationSpec = tween(durationMillis = 220, delayMillis = 90))
                ).togetherWith(
                    slideOutVertically(
                        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
                        targetOffsetY = { -it / 2 },
                    ) + fadeOut(animationSpec = tween(durationMillis = 180)),
                )
            },
            label = "PlayerInlineLyricsLine",
        ) { lines ->
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                lines.forEach { line ->
                    Text(
                        text = line.inlineLyricsText(smoothPositionMs, textColor),
                        style =
                            MaterialTheme.typography.titleLarge.copy(
                                fontSize = if (line.isBackground) 14.sp else 17.sp,
                                lineHeight = if (line.isBackground) 18.sp else 21.sp,
                                fontWeight = if (line.isBackground) FontWeight.Bold else FontWeight.ExtraBold,
                                fontStyle = if (line.isBackground) FontStyle.Italic else FontStyle.Normal,
                                textAlign = if (line.isBackground) TextAlign.Center else TextAlign.Start,
                                color = textColor,
                            ),
                        maxLines = if (lines.size > 1) 1 else MaxInlineTextLines,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

private fun List<LyricsEntry>.currentInlineLines(positionMs: Long): List<LyricsEntry> {
    if (isEmpty()) return emptyList()

    val activeIndices = LyricsUtils.findActiveLineIndices(this, positionMs).toMutableSet()
    activeIndices.toList().forEach { index ->
        if (getOrNull(index)?.isBackground == true) {
            val pairedMainIndex = (index - 1 downTo 0).firstOrNull { getOrNull(it)?.isBackground == false }
            if (pairedMainIndex != null) activeIndices.add(pairedMainIndex)
        }
    }

    val activeLines = activeIndices.sorted().mapNotNull(::getOrNull).filter { it.text.isNotBlank() }
    if (activeLines.isNotEmpty()) {
        return activeLines.takeLast(MaxInlineLyricLines)
    }

    val foregroundEntries = filterNot { it.isBackground }.ifEmpty { this }
    val adjustedPosition = positionMs + 120L
    val currentIndex = foregroundEntries.indexOfLast { it.time <= adjustedPosition }

    return foregroundEntries.getOrNull(currentIndex)?.let(::listOf).orEmpty()
}

private const val MaxInlineLyricLines = 3
private const val MaxInlineTextLines = 3
private val InlineLyricsSlotHeight = 68.dp

private fun LyricsEntry.inlineLyricsText(
    positionMs: Long,
    textColor: Color,
) = buildAnnotatedString {
    val timedWords = words?.takeIf { it.isNotEmpty() }

    if (timedWords == null) {
        append(text)
        return@buildAnnotatedString
    }

    timedWords.forEachIndexed { index, word ->
        val wordStartMs = (word.startTime * 1000).toLong()
        val wordEndMs = (word.endTime * 1000).toLong().takeIf { it > wordStartMs } ?: (wordStartMs + 450L)
        val isActive = positionMs in wordStartMs..wordEndMs
        val hasPassed = positionMs > wordEndMs
        val rawProgress =
            when {
                hasPassed -> 1f
                isActive -> ((positionMs - wordStartMs).toFloat() / (wordEndMs - wordStartMs).coerceAtLeast(1)).coerceIn(0f, 1f)
                else -> 0f
            }
        val smoothProgress = rawProgress * rawProgress * (3f - 2f * rawProgress)
        val wordColor =
            when {
                hasPassed -> textColor
                isActive -> textColor.copy(alpha = 0.55f + (0.45f * smoothProgress))
                else -> textColor.copy(alpha = 0.35f)
            }
        val wordWeight =
            when {
                isActive -> FontWeight.Black
                hasPassed -> FontWeight.ExtraBold
                else -> FontWeight.Bold
            }
        val wordShadow =
            when {
                isActive -> {
                    Shadow(
                        color = textColor.copy(alpha = 0.18f + (0.18f * smoothProgress)),
                        offset = Offset.Zero,
                        blurRadius = 8f + (8f * smoothProgress),
                    )
                }

                hasPassed -> {
                    Shadow(
                        color = textColor.copy(alpha = 0.12f),
                        offset = Offset.Zero,
                        blurRadius = 6f,
                    )
                }

                else -> null
            }

        withStyle(SpanStyle(color = wordColor, fontWeight = wordWeight, shadow = wordShadow)) {
            append(word.text)
        }
        if (word.hasTrailingSpace && index < timedWords.lastIndex) {
            append(" ")
        }
    }
}
