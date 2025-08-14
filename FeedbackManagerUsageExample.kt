/*
 * FeedbackManager Usage Examples
 * 
 * This file demonstrates how to use the FeedbackManager class
 * from anywhere in the CoGo application to provide consistent feedback UI.
 */

// Example 1: Simple feedback from any Activity or Fragment
fun sendFeedbackFromActivity(activity: Activity) {
    FeedbackManager.quickFeedback(activity, "Main Screen")
}

// Example 2: Feedback with custom subject from a dialog
fun sendCustomFeedback(context: Context) {
    FeedbackManager.showFeedbackDialog(
        context = context,
        currentScreen = "Git Operations",
        customSubject = "Git Issue Report",
        includeStackTrace = false
    )
}

// Example 3: Direct feedback sending without dialog
fun sendDirectFeedback(context: Context, activityLauncher: ActivityResultLauncher<Intent>) {
    FeedbackManager.sendFeedback(
        context = context,
        currentScreen = "Editor",
        shareActivityResultLauncher = activityLauncher
    )
}

// Example 4: Menu item click handler
fun setupFeedbackMenuItem(context: Context, menuItem: MenuItem) {
    menuItem.setOnMenuItemClickListener {
        FeedbackManager.quickFeedback(context)
        true
    }
}

// Example 5: Floating Action Button click handler
fun setupFeedbackFAB(context: Context, fab: FloatingActionButton) {
    fab.setOnClickListener {
        FeedbackManager.showFeedbackDialog(
            context = context,
            currentScreen = "File Browser"
        )
    }
}

// Example 6: Error reporting with custom message
fun reportError(context: Context, errorMessage: String) {
    FeedbackManager.showFeedbackDialog(
        context = context,
        customSubject = "Error Report: $errorMessage",
        includeStackTrace = true
    )
}