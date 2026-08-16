package com.gproust.sprout.ui.stats

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BabyChangingStation
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gproust.sprout.R
import com.gproust.sprout.data.SproutRepository
import com.gproust.sprout.data.local.DiaperEntity
import com.gproust.sprout.data.local.FeedingEntity
import com.gproust.sprout.data.local.GrowthEntity
import com.gproust.sprout.data.local.SleepEntity
import com.gproust.sprout.ui.common.ChoiceChips
import com.gproust.sprout.ui.common.EmptyHint
import com.gproust.sprout.ui.common.SproutTopBar
import com.gproust.sprout.ui.common.formatDate
import com.gproust.sprout.ui.common.formatDuration
import com.gproust.sprout.ui.rememberSproutViewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

/** How far back the screen looks. Kept short: this is a follow-up, not an archive. */
enum class StatsPeriod(val days: Int, @param:StringRes val labelRes: Int) {
    WEEK(7, R.string.stats_period_week),
    MONTH(30, R.string.stats_period_month),
    QUARTER(90, R.string.stats_period_quarter),
}

/** The chip label for each curve the growth card can show. */
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
    val days: List<DayStats> = emptyList(),
    val averages: StatsAverages = StatsAverages(),
    /** Measurements inside the window, oldest first. */
    val growth: List<GrowthEntity> = emptyList(),
    val today: LocalDate = LocalDate.now(),
) {
    /** Whether anything at all was logged in the window — an empty week says so once. */
    val hasLogs: Boolean get() = days.any { it.feedCount > 0 || it.sleepCount > 0 || it.diaperCount > 0 }
}

class StatsViewModel(private val repository: SproutRepository) : ViewModel() {

    private val period = MutableStateFlow(StatsPeriod.WEEK)

    fun setPeriod(value: StatsPeriod) {
        period.value = value
    }

    private val logs = combine(
        repository.feedings,
        repository.sleeps,
        repository.diapers,
        repository.growth,
    ) { feedings, sleeps, diapers, growth -> Logs(feedings, sleeps, diapers, growth) }

    val uiState = combine(repository.baby, logs, period) { baby, entries, chosen ->
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val born = baby?.birthDate?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        val from = statsWindowStart(today, chosen.days, born)
        val days = dailyStats(entries.feedings, entries.sleeps, entries.diapers, from, today, now, zone)

        StatsUiState(
            hasBaby = baby != null,
            birthDate = baby?.birthDate,
            period = chosen,
            days = days,
            averages = averagesOf(days, today),
            // Growth is shown over the whole history rather than the window: a
            // curve is only worth reading over months, and a fortnight of it
            // would be two dots and no shape.
            growth = entries.growth.sortedBy { it.time },
            today = today,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StatsUiState())
}

@Composable
fun StatsScreen(onBack: () -> Unit = {}) {
    val vm: StatsViewModel = viewModel(factory = rememberSproutViewModelFactory())
    val state by vm.uiState.collectAsState()
    val context = LocalContext.current
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
                    onSelect = vm::setPeriod,
                    labelOf = { periodLabels.getValue(it) },
                )
            }

            item {
                Text(
                    if (state.averages.dayCount == 1 && state.days.size == 1) {
                        stringResource(R.string.stats_average_today_only)
                    } else {
                        stringResource(R.string.stats_average_over, state.averages.dayCount)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!state.hasLogs) {
                item { EmptyHint(stringResource(R.string.stats_empty)) }
            } else {
                item { FeedingStats(state, context) }
                item { SleepStats(state, context) }
                item { DiaperStats(state, context) }
            }

            item { GrowthStats(state, context) }
        }
    }
}

