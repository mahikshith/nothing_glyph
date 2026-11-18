package com.dotforge.setup

/**
 * The guided flow for notification access. Android 13+ blocks it for apps installed outside an app store
 * ("restricted settings"), so when the user comes back from the settings page without access the flow shows
 * the unlock steps (App info > menu > Allow restricted settings) instead of asking again.
 */
class AccessFlow {
    enum class Step {
        /** Nothing on screen. */
        NONE,
        /** One line of why, "Open settings" and "Not now". */
        EXPLAIN,
        /** The unlock steps, "Open App info", "Try again" and "Not now". */
        UNLOCK,
    }

    var step: Step = Step.NONE
        private set

    /** The user went to a settings page from the flow and has not come back yet. */
    private var away = false

    /** Sent to the settings page at least once, so a refusal there means restricted settings. */
    private var triedSettings = false

    /** A feature needs access. Returns true when it is already granted. */
    fun request(granted: Boolean): Boolean {
        if (granted) {
            step = Step.NONE
            return true
        }
        step = if (triedSettings) Step.UNLOCK else Step.EXPLAIN
        return false
    }

    /** The notification-access settings page was opened. */
    fun openedAccessSettings() {
        triedSettings = true
        away = true
        step = Step.NONE
    }

    /** Our App info page was opened to allow restricted settings. */
    fun openedAppInfo() {
        away = true
        step = Step.NONE
    }

    /** The app came back to the foreground; [granted] is the access state now. */
    fun resumed(granted: Boolean) {
        val wasAway = away
        away = false
        step = when {
            granted -> Step.NONE
            wasAway -> Step.UNLOCK
            else -> step
        }
    }

    fun notNow() {
        step = Step.NONE
    }
}
