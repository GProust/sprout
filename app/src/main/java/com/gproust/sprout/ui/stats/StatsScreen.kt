package com.gproust.sprout.ui.stats

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BabyChangingStation
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gproust.sprout.R
import com.gproust.sprout.data.SproutRepository
import com.gproust.sprout.data.local.DiaperEntity
import com.gproust.sprout.data.local.FeedType
import com.gproust.sprout.data.local.FeedingEntity
import com.gproust.sprout.data.local.GrowthEntity
import com.gproust.sprout.data.local.SleepEntity
import com.gproust.sprout.ui.common.ChoiceChips
import com.gproust.sprout.ui.common.EmptyHint
import com.gproust.sprout.ui.common.SproutTopBar
import com.gproust.sprout.ui.common.formatDate
import com.gproust.sprout.ui.common.formatDuration
import com.gproust.sprout.ui.common.formatTime
import com.gproust.sprout.ui.diaper.label
import com.gproust.sprout.ui.diaper.swatch
import com.gproust.sprout.ui.rememberSproutViewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** How wide the window is. Kept short: this is a follow-up, not an archive. */
enum class StatsPeriod(val days: Int, @param:StringRes val labelRes: Int) {
    WEEK(7, R.string.stats_period_week),
    MONTH(30, R.string.stats_period_month),
    QUARTER(90, R.string.stats_period_quarter),
}

/**
 * Which reference the growth card is read against.
 *
 * A view choice, not a fact about the baby: nothing is stored, nothing is
 * synced, and the app still never asks a baby's sex (BDR-0008). Picking one
 * only narrows what is on screen, and the wording under the chart says so.
 */
enum class GrowthReference(@param:StringRes val labelRes: Int, val sex: WhoSex?) {
    BOTH(R.string.stats_reference_both, null),
    GIRLS(R.string.stats_reference_girls, WhoSex.GIRLS),
    BOYS(R.string.stats_reference_boys, WhoSex.BOYS),
}

/**
 * Which number the feeding chart draws.
 *
 * They cannot share one chart: a count, a duration, millilitres and grams have
 * nothing to do with each other on a single axis, and putting two of them on
 * two axes would invent a relationship that isn't there. So the card draws one
 * at a time.
 */
enum class FeedSeries(@param:StringRes val labelRes: Int) {
    COUNT(R.string.stats_feeds_per_day),
    BREAST(R.string.stats_breast_per_day),
    BOTTLE(R.string.stats_bottle_per_day),
    SOLID(R.string.stats_solid_per_day),
}

private val MEASURE_LABELS = mapOf(
    GrowthMeasure.WEIGHT to R.string.stats_measure_weight,
    GrowthMeasure.LENGTH to R.string.stats_measure_length,
    GrowthMeasure.HEAD to R.string.stats_measure_head,
)

private class Logs(
    val feedings: List<FeedingEntity>,
    val sleeps: List<SleepEntity>,
    val diapers: List<DiaperEntity>,
    val growth: List<GrowthEntity>,
)

data class StatsUiState(
    val hasBaby: Boolean = false,
    /** Null until a baby is selected; the growth curves are drawn against it. */
    val birthDate: Long? = null,
    val period: StatsPeriod = StatsPeriod.WEEK,
    /** How many days back the window ends; 0 is "ending today". */
    val offset: Int = 0,
    val maxOffset: Int = 0,
    val days: List<DayStats> = emptyList(),
    val averages: StatsAverages = StatsAverages(),
    /** Measurements over the whole history, oldest first. */
    val growth: List<GrowthEntity> = emptyList(),
    val feedings: List<FeedingEntity> = emptyList(),
    val sleeps: List<SleepEntity> = emptyList(),
    val diapers: List<DiaperEntity> = emptyList(),
    val now: Long = 0L,
) {
    /** Whether anything at all was logged in the window — an empty week says so once. */
    val hasLogs: Boolean
        get() = days.any { it.feedCount > 0 || it.sleepCount > 0 || it.diaperCount > 0 }

    /** True while the window still ends today, which is when today is left out of the averages. */
    val endsToday: Boolean get() = offset == 0
}

class StatsViewModel(private val repository: SproutRepository) : ViewModel() {

    private val period = MutableStateFlow(StatsPeriod.WEEK)
    private val offset = MutableStateFlow(0)

    fun setPeriod(value: StatsPeriod) {
        period.value = value
    }

    /** Walks the window through time; the flow clamps it to the baby's life. */
    fun moveWindow(byDays: Int) {
        offset.value = (offset.value + byDays).coerceAtLeast(0)
    }

    private val logs = combine(
        repository.feedings,
        repository.sleeps,
        repository.diapers,
        repository.growth,
    ) { feedings, sleeps, diapers, growth -> Logs(feedings, sleeps, diapers, growth) }

    private val window = combine(period, offset) { chosen, at -> WindowChoice(chosen, at) }

