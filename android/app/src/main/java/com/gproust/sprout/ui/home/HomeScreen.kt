@file:OptIn(ExperimentalMaterial3Api::class)

package com.gproust.sprout.ui.home

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gproust.sprout.R
import com.gproust.sprout.data.MEDICINE_DAY_MS
import com.gproust.sprout.data.MedicineWatch
import com.gproust.sprout.data.SproutRepository
import com.gproust.sprout.data.local.BreastSide
import com.gproust.sprout.data.local.DiaperEntity
import com.gproust.sprout.data.local.FeedingEntity
import com.gproust.sprout.data.local.MedicineDoseEntity
import com.gproust.sprout.data.local.MedicineEntity
import com.gproust.sprout.data.local.SleepEntity
import com.gproust.sprout.notifications.MedicineReminders
import com.gproust.sprout.ui.common.ageInDays
import com.gproust.sprout.ui.common.babyAge
import com.gproust.sprout.ui.common.currentGrowthSpurt
import com.gproust.sprout.ui.common.formatClock
import com.gproust.sprout.ui.common.greetingFor
import com.gproust.sprout.ui.common.growthSpurtAgeLabel
import com.gproust.sprout.ui.common.shouldOfferCheckIn
import com.gproust.sprout.ui.common.startOfDay
import com.gproust.sprout.ui.common.upcomingGrowthSpurt
import com.gproust.sprout.ui.feeding.NursingSessionStore
import com.gproust.sprout.ui.navigation.Routes
import com.gproust.sprout.ui.rememberSproutViewModelFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** How often the dashboard's "how long ago" chips are refreshed. */
private const val CHIP_REFRESH_MS = 60_000L

/** How often the summary itself is recomputed. See [HomeViewModel.minuteTick]. */
private const val SUMMARY_TICK_MS = 60_000L

data class HomeUiState(
    val parentName: String? = null,
    val hasProfile: Boolean = false,
    /** One line per tracked baby, in birth order. */
    val babies: List<BabySummary> = emptyList(),
    /** Growth-spurt notes worth showing, keyed by baby id. */
    val spurts: Map<Long, GrowthSpurtUi> = emptyMap(),
    /** Whether this parent tracks their own wellbeing at all (Settings). */
    val tracksWellbeing: Boolean = true,
    /** Whether today's check-in is still waiting to be filled in. */
    val checkInPending: Boolean = false,
)

/** The growth spurt note to show for a baby, when one is relevant. */
data class GrowthSpurtUi(val ageLabel: String, val startsSoon: Boolean)

/** One emission of every household-wide read the dashboard folds together. */
private data class HouseholdRows(
    val feedings: List<FeedingEntity>,
    val sleeps: List<SleepEntity>,
    val diapers: List<DiaperEntity>,
    val medicine: MedicineRows,
)

/** The as-needed medicines, their doses, and what has been put away (BDR-16). */
private data class MedicineRows(
    val medicines: List<MedicineEntity>,
    val doses: List<MedicineDoseEntity>,
    val dismissed: Map<String, Long>,
)

