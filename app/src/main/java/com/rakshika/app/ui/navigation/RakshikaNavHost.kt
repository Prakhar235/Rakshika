package com.rakshika.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import com.rakshika.app.ui.screens.home.HomeScreen
import com.rakshika.app.ui.screens.map.MapScreen
import com.rakshika.app.ui.screens.timeline.TimelineScreen
import com.rakshika.app.ui.theme.RakshikaRed
import com.rakshika.app.ui.theme.SurfaceCard

private sealed class Dest(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    data object Home : Dest("home", "Home", Icons.Filled.Home)
    data object Map : Dest("map", "Map", Icons.Filled.Map)
    data object Timeline : Dest("timeline", "Activity", Icons.Filled.List)
    data object Contacts : Dest("contacts", "Contacts", Icons.Filled.Group)
    data object Demo : Dest("demo", "Demo", Icons.Filled.PlayCircle)
}

private val bottomDestinations = listOf(Dest.Home, Dest.Map, Dest.Timeline, Dest.Contacts, Dest.Demo)

@Composable
fun RakshikaNavHost(viewModel: RakshikaViewModel = viewModel()) {
    val navController = rememberNavController()
    val uiState by viewModel.uiState.collectAsState()

    if (uiState.fakeCallRinging) {
        FakeCallScreen(onEndCall = viewModel::endFakeCall)
        return
    }

    Scaffold(
        containerColor = SurfaceCard,
        bottomBar = {
            NavigationBar(containerColor = SurfaceCard) {
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
                HomeScreen(
                    state = uiState,
                    onToggleOnline = viewModel::toggleOnlineMode,
                    onSosTriggered = { viewModel.triggerSos() },
                    onStartCheckIn = viewModel::startCheckIn,
                    onCancelCheckIn = { viewModel.cancelCheckIn() },
                    onShareLocation = viewModel::shareLocation,
                    onFakeCall = viewModel::startFakeCall,
                    onOpenContacts = {
                        navController.navigate(Dest.Contacts.route) { launchSingleTop = true }
                    }
                )
            }
            composable(Dest.Map.route) {
                MapScreen(
                    onSos = { viewModel.triggerSos() },
                    onShareLocation = viewModel::shareLocation
                )
            }
            composable(Dest.Timeline.route) {
                TimelineScreen(events = uiState.events)
            }
            composable(Dest.Contacts.route) {
                ContactsScreen(
                    contacts = uiState.contacts,
                    onAddContact = viewModel::addContact,
                    onRemoveContact = viewModel::removeContact
                )
            }
            composable(Dest.Demo.route) {
                DemoScreen()
            }
        }
    }
}