    val uiState = combine(repository.baby, logs, window) { baby, entries, chosen ->
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val born = baby?.birthDate?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        val ceiling = maxStatsOffset(today, born)
        val clamped = chosen.offset.coerceIn(0, ceiling)
        val span = statsWindow(today, chosen.period.days, born, clamped)
        val days = dailyStats(entries.feedings, entries.sleeps, entries.diapers, span.from, span.to, now, zone)

        StatsUiState(
            hasBaby = baby != null,
            birthDate = baby?.birthDate,
            period = chosen.period,
            offset = clamped,
            maxOffset = ceiling,
            days = days,
            averages = averagesOf(days, today),
            // Growth is shown over the whole history rather than the window: a
            // curve is only worth reading over months, and a fortnight of it
            // would be two dots and no shape.
            growth = entries.growth.sortedBy { it.time },
            feedings = entries.feedings,
            sleeps = entries.sleeps,
            diapers = entries.diapers,
            now = now,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StatsUiState())

    private data class WindowChoice(val period: StatsPeriod, val offset: Int)
}

@Composable
fun StatsScreen(onBack: () -> Unit = {}) {
    val vm: StatsViewModel = viewModel(factory = rememberSproutViewModelFactory())
    val state by vm.uiState.collectAsState()
    val context = LocalContext.current

    // One selected day, shared by the three cards: tapping the 14th in any of
    // them answers "what happened that day" everywhere at once.
    var selectedDay by remember { mutableStateOf<LocalDate?>(null) }

    // Resolved out here rather than inside `labelOf`: neither that lambda nor
    // `LazyColumn`'s content is composable, so a `Context.getString` in either
    // would not be re-read when the configuration changes — switching language
    // in Settings would leave the old chips behind.
    val periodLabels = StatsPeriod.entries.associateWith { stringResource(it.labelRes) }

    Scaffold(
        topBar = { SproutTopBar(stringResource(R.string.screen_stats), onBack = onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.hasBaby) {
                item { EmptyHint(stringResource(R.string.stats_no_baby)) }
                return@LazyColumn
            }

            item {
                ChoiceChips(
                    options = StatsPeriod.entries,
                    selected = state.period,
                    onSelect = {
                        selectedDay = null
                        vm.setPeriod(it)
                    },
                    labelOf = { periodLabels.getValue(it) },
                )
            }

            item {
                WindowBar(state, context) {
                    selectedDay = null
                    vm.moveWindow(it)
                }
            }

            if (!state.hasLogs) {
                item { EmptyHint(stringResource(R.string.stats_empty)) }
            } else {
                val onPick: (LocalDate?) -> Unit = { selectedDay = it }
                val onMove: (Int) -> Unit = {
                    selectedDay = null
                    vm.moveWindow(it)
                }
                item { FeedingCard(state, context, selectedDay, onPick, onMove) }
                item { SleepCard(state, context, selectedDay, onPick, onMove) }
                item { DiaperCard(state, context, selectedDay, onPick, onMove) }
            }

            item { GrowthCard(state, context) }
        }
    }
}

/** ‹ the window ›, showing its dates once it has walked off today. */
@Composable
private fun WindowBar(state: StatsUiState, context: Context, onMove: (Int) -> Unit) {
    val label = when {
        !state.endsToday && state.days.isNotEmpty() -> context.getString(
            R.string.stats_window_range,
            formatDate(context, state.days.first().date.atStartOfDayMillis()),
            formatDate(context, state.days.last().date.atStartOfDayMillis()),
        )

        state.averages.dayCount == 1 && state.days.size == 1 ->
            stringResource(R.string.stats_average_today_only)

        else -> stringResource(R.string.stats_average_over, state.averages.dayCount)
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onMove(state.period.days) }, enabled = state.offset < state.maxOffset) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.stats_window_earlier),
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { onMove(-state.period.days) }, enabled = state.offset > 0) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.stats_window_later),
            )
        }
    }
}

// --- the three log cards -------------------------------------------------

