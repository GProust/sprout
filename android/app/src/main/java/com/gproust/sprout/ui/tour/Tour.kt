package com.gproust.sprout.ui.tour

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.gproust.sprout.R
import com.gproust.sprout.data.TourStep
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * How dark the scrim is: enough to read the card over a busy dashboard, not so
 * much that the cut-out is the only thing left to see.
 */
private const val SCRIM_ALPHA = 0.62f

/** The fade as the tour appears, the only movement it has. */
private const val FADE_MS = 200

/** Room around a target, so the ring does not sit on the thing it circles. */
private val HOLE_MARGIN = 6.dp

private val HOLE_CORNER = 12.dp

private val CARD_MAX_WIDTH = 480.dp

/**
 * With less room than this on both sides of the cut-out, the card sits at the
 * bottom of the screen instead, over whatever is there.
 */
private val MIN_CARD_ROOM = 180.dp

/**
 * Where each step's area is on screen, as the areas themselves report it.
 *
 * Measured rather than computed: the dashboard is a different shape with one
 * baby or two, with a feed running or a medicine waiting, and in seven
 * languages at any font size. A step whose area has not reported — scrolled
 * away, or not drawn at all — shows its card with no cut-out, and the card
 * still says in words where the area is.
 */
@Stable
class TourTargets {
    private val bounds = mutableStateMapOf<TourStep, Rect>()

    /** Where [step]'s area is, in the root's coordinates, or null when it is not on screen. */
    operator fun get(step: TourStep): Rect? = bounds[step]?.takeUnless { it.isEmpty }

    internal fun report(step: TourStep, rect: Rect) {
        if (bounds[step] != rect) bounds[step] = rect
    }

    internal fun forget(step: TourStep) {
        bounds.remove(step)
    }
}

/**
 * The tour's targets, provided by the shell only around the dashboard and its
 * bottom bar. Null everywhere else, which is what keeps the baby's own tab —
 * the same pane, with the same modifiers — and every screenshot capture, which
 * renders screens without the shell, from reporting anything.
 */
val LocalTourTargets = staticCompositionLocalOf<TourTargets?> { null }

/**
 * Marks this element as the area [step] points at. Does nothing at all outside
 * the dashboard: see [LocalTourTargets].
 */
fun Modifier.tourTarget(step: TourStep): Modifier = composed {
    val targets = LocalTourTargets.current
    if (targets == null) {
        Modifier
    } else {
        DisposableEffect(targets, step) {
            onDispose { targets.forget(step) }
        }
        // Clipped to what is actually visible, so a grid half below the fold
        // is ringed where it can be seen rather than under the bottom bar.
        Modifier.onGloballyPositioned { targets.report(step, it.boundsInRoot()) }
    }
}

/**
 * One step of the guided tour (BDR-27): the screen dimmed, a cut-out around
 * the area the step is about, and a card saying what it is.
 *
 * Drawn by hand rather than with a library: a tour library brings its own
 * manifest into the merged build, and `AttackSurfaceTest` pins that build.
 *
 * - **The cut-out stays live.** Nothing here takes a touch inside it, so the
 *   parent can tap the very thing the card describes — and doing so leaves the
 *   dashboard or starts a feed, either of which ends the tour.
 * - **Everywhere else skips.** A tap outside the card, or Back, is *Skip tour*.
 * - **The cut-out is visual only.** Each card says in words where its area is,
 *   and the shell hides what the scrim dims from TalkBack while a step is up.
 *   The card is a pane, announced as it appears, and *Skip tour* is the first
 *   thing in it a screen reader reaches.
 * - **No movement but a short fade in**, and none at all when the system is
 *   asked for no animations.
 */