class HomeViewModel(
    private val repository: SproutRepository,
    private val context: Context,
) : ViewModel() {
    /**
     * The dashboard's read window, fixed for the life of the view model. It
     * only decides how far back a "last fed" can be found, so a few hours of
     * drift over a long-lived process changes nothing anyone can see.
     */
    private val windowStart = System.currentTimeMillis() - HOUSEHOLD_WINDOW_MS

    val activeBabyId = repository.parentProfile
        .map { it?.activeBabyId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * Selecting a baby is a suspending call on purpose. The quick feed button
     * has to know the write will land on the card that was tapped, and every
     * feeding write resolves the active baby when it inserts — so the caller
     * awaits this before it navigates, rather than firing both and hoping.
     */
    suspend fun selectBaby(id: Long) = repository.setActiveBaby(id)

    /** "Not today" on the check-in card: put it away until tomorrow, nothing saved. */
    fun dismissCheckIn() = viewModelScope.launch {
        repository.updateParentLastCheckIn(System.currentTimeMillis())
    }

    /** Close a sleep that was logged as still running. */
    fun wakeUp(sleep: SleepEntity) = viewModelScope.launch {
        repository.updateSleep(sleep.copy(endTime = System.currentTimeMillis()))
    }

    /**
     * Logs a dose of [medicine] as given now, straight from the dashboard, and
     * moves its reminder on.
     *
     * The same write the as-needed screen makes, offered a screen earlier: the
     * dashboard is where a parent already is when the wait runs out, and a dose
     * given but not logged is the failure the feature exists to prevent
     * (BDR-15, BDR-16).
     */
    fun giveDose(watch: MedicineWatch) = viewModelScope.launch {
        val medicine = watch.medicine
        repository.giveMedicineDose(medicine, System.currentTimeMillis())
        // Re-read rather than reuse the dashboard's list: the flow that feeds
        // this screen and the write above are not ordered against each other,
        // so arming from it could set the alarm from the previous dose.
        MedicineReminders.schedule(
            context,
            medicine,
            repository.recentDosesOf(medicine.uid, MEDICINE_DAY_MS),
        )
    }

    /**
     * Puts a medicine away until it is next given (BDR-16).
     *
     * The dose it was dismissed against is what is stored, so this expires on
     * its own: the line comes back the moment there is a newer dose to count
     * from. Nothing is written to the baby's record — a dismissal is about a
     * parent having read a screen, and the other phone's parent has not.
     */
    fun dismiss(watch: MedicineWatch) {
        val lastDoseAt = watch.readiness.lastDoseAt ?: return
        MedicineDismissals.dismiss(context, watch.medicine.uid, lastDoseAt)
    }

    /**
     * The three medicine reads, folded first.
     *
     * Five flows is what one [combine] takes, and the dashboard now wants six —
     * so the medicines go together, which is the grouping that would have been
     * chosen anyway: a dose belongs to a medicine, and a dismissal names one.
     */
    private val medicineRows = combine(
        repository.householdMedicines,
        repository.householdMedicineDoses(windowStart),
        MedicineDismissals.dismissals(context),
    ) { medicines, doses, dismissed -> MedicineRows(medicines, doses, dismissed) }

    /**
     * The household's recent rows, as one value.
     *
     * Grouped rather than combined at the top for more than the arity: the
     * summary is a function of all of them together, and emitting it from a
     * partial mixture would draw a baby's feeds beside another minute's doses.
     */
    private val householdRows = combine(
        repository.householdFeedings(windowStart),
        repository.householdSleeps(windowStart),
        repository.householdDiapers(windowStart),
        medicineRows,
    ) { feedings, sleeps, diapers, medicine ->
        HouseholdRows(feedings, sleeps, diapers, medicine)
    }

    /**
     * A minute hand for the summary itself.
     *
     * Most of this screen is a time *stamp* that the composable formats against
     * its own clock, so nothing here had to tick. A medicine's state is not:
     * it is arithmetic against now, and a wait that runs out while the app is
     * open has to turn green with nothing written and nobody touching anything
     * (BDR-16). The fold is over a week of rows at most, once a minute.
     */
    private val minuteTick = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(SUMMARY_TICK_MS)
        }
    }

    val uiState = combine(
        repository.parentProfile,
        repository.babies,
        householdRows,
        repository.ongoingSleeps,
        minuteTick,
    ) { parent, babies, rows, ongoing, now ->
        val summaries = summariseHousehold(
            babies = babies,
            feedings = rows.feedings,
            sleeps = rows.sleeps,
            diapers = rows.diapers,
            ongoingSleeps = ongoing,
            medicines = rows.medicine.medicines,
            medicineDoses = rows.medicine.doses,
            dismissedMedicines = rows.medicine.dismissed,
            dayStart = startOfDay(now),
            now = now,
        )

        HomeUiState(
            parentName = parent?.name,
            hasProfile = babies.isNotEmpty(),
            babies = summaries,
            spurts = summaries.mapNotNull { summary ->
                spurtFor(summary.baby.birthDate, now)?.let { summary.baby.id to it }
            }.toMap(),
            // Defaults to on before the profile has loaded, so the shortcut
            // doesn't blink in; only an explicit "off" hides it.
            tracksWellbeing = parent?.trackWellbeing != false,
            checkInPending = parent != null &&
                shouldOfferCheckIn(parent.trackWellbeing, parent.lastCheckIn, now),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())

    private fun spurtFor(birthDate: Long, now: Long): GrowthSpurtUi? {
        val ageDays = ageInDays(birthDate, now)
        currentGrowthSpurt(ageDays)?.let {
            return GrowthSpurtUi(growthSpurtAgeLabel(context, it), startsSoon = false)
        }
        upcomingGrowthSpurt(ageDays)?.let {
            return GrowthSpurtUi(growthSpurtAgeLabel(context, it), startsSoon = true)
        }
        return null
    }
}

