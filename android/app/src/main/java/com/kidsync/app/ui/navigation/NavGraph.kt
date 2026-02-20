package com.kidsync.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.kidsync.app.ui.screens.auth.LoginScreen
import com.kidsync.app.ui.screens.auth.RecoveryKeyScreen
import com.kidsync.app.ui.screens.auth.RecoveryRestoreScreen
import com.kidsync.app.ui.screens.auth.RegisterScreen
import com.kidsync.app.ui.screens.auth.TotpSetupScreen
import com.kidsync.app.ui.screens.auth.WelcomeScreen
import com.kidsync.app.ui.screens.dashboard.DashboardScreen
import com.kidsync.app.ui.screens.family.AddChildrenScreen
import com.kidsync.app.ui.screens.family.FamilySetupScreen
import com.kidsync.app.ui.screens.family.InviteCoParentScreen
import com.kidsync.app.ui.screens.family.JoinFamilyScreen
import com.kidsync.app.ui.screens.settings.DeviceListScreen
import com.kidsync.app.ui.screens.settings.ServerConfigScreen
import com.kidsync.app.ui.screens.settings.SettingsScreen

/**
 * Main navigation graph for the KidSync app.
 *
 * Flow:
 * Splash -> Welcome -> Register/Login -> TotpSetup -> RecoveryKey -> FamilySetup ->
 * AddChildren -> InviteCoParent -> Dashboard
 *
 * Alternative: Welcome -> Login -> Dashboard (returning user)
 * Alternative: Welcome -> JoinFamily -> Dashboard (invited co-parent)
 */
@Composable
fun KidSyncNavGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    startDestination: String = Routes.Splash.route
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {
        composable(Routes.Splash.route) {
            // Splash auto-navigates based on auth state
            // Handled by AuthViewModel checking stored session
            WelcomeScreen(
                onGetStarted = {
                    navController.navigate(Routes.Register.route) {
                        popUpTo(Routes.Splash.route) { inclusive = true }
                    }
                },
                onHaveAccount = {
                    navController.navigate(Routes.Login.route) {
                        popUpTo(Routes.Splash.route) { inclusive = true }
                    }
                },
                onJoinFamily = {
                    navController.navigate(Routes.JoinFamily.route)
                }
            )
        }

        composable(Routes.Welcome.route) {
            WelcomeScreen(
                onGetStarted = {
                    navController.navigate(Routes.Register.route)
                },
                onHaveAccount = {
                    navController.navigate(Routes.Login.route)
                },
                onJoinFamily = {
                    navController.navigate(Routes.JoinFamily.route)
                }
            )
        }

        composable(Routes.Register.route) {
            RegisterScreen(
                onRegistered = {
                    navController.navigate(Routes.TotpSetup.route) {
                        popUpTo(Routes.Welcome.route) { inclusive = true }
                    }
                },
                onNavigateToLogin = {
                    navController.navigate(Routes.Login.route) {
                        popUpTo(Routes.Register.route) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.Login.route) {
            LoginScreen(
                onLoggedIn = {
                    navController.navigate(Routes.Dashboard.route) {
                        popUpTo(Routes.Welcome.route) { inclusive = true }
                    }
                },
                onNavigateToRegister = {
                    navController.navigate(Routes.Register.route) {
                        popUpTo(Routes.Login.route) { inclusive = true }
                    }
                },
                onRecoveryRestore = {
                    navController.navigate(Routes.RecoveryRestore.route)
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.TotpSetup.route) {
            TotpSetupScreen(
                onTotpVerified = {
                    navController.navigate(Routes.RecoveryKey.route) {
                        popUpTo(Routes.TotpSetup.route) { inclusive = true }
                    }
                },
                onSkip = {
                    navController.navigate(Routes.RecoveryKey.route) {
                        popUpTo(Routes.TotpSetup.route) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.RecoveryKey.route) {
            RecoveryKeyScreen(
                onContinue = {
                    navController.navigate(Routes.FamilySetup.route) {
                        popUpTo(Routes.RecoveryKey.route) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.RecoveryRestore.route) {
            RecoveryRestoreScreen(
                onRestored = {
                    navController.navigate(Routes.Dashboard.route) {
                        popUpTo(Routes.Welcome.route) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.FamilySetup.route) {
            FamilySetupScreen(
                onFamilyCreated = {
                    navController.navigate(Routes.AddChildren.route)
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.AddChildren.route) {
            AddChildrenScreen(
                onContinue = {
                    navController.navigate(Routes.InviteCoParent.route)
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.InviteCoParent.route) {
            InviteCoParentScreen(
                onContinue = {
                    navController.navigate(Routes.Dashboard.route) {
                        popUpTo(Routes.FamilySetup.route) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.JoinFamily.route) {
            JoinFamilyScreen(
                onJoined = {
                    navController.navigate(Routes.Dashboard.route) {
                        popUpTo(Routes.Welcome.route) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.Dashboard.route) {
            DashboardScreen(
                onNavigateToSettings = {
                    navController.navigate(Routes.Settings.route)
                }
            )
        }

        composable(Routes.Settings.route) {
            SettingsScreen(
                onNavigateToDeviceList = {
                    navController.navigate(Routes.DeviceList.route)
                },
                onNavigateToServerConfig = {
                    navController.navigate(Routes.ServerConfig.route)
                },
                onLogout = {
                    navController.navigate(Routes.Welcome.route) {
                        popUpTo(Routes.Dashboard.route) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.DeviceList.route) {
            DeviceListScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.ServerConfig.route) {
            ServerConfigScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
