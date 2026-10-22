package com.gproust.sprout.ui.feeding

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gproust.sprout.R
import com.gproust.sprout.data.FoodHistory
import com.gproust.sprout.data.SproutRepository
import com.gproust.sprout.data.breastfeedJoinGap
import com.gproust.sprout.data.breastfeedJoinOffers
import com.gproust.sprout.data.allergyIdentities
import com.gproust.sprout.data.foodHistories
import com.gproust.sprout.data.joinedBreastfeed
import com.gproust.sprout.data.local.BreastSide
import com.gproust.sprout.data.local.FeedType
import com.gproust.sprout.data.local.FeedingEntity
import com.gproust.sprout.data.local.MealFood
import com.gproust.sprout.data.local.NursingSegment
import com.gproust.sprout.data.sync.newUid
import com.gproust.sprout.notifications.FeedingReminders
import com.gproust.sprout.ui.common.AddEntryFab
import com.gproust.sprout.ui.common.AddEntrySheet
import com.gproust.sprout.ui.common.ChoiceChips
import com.gproust.sprout.ui.common.ConfirmDeleteDialog
import com.gproust.sprout.ui.common.DatePickerField
import com.gproust.sprout.ui.common.DayHeader
import com.gproust.sprout.ui.common.EmptyHint
import com.gproust.sprout.ui.common.EntryCard
import com.gproust.sprout.ui.common.FieldLabel
import com.gproust.sprout.ui.common.NotesField
import com.gproust.sprout.ui.common.NumberField
import com.gproust.sprout.ui.common.SproutTopBar
import com.gproust.sprout.ui.common.TimePickerField
import com.gproust.sprout.ui.common.formatClock
import com.gproust.sprout.ui.common.formatDuration
import com.gproust.sprout.ui.common.formatTime
import com.gproust.sprout.ui.common.startOfDay
import com.gproust.sprout.ui.foods.MealFoodsEditor
import com.gproust.sprout.ui.foods.displayName
import com.gproust.sprout.ui.help.HelpButton
import com.gproust.sprout.ui.help.HelpPage
import com.gproust.sprout.ui.rememberSproutViewModelFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FeedingViewModel(
    private val repository: SproutRepository,
    private val context: Context,
) : ViewModel() {
    val feedings = repository.feedings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** The household's foods, offered back by the solid meal form (BDR-20). */
    val knownFoods = repository.householdMealsWithFoods
        .map(::foodHistories)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** The active baby's allergies, marked on the meal form's foods (BDR-21). */
    val allergies = repository.allergies
        .map(::allergyIdentities)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /**
     * Every running feed, straight from the store rather than a copy of it.
     * There can be more than one of these ViewModels alive at a time — the
     * widget can land on a second Feeding screen while the timer is open — and
     * a private copy each is how the same breastfeed used to be saved more than
     * once. More than one feed, too: with twins, each baby can have one running
     * (BDR-23), so a screen looks its own up with [runningFor].
     */
    val nursing: StateFlow<List<NursingSession>> = NursingSessionStore.sessions(context)

    /**
     * The selected baby's uid: the one whose history this screen lists, and so
     * the one whose feed its bar shows and starts.
     */
    val activeBabyUid: StateFlow<String?> =
        combine(repository.parentProfile, repository.babies) { parent, babies ->
            babies.firstOrNull { it.id == parent?.activeBabyId }?.uid
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Each baby's name by uid, so a timer can say whose feed it is timing. */
    val babyNames: StateFlow<Map<String, String>> = repository.babies
        .map { babies -> babies.associate { it.uid to it.name } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** The selected baby's uid, read now rather than whenever the flow next emits. */
    suspend fun activeBabyUidNow(): String? = repository.activeBabyUidNow()

    /**
     * The widget and the notification draw the running timers, so they follow
     * every change to them (BDR-24).
     */
    private fun refreshWidget() {
        viewModelScope.launch { NursingActions.refreshOutside(context, repository) }
    }

    fun add(entity: FeedingEntity) = viewModelScope.launch {
        repository.addFeeding(entity)
        // A feed was logged → push the "too long since a feed" reminder out.
        FeedingReminders.rescheduleActiveBaby(context, repository)
    }

    fun delete(entity: FeedingEntity) = viewModelScope.launch {
        repository.deleteFeeding(entity)
        FeedingReminders.rescheduleActiveBaby(context, repository)
    }

    /**
     * Join two breastfeeds saved apart into the one feed they were (BDR-19).
     * If the later one was the last feed, the last feed now began when the
     * earlier one did — so the reminder moves with it.
     */
    fun join(earlier: FeedingEntity, later: FeedingEntity) = viewModelScope.launch {
        if (repository.joinBreastfeeds(earlier, later)) {
            FeedingReminders.rescheduleActiveBaby(context, repository)
        }
    }

    /**
     * Begin timing a breastfeed for [babyUid] on [side] (left or right). A feed
     * already running for that baby is left alone: two ways into the timer
     * opening at once is not a reason to time one baby twice. Another baby's
     * feed is no obstacle — twins can feed together (BDR-23).
     */
    fun startNursing(side: BreastSide, babyUid: String?) {
        NursingActions.start(context, side, babyUid)
        refreshWidget()
    }

    /** Bank [babyUid]'s current breast as a completed segment and switch to the other. */
    fun switchBreast(babyUid: String?) {
        NursingActions.apply(context, babyUid, NursingControl.SWITCH)
        refreshWidget()
    }

    /**
     * Bank the breast [babyUid] is nursing at and start a break — the burp
     * between the sides, the nappy halfway through. The session stays open and
     * keeps its start time; only the clock at the breast stops. Another baby's
     * feed carries on.
     */
    fun pauseNursing(babyUid: String?) {
        NursingActions.apply(context, babyUid, NursingControl.PAUSE)
        refreshWidget()
    }

    /** Come back from a break, on whichever breast [babyUid]'s feed carries on with. */
    fun resumeNursing(babyUid: String?, side: BreastSide) {
        val control = if (side == BreastSide.RIGHT) NursingControl.RESUME_RIGHT else NursingControl.RESUME_LEFT
        NursingActions.apply(context, babyUid, control)
        refreshWidget()
    }

    /**
     * Finish [babyUid]'s session and persist it as a feeding.
     *
     * The session is *taken* from the store before anything is saved, so that
     * of everything that might still be showing this timer — a second copy of
     * the nursing screen left on the back stack, a second tap on Save — only
     * one is ever handed something to log.
     */
    fun stopNursing(babyUid: String?, notes: String = "") {
        val s = NursingActions.take(context, babyUid) ?: return
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            // To the baby the session was started for, not to whoever is
            // selected now. With twins, those two are not always the same.
            NursingActions.save(context, repository, s, notes, now)
        }
        refreshWidget()
    }

    /** Discard [babyUid]'s running session without saving it. */
    fun cancelNursing(babyUid: String?) {
        NursingActions.take(context, babyUid)
        refreshWidget()
    }
}

@Composable
fun FeedingScreen(
    onBack: () -> Unit = {},
    /** Opens the live timer on a breast, for the baby named by uid. */
    onOpenNursing: (BreastSide, String?) -> Unit = { _, _ -> },
    vm: FeedingViewModel = viewModel(factory = rememberSproutViewModelFactory()),
) {
    val feedings by vm.feedings.collectAsState()
    val knownFoods by vm.knownFoods.collectAsState()
    val allergies by vm.allergies.collectAsState()
    val sessions by vm.nursing.collectAsState()
    val activeBabyUid by vm.activeBabyUid.collectAsState()
    // This is the selected baby's history, so its bar is the selected baby's
    // feed. A twin's feed running at the same time is on the dashboard, with
    // the twin's name on it (BDR-23).
    val nursing = sessions.runningFor(activeBabyUid)
    val context = LocalContext.current
    var editing by remember { mutableStateOf<FeedingEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    // The feed awaiting a "yes, delete it" — deletes are permanent.
    var deleting by remember { mutableStateOf<FeedingEntity?>(null) }
    // The two feeds awaiting a "yes, join them" — earlier first. Joining can't
    // be taken back either, so it asks the same way a delete does.
    var joining by remember { mutableStateOf<Pair<FeedingEntity, FeedingEntity>?>(null) }

    editing?.let { entry ->
        EditFeedingDialog(
            entry = entry,
            knownFoods = knownFoods,
            allergies = allergies,
            onDismiss = { editing = null },
            onSave = { vm.add(it); editing = null },
            onDelete = { deleting = entry },
        )
    }

    // Sits on top of the editor when the delete came from there, so backing out
    // of the confirmation leaves you where you were.
    deleting?.let { entry ->
        ConfirmDeleteDialog(
            onConfirm = { vm.delete(entry); deleting = null; editing = null },
            onDismiss = { deleting = null },
        )
    }

    joining?.let { (earlier, later) ->
        ConfirmJoinDialog(
            earlier = earlier,
            later = later,
            onConfirm = { vm.join(earlier, later); joining = null },
            onDismiss = { joining = null },
        )
    }

    if (adding) {
        AddEntrySheet(
            title = stringResource(R.string.feeding_log_title),
            onDismiss = { adding = false },
        ) {
            FeedingForm(
                initial = null,
                submitLabel = stringResource(R.string.feeding_add),
                onSubmit = { vm.add(it); adding = false },
                knownFoods = knownFoods,
                allergies = allergies,
            )
        }
    }

    // The history is the screen: newest feed on top, grouped by day. Logging
    // happens in a bottom sheet behind the "+" button, so the last feed is
    // always visible at a glance.
    val byDay = remember(feedings) { feedings.groupBy { startOfDay(it.startTime) } }
    // Which cards can be joined to the feed below them (BDR-19): the later of
    // two neighbouring breastfeeds a short break apart, keyed by its uid.
    val joinable = remember(feedings) { breastfeedJoinOffers(feedings) }

    Scaffold(
        topBar = {
            SproutTopBar(
                stringResource(R.string.screen_feeding),
                onBack = onBack,
                actions = { HelpButton(HelpPage.FEEDING) },
            )
        },
        floatingActionButton = {
            AddEntryFab(stringResource(R.string.feeding_log_title)) { adding = true }
        },
        bottomBar = {
            NursingBar(
                session = nursing,
                onStart = { side -> onOpenNursing(side, activeBabyUid) },
                onResume = { nursing?.let { onOpenNursing(it.currentSide, it.babyUid) } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("feedingList"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (feedings.isEmpty()) {
                item { EmptyHint(stringResource(R.string.feeding_empty)) }
            }
            byDay.forEach { (day, entries) ->
                item(key = "day-$day") { DayHeader(day) }
                items(entries, key = { it.id }) { entry ->
                    val joinsTo = joinable[entry.uid]
                    EntryCard(
                        title = feedingTitle(context, entry),
                        subtitle = feedingSubtitle(context, entry),
                        meta = formatTime(entry.startTime),
                        icon = Icons.Filled.LocalDrink,
                        onDelete = { deleting = entry },
                        onClick = { editing = entry },
                        details = if (entry.type == FeedType.BREAST && entry.segments.isNotEmpty()) {
                            { FeedingSegmentDetails(entry.segments) }
                        } else {
                            null
                        },
                        // Offered on the later feed only, beside Details — the
                        // row a joinable card already has, so nothing grows.
                        action = if (joinsTo != null) {
                            { JoinWithPreviousButton { joining = joinsTo to entry } }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

/**
 * The breastfeeding start/stop controls, pinned to the bottom of the feeding
 * screen. Idle: a Left and a Right button (positioned to match the breast).
 * Running: a single button that re-opens the live timer, showing the time at
 * the breast so a session left in the background — or left on a break — is
 * never forgotten.
 */
@Composable
private fun NursingBar(
    session: NursingSession?,
    onStart: (BreastSide) -> Unit,
    onResume: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                stringResource(R.string.feeding_breastfeeding),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(8.dp))
            if (session == null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { onStart(BreastSide.LEFT) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.feeding_start_left))
                    }
                    Button(onClick = { onStart(BreastSide.RIGHT) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.feeding_start_right))
                    }
                }
            } else {
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(session.sessionStart) {
                    while (true) {
                        now = System.currentTimeMillis()
                        delay(1000)
                    }
                }
                // The time at the breast, which is not the time since the feed
                // began: a session on a break shows a clock that has stopped.
                val totalMs = session.nursedMs(now)
                Button(onClick = onResume, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (session.isPaused) {
                            stringResource(R.string.feeding_paused_for, formatClock(totalMs))
                        } else {
                            stringResource(R.string.feeding_resume, formatClock(totalMs))
                        },
                    )
                }
            }
        }
    }
}

/**
 * Full-screen live breastfeeding timer, for one baby. Started from the feeding
 * screen's Left/Right buttons or a baby's card; lets you switch sides (banking
 * each segment's duration), pause for a burp and come back on either breast,
 * and stop to save, or cancel to discard. Leaving via Back keeps the session
 * running so it can be resumed.
 *
 * [babyUid] is the baby whose feed this is. With twins feeding together each
 * has a timer of its own (BDR-23), and a screen that looked up "the" feed would
 * be showing whichever came first. Null asks for the selected baby, resolved
 * once on arrival and then held.
 */
@Composable
fun NursingScreen(
    side: BreastSide,
    babyUid: String? = null,
    onDone: () -> Unit = {},
    vm: FeedingViewModel = viewModel(factory = rememberSproutViewModelFactory()),
) {
    val sessions by vm.nursing.collectAsState()
    val names by vm.babyNames.collectAsState()

    // Whether this copy of the screen has already made its start-or-resume
    // decision. Saved, so it survives the screen sitting on the back stack
    // while something else is on top of it — a widget tap landing on Feeding,
    // say. Coming back to a screen that has been here before must never start a
    // second feed: the timer it was showing may since have been saved from
    // somewhere else, and starting again is how a tap that looked like
    // "resume" turned into another breastfeed.
    var arrived by rememberSaveable { mutableStateOf(false) }
    // Whose feed this is, once known. Saved with [arrived], so a screen coming
    // back off the stack is still the same baby's timer.
    var baby by rememberSaveable { mutableStateOf(babyUid) }
    var resolved by rememberSaveable { mutableStateOf(babyUid != null) }

    LaunchedEffect(Unit) {
        when {
            // First time here: start a session unless that baby already has one
            // running (resumed from the bar, or restored after process death).
            !arrived -> {
                if (!resolved) {
                    baby = vm.activeBabyUidNow()
                    resolved = true
                }
                arrived = true
                if (vm.nursing.value.runningFor(baby) == null) vm.startNursing(side, baby)
            }
            // Back on a screen whose feed has already been saved — there is
            // nothing left to show, so step off it rather than sit on a
            // spinner or start something new.
            vm.nursing.value.runningFor(baby) == null -> onDone()
        }
    }

    val session = if (resolved) sessions.runningFor(baby) else null
    // The baby's name under the clock, once there is more than one feed it
    // could be: with one feed running there is nothing to tell apart.
    val name = baby?.let { names[it] }?.takeIf { sessions.size > 1 }

    Scaffold(
        topBar = {
            SproutTopBar(
                stringResource(R.string.feeding_breastfeeding),
                onBack = onDone,
                actions = { HelpButton(HelpPage.FEEDING) },
            )
        },
    ) { padding ->
        val s = session
        if (s == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            NursingRunning(
                session = s,
                babyName = name,
                onSwitch = { vm.switchBreast(s.babyUid) },
                onPause = { vm.pauseNursing(s.babyUid) },
                onResume = { side -> vm.resumeNursing(s.babyUid, side) },
                onStop = { notes -> vm.stopNursing(s.babyUid, notes); onDone() },
                onCancel = { vm.cancelNursing(s.babyUid); onDone() },
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
}

@Composable
private fun NursingRunning(
    session: NursingSession,
    babyName: String?,
    onSwitch: () -> Unit,
    onPause: () -> Unit,
    onResume: (BreastSide) -> Unit,
    onStop: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var notes by remember { mutableStateOf("") }

    // Tick once a second so the live timer keeps moving while this is shown.
    LaunchedEffect(session.sessionStart) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    val paused = session.isPaused
    val onLeft = session.currentSide == BreastSide.LEFT
    val leftMs = session.nursedMs(BreastSide.LEFT, now)
    val rightMs = session.nursedMs(BreastSide.RIGHT, now)
    val totalMs = leftMs + rightMs
    // Every stretch so far, including the one in progress (ends at `now`). On a
    // break there is none in progress — it was banked when the break began.
    val liveSegments = session.segmentsAt(now)

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        if (babyName != null) {
            Text(
                babyName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            formatClock(totalMs),
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            when {
                paused -> stringResource(R.string.feeding_paused_for, formatClock(session.pausedMs(now)))
                onLeft -> stringResource(R.string.feeding_on_left)
                else -> stringResource(R.string.feeding_on_right)
            },
            style = MaterialTheme.typography.bodyLarge,
            color = if (paused) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.primary,
        )

        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            SideTotal(
                label = stringResource(R.string.side_left),
                value = formatClock(leftMs),
                active = onLeft && !paused,
            )
            SideTotal(
                label = stringResource(R.string.side_right),
                value = formatClock(rightMs),
                active = !onLeft && !paused,
            )
        }

        // Each back-and-forth stretch with its own time range and duration.
        Spacer(Modifier.height(20.dp))
        FieldLabel(stringResource(R.string.feeding_segments))
        liveSegments.forEachIndexed { i, seg ->
            // The gap a break left behind, shown where it happened rather than
            // as a total at the bottom: what a parent wants back is *when* the
            // last one was, to know whether this one has gone on too long.
            val gap = if (i == 0) 0L else seg.startTime - liveSegments[i - 1].endTime
            if (gap > 0L) {
                BreakRow(
                    range = rangeLabel(liveSegments[i - 1].endTime, seg.startTime),
                    duration = formatDuration(context, gap),
                    active = false,
                )
            }
            SegmentRow(
                side = seg.side.label(context),
                range = segmentRange(seg),
                duration = formatDuration(context, (seg.endTime - seg.startTime).coerceAtLeast(0L)),
                active = !paused && i == liveSegments.lastIndex,
            )
        }
        session.pausedAt?.let { start ->
            BreakRow(
                range = rangeLabel(start, now),
                duration = formatDuration(context, session.pausedMs(now)),
                active = true,
            )
        }

        Spacer(Modifier.height(24.dp))
        if (paused) {
            // Resuming names the breast rather than saying "resume", because
            // the whole point of the break is that the next stretch is often
            // the other side — and after a burp nobody remembers which.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(
                    onClick = { onResume(BreastSide.LEFT) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.feeding_resume_left))
                }
                FilledTonalButton(
                    onClick = { onResume(BreastSide.RIGHT) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.feeding_resume_right))
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onPause, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.feeding_pause))
                }
                FilledTonalButton(onClick = onSwitch, modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(
                            if (onLeft) R.string.feeding_switch_to_right
                            else R.string.feeding_switch_to_left,
                        ),
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        NotesField(value = notes, onChange = { notes = it })

        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Cancel is a small round red cross; saving is the prominent action.
            OutlinedIconButton(
                onClick = onCancel,
                colors = IconButtonDefaults.outlinedIconButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel))
            }
            Button(onClick = { onStop(notes) }, modifier = Modifier.weight(1f)) {
                Icon(
                    Icons.Filled.Save,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.feeding_stop_save))
            }
        }
    }
}

