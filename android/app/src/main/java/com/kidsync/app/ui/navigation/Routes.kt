package com.kidsync.app.ui.navigation

/**
 * Sealed class defining all navigation routes in the KidSync app.
 * Updated for zero-knowledge architecture: removed Login, Register, TotpSetup.
 * Added KeySetup, BucketSetup, Pairing, JoinBucket routes.
 * Each route has a unique [route] string used by the Navigation component.
 */
sealed class Routes(val route: String) {

    // Auth flow (zero-knowledge)
    data object Splash : Routes("splash")
    data object Welcome : Routes("welcome")
    data object KeySetup : Routes("key_setup")
    data object RecoveryKey : Routes("recovery_key")
    data object RecoveryRestore : Routes("recovery_restore")

    // Bucket onboarding flow (replaces family flow)
    data object BucketSetup : Routes("bucket_setup")
    data object AddChildren : Routes("add_children")
    data object Pairing : Routes("pairing")
    data object JoinBucket : Routes("join_bucket")

    // Main app
    data object Dashboard : Routes("dashboard")

    // Calendar
    data object Calendar : Routes("calendar")
    data object DayDetail : Routes("day_detail/{date}") {
        fun createRoute(date: String) = "day_detail/$date"
    }
    data object ScheduleSetup : Routes("schedule_setup")
    data object CustomPattern : Routes("custom_pattern")
    data object AnchorDate : Routes("anchor_date")
    data object SwapRequest : Routes("swap_request?startDate={startDate}") {
        fun createRoute(startDate: String? = null) =
            if (startDate != null) "swap_request?startDate=$startDate"
            else "swap_request"
    }
    data object SwapApproval : Routes("swap_approval")
    data object EventForm : Routes("event_form?date={date}&eventId={eventId}") {
        fun createRoute(date: String? = null, eventId: String? = null): String {
            val params = mutableListOf<String>()
            date?.let { params.add("date=$it") }
            eventId?.let { params.add("eventId=$it") }
            return if (params.isEmpty()) "event_form"
            else "event_form?${params.joinToString("&")}"
        }
    }

    // Expenses
    data object ExpenseList : Routes("expense_list")
    data object ExpenseDetail : Routes("expense_detail/{expenseId}") {
        fun createRoute(expenseId: String) = "expense_detail/$expenseId"
    }
    data object AddExpense : Routes("add_expense")
    data object ExpenseSummary : Routes("expense_summary")

    // Info Bank
    data object InfoBankList : Routes("info_bank_list")
    data object InfoBankDetail : Routes("info_bank_detail/{entryId}") {
        fun createRoute(entryId: String) = "info_bank_detail/$entryId"
    }
    data object InfoBankAdd : Routes("info_bank_add")
    data object InfoBankEdit : Routes("info_bank_edit/{entryId}") {
        fun createRoute(entryId: String) = "info_bank_edit/$entryId"
    }

    // Settings
    data object Settings : Routes("settings")
    data object DeviceList : Routes("device_list")
    data object ServerConfig : Routes("server_config")
}
