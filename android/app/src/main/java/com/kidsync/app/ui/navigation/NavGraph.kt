package com.kidsync.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.kidsync.app.ui.screens.auth.LoginScreen
import com.kidsync.app.ui.screens.auth.RecoveryKeyScreen
import com.kidsync.app.ui.screens.auth.RecoveryRestoreScreen
import com.kidsync.app.ui.screens.auth.RegisterScreen
import com.kidsync.app.ui.screens.auth.TotpSetupScreen
import com.kidsync.app.ui.screens.auth.WelcomeScreen
import com.kidsync.app.ui.viewmodel.AuthViewModel
import androidx.navigation.NavType
import androidx.navigation.navArgument
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
import com.kidsync.app.ui.screens.family.FamilySetupScreen
import com.kidsync.app.ui.viewmodel.FamilyViewModel
import com.kidsync.app.ui.screens.family.InviteCoParentScreen
import com.kidsync.app.ui.screens.family.JoinFamilyScreen
import com.kidsync.app.ui.screens.infobank.InfoBankDetailScreen
import com.kidsync.app.ui.screens.infobank.InfoBankFormScreen
import com.kidsync.app.ui.screens.infobank.InfoBankScreen
import com.kidsync.app.ui.screens.settings.DeviceListScreen
import com.kidsync.app.ui.screens.settings.ServerConfigScreen
import com.kidsync.app.ui.screens.settings.SettingsScreen

/**
 * SEC-C2: Auth guard wrapper that redirects unauthenticated users to Welcome.
 */
@Composable
fun AuthenticatedRoute(
    authViewModel: AuthViewModel,
    navController: NavHostController,
    content: @Composable () -> Unit
) {
    val uiState by authViewModel.uiState.collectAsState()
    LaunchedEffect(uiState.isLoggedIn) {
        if (!uiState.isLoggedIn) {
            navController.navigate(Routes.Welcome.route) {
                popUpTo(0) { inclusive = true }
            }
        }
    }
    if (uiState.isLoggedIn) {
        content()
    }
}

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
    // SEC-C2: Shared auth view model for navigation-level auth checks
    val authViewModel: AuthViewModel = hiltViewModel()

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
            val familyViewModel: FamilyViewModel = hiltViewModel()
            FamilySetupScreen(
                onFamilyCreated = {
                    // Store solo flag for downstream navigation decisions
                    val isSolo = familyViewModel.uiState.value.isSolo
                    navController.currentBackStackEntry
                        ?.savedStateHandle?.set("isSolo", isSolo)
                    navController.navigate(Routes.AddChildren.route)
                },
                onBack = { navController.popBackStack() },
                viewModel = familyViewModel
            )
        }

        composable(Routes.AddChildren.route) {
            AddChildrenScreen(
                onContinue = {
                    // Check if the previous entry stored solo mode flag
                    val isSolo = navController.previousBackStackEntry
                        ?.savedStateHandle?.get<Boolean>("isSolo") ?: false
                    if (isSolo) {
                        // Solo mode: skip invite and go straight to dashboard
                        navController.navigate(Routes.Dashboard.route) {
                            popUpTo(Routes.FamilySetup.route) { inclusive = true }
                        }
                    } else {
                        navController.navigate(Routes.InviteCoParent.route)
                    }
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
                        navController.navigate(Routes.InviteCoParent.route)
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
                    onInviteCoParent = {
                        navController.navigate(Routes.InviteCoParent.route)
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
    }
}
