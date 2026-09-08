package com.gproust.sprout.ui.report

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gproust.sprout.R
import com.gproust.sprout.data.SproutRepository
import com.gproust.sprout.data.export.ExportFiles
import com.gproust.sprout.data.export.Xlsx
import com.gproust.sprout.ui.common.ChoiceChips
import com.gproust.sprout.ui.common.DatePickerField
import com.gproust.sprout.ui.common.EmptyHint
import com.gproust.sprout.ui.common.SectionLabel
import com.gproust.sprout.ui.common.SproutTopBar
import com.gproust.sprout.ui.common.formatDate
import com.gproust.sprout.ui.rememberSproutViewModelFactory
import com.gproust.sprout.ui.stats.WhoSex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Which file the parent asked for. */
enum class ReportFormat { PDF, WORKBOOK }

/**
 * The WHO reference the growth pages are read against, as three chips.
 *
 * A view choice and nothing more: it is not stored, not synced and not
 * remembered between exports, because remembering it would be keeping a baby's
 * sex on the device by the back door — the very field BDR-0008 decided not to
 * have. Every export starts at [BOTH] again.
 */
enum class ReportReference(val sex: WhoSex?) {
    BOTH(null),
    GIRLS(WhoSex.GIRLS),
    BOYS(WhoSex.BOYS),
}

data class ReportUiState(
    val babyName: String? = null,
    val birthDate: Long? = null,
    val options: ReportOptions = ReportOptions(),
    val range: ReportRange? = null,
    val working: Boolean = false,
    val failed: Boolean = false,
)

class ReportViewModel(
    private val repository: SproutRepository,
    private val context: Context,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()

    private val _state = MutableStateFlow(ReportUiState())
    val state = _state.asStateFlow()

    /** The share sheet to open, once a file has been written. */
    private val _share = MutableStateFlow<Intent?>(null)
    val share = _share.asStateFlow()

    private var babyId: Long? = null

    fun setBaby(id: Long) {
        if (babyId == id) return
        babyId = id
        viewModelScope.launch {
            val baby = repository.activeBaby(id) ?: return@launch
            _state.update {
                it.copy(babyName = baby.name, birthDate = baby.birthDate)
            }
            recomputeRange()
        }
    }

    fun setPeriod(period: ReportPeriod) {
        val today = LocalDate.now(zone)
        _state.update { current ->
            current.copy(
                options = current.options.copy(
                    period = period,
                    // A custom range opens on the last thirty days rather than
                    // on nothing, so the two pickers start somewhere sensible.
                    customFrom = current.options.customFrom ?: today.minusDays(29),
                    customTo = current.options.customTo ?: today,
                ),
            )
        }
        recomputeRange()
    }

    fun setCustomFrom(day: LocalDate) {
        _state.update { it.copy(options = it.options.copy(customFrom = day)) }
        recomputeRange()
    }

    fun setCustomTo(day: LocalDate) {
        _state.update { it.copy(options = it.options.copy(customTo = day)) }
        recomputeRange()
    }

    fun setReference(reference: ReportReference) {
        _state.update { it.copy(options = it.options.copy(reference = reference.sex)) }
    }

    fun setIncludeDailyTable(include: Boolean) {
        _state.update { it.copy(options = it.options.copy(includeDailyTable = include)) }
    }

    fun setIncludeTreatments(include: Boolean) {
        _state.update { it.copy(options = it.options.copy(includeTreatments = include)) }
    }

    fun setIncludeNotes(include: Boolean) {
        _state.update { it.copy(options = it.options.copy(includeNotes = include)) }
    }

    fun shareConsumed() {
        _share.value = null
    }

    /**
     * Builds the file and hands back a share sheet.
     *
     * All of it happens off the main thread — a year of entries is a few
     * thousand rows to read, total and draw — and the screen says it is working
     * meanwhile rather than appearing to have ignored the tap.
     */
    fun export(format: ReportFormat) {
        val id = babyId ?: return
        if (_state.value.working) return
        _state.update { it.copy(working = true, failed = false) }
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val report = assemble(id)
                    val name = ExportFiles.fileName(
                        report.babyName,
                        report.range.from,
                        report.range.to,
                        if (format == ReportFormat.PDF) "pdf" else "xlsx",
                    )
                    val uri = ExportFiles.stage(context, name) { out ->
                        when (format) {
                            ReportFormat.PDF -> ReportPdf(context, report, zone).write(out)
                            ReportFormat.WORKBOOK -> out.write(ReportWorkbook.bytes(report, zone))
                        }
                    }
                    ExportFiles.shareIntent(
                        uri,
                        if (format == ReportFormat.PDF) ExportFiles.PDF_MIME else Xlsx.MIME_TYPE,
                        context.getString(R.string.report_share_subject, report.babyName),
                    )
                }
            }
            _state.update { it.copy(working = false, failed = result.isFailure) }
            result.getOrNull()?.let { _share.value = it }
        }
    }

    private suspend fun assemble(id: Long): ReportContent {
        val baby = requireNotNull(repository.activeBaby(id)) { "no baby $id" }
        return buildReport(
            baby = baby,
            feedings = repository.feedingsForBabyOnce(id),
            sleeps = repository.sleepsForBabyOnce(id),
            diapers = repository.diapersForBabyOnce(id),
            growth = repository.growthForBabyOnce(id),
            treatments = repository.treatmentsForBabyOnce(id),
            options = _state.value.options,
            now = System.currentTimeMillis(),
            zone = zone,
        )
    }

    /** The range the current choices actually mean, clamped and shown before anything is made. */
    private fun recomputeRange() {
        val birth = _state.value.birthDate ?: return
        val options = _state.value.options
        val range = reportRange(
            period = options.period,
            today = LocalDate.now(zone),
            birthDay = Instant.ofEpochMilli(birth).atZone(zone).toLocalDate(),
            customFrom = options.customFrom,
            customTo = options.customTo,
        )
        _state.update { it.copy(range = range) }
    }
}

