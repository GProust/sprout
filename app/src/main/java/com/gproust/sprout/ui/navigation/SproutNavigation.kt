package com.gproust.sprout.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BabyChangingStation
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.gproust.sprout.R
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.gproust.sprout.ui.checkin.DailyCheckInScreen
import com.gproust.sprout.ui.diaper.DiaperScreen
import com.gproust.sprout.ui.feeding.FeedingScreen
import com.gproust.sprout.ui.growth.GrowthScreen
import com.gproust.sprout.ui.health.HealthScreen
import com.gproust.sprout.ui.home.HomeScreen
import com.gproust.sprout.ui.onboarding.OnboardingScreen
import com.gproust.sprout.ui.profile.ProfileScreen
import com.gproust.sprout.ui.rememberSproutViewModelFactory
import com.gproust.sprout.ui.settings.SettingsScreen
import com.gproust.sprout.ui.sleep.SleepScreen
import com.gproust.sprout.ui.startup.Startup
import com.gproust.sprout.ui.startup.StartupViewModel

object Routes {
    const val HOME = "home"
    const val FEEDING = "feeding"
    const val SLEEP = "sleep"
    const val DIAPER = "diaper"
    const val GROWTH = "growth"
    const val HEALTH = "health"
    const val PROFILE = "profile"
    const val SETTINGS = "settings"
}

private data class BottomDestination(
    val route: String,
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
)

private val bottomDestinations = listOf(
    BottomDestination(Routes.HOME, R.string.nav_home, Icons.Filled.Home),
    BottomDestination(Routes.FEEDING, R.string.nav_feed, Icons.Filled.LocalDrink),
    BottomDestination(Routes.SLEEP, R.string.nav_sleep, Icons.Filled.Bedtime),
    BottomDestination(Routes.DIAPER, R.string.nav_diaper, Icons.Filled.BabyChangingStation),
    BottomDestination(Routes.GROWTH, R.string.nav_growth, Icons.Filled.Monitor),
)

/**
 * Root composable: chooses between onboarding, the daily check-in, and the main app
 * based on the startup stage.
 */
@Composable
fun SproutApp() {
    val startupVm: StartupViewModel = viewModel(factory = rememberSproutViewModelFactory())
    val stage by startupVm.startup.collectAsState()

    when (val s = stage) {
        Startup.Loading -> LoadingScreen()
        Startup.Onboarding -> OnboardingScreen(onFinish = startupVm::completeOnboarding)
        is Startup.CheckIn -> DailyCheckInScreen(
            name = s.name,
            gaveBirth = s.gaveBirth,
            breastfeeding = s.breastfeeding,
            deliveryType = s.deliveryType,
            onSubmit = startupVm::submitCheckIn,
            onSkip = startupVm::markCheckedIn,
        )
        Startup.Main -> MainScaffold()
    }
}

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun MainScaffold() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val showBottomBar = currentRoute in bottomDestinations.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomDestinations.forEach { dest ->
                        val selected = backStackEntry?.destination?.hierarchy
                            ?.any { it.route == dest.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(dest.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(dest.icon, contentDescription = stringResource(dest.labelRes))
                            },
                            label = { Text(stringResource(dest.labelRes)) },
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
                HomeScreen(onNavigate = { route -> navController.navigate(route) })
            }
            composable(Routes.FEEDING) { FeedingScreen() }
            composable(Routes.SLEEP) { SleepScreen() }
            composable(Routes.DIAPER) { DiaperScreen() }
            composable(Routes.GROWTH) { GrowthScreen() }
            composable(Routes.HEALTH) {
                HealthScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.PROFILE) {
                ProfileScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
