package com.adbhelper.app.ui.navigation

import androidx.compose.runtime.*
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.adbhelper.app.ui.screens.*

object Routes {
    const val HOME = "home"
    const val DEVICE = "device"
    const val SHELL = "shell"
    const val SCRIPTS = "scripts"
    const val SCRIPT_EDITOR = "script_editor/{scriptId}"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
}

@Composable
fun AdbHelperNavHost(
    navController: NavHostController = rememberNavController()
) {
    // 导航锁：防止快速操作导致白屏
    var navLocked by remember { mutableStateOf(false) }

    DisposableEffect(navController) {
        val listener = androidx.navigation.NavController.OnDestinationChangedListener { _, _, _ ->
            navLocked = true
        }
        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }

    // 目标页面渲染后解锁（延迟 300ms 等动画完成）
    val currentEntry by navController.currentBackStackEntryAsState()
    LaunchedEffect(currentEntry?.destination?.route) {
        kotlinx.coroutines.delay(300)
        navLocked = false
    }

    val navLockedState = rememberUpdatedState(navLocked)

    fun navigateSafe(action: () -> Unit) {
        if (!navLockedState.value) action()
    }

    val popBackDebounced: () -> Unit = {
        navigateSafe {
            if (navController.currentBackStackEntry?.destination?.route != Routes.HOME) {
                navController.popBackStack()
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = Routes.HOME
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onNavigateToDevice = { navigateSafe { navController.navigate(Routes.DEVICE) { launchSingleTop = true } } },
                onNavigateToShell = { navigateSafe { navController.navigate(Routes.SHELL) { launchSingleTop = true } } },
                onNavigateToScripts = { navigateSafe { navController.navigate(Routes.SCRIPTS) { launchSingleTop = true } } },
                onNavigateToSettings = { navigateSafe { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } } }
            )
        }

        composable(Routes.DEVICE) {
            DeviceScreen(
                onNavigateBack = popBackDebounced
            )
        }

        composable(Routes.SHELL) {
            ShellScreen(
                onNavigateBack = popBackDebounced
            )
        }

        composable(Routes.SCRIPTS) {
            ScriptsScreen(
                onNavigateBack = popBackDebounced,
                onNavigateToEditor = { scriptId ->
                    navigateSafe { navController.navigate("script_editor/$scriptId") { launchSingleTop = true } }
                },
                onNavigateToShell = { navigateSafe { navController.navigate(Routes.SHELL) { launchSingleTop = true } } }
            )
        }

        composable(Routes.SCRIPT_EDITOR) { backStackEntry ->
            val scriptId = backStackEntry.arguments?.getString("scriptId") ?: "new"
            ScriptEditorScreen(
                scriptId = scriptId,
                onNavigateBack = popBackDebounced,
                onNavigateToShell = { navigateSafe { navController.navigate(Routes.SHELL) { launchSingleTop = true } } }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onNavigateBack = popBackDebounced,
                onNavigateToAbout = { navigateSafe { navController.navigate(Routes.ABOUT) { launchSingleTop = true } } }
            )
        }

        composable(Routes.ABOUT) {
            AboutScreen(
                onNavigateBack = popBackDebounced
            )
        }
    }
}