@Composable
private fun FeedingCard(
    state: StatsUiState,
    context: Context,
    selectedDay: LocalDate?,
    onSelectDay: (LocalDate?) -> Unit,
    onMove: (Int) -> Unit,
) {
    var series by remember { mutableStateOf(FeedSeries.COUNT) }
    val seriesLabels = FeedSeries.entries.associateWith { stringResource(it.labelRes) }
    val averages = state.averages
    val bar = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val ring = MaterialTheme.colorScheme.onSurface

    StatsCard(stringResource(R.string.stats_feeding_title), Icons.Filled.LocalDrink) {
        StatLine(stringResource(R.string.stats_feeds_per_day), decimal(averages.feedsPerDay), true)
        StatLine(
            stringResource(R.string.stats_breast_per_day),
            countAndAmount(context, averages.breastfeedsPerDay, formatDuration(context, averages.breastMillisPerDay)),
        )
        StatLine(
            stringResource(R.string.stats_bottle_per_day),
            countAndAmount(
                context,
                averages.bottlesPerDay,
                context.getString(R.string.feeding_amount_ml, averages.bottleMlPerDay),
            ),
        )
        StatLine(
            stringResource(R.string.stats_solid_per_day),
            countAndAmount(
                context,
                averages.solidsPerDay,
                context.getString(R.string.feeding_amount_g, averages.solidGramsPerDay),
            ),
        )

        Spacer(Modifier.height(12.dp))
        ChoiceChips(
            options = FeedSeries.entries,
            selected = series,
            onSelect = { series = it },
            labelOf = { seriesLabels.getValue(it) },
        )

        val value: (DayStats) -> Float = when (series) {
            FeedSeries.COUNT -> { d -> d.feedCount.toFloat() }
            FeedSeries.BREAST -> { d -> d.breastMillis / 3_600_000f }
            FeedSeries.BOTTLE -> { d -> d.bottleMl.toFloat() }
            FeedSeries.SOLID -> { d -> d.solidGrams.toFloat() }
        }
        val top = when (series) {
            FeedSeries.COUNT -> state.days.maxOfOrNull { it.feedCount }?.toString()
            FeedSeries.BREAST -> state.days.maxOfOrNull { it.breastMillis }?.let { formatDuration(context, it) }
            FeedSeries.BOTTLE -> state.days.maxOfOrNull { it.bottleMl }
                ?.let { context.getString(R.string.feeding_amount_ml, it) }
            FeedSeries.SOLID -> state.days.maxOfOrNull { it.solidGrams }
                ?.let { context.getString(R.string.feeding_amount_g, it) }
        }.orEmpty()

        DayChart(state, context, top, selectedDay, onSelectDay, onMove) { days, selected ->
            drawDayBars(days, value, bar, empty)
            drawSelection(days, selected, ring)
        }

        DayDetail(selectedDay, context, onSelectDay) { day ->
            val feeds = feedsOn(state.feedings, day)
            if (feeds.isEmpty()) {
                DetailEmpty(stringResource(R.string.stats_detail_no_feeds))
            } else {
                feeds.forEach { DetailRow(formatTime(it.startTime), feedLine(context, it)) }
            }
        }
    }
}

@Composable
private fun SleepCard(
    state: StatsUiState,
    context: Context,
    selectedDay: LocalDate?,
    onSelectDay: (LocalDate?) -> Unit,
    onMove: (Int) -> Unit,
) {
    val bar = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val ring = MaterialTheme.colorScheme.onSurface

    StatsCard(stringResource(R.string.stats_sleep_title), Icons.Filled.Bedtime) {
        StatLine(
            stringResource(R.string.stats_sleep_per_day),
            formatDuration(context, state.averages.sleepMillisPerDay),
            true,
        )
        StatLine(stringResource(R.string.stats_sleeps_per_day), decimal(state.averages.sleepsPerDay))

        val top = state.days.maxOfOrNull { it.sleepMillis }?.let { formatDuration(context, it) }.orEmpty()
        DayChart(state, context, top, selectedDay, onSelectDay, onMove) { days, selected ->
            drawDayBars(days, { it.sleepMillis / 3_600_000f }, bar, empty)
            drawSelection(days, selected, ring)
        }

        DayDetail(selectedDay, context, onSelectDay) { day ->
            val sleeps = sleepsOn(state.sleeps, day, state.now)
            if (sleeps.isEmpty()) {
                DetailEmpty(stringResource(R.string.stats_detail_no_sleep))
            } else {
                sleeps.forEach { sleep ->
                    val end = (sleep.endTime ?: state.now).coerceAtLeast(sleep.startTime)
                    val whole = end - sleep.startTime
                    val here = sleepMillisOn(sleep, day, state.now)
                    // A night belongs to two days; saying how much of it landed
                    // here is why it was split in the first place.
                    val line = if (here < whole) {
                        context.getString(
                            R.string.stats_detail_sleep_split,
                            formatTime(end),
                            formatDuration(context, whole),
                            formatDuration(context, here),
                        )
                    } else {
                        context.getString(R.string.stats_detail_sleep, formatTime(end), formatDuration(context, whole))
                    }
                    DetailRow(formatTime(sleep.startTime), line)
                }
            }
        }
    }
}