@Composable
private fun SegmentRow(side: String, range: String, duration: String, active: Boolean) {
    val color = if (active) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            side,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = color,
            modifier = Modifier.weight(1f),
        )
        Text(
            range,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            duration,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = color,
        )
    }
}

/**
 * A break in the middle of a session — the burp between the sides, the nappy.
 * The same three columns as a stretch so the session reads as one timeline,
 * and named rather than left as a hole in it, but quieter: it is the part of
 * the feed where nothing was being drunk.
 */
@Composable
private fun BreakRow(range: String, duration: String, active: Boolean) {
    val weight = if (active) FontWeight.Bold else FontWeight.Normal
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.feeding_paused),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = weight,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            range,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            duration,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = weight,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SideTotal(label: String, value: String, active: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/**
 * The quiet way into a join: small, in the muted colour of the rest of the
 * card, and never anything that suggests the two feeds *should* be one. Only
 * the parent who was there knows that (BDR-19).
 */
@Composable
private fun JoinWithPreviousButton(onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.feeding_join), style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * "Join these two feeds?" — names both by their start times and the gap
 * between them, and shows the joined feed's timeline before anything is
 * written, so what the parent agrees to is exactly what they will get.
 */
@Composable
private fun ConfirmJoinDialog(
    earlier: FeedingEntity,
    later: FeedingEntity,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val gap = breastfeedJoinGap(earlier, later) ?: 0L
    val preview = remember(earlier, later) { joinedBreastfeed(earlier, later) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.feeding_join_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (gap > 0L) {
                        stringResource(
                            R.string.feeding_join_body,
                            formatTime(earlier.startTime),
                            formatTime(later.startTime),
                            formatDuration(context, gap),
                        )
                    } else {
                        stringResource(
                            R.string.feeding_join_body_no_gap,
                            formatTime(earlier.startTime),
                            formatTime(later.startTime),
                        )
                    },
                )
                FeedingSegmentDetails(preview.segments)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.feeding_join_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * Edit (or delete) an existing feed in a dialog, reusing [FeedingForm]. The
 * First foods page opens the same dialog on a meal, held to solids, so a meal
 * corrected from either screen is corrected the same way (BDR-20).
 */
@Composable
internal fun EditFeedingDialog(
    entry: FeedingEntity,
    knownFoods: List<FoodHistory>,
    onDismiss: () -> Unit,
    onSave: (FeedingEntity) -> Unit,
    onDelete: () -> Unit,
    title: String = stringResource(R.string.feeding_edit_title),
    fixedType: FeedType? = null,
    allergies: Set<String> = emptySet(),
) {
    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                FeedingForm(
                    initial = entry,
                    submitLabel = stringResource(R.string.action_save),
                    onSubmit = onSave,
                    onDelete = onDelete,
                    knownFoods = knownFoods,
                    fixedType = fixedType,
                    allergies = allergies,
                )
            }
        }
    }
}

