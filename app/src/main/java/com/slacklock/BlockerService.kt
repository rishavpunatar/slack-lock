package com.slacklock

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager

/**
 * Enforces active locks by sending the user back to the home screen.
 *
 * Slack: any Slack window during a Slack lock is sent home.
 *
 * Work Gmail: Gmail is one app for every account, so during a Gmail lock we read
 * Gmail's account button to see which account is showing. Work account → home.
 * Another account → left alone. Some Gmail screens (an open email, compose) have
 * no account button; if we haven't seen the account since Gmail was opened, we
 * wait briefly (the inbox may still be loading) then press Back, which lands on
 * that account's inbox where the button is visible.
 *
 * Android only reports a window after it's on screen, so while the account is
 * unknown, and while the Home animation plays, a [Curtain] covers the screen to
 * stop locked content flashing up.
 *
 * Outside a Gmail lock the service narrows itself back to Slack window events
 * only, exactly as before Gmail support existed.
 */
class BlockerService : AccessibilityService() {

    private enum class GmailAccount { UNKNOWN, WORK, OTHER }

    private var gmailAccount = GmailAccount.UNKNOWN
    private var unknownSince = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val recheckGmail = Runnable { enforceGmail() }
    private var contentCheckPending = false
    private val contentCheck = Runnable {
        contentCheckPending = false
        enforceGmail()
    }
    private lateinit var curtain: Curtain
    private var widened: Boolean? = null
    private var transientPackages: Set<String> = emptySet()

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == LockTarget.WORK_GMAIL.prefsKey) updateEventScope()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        curtain = Curtain(this)
        // Windows that float over Gmail without the user leaving it.
        val imm = getSystemService(InputMethodManager::class.java)
        transientPackages = buildSet {
            add("com.android.systemui")
            add(packageName)
            imm?.enabledInputMethodList?.forEach { add(it.packageName) }
        }
        BlockState.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
        updateEventScope()
    }

    override fun onDestroy() {
        BlockState.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        handler.removeCallbacks(recheckGmail)
        handler.removeCallbacks(contentCheck)
        if (::curtain.isInitialized) curtain.hide()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        updateEventScope()
        when (pkg) {
            LockTarget.SLACK.packageName -> {
                if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
                if (BlockState.isBlocked(this, LockTarget.SLACK)) goHome()
            }
            LockTarget.WORK_GMAIL.packageName -> {
                if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                    enforceGmail()
                } else if (!contentCheckPending) {
                    // Content changes come in bursts (scrolling, loading): check at most every 100 ms.
                    contentCheckPending = true
                    handler.postDelayed(contentCheck, CONTENT_CHECK_MS)
                }
            }
            else -> {
                // The user moved to another app; recheck the account next time Gmail opens.
                if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                    pkg !in transientPackages
                ) {
                    forgetGmailAccount()
                    curtain.hideAfter(HOME_SETTLE_MS)
                }
            }
        }
    }

    private fun enforceGmail() {
        if (!BlockState.isBlocked(this, LockTarget.WORK_GMAIL)) return
        val workAccount = BlockState.workGmailAccount(this) ?: return
        // Cover first: the scan below takes a few frames. If it finds a personal
        // account the cover comes down before it is ever drawn.
        if (gmailAccount == GmailAccount.UNKNOWN) curtain.show()
        val root = rootInActiveWindow
            ?.takeIf { it.packageName?.toString() == LockTarget.WORK_GMAIL.packageName }

        val signedIn = root?.let { findSignedInAccount(it) }
        if (signedIn != null) {
            val isWork = WorkAccount.matches(workAccount, signedIn)
            val detected = if (isWork) GmailAccount.WORK else GmailAccount.OTHER
            if (detected != gmailAccount) BlockState.recordGmailCheck(this, isWork)
            gmailAccount = detected
            unknownSince = 0L
            handler.removeCallbacks(recheckGmail)
        }

        when (gmailAccount) {
            GmailAccount.WORK -> goHome()
            GmailAccount.UNKNOWN -> {
                // No account button yet. Keep Gmail covered, give the screen a moment
                // to load, then step back towards the inbox to find out which account this is.
                curtain.show()
                val now = SystemClock.elapsedRealtime()
                if (unknownSince == 0L) {
                    unknownSince = now
                    handler.postDelayed(recheckGmail, ACCOUNT_GRACE_MS)
                } else if (now - unknownSince >= ACCOUNT_GRACE_MS && root != null) {
                    unknownSince = 0L
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
            }
            GmailAccount.OTHER -> curtain.hide()
        }
    }

    private fun goHome() {
        forgetGmailAccount()
        curtain.show()
        curtain.hideAfter(HOME_SETTLE_MS)
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    private fun forgetGmailAccount() {
        gmailAccount = GmailAccount.UNKNOWN
        unknownSince = 0L
        handler.removeCallbacks(recheckGmail)
        handler.removeCallbacks(contentCheck)
        contentCheckPending = false
    }

    /** Depth-first search for Gmail's account button; stops at the first match. */
    private fun findSignedInAccount(root: AccessibilityNodeInfo): String? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < MAX_NODES) {
            val node = stack.removeLast()
            visited++
            WorkAccount.signedInAccountFromNode(node.viewIdResourceName, node.contentDescription)
                ?.let { return it }
            for (i in node.childCount - 1 downTo 0) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return null
    }

    /**
     * During a Gmail lock: all apps' window changes (to notice leaving Gmail) plus
     * Gmail content changes (to catch account switches). Otherwise: Slack only.
     */
    private fun updateEventScope() {
        val wide = BlockState.isBlocked(this, LockTarget.WORK_GMAIL)
        if (wide == widened) return
        val info = serviceInfo ?: return
        if (wide) {
            info.packageNames = null
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        } else {
            info.packageNames = arrayOf(LockTarget.SLACK.packageName)
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            forgetGmailAccount()
        }
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        serviceInfo = info
        widened = wide
    }

    override fun onInterrupt() { /* no-op */ }

    private companion object {
        const val ACCOUNT_GRACE_MS = 1500L
        const val CONTENT_CHECK_MS = 100L
        const val HOME_SETTLE_MS = 600L
        const val MAX_NODES = 3000
    }
}
