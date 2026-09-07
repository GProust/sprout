package com.gproust.sprout.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.gproust.sprout.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gproust.sprout.data.SproutRepository
import com.gproust.sprout.data.local.BabyEntity
import com.gproust.sprout.data.local.BreastSide
import com.gproust.sprout.ui.baby.BabyScreen
import com.gproust.sprout.ui.checkin.CheckInRoute
import com.gproust.sprout.ui.diaper.DiaperScreen
import com.gproust.sprout.ui.feeding.FeedingScreen
import com.gproust.sprout.ui.feeding.FeedingViewModel
import com.gproust.sprout.ui.feeding.NursingScreen
import com.gproust.sprout.ui.growth.GrowthScreen
import com.gproust.sprout.ui.health.HealthScreen
import com.gproust.sprout.ui.home.HomeScreen
import com.gproust.sprout.ui.onboarding.OnboardingScreen
import com.gproust.sprout.ui.profile.ProfileScreen
import com.gproust.sprout.ui.pumping.PumpingScreen
import com.gproust.sprout.ui.rememberSproutViewModelFactory
import com.gproust.sprout.ui.settings.SettingsScreen
import com.gproust.sprout.ui.sync.SyncScreen
import com.gproust.sprout.ui.sleep.SleepScreen
import com.gproust.sprout.ui.stats.StatsScreen
import com.gproust.sprout.ui.treatments.TreatmentsScreen
import com.gproust.sprout.ui.you.YouScreen
import com.gproust.sprout.ui.startup.Startup
import com.gproust.sprout.ui.startup.StartupViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

object Routes {
    const val HOME = "home"
    const val BABY = "baby"
    const val YOU = "you"
    const val FEEDING = "feeding"
    const val FEEDING_NURSING = "feeding/nursing/{side}"
    const val PUMPING = "pumping"
    const val SLEEP = "sleep"
    const val DIAPER = "diaper"
    const val GROWTH = "growth"
    const val HEALTH = "health"
    const val STATS = "stats"
    const val CHECKIN = "checkin"
    const val PROFILE = "profile"
    const val SETTINGS = "settings"
    const val TREATMENTS = "treatments"
    const val SYNC = "settings/sync"

    /** The live nursing screen for one breast, timer already running. */
    fun nursing(side: BreastSide) = "feeding/nursing/${side.name}"
}

private data class BottomDestination(
    val route: String,
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
)

/**
 * The bottom bar is four *kinds of place*, not four of the things there are to
 * log (BDR-9, BDR-10): the household, the child you picked, the numbers, and
 * you. The logs themselves are all reached from the dashboard's grid, which is
 * the only arrangement with room for every one of them — the old bar had five
 * baby-scoped screens and four seats, and treatments never got one.
 *
 * [Routes.BABY] stays in this list even when it isn't drawn in the bar, so that
 * opening a baby from a card always behaves as a sibling tab rather than
 * sometimes being pushed on top of Home.
 */
private val bottomDestinations = listOf(
    BottomDestination(Routes.HOME, R.string.nav_home, Icons.Filled.Home),
    BottomDestination(Routes.BABY, R.string.nav_baby, Icons.Filled.Person),
    BottomDestination(Routes.STATS, R.string.nav_trends, Icons.Filled.BarChart),
    BottomDestination(Routes.YOU, R.string.nav_you, Icons.Filled.Favorite),
)

/**
 * Every route this app knows how to open.
 *
 * The launching intent's route is attacker-controllable in principle — it is an
 * extra on an exported activity — so it is matched against this set before it
 * reaches the nav controller, which would throw on anything unrecognised.
 */
private val knownRoutes = setOf(
    Routes.HOME, Routes.BABY, Routes.YOU, Routes.STATS,
    Routes.FEEDING, Routes.PUMPING, Routes.SLEEP, Routes.DIAPER,
    Routes.GROWTH, Routes.HEALTH, Routes.TREATMENTS, Routes.CHECKIN,
    Routes.PROFILE, Routes.SETTINGS, Routes.SYNC,
)

