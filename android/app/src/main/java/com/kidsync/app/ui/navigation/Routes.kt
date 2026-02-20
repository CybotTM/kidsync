package com.kidsync.app.ui.navigation

/**
 * Sealed class defining all navigation routes in the KidSync app.
 * Each route has a unique [route] string used by the Navigation component.
 */
sealed class Routes(val route: String) {

    // Auth flow
    data object Splash : Routes("splash")
    data object Welcome : Routes("welcome")
    data object Register : Routes("register")
    data object Login : Routes("login")
    data object TotpSetup : Routes("totp_setup")
    data object RecoveryKey : Routes("recovery_key")
    data object RecoveryRestore : Routes("recovery_restore")

    // Family onboarding flow
    data object FamilySetup : Routes("family_setup")
    data object AddChildren : Routes("add_children")
    data object InviteCoParent : Routes("invite_co_parent")
    data object JoinFamily : Routes("join_family")

    // Main app
    data object Dashboard : Routes("dashboard")

    // Settings
    data object Settings : Routes("settings")
    data object DeviceList : Routes("device_list")
    data object ServerConfig : Routes("server_config")
}