/**
 * The household dashboard.
 *
 * It shows every tracked baby rather than whichever one is active, because the
 * thing being done here is logging, and with more than one baby a global
 * "current child" set in a menu is how a feed ends up on the wrong one
 * (BDR-9). With a single baby there is nothing to disambiguate, so the one
 * card opens out into the full baby view in place — same screen, composed for
 * the family it belongs to.
 */
@Composable
fun HomeScreen(
    onNavigate: (String) -> Unit,
    onQuickFeed: (BreastSide) -> Unit,
    onShareRecord: (Long) -> Unit = {},
) {
    val vm: HomeViewModel = viewModel(factory = rememberSproutViewModelFactory())
    val state by vm.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Only coarse enough for "2h ago"; the live row keeps its own second hand.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(CHIP_REFRESH_MS)
            now = System.currentTimeMillis()
        }
    }

    /** Set the baby the card belongs to, *then* act. Order matters here. */
    fun withBaby(babyId: Long, then: () -> Unit) = scope.launch {
        vm.selectBaby(babyId)
        then()
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { onNavigate(Routes.PROFILE) }) {
                        Icon(
                            Icons.Filled.Person,
                            contentDescription = stringResource(R.string.screen_babies),
                        )
                    }
                    IconButton(onClick = { onNavigate(Routes.SETTINGS) }) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.cd_settings),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (!state.hasProfile) {
                NoBabyYet(onSetUp = { onNavigate(Routes.PROFILE) })
                return@Column
            }

            state.parentName?.let { parent ->
                Text(
                    stringResource(R.string.home_greeting, greetingFor(context, now), parent),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }

            LiveRow(
                sleeping = state.babies.firstOrNull { it.ongoingSleep != null },
                onOpenNursing = onQuickFeed,
                onWakeUp = vm::wakeUp,
            )

            val single = state.babies.singleOrNull()
            if (single != null) {
                // One baby: no card to pick, so the dashboard *is* the baby view.
                BabyPane(
                    summary = single,
                    tracksWellbeing = state.tracksWellbeing,
                    now = now,
                    onFeed = { side -> withBaby(single.baby.id) { onQuickFeed(side) } },
                    onNavigate = onNavigate,
                    onGiveMedicine = vm::giveDose,
                    onDismissMedicine = vm::dismiss,
                    onShareRecord = { onShareRecord(single.baby.id) },
                    header = {
                        Text(
                            single.baby.name,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            babyAge(context, single.baby.birthDate, now),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    },
                )
                state.spurts[single.baby.id]?.let {
                    Spacer(Modifier.height(16.dp))
                    GrowthSpurtNote(it)
                }
            } else {
                state.babies.forEach { summary ->
                    BabyCard(
                        summary = summary,
                        now = now,
                        onOpen = { withBaby(summary.baby.id) { onNavigate(Routes.BABY) } },
                        onFeed = { side -> withBaby(summary.baby.id) { onQuickFeed(side) } },
                        onGiveMedicine = vm::giveDose,
                        onDismissMedicine = vm::dismiss,
                        // Selects the baby on the way, so the screen that opens
                        // is this card's child rather than whichever was last
                        // active — the dose the card offers already knows.
                        onOpenMedicines = {
                            withBaby(summary.baby.id) { onNavigate(Routes.MEDICINES) }
                        },
                    )
                    state.spurts[summary.baby.id]?.let {
                        Spacer(Modifier.height(8.dp))
                        GrowthSpurtNote(it)
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }

            if (state.checkInPending) {
                Spacer(Modifier.height(16.dp))
                CheckInCard(
                    onCheckIn = { onNavigate(Routes.CHECKIN) },
                    onDismiss = vm::dismissCheckIn,
                )
            }
        }
    }
}

/**
 * What is happening right now, if anything is.
 *
 * A breastfeed in progress was already persisted and already shown on the
 * launcher widget, but never here — so the app's own front door was the one
 * place that didn't know a timer was running. A sleep with no end time is the
 * same story, and the one it needs is "woke up".
 */
@Composable
private fun LiveRow(
    sleeping: BabySummary?,
    onOpenNursing: (BreastSide) -> Unit,
    onWakeUp: (SleepEntity) -> Unit,
) {
    val context = LocalContext.current
    // The shared session, not a snapshot: the card has to go the moment the
    // feed is saved, wherever it was saved from, or it offers to reopen a
    // timer that is no longer running.
    val nursing = NursingSessionStore.sessions(context).collectAsState().value
    if (nursing == null && sleeping == null) return

    // Ticks only while something is actually running.
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(nursing != null, sleeping != null) {
        while (true) {
            delay(1_000)
            tick = System.currentTimeMillis()
        }
    }

    if (nursing != null) {
        LiveCard(
            icon = Icons.Filled.LocalDrink,
            title = stringResource(
                R.string.home_live_nursing,
                stringResource(
                    when {
                        // A feed on a break says so here too, or the dashboard
                        // shows a side that nobody is actually feeding on.
                        nursing.isPaused -> R.string.feeding_paused
                        nursing.currentSide == BreastSide.RIGHT -> R.string.side_right
                        else -> R.string.side_left
                    },
                ),
            ),
            // Time at the breast, not time since the feed began: the clock
            // stops while the break runs, exactly as it does on the timer.
            value = formatClock(nursing.nursedMs(tick)),
            actionLabel = stringResource(R.string.home_live_open),
            // The same path the quick feed button takes, which puts the feed
            // history under the timer; the session is already running, so
            // arriving there resumes it rather than starting a second one.
            onAction = { onOpenNursing(nursing.currentSide) },
        )
        Spacer(Modifier.height(12.dp))
    }

    val sleep = sleeping?.ongoingSleep
    if (sleeping != null && sleep != null) {
        LiveCard(
            icon = Icons.Filled.Bedtime,
            title = stringResource(R.string.home_live_asleep, sleeping.baby.name),
            value = formatClock(tick - sleep.startTime),
            actionLabel = stringResource(R.string.sleep_woke_up),
            onAction = { onWakeUp(sleep) },
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun LiveCard(
    icon: ImageVector,
    title: String,
    value: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null)
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                Text(value, style = MaterialTheme.typography.headlineSmall)
            }
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onPrimary,
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun NoBabyYet(onSetUp: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text(
                stringResource(R.string.home_welcome_no_profile),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                stringResource(R.string.home_setup_prompt),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onSetUp) {
                Text(stringResource(R.string.home_setup_profile))
            }
        }
    }
}

/**
 * Today's wellbeing check-in, waiting on the dashboard. It sits here rather than
 * opening at launch so a parent reaching for the app mid-feed never has to get
 * past it; "Not today" puts it away until tomorrow without saving anything.
 *
 * It moved below the babies rather than above them when the dashboard was
 * rebuilt — still waiting, still dismissible, just no longer between a parent
 * and the reason they opened the app (BDR-6, BDR-9).
 */
@Composable
private fun CheckInCard(onCheckIn: () -> Unit, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Favorite, contentDescription = null)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(
                        stringResource(R.string.home_checkin_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.home_checkin_body),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.home_checkin_dismiss))
                }
                Button(onClick = onCheckIn, modifier = Modifier.padding(start = 8.dp)) {
                    Text(stringResource(R.string.home_checkin_action))
                }
            }
        }
    }
}