/**
 * The shared feed editor used both for manual logging (no [initial]) and for
 * editing an existing entry. Holds its own field state, re-initialised whenever
 * [initial] changes. When adding, the fields reset after submitting.
 *
 * [fixedType] holds the form to one kind of feed and hides the choice — the
 * First foods page logs solid meals and nothing else. [knownFoods] is what a
 * solid meal's food list offers back (BDR-20), and [allergies] the foods it
 * marks as ones the baby is noted as allergic to (BDR-21).
 */
@Composable
internal fun FeedingForm(
    initial: FeedingEntity?,
    submitLabel: String,
    onSubmit: (FeedingEntity) -> Unit,
    onDelete: (() -> Unit)? = null,
    knownFoods: List<FoodHistory> = emptyList(),
    fixedType: FeedType? = null,
    allergies: Set<String> = emptySet(),
) {
    val context = LocalContext.current
    val now = System.currentTimeMillis()

    var type by remember(initial) { mutableStateOf(initial?.type ?: fixedType ?: FeedType.BREAST) }
    var amount by remember(initial) { mutableStateOf(initial?.amountMl?.toString() ?: "") }
    // Solids are weighed rather than measured, so they get their own field —
    // sharing one with the bottle's millilitres would carry a number across
    // when the type is switched and silently change what it means.
    var grams by remember(initial) { mutableStateOf(initial?.amountGrams?.toString() ?: "") }
    // What a solid meal was made of. Kept while the type is switched about, and
    // only dropped on saving something that is not a solid meal.
    var foods by remember(initial) { mutableStateOf<List<MealFood>>(initial?.foods.orEmpty()) }
    var start by remember(initial) { mutableLongStateOf(initial?.startTime ?: now) }
    // Whether each breast's length is entered as an end time or a duration.
    var byEnd by remember(initial) { mutableStateOf(initial?.endTime != null) }
    // One row per breast stretch (left/right + length); "both" isn't an option —
    // you list the actual sides instead.
    var segs by remember(initial) { mutableStateOf(initialSegInputs(initial, now)) }
    var notes by remember(initial) { mutableStateOf(initial?.notes ?: "") }

    fun reset() {
        type = fixedType ?: FeedType.BREAST
        amount = ""
        grams = ""
        foods = emptyList()
        start = System.currentTimeMillis()
        byEnd = false
        segs = listOf(SegInput(BreastSide.LEFT, "", System.currentTimeMillis()))
        notes = ""
    }

    fun build(): FeedingEntity {
        val breast = type == FeedType.BREAST
        // Chain the rows into timed segments: each starts where the previous
        // ended (the first at [start]); its length comes from an end time or a
        // duration. Empty/invalid rows are skipped.
        val built = mutableListOf<NursingSegment>()
        if (breast) {
            var cursor = start
            for (seg in segs) {
                // A break the session recorded stays a break: editing the
                // minutes at the breast must not quietly swallow the burp.
                cursor += seg.gapBeforeMs
                val segEnd = if (byEnd) seg.end else seg.durationMin.toLongOrNull()?.let { cursor + it * 60_000L }
                if (segEnd != null && segEnd > cursor) {
                    built += NursingSegment(seg.side, cursor, segEnd)
                    cursor = segEnd
                }
            }
        }
        val left = built.filter { it.side == BreastSide.LEFT }.sumOf { it.endTime - it.startTime }
        val right = built.filter { it.side == BreastSide.RIGHT }.sumOf { it.endTime - it.startTime }
        val breastSide = when {
            left > 0 && right > 0 -> BreastSide.BOTH
            right > 0 -> BreastSide.RIGHT
            left > 0 -> BreastSide.LEFT
            else -> segs.firstOrNull()?.side ?: BreastSide.LEFT
        }
        return FeedingEntity(
            id = initial?.id ?: 0L,
            // The row being edited keeps its name. A new uid on every edit made
            // the other phone see a second feed beside the first rather than a
            // correction of it.
            uid = initial?.uid ?: newUid(),
            type = type,
            side = if (breast) breastSide else null,
            amountMl = if (type == FeedType.BOTTLE) amount.toIntOrNull() else null,
            amountGrams = if (type == FeedType.SOLID) grams.toIntOrNull() else null,
            startTime = start,
            endTime = if (breast) built.lastOrNull()?.endTime else null,
            leftDurationMs = left.takeIf { breast && it > 0 },
            rightDurationMs = right.takeIf { breast && it > 0 },
            segments = built,
            foods = if (type == FeedType.SOLID) foods else emptyList(),
            notes = notes.ifBlank { null },
        )
    }

    if (fixedType == null) {
        FieldLabel(stringResource(R.string.field_type))
        ChoiceChips(
            options = FeedType.entries,
            selected = type,
            onSelect = { type = it },
            labelOf = { it.label(context) },
        )
    }

    if (type == FeedType.BOTTLE) {
        FieldLabel(stringResource(R.string.field_amount))
        NumberField(
            label = stringResource(R.string.field_amount),
            value = amount,
            onChange = { amount = it },
            suffix = stringResource(R.string.unit_ml),
        )
    }

    // What was in it first, then how much. The weight is optional, and stays
    // optional: a purée is often given without a scale in sight, and a solid
    // feed with no number is still worth logging. It is the meal's — what gets
    // weighed is the bowl, not each food in it (BDR-20).
    if (type == FeedType.SOLID) {
        MealFoodsEditor(foods = foods, onChange = { foods = it }, known = knownFoods, allergies = allergies)
        FieldLabel(stringResource(R.string.field_amount_solid))
        NumberField(
            label = stringResource(R.string.field_amount_solid),
            value = grams,
            onChange = { grams = it },
            suffix = stringResource(R.string.unit_g),
        )
    }

    FieldLabel(stringResource(R.string.field_time))
    // A feed often gets logged after the fact, sometimes not until the next day,
    // so the date is pickable and starts on today. Future dates are blocked — a
    // feed can't have happened yet.
    DatePickerField(
        label = stringResource(R.string.picker_on),
        millis = start,
        allowFuture = false,
        onChange = { picked ->
            // Carry the segment end times along by the same offset, so a session
            // entered as end times keeps its shape (even across midnight).
            val shift = picked - start
            start = picked
            segs = segs.map { it.copy(end = it.end + shift) }
        },
    )
    // Breast feeds pair the start with per-side end times, so "From"/"To" reads
    // better than "At" (in French both picker_at and picker_to are "À").
    val startLabel = if (type == FeedType.BREAST) R.string.picker_from else R.string.picker_at
    TimePickerField(label = stringResource(startLabel), millis = start, onChange = { start = it })

    if (type == FeedType.BREAST) {
        val durationLabel = stringResource(R.string.field_duration)
        val endLabel = stringResource(R.string.feeding_length_end)
        FieldLabel(stringResource(R.string.feeding_length))
        ChoiceChips(
            options = listOf(false, true),
            selected = byEnd,
            onSelect = { byEnd = it },
            labelOf = { if (it) endLabel else durationLabel },
        )

        // One row per side; each its own duration or end time, in order.
        segs.forEachIndexed { i, seg ->
            SegmentInputRow(
                seg = seg,
                byEnd = byEnd,
                canRemove = segs.size > 1,
                onSide = { s -> segs = segs.mapIndexed { j, x -> if (j == i) x.copy(side = s) else x } },
                onDuration = { v -> segs = segs.mapIndexed { j, x -> if (j == i) x.copy(durationMin = v) else x } },
                onEnd = { v -> segs = segs.mapIndexed { j, x -> if (j == i) x.copy(end = v) else x } },
                onRemove = { segs = segs.filterIndexed { j, _ -> j != i } },
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                val last = segs.lastOrNull()
                val nextSide = if (last?.side == BreastSide.LEFT) BreastSide.RIGHT else BreastSide.LEFT
                segs = segs + SegInput(nextSide, "", last?.end ?: start)
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.feeding_add_side))
        }
    }

    Spacer(Modifier.height(8.dp))
    NotesField(value = notes, onChange = { notes = it })

    Spacer(Modifier.height(12.dp))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (onDelete != null) Arrangement.SpaceBetween else Arrangement.End,
    ) {
        if (onDelete != null) {
            TextButton(onClick = onDelete) {
                Text(
                    stringResource(R.string.action_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Button(onClick = {
            onSubmit(build())
            if (initial == null) reset()
        }) {
            Text(submitLabel)
        }
    }
}

/** A single editable breast stretch in the manual log form. */
private data class SegInput(
    val side: BreastSide,
    val durationMin: String,
    val end: Long,
    /**
     * The break between the previous stretch and this one, carried through an
     * edit untouched. There is nothing in the form to change it — a break is
     * something that happened, not a figure to type — but dropping it would
     * turn every edited session into one that ran straight through.
     */
    val gapBeforeMs: Long = 0L,
)

/** Pre-fills the segment rows when editing (or one empty Left row when adding). */
private fun initialSegInputs(initial: FeedingEntity?, now: Long): List<SegInput> {
    if (initial == null || initial.type != FeedType.BREAST) {
        return listOf(SegInput(BreastSide.LEFT, "", now))
    }
    if (initial.segments.isNotEmpty()) {
        var prevEnd = initial.startTime
        return initial.segments.map { seg ->
            val row = SegInput(
                side = seg.side,
                durationMin = ((seg.endTime - seg.startTime) / 60_000L).toString(),
                end = seg.endTime,
                gapBeforeMs = (seg.startTime - prevEnd).coerceAtLeast(0L),
            )
            prevEnd = seg.endTime
            row
        }
    }
    // Older entry without per-segment timing: rebuild rows from the per-side
    // totals, or from the overall length, anchored at the start time.
    val rows = mutableListOf<SegInput>()
    var cursor = initial.startTime
    initial.leftDurationMs?.let { cursor += it; rows += SegInput(BreastSide.LEFT, (it / 60_000L).toString(), cursor) }
    initial.rightDurationMs?.let { cursor += it; rows += SegInput(BreastSide.RIGHT, (it / 60_000L).toString(), cursor) }
    if (rows.isEmpty()) {
        val dur = initial.endTime?.let { it - initial.startTime }?.takeIf { it > 0 }
        val side = initial.side?.takeIf { it != BreastSide.BOTH } ?: BreastSide.LEFT
        rows += SegInput(side, dur?.let { (it / 60_000L).toString() } ?: "", initial.endTime ?: now)
    }
    return rows
}

@Composable
private fun SegmentInputRow(
    seg: SegInput,
    byEnd: Boolean,
    canRemove: Boolean,
    onSide: (BreastSide) -> Unit,
    onDuration: (String) -> Unit,
    onEnd: (Long) -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            ChoiceChips(
                options = listOf(BreastSide.LEFT, BreastSide.RIGHT),
                selected = seg.side,
                onSelect = onSide,
                labelOf = { it.label(context) },
            )
        }
        if (canRemove) {
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
    if (byEnd) {
        TimePickerField(label = stringResource(R.string.picker_to), millis = seg.end, onChange = onEnd)
    } else {
        NumberField(
            label = stringResource(R.string.feeding_duration_minutes),
            value = seg.durationMin,
            onChange = onDuration,
            suffix = stringResource(R.string.unit_min),
        )
    }
}

private fun feedingTitle(context: Context, entry: FeedingEntity): String {
    val sep = context.getString(R.string.feeding_detail_separator)
    return when (entry.type) {
        FeedType.BREAST -> context.getString(R.string.feed_type_breast) +
            (entry.side?.let { sep + it.label(context) } ?: "")
        FeedType.BOTTLE -> context.getString(R.string.feed_type_bottle) +
            (entry.amountMl?.let { sep + context.getString(R.string.feeding_amount_ml, it) } ?: "")
        FeedType.SOLID -> context.getString(R.string.feed_type_solid) +
            (entry.amountGrams?.let { sep + context.getString(R.string.feeding_amount_g, it) } ?: "")
    }
}

/** "10:00–10:08" — a clock range, for a stretch at the breast or a break. */
private fun rangeLabel(start: Long, end: Long): String =
    "${formatTime(start)}–${formatTime(end)}"

private fun segmentRange(seg: NursingSegment): String = rangeLabel(seg.startTime, seg.endTime)

/** "Left 8m · 10:00–10:08" for one nursing segment. */
private fun segmentLine(context: Context, seg: NursingSegment): String = context.getString(
    R.string.feeding_segment_line,
    seg.side.label(context),
    formatDuration(context, (seg.endTime - seg.startTime).coerceAtLeast(0L)),
    segmentRange(seg),
)

/** "Paused 4m · 10:08–10:12" for a break between two stretches. */
private fun breakLine(context: Context, start: Long, end: Long): String = context.getString(
    R.string.feeding_segment_line,
    context.getString(R.string.feeding_paused),
    formatDuration(context, (end - start).coerceAtLeast(0L)),
    rangeLabel(start, end),
)

/**
 * The light history preview: per-side totals and the overall total for a
 * breastfeed (the per-stretch detail lives behind the card's "Details" button).
 */
private fun feedingSubtitle(context: Context, entry: FeedingEntity): String {
    val sep = context.getString(R.string.feeding_detail_separator)
    val parts = mutableListOf<String>()
    if (entry.type == FeedType.BREAST) {
        val left = entry.leftDurationMs
        val right = entry.rightDurationMs
        if (left != null || right != null) {
            left?.let {
                parts += context.getString(
                    R.string.feeding_side_time,
                    context.getString(R.string.side_left),
                    formatDuration(context, it),
                )
            }
            right?.let {
                parts += context.getString(
                    R.string.feeding_side_time,
                    context.getString(R.string.side_right),
                    formatDuration(context, it),
                )
            }
            if (left != null && right != null) {
                parts += context.getString(R.string.feeding_total, formatDuration(context, left + right))
            }
            // Only when there was one: a feed that ran straight through says
            // nothing about breaks, the way it always has.
            breastfeedPausedMillis(entry.segments).takeIf { it > 0L }?.let {
                parts += context.getString(R.string.feeding_paused_total, formatDuration(context, it))
            }
        } else {
            // A quick breastfeed with only an overall length.
            entry.endTime?.takeIf { it > entry.startTime }?.let {
                parts += formatDuration(context, it - entry.startTime)
            }
        }
    }
    // What a solid meal was made of, in the parent's own words (BDR-20).
    if (entry.type == FeedType.SOLID && entry.foods.isNotEmpty()) {
        parts += entry.foods.joinToString(", ") { it.displayName(context) }
    }
    entry.notes?.takeIf { it.isNotBlank() }?.let { parts += it }
    return parts.joinToString(sep)
}

/** The per-stretch breakdown shown when a breastfeed card is expanded. */
@Composable
private fun FeedingSegmentDetails(segments: List<NursingSegment>) {
    val context = LocalContext.current
    Column {
        segments.forEachIndexed { i, seg ->
            // The break is the gap the stretches leave; nothing records one of
            // its own, so a feed logged before breaks existed shows none.
            val gap = if (i == 0) 0L else seg.startTime - segments[i - 1].endTime
            if (gap > 0L) {
                Text(
                    breakLine(context, segments[i - 1].endTime, seg.startTime),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                segmentLine(context, seg),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun BreastSide.label(context: Context): String = context.getString(
    when (this) {
        BreastSide.LEFT -> R.string.side_left
        BreastSide.RIGHT -> R.string.side_right
        BreastSide.BOTH -> R.string.side_both
    },
)

private fun FeedType.label(context: Context): String = context.getString(
    when (this) {
        FeedType.BREAST -> R.string.feed_type_breast
        FeedType.BOTTLE -> R.string.feed_type_bottle
        FeedType.SOLID -> R.string.feed_type_solid
    },
)
