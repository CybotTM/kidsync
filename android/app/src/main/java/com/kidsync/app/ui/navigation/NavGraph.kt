package com.kidsync.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.kidsync.app.ui.screens.auth.KeySetupScreen
import com.kidsync.app.ui.screens.auth.RecoveryKeyScreen
import com.kidsync.app.ui.screens.auth.RecoveryRestoreScreen
import com.kidsync.app.ui.screens.auth.WelcomeScreen
import com.kidsync.app.ui.screens.bucket.BucketSetupScreen
import com.kidsync.app.ui.screens.bucket.JoinBucketScreen
import com.kidsync.app.ui.screens.bucket.PairingScreen
import com.kidsync.app.ui.screens.calendar.AnchorDateScreen
import com.kidsync.app.ui.screens.calendar.CalendarScreen
import com.kidsync.app.ui.screens.calendar.CustomPatternScreen
import com.kidsync.app.ui.screens.calendar.DayDetailScreen
import com.kidsync.app.ui.screens.calendar.EventFormScreen
import com.kidsync.app.ui.screens.calendar.ScheduleSetupScreen
import com.kidsync.app.ui.screens.calendar.SwapApprovalScreen
import com.kidsync.app.ui.screens.calendar.SwapRequestScreen
import com.kidsync.app.ui.screens.dashboard.DashboardScreen
import com.kidsync.app.ui.screens.expense.AddExpenseScreen
import com.kidsync.app.ui.screens.expense.ExpenseDetailScreen
import com.kidsync.app.ui.screens.expense.ExpenseListScreen
import com.kidsync.app.ui.screens.expense.ExpenseSummaryScreen
import com.kidsync.app.ui.screens.family.AddChildrenScreen
import com.kidsync.app.ui.screens.infobank.InfoBankDetailScreen
import com.kidsync.app.ui.screens.infobank.InfoBankFormScreen
import com.kidsync.app.ui.screens.infobank.InfoBankScreen
import com.kidsync.app.ui.screens.settings.DeviceListScreen
import com.kidsync.app.ui.screens.settings.FileTransferScreen
import com.kidsync.app.ui.screens.settings.P2PSyncScreen
import com.kidsync.app.ui.screens.settings.ServerConfigScreen
import com.kidsync.app.ui.screens.settings.SettingsScreen
import com.kidsync.app.ui.screens.settings.WebDavSettingsScreen
import com.kidsync.app.ui.viewmodel.AuthViewModel

/**
 * Auth guard wrapper that redirects unauthenticated users to Welcome.
 * Uses key-based authentication check instead of email/password session.
 */
@Composable
fun AuthenticatedRoute(
    authViewModel: AuthViewModel,
    navController: NavHostController,
    content: @Composable () -> Unit
) {
    val uiState by authViewModel.uiState.collectAsState()
    LaunchedEffect(uiState.isAuthenticated) {
        if (!uiState.isAuthenticated) {
            navController.navigate(Routes.Welcome.route) {
                popUpTo(0) { inclusive = true }
            }
        }
    }
    if (uiState.isAuthenticated) {
        content()
    }
}

/**
 * Main navigation graph for the KidSync app.
 *
 * Zero-knowledge auth flow:
 * Splash -> Welcome -> KeySetup -> RecoveryKey -> BucketSetup ->
 * AddChildren -> Pairing -> Dashboard
 *
 * Alternative: Welcome -> RecoveryRestore -> Dashboard (recovering user)
 * Alternative: Welcome -> (auto-auth) -> Dashboard (returning device)
 * Alternative: JoinBucket -> Dashboard (scanning co-parent's QR code)
 */
