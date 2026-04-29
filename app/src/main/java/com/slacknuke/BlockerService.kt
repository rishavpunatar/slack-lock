package com.slacknuke

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * When Slack comes to the foreground during an active block window,
 * we send the user back to the home screen.
 *
 * The service is configured (in res/xml/accessibility_config.xml) to only
 * receive window-state-change events for Slack's package, so it does
 * essentially nothing the rest of the time.
 */
class BlockerService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != BlockState.SLACK_PACKAGE) return
        if (!BlockState.isBlocked(this)) return
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    override fun onInterrupt() { /* no-op */ }
}