/**
 * Navigates to a top-level (bottom-bar) destination using the multiple-back-stack
 * pattern: each tab keeps its own back stack, saved and restored as you switch.
 *
 * This must be used for every navigation to a bottom-bar destination — including
 * the shortcuts on the Home screen. Reaching one of these destinations with a
 * plain [NavController.navigate] would push it on top of Home instead of making
 * it a sibling tab, which corrupts the saved state and makes the Home tab restore
 * the wrong screen (e.g. tapping Home landing on the baby's screen).
 */
private fun NavController.navigateToBottomDestination(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

private fun isBottomDestination(route: String) = bottomDestinations.any { it.route == route }

/**
 * Opens any known route the right way round: tabs as siblings, everything else
 * pushed on top of wherever we are.
 *
 * The widget asks for [Routes.FEEDING], which stopped being a tab when the bar
 * became four kinds of place — so a guard that only honoured bottom destinations
 * would drop that tap on the floor without saying anything.
 */
private fun NavController.navigateToKnown(route: String) {
    if (route !in knownRoutes) return
    if (isBottomDestination(route)) {
        navigateToBottomDestination(route)
    } else {
        // Come back to the screen if it is already open rather than stacking a
        // second copy of it. A widget tap arriving while the live nursing timer
        // was on top used to push a second Feeding screen over it and leave the
        // timer behind, one Back press from saving the same feed again.
        navigate(route) {
            popUpTo(route)
            launchSingleTop = true
        }
    }
}

/**
 * Opens the live timer with exactly one nursing screen on the stack.
 *
 * Anything already sitting above Feeding is a nursing screen from an earlier
 * trip through here; leaving it there leaves a second timer for the same feed
 * a Back press away.
 */
private fun NavController.navigateToNursing(side: BreastSide) {
    navigate(Routes.nursing(side)) { popUpTo(Routes.FEEDING) }
}

/** Babies and the current selection, for the shell's own bar. */
class ShellViewModel(repository: SproutRepository) : ViewModel() {
    val babies = repository.babies
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val activeBabyId = repository.parentProfile
        .map { it?.activeBabyId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}

/**
 * Root composable: chooses between onboarding and the main app based on the
 * startup stage. The daily check-in is *not* one of the stages — it waits on the
 * dashboard instead of standing between a parent and the app. [routeRequest] is
 * a route the launching intent asked to open (e.g. the widget landing on
 * Feeding); it is honoured once the main scaffold is up and acknowledged via
 * [onRouteConsumed].
 */
@Composable
fun SproutApp(
    routeRequest: String? = null,
    onRouteConsumed: () -> Unit = {},
    /** A Sprout file another app just opened with us (an invitation, or a replica). */
    syncFile: Uri? = null,
    onSyncFileConsumed: () -> Unit = {},
) {
    val startupVm: StartupViewModel = viewModel(factory = rememberSproutViewModelFactory())
    val stage by startupVm.startup.collectAsState()

    when (stage) {
        Startup.Loading -> LoadingScreen()
        Startup.Onboarding -> OnboardingScreen(onFinish = startupVm::completeOnboarding)
        Startup.Main -> MainScaffold(routeRequest, onRouteConsumed, syncFile, onSyncFileConsumed)
    }
}

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun MainScaffold(
    routeRequest: String? = null,
    onRouteConsumed: () -> Unit = {},
    syncFile: Uri? = null,
    onSyncFileConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val shellVm: ShellViewModel = viewModel(factory = rememberSproutViewModelFactory())
    val babies by shellVm.babies.collectAsState()
    val activeBabyId by shellVm.activeBabyId.collectAsState()

    val showBottomBar = currentRoute in bottomDestinations.map { it.route }

    // Honour a route the launching intent asked for (widget tap → Feeding),
    // then consume it so it doesn't re-fire on recomposition or rotation.
    LaunchedEffect(routeRequest) {
        if (routeRequest != null) {
            navController.navigateToKnown(routeRequest)
            onRouteConsumed()
        }
    }

    // A Sprout file opened from a messaging app lands on the sync screen, which
    // is the only place that knows what to do with one.
    LaunchedEffect(syncFile) {
        if (syncFile != null) navController.navigate(Routes.SYNC)
    }

    /**
     * Start a breastfeed from the dashboard. Feeding goes on the stack first so
     * that backing out of the timer lands on the feed history rather than on
     * Home, and so the nursing screen can still share the feeding ViewModel that
     * owns the live session.
     */
    fun openNursing(side: BreastSide) {
        navController.navigate(Routes.FEEDING) {
            // Reuse the Feeding screen when it is already open: a second copy
            // of it is a second copy of everything the timer is shown by.
            popUpTo(Routes.FEEDING)
            launchSingleTop = true
        }
        navController.navigateToNursing(side)
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    // The baby's own tab only earns a seat once there is a
                    // choice to make; with one baby the dashboard is already it.
                    val visible = bottomDestinations.filter {
                        it.route != Routes.BABY || babies.size > 1
                    }
                    visible.forEach { dest ->
                        val selected = backStackEntry?.destination?.hierarchy
                            ?.any { it.route == dest.route } == true
                        val label = babyTabLabel(dest, babies, activeBabyId)
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.navigateToBottomDestination(dest.route) },
                            icon = { Icon(dest.icon, contentDescription = label) },
                            label = { Text(label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    onNavigate = { route -> navController.navigateToKnown(route) },
                    onQuickFeed = { side -> openNursing(side) },
                )
            }
            composable(Routes.BABY) {
                BabyScreen(
                    onNavigate = { route -> navController.navigateToKnown(route) },
                    onQuickFeed = { side -> openNursing(side) },
                )
            }
            composable(Routes.YOU) {
                YouScreen(onNavigate = { route -> navController.navigateToKnown(route) })
            }
            composable(Routes.FEEDING) {
                FeedingScreen(
                    onBack = { navController.popBackStack() },
                    onOpenNursing = { side -> navController.navigateToNursing(side) },
                )
            }
            composable(
                Routes.FEEDING_NURSING,
                arguments = listOf(navArgument("side") { type = NavType.StringType }),
            ) { entry ->
                val side = entry.arguments?.getString("side")
                    ?.let { runCatching { BreastSide.valueOf(it) }.getOrNull() }
                    ?: BreastSide.LEFT
                // Share the feeding screen's ViewModel so the live session is the
                // same one its bottom bar started and can resume.
                val feedingEntry = remember(entry) { navController.getBackStackEntry(Routes.FEEDING) }
                val vm: FeedingViewModel = viewModel(
                    viewModelStoreOwner = feedingEntry,
                    factory = rememberSproutViewModelFactory(),
                )
                NursingScreen(side = side, onDone = { navController.popBackStack() }, vm = vm)
            }
            composable(Routes.PUMPING) {
                PumpingScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.SLEEP) {
                SleepScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.DIAPER) {
                DiaperScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.GROWTH) {
                GrowthScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.HEALTH) {
                HealthScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.STATS) {
                StatsScreen()
            }
            composable(Routes.CHECKIN) {
                CheckInRoute(onDone = { navController.popBackStack() })
            }
            composable(Routes.PROFILE) {
                ProfileScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenSync = { navController.navigate(Routes.SYNC) },
                )
            }
            composable(Routes.SYNC) {
                SyncScreen(
                    onBack = { navController.popBackStack() },
                    incomingFile = syncFile,
                    onIncomingFileHandled = onSyncFileConsumed,
                )
            }
            composable(Routes.TREATMENTS) {
                TreatmentsScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

/**
 * The baby tab wears the child's name, because "Baby" next to a dashboard that
 * lists Léa and Noé says nothing about which one it opens.
 */
@Composable
private fun babyTabLabel(
    dest: BottomDestination,
    babies: List<BabyEntity>,
    activeBabyId: Long?,
): String {
    if (dest.route != Routes.BABY) return stringResource(dest.labelRes)
    val active = babies.firstOrNull { it.id == activeBabyId }
    return active?.name ?: stringResource(dest.labelRes)
}