/**
 * "Share a record": pick a stretch of days, get a PDF for the appointment or a
 * workbook for whoever wants the rows (BDR-0012).
 *
 * The baby is settled before this screen opens — it is reached from the share
 * action on that baby's own page — so there is no picker here and no way to
 * export the wrong child's record by leaving a menu on the wrong name.
 */
@Composable
fun ReportScreen(babyId: Long, onBack: () -> Unit) {
    val vm: ReportViewModel = viewModel(factory = rememberSproutViewModelFactory())
    val state by vm.state.collectAsState()
    val share by vm.share.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(babyId) { vm.setBaby(babyId) }
    LaunchedEffect(share) {
        share?.let {
            context.startActivity(it)
            vm.shareConsumed()
        }
    }

    val periodLabels = ReportPeriod.entries.associateWith { period ->
        stringResource(
            when (period) {
                ReportPeriod.WEEK -> R.string.stats_period_week
                ReportPeriod.MONTH -> R.string.stats_period_month
                ReportPeriod.QUARTER -> R.string.stats_period_quarter
                ReportPeriod.SINCE_BIRTH -> R.string.report_period_birth
                ReportPeriod.CUSTOM -> R.string.report_period_custom
            },
        )
    }
    val referenceLabels = ReportReference.entries.associateWith { reference ->
        stringResource(
            when (reference) {
                ReportReference.BOTH -> R.string.stats_reference_both
                ReportReference.GIRLS -> R.string.stats_reference_girls
                ReportReference.BOYS -> R.string.stats_reference_boys
            },
        )
    }

    Scaffold(
        topBar = { SproutTopBar(stringResource(R.string.report_screen_title), onBack = onBack) },
        // The two buttons sit in a bar of their own rather than at the foot of
        // the list. Everything above them is a choice with a sensible default,
        // so the common trip through this screen is "open it, tap PDF" — and a
        // screen whose whole point is one tap should not open with that tap
        // below the fold, let alone a scroll and a half down once a custom
        // range has added its two date fields.
        bottomBar = {
            if (state.babyName != null) {
                ExportBar(
                    working = state.working,
                    failed = state.failed,
                    onExport = vm::export,
                )
            }
        },
    ) { padding ->
        if (state.babyName == null) {
            EmptyHint(
                stringResource(R.string.stats_no_baby),
                Modifier.padding(padding).padding(16.dp),
            )
            return@Scaffold
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                stringResource(R.string.report_intro, state.babyName.orEmpty()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionLabel(stringResource(R.string.report_period_label))
            ChoiceChips(
                options = ReportPeriod.entries,
                selected = state.options.period,
                onSelect = vm::setPeriod,
                labelOf = { periodLabels.getValue(it) },
            )

            if (state.options.period == ReportPeriod.CUSTOM) {
                val zone = ZoneId.systemDefault()
                val from = state.options.customFrom ?: LocalDate.now(zone).minusDays(29)
                val to = state.options.customTo ?: LocalDate.now(zone)
                Column(
                    Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DatePickerField(
                        label = stringResource(R.string.report_custom_from),
                        millis = from.atStartOfDay(zone).toInstant().toEpochMilli(),
                        onChange = { vm.setCustomFrom(it.toLocalDate(zone)) },
                        allowFuture = false,
                    )
                    DatePickerField(
                        label = stringResource(R.string.report_custom_to),
                        millis = to.atStartOfDay(zone).toInstant().toEpochMilli(),
                        onChange = { vm.setCustomTo(it.toLocalDate(zone)) },
                        allowFuture = false,
                    )
                }
            }

            state.range?.let { range ->
                RangeSummary(range, context)
            }

            SectionLabel(stringResource(R.string.report_reference_label))
            ChoiceChips(
                options = ReportReference.entries,
                selected = ReportReference.entries.first { it.sex == state.options.reference },
                onSelect = vm::setReference,
                labelOf = { referenceLabels.getValue(it) },
            )
            Text(
                stringResource(R.string.report_reference_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            SectionLabel(stringResource(R.string.report_include_label))
            ToggleRow(
                title = stringResource(R.string.report_include_daily),
                subtitle = stringResource(R.string.report_include_daily_hint),
                checked = state.options.includeDailyTable,
                onChange = vm::setIncludeDailyTable,
            )
            ToggleRow(
                title = stringResource(R.string.screen_treatments),
                subtitle = stringResource(R.string.report_include_treatments_hint),
                checked = state.options.includeTreatments,
                onChange = vm::setIncludeTreatments,
            )
            ToggleRow(
                title = stringResource(R.string.report_include_notes),
                subtitle = stringResource(R.string.report_include_notes_hint),
                checked = state.options.includeNotes,
                onChange = vm::setIncludeNotes,
            )

            Text(
                stringResource(R.string.report_privacy_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

/** The two things this screen exists to produce, always within reach. */
@Composable
private fun ExportBar(
    working: Boolean,
    failed: Boolean,
    onExport: (ReportFormat) -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (working) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        stringResource(R.string.report_working),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (failed) {
                Text(
                    stringResource(R.string.report_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = { onExport(ReportFormat.PDF) },
                    enabled = !working,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Description, contentDescription = null)
                    Text(
                        stringResource(R.string.report_make_pdf),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                OutlinedButton(
                    onClick = { onExport(ReportFormat.WORKBOOK) },
                    enabled = !working,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.TableChart, contentDescription = null)
                    Text(
                        stringResource(R.string.report_make_workbook),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

/**
 * The days the report will actually cover.
 *
 * Shown before anything is made, because the range asked for and the range
 * covered are not always the same: a window is clamped to the birth, and to
 * today. Finding that out from the finished document would be finding it out
 * too late.
 */
@Composable
private fun RangeSummary(range: ReportRange, context: Context) {
    Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(
                    R.string.stats_window_range,
                    formatDate(context, range.from.atStartOfDayMillis()),
                    formatDate(context, range.to.atStartOfDayMillis()),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.report_days_count, range.dayCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
        HorizontalDivider()
    }
}

private fun Long.toLocalDate(zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

private fun LocalDate.atStartOfDayMillis(): Long =
    atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
