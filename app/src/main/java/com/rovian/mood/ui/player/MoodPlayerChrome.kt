package com.rovian.mood.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rovian.mood.brand.MoodWordmark
import com.rovian.mood.ui.components.CapsuleProgress
import com.rovian.mood.ui.components.Glyph
import com.rovian.mood.ui.components.GlyphButton
import com.rovian.mood.ui.components.formatTime
import com.rovian.mood.ui.theme.MoodColors
import kotlinx.coroutines.delay

/** What the player bar can do right now. Buttons for `false` entries are not shown at all. */
data class PlayerAbilities(
    val playPause: Boolean = false,
    val seek: Boolean = false,
    val previous: Boolean = false,
    val next: Boolean = false,
    val shuffle: Boolean = false,
    val repeat: Boolean = false,
)

data class PlayerActions(
    val togglePlay: () -> Unit = {},
    val seekTo: (Long) -> Unit = {},
    val seekBy: (Long) -> Unit = {},
    val previous: () -> Unit = {},
    val next: () -> Unit = {},
    val shuffle: () -> Unit = {},
    val repeat: () -> Unit = {},
)

/** Auto-hide state shared by every Mood player surface. */
class PlayerChromeState {
    var visible by mutableStateOf(true)
    var lastTouch by mutableLongStateOf(System.currentTimeMillis())
    var flash by mutableStateOf<String?>(null)
    var scrubbing by mutableStateOf(false)
    fun poke() {
        visible = true
        lastTouch = System.currentTimeMillis()
    }
}

/**
 * Mood's player, as designed: full-bleed picture, the Mood wordmark top-left, a
 * solid black control bar with the capsule progress indicator and
 * shuffle · −10 · previous · play · next · +10 · repeat. Double-tap the left or
 * right half to jump 10 s (shown as large −10s / +10s). The chrome fades away
 * after 4 s of inactivity and returns on touch or pointer movement.
 */
@Composable
fun MoodPlayerChrome(
    state: PlayerChromeState,
    positionMs: Long,
    durationMs: Long?,
    playing: Boolean,
    abilities: PlayerAbilities,
    actions: PlayerActions,
    modifier: Modifier = Modifier,
    holdVisible: Boolean = false,
    shuffleOn: Boolean = false,
    repeatOn: Boolean = false,
    repeatOne: Boolean = false,
    topEnd: @Composable () -> Unit = {},
    aboveBar: @Composable ColumnScope.() -> Unit = {},
    belowControls: @Composable ColumnScope.() -> Unit = {},
    picture: @Composable BoxScope.() -> Unit,
) {
    LaunchedEffect(state.lastTouch, state.visible, holdVisible, state.scrubbing) {
        if (state.visible && !holdVisible && !state.scrubbing) {
            delay(HIDE_AFTER_MS)
            state.visible = false
        }
    }
    LaunchedEffect(state.flash) {
        if (state.flash != null) {
            delay(700)
            state.flash = null
        }
    }
    val alpha by animateFloatAsState(if (state.visible) 1f else 0f, tween(if (state.visible) 240 else 520), label = "chrome")

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        if (e.type == PointerEventType.Move) state.poke()
                    }
                }
            }
            .pointerInput(abilities.seek) {
                detectTapGestures(
                    onTap = { if (state.visible) state.visible = false else state.poke() },
                    onDoubleTap = { o ->
                        if (abilities.seek) {
                            val back = o.x < size.width / 2
                            actions.seekBy(if (back) -SKIP_MS else SKIP_MS)
                            state.flash = if (back) "-10s" else "+10s"
                        }
                        state.poke()
                    },
                )
            },
    ) {
        picture()

        // Big −10s / +10s, on the side that was tapped, like the design.
        AnimatedVisibility(
            visible = state.flash != null,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(300)),
            modifier = Modifier.align(if (state.flash == "-10s") Alignment.CenterStart else Alignment.CenterEnd),
        ) {
            Text(
                state.flash ?: "",
                color = Color.White,
                fontSize = 96.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }

        Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }) {
            Row(
                Modifier.fillMaxWidth().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MoodWordmark(Modifier.width(118.dp), letterColor = Color.White)
                Spacer(Modifier.weight(1f))
                topEnd()
            }

            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    content = aboveBar,
                )
                Column(
                    Modifier.fillMaxWidth().background(Color.Black).navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(Modifier.widthIn(max = 780.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        val d = durationMs
                        if (d != null && d > 0) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(formatTime(positionMs), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                                Box(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    CapsuleProgress(
                                        positionMs = positionMs,
                                        durationMs = d,
                                        seekable = abilities.seek,
                                        onSeek = { state.scrubbing = false; actions.seekTo(it) },
                                        onInteract = { state.scrubbing = true; state.poke() },
                                    )
                                }
                                Text(formatTime(d), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                            }
                        }
                        ControlRow(state, playing, abilities, actions, shuffleOn, repeatOn, repeatOne)
                        belowControls()
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlRow(
    state: PlayerChromeState,
    playing: Boolean,
    a: PlayerAbilities,
    act: PlayerActions,
    shuffleOn: Boolean,
    repeatOn: Boolean,
    repeatOne: Boolean,
) {
    fun tap(block: () -> Unit): () -> Unit = { state.poke(); block() }
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (a.shuffle) GlyphButton(Glyph.Shuffle, "Shuffle", tap(act.shuffle), tint = if (shuffleOn) MoodColors.Orange else Color.White)
        if (a.seek) GlyphButton(Glyph.Back10, "Back 10 seconds", tap { act.seekBy(-SKIP_MS); state.flash = "-10s" })
        if (a.previous) GlyphButton(Glyph.Previous, "Previous", tap(act.previous))
        if (a.playPause) {
            GlyphButton(
                if (playing) Glyph.Pause else Glyph.Play,
                if (playing) "Pause" else "Play",
                tap(act.togglePlay),
                size = 60.dp,
                iconSize = 28.dp,
                filled = true,
            )
        }
        if (a.next) GlyphButton(Glyph.Next, "Next", tap(act.next))
        if (a.seek) GlyphButton(Glyph.Forward10, "Forward 10 seconds", tap { act.seekBy(SKIP_MS); state.flash = "+10s" })
        if (a.repeat) {
            GlyphButton(
                if (repeatOne) Glyph.RepeatOne else Glyph.Repeat,
                "Repeat",
                tap(act.repeat),
                tint = if (repeatOn) MoodColors.Orange else Color.White,
            )
        }
    }
}

private const val HIDE_AFTER_MS = 4_000L
private const val SKIP_MS = 10_000L