@Composable
private fun FeedingStats(state: StatsUiState, context: Context) {
    val averages = state.averages
    StatsCard(stringResource(R.string.stats_feeding_title), Icons.Filled.LocalDrink) {
        StatLine(
            stringResource(R.string.stats_feeds_per_day),
            decimal(averages.feedsPerDay),
            emphasis = true,
        )
        StatLine(
            stringResource(R.string.stats_breast_per_day),
            countAndAmount(
                context,
                averages.breastfeedsPerDay,
                formatDuration(context, averages.breastMillisPerDay),
            ),
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
        DayChart(
            days = state.days,
            value = { it.feedCount.toFloat() },
            context = context,
            topLabel = state.days.maxOfOrNull { it.feedCount }?.toString().orEmpty(),
        )
    }
}

@Composable
private fun SleepStats(state: StatsUiState, context: Context) {
    StatsCard(stringResource(R.string.stats_sleep_title), Icons.Filled.Bedtime) {
        StatLine(
            stringResource(R.string.stats_sleep_per_day),
            formatDuration(context, state.averages.sleepMillisPerDay),
            emphasis = true,
        )
        StatLine(
            stringResource(R.string.stats_sleeps_per_day),
            decimal(state.averages.sleepsPerDay),
        )
        DayChart(
            days = state.days,
            value = { it.sleepMillis / 3_600_000f },
            context = context,
            topLabel = state.days.maxOfOrNull { it.sleepMillis }
                ?.let { formatDuration(context, it) }
                .orEmpty(),
        )
    }
}

@Composable
private fun DiaperStats(state: StatsUiState, context: Context) {
    StatsCard(stringResource(R.string.stats_diaper_title), Icons.Filled.BabyChangingStation) {
        StatLine(
            stringResource(R.string.stats_diapers_per_day),
            decimal(state.averages.diapersPerDay),
            emphasis = true,
        )
        StatLine(stringResource(R.string.stats_wet_per_day), decimal(state.averages.wetPerDay))
        StatLine(stringResource(R.string.stats_dirty_per_day), decimal(state.averages.dirtyPerDay))
        DayChart(
            days = state.days,
            value = { it.diaperCount.toFloat() },
            context = context,
            topLabel = state.days.maxOfOrNull { it.diaperCount }?.toString().orEmpty(),
        )
    }
}

/**
 * The growth card: a baby's own measurements drawn over the WHO band, and
 * where the most recent one falls in it.
 */
@Composable
private fun GrowthStats(state: StatsUiState, context: Context) {
    var measure by remember { mutableStateOf(GrowthMeasure.WEIGHT) }
    val birthDate = state.birthDate
    // See the period chips: resolved in the composable, not in `labelOf`.
    val measureLabels = GrowthMeasure.entries.associateWith { stringResource(MEASURE_LABELS.getValue(it)) }

    StatsCard(stringResource(R.string.stats_growth_title), Icons.Filled.Monitor) {
        ChoiceChips(
            options = GrowthMeasure.entries,
            selected = measure,
            onSelect = { measure = it },
            labelOf = { measureLabels.getValue(it) },
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

        GrowthCurve(
            band = band,
            points = points,
            lineColor = MaterialTheme.colorScheme.primary,
            bandColor = MaterialTheme.colorScheme.tertiary,
            medianColor = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().height(200.dp),
        )

        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.stats_age_months, 0), style = MaterialTheme.typography.labelSmall)
            Text(
                stringResource(R.string.stats_age_months, band.lastOrNull()?.ageMonths?.roundToInt() ?: 0),
                style = MaterialTheme.typography.labelSmall,
            )
        }

        Spacer(Modifier.height(8.dp))
        val last = points.last()
        val placement = whoPlacement(measure, last.first, last.second)
        Text(
            measurementLine(context, measure, last.second, last.first),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            when {
                placement == null -> stringResource(R.string.stats_who_past_range)
                placement.insideBand -> stringResource(
                    R.string.stats_who_inside,
                    percentileRange(context, placement),
                )

                else -> stringResource(
                    R.string.stats_who_outside,
                    percentileRange(context, placement),
                )
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            stringResource(R.string.stats_who_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** The measure's value for an entry, in the unit the WHO tables use; null when unmeasured. */
private fun valueOf(entry: GrowthEntity, measure: GrowthMeasure): Double? = when (measure) {
    GrowthMeasure.WEIGHT -> entry.weightGrams?.let { it / 1000.0 }
    GrowthMeasure.LENGTH -> entry.heightMm?.let { it / 10.0 }
    GrowthMeasure.HEAD -> entry.headMm?.let { it / 10.0 }
}

private fun measurementLine(
    context: Context,
    measure: GrowthMeasure,
    value: Double,
    ageMonths: Double,
): String {
    val measured = when (measure) {
        GrowthMeasure.WEIGHT -> context.getString(R.string.growth_weight_kg, value)
        // Both are a plain "38.4 cm" here; which of the two it is has just been
        // said by the selected chip, so `growth_head_cm` — which names itself —
        // would repeat it.
        GrowthMeasure.LENGTH, GrowthMeasure.HEAD ->
            context.getString(R.string.growth_height_cm, value)
    }
    return context.getString(
        R.string.stats_last_measurement,
        measured,
        context.getString(R.string.stats_age_months, ageMonths.roundToInt()),
    )
}

/**
 * "P30–P48" — a span rather than one number, because the two references
 * disagree and Sprout does not know which one applies. The notation is the one
 * printed on growth charts and in health records, in every language we ship.
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
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = if (emphasis) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasis) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/**
 * One bar per day of the window, with the first and last day named underneath.
 *
 * Deliberately unlabelled between the ends: at 90 days there is no room for
 * dates, and the shape — a run of empty days, a night that went badly — is what
 * the chart is for. The exact numbers are one screen away in each log.
 */
@Composable
private fun DayChart(
    days: List<DayStats>,
    value: (DayStats) -> Float,
    context: Context,
    topLabel: String,
) {
    if (days.isEmpty()) return
    val barColor = MaterialTheme.colorScheme.primary
    val emptyColor = MaterialTheme.colorScheme.surfaceVariant

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
    Canvas(Modifier.fillMaxWidth().height(80.dp)) {
        val max = days.maxOf { value(it) }.coerceAtLeast(0.001f)
        val slot = size.width / days.size
        val barWidth = (slot * 0.7f).coerceAtLeast(1f)
        val inset = (slot - barWidth) / 2
        days.forEachIndexed { index, day ->
            val v = value(day).coerceAtLeast(0f)
            // A day with nothing logged keeps a sliver, so the column reads as
            // "a day that happened and was empty" rather than as missing.
            val height = if (v == 0f) 2f else (v / max) * size.height
            drawRect(
                color = if (v == 0f) emptyColor else barColor,
                topLeft = Offset(index * slot + inset, size.height - height),
                size = Size(barWidth, height),
            )
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(shortDate(context, days.first().date), style = MaterialTheme.typography.labelSmall)
        Text(shortDate(context, days.last().date), style = MaterialTheme.typography.labelSmall)
    }
}

/** The window's end dates, in the same short form the history lists use. */
private fun shortDate(context: Context, date: LocalDate): String =
    formatDate(context, date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())

/**
 * A baby's measurements over the WHO band.
 *
 * The band is drawn as two nested shaded areas — the 3rd to 97th percentile,
 * and the 15th to 85th inside it — with the two medians as dashed lines, so it
 * reads at a glance the way a printed growth chart does: the further from the
 * middle of the shading, the further from the middle of the reference.
 */
@Composable
private fun GrowthCurve(
    band: List<WhoBandPoint>,
    points: List<Pair<Double, Double>>,
    lineColor: Color,
    bandColor: Color,
    medianColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val maxAge = maxOf(
            band.lastOrNull()?.ageMonths ?: 0.0,
            points.maxOfOrNull { it.first } ?: 0.0,
        ).coerceAtLeast(1.0)
        val values = band.flatMap { listOf(it.p3, it.p97) } + points.map { it.second }
        val minValue = values.min()
        val maxValue = values.max()
        val span = (maxValue - minValue).coerceAtLeast(0.001)
        val padY = size.height * 0.06f
        val usableHeight = size.height - padY * 2

        fun x(ageMonths: Double) = (ageMonths / maxAge).toFloat() * size.width
        fun y(value: Double) = padY + (1f - ((value - minValue) / span).toFloat()) * usableHeight

        fun area(lower: (WhoBandPoint) -> Double, upper: (WhoBandPoint) -> Double) = Path().apply {
            band.forEachIndexed { i, p ->
                if (i == 0) moveTo(x(p.ageMonths), y(upper(p))) else lineTo(x(p.ageMonths), y(upper(p)))
            }
            band.asReversed().forEach { p -> lineTo(x(p.ageMonths), y(lower(p))) }
            close()
        }

        fun line(of: (WhoBandPoint) -> Double) = Path().apply {
            band.forEachIndexed { i, p ->
                if (i == 0) moveTo(x(p.ageMonths), y(of(p))) else lineTo(x(p.ageMonths), y(of(p)))
            }
        }

        if (band.size >= 2) {
            drawPath(area({ it.p3 }, { it.p97 }), color = bandColor.copy(alpha = 0.14f))
            drawPath(area({ it.p15 }, { it.p85 }), color = bandColor.copy(alpha = 0.16f))
            val dashes = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
            drawPath(
                line { it.girlsMedian },
                color = medianColor.copy(alpha = 0.5f),
                style = Stroke(width = 2f, pathEffect = dashes),
            )
            drawPath(
                line { it.boysMedian },
                color = medianColor.copy(alpha = 0.5f),
                style = Stroke(width = 2f, pathEffect = dashes),
            )
        }

        val own = points.map { Offset(x(it.first), y(it.second)) }
        if (own.size >= 2) {
            drawPath(
                Path().apply {
                    own.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
                },
                color = lineColor,
                style = Stroke(width = 5f),
            )
        }
        own.forEach { drawCircle(color = lineColor, radius = 7f, center = it) }
    }
}
