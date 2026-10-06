package dev.joseramos.aireader.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import dev.joseramos.aireader.R
import dev.joseramos.aireader.core.designsystem.component.TabBar
import dev.joseramos.aireader.core.designsystem.component.TabItem
import dev.joseramos.aireader.core.designsystem.theme.AppTheme
import dev.joseramos.aireader.core.designsystem.theme.BarSize
import dev.joseramos.aireader.core.designsystem.theme.LocalBottomBarHeight
import dev.joseramos.aireader.feature.characters.CharactersRoute
import dev.joseramos.aireader.feature.characters.RelationsGraphRoute
import dev.joseramos.aireader.feature.characters.charactersScreens
import dev.joseramos.aireader.feature.chat.ChatRoute
import dev.joseramos.aireader.feature.chat.chatScreen
import dev.joseramos.aireader.feature.library.LibraryRoute
import dev.joseramos.aireader.feature.library.libraryScreen
import dev.joseramos.aireader.feature.reader.ReaderRoute
import dev.joseramos.aireader.feature.reader.player.MiniPlayer
import dev.joseramos.aireader.feature.reader.player.MiniPlayerHeight
import dev.joseramos.aireader.feature.reader.player.PlayerViewModel
import dev.joseramos.aireader.feature.reader.readerScreen
import dev.joseramos.aireader.feature.settings.SettingsRoute
import dev.joseramos.aireader.feature.settings.settingsScreen
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Pestañas de primer nivel (docs/02-diseno-tecnico.md §3.3). */
private enum class TopLevel(val route: Any) { LIBRARY(LibraryRoute), SETTINGS(SettingsRoute) }

@Composable
fun AiReaderApp(
    openBookRequests: Flow<String> = emptyFlow(),
    navController: NavHostController = rememberNavController()
) {
    val hazeState = rememberHazeState()
    val player: PlayerViewModel = hiltViewModel()
    val playback by player.state.collectAsStateWithLifecycle()
    // PDFs abiertos desde otras apps: se importan y se abren directamente en el lector.
    LaunchedEffect(openBookRequests) {
        openBookRequests.collect { navController.navigate(ReaderRoute(it)) }
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val selectedTab = when {
        destination?.hasRoute(LibraryRoute::class) == true -> TopLevel.LIBRARY
        destination?.hasRoute(SettingsRoute::class) == true -> TopLevel.SETTINGS
        else -> null
    }
    val tabs = listOf(
        TabItem(stringResource(R.string.tab_library), Icons.Outlined.AutoStories, Icons.Filled.AutoStories),
        TabItem(stringResource(R.string.tab_settings), Icons.Outlined.Settings, Icons.Filled.Settings)
    )

    Box(Modifier.fillMaxSize().background(AppTheme.colors.background)) {
        val showMiniPlayer = selectedTab != null && playback.isActive
        val bottomBars = when {
            selectedTab == null -> 0.dp
            showMiniPlayer -> BarSize.tabBar + MiniPlayerHeight
            else -> BarSize.tabBar
        }
        CompositionLocalProvider(LocalBottomBarHeight provides bottomBars) {
            NavHost(
                navController = navController,
                startDestination = LibraryRoute,
                modifier = Modifier.fillMaxSize().hazeSource(hazeState),
                enterTransition = { fadeIn() },
                exitTransition = { fadeOut() }
            ) {
                libraryScreen(onOpenBook = { bookId, page -> navController.navigate(ReaderRoute(bookId, page)) })
                settingsScreen()
                readerScreen(
                    onBack = navController::popBackStack,
                    onOpenChat = { navController.navigate(ChatRoute(it)) },
                    onOpenCharacters = { navController.navigate(CharactersRoute(it)) }
                )
                charactersScreens(
                    onBack = navController::popBackStack,
                    onOpenPage = { bookId, page ->
                        // Vuelve al lector de debajo, abierto en la página pedida.
                        navController.navigate(ReaderRoute(bookId, page)) { popUpTo<ReaderRoute> { inclusive = true } }
                    },
                    onOpenGraph = { navController.navigate(RelationsGraphRoute(it)) }
                )
                chatScreen(
                    onBack = navController::popBackStack,
                    onOpenPage = { bookId, page ->
                        // Sustituye el lector que hay debajo del chat por uno abierto en la página citada.
                        navController.navigate(ReaderRoute(bookId, page)) { popUpTo<ReaderRoute> { inclusive = true } }
                    },
                    onOpenSource = { bookId, messageId, source ->
                        // Igual, en la página donde empieza la fuente y con su pasaje resaltado.
                        navController.navigate(ReaderRoute(bookId, source.startPage, messageId, source.number)) {
                            popUpTo<ReaderRoute> { inclusive = true }
                        }
                    }
                )
            }
        }
        if (selectedTab != null) {
            Column(Modifier.align(Alignment.BottomCenter)) {
                if (showMiniPlayer) {
                    MiniPlayer(onOpenBook = { navController.navigate(ReaderRoute(it)) }, viewModel = player)
                }
                TabBar(
                    items = tabs,
                    selectedIndex = selectedTab.ordinal,
                    onSelect = { navController.navigateToTab(TopLevel.entries[it]) },
                    hazeState = hazeState
                )
            }
        }
    }
}

/** Cambia de pestaña conservando el estado de cada una, como en iOS. */
private fun NavHostController.navigateToTab(tab: TopLevel) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