@Composable
fun KidSyncNavGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    startDestination: String = Routes.Splash.route
) {
    val authViewModel: AuthViewModel = hiltViewModel()

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {
        // Splash: auto-navigates based on existing device keys
        composable(Routes.Splash.route) {
            val uiState by authViewModel.uiState.collectAsState()
            LaunchedEffect(uiState.isAuthenticated) {
                if (uiState.isAuthenticated) {
                    navController.navigate(Routes.Dashboard.route) {
                        popUpTo(Routes.Splash.route) { inclusive = true }
                    }
                } else if (!uiState.isLoading) {
                    navController.navigate(Routes.Welcome.route) {
                        popUpTo(Routes.Splash.route) { inclusive = true }
                    }
                }
            }
        }

        composable(Routes.Welcome.route) {
            WelcomeScreen(
                onSetUpNew = {
                    navController.navigate(Routes.KeySetup.route)
                },
                onRestoreFromRecovery = {
                    navController.navigate(Routes.RecoveryRestore.route)
                }
            )
        }

        composable(Routes.KeySetup.route) {
            KeySetupScreen(
                onKeysReady = {
                    navController.navigate(Routes.RecoveryKey.route) {
                        popUpTo(Routes.Welcome.route) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.RecoveryKey.route) {
            RecoveryKeyScreen(
                onContinue = {
                    navController.navigate(Routes.BucketSetup.route) {
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

        composable(Routes.BucketSetup.route) {
            BucketSetupScreen(
                onBucketCreated = {
                    navController.navigate(Routes.AddChildren.route)
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.AddChildren.route) {
            AddChildrenScreen(
                onContinue = {
                    navController.navigate(Routes.Pairing.route)
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.Pairing.route) {
            PairingScreen(
                onContinue = {
                    navController.navigate(Routes.Dashboard.route) {
                        popUpTo(Routes.BucketSetup.route) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.JoinBucket.route) {
            JoinBucketScreen(
                onJoined = {
                    navController.navigate(Routes.Dashboard.route) {
                        popUpTo(Routes.Welcome.route) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.Dashboard.route) {
            AuthenticatedRoute(authViewModel, navController) {
                DashboardScreen(
                    onNavigateToSettings = {
                        navController.navigate(Routes.Settings.route)
                    },
                    onNavigateToExpenses = {
                        navController.navigate(Routes.ExpenseList.route)
                    },
                    onNavigateToCalendar = {
                        navController.navigate(Routes.Calendar.route)
                    },
                    onNavigateToInfoBank = {
                        navController.navigate(Routes.InfoBankList.route)
                    },
                    onInviteCoParent = {
                        navController.navigate(Routes.Pairing.route)
                    }
                )
            }
        }

        // Calendar flow
        composable(Routes.Calendar.route) {
            AuthenticatedRoute(authViewModel, navController) {
                CalendarScreen(
                    onBack = { navController.popBackStack() },
                    onDayClick = { date ->
                        navController.navigate(Routes.DayDetail.createRoute(date))
                    },
                    onRequestSwap = {
                        navController.navigate(Routes.SwapRequest.createRoute())
                    },
                    onSetupSchedule = {
                        navController.navigate(Routes.ScheduleSetup.route)
                    }
                )
            }
        }

        composable(
            route = Routes.DayDetail.route,
            arguments = listOf(
                navArgument("date") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            AuthenticatedRoute(authViewModel, navController) {
                val date = backStackEntry.arguments?.getString("date") ?: ""
                DayDetailScreen(
                    date = date,
                    onBack = { navController.popBackStack() },
                    onAddEvent = { eventDate ->
                        navController.navigate(Routes.EventForm.createRoute(date = eventDate))
                    },
                    onRequestSwap = { swapDate ->
                        navController.navigate(Routes.SwapRequest.createRoute(startDate = swapDate))
                    }
                )
            }
        }

        composable(Routes.ScheduleSetup.route) {
            AuthenticatedRoute(authViewModel, navController) {
                ScheduleSetupScreen(
                    onBack = { navController.popBackStack() },
                    onPresetSelected = {
                        navController.navigate(Routes.AnchorDate.route)
                    },
                    onCustomSelected = {
                        navController.navigate(Routes.CustomPattern.route)
                    }
                )
            }
        }

        composable(Routes.CustomPattern.route) {
            AuthenticatedRoute(authViewModel, navController) {
                CustomPatternScreen(
                    onBack = { navController.popBackStack() },
                    onContinue = {
                        navController.navigate(Routes.AnchorDate.route)
                    }
                )
            }
        }

        composable(Routes.AnchorDate.route) {
            AuthenticatedRoute(authViewModel, navController) {
                AnchorDateScreen(
                    onBack = { navController.popBackStack() },
                    onScheduleSaved = {
                        navController.navigate(Routes.Calendar.route) {
                            popUpTo(Routes.ScheduleSetup.route) { inclusive = true }
                        }
                    }
                )
            }
        }

        composable(
            route = Routes.SwapRequest.route,
            arguments = listOf(
                navArgument("startDate") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            AuthenticatedRoute(authViewModel, navController) {
                val startDate = backStackEntry.arguments?.getString("startDate")
                SwapRequestScreen(
                    startDate = startDate,
                    onBack = { navController.popBackStack() },
                    onSwapSubmitted = {
                        navController.popBackStack()
                    }
                )
            }
        }

        composable(Routes.SwapApproval.route) {
            AuthenticatedRoute(authViewModel, navController) {
                SwapApprovalScreen(
                    onBack = { navController.popBackStack() }
                )
            }
        }

        composable(
            route = Routes.EventForm.route,
            arguments = listOf(
                navArgument("date") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("eventId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            AuthenticatedRoute(authViewModel, navController) {
                val date = backStackEntry.arguments?.getString("date")
                val eventId = backStackEntry.arguments?.getString("eventId")
                EventFormScreen(
                    date = date,
                    eventId = eventId,
                    onBack = { navController.popBackStack() },
                    onSaved = { navController.popBackStack() }
                )
            }
        }

        // Expense flow
        composable(Routes.ExpenseList.route) {
            AuthenticatedRoute(authViewModel, navController) {
                ExpenseListScreen(
                    onNavigateToDetail = { expenseId ->
                        navController.navigate(Routes.ExpenseDetail.createRoute(expenseId))
                    },
                    onNavigateToAddExpense = {
                        navController.navigate(Routes.AddExpense.route)
                    },
                    onNavigateToSummary = {
                        navController.navigate(Routes.ExpenseSummary.route)
                    },
                    onBack = { navController.popBackStack() }
                )
            }
        }

        composable(
            route = Routes.ExpenseDetail.route,
            arguments = listOf(
                navArgument("expenseId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            AuthenticatedRoute(authViewModel, navController) {
                val expenseId = backStackEntry.arguments?.getString("expenseId") ?: ""
                ExpenseDetailScreen(
                    expenseId = expenseId,
                    onBack = { navController.popBackStack() }
                )
            }
        }

        composable(Routes.AddExpense.route) {
            AuthenticatedRoute(authViewModel, navController) {
                AddExpenseScreen(
                    onBack = { navController.popBackStack() },
                    onSaved = { navController.popBackStack() }
                )
            }
        }

        composable(Routes.ExpenseSummary.route) {
            AuthenticatedRoute(authViewModel, navController) {
                ExpenseSummaryScreen(
                    onBack = { navController.popBackStack() }
                )
            }
        }

        // Info Bank flow
        composable(Routes.InfoBankList.route) {
            AuthenticatedRoute(authViewModel, navController) {
                InfoBankScreen(
                    onNavigateToDetail = { entryId ->
                        navController.navigate(Routes.InfoBankDetail.createRoute(entryId))
                    },
                    onNavigateToAddEntry = {
                        navController.navigate(Routes.InfoBankAdd.route)
                    },
                    onBack = { navController.popBackStack() }
                )
            }
        }

        composable(
            route = Routes.InfoBankDetail.route,
            arguments = listOf(
                navArgument("entryId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            AuthenticatedRoute(authViewModel, navController) {
                val entryId = backStackEntry.arguments?.getString("entryId") ?: ""
                InfoBankDetailScreen(
                    entryId = entryId,
                    onBack = { navController.popBackStack() },
                    onEdit = { id ->
                        navController.navigate(Routes.InfoBankEdit.createRoute(id))
                    }
                )
            }
        }

        composable(Routes.InfoBankAdd.route) {
            AuthenticatedRoute(authViewModel, navController) {
                InfoBankFormScreen(
                    entryId = null,
                    onBack = { navController.popBackStack() },
                    onSaved = { navController.popBackStack() }
                )
            }
        }

        composable(
            route = Routes.InfoBankEdit.route,
            arguments = listOf(
                navArgument("entryId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            AuthenticatedRoute(authViewModel, navController) {
                val entryId = backStackEntry.arguments?.getString("entryId") ?: ""
                InfoBankFormScreen(
                    entryId = entryId,
                    onBack = { navController.popBackStack() },
                    onSaved = { navController.popBackStack() }
                )
            }
        }

        composable(Routes.Settings.route) {
            AuthenticatedRoute(authViewModel, navController) {
                SettingsScreen(
                    onNavigateToDeviceList = {
                        navController.navigate(Routes.DeviceList.route)
                    },
                    onNavigateToServerConfig = {
                        navController.navigate(Routes.ServerConfig.route)
                    },
                    onNavigateToFileTransfer = {
                        navController.navigate(Routes.FileTransfer.route)
                    },
                    onNavigateToWebDavSettings = {
                        navController.navigate(Routes.WebDavSettings.route)
                    },
                    onNavigateToP2PSync = {
                        navController.navigate(Routes.P2PSync.route)
                    },
                    onInviteCoParent = {
                        navController.navigate(Routes.Pairing.route)
                    },
                    onLogout = {
                        navController.navigate(Routes.Welcome.route) {
                            popUpTo(Routes.Dashboard.route) { inclusive = true }
                        }
                    },
                    onBack = { navController.popBackStack() }
                )
            }
        }

        composable(Routes.DeviceList.route) {
            AuthenticatedRoute(authViewModel, navController) {
                DeviceListScreen(
                    onBack = { navController.popBackStack() }
                )
            }
        }

        composable(Routes.ServerConfig.route) {
            AuthenticatedRoute(authViewModel, navController) {
                ServerConfigScreen(
                    onBack = { navController.popBackStack() }
                )
            }
        }

        composable(Routes.FileTransfer.route) {
            AuthenticatedRoute(authViewModel, navController) {
                FileTransferScreen(
                    onBack = { navController.popBackStack() }
                )
            }
        }

        composable(Routes.WebDavSettings.route) {
            AuthenticatedRoute(authViewModel, navController) {
                WebDavSettingsScreen(
                    onBack = { navController.popBackStack() }
                )
            }
        }

        composable(Routes.P2PSync.route) {
            AuthenticatedRoute(authViewModel, navController) {
                P2PSyncScreen(
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