@Composable
private fun DiaperCard(
    state: StatsUiState,
    context: Context,
    selectedDay: LocalDate?,
    onSelectDay: (LocalDate?) -> Unit,
    onMove: (Int) -> Unit,
) {
    val wet = MaterialTheme.colorScheme.primary
    val dirty = dirtyColor()
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val ring = MaterialTheme.colorScheme.onSurface

    StatsCard(stringResource(R.string.stats_diaper_title), Icons.Filled.BabyChangingStation) {
        StatLine(stringResource(R.string.stats_diapers_per_day), decimal(state.averages.diapersPerDay), true)
        StatLine(stringResource(R.string.stats_wet_per_day), decimal(state.averages.wetPerDay))
        StatLine(stringResource(R.string.stats_dirty_per_day), decimal(state.averages.dirtyPerDay))

        val top = state.days.maxOfOrNull { it.diaperCount }?.toString().orEmpty()
        DayChart(state, context, top, selectedDay, onSelectDay, onMove) { days, selected ->
            drawStackedDiapers(days, wet, dirty, empty)
            drawSelection(days, selected, ring)
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LegendKey(stringResource(R.string.stats_wet_per_day), wet)
            LegendKey(stringResource(R.string.stats_diaper_both), wet, hatchWith = dirty)
            LegendKey(stringResource(R.string.stats_dirty_per_day), dirty)
        }

        DayDetail(selectedDay, context, onSelectDay) { day ->
            val changes = diapersOn(state.diapers, day)
            if (changes.isEmpty()) {
                DetailEmpty(stringResource(R.string.stats_detail_no_diapers))
            } else {
                changes.forEach { change ->
                    DetailRow(formatTime(change.time), diaperLine(context, change)) {
                        val colour = change.stoolColor ?: return@DetailRow
                        Box(Modifier.size(12.dp).clip(CircleShape).background(colour.swatch()))
                        Text(
                            colour.label(context),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

// --- growth --------------------------------------------------------------

@Composable
private fun GrowthCard(state: StatsUiState, context: Context) {
    var measure by remember { mutableStateOf(GrowthMeasure.WEIGHT) }
    var reference by remember { mutableStateOf(GrowthReference.BOTH) }
    var reading by remember { mutableIntStateOf(-1) }

    val measureLabels = GrowthMeasure.entries.associateWith { stringResource(MEASURE_LABELS.getValue(it)) }
    val referenceLabels = GrowthReference.entries.associateWith { stringResource(it.labelRes) }
    val measurer = rememberTextMeasurer()
    val birthDate = state.birthDate

    StatsCard(stringResource(R.string.stats_growth_title), Icons.Filled.Monitor) {
        ChoiceChips(
            options = GrowthMeasure.entries,
            selected = measure,
            onSelect = { measure = it; reading = -1 },
            labelOf = { measureLabels.getValue(it) },
        )
        Spacer(Modifier.height(6.dp))
        ChoiceChips(
            options = GrowthReference.entries,
            selected = reference,
            onSelect = { reference = it },
            labelOf = { referenceLabels.getValue(it) },
        )
        Spacer(Modifier.height(8.dp))

        val points = if (birthDate == null) emptyList() else state.growth.mapNotNull { entry ->
            valueOf(entry, measure)?.let { ageInMonths(birthDate, entry.time) to it }
        }.filter { it.first >= 0 }

        if (points.isEmpty()) {
            EmptyHint(stringResource(R.string.stats_growth_empty))
            return@StatsCard
        }

        val maxAge = points.maxOf { it.first }.coerceAtLeast(1.0).coerceAtMost(WHO_MAX_AGE_MONTHS)
        // A little headroom to the right, so the newest dot isn't on the edge.
        val band = whoBand(measure, 0.0, (maxAge * 1.15).coerceAtMost(WHO_MAX_AGE_MONTHS))
        val sexes = reference.sex?.let { listOf(it) } ?: listOf(WhoSex.GIRLS, WhoSex.BOYS)

        val below = bandBelowColor()
        val above = bandAboveColor()
        val ownColor = MaterialTheme.colorScheme.primary
        val medianColor = MaterialTheme.colorScheme.onSurfaceVariant
        val surface = MaterialTheme.colorScheme.surface
        val outline = MaterialTheme.colorScheme.outlineVariant
        val ink = MaterialTheme.colorScheme.onSurface
        val readoutStyle = MaterialTheme.typography.labelSmall.copy(color = ink)
        val readoutText = if (reading in points.indices) {
            readoutLines(context, measure, reference, points[reading])
        } else {
            emptyList()
        }

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(200.dp)
                .pointerInput(points, band, sexes) {
                    detectTapGestures { tap ->
                        val plot = GrowthPlot(band, points, sexes, size.width.toFloat(), size.height.toFloat())
                        val hit = plot.nearestPoint(tap.x)
                        // Tapping the same measurement again puts the readout away.
                        reading = if (hit == reading) -1 else hit
                    }
                },
        ) {
            val plot = GrowthPlot(band, points, sexes, size.width, size.height)
            drawWhoBand(plot, band, sexes, below, above, medianColor)
            drawOwnCurve(plot, points, ownColor)
            if (reading in points.indices) {
                drawReadout(plot, points[reading], readoutText, measurer, readoutStyle, ink, surface, outline)
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.stats_age_months, 0), style = MaterialTheme.typography.labelSmall)
            Text(
                stringResource(R.string.stats_age_months, band.lastOrNull()?.ageMonths?.roundToInt() ?: 0),
                style = MaterialTheme.typography.labelSmall,
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LegendKey(stringResource(R.string.stats_band_above), above.copy(alpha = .55f))
            LegendKey(stringResource(R.string.stats_band_below), below.copy(alpha = .55f))
        }

        val last = points.last()
        val placement = whoPlacement(measure, last.first, last.second, reference.sex)
        Spacer(Modifier.height(8.dp))
        Text(
            measurementLine(context, measure, last.second, last.first),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            when {
                placement == null -> stringResource(R.string.stats_who_past_range)
                placement.insideBand -> stringResource(R.string.stats_who_inside, percentileRange(context, placement))
                else -> stringResource(R.string.stats_who_outside, percentileRange(context, placement))
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            when (reference.sex) {
                // Not `lowercase()` on the chip label: German capitalises its
                // nouns, so folding the case there would be wrong in the one
                // language that cares.
                null -> stringResource(R.string.stats_who_disclaimer_both)
                WhoSex.GIRLS -> stringResource(
                    R.string.stats_who_disclaimer_one,
                    stringResource(R.string.stats_reference_girls_in_sentence),
                )

                WhoSex.BOYS -> stringResource(
                    R.string.stats_who_disclaimer_one,
                    stringResource(R.string.stats_reference_boys_in_sentence),
                )
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * Where the growth chart puts things, given a canvas size.
 *
 * Pulled out of the drawing so the tap handler can ask the same question the
 * pixels answer — which measurement is under this finger — without the two
 * drifting apart.
 */
private class GrowthPlot(
    band: List<WhoBandPoint>,
    points: List<Pair<Double, Double>>,
    sexes: List<WhoSex>,
    val width: Float,
    val height: Float,
) {
    private val maxAge = maxOf(
        band.lastOrNull()?.ageMonths ?: 0.0,
        points.maxOfOrNull { it.first } ?: 0.0,
    ).coerceAtLeast(1.0)

    private val values = band.flatMap { p -> sexes.flatMap { listOf(p.of(it).p3, p.of(it).p97) } } +
        points.map { it.second }
    private val min = values.minOrNull() ?: 0.0
    private val span = ((values.maxOrNull() ?: 1.0) - min).coerceAtLeast(0.001)
    private val padY = height * 0.06f
    private val usable = height - padY * 2

    private val xs = points.map { x(it.first) }

    fun x(ageMonths: Double): Float = (ageMonths / maxAge).toFloat() * width
    fun y(value: Double): Float = padY + (1f - ((value - min) / span).toFloat()) * usable

    /** The measurement nearest a horizontal position, or -1 when there are none. */
    fun nearestPoint(atX: Float): Int {
        if (xs.isEmpty()) return -1
        var best = 0
        for (i in xs.indices) if (abs(xs[i] - atX) < abs(xs[best] - atX)) best = i
        return best
    }
}

/**
 * The reference behind the baby's own line.
 *
 * Two encodings, kept apart so neither has to carry the other's meaning:
 *
 * - **Colour is the side of the median.** Warm below it, cool above it — a
 *   diverging pair, which is what a diverging pair is for, and validated to
 *   stay distinguishable under colour-vision deficiency.
 * - **Texture is which reference.** With both shown, the girls' band is plain
 *   and the boys' is hatched, each keeping its own median line. Two more hues
 *   would have been the obvious move and the wrong one: every four-hue set
 *   tried here collapsed to a deuteranopic delta-E of 3 to 4, which is no
 *   distinction at all for roughly one man in twelve.
 */
private fun DrawScope.drawWhoBand(
    plot: GrowthPlot,
    band: List<WhoBandPoint>,
    sexes: List<WhoSex>,
    below: Color,
    above: Color,
    medianColor: Color,
) {
    if (band.size < 2) return

    fun area(lower: (WhoPercentiles) -> Double, upper: (WhoPercentiles) -> Double, sex: WhoSex) = Path().apply {
        band.forEachIndexed { i, p ->
            val px = plot.x(p.ageMonths)
            val py = plot.y(upper(p.of(sex)))
            if (i == 0) moveTo(px, py) else lineTo(px, py)
        }
        band.asReversed().forEach { p -> lineTo(plot.x(p.ageMonths), plot.y(lower(p.of(sex)))) }
        close()
    }

    sexes.forEach { sex ->
        val hatched = sexes.size > 1 && sex == WhoSex.BOYS
        val outer = listOf(
            area({ it.p3 }, { it.p50 }, sex) to below,
            area({ it.p50 }, { it.p97 }, sex) to above,
        )
        // The 15th–85th again on top, so the middle of the reference reads as
        // the middle rather than as more of the same.
        val inner = listOf(
            area({ it.p15 }, { it.p50 }, sex) to below,
            area({ it.p50 }, { it.p85 }, sex) to above,
        )
        (outer + inner).forEachIndexed { i, (path, colour) ->
            val alpha = if (i < 2) 0.15f else 0.18f
            if (hatched) {
                clipPath(path) { drawHatchLines(colour.copy(alpha = alpha * 2.2f)) }
            } else {
                drawPath(path, colour.copy(alpha = alpha))
            }
        }

        val median = Path().apply {
            band.forEachIndexed { i, p ->
                val px = plot.x(p.ageMonths)
                val py = plot.y(p.of(sex).p50)
                if (i == 0) moveTo(px, py) else lineTo(px, py)
            }
        }
        val dashes = if (hatched) floatArrayOf(9f, 4f) else floatArrayOf(4f, 4f)
        drawPath(
            median,
            color = medianColor.copy(alpha = .6f),
            style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(dashes)),
        )
    }
}

/** Diagonal strokes at 45°, for the hatched reference and the mixed nappy. */
private fun DrawScope.drawHatchLines(colour: Color, step: Float = 9f, thickness: Float = 4f) {
    var x = -size.height
    while (x < size.width + size.height) {
        drawLine(
            colour,
            start = Offset(x, size.height),
            end = Offset(x + size.height, 0f),
            strokeWidth = thickness,
        )
        x += step
    }
}

private fun DrawScope.drawOwnCurve(plot: GrowthPlot, points: List<Pair<Double, Double>>, colour: Color) {
    val own = points.map { Offset(plot.x(it.first), plot.y(it.second)) }
    if (own.size >= 2) {
        val path = Path().apply {
            own.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
        }
        drawPath(path, colour, style = Stroke(width = 6f))
    }
    own.forEach { drawCircle(colour, radius = 9f, center = it) }
}

/** The crosshair and the card: what this measurement was, and where it lands. */
private fun DrawScope.drawReadout(
    plot: GrowthPlot,
    point: Pair<Double, Double>,
    lines: List<String>,
    measurer: TextMeasurer,
    style: TextStyle,
    ink: Color,
    surface: Color,
    outline: Color,
) {
    val px = plot.x(point.first)
    val py = plot.y(point.second)
    drawLine(
        ink.copy(alpha = .35f),
        start = Offset(px, 0f),
        end = Offset(px, size.height),
        strokeWidth = 2f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
    )
    drawCircle(ink.copy(alpha = .55f), radius = 14f, center = Offset(px, py), style = Stroke(width = 3f))

    val laid = lines.map { measurer.measure(it, style) }
    val cardWidth = (laid.maxOfOrNull { it.size.width } ?: 0).toFloat() + 24f
    val cardHeight = laid.sumOf { it.size.height }.toFloat() + 20f
    // Flip the card to the other side rather than let it run off the chart.
    val left = (px + 16f).coerceIn(4f, (size.width - cardWidth - 4f).coerceAtLeast(4f))
    val top = (py - cardHeight - 16f).coerceIn(4f, (size.height - cardHeight - 4f).coerceAtLeast(4f))

    drawRoundRect(
        surface,
        topLeft = Offset(left, top),
        size = Size(cardWidth, cardHeight),
        cornerRadius = CornerRadius(10f, 10f),
    )
    drawRoundRect(
        outline,
        topLeft = Offset(left, top),
        size = Size(cardWidth, cardHeight),
        cornerRadius = CornerRadius(10f, 10f),
        style = Stroke(width = 2f),
    )
    var y = top + 10f
    laid.forEach {
        drawText(it, topLeft = Offset(left + 12f, y))
        y += it.size.height
    }
}

// --- the day charts ------------------------------------------------------

private const val BARS_HEIGHT_DP = 80

/**
 * One bar per day, with the window's ends named underneath.
 *
 * Deliberately unlabelled between them: at 90 days there is no room for dates,
 * and the shape — a run of empty days, a night that went badly — is what the
 * chart is for. Tapping a day opens what it was made of; dragging sideways
 * walks the window through time, and every chart on the screen moves together.
 */
@Composable
private fun DayChart(
    state: StatsUiState,
    context: Context,
    topLabel: String,
    selectedDay: LocalDate?,
    onSelectDay: (LocalDate?) -> Unit,
    onMove: (Int) -> Unit,
    draw: DrawScope.(days: List<DayStats>, selected: LocalDate?) -> Unit,
) {
    val days = state.days
    if (days.isEmpty()) return

    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            stringResource(R.string.stats_per_day),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            topLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(4.dp))

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(BARS_HEIGHT_DP.dp)
            .pointerInput(days) {
                detectTapGestures { tap ->
                    val index = ((tap.x / size.width) * days.size).toInt().coerceIn(0, days.lastIndex)
                    val picked = days[index].date
                    // Tapping the open day again puts it away.
                    onSelectDay(if (picked == selectedDay) null else picked)
                }
            }
            .pointerInput(days.size) {
                // A whole chart width is a whole window, so the data tracks the
                // finger. Only the horizontal gesture is taken; the list keeps
                // scrolling vertically.
                var carried = 0f
                detectHorizontalDragGestures(
                    onDragStart = { carried = 0f },
                ) { _, dragAmount ->
                    carried += dragAmount
                    val perDay = size.width / days.size.coerceAtLeast(1)
                    val moved = (carried / perDay).toInt()
                    if (moved != 0) {
                        // Pulling right walks backwards, the way a filmstrip would.
                        onMove(moved)
                        carried -= moved * perDay
                    }
                }
            },
    ) {
        draw(days, selectedDay)
    }

    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(shortDate(context, days.first().date), style = MaterialTheme.typography.labelSmall)
        Text(shortDate(context, days.last().date), style = MaterialTheme.typography.labelSmall)
    }
}

private fun DrawScope.barGeometry(count: Int): Triple<Float, Float, Float> {
    val slot = size.width / count
    val width = (slot * 0.7f).coerceAtLeast(1f)
    return Triple(slot, width, (slot - width) / 2f)
}

private fun DrawScope.drawDayBars(
    days: List<DayStats>,
    value: (DayStats) -> Float,
    bar: Color,
    empty: Color,
) {
    val max = days.maxOf(value).coerceAtLeast(0.001f)
    val (slot, width, inset) = barGeometry(days.size)
    days.forEachIndexed { i, day ->
        val v = value(day).coerceAtLeast(0f)
        // A day with nothing logged keeps a sliver, so the column reads as "a
        // day that happened and was empty" rather than as missing.
        val height = if (v == 0f) 2f else (v / max) * size.height
        drawRect(
            color = if (v == 0f) empty else bar,
            topLeft = Offset(i * slot + inset, size.height - height),
            size = Size(width, height),
        )
    }
}

/**
 * The nappy bars, split into what each change was made of: urine only, both,
 * stool only. The three add up to the number of changes — stacking `wet` and
 * `dirty` straight would count every mixed change twice and make the bar
 * disagree with the figure above it.
 */
private fun DrawScope.drawStackedDiapers(days: List<DayStats>, wet: Color, dirty: Color, empty: Color) {
    val max = days.maxOf { it.diaperCount }.coerceAtLeast(1)
    val (slot, width, inset) = barGeometry(days.size)
    val gap = 2f

    days.forEachIndexed { i, day ->
        val x = i * slot + inset
        if (day.diaperCount == 0) {
            drawRect(empty, topLeft = Offset(x, size.height - 2f), size = Size(width, 2f))
            return@forEachIndexed
        }
        val unit = size.height / max
        val segments = listOf(
            day.wetOnlyCount to wet,
            day.bothCount to null,
            day.dirtyOnlyCount to dirty,
        ).filter { it.first > 0 }

        var y = size.height
        segments.forEachIndexed { n, (count, colour) ->
            val full = count * unit
            y -= full
            val height = full - if (n < segments.size - 1) gap else 0f
            if (height <= 0f) return@forEachIndexed
            if (colour == null) {
                // "Both" is a hatch of the two rather than a third hue — the
                // segment is literally the overlap of the other two.
                clipPath(
                    Path().apply { addRect(Rect(x, y, x + width, y + height)) },
                ) {
                    drawRect(wet, topLeft = Offset(x, y), size = Size(width, height))
                    drawHatchLines(dirty, step = 8f, thickness = 4f)
                }
            } else {
                drawRect(colour, topLeft = Offset(x, y), size = Size(width, height))
            }
        }
    }
}

/** The open day keeps its colours and gains an outline. */
private fun DrawScope.drawSelection(days: List<DayStats>, selected: LocalDate?, ring: Color) {
    val index = days.indexOfFirst { it.date == selected }
    if (index < 0) return
    val (slot, width, inset) = barGeometry(days.size)
    drawRoundRect(
        ring.copy(alpha = .8f),
        topLeft = Offset(index * slot + inset - 3f, 0f),
        size = Size(width + 6f, size.height),
        cornerRadius = CornerRadius(4f, 4f),
        style = Stroke(width = 2f),
    )
}

// --- the day's detail ----------------------------------------------------

@Composable
private fun DayDetail(
    day: LocalDate?,
    context: Context,
    onClose: (LocalDate?) -> Unit,
    content: @Composable (LocalDate) -> Unit,
) {
    if (day == null) return
    Spacer(Modifier.height(10.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            formatDate(context, day.atStartOfDayMillis()),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onClose(null) }) { Text(stringResource(R.string.action_close)) }
    }
    content(day)
}

@Composable
private fun DetailRow(time: String, text: String, trailing: @Composable (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            time,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(44.dp),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
private fun DetailEmpty(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

// --- small pieces --------------------------------------------------------

@Composable
private fun StatsCard(title: String, icon: ImageVector, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun StatLine(label: String, value: String, emphasis: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = if (emphasis) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasis) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** A swatch and its name; [hatchWith] draws the overlap of two colours. */
@Composable
private fun LegendKey(label: String, colour: Color, hatchWith: Color? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(width = 14.dp, height = 10.dp)) {
            drawRect(colour)
            if (hatchWith != null) {
                clipPath(Path().apply { addRect(Rect(0f, 0f, size.width, size.height)) }) {
                    drawHatchLines(hatchWith, step = 7f, thickness = 3.5f)
                }
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

// --- colours -------------------------------------------------------------
//
// Not Material roles: these carry meaning of their own, and were picked to
// survive colour-vision deficiency rather than to match the theme. The band
// pair is diverging (warm below the median, cool above); the nappy pair
// separates urine from stool. Both were checked with a CVD validator, and the
// dark variants are lightened to hold their contrast on a dark surface.

@Composable private fun bandBelowColor(): Color =
    if (isSystemInDarkTheme()) Color(0xFFE9A664) else Color(0xFFDE8A3F)

@Composable private fun bandAboveColor(): Color =
    if (isSystemInDarkTheme()) Color(0xFF6FA9E6) else Color(0xFF4A93DE)

@Composable private fun dirtyColor(): Color =
    if (isSystemInDarkTheme()) Color(0xFFCF8949) else Color(0xFFB26A2B)

// --- formatting ----------------------------------------------------------

/** The measure's value for an entry, in the unit the WHO tables use; null when unmeasured. */
private fun valueOf(entry: GrowthEntity, measure: GrowthMeasure): Double? = when (measure) {
    GrowthMeasure.WEIGHT -> entry.weightGrams?.let { it / 1000.0 }
    GrowthMeasure.LENGTH -> entry.heightMm?.let { it / 10.0 }
    GrowthMeasure.HEAD -> entry.headMm?.let { it / 10.0 }
}

private fun measured(context: Context, measure: GrowthMeasure, value: Double): String = when (measure) {
    GrowthMeasure.WEIGHT -> context.getString(R.string.growth_weight_kg, value)
    // Both are a plain "38.4 cm" here; which of the two it is has just been
    // said by the selected chip, so `growth_head_cm` — which names itself —
    // would repeat it.
    GrowthMeasure.LENGTH, GrowthMeasure.HEAD -> context.getString(R.string.growth_height_cm, value)
}

private fun measurementLine(
    context: Context,
    measure: GrowthMeasure,
    value: Double,
    ageMonths: Double,
): String = context.getString(
    R.string.stats_last_measurement,
    measured(context, measure, value),
    context.getString(R.string.stats_age_months, ageMonths.roundToInt()),
)

/** "5.95 kg · 2.8 months" then the percentile, read against what is on screen. */
private fun readoutLines(
    context: Context,
    measure: GrowthMeasure,
    reference: GrowthReference,
    point: Pair<Double, Double>,
): List<String> {
    val (ageMonths, value) = point
    val age = if (ageMonths < 2) {
        context.getString(R.string.stats_age_days, (ageMonths * 30.4375).roundToInt())
    } else {
        context.getString(R.string.stats_age_months, ageMonths.roundToInt())
    }
    val first = context.getString(R.string.stats_readout_measure, measured(context, measure, value), age)
    val placement = whoPlacement(measure, ageMonths, value, reference.sex)
        ?: return listOf(first, context.getString(R.string.stats_readout_past_range))
    return listOf(first, percentileRange(context, placement))
}

/**
 * "P30–P48" — a span rather than one number when both references are on
 * screen, because they disagree and Sprout does not know which one applies.
 * The notation is the one printed on growth charts and in health records, in
 * every language we ship.
 */
private fun percentileRange(context: Context, placement: WhoPlacement): String {
    val low = placement.lowPercentile.roundToInt().coerceIn(1, 99)
    val high = placement.highPercentile.roundToInt().coerceIn(1, 99)
    return if (low == high) {
        context.getString(R.string.stats_percentile_one, low)
    } else {
        context.getString(R.string.stats_percentile_range, low, high)
    }
}

/** "3.2 × · 210 ml" — how often, and how much, on an average day. */
private fun countAndAmount(context: Context, count: Double, amount: String): String =
    context.getString(R.string.stats_count_and_amount, decimal(count), amount)

/** One decimal place, in the reader's own numbering. */
private fun decimal(value: Double): String = String.format(Locale.getDefault(), "%.1f", value)

private fun feedLine(context: Context, feed: FeedingEntity): String = when (feed.type) {
    FeedType.BREAST -> context.getString(
        R.string.stats_detail_breast,
        formatDuration(context, breastfeedMillis(feed)),
    )

    FeedType.BOTTLE -> feed.amountMl?.let {
        context.getString(R.string.stats_detail_bottle, context.getString(R.string.feeding_amount_ml, it))
    } ?: context.getString(R.string.stats_detail_bottle_unmeasured)

    FeedType.SOLID -> feed.amountGrams?.let {
        context.getString(R.string.stats_detail_solid, context.getString(R.string.feeding_amount_g, it))
    } ?: context.getString(R.string.stats_detail_solid_unweighed)
}

private fun diaperLine(context: Context, change: DiaperEntity): String = when {
    change.wet && change.dirty -> context.getString(R.string.stats_diaper_both)
    change.dirty -> context.getString(R.string.stats_dirty_per_day)
    else -> context.getString(R.string.stats_wet_per_day)
}

/** The window's end dates, in the same short form the history lists use. */
private fun shortDate(context: Context, date: LocalDate): String =
    formatDate(context, date.atStartOfDayMillis())

private fun LocalDate.atStartOfDayMillis(): Long =
    atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
