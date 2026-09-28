package com.rakshika.app.ui.navigation

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.rakshika.app.RakshikaViewModel
import com.rakshika.app.ui.screens.call.FakeCallScreen
import com.rakshika.app.ui.screens.contacts.ContactsScreen
import com.rakshika.app.ui.screens.demo.DemoScreen
import com.rakshika.app.ui.screens.ride.RideScreen
import com.rakshika.app.ui.screens.timeline.TimelineScreen
import com.rakshika.app.ui.theme.RakshikaRed
import com.rakshika.app.ui.theme.SurfaceCard

private sealed class Dest(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    /** The real app's route search, route choice and GPS-followed ride — the screen the app opens on. */
    data object Home : Dest("home", "Home", Icons.Filled.Home)
    data object Timeline : Dest("timeline", "Activity", Icons.Filled.List)
    data object Contacts : Dest("contacts", "Contacts", Icons.Filled.Group)
    data object Demo : Dest("demo", "Demo", Icons.Filled.PlayCircle)
}

private val bottomDestinations = listOf(Dest.Home, Dest.Timeline, Dest.Contacts, Dest.Demo)

@Composable
fun RakshikaNavHost(viewModel: RakshikaViewModel = viewModel()) {
    val navController = rememberNavController()
    val uiState by viewModel.uiState.collectAsState()
    // True while a ride is being navigated: bottom bar and system bars hide so the map gets the whole screen.
    var fullScreen by remember { mutableStateOf(false) }
    val view = LocalView.current
    DisposableEffect(fullScreen) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (fullScreen) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    // The fake call is drawn over the app rather than instead of it, so a ride in progress keeps its screen state.
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = SurfaceCard,
            bottomBar = {
                if (!fullScreen) NavigationBar(containerColor = SurfaceCard) {
                    val backStackEntry by navController.currentBackStackEntryAsState()
                    val currentDestination = backStackEntry?.destination

                    bottomDestinations.forEach { dest ->
                        val selected = currentDestination?.hierarchy?.any { it.route == dest.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(dest.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(dest.icon, contentDescription = dest.label) },
                            label = { Text(dest.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = RakshikaRed,
                                selectedTextColor = RakshikaRed
                            )
                        )
                    }
                }
            }
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = Dest.Home.route,
                modifier = Modifier.padding(padding)
            ) {
                composable(Dest.Home.route) {
                    // The real app: same flow as the demo's "Try it yourself", but the ride follows the phone's GPS.
                    RideScreen(
                        onFakeCall = viewModel::startFakeCall,
                        onFullScreenChange = { fullScreen = it },
                        realMotion = true,
                        onSos = viewModel::sendSos
                    )
                }
                composable(Dest.Timeline.route) {
                    TimelineScreen(events = uiState.events)
                }
                composable(Dest.Contacts.route) {
                    ContactsScreen(
                        contacts = uiState.contacts,
                        smsPermissionGranted = uiState.smsPermissionGranted,
                        onSmsPermissionResult = viewModel::refreshSmsPermission,
                        onAddContact = viewModel::addContact,
                        onRemoveContact = viewModel::removeContact,
                        onToggleAlerts = viewModel::setContactAlerts
                    )
                }
                composable(Dest.Demo.route) {
                    DemoScreen(onFakeCall = viewModel::startFakeCall, onFullScreenChange = { fullScreen = it })
                }
            }
        }

        if (uiState.fakeCallRinging) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } }) {
                FakeCallScreen(onEndCall = viewModel::endFakeCall)
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