@Composable
fun TourOverlay(
    step: TourStep,
    position: Int,
    count: Int,
    isLast: Boolean,
    target: Rect?,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val reduceMotion = rememberReducedMotion()
    val fade = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(Unit) { fade.animateTo(1f, tween(FADE_MS)) }
    // Where this overlay sits in the root, which the targets report against.
    var origin by remember { mutableStateOf(Offset.Zero) }

    BackHandler(onBack = onSkip)

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot() }
            .graphicsLayer { alpha = fade.value },
    ) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val hole = target
            ?.translate(-origin.x, -origin.y)
            ?.inflate(with(density) { HOLE_MARGIN.toPx() })
            ?.intersect(Rect(0f, 0f, width, height))
            ?.takeUnless { it.isEmpty }
        val corner = CornerRadius(with(density) { HOLE_CORNER.toPx() })
        val ringWidth = with(density) { 2.dp.toPx() }
        val scrim = MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA)
        val ring = MaterialTheme.colorScheme.inversePrimary

        // Drawn only: a canvas takes no touches, which is what keeps the
        // cut-out live.
        Canvas(Modifier.fillMaxSize()) {
            if (hole == null) {
                drawRect(scrim)
            } else {
                val cutout = Path().apply { addRoundRect(RoundRect(hole, corner)) }
                clipPath(cutout, clipOp = ClipOp.Difference) { drawRect(scrim) }
                drawRoundRect(
                    color = ring,
                    topLeft = hole.topLeft,
                    size = hole.size,
                    cornerRadius = corner,
                    style = Stroke(width = ringWidth),
                )
            }
        }

        // The scrim's touches, as four bands around the cut-out rather than
        // one sheet over everything: a sheet would swallow the cut-out's
        // touches too, because the layer under a hit sibling never sees one.
        if (hole == null) {
            SkipArea(Rect(0f, 0f, width, height), onSkip)
        } else {
            SkipArea(Rect(0f, 0f, width, hole.top), onSkip)
            SkipArea(Rect(0f, hole.bottom, width, height), onSkip)
            SkipArea(Rect(0f, hole.top, hole.left, hole.bottom), onSkip)
            SkipArea(Rect(hole.right, hole.top, width, hole.bottom), onSkip)
        }

        // The card goes on whichever side of the cut-out has more room, clear
        // of the status and navigation bars.
        val bars = WindowInsets.systemBars.asPaddingValues()
        val top = bars.calculateTopPadding() + 16.dp
        val bottom = bars.calculateBottomPadding() + 16.dp
        val gap = 12.dp
        val roomAbove = hole?.top ?: 0f
        val roomBelow = if (hole == null) 0f else height - hole.bottom
        val placement = when {
            hole == null -> Modifier.align(Alignment.Center).padding(top = top, bottom = bottom)
            max(roomAbove, roomBelow) < with(density) { MIN_CARD_ROOM.toPx() } ->
                Modifier.align(Alignment.BottomCenter).padding(top = top, bottom = bottom)
            roomAbove > roomBelow -> Modifier
                .align(Alignment.BottomCenter)
                .padding(top = top, bottom = with(density) { (height - hole.top).toDp() } + gap)
            else -> Modifier
                .align(Alignment.TopCenter)
                .padding(top = with(density) { hole.bottom.toDp() } + gap, bottom = bottom)
        }
        TourCard(
            step = step,
            position = position,
            count = count,
            isLast = isLast,
            onNext = onNext,
            onSkip = onSkip,
            modifier = placement
                .padding(horizontal = 16.dp)
                .widthIn(max = CARD_MAX_WIDTH)
                .fillMaxWidth(),
        )
    }
}

/** One band of the scrim: a tap on it skips the tour. */
@Composable
private fun SkipArea(area: Rect, onSkip: () -> Unit) {
    if (area.isEmpty) return
    val density = LocalDensity.current
    val skip by rememberUpdatedState(onSkip)
    Box(
        Modifier
            .offset { IntOffset(area.left.roundToInt(), area.top.roundToInt()) }
            .size(with(density) { area.width.toDp() }, with(density) { area.height.toDp() })
            .pointerInput(Unit) { detectTapGestures { skip() } },
    )
}

@Composable
private fun TourCard(
    step: TourStep,
    position: Int,
    count: Int,
    isLast: Boolean,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val words = tourWords(step)
    val title = stringResource(words.title)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        modifier = modifier.semantics {
            // Announced as it appears, and again as each step replaces it.
            paneTitle = title
            isTraversalGroup = true
        },
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.tour_step_count, position, count),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                // Where a skip is expected to be, at the end of the row — and
                // ahead of everything else for a screen reader, which reaches
                // it before the step's own words.
                TextButton(
                    onClick = onSkip,
                    modifier = Modifier.semantics { traversalIndex = -1f },
                ) {
                    Text(stringResource(R.string.tour_skip))
                }
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(words.body),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(onClick = onNext) {
                    Text(stringResource(if (isLast) R.string.tour_done else R.string.action_next))
                }
            }
        }
    }
}

/** What one step says. The same keys iOS reads, generated from these. */
private data class TourWords(@param:StringRes val title: Int, @param:StringRes val body: Int)

private fun tourWords(step: TourStep) = when (step) {
    TourStep.LOG_TILES -> TourWords(R.string.tour_step_1_title, R.string.tour_step_1_body)
    TourStep.BREASTFEED -> TourWords(R.string.tour_step_2_title, R.string.tour_step_2_body)
    TourStep.BOTTOM_BAR -> TourWords(R.string.tour_step_3_title, R.string.tour_step_3_body)
    TourStep.SETTINGS -> TourWords(R.string.tour_step_4_title, R.string.tour_step_4_body)
}

/**
 * Whether the system has been asked for no animations — Accessibility's
 * *Remove animations*, or the developer setting, both of which set the
 * animator scale to zero.
 */
@Composable
private fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
}
